import Database from 'better-sqlite3';
import { existsSync } from 'node:fs';
import { formatReport, readSchemaReport } from './schema-version.js';

/**
 * Report whether a database is up to date, and never write to it.
 *
 * This is the operator answer to "is the database in production current?": it opens
 * the file read-only, prints the revision marker, the table inventory and the
 * verdict, and exits non-zero unless the verdict is `current`. It never creates,
 * migrates, stamps, or repairs anything: any change to an existing database needs an
 * explicitly approved operation (see the Production Immutability Gate in the README).
 *
 * Usage: node dist/db/status.js [database-path]
 *        DATABASE_PATH=/app/packages/backend/data/trindade.db node dist/db/status.js
 */
const databasePath = process.argv[2] ?? process.env.DATABASE_PATH ?? 'data/trindade.db';

if (!existsSync(databasePath)) {
  console.error(`schema status: no database at ${databasePath}`);
  process.exitCode = 1;
} else {
  let db: Database.Database | undefined;
  try {
    db = new Database(databasePath, { readonly: true, fileMustExist: true });
    const report = readSchemaReport(db);
    for (const line of formatReport(databasePath, report)) console.log(line);
    process.exitCode = report.verdict === 'current' ? 0 : 1;
  } catch (error) {
    console.error(`schema status: cannot read ${databasePath}: ${(error as Error).message}`);
    process.exitCode = 1;
  } finally {
    db?.close();
  }
}
