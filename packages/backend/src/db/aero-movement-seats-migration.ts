import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 7 → 8 cutover: a seat is not unique, a seat plus its product is.
 *
 * Revision 7 read one ERP seat as one product and keyed the mirror by `(movement_date, ent_lan)`. A
 * purchase document is a single `ENT_LAN` with one line per product, so a second line overwrote the
 * first and the plant lost the rest of the document. The fix is the key, not the report: SQLite cannot
 * alter a primary key, so the table is rebuilt, every row is copied, and the copy is counted before the
 * revision is stamped. The rebuild runs inside one immediate transaction, so a crash leaves the old table
 * exactly as it was.
 *
 * The line's identity is `(seat, product)`. Two lines of the same product under one seat would still
 * collapse because the company WebService exposes no line identifier; that limit is documented rather
 * than hidden.
 */
export function migrateAeroMovementSeats(db: Database.Database): void {
  db.transaction(() => {
    const before = (db.prepare('SELECT COUNT(*) AS n FROM aero_movements').get() as { n: number }).n;

    db.exec(`CREATE TABLE aero_movements_revision8 (
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
      UNIQUE(movement_date, ent_lan, product_code)
    )`);

    db.exec(`INSERT INTO aero_movements_revision8
      (id, movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
      SELECT id, movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at
      FROM aero_movements`);

    const after = (db.prepare('SELECT COUNT(*) AS n FROM aero_movements_revision8').get() as { n: number }).n;
    if (after !== before) {
      throw new Error(`aero_movements rekey copied ${after} of ${before} rows; refusing to drop the original`);
    }

    db.exec('DROP TABLE aero_movements');
    db.exec('ALTER TABLE aero_movements_revision8 RENAME TO aero_movements');
    db.exec('CREATE INDEX IF NOT EXISTS idx_aero_movements_date ON aero_movements(movement_date)');

    stampSchemaVersion(db, 8);
  }).immediate();
}
