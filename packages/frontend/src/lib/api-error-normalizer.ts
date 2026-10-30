/**
 * Pure functions to classify and map API error codes and messages into i18n keys.
 */

/**
 * Resolves a raw backend error message and status code into a translation key, or returns null if no mapping applies.
 */
export function resolveApiErrorKey(status: number, rawMessage: string): string | null {
  const msg = (rawMessage || '').trim();

  if (status === 401) {
    if (msg === 'Invalid credentials' || msg.toLowerCase().includes('credential')) {
      return 'auth.invalidCredentials';
    }
  }

  if (status === 400) {
    if (msg === 'Invalid current password' || msg.toLowerCase().includes('current password')) {
      return 'auth.invalidCurrentPassword';
    }
    if (
      msg.includes('UNIQUE constraint failed') ||
      msg.includes('userAlreadyExists') ||
      msg.toLowerCase().includes('already in use') ||
      msg.toLowerCase().includes('already exists')
    ) {
      return 'admin.workers.userAlreadyExists';
    }
    if (msg === 'Invalid input' || msg.toLowerCase().includes('invalid input')) {
      return 'api.invalidInput';
    }
  }

  if (status === 409) {
    if (msg.includes('Password or account changed') || msg.includes('sign in again')) {
      return 'auth.passwordChangedRelogin';
    }
    if (
      msg.includes('UNIQUE constraint failed') ||
      msg.includes('userAlreadyExists') ||
      msg.toLowerCase().includes('already in use') ||
      msg.toLowerCase().includes('already exists')
    ) {
      return 'admin.workers.userAlreadyExists';
    }
    if (msg.includes('Motorista já agendado') || msg.includes('Motorista')) {
      return 'loading.driverAlreadyScheduled';
    }
    if (msg.includes('Veículo já agendado') || msg.includes('Veículo')) {
      return 'loading.vehicleAlreadyScheduled';
    }
    // A launch the ERP already recorded: the backend refuses the edit, and the screen has to say why
    // instead of repeating an English sentence to a Portuguese-speaking operator.
    if (msg.toLowerCase().includes('reconciled with the erp')) {
      return 'massas.reconciledLocked';
    }
  }

  if (status === 403) {
    if (msg === 'Insufficient permissions' || msg.toLowerCase().includes('permission')) {
      return 'api.forbidden';
    }
  }

  return null;
}

/**
 * Detects whether an exception thrown during fetch represents a network or connectivity failure.
 */
export function isNetworkError(error: unknown): boolean {
  if (!error) return false;
  if (error instanceof TypeError) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes('failed to fetch') ||
      msg.includes('network') ||
      msg.includes('load failed') ||
      msg.includes('networkerror')
    );
  }
  if (error instanceof Error) {
    const name = error.name.toLowerCase();
    const msg = error.message.toLowerCase();
    return (
      name.includes('networkerror') ||
      name.includes('aborterror') ||
      msg.includes('failed to fetch') ||
      msg.includes('network') ||
      msg.includes('network error')
    );
  }
  return false;
}
