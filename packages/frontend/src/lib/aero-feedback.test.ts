import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  connectionVerdictTone,
  launchMirrorHint,
  movementsSyncFeedback,
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
});

describe('connectionVerdictTone', () => {
  it('keeps a trip without a verdict as a warning, not a failure', () => {
    assert.equal(connectionVerdictTone('ok'), 'ok');
    assert.equal(connectionVerdictTone('error'), 'error');
    assert.equal(connectionVerdictTone('unknown'), 'warn');
  });
});
