import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { massasBalance, massasUnifiedRows, type MassasUnifiedInput } from './massas-unified';

/**
 * The bar at the top of the day answers one question with two numbers: what the plant made, and what the
 * company already knows. Anything counted twice would make the bar lie in the optimistic direction, so the
 * cases below are the two the plant actually argues about.
 */
const PRODUCT = { id: 1, code: '1001', name: 'Massa de Pastel', unitsPerBox: 10 };

function day(overrides: Partial<MassasUnifiedInput>): MassasUnifiedInput {
  return { launches: [], movements: [], products: [PRODUCT], ...overrides };
}

function launch(id: number, units: number, boxes: number) {
  return {
    id,
    launch_date: '2026-05-20',
    user_id: 7,
    username: 'wilkin',
    display_name: 'Wilkin',
    product_code: '1001',
    product_name: 'Massa de Pastel',
    boxes,
    units,
    created_at: '2026-05-20T08:00:00.000Z',
    updated_at: '2026-05-20T08:00:00.000Z',
  };
}

function seat(entLan: number, quantity: number, operator: string) {
  return {
    entLan,
    entryDate: '2026-05-20',
    movementType: 'E' as const,
    productCode: '1001',
    productDesc: 'Massa de Pastel',
    quantity,
    operatorName: operator,
    observation: '',
  };
}

describe('massas balance', () => {
  it('counts a reconciled launch once and adds the seat only the tablet saw', () => {
    const rows = massasUnifiedRows(
      day({ launches: [launch(1, 100, 10)], movements: [seat(900, 100, 'Wilkin'), seat(901, 50, 'Wilkin')] }),
    );

    // 100 reconciled + 50 that exists only on the tablet: the plant made 150 and the company has 150.
    assert.deepEqual(massasBalance(rows), { produced: 150, registered: 150, difference: 0 });
  });

  it('reports a negative difference while a launch has not reached the company', () => {
    const rows = massasUnifiedRows(day({ launches: [launch(1, 100, 10)] }));

    // Produced but not registered: the bar says -100 instead of showing an empty day.
    assert.deepEqual(massasBalance(rows), { produced: 100, registered: 0, difference: -100 });
  });

  it('adds one pending launch to the produced side without inflating the registered side', () => {
    const rows = massasUnifiedRows(
      day({ launches: [launch(1, 100, 10), launch(2, 40, 4)], movements: [seat(900, 100, 'Wilkin')] }),
    );

    assert.deepEqual(massasBalance(rows), { produced: 140, registered: 100, difference: -40 });
  });

  it('answers zeroes for a day nobody counted', () => {
    assert.deepEqual(massasBalance([]), { produced: 0, registered: 0, difference: 0 });
  });
});
