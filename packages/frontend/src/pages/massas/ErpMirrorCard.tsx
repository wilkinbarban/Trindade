import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { BadgeCheck, CloudDownload, RefreshCw, Users } from 'lucide-react';
import type { AeroErpMovementItem } from '../../lib/aero-integration';
import { aeroImportPlan, type AeroMovementSnapshot } from '../../lib/aero-mirror';
import { reconcileAeroMovements } from '../../lib/aero-integration';
import type { MassaIndividualLaunch, MassaProductTotal } from '../../lib/massas';

export interface AeroImportRequest {
  /** The seat being imported, so its own row can show it is busy. */
  entLan: number;
  productCode: string;
  boxes: number;
  date: string;
}

interface ErpMirrorCardProps {
  snapshot: AeroMovementSnapshot | null;
  loading: boolean;
  error: string | null;
  /** Whether this device has the bridge configured, so it can actually reach the ERP. */
  canSync: boolean;
  onSync: () => void;
  /** The day's local launches, which is what makes a seat "Conciliado". */
  launches: MassaIndividualLaunch[];
  products: MassaProductTotal[];
  onImport?: (request: AeroImportRequest) => void;
  importingEntLan?: number | null;
}

/**
 * The company ERP's side of the day, as the plant reads it.
 *
 * Read-only by design: every control here reads or imports. A seat is never edited or deleted in Trindade,
 * because `ENT_LAN` belongs to Aero32; importing creates a new local launch and leaves the company's record
 * where it is.
 */
export function ErpMirrorCard({
  snapshot,
  loading,
  error,
  canSync,
  onSync,
  launches,
  products,
  onImport,
  importingEntLan = null,
}: ErpMirrorCardProps) {
  const { t } = useTranslation();

  const reconciliation = useMemo(
    () => reconcileAeroMovements(snapshot?.movements ?? [], launches),
    [snapshot, launches],
  );

  const reconciled = useMemo(() => new Set(reconciliation.reconciled), [reconciliation]);

  const importable = useMemo(() => {
    const map = new Map<number, AeroImportRequest>();
    for (const movement of snapshot?.movements ?? []) {
      if (reconciled.has(movement.entLan)) continue;
      const plan = aeroImportPlan(movement, products);
      if (plan) {
        map.set(movement.entLan, { entLan: movement.entLan, ...plan, date: movement.entryDate });
      }
    }
    return map;
  }, [snapshot, products, reconciled]);

  const syncedAtLabel = useMemo(() => {
    if (!snapshot?.syncedAt) return null;
    const parsed = new Date(snapshot.syncedAt);
    if (Number.isNaN(parsed.getTime())) return null;
    return parsed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }, [snapshot]);

  const seats: AeroErpMovementItem[] = snapshot?.movements ?? [];

  return (
    <Card className="border-gray-200 shadow-sm">
      <CardContent className="pt-6">
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.erp.title')}</h2>
            <p className="text-xs text-gray-500">{t('massas.erp.subtitle')}</p>
          </div>
          {canSync ? (
            <Button
              type="button"
              onClick={onSync}
              disabled={loading}
              className="min-h-[44px] w-full shrink-0 gap-2 bg-slate-800 font-semibold text-white hover:bg-slate-900 sm:w-auto"
              data-testid="erp-mirror-sync"
            >
              <CloudDownload className="h-4 w-4" />
              {t('massas.erp.sync')}
            </Button>
          ) : (
            <p className="text-[11px] text-gray-500 sm:max-w-[14rem] sm:text-right">
              {t('massas.erp.needSetup')}
            </p>
          )}
        </div>

        {error && (
          <div
            role="alert"
            className="mb-3 rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-800"
            data-testid="erp-mirror-error"
          >
            {error}
          </div>
        )}

        <div className="mb-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-gray-500">
          {loading ? (
            <span className="inline-flex items-center gap-1.5">
              <RefreshCw className="h-3.5 w-3.5 animate-spin" />
              {t('massas.erp.loading')}
            </span>
          ) : syncedAtLabel ? (
            <span>{t('massas.erp.lastSync', { time: syncedAtLabel })}</span>
          ) : (
            <span>{t('massas.erp.neverSynced')}</span>
          )}
          {seats.length > 0 && (
            <span className="inline-flex items-center gap-1 rounded-full bg-slate-900/5 px-2 py-0.5 font-semibold text-slate-700 tabular-nums">
              {seats.length}
            </span>
          )}
        </div>

        {seats.length === 0 ? (
          <div
            className="rounded-lg border border-dashed border-gray-200 bg-gray-50 py-8 text-center text-sm text-gray-500"
            data-testid="erp-mirror-empty"
          >
            {snapshot ? t('massas.erp.empty') : t('massas.erp.neverSynced')}
          </div>
        ) : (
          <div className="space-y-2.5" data-testid="erp-mirror-seats">
            {seats.map((seat) => {
              const isReconciled = reconciled.has(seat.entLan);
              const plan = importable.get(seat.entLan);
              const busy = importingEntLan === seat.entLan;

              return (
                <div
                  key={seat.entLan}
                  className={`rounded-lg border p-3.5 ${
                    isReconciled ? 'border-emerald-200 bg-emerald-50/50' : 'border-amber-200 bg-amber-50/40'
                  }`}
                  data-testid={`erp-seat-${seat.entLan}`}
                >
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-sm font-bold leading-snug text-gray-900 break-words">
                        {seat.productDesc}
                      </p>
                      <p className="mt-0.5 text-[11px] text-gray-500 tabular-nums">
                        {seat.productCode} · {t('massas.erp.seat', { entLan: seat.entLan })}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1.5">
                      <span className="inline-flex items-center rounded-md bg-amber-600 px-2 py-0.5 text-sm font-extrabold text-white tabular-nums">
                        {t('massas.erp.quantity', { units: seat.quantity })}
                      </span>
                    </div>
                  </div>

                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="inline-flex max-w-full items-center gap-1 rounded-full bg-white/80 px-2 py-0.5 text-[11px] font-semibold text-slate-700 ring-1 ring-slate-200">
                        <Users className="h-3 w-3 shrink-0" />
                        <span className="truncate">{seat.operatorName}</span>
                      </span>
                      {isReconciled ? (
                        <span
                          className="inline-flex items-center gap-1 rounded-full bg-emerald-600 px-2 py-0.5 text-[11px] font-bold text-white"
                          data-testid={`erp-seat-reconciled-${seat.entLan}`}
                        >
                          <BadgeCheck className="h-3 w-3" />
                          {t('massas.erp.conciliated')}
                        </span>
                      ) : (
                        <span
                          className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-bold text-amber-900"
                          data-testid={`erp-seat-pending-${seat.entLan}`}
                        >
                          {t('massas.erp.pending')}
                        </span>
                      )}
                    </div>

                    {!isReconciled && plan && onImport && (
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => onImport(plan)}
                        disabled={busy}
                        className="min-h-[44px] w-full shrink-0 gap-1.5 text-xs font-semibold sm:w-auto"
                        data-testid={`erp-seat-import-${seat.entLan}`}
                      >
                        <CloudDownload className="h-4 w-4" />
                        {busy ? t('massas.erp.importing') : t('massas.erp.import')}
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        <p className="mt-3 text-[11px] leading-snug text-gray-500">{t('massas.erp.readOnly')}</p>
      </CardContent>
    </Card>
  );
}
