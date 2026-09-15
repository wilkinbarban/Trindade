import type Database from 'better-sqlite3';
import { migrateLegacyReportTemperatures } from './report-temperatures-migration.js';
import { SCHEMA_VERSION, readSchemaReport, stampSchemaVersion, type SchemaReport } from './schema-version.js';

export type MigrateResult =
  | { outcome: 'already-current'; report: SchemaReport }
  | { outcome: 'migrated'; from: number; to: number; rebuiltTemperatures: boolean; report: SchemaReport }
  | { outcome: 'refused'; reason: string; report: SchemaReport };

/**
 * Bring an existing database up to the supported revision, once, and never at
 * startup. This is the sanctioned write path that the Production Immutability Gate
 * leaves to an explicit operator decision: the caller opens the file read-write only
 * after classifying it read-only, and this function refuses anything it cannot
 * safely migrate.
 *
 * - `current` is a no-op.
 * - `newer` is refused: this build knows nothing about that revision and must never
 *   downgrade it.
 * - `incompatible` (failed integrity or missing tables) is refused without writing.
 * - `unversioned` applies the one migration that predates versioning — the legacy
 *   `report_temperatures` rebuild — and then stamps the revision. The rebuild is
 *   idempotent (it no-ops once `reading_index` exists), and the stamp lands after it,
 *   so a crash between the two leaves a migrated-but-unversioned database that this
 *   path repairs on the next run.
 *
 * When a second schema revision exists, the single rebuild is replaced by a stepwise
 * list driven by `before.version`.
 */
export function migrateDatabase(db: Database.Database): MigrateResult {
  const before = readSchemaReport(db);

  if (before.verdict === 'newer') {
    return {
      outcome: 'refused',
      reason: `revision ${before.version} is newer than this build supports (${SCHEMA_VERSION}); never downgrade`,
      report: before,
    };
  }
  if (before.verdict === 'incompatible') {
    return { outcome: 'refused', reason: before.summary, report: before };
  }
  if (before.verdict === 'current') {
    return { outcome: 'already-current', report: before };
  }

  const rebuiltTemperatures = migrateLegacyReportTemperatures(db);
  stampSchemaVersion(db);

  const after = readSchemaReport(db);
  if (after.verdict !== 'current') {
    return { outcome: 'refused', reason: `migration produced ${after.verdict}: ${after.summary}`, report: after };
  }
  return { outcome: 'migrated', from: before.version, to: SCHEMA_VERSION, rebuiltTemperatures, report: after };
}
