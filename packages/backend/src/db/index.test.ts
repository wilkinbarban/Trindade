import { afterEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { openDatabase } from './index.js';

describe('safe database startup', () => {
  const directories: string[] = [];
  afterEach(() => directories.splice(0).forEach(path => rmSync(path, { recursive: true, force: true })));
  const target = () => { const dir = mkdtempSync(join(tmpdir(), 'trindade-startup-')); directories.push(dir); return join(dir, 'app.db'); };

  it('initializes references without fixed users only for a positively fresh target', () => {
    const path = target();
    const db = openDatabase(path);
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0);
    assert.ok((db.prepare('SELECT COUNT(*) FROM roles').pluck().get() as number) > 0);
    db.close();
  });

  it('permits a legitimate write after reopening an existing database', () => {
    const path = target();
    const seed = new Database(path);
    seed.exec(readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8'));
    seed.close();

    const db = openDatabase(path);
    assert.doesNotThrow(() => db.prepare("INSERT INTO settings VALUES ('editable','yes',datetime('now'))").run());
    db.close();
  });

  it('reopens an existing installation without resetting its contents', () => {
    const path = target();
    const seed = new Database(path);
    seed.exec(readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8'));
    seed.prepare("INSERT INTO settings VALUES ('preserved','yes',datetime('now'))").run();
    seed.close();

    const db = openDatabase(path);
    assert.equal(db.prepare("SELECT value FROM settings WHERE key = 'preserved'").pluck().get(), 'yes');
    db.close();
  });

  it('serves login through existing-install startup without writing production data', async () => {
    const path = target();
    const seed = new Database(path);
    seed.exec(readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8'));
    seed.exec(readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8'));
    seed.prepare('INSERT INTO users (username,password_hash,display_name,role_id,is_active) VALUES (?,?,?,?,1)')
      .run('existing-admin', bcrypt.hashSync('existing-password', 4), 'Existing Admin', 1);
    const reportId = Number(seed.prepare("INSERT INTO reports (user_id,turno,report_date) VALUES (1,'tarde','2026-08-01')").run().lastInsertRowid);
    seed.prepare('INSERT INTO report_photos (report_id,file_path,file_size,mime_type,public_token,created_at) VALUES (?,?,?,?,?,?)')
      .run(reportId, 'expired.jpg', 7, 'image/jpeg', 'expired-token', '2000-01-01 00:00:00');
    seed.prepare('INSERT INTO report_photos (report_id,file_path,file_size,mime_type,public_token,created_at) VALUES (?,?,?,?,?,?)')
      .run(reportId, 'retained.jpg', 8, 'image/jpeg', 'retained-token', '2999-01-01 00:00:00');
    seed.close();
    const photosDir = join(path, '..', 'photos');
    mkdirSync(photosDir, { recursive: true });
    writeFileSync(join(photosDir, 'expired.jpg'), 'expired');
    writeFileSync(join(photosDir, 'retained.jpg'), 'retained');
    const before = readFileSync(path);
    const retainedBefore = readFileSync(join(photosDir, 'retained.jpg'));
    const port = 30000 + (process.pid % 20000);
    const { NODE_TEST_CONTEXT: _nodeTestContext, ...serverEnv } = process.env;
    const serverPath = join(import.meta.dirname, '../../dist/server.js');
    const child = spawn(process.execPath, [serverPath], {
      cwd: join(import.meta.dirname, '../../'),
      env: { ...serverEnv, DATABASE_PATH: path, JWT_SECRET: 'existing-install-test-secret-32-bytes', HOST: '127.0.0.1', PORT: String(port), PHOTOS_DIR: photosDir },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let logs = '';
    child.stdout.on('data', chunk => { logs += chunk; });
    child.stderr.on('data', chunk => { logs += chunk; });
    try {
      let healthStatus = 0;
      for (let attempt = 0; attempt < 50; attempt++) {
        try { healthStatus = (await fetch(`http://127.0.0.1:${port}/api/health`)).status; if (healthStatus === 200) break; } catch {}
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      assert.equal(healthStatus, 200, logs);
      const response = await fetch(`http://127.0.0.1:${port}/api/auth/login`, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username: 'existing-admin', password: 'existing-password' }),
      });
      assert.equal(response.status, 200, logs);
      assert.ok((await response.json() as { token?: string }).token);
      await new Promise(resolve => setTimeout(resolve, 25));
    } finally {
      if (child.exitCode === null) {
        child.kill('SIGTERM');
        await new Promise(resolve => child.once('exit', resolve));
      }
    }
    assert.equal(existsSync(join(photosDir, 'expired.jpg')), false);
    assert.deepEqual(readFileSync(join(photosDir, 'retained.jpg')), retainedBefore);
    assert.deepEqual(readFileSync(path), before);
  });

  it('refuses an ambiguous target before mutation', () => {
    const path = target(); writeFileSync(path, '');
    assert.throws(() => openDatabase(path), /startup refused/);
    assert.equal(readFileSync(path).length, 0);
  });
});
