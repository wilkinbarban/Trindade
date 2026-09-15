import { useState, useEffect, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { ApiClientError, consumeSessionExpiredNotice } from '../api/client';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../components/ui/card';

export function LoginPage() {
  const { t } = useTranslation();
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (consumeSessionExpiredNotice()) {
      setError(t('auth.sessionExpired'));
    }
  }, [t]);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await login(username, password);
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(err.message);
      } else {
        setError(t('auth.error'));
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-sm">
        {/* Branding */}
        <div className="text-center mb-8">
          <img
            src={`${import.meta.env.BASE_URL}logomarca.png.webp`}
            alt={t('app.name')}
            className="mx-auto mb-4 w-full max-w-[280px] h-auto"
          />
          <h1 className="text-2xl font-bold text-gray-800">{t('app.name')}</h1>
          <p className="mt-1 text-gray-500">{t('app.tagline')}</p>
        </div>

        {/* Login form */}
        <Card>
          <CardHeader>
            <CardTitle>{t('auth.signIn')}</CardTitle>
            <CardDescription className="sr-only">{t('auth.signInDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              {error && (
                <div
                  role="alert"
                  aria-live="assertive"
                  className="p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md"
                >
                  {error}
                </div>
              )}

              <div className="space-y-1.5">
                <label
                  htmlFor="username"
                  className="block text-sm font-medium text-gray-700"
                >
                  {t('auth.username')}
                </label>
                <Input
                  id="username"
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  required
                  autoComplete="username"
                  autoFocus
                />
              </div>

              <div className="space-y-1.5">
                <label
                  htmlFor="password"
                  className="block text-sm font-medium text-gray-700"
                >
                  {t('auth.password')}
                </label>
                <Input
                  id="password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete="current-password"
                />
              </div>

              <Button type="submit" disabled={loading} className="w-full">
                {loading ? t('auth.signingIn') : t('auth.signIn')}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
