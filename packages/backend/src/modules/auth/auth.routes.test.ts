import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import bcrypt from 'bcryptjs';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { authRoutes } from './auth.routes.js';
import { createAuthenticate } from './auth.middleware.js';
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

  // --- Session refresh and revocation ---

  describe('Session refresh and revocation', () => {
    async function loginWithSession(username: string, password: string) {
      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username, password },
      });
      assert.strictEqual(res.statusCode, 200, res.body);
      const body = JSON.parse(res.body);
      return {
        token: body.token as string,
        refreshToken: body.refreshToken as string,
        expiresIn: body.expiresIn as number,
      };
    }

    function refresh(refreshToken: string) {
      return app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
    }

    it('login returns a refresh token, its lifetime, and never stores the raw token', async () => {
      const session = await loginWithSession(fixtures.admin.username, fixtures.admin.password);

      assert.strictEqual(typeof session.refreshToken, 'string');
      assert.ok(session.refreshToken.length >= 32, 'the refresh token is too short to be high entropy');
      assert.strictEqual(session.expiresIn, 900);

      const stored = db
        .prepare('SELECT token_hash FROM auth_sessions WHERE user_id = ?')
        .pluck()
        .all(fixtures.admin.id) as string[];
      assert.ok(stored.length > 0, 'no session was persisted');
      assert.ok(!stored.includes(session.refreshToken), 'the raw refresh token was persisted');
    });

    it('refreshes a session without any Authorization header', async () => {
      const session = await loginWithSession(fixtures.admin.username, fixtures.admin.password);

      const res = await refresh(session.refreshToken);
      assert.strictEqual(res.statusCode, 200, res.body);

      const body = JSON.parse(res.body);
      assert.strictEqual(typeof body.token, 'string');
      assert.strictEqual(typeof body.refreshToken, 'string');
      assert.notStrictEqual(body.refreshToken, session.refreshToken, 'the refresh token was not rotated');
      assert.strictEqual(body.expiresIn, 900);
    });

    it('rejects a refresh token that was already rotated away', async () => {
      const session = await loginWithSession(fixtures.admin.username, fixtures.admin.password);
      assert.strictEqual((await refresh(session.refreshToken)).statusCode, 200);

      const replay = await refresh(session.refreshToken);
      assert.strictEqual(replay.statusCode, 401);
      assert.strictEqual(JSON.parse(replay.body).error, 'Invalid or expired session');
    });

    it('rejects a malformed refresh payload with 400', async () => {
      const res = await app.inject({ method: 'POST', url: '/api/auth/refresh', payload: {} });
      assert.strictEqual(res.statusCode, 400);
      assert.strictEqual(JSON.parse(res.body).error, 'Invalid input');
    });

    it('logout ends only the session whose refresh token was presented', async () => {
      const phone = await loginWithSession(fixtures.admin.username, fixtures.admin.password);
      const desktop = await loginWithSession(fixtures.admin.username, fixtures.admin.password);

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/logout',
        headers: { authorization: `Bearer ${phone.token}` },
        payload: { refreshToken: phone.refreshToken },
      });
      assert.strictEqual(res.statusCode, 200, res.body);

      assert.strictEqual((await refresh(phone.refreshToken)).statusCode, 401);
      assert.strictEqual(
        (await refresh(desktop.refreshToken)).statusCode,
        200,
        'logging out one device ended another session',
      );
    });

    it('logout still succeeds with no body at all, the way the web client sends it', async () => {
      const session = await loginWithSession(fixtures.admin.username, fixtures.admin.password);

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/logout',
        headers: { authorization: `Bearer ${session.token}` },
      });
      assert.strictEqual(res.statusCode, 200, res.body);
      assert.deepStrictEqual(JSON.parse(res.body), { success: true });
    });

    it('changing the password ends every session of that user', async () => {
      const first = await loginWithSession(fixtures.worker.username, fixtures.worker.password);
      const second = await loginWithSession(fixtures.worker.username, fixtures.worker.password);

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${first.token}` },
        payload: { currentPassword: fixtures.worker.password, newPassword: 'changed-password-2026' },
      });
      assert.strictEqual(res.statusCode, 200, res.body);

      assert.strictEqual((await refresh(first.refreshToken)).statusCode, 401);
      assert.strictEqual((await refresh(second.refreshToken)).statusCode, 401);

      // Restore the fixture credential so tests declared elsewhere in this file keep working.
      const restore = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${first.token}` },
        payload: { currentPassword: 'changed-password-2026', newPassword: fixtures.worker.password },
      });
      assert.strictEqual(restore.statusCode, 200, restore.body);
    });

    it('refuses to refresh a session whose user was deactivated', async () => {
      const username = `deactivated-${Date.now()}`;
      const password = 'deactivated-password-2026';
      const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
      const userId = Number(
        db
          .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)')
          .run(username, await bcrypt.hash(password, 4), username, roleId).lastInsertRowid,
      );

      const session = await loginWithSession(username, password);
      db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(userId);

      const res = await refresh(session.refreshToken);
      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(
        db.prepare('SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL').pluck().get(userId),
        0,
        'the deactivated user kept a live session',
      );
    });
  });

  // A database written by the previous build has no session table. The server still boots and says
  // so; what these cover is that the session endpoints refuse clearly instead of letting a driver
  // error escape — and on the unauthenticated refresh path, escape to an anonymous caller.
  describe('A database without the session table', () => {
    let degraded: FastifyInstance;

    before(async () => {
      // Same database and secret as the main app, so a token issued there is valid here: the
      // subject is the missing session store, not a different installation. In server.ts this flag
      // is fed by `sessionStoreExists`, whose detection the service suite proves directly against
      // a table-less database.
      degraded = Fastify({ logger: false });
      degraded.decorate('db', db);
      degraded.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));
      await degraded.register(authRoutes, {
        prefix: '/api/auth',
        jwtSecret: fixtures.jwtSecret,
        sessionStoreAvailable: false,
      });
      await degraded.ready();
    });

    after(async () => {
      await degraded.close();
    });

    it('answers 503 with an operator-facing reason instead of a driver error', async () => {
      const response = await degraded.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: fixtures.admin.username, password: fixtures.admin.password },
      });

      assert.strictEqual(response.statusCode, 503, response.body);
      assert.match(JSON.parse(response.body).error, /db:migrate/);
      assert.doesNotMatch(response.body, /auth_sessions|SQLITE/, 'the response leaked the schema');
    });

    it('refuses the unauthenticated refresh path the same way, never with a 500', async () => {
      const refresh = await degraded.inject({
        method: 'POST',
        url: '/api/auth/refresh',
        payload: { refreshToken: 'any-token-at-all' },
      });

      assert.strictEqual(refresh.statusCode, 503, refresh.body);
      assert.doesNotMatch(refresh.body, /SQLITE/, 'an anonymous caller saw a driver error');
    });

    it('lets logout succeed without touching the session store', async () => {
      const token = await loginAs(app, fixtures.admin.username, fixtures.admin.password);

      const logout = await degraded.inject({
        method: 'POST',
        url: '/api/auth/logout',
        headers: { authorization: `Bearer ${token}` },
      });

      // Logout is idempotent and the state it wants already holds when there is no session store:
      // there is no session to end. Answering 503 would tell a client its logout failed and invite
      // a retry, so the requirement here is narrower — it must not fail, and must not surface a
      // driver error.
      assert.strictEqual(logout.statusCode, 200, logout.body);
      assert.doesNotMatch(logout.body, /SQLITE/, 'logout surfaced a driver error');
    });

    // The gap three lenses found on the previous pass: the degraded suite covered login, refresh
    // and logout, so this fourth session-touching path went unproved and shipped without its guard.
    it('refuses change-password before it can half-change the credential', async () => {
      const token = await loginAs(app, fixtures.admin.username, fixtures.admin.password);

      const response = await degraded.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${token}` },
        payload: { currentPassword: fixtures.admin.password, newPassword: 'replacement-password-2026' },
      });

      assert.strictEqual(response.statusCode, 503, response.body);
      assert.doesNotMatch(response.body, /SQLITE|auth_sessions/, 'change-password leaked the schema');

      // The refusal has to land before the UPDATE. If it did not, the password would already be
      // replaced with no session revoked, which is the partial state the review called out.
      await loginAs(app, fixtures.admin.username, fixtures.admin.password);
    });
  });
});
