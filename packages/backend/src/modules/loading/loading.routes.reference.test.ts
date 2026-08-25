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

  it('unauthenticated requests return 401', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=2026-06-12',
    });
    assert.strictEqual(res.statusCode, 401);
  });

  // ---- Time Slots ----

  it('GET /time-slots returns configured time slots', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/time-slots',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.timeSlots), 'timeSlots should be an array');
    assert.ok(body.timeSlots.length >= 7, 'should have at least 7 time slots');
    assert.ok(body.timeSlots.includes('04:00'));
    assert.ok(body.timeSlots.includes('07:00'));
  });

  // ---- Drivers: List ----

  it('GET /drivers returns only active drivers', async () => {
    // Seed one active and one inactive driver
    seedDriver(db, 'Ativo Silva', 'ABC1234');
    seedDriver(db, 'Ativo Souza', 'DEF5678');
    const inactiveId = seedDriver(db, 'Inativo Teste', 'XYZ9999');
    db.prepare('UPDATE drivers SET is_active = 0 WHERE id = ?').run(inactiveId);

    const res = await app.inject({
      method: 'GET',
      url: '/api/loading/drivers',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.drivers), 'drivers should be an array');
    assert.ok(body.drivers.length >= 2, 'should have at least 2 active drivers');

    const names: string[] = body.drivers.map((d: any) => d.name);
    assert.ok(names.includes('Ativo Silva'));
    assert.ok(names.includes('Ativo Souza'));
    assert.ok(!names.includes('Inativo Teste'), 'inactive driver should not appear');
  });

  // ---- Drivers: Create ----

  it('POST /drivers creates a new fletero and returns 201', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/drivers',
      headers: { authorization: `Bearer ${token}` },
      payload: { name: 'João Caminhoneiro', license_plate: 'JKL4321' },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(body.driver, 'driver should be present');
    assert.strictEqual(body.driver.name, 'João Caminhoneiro');
    assert.strictEqual(body.driver.license_plate, 'JKL4321');
    assert.ok(body.driver.id, 'driver should have an id');

    // Verify it appears in list immediately
    const listRes = await app.inject({
      method: 'GET',
      url: '/api/loading/drivers',
      headers: { authorization: `Bearer ${token}` },
    });
    const list = JSON.parse(listRes.body);
    assert.ok(list.drivers.some((d: any) => d.name === 'João Caminhoneiro'));
  });

  it('POST /drivers creates driver without license plate (optional)', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/drivers',
      headers: { authorization: `Bearer ${token}` },
      payload: { name: 'Sem Placa' },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.driver.name, 'Sem Placa');
    assert.strictEqual(body.driver.license_plate, null);
  });

  it('POST /drivers validates body and returns 400 on empty name', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/drivers',
      headers: { authorization: `Bearer ${token}` },
      payload: { name: '' },
    });

    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, 'Invalid input');
  });

  // ---- Schedules: Create ----

});
