import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import DatabaseConstructor, { type Database } from 'better-sqlite3';

import { authRoutes } from './auth.routes.js';
import { createAuthenticate } from './auth.middleware.js';
import { buildAuthTestApp } from './auth-test-helper.js';
import { defaultRegistrationRateLimiter, RegistrationRateLimiter } from './auth.rate-limiter.js';

// What the route must not accept and must not let through: privilege fields that would change the
// stored role, X-Forwarded-For that would forge a fresh identity, the per-username/global/concurrent
// bounds, and payloads outside the schema's length and byte limits. Cases that need tighter limits
// build an isolated in-memory app instead of sharing the file-level fixtures. Bootstrap, storage,
// and audit guards live in auth.routes.register-negative.test.ts.
describe('POST /api/auth/register admission and input guards', () => {
  let app: FastifyInstance;
  let db: Database;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db;
  });

  beforeEach(() => defaultRegistrationRateLimiter.reset());

  after(async () => {
    await app.close();
    db.close();
  });

  it('privilege-field injection rejected or ignored safely: strict schema rejects role_id, is_active, and extra fields', async () => {
    const base = {
      username: 'worker-privilege-attempt',
      display_name: 'Attempted Admin',
      password: 'password123',
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
        payload: { username: 'target-worker', display_name: 'Target', password: 'password123' },
      });
      assert.strictEqual(res1.statusCode, 200);

      const res2 = await isolatedApp.inject({
        method: 'POST',
        url: '/api/auth/register',
        headers: { 'x-forwarded-for': '192.168.1.2' },
        payload: { username: 'target-worker', display_name: 'Target', password: 'password123' },
      });
      assert.strictEqual(res2.statusCode, 200);

      // 3rd attempt for target-worker fails with 429 even with new forged XFF
      const res3 = await isolatedApp.inject({
        method: 'POST',
        url: '/api/auth/register',
        headers: { 'x-forwarded-for': '192.168.1.3' },
        payload: { username: 'target-worker', display_name: 'Target', password: 'password123' },
      });
      assert.strictEqual(res3.statusCode, 429);
      assert.match(JSON.parse(res3.body).error, /limite/i);
      assert.ok(res3.headers['retry-after']);

      // 2. Global limit: 4th global attempt (with different username) succeeds
      const res4 = await isolatedApp.inject({
        method: 'POST',
        url: '/api/auth/register',
        headers: { 'x-forwarded-for': '192.168.1.4' },
        payload: { username: 'other-worker-1', display_name: 'Other', password: 'password123' },
      });
      assert.strictEqual(res4.statusCode, 200);

      // 5th global attempt fails with 429 despite fresh username and fresh XFF
      const res5 = await isolatedApp.inject({
        method: 'POST',
        url: '/api/auth/register',
        headers: { 'x-forwarded-for': '192.168.1.5' },
        payload: { username: 'other-worker-2', display_name: 'Other', password: 'password123' },
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
        payload: { username: 'burst-worker', display_name: 'Burst', password: 'password123' },
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

  it('max input: validates bounds on username, display_name, and password bytes', async () => {
    // 1. Username too long (> 50 chars)
    const resUserTooLong = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'a'.repeat(51), display_name: 'Worker', password: 'password123' },
    });
    assert.strictEqual(resUserTooLong.statusCode, 400);

    // 2. Display name too long (> 100 chars)
    const resDisplayTooLong = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'valid-user', display_name: 'a'.repeat(101), password: 'password123' },
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

    // 5. Password too short (< 8 chars, raised from 4 to match setup/change-password)
    const resPass3Chars = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'valid-user', display_name: 'Worker', password: '123' },
    });
    assert.strictEqual(resPass3Chars.statusCode, 400);

    const resPass7Chars = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'valid-user', display_name: 'Worker', password: '1234567' },
    });
    assert.strictEqual(resPass7Chars.statusCode, 400);
    const resPass7Body = JSON.parse(resPass7Chars.body);
    assert.strictEqual(resPass7Body.error, 'Invalid input');
    assert.match(
      resPass7Body.details.fieldErrors.password?.[0] ?? '',
      /at least 8 characters/i
    );

    // Boundary: 8 characters is accepted
    const resPass8Chars = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'valid-user-8char', display_name: 'Worker', password: '12345678' },
    });
    assert.strictEqual(resPass8Chars.statusCode, 200);

    // 6. Empty / whitespace-only fields
    const resBlankUsername = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: '   ', display_name: 'Worker', password: 'password123' },
    });
    assert.strictEqual(resBlankUsername.statusCode, 400);

    const resBlankDisplay = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'valid-user', display_name: '   ', password: 'password123' },
    });
    assert.strictEqual(resBlankDisplay.statusCode, 400);
  });
});
