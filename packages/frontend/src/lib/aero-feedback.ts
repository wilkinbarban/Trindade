/**
 * What the operator is told when an action leaves the app and comes back.
 *
 * The phone runs the Atajos app, so the page never sees the raw answer: it sees a verdict or a list of seats.
 * Turning those into a tone and a sentence is a decision, and decisions are cheaper to test than dialogs, so
 * they live here and the components only render the result.
 */

export type AeroResultTone = 'ok' | 'warn' | 'error';

/** The two keys a result dialog needs: what happened, and how many seats it was about. */
export interface AeroSyncFeedback {
  tone: AeroResultTone;
  titleKey: 'massas.erp.syncOkTitle' | 'massas.erp.syncErrorTitle';
  messageKey: 'massas.erp.syncOk' | 'massas.erp.syncEmpty' | 'massas.erp.syncError';
  count: number;
}

/**
 * Reads the movements round trip.
 *
 * An empty day is not a failure -- the factory produced nothing, or nobody launched it -- but celebrating
 * "0 movimentos" is worse than saying the day is empty, so it gets the warning tone.
 */
export function movementsSyncFeedback(result: {
  status: 'ok' | 'error';
  /** Absent when the answer was unreadable, which is an error and never an empty day. */
  movements?: readonly unknown[];
}): AeroSyncFeedback {
  if (result.status === 'error') {
    return {
      tone: 'error',
      titleKey: 'massas.erp.syncErrorTitle',
      messageKey: 'massas.erp.syncError',
      count: 0,
    };
  }

  const count = result.movements?.length ?? 0;
  if (count === 0) {
    return {
      tone: 'warn',
      titleKey: 'massas.erp.syncOkTitle',
      messageKey: 'massas.erp.syncEmpty',
      count: 0,
    };
  }

  return { tone: 'ok', titleKey: 'massas.erp.syncOkTitle', messageKey: 'massas.erp.syncOk', count };
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
