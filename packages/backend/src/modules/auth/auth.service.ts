import type Database from 'better-sqlite3';
import { log as auditLog } from '../audit/audit.service.js';
import { normalizeUsername } from './auth.rate-limiter.js';

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
 * Reports whether an existing account normalizes to the candidate username.
 *
 * The users.username UNIQUE constraint is applied with SQLite's default BINARY collation, and
 * SQLite's `lower()` folds only ASCII A-Z, so neither the constraint nor a `lower()`-based query
 * catches a case variant, let alone a Unicode one (e.g. `Admin` vs `admin`, or `Ünïcode` vs
 * `ünïcode`). The comparison therefore runs in JavaScript with the exact `normalizeUsername` that
 * registration and rate limiting already use, over every stored username.
 *
 * This is an O(stored users) scan per registration. That is intentional: detecting a Unicode case
 * twin without a schema change (a NOCASE index is ASCII-only and would not fold `Ü`) requires the
 * full scan. Registration is admission-gated and rare, so the cost is bounded in practice; a
 * future functional index or a persisted normalized column would remove the scan.
 *
 * Callers must invoke this inside the same IMMEDIATE transaction as the INSERT, so two concurrent
 * case variants cannot both observe an empty table and both insert.
 */
function hasNormalizedUsernameTwin(
  db: Database.Database,
  normalizedCandidate: string,
): boolean {
  const rows = db.prepare('SELECT username FROM users').all() as { username: string }[];
  return rows.some((row) => normalizeUsername(row.username) === normalizedCandidate);
}

/**
 * Inserts a newly registered worker into the database.
 * Hardcodes role_id = 2 (Trabalhador) and is_active = 0.
 *
 * Atomically checks bootstrap requirement and the normalized username twin inside an immediate
 * transaction so that the first worker cannot preempt administrator setup under concurrent
 * requests and two case variants of the same username cannot both be inserted.
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

    // Detected before the INSERT and inside this same write lock: an operator account stored with
    // its original spelling (e.g. `Admin`) must not gain a normalized twin (`admin`) through public
    // registration. Returning duplicate here leaves the account untouched and writes no audit row.
    if (hasNormalizedUsernameTwin(db, normalizeUsername(params.username))) {
      return { outcome: 'duplicate' as const };
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
    | { outcome: 'duplicate' }
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

  if (txResult.outcome === 'duplicate') {
    return { outcome: 'duplicate' };
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
