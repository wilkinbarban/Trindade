export interface MassaProductTotal {
  code: string;
  name: string;
  unitsPerBox: number;
  totalBoxes: number;
  totalUnits: number;
}

export interface MassaIndividualLaunch {
  id: number;
  launch_date: string;
  product_code: string;
  product_name: string;
  boxes: number;
  units: number;
  user_id: number;
  username: string;
  display_name: string;
  created_at: string;
  updated_at: string;
}

export interface MassasLaunchDayView {
  date: string;
  products: MassaProductTotal[];
  launches: MassaIndividualLaunch[];
  totalBoxes: number;
  totalUnits: number;
}

export function saopauloToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

export function brazilianDate(date: string): string {
  const [year, month, day] = date.split('-');
  return year && month && day ? `${day}/${month}/${year}` : date;
}

/**
 * Formats WhatsApp message for massas launches: the collective summary of the day
 * plus a per-worker breakdown, with the redundant "MASSA" prefix removed from names.
 */
export function massasMessage(view: MassasLaunchDayView): string {
  const lines: string[] = [`🍝 *Lançamento de Massas — ${brazilianDate(view.date)}*`, ''];

  const launchedProducts = view.products.filter((p) => p.totalUnits > 0);

  if (launchedProducts.length === 0) {
    lines.push('Nenhum lançamento registrado até agora.');
    return lines.join('\n');
  }

  lines.push('*Resumo Coletivo:*');
  for (const product of launchedProducts) {
    lines.push(
      `• ${stripMassasPrefix(product.name)}: ${product.totalUnits} un (${product.totalBoxes} cx)`
    );
  }

  const workers = buildWorkerTotals(view);
  if (workers.length > 0) {
    lines.push('');
    lines.push('*Por Trabalhador:*');
    workers.forEach((worker, index) => {
      if (index > 0) lines.push('');
      lines.push(`👤 *${worker.name}:*`);
      for (const product of worker.products) {
        lines.push(
          `• ${stripMassasPrefix(product.name)}: ${product.totalUnits} un (${product.totalBoxes} cx)`
        );
      }
    });
  }

  return lines.join('\n');
}

/**
 * Removes the leading "MASSA" (or "MASSA DE") prefix from a product name,
 * so the message reads "CASEIRA" instead of "MASSA CASEIRA".
 */
export function stripMassasPrefix(name: string): string {
  return name
    .trim()
    .replace(/^MASSA\s+(?:DE\s+)?/i, '')
    .trim();
}

/**
 * Locale-independent name comparison, matching Kotlin's `compareBy { it.lowercase() }` so the web and
 * Android builds order workers the same way even when a name starts with an accented letter.
 */
export function compareWorkerNames(a: string, b: string): number {
  const left = a.toLowerCase();
  const right = b.toLowerCase();
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

export interface MassaWorkerProductLine {
  code: string;
  name: string;
  totalBoxes: number;
  totalUnits: number;
}

export interface MassaWorkerTotals {
  userId: number;
  name: string;
  products: MassaWorkerProductLine[];
}

function sortByCatalogOrder<T extends { code: string; name: string }>(
  items: T[],
  catalog: { code: string }[]
): T[] {
  const order = new Map(catalog.map((p, index) => [p.code, index]));
  return [...items].sort((a, b) => {
    const left = order.get(a.code) ?? Number.MAX_SAFE_INTEGER;
    const right = order.get(b.code) ?? Number.MAX_SAFE_INTEGER;
    return left === right ? compareWorkerNames(a.name, b.name) : left - right;
  });
}

/**
 * Consolidates launches per product, using the catalog only as the source of
 * names, units per box and display order. This is what makes the collective
 * summary follow the worker filter instead of the server-wide aggregate.
 */
export function groupProductsFromLaunches(
  launches: MassaIndividualLaunch[],
  catalog: MassaProductTotal[]
): MassaProductTotal[] {
  const byCode = new Map<string, MassaProductTotal>();

  for (const launch of launches) {
    const meta = catalog.find((p) => p.code === launch.product_code);
    const entry = byCode.get(launch.product_code) ?? {
      code: launch.product_code,
      name: meta?.name ?? launch.product_name,
      unitsPerBox:
        meta?.unitsPerBox ?? (launch.boxes > 0 ? Math.round(launch.units / launch.boxes) : 0),
      totalBoxes: 0,
      totalUnits: 0,
    };
    entry.totalBoxes += launch.boxes;
    entry.totalUnits += launch.units;
    byCode.set(launch.product_code, entry);
  }

  return sortByCatalogOrder(
    Array.from(byCode.values()).filter((p) => p.totalUnits > 0),
    catalog
  );
}

/**
 * Lists the distinct workers who launched a given product, sorted by name.
 */
export function productWorkerNames(
  launches: MassaIndividualLaunch[],
  productCode: string
): string[] {
  const names = new Set<string>();
  for (const launch of launches) {
    if (launch.product_code !== productCode) continue;
    const name = (launch.display_name || launch.username || '').trim();
    if (name) names.add(name);
  }
  return Array.from(names).sort(compareWorkerNames);
}

/**
 * Builds the per-worker breakdown of the day, with products in catalog order.
 */
export function buildWorkerTotals(view: MassasLaunchDayView): MassaWorkerTotals[] {
  const byUser = new Map<number, { name: string; products: Map<string, MassaWorkerProductLine> }>();

  for (const launch of view.launches || []) {
    const name = (launch.display_name || launch.username || `Operador ${launch.user_id}`).trim();
    const entry = byUser.get(launch.user_id) ?? { name, products: new Map() };
    if (name) entry.name = name;

    const line = entry.products.get(launch.product_code) ?? {
      code: launch.product_code,
      name: launch.product_name,
      totalBoxes: 0,
      totalUnits: 0,
    };
    line.totalBoxes += launch.boxes;
    line.totalUnits += launch.units;
    entry.products.set(launch.product_code, line);
    byUser.set(launch.user_id, entry);
  }

  return Array.from(byUser.entries())
    .map(([userId, entry]) => ({
      userId,
      name: entry.name,
      products: sortByCatalogOrder(
        Array.from(entry.products.values()).filter((p) => p.totalUnits > 0),
        view.products
      ),
    }))
    .filter((worker) => worker.products.length > 0)
    .sort((a, b) => compareWorkerNames(a.name, b.name));
}

/**
 * Passive polling interval in milliseconds for live-reload across floor devices.
 */
export const POLL_INTERVAL_MS = 15000;

export interface MassasPollingGuards {
  visibilityState: DocumentVisibilityState | string;
  isEditing: boolean;
  isSubmitting: boolean;
  isToday?: boolean;
}

/**
 * Guard predicate determining if silent background polling should run.
 * Suspends when document is hidden (background tab/screen off), when
 * the operator is actively editing or submitting, or on past dates.
 */
export function shouldPollMassas({
  visibilityState,
  isEditing,
  isSubmitting,
  isToday = true,
}: MassasPollingGuards): boolean {
  if (isToday === false) return false;
  if (visibilityState !== 'visible') return false;
  if (isEditing) return false;
  if (isSubmitting) return false;
  return true;
}

export interface MassaOperator {
  id: number;
  name: string;
}

/**
 * Extracts the unique operators who registered launches on a date, sorted by name.
 */
export function extractOperators(launches: MassaIndividualLaunch[]): MassaOperator[] {
  const map = new Map<number, string>();
  for (const launch of launches) {
    if (!map.has(launch.user_id)) {
      const name = (launch.display_name || launch.username || `Operador ${launch.user_id}`).trim();
      map.set(launch.user_id, name);
    }
  }
  return Array.from(map.entries())
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => compareWorkerNames(a.name, b.name));
}

/**
 * Filters individual launches by operator ID, or returns all launches when operatorId is 'all'.
 */
export function filterLaunchesByOperator(
  launches: MassaIndividualLaunch[],
  operatorId: number | 'all'
): MassaIndividualLaunch[] {
  if (operatorId === 'all') return launches;
  return launches.filter((l) => l.user_id === operatorId);
}

/**
 * Calculates total boxes and units for a selected operator, or null when 'all' is selected.
 */
export function calculateOperatorSubtotal(
  launches: MassaIndividualLaunch[],
  operatorId: number | 'all'
): { boxes: number; units: number } | null {
  if (operatorId === 'all') return null;
  const filtered = filterLaunchesByOperator(launches, operatorId);
  return {
    boxes: filtered.reduce((acc, l) => acc + l.boxes, 0),
    units: filtered.reduce((acc, l) => acc + l.units, 0),
  };
}


