import { aeroImportPlan, aeroMirrorSeats, type AeroImportProduct } from './aero-mirror';
import { reconcileAeroMovements, type AeroErpMovementItem } from './aero-integration';
import type { MassaIndividualLaunch } from './massas';

/** The three things a row can be, and the only three. */
export type MassasRowState = 'reconciled' | 'pendingErp' | 'erpOnly';

/** One line of the plant's day, no matter which screen wrote it. */
export interface MassasUnifiedRow {
  /** Stable across reloads: the launch id when there is one, the seat's `ENT_LAN` when there is not. */
  key: string;
  state: MassasRowState;
  operatorName: string;
  /** Lowercased, so the worker filter and the grouping agree. */
  operatorKey: string;
  productCode: string;
  productName: string;
  boxes: number;
  units: number;
  launchIds: number[];
  entLans: number[];
  /** True only for `erpOnly` seats that fit whole boxes; the import action exists for these. */
  importable: boolean;
  /** True when the day and the device already said "carregado na tablet" for a pending row. */
  tabletAcked: boolean;
}

export interface MassasUnifiedInput {
  launches: readonly MassaIndividualLaunch[];
  /** The ERP's day, exactly as the bridge stored it. */
  movements: readonly AeroErpMovementItem[];
  /** The plant's catalogue, used both to filter supplies out and to know `unitsPerBox`. */
  products: readonly AeroImportProduct[];
  /** Launch ids already acknowledged on this device for this day. */
  ackedLaunchIds?: readonly number[];
}

/** What importing a seat sends: the seat, its product, the whole boxes it fits and the day it belongs to. */
export interface AeroImportRequest {
  entLan: number;
  productCode: string;
  boxes: number;
  date: string;
}

/** The names an operator answers to, lowercased for a filter a person types. */
function operatorNames(launch: MassaIndividualLaunch): string[] {
  return [launch.display_name, launch.username]
    .map((name) => String(name ?? '').trim().toLowerCase())
    .filter(Boolean);
}

/** The catalogue entry for a code, tolerating the padding both sides use differently. */
function productFor(products: readonly AeroImportProduct[], code: string): AeroImportProduct | null {
  const digits = String(code).trim().replace(/^0+/, '') || '0';
  return (
    products.find((product) => (String(product.code).trim().replace(/^0+/, '') || '0') === digits) ?? null
  );
}

const STATE_ORDER: Record<MassasRowState, number> = {
  // What the plant still owes Delphi comes first: it is the only state that asks for an action here.
  pendingErp: 0,
  erpOnly: 1,
  reconciled: 2,
};

/**
 * The plant's day as one table.
 *
 * Two screens record the same boxes, and the table cannot show both lists: a box launched here and recorded
 * on the tablet would appear twice and the operator would not know which line to trust. So the day is
 * reconciled once and each box is one row with its real state:
 *
 * - `reconciled`: launched here and already in the ERP.
 * - `pendingErp`: launched here and not in the ERP yet.
 * - `erpOnly`: recorded on the company tablet and never launched here; importable when it fits whole boxes.
 *
 * A stock exit never becomes a row: it is product leaving the plant, not production.
 */
export function massasUnifiedRows(
  input: MassasUnifiedInput,
  operatorFilter = '',
): MassasUnifiedRow[] {
  const { launches, movements, products, ackedLaunchIds = [] } = input;
  const seats = aeroMirrorSeats(movements, products);
  const reconciliation = reconcileAeroMovements(seats, launches);
  const matchedLaunchIds = new Set(reconciliation.matchedLaunchIds);
  const reconciledEntLans = new Set(reconciliation.reconciled);
  const acked = new Set(ackedLaunchIds);
  const filter = operatorFilter.trim().toLowerCase();

  const rows: MassasUnifiedRow[] = [];

  for (const launch of launches) {
    if (launch.boxes <= 0) continue;
    const names = operatorNames(launch);
    // The filter is universal: it looks at every name the operator uses, on both sides of the day.
    if (filter && !names.some((name) => name.includes(filter))) continue;
    const reconciled = matchedLaunchIds.has(launch.id);
    rows.push({
      key: `launch-${launch.id}`,
      state: reconciled ? 'reconciled' : 'pendingErp',
      operatorName: (launch.display_name || launch.username || 'Operador').trim(),
      operatorKey: (launch.display_name || launch.username || 'operador').trim().toLowerCase(),
      productCode: launch.product_code,
      productName: launch.product_name,
      boxes: launch.boxes,
      units: launch.units,
      launchIds: [launch.id],
      // The reconciliation matches counts, not pairs: two identical seats are interchangeable, so naming one
      // as this launch's seat would be a guess. The row says it is paired and leaves the id to the ERP side.
      entLans: [],
      importable: false,
      tabletAcked: !reconciled && acked.has(launch.id),
    });
  }

  for (const seat of seats) {
    if (reconciledEntLans.has(seat.entLan)) continue;
    if (seat.movementType !== 'E') continue;
    const product = productFor(products, seat.productCode);
    if (!product) continue;
    const operator = seat.operatorName.trim();
    if (filter && !operator.toLowerCase().includes(filter)) continue;
    const plan = aeroImportPlan(seat, products);
    rows.push({
      key: `seat-${seat.entLan}`,
      state: 'erpOnly',
      operatorName: operator,
      operatorKey: operator.toLowerCase(),
      productCode: product.code,
      productName: product.name,
      boxes: product.unitsPerBox > 0 ? seat.quantity / product.unitsPerBox : 0,
      units: seat.quantity,
      launchIds: [],
      entLans: [seat.entLan],
      importable: plan !== null,
      tabletAcked: false,
    });
  }

  return rows.sort(
    (a, b) =>
      STATE_ORDER[a.state] - STATE_ORDER[b.state] ||
      a.operatorName.localeCompare(b.operatorName, 'pt-BR') ||
      a.productName.localeCompare(b.productName, 'pt-BR'),
  );
}

/**
 * The launch ids this phone already carried to the tablet on that day.
 *
 * The mark is per device on purpose: it says "I loaded these boxes", not "the ERP has them". It dies with the
 * day, because a new shift carries new boxes, and it is stored outside the database so acknowledging never
 * looks like a launch.
 */
export function ackedLaunchIdsKey(date: string): string {
  return `aero_tablet_acked_${date}`;
}

export function loadAckedLaunchIds(date: string): number[] {
  try {
    const raw = window.localStorage.getItem(ackedLaunchIdsKey(date));
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id): id is number => Number.isInteger(id)) : [];
  } catch {
    return [];
  }
}

/** Adds or removes one id and returns the new list, so the caller can render without re-reading. */
export function toggleAckedLaunchId(date: string, launchId: number): number[] {
  const current = loadAckedLaunchIds(date);
  const next = current.includes(launchId) ? current.filter((id) => id !== launchId) : [...current, launchId];
  try {
    window.localStorage.setItem(ackedLaunchIdsKey(date), JSON.stringify(next));
  } catch {
    // A phone with storage disabled still gets the answer for this render; only the memory is lost.
  }
  return next;
}
