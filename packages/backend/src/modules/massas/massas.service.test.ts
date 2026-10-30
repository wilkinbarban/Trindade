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
  massasLaunchHistory,
  updateMassaLaunch,
} from './massas.service.js';
import { getSaoPauloDateString } from '../../utils/date.js';

describe('massas launch service', () => {
  let db: Database.Database;
  // The only day the service still accepts a new launch: the past is a record, and the freeze now covers
  // the birth of a row as well as its later life.
  const testDate = getSaoPauloDateString();
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


  /**
   * A launch written straight into the table.
   *
   * The service itself refuses to create a row on a past day, which is the rule under test here, so a test
   * about reading or freezing yesterday has to plant its fixture the way the day was really recorded: a
   * direct row, not a call through the door the rule closes.
   */
  function insertPastLaunch(date: string, userId: number, productCode = '1045', boxes = 4): number {
    const result = db
      .prepare(
        `INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(date, productCode, boxes, userId, `${date}T10:00:00.000Z`, `${date}T10:00:00.000Z`);
    return Number(result.lastInsertRowid);
  }

  it('keeps the seat an imported launch came from and attributes it to that seat\'s operator', () => {
    // The seat says VICTOR; the request arrives signed by whoever pressed Importar. The launch has to carry
    // both facts, or the day shows VICTOR's boxes under the importer's name and never reconciles.
    const [created] = createMassaLaunches(db, {
      date: testDate,
      userId: userBobId,
      operatorName: 'ALICE SILVA',
      items: [{ product_code: '5000', boxes: 27, ent_lan: 35937 }],
    });

    assert.equal(created.ent_lan, 35937);
    assert.equal(created.user_id, userAliceId);
    assert.equal(created.username, 'alice');

    // The day view is the reading the screens build on, so it has to answer the seat as well.
    const view = massasLaunchDayView(db, testDate);
    assert.equal(view.launches[0].ent_lan, 35937);
  });

  it('keeps the seat even when the operator name matches nobody', () => {
    // S2: no resolution means the authenticated user, but the seat is a fact about the boxes, not about
    // the person, so it survives.
    const [created] = createMassaLaunches(db, {
      date: testDate,
      userId: userBobId,
      operatorName: 'MARIA DA SILVA',
      items: [{ product_code: '22', boxes: 3, ent_lan: 35939 }],
    });

    assert.equal(created.ent_lan, 35939);
    assert.equal(created.user_id, userBobId);
  });

  it('refuses a second launch for a seat already here instead of duplicating it', () => {
    createMassaLaunches(db, {
      date: testDate,
      userId: userAliceId,
      items: [{ product_code: '5000', boxes: 27, ent_lan: 35937 }],
    });

    assert.throws(
      () =>
        createMassaLaunches(db, {
          date: testDate,
          userId: userAliceId,
          items: [{ product_code: '5000', boxes: 27, ent_lan: 35937 }],
        }),
      (error: unknown) => (error as { code?: string }).code === 'ALREADY_IMPORTED',
    );

    // The refusal is total: the day still holds exactly the first launch.
    assert.equal(massasLaunchDayView(db, testDate).launches.length, 1);
  });

  it('refuses to create a launch on a past day, administrator included', () => {
    // S8: the history admits no new rows either. A launch recorded for yesterday after midnight is a number
    // nobody typed on the day it claims, and the audit the company keeps is exactly that claim.
    const yesterday = getSaoPauloDateString(new Date(Date.now() - 86_400_000));

    // The service takes no role: the freeze is a property of the day, so the administrator's id is refused
    // by the very same line that refuses the worker's.
    for (const userId of [userAliceId, userBobId]) {
      assert.throws(
        () =>
          createMassaLaunches(db, {
            date: yesterday,
            userId,
            items: [{ product_code: '1045', boxes: 2 }],
          }),
        (err: Error & { code?: string }) => err.code === 'HISTORY_FROZEN',
      );
    }

    assert.deepEqual(massasLaunchDayView(db, yesterday).launches, [], 'the past day gained nothing');
    assert.deepEqual(massasLaunchHistory(db), [], 'the history is empty because nobody recorded anything');
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
    // Today is the only day a screen may still change: what is done is done, so the update tests run on it.
    const today = getSaoPauloDateString();
    const [launch] = createMassaLaunches(db, {
      date: today,
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

    const view = massasLaunchDayView(db, today);
    assert.equal(view.totalBoxes, 10);
    assert.equal(view.totalUnits, 100);
  });

  it('refuses to rewrite a launch from a past day, administrator included', () => {
    // The history answers "who launched this, and when". An edit after midnight turns yesterday's book into
    // something nobody recorded, and a role that can do it is a role that can erase the plant's trail.
    const pastDate = getSaoPauloDateString(new Date(Date.now() - 86_400_000));
    const launchId = insertPastLaunch(pastDate, userAliceId);

    for (const userRole of ['Administrador', undefined]) {
      assert.throws(
        () => updateMassaLaunch(db, { id: launchId, userId: userAliceId, boxes: 10, userRole }),
        (err: Error & { code?: string }) => err.code === 'HISTORY_FROZEN',
      );
    }

    const view = massasLaunchDayView(db, pastDate);
    assert.equal(view.totalBoxes, 4, 'the frozen day keeps exactly the boxes the plant recorded');
  });

  it('refuses to delete a launch from a past day, administrator included', () => {
    const pastDate = getSaoPauloDateString(new Date(Date.now() - 86_400_000));
    const launchId = insertPastLaunch(pastDate, userAliceId);

    for (const userRole of ['Administrador', undefined]) {
      assert.throws(
        () => deleteMassaLaunch(db, { id: launchId, userId: userAliceId, userRole }),
        (err: Error & { code?: string }) => err.code === 'HISTORY_FROZEN',
      );
    }

    assert.equal(massasLaunchDayView(db, pastDate).launches.length, 1, 'the frozen day still has its launch');
  });

  it('enforces RBAC on update: refuses when another user attempts to update', () => {
    // RBAC is only reachable on a day that can still change: a past day is frozen before roles are weighed.
    const today = getSaoPauloDateString();
    const [launch] = createMassaLaunches(db, {
      date: today,
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

    // Bob creates a launch, Alice (admin) edits it successfully
    const [bobLaunch] = createMassaLaunches(db, {
      date: today,
      userId: userBobId,
      items: [{ product_code: '1045', boxes: 2 }],
    });

    const adminUpdated = updateMassaLaunch(db, {
      id: bobLaunch.id,
      userId: userAliceId,
      userRole: 'Administrador',
      boxes: 6,
    });
    assert.equal(adminUpdated.boxes, 6);
  });

  it('allows owner to delete launch and enforces RBAC for other users', () => {
    const today = getSaoPauloDateString();
    const [launch] = createMassaLaunches(db, {
      date: today,
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

    // Bob creates a launch, Alice (admin) deletes it successfully
    const [bobLaunch] = createMassaLaunches(db, {
      date: today,
      userId: userBobId,
      items: [{ product_code: '1045', boxes: 2 }],
    });

    assert.doesNotThrow(() => {
      deleteMassaLaunch(db, {
        id: bobLaunch.id,
        userId: userAliceId,
        userRole: 'Administrador',
      });
    });

    // Alice deletes her own launch
    assert.doesNotThrow(() => {
      deleteMassaLaunch(db, {
        id: launch.id,
        userId: userAliceId,
      });
    });

    const view = massasLaunchDayView(db, today);
    assert.equal(view.launches.length, 0);
    assert.equal(view.totalBoxes, 0);
  });

  it('aggregates history by date with product count, boxes, units, and operators count', () => {
    // Empty history
    assert.deepEqual(massasLaunchHistory(db), []);

    const today = getSaoPauloDateString();
    const yesterday = getSaoPauloDateString(new Date(Date.now() - 86_400_000));

    // Yesterday: a row written on the day, planted directly because the service no longer creates past days.
    insertPastLaunch(yesterday, userAliceId, '1045', 2);

    // Today: Alice launches 1045 (10 un/cx) 3 boxes (30 units) and 84 (20 un/cx) 2 boxes (40 units)
    createMassaLaunches(db, {
      date: today,
      userId: userAliceId,
      items: [
        { product_code: '1045', boxes: 3 },
        { product_code: '84', boxes: 2 },
      ],
    });
    // Bob launches 84 (20 un/cx) 1 box (20 units)
    createMassaLaunches(db, {
      date: today,
      userId: userBobId,
      items: [{ product_code: '84', boxes: 1 }],
    });

    const history = massasLaunchHistory(db);
    assert.equal(history.length, 2);

    // Ordered DESC: today first
    assert.equal(history[0].date, today);
    assert.equal(history[0].productsCount, 2); // 1045 and 84
    assert.equal(history[0].totalBoxes, 6); // 3 + 2 + 1
    assert.equal(history[0].totalUnits, 90); // 30 + 40 + 20
    assert.equal(history[0].operatorsCount, 2); // Alice and Bob

    // Yesterday second
    assert.equal(history[1].date, yesterday);
    assert.equal(history[1].productsCount, 1);
    assert.equal(history[1].totalBoxes, 2);
    assert.equal(history[1].totalUnits, 20);
    assert.equal(history[1].operatorsCount, 1);
  });
});

