import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { openDatabase } from './index.js';
import {
  BASELINE_TABLES,
  REVISION_TWO_TABLES,
  SCHEMA_TABLES,
  SCHEMA_VERSION,
  classifySchema,
  formatReport,
  observeSchema,
  readSchemaReport,
  requiredTablesFor,
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
    assert.ok(
      observeSchema(db).tables.includes('auth_sessions'),
      'a fresh installation must contain the table revision 2 introduces',
    );
    db.close();
  });

  // The migrate command refuses an `incompatible` database without ever opening it for
  // writing, so a table added by a later revision must NOT be required of an earlier one:
  // requiring `auth_sessions` of a revision 1 database would make the migration that adds
  // it unreachable.
  it('requires of a database only the tables its own revision defines', () => {
    const revisionOne = classifySchema({ version: 1, integrity: 'ok', tables: [...BASELINE_TABLES] });
    assert.deepEqual(revisionOne.missingTables, []);
    assert.equal(revisionOne.verdict, 'outdated');

    const revisionTwo = classifySchema({ version: 2, integrity: 'ok', tables: [...SCHEMA_TABLES] });
    assert.deepEqual(revisionTwo.missingTables, []);
    assert.equal(revisionTwo.verdict, 'current');

    // A table missing from the revision the database actually claims is still a failure.
    const damaged = classifySchema({
      version: 2,
      integrity: 'ok',
      tables: [...SCHEMA_TABLES].filter((table) => table !== 'auth_sessions'),
    });
    assert.deepEqual(damaged.missingTables, ['auth_sessions']);
    assert.equal(damaged.verdict, 'incompatible');

    // An unversioned database is held only to the baseline, since nothing more is known.
    const unversioned = classifySchema({ version: 0, integrity: 'ok', tables: [...BASELINE_TABLES] });
    assert.deepEqual(unversioned.missingTables, []);
    assert.equal(unversioned.verdict, 'unversioned');

    // The per-revision inventories must add up to the full current inventory.
    assert.deepEqual([...requiredTablesFor(SCHEMA_VERSION)].sort(), [...SCHEMA_TABLES].sort());
    assert.deepEqual([...requiredTablesFor(1)].sort(), [...BASELINE_TABLES].sort());
    assert.deepEqual([...SCHEMA_TABLES].sort(), [...BASELINE_TABLES, ...REVISION_TWO_TABLES].sort());
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
      'schema revision: 2 (this build supports 2)',
      'tables: 15 observed, 0 missing, 0 unexpected',
      'verdict: current — revision 2; 15 tables present',
    ]);

    const missing = formatReport(
      '/tmp/broken.db',
      classifySchema(observation({ tables: SCHEMA_TABLES.filter((table) => table !== 'reports') })),
    );
    assert.equal(missing[3], 'tables: 14 observed, 1 missing, 0 unexpected');
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
