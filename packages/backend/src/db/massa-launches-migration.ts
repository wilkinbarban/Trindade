import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 5 → 6 cutover: the daily launch of massa boxes per product and user.
 *
 * Creating a table rewrites nothing, which makes this step safe to run against the live
 * database: every count, report, schedule and user stays intact. The table is created and
 * the revision stamped inside an immediate transaction.
 */
export function migrateMassaLaunches(db: Database.Database): void {
  db.transaction(() => {
    db.exec(`CREATE TABLE IF NOT EXISTS massa_launches (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      launch_date     TEXT NOT NULL,
      product_code    TEXT NOT NULL,
      boxes           INTEGER NOT NULL,
      user_id         INTEGER NOT NULL REFERENCES users(id),
      created_at      TEXT NOT NULL,
      updated_at      TEXT NOT NULL
    )`);
    db.exec(`CREATE INDEX IF NOT EXISTS idx_massa_launches_date ON massa_launches(launch_date)`);
    stampSchemaVersion(db, 6);
  }).immediate();
}
