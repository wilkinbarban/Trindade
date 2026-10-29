import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  batchSyncFeedback,
  connectionVerdictTone,
  launchMirrorHint,
  movementsSyncFeedback,
  readAeroBatchResult,
  receiptsSyncFeedback,
} from './aero-feedback';

describe('movementsSyncFeedback', () => {
  it('reports how many massas the ERP had for the day', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', count: 10 });
    assert.deepEqual(feedback, {
      tone: 'ok',
      titleKey: 'massas.erp.syncOkTitle',
      messageKey: 'massas.erp.syncOk',
      count: 10,
    });
  });

  it('keeps one massa as one massa', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', count: 1 });
    assert.equal(feedback.tone, 'ok');
    assert.equal(feedback.count, 1);
  });

  it('says no massa was synced instead of celebrating zero', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', count: 0 });
    assert.equal(feedback.tone, 'warn');
    assert.equal(feedback.messageKey, 'massas.erp.syncEmpty');
    assert.equal(feedback.count, 0);
  });

  it('turns an unreadable answer into an error, never into an empty day', () => {
    const feedback = movementsSyncFeedback({ status: 'error', count: 0 });
    assert.deepEqual(feedback, {
      tone: 'error',
      titleKey: 'massas.erp.syncErrorTitle',
      messageKey: 'massas.erp.syncError',
      count: 0,
    });
  });
});

describe('receiptsSyncFeedback', () => {
  it('counts the supplies the day brought, with its own words', () => {
    const feedback = receiptsSyncFeedback({ status: 'ok', count: 1 });
    assert.deepEqual(feedback, {
      tone: 'ok',
      titleKey: 'reports.receiptsSyncTitle',
      messageKey: 'reports.receiptsSyncOk',
      count: 1,
    });
  });

  it('says no supply arrived rather than reusing the masses sentence', () => {
    const feedback = receiptsSyncFeedback({ status: 'ok', count: 0 });
    assert.equal(feedback.tone, 'warn');
    assert.equal(feedback.messageKey, 'reports.receiptsSyncEmpty');
  });

  it('reports the failure in the report\'s own vocabulary', () => {
    const feedback = receiptsSyncFeedback({ status: 'error', count: 0 });
    assert.equal(feedback.tone, 'error');
    assert.equal(feedback.titleKey, 'reports.receiptsSyncErrorTitle');
    assert.equal(feedback.messageKey, 'reports.receiptsSyncError');
  });
});

describe('launchMirrorHint', () => {
  it('points at the mirror only when the company confirmed the entry', () => {
    assert.equal(launchMirrorHint({ type: 'success', sentToErp: true }), true);
    assert.equal(launchMirrorHint({ type: 'success' }), false);
    assert.equal(launchMirrorHint({ type: 'error', sentToErp: true }), false);
  });

  it('still points at the mirror when only part of the batch made it', () => {
    // Un lote parcial confirmó al menos un asiento: el aviso es útil para ese producto.
    assert.equal(launchMirrorHint({ type: 'warn', sentToErp: true }), true);
  });
});

describe('connectionVerdictTone', () => {
  it('keeps a trip without a verdict as a warning, not a failure', () => {
    assert.equal(connectionVerdictTone('ok'), 'ok');
    assert.equal(connectionVerdictTone('error'), 'error');
    assert.equal(connectionVerdictTone('unknown'), 'warn');
  });
});

describe('readAeroBatchResult', () => {
  const ok = { success: true, message: 'Gravado' };
  const refused = { success: false, message: 'Item sem CST' };

  it('reads three answers for three products as one complete batch', () => {
    const outcome = readAeroBatchResult([ok, ok, ok], 3);

    assert.equal(outcome.state, 'complete');
    assert.equal(outcome.total, 3);
    assert.equal(outcome.ok, 3);
    assert.equal(outcome.failed, 0);
    assert.deepEqual(outcome.failedIndexes, []);
    assert.equal(outcome.message, null);
  });

  it('keeps the batch partial when the ERP refused one item, and names which one', () => {
    const outcome = readAeroBatchResult([ok, refused, ok], 3);

    assert.equal(outcome.state, 'partial');
    assert.equal(outcome.ok, 2);
    assert.equal(outcome.failed, 1);
    // El índice es la posición en `bodies`: la página sabe qué producto reencolar sin adivinar.
    assert.deepEqual(outcome.failedIndexes, [1]);
    assert.equal(outcome.message, 'Item sem CST');
  });

  it('counts a missing answer as a failure instead of quietly shrinking the batch', () => {
    const outcome = readAeroBatchResult([ok, ok], 3);

    assert.equal(outcome.state, 'partial');
    assert.equal(outcome.ok, 2);
    assert.equal(outcome.failed, 1);
    assert.deepEqual(outcome.failedIndexes, [2]);
  });

  it('calls the batch failed when no answer was a success', () => {
    const outcome = readAeroBatchResult([refused, refused], 2);

    assert.equal(outcome.state, 'failed');
    assert.equal(outcome.ok, 0);
    assert.equal(outcome.failed, 2);
    assert.equal(outcome.message, 'Item sem CST');
  });

  it('treats an answer it cannot read as that item failing, not as the batch being unreadable', () => {
    const outcome = readAeroBatchResult([ok, 'Could not connect to the server', ok], 3);

    assert.equal(outcome.state, 'partial');
    assert.equal(outcome.ok, 2);
    assert.deepEqual(outcome.failedIndexes, [1]);
    assert.equal(outcome.message, null);
  });

  it('says the batch is unreadable only when the answer is not an array at all', () => {
    const outcome = readAeroBatchResult(null, 3);

    assert.equal(outcome.state, 'unreadable');
    assert.equal(outcome.failed, 3);
    assert.equal(batchSyncFeedback(outcome)?.messageKey, 'massas.erp.batchUnreadable');
  });

  it('falls back to the single-product result when a phone without the batch branch answered', () => {
    // El atajo viejo ignora `bodies` y contesta un objeto suelto: el lote degrada al flujo de siempre.
    const outcome = readAeroBatchResult(ok, 3);

    assert.equal(outcome.state, 'legacy');
    assert.equal(outcome.ok, 1);
    // Un lote degradado no abre modal de lote: la página sigue drenando producto por producto.
    assert.equal(batchSyncFeedback(outcome), null);
  });
});

describe('batchSyncFeedback', () => {
  const ok = { success: true, message: 'Gravado' };
  const refused = { success: false, message: 'Item sem CST' };

  it('celebrates once for the whole batch, with the count of products', () => {
    const feedback = batchSyncFeedback(readAeroBatchResult([ok, ok, ok], 3));

    assert.equal(feedback?.tone, 'ok');
    assert.equal(feedback?.titleKey, 'massas.erp.batchOkTitle');
    assert.equal(feedback?.messageKey, 'massas.erp.batchOk');
    assert.equal(feedback?.count, 3);
  });

  it('warns with both numbers when part of the batch did not make it', () => {
    const feedback = batchSyncFeedback(readAeroBatchResult([ok, refused, ok], 3));

    assert.equal(feedback?.tone, 'warn');
    assert.equal(feedback?.titleKey, 'massas.erp.batchPartialTitle');
    assert.equal(feedback?.messageKey, 'massas.erp.batchPartial');
    assert.deepEqual(feedback?.params, { ok: 2, failed: 1, total: 3 });
  });

  it('does not dress a total failure as a partial one', () => {
    const feedback = batchSyncFeedback(readAeroBatchResult([refused, refused], 2));

    assert.equal(feedback?.tone, 'error');
    assert.equal(feedback?.titleKey, 'massas.erp.batchErrorTitle');
    assert.equal(feedback?.messageKey, 'massas.erp.batchFailed');
    assert.equal(feedback?.count, 2);
  });
});
