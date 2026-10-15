import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import {
  groupByCategory,
  mailtoLink,
  saopauloToday,
  stockEmailBody,
  stockMessage,
  type StockDay,
  type StockProduct,
} from '../lib/stock';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';

/**
 * The stock counter.
 *
 * The screen collects what an operator counts and the API owns the arithmetic, so this file never
 * multiplies anything: it sends the boxes, reloads the day and shows what the server answered. That is
 * what keeps the two numbers agreeing with each other, and it is why a reset is a call and not a
 * local zero.
 *
 * The catalogue arrives with the day — every product of the catalogue, at zero if nothing was counted —
 * so the sections below are derived from the API rather than from a second copy of the list kept here.
 */
const EMAIL_TO = 'wilkin.barban@gmail.com';

interface DriverRow {
  id: number;
  name: string;
  phone: string | null;
}

interface HistoryDay {
  date: string;
  products: number;
  boxes: number;
  units: number;
}

export function StockCalculatorPage() {
  const { t } = useTranslation();
  const [date, setDate] = useState(saopauloToday());
  const [day, setDay] = useState<StockDay | null>(null);
  const [history, setHistory] = useState<HistoryDay[]>([]);
  const [drivers, setDrivers] = useState<DriverRow[]>([]);
  const [selectedDriverId, setSelectedDriverId] = useState('');
  const [freeNumber, setFreeNumber] = useState('');
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [tab, setTab] = useState('');

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

  useEffect(() => {
    let cancelled = false;
    api
      .get<{ drivers: DriverRow[] }>('/loading/drivers')
      .then((data) => {
        if (!cancelled) setDrivers((data.drivers ?? []).filter((d) => (d.phone ?? '').trim() !== ''));
      })
      .catch(() => {
        if (!cancelled) setDrivers([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const groups = useMemo(() => (day === null ? [] : groupByCategory(day.products)), [day]);
  const active = useMemo(() => {
    if (groups.length === 0) return null;
    return groups.find((group) => group.category === tab) ?? groups[0];
  }, [groups, tab]);

  const message = day === null ? '' : stockMessage(day);
  const emailBody = day === null ? '' : stockEmailBody(day);
  const selectedDriver = drivers.find((d) => String(d.id) === selectedDriverId);
  const targetNumber = (selectedDriver?.phone ?? freeNumber).trim();
  const countedBoxes = day?.totalBoxes ?? 0;

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
      <div>
        <h1 className="text-xl font-semibold text-gray-800">{t('stock.title')}</h1>
        <p className="text-sm text-gray-500">{t('stock.subtitle')}</p>
      </div>

      <Card>
        <CardContent className="p-3 space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-600">{t('stock.date')}</label>
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} data-testid="stock-date" />
            </div>
            {history.length > 0 && (
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs text-gray-500">{t('stock.history')}</span>
                {history.slice(0, 6).map((entry) => (
                  <button
                    key={entry.date}
                    onClick={() => setDate(entry.date)}
                    className={`text-xs px-2 py-1 rounded border ${entry.date === date ? 'bg-gray-800 text-white' : 'bg-white text-gray-700'}`}
                    data-testid={`stock-history-${entry.date}`}
                  >
                    {entry.date.slice(8, 10)}/{entry.date.slice(5, 7)} · {entry.boxes}
                  </button>
                ))}
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
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-gray-500 uppercase">
                  <th className="py-2 pr-2">{t('stock.code')}</th>
                  <th className="py-2 pr-2">{t('stock.product')}</th>
                  <th className="py-2 pr-2">{t('stock.boxes')}</th>
                  <th className="py-2 pr-2">{t('stock.units')}</th>
                  <th className="py-2 pr-2">{t('stock.addBoxes')}</th>
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
                    <td className="py-2 pr-2">
                      <div className="flex items-center gap-2">
                        <Input
                          value={quantities[product.code] ?? ''}
                          onChange={(e) => setQuantities({ ...quantities, [product.code]: e.target.value })}
                          inputMode="numeric"
                          placeholder="0"
                          aria-label={product.name}
                          className="w-20"
                          data-testid={`stock-input-${product.code}`}
                        />
                        <Button onClick={() => sendEntry(product)} disabled={busy} data-testid={`stock-add-${product.code}`}>
                          {t('stock.add')}
                        </Button>
                        <Button
                          variant="outline"
                          onClick={() => resetProduct(product)}
                          disabled={busy || product.boxes === 0}
                          data-testid={`stock-reset-${product.code}`}
                        >
                          {t('stock.reset')}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="flex items-center justify-between pt-2 border-t">
            <span className="text-sm text-gray-500">{t('stock.total')}</span>
            <span className="text-lg font-semibold text-green-700" data-testid="stock-total">
              {countedBoxes} {t('stock.boxesWord')} · {day?.totalUnits ?? 0} {t('stock.unitsWord')}
            </span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-3 space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-gray-600">{t('stock.driver')}</label>
              <select
                value={selectedDriverId}
                onChange={(e) => setSelectedDriverId(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
                data-testid="stock-driver"
              >
                <option value="">{t('stock.selectDriver')}</option>
                {drivers.map((d) => (
                  <option key={d.id} value={String(d.id)}>
                    {d.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600">{t('stock.freeNumber')}</label>
              <Input
                value={freeNumber}
                onChange={(e) => setFreeNumber(e.target.value)}
                placeholder="+55 …"
                disabled={selectedDriverId !== ''}
                data-testid="stock-free-number"
              />
            </div>
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
