import type Database from 'better-sqlite3';

/**
 * Create the `auth_sessions` table and its indexes introduced in schema revision 2.
 *
 * This is the strictly additive 1 → 2 step: every statement uses `IF NOT EXISTS`, so a
 * partially applied or re-run migration completes without touching existing data. No
 * table rebuild and no data movement is performed.
 */
export function migrateAuthSessions(db: Database.Database): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS auth_sessions (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash   TEXT    NOT NULL UNIQUE,   -- sha256 hex of the refresh token; never the raw value
      family_id    TEXT    NOT NULL,          -- rotation family, for reuse detection
      expires_at   TEXT    NOT NULL,
      revoked_at   TEXT,
      replaced_by  TEXT,                      -- token_hash of the successor
      created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
      last_used_at TEXT,
      ip_address   TEXT
    );

    CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id   ON auth_sessions(user_id);
    CREATE INDEX IF NOT EXISTS idx_auth_sessions_family_id ON auth_sessions(family_id);
  `);
}
