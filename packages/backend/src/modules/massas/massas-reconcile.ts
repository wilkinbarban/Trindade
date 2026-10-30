/**
 * The matching rule between what Trindade launched and what the Delphi tablet recorded.
 *
 * The two sides describe the same boxes from different ends: the app stores a launch per operator and
 * product, the ERP answers one seat per document line. This module is the one place that decides when both
 * describe the same boxes, and it works without a database so the rule can be tested as a table.
 */

/** The minimum a launch has to expose to be reconciled. */
export interface MassasReconcilableLaunch {
  id: number;
  launch_date: string;
  product_code: string;
  boxes: number;
  units: number;
  username: string;
  display_name: string;
  /** The ERP seat this launch was imported from, when it came from one. */
  ent_lan?: number | null;
}

/** The minimum a seat has to expose; `AeroMovement` already satisfies it. */
export interface MassasReconcilableSeat {
  ent_lan: number;
  product_code: string;
  quantity: number;
  entry_date: string;
  operator_name: string;
  movement_type: 'E' | 'S';
}

export interface MassasReconciliation {
  reconciledEntLans: number[];
  matchedLaunchIds: number[];
  unmatchedLaunchIds: number[];
}

/** Both sides pad the code differently, so the comparison drops the leading zeros. */
export function codeKey(code: string): string {
  return String(code).trim().replace(/^0+/, '') || '0';
}

/** Operators are compared the way a person reads them: case and surrounding space are noise. */
export function operatorKey(value: string | null | undefined): string {
  return String(value ?? '').trim().toUpperCase();
}

function roundUnits(value: number): number {
  return Math.round(Number(value) * 1000) / 1000;
}

/**
 * The same boxes written as one comparable string: the day, the code without padding, the units and who
 * recorded them.
 *
 * The operator is part of the identity, not a detail: two operators can meaningfully record the same
 * quantity of the same product on the same day, and pairing one launch with the other's seat would mark
 * the wrong person as reconciled.
 */
function boxesKey(date: string, code: string, units: number, operator: string): string {
  return [String(date).slice(0, 10), codeKey(code), roundUnits(units), operatorKey(operator)].join('|');
}

/**
 * Every name a launch can be recognised by on the tablet.
 *
 * `ENT_USU` is typed on a Delphi screen, where the operator writes their own name; Trindade's login is a
 * short username. A seat therefore has to be able to answer to either, or the day looks half reconciled
 * while it is only half typed.
 */
function launchKeys(launch: MassasReconcilableLaunch): string[] {
  return [
    boxesKey(launch.launch_date, launch.product_code, launch.units, launch.username),
    boxesKey(launch.launch_date, launch.product_code, launch.units, launch.display_name),
  ];
}

function seatKey(seat: MassasReconcilableSeat): string {
  return boxesKey(seat.entry_date, seat.product_code, seat.quantity, seat.operator_name);
}

/**
 * Pairs the ERP's seats with the launches Trindade already has.
 *
 * Each launch reconciles at most one seat and is consumed in order, so two identical seats mean the
 * operator loaded twice and the second one keeps looking for a launch of its own. A stock exit never
 * reconciles: this side records entries only.
 *
 * The seat a launch was imported from decides first, without looking at the name: `ENT_LAN` is the same
 * row of the company's book, and the launch exists because an operator pressed Importar on it. The name
 * pass is what remains, for the launches the plant typed here.
 *
 * A launch answers to both its username and its display name, because the name typed on the tablet is the
 * person's name and not the login they use here.
 */
export function reconcileMassasDay(
  launches: readonly MassasReconcilableLaunch[],
  seats: readonly MassasReconcilableSeat[],
): MassasReconciliation {
  const consumed = new Set<number>();
  const reconciledEntLans: number[] = [];
  const matchedLaunchIds: number[] = [];

  // First pass: the seat a launch was imported from is not a guess, it is the same row of paper on both
  // sides. It pairs regardless of who the launch belongs to, which is the only way an import made by one
  // operator can settle against a seat recorded by another -- and that mismatch is why the import button
  // used to come back on every reload.
  const bySeat = new Map<number, MassasReconcilableLaunch>();
  for (const launch of launches) {
    if (launch.ent_lan !== null && launch.ent_lan !== undefined) bySeat.set(launch.ent_lan, launch);
  }

  for (const seat of seats) {
    if (seat.movement_type !== 'E') continue;
    const launch = bySeat.get(seat.ent_lan);
    if (!launch || consumed.has(launch.id)) continue;
    consumed.add(launch.id);
    reconciledEntLans.push(seat.ent_lan);
    matchedLaunchIds.push(launch.id);
  }

  // Second pass: launches typed by hand have no seat, so they keep matching on what both sides wrote --
  // day, product, units and operator.
  const byKey = new Map<string, MassasReconcilableLaunch[]>();
  for (const launch of launches) {
    if (consumed.has(launch.id)) continue;
    for (const key of launchKeys(launch)) {
      const list = byKey.get(key);
      if (list) list.push(launch);
      else byKey.set(key, [launch]);
    }
  }

  const reconciledSets = new Set(reconciledEntLans);
  for (const seat of seats) {
    if (seat.movement_type !== 'E') continue;
    if (reconciledSets.has(seat.ent_lan)) continue;
    const candidates = byKey.get(seatKey(seat));
    const launch = candidates?.find((candidate) => !consumed.has(candidate.id));
    if (!launch) continue;
    consumed.add(launch.id);
    reconciledEntLans.push(seat.ent_lan);
    matchedLaunchIds.push(launch.id);
  }

  const matched = new Set(matchedLaunchIds);
  return {
    reconciledEntLans,
    matchedLaunchIds,
    unmatchedLaunchIds: launches.filter((launch) => !matched.has(launch.id)).map((launch) => launch.id),
  };
}
