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
