import type Database from 'better-sqlite3';
import { log as auditLog } from '../audit/audit.service.js';

export interface RegisterWorkerParams {
  username: string;
  displayName: string;
  passwordHash: string;
  ipAddress?: string;
  logger?: {
    warn: (obj: unknown, msg?: string) => void;
  };
}

export type RegisterWorkerResult =
  | { outcome: 'created'; userId: number; auditError?: unknown }
  | { outcome: 'duplicate' }
  | { outcome: 'bootstrap_required' };

/**
 * Checks if the system is in bootstrap mode (users table empty).
 * While users table is empty, public worker registration MUST be rejected
 * so that initial administrator setup cannot be preempted.
 */
export function isBootstrapRequired(db: Database.Database): boolean {
  return (db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number) === 0;
}

/**
 * Distinguishes only UNIQUE violation of users.username from other database errors.
 * FK violations (e.g. missing role), NOT NULL violations, and schema corruption
 * must NOT be treated as duplicates.
 */
function isUniqueUsernameViolation(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const err = error as { code?: string; message?: string };
  const isConstraint =
    err.code === 'SQLITE_CONSTRAINT_UNIQUE' ||
    err.code === 'SQLITE_CONSTRAINT';
  return (
    isConstraint &&
    typeof err.message === 'string' &&
    err.message.includes('users.username')
  );
}

/**
 * Inserts a newly registered worker into the database.
 * Hardcodes role_id = 2 (Trabalhador) and is_active = 0.
 *
 * Atomically checks bootstrap requirement inside an immediate transaction so that
 * the first worker cannot preempt administrator setup under concurrent requests.
 *
 * Treats ONLY UNIQUE violation of users.username as a duplicate outcome.
 * Any other constraint failure (e.g. missing worker role row via FK) or schema error
 * is re-thrown so the caller can return a server error (500).
 *
 * When newly created, attempts to record an audit log using the newly inserted user ID.
 * An audit failure does not prevent or roll back registration, but is surfaced to the
 * logger warning if provided.
 */
export function registerWorkerUser(
  db: Database.Database,
  params: RegisterWorkerParams,
): RegisterWorkerResult {
  const insertWorkerTx = db.transaction(() => {
    if (isBootstrapRequired(db)) {
      return { outcome: 'bootstrap_required' as const };
    }

    const result = db
      .prepare(
        `INSERT INTO users (username, password_hash, display_name, role_id, is_active)
         VALUES (?, ?, ?, 2, 0)`
      )
      .run(params.username, params.passwordHash, params.displayName);

    const userId = Number(result.lastInsertRowid);
    return { outcome: 'created' as const, userId };
  });

  let txResult:
    | { outcome: 'bootstrap_required' }
    | { outcome: 'created'; userId: number };

  try {
    txResult = insertWorkerTx.immediate();
  } catch (error: any) {
    if (isUniqueUsernameViolation(error)) {
      return { outcome: 'duplicate' };
    }
    // Re-throw any non-unique database error (FK violation, NOT NULL, schema, disk error)
    throw error;
  }

  if (txResult.outcome === 'bootstrap_required') {
    return { outcome: 'bootstrap_required' };
  }

  const { userId } = txResult;

  let auditError: unknown;
  try {
    auditLog(db, {
      userId,
      action: 'register',
      entityType: 'user',
      entityId: userId,
      ipAddress: params.ipAddress,
      details: { enrollment: 'worker_self_registration' },
    });
  } catch (err) {
    auditError = err;
    if (params.logger?.warn) {
      params.logger.warn(
        { err, userId },
        'Worker registration succeeded but its audit record could not be persisted'
      );
    }
  }

  return { outcome: 'created', userId, auditError };
}
