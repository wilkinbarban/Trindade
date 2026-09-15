import type Database from 'better-sqlite3';

/**
 * Schema revision stamped into `PRAGMA user_version` by a fresh installation.
 *
 * Revision history:
 * - 1: the schema as of the 2026-06-17 admin-refactor-loading-rules change, which
 *   dropped `report_products` and `report_quantities` (their data is carried by
 *   `report_items.selected_products` as JSON) and left 14 user tables.
 *
 * Databases created before versioning carry `user_version = 0` and are reported as
 * `unversioned`; nothing about them is assumed, and no startup path rewrites them.
 */
export const SCHEMA_VERSION = 1;

/**
 * The user tables the approved schema defines. Kept in code because the read-only
 * status report needs an expectation to compare against; the test suite ties this
 * list to the tables `schema.sql` actually creates.
 */
export const SCHEMA_TABLES = [
  'audit_logs',
  'drivers',
  'loading_schedules',
  'report_categories',
  'report_items',
  'report_photos',
  'report_tasks',
  'report_templates',
  'report_temperatures',
  'reports',
  'roles',
  'settings',
  'users',
  'vehicles',
] as const;

export type SchemaObservation = {
  version: number;
  integrity: string;
  tables: string[];
};

export type SchemaVerdict =
  /** Revision matches and every expected table is present. */
  | 'current'
  /** No revision stamp: created before schema versioning. */
  | 'unversioned'
  /** Stamped with an older revision, so a migration is pending. */
  | 'outdated'
  /** Integrity failed or expected tables are missing. */
  | 'incompatible'
  /** Stamped with a newer revision than this build supports: never downgrade it. */
  | 'newer';

export type SchemaReport = {
  version: number;
  expectedVersion: number;
  integrity: string;
  tables: string[];
  missingTables: string[];
  unexpectedTables: string[];
  verdict: SchemaVerdict;
  summary: string;
};

/**
 * Classify one observation against the supported revision.
 *
 * Precedence is deliberate: a failed integrity check is reported before anything
 * else, then a database stamped with a newer revision (it was written by a build
 * this one knows nothing about, so missing tables would be misleading), then
 * missing tables, then the revision comparison.
 */
export function classifySchema(
  observation: SchemaObservation,
  expectedVersion: number = SCHEMA_VERSION,
): SchemaReport {
  const tables = [...observation.tables].sort();
  const expected: readonly string[] = SCHEMA_TABLES;
  const missingTables = expected.filter((table) => !tables.includes(table));
  const unexpectedTables = tables.filter((table) => !expected.includes(table));

  let verdict: SchemaVerdict;
  if (observation.integrity !== 'ok') verdict = 'incompatible';
  else if (observation.version > expectedVersion) verdict = 'newer';
  else if (missingTables.length > 0) verdict = 'incompatible';
  else if (observation.version === 0) verdict = 'unversioned';
  else if (observation.version < expectedVersion) verdict = 'outdated';
  else verdict = 'current';

  return {
    version: observation.version,
    expectedVersion,
    integrity: observation.integrity,
    tables,
    missingTables,
    unexpectedTables,
    verdict,
    summary: summarize(verdict, observation.version, expectedVersion, missingTables, tables, unexpectedTables),
  };
}

function summarize(
  verdict: SchemaVerdict,
  version: number,
  expectedVersion: number,
  missingTables: string[],
  tables: string[],
  unexpectedTables: string[],
): string {
  const extra = unexpectedTables.length > 0 ? `; unexpected tables: ${unexpectedTables.join(', ')}` : '';
  switch (verdict) {
    case 'current':
      return `revision ${version}; ${tables.length} tables present${extra}`;
    case 'unversioned':
      return `no revision stamp (created before schema versioning); ${tables.length} tables present${extra}`;
    case 'outdated':
      return `revision ${version} is older than the supported revision ${expectedVersion}; a migration is pending${extra}`;
    case 'newer':
      return `revision ${version} is newer than the supported revision ${expectedVersion}; this build must not touch it${extra}`;
    case 'incompatible':
      return missingTables.length > 0
        ? `missing tables: ${missingTables.join(', ')}${extra}`
        : 'integrity check failed';
  }
}

/** Read one observation from a database. Performs no writes. */
export function observeSchema(db: Database.Database): SchemaObservation {
  const version = db.pragma('user_version', { simple: true }) as number;
  const integrity = db.pragma('quick_check', { simple: true }) as string;
  const tables = (
    db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
      .pluck()
      .all() as string[]
  ).sort();
  return { version, integrity, tables };
}

/** Classify a database against the supported revision. Performs no writes. */
export function readSchemaReport(
  db: Database.Database,
  expectedVersion: number = SCHEMA_VERSION,
): SchemaReport {
  return classifySchema(observeSchema(db), expectedVersion);
}

/** Render one report as the lines the status command prints. */
export function formatReport(databasePath: string, report: SchemaReport): string[] {
  const lines = [
    `database: ${databasePath}`,
    `integrity: ${report.integrity}`,
    `schema revision: ${report.version} (this build supports ${report.expectedVersion})`,
    `tables: ${report.tables.length} observed, ${report.missingTables.length} missing, ${report.unexpectedTables.length} unexpected`,
  ];
  if (report.missingTables.length > 0) lines.push(`missing tables: ${report.missingTables.join(', ')}`);
  if (report.unexpectedTables.length > 0) lines.push(`unexpected tables: ${report.unexpectedTables.join(', ')}`);
  lines.push(`verdict: ${report.verdict} — ${report.summary}`);
  return lines;
}

/**
 * Stamp the supported revision into a database this process just created. Only the
 * fresh-installation path may call this: an existing production database is never
 * stamped, migrated, or otherwise written by startup.
 */
export function stampSchemaVersion(db: Database.Database): void {
  db.pragma(`user_version = ${SCHEMA_VERSION}`);
}
