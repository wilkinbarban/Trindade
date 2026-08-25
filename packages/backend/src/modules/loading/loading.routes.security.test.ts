import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildLoadingTestApp, type TestFixtures } from './loading-test-helper.js';

async function login(app: FastifyInstance, username: string, password: string): Promise<string> {
  const response = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { username, password } });
  return JSON.parse(response.body).token;
}

function insertDriver(db: Database.Database, name: string): number {
  return Number(db.prepare("INSERT INTO drivers (name, driver_type) VALUES (?, 'fletero')").run(name).lastInsertRowid);
}

function insertSchedule(db: Database.Database, driverId: number, userId: number, createdAt = "datetime('now')", active = 1): number {
  return Number(db.prepare(
    `INSERT INTO loading_schedules (schedule_date, time_slot, driver_id, driver_type, user_id, created_at, is_active)
     VALUES ('2026-08-25', '04:00', ?, 'fletero', ?, ${createdAt}, ?)`
  ).run(driverId, userId, active).lastInsertRowid);
}

describe('Loading route security', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;
  let adminToken: string;
  let workerToken: string;

  before(async () => {
    ({ app, db, fixtures } = await buildLoadingTestApp());
    adminToken = await login(app, fixtures.admin.username, fixtures.admin.password);
    workerToken = await login(app, fixtures.worker.username, fixtures.worker.password);
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('denies worker deactivation and batch lifecycle mutations without changing data or writing audit records', async () => {
    const id = insertSchedule(db, insertDriver(db, 'Denied'), fixtures.worker.id);
    const beforeAudit = (db.prepare('SELECT COUNT(*) AS count FROM audit_logs').get() as { count: number }).count;
    for (const request of [
      { method: 'PATCH', url: `/api/loading/schedules/${id}/deactivate` },
      { method: 'PATCH', url: '/api/loading/schedules/batch/2026-08-25/deactivate' },
      { method: 'DELETE', url: '/api/loading/schedules/batch/2026-08-25' },
    ] as const) {
      const response = await app.inject({ ...request, headers: { authorization: `Bearer ${workerToken}` } });
      assert.equal(response.statusCode, 403);
    }
    assert.equal((db.prepare('SELECT is_active FROM loading_schedules WHERE id = ?').get(id) as { is_active: number }).is_active, 1);
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM audit_logs').get() as { count: number }).count, beforeAudit);
  });

  it('denies non-owner deletion without persistence or audit side effects, while admins override', async () => {
    const id = insertSchedule(db, insertDriver(db, 'Owned'), fixtures.admin.id);
    const beforeAudit = (db.prepare('SELECT COUNT(*) AS count FROM audit_logs').get() as { count: number }).count;
    const denied = await app.inject({ method: 'DELETE', url: `/api/loading/schedules/${id}`, headers: { authorization: `Bearer ${workerToken}` } });
    assert.equal(denied.statusCode, 403);
    assert.ok(db.prepare('SELECT id FROM loading_schedules WHERE id = ?').get(id));
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM audit_logs').get() as { count: number }).count, beforeAudit);
    const allowed = await app.inject({ method: 'DELETE', url: `/api/loading/schedules/${id}`, headers: { authorization: `Bearer ${adminToken}` } });
    assert.equal(allowed.statusCode, 204);
    assert.equal((db.prepare('SELECT COUNT(*) AS count FROM audit_logs WHERE action = ? AND entity_type = ?').get('delete', 'loading') as { count: number }).count, 1);
  });

  it('allows workers to permanently delete own inactive, old, and future records', async () => {
    const ids = [
      insertSchedule(db, insertDriver(db, 'Inactive'), fixtures.worker.id, "datetime('now', '-2 hours')", 0),
      insertSchedule(db, insertDriver(db, 'Old'), fixtures.worker.id, "datetime('now', '-2 hours')"),
      insertSchedule(db, insertDriver(db, 'Future'), fixtures.worker.id, "datetime('now', '+2 hours')"),
    ];
    for (const id of ids) {
      const response = await app.inject({ method: 'DELETE', url: `/api/loading/schedules/${id}`, headers: { authorization: `Bearer ${workerToken}` } });
      assert.equal(response.statusCode, 204);
    }
    assert.equal((db.prepare(`SELECT COUNT(*) AS count FROM loading_schedules WHERE id IN (${ids.join(',')})`).get() as { count: number }).count, 0);
  });

  it('writes audit entries for successful single and batch mutations and validates invalid targets', async () => {
    const id = insertSchedule(db, insertDriver(db, 'Mutation'), fixtures.admin.id);
    const deactivate = await app.inject({ method: 'PATCH', url: `/api/loading/schedules/${id}/deactivate`, headers: { authorization: `Bearer ${adminToken}` } });
    assert.equal(deactivate.statusCode, 200);
    const batch = await app.inject({ method: 'PATCH', url: '/api/loading/schedules/batch/2026-08-25/deactivate', headers: { authorization: `Bearer ${adminToken}` } });
    assert.equal(batch.statusCode, 200);
    const removal = await app.inject({ method: 'DELETE', url: '/api/loading/schedules/batch/2026-08-25', headers: { authorization: `Bearer ${adminToken}` } });
    assert.equal(removal.statusCode, 204);
    assert.equal((db.prepare(`SELECT COUNT(*) AS count FROM audit_logs WHERE entity_type IN ('loading', 'loading_batch')`).get() as { count: number }).count >= 3, true);
    assert.equal((await app.inject({ method: 'PATCH', url: '/api/loading/schedules/nope/deactivate', headers: { authorization: `Bearer ${adminToken}` } })).statusCode, 400);
    assert.equal((await app.inject({ method: 'DELETE', url: '/api/loading/schedules/batch/nope', headers: { authorization: `Bearer ${adminToken}` } })).statusCode, 400);
    assert.equal((await app.inject({ method: 'DELETE', url: '/api/loading/schedules/99999', headers: { authorization: `Bearer ${adminToken}` } })).statusCode, 404);
  });
});
