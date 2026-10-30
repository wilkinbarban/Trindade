import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 8 → 9 cutover: a launch may say which ERP seat it came from.
 *
 * Until now an imported seat became a launch owned by whoever pressed "Importar", and the seat itself was
 * forgotten. Reconciliation then had nothing but the operator's name to pair them, so a seat recorded on the
 * tablet by VICTOR could never settle against a launch stored under WILKIN: the row stayed importable, the
 * operator pressed again, and each press added one more orphan launch to the day.
 *
 * The column is what makes the pair knowable instead of guessable, and it is nullable because a launch typed
 * by hand has no seat behind it. A partial unique index keeps the meaning one-to-one: many unseated launches
 * are normal, the same seat twice is a duplicated import.
 *
 * Adding a column and an index rewrites nothing, so this is safe against the live database: every launch
 * keeps its id, its boxes and its counters, and the seats it could not name stay empty.
 */
export function migrateMassaLaunchSeats(db: Database.Database): void {
  db.transaction(() => {
    const before = (db.prepare('SELECT COUNT(*) AS n FROM massa_launches').get() as { n: number }).n;

    const columns = (db.prepare('PRAGMA table_info(massa_launches)').all() as { name: string }[]).map(
      (row) => row.name,
    );
    if (!columns.includes('ent_lan')) {
      db.exec('ALTER TABLE massa_launches ADD COLUMN ent_lan INTEGER DEFAULT NULL');
    }

    // `WHERE ent_lan IS NOT NULL`: an unseated launch is not a competitor for the same seat, and SQLite
    // already treats NULLs as distinct, so this only documents what the index means.
    db.exec(`CREATE UNIQUE INDEX IF NOT EXISTS uq_massa_launches_ent_lan
      ON massa_launches(ent_lan) WHERE ent_lan IS NOT NULL`);

    const after = (db.prepare('SELECT COUNT(*) AS n FROM massa_launches').get() as { n: number }).n;
    if (after !== before) {
      throw new Error(`massa_launches seat migration changed ${before} launches into ${after}; refusing to stamp`);
    }

    stampSchemaVersion(db, 9);
  }).immediate();
}
