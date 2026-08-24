import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { bootstrapRoutes } from './bootstrap.routes.js';

describe('administrator bootstrap', () => {
  const db = new Database(':memory:');
  const app = Fastify({ logger: false });
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  app.decorate('db', db); app.decorate('authenticate', async () => {});
  before(async () => { await app.register(bootstrapRoutes, { prefix: '/api/auth' }); await app.ready(); });
  after(async () => { await app.close(); db.close(); });

  it('creates exactly one active Administrador and closes setup', async () => {
    const payload = { username: 'owner', displayName: 'Administrador', password: 'strong-password' };
    assert.equal((await app.inject({ method: 'GET', url: '/api/auth/setup/status' })).json().setupRequired, true);
    const attempts = await Promise.all([
      app.inject({ method: 'POST', url: '/api/auth/setup', payload }),
      app.inject({ method: 'POST', url: '/api/auth/setup', payload }),
    ]);
    assert.deepEqual(attempts.map(({ statusCode }) => statusCode).sort(), [201, 409]);
    const user = db.prepare(`SELECT u.password_hash, u.is_active, r.name role FROM users u JOIN roles r ON r.id=u.role_id`).get() as any;
    assert.equal(user.role, 'Administrador');
    assert.equal(user.is_active, 1);
    assert.equal(await bcrypt.compare(payload.password, user.password_hash), true);
    assert.equal(user.password_hash.includes(payload.password), false);
  });

});
