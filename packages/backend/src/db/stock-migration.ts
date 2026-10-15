import type Database from 'better-sqlite3';
import { stampSchemaVersion } from './schema-version.js';

/**
 * Revision 4 → 5 cutover: the daily count of boxes per product.
 *
 * Creating a table rewrites nothing, which is the whole reason this step is safe to run against the
 * live database: every report, schedule and user stays exactly where it was, and the counting starts
 * from an empty table. The table is created and the revision stamped inside one immediate
 * transaction, so a crash between the two rolls both back and the step stays re-runnable instead of
 * leaving a table stamped under the wrong revision.
 */
export function migrateStockCounts(db: Database.Database): void {
  db.transaction(() => {
    db.exec(`CREATE TABLE IF NOT EXISTS stock_counts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      count_date TEXT NOT NULL,
      category TEXT NOT NULL,
      product_code TEXT NOT NULL,
      product_name TEXT NOT NULL,
      units_per_box INTEGER NOT NULL,
      boxes_total INTEGER NOT NULL DEFAULT 0,
      units_total INTEGER NOT NULL DEFAULT 0,
      entries_log TEXT NOT NULL DEFAULT '[]',
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      user_id INTEGER REFERENCES users(id),
      UNIQUE(count_date, product_code)
    )`);
    stampSchemaVersion(db, 5);
  }).immediate();
}
