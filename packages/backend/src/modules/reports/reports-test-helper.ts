import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';

export const databases: Database.Database[] = [];

export function setupReportsDb(): Database.Database {
  const db = new Database(':memory:');
  databases.push(db);
  db.pragma('foreign_keys = ON');
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  db.prepare(
    `INSERT INTO users (id, username, password_hash, display_name, role_id, is_active)
     VALUES (1, 'owner', 'hash', 'Owner', 1, 1), (2, 'other', 'hash', 'Other', 2, 1)`
  ).run();
  return db;
}

export function completeTemperatures(db: Database.Database) {
  return (db.prepare(
    `SELECT rt.name_pt AS location, rt.temperature_readings
     FROM report_tasks rt JOIN report_categories rc ON rc.id = rt.category_id
     WHERE rc.category_type = 'temperature' AND rc.is_active = 1 AND rt.is_active = 1`
  ).all() as { location: string; temperature_readings: number }[]).flatMap((task) =>
    Array.from({ length: task.temperature_readings }, (_, index) => ({
      location: task.location,
      readingIndex: index + 1,
      value: -18 - index,
    }))
  );
}

export function closeReportDatabases(): void {
  while (databases.length) databases.pop()!.close();
}
