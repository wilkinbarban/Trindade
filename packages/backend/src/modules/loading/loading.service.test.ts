import { afterEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';

import type { HistoryActor } from '../history-permissions.js';
import * as loading from './loading.service.js';

const databases: Database.Database[] = [];

/** The actor a route would pass: `create` needs it to project permissions. */
const ADMIN: HistoryActor = { sub: 1, role: 'Administrador' };

function buildDb(): Database.Database {
  const db = new Database(':memory:');
  databases.push(db);
  db.pragma('foreign_keys = ON');
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  db.prepare(
    `INSERT INTO users (id, username, password_hash, display_name, role_id, is_active)
     VALUES (1, 'owner', 'hash', 'Owner', 2, 1), (2, 'other', 'hash', 'Other', 2, 1)`
  ).run();
  return db;
}

function driver(db: Database.Database, name: string, type: 'casa' | 'fletero' = 'fletero', active = 1): number {
  return Number(db.prepare(
    'INSERT INTO drivers (name, license_plate, driver_type, is_active) VALUES (?, ?, ?, ?)'
  ).run(name, `${name}-plate`, type, active).lastInsertRowid);
}

function vehicle(db: Database.Database, name: string, active = 1): number {
  return Number(db.prepare(
    'INSERT INTO vehicles (description, license_plate, is_active) VALUES (?, ?, ?)'
  ).run(name, `${name}-plate`, active).lastInsertRowid);
}

function schedule(
  db: Database.Database,
  options: { date?: string; time?: string; driverId: number; vehicleId?: number | null; userId?: number; createdAt?: string; active?: number }
): number {
  return Number(db.prepare(
    `INSERT INTO loading_schedules
     (schedule_date, time_slot, driver_id, vehicle_id, driver_type, user_id, created_at, is_active)
     VALUES (?, ?, ?, ?, (SELECT driver_type FROM drivers WHERE id = ?), ?, COALESCE(?, datetime('now')), ?)`
  ).run(options.date ?? '2026-08-25', options.time ?? '04:00', options.driverId, options.vehicleId ?? null,
    options.driverId, options.userId ?? 1, options.createdAt ?? null, options.active ?? 1).lastInsertRowid);
}

afterEach(() => {
  while (databases.length) databases.pop()!.close();
});

describe('loading service direct persistence contract', () => {
  it('accepts rolling quota boundary and inactive prior entries because quota is informative', () => {
    const db = buildDb();
    const ids = Array.from({ length: 5 }, (_, index) => driver(db, `F${index}`));
    for (const id of ids.slice(0, 3)) {
      assert.ok('id' in loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'fletero', driver_id: id }, ADMIN));
    }
    const fourth = loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:30', driver_type: 'fletero', driver_id: ids[3] }, ADMIN);
    assert.ok('id' in fourth, 'fourth rolling-window fletero remains permitted');
    loading.deactivate(db, (fourth as { id: number }).id);
    assert.ok('id' in loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:30', driver_type: 'fletero', driver_id: ids[4] }, ADMIN));
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM loading_schedules').get() as { count: number }).count, 5);
  });

  it('uses authoritative driver type, validates active references, and prevents active duplicates', () => {
    const db = buildDb();
    const fletero = driver(db, 'F');
    const casa = driver(db, 'C', 'casa');
    const inactive = driver(db, 'I', 'fletero', 0);
    const van = vehicle(db, 'Van');
    const inactiveVan = vehicle(db, 'Old', 0);
    assert.equal((loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'casa', driver_id: fletero, vehicle_id: van }, ADMIN) as { status: number }).status, 400);
    assert.equal((loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'fletero', driver_id: casa }, ADMIN) as { status: number }).status, 400);
    assert.equal((loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'fletero', driver_id: inactive }, ADMIN) as { status: number }).status, 400);
    assert.equal((loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'casa', driver_id: casa, vehicle_id: inactiveVan }, ADMIN) as { status: number }).status, 400);
    const created = loading.create(db, { schedule_date: '2026-08-25', time_slot: '04:00', driver_type: 'fletero', driver_id: fletero }, ADMIN);
    assert.ok('id' in created);
    assert.equal((loading.create(db, { schedule_date: '2026-08-25', time_slot: '05:00', driver_type: 'fletero', driver_id: fletero }, ADMIN) as { status: number }).status, 409);
    const activeCasa = loading.create(db, { schedule_date: '2026-08-27', time_slot: '04:00', driver_type: 'casa', driver_id: casa, vehicle_id: van }, ADMIN);
    assert.ok('id' in activeCasa);
    const anotherCasa = driver(db, 'C2', 'casa');
    assert.equal(
      (loading.create(db, { schedule_date: '2026-08-27', time_slot: '05:00', driver_type: 'casa', driver_id: anotherCasa, vehicle_id: van }, ADMIN) as { status: number }).status,
      409
    );
    schedule(db, { date: '2026-08-26', driverId: casa, vehicleId: van, active: 0 });
    db.prepare('UPDATE loading_schedules SET is_active = 0 WHERE schedule_date = ?').run('2026-08-26');
    assert.equal(
      (loading.create(db, { schedule_date: '2026-08-26', time_slot: '05:00', driver_type: 'casa', driver_id: casa, vehicle_id: van }, ADMIN) as { status: number }).status,
      400,
      'a date containing only inactive entries remains a closed batch'
    );
  });

  it('merges partial updates, authoritatively converts casa/fletero vehicle state, and excludes itself from duplicates', () => {
    const db = buildDb();
    const casa = driver(db, 'C', 'casa');
    const fletero = driver(db, 'F');
    const van = vehicle(db, 'Van');
    const id = schedule(db, { driverId: casa, vehicleId: van, createdAt: new Date().toISOString() });
    const actor = { sub: 1, role: 'Trabalhador' };
    const selfUpdate = loading.update(db, id, { time_slot: '05:00' }, actor) as { time_slot: string };
    assert.equal(selfUpdate.time_slot, '05:00');
    const converted = loading.update(db, id, { driver_id: fletero }, actor) as { driver_type: string; vehicle_id: number | null };
    assert.equal(converted.driver_type, 'fletero');
    assert.equal(converted.vehicle_id, null);
    const secondVan = vehicle(db, 'Second');
    assert.equal((loading.update(db, id, { driver_id: casa, vehicle_id: secondVan }, actor) as { vehicle_id: number }).vehicle_id, secondVan);
    assert.equal((loading.update(db, id, { vehicle_id: 999 }, actor) as { status: number }).status, 400);
    const inactiveVan = vehicle(db, 'Inactive', 0);
    assert.equal((loading.update(db, id, { vehicle_id: inactiveVan }, actor) as { status: number }).status, 400);
    assert.equal((loading.update(db, id, { vehicle_id: null }, actor) as { status: number }).status, 400);
    const otherCasa = driver(db, 'Other Casa', 'casa');
    const fleteroOnlyId = schedule(db, { date: '2026-08-27', driverId: fletero, createdAt: new Date().toISOString() });
    assert.equal((loading.update(db, fleteroOnlyId, { driver_id: otherCasa }, actor) as { status: number }).status, 400);
    schedule(db, { date: '2026-08-26', driverId: fletero, createdAt: new Date().toISOString() });
    assert.equal((loading.update(db, id, { schedule_date: '2026-08-26', driver_id: fletero }, actor) as { status: number }).status, 409);
  });

  it('projects history permissions fail-closed for future rows and aggregates history filters', () => {
    const db = buildDb();
    const first = driver(db, 'First');
    const second = driver(db, 'Second');
    schedule(db, { date: '2026-08-25', driverId: first, createdAt: new Date(Date.now() + 60_000).toISOString() });
    schedule(db, { date: '2026-08-25', time: '04:30', driverId: second, userId: 2 });
    schedule(db, { date: '2026-09-01', driverId: driver(db, 'Third'), userId: 2 });
    const history = loading.listScheduleHistory(db, { month: '2026-08', page: 1, pageSize: 1 }, { sub: 1, role: 'Trabalhador' });
    assert.equal(history.pagination.total, 1);
    assert.equal(history.items[0].total_loadings, 2);
    assert.equal(history.items[0].creator?.id, 2);
    assert.equal(history.items[0].canEdit, false);
    assert.equal(history.items[0].canDelete, true);
    assert.equal(loading.listScheduleHistory(db, { userId: 2, page: 1, pageSize: 30 }, { sub: 2, role: 'Administrador' }).pagination.total, 2);
  });

  it('projects list-by-date ownership and future timestamps fail-closed', () => {
    const db = buildDb();
    const own = driver(db, 'Own');
    const other = driver(db, 'Other');
    schedule(db, { driverId: own, userId: 1, createdAt: new Date().toISOString() });
    schedule(db, { time: '04:30', driverId: other, userId: 2, createdAt: new Date(Date.now() + 60_000).toISOString() });
    const rows = loading.listByDate(db, '2026-08-25', { sub: 1, role: 'Trabalhador' });
    assert.equal(rows.length, 2);
    assert.equal(rows[0].canEdit, true);
    assert.equal(rows[1].canEdit, false);
    assert.equal(rows[1].readOnly, true);
  });

  it('keeps the one-hour window at every loading entry point', () => {
    // The counterpart to the reports window, which allows this very timestamp. Five hours old is
    // outside a one-hour window and inside a current-or-previous-day one, so each of these three
    // assertions fails if a loading call site is ever pointed at the reports rule. Without them the
    // suite stays green through that change, because every other loading fixture is either fresh or
    // in the future -- the two cases where the rules agree.
    const db = buildDb();
    const own = driver(db, 'Own');
    const fiveHoursAgo = new Date(Date.now() - 5 * 60 * 60 * 1000).toISOString();
    const id = schedule(db, { driverId: own, userId: 1, createdAt: fiveHoursAgo });

    const rows = loading.listByDate(db, '2026-08-25', { sub: 1, role: 'Trabalhador' });
    assert.equal(rows[0].canEdit, false, 'a five-hour-old schedule is outside the loading window');
    assert.equal(rows[0].readOnly, true);

    const denied = loading.update(db, id, {}, { sub: 1, role: 'Trabalhador' });
    assert.equal((denied as { status: number }).status, 403, 'the update path enforces the same window');

    const history = loading.listScheduleHistory(db, { month: '2026-08', page: 1, pageSize: 30 }, { sub: 1, role: 'Trabalhador' });
    assert.equal(history.items[0]?.canEdit, false, 'the history path enforces the same window');
  });

  it('removes/deactivates single and batch targets and returns false for missing targets', () => {
    const db = buildDb();
    const id = schedule(db, { driverId: driver(db, 'F') });
    assert.equal(loading.deactivate(db, id), true);
    assert.equal(loading.deactivate(db, id), true, 'already-inactive targets report their matched persistence update');
    assert.equal(loading.deactivate(db, 999), false);
    assert.equal(loading.remove(db, id), true);
    assert.equal(loading.remove(db, 999), false);
    const batch = schedule(db, { date: '2026-08-26', driverId: driver(db, 'B') });
    assert.equal(loading.deactivateBatch(db, '2026-08-26'), true);
    assert.equal(loading.removeBatch(db, '2026-08-26'), true);
    assert.equal(loading.removeBatch(db, 'missing'), false);
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM loading_schedules WHERE schedule_date = ?').get('2026-08-26') as { count: number }).count, 0);
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM loading_schedules WHERE id = ?').get(id) as { count: number }).count, 0);
    assert.ok(batch > 0);
  });

  it('lists active drivers/vehicles and parses configured or fallback time slots', () => {
    const db = buildDb();
    driver(db, 'Inactive', 'fletero', 0);
    const active = driver(db, 'Active');
    vehicle(db, 'Active van');
    vehicle(db, 'Old van', 0);
    assert.ok(loading.listDrivers(db).map((row) => row.id).includes(active));
    assert.ok(loading.listActiveVehicles(db).some((v) => v.description === 'Active van'));
    assert.ok(!loading.listActiveVehicles(db).some((v) => v.description === 'Old van'));
    assert.deepEqual(loading.getTimeSlots(db), ['04:00', '04:30', '05:00', '05:30', '06:00', '06:30', '07:00']);
    db.prepare("UPDATE settings SET value = 'invalid' WHERE key = 'loading_time_slots'").run();
    assert.equal(loading.getTimeSlots(db)[0], '04:00');
  });
});
