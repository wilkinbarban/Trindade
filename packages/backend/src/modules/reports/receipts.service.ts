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

/**
 * The day Recebimento stopped being a checklist.
 *
 * A report dated before this keeps its own ticks and prints them: rewriting what the plant already sent to
 * the group would be inventing history. A report from this day on answers from the snapshot, and when the
 * day was never mirrored it says that instead of printing fifteen unticked tasks.
 */
export const ERP_RECEIPT_SINCE = '2026-10-29';

/** Whether a report dated `date` takes its Recebimento section from the ERP mirror. */
export function usesErpReceipts(date: string): boolean {
  return date >= ERP_RECEIPT_SINCE;
}

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
 * The one rule both lenses share: an incoming entry is a supply when it is not a massa the plant produces.
 *
 * A stock exit is neither: it is the plant sending product out, so it belongs to no lens and must not
 * inflate the count of a day that only received.
 */
function isSupplyReceipt(movement: Pick<AeroMovement, 'movement_type' | 'product_code'>): boolean {
  return movement.movement_type === 'E' && !PRODUCED_CODES.has(erpCodeKey(movement.product_code));
}

/** A day's seats split by the lens each screen counts: the plant's massas, and everything it received. */
export interface MovementLenses {
  massas: number;
  insumos: number;
}

/**
 * Counts the day as each module reads it.
 *
 * Both screens store the whole snapshot; what changes is the lens. The masses screen only celebrates what
 * the plant produced, and the report only counts what came in from outside -- counting all seats in both
 * would tell the operator that eleven boxes arrived when one did.
 */
export function movementLenses(
  movements: readonly Pick<AeroMovement, 'movement_type' | 'product_code'>[],
): MovementLenses {
  const lenses: MovementLenses = { massas: 0, insumos: 0 };
  for (const movement of movements) {
    if (isSupplyReceipt(movement)) lenses.insumos += 1;
    else if (movement.movement_type === 'E') lenses.massas += 1;
  }
  return lenses;
}

/**
 * The incoming supplies of one day: stock entries that are not a massa the plant produces.
 *
 * A stock exit is the plant sending product out, not receiving it. A massa entry is production coming
 * back into stock, and it already has its own screen. Everything else -- flour, boxes, film, salt, or a
 * third-party product the plant resells -- is a receipt.
 */
export function supplyReceipts(movements: readonly AeroMovement[]): ReceiptLine[] {
  return movements
    .filter(isSupplyReceipt)
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
