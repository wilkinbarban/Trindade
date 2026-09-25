import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import Database from 'better-sqlite3';
import { bootstrapRoutes } from './bootstrap.routes.js';

describe('administrator bootstrap HTTP boundary', () => {
  const db = new Database(':memory:');
  const app = Fastify({ logger: false });
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  app.decorate('db', db);
  before(async () => { await app.register(bootstrapRoutes, { prefix: '/api/auth' }); await app.ready(); });
  after(async () => { await app.close(); db.close(); });

  it('reports an empty installation without granting HTTP provisioning', async () => {
    assert.equal((await app.inject({ method: 'GET', url: '/api/auth/setup/status' })).json().setupRequired, true);
    const attempts = await Promise.all(Array.from({ length: 12 }, (_, index) =>
      app.inject({ method: 'POST', url: '/api/auth/setup', payload: index % 2
        ? { username: 'owner', displayName: 'Owner', password: 'strong-password' }
        : { password: 'another-secret' } })));
    assert.deepEqual(attempts.map((response) => response.statusCode), Array(12).fill(410));
    assert.ok(attempts.every((response) => !response.body.includes('another-secret')));
    assert.equal(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), 0);
    assert.equal((await app.inject({ method: 'GET', url: '/api/auth/setup/status' })).json().setupRequired, true);
  });
});
