import { readAeroBridgeResult } from './aero-integration';

/**
 * What the operator is told when an action leaves the app and comes back.
 *
 * The phone runs the Atajos app, so the page never sees the raw answer: it sees a verdict or a list of seats.
 * Turning those into a tone and a sentence is a decision, and decisions are cheaper to test than dialogs, so
 * they live here and the components only render the result.
 */

export type AeroResultTone = 'ok' | 'warn' | 'error';

/** Every sentence a sync dialog can say, so a renamed key fails here instead of at runtime. */
export type AeroFeedbackKey =
  | 'massas.erp.syncOkTitle'
  | 'massas.erp.syncErrorTitle'
  | 'massas.erp.syncOk'
  | 'massas.erp.syncEmpty'
  | 'massas.erp.syncError'
  | 'massas.erp.batchOkTitle'
  | 'massas.erp.batchPartialTitle'
  | 'massas.erp.batchErrorTitle'
  | 'massas.erp.batchOk'
  | 'massas.erp.batchPartial'
  | 'massas.erp.batchFailed'
  | 'massas.erp.batchUnreadable'
  | 'reports.receiptsSyncTitle'
  | 'reports.receiptsSyncErrorTitle'
  | 'reports.receiptsSyncOk'
  | 'reports.receiptsSyncEmpty'
  | 'reports.receiptsSyncError';

/** The two keys a result dialog needs: what happened, and how many seats it was about. */
export interface AeroSyncFeedback {
  tone: AeroResultTone;
  titleKey: AeroFeedbackKey;
  messageKey: AeroFeedbackKey;
  count: number;
  /** Extra numbers the sentence interpolates, such as how many of a batch failed. */
  params?: Record<string, number>;
}

/** The three sentences a lens can use. Each screen brings its own, so neither borrows the other's words. */
interface SyncVocabulary {
  okTitle: AeroFeedbackKey;
  errorTitle: AeroFeedbackKey;
  ok: AeroFeedbackKey;
  empty: AeroFeedbackKey;
  error: AeroFeedbackKey;
}

const MASSAS_VOCABULARY: SyncVocabulary = {
  okTitle: 'massas.erp.syncOkTitle',
  errorTitle: 'massas.erp.syncErrorTitle',
  ok: 'massas.erp.syncOk',
  empty: 'massas.erp.syncEmpty',
  error: 'massas.erp.syncError',
};

const RECEIPTS_VOCABULARY: SyncVocabulary = {
  okTitle: 'reports.receiptsSyncTitle',
  errorTitle: 'reports.receiptsSyncErrorTitle',
  ok: 'reports.receiptsSyncOk',
  empty: 'reports.receiptsSyncEmpty',
  error: 'reports.receiptsSyncError',
};

/**
 * Reads a movements round trip through one lens.
 *
 * The count is the lens's number, not the day's: an empty day for the masses can be a busy one for the
 * report, and each dialog says only what its screen is about.
 */
function syncFeedback(
  result: { status: 'ok' | 'error'; count: number },
  vocabulary: SyncVocabulary,
): AeroSyncFeedback {
  if (result.status === 'error') {
    return {
      tone: 'error',
      titleKey: vocabulary.errorTitle,
      messageKey: vocabulary.error,
      count: 0,
    };
  }

  if (result.count === 0) {
    return {
      tone: 'warn',
      titleKey: vocabulary.okTitle,
      messageKey: vocabulary.empty,
      count: 0,
    };
  }

  return { tone: 'ok', titleKey: vocabulary.okTitle, messageKey: vocabulary.ok, count: result.count };
}

/**
 * Reads the movements round trip.
 *
 * An empty day is not a failure -- the factory produced nothing, or nobody launched it -- but celebrating
 * "0 movimentos" is worse than saying the day is empty, so it gets the warning tone.
 */
export function movementsSyncFeedback(result: {
  status: 'ok' | 'error';
  /** How many massas the day carried. The supplies the same snapshot holds are not this screen's business. */
  count: number;
}): AeroSyncFeedback {
  return syncFeedback(result, MASSAS_VOCABULARY);
}

/** The same round trip read from Recebimento: only the supplies, never the massas. */
export function receiptsSyncFeedback(result: {
  status: 'ok' | 'error';
  count: number;
}): AeroSyncFeedback {
  return syncFeedback(result, RECEIPTS_VOCABULARY);
}

/**
 * Whether the launch result should point at the mirror.
 *
 * Only when the company actually confirmed the entry: telling the operator to look for a seat that never
 * travelled would send them to an empty tab and make the whole mirror look broken. A partial batch is the
 * same promise for the products that did make it, so the warning tone still points at the mirror.
 */
export function launchMirrorHint(result: {
  type: 'success' | 'warn' | 'error';
  sentToErp?: boolean;
}): boolean {
  return result.type !== 'error' && result.sentToErp === true;
}

/** The colour of the connection test, including the trip that came back without a verdict. */
export function connectionVerdictTone(verdict: 'ok' | 'error' | 'unknown'): AeroResultTone {
  if (verdict === 'ok') return 'ok';
  if (verdict === 'error') return 'error';
  return 'warn';
}

/**
 * What came back from a batch of products the phone posted one by one.
 *
 * The shortcut does not judge: it repeats the request and brings the answers in the order it sent them. This
 * is where that array becomes a verdict, and where the index of each failure survives, so the page can put
 * exactly those products back in the queue instead of asking the operator to guess.
 */
export interface AeroBatchOutcome {
  /** `legacy` is the phone that has no batch branch: it answered one object, and the page keeps walking. */
  state: 'complete' | 'partial' | 'failed' | 'unreadable' | 'legacy';
  /** How many products the page sent in that envelope. */
  total: number;
  ok: number;
  failed: number;
  /** Positions in the sent batch whose answer was not a success, missing answers included. */
  failedIndexes: number[];
  /** The first ERP sentence an operator can read, if there was one. */
  message: string | null;
}

/**
 * Reads the array the shortcut brought back, against the batch the page sent.
 *
 * A shorter array is not a smaller batch: the products without an answer failed, and pretending the batch
 * ended early would clear the queue over work the ERP never confirmed. A single object is the answer of an
 * unpatched phone -- it ignored `bodies` and posted one body -- and is reported as `legacy` so the page can
 * keep its old one-product-per-trip loop instead of showing a batch dialog for one product.
 */
export function readAeroBatchResult(data: unknown, expected: number): AeroBatchOutcome {
  if (!Array.isArray(data)) {
    if (typeof data === 'object' && data !== null) {
      const verdict = readAeroBridgeResult(data);
      return {
        state: 'legacy',
        total: 1,
        ok: verdict.status === 'ok' ? 1 : 0,
        failed: verdict.status === 'ok' ? 0 : 1,
        failedIndexes: verdict.status === 'ok' ? [] : [0],
        message: verdict.message,
      };
    }
    return {
      state: 'unreadable',
      total: expected,
      ok: 0,
      failed: expected,
      failedIndexes: Array.from({ length: expected }, (_, index) => index),
      message: null,
    };
  }

  const failedIndexes: number[] = [];
  let message: string | null = null;
  for (let index = 0; index < expected; index += 1) {
    const answer = index < data.length ? readAeroBridgeResult(data[index]) : { status: 'error' as const, message: null };
    if (answer.status === 'ok') continue;
    failedIndexes.push(index);
    if (!message && answer.message) message = answer.message;
  }

  const failed = failedIndexes.length;
  const ok = expected - failed;
  const state: AeroBatchOutcome['state'] = failed === 0 ? 'complete' : ok === 0 ? 'failed' : 'partial';
  return { state, total: expected, ok, failed, failedIndexes, message };
}

/**
 * The dialog for a batch, or nothing when the batch has to degrade to the old loop.
 *
 * A total failure and an unreadable answer are both errors, but they say different things: one means the ERP
 * refused every item, the other means nobody knows what the ERP said.
 */
export function batchSyncFeedback(outcome: AeroBatchOutcome): AeroSyncFeedback | null {
  if (outcome.state === 'legacy') return null;

  if (outcome.state === 'unreadable') {
    return {
      tone: 'error',
      titleKey: 'massas.erp.batchErrorTitle',
      messageKey: 'massas.erp.batchUnreadable',
      count: outcome.total,
      params: { failed: outcome.failed, total: outcome.total },
    };
  }

  if (outcome.state === 'failed') {
    return {
      tone: 'error',
      titleKey: 'massas.erp.batchErrorTitle',
      messageKey: 'massas.erp.batchFailed',
      count: outcome.total,
      params: { failed: outcome.failed, total: outcome.total },
    };
  }

  if (outcome.state === 'partial') {
    return {
      tone: 'warn',
      titleKey: 'massas.erp.batchPartialTitle',
      messageKey: 'massas.erp.batchPartial',
      count: outcome.total,
      params: { ok: outcome.ok, failed: outcome.failed, total: outcome.total },
    };
  }

  return {
    tone: 'ok',
    titleKey: 'massas.erp.batchOkTitle',
    messageKey: 'massas.erp.batchOk',
    count: outcome.total,
    params: { ok: outcome.ok, total: outcome.total },
  };
}
