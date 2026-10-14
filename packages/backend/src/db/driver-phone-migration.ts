import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 3 → 4 cutover: the optional phone a fletero can be reached at.
 *
 * The column is additive and rewrites nothing, which is the whole design: every driver that already
 * exists keeps its row and answers a null phone until an operator types one, so the migration cannot
 * lose a fleet or a schedule that references it. The `ALTER` and the stamp share one immediate
 * transaction, so a crash between them rolls both back and the step stays re-runnable rather than
 * half-applied.
 */
export function migrateDriverPhone(db: Database.Database): void {
  db.transaction(() => {
    db.exec('ALTER TABLE drivers ADD COLUMN phone TEXT');
    stampSchemaVersion(db, 4);
  }).immediate();
}
