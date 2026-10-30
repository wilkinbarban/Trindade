import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { CloudOff, PackageCheck, RefreshCw, Smartphone, Truck } from 'lucide-react';
import { api } from '../../api/client';
import { Button } from '../ui/button';
import { AeroResultDialog } from '../../pages/massas/AeroResultDialog';
import type { AeroSyncFeedback } from '../../lib/aero-feedback';
import { receiptsSyncFeedback } from '../../lib/aero-feedback';
import {
  aeroBridgeCallbackFor,
  aeroSettingsReady,
  buildAeroMovementsSnapshot,
  buildAeroBridgeTriggerUrl,
  loadAeroSettings,
  parseAeroCallback,
  readAeroMovements,
} from '../../lib/aero-integration';
import { aeroMovementsEnvelope } from '../../lib/aero-mirror';
import { readReportReceiptsDay, type ReportReceiptsDay } from '../../lib/receipts';

interface ReceiptsSectionProps {
  /** The report's day. The section answers that day, even if it is not today. */
  date: string;
  readOnly?: boolean;
}

/**
 * Recebimento, as the ERP answered it.
 *
 * The section is read-only everywhere: the seats belong to Aero32 and Trindade only copies what the bridge
 * brought. It never estimates and never hides a state -- an empty day, a day nobody synced and a missing
 * mirror table are three different sentences, because they mean three different things to the operator.
 *
 * The sync runs the same bridge the launch screen runs, and comes back here instead of to the launches: the
 * callback URL the shortcut is handed is this page's own route, so the operator never loses the report they
 * were filing. The snapshot is stored whole; what this screen counts is only its lens, the supplies.
 */
export function ReceiptsSection({ date, readOnly = false }: ReceiptsSectionProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const [day, setDay] = useState<ReportReceiptsDay | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [syncResult, setSyncResult] = useState<{
    feedback: AeroSyncFeedback;
    detail: string | null;
  } | null>(null);
  // The callback is consumed once: a re-render must not store the same day again, and a pull-to-refresh in
  // the factory must not replay a result the operator already read.
  const callbackHandled = useRef(false);

  const settings = useMemo(() => loadAeroSettings(), []);
  const canSync = !readOnly && aeroSettingsReady(settings);

  const load = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const body = await api.get<unknown>(`/reports/receipts?date=${date}`);
      const parsed = readReportReceiptsDay(body);
      if (!parsed) {
        setDay(null);
        setError(true);
        return;
      }
      setDay(parsed);
    } catch {
      setDay(null);
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * Closes the sync round trip: stores the snapshot the phone brought and counts this screen's lens.
   *
   * `insumos` comes from the mirror, which is the only place that knows the catalogue; counting here would
   * mean a second copy of the rule. A day with masas and no supplies answers zero, never "nothing happened".
   */
  const finishSync = useCallback(
    async (data: unknown) => {
      const read = readAeroMovements(data);
      if (read.status === 'error') {
        setSyncResult({
          feedback: receiptsSyncFeedback({ status: 'error', count: 0 }),
          detail: read.message,
        });
        return;
      }

      try {
        const stored = await api.post<{ insumos?: number }>(
          '/integration/aero/movements',
          buildAeroMovementsSnapshot(date, read.movements),
        );
        await load();
        setSyncResult({
          feedback: receiptsSyncFeedback({ status: 'ok', count: stored?.insumos ?? 0 }),
          detail: null,
        });
      } catch {
        setSyncResult({
          feedback: receiptsSyncFeedback({ status: 'error', count: 0 }),
          detail: t('reports.receiptsError'),
        });
      }
    },
    [date, load, t],
  );

  useEffect(() => {
    if (callbackHandled.current) return;
    const parsed = parseAeroCallback(window.location.search);
    if (!parsed || parsed.kind !== 'bridge' || parsed.action !== 'movimentos') return;
    callbackHandled.current = true;
    window.history.replaceState({}, '', `${window.location.pathname}${window.location.hash}`);
    void finishSync(parsed.data);
  }, [finishSync]);

  const handleSync = useCallback(() => {
    if (!canSync) return;
    const envelope = aeroMovementsEnvelope({
      wsUrl: settings.wsUrl,
      username: settings.username,
      password: settings.password,
      date,
      userName: null,
      callbackUrl: aeroBridgeCallbackFor('movimentos', location.pathname),
    });
    window.location.href = buildAeroBridgeTriggerUrl({ name: settings.shortcutName, envelope });
  }, [canSync, date, location.pathname, settings]);

  const syncedAtLabel = useMemo(() => {
    if (!day?.syncedAt) return null;
    const parsed = new Date(day.syncedAt);
    if (Number.isNaN(parsed.getTime())) return null;
    return parsed.toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' });
  }, [day, i18n.language]);

  const title = t('reports.receiptsTitle');

  return (
    <div className="mb-6" data-testid="receipts-section">
      <div className="mb-3 flex flex-col gap-2 border-b border-gray-200 pb-2 sm:flex-row sm:items-center sm:justify-between">
        <h3 className="text-md font-semibold text-gray-800">{title}</h3>
        <div className="flex flex-wrap items-center gap-2">
          {syncedAtLabel && (
            <span className="text-[11px] text-gray-500" data-testid="receipts-synced-at">
              {t('reports.receiptsSyncedAt', { time: syncedAtLabel })}
            </span>
          )}
          {!readOnly && (
            <div className="flex w-full items-center gap-2 sm:w-auto">
              <Button
                type="button"
                onClick={handleSync}
                disabled={!canSync}
                className="min-h-[44px] flex-1 gap-2 text-xs font-semibold sm:flex-none"
                data-testid="receipts-sync"
              >
                <Smartphone className="h-4 w-4" />
                {t('reports.receiptsSync')}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => void load()}
                disabled={loading}
                aria-label={t('reports.receiptsRefresh')}
                className="min-h-[44px] w-11 shrink-0"
                data-testid="receipts-refresh"
              >
                <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
              </Button>
            </div>
          )}
        </div>
      </div>
      <p className="mb-3 text-xs text-gray-500">{t('reports.receiptsSubtitle')}</p>
      {!readOnly && !canSync && (
        <p className="mb-3 text-[11px] text-amber-800" data-testid="receipts-sync-hint">
          {t('reports.receiptsSyncNotConfigured')}
        </p>
      )}

      {loading ? (
        <div
          className="flex items-center gap-2 rounded-lg border border-dashed border-gray-200 bg-gray-50 px-3 py-4 text-sm text-gray-500"
          data-testid="receipts-loading"
        >
          <RefreshCw className="h-4 w-4 animate-spin" />
          {t('reports.receiptsLoading')}
        </div>
      ) : error ? (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-800"
          data-testid="receipts-error"
        >
          {t('reports.receiptsError')}
        </div>
      ) : day && !day.migrated ? (
        <div
          className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"
          data-testid="receipts-unmigrated"
        >
          <CloudOff className="mt-0.5 h-4 w-4 shrink-0" />
          {t('reports.receiptsUnmigrated')}
        </div>
      ) : day && day.syncedAt === null ? (
        <div
          className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"
          data-testid="receipts-pending"
        >
          <p className="flex items-center gap-2 font-semibold">
            <Truck className="h-4 w-4 shrink-0" />
            {t('reports.receiptsPending')}
          </p>
          <p className="mt-1">{t('reports.receiptsPendingHint')}</p>
          {!canSync && (
            <Link
              to="/massas-launch"
              className="mt-2 inline-flex min-h-[44px] items-center gap-1.5 font-semibold text-amber-900 underline underline-offset-2"
              data-testid="receipts-open-massas"
            >
              {t('reports.receiptsOpenMassas')}
            </Link>
          )}
        </div>
      ) : day && day.receipts.length === 0 ? (
        <div
          className="rounded-lg border border-dashed border-gray-200 bg-gray-50 px-3 py-4 text-sm text-gray-500"
          data-testid="receipts-empty"
        >
          {t('reports.receiptsEmpty')}
        </div>
      ) : (
        <ul className="space-y-1.5" data-testid="receipts-list">
          {day?.receipts.map((receipt) => (
            <li
              key={`${receipt.entLan}-${receipt.productCode}`}
              className="rounded-lg border border-gray-200 bg-white p-3"
              data-testid={`receipt-line-${receipt.entLan}-${receipt.productCode}`}
            >
              <div className="flex items-start justify-between gap-2">
                <p className="min-w-0 text-sm font-semibold text-gray-900 break-words">{receipt.productDesc}</p>
                <span className="inline-flex shrink-0 items-center gap-1 rounded-md bg-emerald-600 px-2 py-0.5 text-xs font-bold text-white tabular-nums">
                  <PackageCheck className="h-3 w-3" />
                  {t('reports.receiptsUnits', { quantity: receipt.quantity })}
                </span>
              </div>
              <p className="mt-1 text-[11px] text-gray-500 tabular-nums">
                {receipt.operatorName}
              </p>
            </li>
          ))}
        </ul>
      )}

      <AeroResultDialog
        open={syncResult !== null}
        tone={syncResult?.feedback.tone ?? 'ok'}
        title={t(syncResult?.feedback.titleKey ?? 'reports.receiptsSyncTitle')}
        message={t(syncResult?.feedback.messageKey ?? 'reports.receiptsSyncError', {
          count: syncResult?.feedback.count ?? 0,
        })}
        hint={syncResult?.detail ?? null}
        onClose={() => setSyncResult(null)}
        testId="receipts-sync-result-modal"
      />
    </div>
  );
}
