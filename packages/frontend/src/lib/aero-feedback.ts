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
 * travelled would send them to an empty tab and make the whole mirror look broken.
 */
export function launchMirrorHint(result: { type: 'success' | 'error'; sentToErp?: boolean }): boolean {
  return result.type === 'success' && result.sentToErp === true;
}

/** The colour of the connection test, including the trip that came back without a verdict. */
export function connectionVerdictTone(verdict: 'ok' | 'error' | 'unknown'): AeroResultTone {
  if (verdict === 'ok') return 'ok';
  if (verdict === 'error') return 'error';
  return 'warn';
}
