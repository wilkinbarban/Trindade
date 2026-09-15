import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from 'react';
import { api, apiUrl, clearStoredSession, markSessionExpiredNotice } from '../api/client';

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
        const response = await fetch(apiUrl('/auth/me'), {
          headers: { Authorization: `Bearer ${storedToken}` },
        });

        if (!response.ok) {
          if (response.status === 401) {
            clearStoredSession();
            markSessionExpiredNotice();
          }
          if (!cancelled) {
            setToken(null);
            setUser(null);
            setIsLoading(false);
          }
          return;
        }

        const data = (await response.json()) as { user: User };
        if (!cancelled) {
          setToken(storedToken);
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
      const response = await api.post<{ token: string; user: User }>(
        '/auth/login',
        { username, password }
      );

      localStorage.setItem('auth_token', response.token);
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
