import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Writable } from 'node:stream';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import DatabaseConstructor, { type Database } from 'better-sqlite3';

import { authRoutes } from './auth.routes.js';
import { createAuthenticate } from './auth.middleware.js';
import { buildAuthTestApp } from './auth-test-helper.js';
import { REGISTER_NEUTRAL_MESSAGE } from './auth.schema.js';
import { bootstrapRoutes } from './bootstrap.routes.js';

// The empty-installation bootstrap guard, the no-mutation-on-error invariant, a missing worker
// role during the INSERT, and an audit write that fails while the public response stays neutral.
// Input, rate-limit, and schema-bound guards live in auth.routes.register-admission.test.ts;
// the registerWorkerUser storage invariants themselves live in auth.service.test.ts. Cases that
// need a broken schema build an isolated in-memory app instead of sharing the file-level fixtures.
describe('POST /api/auth/register bootstrap, storage, and audit guards', () => {
  let app: FastifyInstance;
  let db: Database.Database;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db;
  });

  after(async () => {
    await app.close();
    db.close();
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
        payload: { username: 'preempt-worker', display_name: 'Preempt', password: 'password123' },
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
      payload: { username: 'bad-injection', display_name: 'Bad', password: 'password123', role_id: 1 },
    });

    const usersAfter = db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number;
    const auditsAfter = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

    assert.strictEqual(usersAfter, usersBefore, 'users table must not mutate on error');
    assert.strictEqual(auditsAfter, auditsBefore, 'audit_logs table must not mutate on error');
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
          password: 'password123',
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
          password: 'password123',
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
