import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { captureRecoverySet, executeChecked, verifyIsolatedRestore } from './recovery.js';
import { classifyInstallation } from './db/install-lifecycle.js';

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'trindade-recovery-'));
  const source = join(root, 'source');
  const output = join(root, 'backup');
  const restore = join(root, 'restore');
  const assets = join(source, 'photos');
  mkdirSync(assets, { recursive: true });
  writeFileSync(join(assets, 'photo.jpg'), 'photo');
  const databasePath = join(source, 'trindade.db');
  const db = new Database(databasePath);
  db.exec('CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT); INSERT INTO users VALUES (1, \'operator\')');
  db.pragma('journal_mode = WAL');
  return { root, source, output, restore, assets, databasePath, db };
}

describe('recovery threat boundary', () => {
  it('rejects path traversal outside the allowlisted root', async () => {
    const f = fixture();
    await assert.rejects(
      captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: join(f.root, '..', 'escape'), allowedRoots: [f.root] }),
      /escapes the allowlisted roots/
    );
    f.db.close();
  });

  it('rejects shell metacharacters in recovery paths', async () => {
    const f = fixture();
    await assert.rejects(
      captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: join(f.root, 'bad;name'), allowedRoots: [f.root] }),
      /without shell metacharacters/
    );
    f.db.close();
  });

  it('rejects a symlink escape from an allowlisted root', async () => {
    const f = fixture();
    const outside = mkdtempSync(join(tmpdir(), 'trindade-outside-'));
    symlinkSync(outside, join(f.source, 'escaped'));
    await assert.rejects(
      captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: join(f.source, 'escaped'), allowedRoots: [f.root] }),
      /escapes the allowlisted roots/
    );
    f.db.close();
  });

  it('rejects recovery output that overlaps production data', async () => {
    const f = fixture();
    await assert.rejects(
      captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: join(f.source, 'nested'), allowedRoots: [f.root] }),
      /overlaps production data/
    );
    f.db.close();
  });

  it('rejects a non-zero recovery process exit', async () => {
    await assert.rejects(executeChecked(async () => ({ code: 2, stdout: '', stderr: 'failed', timedOut: false }), 'tool', [], 'ok'), /exit 2/);
  });

  it('rejects a timed-out recovery process', async () => {
    await assert.rejects(executeChecked(async () => ({ code: 0, stdout: '', stderr: '', timedOut: true }), 'tool', [], 'ok'), /timed out/);
  });

  it('rejects partial recovery process output', async () => {
    await assert.rejects(executeChecked(async () => ({ code: 0, stdout: 'partial', stderr: '', timedOut: false }), 'tool', [], 'complete'), /incomplete output/);
  });
});

describe('recovery harness', () => {
  it('captures a consistent snapshot and proves an isolated restore', async () => {
    const f = fixture();
    const manifestPath = await captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: f.output, allowedRoots: [f.root] });
    const proof = verifyIsolatedRestore({ manifestPath, targetDir: f.restore, allowedRoots: [f.root] });
    assert.equal(proof.integrity, 'ok');
    assert.equal(proof.rowCounts.users, 1);
    assert.equal(proof.assetsVerified, 1);
    assert.equal(f.db.prepare('SELECT username FROM users').pluck().get(), 'operator');
    f.db.close();
  });
});

describe('installation classification', () => {
  it('classifies only a missing target as fresh and known data as existing', () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-lifecycle-'));
    assert.equal(classifyInstallation(join(root, 'missing.db')).kind, 'fresh');
    const path = join(root, 'existing.db');
    const db = new Database(path);
    db.exec('CREATE TABLE users (id INTEGER); INSERT INTO users VALUES (1)');
    db.close();
    assert.equal(classifyInstallation(path).kind, 'existing');
  });

  it('fails closed for empty, partial, corrupt, and sidecar-only targets', () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-lifecycle-'));
    for (const [name, contents] of [['empty.db', ''], ['partial.db', 'not sqlite']]) {
      const path = join(root, name);
      writeFileSync(path, contents);
      assert.equal(classifyInstallation(path).kind, 'ambiguous');
    }
    const sidecarTarget = join(root, 'sidecar.db');
    writeFileSync(`${sidecarTarget}-wal`, 'orphan');
    assert.equal(classifyInstallation(sidecarTarget).kind, 'ambiguous');
  });
});
