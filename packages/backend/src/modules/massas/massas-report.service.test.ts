import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { massasReportDayView } from './massas-report.service.js';

/**
 * The day the plant actually produced is not one table: Trindade records what the operator launched here,
 * and the Delphi tablet records what somebody loaded straight into the company ERP. The report has to add
 * both without counting the same boxes twice, which is the whole reason the reconciliation exists.
 */
describe('massas report — the day the plant produced', () => {
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

  it('counts a reconciled seat once and adds the tablet production to its operator', () => {
    // 4 caixas de CASEIRA (10 un cada uma) lançadas em Trindade e reconhecidas pelo ERP; mais 3 caixas de
    // ROLO que só existem na tablet da empresa.
    insertLaunch(aliceId, '1045', 4);
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1047', quantity: 18, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 58, 'the reconciled 40 are counted once, not twice');
    const alice = view.operators.find((operator) => operator.name === 'Alice Silva');
    assert.ok(alice, 'Trindade keeps the operator display name');
    assert.equal(alice.units, 40);
    assert.equal(alice.erpOnly, false);
    const tiago = view.operators.find((operator) => operator.name === 'TIAGO');
    assert.ok(tiago, 'an operator that only exists in the ERP still appears');
    assert.equal(tiago.units, 18);
    assert.equal(tiago.erpOnly, true);
    assert.deepEqual(view.reconciledEntLans, [9001]);
  });

  it('credits an imported launch to the operator the seat names, not to whoever imported it', () => {
    // The mirror belonged to JOAO H, who has no Trindade account, and the import left the launch under
    // Alice: the report has to answer the plant with the person who actually produced the boxes.
    insertLaunch(aliceId, '1047', 2);
    db.prepare('UPDATE massa_launches SET ent_lan = 35936 WHERE launch_date = ?').run(date);
    insertSeat({ entLan: 35936, code: '1047', quantity: 12, operator: 'JOAO H' });

    const view = massasReportDayView(db, date);

    const joao = view.operators.find((operator) => operator.name === 'JOAO H');
    assert.ok(joao, 'the seat names the operator and the report carries it');
    assert.equal(joao.units, 12);
    assert.equal(joao.erpOnly, true, 'no local account, so the report marks it as ERP only');
    const alice = view.operators.find((operator) => operator.name === 'Alice Silva');
    assert.equal(alice, undefined, 'the importer does not keep production that was never his');
    assert.equal(view.totalUnits, 12);
  });

  it('keeps the local name when the seat operator does have an account', () => {
    insertLaunch(aliceId, '1047', 2);
    db.prepare('UPDATE massa_launches SET ent_lan = 35937 WHERE launch_date = ?').run(date);
    insertSeat({ entLan: 35937, code: '1047', quantity: 12, operator: 'ALICE SILVA' });

    const view = massasReportDayView(db, date);

    assert.equal(view.operators.length, 1);
    assert.equal(view.operators[0].name, 'Alice Silva');
    assert.equal(view.operators[0].erpOnly, false);
    assert.equal(view.operators[0].units, 12);
  });

  it('compares the reconciled production against what the ERP recorded', () => {
    insertLaunch(aliceId, '1045', 4);
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1047', quantity: 18, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.balance.producedUnits, 58);
    assert.equal(view.balance.registeredUnits, 58);
    assert.equal(view.balance.differenceUnits, 0, 'zero means the day is fully reconciled');
  });

  it('warns with a negative difference when Trindade produced what the ERP never saw', () => {
    insertLaunch(aliceId, '1045', 4);
    insertLaunch(aliceId, '1047', 2);

    const view = massasReportDayView(db, date);

    assert.equal(view.balance.producedUnits, 52);
    assert.equal(view.balance.registeredUnits, 0);
    assert.equal(view.balance.differenceUnits, -52);
  });

  it('associates an ERP operator with a Trindade user ignoring case', () => {
    // Nome igual ao display_name, quantidade que não reconcilia: tem que somar no mesmo operário, não num
    // chip novo com o nome em maiúsculas.
    insertSeat({ entLan: 9003, code: '1047', quantity: 6, operator: 'alice silva' });

    const view = massasReportDayView(db, date);

    assert.equal(view.operators.length, 1);
    assert.equal(view.operators[0].name, 'Alice Silva');
    assert.equal(view.operators[0].erpOnly, false);
    assert.equal(view.operators[0].units, 6);
  });

  it('never counts a stock exit as production', () => {
    insertSeat({ entLan: 9004, code: '1045', quantity: 100, operator: 'TIAGO', type: 'S' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.equal(view.balance.registeredUnits, 0);
    assert.deepEqual(view.operators, []);
  });

  it('ignores ERP seats outside the massas catalogue', () => {
    insertSeat({ entLan: 9005, code: 'INSUMO-FARINHA', quantity: 500, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 0);
    assert.equal(view.balance.registeredUnits, 0);
  });

  it('answers the launches alone when the mirror was never migrated', () => {
    insertLaunch(aliceId, '1045', 4);
    db.exec('DROP TABLE aero_movements');

    const view = massasReportDayView(db, date);

    assert.equal(view.totalUnits, 40);
    assert.equal(view.balance.registeredUnits, 0);
    assert.equal(view.balance.differenceUnits, -40);
  });

  it('keeps the product totals the collective summary already reads', () => {
    insertLaunch(aliceId, '1045', 4);
    insertSeat({ entLan: 9001, code: '1045', quantity: 40, operator: 'ALICE SILVA' });
    insertSeat({ entLan: 9002, code: '1045', quantity: 30, operator: 'TIAGO' });

    const view = massasReportDayView(db, date);

    const caseira = view.products.find((product) => product.code === '1045');
    assert.ok(caseira);
    assert.equal(caseira.totalBoxes, 7);
    assert.equal(caseira.totalUnits, 70);
  });
});

