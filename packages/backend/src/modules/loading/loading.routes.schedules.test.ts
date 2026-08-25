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

  it('POST /schedules creates a schedule entry and returns 201', async () => {
    const driverId = seedDriver(db, 'Fletero 1', 'AAA1111');

    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-15',
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(body.schedule, 'schedule should be present');
    assert.strictEqual(body.schedule.schedule_date, '2026-06-15');
    assert.strictEqual(body.schedule.time_slot, '04:00');
    assert.strictEqual(body.schedule.driver_type, 'fletero');
    assert.strictEqual(body.schedule.driver_id, driverId);
    assert.strictEqual(body.schedule.driver_name, 'Fletero 1');
    assert.strictEqual(body.schedule.license_plate, 'AAA1111');
    assert.ok(body.schedule.id, 'schedule should have an id');
  });

  // ---- Schedules: List ----

  it('GET /schedules returns entries sorted by time_slot ascending', async () => {
    const d1 = seedDriver(db, 'Fletero Lista A', 'LST0001');
    const d2 = seedDriver(db, 'Fletero Lista B', 'LST0002');

    // Create entries in non-ascending order
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-20',
        time_slot: '06:00',
        driver_type: 'fletero',
        driver_id: d1,
      },
    });

    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-06-20',
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: d2,
      },
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=2026-06-20',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.schedules), 'schedules should be an array');
    assert.strictEqual(body.schedules.length, 2);

    // Ascending order check
    assert.strictEqual(body.schedules[0].time_slot, '04:00');
    assert.strictEqual(body.schedules[1].time_slot, '06:00');
  });

  it('GET /schedules returns empty array for date with no entries', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=2026-12-31',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.schedules));
    assert.strictEqual(body.schedules.length, 0);
  });

  it('GET /schedules validates date format and returns 400 on bad input', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=invalid',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  // ---- Quota Enforcement: Max 3 fleteros ----

  it('POST /schedules accepts the 3rd fletero (within quota)', async () => {
    const d1 = seedDriver(db, 'Fletero Quota A', 'QTA0001');
    const d2 = seedDriver(db, 'Fletero Quota B', 'QTA0002');
    const d3 = seedDriver(db, 'Fletero Quota C', 'QTA0003');

    // Slot: 04:30 on 2026-07-01
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-07-01',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: d1,
      },
    });
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-07-01',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: d2,
      },
    });

    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-07-01',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: d3,
      },
    });

    assert.strictEqual(res.statusCode, 201, '3rd fletero should be accepted');
  });

  it('POST /schedules allows 4th fletero (informative limit)', async () => {
    const d4 = seedDriver(db, 'Fletero Quota D', 'QTA0004');

    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-07-01',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: d4,
      },
    });

    assert.strictEqual(res.statusCode, 201, '4th fletero should be allowed');
  });

  it('POST /schedules allows scheduling even if rolling 60-minute window is overloaded', async () => {
    const d1 = seedDriver(db, 'Fletero Roll A', 'ROL0001');
    const d2 = seedDriver(db, 'Fletero Roll B', 'ROL0002');
    const d3 = seedDriver(db, 'Fletero Roll C', 'ROL0003');
    const d4 = seedDriver(db, 'Fletero Roll D', 'ROL0004');
    const d5 = seedDriver(db, 'Fletero Roll E', 'ROL0005');
    const d6 = seedDriver(db, 'Fletero Roll F', 'ROL0006');
    const d7 = seedDriver(db, 'Fletero Roll G', 'ROL0007');
    const d8 = seedDriver(db, 'Fletero Roll H', 'ROL0008');

    // 1. Schedule 3 fleteros at 04:00.
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '04:00', driver_type: 'fletero', driver_id: d1 },
    });
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '04:00', driver_type: 'fletero', driver_id: d2 },
    });
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '04:00', driver_type: 'fletero', driver_id: d3 },
    });

    // 2. Schedule a 4th fletero at 04:30 (should succeed, since it is now informative).
    const res4 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '04:30', driver_type: 'fletero', driver_id: d4 },
    });
    assert.strictEqual(res4.statusCode, 201, '4th fletero at 04:30 should be allowed');

    // 3. 05:00 is exactly 60 minutes after 04:00, so it starts a new allowed window.
    const res5 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '05:00', driver_type: 'fletero', driver_id: d5 },
    });
    assert.strictEqual(res5.statusCode, 201, '1st fletero at 05:00 should be accepted');

    const res6 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '05:00', driver_type: 'fletero', driver_id: d6 },
    });
    assert.strictEqual(res6.statusCode, 201, '2nd fletero at 05:00 should be accepted');

    const res7 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '05:00', driver_type: 'fletero', driver_id: d7 },
    });
    assert.strictEqual(res7.statusCode, 201, '3rd fletero at 05:00 should be accepted');

    const res8 = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: { schedule_date: '2026-12-20', time_slot: '05:00', driver_type: 'fletero', driver_id: d8 },
    });
    assert.strictEqual(res8.statusCode, 201, '4th fletero at 05:00 should be accepted');
  });

  it('POST /schedules validates driver-vehicle constraints for casa and fletero drivers', async () => {
    const casaDriverId = seedDriver(db, 'Motorista Casa', 'CAS0001', 'casa');
    const fleteroDriverId = seedDriver(db, 'Motorista Terceiro', 'FLE0001', 'fletero');

    // 1. Casa driver without vehicle -> should fail with 400
    const resCasaNoVehicle = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-12-21',
        time_slot: '04:00',
        driver_type: 'casa',
        driver_id: casaDriverId,
      },
    });
    assert.strictEqual(resCasaNoVehicle.statusCode, 400);

    // 2. Casa driver with active vehicle -> should succeed with 201
    const resCasaWithVehicle = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-12-21',
        time_slot: '04:00',
        driver_type: 'casa',
        driver_id: casaDriverId,
        vehicle_id: vehicleId,
      },
    });
    assert.strictEqual(resCasaWithVehicle.statusCode, 201);

    // 3. Fletero driver with vehicle -> should fail with 400 (Zod refine blocks it)
    const resFleteroWithVehicle = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-12-21',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: fleteroDriverId,
        vehicle_id: vehicleId,
      },
    });
    assert.strictEqual(resFleteroWithVehicle.statusCode, 400);

    // 4. Fletero driver without vehicle -> should succeed with 201
    const resFleteroNoVehicle = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-12-21',
        time_slot: '04:30',
        driver_type: 'fletero',
        driver_id: fleteroDriverId,
      },
    });
    assert.strictEqual(resFleteroNoVehicle.statusCode, 201);
  });

  // ---- Duplicate Prevention ----

  it('POST /schedules rejects duplicate driver in another slot on the same batch date', async () => {
    const driverId = seedDriver(db, 'Fletero Dup', 'DUP0001');

    // First: succeed
    await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-08-01',
        time_slot: '05:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });

    // Second: duplicate
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-08-01',
        time_slot: '06:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });

    assert.strictEqual(res.statusCode, 409);
    const body = JSON.parse(res.body);
    assert.ok(
      body.error.includes('agendado') || body.error.includes('Motorista'),
      'error should mention duplicate'
    );
  });

  // ---- Schedules: Delete ----

});
