export const MASSAS_ERROR_CODES = {
  FUTURE_DATE: 'MASSAS_FUTURE_DATE',
  INVALID_DATE_FORMAT: 'MASSAS_INVALID_DATE_FORMAT',
  INVALID_LAUNCH_DATA: 'MASSAS_INVALID_LAUNCH_DATA',
  EMPTY_ITEMS: 'MASSAS_EMPTY_ITEMS',
  INVALID_ID: 'MASSAS_INVALID_ID',
  INVALID_BOXES: 'MASSAS_INVALID_BOXES',
  NOT_FOUND: 'MASSAS_NOT_FOUND',
  FORBIDDEN: 'MASSAS_FORBIDDEN',
  RECONCILED: 'MASSAS_RECONCILED',
  HISTORY_FROZEN: 'MASSAS_HISTORY_FROZEN',
  CREATE_FAILED: 'MASSAS_CREATE_FAILED',
  ALREADY_IMPORTED: 'MASSAS_ALREADY_IMPORTED',
  OPERATOR_NOT_LINKED: 'MASSAS_OPERATOR_NOT_LINKED',
  OPERATOR_INACTIVE: 'MASSAS_OPERATOR_INACTIVE',
  UPDATE_FAILED: 'MASSAS_UPDATE_FAILED',
  DELETE_FAILED: 'MASSAS_DELETE_FAILED',
} as const;

export type MassasErrorCode = (typeof MASSAS_ERROR_CODES)[keyof typeof MASSAS_ERROR_CODES];

export interface ApiErrorPayload {
  error: string;
  code: string;
  details?: unknown;
}

/**
 * Creates a structured API error response object with an English message and a semantic code.
 */
export function apiError(error: string, code: string, details?: unknown): ApiErrorPayload {
  if (details !== undefined) {
    return { error, code, details };
  }
  return { error, code };
}
