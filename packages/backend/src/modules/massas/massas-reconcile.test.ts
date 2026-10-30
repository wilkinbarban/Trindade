import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { reconcileMassasDay } from './massas-reconcile.js';

/**
 * The matching rule itself, without a database: the backend needs the same answer the phone computed when
 * it mirrored the day, and the cases below are the table both copies are tested against.
 */
describe('reconcileMassasDay', () => {
  const launch = {
    id: 1,
    launch_date: '2026-06-12',
    product_code: '1045',
    boxes: 4,
    units: 40,
    username: 'alice',
    display_name: 'Alice Silva',
  };

  it('pairs one launch with one seat and consumes the launch', () => {
    const result = reconcileMassasDay(
      [launch, { ...launch, id: 2 }],
      [
        { ent_lan: 9001, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'ALICE SILVA', movement_type: 'E' },
        { ent_lan: 9002, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'ALICE SILVA', movement_type: 'E' },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [9001, 9002]);
    assert.deepEqual(result.matchedLaunchIds, [1, 2]);
    assert.deepEqual(result.unmatchedLaunchIds, []);
  });

  it('does not pair a second identical seat with the same launch', () => {
    const result = reconcileMassasDay(
      [launch],
      [
        { ent_lan: 9001, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'ALICE SILVA', movement_type: 'E' },
        { ent_lan: 9002, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'ALICE SILVA', movement_type: 'E' },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [9001]);
    assert.deepEqual(result.matchedLaunchIds, [1]);
  });

  it('matches codes with different padding and operators with different case', () => {
    const result = reconcileMassasDay(
      [{ ...launch, product_code: '01045', boxes: 4, units: 40 }],
      [
        { ent_lan: 9001, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'alice silva', movement_type: 'E' },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [9001]);
  });

  it('never reconciles a different operator or a stock exit', () => {
    const result = reconcileMassasDay(
      [launch],
      [
        { ent_lan: 9001, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'TIAGO', movement_type: 'E' },
        { ent_lan: 9002, product_code: '1045', quantity: 40, entry_date: '2026-06-12', operator_name: 'ALICE SILVA', movement_type: 'S' },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, []);
    assert.deepEqual(result.unmatchedLaunchIds, [1]);
  });
});
