import i18n from '../i18n/config';
import { GUEST_READONLY_MESSAGE, isGuestMutationRefusal } from '../lib/guest-readonly';
import { resolveApiErrorKey, isNetworkError } from '../lib/api-error-normalizer';

const APP_BASE = (import.meta.env?.BASE_URL ?? '').replace(/\/$/, '');
export const API_BASE = `${APP_BASE}/api`;

export function apiUrl(path: string): string {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path;
  }

  if (path.startsWith('/api/') || path.startsWith('/p/')) {
    return `${APP_BASE}${path}`;
  }

  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`;
}

export function appUrl(path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${APP_BASE}${normalizedPath}` || normalizedPath;
}


const SESSION_EXPIRED_NOTICE_KEY = 'auth_notice';
const SESSION_EXPIRED_NOTICE_VALUE = 'session_expired';

export function markSessionExpiredNotice(): void {
  sessionStorage.setItem(SESSION_EXPIRED_NOTICE_KEY, SESSION_EXPIRED_NOTICE_VALUE);
}

export function consumeSessionExpiredNotice(): boolean {
  const value = sessionStorage.getItem(SESSION_EXPIRED_NOTICE_KEY);
  if (value === SESSION_EXPIRED_NOTICE_VALUE) {
    sessionStorage.removeItem(SESSION_EXPIRED_NOTICE_KEY);
    return true;
  }
  return false;
}

const GUEST_READONLY_NOTICE_KEY = 'guest_readonly_notice';

/**
 * El evento para el caso en que el aviso se marca con la aplicación ya montada: el sessionStorage solo
 * se lee una vez, al montar el host, y una mutación del invitado ocurre en cualquier pantalla.
 */
export const GUEST_READONLY_NOTICE_EVENT = 'guest-readonly-notice';

/**
 * El invitado intentó una mutación y el backend la rechazó. Se guarda el mensaje -- el del servidor, que
 * es el literal de la directiva -- para que sobreviva a la navegación, igual que el aviso de sesión
 * vencida, y se emite el evento para que el host lo muestre en el acto.
 */
export function markGuestReadonlyNotice(): void {
  sessionStorage.setItem(GUEST_READONLY_NOTICE_KEY, GUEST_READONLY_MESSAGE);
  window.dispatchEvent(new Event(GUEST_READONLY_NOTICE_EVENT));
}

export function consumeGuestReadonlyNotice(): string | null {
  const value = sessionStorage.getItem(GUEST_READONLY_NOTICE_KEY);
  if (value) {
    sessionStorage.removeItem(GUEST_READONLY_NOTICE_KEY);
  }
  return value;
}

export const AUTH_SESSION_REFRESHED_EVENT = 'auth-session-refreshed';

export function clearStoredSession(): void {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('auth_refresh_token');
  localStorage.removeItem('auth_user');
}

export function handleUnauthorizedRedirect(): void {
  clearStoredSession();
  markSessionExpiredNotice();
  if (typeof window !== 'undefined' && window.location && window.location.pathname !== appUrl('/login')) {
    window.location.href = appUrl('/login');
  }
}

function isLoginRequest(path: string): boolean {
  return path === '/auth/login' || path === '/api/auth/login';
}

function isTokenlessPath(path: string): boolean {
  return (
    path === '/auth/login' ||
    path === '/api/auth/login' ||
    path === '/auth/refresh' ||
    path === '/api/auth/refresh' ||
    path === '/auth/setup' ||
    path === '/api/auth/setup' ||
    path === '/auth/register' ||
    path === '/api/auth/register'
  );
}

let inFlightRefresh: Promise<boolean> | null = null;

export async function refreshTokenOnce(attemptedToken: string): Promise<boolean> {
  const currentToken = localStorage.getItem('auth_token');
  if (currentToken && currentToken !== attemptedToken) {
    return true;
  }

  const refreshToken = localStorage.getItem('auth_refresh_token');
  if (!refreshToken) {
    return false;
  }

  if (inFlightRefresh) {
    return inFlightRefresh;
  }

  let refreshPromise: Promise<boolean> | null = null;
  refreshPromise = (async () => {
    try {
      const lang = localStorage.getItem('i18nextLng') || 'pt-BR';
      const response = await fetch(apiUrl('/auth/refresh'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept-Language': lang,
        },
        body: JSON.stringify({ refreshToken }),
      });

      if (!response.ok) {
        return false;
      }

      const data = await response.json();
      if (!data || typeof data !== 'object' || !data.token || !data.refreshToken) {
        return false;
      }

      localStorage.setItem('auth_token', data.token);
      localStorage.setItem('auth_refresh_token', data.refreshToken);

      if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
        window.dispatchEvent(
          new CustomEvent(AUTH_SESSION_REFRESHED_EVENT, {
            detail: { token: data.token, refreshToken: data.refreshToken },
          })
        );
      }

      return true;
    } catch {
      return false;
    } finally {
      if (inFlightRefresh === refreshPromise) {
        inFlightRefresh = null;
      }
    }
  })();

  inFlightRefresh = refreshPromise;
  return inFlightRefresh;
}

export class ApiClientError extends Error {
  constructor(
    public status: number,
    message: string,
    public details?: unknown
  ) {
    super(message);
    this.name = 'ApiClientError';
  }
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  isRetry = false
): Promise<T> {
  const token = localStorage.getItem('auth_token');
  const lang = localStorage.getItem('i18nextLng') || 'pt-BR';

  const headers: Record<string, string> = {
    'Accept-Language': lang,
  };

  if (options.body) {
    headers['Content-Type'] = 'application/json';
  }

  // Merge any custom headers
  if (options.headers) {
    if (options.headers instanceof Headers) {
      options.headers.forEach((value, key) => {
        headers[key] = value;
      });
    } else if (Array.isArray(options.headers)) {
      for (const [key, value] of options.headers) {
        headers[key] = value;
      }
    } else {
      Object.assign(headers, options.headers);
    }
  }

  if (token && !isTokenlessPath(path)) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(apiUrl(path), {
      ...options,
      headers,
    });
  } catch (err) {
    if (isNetworkError(err)) {
      throw new ApiClientError(0, i18n.t('api.networkError'));
    }
    throw err;
  }

  let data: any = null;
  const contentType = response.headers.get('content-type');
  if (response.status !== 204 && contentType && contentType.includes('application/json')) {
    data = await response.json();
  }

  // Store refresh token and access token if returned on login
  if (response.ok && isLoginRequest(path) && data && typeof data === 'object') {
    if (data.token) {
      localStorage.setItem('auth_token', data.token);
    }
    if (data.refreshToken) {
      localStorage.setItem('auth_refresh_token', data.refreshToken);
    }
  }

  if (response.status === 401) {
    if (token && !isTokenlessPath(path)) {
      // A 401 with a token in hand means the session expired, whatever sentence the server chose
      // to wrap it in: the renewal is attempted once and the original request retried.
      if (!isRetry) {
        const renewed = await refreshTokenOnce(token);
        if (renewed) {
          return request<T>(path, options, true);
        }
      }
      handleUnauthorizedRedirect();
      throw new ApiClientError(401, i18n.t('api.sessionExpired'));
    }

    const rawError = (data && (data.error || data.message)) || '';
    const mappedKey = resolveApiErrorKey(401, rawError);
    const message = mappedKey ? i18n.t(mappedKey) : (rawError || i18n.t('api.unauthorized'));

    throw new ApiClientError(401, message, data && data.details);
  }

  if (!response.ok) {
    // Las dos claves: la guarda del invitado responde { message } y el resto de la API responde { error }.
    const rawError = (data && (data.message || data.error)) || '';
    // The semantic code travels with the sentence: a translated backend or a rewording must not turn a
    // known refusal into a raw message on screen.
    const rawCode = (data && (data as { code?: string }).code) || '';
    const mappedKey = resolveApiErrorKey(response.status, rawError, rawCode);
    const message = mappedKey ? i18n.t(mappedKey) : (rawError || i18n.t('api.requestFailed'));

    // El invitado es de solo lectura: se reconoce ESE rechazo -- y solo ese -- para mostrarle el aviso
    // informativo. Cualquier otro error sigue el camino de siempre.
    if (isGuestMutationRefusal(response.status, rawError || message)) {
      markGuestReadonlyNotice();
    }

    throw new ApiClientError(response.status, message, data && data.details);
  }

  return data as T;
}

export const api = {
  get<T>(path: string): Promise<T> {
    return request<T>(path);
  },

  post<T>(path: string, body: unknown): Promise<T> {
    return request<T>(path, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  patch<T>(path: string, body: unknown): Promise<T> {
    return request<T>(path, {
      method: 'PATCH',
      body: JSON.stringify(body),
    });
  },

  put<T>(path: string, body: unknown): Promise<T> {
    return request<T>(path, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
  },

  async del<T = void>(path: string): Promise<T> {
    return request<T>(path, { method: 'DELETE' });
  },

  /**
   * Upload a file via multipart/form-data.
   * Does NOT set Content-Type so the browser sets it with the correct boundary.
   */
  async upload<T>(path: string, formData: FormData, isRetry = false): Promise<T> {
    const token = localStorage.getItem('auth_token');
    const lang = localStorage.getItem('i18nextLng') || 'pt-BR';

    const headers: Record<string, string> = {
      'Accept-Language': lang,
    };
    if (token && !isTokenlessPath(path)) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    // Do NOT set Content-Type — browser adds multipart boundary

    let response: Response;
    try {
      response = await fetch(apiUrl(path), {
        method: 'POST',
        headers,
        body: formData,
      });
    } catch (err) {
      if (isNetworkError(err)) {
        throw new ApiClientError(0, i18n.t('api.networkError'));
      }
      throw err;
    }

    let data: any = null;
    const contentType = response.headers.get('content-type');
    if (response.status !== 204 && contentType && contentType.includes('application/json')) {
      data = await response.json();
    }

    if (response.status === 401) {
      if (token && !isTokenlessPath(path)) {
        if (!isRetry) {
          const renewed = await refreshTokenOnce(token);
          if (renewed) {
            return api.upload<T>(path, formData, true);
          }
        }
        handleUnauthorizedRedirect();
        throw new ApiClientError(401, i18n.t('api.sessionExpired'));
      }

      const rawError = (data && (data.error || data.message)) || '';
      const mappedKey = resolveApiErrorKey(401, rawError);
      const message = mappedKey ? i18n.t(mappedKey) : (rawError || i18n.t('api.unauthorized'));

      throw new ApiClientError(401, message, data && data.details);
    }

    if (!response.ok) {
      const rawError = (data && (data.message || data.error)) || '';
      const mappedKey = resolveApiErrorKey(response.status, rawError);
      const message = mappedKey ? i18n.t(mappedKey) : (rawError || i18n.t('api.uploadFailed'));

      throw new ApiClientError(
        response.status,
        message,
        data && data.details
      );
    }

    return data as T;
  },
};
