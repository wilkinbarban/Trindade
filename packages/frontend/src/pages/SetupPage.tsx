import { useState } from 'react';
import { api, ApiClientError } from '../api/client';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../components/ui/card';

export function SetupPage({ onComplete }: { onComplete: () => void }) {
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function verify() {
    setError(''); setLoading(true);
    try {
      const status = await api.get<{ setupRequired: boolean }>('/auth/setup/status');
      if (status.setupRequired) {
        setError('A configuração ainda não foi concluída. Solicite ao operador que crie o administrador.');
      } else {
        onComplete();
      }
    } catch (reason) {
      setError(reason instanceof ApiClientError ? reason.message : 'Não foi possível verificar a configuração. Tente novamente.');
    } finally { setLoading(false); }
  }

  return <main className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
    <Card className="w-full max-w-sm">
      <CardHeader><CardTitle>Configuração inicial</CardTitle><CardDescription>O primeiro administrador deve ser criado pelo operador, fora deste site. Entre em contato com o responsável pela instalação; não informe senhas nesta página.</CardDescription></CardHeader>
      <CardContent className="space-y-4">
        {error && <div role="alert" className="p-3 text-sm text-red-700 bg-red-50 rounded-md">{error}</div>}
        <Button className="w-full" type="button" onClick={() => void verify()} disabled={loading}>{loading ? 'Verificando…' : 'Verificar configuração'}</Button>
      </CardContent>
    </Card>
  </main>;
}
