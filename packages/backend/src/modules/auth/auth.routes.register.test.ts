import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import bcrypt from 'bcryptjs';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { defaultAuthAdmissionLimiter } from './auth.admission-limiter.js';
import { buildAuthTestApp } from './auth-test-helper.js';
import { REGISTER_NEUTRAL_MESSAGE } from './auth.schema.js';
import { defaultRegistrationRateLimiter } from './auth.rate-limiter.js';

// Public worker registration is the one unauthenticated write path in the auth module. These
// cases pin its core contract: a self-registered worker lands pending approval, an administrator
// activation turns that account into a working login, a duplicate registration stays neutral
// without mutating the row, and a successful insert writes exactly one audit record. Input and
// admission guards live in auth.routes.register-admission.test.ts; bootstrap, storage and audit
// failure guards live in auth.routes.register-negative.test.ts; identity resolution lives in
// auth.routes.register-identity.test.ts.
describe('POST /api/auth/register (Public Worker Registration)', () => {
  let app: FastifyInstance;
  let db: Database.Database;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db;
  });

  // The routes share a process-wide admission limiter, so without a per-test reset the logins
  // exercised below would accumulate against later cases. Reset restores the state a fresh
  // process would start with; it never weakens the production defaults.
  beforeEach(() => defaultAuthAdmissionLimiter.reset());
  beforeEach(() => defaultRegistrationRateLimiter.reset());

  after(async () => {
    await app.close();
    db.close();
  });

  it('pending login 401/no token: registered worker cannot log in until administrator activates', async () => {
    const payload = {
      username: 'worker-pending',
      display_name: 'Pending Worker',
      password: 'password123',
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
      password: 'password123',
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

  it('audit record uses newly inserted user id safely on success', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'audit-test-worker', display_name: 'Audit Worker', password: 'password123' },
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
      payload: { username: 'audit-test-worker', display_name: 'Audit Worker', password: 'password123' },
    });
    const auditsAfterDuplicate = db.prepare('SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = ?').pluck().get(workerUser.id, 'register') as number;
    assert.strictEqual(auditsAfterDuplicate, 1, 'duplicate registration must not produce additional audit log');
  });
});
