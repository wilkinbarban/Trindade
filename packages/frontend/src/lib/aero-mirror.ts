import {
  buildAeroBridgeEnvelope,
  buildMovimentoItemPayload,
  type AeroBridgeEnvelope,
  type AeroErpMovementItem,
} from './aero-integration';

/**
 * The mirror, end to end: what to ask the company for, and how to read what came back.
 *
 * The app keeps the primitives in `aero-integration.ts`; this module is the one feature built on them, so
 * the page only calls what is here and never assembles an envelope of its own.
 */

/** The bank of the day as the plant reads it, either straight from the ERP or from the stored snapshot. */
export interface AeroMovementSnapshot {
  date: string;
  movements: AeroErpMovementItem[];
  /** When Trindade last heard from the ERP, or `null` for a day nobody synced yet. */
  syncedAt: string | null;
}

/**
 * The envelope that asks Aero32 for the day's movements through the one bridge.
 *
 * `userName` absent is the ordinary case: the plant reads the whole day, because the boxes are the
 * factory's and any operator may have launched them. `ENTSAI: 'E'` because Trindade only records entries;
 * a stock exit is the company's business and has nothing here to reconcile against.
 */
export function aeroMovementsEnvelope(args: {
  wsUrl: string;
  username: string;
  password: string;
  date: string;
  userName?: string | null;
  callbackUrl: string;
}): AeroBridgeEnvelope {
  return buildAeroBridgeEnvelope({
    wsUrl: args.wsUrl,
    username: args.username,
    password: args.password,
    endpoint: 'movimentoitem',
    payload: buildMovimentoItemPayload({
      userName: args.userName ?? null,
      startDate: args.date,
      endDate: args.date,
      entrySaida: 'E',
    }),
    callbackUrl: args.callbackUrl,
  });
}

function snapshotText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function snapshotDate(value: unknown): string | null {
  const text = snapshotText(value);
  return text && /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : null;
}

/** One stored row, read strictly: the mirror never invents a seat the backend did not store. */
function readStoredSeat(raw: unknown): { item: AeroErpMovementItem; syncedAt: string | null } | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const record = raw as Record<string, unknown>;

  const entLan = record.ent_lan;
  const quantity = record.quantity;
  const productCode = snapshotText(record.product_code);
  const productDesc = snapshotText(record.product_desc);
  const entryDate = snapshotDate(record.entry_date);
  const operatorName = snapshotText(record.operator_name);
  const movementType = record.movement_type;

  if (typeof entLan !== 'number' || !Number.isInteger(entLan) || entLan <= 0) return null;
  if (typeof quantity !== 'number' || !Number.isFinite(quantity) || quantity < 0) return null;
  if (!productCode || !productDesc || !entryDate || !operatorName) return null;
  if (movementType !== 'E' && movementType !== 'S') return null;

  return {
    item: {
      entLan,
      productCode,
      productDesc,
      quantity,
      entryDate,
      operatorName: operatorName.toUpperCase(),
      movementType,
    },
    syncedAt: snapshotText(record.synced_at),
  };
}

/**
 * Reads the snapshot the backend serves for one day.
 *
 * A body that is not the snapshot this module asked for is refused (`null`), because "no answer" and "an
 * empty day" are different things for the plant: the first one is a bug to show, the second one is a day
 * nobody launched yet. A single unreadable row is dropped instead of hiding the rest.
 */
export function readAeroMovementsSnapshot(body: unknown): AeroMovementSnapshot | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const record = body as Record<string, unknown>;

  const date = snapshotDate(record.date);
  if (!date || !Array.isArray(record.movements)) return null;

  const movements: AeroErpMovementItem[] = [];
  let syncedAt: string | null = null;
  for (const raw of record.movements) {
    const seat = readStoredSeat(raw);
    if (!seat) continue;
    movements.push(seat.item);
    if (seat.syncedAt && (!syncedAt || seat.syncedAt > syncedAt)) {
      syncedAt = seat.syncedAt;
    }
  }

  return { date, movements, syncedAt };
}

/** The catalog entry a launch is recorded against, as the page already holds it. */
export interface AeroImportProduct {
  code: string;
  name: string;
  unitsPerBox: number;
}

/** What an import would send, before the page posts it. */
export interface AeroImportPlan {
  /** The plant's own code, which is the one a local launch records. */
  productCode: string;
  boxes: number;
}

/** Both sides write the same code with different padding. */
function importCodeKey(code: string): string {
  return String(code).trim().replace(/^0+/, '') || '0';
}

/**
 * Decides whether an ERP seat can become a Trindade launch, and in what boxes.
 *
 * The mirror is read-only, so "import" always means a new local launch, never an edit of the company's
 * record. Three things must hold: the seat is an entry (a stock exit is not production), the plant has the
 * product, and the units fit whole boxes -- a launch here is counted in boxes, and inventing a fraction
 * would record something the factory never counted.
 */
export function aeroImportPlan(
  movement: AeroErpMovementItem,
  products: readonly AeroImportProduct[],
): AeroImportPlan | null {
  if (movement.movementType !== 'E') return null;

  const key = importCodeKey(movement.productCode);
  const product = products.find((candidate) => importCodeKey(candidate.code) === key);
  if (!product || !Number.isInteger(product.unitsPerBox) || product.unitsPerBox <= 0) return null;

  if (!Number.isInteger(movement.quantity) || movement.quantity <= 0) return null;
  if (movement.quantity % product.unitsPerBox !== 0) return null;

  return { productCode: product.code, boxes: movement.quantity / product.unitsPerBox };
}
