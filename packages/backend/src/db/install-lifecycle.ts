import Database from 'better-sqlite3';
import { accessSync, constants, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';

export type InstallationClass =
  | { kind: 'fresh'; reason: string }
  | { kind: 'existing'; reason: string }
  | { kind: 'ambiguous'; reason: string };

export function classifyInstallation(databasePath: string): InstallationClass {
  try {
    accessSync(dirname(databasePath), constants.R_OK | constants.W_OK);
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
    const integrity = db.pragma('quick_check', { simple: true });
    const tables = db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").pluck().get() as number;
    db.close();
    if (integrity !== 'ok' || tables === 0) return { kind: 'ambiguous', reason: 'Database identity is incomplete' };
    return { kind: 'existing', reason: 'A readable database with an established schema exists' };
  } catch {
    return { kind: 'ambiguous', reason: 'Database cannot be safely identified' };
  }
}
