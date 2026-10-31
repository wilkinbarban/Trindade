#!/usr/bin/env -S npx tsx
/**
 * Credits the ERP seats the plant could not attribute to the operator they name.
 *
 * Usage:
 *   npx tsx packages/backend/scripts/adopt-erp-seats.ts --ent-lan 35947,35952 [--dry-run]
 *
 * The database is the one the API uses: `DATABASE_PATH`, or `packages/backend/data/trindade.db`.
 * A dry run prints exactly what a real run would do and writes nothing.
 *
 * Every real run first copies the database next to itself (`<db>.pre-adopt-<timestamp>.db`), because moving
 * somebody's production is not undoable by re-running a script.
 */
import { copyFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import Database from 'better-sqlite3';
import { adoptErpSeats } from '../src/modules/massas/adopt-erp-seats.js';

function argument(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index === -1 ? undefined : process.argv[index + 1];
}

const rawSeats = argument('ent-lan');
if (rawSeats === undefined) {
  console.error('Missing --ent-lan <id>[,<id>...]');
  process.exit(2);
}

const entLans = rawSeats
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isInteger(value) && value > 0);
if (entLans.length === 0) {
  console.error(`No seat number in --ent-lan ${rawSeats}`);
  process.exit(2);
}

const dryRun = process.argv.includes('--dry-run');
const dbPath = resolve(argument('db') ?? process.env.DATABASE_PATH ?? join(import.meta.dirname, '../data/trindade.db'));

if (!dryRun) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const backup = join(dirname(dbPath), `${basename(dbPath, '.db')}.pre-adopt-${stamp}.db`);
  copyFileSync(dbPath, backup);
  console.log(`Backup: ${backup}`);
}

const db = new Database(dbPath, { fileMustExist: true });
db.pragma('foreign_keys = ON');

const report = adoptErpSeats(db, entLans, { dryRun });

for (const seat of report) {
  const who = seat.username === undefined ? '-' : `${seat.username} (#${seat.userId})`;
  console.log(`seat ${seat.entLan}  ${seat.status.padEnd(13)}  operator ${seat.operatorName ?? '-'}  -> ${who}  launches moved: ${seat.movedLaunches}`);
}

db.close();
