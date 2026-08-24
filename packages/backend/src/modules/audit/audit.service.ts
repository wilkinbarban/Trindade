import type Database from 'better-sqlite3';

// ---- Types ----

export interface AuditLogParams {
  userId: number;
  action: string;
  entityType: string;
  entityId?: number;
  ipAddress?: string;
  details?: Record<string, unknown>;
}

export interface AuditLogRow {
  id: number;
  user_id: number;
  display_name: string | null;
  action: string;
  entity_type: string;
  entity_id: number | null;
  ip_address: string | null;
  details: string | null;
  created_at: string;
}

export interface AuditLogQuery {
  page?: number;
  limit?: number;
  action?: string;
  entityType?: string;
  userId?: number;
}

export interface AuditLogPage {
  logs: AuditLogRow[];
  total: number;
  page: number;
  limit: number;
  totalPages: number;
}

// ---- Core audit log insert ----

/**
 * Record an auditable action into the `audit_logs` table.
 *
 * This is a pure function that takes the DB handle and parameters.
 * It is called explicitly from route handlers or service functions
 * after a critical operation completes successfully.
 */
export function log(db: Database.Database, params: AuditLogParams): void {
  db.prepare(
    `INSERT INTO audit_logs (user_id, action, entity_type, entity_id, ip_address, details)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).run(
    params.userId,
    params.action,
    params.entityType,
    params.entityId ?? null,
    params.ipAddress ?? null,
    params.details ? JSON.stringify(params.details) : null
  );
}

// ---- Audit log query (admin-only) ----

/**
 * Retrieve paginated, filtered audit logs ordered by newest first.
 */
export function queryLogs(
  db: Database.Database,
  params: AuditLogQuery
): AuditLogPage {
  const page = Math.max(1, params.page ?? 1);
  const limit = Math.min(100, Math.max(1, params.limit ?? 20));
  const offset = (page - 1) * limit;

  const conditions: string[] = [];
  const bindings: unknown[] = [];

  if (params.action) {
    conditions.push('a.action = ?');
    bindings.push(params.action);
  }
  if (params.entityType) {
    conditions.push('a.entity_type = ?');
    bindings.push(params.entityType);
  }
  if (params.userId) {
    conditions.push('a.user_id = ?');
    bindings.push(params.userId);
  }

  const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

  // Count total matching rows
  const countRow = db
    .prepare(`SELECT COUNT(*) AS total FROM audit_logs a ${where}`)
    .get(...bindings) as { total: number };

  const total = countRow.total;
  const totalPages = Math.ceil(total / limit) || 1;

  // Fetch page
  const logs = db
    .prepare(
      `SELECT a.id, a.user_id, u.display_name, a.action, a.entity_type,
              a.entity_id, a.ip_address, a.details, a.created_at
       FROM audit_logs a
       LEFT JOIN users u ON a.user_id = u.id
       ${where}
       ORDER BY a.created_at DESC
       LIMIT ? OFFSET ?`
    )
    .all(...bindings, limit, offset) as AuditLogRow[];

  return { logs, total, page, limit, totalPages };
}
