import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { mailtoLink, saopauloToday, stockEmailBody, stockMessage, type StockDay, type StockProduct } from '../lib/stock';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers "what is on the shelf now"; this answers "what did we count that day", which is a
 * different question asked at a different moment, so it gets its own route rather than a panel floating
 * over the counter. Two rules come with it: only what was counted is listed, because a table of
 * twenty-five rows with twenty-four zeroes hides the one row somebody came for; and the role decides
 * whether the visit is a reading or a correction, with the server deciding the same thing again.
 */
const EMAIL_TO = 'wilkin.barban@gmail.com';

interface HistoryDay {
  date: string;
  products: number;
  boxes: number;
  units: number;
}

export function StockHistoryPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saopauloToday());
  const [day, setDay] = useState<StockDay | null>(null);
  const [days, setDays] = useState<HistoryDay[]>([]);
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [freeNumber, setFreeNumber] = useState('');

  const canEdit = user?.role === 'Administrador';

  const load = useCallback(
    async (target: string) => {
      setError('');
      try {
        setDay(await api.get<StockDay>(`/stock?date=${target}`));
      } catch {
        setError(t('stock.loadError'));
      }
      try {
        const counted = await api.get<{ days: HistoryDay[] }>('/stock/history');
        setDays(counted.days ?? []);
      } catch {
        setDays([]);
      }
    },
    [t]
  );

  useEffect(() => {
    void load(date);
  }, [date, load]);

  const counted = day === null ? [] : day.products.filter((product) => product.units > 0);
  const message = day === null ? '' : stockMessage(day);
  const emailBody = day === null ? '' : stockEmailBody(day);
  const targetNumber = freeNumber.trim();

  async function sendEntry(product: StockProduct) {
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
    } catch {
      setNote(t('stock.saveError'));
    } finally {
      setBusy(false);
    }
  }

  async function resetProduct(product: StockProduct) {
    setBusy(true);
    setNote('');
    try {
      await api.post<StockProduct>('/stock/reset-product', { date, product_code: product.code });
      await load(date);
      setNote(t('stock.resetDone'));
    } catch {
      setNote(t('stock.saveError'));
    } finally {
      setBusy(false);
    }
  }

  async function shareWhatsApp() {
    if (day === null) return;
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
    try {
      window.location.href = mailtoLink(EMAIL_TO, `${t('stock.emailSubject')} ${date}`, emailBody);
      setNote(t('stock.opened'));
    } catch {
      const outcome = await shareOrCopy(emailBody);
      if (outcome === 'copied') setNote(t('stock.copied'));
      else setNote(t('stock.unavailable'));
    }
  }

  const totalBoxes = counted.reduce((sum, product) => sum + product.boxes, 0);
  const totalUnits = counted.reduce((sum, product) => sum + product.units, 0);

  return (
    <div className="p-4 space-y-4" data-testid="stock-history-page">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-gray-800">{t('stock.historyTitle')}</h1>
          <p className="text-sm text-gray-500">{canEdit ? t('stock.historySubtitle') : t('stock.readOnly')}</p>
        </div>
        <Link to="/stock-calculator">
          <Button variant="outline" data-testid="stock-history-back">
            {t('stock.backToCounter')}
          </Button>
        </Link>
      </div>

      <Card>
        <CardContent className="p-3 space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-600">{t('stock.date')}</label>
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} data-testid="stock-history-date" />
            </div>
            {days.length > 0 && (
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs text-gray-500">{t('stock.history')}</span>
                {days.slice(0, 10).map((entry) => (
                  <button
                    key={entry.date}
                    onClick={() => setDate(entry.date)}
                    className={`text-xs px-2 py-1 rounded border ${entry.date === date ? 'bg-gray-800 text-white' : 'bg-white text-gray-700'}`}
                    data-testid={`stock-history-day-${entry.date}`}
                  >
                    {entry.date.slice(8, 10)}/{entry.date.slice(5, 7)} · {entry.units}
                  </button>
                ))}
              </div>
            )}
          </div>

          {error !== '' && <p className="text-sm text-red-600">{error}</p>}

          {counted.length === 0 && (
            <p className="text-sm text-gray-500" data-testid="stock-history-empty">
              {t('stock.emptyHistory')}
            </p>
          )}

          {counted.length > 0 && (
            <>
              <div className="space-y-3 md:hidden" data-testid="stock-history-cards">
                {counted.map((product) => (
                  <div key={product.code} className="border rounded-lg p-3 space-y-2" data-testid={`stock-history-card-${product.code}`}>
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="font-medium text-gray-900">{product.name}</span>
                      <span className="text-xs text-gray-500">{product.code}</span>
                    </div>
                    <div className="text-xs text-gray-500">{product.category}</div>
                    <div className="flex gap-4 text-sm text-gray-600">
                      <span>
                        {t('stock.boxes')}: <strong className="text-gray-900" data-testid={`stock-history-boxes-${product.code}`}>{product.boxes}</strong>
                      </span>
                      <span>
                        {t('stock.units')}: <strong className="text-gray-900" data-testid={`stock-history-units-${product.code}`}>{product.units}</strong>
                      </span>
                    </div>
                    {canEdit && (
                      <div className="flex items-center gap-2">
                        <Input
                          value={quantities[product.code] ?? ''}
                          onChange={(e) => setQuantities({ ...quantities, [product.code]: e.target.value })}
                          inputMode="numeric"
                          placeholder="0"
                          aria-label={product.name}
                          className="w-20"
                          data-testid={`stock-history-mobile-input-${product.code}`}
                        />
                        <Button onClick={() => sendEntry(product)} disabled={busy} data-testid={`stock-history-mobile-add-${product.code}`}>
                          {t('stock.add')}
                        </Button>
                        <Button variant="outline" onClick={() => resetProduct(product)} disabled={busy} data-testid={`stock-history-mobile-reset-${product.code}`}>
                          {t('stock.reset')}
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>

              <div className="hidden md:block overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-gray-500 uppercase">
                      <th className="py-2 pr-2">{t('stock.category')}</th>
                      <th className="py-2 pr-2">{t('stock.code')}</th>
                      <th className="py-2 pr-2">{t('stock.product')}</th>
                      <th className="py-2 pr-2">{t('stock.boxes')}</th>
                      <th className="py-2 pr-2">{t('stock.units')}</th>
                      {canEdit && <th className="py-2 pr-2">{t('stock.addBoxes')}</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {counted.map((product) => (
                      <tr key={product.code} className="border-b last:border-0" data-testid={`stock-history-row-${product.code}`}>
                        <td className="py-2 pr-2 text-gray-500">{product.category}</td>
                        <td className="py-2 pr-2 text-gray-500">{product.code}</td>
                        <td className="py-2 pr-2">{product.name}</td>
                        <td className="py-2 pr-2 font-medium">{product.boxes}</td>
                        <td className="py-2 pr-2 text-gray-600">{product.units}</td>
                        {canEdit && (
                          <td className="py-2 pr-2">
                            <div className="flex items-center gap-2">
                              <Input
                                value={quantities[product.code] ?? ''}
                                onChange={(e) => setQuantities({ ...quantities, [product.code]: e.target.value })}
                                inputMode="numeric"
                                placeholder="0"
                                aria-label={product.name}
                                className="w-20"
                                data-testid={`stock-history-input-${product.code}`}
                              />
                              <Button onClick={() => sendEntry(product)} disabled={busy} data-testid={`stock-history-add-${product.code}`}>
                                {t('stock.add')}
                              </Button>
                              <Button variant="outline" onClick={() => resetProduct(product)} disabled={busy} data-testid={`stock-history-reset-${product.code}`}>
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

              <div className="flex items-center justify-between pt-2 border-t">
                <span className="text-sm text-gray-500">{t('stock.total')}</span>
                <span className="text-lg font-semibold text-green-700" data-testid="stock-history-total">
                  {totalBoxes} {t('stock.boxesWord')} · {totalUnits} {t('stock.unitsWord')}
                </span>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-3 space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600">{t('stock.freeNumber')}</label>
            <Input value={freeNumber} onChange={(e) => setFreeNumber(e.target.value)} placeholder="+55 …" data-testid="stock-history-number" />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600">{t('stock.message')}</label>
            <pre className="whitespace-pre-wrap text-xs bg-gray-50 border rounded-md p-2 max-h-64 overflow-auto" data-testid="stock-history-message">
              {message}
            </pre>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={shareWhatsApp} disabled={day === null} data-testid="stock-history-share">
              {t('stock.shareWhatsapp')}
            </Button>
            <Button variant="outline" onClick={sendEmail} disabled={day === null} data-testid="stock-history-email">
              {t('stock.shareEmail')}
            </Button>
            <span className="text-xs text-gray-400">{EMAIL_TO}</span>
            {note !== '' && <span className="text-xs text-gray-500">{note}</span>}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
