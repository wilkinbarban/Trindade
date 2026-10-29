/**
 * The report's Recebimento section, read from the ERP mirror.
 *
 * The server owns the classification (which entry is a supply, which day was consulted); this module only
 * carries the shape and the one date rule both layers have to agree on. Keeping the cutover here, next to
 * the reader, means a page never has to know how a receipt is decided.
 */

/** `Recebimento`, the category whose content is the mirror instead of a checklist. */
export const ERP_RECEIPT_CATEGORY_ID = 4;

/**
 * The day Recebimento switched from a checklist to the mirror. Reports dated before it keep their ticks:
 * the plant's history is not rewritten by a later feature. Mirrors `ERP_RECEIPT_SINCE` in the backend.
 */
export const ERP_RECEIPT_SINCE = '2026-10-29';

/** Whether a report dated `date` takes its Recebimento section from the mirror. */
export function usesErpReceipts(date: string): boolean {
  return date >= ERP_RECEIPT_SINCE;
}

export interface ReportReceiptLine {
  entLan: number;
  productCode: string;
  productDesc: string;
  quantity: number;
  operatorName: string;
  entryDate: string;
}

export interface ReportReceiptsDay {
  date: string;
  /** False only when the mirror table is missing, which the page reports as "run the migration". */
  migrated: boolean;
  /** When Trindade last heard from the ERP for this day; null means nobody asked. */
  syncedAt: string | null;
  receipts: ReportReceiptLine[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isReceiptLine(value: unknown): value is ReportReceiptLine {
  if (!isRecord(value)) return false;
  return (
    typeof value.entLan === 'number' &&
    typeof value.productCode === 'string' &&
    typeof value.productDesc === 'string' &&
    typeof value.quantity === 'number' &&
    typeof value.operatorName === 'string' &&
    typeof value.entryDate === 'string'
  );
}

/**
 * Reads the answer the route sends, refusing anything it cannot describe.
 *
 * A malformed body must not reach the screen as a half-filled list: the page would show a receipt with no
 * quantity or drop a line without saying so. `null` means "unknown answer", which the section reports as an
 * error instead of as a day with nothing received.
 */
export function readReportReceiptsDay(body: unknown): ReportReceiptsDay | null {
  if (!isRecord(body)) return null;
  if (typeof body.date !== 'string') return null;
  if (typeof body.migrated !== 'boolean') return null;
  if (body.syncedAt !== null && typeof body.syncedAt !== 'string') return null;
  if (!Array.isArray(body.receipts)) return null;
  if (!body.receipts.every(isReceiptLine)) return null;

  return {
    date: body.date,
    migrated: body.migrated,
    syncedAt: body.syncedAt,
    receipts: body.receipts,
  };
}
