import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import jwt from 'jsonwebtoken';
import { buildTestApp, type TestFixtures } from '../../test-helper.js';

/**
 * The report reads the day's incoming supplies from the mirror Trindade already keeps. The route exists so
 * the SPA and the WhatsApp export classify a seat the same way; a test here proves the public answer, not
 * the SQL behind it.
 */
describe('Report receipts route', () => {
  let app: import('fastify').FastifyInstance;
  let db: import('better-sqlite3').Database;
  let fixtures: TestFixtures;
  let workerToken: string;

  const url = (date?: string) =>
    date === undefined ? '/api/reports/receipts' : `/api/reports/receipts?date=${date}`;

  before(async () => {
    const built = await buildTestApp();
    app = built.app;
    db = built.db;
    fixtures = built.fixtures;

    workerToken = jwt.sign(
      { sub: fixtures.worker.id, username: fixtures.worker.username, role: 'Trabalhador', security_version: 1 },
      fixtures.jwtSecret,
    );
  });

  after(async () => {
    await app.close();
    db.close();
  });

  function seed(rows: ReadonlyArray<{ ent_lan: number; product_code: string; product_desc: string; quantity: number; operator_name: string; movement_type?: 'E' | 'S' }>) {
    const insert = db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES ('2026-10-29', @ent_lan, @product_code, @product_desc, @quantity, '2026-10-29', @operator_name, @movement_type, '2026-10-29T18:00:00.000Z')`,
    );
    for (const row of rows) insert.run({ movement_type: 'E', ...row });
  }

  it('answers 401 without a token', async () => {
    const res = await app.inject({ method: 'GET', url: url('2026-10-29') });
    assert.equal(res.statusCode, 401);
  });

  it('answers the day with its supplies and the snapshot time', async () => {
    seed([
      { ent_lan: 35911, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 3606, operator_name: 'VIVIANE' },
      { ent_lan: 35912, product_code: '000022', product_desc: 'MASSA PARA PASTEL ROLO 500G', quantity: 15, operator_name: 'JEFFERSON' },
      { ent_lan: 35913, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 10, operator_name: 'VIVIANE', movement_type: 'S' },
    ]);

    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-29'),
      headers: { authorization: `Bearer ${workerToken}` },
    });

    assert.equal(res.statusCode, 200);
    assert.deepEqual(JSON.parse(res.body), {
      date: '2026-10-29',
      migrated: true,
      syncedAt: '2026-10-29T18:00:00.000Z',
      receipts: [
        {
          entLan: 35911,
          productCode: '001774',
          productDesc: 'CAIXA DE PAPELAO PEQUENA MISTA',
          quantity: 3606,
          operatorName: 'VIVIANE',
          entryDate: '2026-10-29',
        },
      ],
    });
  });

  it('distinguishes a consulted day with no supplies from a day nobody asked about', async () => {
    seed([{ ent_lan: 35920, product_code: '000084', product_desc: 'MASSA PARA PASTEL DISCO 500G', quantity: 500, operator_name: 'VINICIUS R' }]);

    const consulted = await app.inject({
      method: 'GET',
      url: url('2026-10-29'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.equal(JSON.parse(consulted.body).syncedAt, '2026-10-29T18:00:00.000Z');

    // A different day has no rows at all: the mirror table exists, but this day was never consulted.
    const unasked = await app.inject({
      method: 'GET',
      url: url('2026-10-30'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.deepEqual(JSON.parse(unasked.body), {
      date: '2026-10-30',
      migrated: true,
      syncedAt: null,
      receipts: [],
    });
  });

  it('requires a date in YYYY-MM-DD', async () => {
    // The contract validates the shape, exactly like every other date in this API: a well-formed day with
    // no rows is an empty answer, not a bad request.
    for (const date of ['30-10-2026', 'today', '']) {
      const res = await app.inject({
        method: 'GET',
        url: url(date),
        headers: { authorization: `Bearer ${workerToken}` },
      });
      assert.equal(res.statusCode, 400, `date=${date} must be refused`);
    }
  });

  it('answers 503 with a named code when the mirror table is missing', async () => {
    db.exec('DROP TABLE aero_movements');

    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-29'),
      headers: { authorization: `Bearer ${workerToken}` },
    });

    assert.equal(res.statusCode, 503);
    assert.equal(JSON.parse(res.body).code, 'AERO_MOVEMENTS_NOT_MIGRATED');
  });
});
