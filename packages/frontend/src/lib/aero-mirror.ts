import {
  aeroBridgeCallbackFor,
  aeroSettingsReady,
  buildAeroBridgeEnvelope,
  buildAeroBridgeTriggerUrl,
  buildMovimentoItemPayload,
  type AeroBridgeEnvelope,
  type AeroErpMovementItem,
  type AeroSettings,
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
export interface AeroMovementsSyncOptions {
  /** The day to ask for. */
  date: string;
  /** Where the shortcut comes back; the page the operator was on. */
  callbackPath: string;
  /** How the shortcut is launched. The page assigns `location.href`; a test records the URL instead. */
  navigate?: (url: string) => void;
}

/**
 * Asks the company for a day of movements, or says it cannot.
 *
 * The report has one sync button for both blocks, so the wiring lives here once: the button, the receipts
 * section and the launch screen all fire the same envelope, and the caller learns whether anything was
 * launched. A device without company access gets `false` and refreshes what it already read instead of
 * showing a spinner for a request that never started.
 */
export function triggerAeroMovementsSync(settings: AeroSettings, options: AeroMovementsSyncOptions): boolean {
  if (!aeroSettingsReady(settings)) return false;
  const envelope = aeroMovementsEnvelope({
    wsUrl: settings.wsUrl,
    username: settings.username,
    password: settings.password,
    date: options.date,
    userName: null,
    callbackUrl: aeroBridgeCallbackFor('movimentos', options.callbackPath),
  });
  const url = buildAeroBridgeTriggerUrl({ name: settings.shortcutName, envelope });
  if (options.navigate) {
    options.navigate(url);
  } else {
    window.location.href = url;
  }
  return true;
}

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
    endpoint: 'movimentoitem?_method=PATCH',
    payload: {
      _method: 'PATCH',
      ...buildMovimentoItemPayload({
        userName: args.userName ?? null,
        startDate: args.date,
        endDate: args.date,
        entrySaida: 'E',
      }),
    },
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
      // Stored rows carry it; a snapshot written before the field existed answers null, which reads as
      // "unknown owner" and keeps the import button away from a worker until the backend is asked.
      ownerUserId:
        typeof record.owner_user_id === 'number' && Number.isInteger(record.owner_user_id)
          ? record.owner_user_id
          : null,
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

/**
 * The hour the header shows under the sync button.
 *
 * The snapshot carries the moment the phone stored it, not the moment Delphi answered, so this line says when
 * this day was read and never pretends the ERP agreed. A factory reads its own clock: the timestamp is
 * converted to the plant's zone, and a phone that never synced gets a dash instead of a made-up time.
 */
export function lastSyncLabel(syncedAt: string | null, timeZone?: string): string {
  if (!syncedAt) return '—';
  const date = new Date(syncedAt);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone,
  }).format(date);
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
 * The seats this tab is about.
 *
 * The bridge asks the ERP for the whole day, and the day carries more than what the plant produced: a box of
 * packaging, bags of flour and straws arrive through the same endpoint. Those are supplies, they belong to
 * Recebimento, and a screen that shows them without an import button is a screen telling the operator about a
 * problem it cannot solve.
 *
 * The catalogue is the filter, not the direction: a mass that left the plant is still a mass, and this tab
 * shows what the company recorded so the launch can be found here.
 */
export function aeroMirrorSeats(
  movements: readonly AeroErpMovementItem[],
  products: readonly AeroImportProduct[],
): AeroErpMovementItem[] {
  const codes = new Set(products.map((product) => importCodeKey(product.code)));
  if (codes.size === 0) return [];
  return movements.filter((movement) => codes.has(importCodeKey(movement.productCode)));
}

/**
 * Decides whether an ERP seat can become a Trindade launch, and in what boxes.
 *
 * The mirror is read-only, so "import" always means a new local launch, never an edit of the company's
 * record. Three things must hold: the seat is an entry (a stock exit is not production), the plant has the
 * product, and the units fit whole boxes -- a launch here is counted in boxes, and inventing a fraction
 * would record something the factory never counted.
 */
/**
 * The body the import sends: the boxes, plus the identity of the seat they came from.
 *
 * The seat and its operator are what make the imported launch pair with the company's record; a launch
 * without them belongs to whoever pressed the button and can only be matched by name, which is a guess
 * between two people.
 */
export function aeroImportPayload(
  movement: Pick<AeroErpMovementItem, 'entLan' | 'operatorName'>,
  plan: AeroImportPlan,
  date: string,
): {
  date: string;
  ent_lan: number;
  operator_name: string;
  items: { product_code: string; boxes: number }[];
} {
  return {
    date,
    ent_lan: movement.entLan,
    operator_name: movement.operatorName.trim(),
    items: [{ product_code: plan.productCode, boxes: plan.boxes }],
  };
}

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
