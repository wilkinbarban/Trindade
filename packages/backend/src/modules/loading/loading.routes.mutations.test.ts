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

  it('DELETE /schedules/:id removes a schedule entry', async () => {
    const driverId = seedDriver(db, 'Fletero Delete', 'DEL0001');

    const createRes = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-09-01',
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });
    const scheduleId = JSON.parse(createRes.body).schedule.id;

    const delRes = await app.inject({
      method: 'DELETE',
      url: `/api/loading/schedules/${scheduleId}`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(delRes.statusCode, 204);

    // Verify it's gone
    const listRes = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=2026-09-01',
      headers: { authorization: `Bearer ${token}` },
    });
    const listBody = JSON.parse(listRes.body);
    assert.strictEqual(listBody.schedules.length, 0);
  });

  it('DELETE /schedules/:id releases casa and fletero drivers for another active slot', async () => {
    const fleteroId = seedDriver(db, 'Fletero Reassign', 'REA-FLT');
    const casaId = seedDriver(db, 'Casa Reassign', 'REA-CAS', 'casa');
    const date = '2026-09-02';

    for (const [driverId, driverType, assignedVehicleId] of [
      [fleteroId, 'fletero', undefined],
      [casaId, 'casa', vehicleId],
    ] as const) {
      const created = await app.inject({
        method: 'POST',
        url: '/api/loading/schedules',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          schedule_date: date,
          time_slot: '04:00',
          driver_type: driverType,
          driver_id: driverId,
          ...(assignedVehicleId ? { vehicle_id: assignedVehicleId } : {}),
        },
      });
      assert.strictEqual(created.statusCode, 201);

      const scheduleId = JSON.parse(created.body).schedule.id;
      const deleted = await app.inject({
        method: 'DELETE',
        url: `/api/loading/schedules/${scheduleId}`,
        headers: { authorization: `Bearer ${token}` },
      });
      assert.strictEqual(deleted.statusCode, 204);

      const reassigned = await app.inject({
        method: 'POST',
        url: '/api/loading/schedules',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          schedule_date: date,
          time_slot: '05:00',
          driver_type: driverType,
          driver_id: driverId,
          ...(assignedVehicleId ? { vehicle_id: assignedVehicleId } : {}),
        },
      });
      assert.strictEqual(reassigned.statusCode, 201);
    }
  });

  it('DELETE /schedules/:id returns 404 for non-existent entry', async () => {
    const res = await app.inject({
      method: 'DELETE',
      url: '/api/loading/schedules/99999',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  // ---- Export ----

  it('PATCH /schedules/:id updates time_slot successfully', async () => {
    const driverId = seedDriver(db, 'Fletero Patch', 'PTCH001');
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-11-01',
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: driverId,
      },
    });
    const scheduleId = JSON.parse(createRes.body).schedule.id;

    const res = await app.inject({
      method: 'PATCH',
      url: `/api/loading/schedules/${scheduleId}`,
      headers: { authorization: `Bearer ${token}` },
      payload: { time_slot: '06:00' },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.schedule.time_slot, '06:00');
    assert.strictEqual(body.schedule.id, scheduleId);
    assert.strictEqual(body.schedule.driver_name, 'Fletero Patch');
  });

  it('PATCH /schedules/:id updates successfully even if it exceeds rolling quota', async () => {
    // Fill 04:30 slot with 3 fleteros on 2026-11-15
    const d1 = seedDriver(db, 'Fletero Rollback A', 'RBK001');
    const d2 = seedDriver(db, 'Fletero Rollback B', 'RBK002');
    const d3 = seedDriver(db, 'Fletero Rollback C', 'RBK003');
    const d4 = seedDriver(db, 'Fletero Target', 'RBK004');

    for (const did of [d1, d2, d3]) {
      await app.inject({
        method: 'POST',
        url: '/api/loading/schedules',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          schedule_date: '2026-11-15',
          time_slot: '04:30',
          driver_type: 'fletero',
          driver_id: did,
        },
      });
    }

    // Create entry for d4 in a different slot (06:00)
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        schedule_date: '2026-11-15',
        time_slot: '06:00',
        driver_type: 'fletero',
        driver_id: d4,
      },
    });
    const scheduleId = JSON.parse(createRes.body).schedule.id;

    // Move d4 to 04:30 (exceeding the quota of 3 fleteros)
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/loading/schedules/${scheduleId}`,
      headers: { authorization: `Bearer ${token}` },
      payload: { time_slot: '04:30' },
    });

    assert.strictEqual(res.statusCode, 200, 'should allow updating slot even if exceeding quota');
    const body = JSON.parse(res.body);
    assert.strictEqual(body.schedule.time_slot, '04:30');

    // Verify d4 is successfully in 04:30
    const getRes = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules?date=2026-11-15',
      headers: { authorization: `Bearer ${token}` },
    });
    const list = JSON.parse(getRes.body);
    const entry = list.schedules.find((s: any) => s.id === scheduleId);
    assert.ok(entry, 'entry should still exist');
    assert.strictEqual(entry.time_slot, '04:30', 'time_slot should have changed');
  });

  it('PATCH /schedules/:id returns 404 for non-existent entry', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/loading/schedules/99999',
      headers: { authorization: `Bearer ${token}` },
      payload: { time_slot: '04:00' },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  it('PATCH /schedules/:id returns 400 for invalid payload', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/loading/schedules/1',
      headers: { authorization: `Bearer ${token}` },
      payload: { time_slot: '' },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  it('PATCH /schedules/:id returns 401 without auth', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/loading/schedules/1',
      payload: { time_slot: '04:00' },
    });

    assert.strictEqual(res.statusCode, 401);
  });

  it('admin can deactivate and delete a loading batch transactionally', async () => {
    const d1 = seedDriver(db, 'Batch Admin Driver A', 'BAD-1');
    const d2 = seedDriver(db, 'Batch Admin Driver B', 'BAD-2');
    db.prepare(`
      INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id, created_at)
      VALUES (32000, '2026-06-19', '04:00', ?, 'fletero', NULL, datetime('now', '-30 minutes'))
    `).run(d1);
    db.prepare(`
      INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id, created_at)
      VALUES (32001, '2026-06-19', '04:30', ?, 'fletero', NULL, datetime('now', '-29 minutes'))
    `).run(d2);

    const history = await app.inject({
      method: 'GET',
      url: '/api/loading/schedules/history?date=2026-06-19',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(history.statusCode, 200);
    const batch = JSON.parse(history.body).items[0];
    assert.strictEqual(batch.creator, null);
    assert.strictEqual(batch.readOnly, true);
    assert.strictEqual(batch.canDeactivate, true);
    assert.strictEqual(batch.canDelete, true);
    assert.strictEqual(batch.total_loadings, 2);

    const deactivated = await app.inject({
      method: 'PATCH',
      url: '/api/loading/schedules/batch/2026-06-19/deactivate',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(deactivated.statusCode, 200);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM loading_schedules WHERE schedule_date = ? AND is_active = 0').get('2026-06-19') as any).count, 2);

    const deleted = await app.inject({
      method: 'DELETE',
      url: '/api/loading/schedules/batch/2026-06-19',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(deleted.statusCode, 204);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM loading_schedules WHERE schedule_date = ?').get('2026-06-19') as any).count, 0);
  });

  it('worker can delete only loading history records it owns', async () => {
    const workerToken = await loginAs(app, fixtures.worker.username, fixtures.worker.password);
    const driverId = seedDriver(db, 'Worker Delete Driver', 'WRK-1');
    db.prepare(`INSERT INTO loading_schedules (id, schedule_date, time_slot, driver_id, driver_type, user_id) VALUES (9203, '2026-06-18', '05:00', ?, 'fletero', ?)`).run(driverId, fixtures.admin.id);
    const res = await app.inject({ method: 'DELETE', url: '/api/loading/schedules/9203', headers: { authorization: `Bearer ${workerToken}` } });
    assert.strictEqual(res.statusCode, 403);
    db.prepare('UPDATE loading_schedules SET user_id = ? WHERE id = 9203').run(fixtures.worker.id);
    const ownRes = await app.inject({ method: 'DELETE', url: '/api/loading/schedules/9203', headers: { authorization: `Bearer ${workerToken}` } });
    assert.strictEqual(ownRes.statusCode, 204);
  });

});
