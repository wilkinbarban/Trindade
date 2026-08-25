import type Database from 'better-sqlite3';

interface ExportScheduleRow {
  id: number;
  time_slot: string;
  driver_type: 'casa' | 'fletero';
  driver_name: string | null;
  vehicle_plate: string | null;
}

const HEADER = '🚛 *CRONOGRAMA DE CARREGAMENTO*';
const SEPARATOR = '━━━━━━━━━━━━━━';

const WEEKDAYS = [
  'Domingo',
  'Segunda-feira',
  'Terça-feira',
  'Quarta-feira',
  'Quinta-feira',
  'Sexta-feira',
  'Sábado',
];

const CLOCK_EMOJIS: Record<string, string> = {
  '04:00': '🕓',
  '04:30': '🕟',
  '05:00': '🕔',
  '05:30': '🕠',
  '06:00': '🕕',
  '06:30': '🕡',
  '07:00': '🕖',
};

function nextLoadingDate(batchDate: string): Date {
  const [year, month, day] = batchDate.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  const utcDay = date.getUTCDay();
  if (utcDay === 5) { // Friday
    date.setUTCDate(date.getUTCDate() + 3); // Next Monday
  } else {
    date.setUTCDate(date.getUTCDate() + 1);
  }
  return date;
}

function formatPtBrDate(date: Date): string {
  const weekday = WEEKDAYS[date.getUTCDay()];
  const day = String(date.getUTCDate()).padStart(2, '0');
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const year = date.getUTCFullYear();
  return `${weekday} • ${day}/${month}/${year}`;
}

function formatRow(row: ExportScheduleRow): string {
  const emoji = CLOCK_EMOJIS[row.time_slot] ?? '🕒';
  const name = row.driver_name || '-';
  const vehicle = row.driver_type === 'casa' && row.vehicle_plate ? ` 🚚 ${row.vehicle_plate}` : '';
  return `${emoji} ${row.time_slot}  │ ${name}${vehicle}`;
}

export function generateWhatsAppText(db: Database.Database, date: string): string {
  const invalid = db.prepare(
    `SELECT ls.id,
            CASE
              WHEN ls.user_id IS NULL OR u.id IS NULL THEN 'user'
              WHEN ls.driver_id IS NULL OR d.id IS NULL THEN 'driver'
              WHEN ls.driver_type = 'casa' AND (ls.vehicle_id IS NULL OR v.id IS NULL) THEN 'vehicle'
            END AS reference
     FROM loading_schedules ls
     LEFT JOIN users u ON u.id = ls.user_id
     LEFT JOIN drivers d ON d.id = ls.driver_id
     LEFT JOIN vehicles v ON v.id = ls.vehicle_id
     WHERE ls.schedule_date = ? AND ls.is_active = 1
       AND (ls.user_id IS NULL OR u.id IS NULL OR ls.driver_id IS NULL OR d.id IS NULL
         OR (ls.driver_type = 'casa' AND (ls.vehicle_id IS NULL OR v.id IS NULL)))
     LIMIT 1`
  ).get(date) as { id: number; reference: string } | undefined;
  if (invalid) throw new Error(`Missing required loading reference: ${invalid.reference} for schedule ${invalid.id}`);

  const creatorRow = db
    .prepare(
      `SELECT u.display_name
       FROM loading_schedules ls
       JOIN users u ON ls.user_id = u.id
       WHERE ls.schedule_date = ? AND ls.user_id IS NOT NULL
       ORDER BY ls.created_at ASC, ls.id ASC
       LIMIT 1`
    )
    .get(date) as { display_name: string } | undefined;
  const responsibleName = creatorRow?.display_name || 'Desconhecido';

  const rows = db
    .prepare(
      `SELECT ls.id, ls.time_slot,
              ls.driver_type,
              d.name AS driver_name,
              v.license_plate AS vehicle_plate
       FROM loading_schedules ls
       LEFT JOIN drivers d ON ls.driver_id = d.id
       LEFT JOIN vehicles v ON ls.vehicle_id = v.id
       WHERE ls.schedule_date = ? AND ls.is_active = 1
       ORDER BY ls.time_slot ASC, ls.id ASC`
    )
    .all(date) as ExportScheduleRow[];

  const lines: string[] = [
    HEADER,
    '',
    `📅 ${formatPtBrDate(nextLoadingDate(date))}`,
    `👤 *Responsável:* ${responsibleName}`,
    '',
    SEPARATOR,
    '',
  ];

  if (rows.length === 0) {
    lines.push('_Nenhum carregamento agendado para esta data._');
  } else {
    lines.push(...rows.map(formatRow));
  }

  lines.push(
    '',
    SEPARATOR,
    '',
    `📌 Total de carregamentos: *${rows.length}*`,
    '✅ Bom trabalho a todos!',
    '',
    '🏭 Trindade Massas — Sistema de Carregamento'
  );

  return lines.join('\n');
}
