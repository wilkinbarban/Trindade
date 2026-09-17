import type Database from 'better-sqlite3';
import { existsSync, unlinkSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { join } from 'node:path';
import type { HistoryQuery, HistoryPagination, ReportListItem, ReportPhoto } from './reports.schema.js';
import { projectHistoryPermissions, type HistoryActor } from '../history-permissions.js';

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


export type { ReportPhoto };

export const MAX_REPORT_PHOTOS = 5;

function photoUrl(photoId: number): string {
  return `/api/reports/photos/${photoId}`;
}

function publicPhotoUrl(token: string, publicBaseUrl?: string): string {
  const path = `/p/${token}`;
  return publicBaseUrl ? `${publicBaseUrl.replace(/\/$/, '')}${path}` : path;
}

function withPhotoUrls(photo: ReportPhoto, publicBaseUrl?: string): ReportPhoto {
  return {
    ...photo,
    url: photoUrl(photo.id),
    publicUrl: publicPhotoUrl(photo.public_token, publicBaseUrl),
  };
}

export function savePhoto(
  db: Database.Database,
  reportId: number,
  filePath: string,
  fileSize: number,
  mimeType: string,
  actor: HistoryActor
): ReportPhoto {
  const permissions = getReportPermissions(db, reportId, actor);
  if (!permissions?.canEdit) {
    throw Object.assign(new Error('Registro somente leitura. Fotos só podem ser alteradas pelo criador durante a primeira hora.'), { status: 403 });
  }

  const currentCount = countPhotosByReport(db, reportId);
  if (currentCount >= MAX_REPORT_PHOTOS) {
    throw Object.assign(new Error('Limite de 5 fotos por relatório atingido.'), { status: 409 });
  }

  const result = db
    .prepare(
      `INSERT INTO report_photos (report_id, file_path, file_size, mime_type, public_token)
       VALUES (?, ?, ?, ?, ?)`
    )
    .run(reportId, filePath, fileSize, mimeType, randomBytes(6).toString('hex'));

  return getPhotoById(db, result.lastInsertRowid as number)!;
}

export function getPhotosByReport(
  db: Database.Database,
  reportId: number,
  publicBaseUrl?: string
): ReportPhoto[] {
  const rows = db
    .prepare(
      `SELECT id, report_id, file_path, file_size, mime_type, public_token, created_at
       FROM report_photos
       WHERE report_id = ?
       ORDER BY created_at DESC`
    )
    .all(reportId) as ReportPhoto[];

  return rows.map((photo) => withPhotoUrls(photo, publicBaseUrl));
}

export function countPhotosByReport(db: Database.Database, reportId: number): number {
  return (db
    .prepare('SELECT COUNT(*) AS count FROM report_photos WHERE report_id = ?')
    .get(reportId) as { count: number }).count;
}

export function getPhotoById(
  db: Database.Database,
  photoId: number
): ReportPhoto | null {
  const row = db
    .prepare(
      `SELECT id, report_id, file_path, file_size, mime_type, public_token, created_at
       FROM report_photos
       WHERE id = ?`
    )
    .get(photoId) as ReportPhoto | undefined;

  return row ? withPhotoUrls(row) : null;
}

export function getPhotoByPublicToken(
  db: Database.Database,
  token: string
): ReportPhoto | null {
  const row = db
    .prepare(
      `SELECT id, report_id, file_path, file_size, mime_type, public_token, created_at
       FROM report_photos
       WHERE public_token = ?`
    )
    .get(token) as ReportPhoto | undefined;

  return row ? withPhotoUrls(row) : null;
}

export function deletePhoto(
  db: Database.Database,
  photoId: number,
  actor: HistoryActor
): ReportPhoto | null | { error: string; status: number } {
  const photo = getPhotoById(db, photoId);
  if (!photo) return null;

  const permissions = getReportPermissions(db, photo.report_id, actor);
  if (!permissions?.canEdit) {
    return { error: 'Registro somente leitura. Fotos só podem ser alteradas pelo criador durante a primeira hora.', status: 403 };
  }

  db.prepare('DELETE FROM report_photos WHERE id = ?').run(photoId);
  return photo;
}

/**
 * Check if a report's edit window has expired.
 * Reports can only be modified on the current or previous day.
 */
export function getReportPermissions(db: Database.Database, reportId: number, actor: HistoryActor) {
  const report = db
    .prepare('SELECT user_id, created_at, is_active FROM reports WHERE id = ?')
    .get(reportId) as { user_id: number; created_at: string; is_active: number } | undefined;
  if (!report) return null;
  return projectHistoryPermissions(actor, report);
}

export function isReportReadOnly(db: Database.Database, reportId: number, actor: HistoryActor): boolean {
  const permissions = getReportPermissions(db, reportId, actor);
  return !permissions || permissions.readOnly;
}

export function deactivateReport(db: Database.Database, reportId: number): boolean {
  const result = db.prepare("UPDATE reports SET is_active = 0, updated_at = datetime('now') WHERE id = ?").run(reportId);
  return result.changes > 0;
}

export function cleanupExpiredPhotos(db: Database.Database, photosDir: string, retentionDays = 30): { deleted: number; errors: number } {
  const cutoff = new Date(Date.now() - retentionDays * 24 * 60 * 60 * 1000).toISOString().slice(0, 19).replace('T', ' ');
  const expired = db
    .prepare(`SELECT id, report_id, file_path, file_size, mime_type, public_token, created_at FROM report_photos WHERE created_at < ?`)
    .all(cutoff) as ReportPhoto[];

  let deleted = 0;
  let errors = 0;
  const deleteRow = db.prepare('DELETE FROM report_photos WHERE id = ?');
  for (const photo of expired) {
    const filePath = join(photosDir, photo.file_path);
    try {
      if (existsSync(filePath)) unlinkSync(filePath);
      deleteRow.run(photo.id);
      deleted += 1;
    } catch {
      errors += 1;
    }
  }
  return { deleted, errors };
}

export function deleteReportTransactionally(db: Database.Database, reportId: number): ReportPhoto[] | null {
  const photos = getPhotosByReport(db, reportId);
  const tx = db.transaction(() => {
    const existing = db.prepare('SELECT id FROM reports WHERE id = ?').get(reportId);
    if (!existing) return false;
    db.prepare('DELETE FROM report_photos WHERE report_id = ?').run(reportId);
    db.prepare('DELETE FROM report_items WHERE report_id = ?').run(reportId);
    db.prepare('DELETE FROM report_temperatures WHERE report_id = ?').run(reportId);
    db.prepare('DELETE FROM reports WHERE id = ?').run(reportId);
    return true;
  });
  return tx() ? photos : null;
}

export function listUserOptions(db: Database.Database) {
  return db.prepare(`SELECT id, display_name FROM users WHERE is_active = 1 ORDER BY display_name ASC`).all() as { id: number; display_name: string }[];
}
export function listReportHistory(db: Database.Database, query: HistoryQuery, actor: HistoryActor): { items: ReportListItem[]; pagination: HistoryPagination } {
  const where: string[] = [];
  const params: unknown[] = [];
  if (query.date) {
    where.push('r.report_date = ?');
    params.push(query.date);
  }
  if (query.month) {
    where.push("r.report_date >= ? AND r.report_date < date(?, '+1 month')");
    params.push(`${query.month}-01`, `${query.month}-01`);
  }
  if (query.userId) {
    where.push('r.user_id = ?');
    params.push(query.userId);
  }
  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const total = (db.prepare(`SELECT COUNT(*) AS count FROM reports r ${whereSql}`).get(...params) as { count: number }).count;
  const page = query.page;
  const pageSize = query.pageSize;
  const offset = (page - 1) * pageSize;
  const rows = db.prepare(`
    SELECT r.id, r.user_id, r.turno, r.report_date, r.notes, r.created_at, r.updated_at, r.is_active, u.display_name
    FROM reports r
    JOIN users u ON r.user_id = u.id
    ${whereSql}
    ORDER BY r.created_at DESC, r.id DESC
    LIMIT ? OFFSET ?
  `).all(...params, pageSize, offset) as ReportRow[];

  const items = rows.map((r) => ({
    id: r.id,
    turno: r.turno as 'tarde' | 'noite',
    report_date: r.report_date,
    notes: r.notes,
    created_at: r.created_at,
    ...projectHistoryPermissions(actor, r),
    user: { id: r.user_id, display_name: r.display_name },
  }));

  return { items, pagination: { page, pageSize, total, totalPages: Math.ceil(total / pageSize) } };
}
