import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildDashboardTestApp, type TestFixtures } from './dashboard-test-helper.js';

describe('Dashboard Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildDashboardTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('GET /api/dashboard/summary returns counts with valid token', async () => {
    // Login first
    const loginRes = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.admin,
    });
    const { token } = JSON.parse(loginRes.body);

    const response = await app.inject({
      method: 'GET',
      url: '/api/dashboard/summary',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(response.statusCode, 200);

    const body = JSON.parse(response.body);
    assert.strictEqual(typeof body.reportsToday, 'number');
    assert.strictEqual(typeof body.schedulesTomorrow, 'number');
    assert.strictEqual(typeof body.activeUsers, 'number');
    assert.strictEqual(typeof body.reportsTotal, 'number');
    assert.strictEqual(typeof body.schedulesTotal, 'number');
    assert.ok(body.latestReportId === null || typeof body.latestReportId === 'number');
    assert.strictEqual(typeof body.higieneDone, 'number');
    assert.strictEqual(typeof body.higieneTotal, 'number');
    assert.strictEqual(typeof body.recepcionDone, 'number');
    assert.strictEqual(typeof body.recepcionTotal, 'number');

    // The test app creates one administrator and one worker.
    assert.strictEqual(body.activeUsers, 2);
    // No reports or schedules yet
    assert.strictEqual(body.reportsToday, 0);
    assert.strictEqual(body.schedulesTomorrow, 0);
    assert.strictEqual(body.reportsTotal, 0);
    assert.strictEqual(body.schedulesTotal, 0);
    assert.strictEqual(body.latestReportId, null);
    assert.strictEqual(body.higieneDone, 0);
    const higieneTotal = (db.prepare(`SELECT COUNT(*) AS count FROM report_tasks rt JOIN report_categories rc ON rc.id = rt.category_id WHERE rc.name_pt = 'Higiene e Organização' AND rt.is_active = 1`).get() as { count: number }).count;
    assert.strictEqual(body.higieneTotal, higieneTotal);
    assert.strictEqual(body.recepcionDone, 0);
    const recepcionTotal = (db.prepare(`SELECT COUNT(*) AS count FROM report_tasks rt JOIN report_categories rc ON rc.id = rt.category_id WHERE rc.id = 4 AND rt.is_active = 1`).get() as { count: number }).count;
    assert.strictEqual(body.recepcionTotal, recepcionTotal);
  });

  it('GET /api/dashboard/summary returns 401 without token', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/api/dashboard/summary',
    });

    assert.strictEqual(response.statusCode, 401);
  });

  it('GET /api/dashboard/summary returns 401 with invalid token', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/api/dashboard/summary',
      headers: { authorization: 'Bearer invalid-token-here' },
    });

    assert.strictEqual(response.statusCode, 401);
  });

  it('GET /api/dashboard/summary counts only tomorrow schedules in São Paulo', async (t) => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-08-24T02:30:00.000Z') });
    t.after(() => t.mock.timers.reset());

    db.prepare(
      `INSERT INTO loading_schedules (schedule_date, time_slot, driver_type, is_active)
       VALUES (?, '04:00', 'fletero', 1), (?, '04:30', 'fletero', 1), (?, '05:00', 'fletero', 1)`
    ).run('2026-08-23', '2026-08-23', '2026-08-24');

    const loginRes = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.admin,
    });
    const { token } = JSON.parse(loginRes.body);

    const response = await app.inject({
      method: 'GET',
      url: '/api/dashboard/summary',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(response.statusCode, 200);
    assert.strictEqual(JSON.parse(response.body).schedulesTomorrow, 1);
  });
});
