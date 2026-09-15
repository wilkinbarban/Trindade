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
      throw new ApiClientError(401, 'Sessão expirada. Entre novamente para continuar.');
    }

    throw new ApiClientError(401, (data && data.error) || 'Unauthorized', data && data.details);
  }

  if (!response.ok) {
    throw new ApiClientError(
      response.status,
      (data && data.error) || 'Request failed',
      data && data.details
    );
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
        throw new ApiClientError(401, 'Sessão expirada. Entre novamente para continuar.');
      }

      throw new ApiClientError(401, (data && data.error) || 'Unauthorized', data && data.details);
    }

    if (!response.ok) {
      throw new ApiClientError(
        response.status,
        (data && data.error) || 'Upload failed',
        data && data.details
      );
    }

    return data as T;
  },
};
