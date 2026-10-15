import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { createAuthenticate } from '../auth/auth.middleware.js';
import { AuthAdmissionLimiter } from '../auth/auth.admission-limiter.js';
import { authRoutes } from '../auth/auth.routes.js';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from '../../db/reference-data.js';
import { stockRoutes } from './stock.routes.js';

/**
 * The counting endpoints over http, with the same guard the rest of the API uses.
 *
 * The service owns the arithmetic and has its own tests; what is asserted here is the wire: who may
 * call, what a refusal looks like, and that the body the caller sends is the body that reaches the
 * counter. A route that accepted an unauthenticated call would be a way to rewrite a day's count.
 */
describe('stock routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let jwtSecret = '';
  let token = '';
  let userId = 0;
  const username = `stock-${randomUUID()}`;
  const password = randomUUID();

  before(async () => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
    verifyReferencePrerequisites(db);
    const inserted = db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
      .run(username, await bcrypt.hash(password, 4), username, REFERENCE_DATASET.roles.find((r) => r.name === 'Administrador')!.id);
    userId = Number(inserted.lastInsertRowid);

    jwtSecret = randomUUID();
    app = Fastify({ logger: false });
    app.decorate('db', db);
    app.decorate('authenticate', createAuthenticate(jwtSecret));
    const admissionLimiter = new AuthAdmissionLimiter({ maxUsernameLogin: 10_000, maxGlobalLogin: 10_000 });
    await app.register(authRoutes, { prefix: '/api/auth', jwtSecret, authAdmissionLimiter: admissionLimiter });
    await app.register(stockRoutes, { prefix: '/api/stock' });
    await app.ready();
  });

  after(async () => {
    await app.close();
    db.close();
  });

  function headers(): Record<string, string> {
    return { authorization: `Bearer ${token}` };
  }

  it('refuses every call without a session', async () => {
    const list = await app.inject({ method: 'GET', url: '/api/stock?date=2026-10-15' });
    const add = await app.inject({ method: 'POST', url: '/api/stock/add-entry', payload: { date: '2026-10-15', product_code: '787', boxes_added: 1 } });
    const reset = await app.inject({ method: 'POST', url: '/api/stock/reset-product', payload: { date: '2026-10-15', product_code: '787' } });
    const history = await app.inject({ method: 'GET', url: '/api/stock/history' });
    assert.deepStrictEqual([list.statusCode, add.statusCode, reset.statusCode, history.statusCode], [401, 401, 401, 401]);
  });

  it('answers the day with the whole catalog, accumulates through the wire, and lists the history', async () => {
    const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { username, password } });
    assert.strictEqual(login.statusCode, 200);
    token = (login.json() as { token: string }).token;
    assert.ok(token.length > 0 && userId > 0);

    const empty = await app.inject({ method: 'GET', url: '/api/stock?date=2026-10-15', headers: headers() });
    assert.strictEqual(empty.statusCode, 200);
    assert.strictEqual((empty.json() as { products: unknown[] }).products.length, 25);

    const first = await app.inject({ method: 'POST', url: '/api/stock/add-entry', headers: headers(), payload: { date: '2026-10-15', product_code: '1800', boxes_added: 2 } });
    assert.strictEqual(first.statusCode, 200);
    const second = await app.inject({ method: 'POST', url: '/api/stock/add-entry', headers: headers(), payload: { date: '2026-10-15', product_code: '1800', boxes_added: 3 } });
    assert.strictEqual(second.statusCode, 200);
    const day = await app.inject({ method: 'GET', url: '/api/stock?date=2026-10-15', headers: headers() });
    const brotinho = (day.json() as { products: Array<{ code: string; boxes: number; units: number }> }).products.find((p) => p.code === '1800')!;
    assert.deepStrictEqual([brotinho.boxes, brotinho.units], [5, 250]);

    const refused = await app.inject({ method: 'POST', url: '/api/stock/add-entry', headers: headers(), payload: { date: '2026-10-15', product_code: '999999', boxes_added: 1 } });
    assert.strictEqual(refused.statusCode, 400);
    const zero = await app.inject({ method: 'POST', url: '/api/stock/add-entry', headers: headers(), payload: { date: '2026-10-15', product_code: '1800', boxes_added: 0 } });
    assert.strictEqual(zero.statusCode, 400);

    const reset = await app.inject({ method: 'POST', url: '/api/stock/reset-product', headers: headers(), payload: { date: '2026-10-15', product_code: '1800' } });
    assert.strictEqual(reset.statusCode, 200);
    const after = await app.inject({ method: 'GET', url: '/api/stock?date=2026-10-15', headers: headers() });
    assert.strictEqual((after.json() as { products: Array<{ code: string; boxes: number }> }).products.find((p) => p.code === '1800')!.boxes, 0);

    const history = await app.inject({ method: 'GET', url: '/api/stock/history', headers: headers() });
    assert.strictEqual(history.statusCode, 200);
    assert.deepStrictEqual((history.json() as { days: Array<{ date: string }> }).days.map((d) => d.date), ['2026-10-15']);
  });
});
