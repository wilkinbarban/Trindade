import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RefreshCw } from 'lucide-react';
import { api } from '../../api/client';
import { Button } from '../ui/button';
import { LoadingSpinner } from '../ui/loading-spinner';
import { MassasBalanceBar } from '../../pages/massas/MassasBalanceBar';

/** One product's share of an operator's day, as the report counts it. */
export interface MassasReportOperatorProduct {
  code: string;
  name: string;
  boxes: number;
  units: number;
}

/** One operator's share of the day, no matter which screen recorded it. */
export interface MassasReportOperator {
  name: string;
  username: string | null;
  boxes: number;
  units: number;
  products: MassasReportOperatorProduct[];
  /** True when the name only exists in the ERP, so the card does not pretend it is an account here. */
  erpOnly: boolean;
}

export interface MassasReportProduct {
  code: string;
  name: string;
  unitsPerBox: number;
  totalBoxes: number;
  totalUnits: number;
}

/** The day the plant really produced, read from both sides. */
export interface MassasReportDay {
  date: string;
  operators: MassasReportOperator[];
  products: MassasReportProduct[];
  totalBoxes: number;
  totalUnits: number;
  balance: {
    producedUnits: number;
    producedBoxes: number;
    registeredBoxes: number;
    registeredUnits: number;
    differenceUnits: number;
    /** How many ERP seats the plant also recorded, and how many it never claimed. */
    reconciledSeats: number;
    pendingSeats: number;
  };
  reconciledEntLans: number[];
}

interface MassasReportSectionProps {
  /** The day the report describes; the card answers that day even when it is not today. */
  date: string;
}

/**
 * Massas, as the report counts them.
 *
 * The card shows the plant's real day: what an operator launched here plus what the company tablet loaded
 * straight into Delphi, each box counted once. An operator who only exists in the ERP keeps their name and
 * wears a chip saying so, because inventing an account here would be worse than admitting the gap.
 *
 * It is read-only in both screens: this is what the report will say, not a second place to type launches.
 */
export function MassasReportSection({ date }: MassasReportSectionProps) {
  const { t } = useTranslation();
  const [day, setDay] = useState<MassasReportDay | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const body = await api.get<MassasReportDay>(`/massas-launch/report?date=${date}`);
      setDay(body);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    void load();
  }, [load]);

  const operators = day?.operators ?? [];
  const activeProducts = (day?.products ?? []).filter((product) => product.totalUnits > 0);

  return (
    <section data-testid="massas-report-section" className="space-y-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="flex items-center gap-2 text-base font-semibold text-gray-800">
            <span aria-hidden="true">🍝</span>
            <span>{t('reports.massasTitle', 'Massas do dia')}</span>
          </h3>
          {day && day.totalUnits > 0 && (
            <p className="mt-0.5 text-sm text-gray-600" data-testid="massas-report-totals">
              {t('reports.massasTotals', '{{boxes}} cx · {{units}} un', {
                boxes: day.totalBoxes,
                units: day.totalUnits,
              })}
            </p>
          )}
        </div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => void load()}
          disabled={loading}
          aria-label={t('reports.massasRefresh', 'Atualizar massas')}
          data-testid="massas-report-refresh"
          className="min-h-[44px] min-w-[44px] shrink-0"
        >
          <RefreshCw className={loading ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
        </Button>
      </div>

      {loading && !day && (
        <div className="flex justify-center py-6">
          <LoadingSpinner />
        </div>
      )}

      {error && (
        <p role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {t('reports.massasError', 'Não foi possível ler as massas do dia.')}
        </p>
      )}

      {day && (
        // The day's accounting, not the rows on screen: Produzido comes from the plant, No ERP from the seats
        // the company recorded, and the difference is the figure the supervisor has to explain.
        <MassasBalanceBar
          produced={day.balance.producedUnits}
          registered={day.balance.registeredUnits}
          difference={day.balance.differenceUnits}
          testId="massas-report-balance"
        />
      )}

      {day && operators.length === 0 && (
        <p className="text-sm text-gray-600" data-testid="massas-report-empty">
          {t('reports.massasEmpty', 'Nenhuma massa lançada neste dia.')}
        </p>
      )}

      {operators.length > 0 && (
        <ul className="space-y-2" data-testid="massas-report-operators">
          {operators.map((operator) => (
            <li key={operator.name} className="rounded-md border border-gray-200 p-3">
              <div className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-1">
                <span className="flex min-w-0 items-center gap-1.5 text-sm font-medium text-gray-800">
                  <span aria-hidden="true">👤</span>
                  <span className="truncate">{operator.name}</span>
                  {operator.erpOnly && (
                    <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-semibold text-amber-800">
                      {t('reports.massasErpOnly', 'só ERP')}
                    </span>
                  )}
                </span>
                <span className="text-sm font-semibold text-gray-700">{operator.units} un</span>
              </div>
              <ul className="mt-1 space-y-0.5">
                {operator.products.map((product) => (
                  <li key={product.code} className="flex justify-between gap-2 text-sm text-gray-600">
                    <span className="truncate">{product.name}</span>
                    <span className="shrink-0">{product.units} un</span>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}

      {activeProducts.length > 0 && (
        <div data-testid="massas-report-products">
          <p className="text-xs font-semibold uppercase tracking-wide text-gray-500">
            {t('reports.massasSummary', 'Resumo coletivo')}
          </p>
          <ul className="mt-1 space-y-0.5">
            {activeProducts.map((product) => (
              <li key={product.code} className="flex justify-between gap-2 text-sm text-gray-700">
                <span className="flex min-w-0 items-center gap-1.5">
                  <span aria-hidden="true">📦</span>
                  <span className="truncate">{product.name}</span>
                </span>
                <span className="shrink-0 font-medium">{product.totalUnits} un</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
