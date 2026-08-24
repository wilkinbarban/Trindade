import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';

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

describe('Auth Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db; // eslint-disable-line @typescript-eslint/no-unused-vars
    fixtures = result.fixtures;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  // --- Login ---

  it('successful login returns JWT with user data', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.admin,
    });

    assert.strictEqual(response.statusCode, 200);

    const body = JSON.parse(response.body);
    assert.ok(body.token, 'token should be present');
    assert.strictEqual(typeof body.token, 'string');
    assert.strictEqual(body.user.username, fixtures.admin.username);
    assert.strictEqual(body.user.role, 'Administrador');
    assert.strictEqual(body.user.id, 1);
  });

  it('invalid password returns 401', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: fixtures.admin.username, password: 'wrongpassword' },
    });

    assert.strictEqual(response.statusCode, 401);

    const body = JSON.parse(response.body);
    assert.strictEqual(body.error, 'Invalid credentials');
  });

  it('non-existent user returns 401', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'nobody', password: 'irrelevant' },
    });

    assert.strictEqual(response.statusCode, 401);
  });

  it('missing username returns 400', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { password: fixtures.admin.password },
    });

    assert.strictEqual(response.statusCode, 400);

    const body = JSON.parse(response.body);
    assert.strictEqual(body.error, 'Invalid input');
  });

  it('missing password returns 400', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: fixtures.admin.username },
    });

    assert.strictEqual(response.statusCode, 400);
  });

  // --- /me endpoint ---

  it('/me returns current user with valid token', async () => {
    // First login to get a token
    const loginRes = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.admin,
    });
    const { token } = JSON.parse(loginRes.body);

    const response = await app.inject({
      method: 'GET',
      url: '/api/auth/me',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(response.statusCode, 200);

    const body = JSON.parse(response.body);
    assert.strictEqual(body.user.username, fixtures.admin.username);
    assert.strictEqual(body.user.display_name, fixtures.admin.username);
  });

  it('/me returns 401 without token', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/api/auth/me',
    });

    assert.strictEqual(response.statusCode, 401);
  });

  // --- Role guard ---

  it('role guard returns 403 for Trabajador accessing admin route', async () => {
    // Login as worker (Trabajador role)
    const loginRes = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.worker,
    });
    const { token } = JSON.parse(loginRes.body);

    const response = await app.inject({
      method: 'GET',
      url: '/__test_admin_only',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(response.statusCode, 403);

    const body = JSON.parse(response.body);
    assert.strictEqual(body.error, 'Insufficient permissions');
  });

  it('role guard allows Administrador to access admin route', async () => {
    const loginRes = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: fixtures.admin,
    });
    const { token } = JSON.parse(loginRes.body);

    const response = await app.inject({
      method: 'GET',
      url: '/__test_admin_only',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(response.statusCode, 200);
  });

  // --- Password Change ---

  describe('GET/PATCH /api/auth/profile', () => {
    it('returns and updates only the current user display name via profile', async () => {
      db.prepare(
        `INSERT INTO users (id, username, password_hash, display_name, role_id, is_active)
         SELECT 99, 'profileuser', password_hash, 'Profile User', 2, 1 FROM users WHERE username = ?`
      ).run(fixtures.worker.username);
      const token = await loginAs(app, 'profileuser', fixtures.worker.password);

      const getRes = await app.inject({
        method: 'GET',
        url: '/api/auth/profile',
        headers: { authorization: `Bearer ${token}` },
      });
      assert.strictEqual(getRes.statusCode, 200);
      assert.strictEqual(JSON.parse(getRes.body).user.username, 'profileuser');

      const patchRes = await app.inject({
        method: 'PATCH',
        url: '/api/auth/profile',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          display_name: 'Demo Profile',
        },
      });
      assert.strictEqual(patchRes.statusCode, 200);
      const user = JSON.parse(patchRes.body).user;
      assert.strictEqual(user.id, 99);
      assert.strictEqual(user.username, 'profileuser');
      assert.strictEqual(user.display_name, 'Demo Profile');

      const profileRow = db.prepare('SELECT username, display_name FROM users WHERE id = 99').get() as { username: string; display_name: string };
      assert.strictEqual(profileRow.username, 'profileuser');
      assert.strictEqual(profileRow.display_name, 'Demo Profile');
    });

    it('does not expose route parameters for other-user profile access', async () => {
      const token = await loginAs(app, fixtures.worker.username, fixtures.worker.password);
      const res = await app.inject({
        method: 'GET',
        url: '/api/auth/profile/1',
        headers: { authorization: `Bearer ${token}` },
      });
      assert.strictEqual(res.statusCode, 404);
    });

    it('rejects username updates through profile', async () => {
      const token = await loginAs(app, fixtures.worker.username, fixtures.worker.password);
      const res = await app.inject({
        method: 'PATCH',
        url: '/api/auth/profile',
        headers: { authorization: `Bearer ${token}` },
        payload: { username: fixtures.admin.username },
      });
      assert.strictEqual(res.statusCode, 400);
    });
  });

  describe('POST /api/auth/change-password', () => {
    it('successfully changes password with valid input', async () => {
      db.prepare(
        `INSERT INTO users (username, password_hash, display_name, role_id, is_active)
         SELECT 'password-change-user', password_hash, 'Password Change User', 1, 1 FROM users WHERE username = ?`
      ).run(fixtures.admin.username);
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: 'password-change-user', password: fixtures.admin.password },
      });
      const { token } = JSON.parse(loginRes.body);

      // Change password
      const changeRes = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${token}` },
        payload: { currentPassword: fixtures.admin.password, newPassword: 'newsecurepassword' },
      });
      assert.strictEqual(changeRes.statusCode, 200);
      assert.deepStrictEqual(JSON.parse(changeRes.body), { success: true });

      // Try logging in with old password (should fail)
      const loginOldRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: 'password-change-user', password: fixtures.admin.password },
      });
      assert.strictEqual(loginOldRes.statusCode, 401);

      // Try logging in with new password (should succeed)
      const loginNewRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: 'password-change-user', password: 'newsecurepassword' },
      });
      assert.strictEqual(loginNewRes.statusCode, 200);
    });

    it('returns 400 for incorrect current password', async () => {
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: fixtures.worker,
      });
      const { token } = JSON.parse(loginRes.body);

      const changeRes = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${token}` },
        payload: { currentPassword: 'wrongcurrent', newPassword: 'newpassword' },
      });
      assert.strictEqual(changeRes.statusCode, 400);
      const body = JSON.parse(changeRes.body);
      assert.strictEqual(body.error, 'Invalid current password');
    });

    it('returns 400 for new password shorter than 4 characters', async () => {
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: fixtures.worker,
      });
      const { token } = JSON.parse(loginRes.body);

      const changeRes = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${token}` },
        payload: { currentPassword: fixtures.worker.password, newPassword: '123' },
      });
      assert.strictEqual(changeRes.statusCode, 400);
      const body = JSON.parse(changeRes.body);
      assert.strictEqual(body.error, 'Invalid input');
    });

    it('returns 401 when unauthenticated', async () => {
      const changeRes = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        payload: { currentPassword: fixtures.worker.password, newPassword: 'newpassword' },
      });
      assert.strictEqual(changeRes.statusCode, 401);
    });
  });
});
