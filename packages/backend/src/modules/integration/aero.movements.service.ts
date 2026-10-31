import type Database from 'better-sqlite3';
import type { AeroMovementItemInput } from './aero.movements.schema.js';
import { matchOperatorUserId, type OperatorCandidate } from '../massas/massas-operator.js';

/**
 * What the seat's operator means for Trindade, in the terms a screen has to act on.
 *
 * `owner_user_id` answers who may import the seat today. This answers why, so the row can offer the one
 * action that works: create the operator when the name is unknown, reactivate the account when it exists
 * and was deactivated, or import when somebody owns it.
 */
export type SeatOwnerStatus = 'active' | 'inactive' | 'missing';

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
  /**
   * The account this seat belongs to, resolved from `operator_name`.
   *
   * `null` means the name the ERP wrote belongs to nobody here, which is a fact the screens need: an
   * import button offered on somebody else's seat is a button the backend will refuse. The value comes
   * from the same resolution the import rule uses, so hiding the button and refusing the request cannot
   * disagree.
   */
  owner_user_id: number | null;
  /** Why `owner_user_id` is what it is: an active owner, a deactivated account, or nobody here. */
  owner_status: SeatOwnerStatus;
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

  if (rows.length === 0) return [];
  const owners = seatOwners(db, rows.map((row) => row.operator_name));
  return rows.map(({ id: _id, ...movement }) => {
    const owner = owners.get(movement.operator_name);
    return {
      ...movement,
      owner_user_id: owner?.userId ?? null,
      owner_status: owner?.status ?? 'missing',
    };
  });
}

/**
 * Resolves every operator name of one day in a single read.
 *
 * One query for the day's people instead of one per seat: a day has a handful of operators and dozens of
 * seats, and the resolution itself is the same function that decides who may import.
 */
function seatOwners(
  db: Database.Database,
  names: readonly string[],
): Map<string, { userId: number | null; status: SeatOwnerStatus }> {
  const active = db
    .prepare('SELECT id, username, display_name FROM users WHERE is_active = 1 ORDER BY id')
    .all() as OperatorCandidate[];
  const inactive = db
    .prepare('SELECT id, username, display_name FROM users WHERE is_active = 0 ORDER BY id')
    .all() as OperatorCandidate[];
  const resolved = new Map<string, { userId: number | null; status: SeatOwnerStatus }>();
  for (const name of names) {
    // Active first, exactly as the import rule resolves: a working person is never shadowed by a namesake
    // who was deactivated. A deactivated account answers `null` for the owner, because it cannot import.
    const activeMatch = matchOperatorUserId(active, name);
    if (activeMatch !== null) {
      resolved.set(name, { userId: activeMatch, status: 'active' });
      continue;
    }
    const inactiveMatch = matchOperatorUserId(inactive, name);
    resolved.set(name, {
      userId: null,
      status: inactiveMatch === null ? 'missing' : 'inactive',
    });
  }
  return resolved;
}
