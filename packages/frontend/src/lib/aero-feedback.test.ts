import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { connectionVerdictTone, launchMirrorHint, movementsSyncFeedback } from './aero-feedback';

describe('movementsSyncFeedback', () => {
  const seats = (count: number) => Array.from({ length: count }, (_, index) => ({ entLan: 1000 + index }));

  it('reports how many seats the ERP had for the day', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', movements: seats(11) });
    assert.deepEqual(feedback, {
      tone: 'ok',
      titleKey: 'massas.erp.syncOkTitle',
      messageKey: 'massas.erp.syncOk',
      count: 11,
    });
  });

  it('keeps one seat as one seat', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', movements: seats(1) });
    assert.equal(feedback.tone, 'ok');
    assert.equal(feedback.count, 1);
  });

  it('says the day is empty instead of celebrating zero seats', () => {
    const feedback = movementsSyncFeedback({ status: 'ok', movements: [] });
    assert.equal(feedback.tone, 'warn');
    assert.equal(feedback.messageKey, 'massas.erp.syncEmpty');
    assert.equal(feedback.count, 0);
  });

  it('turns an unreadable answer into an error, never into an empty day', () => {
    const feedback = movementsSyncFeedback({ status: 'error', movements: [] });
    assert.deepEqual(feedback, {
      tone: 'error',
      titleKey: 'massas.erp.syncErrorTitle',
      messageKey: 'massas.erp.syncError',
      count: 0,
    });
  });
});

describe('launchMirrorHint', () => {
  it('points at the mirror once the launch reached the company', () => {
    assert.equal(launchMirrorHint({ type: 'success', sentToErp: true }), true);
  });

  it('stays quiet when the launch never left Trindade', () => {
    assert.equal(launchMirrorHint({ type: 'success', sentToErp: false }), false);
    assert.equal(launchMirrorHint({ type: 'success' }), false);
  });

  it('never promises a seat the ERP did not confirm', () => {
    assert.equal(launchMirrorHint({ type: 'error', sentToErp: true }), false);
  });
});

describe('connectionVerdictTone', () => {
  it('colours a test the way it ended', () => {
    assert.equal(connectionVerdictTone('ok'), 'ok');
    assert.equal(connectionVerdictTone('error'), 'error');
    // Volver sin veredicto no es un éxito ni una falla del ERP: es un intento que quedó abierto.
    assert.equal(connectionVerdictTone('unknown'), 'warn');
  });
});
