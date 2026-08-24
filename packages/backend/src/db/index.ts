import Database, { type Database as DatabaseType } from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { classifyInstallation } from './install-lifecycle.js';

export function openDatabase(databasePath: string): DatabaseType {
  const installation = classifyInstallation(databasePath);
  if (installation.kind === 'ambiguous') {
    throw new Error(`Database startup refused: ${installation.reason}`);
  }

  const db = new Database(databasePath, installation.kind === 'existing'
    ? { fileMustExist: true }
    : undefined);
  db.pragma('foreign_keys = ON');
  if (installation.kind === 'fresh') {
    const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');
    const seed = readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8');
    db.exec(schema);
    db.transaction(() => db.exec(seed))();
  }
  return db;
}
