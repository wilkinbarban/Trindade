import type Database from 'better-sqlite3';

export function migrateLegacyReportTemperatures(db: Database.Database): void {
  const reportTemperatures = db
    .prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'report_temperatures'")
    .get() as { sql: string } | undefined;

  if (!reportTemperatures || reportTemperatures.sql.includes('reading_index')) return;

  db.pragma('foreign_keys = OFF');
  try {
    db.transaction(() => {
      // A previous interrupted legacy migration may have left this temporary table behind.
      db.exec('DROP TABLE IF EXISTS new_report_temperatures');
      db.exec(`
        CREATE TABLE new_report_temperatures (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          report_id INTEGER NOT NULL REFERENCES reports(id),
          location TEXT NOT NULL,
          reading_index INTEGER NOT NULL DEFAULT 1 CHECK(reading_index BETWEEN 1 AND 3),
          value REAL NOT NULL,
          created_at TEXT NOT NULL DEFAULT (datetime('now')),
          UNIQUE(report_id, location, reading_index)
        );
        INSERT INTO new_report_temperatures (id, report_id, location, reading_index, value, created_at)
        SELECT id, report_id, location, 1, value, created_at FROM report_temperatures;
        DROP TABLE report_temperatures;
        ALTER TABLE new_report_temperatures RENAME TO report_temperatures;
      `);
    })();
  } finally {
    db.pragma('foreign_keys = ON');
  }
}
