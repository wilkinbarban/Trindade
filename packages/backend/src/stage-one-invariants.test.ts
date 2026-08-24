import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { openDatabase } from './db/index.js';
import { captureRecoverySet, verifyIsolatedRestore } from './recovery.js';

const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

describe('stage-one installation invariants', () => {
  const roots: string[] = [];
  const fixtureRoot = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-stage-one-'));
    roots.push(root);
    return root;
  };

  afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  it('initializes only required references for a fresh installation', () => {
    const databasePath = join(fixtureRoot(), 'fresh.db');
    const db = openDatabase(databasePath);
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0);
    assert.ok((db.prepare('SELECT COUNT(*) FROM roles').pluck().get() as number) > 0);
    assert.equal(db.prepare('SELECT COUNT(*) FROM drivers').pluck().get(), 0);
    assert.equal(db.prepare('SELECT COUNT(*) FROM vehicles').pluck().get(), 0);
    db.close();
  });

  it('preserves existing database and asset fingerprints while proving an isolated restore', async () => {
    const root = fixtureRoot();
    const source = join(root, 'source');
    const photos = join(source, 'photos');
    mkdirSync(photos, { recursive: true });
    writeFileSync(join(photos, 'proof.txt'), 'associated-file');
    const databasePath = join(source, 'existing.db');
    const seed = new Database(databasePath);
    seed.exec(readFileSync(join(import.meta.dirname, 'db/schema.sql'), 'utf8'));
    seed.exec(readFileSync(join(import.meta.dirname, 'db/seed.sql'), 'utf8'));
    seed.prepare("INSERT INTO settings VALUES ('preserved','yes',datetime('now'))").run();
    seed.close();

    const before = { database: digest(databasePath), asset: digest(join(photos, 'proof.txt')) };
    const manifestPath = await captureRecoverySet({
      databasePath,
      assetsPath: photos,
      outputDir: join(root, 'recovery-set'),
      allowedRoots: [root],
    });
    const proof = verifyIsolatedRestore({
      manifestPath,
      targetDir: join(root, 'isolated-restore'),
      allowedRoots: [root],
    });
    assert.equal(proof.integrity, 'ok');
    assert.ok(proof.rowCounts.roles > 0);
    assert.equal(proof.assetsVerified, 1);
    assert.deepEqual(
      { database: digest(databasePath), asset: digest(join(photos, 'proof.txt')) },
      before,
    );

    const existing = openDatabase(databasePath);
    assert.equal(existing.prepare("SELECT value FROM settings WHERE key = 'preserved'").pluck().get(), 'yes');
    assert.doesNotThrow(() => existing.prepare("INSERT INTO settings VALUES ('legitimate','write',datetime('now'))").run());
    assert.equal(existing.prepare("SELECT value FROM settings WHERE key = 'legitimate'").pluck().get(), 'write');
    existing.close();
  });
});
