import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Link } from 'react-router-dom';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import {
  groupByCategory,
  mailtoLink,
  stockAddDisabled,
  stockVisibleErrors,
  stockEmailBody,
  stockMessage,
  stockSummary,
  type StockDay,
  type StockProduct,
} from '../lib/stock';
import { formatPhoneMask, toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { StockCollectiveSummary } from '../components/stock/StockCollectiveSummary';
import { saoPauloToday } from '../lib/date';

/**
 * The stock counter.
 *
 * The screen collects what an operator counts and the API owns the arithmetic, so this file never
 * multiplies anything: it sends the boxes, reloads the day and shows what the server answered. That is
 * what keeps the two numbers agreeing with each other, and it is why a reset is a call and not a local
 * zero.
 *
 * The catalogue arrives with the day — every product of the catalogue, at zero if nothing was counted —
 * so the sections below are derived from the API rather than from a second copy of the list kept here.
 *
 * Two layouts, one behaviour. The counter is used standing at a shelf with a phone, so below `md` each
 * product is a card with the metrics it is being counted against and a row big enough to tap; from `md`
 * up it is the dense table an office screen wants. Both read the same state and call the same handlers,
 * because two layouts that drift are two counters.
 */
const EMAIL_TO = 'xmitrindade@hotmail.com';

interface HistoryDay {
  date: string;
  products: number;
  boxes: number;
  units: number;
}

export function StockCalculatorPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saoPauloToday());
  const [day, setDay] = useState<StockDay | null>(null);
  const [history, setHistory] = useState<HistoryDay[]>([]);
  const [freeNumber, setFreeNumber] = useState('');
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [tab, setTab] = useState('');
  // El campo en edicion y los ya dejados: el cartel es una cuestion de momento, no solo del numero.
  const [focused, setFocused] = useState<string | null>(null);
  const [blurred, setBlurred] = useState<Record<string, boolean>>({});

  const canEdit = user?.role === 'Administrador';

  const load = useCallback(
    async (target: string) => {
      setError('');
      try {
        const loaded = await api.get<StockDay>(`/stock?date=${target}`);
        setDay(loaded);
      } catch {
        setError(t('stock.loadError'));
      }
      try {
        const counted = await api.get<{ days: HistoryDay[] }>('/stock/history');
        setHistory(counted.days ?? []);
      } catch {
        setHistory([]);
      }
    },
    [t]
  );

  useEffect(() => {
    void load(date);
  }, [date, load]);

  const summary = useMemo(() => (day === null ? [] : stockSummary(day)), [day]);
  const groups = useMemo(() => (day === null ? [] : groupByCategory(day.products)), [day]);
  const active = useMemo(() => {
    if (groups.length === 0) return null;
    return groups.find((group) => group.category === tab) ?? groups[0];
  }, [groups, tab]);

  const message = day === null ? '' : stockMessage(day);
  const emailBody = day === null ? '' : stockEmailBody(day);
  const targetNumber = toWhatsAppNumber(freeNumber);
  const visibleErrors = stockVisibleErrors(quantities, { blurred, focused });
  const boxes = (code: string) => quantities[code] ?? '';

  async function sendEntry(product: StockProduct) {
    if (!canEdit) {
      setNote(t('stock.adminOnly'));
      return;
    }
    const raw = (quantities[product.code] ?? '').trim();
    const boxes = Number(raw);
    if (raw === '' || !Number.isInteger(boxes) || boxes <= 0) {
      setNote(t('stock.invalidBoxes'));
      return;
    }
    setBusy(true);
    setNote('');
    try {
      await api.post<StockProduct>('/stock/add-entry', { date, product_code: product.code, boxes_added: boxes });
      setQuantities({ ...quantities, [product.code]: '' });
      await load(date);
      setNote(t('stock.saved'));
    } catch (err: any) {
      if (err?.status === 403) {
        setNote(t('stock.adminOnly'));
      } else {
        setNote(t('stock.saveError'));
      }
    } finally {
      setBusy(false);
    }
  }

  async function resetProduct(product: StockProduct) {
    if (!canEdit) {
      setNote(t('stock.adminOnly'));
      return;
    }
    setBusy(true);
    setNote('');
    try {
      await api.post<StockProduct>('/stock/reset-product', { date, product_code: product.code });
      await load(date);
      setNote(t('stock.resetDone'));
    } catch (err: any) {
      if (err?.status === 403) {
        setNote(t('stock.adminOnly'));
      } else {
        setNote(t('stock.saveError'));
      }
    } finally {
      setBusy(false);
    }
  }

  async function shareWhatsApp() {
    if (day === null) return;
    setNote('');
    if (targetNumber !== '') {
      const link = waMeLink(targetNumber, message);
      if (link === null) {
        setNote(t('stock.unavailable'));
        return;
      }
      window.open(link, '_blank', 'noopener');
      setNote(t('stock.opened'));
      return;
    }
    const outcome = await shareOrCopy(message);
    if (outcome === 'copied') setNote(t('stock.copied'));
    else if (outcome === 'failed') setNote(t('stock.unavailable'));
  }

  async function sendEmail() {
    if (day === null) return;
    setNote('');
    const link = mailtoLink(EMAIL_TO, `${t('stock.emailSubject')} ${date}`, emailBody);
    try {
      window.location.href = link;
      setNote(t('stock.opened'));
    } catch {
      // A browser with no mail handler still has a clipboard, and the count is worth more copied than lost.
      const outcome = await shareOrCopy(emailBody);
      if (outcome === 'copied') setNote(t('stock.copied'));
      else setNote(t('stock.unavailable'));
    }
  }

  return (
    <div className="p-4 space-y-4" data-testid="stock-calculator">
      {/* En un teléfono el título, la ayuda y el botón de histórico no entran en una fila: con el
          botón forzado al lado, "Calculador de Estoque" se partía en tres renglones y la ayuda
          quedaba colgada entre ellos. Debajo de `sm` el encabezado se apila, el botón ocupa el ancho
          y el título conserva su renglón. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-xl font-semibold text-gray-800">{t('stock.title')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.stock.title')}
              subtitle={t('moduleHelp.stock.subtitle')}
              description={t('moduleHelp.stock.description')}
              sections={t('moduleHelp.stock.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500">{canEdit ? t('stock.subtitle') : t('stock.readOnly')}</p>
        </div>
        <Link to="/stock-history" className="w-full sm:w-auto shrink-0">
          <Button variant="outline" className="w-full sm:w-auto" data-testid="stock-open-history">
            {t('stock.goToHistory')}
          </Button>
        </Link>
      </div>

      <Card>
        <CardContent className="p-3 space-y-3">
          {/* La fecha y los días ya contados son dos cosas distintas y en un teléfono se separan:
              el campo arriba a todo el ancho, y los chips en una sola fila con scroll horizontal en
              lugar de partirse en renglones quebrados de dos botones. */}
          <div className="flex flex-col sm:flex-row sm:items-end gap-3">
            <div className="w-full sm:w-auto">
              <label className="block text-xs font-medium text-gray-600">{t('stock.date')}</label>
              <Input
                type="date"
                value={date}
                max={saoPauloToday()}
                onChange={(e) => setDate(e.target.value)}
                className="w-full sm:w-auto"
                data-testid="stock-date"
              />
            </div>
            {history.length > 0 && (
              <div className="w-full sm:w-auto sm:flex-1 min-w-0">
                <span className="block text-xs text-gray-500 sm:mb-1">{t('stock.history')}</span>
                <div className="flex items-center gap-2 overflow-x-auto pb-1 max-w-full scrollbar-none" data-testid="stock-history-days">
                  {/* Tres en un teléfono y cinco en una pantalla ancha: el resto sigue alcanzable por
                      la fecha, y así la fila no se lleva media pantalla del contador. */}
                  {history.slice(0, 5).map((entry, idx) => (
                    <button
                      key={entry.date}
                      onClick={() => setDate(entry.date)}
                      className={`${idx >= 3 ? 'hidden sm:inline-flex' : 'inline-flex'} shrink-0 text-xs px-2.5 py-1 rounded-md border font-medium ${entry.date === date ? 'bg-gray-800 border-gray-800 text-white' : 'bg-white border-gray-300 text-gray-700'}`}
                      data-testid={`stock-history-${entry.date}`}
                    >
                      {entry.date.slice(8, 10)}/{entry.date.slice(5, 7)} · {entry.boxes}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>

          <div className="flex items-center gap-2 border-b">
            {groups.map((group) => (
              <button
                key={group.category}
                onClick={() => setTab(group.category)}
                className={`px-3 py-2 text-sm border-b-2 ${active?.category === group.category ? 'border-gray-800 font-medium text-gray-900' : 'border-transparent text-gray-500'}`}
                data-testid={`stock-tab-${group.category}`}
              >
                {t(group.category === 'Massas' ? 'stock.massas' : 'stock.terceirizados')} ({group.products.length})
              </button>
            ))}
          </div>

          {error !== '' && <p className="text-sm text-red-600">{error}</p>}

          {active !== null && (
            <div className="space-y-3 md:hidden" data-testid="stock-cards">
              {active.products.map((product) => (
                <div key={product.code} className="border rounded-lg p-3 space-y-2" data-testid={`stock-card-${product.code}`}>
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="font-medium text-gray-900">{product.name}</span>
                    <span className="text-xs text-gray-500 font-mono">Cód: {product.code}</span>
                  </div>
                  <div className="flex gap-4 text-sm text-gray-600">
                    <span>
                      {t('stock.boxes')}: <strong className="text-gray-900" data-testid={`stock-mobile-boxes-${product.code}`}>{product.boxes}</strong>
                    </span>
                    <span>
                      {t('stock.units')}: <strong className="text-gray-900" data-testid={`stock-mobile-units-${product.code}`}>{product.units}</strong>
                      <span className="text-xs text-gray-400"> ({product.unitsPerBox}/cx)</span>
                    </span>
                  </div>
                  {/* Se envuelve en vez de desbordar: en un celular de 360px el campo, el aviso de
                      error y los dos botones no entran en una sola línea, y sin wrap el último botón
                      terminaba salido del margen de la tarjeta. Con wrap, la fila baja entera. */}
                  {canEdit && (
                    <div className="flex flex-wrap items-center gap-2">
                      <Input
                        value={quantities[product.code] ?? ''}
                        disabled={!canEdit}
                        onFocus={() => setFocused(product.code)}
                        onChange={(e) => {
                          setQuantities({ ...quantities, [product.code]: e.target.value });
                          // Volver a escribir apaga el cartel: la correccion esta en curso.
                          setBlurred({ ...blurred, [product.code]: false });
                        }}
                        onBlur={() => {
                          setBlurred({ ...blurred, [product.code]: true });
                          setFocused(null);
                        }}
                        inputMode="numeric"
                        placeholder="0"
                        aria-label={product.name}
                        className="w-20"
                        data-testid={`stock-mobile-input-${product.code}`}
                      />
                      {visibleErrors.includes(product.code) && (
                        <span className="text-xs text-red-600" data-testid={`stock-mobile-error-${product.code}`}>
                          {t('stock.invalidBoxesInline')}
                        </span>
                      )}
                      <Button
                        onClick={() => sendEntry(product)}
                        disabled={!canEdit || busy || stockAddDisabled(boxes(product.code))}
                        size="sm"
                        data-testid={`stock-mobile-add-${product.code}`}
                      >
                        {t('stock.add')}
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => resetProduct(product)}
                        disabled={!canEdit || busy || product.boxes === 0}
                        size="sm"
                        data-testid={`stock-mobile-reset-${product.code}`}
                      >
                        {t('stock.reset')}
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {active !== null && (
            <div className="hidden md:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('stock.code')}</th>
                    <th className="py-2 pr-2">{t('stock.product')}</th>
                    <th className="py-2 pr-2">{t('stock.boxes')}</th>
                    <th className="py-2 pr-2">{t('stock.units')}</th>
                    {canEdit && <th className="py-2 pr-2">{t('stock.addBoxes')}</th>}
                  </tr>
                </thead>
                <tbody>
                  {active.products.map((product) => (
                    <tr key={product.code} className="border-b last:border-0">
                      <td className="py-2 pr-2 text-gray-500">{product.code}</td>
                      <td className="py-2 pr-2">{product.name}</td>
                      <td className="py-2 pr-2 font-medium" data-testid={`stock-boxes-${product.code}`}>
                        {product.boxes}
                      </td>
                      <td className="py-2 pr-2 text-gray-600" data-testid={`stock-units-${product.code}`}>
                        {product.units} <span className="text-xs text-gray-400">({product.unitsPerBox}/cx)</span>
                      </td>
                      {canEdit && (
                        <td className="py-2 pr-2">
                          <div className="flex items-center gap-2">
                            <Input
                              value={quantities[product.code] ?? ''}
                              disabled={!canEdit}
                              onFocus={() => setFocused(product.code)}
                              onChange={(e) => {
                                setQuantities({ ...quantities, [product.code]: e.target.value });
                                // Volver a escribir apaga el cartel: la correccion esta en curso.
                                setBlurred({ ...blurred, [product.code]: false });
                              }}
                              onBlur={() => {
                                setBlurred({ ...blurred, [product.code]: true });
                                setFocused(null);
                              }}
                              inputMode="numeric"
                              placeholder="0"
                              aria-label={product.name}
                              className="w-20"
                              data-testid={`stock-input-${product.code}`}
                            />
                            {visibleErrors.includes(product.code) && (
                              <span className="text-xs text-red-600" data-testid={`stock-error-${product.code}`}>
                                {t('stock.invalidBoxesInline')}
                              </span>
                            )}
                            <Button
                              onClick={() => sendEntry(product)}
                              disabled={!canEdit || busy || stockAddDisabled(boxes(product.code))}
                              data-testid={`stock-add-${product.code}`}
                            >
                              {t('stock.add')}
                            </Button>
                            <Button
                              variant="outline"
                              onClick={() => resetProduct(product)}
                              disabled={!canEdit || busy || product.boxes === 0}
                              data-testid={`stock-reset-${product.code}`}
                            >
                              {t('stock.reset')}
                            </Button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="flex items-center justify-between pt-2 border-t">
            <span className="text-sm text-gray-500">{t('stock.total')}</span>
            <span className="text-lg font-semibold text-green-700" data-testid="stock-total">
              {day?.totalBoxes ?? 0} {t('stock.boxesWord')} · {day?.totalUnits ?? 0} {t('stock.unitsWord')}
            </span>
          </div>
        </CardContent>
      </Card>

      <StockCollectiveSummary products={summary} testIdPrefix="stock" />

      <Card>
        <CardContent className="p-3 space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600">{t('stock.freeNumber')}</label>
            <Input
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={freeNumber}
              onChange={(e) => setFreeNumber(formatPhoneMask(e.target.value))}
              placeholder="(11) 9XXXX-XXXX"
              data-testid="stock-free-number"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-600">{t('stock.message')}</label>
            <pre
              className="whitespace-pre-wrap text-xs bg-gray-50 border rounded-md p-2 max-h-64 overflow-auto"
              data-testid="stock-message"
            >
              {message}
            </pre>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={shareWhatsApp} disabled={day === null} data-testid="stock-share">
              {t('stock.shareWhatsapp')}
            </Button>
            <Button variant="outline" onClick={sendEmail} disabled={day === null} data-testid="stock-email">
              {t('stock.shareEmail')}
            </Button>
            <span className="text-xs text-gray-400">{EMAIL_TO}</span>
            {note !== '' && (
              <span className="text-xs text-gray-500" data-testid="stock-note">
                {note}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

    </div>
  );
}
