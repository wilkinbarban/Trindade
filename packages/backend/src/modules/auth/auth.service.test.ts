import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';

import { registerSchema } from './auth.schema.js';
import { registerWorkerUser } from './auth.service.js';

const schema = readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8');
const seed = readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8');

// `registerWorkerUser` is the whole storage contract behind POST /api/auth/register: it alone
// decides the role and pending state of a self-registered worker, whether a spelling collision
// is a duplicate, and whether an unexpected constraint failure may reach the route as a 500. The
// route tests pin the HTTP surface; these cases pin the invariants against a bare in-memory
// database, where no limiter, hash, or logger can stand in for a storage decision.
describe('registerWorkerUser storage invariants', () => {
  let db: Database.Database;

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(schema);
    db.exec(seed);
  });

  afterEach(() => db.close());

  /** A pre-existing operator, the way provisioning writes one: verbatim spelling, active, role 1. */
  function insertOperator(username: string): void {
    db.prepare(
      'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,1,1)'
    ).run(username, 'operator-hash', `Operator ${username}`);
  }

  function workerRow(username: string) {
    return db
      .prepare('SELECT username, display_name, role_id, is_active FROM users WHERE username = ?')
      .get(username) as
      | { username: string; display_name: string; role_id: number; is_active: number }
      | undefined;
  }

  it('creates the account inactive on role 2, never the role a caller might imply', () => {
    insertOperator('Admin');

    const result = registerWorkerUser(db, {
      username: 'novo-trabalhador',
      displayName: 'Novo Trabalhador',
      passwordHash: 'worker-hash',
      ipAddress: '10.0.0.7',
    });

    assert.equal(result.outcome, 'created');
    if (result.outcome !== 'created') throw new Error('unreachable');

    const row = workerRow('novo-trabalhador');
    assert.ok(row, 'the worker row was not inserted');
    assert.equal(row.role_id, 2, 'self-registration must never land on the administrator role');
    assert.equal(row.is_active, 0, 'self-registration must stay pending approval');
    assert.equal(row.display_name, 'Novo Trabalhador');

    const audit = db
      .prepare('SELECT action, entity_type, entity_id, ip_address FROM audit_logs WHERE user_id = ?')
      .get(result.userId) as { action: string; entity_type: string; entity_id: number; ip_address: string };
    assert.equal(audit.action, 'register');
    assert.equal(audit.entity_type, 'user');
    assert.equal(audit.entity_id, result.userId);
    assert.equal(audit.ip_address, '10.0.0.7');
  });

  it('refuses while the installation is empty, inserting nothing (bootstrap guard)', () => {
    db.exec('DELETE FROM users');

    const result = registerWorkerUser(db, {
      username: 'atomic-preempt-worker',
      displayName: 'Preempt Worker',
      passwordHash: 'dummy-hash',
    });

    assert.equal(result.outcome, 'bootstrap_required');
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0);
  });

  it('treats an ASCII case variant of a stored username as duplicate, without insert or audit', () => {
    insertOperator('Admin');
    const usersBefore = db.prepare('SELECT COUNT(*) FROM users').pluck().get();
    const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get();

    const result = registerWorkerUser(db, {
      username: 'admin',
      displayName: 'Imposter Worker',
      passwordHash: 'worker-hash',
    });

    assert.equal(result.outcome, 'duplicate');
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), usersBefore);
    assert.equal(db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get(), auditsBefore);
    assert.equal(workerRow('Admin')?.display_name, 'Operator Admin');
  });

  it('treats a Unicode case variant that SQLite BINARY and ASCII lower() cannot match as duplicate', () => {
    const stored = 'ÜnïcodeÖperator';
    const candidate = stored.toLowerCase();
    insertOperator(stored);

    // Premise: the UNIQUE constraint and `lower()` are ASCII-only, so only the JS scan can stop
    // this twin. If the constraint ever did match, this test would be proving nothing.
    assert.notEqual(candidate, stored);
    assert.equal(db.prepare('SELECT COUNT(*) FROM users WHERE username = ?').pluck().get(candidate), 0);

    const usersBefore = db.prepare('SELECT COUNT(*) FROM users').pluck().get();
    const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get();

    const result = registerWorkerUser(db, {
      username: candidate,
      displayName: 'Unicode Imposter',
      passwordHash: 'worker-hash',
    });

    assert.equal(result.outcome, 'duplicate');
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), usersBefore);
    assert.equal(db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get(), auditsBefore);
  });

  it('propagates a non-UNIQUE constraint failure instead of reporting a duplicate', () => {
    insertOperator('Admin');
    // A missing worker role makes the INSERT fail on the role_id foreign key: SQLITE_CONSTRAINT_*
    // but not a users.username UNIQUE violation, so the service must re-throw rather than answer
    // `duplicate`.
    db.exec('DELETE FROM roles WHERE id = 2');

    assert.throws(() =>
      registerWorkerUser(db, {
        username: 'missing-role-worker',
        displayName: 'Missing Role Worker',
        passwordHash: 'worker-hash',
      })
    );
    assert.equal(workerRow('missing-role-worker'), undefined);
  });
});

// The route parses the body with this schema before the service ever sees it, so the schema owns
// the admission shape: privilege fields must not survive parsing, and the field bounds are the
// first line of defence against an oversized or empty payload.
describe('registerSchema admission invariants', () => {
  const valid = { username: 'worker-schema', display_name: 'Schema Worker', password: 'password123' };

  it('accepts the documented body and rejects unknown or privilege-bearing fields', () => {
    assert.equal(registerSchema.safeParse(valid).success, true);

    for (const payload of [
      { ...valid, role_id: 1 },
      { ...valid, is_active: 1 },
      { ...valid, role: 'Administrador' },
      { ...valid, id: 999 },
    ]) {
      assert.equal(registerSchema.safeParse(payload).success, false, JSON.stringify(payload));
    }
  });

  it('enforces length and byte bounds on username, display_name, and password', () => {
    assert.equal(registerSchema.safeParse({ ...valid, username: 'a'.repeat(51) }).success, false);
    assert.equal(registerSchema.safeParse({ ...valid, display_name: 'a'.repeat(101) }).success, false);
    assert.equal(registerSchema.safeParse({ ...valid, username: '   ' }).success, false);
    assert.equal(registerSchema.safeParse({ ...valid, display_name: '   ' }).success, false);
    assert.equal(registerSchema.safeParse({ ...valid, password: '1234567' }).success, false);
    assert.equal(registerSchema.safeParse({ ...valid, password: 'a'.repeat(73) }).success, false);
    // 25 emoji are 25 characters but 100 UTF-8 bytes, so the byte-bound refine must reject them.
    assert.equal(registerSchema.safeParse({ ...valid, password: '🔐'.repeat(25) }).success, false);
  });
});
