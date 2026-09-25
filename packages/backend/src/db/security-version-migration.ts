import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/** Revision 2 → 3 cutover: invalidate every live refresh session, including unexpired rows. */
export function migrateSecurityVersion(db: Database.Database): void {
  db.transaction(() => {
    db.exec('ALTER TABLE users ADD COLUMN security_version INTEGER NOT NULL DEFAULT 1 CHECK (security_version >= 1)');
    db.prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE revoked_at IS NULL").run();
    stampSchemaVersion(db, 3);
  }).immediate();
}
