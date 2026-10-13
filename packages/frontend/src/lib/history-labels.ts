/**
 * Translation keys the history screens need for labels that are not visible text.
 *
 * The filters carry no visible label, so they depend on a programmatic name, and the row actions
 * that are icon-only need one too. Naming them here, once, lets a test prove that every key the
 * screens ask for exists in every catalogue: a missing key would otherwise reach a screen reader
 * as the raw key, which is exactly the failure this work exists to remove.
 *
 * A domain lists only the actions it really offers. The loading history has no icon-only "view"
 * control — its card is a link with visible text, and giving that link an aria-label would replace
 * the text the operator can see — so listing "view" for it would demand copy nothing uses.
 */
export const HISTORY_ACTIONS = {
  report: ['view', 'deactivate', 'delete'],
  loading: ['deactivate', 'delete'],
} as const;

export type HistoryDomain = keyof typeof HISTORY_ACTIONS;
export type HistoryIconAction = 'view' | 'deactivate' | 'delete';

/**
 * The accessible name for a row action.
 *
 * The name carries the row's date because "Delete" alone tells the operator nothing about which
 * record is about to go; that context is what makes the control safe to use without seeing it.
 */
export function historyActionLabelKey(domain: HistoryDomain, action: HistoryIconAction): string {
  return `history.${action}${domain === 'report' ? 'Report' : 'Loading'}`;
}

export const HISTORY_FILTER_LABEL_KEYS = [
  'history.filterDate',
  'history.filterMonth',
  'history.filterUser',
] as const;

/** Every action key the screens actually render, derived from the actions each domain offers. */
export const HISTORY_ACTION_LABEL_KEYS: readonly string[] = (
  Object.keys(HISTORY_ACTIONS) as HistoryDomain[]
).flatMap((domain) => HISTORY_ACTIONS[domain].map((action) => historyActionLabelKey(domain, action)));

/** Keys whose confirmation dialog reuses copy the app already had. */
export const HISTORY_CONFIRM_KEYS = ['ui.deleteConfirm', 'ui.deletePermanently', 'ui.cancel'] as const;
