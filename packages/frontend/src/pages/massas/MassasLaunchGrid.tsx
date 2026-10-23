import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Send } from 'lucide-react';
import type { MassaProductTotal } from '../../lib/massas';

interface MassasLaunchGridProps {
  products: MassaProductTotal[];
  quantities: Record<string, string>;
  onQuantityChange: (code: string, rawVal: string) => void;
  onLaunch: () => void;
  submitting: boolean;
  date: string;
}

export function MassasLaunchGrid({
  products,
  quantities,
  onQuantityChange,
  onLaunch,
  submitting,
  date,
}: MassasLaunchGridProps) {
  const { t } = useTranslation();

  const itemsToLaunch = useMemo(() => {
    return products
      .map((p) => {
        const boxesNum = parseInt(quantities[p.code] || '0', 10);
        return {
          boxes: boxesNum,
          units: boxesNum * p.unitsPerBox,
        };
      })
      .filter((item) => item.boxes > 0);
  }, [products, quantities]);

  const totalLaunchBoxes = itemsToLaunch.reduce((acc, curr) => acc + curr.boxes, 0);
  const totalLaunchUnits = itemsToLaunch.reduce((acc, curr) => acc + curr.units, 0);

  return (
    <Card className="border-gray-200 shadow-sm">
      <CardContent className="pt-6">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.title')}</h2>
            <p className="text-xs text-gray-500">
              {t('massas.liveUnits')} · {t('massas.date')}: {date}
            </p>
          </div>
          {totalLaunchBoxes > 0 && (
            <div className="text-right">
              <span className="text-xs font-semibold uppercase text-gray-400 block">{t('massas.liveUnits')}</span>
              <span className="text-base font-bold text-amber-700">
                {totalLaunchBoxes} cx · {totalLaunchUnits} un
              </span>
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
          {products.map((product) => {
            const currentBoxes = quantities[product.code] || '';
            const numBoxes = parseInt(currentBoxes || '0', 10);
            const liveUnits = numBoxes * product.unitsPerBox;

            return (
              <div
                key={product.code}
                className={`p-3 rounded-lg border transition-all ${
                  numBoxes > 0 ? 'bg-amber-50/50 border-amber-300 shadow-xs' : 'bg-gray-50/50 border-gray-200'
                }`}
              >
                <div className="flex justify-between items-start gap-1 mb-1">
                  {/* Sin `truncate`: en un teléfono el nombre quedaba "MASSA LASANHA TRADICIONAL ..." y el
                      título del tooltip no existe en una pantalla táctil. Envuelve y se lee entero. */}
                  <span className="text-xs font-bold text-gray-800 leading-snug break-words min-w-0" title={product.name}>
                    {product.name}
                  </span>
                  <span className="text-[11px] text-gray-500 font-medium shrink-0">
                    {product.unitsPerBox} un/cx
                  </span>
                </div>

                <div className="mt-2 flex items-center gap-2">
                  <div className="flex-1">
                    <Input
                      type="number"
                      min="0"
                      step="1"
                      inputMode="numeric"
                      placeholder="0 cx"
                      value={currentBoxes}
                      onChange={(e) => onQuantityChange(product.code, e.target.value)}
                      className="h-9 text-sm text-center font-bold"
                    />
                  </div>
                </div>

                <div className="mt-1 text-right">
                  <span className={`text-xs font-semibold ${numBoxes > 0 ? 'text-amber-700' : 'text-gray-400'}`}>
                    {liveUnits} un
                  </span>
                </div>
              </div>
            );
          })}
        </div>

        {/* Action Row: Botón Lançar SIN botón resetear */}
        <div className="mt-6 flex flex-col sm:flex-row items-center justify-end gap-3 pt-4 border-t border-gray-100">
          <Button
            type="button"
            onClick={onLaunch}
            disabled={itemsToLaunch.length === 0 || submitting}
            className="w-full sm:w-auto bg-amber-600 hover:bg-amber-700 text-white font-semibold px-6"
          >
            <Send className="h-4 w-4 mr-2" />
            {submitting ? t('massas.launching') : t('massas.launch')}
            {totalLaunchBoxes > 0 && ` (${totalLaunchBoxes} cx)`}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
