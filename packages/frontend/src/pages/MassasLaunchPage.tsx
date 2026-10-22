import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import {
  massasMessage,
  saopauloToday,
  POLL_INTERVAL_MS,
  shouldPollMassas,
  type MassasLaunchDayView,
  type MassaIndividualLaunch,
} from '../lib/massas';
import { History } from 'lucide-react';
import { Dialog } from '../components/ui/dialog';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { MassasLaunchGrid } from './massas/MassasLaunchGrid';
import { MassasCollectiveTable } from './massas/MassasCollectiveTable';
import { MassasWhatsAppShare } from './massas/MassasWhatsAppShare';

export function MassasLaunchPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saopauloToday());
  const [dayView, setDayView] = useState<MassasLaunchDayView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // Block 1 state: quantities per product code
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const [itemsToConfirm, setItemsToConfirm] = useState<
    Array<{ product_code: string; name: string; boxes: number; units: number }>
  >([]);
  const [resultModal, setResultModal] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // Block 2 state: inline editing of an individual launch
  const [editingLaunchId, setEditingLaunchId] = useState<number | null>(null);
  const [editingBoxes, setEditingBoxes] = useState('');
  const [editingBusy, setEditingBusy] = useState(false);
  const [deletingLaunchId, setDeletingLaunchId] = useState<number | null>(null);

  // Block 3 state: WhatsApp phone input
  const [phone, setPhone] = useState('');
  const [copyNote, setCopyNote] = useState('');

  const load = useCallback(
    async (targetDate: string, isSilent = false) => {
      if (!isSilent) {
        setError('');
      }
      try {
        const loaded = await api.get<MassasLaunchDayView>(`/massas-launch?date=${targetDate}`);
        setDayView(loaded);
      } catch (err) {
        if (!isSilent) {
          setError(err instanceof Error ? err.message : t('massas.loadError'));
        }
      } finally {
        if (!isSilent) {
          setLoading(false);
        }
      }
    },
    [t]
  );

  // Initial load or date switch
  useEffect(() => {
    void load(date);
  }, [date, load]);

  // Passive live-reload polling every 15s with visibility and interaction guards
  useEffect(() => {
    const triggerSilentPoll = () => {
      const visibilityState = typeof document !== 'undefined' ? document.visibilityState : 'visible';
      const isEditing = editingLaunchId !== null;
      if (shouldPollMassas({ visibilityState, isEditing, isSubmitting: submitting })) {
        void load(date, true);
      }
    };

    const intervalId = window.setInterval(triggerSilentPoll, POLL_INTERVAL_MS);

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        triggerSilentPoll();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      window.clearInterval(intervalId);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [date, load, editingLaunchId, submitting]);

  const handleQuantityChange = (code: string, rawVal: string) => {
    const cleaned = rawVal.replace(/\D/g, '');
    setQuantities((prev) => ({ ...prev, [code]: cleaned }));
  };

  const handleRequestLaunch = () => {
    if (!dayView || submitting) return;
    const items = dayView.products
      .map((p) => {
        const boxes = parseInt(quantities[p.code] || '0', 10);
        return {
          product_code: p.code,
          name: p.name,
          boxes,
          units: boxes * p.unitsPerBox,
        };
      })
      .filter((i) => i.boxes > 0);

    if (items.length === 0) return;
    setItemsToConfirm(items);
    setIsConfirmOpen(true);
  };

  const handleConfirmLaunch = async () => {
    if (!dayView || submitting || itemsToConfirm.length === 0) return;
    setSubmitting(true);
    setIsConfirmOpen(false);
    try {
      await api.post('/massas-launch', {
        date,
        items: itemsToConfirm.map((i) => ({ product_code: i.product_code, boxes: i.boxes })),
      });
      setQuantities({});
      setResultModal({
        type: 'success',
        message: t('massas.launchSuccess', 'Lançamento registrado com sucesso!'),
      });
      await load(date);
    } catch (err) {
      setResultModal({
        type: 'error',
        message: err instanceof Error ? err.message : t('massas.launchError', 'Erro ao registrar lançamento.'),
      });
      // Do not clear quantities on error so the operator can retry
    } finally {
      setSubmitting(false);
    }
  };

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
      await load(date);
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
      await load(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.deleteError') });
    } finally {
      setDeletingLaunchId(null);
    }
  };

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
      {/* Top Title & Date Selection */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white p-6 rounded-xl border border-gray-200 shadow-sm">
        <div>
          <div className="flex items-center gap-2">
            <span className="p-2 bg-amber-100 text-amber-800 rounded-lg text-xl flex items-center justify-center w-10 h-10 select-none">
              🍝
            </span>
            <h1 className="text-2xl font-bold text-gray-900">{t('massas.title')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.massasLaunch.title')}
              subtitle={t('moduleHelp.massasLaunch.subtitle')}
              description={t('moduleHelp.massasLaunch.description')}
              sections={t('moduleHelp.massasLaunch.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2 w-full sm:w-auto">
          <Link to="/massas-history" className="flex-1 sm:flex-initial">
            <Button variant="outline" className="w-full sm:w-auto flex items-center justify-center gap-1.5 text-xs font-semibold px-2.5">
              <History className="h-4 w-4 shrink-0" />
              <span className="truncate">{t('massas.viewHistory')}</span>
            </Button>
          </Link>
          <div className="flex items-center gap-1.5 flex-1 sm:flex-initial">
            <label htmlFor="massa-date" className="sr-only sm:not-sr-only text-sm font-medium text-gray-700 whitespace-nowrap">
              {t('massas.date')}:
            </label>
            <Input
              id="massa-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="w-full sm:w-auto font-medium text-xs sm:text-sm px-2.5"
            />
          </div>
        </div>
      </div>

      {/* Global Notices */}
      {error && (
        <div role="alert" className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
          {error}
        </div>
      )}
      {notice && (
        <div
          role="status"
          className={`p-4 rounded-lg border ${
            notice.type === 'success'
              ? 'bg-green-50 text-green-800 border-green-200'
              : 'bg-red-50 text-red-800 border-red-200'
          }`}
        >
          {notice.message}
        </div>
      )}

      {/* BLOQUE 1 (Superior): Formulario / Grilla de 10 Productos SIN botón resetear */}
      {dayView && (
        <MassasLaunchGrid
          products={dayView.products}
          quantities={quantities}
          onQuantityChange={handleQuantityChange}
          onLaunch={handleRequestLaunch}
          submitting={submitting}
          date={date}
        />
      )}

      {/* BLOQUE 2 (Intermedio): Resumen Colectivo y Desglose RBAC */}
      {dayView && (
        <MassasCollectiveTable
          products={dayView.products}
          launches={dayView.launches}
          currentUserId={user?.id}
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

      {/* BLOQUE 3 (Inferior): Compartir por WhatsApp con máscara y unidades totales */}
      <MassasWhatsAppShare
        phone={phone}
        onPhoneChange={setPhone}
        previewMessage={previewMessage}
        onCopy={handleCopyReport}
        onOpenWhatsApp={handleOpenWhatsApp}
        copyNote={copyNote}
      />

      {/* Modal de Confirmação Prévio ao Lançamento */}
      {isConfirmOpen && (
        <Dialog
          open={isConfirmOpen}
          onClose={() => setIsConfirmOpen(false)}
          labelledBy="massa-confirm-title"
          describedBy="massa-confirm-desc"
          panelTestId="massa-confirm-modal"
        >
          <div className="p-6 space-y-4 max-w-md w-full">
            <h2 id="massa-confirm-title" className="text-lg font-bold text-gray-900 flex items-center gap-2">
              <span className="text-xl">🍝</span>
              <span>{t('massas.confirmTitle', 'Confirmar Lançamento de Massas')}</span>
            </h2>
            <p id="massa-confirm-desc" className="text-xs text-gray-500">
              {t('massas.confirmSubtitle', 'Verifique os produtos e caixas antes de confirmar o lançamento:')}
            </p>

            <div className="max-h-60 overflow-y-auto space-y-2 border rounded-lg p-3 bg-gray-50">
              {itemsToConfirm.map((item) => (
                <div key={item.product_code} className="flex justify-between items-center text-sm">
                  <span className="font-medium text-gray-800">📦 {item.name}</span>
                  <span className="font-bold text-amber-800">{item.boxes} cx ({item.units} un)</span>
                </div>
              ))}
            </div>

            <div className="pt-2 border-t flex justify-between items-center text-sm">
              <span className="font-semibold text-gray-700">{t('massas.totalToLaunch', 'Total Geral a Lançar')}:</span>
              <span className="font-extrabold text-base text-amber-900">
                {itemsToConfirm.reduce((sum, i) => sum + i.boxes, 0)} cx ({itemsToConfirm.reduce((sum, i) => sum + i.units, 0)} un)
              </span>
            </div>

            <div className="flex justify-end gap-3 pt-3">
              <Button
                variant="outline"
                onClick={() => setIsConfirmOpen(false)}
                disabled={submitting}
              >
                {t('massas.cancel', 'Cancelar')}
              </Button>
              <Button
                onClick={handleConfirmLaunch}
                disabled={submitting}
                className="bg-amber-600 hover:bg-amber-700 text-white font-semibold"
              >
                {submitting ? t('massas.submitting', 'Lançando...') : t('massas.confirmAction', 'Confirmar')}
              </Button>
            </div>
          </div>
        </Dialog>
      )}

      {/* Diálogo Modal de Notificação de Resultado */}
      {resultModal && (
        <Dialog
          open={resultModal !== null}
          onClose={() => setResultModal(null)}
          labelledBy="massa-result-title"
          panelTestId="massa-result-modal"
        >
          <div className="p-6 space-y-4 max-w-sm w-full text-center">
            <div className="text-4xl">
              {resultModal.type === 'success' ? '✅' : '❌'}
            </div>
            <h2 id="massa-result-title" className="text-lg font-bold text-gray-900">
              {resultModal.type === 'success'
                ? t('massas.successTitle', 'Lançamento Concluído')
                : t('massas.errorTitle', 'Erro no Lançamento')}
            </h2>
            <p className="text-sm text-gray-600">
              {resultModal.message}
            </p>
            <div className="pt-2">
              <Button
                onClick={() => setResultModal(null)}
                className={`w-full font-semibold ${
                  resultModal.type === 'success'
                    ? 'bg-green-600 hover:bg-green-700 text-white'
                    : 'bg-red-600 hover:bg-red-700 text-white'
                }`}
              >
                {t('massas.close', 'Fechar')}
              </Button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  );
}
