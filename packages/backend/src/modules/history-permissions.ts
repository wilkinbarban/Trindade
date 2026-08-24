export interface HistoryActor {
  sub: number;
  role: string;
}

export interface OwnableHistoryRow {
  user_id: number | null;
  created_at: string;
  is_active?: number | boolean | null;
}

const EDIT_WINDOW_MS = 60 * 60 * 1000;

function parseSqliteDate(value: string): Date {
  const iso = value.includes('T') ? value : value.replace(' ', 'T');
  return new Date(iso.endsWith('Z') || /[+-]\d{2}:?\d{2}$/.test(iso) ? iso : `${iso}Z`);
}

export function isAdmin(actor: HistoryActor): boolean {
  return actor.role === 'Administrador';
}

export function isWithinOneHour(createdAt: string, now: Date = new Date()): boolean {
  const created = parseSqliteDate(createdAt);
  if (Number.isNaN(created.getTime())) return false;
  const age = now.getTime() - created.getTime();
  return age >= 0 && age < EDIT_WINDOW_MS;
}

export function projectHistoryPermissions(actor: HistoryActor, row: OwnableHistoryRow) {
  const active = row.is_active === undefined || row.is_active === null ? true : Boolean(row.is_active);
  const canEdit = active && row.user_id === actor.sub && isWithinOneHour(row.created_at);
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
