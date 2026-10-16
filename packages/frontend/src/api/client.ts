import i18n from '../i18n/config';
import { GUEST_READONLY_MESSAGE, isGuestMutationRefusal } from '../lib/guest-readonly';

const APP_BASE = import.meta.env.BASE_URL.replace(/\/$/, '');
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

export function clearStoredSession(): void {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('auth_user');
}

export function handleUnauthorizedRedirect(): void {
  clearStoredSession();
  markSessionExpiredNotice();
  if (window.location.pathname !== appUrl('/login')) {
    window.location.href = appUrl('/login');
  }
}

function isLoginRequest(path: string): boolean {
  return path === '/auth/login' || path === '/api/auth/login';
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
  options: RequestInit = {}
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

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(apiUrl(path), {
    ...options,
    headers,
  });

  let data: any = null;
  const contentType = response.headers.get('content-type');
  if (response.status !== 204 && contentType && contentType.includes('application/json')) {
    data = await response.json();
  }

  if (response.status === 401) {
    if (token && !isLoginRequest(path)) {
      handleUnauthorizedRedirect();
      throw new ApiClientError(401, i18n.t('api.sessionExpired'));
    }

    throw new ApiClientError(401, (data && data.error) || i18n.t('api.unauthorized'), data && data.details);
  }

  if (!response.ok) {
    // Las dos claves: la guarda del invitado responde { message } y el resto de la API responde { error }.
    const message = (data && (data.message || data.error)) || i18n.t('api.requestFailed');

    // El invitado es de solo lectura: se reconoce ESE rechazo -- y solo ese -- para mostrarle el aviso
    // informativo. Cualquier otro error sigue el camino de siempre.
    if (isGuestMutationRefusal(response.status, message)) {
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
  async upload<T>(path: string, formData: FormData): Promise<T> {
    const token = localStorage.getItem('auth_token');
    const lang = localStorage.getItem('i18nextLng') || 'pt-BR';

    const headers: Record<string, string> = {
      'Accept-Language': lang,
    };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    // Do NOT set Content-Type — browser adds multipart boundary

    const response = await fetch(apiUrl(path), {
      method: 'POST',
      headers,
      body: formData,
    });

    let data: any = null;
    const contentType = response.headers.get('content-type');
    if (response.status !== 204 && contentType && contentType.includes('application/json')) {
      data = await response.json();
    }

    if (response.status === 401) {
      if (token) {
        handleUnauthorizedRedirect();
        throw new ApiClientError(401, i18n.t('api.sessionExpired'));
      }

      throw new ApiClientError(401, (data && data.error) || i18n.t('api.unauthorized'), data && data.details);
    }

    if (!response.ok) {
      throw new ApiClientError(
        response.status,
        (data && data.error) || i18n.t('api.uploadFailed'),
        data && data.details
      );
    }

    return data as T;
  },
};
