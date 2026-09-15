import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import { openDatabase } from './index.js';
import { schemaNoticeForStartup } from './schema-notice.js';
import { SCHEMA_VERSION } from './schema-version.js';

const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

describe('startup schema notice', () => {
  const roots: string[] = [];
  const target = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-schema-notice-'));
    roots.push(root);
    return join(root, 'app.db');
  };

  afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  it('reports a fresh installation as current without warning', () => {
    const path = target();
    const db = openDatabase(path);
    const notice = schemaNoticeForStartup(db);
    db.close();

    assert.equal(notice.level, 'info');
    assert.equal(notice.message, 'database schema notice');
    assert.equal(notice.payload.verdict, 'current');
    assert.equal(notice.payload.revision, SCHEMA_VERSION);
    assert.equal(notice.payload.tables, 14);
    assert.deepEqual(notice.payload.missingTables, []);
  });

  it('reports an unversioned database at info level and does not write to it', () => {
    const path = target();
    const created = openDatabase(path);
    created.pragma('user_version = 0'); // an installation that predates schema versioning
    created.close();
    const before = digest(path);

    const db = openDatabase(path);
    const notice = schemaNoticeForStartup(db);
    assert.equal(notice.level, 'info');
    assert.equal(notice.payload.verdict, 'unversioned');
    assert.equal(db.pragma('user_version', { simple: true }), 0);
    db.close();

    assert.equal(digest(path), before, 'the startup notice modified the database');
  });

  it('warns when the database was written by a newer build, without writing', () => {
    const path = target();
    const created = openDatabase(path);
    created.pragma(`user_version = ${SCHEMA_VERSION + 1}`);
    created.close();
    const before = digest(path);

    const db = openDatabase(path);
    const notice = schemaNoticeForStartup(db);
    assert.equal(notice.level, 'warn');
    assert.equal(notice.payload.verdict, 'newer');
    assert.equal(db.pragma('user_version', { simple: true }), SCHEMA_VERSION + 1);
    db.close();

    assert.equal(digest(path), before, 'the startup notice modified the database');
  });

  it('warns when the schema is incomplete', () => {
    const path = target();
    const created = openDatabase(path);
    created.pragma('foreign_keys = OFF');
    created.exec('DROP TABLE settings');
    created.pragma('foreign_keys = ON');
    created.close();

    const db = openDatabase(path);
    const notice = schemaNoticeForStartup(db);
    assert.equal(notice.level, 'warn');
    assert.equal(notice.payload.verdict, 'incompatible');
    assert.deepEqual(notice.payload.missingTables, ['settings']);
    db.close();
  });

  it('reports an unavailable notice instead of throwing when the database cannot be read', () => {
    const db = openDatabase(target());
    db.close();

    const notice = schemaNoticeForStartup(db);
    assert.equal(notice.level, 'warn');
    assert.equal(notice.message, 'database schema notice unavailable');
    assert.equal(notice.payload.verdict, undefined);
    assert.equal(typeof notice.payload.error, 'string');
    assert.notEqual(notice.payload.error, '');
  });
});
