import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { migrateDatabase } from './migrations.js';
import { migrateSecurityVersion } from './security-version-migration.js';

const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');

function revisionTwo(): Database.Database {
  const db = new Database(':memory:');
  db.exec(schema);
  db.exec('ALTER TABLE users DROP COLUMN security_version');
  db.pragma('user_version = 2');
  db.exec("INSERT INTO roles (id, name) VALUES (1, 'Administrador')");
  db.exec("INSERT INTO users (id, username, password_hash, display_name, role_id) VALUES (1, 'owner', 'hash', 'Owner', 1)");
  db.prepare('INSERT INTO auth_sessions (user_id, token_hash, family_id, expires_at, revoked_at) VALUES (1, ?, ?, ?, ?)')
    .run('live', 'family', '2999-01-01', null);
  db.prepare('INSERT INTO auth_sessions (user_id, token_hash, family_id, expires_at, revoked_at) VALUES (1, ?, ?, ?, ?)')
    .run('old', 'family', '2000-01-01', '2000-01-01');
  return db;
}

describe('revision 2 → 3 security cutover', () => {
  it('adds a constrained default and revokes existing live sessions exactly once', () => {
    const db = revisionTwo();
    try {
      assert.equal(migrateDatabase(db).outcome, 'migrated');
      assert.equal(db.pragma('user_version', { simple: true }), 3);
      assert.equal(db.prepare('SELECT security_version FROM users WHERE id = 1').pluck().get(), 1);
      assert.throws(() => db.prepare('UPDATE users SET security_version = 0 WHERE id = 1').run(), /CHECK constraint/);
      assert.ok(db.prepare("SELECT revoked_at FROM auth_sessions WHERE token_hash = 'live'").pluck().get());
      assert.equal(db.prepare("SELECT revoked_at FROM auth_sessions WHERE token_hash = 'old'").pluck().get(), '2000-01-01');
      db.prepare('INSERT INTO auth_sessions (user_id, token_hash, family_id, expires_at) VALUES (1, ?, ?, ?)')
        .run('new', 'family', '2999-01-01');
      assert.equal(migrateDatabase(db).outcome, 'already-current');
      assert.equal(db.prepare("SELECT revoked_at FROM auth_sessions WHERE token_hash = 'new'").pluck().get(), null);
    } finally { db.close(); }
  });

  it('rolls back column, revocation, and version when revocation fails', () => {
    const db = revisionTwo();
    try {
      db.exec("CREATE TRIGGER prevent_cutover BEFORE UPDATE ON auth_sessions BEGIN SELECT RAISE(ABORT, 'cutover rejected'); END");
      assert.throws(() => migrateSecurityVersion(db), /cutover rejected/);
      assert.equal(db.pragma('user_version', { simple: true }), 2);
      assert.ok(!(db.prepare('PRAGMA table_info(users)').all() as { name: string }[]).some((c) => c.name === 'security_version'));
      assert.equal(db.prepare("SELECT revoked_at FROM auth_sessions WHERE token_hash = 'live'").pluck().get(), null);
      db.exec('DROP TRIGGER prevent_cutover');
      assert.equal(migrateDatabase(db).outcome, 'migrated');
    } finally { db.close(); }
  });
});
