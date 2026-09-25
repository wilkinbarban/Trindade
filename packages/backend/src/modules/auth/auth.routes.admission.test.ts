import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import Fastify from 'fastify';
import bcrypt from 'bcryptjs';
import { AuthAdmissionLimiter } from './auth.admission-limiter.js';
import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';
import { createAuthenticate } from './auth.middleware.js';
import { authRoutes } from './auth.routes.js';

describe('auth route admission (isolated limiter)', () => {
  let source: Awaited<ReturnType<typeof buildAuthTestApp>>;
  let app: ReturnType<typeof Fastify>;
  let fixtures: TestFixtures;
  let now = 0;
  const limiter = new AuthAdmissionLimiter({
    now: () => now,
    windowMs: 2_000,
    maxGlobalLogin: 20,
    maxUsernameLogin: 1,
    maxGlobalRefresh: 20,
    maxTokenRefresh: 1,
    maxConcurrentCompares: 1,
  });

  before(async () => {
    source = await buildAuthTestApp();
    fixtures = source.fixtures;
    app = Fastify({ logger: false });
    app.decorate('db', source.db);
    app.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));
    await app.register(authRoutes, {
      prefix: '/api/auth', jwtSecret: fixtures.jwtSecret, authAdmissionLimiter: limiter,
    });
    await app.ready();
  });

  after(async () => {
    if (app) await app.close();
    if (source) {
      await source.app.close();
      source.db.close();
    }
  });

  const login = (username: string, forwarded = '198.51.100.1') => app.inject({
    method: 'POST', url: '/api/auth/login',
    headers: { 'x-forwarded-for': forwarded },
    payload: { username, password: fixtures.admin.password },
  });
  const refresh = (refreshToken: string) => app.inject({
    method: 'POST', url: '/api/auth/refresh', payload: { refreshToken },
  });

  it('limits the normalized user despite forged XFF, reports remaining window, and admits after expiry', async () => {
    now = 0;
    limiter.reset();
    const first = await login(fixtures.admin.username);
    assert.equal(first.statusCode, 200);
    now = 500;
    const blocked = await login(`  ${fixtures.admin.username.toUpperCase()}  `, '203.0.113.99');
    assert.equal(blocked.statusCode, 429);
    assert.equal(blocked.headers['retry-after'], '2');
    assert.deepEqual(blocked.json(), { error: 'Too many requests' });
    now = 2_000;
    assert.equal((await login(fixtures.admin.username, '192.0.2.8')).statusCode, 200);
  });

  it('rejects a second concurrent bcrypt comparison and releases the slot when the first completes', async () => {
    now = 0;
    limiter.reset();
    const original = bcrypt.compare;
    let entered!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    bcrypt.compare = (async (password: string, hash: string) => {
      entered();
      await pending;
      return original(password, hash);
    }) as typeof bcrypt.compare;
    try {
      const first = login(fixtures.admin.username);
      await started;
      const blocked = await login(fixtures.worker.username);
      assert.equal(blocked.statusCode, 429);
      assert.equal(blocked.headers['retry-after'], '1');
      release();
      assert.equal((await first).statusCode, 200);
      now = 2_000;
      assert.equal((await login(fixtures.admin.username)).statusCode, 200);
    } finally {
      release();
      bcrypt.compare = original;
    }
  });

  it('admits refresh independently, limits token replay, and does not count invalid schemas', async () => {
    now = 0;
    limiter.reset();
    const invalidLogin = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { username: fixtures.admin.username } });
    assert.equal(invalidLogin.statusCode, 400);
    const loggedIn = await login(fixtures.admin.username);
    assert.equal(loggedIn.statusCode, 200);
    const token = loggedIn.json().refreshToken as string;
    const invalidRefresh = await app.inject({ method: 'POST', url: '/api/auth/refresh', payload: {} });
    assert.equal(invalidRefresh.statusCode, 400);
    const rotated = await refresh(token);
    assert.equal(rotated.statusCode, 200);
    const blocked = await refresh(token);
    assert.equal(blocked.statusCode, 429);
    assert.equal(blocked.headers['retry-after'], '2');
    // A different token still reaches credential validation, not the per-token limiter.
    assert.equal((await refresh('not-a-valid-session')).statusCode, 401);
    now = 2_000;
    assert.equal((await refresh(token)).statusCode, 401);
  });
});
