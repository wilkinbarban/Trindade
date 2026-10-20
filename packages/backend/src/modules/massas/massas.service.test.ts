import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { MASSAS } from '../stock/stock.catalog.js';
import {
  createMassaLaunches,
  deleteMassaLaunch,
  massasLaunchDayView,
  updateMassaLaunch,
} from './massas.service.js';

describe('massas launch service', () => {
  let db: Database.Database;
  const testDate = '2026-10-20';
  let userAliceId: number;
  let userBobId: number;

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));

    const roleAdmin = db.prepare("SELECT id FROM roles WHERE name = 'Administrador'").pluck().get() as number;
    const roleWorker = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;

    const resAlice = db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
      .run('alice', 'hash', 'Alice Silva', roleAdmin);
    userAliceId = Number(resAlice.lastInsertRowid);

    const resBob = db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
      .run('bob', 'hash', 'Bob Santos', roleWorker);
    userBobId = Number(resBob.lastInsertRowid);
  });

  it('answers all 10 massas products at zero when day has no launches', () => {
    const view = massasLaunchDayView(db, testDate);
    assert.equal(view.date, testDate);
    assert.equal(view.products.length, 10);
    assert.equal(view.totalBoxes, 0);
    assert.equal(view.totalUnits, 0);
    assert.deepEqual(view.launches, []);

    // Check order and codes match MASSAS catalog
    for (let i = 0; i < MASSAS.length; i++) {
      assert.equal(view.products[i].code, MASSAS[i].code);
      assert.equal(view.products[i].name, MASSAS[i].name);
      assert.equal(view.products[i].unitsPerBox, MASSAS[i].unitsPerBox);
      assert.equal(view.products[i].totalBoxes, 0);
      assert.equal(view.products[i].totalUnits, 0);
    }
  });

  it('records launches, calculates units, and aggregates collective totals', () => {
    // Alice launches MASSA CASEIRA (code 1045, 10 un/cx) 5 boxes
    const createdAlice = createMassaLaunches(db, {
      date: testDate,
      userId: userAliceId,
      items: [{ product_code: '1045', boxes: 5 }],
    });
    assert.equal(createdAlice.length, 1);
    assert.equal(createdAlice[0].product_name, 'MASSA CASEIRA');
    assert.equal(createdAlice[0].boxes, 5);
    assert.equal(createdAlice[0].units, 50);
    assert.equal(createdAlice[0].username, 'alice');

    // Bob launches MASSA CASEIRA 3 boxes and MASSA DISCO 500G (code 84, 20 un/cx) 2 boxes
    const createdBob = createMassaLaunches(db, {
      date: testDate,
      userId: userBobId,
      items: [
        { product_code: '1045', boxes: 3 },
        { product_code: '84', boxes: 2 },
      ],
    });
    assert.equal(createdBob.length, 2);

    const view = massasLaunchDayView(db, testDate);
    assert.equal(view.launches.length, 3);

    // MASSA CASEIRA: 5 + 3 = 8 boxes * 10 = 80 units
    const caseira = view.products.find((p) => p.code === '1045');
    assert.ok(caseira);
    assert.equal(caseira.totalBoxes, 8);
    assert.equal(caseira.totalUnits, 80);

    // MASSA DISCO 500G: 2 boxes * 20 = 40 units
    const disco500 = view.products.find((p) => p.code === '84');
    assert.ok(disco500);
    assert.equal(disco500.totalBoxes, 2);
    assert.equal(disco500.totalUnits, 40);

    assert.equal(view.totalBoxes, 10);
    assert.equal(view.totalUnits, 120);
  });

  it('throws error when creating launch with unknown product code or invalid boxes', () => {
    assert.throws(
      () =>
        createMassaLaunches(db, {
          date: testDate,
          userId: userAliceId,
          items: [{ product_code: '999999', boxes: 1 }],
        }),
      /Unknown massa product code/
    );

    assert.throws(
      () =>
        createMassaLaunches(db, {
          date: testDate,
          userId: userAliceId,
          items: [{ product_code: '1045', boxes: 0 }],
        }),
      /Boxes must be a positive integer/
    );
  });

  it('allows owner to update boxes and recomputes units', () => {
    const [launch] = createMassaLaunches(db, {
      date: testDate,
      userId: userAliceId,
      items: [{ product_code: '1045', boxes: 4 }],
    });

    const updated = updateMassaLaunch(db, {
      id: launch.id,
      userId: userAliceId,
      boxes: 10,
    });
    assert.equal(updated.boxes, 10);
    assert.equal(updated.units, 100);

    const view = massasLaunchDayView(db, testDate);
    assert.equal(view.totalBoxes, 10);
    assert.equal(view.totalUnits, 100);
  });

  it('enforces RBAC on update: refuses when another user attempts to update', () => {
    const [launch] = createMassaLaunches(db, {
      date: testDate,
      userId: userAliceId,
      items: [{ product_code: '1045', boxes: 4 }],
    });

    assert.throws(
      () =>
        updateMassaLaunch(db, {
          id: launch.id,
          userId: userBobId,
          boxes: 10,
        }),
      (err: unknown) => (err as { code?: string }).code === 'FORBIDDEN'
    );
  });

  it('allows owner to delete launch and enforces RBAC for other users', () => {
    const [launch] = createMassaLaunches(db, {
      date: testDate,
      userId: userAliceId,
      items: [{ product_code: '1045', boxes: 4 }],
    });

    // Bob tries to delete Alice's launch
    assert.throws(
      () =>
        deleteMassaLaunch(db, {
          id: launch.id,
          userId: userBobId,
        }),
      (err: unknown) => (err as { code?: string }).code === 'FORBIDDEN'
    );

    // Alice deletes her own launch
    assert.doesNotThrow(() => {
      deleteMassaLaunch(db, {
        id: launch.id,
        userId: userAliceId,
      });
    });

    const view = massasLaunchDayView(db, testDate);
    assert.equal(view.launches.length, 0);
    assert.equal(view.totalBoxes, 0);
  });
});
