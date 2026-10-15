import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Writable } from 'node:stream';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import DatabaseConstructor, { type Database } from 'better-sqlite3';

import { authRoutes } from './auth.routes.js';
import { AuthAdmissionLimiter, defaultAuthAdmissionLimiter } from './auth.admission-limiter.js';
import { createAuthenticate } from './auth.middleware.js';
import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';
import { REGISTER_NEUTRAL_MESSAGE } from './auth.schema.js';
import { defaultRegistrationRateLimiter, RegistrationRateLimiter } from './auth.rate-limiter.js';
import { registerWorkerUser } from './auth.service.js';
import { updateUser } from '../admin/admin.service.js';
import { bootstrapRoutes } from './bootstrap.routes.js';

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
  let db: Database;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db; // eslint-disable-line @typescript-eslint/no-unused-vars
    fixtures = result.fixtures;
  });

  beforeEach(() => defaultAuthAdmissionLimiter.reset());

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

  it('login rejects a hash changed while async bcrypt comparison was pending', async () => {
    const isolated = await buildAuthTestApp();
    const compare = bcrypt.compare;
    try {
      bcrypt.compare = (async (password: string, hash: string) => {
        const matched = await compare(password, hash);
        isolated.db.prepare('UPDATE users SET password_hash = ? WHERE id = ?')
          .run(await bcrypt.hash('replacement-password', 4), isolated.fixtures.admin.id);
        return matched;
      }) as typeof bcrypt.compare;
      const response = await isolated.app.inject({ method: 'POST', url: '/api/auth/login',
        payload: isolated.fixtures.admin });
      assert.strictEqual(response.statusCode, 401);
      assert.strictEqual(isolated.db.prepare('SELECT COUNT(*) FROM auth_sessions').pluck().get(), 0);
    } finally {
      bcrypt.compare = compare;
      await isolated.app.close();
      isolated.db.close();
    }
  });

  it('refresh signs current role and security version and rejects cutover sessions', async () => {
    const isolated = await buildAuthTestApp();
    try {
      const login = await isolated.app.inject({ method: 'POST', url: '/api/auth/login', payload: isolated.fixtures.admin });
      assert.strictEqual(login.statusCode, 200);
      const old = JSON.parse(login.body);
      isolated.db.prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE revoked_at IS NULL").run();
      const rejected = await isolated.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken: old.refreshToken } });
      assert.strictEqual(rejected.statusCode, 401);
      const fresh = await isolated.app.inject({ method: 'POST', url: '/api/auth/login', payload: isolated.fixtures.admin });
      assert.strictEqual(fresh.statusCode, 200);
      isolated.db.prepare('UPDATE users SET role_id = 2, security_version = security_version + 1 WHERE id = ?').run(isolated.fixtures.admin.id);
      const refreshed = await isolated.app.inject({ method: 'POST', url: '/api/auth/refresh',
        payload: { refreshToken: JSON.parse(fresh.body).refreshToken } });
      assert.strictEqual(refreshed.statusCode, 200);
      const claims = jwt.verify(JSON.parse(refreshed.body).token, isolated.fixtures.jwtSecret) as jwt.JwtPayload;
      assert.strictEqual(claims.role, 'Trabalhador');
      assert.strictEqual(claims.security_version, 2);
    } finally {
      await isolated.app.close();
      isolated.db.close();
    }
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

  it('isolated admission rejects normalized login bursts and spoofed forwarding headers without affecting refresh success', async () => {
    let now = 0;
    const limiter = new AuthAdmissionLimiter({ now: () => now, windowMs: 2000, maxUsernameLogin: 1, maxGlobalLogin: 3, maxTokenRefresh: 1 });
    const isolated = Fastify({ logger: false });
    isolated.decorate('db', db);
    isolated.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));
    await isolated.register(authRoutes, { prefix: '/api/auth', jwtSecret: fixtures.jwtSecret, authAdmissionLimiter: limiter });
    try {
      const send = (username: string, forwarded: string) => isolated.inject({ method: 'POST', url: '/api/auth/login', headers: { 'x-forwarded-for': forwarded }, payload: { username, password: fixtures.admin.password } });
      const first = await send(fixtures.admin.username, '1.1.1.1');
      assert.equal(first.statusCode, 200);
      const rejected = await send(fixtures.admin.username.toUpperCase(), '2.2.2.2');
      assert.equal(rejected.statusCode, 429);
      assert.equal(rejected.headers['retry-after'], '2');
      assert.deepEqual(JSON.parse(rejected.body), { error: 'Too many requests' });
      const refreshToken = JSON.parse(first.body).refreshToken;
      const refreshed = await isolated.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
      assert.equal(refreshed.statusCode, 200);
      const replay = await isolated.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
      assert.equal(replay.statusCode, 429);
      assert.equal(replay.headers['retry-after'], '2');
      now = 2000;
      assert.equal((await send(fixtures.admin.username, '3.3.3.3')).statusCode, 200);
    } finally {
      await isolated.close();
    }
  });

  it('concurrent login burst has a bounded bcrypt admission slot', async () => {
    const isolated = Fastify({ logger: false });
    isolated.decorate('db', db);
    isolated.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));
    await isolated.register(authRoutes, { prefix: '/api/auth', jwtSecret: fixtures.jwtSecret, authAdmissionLimiter: new AuthAdmissionLimiter({ maxConcurrentCompares: 1, maxUsernameLogin: 100 }) });
    try {
      const responses = await Promise.all(Array.from({ length: 8 }, () => isolated.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin })));
      assert.ok(responses.some((response) => response.statusCode === 429));
      assert.ok(responses.some((response) => response.statusCode === 200));
      assert.ok(responses.filter((response) => response.statusCode === 429).every((response) => response.headers['retry-after'] === '1'));
    } finally {
      await isolated.close();
    }
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
    it('rejects a concurrent credential replacement after bcrypt yields', async () => {
      const isolated = await buildAuthTestApp();
      const compare = bcrypt.compare;
      try {
        const login = await isolated.app.inject({ method: 'POST', url: '/api/auth/login', payload: isolated.fixtures.worker });
        const { token, refreshToken } = JSON.parse(login.body);
        let replacement: string;
        bcrypt.compare = (async (password: string, hash: string) => {
          const valid = await compare(password, hash);
          updateUser(isolated.db, isolated.fixtures.worker.id, { password: 'concurrent-password' });
          replacement = isolated.db.prepare('SELECT password_hash FROM users WHERE id = ?').pluck().get(isolated.fixtures.worker.id) as string;
          return valid;
        }) as typeof bcrypt.compare;
        const response = await isolated.app.inject({ method: 'POST', url: '/api/auth/change-password',
          headers: { authorization: `Bearer ${token}` },
          payload: { currentPassword: isolated.fixtures.worker.password, newPassword: 'must-not-win-at-all' } });
        assert.strictEqual(response.statusCode, 409);
        assert.strictEqual(isolated.db.prepare('SELECT password_hash FROM users WHERE id = ?').pluck().get(isolated.fixtures.worker.id), replacement!);
        assert.strictEqual((await isolated.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } })).statusCode, 401);
      } finally { bcrypt.compare = compare; await isolated.app.close(); isolated.db.close(); }
    });

    it('rolls back password and version when session revocation fails', async () => {
      const isolated = await buildAuthTestApp();
      try {
        const login = await isolated.app.inject({ method: 'POST', url: '/api/auth/login', payload: isolated.fixtures.worker });
        const { token, refreshToken } = JSON.parse(login.body);
        const before = isolated.db.prepare('SELECT password_hash, security_version FROM users WHERE id = ?').get(isolated.fixtures.worker.id);
        isolated.db.exec(`CREATE TRIGGER deny_revoke_password_test BEFORE UPDATE OF revoked_at ON auth_sessions
          BEGIN SELECT RAISE(ABORT, 'revocation failed'); END`);
        const response = await isolated.app.inject({ method: 'POST', url: '/api/auth/change-password',
          headers: { authorization: `Bearer ${token}` },
          payload: { currentPassword: isolated.fixtures.worker.password, newPassword: 'must-not-stick-ever' } });
        assert.strictEqual(response.statusCode, 500);
        assert.deepStrictEqual(isolated.db.prepare('SELECT password_hash, security_version FROM users WHERE id = ?').get(isolated.fixtures.worker.id), before);
        isolated.db.exec('DROP TRIGGER deny_revoke_password_test');
        assert.strictEqual((await isolated.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } })).statusCode, 200);
      } finally { await isolated.app.close(); isolated.db.close(); }
    });
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
        payload: { currentPassword: 'wrongcurrent', newPassword: 'new-password-2026-long' },
      });
      assert.strictEqual(changeRes.statusCode, 400);
      const body = JSON.parse(changeRes.body);
      assert.strictEqual(body.error, 'Invalid current password');
    });

    it('returns 400 for a new password shorter than 15 graphemes', async () => {
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

      // The old access token is invalid immediately; re-authenticate before restoring the fixture.
      assert.strictEqual((await app.inject({ method: 'GET', url: '/api/auth/me',
        headers: { authorization: `Bearer ${first.token}` } })).statusCode, 401);
      const replacement = await loginWithSession(fixtures.worker.username, 'changed-password-2026');
      const restore = await app.inject({
        method: 'POST',
        url: '/api/auth/change-password',
        headers: { authorization: `Bearer ${replacement.token}` },
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
        sessionStoreAvailable: () => false,
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

  // --- Public Worker Registration (POST /api/auth/register) ---

  describe('POST /api/auth/register (Public Worker Registration)', () => {
    beforeEach(() => {
      defaultRegistrationRateLimiter.reset();
    });

    it('pending login 401/no token: registered worker cannot log in until administrator activates', async () => {
      const payload = {
        username: 'worker-pending',
        display_name: 'Pending Worker',
        password: 'password1234567',
      };

      const registerRes = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload,
      });

      assert.strictEqual(registerRes.statusCode, 200);
      const registerBody = JSON.parse(registerRes.body);
      assert.strictEqual(registerBody.message, REGISTER_NEUTRAL_MESSAGE);
      assert.strictEqual(registerBody.token, undefined);
      assert.strictEqual(registerBody.refreshToken, undefined);

      // Verify row in database: role_id=2 (Trabalhador), is_active=0
      const user = db
        .prepare('SELECT id, username, role_id, is_active FROM users WHERE username = ?')
        .get('worker-pending') as { id: number; username: string; role_id: number; is_active: number } | undefined;
      assert.ok(user, 'user row should exist in database');
      assert.strictEqual(user.role_id, 2);
      assert.strictEqual(user.is_active, 0);

      // Immediate login attempt must fail with 401 and no token
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: payload.username, password: payload.password },
      });
      assert.strictEqual(loginRes.statusCode, 401);
      const loginBody = JSON.parse(loginRes.body);
      assert.strictEqual(loginBody.error, 'Invalid credentials');
      assert.strictEqual(loginBody.token, undefined);
    });

    it('admin activation enables login: activating is_active=1 enables successful login with token', async () => {
      const payload = {
        username: 'worker-to-activate',
        display_name: 'Active Worker',
        password: 'password1234567',
      };

      const registerRes = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload,
      });
      assert.strictEqual(registerRes.statusCode, 200);

      // Administrator activates the user
      db.prepare('UPDATE users SET is_active = 1 WHERE username = ?').run('worker-to-activate');

      // Now worker can log in
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: payload.username, password: payload.password },
      });
      assert.strictEqual(loginRes.statusCode, 200);
      const loginBody = JSON.parse(loginRes.body);
      assert.ok(loginBody.token, 'token must be present');
      assert.strictEqual(loginBody.user.username, 'worker-to-activate');
      assert.strictEqual(loginBody.user.role, 'Trabalhador');
    });

    it('privilege-field injection rejected or ignored safely: strict schema rejects role_id, is_active, and extra fields', async () => {
      const base = {
        username: 'worker-privilege-attempt',
        display_name: 'Attempted Admin',
        password: 'password1234567',
      };

      const injectionAttempts = [
        { ...base, role_id: 1 },
        { ...base, is_active: 1 },
        { ...base, role: 'Administrador' },
        { ...base, id: 999 },
        { ...base, admin: true },
      ];

      for (const payload of injectionAttempts) {
        const res = await app.inject({
          method: 'POST',
          url: '/api/auth/register',
          payload,
        });
        assert.strictEqual(res.statusCode, 400, `Expected 400 for payload: ${JSON.stringify(payload)}`);
        const body = JSON.parse(res.body);
        assert.strictEqual(body.error, 'Invalid input');
      }

      // Verify no user was created from the injection attempts
      const user = db.prepare('SELECT id FROM users WHERE username = ?').get('worker-privilege-attempt');
      assert.strictEqual(user, undefined);
    });

    it('duplicate neutral body/status: duplicate username returns identical 200 response and does not mutate', async () => {
      const originalPayload = {
        username: 'worker-duplicate-test',
        display_name: 'Original Worker Name',
        password: 'original-password',
      };

      const res1 = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: originalPayload,
      });
      assert.strictEqual(res1.statusCode, 200);
      const body1 = JSON.parse(res1.body);

      // Attempt second registration with identical username but different password and display_name
      const duplicatePayload = {
        username: 'worker-duplicate-test',
        display_name: 'Imposter Worker Name',
        password: 'different-password',
      };

      const res2 = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: duplicatePayload,
      });

      // Same status and same body
      assert.strictEqual(res2.statusCode, 200);
      assert.strictEqual(res2.statusCode, res1.statusCode);
      const body2 = JSON.parse(res2.body);
      assert.deepStrictEqual(body2, body1);

      // Verify database was NOT mutated: only 1 user exists, with original display_name and password
      const users = db.prepare('SELECT * FROM users WHERE username = ?').all('worker-duplicate-test') as any[];
      assert.strictEqual(users.length, 1);
      assert.strictEqual(users[0].display_name, 'Original Worker Name');
      assert.ok(bcrypt.compareSync('original-password', users[0].password_hash));
      assert.ok(!bcrypt.compareSync('different-password', users[0].password_hash));
    });

    it('forged XFF cannot bypass global/target limits: rotating X-Forwarded-For cannot circumvent bounds', async () => {
      // Create isolated app with low limits: max 2 username attempts, max 4 global attempts
      const isolatedDb = new DatabaseConstructor(':memory:');
      isolatedDb.pragma('foreign_keys = ON');
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      isolatedDb.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, 1, 1)').run('admin', 'hash', 'Admin');

      const customLimiter = new RegistrationRateLimiter({
        windowMs: 60_000,
        maxGlobalAttempts: 3,
        maxUsernameAttempts: 2,
      });

      const isolatedApp = Fastify({ logger: false });
      isolatedApp.decorate('db', isolatedDb);
      isolatedApp.decorate('authenticate', createAuthenticate('test-secret'));
      await isolatedApp.register(authRoutes, {
        prefix: '/api/auth',
        jwtSecret: 'test-secret',
        rateLimiter: customLimiter,
      });
      await isolatedApp.ready();

      try {
        // 1. Target limit: 2 attempts allowed for 'target-worker', 3rd blocked despite different XFF
        const res1 = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          headers: { 'x-forwarded-for': '192.168.1.1' },
          payload: { username: 'target-worker', display_name: 'Target', password: 'password1234567' },
        });
        assert.strictEqual(res1.statusCode, 200);

        const res2 = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          headers: { 'x-forwarded-for': '192.168.1.2' },
          payload: { username: 'target-worker', display_name: 'Target', password: 'password1234567' },
        });
        assert.strictEqual(res2.statusCode, 200);

        // 3rd attempt for target-worker fails with 429 even with new forged XFF
        const res3 = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          headers: { 'x-forwarded-for': '192.168.1.3' },
          payload: { username: 'target-worker', display_name: 'Target', password: 'password1234567' },
        });
        assert.strictEqual(res3.statusCode, 429);
        assert.match(JSON.parse(res3.body).error, /limite/i);
        assert.ok(res3.headers['retry-after']);

        // 2. Global limit: 4th global attempt (with different username) succeeds
        const res4 = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          headers: { 'x-forwarded-for': '192.168.1.4' },
          payload: { username: 'other-worker-1', display_name: 'Other', password: 'password1234567' },
        });
        assert.strictEqual(res4.statusCode, 200);

        // 5th global attempt fails with 429 despite fresh username and fresh XFF
        const res5 = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          headers: { 'x-forwarded-for': '192.168.1.5' },
          payload: { username: 'other-worker-2', display_name: 'Other', password: 'password1234567' },
        });
        assert.strictEqual(res5.statusCode, 429);
        assert.match(JSON.parse(res5.body).error, /limite/i);
      } finally {
        await isolatedApp.close();
        isolatedDb.close();
      }
    });

    it('rate limit 429: concurrent hash cap rejection answers 429', async () => {
      const isolatedDb = new DatabaseConstructor(':memory:');
      isolatedDb.pragma('foreign_keys = ON');
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      isolatedDb.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, 1, 1)').run('admin', 'hash', 'Admin');

      const customLimiter = new RegistrationRateLimiter({
        maxConcurrentHashes: 1,
      });

      const isolatedApp = Fastify({ logger: false });
      isolatedApp.decorate('db', isolatedDb);
      isolatedApp.decorate('authenticate', createAuthenticate('test-secret'));
      await isolatedApp.register(authRoutes, {
        prefix: '/api/auth',
        jwtSecret: 'test-secret',
        rateLimiter: customLimiter,
      });
      await isolatedApp.ready();

      try {
        // Manually acquire the single hash slot
        assert.strictEqual(customLimiter.tryAcquireHashSlot(), true);

        // Request during saturated concurrency cap returns 429
        const res = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          payload: { username: 'burst-worker', display_name: 'Burst', password: 'password1234567' },
        });

        assert.strictEqual(res.statusCode, 429);
        const body = JSON.parse(res.body);
        assert.match(body.error, /simultâneas/i);

        customLimiter.releaseHashSlot();
      } finally {
        await isolatedApp.close();
        isolatedDb.close();
      }
    });

    it('bootstrap protected: when users table is empty, registration is rejected without inserting', async () => {
      const emptyDb = new DatabaseConstructor(':memory:');
      emptyDb.pragma('foreign_keys = ON');
      emptyDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      emptyDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      // Ensure users table is completely empty
      emptyDb.exec('DELETE FROM users');

      const bootstrapApp = Fastify({ logger: false });
      bootstrapApp.decorate('db', emptyDb);
      bootstrapApp.decorate('authenticate', createAuthenticate('test-secret'));
      await bootstrapApp.register(authRoutes, { prefix: '/api/auth', jwtSecret: 'test-secret' });
      await bootstrapApp.register(bootstrapRoutes, { prefix: '/api/auth' });
      await bootstrapApp.ready();

      try {
        // 1. Verify bootstrap is required
        const statusBefore = await bootstrapApp.inject({ method: 'GET', url: '/api/auth/setup/status' });
        assert.strictEqual(JSON.parse(statusBefore.body).setupRequired, true);

        // 2. Attempt registration while users table is empty
        const registerRes = await bootstrapApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          payload: { username: 'preempt-worker', display_name: 'Preempt', password: 'password1234567' },
        });

        // Rejected with 409 Conflict
        assert.strictEqual(registerRes.statusCode, 409);
        const body = JSON.parse(registerRes.body);
        assert.match(body.error, /configuração inicial/i);

        // Invariant: no user was inserted
        const count = emptyDb.prepare('SELECT COUNT(*) FROM users').pluck().get();
        assert.strictEqual(count, 0);

        // Invariant: setup status remains open
        const statusAfter = await bootstrapApp.inject({ method: 'GET', url: '/api/auth/setup/status' });
        assert.strictEqual(JSON.parse(statusAfter.body).setupRequired, true);

        // 3. The public endpoint cannot provision even while the installation is empty.
        const setupRes = await bootstrapApp.inject({
          method: 'POST',
          url: '/api/auth/setup',
          payload: { username: 'initial-admin', displayName: 'Admin One', password: 'admin-password-123' },
        });
        assert.strictEqual(setupRes.statusCode, 410);
        assert.strictEqual(emptyDb.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0);
      } finally {
        await bootstrapApp.close();
        emptyDb.close();
      }
    });

    it('max input: validates bounds on username, display_name, and password bytes', async () => {
      // 1. Username too long (> 50 chars)
      const resUserTooLong = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'a'.repeat(51), display_name: 'Worker', password: 'password1234567' },
      });
      assert.strictEqual(resUserTooLong.statusCode, 400);

      // 2. Display name too long (> 100 chars)
      const resDisplayTooLong = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: 'a'.repeat(101), password: 'password1234567' },
      });
      assert.strictEqual(resDisplayTooLong.statusCode, 400);

      // 3. Password too long (> 72 chars)
      const resPassTooLong = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: 'Worker', password: 'a'.repeat(73) },
      });
      assert.strictEqual(resPassTooLong.statusCode, 400);

      // 4. Password multibyte byte length > 72 bytes (even if <= 72 characters)
      // 25 emoji characters: each 4 bytes in UTF-8 = 100 bytes (> 72 bytes)
      const resPassMultiByteTooLong = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: 'Worker', password: '🔐'.repeat(25) },
      });
      assert.strictEqual(resPassMultiByteTooLong.statusCode, 400);

      // 5. Registration requires at least 15 visible graphemes
      const resPass3Chars = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: 'Worker', password: '123' },
      });
      assert.strictEqual(resPass3Chars.statusCode, 400);

      const resPass14Chars = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: 'Worker', password: '12345678901234' },
      });
      assert.strictEqual(resPass14Chars.statusCode, 400);
      const resPass14Body = JSON.parse(resPass14Chars.body);
      assert.strictEqual(resPass14Body.error, 'Invalid input');
      assert.match(
        resPass14Body.details.fieldErrors.password?.[0] ?? '',
        /at least 15 visible graphemes/i
      );

      // Boundary: 15 ASCII graphemes are accepted
      const resPass15Chars = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user-15char', display_name: 'Worker', password: 'turno-limpo2026' },
      });
      assert.strictEqual(resPass15Chars.statusCode, 200);

      // 6. Empty / whitespace-only fields
      const resBlankUsername = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: '   ', display_name: 'Worker', password: 'password1234567' },
      });
      assert.strictEqual(resBlankUsername.statusCode, 400);

      const resBlankDisplay = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'valid-user', display_name: '   ', password: 'password1234567' },
      });
      assert.strictEqual(resBlankDisplay.statusCode, 400);
    });

    it('no mutation on error: failed registrations do not mutate users or audit tables', async () => {
      const usersBefore = db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number;
      const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

      // 1. Validation failure
      await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: '', display_name: '', password: '' },
      });

      // 2. Extra field injection failure
      await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'bad-injection', display_name: 'Bad', password: 'password1234567', role_id: 1 },
      });

      const usersAfter = db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number;
      const auditsAfter = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

      assert.strictEqual(usersAfter, usersBefore, 'users table must not mutate on error');
      assert.strictEqual(auditsAfter, auditsBefore, 'audit_logs table must not mutate on error');
    });

    it('audit record uses newly inserted user id safely on success', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'audit-test-worker', display_name: 'Audit Worker', password: 'password1234567' },
      });
      assert.strictEqual(res.statusCode, 200);

      const workerUser = db.prepare('SELECT id FROM users WHERE username = ?').get('audit-test-worker') as { id: number };
      assert.ok(workerUser);

      // Verify audit log record
      const auditEntry = db
        .prepare('SELECT * FROM audit_logs WHERE user_id = ? AND action = ?')
        .get(workerUser.id, 'register') as any;
      assert.ok(auditEntry, 'audit entry must exist for newly registered worker');
      assert.strictEqual(auditEntry.user_id, workerUser.id);
      assert.strictEqual(auditEntry.entity_type, 'user');
      assert.strictEqual(auditEntry.entity_id, workerUser.id);

      // Duplicate registration does not create another audit entry
      await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'audit-test-worker', display_name: 'Audit Worker', password: 'password1234567' },
      });
      const auditsAfterDuplicate = db.prepare('SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = ?').pluck().get(workerUser.id, 'register') as number;
      assert.strictEqual(auditsAfterDuplicate, 1, 'duplicate registration must not produce additional audit log');
    });

    it('foreign key failure yields server error (500) and never neutral success when worker role row is missing', async () => {
      const isolatedDb = new DatabaseConstructor(':memory:');
      isolatedDb.pragma('foreign_keys = ON');
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      isolatedDb.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, 1, 1)').run('admin', 'hash', 'Admin');

      // Delete worker role (id = 2) to falsify FK reference
      isolatedDb.exec('DELETE FROM roles WHERE id = 2');

      const isolatedApp = Fastify({ logger: false });
      isolatedApp.decorate('db', isolatedDb);
      isolatedApp.decorate('authenticate', createAuthenticate('test-secret'));
      await isolatedApp.register(authRoutes, {
        prefix: '/api/auth',
        jwtSecret: 'test-secret',
      });
      await isolatedApp.ready();

      try {
        const res = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          payload: {
            username: 'missing-role-worker',
            display_name: 'Missing Role Worker',
            password: 'password1234567',
          },
        });

        // Must yield server error 500, NEVER neutral success (200)
        assert.strictEqual(res.statusCode, 500);
        const body = JSON.parse(res.body);
        assert.strictEqual(body.error, 'Erro interno ao processar a solicitação.');
        assert.strictEqual(body.message, undefined);

        // Verify no user row was created
        const user = isolatedDb.prepare('SELECT * FROM users WHERE username = ?').get('missing-role-worker');
        assert.strictEqual(user, undefined);
      } finally {
        await isolatedApp.close();
        isolatedDb.close();
      }
    });

    it('service bootstrap guard is atomic: registerWorkerUser refuses insertion when bootstrap is required', () => {
      const emptyDb = new DatabaseConstructor(':memory:');
      emptyDb.pragma('foreign_keys = ON');
      emptyDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      emptyDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      emptyDb.exec('DELETE FROM users');

      const result = registerWorkerUser(emptyDb, {
        username: 'atomic-preempt-worker',
        displayName: 'Preempt Worker',
        passwordHash: 'dummy-hash',
      });

      assert.strictEqual(result.outcome, 'bootstrap_required');
      const count = emptyDb.prepare('SELECT COUNT(*) FROM users').pluck().get();
      assert.strictEqual(count, 0, 'no user must be inserted when bootstrap is required');
      emptyDb.close();
    });

    it('audit failure surfaces warning to route logger without exposing it to public response', async () => {
      const warnings: any[] = [];
      const logStream = new Writable({
        write(chunk, _encoding, callback) {
          try {
            warnings.push(JSON.parse(chunk.toString()));
          } catch {
            // ignore non-json log lines
          }
          callback();
        }
      });

      const isolatedDb = new DatabaseConstructor(':memory:');
      isolatedDb.pragma('foreign_keys = ON');
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
      isolatedDb.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
      isolatedDb.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, 1, 1)').run('admin', 'hash', 'Admin');

      // Add a trigger on audit_logs that raises an error to simulate audit log persistence failure
      isolatedDb.exec(`
        CREATE TRIGGER fail_audit_insert
        BEFORE INSERT ON audit_logs
        BEGIN
          SELECT RAISE(ABORT, 'simulated audit failure');
        END;
      `);

      const isolatedApp = Fastify({
        logger: {
          level: 'warn',
          stream: logStream,
        },
      });
      isolatedApp.decorate('db', isolatedDb);
      isolatedApp.decorate('authenticate', createAuthenticate('test-secret'));
      await isolatedApp.register(authRoutes, {
        prefix: '/api/auth',
        jwtSecret: 'test-secret',
      });
      await isolatedApp.ready();

      try {
        const res = await isolatedApp.inject({
          method: 'POST',
          url: '/api/auth/register',
          payload: {
            username: 'audit-fail-worker',
            display_name: 'Audit Fail Worker',
            password: 'password1234567',
          },
        });

        // 1. Public response is still 200 with neutral message, never exposing audit failure
        assert.strictEqual(res.statusCode, 200);
        const body = JSON.parse(res.body);
        assert.strictEqual(body.message, REGISTER_NEUTRAL_MESSAGE);
        assert.strictEqual(body.error, undefined);

        // 2. User was still created successfully
        const createdUser = isolatedDb.prepare('SELECT * FROM users WHERE username = ?').get('audit-fail-worker') as any;
        assert.ok(createdUser, 'user must be created despite audit log failure');

        // 3. Warning was surfaced to route logger
        const warnLog = warnings.find(
          (w) => w.level === 40 && typeof w.msg === 'string' && w.msg.includes('audit record could not be persisted')
        );
        assert.ok(warnLog, 'a warning must be emitted to the route logger');
        assert.strictEqual(warnLog.userId, createdUser.id);
        assert.ok(warnLog.err, 'error details should be in the warning log');
      } finally {
        await isolatedApp.close();
        isolatedDb.close();
      }
    });
  });
});
