import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import Fastify, { type FastifyInstance } from 'fastify';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { createAuthenticate } from '../auth/auth.middleware.js';
import { AuthAdmissionLimiter } from '../auth/auth.admission-limiter.js';
import { authRoutes } from '../auth/auth.routes.js';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from '../../db/reference-data.js';
import { getSaoPauloDateString } from '../../utils/date.js';
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
  // The only day a launch may still be rewritten: yesterday is a record the API itself refuses to touch.
  const todayStr = getSaoPauloDateString();

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

  it('GET /report answers the reconciled day, with the tablet production on its operator', async () => {
    const launch = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: dateStr, product_code: '1045', boxes: 2 },
    });
    assert.equal(launch.statusCode, 201);

    // Two seats: one the launch already describes, one that only ever existed on the tablet.
    const insertSeat = (entLan: number, code: string, quantity: number, operator: string) => {
      db.prepare(
        `INSERT INTO aero_movements
           (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
         VALUES (?, ?, ?, 'MASSA', ?, ?, ?, 'E', '2026-10-20T12:00:00.000Z')`,
      ).run(dateStr, entLan, code, quantity, dateStr, operator);
    };
    insertSeat(7000, '1045', 20, 'ALICE');
    insertSeat(7001, '1047', 30, 'TIAGO');

    const res = await app.inject({
      method: 'GET',
      url: `/api/massas-launch/report?date=${dateStr}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });

    assert.equal(res.statusCode, 200);
    const body = JSON.parse(res.body) as {
      totalUnits: number;
      operators: { name: string; units: number }[];
      balance: { producedUnits: number; registeredUnits: number; differenceUnits: number };
      reconciledEntLans: number[];
    };
    // 20 boxes units lançadas aqui + 30 que só a tablet registrou; o seat conciliado não conta duas vezes.
    assert.equal(body.totalUnits, 50);
    assert.deepEqual(body.reconciledEntLans, [7000]);
    // Biggest share first, which is the order the screen prints.
    assert.deepEqual(
      body.operators.map((operator) => [operator.name, operator.units]),
      [['TIAGO', 30], ['Alice', 20]],
    );
    assert.equal(body.balance.producedUnits, 50);
    assert.equal(body.balance.registeredUnits, 50);
    assert.equal(body.balance.differenceUnits, 0);
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

  /**
   * The import path: the page sends the seat and the name the tablet wrote, and the launch has to end up
   * owned by that operator with the seat recorded, or the day shows someone else's boxes and never settles.
   */
  it('POST /api/massas-launch imports a seat under its own operator and records the seat', async () => {
    const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
    for (const [username, displayName] of [
      ['victor', 'Victor'],
      ['gustavo', 'Gustavo'],
    ]) {
      db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)').run(
        username,
        'hash',
        displayName,
        roleId,
      );
    }

    const resVictor = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        ent_lan: 35937,
        operator_name: 'VICTOR',
        items: [{ product_code: '5000', boxes: 27 }],
      },
    });
    assert.equal(resVictor.statusCode, 201, resVictor.body);
    const createdVictor = (JSON.parse(resVictor.body) as { created: MassaIndividualLaunch[] }).created[0];
    assert.equal(createdVictor.ent_lan, 35937);
    assert.equal(createdVictor.username, 'victor');

    // The seat carries a surname initial the login never has; it still resolves to the one Gustavo there is.
    const resGustavo = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        ent_lan: 35939,
        operator_name: 'GUSTAVO H',
        items: [{ product_code: '22', boxes: 3 }],
      },
    });
    assert.equal(resGustavo.statusCode, 201, resGustavo.body);
    const createdGustavo = (JSON.parse(resGustavo.body) as { created: MassaIndividualLaunch[] }).created[0];
    assert.equal(createdGustavo.ent_lan, 35939);
    assert.equal(createdGustavo.username, 'gustavo');

    // The day view is where the screens read the seat from.
    const day = await app.inject({
      method: 'GET',
      url: `/api/massas-launch?date=${dateStr}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    const view = JSON.parse(day.body) as MassasLaunchDayView;
    const seats = view.launches.filter((launch) => launch.ent_lan !== null).map((launch) => launch.ent_lan);
    assert.deepEqual(seats, [35937, 35939]);
  });

  it('keeps the seat when the operator name names nobody, and refuses the same seat twice', async () => {
    // An administrator, because a seat whose operator names nobody has no worker it could belong to.
    const unknown = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        ent_lan: 35950,
        operator_name: 'MARIA DA SILVA',
        items: [{ product_code: '91', boxes: 2 }],
      },
    });
    assert.equal(unknown.statusCode, 201, unknown.body);
    const created = (JSON.parse(unknown.body) as { created: MassaIndividualLaunch[] }).created[0];
    assert.equal(created.ent_lan, 35950);
    assert.equal(created.username, 'alice');

    const again = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        ent_lan: 35950,
        operator_name: 'MARIA DA SILVA',
        items: [{ product_code: '91', boxes: 2 }],
      },
    });
    assert.equal(again.statusCode, 409, again.body);
    assert.equal((JSON.parse(again.body) as { code: string }).code, 'MASSAS_ALREADY_IMPORTED');

    // Nothing new was stored: the refusal is about the seat, and it happens before any insert.
    const stored = db
      .prepare('SELECT COUNT(*) AS n FROM massa_launches WHERE ent_lan = ?')
      .get(35950) as { n: number };
    assert.equal(stored.n, 1);
  });

  it('refuses a seat sent for several items, because one seat is one product line', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: {
        date: dateStr,
        ent_lan: 35960,
        items: [
          { product_code: '1045', boxes: 1 },
          { product_code: '39', boxes: 1 },
        ],
      },
    });
    assert.equal(res.statusCode, 400, res.body);
    assert.equal((JSON.parse(res.body) as { code: string }).code, 'MASSAS_INVALID_LAUNCH_DATA');
  });

  it('PUT /api/massas-launch/:id permits owner to edit and returns 403 for other users', async () => {
    // Alice creates a launch
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: todayStr, product_code: '60', boxes: 3 },
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

    // Bob creates a launch
    const bobCreateRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { date: todayStr, product_code: '1045', boxes: 2 },
    });
    const bobLaunchId = JSON.parse(bobCreateRes.body).created[0].id;

    // Alice (Administrador) edits Bob's launch -> 200 OK
    const adminEditBobRes = await app.inject({
      method: 'PUT',
      url: `/api/massas-launch/${bobLaunchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { boxes: 6 },
    });
    assert.equal(adminEditBobRes.statusCode, 200);
    const updatedBob = JSON.parse(adminEditBobRes.body) as MassaIndividualLaunch;
    assert.equal(updatedBob.boxes, 6);
  });

  it('refuses to edit or delete a launch the ERP already recorded, even for an Administrador', async () => {
    const launch = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: todayStr, product_code: '1045', boxes: 2 },
    });
    assert.equal(launch.statusCode, 201);
    const launchId = (JSON.parse(launch.body) as { created: { id: number }[] }).created[0].id;

    db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, 7100, '1045', 'MASSA', 20, ?, 'ALICE', 'E', '2026-10-20T12:00:00.000Z')`,
    ).run(todayStr, todayStr);

    const edit = await app.inject({
      method: 'PUT',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { boxes: 9 },
    });
    assert.equal(edit.statusCode, 409);
    assert.equal(JSON.parse(edit.body).code, 'MASSAS_RECONCILED');

    const remove = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(remove.statusCode, 409);
    assert.equal(JSON.parse(remove.body).code, 'MASSAS_RECONCILED');

    // The row is still the two boxes the plant launched, not the nine the refused edit asked for.
    const day = await app.inject({
      method: 'GET',
      url: `/api/massas-launch?date=${todayStr}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    const launches = (JSON.parse(day.body) as MassasLaunchDayView).launches;
    const kept = launches.find((row) => row.id === launchId);
    assert.equal(kept?.boxes, 2);
  });

  it('DELETE /api/massas-launch/:id permits owner and Administrador to delete, and returns 403 for other workers', async () => {
    // Alice creates a launch
    const aliceCreateRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: todayStr, product_code: '60', boxes: 3 },
    });
    const aliceLaunchId = JSON.parse(aliceCreateRes.body).created[0].id;

    // Bob (Trabalhador) tries to delete Alice's launch -> 403 Forbidden
    const bobDelAliceRes = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${aliceLaunchId}`,
      headers: { authorization: `Bearer ${tokenBob}` },
    });
    assert.equal(bobDelAliceRes.statusCode, 403);

    // Bob creates a launch
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { date: todayStr, product_code: '91', boxes: 5 },
    });
    const launchId = JSON.parse(createRes.body).created[0].id;

    // Alice (Administrador) deletes Bob's launch -> 200 OK
    const aliceDelRes = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(aliceDelRes.statusCode, 200);
    assert.deepEqual(JSON.parse(aliceDelRes.body), { ok: true });

    // Bob deletes his own remaining launch (creates one to delete)
    const bobOwnCreateRes = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { date: todayStr, product_code: '91', boxes: 1 },
    });
    const bobOwnLaunchId = JSON.parse(bobOwnCreateRes.body).created[0].id;

    // Bob deletes his own launch -> 200 OK
    const bobDelRes = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${bobOwnLaunchId}`,
      headers: { authorization: `Bearer ${tokenBob}` },
    });
    assert.equal(bobDelRes.statusCode, 200);
    assert.deepEqual(JSON.parse(bobDelRes.body), { ok: true });
  });

  it('answers 409 MASSAS_HISTORY_FROZEN when an Administrador rewrites a past day', async () => {
    // The screen no longer offers it, and the API must not accept it either: a request can be written by hand.
    const launch = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: dateStr, product_code: '1045', boxes: 3 },
    });
    assert.equal(launch.statusCode, 201);
    const launchId = (JSON.parse(launch.body) as { created: { id: number }[] }).created[0].id;

    const edit = await app.inject({
      method: 'PUT',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { boxes: 9 },
    });
    assert.equal(edit.statusCode, 409);
    const editBody = JSON.parse(edit.body) as { error: string; code: string };
    assert.equal(editBody.code, 'MASSAS_HISTORY_FROZEN');
    assert.equal(editBody.error, 'O histórico de massas é somente leitura: dias passados não podem ser alterados');

    const removal = await app.inject({
      method: 'DELETE',
      url: `/api/massas-launch/${launchId}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    assert.equal(removal.statusCode, 409);
    assert.equal((JSON.parse(removal.body) as { code: string }).code, 'MASSAS_HISTORY_FROZEN');

    // Both refusals left the recorded day exactly as the plant left it.
    const day = await app.inject({
      method: 'GET',
      url: `/api/massas-launch?date=${dateStr}`,
      headers: { authorization: `Bearer ${tokenAlice}` },
    });
    const kept = (JSON.parse(day.body) as MassasLaunchDayView).launches.find((row) => row.id === launchId);
    assert.equal(kept?.boxes, 3);
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

  it('GET /history requires auth and returns days array with counts', async () => {
    // 401 without token
    const unauthRes = await app.inject({
      method: 'GET',
      url: '/api/massas-launch/history',
    });
    assert.equal(unauthRes.statusCode, 401);

    // Create a launch on a specific date
    await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date: '2026-10-18', product_code: '1045', boxes: 3 },
    });

    // 200 with auth
    const authRes = await app.inject({
      method: 'GET',
      url: '/api/massas-launch/history',
      headers: { authorization: `Bearer ${tokenBob}` },
    });
    assert.equal(authRes.statusCode, 200);
    const body = JSON.parse(authRes.body);
    assert.ok(Array.isArray(body.days));
    const day18 = body.days.find((d: { date: string }) => d.date === '2026-10-18');
    assert.ok(day18);
    assert.equal(day18.productsCount, 1);
    assert.equal(day18.totalBoxes, 3);
    assert.equal(day18.totalUnits, 30);
    assert.equal(day18.operatorsCount, 1);
  });

  describe('S4 & S9: error codes, future dates and language neutrality', () => {
    it('refuses future launch dates with 400 and MASSAS_FUTURE_DATE and persists nothing', async () => {
      const futureDate = '2099-12-31';
      const countBefore = (db.prepare('SELECT COUNT(*) as count FROM massa_launches WHERE launch_date = ?').get(futureDate) as { count: number }).count;
      assert.equal(countBefore, 0);

      const res = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: {
          date: futureDate,
          product_code: '1045',
          boxes: 5,
        },
      });

      assert.equal(res.statusCode, 400);
      const body = JSON.parse(res.body);
      assert.equal(body.error, 'Future launch dates are not allowed');
      assert.equal(body.code, 'MASSAS_FUTURE_DATE');

      const countAfter = (db.prepare('SELECT COUNT(*) as count FROM massa_launches WHERE launch_date = ?').get(futureDate) as { count: number }).count;
      assert.equal(countAfter, 0);
    });

    it('accepts launches for today (São Paulo date)', async () => {
      const today = getSaoPauloDateString();
      const res = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: {
          date: today,
          product_code: '1045',
          boxes: 2,
        },
      });

      assert.equal(res.statusCode, 201);
      const body = JSON.parse(res.body);
      assert.equal(body.created.length, 1);
      assert.equal(body.created[0].launch_date, today);
    });

    it('accepts launches for past dates and defaults omitted date to today', async () => {
      // Past date
      const pastDate = '2026-01-01';
      const resPast = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: {
          date: pastDate,
          product_code: '1045',
          boxes: 1,
        },
      });
      assert.equal(resPast.statusCode, 201);
      assert.equal(JSON.parse(resPast.body).created[0].launch_date, pastDate);

      // Omitted date
      const resOmitted = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: {
          product_code: '1045',
          boxes: 1,
        },
      });
      assert.equal(resOmitted.statusCode, 201);
      assert.equal(JSON.parse(resOmitted.body).created[0].launch_date, getSaoPauloDateString());
    });

    it('returns MASSAS_CREATE_FAILED when creation fails inside the service', async () => {
      const resUnknownProduct = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: {
          date: '2026-10-18',
          items: [{ product_code: '999999', boxes: 1 }],
        },
      });
      assert.equal(resUnknownProduct.statusCode, 400);
      const body = JSON.parse(resUnknownProduct.body);
      assert.equal(body.error, 'Unknown massa product code: 999999');
      assert.equal(body.code, 'MASSAS_CREATE_FAILED');
    });

    it('returns stable semantic codes alongside byte-identical error strings across all routes', async () => {
      // 1. GET / invalid date query
      const resGetDate = await app.inject({
        method: 'GET',
        url: '/api/massas-launch?date=not-a-date',
        headers: { authorization: `Bearer ${tokenAlice}` },
      });
      assert.equal(resGetDate.statusCode, 400);
      const bodyGetDate = JSON.parse(resGetDate.body);
      assert.equal(bodyGetDate.error, 'Invalid date format');
      assert.equal(bodyGetDate.code, 'MASSAS_INVALID_DATE_FORMAT');

      // 2. POST / invalid body schema
      const resPostInvalid = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { date: dateStr, boxes: -5 },
      });
      assert.equal(resPostInvalid.statusCode, 400);
      const bodyPostInvalid = JSON.parse(resPostInvalid.body);
      assert.equal(bodyPostInvalid.error, 'Invalid launch data');
      assert.equal(bodyPostInvalid.code, 'MASSAS_INVALID_LAUNCH_DATA');
      assert.ok(bodyPostInvalid.details);

      // 3. POST / empty items and missing product_code/boxes
      const resPostEmpty = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { date: dateStr },
      });
      assert.equal(resPostEmpty.statusCode, 400);
      const bodyPostEmpty = JSON.parse(resPostEmpty.body);
      assert.equal(bodyPostEmpty.error, 'Must provide either items array or product_code and boxes');
      assert.equal(bodyPostEmpty.code, 'MASSAS_EMPTY_ITEMS');

      // 4. PUT /:id invalid param ID
      const resPutBadId = await app.inject({
        method: 'PUT',
        url: '/api/massas-launch/abc',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { boxes: 2 },
      });
      assert.equal(resPutBadId.statusCode, 400);
      const bodyPutBadId = JSON.parse(resPutBadId.body);
      assert.equal(bodyPutBadId.error, 'Invalid launch ID');
      assert.equal(bodyPutBadId.code, 'MASSAS_INVALID_ID');

      // 5. PUT /:id invalid boxes count
      const resPutBadBoxes = await app.inject({
        method: 'PUT',
        url: '/api/massas-launch/1',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { boxes: 0 },
      });
      assert.equal(resPutBadBoxes.statusCode, 400);
      const bodyPutBadBoxes = JSON.parse(resPutBadBoxes.body);
      assert.equal(bodyPutBadBoxes.error, 'Invalid boxes count');
      assert.equal(bodyPutBadBoxes.code, 'MASSAS_INVALID_BOXES');
      assert.ok(bodyPutBadBoxes.details);

      // 6. PUT /:id not found
      const resPutNotFound = await app.inject({
        method: 'PUT',
        url: '/api/massas-launch/999999',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { boxes: 5 },
      });
      assert.equal(resPutNotFound.statusCode, 404);
      const bodyPutNotFound = JSON.parse(resPutNotFound.body);
      assert.equal(bodyPutNotFound.error, 'Launch not found');
      assert.equal(bodyPutNotFound.code, 'MASSAS_NOT_FOUND');

      // Create a launch by Alice for ownership testing
      const createRes = await app.inject({
        method: 'POST',
        url: '/api/massas-launch',
        headers: { authorization: `Bearer ${tokenAlice}` },
        payload: { date: todayStr, product_code: '1045', boxes: 3 },
      });
      const aliceLaunchId = JSON.parse(createRes.body).created[0].id;

      // 7. PUT /:id forbidden for another user
      const resPutForbidden = await app.inject({
        method: 'PUT',
        url: `/api/massas-launch/${aliceLaunchId}`,
        headers: { authorization: `Bearer ${tokenBob}` },
        payload: { boxes: 6 },
      });
      assert.equal(resPutForbidden.statusCode, 403);
      const bodyPutForbidden = JSON.parse(resPutForbidden.body);
      assert.equal(bodyPutForbidden.error, 'Forbidden: cannot edit launches belonging to another user');
      assert.equal(bodyPutForbidden.code, 'MASSAS_FORBIDDEN');

      // 8. DELETE /:id invalid param ID
      const resDelBadId = await app.inject({
        method: 'DELETE',
        url: '/api/massas-launch/abc',
        headers: { authorization: `Bearer ${tokenAlice}` },
      });
      assert.equal(resDelBadId.statusCode, 400);
      const bodyDelBadId = JSON.parse(resDelBadId.body);
      assert.equal(bodyDelBadId.error, 'Invalid launch ID');
      assert.equal(bodyDelBadId.code, 'MASSAS_INVALID_ID');

      // 9. DELETE /:id not found
      const resDelNotFound = await app.inject({
        method: 'DELETE',
        url: '/api/massas-launch/999999',
        headers: { authorization: `Bearer ${tokenAlice}` },
      });
      assert.equal(resDelNotFound.statusCode, 404);
      const bodyDelNotFound = JSON.parse(resDelNotFound.body);
      assert.equal(bodyDelNotFound.error, 'Launch not found');
      assert.equal(bodyDelNotFound.code, 'MASSAS_NOT_FOUND');

      // 10. DELETE /:id forbidden for another user
      const resDelForbidden = await app.inject({
        method: 'DELETE',
        url: `/api/massas-launch/${aliceLaunchId}`,
        headers: { authorization: `Bearer ${tokenBob}` },
      });
      assert.equal(resDelForbidden.statusCode, 403);
      const bodyDelForbidden = JSON.parse(resDelForbidden.body);
      assert.equal(bodyDelForbidden.error, 'Forbidden: cannot delete launches belonging to another user');
      assert.equal(bodyDelForbidden.code, 'MASSAS_FORBIDDEN');
    });

    it('ignores Accept-Language and returns identical English error responses', async () => {
      const resWithoutLang = await app.inject({
        method: 'GET',
        url: '/api/massas-launch?date=invalid',
        headers: { authorization: `Bearer ${tokenAlice}` },
      });

      const resWithEs = await app.inject({
        method: 'GET',
        url: '/api/massas-launch?date=invalid',
        headers: {
          authorization: `Bearer ${tokenAlice}`,
          'accept-language': 'es-ES,es;q=0.9',
        },
      });

      const resWithPt = await app.inject({
        method: 'GET',
        url: '/api/massas-launch?date=invalid',
        headers: {
          authorization: `Bearer ${tokenAlice}`,
          'accept-language': 'pt-BR,pt;q=0.9',
        },
      });

      assert.equal(resWithoutLang.statusCode, 400);
      assert.equal(resWithEs.statusCode, 400);
      assert.equal(resWithPt.statusCode, 400);

      assert.deepEqual(JSON.parse(resWithEs.body), JSON.parse(resWithoutLang.body));
      assert.deepEqual(JSON.parse(resWithPt.body), JSON.parse(resWithoutLang.body));
      assert.equal(JSON.parse(resWithEs.body).error, 'Invalid date format');
      assert.equal(JSON.parse(resWithEs.body).code, 'MASSAS_INVALID_DATE_FORMAT');
    });
  });

describe('POST /api/massas-launch import ownership', () => {
  it('refuses a worker importing a seat the ERP attributes to somebody else, and stores nothing', async () => {
    const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
    db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)').run(
      'carla',
      'hash',
      'Carla',
      roleId,
    );
    const carlaId = db.prepare("SELECT id FROM users WHERE username = 'carla'").pluck().get() as number;
    const date = '2026-10-21';
    db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, 8001, '1045', 'MASSA', 20, ?, 'CARLA', 'E', '2026-10-21T10:00:00.000Z')`,
    ).run(date, date);

    // Minted here rather than logged in: the account exists for its id and role, and bcrypt work in this
    // test would only make the ownership rule slower to observe.
    const carlaToken = jwt.sign(
      { sub: carlaId, username: 'carla', role: 'Trabalhador', security_version: 1 },
      jwtSecret,
      { expiresIn: 60 },
    );

    // Bob is a worker too, and this seat is Carla's.
    const refused = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenBob}` },
      payload: { date, ent_lan: 8001, operator_name: 'CARLA', items: [{ product_code: '1045', boxes: 1 }] },
    });
    assert.equal(refused.statusCode, 403, refused.body);
    assert.equal((JSON.parse(refused.body) as { code: string }).code, 'MASSAS_FORBIDDEN');
    const stored = db.prepare('SELECT COUNT(*) AS n FROM massa_launches WHERE ent_lan = 8001').get() as { n: number };
    assert.equal(stored.n, 0, 'the refusal stored a launch');

    // Carla owns it, so Carla may import it.
    const allowed = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${carlaToken}` },
      payload: { date, ent_lan: 8001, operator_name: 'CARLA', items: [{ product_code: '1045', boxes: 1 }] },
    });
    assert.equal(allowed.statusCode, 201, allowed.body);
    const created = (JSON.parse(allowed.body) as { created: MassaIndividualLaunch[] }).created[0];
    assert.equal(created.username, 'carla');
  });

  it('lets an administrator import a seat that belongs to a worker', async () => {
    const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
    db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)').run(
      'diego',
      'hash',
      'Diego',
      roleId,
    );
    const date = '2026-10-22';
    db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, 8002, '1045', 'MASSA', 20, ?, 'DIEGO', 'E', '2026-10-22T10:00:00.000Z')`,
    ).run(date, date);

    const res = await app.inject({
      method: 'POST',
      url: '/api/massas-launch',
      headers: { authorization: `Bearer ${tokenAlice}` },
      payload: { date, ent_lan: 8002, operator_name: 'DIEGO', items: [{ product_code: '1045', boxes: 1 }] },
    });
    assert.equal(res.statusCode, 201, res.body);
    const created = (JSON.parse(res.body) as { created: MassaIndividualLaunch[] }).created[0];
    // The boxes are Diego's: the administrator only performed the import.
    assert.equal(created.username, 'diego');
  });
});
});
