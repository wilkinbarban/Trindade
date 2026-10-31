/**
 * Pure functions to classify and map API error codes and messages into i18n keys.
 */

/**
 * Every refusal the massas module can answer with, keyed by the semantic code the backend sends.
 *
 * The code survives a rewording and a translation, so it decides before any sentence does; the sentences
 * below it are the fallback for an older backend or a proxy that drops the field.
 */
const MASSAS_CODE_KEYS: Record<string, string> = {
  MASSAS_FUTURE_DATE: 'massas.error.futureDate',
  MASSAS_INVALID_DATE_FORMAT: 'massas.error.invalidDate',
  MASSAS_INVALID_LAUNCH_DATA: 'massas.error.invalidData',
  MASSAS_EMPTY_ITEMS: 'massas.error.emptyItems',
  MASSAS_INVALID_ID: 'massas.error.invalidId',
  MASSAS_INVALID_BOXES: 'massas.error.invalidBoxes',
  MASSAS_NOT_FOUND: 'massas.error.notFound',
  MASSAS_FORBIDDEN: 'massas.error.forbidden',
  MASSAS_RECONCILED: 'massas.reconciledLocked',
  MASSAS_HISTORY_FROZEN: 'massas.error.historyFrozen',
  MASSAS_CREATE_FAILED: 'massas.error.createFailed',
  MASSAS_ALREADY_IMPORTED: 'massas.error.alreadyImported',
  MASSAS_UPDATE_FAILED: 'massas.error.updateFailed',
  MASSAS_DELETE_FAILED: 'massas.error.deleteFailed',
  MASSAS_OPERATOR_NOT_LINKED: 'massas.error.operatorNotLinked',
  MASSAS_OPERATOR_INACTIVE: 'massas.error.operatorInactive',
};

/**
 * The ERP mirror refusals the Espelho tab can receive.
 *
 * They are not massas codes, but they reach the same operator on the same screen, so they are read here
 * instead of leaking the server's sentence.
 */
const AERO_CODE_KEYS: Record<string, string> = {
  INVALID_AERO_LOG: 'massas.erp.invalidLog',
  INVALID_DATE_FORMAT: 'massas.error.invalidDate',
  INVALID_MOVEMENTS: 'massas.erp.invalidMovements',
  AERO_MOVEMENTS_NOT_MIGRATED: 'massas.erp.mirrorMissing',
};

/**
 * Resolves a raw backend error message and status code into a translation key, or returns null if no mapping applies.
 *
 * `code` is the semantic field of the payload (`MASSAS_FORBIDDEN`, …). When it is one this module knows,
 * it wins: a translated sentence and a rewording both stop being a reason to leak the server's text.
 */
export function resolveApiErrorKey(status: number, rawMessage: string, code?: string | null): string | null {
  const msg = (rawMessage || '').trim();
  const semantic = (code || '').trim().toUpperCase();

  if (semantic && Object.hasOwn(MASSAS_CODE_KEYS, semantic)) {
    return MASSAS_CODE_KEYS[semantic];
  }
  if (semantic && Object.hasOwn(AERO_CODE_KEYS, semantic)) {
    return AERO_CODE_KEYS[semantic];
  }

  // The massas sentences, in the Portuguese the backend answers and in the English a cached client may
  // still hold. Matched before the generic branches so a 403 about a seat is not read as a plain denial.
  if (
    msg.includes('não está vinculado no Trindade') ||
    msg.includes('cadastre o operário antes de importar') ||
    msg.includes('Operator not linked to a Trindade account')
  ) {
    return 'massas.error.operatorNotLinked';
  }
  if (
    msg.includes('está inativo no Trindade') ||
    msg.includes('reative o operário antes de importar') ||
    msg.includes('Operator is inactive in Trindade')
  ) {
    return 'massas.error.operatorInactive';
  }
  if (
    msg.includes('histórico de massas é somente leitura') ||
    msg.includes('history of massas is read-only') ||
    msg.includes('Massas history is read-only')
  ) {
    return 'massas.error.historyFrozen';
  }
  if (msg.includes('pertence a outro operário') || msg.includes('belongs to another operator')) {
    return 'massas.error.forbidden';
  }
  if (msg.includes('já foi importado') || msg.includes('already imported')) {
    return 'massas.error.alreadyImported';
  }
  if (msg.includes('já foi conciliada com o ERP') || msg.toLowerCase().includes('reconciled with the erp')) {
    return 'massas.reconciledLocked';
  }
  if (msg.includes('Não é possível lançar em datas futuras') || msg.includes('Future launch dates are not allowed')) {
    return 'massas.error.futureDate';
  }
  if (msg.includes('Data inválida') || msg.includes('Invalid date format')) {
    return 'massas.error.invalidDate';
  }
  if (msg.includes('Lançamento não encontrado') || msg.includes('Launch not found')) {
    return 'massas.error.notFound';
  }
  if (msg.includes('Lançamento inválido') || msg.includes('Invalid launch ID')) {
    return 'massas.error.invalidId';
  }
  if (msg.includes('Quantidade de caixas inválida') || msg.includes('Invalid boxes count')) {
    return 'massas.error.invalidBoxes';
  }
  if (msg.includes('Envie uma lista de itens') || msg.includes('Must provide either items array')) {
    return 'massas.error.emptyItems';
  }
  if (msg.includes('Dados de lançamento inválidos') || msg.includes('Invalid launch data')) {
    return 'massas.error.invalidData';
  }
  if (msg.includes('Não foi possível registrar o lançamento') || msg.includes('Failed to create launches')) {
    return 'massas.error.createFailed';
  }
  if (msg.includes('Não foi possível atualizar o lançamento') || msg.includes('Failed to update launch')) {
    return 'massas.error.updateFailed';
  }
  if (msg.includes('Não foi possível excluir o lançamento') || msg.includes('Failed to delete launch')) {
    return 'massas.error.deleteFailed';
  }

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
