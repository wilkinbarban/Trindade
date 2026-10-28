import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import { saoPauloToday } from '../lib/date';
import {
  aeroSettingsReady,
  aeroLaunchEnvelope,
  aeroShortcutTriggerUrl,
  buildEntradaItemPayload,
  isAeroAllowed,
  loadAeroSettings,
  parseAeroCallback,
  saveAeroSettings,
  type AeroSettings,
} from '../lib/aero-integration';
import { postAeroAuditEvent } from '../lib/aero-audit';
import { clearAeroQueue, readAeroQueue, takeNextAeroItem, writeAeroQueue, type AeroQueue } from '../lib/aero-queue';
import { AeroIntegrationPanel } from './massas/AeroIntegrationPanel';

export function MassasLaunchPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saoPauloToday());
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

  // Company integration: kept out of the tree entirely for anyone who is not the owner admin.
  const aeroAllowed = isAeroAllowed(user ? { username: user.username, role: user.role } : null);
  const [aeroSettings, setAeroSettings] = useState<AeroSettings>(loadAeroSettings);
  const [syncToErp, setSyncToErp] = useState(() => aeroSettings.syncEnabled && aeroSettingsReady(aeroSettings));
  const [syncAwaiting, setSyncAwaiting] = useState<{ date: string; remaining: number } | null>(() => {
    const queue = readAeroQueue();
    return queue ? { date: queue.date, remaining: queue.items.length } : null;
  });
  const aeroCallbackHandled = useRef(false);

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

      // The entry is already Trindade's before the company hears about it: whatever happens in the
      // Atajos app, the day's production is on record.
      const syncable =
        aeroAllowed && syncToErp && aeroSettingsReady(aeroSettings) && aeroSettings.userCode !== null;
      const requested = itemsToConfirm.map((i) => ({
        productCode: i.product_code,
        productName: i.name,
        quantity: aeroSettings.quantityUnit === 'CX' ? i.boxes : i.units,
      }));

      setQuantities({});
      await load(date);

      if (syncable) {
        const queue: AeroQueue = { createdAt: new Date().toISOString(), date, items: requested };
        writeAeroQueue(queue);
        triggerAeroSync(queue);
        return;
      }

      setResultModal({
        type: 'success',
        message: t('massas.launchSuccess', 'Lançamento registrado com sucesso!'),
      });
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

  /**
   * Hands the next pending entry to the Atajos app.
   *
   * One product per request is a contract detail of `/entradasitem`, and the page can only open one URL,
   * so the queue drains one entry per return trip. The navigation happens last: everything that must
   * survive the trip -- the log line, the remaining queue -- is written before Safari leaves.
   */
  const triggerAeroSync = useCallback(
    (queue: AeroQueue) => {
      const next = takeNextAeroItem(queue);
      if (!next) {
        clearAeroQueue();
        setSyncAwaiting(null);
        return;
      }
      writeAeroQueue(next.rest);
      setSyncAwaiting({ date: queue.date, remaining: next.rest.items.length });

      const payload = buildEntradaItemPayload({
        productCode: next.item.productCode,
        productName: next.item.productName,
        quantity: next.item.quantity,
        entryDate: queue.date,
        userCode: aeroSettings.userCode ?? 0,
        entrySeries: aeroSettings.entrySeries,
      });
      const envelope = aeroLaunchEnvelope({
        wsUrl: aeroSettings.wsUrl,
        username: aeroSettings.username,
        password: aeroSettings.password,
        payload,
      });

      void postAeroAuditEvent({
        event: 'launch_sync',
        phase: 'requested',
        destinationUrl: envelope.url,
        payload,
        note: next.rest.items.length > 0 ? `${next.rest.items.length} pending` : 'last entry',
      });

      window.location.href = aeroShortcutTriggerUrl({ name: aeroSettings.shortcutName, envelope });
    },
    [aeroSettings],
  );

  const aeroDestination = useCallback(
    () => (aeroSettings.wsUrl ? `${aeroSettings.wsUrl.replace(/\/$/, '')}/entradasitem` : ''),
    [aeroSettings.wsUrl],
  );

  /**
   * Records what the operator says happened when the shortcut never called back.
   *
   * A cancelled shortcut, a wrong IP in the Atajos app and a genuine ERP refusal all look identical from
   * the page. Rather than invent a verdict, the page asks, and the answer goes to the same log line the
   * automatic path writes.
   */
  const handleSyncManualResult = async (ok: boolean) => {
    await postAeroAuditEvent({
      event: 'launch_sync',
      phase: 'result',
      destinationUrl: aeroDestination(),
      httpStatus: ok ? 200 : null,
      note: ok ? 'manual_confirmation:ok' : 'manual_confirmation:failed',
      error: ok ? null : 'manual_confirmation',
    });
    clearAeroQueue();
    setSyncAwaiting(null);
    setNotice({ type: ok ? 'success' : 'error', message: t('massas.aero.syncRecorded') });
  };

  const handleToggleSync = (next: boolean) => {
    if (!next) {
      clearAeroQueue();
      setSyncAwaiting(null);
    }
    setSyncToErp(next);
    setAeroSettings(saveAeroSettings({ ...aeroSettings, syncEnabled: next }));
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

  /**
   * The shortcut's way back.
   *
   * The Atajos app finishes by opening this page with `aero_sync` in the query string, which is the only
   * channel a native app has to say "the ERP answered". The parameters are consumed and stripped, so a
   * pull-to-refresh in the factory never replays a confirmation.
   */
  useEffect(() => {
    if (aeroCallbackHandled.current) return;
    const parsed = parseAeroCallback(window.location.search);
    if (!parsed) return;
    aeroCallbackHandled.current = true;
    window.history.replaceState({}, '', `${window.location.pathname}${window.location.hash}`);

    const queue = readAeroQueue();
    const destination = aeroDestination();

    void postAeroAuditEvent({
      event: 'launch_sync',
      phase: 'result',
      destinationUrl: destination,
      httpStatus: parsed.status === 'ok' ? 200 : null,
      response: parsed.message,
      error: parsed.status === 'error' ? parsed.message ?? 'shortcut_error' : null,
    });

    if (parsed.status === 'ok' && queue) {
      triggerAeroSync(queue);
      return;
    }

    clearAeroQueue();
    setSyncAwaiting(null);
    setResultModal(
      parsed.status === 'ok'
        ? { type: 'success', message: parsed.message ?? t('massas.aero.syncConfirmYes') }
        : { type: 'error', message: parsed.message ?? t('massas.aero.syncConfirmNo') },
    );
  }, [aeroDestination, t, triggerAeroSync]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto space-y-8 pb-12">
      {/* Encabezado y Selección de Fecha. Sin el emoji: en un teléfono la placa del espagueti y el
          botón se llevaban el ancho que el título necesitaba. Debajo de `sm` las acciones bajan a su
          propio renglón y la fecha sigue alcanzable con el pulgar. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 bg-white p-6 rounded-xl border border-gray-200 shadow-sm">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">{t('massas.title')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.massasLaunch.title')}
              subtitle={t('moduleHelp.massasLaunch.subtitle')}
              description={t('moduleHelp.massasLaunch.description')}
              sections={t('moduleHelp.massasLaunch.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.subtitle')}</p>
        </div>
        {/* La fecha y el acceso al histórico van lado a lado también en un teléfono: apilados a todo
            el ancho se estiraban como dos bloques y empujaban la grilla fuera de la pantalla. */}
        <div className="grid grid-cols-2 items-end gap-2 w-full sm:flex sm:items-center sm:gap-2 sm:w-auto shrink-0">
          <div className="w-full sm:w-auto">
            <label htmlFor="massa-date" className="block text-xs font-medium text-gray-600 sm:sr-only">
              {t('massas.date')}:
            </label>
            <Input
              id="massa-date"
              type="date"
              max={saoPauloToday()}
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="h-11 w-full px-2.5 text-sm font-medium sm:w-auto"
              data-testid="massas-launch-date"
            />
          </div>
          {/* Un enlace que envuelve un botón deja dos destinos anidados en el mismo punto: el lector de
              pantalla anuncia dos y el dedo no sabe cuál tocó. El destino vive en el enlace. */}
          <Link
            to="/massas-history"
            className="inline-flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-lg border border-gray-300 bg-white px-2.5 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 sm:w-auto sm:min-h-9"
          >
            <History className="h-4 w-4 shrink-0" />
            <span>{t('massas.viewHistory')}</span>
          </Link>
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
          erpControl={
            aeroAllowed ? (
              <div className="flex items-center justify-between gap-3 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 sm:py-1">
                <div className="min-w-0">
                  <span id="aero-sync-label" className="block text-xs font-semibold text-gray-800 leading-tight">
                    {t('massas.aero.syncLabel')}
                  </span>
                  <span className="block text-[11px] text-gray-500 leading-tight">
                    {aeroSettingsReady(aeroSettings) ? t('massas.aero.syncHint') : t('massas.aero.syncNeedsSetup')}
                  </span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={syncToErp}
                  aria-labelledby="aero-sync-label"
                  onClick={() => handleToggleSync(!syncToErp)}
                  disabled={!aeroSettingsReady(aeroSettings)}
                  className="inline-flex h-11 w-14 shrink-0 items-center justify-center rounded-full disabled:opacity-40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
                  data-testid="aero-sync-switch"
                >
                  <span
                    className={`relative flex h-7 w-12 items-center rounded-full transition-colors ${
                      syncToErp ? 'bg-amber-600' : 'bg-gray-300'
                    }`}
                  >
                    <span
                      className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                        syncToErp ? 'translate-x-6' : 'translate-x-1'
                      }`}
                    />
                  </span>
                </button>
              </div>
            ) : undefined
          }
        />
      )}

      {/* Company integration: settings and the shortcut recipe, only for the owner admin. */}
      {aeroAllowed && (
        <AeroIntegrationPanel settings={aeroSettings} onSettingsSaved={setAeroSettings} />
      )}

      {/* The shortcut does not always call back; the operator's answer is the fallback verdict. */}
      {aeroAllowed && syncAwaiting && (
        <div role="status" className="rounded-xl border border-amber-200 bg-amber-50 p-4 space-y-3">
          <div>
            <p className="text-sm font-semibold text-amber-900">{t('massas.aero.syncConfirmTitle')}</p>
            <p className="text-xs text-amber-800 mt-0.5">
              {t('massas.aero.syncConfirmHint')}
              {syncAwaiting.remaining > 0 && ` · ${syncAwaiting.remaining}`}
            </p>
          </div>
          <div className="flex flex-col sm:flex-row gap-2">
            <Button
              type="button"
              onClick={() => void handleSyncManualResult(true)}
              className="min-h-[44px] w-full sm:w-auto bg-green-600 hover:bg-green-700 text-white font-semibold"
            >
              {t('massas.aero.syncConfirmYes')}
            </Button>
            <Button
              type="button"
              onClick={() => void handleSyncManualResult(false)}
              variant="outline"
              className="min-h-[44px] w-full sm:w-auto font-semibold text-red-700"
            >
              {t('massas.aero.syncConfirmNo')}
            </Button>
          </div>
        </div>
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
                <div key={item.product_code} className="flex items-center justify-between gap-2 text-sm">
                  <span className="min-w-0 font-medium text-gray-800">📦 {item.name}</span>
                  <span className="shrink-0 text-right font-bold text-amber-800">{item.boxes} cx ({item.units} un)</span>
                </div>
              ))}
            </div>

            <div className="pt-2 border-t flex justify-between items-center text-sm">
              <span className="font-semibold text-gray-700">{t('massas.totalToLaunch', 'Total Geral a Lançar')}:</span>
              <span className="font-extrabold text-base text-amber-900">
                {itemsToConfirm.reduce((sum, i) => sum + i.boxes, 0)} cx ({itemsToConfirm.reduce((sum, i) => sum + i.units, 0)} un)
              </span>
            </div>

            <div className="flex flex-col-reverse gap-3 pt-3 sm:flex-row sm:justify-end">
              <Button
                variant="outline"
                onClick={() => setIsConfirmOpen(false)}
                disabled={submitting}
                className="min-h-[44px] w-full sm:w-auto"
              >
                {t('massas.cancel', 'Cancelar')}
              </Button>
              <Button
                onClick={handleConfirmLaunch}
                disabled={submitting}
                className="min-h-[44px] w-full bg-amber-600 font-semibold text-white hover:bg-amber-700 sm:w-auto"
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
