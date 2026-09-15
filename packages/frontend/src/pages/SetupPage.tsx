import { useState, type FormEvent } from 'react';
import { api, ApiClientError } from '../api/client';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../components/ui/card';

export function SetupPage({ onComplete }: { onComplete: () => void }) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true);
    try {
      await api.post('/auth/setup', { username, displayName, password });
      onComplete();
    } catch (reason) {
      setError(reason instanceof ApiClientError ? reason.message : 'Não foi possível concluir a configuração inicial.');
    } finally { setLoading(false); }
  }

  return <main className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
    <Card className="w-full max-w-sm">
      <CardHeader><CardTitle>Configuração inicial</CardTitle><CardDescription>Crie o primeiro administrador. Esta etapa será encerrada após a criação.</CardDescription></CardHeader>
      <CardContent><form onSubmit={submit} className="space-y-4">
        {error && <div role="alert" className="p-3 text-sm text-red-700 bg-red-50 rounded-md">{error}</div>}
        <label className="block text-sm font-medium" htmlFor="setup-name">Nome</label>
        <Input id="setup-name" value={displayName} onChange={e => setDisplayName(e.target.value)} required autoComplete="name" />
        <label className="block text-sm font-medium" htmlFor="setup-user">Usuário</label>
        <Input id="setup-user" value={username} onChange={e => setUsername(e.target.value)} required autoComplete="username" />
        <label className="block text-sm font-medium" htmlFor="setup-password">Senha</label>
        <Input id="setup-password" type="password" value={password} onChange={e => setPassword(e.target.value)} minLength={8} required autoComplete="new-password" />
        <Button className="w-full" type="submit" disabled={loading}>{loading ? 'Criando…' : 'Criar administrador'}</Button>
      </form></CardContent>
    </Card>
  </main>;
}
