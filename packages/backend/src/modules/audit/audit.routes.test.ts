import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildAuditTestApp, type TestFixtures } from './audit-test-helper.js';

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

describe('Audit Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let adminToken: string;
  let workerToken: string;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildAuditTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
    adminToken = await loginAs(app, fixtures.admin.username, fixtures.admin.password);
    workerToken = await loginAs(app, fixtures.worker.username, fixtures.worker.password);
  });

  after(async () => {
    await app.close();
    db.close();
  });

  // ============================================================
  // Authentication & Authorization
  // ============================================================

  it('unauthenticated requests return 401', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit',
    });
    assert.strictEqual(res.statusCode, 401);
  });

  it('non-admin (worker) requests return 403', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit',
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.strictEqual(res.statusCode, 403);
  });

  it('admin requests succeed with 200', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit',
      headers: { authorization: `Bearer ${adminToken}` },
    });
    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.logs), 'logs should be an array');
    assert.ok(typeof body.total === 'number', 'total should be a number');
    assert.strictEqual(body.page, 1);
    assert.ok(body.totalPages >= 1);
  });

  // ============================================================
  // Audit log content — login event is recorded
  // ============================================================

  it('logs contain login events after admin login', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?limit=50',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);

    // Find a login event for user_id 1 (admin)
    const loginLogs = body.logs.filter(
      (l: any) => l.action === 'login' && l.user_id === 1
    );
    assert.ok(loginLogs.length >= 1, 'should have at least one login log');
    assert.strictEqual(loginLogs[0].entity_type, 'auth');
  });

  it('logout endpoint records an audit log entry', async () => {
    // Login as worker to get a fresh token
    const workerTok = await loginAs(app, fixtures.worker.username, fixtures.worker.password);

    // Call logout
    const logoutRes = await app.inject({
      method: 'POST',
      url: '/api/auth/logout',
      headers: { authorization: `Bearer ${workerTok}` },
    });
    assert.strictEqual(logoutRes.statusCode, 200);

    // Check audit log (login as admin to query)
    const res = await app.inject({
      method: 'GET',
      url: `/api/admin/audit?action=logout&entityType=auth&limit=50`,
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    const found = body.logs.find(
      (l: any) => l.action === 'logout' && l.entity_type === 'auth'
    );
    assert.ok(found, 'should find audit entry for logout');
  });

  // ============================================================
  // Pagination
  // ============================================================

  it('pagination — page 1 with limit=1 returns 1 log', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?page=1&limit=1',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.logs.length, 1);
    assert.strictEqual(body.limit, 1);
    assert.strictEqual(body.page, 1);
    assert.ok(body.total >= 1);
    assert.ok(body.totalPages >= 1);
  });

  it('pagination — page beyond range returns empty array', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?page=9999',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.logs.length, 0);
    assert.strictEqual(body.page, 9999);
  });

  // ============================================================
  // Filters
  // ============================================================

  it('filters by entityType', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?entityType=auth',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    for (const log of body.logs) {
      assert.strictEqual(log.entity_type, 'auth');
    }
  });

  it('filters by action', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?action=login',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    for (const log of body.logs) {
      assert.strictEqual(log.action, 'login');
    }
  });

  // ============================================================
  // Validation
  // ============================================================

  it('returns 400 for invalid page parameter', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/audit?page=invalid',
      headers: { authorization: `Bearer ${adminToken}` },
    });
    assert.strictEqual(res.statusCode, 400);
  });
});
