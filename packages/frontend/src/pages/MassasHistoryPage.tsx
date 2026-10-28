import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import { massasMessage, type MassasLaunchDayView } from '../lib/massas';
import { ArrowLeft } from 'lucide-react';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { MassasCollectiveTable } from './massas/MassasCollectiveTable';
import { MassasWhatsAppShare } from './massas/MassasWhatsAppShare';
import { saoPauloDaysAgo, saoPauloYesterday } from '../lib/date';

/**
 * A past day of launching, read and shared but not edited.
 *
 * The newest day it will open is yesterday: today is still being launched, and that is the launch
 * screen's job. With that limit in place no day here is ever editable, so the editing handlers this
 * screen used to carry are gone rather than left wired to buttons nobody can reach.
 */
export function MassasHistoryPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saoPauloYesterday());
  const [dayView, setDayView] = useState<MassasLaunchDayView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // WhatsApp state
  const [phone, setPhone] = useState('');
  const [copyNote, setCopyNote] = useState('');

  const loadDay = useCallback(
    async (targetDate: string) => {
      setError('');
      try {
        const loaded = await api.get<MassasLaunchDayView>(`/massas-launch?date=${targetDate}`);
        setDayView(loaded);
      } catch (err) {
        setError(err instanceof Error ? err.message : t('massas.loadError'));
      } finally {
        setLoading(false);
      }
    },
    [t]
  );

  useEffect(() => {
    void loadDay(date);
  }, [date, loadDay]);

  const previewMessage = useMemo(() => {
    if (!dayView) return '';
    return massasMessage(dayView);
  }, [dayView]);

  const handleCopyReport = async () => {
    if (!previewMessage) return;
    setCopyNote('');
    const outcome = await shareOrCopy(previewMessage);
    if (outcome === 'copied' || outcome === 'shared') {
      setCopyNote(t('massas.copied'));
    }
  };

  const handleOpenWhatsApp = () => {
    if (!previewMessage) return;
    const formattedNumber = toWhatsAppNumber(phone);
    if (formattedNumber) {
      const link = waMeLink(formattedNumber, previewMessage);
      if (link) window.open(link, '_blank', 'noopener,noreferrer');
    } else {
      void shareOrCopy(previewMessage);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto space-y-8 pb-12">
      {/* Encabezado y Enlace Cruzado a Hoy. Sin la placa del reloj: en un teléfono se llevaba el
          ancho que el título necesitaba. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 bg-white p-6 rounded-xl border border-gray-200 shadow-sm">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            {/* Un escalón menos en el teléfono: el título entra en dos renglones y el encabezado deja de ser media pantalla. */}
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">{t('massas.historyTitle')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.massasHistory.title')}
              subtitle={t('moduleHelp.massasHistory.subtitle')}
              description={t('moduleHelp.massasHistory.description')}
              sections={t('moduleHelp.massasHistory.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.historySubtitle')}</p>
        </div>
        {/* Misma fila que en el lanzamiento, y por el mismo motivo: el campo de fecha de Safari no cabe
            en media pantalla de iPhone y el botón se le montaba encima. Apilados en el teléfono, en fila
            desde `sm`. Sin los botones de días con lanzamientos ni los carteles de "Hoje"/"Modo Leitura":
            el rango ya dice cuál es el día más nuevo. */}
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-end sm:shrink-0">
          <div className="w-full sm:w-auto">
            <label htmlFor="history-date" className="block text-xs font-medium text-gray-600 sm:sr-only">
              {t('massas.date')}:
            </label>
            <Input
              id="history-date"
              type="date"
              min={saoPauloDaysAgo(30)}
              max={saoPauloYesterday()}
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="w-full sm:w-auto font-medium"
              data-testid="massas-history-date"
            />
          </div>
          <Link to="/massas-launch" className="w-full sm:w-auto">
            <Button variant="outline" className="w-full sm:w-auto flex items-center justify-center gap-2 text-sm font-semibold">
              <ArrowLeft className="h-4 w-4" />
              {t('massas.backToToday')}
            </Button>
          </Link>
        </div>
      </div>

      {/* Avisos Globales */}
      {error && (
        <div role="alert" className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
          {error}
        </div>
      )}
      {/* Resumen Colectivo y Desglose por Operador */}
      {dayView && (
        <MassasCollectiveTable
          products={dayView.products}
          launches={dayView.launches}
          currentUserId={user?.id}
          readOnly
        />
      )}

      {/* Bloque WhatsApp con Preview Actualizado */}
      <MassasWhatsAppShare
        phone={phone}
        onPhoneChange={setPhone}
        previewMessage={previewMessage}
        onCopy={handleCopyReport}
        onOpenWhatsApp={handleOpenWhatsApp}
        copyNote={copyNote}
      />
    </div>
  );
}
