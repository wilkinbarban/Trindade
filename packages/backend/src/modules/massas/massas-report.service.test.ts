import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { massasReportDayView } from './massas-report.service.js';

/**
 * The report answers one question: what does the company have for the day?
 *
 * Its only source is the ERP mirror, so a launch typed here but never sent to the company does not enter it,
 * and a seat does not have to reconcile with anything to be counted.
 */
describe('massas report — the day the company recorded', () => {
  let db: Database.Database;
  let aliceId: number;
  const date = '2026-06-12';

  function insertLaunch(userId: number, code: string, boxes: number): number {
    const result = db
      .prepare(
        `INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, '2026-06-12 09:00:00', '2026-06-12 09:00:00')`,
      )
      .run(date, code, boxes, userId);
    return Number(result.lastInsertRowid);
  }

  function insertSeat(args: {
    entLan: number;
    code: string;
    quantity: number;
    operator: string;
    type?: 'E' | 'S';
  }): void {
    db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2026-06-12T12:00:00.000Z')`,
    ).run(
      date,
      args.entLan,
      args.code,
      `produto ${args.code}`,
      args.quantity,
      date,
      args.operator,
      args.type ?? 'E',
    );
  }

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));

    const roleWorker = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
    const alice = db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
      .run('alice', 'hash', 'Alice Silva', roleWorker);
    aliceId = Number(alice.lastInsertRowid);
  });

  it('leaves out a launch the ERP never saw', () => {
    // The 280 boxes Jaime typed here and never sent to the company are not part of what the company has.
    insertLaunch(aliceId, '1047', 280);

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.deepEqual(view.operators, []);
  });

  it('counts every massas entry the ERP recorded, credited to the operator it names', () => {
    insertSeat({ entLan: 35940, code: '1045', quantity: 3000, operator: 'JONAS' });
    insertSeat({ entLan: 35941, code: '1047', quantity: 1200, operator: 'VIVIANE' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 4200);
    const jonas = view.operators.find((operator) => operator.name === 'JONAS');
    assert.ok(jonas);
    assert.equal(jonas.units, 3000);
    assert.equal(jonas.erpOnly, true);
    assert.deepEqual(jonas.entLans, [35940]);
    const viviane = view.operators.find((operator) => operator.name === 'VIVIANE');
    assert.ok(viviane);
    assert.equal(viviane.units, 1200);
    assert.equal(view.operators[0].name, 'JONAS', 'the busiest operator leads the breakdown');
  });

  it('leaves the supplies out, so the two blocks never claim the same seat', () => {
    // What Jonas and Viviane actually launched that day: brotinho, pizza, canudo and flour, none of them a
    // massa the plant makes. The report's massa block must not carry them.
    insertSeat({ entLan: 35942, code: '001800', quantity: 6940, operator: 'JONAS' });
    insertSeat({ entLan: 35943, code: '001591', quantity: 9053, operator: 'VIVIANE' });
    insertSeat({ entLan: 35944, code: '000138', quantity: 300, operator: 'VIVIANE' });
    insertSeat({ entLan: 35945, code: '000787', quantity: 120, operator: 'JONAS' });
    insertSeat({ entLan: 35946, code: '001040', quantity: 40, operator: 'JONAS' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.deepEqual(view.operators, []);
  });

  it('counts a massas code the ERP pads with zeros', () => {
    // The ERP writes `000084`; the catalogue writes `84`. The zeros are padding, not identity.
    insertSeat({ entLan: 9006, code: '000084', quantity: 100, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 100);
    const disco = view.products.find((product) => product.code === '84');
    assert.ok(disco);
    assert.equal(disco.totalUnits, 100);
    assert.equal(disco.totalBoxes, 5);
  });

  it('keeps the local display name when the ERP operator does have an account', () => {
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });

    const view = massasReportDayView(db, date);

    assert.equal(view.operators.length, 1);
    assert.equal(view.operators[0].name, 'Alice Silva');
    assert.equal(view.operators[0].username, 'alice');
    assert.equal(view.operators[0].erpOnly, false);
    assert.equal(view.operators[0].units, 40);
  });

  it('associates an ERP operator with a Trindade user ignoring case', () => {
    insertSeat({ entLan: 9003, code: '1047', quantity: 6, operator: 'alice silva' });

    const view = massasReportDayView(db, date);

    assert.equal(view.operators.length, 1);
    assert.equal(view.operators[0].name, 'Alice Silva');
    assert.equal(view.operators[0].erpOnly, false);
    assert.equal(view.operators[0].units, 6);
  });

  it('answers both operator shapes, so neither client has to guess', () => {
    // The phone reads `displayName`/`onlyErp`/`entLans` from the generated contract; the web reads `name` and
    // `erpOnly`. Both are the same fact, and sending one alone is what left the card empty.
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1047', quantity: 18, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    const alice = view.operators.find((operator) => operator.displayName === 'Alice Silva');
    assert.ok(alice, 'the local operator answers to both names');
    assert.equal(alice.name, 'Alice Silva');
    assert.equal(alice.operatorKey, 'ALICE SILVA');
    assert.equal(alice.onlyErp, false);
    assert.deepEqual(alice.entLans, [9001]);

    const tiago = view.operators.find((operator) => operator.displayName === 'TIAGO');
    assert.ok(tiago, 'the ERP-only operator answers to both names too');
    assert.equal(tiago.name, 'TIAGO');
    assert.equal(tiago.onlyErp, true);
    assert.deepEqual(tiago.entLans, [9002]);
  });

  it('never counts a stock exit as production', () => {
    insertSeat({ entLan: 9004, code: '1045', quantity: 100, operator: 'TIAGO', type: 'S' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.deepEqual(view.operators, []);
  });

  it('ignores ERP seats outside the massas catalogue', () => {
    insertSeat({ entLan: 9005, code: 'INSUMO-FARINHA', quantity: 500, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.equal(view.balance.registeredUnits, 0);
  });

  it('compares the day against itself, without inventing a difference', () => {
    insertLaunch(aliceId, '1045', 4);
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1047', quantity: 18, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.balance.producedUnits, 58);
    assert.equal(view.balance.registeredUnits, 58);
    assert.equal(view.balance.differenceUnits, 0);
    assert.equal(view.balance.reconciledSeats, 0);
    assert.equal(view.balance.pendingSeats, 0);
    assert.deepEqual(view.reconciledEntLans, []);
  });

  it('answers an empty day when the mirror was never migrated', () => {
    insertLaunch(aliceId, '1045', 4);
    db.exec('DROP TABLE aero_movements');

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.deepEqual(view.operators, []);
  });

  it('keeps the product totals the collective summary already reads', () => {
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1045', quantity: 30, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    const caseira = view.products.find((product) => product.code === '1045');
    assert.ok(caseira);
    assert.equal(caseira.totalBoxes, 7);
    assert.equal(caseira.totalUnits, 70);
  });
});
