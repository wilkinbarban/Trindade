import type Database from 'better-sqlite3';
import { readSchemaReport, type SchemaVerdict } from './schema-version.js';

/**
 * The single schema line a server reports at startup.
 *
 * This exists so an operator can read, from the logs alone, which schema revision the
 * running process actually found. It is deliberately a *notice*: it never migrates,
 * stamps, repairs, or refuses. The only sanctioned write path for an existing database is
 * the operator-invoked `db:migrate` command (see the Production Immutability Gate in the
 * README), and startup must never become a second one.
 */
export type SchemaNotice = {
  level: 'info' | 'warn';
  message: string;
  payload: Record<string, unknown>;
};

/**
 * A pending or unexpected schema is worth a warning; an unversioned database is not,
 * because it is the expected state of any installation that predates schema versioning
 * and adopting it needs the explicit operational approval tracked in the README.
 */
const LEVEL_BY_VERDICT: Record<SchemaVerdict, 'info' | 'warn'> = {
  current: 'info',
  unversioned: 'info',
  outdated: 'warn',
  newer: 'warn',
  incompatible: 'warn',
};

/**
 * Classify the already-open database for the startup log.
 *
 * Never throws. Running it must not be able to fail a boot or force an exit: a
 * classification problem is reported as a warning about the report itself, which is the
 * opposite of the failure mode that silently migrating at startup would create.
 */
export function schemaNoticeForStartup(db: Database.Database): SchemaNotice {
  try {
    const report = readSchemaReport(db);
    return {
      level: LEVEL_BY_VERDICT[report.verdict],
      message: 'database schema notice',
      payload: {
        verdict: report.verdict,
        revision: report.version,
        supportedRevision: report.expectedVersion,
        integrity: report.integrity,
        tables: report.tables.length,
        missingTables: report.missingTables,
        unexpectedTables: report.unexpectedTables,
        summary: report.summary,
      },
    };
  } catch (error) {
    return {
      level: 'warn',
      message: 'database schema notice unavailable',
      payload: { error: (error as Error).message },
    };
  }
}
