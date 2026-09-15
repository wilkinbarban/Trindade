import Database, { type Database as DatabaseType } from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { classifyInstallation } from './install-lifecycle.js';
import { stampSchemaVersion } from './schema-version.js';

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
    // The revision stamp belongs to the creation transaction: a crash must leave
    // either a fully seeded, stamped database or none at all. An existing database
    // is never stamped here (see the Production Immutability Gate in the README).
    db.transaction(() => {
      db.exec(seed);
      stampSchemaVersion(db);
    })();
  }
  return db;
}
