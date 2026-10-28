import { useMemo, type ReactNode } from 'react';
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
  /** Optional control rendered beside the launch button, e.g. the company-sync switch. */
  erpControl?: ReactNode;
}

export function MassasLaunchGrid({
  products,
  quantities,
  onQuantityChange,
  onLaunch,
  submitting,
  date,
  erpControl,
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
        {/* En un teléfono el título y el vivo de cajas no caben en un renglón: el título se partía y
            los totales se comían el ancho que la grilla necesita. Debajo de `sm` bajan a dos filas. */}
        <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">{t('massas.title')}</h2>
            <p className="text-xs text-gray-500">
              {t('massas.liveUnits')} · {t('massas.date')}: {date}
            </p>
          </div>
          {totalLaunchBoxes > 0 && (
            <div className="text-right sm:shrink-0">
              <span className="text-xs font-semibold uppercase text-gray-500 block">{t('massas.liveUnits')}</span>
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

                {/* Las unidades nacen de las cajas: van en el mismo renglón, al lado del campo, en vez
                    de gastar un renglón propio en cada tarjeta. Diez productos son diez renglones
                    menos que recorrer con el pulgar. */}
                <div className="mt-2 flex items-center gap-2">
                  <div className="min-w-0 flex-1">
                    <Input
                      type="number"
                      min="0"
                      step="1"
                      inputMode="numeric"
                      placeholder="0 cx"
                      value={currentBoxes}
                      onChange={(e) => onQuantityChange(product.code, e.target.value)}
                      className="h-11 text-base text-center font-bold sm:h-9 sm:text-sm"
                    />
                  </div>
                  <span
                    className={`w-14 shrink-0 text-right text-xs font-semibold ${
                      numBoxes > 0 ? 'text-amber-700' : 'text-gray-500'
                    }`}
                  >
                    {liveUnits} un
                  </span>
                </div>
              </div>
            );
          })}
        </div>

        {/* Action Row: Botón Lançar SIN botón resetear */}
        <div className="mt-6 flex flex-col sm:flex-row items-center justify-end gap-3 pt-4 border-t border-gray-100">
          {erpControl && <div className="w-full sm:w-auto sm:mr-auto">{erpControl}</div>}
          <Button
            type="button"
            onClick={onLaunch}
            disabled={itemsToLaunch.length === 0 || submitting}
            className="min-h-[44px] w-full bg-amber-600 px-6 font-semibold text-white hover:bg-amber-700 sm:w-auto"
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
