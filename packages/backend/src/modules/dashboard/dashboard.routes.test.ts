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

  // ---- The operational window: which loading day the card is about (T34) ----
  //
  // A loading day is prepared the day before and loaded from 04:00 on. Until the cutoff the day being
  // loaded is today's, which was prepared yesterday; from the cutoff on, today's loading is over and the
  // one being prepared is the next loading day -- the same day the WhatsApp export announces, because
  // the card and the message must not name two different days.

  /**
   * Log in once and read the summary for a set of loading rows, so each case states only its own rows.
   *
   * The clock is the test's: `t.mock.timers.enable({ apis: ['Date'] })` decides what "now" is, and every
   * expectations in these cases is written as a São Paulo local time in a comment beside it.
   */
  async function summaryWith(rows: ReadonlyArray<readonly [string, number]>): Promise<Record<string, unknown>> {
    db.prepare('DELETE FROM loading_schedules').run();
    for (const [date, isActive] of rows) {
      db.prepare(
        `INSERT INTO loading_schedules (schedule_date, time_slot, driver_type, is_active) VALUES (?, '04:00', 'fletero', ?)`,
      ).run(date, isActive);
    }
    const loginRes = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });
    const { token } = JSON.parse(loginRes.body);
    const response = await app.inject({
      method: 'GET',
      url: '/api/dashboard/summary',
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(response.statusCode, 200, response.body);
    return JSON.parse(response.body) as Record<string, unknown>;
  }

  it('before the cutoff the active schedule is today\'s, the one prepared yesterday', async (t) => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-08-24T11:00:00.000Z') }); // 08:00 in São Paulo
    t.after(() => t.mock.timers.reset());

    const body = await summaryWith([
      ['2026-08-24', 1],
      ['2026-08-24', 1],
      ['2026-08-25', 1],
      ['2026-08-24', 0], // a deactivated row must not count
    ]);

    assert.strictEqual(body.activeScheduleDate, '2026-08-24');
    assert.strictEqual(body.activeScheduleCount, 2);
  });

  it('from the cutoff the active schedule is the next loading day', async (t) => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-08-24T13:30:00.000Z') }); // 10:30 in São Paulo
    t.after(() => t.mock.timers.reset());

    const body = await summaryWith([
      ['2026-08-24', 1],
      ['2026-08-24', 1],
      ['2026-08-25', 1],
      ['2026-08-25', 0], // a deactivated row must not count
    ]);

    assert.strictEqual(body.activeScheduleDate, '2026-08-25');
    assert.strictEqual(body.activeScheduleCount, 1);
  });

  it('the cutoff is ten o\'clock itself, not a minute earlier nor later', async (t) => {
    t.after(() => t.mock.timers.reset());

    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-08-24T12:59:59.000Z') }); // 09:59:59
    const before = await summaryWith([['2026-08-24', 1]]);
    assert.strictEqual(before.activeScheduleDate, '2026-08-24', '09:59:59 is still the day being loaded');

    t.mock.timers.setTime(new Date('2026-08-24T13:00:00.000Z').getTime()); // 10:00:00
    const at = await summaryWith([['2026-08-25', 1]]);
    assert.strictEqual(at.activeScheduleDate, '2026-08-25', '10:00:00 already belongs to the next loading day');
  });

  it('a Friday after the cutoff points at Monday, which is the day the WhatsApp text announces', async (t) => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-08-28T13:30:00.000Z') }); // Friday, 10:30 in São Paulo
    t.after(() => t.mock.timers.reset());

    const body = await summaryWith([
      ['2026-08-29', 1], // the Saturday nobody loads
      ['2026-08-31', 1],
      ['2026-08-31', 1],
    ]);

    assert.strictEqual(body.activeScheduleDate, '2026-08-31');
    assert.strictEqual(body.activeScheduleCount, 2);
  });
});
