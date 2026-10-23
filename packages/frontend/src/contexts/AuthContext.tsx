import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from 'react';
import {
  api,
  apiUrl,
  clearStoredSession,
  AUTH_SESSION_REFRESHED_EVENT,
} from '../api/client';

export interface User {
  id: number;
  username: string;
  role: string;
}

export interface AuthState {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<User>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Synchronize token state and localStorage when background session renewal happens
  useEffect(() => {
    const handleRefreshed = (event: Event) => {
      const custom = event as CustomEvent<{ token: string; refreshToken: string }>;
      if (custom.detail?.token) {
        setToken(custom.detail.token);
        if (custom.detail.refreshToken) {
          localStorage.setItem('auth_refresh_token', custom.detail.refreshToken);
        }
      }
    };

    if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
      window.addEventListener(AUTH_SESSION_REFRESHED_EVENT, handleRefreshed);
      return () => {
        window.removeEventListener(AUTH_SESSION_REFRESHED_EVENT, handleRefreshed);
      };
    }
  }, []);

  // Restore session from localStorage on mount
  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      const storedToken = localStorage.getItem('auth_token');
      const storedUser = localStorage.getItem('auth_user');

      if (!storedToken || !storedUser) {
        if (!cancelled) setIsLoading(false);
        return;
      }

      try {
        JSON.parse(storedUser) as User;
      } catch {
        clearStoredSession();
        if (!cancelled) setIsLoading(false);
        return;
      }

      try {
        // api.get transparently renews the session if the access token has expired
        const data = await api.get<{ user: User }>('/auth/me');
        if (!cancelled) {
          setToken(localStorage.getItem('auth_token'));
          setUser(data.user);
          localStorage.setItem('auth_user', JSON.stringify(data.user));
          setIsLoading(false);
        }
      } catch {
        clearStoredSession();
        if (!cancelled) {
          setToken(null);
          setUser(null);
          setIsLoading(false);
        }
      }
    }

    void restoreSession();

    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(
    async (username: string, password: string): Promise<User> => {
      const response = await api.post<{ token: string; refreshToken?: string; user: User }>(
        '/auth/login',
        { username, password }
      );

      localStorage.setItem('auth_token', response.token);
      if (response.refreshToken) {
        localStorage.setItem('auth_refresh_token', response.refreshToken);
      }
      localStorage.setItem('auth_user', JSON.stringify(response.user));
      setToken(response.token);
      setUser(response.user);

      return response.user;
    },
    []
  );

  const logout = useCallback(() => {
    // Try to call the backend logout endpoint (best-effort, fire-and-forget)
    const token = localStorage.getItem('auth_token');
    if (token) {
      fetch(apiUrl('/auth/logout'), {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
      }).catch(() => { /* ignore network errors during logout */ });
    }

    clearStoredSession();
    setToken(null);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, token, isLoading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an <AuthProvider>');
  }
  return ctx;
}
