import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { captureRecoverySet, executeChecked, restoreRecoverySet, verifyIsolatedRestore } from './recovery.js';
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

  it('rejects restore target that overlaps the recovery set', async () => {
    const f = fixture();
    const manifestPath = await captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: f.output, allowedRoots: [f.root] });
    assert.throws(
      () => restoreRecoverySet({
        manifestPath,
        targetDatabasePath: join(f.output, 'nested.db'),
        targetAssetsPath: join(f.root, 'restored-assets'),
        allowedRoots: [f.root],
      }),
      /Restore target overlaps the recovery set/
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

  it('restores recovery set cleanly, clears WAL/SHM sidecars, and passes integrity check', async () => {
    const f = fixture();
    const manifestPath = await captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: f.output, allowedRoots: [f.root] });

    // Mutate source to simulate disaster
    f.db.exec("UPDATE users SET username = 'damaged' WHERE id = 1");
    // Ensure sidecars exist
    writeFileSync(`${f.databasePath}-wal`, 'stale-wal');
    writeFileSync(`${f.databasePath}-shm`, 'stale-shm');
    f.db.close();

    const targetDb = join(f.root, 'target', 'trindade.db');
    const targetPhotos = join(f.root, 'target', 'photos');
    mkdirSync(join(f.root, 'target'), { recursive: true });
    // Simulate stale WAL in target
    writeFileSync(`${targetDb}-wal`, 'stale-target-wal');

    const result = restoreRecoverySet({
      manifestPath,
      targetDatabasePath: targetDb,
      targetAssetsPath: targetPhotos,
      allowedRoots: [f.root],
    });

    assert.equal(result.integrity, 'ok');
    assert.equal(result.rowCounts.users, 1);
    assert.equal(result.assetsRestored, 1);
    // Ensure stale sidecar content was cleared
    if (existsSync(`${targetDb}-wal`)) {
      assert.notEqual(readFileSync(`${targetDb}-wal`, 'utf8'), 'stale-target-wal');
    }

    const restoredDb = new Database(targetDb, { readonly: true });
    assert.equal(restoredDb.prepare('SELECT username FROM users').pluck().get(), 'operator');
    restoredDb.close();
  });

  it('rejects restore if snapshot checksum is tampered', async () => {
    const f = fixture();
    const manifestPath = await captureRecoverySet({ databasePath: f.databasePath, assetsPath: f.assets, outputDir: f.output, allowedRoots: [f.root] });
    f.db.close();

    // Tamper with snapshot
    writeFileSync(join(f.output, 'snapshot.db'), 'corrupted-bytes');

    assert.throws(
      () => restoreRecoverySet({
        manifestPath,
        targetDatabasePath: join(f.root, 'target', 'trindade.db'),
        targetAssetsPath: join(f.root, 'target', 'photos'),
        allowedRoots: [f.root],
      }),
      /Snapshot checksum mismatch/
    );
  });

  it('CLI supports capture, verify-isolated, and restore subcommands', async () => {
    const f = fixture();
    f.db.close();

    const cliPath = join(import.meta.dirname, 'recovery.ts');
    const { execFile } = await import('node:child_process');
    const { promisify } = await import('node:util');
    const exec = promisify(execFile);

    const outDir = join(f.root, 'cli-backup');
    const verifyDir = join(f.root, 'cli-verify');
    const restoreDb = join(f.root, 'cli-restore', 'trindade.db');
    const restorePhotos = join(f.root, 'cli-restore', 'photos');

    // 1. capture
    const { stdout: captureOut } = await exec(process.execPath, [
      '--import', 'tsx', cliPath, 'capture', f.databasePath, f.assets, outDir, '--allow-root', f.root,
    ]);
    const captureResult = JSON.parse(captureOut);
    assert.equal(captureResult.result, 'ok');
    assert.ok(existsSync(captureResult.manifestPath));

    // 2. verify-isolated
    const { stdout: verifyOut } = await exec(process.execPath, [
      '--import', 'tsx', cliPath, 'verify-isolated', captureResult.manifestPath, verifyDir, '--allow-root', f.root,
    ]);
    const verifyResult = JSON.parse(verifyOut);
    assert.equal(verifyResult.result, 'ok');
    assert.equal(verifyResult.integrity, 'ok');

    // 3. restore
    const { stdout: restoreOut } = await exec(process.execPath, [
      '--import', 'tsx', cliPath, 'restore', captureResult.manifestPath, restoreDb, restorePhotos, '--allow-root', f.root,
    ]);
    const restoreResult = JSON.parse(restoreOut);
    assert.equal(restoreResult.result, 'ok');
    assert.equal(restoreResult.integrity, 'ok');
    assert.equal(restoreResult.rowCounts.users, 1);
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
