import type Database from 'better-sqlite3';
import { aeroMovementsTableExists, listAeroMovements, type AeroMovement } from '../integration/aero.movements.service.js';
import { MASSAS } from '../stock/stock.catalog.js';

/**
 * The report's Recebimento section, derived from the ERP snapshot the mirror already keeps.
 *
 * There is deliberately no `receipt_items` table. The seats belong to Aero32, `aero_movements` already
 * holds the whole day, and copying them into a report would create a second answer that can drift from
 * the first. A report therefore points at a date, and this module answers what the plant received that
 * day -- today, yesterday, or a month later, from the same rows.
 */

/**
 * The report category whose content is not a checklist: `Recebimento`.
 *
 * The id is fixed because it is reference data seeded by `reference-data.ts` and no screen renames or
 * reorders it. Everything else in the report stays a checkbox; only this section reads the ERP.
 */
export const ERP_RECEIPT_CATEGORY_ID = 4;

/** One incoming line as the report shows it: the document, the product, how much and who brought it. */
export interface ReceiptLine {
  entLan: number;
  productCode: string;
  productDesc: string;
  quantity: number;
  operatorName: string;
  entryDate: string;
}

export interface DayReceipts {
  date: string;
  /** Whether the mirror table exists at all, so the page can say "run db:migrate" instead of "nothing came". */
  migrated: boolean;
  /** When Trindade last heard from the ERP for this day; null means the day was never consulted. */
  syncedAt: string | null;
  receipts: ReceiptLine[];
}

/**
 * The ERP pads product codes to six digits (`000084`), the catalogue writes them without padding (`84`).
 * The zeros are padding, not identity, so both sides are compared stripped -- the same rule
 * `importCodeKey` applies in the SPA.
 */
export function erpCodeKey(code: string): string {
  return code.trim().replace(/^0+/, '') || '0';
}

const PRODUCED_CODES: ReadonlySet<string> = new Set(MASSAS.map((product) => erpCodeKey(product.code)));

/**
 * The incoming supplies of one day: stock entries that are not a massa the plant produces.
 *
 * A stock exit is the plant sending product out, not receiving it. A massa entry is production coming
 * back into stock, and it already has its own screen. Everything else -- flour, boxes, film, salt, or a
 * third-party product the plant resells -- is a receipt.
 */
export function supplyReceipts(movements: readonly AeroMovement[]): ReceiptLine[] {
  return movements
    .filter((movement) => movement.movement_type === 'E')
    .filter((movement) => !PRODUCED_CODES.has(erpCodeKey(movement.product_code)))
    .map((movement) => ({
      entLan: movement.ent_lan,
      productCode: movement.product_code,
      productDesc: movement.product_desc,
      quantity: movement.quantity,
      operatorName: movement.operator_name,
      entryDate: movement.entry_date,
    }));
}

/**
 * One day's receipts from Trindade's own copy of the ERP answer.
 *
 * `syncedAt` is derived from every mirrored line, not only the supplies: a day whose snapshot carried
 * massas alone was consulted and has nothing to receive, which is a different answer from a day nobody
 * asked about. The newest stamp wins because a later sync is what the plant is looking at.
 */
export function dayReceipts(db: Database.Database, date: string): DayReceipts {
  if (!aeroMovementsTableExists(db)) {
    return { date, migrated: false, syncedAt: null, receipts: [] };
  }

  const movements = listAeroMovements(db, date);
  const syncedAt = movements.reduce<string | null>(
    (newest, movement) => (newest === null || movement.synced_at > newest ? movement.synced_at : newest),
    null,
  );

  return { date, migrated: true, syncedAt, receipts: supplyReceipts(movements) };
}
