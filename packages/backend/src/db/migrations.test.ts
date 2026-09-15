import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { openDatabase } from './index.js';
import { migrateDatabase } from './migrations.js';
import { SCHEMA_VERSION, readSchemaReport } from './schema-version.js';

const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');
const seed = readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8');
const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

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

    const db = new Database(path);
    const result = migrateDatabase(db);
    assert.equal(result.outcome, 'refused');
    assert.match(result.reason, /missing tables: settings/);
    db.close();
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
    assert.match(output, /migrate: stamped user_version = 1/);
    assert.match(output, /after  verdict: current/);
  });

  it('CLI: refuses a newer database and exits non-zero', async () => {
    const path = join(fixtureRoot(), 'newer.db');
    const fresh = openDatabase(path);
    fresh.pragma(`user_version = ${SCHEMA_VERSION + 1}`);
    fresh.close();

    const { code, output } = await runMigrate(path);
    assert.equal(code, 1, output);
    assert.match(output, /refused — revision 2 is newer/);
  });
});
