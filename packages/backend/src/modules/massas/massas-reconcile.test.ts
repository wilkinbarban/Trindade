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

  /**
   * The import case this rule exists for: the tablet recorded the seat under its own operator, the launch
   * was imported by somebody else, and the name alone can never pair them. The seat is the identity.
   */
  it('pairs a launch with the seat it was imported from, whatever the operator names say', () => {
    const imported = {
      ...launch,
      id: 90,
      product_code: '5000',
      boxes: 27,
      units: 270,
      username: 'wilkin',
      display_name: 'Wilkin',
      ent_lan: 35937,
    };
    const result = reconcileMassasDay(
      [imported],
      [
        {
          ent_lan: 35937,
          product_code: '005000',
          quantity: 270,
          entry_date: '2026-10-30',
          operator_name: 'VICTOR',
          movement_type: 'E',
        },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [35937]);
    assert.deepEqual(result.matchedLaunchIds, [90]);
    assert.deepEqual(result.unmatchedLaunchIds, []);
  });

  it('keeps the name matching for launches without a seat', () => {
    // Typed by hand, so there is no seat to trust and the day still has to pair them the old way.
    const result = reconcileMassasDay(
      [{ ...launch, ent_lan: null }],
      [
        {
          ent_lan: 9003,
          product_code: '1045',
          quantity: 40,
          entry_date: '2026-06-12',
          operator_name: 'ALICE SILVA',
          movement_type: 'E',
        },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [9003]);
    assert.deepEqual(result.matchedLaunchIds, [1]);
  });

  it('lets the seat win over a name that would have paired a different launch', () => {
    // Two launches of the same boxes by the same person: one was imported from seat 9004, the other was
    // typed. The seat has to take its own launch, and the name pass must not hand it the other one.
    const typed = { ...launch, id: 5 };
    const imported = { ...launch, id: 6, ent_lan: 9004 };
    const result = reconcileMassasDay(
      [typed, imported],
      [
        {
          ent_lan: 9004,
          product_code: '1045',
          quantity: 40,
          entry_date: '2026-06-12',
          operator_name: 'ALICE SILVA',
          movement_type: 'E',
        },
      ],
    );

    assert.deepEqual(result.reconciledEntLans, [9004]);
    assert.deepEqual(result.matchedLaunchIds, [6]);
    assert.deepEqual(result.unmatchedLaunchIds, [5]);
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
