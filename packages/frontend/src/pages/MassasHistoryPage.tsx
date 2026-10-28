import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Card, CardContent } from '../components/ui/card';
import { DateField } from '../components/ui/date-field';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import { massasMessage, type MassasLaunchDayView, type MassaIndividualLaunch } from '../lib/massas';
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
  const isAdmin = user?.role === 'Administrador';
  const [date, setDate] = useState(saoPauloYesterday());
  const [dayView, setDayView] = useState<MassasLaunchDayView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // Edit / Delete state for Administrador
  const [editingLaunchId, setEditingLaunchId] = useState<number | null>(null);
  const [editingBoxes, setEditingBoxes] = useState('');
  const [editingBusy, setEditingBusy] = useState(false);
  const [deletingLaunchId, setDeletingLaunchId] = useState<number | null>(null);

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

  const startEditing = (launch: MassaIndividualLaunch) => {
    setEditingLaunchId(launch.id);
    setEditingBoxes(String(launch.boxes));
  };

  const cancelEditing = () => {
    setEditingLaunchId(null);
    setEditingBoxes('');
  };

  const saveEditing = async (launchId: number) => {
    const boxesNum = parseInt(editingBoxes, 10);
    if (!Number.isInteger(boxesNum) || boxesNum <= 0 || editingBusy) return;
    setEditingBusy(true);
    setNotice(null);
    try {
      await api.put(`/massas-launch/${launchId}`, { boxes: boxesNum });
      setEditingLaunchId(null);
      setEditingBoxes('');
      setNotice({ type: 'success', message: t('massas.updateSuccess') });
      await loadDay(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.updateError') });
    } finally {
      setEditingBusy(false);
    }
  };

  const handleDeleteLaunch = async (launchId: number) => {
    if (!window.confirm(t('massas.deleteConfirm'))) return;
    setDeletingLaunchId(launchId);
    setNotice(null);
    try {
      await api.del(`/massas-launch/${launchId}`);
      setNotice({ type: 'success', message: t('massas.deleteSuccess') });
      await loadDay(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.deleteError') });
    } finally {
      setDeletingLaunchId(null);
    }
  };

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
    <div className="max-w-6xl mx-auto space-y-4 pb-12 sm:space-y-6">
      {/* Encabezado y Enlace Cruzado a Hoy. Sin la placa del reloj: en un teléfono se llevaba el
          ancho que el título necesitaba. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 bg-white p-4 rounded-xl border border-gray-200 shadow-sm sm:p-6">
        <div className="min-w-0">
          <div className="flex items-center justify-between gap-2">
            {/* Un escalón menos en el teléfono: el título entra en dos renglones y el encabezado deja de ser media pantalla. */}
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">{t('massas.historyTitle')}</h1>
            <div className="shrink-0">
              <ModuleHelpModal
                title={t('moduleHelp.massasHistory.title')}
                subtitle={t('moduleHelp.massasHistory.subtitle')}
                description={t('moduleHelp.massasHistory.description')}
                sections={t('moduleHelp.massasHistory.sections', { returnObjects: true }) as any}
              />
            </div>
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.historySubtitle')}</p>
        </div>
        {/* Una fila, la misma que en el lanzamiento: `DateField` y la vuelta a hoy a ancho propio, con
            la palabra corta en el teléfono. Sin los botones de días con lanzamientos ni los carteles de
            "Hoje"/"Modo Leitura": el rango ya dice cuál es el día más nuevo. */}
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <DateField
            id="history-date"
            label={`${t('massas.date')}:`}
            value={date}
            onChange={setDate}
            min={saoPauloDaysAgo(30)}
            max={saoPauloYesterday()}
            testId="massas-history-date"
          />
          <Link
            to="/massas-launch"
            className="inline-flex h-11 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border border-gray-300 bg-white px-3 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 sm:h-10"
          >
            <ArrowLeft className="h-4 w-4 shrink-0" />
            <span className="sm:hidden">{t('massas.todayShort')}</span>
            <span className="hidden sm:inline">{t('massas.backToToday')}</span>
          </Link>
        </div>
      </div>

      {/* Avisos Globales */}
      {error && (
        <div role="alert" className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
          {error}
        </div>
      )}
      {notice && (
        <div
          role="alert"
          className={`p-4 rounded-lg border text-sm font-medium ${
            notice.type === 'success'
              ? 'bg-green-50 border-green-200 text-green-800'
              : 'bg-red-50 border-red-200 text-red-800'
          }`}
        >
          {notice.message}
        </div>
      )}
      {/* Resumen Colectivo y Desglose por Operador */}
      {dayView && (
        <MassasCollectiveTable
          products={dayView.products}
          launches={dayView.launches}
          currentUserId={user?.id}
          isAdmin={isAdmin}
          readOnly={!isAdmin}
          editingLaunchId={editingLaunchId}
          editingBoxes={editingBoxes}
          editingBusy={editingBusy}
          onStartEdit={startEditing}
          onCancelEdit={cancelEditing}
          onSaveEdit={saveEditing}
          onEditBoxesChange={setEditingBoxes}
          onDelete={handleDeleteLaunch}
          deletingId={deletingLaunchId}
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
