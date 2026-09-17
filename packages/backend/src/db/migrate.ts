import Database from 'better-sqlite3';
import { existsSync } from 'node:fs';
import { migrateDatabase } from './migrations.js';
import { formatReport, readSchemaReport } from './schema-version.js';

/**
 * Apply pending schema migrations to an existing database, once, on demand.
 *
 * This is the only sanctioned write path for an existing database: it is operator-
 * invoked, never part of startup. It first classifies the file read-only and refuses
 * a `newer` or `incompatible` database without ever opening it for writing; only an
 * `unversioned` or `outdated` database is then opened read-write and migrated. Running
 * it against production still requires the recovery-gate evidence (snapshot, isolated
 * restore, approval) recorded in the README.
 *
 * Usage: node dist/db/migrate.js [database-path]
 *        DATABASE_PATH=/app/packages/backend/data/trindade.db node dist/db/migrate.js
 */
const databasePath = process.argv[2] ?? process.env.DATABASE_PATH ?? 'data/trindade.db';

function print(label: string, databasePath: string, report: Parameters<typeof formatReport>[1]): void {
  for (const line of formatReport(databasePath, report)) console.log(`${label}  ${line}`);
}

if (!existsSync(databasePath)) {
  console.error(`schema migrate: no database at ${databasePath}`);
  process.exitCode = 1;
} else {
  let readOnly: Database.Database | undefined;
  try {
    readOnly = new Database(databasePath, { readonly: true, fileMustExist: true });
    const before = readSchemaReport(readOnly);
    readOnly.close();
    readOnly = undefined;
    print('before', databasePath, before);

    if (before.verdict === 'current') {
      console.log('migrate: already current; nothing to do');
    } else if (before.verdict === 'newer' || before.verdict === 'incompatible') {
      console.error(`schema migrate: refused — ${before.summary}`);
      process.exitCode = 1;
    } else {
      const db = new Database(databasePath);
      try {
        const result = migrateDatabase(db);
        if (result.outcome === 'refused') {
          console.error(`schema migrate: refused — ${result.reason}`);
          process.exitCode = 1;
        } else if (result.outcome === 'migrated') {
          console.log(
            `migrate: ${result.rebuiltTemperatures ? 'rebuilt legacy report_temperatures' : 'legacy report_temperatures already current'}`,
          );
          console.log(`migrate: stamped user_version = ${result.to}`);
          print('after', databasePath, result.report);
        }
      } finally {
        db.close();
      }
    }
  } catch (error) {
    console.error(`schema migrate: cannot migrate ${databasePath}: ${(error as Error).message}`);
    process.exitCode = 1;
  } finally {
    readOnly?.close();
  }
}
