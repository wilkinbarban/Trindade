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

  it('POST /schedules validates body and returns 400 on missing fields', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-01',
        // missing time_slot, driver_type
      },
    });

    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, 'Invalid input');
  });

  it('POST /schedules rejects fletero without driver_id (refine rule)', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-01',
        time_slot: '04:00',
        driver_type: 'fletero',
        // no driver_id
      },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  // ---- Schedules: PATCH (Update) ----

  it('POST /schedules rejects duplicate vehicle assignment on the same day in different slots', async () => {
    const driver1 = seedDriver(db, 'Casa Driver X', undefined, 'casa');
    const driver2 = seedDriver(db, 'Casa Driver Y', undefined, 'casa');

    // Assign driver1 with vehicle 1 in 04:00 slot on 2026-06-25
    const res1 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-25',
        time_slot: '04:00',
        driver_type: 'casa',
        driver_id: driver1,
        vehicle_id: vehicleId,
      },
    });
    assert.strictEqual(res1.statusCode, 201);

    // Try to assign driver2 with the same vehicle 1 in 05:00 slot on 2026-06-25
    const res2 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-25',
        time_slot: '05:00',
        driver_type: 'casa',
        driver_id: driver2,
        vehicle_id: vehicleId,
      },
    });
    assert.strictEqual(res2.statusCode, 409);
    assert.ok(JSON.parse(res2.body).error.includes('Veículo já agendado'));
  });

  it('POST /schedules blocks adding active slots to a date with only deactivated entries', async () => {
    const driverId = seedDriver(db, 'Inactive Batch Test Driver', 'IBT-1');
    const targetDate = '2026-09-15';

    // 1. Create a schedule entry
    const res1 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: targetDate,
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });
    assert.strictEqual(res1.statusCode, 201);

    // 2. Deactivate the schedule batch for that date
    const deactRes = await app.inject({
      method: 'PATCH',
      url: `/api/loading/schedules/batch/${targetDate}/deactivate`,
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(deactRes.statusCode, 200);

    // 3. Attempt to add a new slot to that same date. Should be blocked.
    const res2 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}`, 'accept-language': 'es' },
      payload: {
        schedule_date: targetDate,
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });
    assert.strictEqual(res2.statusCode, 400);
    const body2 = JSON.parse(res2.body);
    assert.strictEqual(body2.error, 'Ya existe un cronograma para esta fecha en el historial.');

    // Attempt with Portuguese header
    const res2Pt = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}`, 'accept-language': 'pt-BR' },
      payload: {
        schedule_date: targetDate,
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });
    assert.strictEqual(res2Pt.statusCode, 400);
    const body2Pt = JSON.parse(res2Pt.body);
    assert.strictEqual(body2Pt.error, 'Já existe um cronograma para esta data no histórico.');

    // 4. Delete the batch to clean up
    await app.inject({
      method: 'DELETE',
      url: `/api/loading/schedules/batch/${targetDate}`,
      headers: { authorization: `Bearer ${token}` },
    });
  });

});
