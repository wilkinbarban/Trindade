import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from '../../db/reference-data.js';
import {
  hashRefreshToken,
  issueSession,
  purgeDeadSessions,
  revokeSession,
  revokeUserSessions,
  rotateSession,
  sessionStoreExists,
} from './auth.sessions.service.js';

const schema = readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8');
const seed = readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8');

function familyOf(db: Database.Database, token: string): string {
  const row = db
    .prepare('SELECT family_id FROM auth_sessions WHERE token_hash = ?')
    .get(hashRefreshToken(token)) as { family_id: string } | undefined;
  assert.ok(row, 'no session row exists for this token');
  return row.family_id;
}

function liveSessionCount(db: Database.Database): number {
  return db.prepare('SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NULL').pluck().get() as number;
}

describe('auth session service', () => {
  let db: Database.Database;
  let adminId: number;
  let workerId: number;

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(schema);
    db.exec(seed);
    verifyReferencePrerequisites(db);

    const roleId = (name: string) => REFERENCE_DATASET.roles.find((role) => role.name === name)!.id;
    const insert = db.prepare(
      'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)',
    );
    adminId = Number(insert.run('session-admin', 'fixture-hash', 'Admin', roleId('Administrador')).lastInsertRowid);
    workerId = Number(insert.run('session-worker', 'fixture-hash', 'Worker', roleId('Trabalhador')).lastInsertRowid);
  });

  afterEach(() => db.close());

  it('stores only the hash of a refresh token', () => {
    const token = issueSession(db, adminId, 30);

    const rows = db.prepare('SELECT token_hash FROM auth_sessions').all() as { token_hash: string }[];
    assert.equal(rows.length, 1);
    assert.notEqual(rows[0].token_hash, token, 'the raw refresh token was persisted');
    assert.equal(rows[0].token_hash, hashRefreshToken(token));
    assert.match(rows[0].token_hash, /^[0-9a-f]{64}$/);
  });

  it('rotates within the same family and links the token to its successor', () => {
    const first = issueSession(db, adminId, 30);
    const familyBefore = familyOf(db, first);

    const result = rotateSession(db, first, 30);
    if (result.outcome !== 'rotated') assert.fail(`expected rotated, got ${result.outcome}`);
    assert.equal(result.userId, adminId);
    assert.notEqual(result.refreshToken, first);
    assert.equal(familyOf(db, result.refreshToken), familyBefore, 'rotation left the family');

    const previous = db
      .prepare('SELECT revoked_at, replaced_by FROM auth_sessions WHERE token_hash = ?')
      .get(hashRefreshToken(first)) as { revoked_at: string | null; replaced_by: string | null };
    assert.ok(previous.revoked_at, 'the rotated-away token was not revoked');
    assert.equal(previous.replaced_by, hashRefreshToken(result.refreshToken));
  });

  it('revokes the whole family when a token rotated long ago is presented', () => {
    const first = issueSession(db, adminId, 30);
    const rotated = rotateSession(db, first, 30);
    if (rotated.outcome !== 'rotated') assert.fail('the first rotation did not succeed');
    assert.equal(liveSessionCount(db), 1);

    // Age the rotation past the grace window. At that point a replay is evidence of theft rather
    // than a lost response, which is the case this branch exists for.
    db.prepare("UPDATE auth_sessions SET revoked_at = datetime('now', '-1 hour') WHERE token_hash = ?")
      .run(hashRefreshToken(first));

    assert.deepEqual(rotateSession(db, first, 30), { outcome: 'reused' });
    assert.equal(liveSessionCount(db), 0, 'reuse detection left a live session in the family');
  });

  // A refresh whose response never reached the client, and a replayed token, produce the same
  // state at the same instant. Only age separates them, and treating the retry as theft would
  // sign the user out of every device because of one lost packet.
  it('treats a token rotated seconds ago as a retry and leaves the family alive', () => {
    const first = issueSession(db, adminId, 30);
    const rotated = rotateSession(db, first, 30);
    if (rotated.outcome !== 'rotated') assert.fail('the first rotation did not succeed');

    assert.deepEqual(rotateSession(db, first, 30), { outcome: 'reused-within-grace' });
    assert.equal(liveSessionCount(db), 1, 'a retry signed the user out of their other devices');
    assert.equal(
      rotateSession(db, rotated.refreshToken, 30).outcome,
      'rotated',
      'the legitimate successor must still work after a retry',
    );
  });

  // The guard the routes consume is fed by this check, so it is proven against a database in the
  // shape the previous build produced: no session table at all.
  it('detects a database with no session table', () => {
    assert.equal(sessionStoreExists(db), true);

    db.exec('DROP TABLE auth_sessions');

    assert.equal(sessionStoreExists(db), false);
  });

  it('treats an explicitly revoked token as ended, not as reuse', () => {
    const phone = issueSession(db, adminId, 30);
    const desktop = issueSession(db, adminId, 30);

    assert.equal(revokeSession(db, phone), true);
    assert.equal(revokeSession(db, phone), false, 'revoking an already-ended session reported a change');

    assert.deepEqual(rotateSession(db, phone, 30), { outcome: 'revoked' });

    // The desktop session must survive: a client retrying after its own logout is not an
    // attacker, so it must not cost the user's other devices.
    assert.equal(rotateSession(db, desktop, 30).outcome, 'rotated');
  });

  it('reports an expired session and revokes it', () => {
    const token = issueSession(db, adminId, 0);

    assert.deepEqual(rotateSession(db, token, 30), { outcome: 'expired' });

    const row = db
      .prepare('SELECT revoked_at FROM auth_sessions WHERE token_hash = ?')
      .get(hashRefreshToken(token)) as { revoked_at: string | null };
    assert.ok(row.revoked_at, 'an expired session was left unrevoked');
  });

  it('reports an unknown token without throwing', () => {
    assert.deepEqual(rotateSession(db, 'not-a-real-refresh-token', 30), { outcome: 'unknown' });
  });

  it('revokes the family when the owning user is inactive', () => {
    const token = issueSession(db, workerId, 30);
    db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(workerId);

    assert.deepEqual(rotateSession(db, token, 30), { outcome: 'inactive-user' });
    assert.equal(liveSessionCount(db), 0);
  });

  it('revokes every live session of one user and leaves another user alone', () => {
    const adminOne = issueSession(db, adminId, 30);
    const adminTwo = issueSession(db, adminId, 30);
    const workerOne = issueSession(db, workerId, 30);

    assert.equal(revokeUserSessions(db, adminId), 2);
    assert.equal(revokeUserSessions(db, adminId), 0, 'a second pass reported sessions it had already ended');

    assert.deepEqual(rotateSession(db, adminOne, 30), { outcome: 'revoked' });
    assert.deepEqual(rotateSession(db, adminTwo, 30), { outcome: 'revoked' });
    assert.equal(rotateSession(db, workerOne, 30).outcome, 'rotated', 'another user lost their session');
  });

  it('purges dead sessions past the retention window and never a live one', () => {
    const live = issueSession(db, adminId, 30);
    const deadRevoked = issueSession(db, adminId, 30);
    const deadExpired = issueSession(db, adminId, 0);
    const recentDead = issueSession(db, adminId, 0);
    assert.equal(revokeSession(db, deadRevoked), true);

    const age = (token: string, days: number) =>
      db
        .prepare("UPDATE auth_sessions SET created_at = datetime('now', ?) WHERE token_hash = ?")
        .run(`${days < 0 ? '-' : '+'}${Math.abs(days)} days`, hashRefreshToken(token));

    age(deadRevoked, -90);
    age(deadExpired, -90);
    // Aged far beyond the window, but still live: age alone must never delete a session.
    age(live, -365);

    assert.equal(purgeDeadSessions(db, 30), 2);

    const remaining = db.prepare('SELECT token_hash FROM auth_sessions').pluck().all() as string[];
    assert.deepEqual(remaining.sort(), [hashRefreshToken(live), hashRefreshToken(recentDead)].sort());
  });
});
