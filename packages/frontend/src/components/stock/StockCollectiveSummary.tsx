import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '../ui/card';
import type { StockProduct } from '../../lib/stock';

interface StockCollectiveSummaryProps {
  /** Only the products that were counted: a catalogue with zeroes hides the rows someone came for. */
  products: StockProduct[];
  /**
   * Prefix for the element test ids, so the counter and the history can show the same summary
   * without fighting over the same handles.
   */
  testIdPrefix: string;
  /** Day totals, when the screen wants them under the table instead of in its own card. */
  totalBoxes?: number;
  totalUnits?: number;
}

/**
 * The day's collective summary: a table on a desk, cards on a phone.
 *
 * The card repeats the server's totals instead of adding them up again, and the phone card puts the
 * name on its own row because a shelf read on a 390 px screen had it ending in an ellipsis while two
 * badges held the width.
 */
export function StockCollectiveSummary({
  products,
  testIdPrefix,
  totalBoxes,
  totalUnits,
}: StockCollectiveSummaryProps) {
  const { t } = useTranslation();
  const showsTotals = totalBoxes !== undefined && totalUnits !== undefined;

  return (
    <Card>
      <CardContent className="p-3 space-y-3">
        <div>
          <h2 className="text-lg font-semibold text-gray-900">{t('stock.collectiveTitle')}</h2>
          <p className="text-xs text-gray-500">{t('stock.collectiveSubtitle')}</p>
        </div>

        {products.length === 0 ? (
          <div
            className="py-8 px-4 text-center text-gray-500 bg-gray-50/50 rounded-lg border border-dashed border-gray-200"
            data-testid={`${testIdPrefix}-summary-empty`}
          >
            <p className="text-sm font-medium">{t('stock.collectiveEmpty')}</p>
          </div>
        ) : (
          <>
            <div className="hidden sm:block overflow-x-auto rounded-lg border border-gray-200">
              <table className="w-full text-left text-sm border-collapse">
                <thead>
                  <tr className="bg-gray-100 text-gray-700 text-xs font-semibold uppercase">
                    <th className="py-3 px-4">{t('stock.product')}</th>
                    <th className="py-3 px-4 text-center">{t('stock.unitsPerBoxHeader')}</th>
                    <th className="py-3 px-4 text-right">{t('stock.boxes')}</th>
                    <th className="py-3 px-4 text-right">{t('stock.units')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 bg-white">
                  {products.map((product) => (
                    <tr
                      key={product.code}
                      className="bg-amber-50/20 font-medium"
                      data-testid={`${testIdPrefix}-summary-${product.code}`}
                    >
                      <td className="py-2.5 px-4 font-bold text-gray-900">{product.name}</td>
                      <td className="py-2.5 px-4 text-center text-xs text-gray-500">{product.unitsPerBox}</td>
                      <td className="py-2.5 px-4 text-right">
                        <span className="inline-flex items-center rounded-md bg-gray-900/5 px-2 py-0.5 text-xs font-bold text-gray-900 tabular-nums">
                          {product.boxes} {t('stock.boxesShort')}
                        </span>
                      </td>
                      <td className="py-2.5 px-4 text-right">
                        <span className="inline-flex items-center rounded-md bg-amber-600 px-2 py-0.5 text-xs font-extrabold text-white tabular-nums">
                          {product.units} {t('stock.unitsShort')}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="block sm:hidden space-y-2" data-testid={`${testIdPrefix}-summary-mobile`}>
              {products.map((product) => (
                <div
                  key={product.code}
                  className="p-3 rounded-lg border border-amber-200/80 bg-amber-50/40 shadow-xs"
                >
                  <p className="font-bold text-sm text-gray-900 leading-snug break-words">{product.name}</p>
                  <div className="flex items-center justify-between gap-2 mt-1.5">
                    <span className="text-xs text-gray-500 font-medium">
                      {product.unitsPerBox} {t('stock.unitsPerBoxHeader')}
                    </span>
                    <div className="flex items-center gap-1.5 shrink-0">
                      <span className="inline-flex items-center rounded-md bg-gray-900/5 px-2 py-0.5 text-xs font-bold text-gray-900 tabular-nums">
                        {product.boxes} {t('stock.boxesShort')}
                      </span>
                      <span className="inline-flex items-center rounded-md bg-amber-600 px-2 py-0.5 text-sm font-extrabold text-white tabular-nums">
                        {product.units} {t('stock.unitsShort')}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}

        {showsTotals && (
          <div className="flex items-center justify-between pt-2 border-t" data-testid={`${testIdPrefix}-total`}>
            <span className="text-sm text-gray-500">{t('stock.total')}</span>
            <span className="text-lg font-semibold text-green-700">
              {totalBoxes} {t('stock.boxesWord')} · {totalUnits} {t('stock.unitsWord')}
            </span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
