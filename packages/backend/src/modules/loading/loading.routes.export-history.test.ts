import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildLoadingTestApp, type TestFixtures } from './loading-test-helper.js';

/**
 * Helper: login as admin and return the JWT token.
 */
async function loginAs(
  app: FastifyInstance,
  username: string,
  password: string
): Promise<string> {
  const res = await app.inject({
    method: 'POST',
    url: '/api/auth/login',
    payload: { username, password },
  });
  return JSON.parse(res.body).token;
}

/**
 * Helper: seed a test fletero driver and return its id.
 */
function seedDriver(
  db: Database.Database,
  name: string,
  license_plate?: string,
  driver_type: 'casa' | 'fletero' = 'fletero'
): number {
  const result = db
    .prepare(`INSERT INTO drivers (name, license_plate, driver_type, is_active) VALUES (?, ?, ?, 1)`)
    .run(name, license_plate ?? null, driver_type);
  return result.lastInsertRowid as number;
}

describe('Loading Schedule Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let token: string;
  let vehicleId: number;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildLoadingTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
    token = await loginAs(app, fixtures.admin.username, fixtures.admin.password);
    vehicleId = (db.prepare("SELECT id FROM vehicles WHERE license_plate = 'BDG'").get() as { id: number }).id;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  // ---- Authentication ----

  it('GET /export returns the next-day PT-BR WhatsApp layout, casa vehicle plate, and total count', async () => {
    const casaDriverId = seedDriver(db, 'Edinaldo', 'DRV-CASA', 'casa');
    const fleteroDriverId = seedDriver(db, 'Diego', 'DRV-FLET', 'fletero');

    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-17',
        time_slot: '05:00',
        driver_type: 'casa',
        driver_id: casaDriverId,
        vehicle_id: vehicleId,
      },
    });
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-17',
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: fleteroDriverId,
      },
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/export?date=2026-06-17',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const text: string = JSON.parse(res.body).text;

    assert.ok(text.startsWith('🚛 *CRONOGRAMA DE CARREGAMENTO*'));
    assert.ok(text.includes('📅 Quinta-feira • 18/06/2026'));
    assert.ok(text.includes('━━━━━━━━━━━━━━'));
    assert.ok(text.includes('🕓 04:00  │ Diego'));
    const vehiclePlate = (db.prepare('SELECT license_plate FROM vehicles WHERE id = ?').get(vehicleId) as { license_plate: string }).license_plate;
    assert.ok(text.includes(`🕔 05:00  │ Edinaldo 🚚 ${vehiclePlate}`));
    assert.ok(text.includes('📌 Total de carregamentos: *2*'));
    assert.ok(text.includes('✅ Bom trabalho a todos!'));
    assert.ok(text.includes('🏭 Trindade Massas — Sistema de Carregamento'));
    assert.ok(text.indexOf('04:00') < text.indexOf('05:00'), '04:00 should appear before 05:00');
  });

  it('GET /export returns no-loadings message for empty schedule', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/export?date=2026-12-25',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(body.text.includes('Nenhum carregamento agendado'));
  });

  it('GET /export validates date format and returns 400 on bad input', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/export?date=not-a-date',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  it('GET /export fails explicitly when a required loading reference is missing', async () => {
    db.pragma('foreign_keys = OFF');
    db.prepare("INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, vehicle_id, driver_type, user_id) VALUES (9990, '2026-12-26', '04:00', 9999, NULL, 'fletero', ?)").run(fixtures.admin.id);
    const response = await app.inject({ method: 'GET', url: '/api/loading/export?date=2026-12-26', headers: { authorization: `Bearer ${token}` } });
    assert.equal(response.statusCode, 500);
    db.prepare('DELETE FROM loading_schedules WHERE id = 9990').run();
    db.pragma('foreign_keys = ON');
  });

  // ---- Input Validation ----

  it('GET /schedules/history returns one batch item with total count and creator flags', async () => {
    const d1 = seedDriver(db, 'History Driver A', 'HIS-1');
    const d2 = seedDriver(db, 'History Driver B', 'HIS-2');
    db.prepare(`INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id, created_at) VALUES (9201, '2026-06-18', '04:00', ?, 'fletero', 1, datetime('now', '-30 minutes'))`).run(d1);
    db.prepare(`INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id, created_at) VALUES (9202, '2026-06-18', '04:30', ?, 'fletero', NULL, datetime('now', '-25 minutes'))`).run(d2);

    const res = await app.inject({ method: 'GET', url: '/api/loading/schedules/history?date=2026-06-18', headers: { authorization: `Bearer ${token}` } });
    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.pagination.total, 1);
    assert.strictEqual(body.items.length, 1);
    assert.strictEqual(body.items[0].batch_date, '2026-06-18');
    assert.strictEqual(body.items[0].loading_date, '2026-06-19');
    assert.strictEqual(body.items[0].total_loadings, 2);
    assert.strictEqual(body.items[0].creator.id, 1);
    assert.strictEqual(body.items[0].canEdit, true);
  });

  it('GET /schedules/history paginates batches and combines month/user filters', async () => {
    const insertDriver = db.prepare(`INSERT INTO drivers (id, name, license_plate, driver_type, is_active) VALUES (?, ?, ?, 'fletero', 1)`);
    const insertSchedule = db.prepare(`
      INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id, created_at)
      VALUES (?, ?, ?, ?, 'fletero', ?, ?)
    `);

    for (let i = 0; i < 35; i += 1) {
      const day = String(i + 1).padStart(2, '0');
      const driverId = 31000 + i;
      insertDriver.run(driverId, `History Page Driver ${i}`, `HPD-${i}`);
      insertSchedule.run(31000 + i, `2026-04-${day}`, '04:00', driverId, 1, `2026-04-${day} 08:00:00`);
    }
    for (let i = 0; i < 5; i += 1) {
      const otherUserDriverId = 31100 + i;
      const otherMonthDriverId = 31200 + i;
      insertDriver.run(otherUserDriverId, `Other User Driver ${i}`, `OUD-${i}`);
      insertDriver.run(otherMonthDriverId, `Other Month Driver ${i}`, `OMD-${i}`);
      insertSchedule.run(31100 + i, `2026-04-${String(i + 1).padStart(2, '0')}`, '05:00', otherUserDriverId, 2, `2026-04-20 09:${String(i).padStart(2, '0')}:00`);
      insertSchedule.run(31200 + i, `2026-05-${String(i + 1).padStart(2, '0')}`, '06:00', otherMonthDriverId, 1, `2026-05-20 09:${String(i).padStart(2, '0')}:00`);
    }

    const firstPage = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules/history?month=2026-04&userId=1&page=1&pageSize=30',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(firstPage.statusCode, 200);
    const pageOne = JSON.parse(firstPage.body);
    assert.strictEqual(pageOne.pagination.total, 35);
    assert.strictEqual(pageOne.pagination.totalPages, 2);
    assert.strictEqual(pageOne.items.length, 30);
    assert.ok(pageOne.items.every((item: any) => item.creator?.id === 1));
    assert.ok(pageOne.items.every((item: any) => item.batch_date.startsWith('2026-04')));

    const secondPage = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules/history?month=2026-04&userId=1&page=2&pageSize=30',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(secondPage.statusCode, 200);
    const pageTwo = JSON.parse(secondPage.body);
    assert.strictEqual(pageTwo.items.length, 5);
    assert.deepStrictEqual(pageTwo.items.map((item: any) => item.batch_date), ['2026-04-05', '2026-04-04', '2026-04-03', '2026-04-02', '2026-04-01']);
  });

  it('GET /export for Friday schedule shifts date to next Monday and includes creator display_name', async () => {
    const driverId = seedDriver(db, 'Casa Driver Fri', undefined, 'casa');

    // 2026-06-19 is a Friday. Create schedule on that day
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-19',
        time_slot: '04:00',
        driver_type: 'casa',
        driver_id: driverId,
        vehicle_id: vehicleId,
      },
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/export?date=2026-06-19',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const text: string = JSON.parse(res.body).text;

    // Friday export should show next Monday: 2026-06-22
    assert.ok(text.includes('📅 Segunda-feira • 22/06/2026'));
    // Should display the responsible name
    assert.ok(text.includes('👤 *Responsável:* '));
  });

  it('GET /schedules/history for Friday schedule keeps Friday date as loading_date', async () => {
    const driverId = seedDriver(db, 'Casa Driver Fri 2', undefined, 'casa');

    // 2026-06-26 is a Friday. Create schedule
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-26',
        time_slot: '04:00',
        driver_type: 'casa',
        driver_id: driverId,
        vehicle_id: vehicleId,
      },
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules/history?date=2026-06-26',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.items[0].batch_date, '2026-06-26');
    // loading_date in history must be Friday itself (2026-06-26) instead of Saturday
    assert.strictEqual(body.items[0].loading_date, '2026-06-26');
  });

});
