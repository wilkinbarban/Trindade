import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readReportReceiptsDay, usesErpReceipts } from './receipts';

const line = {
  entLan: 35911,
  productCode: '001774',
  productDesc: 'CAIXA DE PAPELAO PEQUENA MISTA',
  quantity: 3606,
  operatorName: 'VIVIANE',
  entryDate: '2026-10-29',
};

describe('usesErpReceipts', () => {
  it('switches on the cutover day and never before it', () => {
    assert.equal(usesErpReceipts('2026-10-28'), false);
    assert.equal(usesErpReceipts('2026-10-29'), true);
    assert.equal(usesErpReceipts('2027-01-01'), true);
  });
});

describe('readReportReceiptsDay', () => {
  it('reads a day with its lines', () => {
    const day = readReportReceiptsDay({
      date: '2026-10-29',
      migrated: true,
      syncedAt: '2026-10-29T18:00:00.000Z',
      receipts: [line],
    });
    assert.deepEqual(day, {
      date: '2026-10-29',
      migrated: true,
      syncedAt: '2026-10-29T18:00:00.000Z',
      receipts: [line],
    });
  });

  it('reads an unsynced day and a day with no supplies', () => {
    assert.deepEqual(readReportReceiptsDay({ date: '2026-10-29', migrated: true, syncedAt: null, receipts: [] }), {
      date: '2026-10-29',
      migrated: true,
      syncedAt: null,
      receipts: [],
    });
    assert.deepEqual(
      readReportReceiptsDay({ date: '2026-10-29', migrated: true, syncedAt: '2026-10-29T08:00:00.000Z', receipts: [] }),
      { date: '2026-10-29', migrated: true, syncedAt: '2026-10-29T08:00:00.000Z', receipts: [] },
    );
  });

  it('refuses a half-read line instead of showing a receipt with no quantity', () => {
    const { quantity: _quantity, ...withoutQuantity } = line;
    assert.equal(
      readReportReceiptsDay({ date: '2026-10-29', migrated: true, syncedAt: null, receipts: [withoutQuantity] }),
      null,
    );
  });

  it('refuses a body it cannot describe', () => {
    for (const body of [
      null,
      'a string',
      {},
      { date: '2026-10-29', migrated: 'yes', syncedAt: null, receipts: [] },
      { date: '2026-10-29', migrated: true, syncedAt: 3, receipts: [] },
      { date: '2026-10-29', migrated: true, syncedAt: null, receipts: 'none' },
    ]) {
      assert.equal(readReportReceiptsDay(body), null, `${JSON.stringify(body)} must be refused`);
    }
  });
});
