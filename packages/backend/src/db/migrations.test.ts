import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { migrateAuthSessions } from './auth-sessions-migration.js';
import { openDatabase } from './index.js';
import { migrateDatabase } from './migrations.js';
import { SCHEMA_VERSION, readSchemaReport } from './schema-version.js';

const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');
const seed = readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8');
const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

/**
 * Build the revision 1 shape: the current schema minus the table revision 2 introduces,
 * stamped at revision 1. This is what a database written by the previous build looks
 * like, and it is the fixture that proves the 1 → 2 step is reachable at all.
 */
function makeRevisionOneShape(db: Database.Database): void {
  db.exec('DROP TABLE auth_sessions');
  db.pragma('user_version = 1');
}

/** Insert an administrator and return its id, for fixtures that need a report owner. */
function insertUser(db: Database.Database, username: string): number {
  const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Administrador'").pluck().get() as number;
  return Number(
    db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id) VALUES (?,?,?,?)')
      .run(username, 'fixture-hash', username, roleId).lastInsertRowid,
  );
}

/** Replace report_temperatures with the pre-`reading_index` shape it had before versioning. */
function makeLegacyShape(db: Database.Database): void {
  db.pragma('foreign_keys = OFF');
  try {
    db.exec('DROP TABLE report_temperatures');
    db.exec(`
      CREATE TABLE report_temperatures (
        id INTEGER PRIMARY KEY,
        report_id INTEGER NOT NULL REFERENCES reports(id),
        location TEXT NOT NULL,
        value REAL NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(report_id, location)
      );
    `);
  } finally {
    db.pragma('foreign_keys = ON');
  }
}

const migrateScript = join(import.meta.dirname, 'migrate.ts');

function runMigrate(databasePath: string): Promise<{ code: number | null | 'timeout'; output: string }> {
  const { NODE_TEST_CONTEXT: _nodeTestContext, ...env } = process.env;
  const child = spawn(process.execPath, ['--import', 'tsx', migrateScript, databasePath], {
    cwd: join(import.meta.dirname, '..'),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk; });
  child.stderr.on('data', (chunk) => { output += chunk; });
  return new Promise((resolve) => {
    const timer = setTimeout(() => { child.kill(); resolve({ code: 'timeout', output }); }, 30_000);
    child.once('exit', (code) => { clearTimeout(timer); resolve({ code, output }); });
  });
}

describe('schema migration', () => {
  const roots: string[] = [];
  const fixtureRoot = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-schema-migrate-'));
    roots.push(root);
    return root;
  };

  afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  it('is a no-op on a current database', () => {
    const db = openDatabase(join(fixtureRoot(), 'current.db'));
    const result = migrateDatabase(db);
    assert.equal(result.outcome, 'already-current');
    assert.equal(result.report.verdict, 'current');
    db.close();
  });

  it('rebuilds a legacy unversioned database, stamps the revision, and is idempotent', () => {
    const path = join(fixtureRoot(), 'legacy.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    makeLegacyShape(build);
    build.close();

    const db = new Database(path);
    assert.equal(readSchemaReport(db).verdict, 'unversioned');

    const result = migrateDatabase(db);
    if (result.outcome !== 'migrated') assert.fail(`expected migrated, got ${result.outcome}`);
    assert.equal(result.rebuiltTemperatures, true);
    assert.equal(result.from, 0);
    assert.equal(result.to, SCHEMA_VERSION);
    assert.equal(result.report.verdict, 'current');

    const columns = (db.prepare('PRAGMA table_info(report_temperatures)').all() as { name: string }[]).map((c) => c.name);
    assert.ok(columns.includes('reading_index'), 'the legacy table was not rebuilt');
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);

    assert.equal(migrateDatabase(db).outcome, 'already-current');
    db.close();
  });

  it('stamps without rebuilding when the legacy shape is already migrated', () => {
    const path = join(fixtureRoot(), 'production-shape.db');
    const fresh = openDatabase(path);
    fresh.pragma('user_version = 0'); // a database created before versioning, in the current shape
    fresh.close();

    const db = new Database(path);
    assert.equal(readSchemaReport(db).verdict, 'unversioned');
    const result = migrateDatabase(db);
    if (result.outcome !== 'migrated') assert.fail(`expected migrated, got ${result.outcome}`);
    assert.equal(result.rebuiltTemperatures, false);
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);
    db.close();
  });

  it('refuses a newer database without writing', () => {
    const path = join(fixtureRoot(), 'newer.db');
    const fresh = openDatabase(path);
    fresh.pragma(`user_version = ${SCHEMA_VERSION + 1}`);
    fresh.close();
    const before = digest(path);

    const db = new Database(path);
    const result = migrateDatabase(db);
    assert.equal(result.outcome, 'refused');
    assert.match(result.reason, /newer/);
    db.close();

    assert.equal(digest(path), before, 'refusing a newer database modified it');
    assert.equal(new Database(path, { readonly: true }).pragma('user_version', { simple: true }), SCHEMA_VERSION + 1);
  });

  it('refuses an incompatible database without writing', () => {
    const path = join(fixtureRoot(), 'broken.db');
    const fresh = openDatabase(path);
    fresh.pragma('foreign_keys = OFF');
    fresh.exec('DROP TABLE settings');
    fresh.pragma('foreign_keys = ON');
    fresh.close();
    const before = digest(path);

    const db = new Database(path);
    const result = migrateDatabase(db);
    assert.equal(result.outcome, 'refused');
    assert.match(result.reason, /missing tables: settings/);
    db.close();

    assert.equal(digest(path), before, 'refusing an incompatible database modified it');
  });

  // The reason revision 2 exists: a database written by the previous build must be
  // migratable. Requiring `auth_sessions` of a revision 1 database would classify it
  // `incompatible`, and the migrate command refuses that verdict without ever opening
  // the file for writing, so the step that adds the table could never run.
  it('migrates a revision 1 database to the supported revision and preserves its rows', () => {
    const path = join(fixtureRoot(), 'revision-one.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    const userId = insertUser(build, 'legacy-admin');
    const reportId = Number(
      build
        .prepare("INSERT INTO reports (user_id, turno, report_date) VALUES (?,'tarde','2026-07-01')")
        .run(userId).lastInsertRowid,
    );
    makeRevisionOneShape(build);
    build.close();

    const db = new Database(path);
    assert.equal(readSchemaReport(db).verdict, 'outdated');

    const result = migrateDatabase(db);
    if (result.outcome !== 'migrated') assert.fail(`expected migrated, got ${result.outcome}`);
    assert.equal(result.from, 1);
    assert.equal(result.to, SCHEMA_VERSION);
    assert.equal(result.rebuiltTemperatures, false, 'a revision 1 database must skip the legacy rebuild');
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);

    const tables = db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
      .pluck()
      .all() as string[];
    assert.ok(tables.includes('auth_sessions'), 'the revision 2 step did not create auth_sessions');

    assert.deepEqual(db.prepare('SELECT username, display_name, is_active FROM users WHERE id = ?').get(userId), {
      username: 'legacy-admin',
      display_name: 'legacy-admin',
      is_active: 1,
    });
    assert.deepEqual(db.prepare('SELECT user_id, turno, report_date FROM reports WHERE id = ?').get(reportId), {
      user_id: userId,
      turno: 'tarde',
      report_date: '2026-07-01',
    });
    db.close();
  });

  it('applies the revision 2 step idempotently', () => {
    const path = join(fixtureRoot(), 'revision-one-idempotent.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    makeRevisionOneShape(build);
    build.close();

    const db = new Database(path);
    if (migrateDatabase(db).outcome !== 'migrated') assert.fail('the revision 1 fixture did not migrate');
    const userId = insertUser(db, 'session-owner');
    db.prepare('INSERT INTO auth_sessions (user_id, token_hash, family_id, expires_at) VALUES (?,?,?,?)')
      .run(userId, 'token-hash-one', 'family-one', '2999-01-01 00:00:00');

    // A resumed migration re-enters a step after a crash between steps, so re-running it
    // must be a no-op rather than an error, and must not discard session rows.
    assert.doesNotThrow(() => migrateAuthSessions(db));
    assert.doesNotThrow(() => migrateAuthSessions(db));
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);
    assert.equal(db.prepare('SELECT COUNT(*) FROM auth_sessions').pluck().get(), 1);
    assert.equal(migrateDatabase(db).outcome, 'already-current');

    const columns = (db.prepare('PRAGMA table_info(auth_sessions)').all() as { name: string }[]).map((c) => c.name);
    assert.deepEqual(columns, [
      'id',
      'user_id',
      'token_hash',
      'family_id',
      'expires_at',
      'revoked_at',
      'replaced_by',
      'created_at',
      'last_used_at',
      'ip_address',
    ]);
    db.close();
  });

  it('carries legacy temperature readings forward as reading_index 1 when migrating from revision 0', () => {
    const path = join(fixtureRoot(), 'legacy-readings.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    makeLegacyShape(build);
    const userId = insertUser(build, 'legacy-reader');
    const reportId = Number(
      build
        .prepare("INSERT INTO reports (user_id, turno, report_date) VALUES (?,'noite','2026-06-30')")
        .run(userId).lastInsertRowid,
    );
    build.prepare('INSERT INTO report_temperatures (report_id, location, value) VALUES (?,?,?)')
      .run(reportId, 'Câmara Principal', -18);
    build.close();

    const db = new Database(path);
    assert.equal(readSchemaReport(db).verdict, 'unversioned');

    const result = migrateDatabase(db);
    if (result.outcome !== 'migrated') assert.fail(`expected migrated, got ${result.outcome}`);
    assert.equal(result.from, 0);
    assert.equal(result.to, SCHEMA_VERSION);
    assert.equal(result.rebuiltTemperatures, true);
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);

    assert.deepEqual(
      db.prepare('SELECT location, value, reading_index FROM report_temperatures WHERE report_id = ?').all(reportId),
      [{ location: 'Câmara Principal', value: -18, reading_index: 1 }],
    );
    const tables = db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
      .pluck()
      .all() as string[];
    assert.ok(tables.includes('auth_sessions'), 'a revision 0 migration must also reach revision 2');
    db.close();
  });

  // Guards the stepwise list against drifting from SCHEMA_VERSION: a build that supports
  // a revision its steps cannot reach would leave every migrated database stamped low.
  it('leaves every lower revision stamped at exactly the supported revision', () => {
    const cases: ReadonlyArray<{ from: number; prepare: (db: Database.Database) => void }> = [
      { from: 0, prepare: (db) => { db.pragma('user_version = 0'); } },
      { from: 1, prepare: (db) => { makeRevisionOneShape(db); } },
    ];

    for (const testCase of cases) {
      const path = join(fixtureRoot(), `lower-revision-${testCase.from}.db`);
      const fresh = openDatabase(path);
      testCase.prepare(fresh);
      fresh.close();

      const db = new Database(path);
      const result = migrateDatabase(db);
      if (result.outcome !== 'migrated') {
        assert.fail(`from revision ${testCase.from}: expected migrated, got ${result.outcome}`);
      }
      assert.equal(
        db.pragma('user_version', { simple: true }),
        SCHEMA_VERSION,
        `from revision ${testCase.from} did not reach the supported revision`,
      );
      assert.equal(readSchemaReport(db).verdict, 'current', `from revision ${testCase.from} did not end current`);
      db.close();
    }
  });

  it('CLI: migrates a revision 1 database and exits zero', async () => {
    const path = join(fixtureRoot(), 'revision-one-cli.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    makeRevisionOneShape(build);
    build.close();

    const { code, output } = await runMigrate(path);
    assert.equal(code, 0, output);
    assert.match(output, /before  verdict: outdated/);
    assert.match(output, /migrate: legacy report_temperatures already current/);
    assert.match(output, /migrate: stamped user_version = 2/);
    assert.match(output, /after  verdict: current/);
  });

  it('CLI: migrates a legacy database and exits zero', async () => {
    const path = join(fixtureRoot(), 'legacy.db');
    const build = new Database(path);
    build.exec(schema);
    build.exec(seed);
    makeLegacyShape(build);
    build.close();

    const { code, output } = await runMigrate(path);
    assert.equal(code, 0, output);
    assert.match(output, /migrate: rebuilt legacy report_temperatures/);
    assert.match(output, /migrate: stamped user_version = 2/);
    assert.match(output, /after  verdict: current/);
  });

  it('CLI: refuses a newer database and exits non-zero', async () => {
    const path = join(fixtureRoot(), 'newer.db');
    const fresh = openDatabase(path);
    fresh.pragma(`user_version = ${SCHEMA_VERSION + 1}`);
    fresh.close();

    const { code, output } = await runMigrate(path);
    assert.equal(code, 1, output);
    assert.match(output, /refused — revision 3 is newer/);
  });
});
