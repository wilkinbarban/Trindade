import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { checkProvisioningDatabase, provisionAdministrator, readPassword } from './provision-admin.js';
import { stampSchemaVersion } from './schema-version.js';

function fixture() {
  const path = join(mkdtempSync(join(tmpdir(), 'provision-test-')), 'database.db');
  const db = new Database(path);
  db.exec(readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8'));
  stampSchemaVersion(db);
  db.close();
  return path;
}

describe('operator administrator provisioning', () => {
  it('takes a bounded password line from stdin without an interactive echo', async () => {
    const input = new PassThrough();
    const pending = readPassword(input);
    input.end('strong-password\n');
    assert.equal(await pending, 'strong-password');
    const oversized = new PassThrough();
    const rejected = readPassword(oversized);
    oversized.end('x'.repeat(4097));
    await assert.rejects(rejected, /limit/);
  });

  it('creates only one administrator across competing provision attempts', async () => {
    const path = fixture();
    const results = await Promise.allSettled(Array.from({ length: 4 }, (_, index) =>
      provisionAdministrator(path, `owner${index}`, 'Administrator', 'strong-password')));
    assert.equal(results.filter((result) => result.status === 'fulfilled').length, 1);
    const db = new Database(path, { readonly: true });
    try {
      const users = db.prepare(`SELECT users.*, roles.name AS role FROM users JOIN roles ON users.role_id=roles.id`).all() as Array<{ role: string; password_hash: string; is_active: number }>;
      assert.equal(users.length, 1);
      assert.equal(users[0].role, 'Administrador');
      assert.equal(users[0].is_active, 1);
      assert.equal(await bcrypt.compare('strong-password', users[0].password_hash), true);
      assert.equal(users[0].password_hash.includes('strong-password'), false);
    } finally { db.close(); }
    await assert.rejects(provisionAdministrator(path, 'second', 'Second', 'strong-password'), /empty users/);
  });

  it('refuses obsolete session schema and invalid input without creating a user', async () => {
    const path = fixture();
    const db = new Database(path);
    db.exec('DROP TABLE auth_sessions');
    db.close();
    assert.throws(() => checkProvisioningDatabase(path), /not current/);
    await assert.rejects(provisionAdministrator(path, 'owner', 'Owner', 'strong-password'), /not current/);
    const fresh = fixture();
    await assert.rejects(provisionAdministrator(fresh, 'owner', 'Owner', 'short'), /Invalid/);
    const freshDb = new Database(fresh, { readonly: true });
    try { assert.equal(freshDb.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0); }
    finally { freshDb.close(); }
  });
});
