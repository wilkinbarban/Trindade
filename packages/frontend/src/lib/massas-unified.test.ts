import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  massasUnifiedRows,
  reconciledLaunchIds,
  type MassasUnifiedInput,
  type MassasUnifiedRow,
} from './massas-unified';
import type { AeroErpMovementItem } from './aero-integration';
import type { MassaIndividualLaunch } from './massas';

/**
 * The unified table, as a table.
 *
 * The browser only draws what this returns, so the states are decided here: what Trindade launched and the
 * ERP already has, what it launched and Delphi has not seen, and what the company tablet recorded with no
 * launch here at all. One box, one row.
 */

const PRODUCTS = [
  { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10 },
  { code: '1047', name: 'MASSA ROLO 2KG', unitsPerBox: 6 },
];

const DATE = '2026-06-12';

function launch(args: Partial<MassaIndividualLaunch> & { id: number }): MassaIndividualLaunch {
  const product = PRODUCTS.find((candidate) => candidate.code === (args.product_code ?? '1045'))!;
  const boxes = args.boxes ?? 2;
  return {
    id: args.id,
    launch_date: DATE,
    product_code: args.product_code ?? '1045',
    product_name: product.name,
    boxes,
    units: args.units ?? boxes * product.unitsPerBox,
    user_id: 1,
    username: args.username ?? 'alice',
    display_name: args.display_name ?? 'Alice',
    ent_lan: args.ent_lan ?? null,
    created_at: `${DATE} 10:00:00`,
    updated_at: `${DATE} 10:00:00`,
  };
}

function seat(args: Partial<AeroErpMovementItem> & { entLan: number }): AeroErpMovementItem {
  return {
    entLan: args.entLan,
    productCode: args.productCode ?? '1045',
    productDesc: 'MASSA',
    quantity: args.quantity ?? 20,
    entryDate: DATE,
    operatorName: args.operatorName ?? 'ALICE',
    movementType: args.movementType ?? 'E',
  };
}

function input(overrides: Partial<MassasUnifiedInput> = {}): MassasUnifiedInput {
  return {
    launches: [],
    movements: [],
    products: PRODUCTS,
    ...overrides,
  };
}

describe('reconciledLaunchIds', () => {
  it('names the launches the ERP already has, and only those', () => {
    // A launch Delphi has seen is settled: editing or deleting it here would leave the plant's day and the
    // company's book disagreeing, with no screen able to say which one is right.
    const rows = massasUnifiedRows(
      input({
        launches: [launch({ id: 1 }), launch({ id: 2, product_code: '1047' })],
        movements: [seat({ entLan: 900 })],
      }),
    );

    assert.deepEqual([...reconciledLaunchIds(rows)].sort(), [1]);
  });

  it('leaves a pending launch manageable, and a day with no rows open', () => {
    const pending = massasUnifiedRows(input({ launches: [launch({ id: 7 })] }));
    assert.deepEqual([...reconciledLaunchIds(pending)], []);

    // The history screen reads past days and has no ERP half at all: nothing is settled there.
    assert.deepEqual([...reconciledLaunchIds(undefined)], []);
    assert.deepEqual([...reconciledLaunchIds([])], []);
  });

  it('settles every launch behind a reconciled row, not just the first', () => {
    // The row is built here: what this test is about is the helper reading the whole list, not how many
    // launches the matcher happens to pair with one seat.
    const rows: MassasUnifiedRow[] = [
      { ...massasUnifiedRows(input({ launches: [launch({ id: 3 })] }))[0], state: 'reconciled', launchIds: [3, 4] },
      { ...massasUnifiedRows(input({ launches: [launch({ id: 5 })] }))[0], state: 'pendingErp', launchIds: [5] },
    ];

    assert.deepEqual([...reconciledLaunchIds(rows)].sort(), [3, 4]);
  });
});

describe('massasUnifiedRows', () => {
  it('shows one reconciled row for a launch the ERP already has, not two', () => {
    const rows = massasUnifiedRows(input({ launches: [launch({ id: 1 })], movements: [seat({ entLan: 900 })] }));

    assert.equal(rows.length, 1);
    assert.equal(rows[0].state, 'reconciled');
    assert.deepEqual(rows[0].launchIds, [1]);
    assert.equal(rows[0].units, 20);
  });

  /**
   * The production defect, as a row: the tablet recorded the seat under VICTOR, the import landed under
   * Wilkin, and the name comparison refused the pair. The seat stayed erpOnly, the Importar button stayed on
   * screen, and every click added another orphan launch to the day.
   */
  it('settles a seat imported by another operator, using the seat stored with the launch', () => {
    const rows = massasUnifiedRows(
      input({
        launches: [
          launch({ id: 90, product_code: '1047', boxes: 3, username: 'wilkin', display_name: 'Wilkin', ent_lan: 35937 }),
        ],
        movements: [seat({ entLan: 35937, productCode: '001047', quantity: 18, operatorName: 'VICTOR' })],
      }),
    );

    assert.equal(rows.length, 1);
    assert.equal(rows[0].state, 'reconciled');
    assert.deepEqual(rows[0].launchIds, [90]);
    // No leftover erpOnly row means no leftover Importar button.
    assert.equal(rows.some((row) => row.state === 'erpOnly'), false);
  });

  it('keeps a launch Delphi has not seen as pending, and remembers the day it was already carried', () => {
    const pending = massasUnifiedRows(input({ launches: [launch({ id: 7 })] }));
    assert.equal(pending.length, 1);
    assert.equal(pending[0].state, 'pendingErp');
    assert.equal(pending[0].tabletAcked, false);

    const acked = massasUnifiedRows(input({ launches: [launch({ id: 7 })], ackedLaunchIds: [7] }));
    assert.equal(acked[0].tabletAcked, true);
    // Acknowledging it on this phone does not pretend Delphi has it.
    assert.equal(acked[0].state, 'pendingErp');
  });

  it('shows a seat nobody launched here as the operator who typed it, importable in whole boxes', () => {
    const rows = massasUnifiedRows(
      input({ movements: [seat({ entLan: 901, productCode: '1047', quantity: 18, operatorName: 'TIAGO' })] }),
    );

    assert.equal(rows.length, 1);
    assert.equal(rows[0].state, 'erpOnly');
    assert.equal(rows[0].operatorName, 'TIAGO');
    assert.equal(rows[0].boxes, 3);
    assert.equal(rows[0].units, 18);
    assert.equal(rows[0].importable, true);
    assert.deepEqual(rows[0].entLans, [901]);
  });

  it('never offers to import a seat that does not fit whole boxes', () => {
    const rows = massasUnifiedRows(input({ movements: [seat({ entLan: 902, quantity: 15 })] }));
    assert.equal(rows[0].state, 'erpOnly');
    assert.equal(rows[0].importable, false);
  });

  it('never turns a stock exit or a supply into production', () => {
    const rows = massasUnifiedRows(
      input({
        movements: [
          seat({ entLan: 903, movementType: 'S' }),
          seat({ entLan: 904, productCode: '0500', productDesc: 'SACO' }),
        ],
      }),
    );
    assert.deepEqual(rows, []);
  });

  it('filters both sides at once by whichever name the operator is known by', () => {
    const day = input({
      launches: [
        launch({ id: 1, display_name: 'Alice', username: 'alice' }),
        launch({ id: 2, display_name: 'Bruno', username: 'bruno' }),
      ],
      movements: [seat({ entLan: 905, operatorName: 'TIAGO' })],
    });

    assert.deepEqual(
      massasUnifiedRows(day, 'alice').map((row) => row.key),
      ['launch-1'],
    );
    assert.deepEqual(
      massasUnifiedRows(day, 'BRUNO').map((row) => row.key),
      ['launch-2'],
    );
    assert.deepEqual(
      massasUnifiedRows(day, 'tiago').map((row) => row.key),
      ['seat-905'],
    );
    assert.equal(massasUnifiedRows(day, '').length, 3);
  });

  it('puts the rows that need an action first', () => {
    const rows = massasUnifiedRows(
      input({
        launches: [
          launch({ id: 1 }),
          // Bruno launched too and Delphi has no seat for him: this one is waiting on the plant.
          launch({ id: 2, display_name: 'Bruno', username: 'bruno', product_code: '1047' }),
        ],
        movements: [seat({ entLan: 900 }), seat({ entLan: 906, operatorName: 'TIAGO' })],
      }),
    );
    assert.deepEqual(
      rows.map((row) => row.state),
      ['pendingErp', 'erpOnly', 'reconciled'],
    );
  });
});
