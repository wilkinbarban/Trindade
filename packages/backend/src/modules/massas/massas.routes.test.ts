import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import Fastify, { type FastifyInstance } from 'fastify';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { createAuthenticate } from '../auth/auth.middleware.js';
import { AuthAdmissionLimiter } from '../auth/auth.admission-limiter.js';
import { authRoutes } from '../auth/auth.routes.js';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from '../../db/reference-data.js';
import { massasRoutes } from './massas.routes.js';
import type { MassasLaunchDayView, MassaIndividualLaunch } from './massas.service.js';

describe('massas routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let jwtSecret = '';
  let tokenAlice = '';
  let tokenBob = '';
  const passAlice = randomUUID();
  const passBob = randomUUID();
  const dateStr = '2026-10-20';

  before(async () => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
    verifyReferencePrerequisites(db);

    const adminRole = REFERENCE_DATASET.roles.find((r) => r.name === 'Administrador')!.id;
    const workerRole = REFERENCE_DATASET.roles.find((r) => r.name === 'Trabalhador')!.id;

    db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)').run(
      'alice',
      await bcrypt.hash(passAlice, 4),
      'Alice',
      adminRole,
    );
    db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)').run(
      'bob',
      await bcrypt.hash(passBob, 4),
      'Bob',
      workerRole,
    );

    jwtSecret = randomUUID();
    app = Fastify({ logger: false });
    app.decorate('db', db);
    app.decorate('authenticate', createAuthenticate(jwtSecret));
    const admissionLimiter = new AuthAdmissionLimiter({ maxUsernameLogin: 10_000, maxGlobalLogin: 10_000 });
    await app.register(authRoutes, { prefix: '/api/auth', jwtSecret, authAdmissionLimiter: admissionLimiter });
    await app.register(massasRoutes, { prefix: '/api/massas-launch' });
    await app.ready();

    // Login Alice
    const resAlice = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'alice', password: passAlice },
    });
    tokenAlice = JSON.parse(resAlice.body).token;

    // Login Bob
    const resBob = await app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'bob', password: passBob },
    });
    tokenBob = JSON.parse(resBob.body).token;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('refuses unauthenticated calls with 401', async () => {
    const resGet = await app.inject({ method: 'GET', url: `/api/massas-launch?date=${dateStr}` });
    assert.equal(resGet.statusCode, 401);

    const resPost = await app.inject({ method: 'POST', url: '/api/massas-launch', payload: { product_code: '1045', boxes: 1 } });
    assert.equal(resPost.statusCode, 401);

    const resPut = await app.inject({ method: 'PUT', url: '/api/massas-launch/1', payload: { boxes: 2 } });
    assert.equal(resPut.statusCode, 401);

    const resDelete = await app.inject({ method: 'DELETE', url: '/api/massas-launch/1' });
    assert.equal(resDelete.statusCode, 401);
  });

  it('GET /api/massas-launch returns 10 products with collective sums and individual launches', async () => {
    const res = await app.inject({
      method: 'GET',
      url: `/api/massas-launch?date=${dateStr}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(res.statusCode, 200);
    const body = JSON.parse(res.body) as MassasLaunchDayView;
    assert.equal(body.date, dateStr);
    assert.equal(body.products.length, 10);
    assert.equal(body.totalBoxes, 0);
  });

  it('POST /api/massas-launch creates launches for authenticated user', async () => {
    // Single product launch
    const resSingle = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        product_code: '1045', // MASSA CASEIRA (10 un/cx)
        boxes: 4,
      },
    });
    assert.equal(resSingle.statusCode, 201);
    const bodySingle = JSON.parse(resSingle.body) as { created: MassaIndividualLaunch[] };
    assert.equal(bodySingle.created.length, 1);
    assert.equal(bodySingle.created[0].boxes, 4);
    assert.equal(bodySingle.created[0].units, 40);
    assert.equal(bodySingle.created[0].username, 'alice');

    // Batch items launch by Bob
    const resBatch = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: {
        date: dateStr,
        items: [
          { product_code: '1045', boxes: 2 },
          { product_code: '39', boxes: 5 }, // MASSA DISCO 200G (10 un/cx)
        ],
      },
    });
    assert.equal(resBatch.statusCode, 201);
    const bodyBatch = JSON.parse(resBatch.body) as { created: MassaIndividualLaunch[] };
    assert.equal(bodyBatch.created.length, 2);
  });

  it('PUT /api/massas-launch/:id permits owner to edit and returns 403 for other users', async () => {
    // Alice creates a launch
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: dateStr, product_code: '60', boxes: 3 },
    });
    const launchId = JSON.parse(createRes.body).created[0].id;

    // Bob tries to edit Alice's launch -> 403 Forbidden
    const bobEditRes = await app.inject({
      method: 'PUT',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { boxes: 8 },
    });
    assert.equal(bobEditRes.statusCode, 403);

    // Alice edits her own launch -> 200 OK
    const aliceEditRes = await app.inject({
      method: 'PUT',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { boxes: 8 },
    });
    assert.equal(aliceEditRes.statusCode, 200);
    const updated = JSON.parse(aliceEditRes.body) as MassaIndividualLaunch;
    assert.equal(updated.boxes, 8);
  });

  it('DELETE /api/massas-launch/:id permits owner to delete and returns 403 for other users', async () => {
    // Bob creates a launch
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { date: dateStr, product_code: '91', boxes: 5 },
    });
    const launchId = JSON.parse(createRes.body).created[0].id;

    // Alice tries to delete Bob's launch -> 403 Forbidden
    const aliceDelRes = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(aliceDelRes.statusCode, 403);

    // Bob deletes his own launch -> 200 OK
    const bobDelRes = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenBob}` },
    });
    assert.equal(bobDelRes.statusCode, 200);
    assert.deepEqual(JSON.parse(bobDelRes.body), { ok: true });
  });

  it('PUT and DELETE return 404 for non-existent launch', async () => {
    const resPut = await app.inject({
      method: 'PUT',
      url: '/api/massas-launch/999999',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { boxes: 1 },
    });
    assert.equal(resPut.statusCode, 404);

    const resDel = await app.inject({
      method: 'DELETE',
      url: '/api/massas-launch/999999',
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(resDel.statusCode, 404);
  });
});
