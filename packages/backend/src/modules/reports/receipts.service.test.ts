import assert from 'node:assert/strict';
import { after, describe, it } from 'node:test';
import type Database from 'better-sqlite3';
import { closeReportDatabases, setupReportsDb } from './reports-test-helper.js';
import { dayReceipts, supplyReceipts } from './receipts.service.js';
import type { AeroMovement } from '../integration/aero.movements.service.js';

/**
 * The report's Recebimento section is a read of what the ERP already answered, not a second place where
 * those movements are stored. These tests hold the classification rules: an entry that is not a massa the
 * plant produces is an incoming supply, and a document may carry several lines.
 */

function movement(overrides: Partial<AeroMovement>): AeroMovement {
  return {
    ent_lan: 35907,
    product_code: '000084',
    product_desc: 'MASSA PARA PASTEL DISCO 500G',
    quantity: 500,
    entry_date: '2026-10-29',
    operator_name: 'VINICIUS R',
    movement_type: 'E',
    synced_at: '2026-10-29T18:00:00.000Z',
    ...overrides,
  };
}

function seed(db: Database.Database, rows: readonly AeroMovement[]): void {
  const insert = db.prepare(
    `INSERT INTO aero_movements
       (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
     VALUES ('2026-10-29', @ent_lan, @product_code, @product_desc, @quantity, @entry_date, @operator_name, @movement_type, @synced_at)`,
  );
  for (const row of rows) insert.run(row);
}

describe('supplyReceipts', () => {
  it('keeps an entry that is not a massa the plant produces', () => {
    const receipts = supplyReceipts([
      movement({
        ent_lan: 35911,
        product_code: '001774',
        product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA',
        quantity: 3606,
        operator_name: 'VIVIANE',
      }),
    ]);

    assert.deepEqual(receipts, [
      {
        entLan: 35911,
        productCode: '001774',
        productDesc: 'CAIXA DE PAPELAO PEQUENA MISTA',
        quantity: 3606,
        operatorName: 'VIVIANE',
        entryDate: '2026-10-29',
      },
    ]);
  });

  it('drops a massa even when the ERP pads its code with zeros', () => {
    assert.deepEqual(
      supplyReceipts([
        movement({ product_code: '000084' }),
        movement({ product_code: '84', ent_lan: 35908 }),
        movement({ product_code: '000022', ent_lan: 35909 }),
        movement({ product_code: '22', ent_lan: 35910 }),
      ]),
      [],
    );
  });

  it('keeps a third-party product, because the plant receives it too', () => {
    const receipts = supplyReceipts([
      movement({ ent_lan: 35920, product_code: '001667', product_desc: 'BATATA EXTRA FINA', quantity: 200 }),
      movement({ ent_lan: 35921, product_code: '000787', product_desc: 'CANUDO', quantity: 3000 }),
    ]);

    assert.deepEqual(
      receipts.map((receipt) => receipt.productDesc),
      ['BATATA EXTRA FINA', 'CANUDO'],
    );
  });

  it('drops a stock exit: a baja is not a receipt', () => {
    assert.deepEqual(
      supplyReceipts([
        movement({ movement_type: 'S', product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA' }),
      ]),
      [],
    );
  });

  it('keeps every line of one document, in seat order', () => {
    const receipts = supplyReceipts([
      movement({ ent_lan: 35911, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 3606 }),
      movement({ ent_lan: 35911, product_code: '002108', product_desc: 'QUEIJO RALADO', quantity: 40 }),
      movement({ ent_lan: 35910, product_code: '000022', product_desc: 'MASSA PARA PASTEL ROLO 500G', quantity: 4 }),
      movement({ ent_lan: 35912, product_code: '001436', product_desc: 'NHOQUE 400g', quantity: 84 }),
    ]);

    assert.deepEqual(
      receipts.map((receipt) => [receipt.entLan, receipt.productCode]),
      [
        [35911, '001774'],
        [35911, '002108'],
        [35912, '001436'],
      ],
    );
  });

  it('keeps a zero quantity, because the ERP answers what it answered', () => {
    const receipts = supplyReceipts([
      movement({ ent_lan: 35930, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 0 }),
    ]);

    assert.equal(receipts.length, 1);
    assert.equal(receipts[0].quantity, 0);
  });
});

describe('dayReceipts', () => {
  after(() => closeReportDatabases());

  it('answers the day with its snapshot time', () => {
    const db = setupReportsDb();
    seed(db, [
      movement({ ent_lan: 35911, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 3606, operator_name: 'VIVIANE' }),
      movement({ ent_lan: 35912, product_code: '000022', product_desc: 'MASSA PARA PASTEL ROLO 500G', quantity: 15 }),
    ]);

    const day = dayReceipts(db, '2026-10-29');

    assert.equal(day.migrated, true);
    assert.equal(day.syncedAt, '2026-10-29T18:00:00.000Z');
    assert.deepEqual(day.receipts.map((receipt) => receipt.productDesc), ['CAIXA DE PAPELAO PEQUENA MISTA']);
  });

  it('says the day was consulted when the snapshot only carried massas', () => {
    const db = setupReportsDb();
    seed(db, [movement({ ent_lan: 35907 })]);

    const day = dayReceipts(db, '2026-10-29');

    assert.equal(day.migrated, true);
    assert.deepEqual(day.receipts, []);
    assert.equal(day.syncedAt, '2026-10-29T18:00:00.000Z', 'a consulted day with no supplies is not a missing sync');
  });

  it('leaves the day unanswered when no snapshot exists', () => {
    const db = setupReportsDb();

    const day = dayReceipts(db, '2026-10-29');

    assert.equal(day.migrated, true);
    assert.deepEqual(day.receipts, []);
    assert.equal(day.syncedAt, null);
  });

  it('answers migrated:false instead of failing when the mirror table does not exist', () => {
    const db = setupReportsDb();
    db.exec('DROP TABLE aero_movements');

    assert.deepEqual(dayReceipts(db, '2026-10-29'), {
      date: '2026-10-29',
      migrated: false,
      syncedAt: null,
      receipts: [],
    });
  });
});
