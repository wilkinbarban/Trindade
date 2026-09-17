import { createHash, randomBytes, randomUUID } from 'node:crypto';
import type Database from 'better-sqlite3';

/**
 * Persistent refresh-token sessions.
 *
 * Only the SHA-256 hash of a refresh token is ever stored. A leaked database read, a
 * stolen backup, or a log line must not hand anyone a usable credential, and hashing
 * keeps lookup an indexed equality check instead of a table scan. The digest is a plain
 * hash rather than a password KDF on purpose: a 256-bit random token has no guessable
 * structure, so there is nothing for a deliberately slow hash to slow down.
 *
 * Every refresh rotates the token and revokes its predecessor within one family
 * (`family_id`). That is what makes reuse detection possible, and it is why the two ways
 * a token can be dead are kept apart:
 *
 * - **Rotated away** (`replaced_by` set): the client that consumed it already holds its
 *   successor, so whoever presents it is not that client. The token leaked, and the
 *   whole family is revoked.
 * - **Explicitly revoked** (logout, password change): the session simply ended. A client
 *   retrying after a logout must not be treated as an attacker, so the family is left
 *   alone.
 */

const TOKEN_BYTES = 32;

export type RotateSessionResult =
  | { outcome: 'rotated'; userId: number; refreshToken: string }
  | { outcome: 'unknown' }
  | { outcome: 'expired' }
  | { outcome: 'reused' }
  | { outcome: 'revoked' }
  | { outcome: 'inactive-user' };

type SessionRow = {
  id: number;
  user_id: number;
  family_id: string;
  revoked_at: string | null;
  replaced_by: string | null;
  is_expired: number;
};

/** Stable digest of a refresh token. The only form that is ever persisted. */
export function hashRefreshToken(token: string): string {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

function newRefreshToken(): string {
  return randomBytes(TOKEN_BYTES).toString('base64url');
}

/**
 * SQLite datetime modifier. Date arithmetic stays in the database so a session lifetime
 * is computed by the same clock that later compares against it, which also keeps the
 * stored format identical to the schema's other `datetime('now')` columns.
 */
function dayModifier(days: number): string {
  return `${days < 0 ? '-' : '+'}${Math.abs(days)} days`;
}

function insertSession(
  db: Database.Database,
  userId: number,
  familyId: string,
  tokenHash: string,
  ttlDays: number,
  ipAddress: string | null,
): void {
  db.prepare(
    `INSERT INTO auth_sessions (user_id, token_hash, family_id, expires_at, ip_address)
     VALUES (?, ?, ?, datetime('now', ?), ?)`,
  ).run(userId, tokenHash, familyId, dayModifier(ttlDays), ipAddress);
}

function revokeFamily(db: Database.Database, familyId: string): number {
  return db
    .prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE family_id = ? AND revoked_at IS NULL")
    .run(familyId).changes;
}

function revokeRow(db: Database.Database, id: number): void {
  db.prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL").run(id);
}

/**
 * Start a session family for a user and return the raw refresh token. The caller hands
 * it to the client and must never persist or log it.
 *
 * `ttlDays` is not validated here; configuration owns that. A `ttlDays` of 0 produces an
 * already-expired session, which is how the expiry path is exercised.
 */
export function issueSession(
  db: Database.Database,
  userId: number,
  ttlDays: number,
  ipAddress: string | null = null,
): string {
  const token = newRefreshToken();
  insertSession(db, userId, randomUUID(), hashRefreshToken(token), ttlDays, ipAddress);
  return token;
}

/**
 * Exchange a refresh token for a new one, rotating within its family.
 *
 * The lookup, the reuse decision, the expiry decision, and the replacement all happen in
 * one transaction. A crash or a concurrent refresh therefore cannot leave a token both
 * revoked and un-replaced, which is the state that would either lock a legitimate client
 * out or leave a leaked token usable.
 */
export function rotateSession(
  db: Database.Database,
  token: string,
  ttlDays: number,
  ipAddress: string | null = null,
): RotateSessionResult {
  const tokenHash = hashRefreshToken(token);

  const rotate = db.transaction((): RotateSessionResult => {
    const row = db
      .prepare(
        `SELECT id, user_id, family_id, revoked_at, replaced_by,
                (expires_at <= datetime('now')) AS is_expired
         FROM auth_sessions
         WHERE token_hash = ?`,
      )
      .get(tokenHash) as SessionRow | undefined;

    if (!row) return { outcome: 'unknown' };

    // Dead because it was rotated away: its successor already exists, so presenting this
    // one means the token leaked. Treat the whole family as compromised.
    if (row.revoked_at !== null && row.replaced_by !== null) {
      revokeFamily(db, row.family_id);
      return { outcome: 'reused' };
    }

    // Dead because the session ended normally. Nothing suspicious, so the family stands.
    if (row.revoked_at !== null) return { outcome: 'revoked' };

    if (row.is_expired) {
      revokeRow(db, row.id);
      return { outcome: 'expired' };
    }

    const user = db.prepare('SELECT is_active FROM users WHERE id = ?').get(row.user_id) as
      | { is_active: number }
      | undefined;
    if (!user || user.is_active !== 1) {
      revokeFamily(db, row.family_id);
      return { outcome: 'inactive-user' };
    }

    const refreshToken = newRefreshToken();
    const nextHash = hashRefreshToken(refreshToken);
    insertSession(db, row.user_id, row.family_id, nextHash, ttlDays, ipAddress);
    db.prepare(
      `UPDATE auth_sessions
       SET revoked_at = datetime('now'), replaced_by = ?, last_used_at = datetime('now')
       WHERE id = ?`,
    ).run(nextHash, row.id);

    return { outcome: 'rotated', userId: row.user_id, refreshToken };
  });

  return rotate();
}

/**
 * End one device's session. Other sessions of the same user stay alive, so logging out on
 * a phone does not log the desktop out. Returns true only when a live session was ended.
 */
export function revokeSession(db: Database.Database, token: string): boolean {
  return (
    db
      .prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE token_hash = ? AND revoked_at IS NULL")
      .run(hashRefreshToken(token)).changes > 0
  );
}

/**
 * End every live session of one user, returning how many were ended. Used when the
 * credential itself changes and every existing session must be re-established.
 */
export function revokeUserSessions(db: Database.Database, userId: number): number {
  return db
    .prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE user_id = ? AND revoked_at IS NULL")
    .run(userId).changes;
}

/**
 * Delete sessions that are already dead — revoked, or expired — and whose creation is
 * older than the retention window, returning how many rows were deleted.
 *
 * Age alone never deletes anything: a session that is still live is kept however old it
 * is, so a long-lived session is not silently destroyed by housekeeping. The retention
 * window keeps recently dead rows around briefly instead of erasing the evidence that a
 * session ended.
 */
export function purgeExpiredSessions(db: Database.Database, retentionDays: number): number {
  return db
    .prepare(
      `DELETE FROM auth_sessions
       WHERE (revoked_at IS NOT NULL OR expires_at <= datetime('now'))
         AND created_at < datetime('now', ?)`,
    )
    .run(dayModifier(-retentionDays)).changes;
}
