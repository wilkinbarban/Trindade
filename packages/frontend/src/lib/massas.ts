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
 * Formats WhatsApp message for massas launches showing strictly total units
 * per product (for launched products) and the list of unique operators.
 */
export function massasMessage(view: MassasLaunchDayView): string {
  const lines: string[] = [`🍝 *Lançamento de Massas — ${brazilianDate(view.date)}*`, ''];

  const launchedProducts = view.products.filter((p) => p.totalUnits > 0);

  if (launchedProducts.length === 0) {
    lines.push('Nenhum lançamento registrado até agora.');
    return lines.join('\n');
  }

  for (const product of launchedProducts) {
    lines.push(`• ${product.name}: ${product.totalUnits} un`);
  }

  // Collect unique operator names from launches
  const uniqueOperators = Array.from(
    new Set(
      (view.launches || [])
        .map((l) => (l.display_name && l.display_name.trim()) || (l.username && l.username.trim()))
        .filter((name): name is string => Boolean(name))
    )
  );

  if (uniqueOperators.length > 0) {
    lines.push('');
    lines.push(`*Lançado por:* ${uniqueOperators.join(', ')}`);
  }

  return lines.join('\n');
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
    .sort((a, b) => a.name.localeCompare(b.name));
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


