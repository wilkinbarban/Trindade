import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 6 → 7 cutover: the company ERP's own movement seats, mirrored in Trindade.
 *
 * The mirror is a snapshot, not a source of truth: `ENT_LAN` belongs to the ERP, and this table only
 * keeps a copy so every device in the plant reads the same day without running the iOS shortcut again.
 * Creating the table rewrites nothing, so the step is safe against the live database, and the table is
 * created and the revision stamped inside one immediate transaction.
 */
export function migrateAeroMovements(db: Database.Database): void {
  db.transaction(() => {
    db.exec(`CREATE TABLE IF NOT EXISTS aero_movements (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      movement_date   TEXT NOT NULL,
      ent_lan         INTEGER NOT NULL,
      product_code    TEXT NOT NULL,
      product_desc    TEXT NOT NULL,
      quantity        REAL NOT NULL,
      entry_date      TEXT NOT NULL,
      operator_name   TEXT NOT NULL,
      movement_type   TEXT NOT NULL CHECK (movement_type IN ('E', 'S')),
      synced_at       TEXT NOT NULL,
      UNIQUE(movement_date, ent_lan)
    )`);
    db.exec(`CREATE INDEX IF NOT EXISTS idx_aero_movements_date ON aero_movements(movement_date)`);
    stampSchemaVersion(db, 7);
  }).immediate();
}
