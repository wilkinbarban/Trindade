import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveApiErrorKey, isNetworkError } from './api-error-normalizer';

test('resolveApiErrorKey: maps 401 Invalid credentials to auth.invalidCredentials', () => {
  assert.equal(resolveApiErrorKey(401, 'Invalid credentials'), 'auth.invalidCredentials');
  assert.equal(resolveApiErrorKey(401, 'invalid credentials'), 'auth.invalidCredentials');
});

test('resolveApiErrorKey: maps 400 Invalid current password to auth.invalidCurrentPassword', () => {
  assert.equal(resolveApiErrorKey(400, 'Invalid current password'), 'auth.invalidCurrentPassword');
  assert.equal(resolveApiErrorKey(400, 'invalid current password'), 'auth.invalidCurrentPassword');
});

test('resolveApiErrorKey: maps 409 Password or account changed to auth.passwordChangedRelogin', () => {
  assert.equal(
    resolveApiErrorKey(409, 'Password or account changed; sign in again'),
    'auth.passwordChangedRelogin'
  );
});

test('resolveApiErrorKey: maps 403 Insufficient permissions to api.forbidden', () => {
  assert.equal(resolveApiErrorKey(403, 'Insufficient permissions'), 'api.forbidden');
  assert.equal(resolveApiErrorKey(403, 'insufficient permissions'), 'api.forbidden');
});

test('resolveApiErrorKey: maps 400 Invalid input to api.invalidInput', () => {
  assert.equal(resolveApiErrorKey(400, 'Invalid input'), 'api.invalidInput');
  assert.equal(resolveApiErrorKey(400, 'invalid input for user'), 'api.invalidInput');
});

test('resolveApiErrorKey: maps SQLite unique constraints and duplicate usernames to admin.workers.userAlreadyExists', () => {
  assert.equal(
    resolveApiErrorKey(400, 'UNIQUE constraint failed: users.username'),
    'admin.workers.userAlreadyExists'
  );
  assert.equal(
    resolveApiErrorKey(409, 'username already exists'),
    'admin.workers.userAlreadyExists'
  );
});

test('resolveApiErrorKey: maps 409 duplicate driver/vehicle in loading schedule', () => {
  assert.equal(
    resolveApiErrorKey(409, 'Motorista já agendado neste lote de carregamento.'),
    'loading.driverAlreadyScheduled'
  );
  assert.equal(
    resolveApiErrorKey(409, 'Veículo já agendado nesta data.'),
    'loading.vehicleAlreadyScheduled'
  );
});

test('resolveApiErrorKey: returns null for unmapped custom errors', () => {
  assert.equal(resolveApiErrorKey(404, 'Report not found'), null);
  assert.equal(resolveApiErrorKey(500, 'Internal Server Error'), null);
});

test('isNetworkError: correctly detects network failures and fetch errors', () => {
  assert.equal(isNetworkError(new TypeError('Failed to fetch')), true);
  assert.equal(isNetworkError(new TypeError('NetworkError when attempting to fetch resource.')), true);
  assert.equal(isNetworkError(new TypeError('Load failed')), true);
  const networkError = new Error('Network error');
  networkError.name = 'NetworkError';
  assert.equal(isNetworkError(networkError), true);

  assert.equal(isNetworkError(new Error('Validation error')), false);
  assert.equal(isNetworkError(null), false);
  assert.equal(isNetworkError(undefined), false);
});

test('resolveApiErrorKey: maps the reconciled launch 409 to massas.reconciledLocked', () => {
  assert.equal(
    resolveApiErrorKey(409, 'This production was already reconciled with the ERP and cannot change'),
    'massas.reconciledLocked',
  );
});

test('resolveApiErrorKey: a massas code wins over the sentence, in either language', () => {
  // The backend answers in Portuguese now; the code is what survives a rewording, so it decides.
  assert.equal(resolveApiErrorKey(409, '', 'MASSAS_HISTORY_FROZEN'), 'massas.error.historyFrozen');
  assert.equal(resolveApiErrorKey(409, 'O histórico mudou de frase', 'MASSAS_HISTORY_FROZEN'), 'massas.error.historyFrozen');
  assert.equal(resolveApiErrorKey(403, '', 'MASSAS_FORBIDDEN'), 'massas.error.forbidden');
  assert.equal(resolveApiErrorKey(409, '', 'MASSAS_ALREADY_IMPORTED'), 'massas.error.alreadyImported');
});

test('resolveApiErrorKey: the frozen history and the foreign seat are read by their sentence too', () => {
  // An older backend, or a proxy that drops `code`, still gets a Portuguese answer on screen.
  assert.equal(
    resolveApiErrorKey(409, 'O histórico de massas é somente leitura: dias passados não podem ser alterados'),
    'massas.error.historyFrozen',
  );
  assert.equal(resolveApiErrorKey(403, 'Este lançamento pertence a outro operário'), 'massas.error.forbidden');
  assert.equal(resolveApiErrorKey(409, 'Este lançamento do ERP já foi importado'), 'massas.error.alreadyImported');
});

test('resolveApiErrorKey: no massas sentence reaches the screen in English', () => {
  // The old English strings are still refused, because a cached client is not a reason to show English.
  assert.equal(resolveApiErrorKey(403, 'This ERP seat belongs to another operator'), 'massas.error.forbidden');
  assert.equal(resolveApiErrorKey(409, 'This ERP seat was already imported'), 'massas.error.alreadyImported');
});

test('resolveApiErrorKey: every massas code has a translation, none falls through', () => {
  const codes = [
    'MASSAS_FUTURE_DATE',
    'MASSAS_INVALID_DATE_FORMAT',
    'MASSAS_INVALID_LAUNCH_DATA',
    'MASSAS_EMPTY_ITEMS',
    'MASSAS_INVALID_ID',
    'MASSAS_INVALID_BOXES',
    'MASSAS_NOT_FOUND',
    'MASSAS_FORBIDDEN',
    'MASSAS_RECONCILED',
    'MASSAS_HISTORY_FROZEN',
    'MASSAS_CREATE_FAILED',
    'MASSAS_ALREADY_IMPORTED',
    'MASSAS_UPDATE_FAILED',
    'MASSAS_DELETE_FAILED',
  ];
  for (const code of codes) {
    assert.ok(resolveApiErrorKey(400, '', code), `${code} has no key`);
  }
});

test('resolveApiErrorKey: the ERP mirror refusals on the same screen are translated too', () => {
  assert.equal(resolveApiErrorKey(503, '', 'AERO_MOVEMENTS_NOT_MIGRATED'), 'massas.erp.mirrorMissing');
  assert.equal(resolveApiErrorKey(400, '', 'INVALID_MOVEMENTS'), 'massas.erp.invalidMovements');
  assert.equal(resolveApiErrorKey(400, '', 'INVALID_AERO_LOG'), 'massas.erp.invalidLog');
  assert.equal(resolveApiErrorKey(400, 'Data inválida: use AAAA-MM-DD', 'INVALID_DATE_FORMAT'), 'massas.error.invalidDate');
});
