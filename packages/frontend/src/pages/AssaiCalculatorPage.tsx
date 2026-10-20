import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import {
  ASSAI_PRODUCTS,
  ASSAI_WEEKDAY_OPTIONS,
  assaiHasError,
  assaiLines,
  assaiMessage,
  assaiTotalBoxes,
  assaiVisibleErrors,
} from '../lib/assai';
import { formatPhoneMask, toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';

/**
 * The Assaí order calculator.
 *
 * The arithmetic lives in `src/lib/assai.ts` with its own tests, because the rule that matters is a
 * business rule and not a rendering detail: a box holds whole units, so an order only exists in whole
 * boxes. This screen is the table that collects the quantities, the verdict per product, and the two ways
 * the order leaves: a private chat to a driver the fleet already has, or any number the operator types.
 */
export function AssaiCalculatorPage() {
  const { t } = useTranslation();
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  // Un error solo se muestra cuando el operador ya salio del campo: mientras tipea el primer numero,
  // decirle que esta mal es ruido, porque todavia no termino de escribirlo.
  const [blurred, setBlurred] = useState<Record<string, boolean>>({});
  // El campo que se esta editando ahora, y si ya se intento compartir con el pedido incompleto.
  const [focused, setFocused] = useState<string | null>(null);
  const [triedShare, setTriedShare] = useState(false);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [freeNumber, setFreeNumber] = useState('');
  const [note, setNote] = useState('');

  const lines = useMemo(() => assaiLines(quantities), [quantities]);
  const hasError = assaiHasError(lines);
  const visibleErrors = assaiVisibleErrors(lines, { blurred, focused });
  const hasVisibleError = visibleErrors.length > 0;
  const totalBoxes = assaiTotalBoxes(lines);
  const message = assaiMessage(lines, selectedDay);

  const targetNumber = toWhatsAppNumber(freeNumber);

  async function handleShare() {
    setNote('');
    if (hasError) {
      setTriedShare(true);
      return;
    }

    if (targetNumber !== '') {
      const link = waMeLink(targetNumber, message);
      if (link === null) {
        setNote(t('assai.shareUnavailable'));
        return;
      }
      window.open(link, '_blank', 'noopener');
      setNote(t('assai.shareOpened'));
      return;
    }

    // With no number chosen the order still leaves: the platform share sheet, whose fallback is the
    // clipboard, and neither invents a recipient.
    const outcome = await shareOrCopy(message);
    if (outcome === 'copied') setNote(t('assai.shareCopied'));
    else if (outcome === 'failed') setNote(t('assai.shareUnavailable'));
  }

  return (
    <div className="p-4 space-y-4" data-testid="assai-calculator">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-gray-800">{t('assai.title')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.assai.title')}
              subtitle={t('moduleHelp.assai.subtitle')}
              description={t('moduleHelp.assai.description')}
              sections={t('moduleHelp.assai.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500">{t('assai.subtitle')}</p>
        </div>
      </div>

      <Card>
        <CardContent className="p-3">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-xs text-gray-500 uppercase">
                <th className="py-2 pr-2">{t('assai.product')}</th>
                <th className="py-2 pr-2">{t('assai.perBox')}</th>
                <th className="py-2 pr-2">{t('assai.units')}</th>
                <th className="py-2 pr-2">{t('assai.boxes')}</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr key={line.product} className="border-b last:border-0">
                  <td className="py-2 pr-2">{line.product}</td>
                  <td className="py-2 pr-2 text-gray-500">{line.unitsPerBox}</td>
                  <td className="py-2 pr-2">
                    <Input
                      value={quantities[line.product] ?? ''}
                      inputMode="numeric"
                      placeholder="0"
                      aria-label={line.product}
                      data-testid={`assai-units-${line.product}`}
                      onFocus={() => setFocused(line.product)}
                      onChange={(e) => {
                        setQuantities({ ...quantities, [line.product]: e.target.value });
                        // Volver a escribir apaga el cartel del campo: la correccion esta en curso.
                        setBlurred({ ...blurred, [line.product]: false });
                      }}
                      onBlur={() => {
                        setBlurred({ ...blurred, [line.product]: true });
                        setFocused(null);
                      }}
                    />
                    {visibleErrors.includes(line.product) && (
                      <span className="text-xs text-red-600" data-testid={`assai-error-${line.product}`}>
                        {t('assai.errorUnits')}
                      </span>
                    )}
                  </td>
                  <td className="py-2 pr-2" data-testid={`assai-boxes-${line.product}`}>
                    {line.filled && line.exact ? line.boxes : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="flex items-center justify-between pt-3 border-t mt-2">
            <span className="text-sm text-gray-500">{t('assai.totalLabel')}</span>
            <span className="text-lg font-semibold text-green-700" data-testid="assai-total">
              {totalBoxes} {t('assai.boxesWord')}
            </span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-3 space-y-3">
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="block text-xs font-medium text-gray-600">{t('assai.weekdayLabel')}</label>
              {selectedDay && (
                <button
                  type="button"
                  onClick={() => setSelectedDay(null)}
                  className="text-[11px] text-gray-500 hover:text-gray-700 underline"
                  data-testid="assai-weekday-clear"
                >
                  {t('assai.clearWeekday')}
                </button>
              )}
            </div>
            <div
              className="grid grid-cols-2 sm:grid-cols-4 gap-1.5"
              role="radiogroup"
              aria-label={t('assai.weekdayLabel')}
            >
              {ASSAI_WEEKDAY_OPTIONS.map((opt) => {
                const isSelected = selectedDay === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    role="radio"
                    aria-checked={isSelected}
                    data-testid={`assai-weekday-${opt.id}`}
                    onClick={() => setSelectedDay(isSelected ? null : opt.value)}
                    className={`py-2 px-3 text-xs font-medium rounded-lg border text-center transition-all ${
                      isSelected
                        ? 'bg-green-600 text-white border-green-600 shadow-sm'
                        : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'
                    }`}
                  >
                    {t(opt.labelKey)}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-600">{t('assai.freeNumber')}</label>
            <Input
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={freeNumber}
              onChange={(e) => setFreeNumber(formatPhoneMask(e.target.value))}
              placeholder="(11) 9XXXX-XXXX"
              data-testid="assai-free-number"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-600">{t('assai.message')}</label>
            <pre
              className="whitespace-pre-wrap text-xs bg-gray-50 border rounded-md p-2 max-h-56 overflow-auto"
              data-testid="assai-message"
            >
              {message}
            </pre>
          </div>

          <div className="flex items-center gap-3">
            <Button onClick={handleShare} disabled={hasError} data-testid="assai-share">
              {t('assai.share')}
            </Button>
            {hasError && (hasVisibleError || triedShare) && (
              <span className="text-xs text-red-600" data-testid="assai-blocked">
                {t('assai.blocked')}
              </span>
            )}
            {note !== '' && (
              <span className="text-xs text-gray-500" data-testid="assai-note">
                {note}
              </span>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
