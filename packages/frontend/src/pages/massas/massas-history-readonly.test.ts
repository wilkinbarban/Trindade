import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The Massas history is a record, not a work surface.
 *
 * Editing or deleting a past day is how a company loses the ability to answer "who launched this, and when".
 * The page is the only place that decides it, so the assertion is that the page hands the table a read-only
 * one and no way to change anything -- for every role, administrators included.
 */
const historyPage = readFileSync(new URL('../MassasHistoryPage.tsx', import.meta.url), 'utf8');

describe('massas history is read-only', () => {
  it('hands the day table a read-only table, whoever is signed in', () => {
    assert.match(historyPage, /readOnly=\{true\}/);
    // `readOnly={!isAdmin}` was the old rule, and it is what let an administrator rewrite yesterday.
    assert.equal(/readOnly=\{!isAdmin\}/.test(historyPage), false);
  });

  it('reconciles the day with the ERP mirror, like the launch screen does', () => {
    // Without the mirror the history only knows what Trindade recorded, so a launch the ERP already received
    // reads as pending and the day's balance is short by exactly those boxes.
    assert.ok(historyPage.includes('/integration/aero/movements?date='));
    assert.ok(historyPage.includes('massasUnifiedRows('));
    assert.ok(historyPage.includes('unifiedRows={unifiedRows}'));
    assert.ok(historyPage.includes('unifiedDate={date}'));
  });

  it('offers no way to edit or delete from the history', () => {
    for (const handler of ['onStartEdit', 'onDelete', 'onSaveEdit', 'onDeleteLaunch', 'handleDeleteLaunch']) {
      assert.equal(historyPage.includes(`${handler}={`), false, `the history still wires ${handler}`);
    }
  });
});
