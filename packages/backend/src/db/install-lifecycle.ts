import Database from 'better-sqlite3';
import { accessSync, constants, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';
import { SCHEMA_TABLES, observeSchema } from './schema-version.js';

export type InstallationClass =
  | { kind: 'fresh'; reason: string }
  | { kind: 'interrupted'; reason: string }
  | { kind: 'existing'; reason: string }
  | { kind: 'ambiguous'; reason: string };

/**
 * A database that carries the complete current schema, no revision stamp and no rows is
 * the exact state `db.exec(schema)` leaves behind when the creation transaction
 * (seed + stamp) never ran, so the creation was interrupted between the two steps and
 * must be completed instead of being treated as an existing installation.
 *
 * The three conditions are deliberately strict, because this is the only state startup
 * is allowed to finish writing:
 * - a complete current schema rules out a database created before schema versioning
 *   (those cannot contain `auth_sessions`, which only ever existed in builds that stamp
 *   at creation) and a crash that happened mid-schema (missing tables);
 * - no rows at all rules out an installation with data, users, or operational history,
 *   which the Production Immutability Gate keeps startup away from;
 * - no revision stamp is what makes the state inconsistent to begin with.
 *
 * Anything else, however close, stays `existing` and is therefore refused by startup:
 * an ambiguous origin fails closed instead of being healed by guesswork.
 */
function isInterruptedCreation(db: Database.Database, tables: string[], version: number): boolean {
  if (version !== 0) return false;
  if (tables.length !== SCHEMA_TABLES.length) return false;
  if (tables.some((table) => !SCHEMA_TABLES.includes(table))) return false;
  return tables.every((table) => db.prepare(`SELECT 1 FROM "${table}" LIMIT 1`).get() === undefined);
}

export function classifyInstallation(databasePath: string): InstallationClass {
  const parent = dirname(databasePath);
  try {
    if (!statSync(parent).isDirectory()) {
      return { kind: 'ambiguous', reason: 'Database parent is not a directory' };
    }
    accessSync(parent, constants.R_OK | constants.W_OK);
  } catch {
    return { kind: 'ambiguous', reason: 'Database parent cannot be inspected safely' };
  }
  if (!existsSync(databasePath)) {
    if (existsSync(`${databasePath}-wal`) || existsSync(`${databasePath}-shm`)) {
      return { kind: 'ambiguous', reason: 'Database sidecars exist without the primary database' };
    }
    return { kind: 'fresh', reason: 'No database or SQLite sidecars exist' };
  }
  try {
    if (!statSync(databasePath).isFile() || statSync(databasePath).size === 0) {
      return { kind: 'ambiguous', reason: 'Database target is not a non-empty regular file' };
    }
    const db = new Database(databasePath, { readonly: true, fileMustExist: true });
    try {
      const observation = observeSchema(db);
      if (observation.integrity !== 'ok' || observation.tables.length === 0) {
        return { kind: 'ambiguous', reason: 'Database identity is incomplete' };
      }
      if (isInterruptedCreation(db, observation.tables, observation.version)) {
        return {
          kind: 'interrupted',
          reason: 'The complete schema is present without a revision stamp or any data: the creation was interrupted',
        };
      }
      return { kind: 'existing', reason: 'A readable database with an established schema exists' };
    } finally {
      db.close();
    }
  } catch {
    return { kind: 'ambiguous', reason: 'Database cannot be safely identified' };
  }
}
