import { useTranslation } from 'react-i18next';

/**
 * Produzido contra registrado: the three numbers that say whether the company already knows what the plant
 * made, and how much of it is still missing from the ERP.
 *
 * One component for both screens on purpose. «Produção do dia» reads the rows on screen and the report reads
 * the backend's balance, but the operator must see the same three figures drawn the same way in both places,
 * and a copy is how the two drift.
 */
export function MassasBalanceBar({
  produced,
  registered,
  difference,
  testId = 'massas-balance',
}: {
  produced: number;
  registered: number;
  /** `registered - produced`: zero means the day is fully reconciled, negative names what is still missing. */
  difference: number;
  testId?: string;
}) {
  const { t } = useTranslation();
  const tone =
    difference === 0 ? 'text-emerald-700' : difference < 0 ? 'text-amber-700' : 'text-sky-700';

  return (
    <div
      className="grid grid-cols-3 gap-2 rounded-lg border border-gray-200 bg-gray-50 p-3"
      data-testid={testId}
    >
      <div>
        <p className="text-[11px] uppercase tracking-wide text-gray-500">
          {t('massas.balance.produced', 'Produzido')}
        </p>
        <p
          className="text-base font-semibold tabular-nums text-gray-900"
          data-testid="massas-balance-produced"
        >
          {produced} <span className="text-xs font-normal text-gray-500">un</span>
        </p>
      </div>
      <div>
        <p className="text-[11px] uppercase tracking-wide text-gray-500">
          {t('massas.balance.registered', 'No ERP')}
        </p>
        <p
          className="text-base font-semibold tabular-nums text-gray-900"
          data-testid="massas-balance-registered"
        >
          {registered} <span className="text-xs font-normal text-gray-500">un</span>
        </p>
      </div>
      <div>
        <p className="text-[11px] uppercase tracking-wide text-gray-500">
          {t('massas.balance.difference', 'Diferença')}
        </p>
        <p
          className={`text-base font-semibold tabular-nums ${tone}`}
          data-testid="massas-balance-difference"
        >
          {difference > 0 ? `+${difference}` : difference}{' '}
          <span className="text-xs font-normal text-gray-500">un</span>
        </p>
      </div>
    </div>
  );
}
