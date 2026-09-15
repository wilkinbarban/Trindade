import type Database from 'better-sqlite3';
import type { CreateReportBody, UpdateReportBody, ReportDetail } from './reports.schema.js';
import type { HistoryActor } from '../history-permissions.js';
import { projectHistoryPermissions } from '../history-permissions.js';
import { translateText } from '../../utils/translator.js';
import { detectTurno, getReport } from './reports.query.service.js';
import { getSaoPauloDateString } from '../../utils/date.js';

const SPANISH_NOTE_MARKERS = [
  /\b(observaci[oó]n|observaciones|area|área|camara|cámara|fr[ií]a|harina|basura|caja|cajas|cart[oó]n|queso|pan de ajo)\b/i,
  /\b(se\s+realiz[oó]|realizado|todo\s+bien|sin\s+novedad|pendiente|limpieza|organizaci[oó]n)\b/i,
  /\b(el|la|los|las|un|una|unos|unas|del|de la|en las|en los|para la|por la)\b/i,
];

async function normalizeReportNotesToPortuguese(notes: string | null | undefined): Promise<string | null> {
  if (notes === undefined || notes === null) return notes ?? null;

  const cleaned = notes.trim();
  if (!cleaned) return null;

  const looksSpanish = SPANISH_NOTE_MARKERS.some((pattern) => pattern.test(cleaned));
  if (!looksSpanish) return cleaned;

  const translated = await translateText(cleaned, 'es');
  return translated.trim() || cleaned;
}

interface TemperatureTaskConfig {
  name_pt: string;
  temperature_readings: number;
}

function validateTemperatures(db: Database.Database, temperatures: CreateReportBody['temperatures']): void {
  const expectedTasks = db
    .prepare(
      `SELECT rt.name_pt, rt.temperature_readings
       FROM report_tasks rt
       JOIN report_categories rc ON rt.category_id = rc.id
       WHERE rc.category_type = 'temperature' AND rc.is_active = 1 AND rt.is_active = 1`
    )
    .all() as TemperatureTaskConfig[];
  const expected = new Set(expectedTasks.flatMap((task) =>
    Array.from({ length: task.temperature_readings }, (_, index) => `${task.name_pt}:${index + 1}`)
  ));
  const provided = temperatures.map((temp) => `${temp.location}:${temp.readingIndex}`);
  if (provided.length !== expected.size || new Set(provided).size !== provided.length || provided.some((key) => !expected.has(key))) {
    throw Object.assign(new Error('Falta colocar as temperaturas no relatório.'), { statusCode: 400 });
  }
}

export async function createReport(
  db: Database.Database,
  userId: number,
  body: CreateReportBody
): Promise<ReportDetail> {
  validateTemperatures(db, body.temperatures ?? []);

  const turno = body.turno || detectTurno();
  const reportDate = getSaoPauloDateString();

  const existingReport = db
    .prepare(
      `SELECT id FROM reports WHERE report_date = ? AND turno = ? AND is_active = 1`
    )
    .get(reportDate, turno);

  if (existingReport) {
    throw Object.assign(
      new Error('reportAlreadyExists'),
      { statusCode: 400 }
    );
  }

  const notes = await normalizeReportNotesToPortuguese(body.notes);

  const insertReport = db.prepare(
    `INSERT INTO reports (user_id, turno, report_date, notes)
     VALUES (?, ?, ?, ?)`
  );

  const insertItem = db.prepare(
    `INSERT INTO report_items (report_id, task_id, checked, selected_products)
     VALUES (?, ?, ?, ?)`
  );

  const insertTemperature = db.prepare(
    `INSERT INTO report_temperatures (report_id, location, reading_index, value)
      VALUES (?, ?, ?, ?)`
  );

  const transaction = db.transaction(() => {
    const result = insertReport.run(userId, turno, reportDate, notes);
    const reportId = result.lastInsertRowid as number;

    for (const item of body.items ?? []) {
      const selectedStr = item.selectedProducts ? JSON.stringify(item.selectedProducts) : null;
      insertItem.run(reportId, item.taskId, item.checked ? 1 : 0, selectedStr);
    }

    for (const temp of body.temperatures ?? []) {
      insertTemperature.run(reportId, temp.location, temp.readingIndex, temp.value);
    }

    return reportId;
  });

  let reportId: number;
  try {
    reportId = transaction();
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Violação de restrição de banco de dados (ID inválido ou duplicado).'), { statusCode: 400 });
    }
    throw err;
  }
  return getReport(db, reportId)!;
}
export async function updateReport(
  db: Database.Database,
  reportId: number,
  body: UpdateReportBody,
  actor: HistoryActor
): Promise<ReportDetail | { error: string; status: number }> {
  // Check report exists
  const report = db
    .prepare('SELECT id, user_id, created_at, is_active FROM reports WHERE id = ?')
    .get(reportId) as { id: number; user_id: number; created_at: string; is_active: number } | undefined;

  if (!report) {
    return { error: 'Report not found', status: 404 };
  }

  const permissions = projectHistoryPermissions(actor, report);
  if (!permissions.canEdit) {
    return { error: 'Registro somente leitura. Apenas o criador pode editar durante a primeira hora.', status: 403 };
  }

  if (body.temperatures !== undefined) {
    try {
      validateTemperatures(db, body.temperatures);
    } catch (error) {
      return { error: error instanceof Error ? error.message : 'Falta colocar as temperaturas no relatório.', status: 400 };
    }
  }

  const notes = body.notes !== undefined
    ? await normalizeReportNotesToPortuguese(body.notes)
    : undefined;

  // Wrap ALL updates (fields + items + temperatures + quantities)
  // in a single database transaction for atomicity.
  const tx = db.transaction(() => {
    // Update report-level fields
    const updates: string[] = [];
    const params: unknown[] = [];

    if (body.turno !== undefined) {
      updates.push('turno = ?');
      params.push(body.turno);
    }
    if (body.notes !== undefined) {
      updates.push('notes = ?');
      params.push(notes);
    }

    if (updates.length > 0) {
      params.push(reportId);
      db.prepare(`UPDATE reports SET ${updates.join(', ')} WHERE id = ?`).run(...params);
    }

    // Update updated_at unconditionally in the transaction
    db.prepare("UPDATE reports SET updated_at = datetime('now') WHERE id = ?").run(reportId);

    // Update items if provided
    if (body.items !== undefined) {
      db.prepare('DELETE FROM report_items WHERE report_id = ?').run(reportId);
      const insertItem = db.prepare(
        'INSERT INTO report_items (report_id, task_id, checked, selected_products) VALUES (?, ?, ?, ?)'
      );
      for (const item of body.items) {
        const selectedStr = item.selectedProducts ? JSON.stringify(item.selectedProducts) : null;
        insertItem.run(reportId, item.taskId, item.checked ? 1 : 0, selectedStr);
      }
    }

    // Update temperatures if provided
    if (body.temperatures !== undefined) {
      db.prepare('DELETE FROM report_temperatures WHERE report_id = ?').run(reportId);
      const insertTemp = db.prepare(
        'INSERT INTO report_temperatures (report_id, location, reading_index, value) VALUES (?, ?, ?, ?)'
      );
      for (const temp of body.temperatures) {
        insertTemp.run(reportId, temp.location, temp.readingIndex, temp.value);
      }
    }
  });

  try {
    tx();
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      return { error: 'Violação de restrição de banco de dados (ID inválido ou duplicado).', status: 400 };
    }
    throw err;
  }

  return getReport(db, reportId)!;
}
