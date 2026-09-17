import type Database from 'better-sqlite3';
import { migrateAuthSessions } from './auth-sessions-migration.js';
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
 * - `unversioned` (revision 0) runs every step in order and ends stamped at the
 *   supported revision.
 * - `outdated` (a lower stamped revision) runs only the steps above its revision.
 *
 * Each step declares the revision it produces, runs only when the observed revision is
 * below it, is idempotent, and stamps its own revision afterwards. Stamping each step
 * separately means a crash between steps resumes from the next pending one instead of
 * re-running already-applied work.
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

  let rebuiltTemperatures = false;

  // Steps are ordered oldest-first. `apply` is only invoked when `before.version`
  // is below the revision the step produces, so a revision 1 database skips the
  // legacy rebuild entirely and runs only the additive 1 → 2 step.
  const steps: ReadonlyArray<{ to: number; apply: () => void }> = [
    {
      to: 1,
      apply: () => {
        rebuiltTemperatures = migrateLegacyReportTemperatures(db);
        stampSchemaVersion(db, 1);
      },
    },
    {
      to: 2,
      apply: () => {
        migrateAuthSessions(db);
        stampSchemaVersion(db, 2);
      },
    },
  ];

  for (const step of steps) {
    if (before.version < step.to) {
      step.apply();
    }
  }

  // Guard the step list against drifting from the revision this build claims to support:
  // bumping SCHEMA_VERSION without extending the list would otherwise leave every
  // migrated database stamped below what the build advertises, and the post-migration
  // report would then be refused on every run. This compares declared constants only,
  // so it cannot fire because of database state.
  const highestStep = steps.length > 0 ? steps[steps.length - 1].to : 0;
  if (highestStep !== SCHEMA_VERSION) {
    throw new Error(
      `migration steps reach revision ${highestStep} but this build supports ${SCHEMA_VERSION}: ` +
        'the stepwise list and SCHEMA_VERSION must be updated together',
    );
  }

  const after = readSchemaReport(db);
  if (after.verdict !== 'current') {
    return { outcome: 'refused', reason: `migration produced ${after.verdict}: ${after.summary}`, report: after };
  }
  return { outcome: 'migrated', from: before.version, to: SCHEMA_VERSION, rebuiltTemperatures, report: after };
}
