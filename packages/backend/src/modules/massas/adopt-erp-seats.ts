import type Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { deriveOperatorUsername, operatorDisplayName, quickWorkerPassword } from './operator-account.js';
import { resolveOperatorLink } from './massas-operator.js';

/**
 * Credits a seat to the operator the ERP signed on it, creating that operator when Trindade has none.
 *
 * The import used to fall back to the importer's account, so a seat signed `JOAO H` became the
 * administrator's production. The rule that replaced it refuses such a seat instead of misattributing it,
 * which leaves the historical rows wrong until somebody credits them on purpose -- this.
 *
 * It is a correction, not a migration: it touches only the seats it is told about, writes an audit entry per
 * seat, and answers the same thing on a second run because there is nothing left to change.
 */

/** The worker role. Creating an account here never creates a colleague with the administrator's reach. */
const WORKER_ROLE_ID = 2;

export type SeatAdoptionStatus =
  /** The operator existed and did not own the seat yet; the launches moved to them. */
  | 'adopted'
  /** The operator did not exist; the account was created with the generated password, then the seat moved. */
  | 'created'
  | 'already-owned'
  /** The account exists but was deactivated: reactivating is an administrative decision, not a script's. */
  | 'inactive'
  /** The ERP mirror never saw this seat, so there is no operator name to honour. */
  | 'missing-seat'
  /** The seat is known but nobody imported it, so there is nothing to credit. */
  | 'missing-launch'
  /** The generated login already belongs to somebody whose name is not this operator. */
  | 'login-taken';

export interface SeatAdoption {
  entLan: number;
  status: SeatAdoptionStatus;
  operatorName: string | null;
  userId?: number;
  username?: string;
  /** How many launch rows moved (or would move, in a dry run). */
  movedLaunches: number;
}

interface LaunchRow {
  id: number;
  user_id: number;
}

export interface AdoptOptions {
  /** Compute the whole report without writing the account, the rows, or the audit entry. */
  dryRun?: boolean;
}

export function adoptErpSeats(
  db: Database.Database,
  entLans: readonly number[],
  options: AdoptOptions = {},
): SeatAdoption[] {
  const dryRun = options.dryRun === true;
  let report: SeatAdoption[] = [];
  // One code path for both modes: a dry run does the whole work and rolls the transaction back, so what it
  // reports and what a real run writes can never drift apart.
  try {
    db.transaction(() => {
      report = entLans.map((entLan) => adoptOne(db, entLan, dryRun));
      if (dryRun) throw new DryRunRollback();
    })();
  } catch (error) {
    if (!(error instanceof DryRunRollback)) throw error;
  }
  return report;
}

/** Thrown to roll a dry run back; never escapes this module. */
class DryRunRollback extends Error {}

function adoptOne(db: Database.Database, entLan: number, dryRun: boolean): SeatAdoption {
  const operatorName = db
    .prepare('SELECT operator_name FROM aero_movements WHERE ent_lan = ? ORDER BY movement_date DESC LIMIT 1')
    .pluck()
    .get(entLan) as string | null | undefined;

  if (operatorName === undefined) {
    return { entLan, status: 'missing-seat', operatorName: null, movedLaunches: 0 };
  }

  const launches = db
    .prepare('SELECT id, user_id FROM massa_launches WHERE ent_lan = ? ORDER BY id')
    .all(entLan) as LaunchRow[];
  if (launches.length === 0) {
    return { entLan, status: 'missing-launch', operatorName, movedLaunches: 0 };
  }

  const link = resolveOperatorLink(db, operatorName);

  if (link.status === 'inactive') {
    return {
      entLan,
      status: 'inactive',
      operatorName,
      userId: link.userId,
      username: link.username,
      movedLaunches: 0,
    };
  }

  let userId: number;
  let username: string;
  let created = false;

  if (link.status === 'linked') {
    userId = link.userId;
    username = db.prepare('SELECT username FROM users WHERE id = ?').pluck().get(userId) as string;
  } else {
    username = deriveOperatorUsername(operatorName);
    if (username === '') {
      return { entLan, status: 'login-taken', operatorName, movedLaunches: 0 };
    }
    const owner = db.prepare('SELECT id FROM users WHERE username = ?').pluck().get(username) as number | undefined;
    if (owner !== undefined) {
      return { entLan, status: 'login-taken', operatorName, username, movedLaunches: 0 };
    }
    userId = createOperator(db, operatorName, username, dryRun);
    created = true;
  }

  const pending = launches.filter((launch) => launch.user_id !== userId);
  if (pending.length > 0 && !dryRun) {
    const move = db.prepare('UPDATE massa_launches SET user_id = ? WHERE id = ?');
    for (const launch of pending) move.run(userId, launch.id);
    recordAdoption(db, entLan, operatorName, pending, userId);
  }

  return {
    entLan,
    status: created ? 'created' : pending.length === 0 ? 'already-owned' : 'adopted',
    operatorName,
    userId,
    username,
    movedLaunches: pending.length,
  };
}

/** Creates the operator with the same login and password every screen would have generated. */
function createOperator(db: Database.Database, operatorName: string, username: string, dryRun: boolean): number {
  if (dryRun) {
    // A negative id no row can hold: the report only needs to say what would be adopted.
    return -1;
  }
  const result = db
    .prepare(
      `INSERT INTO users (username, password_hash, display_name, role_id, is_active)
       VALUES (?, ?, ?, ?, 1)`,
    )
    .run(username, bcrypt.hashSync(quickWorkerPassword(username), 10), operatorDisplayName(operatorName), WORKER_ROLE_ID);
  return result.lastInsertRowid as number;
}

function recordAdoption(
  db: Database.Database,
  entLan: number,
  operatorName: string,
  launches: readonly LaunchRow[],
  userId: number,
): void {
  db.prepare(
    `INSERT INTO audit_logs (user_id, action, entity_type, entity_id, details)
     VALUES (?, 'edit', 'massa_launch', ?, ?)`,
  ).run(
    userId,
    launches[0].id,
    JSON.stringify({
      reason: 'erp_seat_adoption',
      ent_lan: entLan,
      operator_name: operatorName,
      from_user_id: launches[0].user_id,
      to_user_id: userId,
      launch_ids: launches.map((launch) => launch.id),
    }),
  );
}
