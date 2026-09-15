import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { openDatabase } from './index.js';
import {
  SCHEMA_TABLES,
  SCHEMA_VERSION,
  classifySchema,
  formatReport,
  observeSchema,
  readSchemaReport,
  stampSchemaVersion,
} from './schema-version.js';

const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');
const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

const observation = (overrides: Partial<Parameters<typeof classifySchema>[0]> = {}) => ({
  version: SCHEMA_VERSION,
  integrity: 'ok',
  tables: [...SCHEMA_TABLES],
  ...overrides,
});

describe('schema revision', () => {
  const roots: string[] = [];
  const fixtureRoot = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-schema-version-'));
    roots.push(root);
    return root;
  };

  afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  it('a fresh installation is stamped with the supported revision', () => {
    const databasePath = join(fixtureRoot(), 'fresh.db');
    const db = openDatabase(databasePath);
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);
    const report = readSchemaReport(db);
    assert.equal(report.verdict, 'current');
    assert.deepEqual(report.missingTables, []);
    assert.deepEqual(report.unexpectedTables, []);
    db.close();
  });

  it('schema.sql creates exactly the tables the report expects', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    const observed = observeSchema(db).tables;
    assert.deepEqual(observed, [...SCHEMA_TABLES].sort());
    db.close();
  });

  // The Production Immutability Gate depends on this: startup must not write to an
  // existing database, so an unversioned one stays unversioned until an explicitly
  // approved adoption runs.
  it('opening an existing unversioned database writes nothing', () => {
    const databasePath = join(fixtureRoot(), 'legacy.db');
    const seed = new Database(databasePath);
    seed.exec(schema);
    seed.exec(readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8'));
    seed.close();

    const before = digest(databasePath);
    const existing = openDatabase(databasePath);
    const report = readSchemaReport(existing);
    existing.close();

    assert.equal(report.verdict, 'unversioned');
    assert.deepEqual(report.missingTables, []);
    assert.equal(digest(databasePath), before, 'startup modified an existing database');
  });

  it('classifies every revision state without touching a database', () => {
    assert.equal(classifySchema(observation()).verdict, 'current');
    assert.equal(classifySchema(observation({ version: 0 })).verdict, 'unversioned');
    assert.equal(classifySchema(observation(), 3).verdict, 'outdated');
    assert.equal(classifySchema(observation({ version: 4 }), 3).verdict, 'newer');
    assert.equal(
      classifySchema(observation({ tables: SCHEMA_TABLES.filter((t) => t !== 'reports') })).verdict,
      'incompatible',
    );
    assert.equal(classifySchema(observation({ integrity: 'database disk image is malformed' })).verdict, 'incompatible');
  });

  it('reports missing and unexpected tables explicitly', () => {
    const incomplete = classifySchema(
      observation({ tables: [...SCHEMA_TABLES.filter((t) => t !== 'reports'), 'scratch_notes'] }),
    );
    assert.deepEqual(incomplete.missingTables, ['reports']);
    assert.deepEqual(incomplete.unexpectedTables, ['scratch_notes']);
    assert.match(incomplete.summary, /missing tables: reports/);

    // An extra table is reported but does not change the verdict: it cannot break
    // the application, unlike a missing one.
    const extra = classifySchema(observation({ tables: [...SCHEMA_TABLES, 'scratch_notes'] }));
    assert.equal(extra.verdict, 'current');
    assert.deepEqual(extra.unexpectedTables, ['scratch_notes']);
    assert.match(extra.summary, /unexpected tables: scratch_notes/);
  });

  it('renders a report as the lines the status command prints', () => {
    assert.deepEqual(formatReport('/tmp/fresh.db', classifySchema(observation())), [
      'database: /tmp/fresh.db',
      'integrity: ok',
      'schema revision: 1 (this build supports 1)',
      'tables: 14 observed, 0 missing, 0 unexpected',
      'verdict: current — revision 1; 14 tables present',
    ]);

    const missing = formatReport(
      '/tmp/broken.db',
      classifySchema(observation({ tables: SCHEMA_TABLES.filter((table) => table !== 'reports') })),
    );
    assert.equal(missing[3], 'tables: 13 observed, 1 missing, 0 unexpected');
    assert.equal(missing[4], 'missing tables: reports');
    assert.match(missing[5], /^verdict: incompatible — missing tables: reports$/);
  });

  it('stamps a revision only when asked to', () => {
    const db = new Database(':memory:');
    db.exec(schema);
    assert.equal(db.pragma('user_version', { simple: true }), 0);
    stampSchemaVersion(db);
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION);
    assert.equal(readSchemaReport(db).verdict, 'current');
    db.close();
  });
});
