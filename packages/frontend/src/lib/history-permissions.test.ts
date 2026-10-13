import { test } from 'node:test';
import assert from 'node:assert/strict';
import { historyAffordances, historyActionErrorMessageKey, type HistoryServerPermissions } from './history-permissions.js';

/** Rows as the API projects them; the client renders this projection and infers nothing. */
const workerCanDeleteButNotDeactivate: HistoryServerPermissions = {
  isActive: true, readOnly: true, canEdit: false, canDeactivate: false, canDelete: true,
};
const adminOnActiveReport: HistoryServerPermissions = {
  isActive: true, readOnly: true, canEdit: false, canDeactivate: true, canDelete: true,
};

test('affordances follow the server projection instead of a client-invented role rule', () => {
  // A Trabalhador the server lets delete keeps the delete control: the backend is the authority.
  assert.deepEqual(historyAffordances(workerCanDeleteButNotDeactivate), {
    showEdit: false, showDeactivate: false, showDelete: true,
  });
  assert.deepEqual(historyAffordances(adminOnActiveReport), {
    showEdit: false, showDeactivate: true, showDelete: true,
  });
  assert.deepEqual(historyAffordances({ canEdit: true, canDeactivate: true, canDelete: true }), {
    showEdit: true, showDeactivate: true, showDelete: true,
  });
});

test('a missing or non-boolean flag hides the control rather than guessing', () => {
  assert.deepEqual(historyAffordances({}), { showEdit: false, showDeactivate: false, showDelete: false });
  const partial = { canDelete: true } as HistoryServerPermissions;
  assert.deepEqual(historyAffordances(partial), { showEdit: false, showDeactivate: false, showDelete: true });
  const undefinedFlags = { canEdit: undefined, canDeactivate: undefined, canDelete: undefined };
  assert.deepEqual(historyAffordances(undefinedFlags), { showEdit: false, showDeactivate: false, showDelete: false });
});

test('a refusal becomes an actionable message key instead of an unhandled rejection', () => {
  assert.equal(historyActionErrorMessageKey({ status: 403 }), 'history.actionForbidden');
  assert.equal(historyActionErrorMessageKey({ status: 404 }), 'history.actionMissing');
  assert.equal(historyActionErrorMessageKey({ status: 500 }), 'history.actionFailed');
  assert.equal(historyActionErrorMessageKey(new TypeError('Failed to fetch')), 'history.actionFailed');
  assert.equal(historyActionErrorMessageKey(undefined), 'history.actionFailed');
  assert.equal(historyActionErrorMessageKey({ status: '403' }), 'history.actionFailed', 'only numeric statuses are trusted');
});
