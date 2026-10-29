import type Database from 'better-sqlite3';
import type { AeroMovementItemInput } from './aero.movements.schema.js';

/**
 * One mirrored seat of the company ERP, as this module stores and returns it.
 *
 * `synced_at` is when Trindade heard from the ERP, not when the movement happened: the seat is
 * immutable in Aero32 and `entry_date` is what the factory did.
 */
export interface AeroMovement {
  ent_lan: number;
  product_code: string;
  product_desc: string;
  quantity: number;
  entry_date: string;
  operator_name: string;
  movement_type: 'E' | 'S';
  synced_at: string;
}

interface RawAeroMovementRow extends AeroMovement {
  id: number;
}

const TABLE = 'aero_movements';

/**
 * Whether the revision that adds the mirror table has run against this database.
 *
 * The table is created by `db:migrate`, never at startup (Production Immutability Gate), so an image
 * can be serving before an operator migrated. Asking first turns a bare SQL error into a named answer.
 */
export function aeroMovementsTableExists(db: Database.Database): boolean {
  const row = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")
    .get(TABLE);
  return row !== undefined;
}

/**
 * Stores one day's answer, one row per ERP seat.
 *
 * `ENT_LAN` identifies the document, and a document carries one line per product, so the stored key is
 * the seat plus the product. A second sync of the same day updates the lines it recognises instead of
 * appending duplicates. Rows the snapshot no longer mentions are left alone: a seat does not disappear
 * from Aero32, and an absent one is far more likely to be a partial answer than a deletion -- erasing it
 * here would destroy the only copy the plant reads.
 */
export function saveAeroMovements(
  db: Database.Database,
  args: { date: string; movements: readonly AeroMovementItemInput[] },
): number {
  const syncedAt = new Date().toISOString();
  const statement = db.prepare(
    `INSERT INTO aero_movements
       (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
     VALUES
       (@date, @ent_lan, @product_code, @product_desc, @quantity, @entry_date, @operator_name, @movement_type, @synced_at)
     ON CONFLICT(movement_date, ent_lan, product_code) DO UPDATE SET
       product_code  = excluded.product_code,
       product_desc  = excluded.product_desc,
       quantity      = excluded.quantity,
       entry_date    = excluded.entry_date,
       operator_name = excluded.operator_name,
       movement_type = excluded.movement_type,
       synced_at     = excluded.synced_at`,
  );

  db.transaction(() => {
    for (const movement of args.movements) {
      statement.run({
        date: args.date,
        ent_lan: movement.ent_lan,
        product_code: movement.product_code.trim(),
        product_desc: movement.product_desc.trim(),
        quantity: movement.quantity,
        entry_date: movement.entry_date,
        // The ERP answers `ENT_USU` in uppercase; normalising here keeps one operator from rendering as
        // two chips when a hand-typed seat arrives in lower case.
        operator_name: movement.operator_name.trim().toUpperCase(),
        movement_type: movement.movement_type,
        synced_at: syncedAt,
      });
    }
  })();

  return args.movements.length;
}

/** The seats mirrored for one day, oldest seat first, which is the order the ERP answers in. */
export function listAeroMovements(db: Database.Database, date: string): AeroMovement[] {
  const rows = db
    .prepare(
      `SELECT id, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at
         FROM aero_movements
        WHERE movement_date = ?
        ORDER BY ent_lan, id`,
    )
    .all(date) as RawAeroMovementRow[];

  return rows.map(({ id: _id, ...movement }) => movement);
}
