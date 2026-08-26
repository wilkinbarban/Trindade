import type Database from 'better-sqlite3';

/**
 * Generador Inteligente — WhatsApp text export engine.
 *
 * Generates a structured, emoji-enhanced report message in Brazilian Portuguese (pt-BR)
 * using `name_pt` fields from the database. The output is designed for direct
 * copy-paste into WhatsApp.
 */

interface ExportRow {
  id: number;
  turno: string;
  report_date: string;
  notes: string | null;
  created_at: string;
  display_name: string;
}

interface ExportItemRow {
  task_name: string;
  task_type: string;
  category_name: string;
  checked: number;
}

interface ExportTemperatureRow {
  location: string;
  reading_index: number;
  value: number;
}

function formatTemperature(location: string, values: number[]): string {
  const readings = values.map((value) => `*${value}°C*`).join(' / ');
  return `🌡️ ${location}: ${readings}`;
}

function groupTemperatures(temperatures: ExportTemperatureRow[]): Map<string, ExportTemperatureRow[]> {
  const grouped = new Map<string, ExportTemperatureRow[]>();
  for (const temperature of temperatures) {
    grouped.set(temperature.location, [...(grouped.get(temperature.location) ?? []), temperature]);
  }
  return grouped;
}

const HEADER_TARDE = '🌅 *RELATÓRIO — TURNO DA TARDE*';
const HEADER_NOITE = '🌙 *RELATÓRIO — TURNO DA NOITE*';

export function generateWhatsAppText(db: Database.Database, reportId: number, publicBaseUrl = ''): string | null {
  const missingReference = db.prepare(
    `SELECT ri.id, ri.task_id
     FROM report_items ri
     LEFT JOIN report_tasks rt ON rt.id = ri.task_id
     LEFT JOIN report_categories rc ON rc.id = rt.category_id
     WHERE ri.report_id = ? AND (rt.id IS NULL OR rc.id IS NULL)
     LIMIT 1`
  ).get(reportId) as { id: number; task_id: number } | undefined;
  if (missingReference) {
    throw new Error(`Missing required report reference: item ${missingReference.id}, task ${missingReference.task_id}`);
  }

  const report = db
    .prepare(
      `SELECT r.id, r.turno, r.report_date, r.notes, r.created_at, u.display_name
       FROM reports r
       LEFT JOIN users u ON r.user_id = u.id
       WHERE r.id = ?`
    )
    .get(reportId) as ExportRow | undefined;

  if (!report) return null;
  if (!report.display_name) throw new Error(`Missing required report reference: user for report ${reportId}`);

  const lines: string[] = [];

  // --- Header ---
  const header = report.turno === 'tarde' ? HEADER_TARDE : HEADER_NOITE;
  lines.push(header);
  lines.push('');

  // --- Metadata ---
  const [year, month, day] = report.report_date.split('-');
  lines.push(`📅 *Data:* ${day}/${month}/${year}`);
  lines.push(`👤 *Responsável:* ${report.display_name}`);
  lines.push('');

  // --- Categories & Tasks ---
  const categories = db
    .prepare(
      `SELECT DISTINCT rc.id, rc.name_pt, rc.sort_order
       FROM report_categories rc
       JOIN report_tasks rt ON rc.id = rt.category_id
       JOIN report_items ri ON rt.id = ri.task_id AND ri.report_id = ?
       ORDER BY rc.sort_order`
    )
    .all(reportId) as { id: number; name_pt: string; sort_order: number }[];

  // Also include categories from temperatures
  const tempCategories = db
    .prepare(
      `SELECT DISTINCT rc.id, rc.name_pt, rc.sort_order
       FROM report_categories rc
       JOIN report_tasks rt ON rc.id = rt.category_id
       JOIN report_temperatures rtemp ON rtemp.location = rt.name_pt AND rtemp.report_id = ?
       WHERE rc.category_type = 'temperature'
       ORDER BY rc.sort_order`
    )
    .all(reportId) as { id: number; name_pt: string; sort_order: number }[];

  // Merge all relevant category IDs
  const relevantIds = new Set<number>();
  for (const c of categories) relevantIds.add(c.id);
  for (const c of tempCategories) relevantIds.add(c.id);

  // Fetch actual categories sorted
  const allCategories = db
    .prepare(
      `SELECT id, name_pt, sort_order
       FROM report_categories
       ORDER BY sort_order`
    )
    .all() as { id: number; name_pt: string; sort_order: number }[];

  const activeCategories = allCategories.filter((c) => relevantIds.has(c.id));

  const printedTempLocations = new Set<string>();

  for (const cat of activeCategories) {
    lines.push(`━━━━━━━━━━━━━━`);
    lines.push(`📋 *${cat.name_pt.toUpperCase()}*`);
    lines.push('');

    // --- Checks ---
    const items = db
      .prepare(
        `SELECT rt.name_pt AS task_name, rc.category_type AS task_type,
                rc.name_pt AS category_name, ri.checked, ri.selected_products
         FROM report_items ri
         JOIN report_tasks rt ON ri.task_id = rt.id
         JOIN report_categories rc ON rt.category_id = rc.id
         WHERE rc.id = ? AND ri.report_id = ?
           AND rc.category_type IN ('check', 'check_assai', 'check_normal')
         ORDER BY rt.id ASC`
      )
      .all(cat.id, reportId) as (ExportItemRow & { task_type: string; selected_products?: string })[];

    for (const item of items) {
      if (item.task_type === 'check_assai' || item.task_type === 'check_normal') {
        const selected = item.selected_products ? JSON.parse(item.selected_products) : [];
        if (selected && selected.length > 0) {
          lines.push(`✅ ${item.task_name}:`);
          for (const p of selected) {
            lines.push(`   - ${p}`);
          }
        } else {
          lines.push(`⬜ ${item.task_name}`);
        }
      } else {
        const icon = item.checked ? '✅' : '⬜';
        lines.push(`${icon} ${item.task_name}`);
      }
    }

    // --- Temperatures ---
    const temps = db
      .prepare(
        `SELECT rt_temp.location, rt_temp.reading_index, rt_temp.value
         FROM report_temperatures rt_temp
         JOIN report_tasks rt ON rt_temp.location = rt.name_pt
         JOIN report_categories rc ON rt.category_id = rc.id
         WHERE rc.id = ? AND rt_temp.report_id = ?
           AND rc.category_type = 'temperature'
          ORDER BY rt.id ASC, rt_temp.reading_index ASC`
      )
      .all(cat.id, reportId) as ExportTemperatureRow[];

    for (const [location, readings] of groupTemperatures(temps)) {
      lines.push(formatTemperature(location, readings.map((temp) => temp.value)));
      printedTempLocations.add(location);
    }

    lines.push('');
  }

  // Check for any temperatures in the database that were not printed
  const allTemps = db
    .prepare(
      `SELECT location, reading_index, value
       FROM report_temperatures
       WHERE report_id = ?`
    )
    .all(reportId) as ExportTemperatureRow[];

  const unprintedTemps = allTemps.filter((t) => !printedTempLocations.has(t.location));

  if (unprintedTemps.length > 0) {
    lines.push(`━━━━━━━━━━━━━━`);
    lines.push(`📋 *OUTROS*`);
    lines.push('');
    for (const [location, readings] of groupTemperatures(unprintedTemps)) {
      lines.push(formatTemperature(location, readings.map((temp) => temp.value)));
    }
    lines.push('');
  }

  // --- Notes ---
  const userNotes = report.notes ? report.notes.trim() : '';
  const warnings: string[] = [];
  for (const t of allTemps) {
    if (/c[âáa]mara|fr[ií]a/i.test(t.location)) {
      if (t.value > 8) {
        warnings.push(`A ${t.location} tem uma temperatura de ${t.value}°C, recomenda-se revisar os ares-condicionados, os produtos podem estragar.`);
      } else if (t.value > 4) {
        warnings.push(`A ${t.location} tem uma temperatura de ${t.value}°C, recomenda-se limpar os ares-condicionados.`);
      }
    }
  }

  if (userNotes || warnings.length > 0) {
    lines.push(`📝 *Observações:*`);
    if (userNotes) {
      lines.push(userNotes);
    }
    if (warnings.length > 0) {
      if (userNotes) {
        lines.push('');
      }
      warnings.forEach((w) => lines.push(w));
    }
    lines.push('');
  }


  // --- Photo links ---
  const photos = db
    .prepare(
      `SELECT public_token
       FROM report_photos
       WHERE report_id = ?
       ORDER BY created_at ASC`
    )
    .all(reportId) as { public_token: string }[];

  if (photos.length > 0) {
    const base = publicBaseUrl.replace(/\/$/, '');
    lines.push(`📸 *FOTOS:*`);
    photos.forEach((photo, index) => {
      const path = `/p/${photo.public_token}`;
      lines.push(`${index + 1}. ${base ? `${base}${path}` : path}`);
    });
    lines.push('');
  }

  // --- Footer ---
  lines.push('━━━━━━━━━━━━━━');
  lines.push(`📌 _Trindade Massas — ${day}/${month}/${year}_`);

  return lines.join('\n');
}
