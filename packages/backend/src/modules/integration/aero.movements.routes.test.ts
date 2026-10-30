import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { buildTestApp, type TestFixtures } from '../../test-helper.js';
import { REFERENCE_DATASET } from '../../db/reference-data.js';

/**
 * The ERP mirror is a snapshot, not an authority: the company WebService owns `ENT_LAN`, and these
 * endpoints only keep a copy so every device in the plant reads the same day without running the
 * shortcut again. The tests treat the HTTP surface as the contract -- storage is never inspected
 * directly except to prove that a refused request wrote nothing.
 */
describe('Aero movements routes', () => {
  let app: import('fastify').FastifyInstance;
  let db: import('better-sqlite3').Database;
  let fixtures: TestFixtures;
  let wilkinToken: string;
  let wilkinId: number;
  let workerToken: string;

  const seat = {
    ent_lan: 10542,
    product_code: '001192',
    product_desc: 'MASSA QUADRADA',
    quantity: 90,
    entry_date: '2026-10-28',
    operator_name: 'WILKIN',
    movement_type: 'E' as const,
  };

  const url = (date?: string) =>
    date === undefined
      ? '/api/integration/aero/movements'
      : `/api/integration/aero/movements?date=${date}`;

  before(async () => {
    const built = await buildTestApp();
    app = built.app;
    db = built.db;
    fixtures = built.fixtures;

    const adminRoleId = REFERENCE_DATASET.roles.find((r) => r.name === 'Administrador')!.id;
    const passwordHash = await bcrypt.hash('wilkin-pass', 4);
    const inserted = db
      .prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active, security_version) VALUES (?, ?, ?, ?, 1, 1)',
      )
      .run('wilkin', passwordHash, 'Wilkin Admin', adminRoleId);
    wilkinId = Number(inserted.lastInsertRowid);

    wilkinToken = jwt.sign(
      { sub: wilkinId, username: 'wilkin', role: 'Administrador', security_version: 1 },
      fixtures.jwtSecret,
    );
    workerToken = jwt.sign(
      { sub: fixtures.worker.id, username: fixtures.worker.username, role: 'Trabalhador', security_version: 1 },
      fixtures.jwtSecret,
    );
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('answers 401 without a token, for reading and for writing', async () => {
    const read = await app.inject({ method: 'GET', url: url('2026-10-28') });
    assert.equal(read.statusCode, 401);

    const write = await app.inject({
      method: 'POST',
      url: url(),
      payload: { date: '2026-10-28', movements: [seat] },
    });
    assert.equal(write.statusCode, 401);
  });

  it('answers an empty list for a day with no snapshot', async () => {
    const res = await app.inject({
      method: 'GET',
      url: url('2026-01-02'),
      headers: { authorization: `Bearer ${workerToken}` },
    });

    assert.equal(res.statusCode, 200);
    assert.deepEqual(JSON.parse(res.body), { date: '2026-01-02', movements: [] });
  });

  it('requires a date in YYYY-MM-DD', async () => {
    const missing = await app.inject({
      method: 'GET',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.equal(missing.statusCode, 400);
    assert.equal(JSON.parse(missing.body).code, 'INVALID_DATE_FORMAT');

    const malformed = await app.inject({
      method: 'GET',
      url: url('28-10-2026'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.equal(malformed.statusCode, 400);
    assert.equal(JSON.parse(malformed.body).code, 'INVALID_DATE_FORMAT');
  });

  it('stores the snapshot and reads it back with every field the mirror renders', async () => {
    const stored = await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { date: '2026-10-28', movements: [seat] },
    });
    assert.equal(stored.statusCode, 201);
    assert.deepEqual(JSON.parse(stored.body), { saved: 1, massas: 1, insumos: 0 });

    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-28'),
      headers: { authorization: `Bearer ${workerToken}` },
    });

    assert.equal(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.equal(body.date, '2026-10-28');
    assert.equal(body.movements.length, 1);
    const [movement] = body.movements;
    assert.equal(movement.ent_lan, 10542);
    assert.equal(movement.product_code, '001192');
    assert.equal(movement.product_desc, 'MASSA QUADRADA');
    assert.equal(movement.quantity, 90);
    assert.equal(movement.entry_date, '2026-10-28');
    assert.equal(movement.operator_name, 'WILKIN');
    assert.equal(movement.movement_type, 'E');
    assert.match(movement.synced_at, /^\d{4}-\d{2}-\d{2}T/);
    // The row the screens hide an import button on needs to know whose seat it is, and the answer has to
    // be the one the backend enforces -- the same resolution, from the same seat.
    assert.equal(movement.owner_user_id, wilkinId);
  });

  it('keeps one row per ERP seat: a second sync of the same seat updates it instead of duplicating it', async () => {
    await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        date: '2026-10-28',
        movements: [{ ...seat, quantity: 90, operator_name: 'wilkin' }],
      },
    });

    // The ERP answers the operator in uppercase; the stored name is normalised so two spellings of the
    // same operator do not render as two chips.
    const first = await app.inject({
      method: 'GET',
      url: url('2026-10-28'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.equal(JSON.parse(first.body).movements.length, 1);
    assert.equal(JSON.parse(first.body).movements[0].operator_name, 'WILKIN');

    const stored = await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { date: '2026-10-28', movements: [{ ...seat, quantity: 120 }] },
    });
    assert.equal(stored.statusCode, 201);

    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-28'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    const body = JSON.parse(res.body);
    assert.equal(body.movements.length, 1, 'a seat is unique: the second sync must not add a row');
    assert.equal(body.movements[0].quantity, 120);
  });

  it('keeps every line item of one seat when the document carries several products', async () => {
    // A purchase document is one `ENT_LAN` with one line per product. Reading the seat as one product
    // lost every line but the last, so the mirror stores the seat plus the product.
    const stored = await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        date: '2026-10-31',
        movements: [
          {
            ...seat,
            ent_lan: 35911,
            product_code: '001774',
            product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA',
            quantity: 3606,
            entry_date: '2026-10-31',
            operator_name: 'VIVIANE',
          },
          {
            ...seat,
            ent_lan: 35911,
            product_code: '000084',
            product_desc: 'MASSA PARA PASTEL DISCO 500G',
            quantity: 500,
            entry_date: '2026-10-31',
            operator_name: 'VIVIANE',
          },
        ],
      },
    });
    assert.equal(stored.statusCode, 201);

    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-31'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    const body = JSON.parse(res.body);
    assert.deepEqual(
      body.movements.map((movement: { ent_lan: number; product_code: string; quantity: number }) => ({
        ent_lan: movement.ent_lan,
        product_code: movement.product_code,
        quantity: movement.quantity,
      })),
      [
        { ent_lan: 35911, product_code: '001774', quantity: 3606 },
        { ent_lan: 35911, product_code: '000084', quantity: 500 },
      ],
    );
  });

  it('answers the day split into the lenses each screen counts', async () => {
    // The same snapshot feeds both screens, so the split is decided once, here, instead of being retyped
    // by the masses page and the report with a catalogue each.
    const massas = ['001045', '000039', '000060', '000084', '000091', '001192', '005000', '001047', '000022', '001616'];

    const res = await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        date: '2026-10-31',
        movements: [
          ...massas.map((product_code, index) => ({
            ...seat,
            ent_lan: 36000 + index,
            product_code,
            product_desc: `MASSA ${index}`,
            entry_date: '2026-10-31',
          })),
          {
            ...seat,
            ent_lan: 36011,
            product_code: '001774',
            product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA',
            quantity: 3606,
            entry_date: '2026-10-31',
            operator_name: 'VIVIANE',
          },
        ],
      },
    });

    assert.equal(res.statusCode, 201);
    assert.deepEqual(JSON.parse(res.body), { saved: 11, massas: 10, insumos: 1 });
  });

  it('leaves another day untouched when one day is stamped again', async () => {
    await app.inject({
      method: 'POST',
      url: url(),
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        date: '2026-10-29',
        movements: [{ ...seat, ent_lan: 10600, entry_date: '2026-10-29' }],
      },
    });

    const other = await app.inject({
      method: 'GET',
      url: url('2026-10-29'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.equal(JSON.parse(other.body).movements.length, 1);
    assert.equal(JSON.parse(other.body).movements[0].ent_lan, 10600);
  });

  it('refuses a malformed snapshot and writes nothing', async () => {
    const before = await app.inject({
      method: 'GET',
      url: url('2026-10-30'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.deepEqual(JSON.parse(before.body).movements, []);

    const cases: Array<[string, Record<string, unknown>]> = [
      ['ent_lan not an integer', { date: '2026-10-30', movements: [{ ...seat, ent_lan: 'AB-12' }] }],
      ['quantity not a number', { date: '2026-10-30', movements: [{ ...seat, quantity: 'noventa' }] }],
      ['entry_date in the wrong format', { date: '2026-10-30', movements: [{ ...seat, entry_date: '28/10/2026' }] }],
      ['movement_type outside E/S', { date: '2026-10-30', movements: [{ ...seat, movement_type: 'X' }] }],
      ['an empty operator is not an operator', { date: '2026-10-30', movements: [{ ...seat, operator_name: '   ' }] }],
      ['the snapshot carries no movements array', { date: '2026-10-30', movements: 'none' }],
      ['an unknown field is smuggled in', { date: '2026-10-30', movements: [{ ...seat, secret: 'x' }] }],
    ];

    for (const [name, payload] of cases) {
      const res = await app.inject({
        method: 'POST',
        url: url(),
        headers: { authorization: `Bearer ${workerToken}` },
        payload,
      });
      assert.equal(res.statusCode, 400, name);
      assert.equal(JSON.parse(res.body).code, 'INVALID_MOVEMENTS', name);
    }

    const after = await app.inject({
      method: 'GET',
      url: url('2026-10-30'),
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.deepEqual(JSON.parse(after.body).movements, [], 'a refused snapshot must write nothing');
  });

  it('answers 503 with a named code when the database has not been migrated', async () => {
    db.exec('ALTER TABLE aero_movements RENAME TO aero_movements_missing');

    try {
      const read = await app.inject({
        method: 'GET',
        url: url('2026-10-28'),
        headers: { authorization: `Bearer ${workerToken}` },
      });
      assert.equal(read.statusCode, 503);
      assert.equal(JSON.parse(read.body).code, 'AERO_MOVEMENTS_NOT_MIGRATED');

      const write = await app.inject({
        method: 'POST',
        url: url(),
        headers: { authorization: `Bearer ${workerToken}` },
        payload: { date: '2026-10-28', movements: [seat] },
      });
      assert.equal(write.statusCode, 503);
      assert.equal(JSON.parse(write.body).code, 'AERO_MOVEMENTS_NOT_MIGRATED');
    } finally {
      db.exec('ALTER TABLE aero_movements_missing RENAME TO aero_movements');
    }
  });

  it('reads the snapshot for whoever is signed in, because the plant reads the same mirror', async () => {
    const res = await app.inject({
      method: 'GET',
      url: url('2026-10-28'),
      headers: { authorization: `Bearer ${wilkinToken}` },
    });
    assert.equal(res.statusCode, 200);
    assert.ok(JSON.parse(res.body).movements.length >= 1);
  });
});
