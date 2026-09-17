import { getSaoPauloDateString } from '../utils/date.js';

export interface HistoryActor {
  sub: number;
  role: string;
}

export interface OwnableHistoryRow {
  user_id: number | null;
  created_at: string;
  is_active?: number | boolean | null;
}

/**
 * Which edit window a permissions projection applies.
 *
 * Reports and loading schedules use different windows, and this projection is otherwise identical
 * for both. The choice is therefore an argument, and a required one: a default would let a new
 * call site inherit whichever rule happened to be the default, and nothing would be able to tell
 * the difference afterwards.
 */
export type EditWindow = 'one-hour' | 'sao-paulo-current-and-previous-day';

const ONE_HOUR_MS = 60 * 60 * 1000;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

function parseSqliteDate(value: string): Date {
  const iso = value.includes('T') ? value : value.replace(' ', 'T');
  return new Date(iso.endsWith('Z') || /[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`);
}

export function isAdmin(actor: HistoryActor): boolean {
  return actor.role === 'Administrador';
}

/**
 * The loading schedule window: the creator may edit during the first hour after creation.
 *
 * Named for the window rather than for reports or loading, because it is the readable statement of
 * what it checks and loading is its only caller.
 */
export function isWithinOneHour(createdAt: string, now: Date = new Date()): boolean {
  const created = parseSqliteDate(createdAt);
  if (Number.isNaN(created.getTime())) return false;
  const age = now.getTime() - created.getTime();
  return age >= 0 && age < ONE_HOUR_MS;
}

/**
 * The reports window: the creator may edit while the report's São Paulo date is today or yesterday.
 *
 * Compared as São Paulo date strings rather than as elapsed time, because `created_at` is stored in
 * UTC: a report created at 22:00 in São Paulo is already 01:00 UTC the next day, and an elapsed-time
 * comparison anchored on the wrong day would disagree with the date the operator sees.
 */
export function isWithinReportsEditWindow(createdAt: string, now: Date = new Date()): boolean {
  const created = parseSqliteDate(createdAt);
  if (Number.isNaN(created.getTime())) return false;

  // A future timestamp falls on today's date, so without this guard it would read as editable and
  // the fail-closed expectation for future rows would pass for the wrong reason.
  if (created.getTime() > now.getTime()) return false;

  const today = getSaoPauloDateString(now);
  // Anchored at midday so subtracting a day can never cross two date boundaries.
  const yesterday = getSaoPauloDateString(new Date(new Date(`${today}T12:00:00Z`).getTime() - ONE_DAY_MS));
  const createdDay = getSaoPauloDateString(created);

  return createdDay === today || createdDay === yesterday;
}

export function isWithinEditWindow(
  window: EditWindow,
  createdAt: string,
  now: Date = new Date(),
): boolean {
  return window === 'one-hour'
    ? isWithinOneHour(createdAt, now)
    : isWithinReportsEditWindow(createdAt, now);
}

export function projectHistoryPermissions(
  actor: HistoryActor,
  row: OwnableHistoryRow,
  window: EditWindow,
  now: Date = new Date(),
) {
  const active = row.is_active === undefined || row.is_active === null ? true : Boolean(row.is_active);
  const canEdit = active && row.user_id === actor.sub && isWithinEditWindow(window, row.created_at, now);
  const canDeactivate = isAdmin(actor) && active;
  const canDelete = actor.role === 'Administrador' || actor.role === 'Trabalhador';

  return {
    isActive: active,
    readOnly: !canEdit,
    canEdit,
    canDeactivate,
    canDelete,
  };
}
