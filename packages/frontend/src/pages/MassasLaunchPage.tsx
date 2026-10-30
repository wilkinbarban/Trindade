import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api, ApiClientError } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Button } from '../components/ui/button';
import { DateField } from '../components/ui/date-field';
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
import { History, RefreshCw } from 'lucide-react';
import { Dialog } from '../components/ui/dialog';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { MassasLaunchGrid } from './massas/MassasLaunchGrid';
import { MassasCollectiveTable } from './massas/MassasCollectiveTable';
import {
  loadAckedLaunchIds,
  massasUnifiedRows,
  toggleAckedLaunchId,
  type MassasUnifiedRow,
} from '../lib/massas-unified';
import { MassasWhatsAppShare } from './massas/MassasWhatsAppShare';
import { saoPauloToday } from '../lib/date';
import { isClosedDay } from '../lib/massas-day';
import {
  aeroBridgeCallbackFor,
  aeroSettingsReady,
  aeroEndpoint,
  buildAeroBridgeEnvelope,
  buildAeroBridgeTriggerUrl,
  buildAeroMovementsSnapshot,
  buildEntradaItemPayload,
  isAeroAllowed,
  loadAeroSettings,
  parseAeroCallback,
  readAeroBridgeResult,
  readAeroMovements,
  saveAeroSettings,
  type AeroSettings,
} from '../lib/aero-integration';
import { postAeroAuditEvent } from '../lib/aero-audit';
import {
  aeroTestNoVerdictEvent,
  aeroTestResultEvent,
  clearAeroPendingTest,
  loadAeroPendingTest,
  saveAeroTestOutcome,
  type AeroTestOutcome,
} from '../lib/aero-test';
import {
  clearAeroQueue,
  pendingLaunchQueueItem,
  readAeroQueue,
  takeAeroBatch,
  writeAeroQueue,
  type AeroQueue,
} from '../lib/aero-queue';
import { AERO_BATCH_MAX, buildAeroLaunchTrips } from '../lib/aero-batch';
import { formatDateLabel } from '../lib/date';
import { AeroResultDialog } from './massas/AeroResultDialog';
import { AeroDeviceBanner } from '../components/aero/AeroDeviceBanner';
import { useDevice } from '../contexts/DeviceContext';
import { canControlAero } from '../lib/device-detection';
import {
  batchSyncFeedback,
  launchMirrorHint,
  movementsSyncFeedback,
  readAeroBatchResult,
  type AeroSyncFeedback,
} from '../lib/aero-feedback';
import {
  aeroImportPayload,
  aeroMovementsEnvelope,
  lastSyncLabel,
  readAeroMovementsSnapshot,
  type AeroMovementSnapshot,
} from '../lib/aero-mirror';

export function MassasLaunchPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saoPauloToday());
  // Today is the only day that still accepts boxes: yesterday is a record the API refuses to reopen.
  const dayIsClosed = isClosedDay(date, saoPauloToday());
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
  const [resultModal, setResultModal] = useState<{
    type: 'success' | 'warn' | 'error';
    /** Already-translated title, for the dialogs whose sentence is not one of the two generic ones. */
    title?: string;
    message: string;
    /** El lanzamiento viajó a la empresa: el espejo ya tiene un asiento nuevo que mostrar. */
    sentToErp?: boolean;
    /** Products the ERP refused or never answered: the operator can send exactly those again. */
    retryCount?: number;
  } | null>(null);

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
  const [aeroTestOutcome, setAeroTestOutcome] = useState<AeroTestOutcome | null>(null);
  const [syncToErp, setSyncToErp] = useState(() => aeroSettings.syncEnabled && aeroSettingsReady(aeroSettings));
  // El sistema operativo decide si estas acciones pueden mover algo. En una computadora no hay forma de
  // hablar con Aero32: la tabla y el espejo se ven enteros y deshabilitados, con el banner que dice qué
  // dispositivo sí puede.
  const device = useDevice();
  const deviceLimited = !canControlAero(device);
  const [syncAwaiting, setSyncAwaiting] = useState<{ date: string; remaining: number } | null>(() => {
    const queue = readAeroQueue();
    // The indicator counts products, not trips: "3 pendentes" is what the operator launched, and after a
    // partial batch it says how many are still owed to the company.
    return queue ? { date: queue.date, remaining: queue.items.length + queue.inFlight.length } : null;
  });
  const aeroCallbackHandled = useRef(false);

  // The company's side of the day. It lives in the backend so any phone in the plant reads the same
  // snapshot, and it survives a reload that the callback never reaches.
  const [mirror, setMirror] = useState<{
    loading: boolean;
    error: string | null;
    snapshot: AeroMovementSnapshot | null;
  }>({ loading: false, error: null, snapshot: null });
  const [importingEntLan, setImportingEntLan] = useState<number | null>(null);
  /** What this phone already carried to the tablet today; the mark is per device and dies with the day. */
  const [ackedLaunchIds, setAckedLaunchIds] = useState<number[]>(() => loadAckedLaunchIds(date));
  /** Lo que la consulta de movimientos tiene para decir cuando el teléfono vuelve. */
  const [syncResult, setSyncResult] = useState<{
    feedback: AeroSyncFeedback;
    /** What the ERP said when it refused, shown as written instead of paraphrased. */
    detail: string | null;
  } | null>(null);

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

  /**
   * Reads the mirrored day from Trindade's own database.
   *
   * A 503 is not a failure of the request but of the installation: the revision is pending, and the plant
   * must be told to run `db:migrate` instead of reading an empty day as "nothing was produced".
   */
  const loadMirror = useCallback(
    async (targetDate: string) => {
      setMirror((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const body = await api.get<unknown>(`/integration/aero/movements?date=${targetDate}`);
        const snapshot = readAeroMovementsSnapshot(body);
        if (!snapshot) {
          setMirror({ loading: false, error: t('massas.erp.error'), snapshot: null });
          return;
        }
        setMirror({ loading: false, error: null, snapshot });
      } catch (err) {
        const message =
          err instanceof ApiClientError && err.status === 503
            ? t('massas.erp.notMigrated')
            : err instanceof Error
              ? err.message
              : t('massas.erp.error');
        setMirror({ loading: false, error: message, snapshot: null });
      }
    },
    [t],
  );

  // Initial load or date switch
  useEffect(() => {
    void load(date);
  }, [date, load]);

  // The mirror follows the same day as the launch list.
  useEffect(() => {
    void loadMirror(date);
  }, [date, loadMirror]);

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

  /** The panel shows the verdict once and the page forgets it, so a later render cannot replay it. */
  const handleTestOutcomeHandled = useCallback(() => setAeroTestOutcome(null), []);

  /** Where a launch would land, in the log's terms. */
  const aeroDestination = useCallback(
    () => (aeroSettings.wsUrl ? `${aeroSettings.wsUrl.replace(/\/$/, '')}/entradasitem` : ''),
    [aeroSettings.wsUrl],
  );

  const handleRequestLaunch = () => {
    // Defence in depth: the grid hides the button, and the handler refuses anyway, because a closed day
    // reached from a stale render must not become a 409 the operator has to decode.
    if (dayIsClosed) return;
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
      const created = await api.post<{ created: MassaIndividualLaunch[] }>('/massas-launch', {
        date,
        items: itemsToConfirm.map((i) => ({ product_code: i.product_code, boxes: i.boxes })),
      });

      // The entry is already Trindade's before the company hears about it: whatever happens in the
      // Atajos app, the day's production is on record.
      const wantsSync = aeroAllowed && syncToErp;
      const syncable = wantsSync && aeroSettingsReady(aeroSettings);
      const requested = itemsToConfirm.map((i) => ({
        productCode: i.product_code,
        productName: i.name,
        quantity: i.units,
        // Only this trip's launches: the day may already carry earlier ones at the same product.
        launchIds: (created?.created ?? [])
          .filter((launch) => launch.product_code === i.product_code)
          .map((launch) => launch.id),
      }));

      setQuantities({});
      await load(date);

      if (syncable) {
        const queue: AeroQueue = { createdAt: new Date().toISOString(), date, items: requested, inFlight: [] };
        writeAeroQueue(queue);
        triggerAeroSync(queue);
        return;
      }

      if (wantsSync) {
        // The switch said "send it to the company" and the entry stayed home. The operator's launch is safe,
        // but the reason belongs in the log: at this point the configuration itself is what is missing.
        void postAeroAuditEvent({
          event: 'launch_sync',
          phase: 'requested',
          destinationUrl: aeroDestination() || aeroSettings.wsUrl,
          payload: requested,
          error: 'integration not ready',
          note: `${requested.length} not sent`,
        });
      }

      setResultModal({
        type: 'success',
        message: t('massas.launchSuccess', 'Lançamento registrado com sucesso!'),
        sentToErp: false,
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
   * Hands the next batch of pending entries to the Atajos app.
   *
   * One envelope per trip, and one trip per trip: a launch of three products used to be three returns to
   * Safari, and the operator never knew whether the second one had arrived. Up to `AERO_BATCH_MAX` products
   * now travel together and the shortcut repeats the request on the phone. What does not fit keeps waiting in
   * the queue and becomes the next envelope.
   *
   * The navigation happens last: everything that must survive the trip -- the log line, the remaining queue,
   * which products are in the phone -- is written before Safari leaves.
   */
  const triggerAeroSync = useCallback(
    (queue: AeroQueue) => {
      const taken = takeAeroBatch(queue, AERO_BATCH_MAX);
      if (taken.batch.length === 0) {
        clearAeroQueue();
        setSyncAwaiting(null);
        return;
      }

      // The products travel with the queue so the return can name the failures instead of asking the
      // operator which of the three it was.
      writeAeroQueue({ ...taken.rest, inFlight: taken.batch });
      setSyncAwaiting({ date: queue.date, remaining: taken.batch.length + taken.rest.items.length });

      // `ENT_USU` lleva el usuario en mayúsculas: es lo que el binario Delphi manda y lo que el ERP acepta.
      // El lanzamiento pertenece a quien cargó las cajas, así que cada entrada viaja con su operario; el de
      // la sesión es sólo el respaldo de una entrada vieja que no lo guardó.
      const operatorUser = aeroSettings.username?.trim() || user?.username?.trim() || null;

      const payloads = taken.batch.map((item) =>
        buildEntradaItemPayload({
          productCode: item.productCode,
          productName: item.productName,
          quantity: item.quantity,
          entryDate: queue.date,
          userCode: item.operatorName ?? operatorUser,
          launchIds: item.launchIds,
        }),
      );
      // El atajo no sabe de massas: recibe dónde, quién, qué y a dónde volver. Cambiar la clave o la IP es
      // un cambio en la web, y el iPhone no se toca. Un producto viaja exactamente como siempre.
      const [trip] = buildAeroLaunchTrips({
        payloads,
        wsUrl: aeroSettings.wsUrl,
        username: aeroSettings.username,
        password: aeroSettings.password,
        endpoint: 'entradasitem',
        singleCallbackUrl: aeroBridgeCallbackFor('entrada'),
        batchCallbackUrl: aeroBridgeCallbackFor('entrada_lote'),
      });
      if (!trip) return;

      void postAeroAuditEvent({
        event: 'launch_sync',
        phase: 'requested',
        destinationUrl: aeroEndpoint(aeroSettings.wsUrl, 'entradasitem'),
        // One line per trip: the products are in the payload, and a log that grows with the plant's output
        // is unreadable exactly when it is needed.
        payload: payloads.length === 1 ? payloads[0] : payloads,
        note:
          payloads.length === 1
            ? `${taken.rest.items.length > 0 ? `${taken.rest.items.length} pending` : 'last entry'} · via=bridge`
            : `batch ${payloads.length}${taken.rest.items.length > 0 ? ` · ${taken.rest.items.length} pending` : ''} · via=bridge`,
      });

      window.location.href = buildAeroBridgeTriggerUrl({ name: aeroSettings.shortcutName, envelope: trip.envelope });
    },
    [aeroSettings, user],
  );

  /**
   * Sends one pending row to the ERP.
   *
   * The row's button used to call the movements read, so the trip asked the plant for the day and the
   * launch stayed pending. This is the other trip: one queue with this row's product, its boxes in units,
   * its launch ids and, above all, its operator -- the seat the company receives has to name the person
   * who typed the boxes, not whoever configured the phone.
   */
  const handleSendPendingLaunch = useCallback(
    (row: MassasUnifiedRow) => {
      if (!aeroSettingsReady(aeroSettings)) return;
      const item = pendingLaunchQueueItem({
        productCode: row.productCode,
        productName: row.productName,
        // The row already reconciled boxes into units against the plant's catalogue.
        units: row.units,
        operatorName: row.operatorName,
        launchIds: row.launchIds,
      });
      const queue: AeroQueue = { createdAt: new Date().toISOString(), date, items: [item], inFlight: [] };
      writeAeroQueue(queue);
      triggerAeroSync(queue);
    },
    [aeroSettings, date, triggerAeroSync],
  );

  /**
   * Sends the products the ERP refused or never answered, and only those.
   *
   * They are already at the front of the queue: the partial batch put them back. A second trip is the
   * operator's decision, never the page's, because a phone that leaves on its own is the problem this whole
   * milestone exists to remove.
   */
  const retryFailedLaunches = useCallback(() => {
    setResultModal(null);
    const queue = readAeroQueue();
    if (!queue) return;
    triggerAeroSync(queue);
  }, [triggerAeroSync]);

  /**
   * Asks the company for the day's movements through the same bridge every launch uses.
   *
   * The query leaves the operator filter open on purpose: the boxes are the factory's, and any operator may
   * have launched them. Publishing it as `read_history` keeps the audit trail honest about what this phone
   * actually asked for.
   */
  const handleSyncFromErp = () => {
    if (!aeroSettingsReady(aeroSettings)) return;

    const envelope = aeroMovementsEnvelope({
      wsUrl: aeroSettings.wsUrl,
      username: aeroSettings.username,
      password: aeroSettings.password,
      date,
      userName: null,
      callbackUrl: aeroBridgeCallbackFor('movimentos'),
    });

    void postAeroAuditEvent({
      event: 'read_history',
      phase: 'requested',
      destinationUrl: aeroEndpoint(aeroSettings.wsUrl, 'movimentoitem'),
      payload: JSON.parse(envelope.body),
      note: 'via=bridge',
    });

    window.location.href = buildAeroBridgeTriggerUrl({ name: aeroSettings.shortcutName, envelope });
  };

  /**
   * Closes the movements round trip: stores the snapshot and re-reads the day.
   *
   * The ERP answered the phone, not this page, so the answer travels back in the URL and becomes the
   * snapshot every other device will read. Failing to store it does not discard what came back: the mirror
   * shows the seats and the error at the same time, so the operator can still see the factory's record.
   */
  const handleMovementsCallback = useCallback(
    async (data: unknown) => {
      const read = readAeroMovements(data);
      if (read.status === 'error') {
        setSyncResult({ feedback: movementsSyncFeedback({ status: 'error', count: 0 }), detail: read.message });
        void postAeroAuditEvent({
          event: 'read_history',
          phase: 'result',
          destinationUrl: aeroEndpoint(aeroSettings.wsUrl, 'movimentoitem'),
          error: read.message ?? 'movements_unreadable',
          note: 'via=bridge',
        });
        setMirror({
          loading: false,
          error: read.message ?? t('massas.erp.error'),
          snapshot: null,
        });
        return;
      }

      void postAeroAuditEvent({
        event: 'read_history',
        phase: 'result',
        destinationUrl: aeroEndpoint(aeroSettings.wsUrl, 'movimentoitem'),
        httpStatus: 200,
        note: `seats=${read.movements.length} skipped=${read.skipped} · via=bridge`,
      });

      try {
        // The mirror answers how the day splits, so this page counts its own lens without carrying a copy
        // of the catalogue: the boxes are not this screen's business and must never appear in its dialog.
        const stored = await api.post<{ massas?: number }>(
          '/integration/aero/movements',
          buildAeroMovementsSnapshot(date, read.movements),
        );
        await loadMirror(date);
        setSyncResult({
          feedback: movementsSyncFeedback({ status: 'ok', count: stored?.massas ?? 0 }),
          detail: null,
        });
      } catch {
        // The snapshot is a cache of the company's record, so failing to store it must not throw away what
        // came back: the seats stay on screen, next to the reason they will not survive a reload.
        setMirror({
          loading: false,
          error: t('massas.erp.error'),
          snapshot: { date, movements: read.movements, syncedAt: new Date().toISOString() },
        });
        setSyncResult({
          feedback: movementsSyncFeedback({ status: 'ok', count: 0 }),
          detail: t('massas.erp.storeFailed'),
        });
      }
    },
    [aeroSettings.wsUrl, date, loadMirror, t],
  );

  /**
   * Brings one ERP seat into Trindade as a new local launch.
   *
   * Never an edit: the company's seat stays where it is. The boxes were computed from the ERP units and the
   * catalog, so a seat whose quantity does not fit whole boxes offers no button at all.
   */
  const handleImportErpSeat = async (request: {
    entLan: number;
    operatorName: string;
    productCode: string;
    boxes: number;
    date: string;
  }) => {
    setImportingEntLan(request.entLan);
    try {
      // The seat and the operator the tablet wrote travel with the boxes: without them the launch belongs
      // to whoever pressed Importar and can only be paired by name, which is how a seat never settled.
      await api.post(
        '/massas-launch',
        aeroImportPayload(
          { entLan: request.entLan, operatorName: request.operatorName },
          { productCode: request.productCode, boxes: request.boxes },
          request.date,
        ),
      );
      setNotice({ type: 'success', message: t('massas.erp.imported') });
      await Promise.all([load(date), loadMirror(date)]);
    } catch (err) {
      setNotice({
        type: 'error',
        message: err instanceof Error ? err.message : t('massas.erp.importError'),
      });
    } finally {
      setImportingEntLan(null);
    }
  };

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
   * The Atajos app finishes by opening this page with `aero_action` and `aero_data` -- the ERP answer, as it
   * came -- which is the only channel a native app has to say what happened. The legacy `aero_sync` and
   * `aero_test` parameters are still read, so a phone that has not rebuilt its shortcut keeps working. The
   * query string is consumed and stripped, so a pull-to-refresh in the factory never replays a result.
   */
  useEffect(() => {
    if (aeroCallbackHandled.current) return;
    const parsed = parseAeroCallback(window.location.search);
    if (!parsed) return;
    aeroCallbackHandled.current = true;
    window.history.replaceState({}, '', `${window.location.pathname}${window.location.hash}`);

    if (parsed.kind === 'test') {
      // The attempt was written before the phone left; this line closes it with what the endpoint said.
      const pending = loadAeroPendingTest();
      const destinationUrl = pending?.destinationUrl ?? aeroDestination() ?? aeroSettings.wsUrl;
      clearAeroPendingTest();
      void postAeroAuditEvent(
        aeroTestResultEvent({ verdict: parsed.verdict, raw: parsed.raw, destinationUrl }),
      );
      setAeroTestOutcome({ verdict: parsed.verdict, raw: parsed.raw, destinationUrl });
      // The panel that asked for this test may now be the one in the admin area, one trip away.
      saveAeroTestOutcome({ verdict: parsed.verdict, raw: parsed.raw, destinationUrl });
      return;
    }

    if (parsed.kind === 'bridge') {
      // La consulta de movimientos no es un lanzamiento: viene con la respuesta del ERP y se guarda como
      // snapshot del día, que es lo que el resto de la planta va a leer.
      if (parsed.action === 'movimentos') {
        void handleMovementsCallback(parsed.data);
        return;
      }

      // Un lote vuelve como un array, en el orden en que se envió: cada posición es un producto y las que
      // falten son productos sin confirmar, no un lote que terminó antes.
      if (parsed.action === 'entrada_lote') {
        const queue = readAeroQueue();
        const sent = queue?.inFlight ?? [];
        const expected = sent.length > 0 ? sent.length : Array.isArray(parsed.data) ? parsed.data.length : 1;
        const batch = readAeroBatchResult(parsed.data, expected);
        const destination = aeroDestination();

        void postAeroAuditEvent({
          event: 'launch_sync',
          phase: 'result',
          destinationUrl: destination,
          httpStatus: batch.ok === batch.total ? 200 : null,
          response: `${batch.ok}/${batch.total} ok`,
          error: batch.failed > 0 ? batch.message ?? 'batch_item_failed' : null,
          note: `batch ok=${batch.ok} failed=${batch.failed} · via=bridge`,
        });

        const feedback = batchSyncFeedback(batch);

        // Un atajo sin la rama de lote ignora `bodies` y contesta un objeto suelto: el lote degrada al flujo
        // de siempre, con el primer producto ya confirmado y el resto todavía esperando en la cola.
        if (!feedback) {
          const rest = queue
            ? {
                ...queue,
                items: [...(batch.ok === 1 ? sent.slice(1) : sent), ...queue.items],
                inFlight: [],
              }
            : null;
          if (rest && rest.items.length > 0) {
            writeAeroQueue(rest);
            triggerAeroSync(rest);
            return;
          }
          clearAeroQueue();
          setSyncAwaiting(null);
          setResultModal(
            batch.ok === 1
              ? { type: 'success', message: t('massas.aero.syncConfirmYes'), sentToErp: true }
              : { type: 'error', message: batch.message ?? t('massas.aero.syncConfirmNo') },
          );
          return;
        }

        // Sólo lo que el ERP no confirmó vuelve a la cola, y vuelve al frente: un lote completo se limpia, y
        // uno fallido no se da por bueno. Un reintento es del operario, no de la página.
        const failedItems = batch.failedIndexes
          .map((index) => sent[index])
          .filter((item): item is NonNullable<typeof item> => item !== undefined);
        const retryItems =
          batch.state === 'complete' ? [] : batch.state === 'partial' ? failedItems : sent;
        const rest = queue ? { ...queue, items: [...retryItems, ...queue.items], inFlight: [] } : null;
        if (rest) writeAeroQueue(rest);
        else clearAeroQueue();
        setSyncAwaiting(rest && rest.items.length > 0 ? { date: rest.date, remaining: rest.items.length } : null);

        setResultModal({
          type: feedback.tone === 'ok' ? 'success' : feedback.tone === 'warn' ? 'warn' : 'error',
          title: t(feedback.titleKey),
          message: t(feedback.messageKey, {
            ...feedback.params,
            count: feedback.count,
            date: queue?.date ?? '',
            detail: batch.message ?? t('massas.erp.batchNoDetail'),
          }),
          sentToErp: batch.ok > 0,
          retryCount: batch.state === 'complete' ? undefined : batch.failed,
        });
        return;
      }

      // El puente devuelve la respuesta del ERP sin interpretarla: acá se decide qué significa.
      const outcome = readAeroBridgeResult(parsed.data);
      void postAeroAuditEvent({
        event: 'launch_sync',
        phase: 'result',
        destinationUrl: aeroDestination(),
        httpStatus: outcome.status === 'ok' ? 200 : null,
        response: outcome.message,
        error: outcome.status === 'error' ? outcome.message ?? 'bridge_unreadable' : null,
        note: `action=${parsed.action} · via=bridge`,
      });

      // Sólo la entrada es un lanzamiento que esta página tiene que cerrar. Una baja o una consulta vuelven
      // igual, y quedan como evidencia en el log.
      if (parsed.action !== 'entrada') return;

      const queue = readAeroQueue();

      // El producto que viajó ya está confirmado, así que sale de `inFlight`: lo que quede pendiente es lo
      // que todavía no salió, y eso es lo único que puede disparar otro viaje. Mirar sólo `items` dejaba al
      // operario sin veredicto justo en el caso normal, un producto que vuelve y no deja nada detrás.
      if (outcome.status === 'ok' && queue) {
        const rest = { ...queue, inFlight: [] };
        if (rest.items.length > 0) {
          writeAeroQueue(rest);
          triggerAeroSync(rest);
          return;
        }
      }

      // Una entrada rechazada no se pierde: vuelve al frente de la cola para que el reintento la encuentre.
      const refused = outcome.status !== 'ok' && queue ? { ...queue, items: [...queue.inFlight, ...queue.items], inFlight: [] } : null;
      if (refused && refused.items.length > 0) {
        writeAeroQueue(refused);
        setSyncAwaiting({ date: refused.date, remaining: refused.items.length });
      } else {
        clearAeroQueue();
        setSyncAwaiting(null);
      }

      setResultModal(
        outcome.status === 'ok'
          ? { type: 'success', message: outcome.message ?? t('massas.aero.syncConfirmYes'), sentToErp: true }
          : { type: 'error', message: outcome.message ?? t('massas.aero.syncConfirmNo') },
      );
      return;
    }

    if (parsed.kind !== 'sync') return;

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
  }, [aeroDestination, aeroSettings.wsUrl, handleMovementsCallback, t, triggerAeroSync]);

  /**
   * The operator came back from the phone without a verdict.
   *
   * Returning to this page with an open attempt means the shortcut was cancelled, failed before it could
   * read the answer, or the return URL never opened. Closing the attempt keeps the trail honest: an open
   * `requested` line forever is noise, and inventing a success would be worse.
   */
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      if (aeroCallbackHandled.current) return;
      // A callback in the URL belongs to the effect above, whatever order the browser fires events in.
      if (parseAeroCallback(window.location.search)) return;
      const pending = loadAeroPendingTest();
      if (!pending) return;
      clearAeroPendingTest();
      void postAeroAuditEvent(aeroTestNoVerdictEvent({ destinationUrl: pending.destinationUrl }));
      setAeroTestOutcome({ verdict: 'unknown', raw: '', destinationUrl: pending.destinationUrl });
      saveAeroTestOutcome({ verdict: 'unknown', raw: '', destinationUrl: pending.destinationUrl });
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  // One row per box, from both screens. It is derived on every render on purpose: the day and the mirror are
  // the only truth, and a stored row state would be a third one to keep in sync (D5).
  const unifiedRows = useMemo<MassasUnifiedRow[]>(
    () =>
      massasUnifiedRows({
        launches: dayView?.launches ?? [],
        movements: mirror.snapshot?.movements ?? [],
        products: dayView?.products ?? [],
        ackedLaunchIds,
      }),
    [dayView, mirror.snapshot, ackedLaunchIds],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }



  const handleToggleAck = (row: MassasUnifiedRow) => {
    const launchId = row.launchIds[0];
    if (launchId === undefined) return;
    setAckedLaunchIds(toggleAckedLaunchId(date, launchId));
  };

  return (
    <div className="max-w-6xl mx-auto space-y-4 pb-12 sm:space-y-6">
      {aeroAllowed && deviceLimited && <AeroDeviceBanner device={device} />}
      {/* Encabezado y Selección de Fecha. Sin el emoji: en un teléfono la placa del espagueti y el
          botón se llevaban el ancho que el título necesitaba. Debajo de `sm` las acciones bajan a su
          propio renglón y la fecha sigue alcanzable con el pulgar. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 bg-white p-4 rounded-xl border border-gray-200 shadow-sm sm:p-6">
        <div className="min-w-0">
          <div className="flex items-center justify-between gap-2">
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">{t('massas.title')}</h1>
            <div className="shrink-0">
              <ModuleHelpModal
                title={t('moduleHelp.massasLaunch.title')}
                subtitle={t('moduleHelp.massasLaunch.subtitle')}
                description={t('moduleHelp.massasLaunch.description')}
                sections={t('moduleHelp.massasLaunch.sections', { returnObjects: true }) as any}
              />
            </div>
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.subtitle')}</p>
          {aeroAllowed && (
            <div className="mt-3 flex flex-wrap items-center gap-2" data-testid="massas-sync-header">
              <button
                type="button"
                onClick={handleSyncFromErp}
                disabled={mirror.loading || !aeroSettingsReady(aeroSettings) || deviceLimited}
                className="inline-flex min-h-[44px] items-center gap-1.5 rounded-lg bg-amber-600 px-3 text-xs font-semibold text-white transition-colors hover:bg-amber-700 disabled:opacity-50"
                data-testid="massas-sync-erp"
              >
                <RefreshCw className={`h-4 w-4 shrink-0 ${mirror.loading ? 'animate-spin' : ''}`} />
                {t('massas.syncHeader.action', 'Sincronizar com ERP')}
              </button>
              <span className="text-xs text-gray-500" data-testid="massas-last-sync">
                {t('massas.syncHeader.lastSync', 'Último sync')}:{' '}
                <span className="font-semibold tabular-nums text-gray-700">
                  {lastSyncLabel(mirror.snapshot?.syncedAt ?? null)}
                </span>
              </span>
            </div>
          )}
        </div>
        {/* Una sola fila también en el teléfono. Los dos controles miden lo que su propio texto mide:
            `DateField` dibuja la fecha él mismo y deja el input nativo invisible encima, así que WebKit
            no puede reclamar los ~250px con los que dibujaría "28 de out. de 2026". El nombre completo
            del destino sigue estando desde `sm`. */}
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <DateField
            id="massa-date"
            label={`${t('massas.date')}:`}
            value={date}
            onChange={setDate}
            max={saoPauloToday()}
            testId="massas-launch-date"
          />
          {/* Un enlace que envuelve un botón deja dos destinos anidados en el mismo punto: el lector de
              pantalla anuncia dos y el dedo no sabe cuál tocó. El destino vive en el enlace. */}
          <Link
            to="/massas-history"
            className="inline-flex h-11 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border border-gray-300 bg-white px-3 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 sm:h-10"

          >
            <History className="h-4 w-4 shrink-0" />
            <span className="sm:hidden">{t('massas.historyShort')}</span>
            <span className="hidden sm:inline">{t('massas.viewHistory')}</span>
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
          readOnly={dayIsClosed}
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
                  disabled={!aeroSettingsReady(aeroSettings) || deviceLimited}
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
          isAdmin={user?.role === 'Administrador'}
          editingLaunchId={editingLaunchId}
          editingBoxes={editingBoxes}
          editingBusy={editingBusy}
          onStartEdit={startEditing}
          onCancelEdit={cancelEditing}
          onSaveEdit={saveEditing}
          onEditBoxesChange={setEditingBoxes}
          onDelete={handleDeleteLaunch}
          deletingId={deletingLaunchId}
          unifiedRows={unifiedRows}
          unifiedDate={date}
          canSync={aeroAllowed && aeroSettingsReady(aeroSettings)}
          deviceLimited={deviceLimited}
          onSendToErp={handleSendPendingLaunch}
          onImport={(request) => void handleImportErpSeat(request)}
          importingEntLan={importingEntLan}
          onToggleAck={handleToggleAck}
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

      {/* Resultado da consulta ao ERP: quantos assentos vieram, ou por que não vieram */}
      <AeroResultDialog
        open={syncResult !== null}
        tone={syncResult?.feedback.tone ?? 'ok'}
        title={t(syncResult?.feedback.titleKey ?? 'massas.erp.syncOkTitle')}
        message={t(syncResult?.feedback.messageKey ?? 'massas.erp.syncError', {
          count: syncResult?.feedback.count ?? 0,
          date: formatDateLabel(date),
        })}
        hint={syncResult?.detail ?? null}
        onClose={() => setSyncResult(null)}
        testId="aero-sync-result-modal"
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
              {resultModal.type === 'success' ? '✅' : resultModal.type === 'warn' ? '⚠️' : '❌'}
            </div>
            <h2 id="massa-result-title" className="text-lg font-bold text-gray-900">
              {resultModal.title ??
                (resultModal.type === 'success'
                  ? t('massas.successTitle', 'Lançamento Concluído')
                  : t('massas.errorTitle', 'Erro no Lançamento'))}
            </h2>
            <p className="text-sm text-gray-600" data-testid="massa-result-message">
              {resultModal.message}
            </p>
            {launchMirrorHint(resultModal) && (
              <p
                className="rounded-lg border border-amber-200 bg-amber-50 p-2.5 text-xs leading-relaxed text-amber-900"
                data-testid="massa-result-mirror-hint"
              >
                {t('massas.erp.mirrorHint')}
              </p>
            )}
            <div className="space-y-2 pt-2">
              {resultModal.retryCount ? (
                <Button
                  onClick={retryFailedLaunches}
                  className="min-h-[44px] w-full bg-amber-600 font-semibold text-white hover:bg-amber-700"
                  data-testid="massa-result-retry"
                >
                  {t('massas.erp.batchRetry', { count: resultModal.retryCount })}
                </Button>
              ) : null}
              <Button
                onClick={() => setResultModal(null)}
                className={`min-h-[44px] w-full font-semibold ${
                  resultModal.type === 'success'
                    ? 'bg-green-600 hover:bg-green-700 text-white'
                    : resultModal.type === 'warn'
                      ? 'bg-amber-600 hover:bg-amber-700 text-white'
                      : 'bg-red-600 hover:bg-red-700 text-white'
                }`}
                data-testid="massa-result-close"
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
