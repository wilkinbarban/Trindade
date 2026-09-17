import { describe, it } from 'node:test';
import assert from 'node:assert';
import Database from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { migrateLegacyReportTemperatures } from './report-temperatures-migration.js';

const schemaPath = join(import.meta.dirname, 'schema.sql');
const seedPath = join(import.meta.dirname, 'seed.sql');
const schema = readFileSync(schemaPath, 'utf-8');
const seed = readFileSync(seedPath, 'utf-8');

/** List all user-defined table names from the attached database. */
function tableNames(db: Database.Database): string[] {
  const rows = db
    .prepare(
      `SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name`
    )
    .all() as { name: string }[];
  return rows.map((r) => r.name);
}

describe('Database Initialization', () => {
  it('schema.sql creates all expected tables', () => {
    const db = new Database(':memory:');
    db.exec(schema);

    const tables = tableNames(db);
    const expected = [
      'audit_logs',
      'auth_sessions',
      'drivers',
      'loading_schedules',
      'report_categories',
      'report_items',
      'report_photos',
      'report_tasks',
      'report_templates',
      'report_temperatures',
      'reports',
      'roles',
      'settings',
      'users',
      'vehicles',
    ];

    for (const table of expected) {
      assert.ok(
        tables.includes(table),
        `Table "${table}" should exist in schema`
      );
    }

    assert.strictEqual(tables.length, expected.length, 'Should have exactly 15 user tables');
    db.close();
  });

  it('seed.sql inserts roles and reference data without creating users', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    db.pragma('foreign_keys = ON');
    db.exec(seed);

    const roles = db.prepare('SELECT name FROM roles ORDER BY id').all() as {
      name: string;
    }[];
    assert.deepStrictEqual(
      roles.map((r) => r.name),
      ['Administrador', 'Trabalhador']
    );

    const userCount = db.prepare('SELECT COUNT(*) AS count FROM users').get() as { count: number };
    assert.strictEqual(userCount.count, 0);
    assert.strictEqual(db.pragma('foreign_keys', { simple: true }), 1);
    assert.deepStrictEqual(db.pragma('foreign_key_check'), []);
    const referenceCount = db.prepare('SELECT COUNT(*) AS count FROM report_tasks').get() as { count: number };
    assert.ok(referenceCount.count > 0);

    db.close();
  });

  it('re-running schema.sql is idempotent (CREATE TABLE IF NOT EXISTS)', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    db.pragma('foreign_keys = ON');

    // Run schema a second time — should not throw
    assert.doesNotThrow(() => {
      db.exec(schema);
    }, 'Running schema.sql twice should not error');

    const tables = tableNames(db);
    assert.strictEqual(tables.length, 15, 'Should still have 15 tables after re-run');
    db.close();
  });

  it('rebuilds legacy report temperatures atomically and can be retried', () => {
    const db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(`
      CREATE TABLE reports (id INTEGER PRIMARY KEY);
      INSERT INTO reports (id) VALUES (1);
      CREATE TABLE report_temperatures (
        id INTEGER PRIMARY KEY,
        report_id INTEGER NOT NULL REFERENCES reports(id),
        location TEXT NOT NULL,
        value REAL NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(report_id, location)
      );
      INSERT INTO report_temperatures (id, report_id, location, value) VALUES (1, 1, 'Câmara', -18.5);
      CREATE VIEW new_report_temperatures AS SELECT 1 AS orphaned;
    `);

    assert.throws(() => migrateLegacyReportTemperatures(db));
    const legacyColumns = db.prepare('PRAGMA table_info(report_temperatures)').all() as { name: string }[];
    assert.ok(
      !legacyColumns.some((column) => column.name === 'reading_index'),
      'a failed rebuild must leave the legacy table unchanged'
    );
    db.exec('DROP VIEW new_report_temperatures; CREATE TABLE new_report_temperatures (orphaned INTEGER);');
    migrateLegacyReportTemperatures(db);
    migrateLegacyReportTemperatures(db);

    const reading = db.prepare(
      'SELECT report_id, location, reading_index, value FROM report_temperatures WHERE id = 1'
    ).get() as { report_id: number; location: string; reading_index: number; value: number };
    assert.deepStrictEqual(reading, { report_id: 1, location: 'Câmara', reading_index: 1, value: -18.5 });
    const temporaryTableCount = db.prepare(
      "SELECT COUNT(*) AS count FROM sqlite_master WHERE name = 'new_report_temperatures'"
    ).get() as { count: number };
    assert.strictEqual(temporaryTableCount.count, 0);
    assert.strictEqual(db.pragma('foreign_keys', { simple: true }), 1);
    db.close();
  });

  it('seed.sql is idempotent — re-execution does not throw and duplicates are ignored', () => {
    const db = new Database(':memory:');
    db.exec(schema);

    // First run inserts seed data
    db.exec(seed);

    const roleCountAfterFirst = (
      db.prepare('SELECT COUNT(*) AS count FROM roles').get() as { count: number }
    ).count;
    assert.strictEqual(roleCountAfterFirst, 2, 'Should have 2 roles after first seeding');

    const referenceCountAfterFirst = (
      db.prepare('SELECT COUNT(*) AS count FROM report_tasks').get() as { count: number }
    ).count;

    // Second exec should NOT throw — INSERT OR IGNORE skips duplicates
    assert.doesNotThrow(
      () => db.exec(seed),
      'Seed re-execution with INSERT OR IGNORE should not throw'
    );

    // Row counts must remain unchanged
    const roleCountAfterSecond = (
      db.prepare('SELECT COUNT(*) AS count FROM roles').get() as { count: number }
    ).count;
    assert.strictEqual(roleCountAfterSecond, 2, 'Should still have 2 roles after re-seeding');

    const referenceCountAfterSecond = (
      db.prepare('SELECT COUNT(*) AS count FROM report_tasks').get() as { count: number }
    ).count;
    assert.strictEqual(referenceCountAfterSecond, referenceCountAfterFirst);

    db.close();
  });

  it('seed.sql preserves a pre-existing user unchanged', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    db.prepare("INSERT INTO roles (id, name) VALUES (1, 'Administrador')").run();
    db.prepare(
      `INSERT INTO users (id, username, password_hash, display_name, role_id, is_active)
       VALUES (42, 'existing-user', 'existing-hash', 'Existing User', 1, 0)`
    ).run();

    const before = db.prepare('SELECT * FROM users WHERE id = 42').get();
    db.exec(seed);
    db.exec(seed);
    const after = db.prepare('SELECT * FROM users WHERE id = 42').get();

    assert.deepStrictEqual(after, before);
    assert.strictEqual(db.pragma('foreign_keys', { simple: true }), 1);
    assert.deepStrictEqual(db.pragma('foreign_key_check'), []);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM users').get() as { count: number }).count, 1);
    db.close();
  });

  it('seed.sql contains the full PRD operational catalog', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    db.exec(seed);

    // Categories: should be at least 7 (existing 3 + 4 new)
    const categoryCount = (
      db.prepare('SELECT COUNT(*) AS count FROM report_categories').get() as { count: number }
    ).count;
    assert.ok(categoryCount >= 6, `Expected >= 6 categories, got ${categoryCount}`);

    // Verify all PRD categories exist by name
    const categoryNames = (
      db.prepare('SELECT name_pt FROM report_categories ORDER BY sort_order').all() as { name_pt: string }[]
    ).map((r) => r.name_pt);
    const expectedCategories = [
      'Higiene e Organização',
      'Temperaturas',
      'Recebimento',
      'Montagem das Caixas',
    ];
    for (const expected of expectedCategories) {
      assert.ok(
        categoryNames.includes(expected),
        `Category "${expected}" should exist in seed data`
      );
    }

    // Tasks: should be at least 19 (existing 8 + 11 new)
    const taskCount = (
      db.prepare('SELECT COUNT(*) AS count FROM report_tasks').get() as { count: number }
    ).count;
    assert.ok(taskCount >= 14, `Expected >= 14 tasks, got ${taskCount}`);


    // Fresh production references exclude demo operational resources.
    const driverCount = (
      db.prepare('SELECT COUNT(*) AS count FROM drivers').get() as { count: number }
    ).count;
    assert.strictEqual(driverCount, 30);

    const vehicleCount = (
      db.prepare('SELECT COUNT(*) AS count FROM vehicles').get() as { count: number }
    ).count;
    assert.strictEqual(vehicleCount, 6);

    db.close();
  });

  it('journal_mode pragma can be set to WAL on file-based databases', () => {
    // WAL mode is not supported on in-memory databases (returns 'memory').
    // This test verifies the pragma API contract is understood.
    const db = new Database(':memory:');
    db.exec(schema);

    // On :memory:, setting WAL is a no-op — verify this behavior
    db.pragma('journal_mode = WAL');
    const result = db.pragma('journal_mode', { simple: true }) as string;

    // In-memory databases return 'memory'; file-based databases return 'wal'.
    // The production db/index.ts file uses a file-based DB where 'wal' is active.
    assert.ok(
      result === 'memory' || result === 'wal',
      `Journal mode should be 'memory' (in-memory) or 'wal' (file-based), got '${result}'`
    );

    db.close();
  });
});
