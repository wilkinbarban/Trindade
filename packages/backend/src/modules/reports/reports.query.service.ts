import type Database from 'better-sqlite3';
import type { CategoryResponse, TaskResponse, ReportQuery, ReportListItem, ReportDetail, ReportItemDetail, ReportTemperatureDetail, TaskType } from './reports.schema.js';
import { parseSelectedProducts, TaskTypeSchema } from './reports.schema.js';
import { projectHistoryPermissions, type HistoryActor } from '../history-permissions.js';
import { getSaoPauloDateString } from '../../utils/date.js';

// ---- Turno Detection ----

/**
 * Auto-detect the shift turn based on current server time.
 * 06:00–17:59 → 'tarde' | 18:00–05:59 → 'noite'
 */
export function detectTurno(): 'tarde' | 'noite' {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    hour: 'numeric',
    minute: 'numeric',
    hour12: false,
  });
  const timeStr = formatter.format(new Date());
  const [hour, minute] = timeStr.split(':').map((s) => parseInt(s, 10));
  const minutes = hour * 60 + minute;
  const cutoffTarde = 18 * 60 + 30; // 18:30
  const cutoffNoite = 6 * 60;       // 06:00
  if (minutes >= cutoffNoite && minutes < cutoffTarde) {
    return 'noite';
  }
  return 'tarde';
}

// ---- Reference Data Queries ----

export function getCategories(db: Database.Database): CategoryResponse[] {
  const categories = db
    .prepare(
      `SELECT id, parent_category_id, name_pt, name_es, sort_order, category_type
       FROM report_categories
       WHERE is_active = 1
       ORDER BY COALESCE(parent_category_id, id), sort_order, id`
    )
    .all() as CategoryResponse[];

  const selectTasks = db.prepare(
      `SELECT rt.id, rt.category_id, rt.name_pt, rt.name_es, rt.temperature_readings, rc.category_type AS task_type
     FROM report_tasks rt
     JOIN report_categories rc ON rt.category_id = rc.id
     WHERE rt.category_id = ? AND rt.is_active = 1
     ORDER BY rt.id ASC`
  );

  return categories.map((cat) => {
    const tasks = selectTasks.all(cat.id) as TaskResponse[];
    return { ...cat, tasks };
  });
}
interface ReportItemRow {
  task_id: number;
  task_name: string;
  task_name_es: string;
  task_type: string;
  category_name: string;
  category_name_es: string;
  checked: number;
  selected_products: string | null;
}

/**
 * Narrow a stored category type to the enum the response declares.
 *
 * The column carries a CHECK constraint over these same four values, so a mismatch means the
 * database was changed outside this application. There is no honest fallback: every alternative
 * would misreport which element type the task is, and the response schema admits only the four. So
 * this fails loudly rather than substituting one, and the enum in `ReportItemDetailSchema` is what
 * a contract test proves against real rows.
 */
function narrowTaskType(value: string): TaskType {
  const parsed = TaskTypeSchema.safeParse(value);
  if (!parsed.success) {
    throw new Error(`report_items row carries an unknown task type: ${value}`);
  }
  return parsed.data;
}

interface ReportRow {
  id: number;
  user_id: number;
  turno: string;
  report_date: string;
  notes: string | null;
  created_at: string;
  updated_at: string;
  display_name: string;
  is_active: number;
}
export function getReport(
  db: Database.Database,
  reportId: number,
  actor?: HistoryActor
): ReportDetail | null {
  const row = db
    .prepare(
      `SELECT r.id, r.user_id, r.turno, r.report_date, r.notes,
              r.created_at, r.updated_at, r.is_active, u.display_name
       FROM reports r
       JOIN users u ON r.user_id = u.id
       WHERE r.id = ?`
    )
    .get(reportId) as ReportRow | undefined;

  if (!row) return null;

  return enrichReport(db, row, actor);
}

export function listReports(
  db: Database.Database,
  query: ReportQuery
): ReportListItem[] {
  let dateFilter: string;
  const params: string[] = [];
  const today = getSaoPauloDateString();
  const todayDate = new Date(`${today}T12:00:00Z`);

  switch (query.period) {
    case 'today':
      dateFilter = `r.report_date = ?`;
      params.push(today);
      break;
    case 'yesterday': {
      const yesterday = getSaoPauloDateString(new Date(todayDate.getTime() - 24 * 60 * 60 * 1000));
      dateFilter = `r.report_date = ?`;
      params.push(yesterday);
      break;
    }
    case '7days': {
      const days7Ago = getSaoPauloDateString(new Date(todayDate.getTime() - 7 * 24 * 60 * 60 * 1000));
      dateFilter = `r.report_date >= ?`;
      params.push(days7Ago);
      break;
    }
    case '30days': {
      const days30Ago = getSaoPauloDateString(new Date(todayDate.getTime() - 30 * 24 * 60 * 60 * 1000));
      dateFilter = `r.report_date >= ?`;
      params.push(days30Ago);
      break;
    }
    case 'custom':
      if (query.from && query.to) {
        dateFilter = `r.report_date >= ? AND r.report_date <= ?`;
        params.push(query.from, query.to);
      } else {
        dateFilter = `r.report_date = ?`;
        params.push(today);
      }
      break;
    default:
      dateFilter = `r.report_date = ?`;
      params.push(today);
  }

  const sql = `
    SELECT r.id, r.user_id, r.turno, r.report_date, r.notes,
           r.created_at, r.updated_at, u.display_name
    FROM reports r
    JOIN users u ON r.user_id = u.id
    WHERE ${dateFilter}
    ORDER BY r.report_date DESC, r.created_at DESC
  `;

  const rows = db.prepare(sql).all(...params) as ReportRow[];

  return rows.map((r) => ({
    id: r.id,
    turno: r.turno as 'tarde' | 'noite',
    report_date: r.report_date,
    notes: r.notes,
    created_at: r.created_at,
    user: {
      id: r.user_id,
      display_name: r.display_name,
    },
  }));
}
// ---- Internal helpers ----

function enrichReport(db: Database.Database, row: ReportRow, actor?: HistoryActor): ReportDetail {
  const itemRows = db
    .prepare(
      `SELECT ri.task_id, rt.name_pt AS task_name, rt.name_es AS task_name_es, rc.category_type AS task_type,
              rc.name_pt AS category_name, rc.name_es AS category_name_es, ri.checked, ri.selected_products
       FROM report_items ri
       JOIN report_tasks rt ON ri.task_id = rt.id
       JOIN report_categories rc ON rt.category_id = rc.id
       WHERE ri.report_id = ?
       ORDER BY rc.sort_order ASC, rt.id ASC`
    )
    .all(row.id) as ReportItemRow[];

  const items: ReportItemDetail[] = itemRows.map((r) => ({
    task_id: r.task_id,
    task_name: r.task_name,
    task_name_es: r.task_name_es,
    task_type: narrowTaskType(r.task_type),
    category_name: r.category_name,
    category_name_es: r.category_name_es,
    checked: Boolean(r.checked),
    selectedProducts: parseSelectedProducts(r.selected_products),
  }));

  const temperatures = db
    .prepare(
      `SELECT rt_temp.location, rt_temp.reading_index AS readingIndex, rt_temp.value, rt.name_es AS location_es
       FROM report_temperatures rt_temp
       LEFT JOIN report_tasks rt ON rt_temp.location = rt.name_pt
       LEFT JOIN report_categories rc ON rt.category_id = rc.id AND rc.category_type = 'temperature'
        WHERE rt_temp.report_id = ?
        ORDER BY rc.sort_order ASC, rt.id ASC, rt_temp.reading_index ASC`
    )
    .all(row.id) as ReportTemperatureDetail[];

  const permissions = actor ? projectHistoryPermissions(actor, row, 'sao-paulo-current-and-previous-day') : undefined;

  return {
    id: row.id,
    turno: row.turno as 'tarde' | 'noite',
    report_date: row.report_date,
    notes: row.notes,
    created_at: row.created_at,
    updated_at: row.updated_at,
    ...(permissions ?? {}),
    user: {
      id: row.user_id,
      display_name: row.display_name,
    },
    items,
    temperatures,
  };
}
