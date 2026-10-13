/**
 * Which destructive actions a history row may offer.
 *
 * The API already decides that per row (`projectHistoryPermissions` on the backend) and sends
 * the answer along with each item. This module renders that answer and never re-derives it
 * from the role: a client-invented role or ownership rule would disagree with the server as
 * soon as either side changes, and the ledger that opened this work forbids exactly that.
 *
 * Anything that is not literally `true` means "not allowed", so a missing or malformed flag
 * hides the control instead of offering an action the server is going to refuse.
 */
export interface HistoryServerPermissions {
  isActive?: boolean;
  readOnly?: boolean;
  canEdit?: boolean;
  canDeactivate?: boolean;
  canDelete?: boolean;
}

export interface HistoryAffordances {
  showEdit: boolean;
  showDeactivate: boolean;
  showDelete: boolean;
}

export function historyAffordances(row: HistoryServerPermissions): HistoryAffordances {
  return {
    showEdit: row.canEdit === true,
    showDeactivate: row.canDeactivate === true,
    showDelete: row.canDelete === true,
  };
}

/**
 * Translation key for a refused action.
 *
 * A refusal has to be actionable: the operator must read why nothing happened instead of
 * meeting an unhandled rejection. Only a numeric HTTP status is trusted; a network failure or
 * any unknown error shape falls back to the generic message.
 */
export function historyActionErrorMessageKey(error: unknown): string {
  const status =
    typeof error === 'object' && error !== null && typeof (error as { status?: unknown }).status === 'number'
      ? (error as { status: number }).status
      : null;
  if (status === 403) return 'history.actionForbidden';
  if (status === 404) return 'history.actionMissing';
  return 'history.actionFailed';
}
