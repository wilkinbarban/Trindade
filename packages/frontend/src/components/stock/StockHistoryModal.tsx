import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useAuth } from '../../contexts/AuthContext';
import { Button } from '../ui/button';
import { Dialog } from '../ui/dialog';
import { Input } from '../ui/input';
import type { StockDay, StockProduct } from '../../lib/stock';

/**
 * A past day of counting, read by whoever needs to see it and written only by an administrator.
 *
 * Two rules meet here. The first is that a count is a document about a day: reading yesterday's numbers
 * is part of the job, and rewriting them is a correction, so the role decides which of the two a person
 * is doing — and the server decides it again, because a screen that only hides a button is not a
 * permission. The second is that only what was counted appears: a table of twenty-five rows with
 * twenty-four zeroes hides the one row somebody opened this to see.
 */
interface StockHistoryModalProps {
  open: boolean;
  onClose: () => void;
  /** The days the counter already has, newest first, offered as a shortcut to pick one. */
  days: Array<{ date: string; products: number; boxes: number; units: number }>;
  /** Called after a write, so the screen behind shows the day it just changed. */
  onChanged: () => void;
}

export function StockHistoryModal({ open, onClose, days, onChanged }: StockHistoryModalProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState('');
  const [day, setDay] = useState<StockDay | null>(null);
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');

  const canEdit = user?.role === 'Administrador';

  const load = useCallback(
    async (target: string) => {
      if (target === '') {
        setDay(null);
        return;
      }
      setError('');
      try {
        setDay(await api.get<StockDay>(`/stock?date=${target}`));
      } catch {
        setError(t('stock.loadError'));
      }
    },
    [t]
  );

  useEffect(() => {
    if (!open) return;
    const first = date !== '' ? date : (days[0]?.date ?? '');
    if (first !== date) setDate(first);
    void load(first);
  }, [open, date, days, load]);

  useEffect(() => {
    if (!open) setNote('');
  }, [open]);

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
      onChanged();
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
      onChanged();
      setNote(t('stock.resetDone'));
    } catch {
      setNote(t('stock.saveError'));
    } finally {
      setBusy(false);
    }
  }

  const counted = day === null ? [] : day.products.filter((product) => product.units > 0);

  return (
    <Dialog open={open} onClose={onClose} labelledBy="stock-history-title" panelTestId="stock-history-modal">
      <div className="space-y-4" data-testid="stock-history">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id="stock-history-title" className="text-lg font-semibold text-gray-800">
              {t('stock.historyTitle')}
            </h2>
            <p className="text-xs text-gray-500">{t('stock.readOnly')}</p>
          </div>
          <Button variant="outline" onClick={onClose} data-testid="stock-history-close">
            {t('stock.close')}
          </Button>
        </div>

        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-600">{t('stock.date')}</label>
            <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} data-testid="stock-history-date" />
          </div>
          {days.length > 0 && (
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-xs text-gray-500">{t('stock.history')}</span>
              {days.slice(0, 8).map((entry) => (
                <button
                  key={entry.date}
                  onClick={() => setDate(entry.date)}
                  className={`text-xs px-2 py-1 rounded border ${entry.date === date ? 'bg-gray-800 text-white' : 'bg-white text-gray-700'}`}
                  data-testid={`stock-history-day-${entry.date}`}
                >
                  {entry.date.slice(8, 10)}/{entry.date.slice(5, 7)} · {entry.boxes}
                </button>
              ))}
            </div>
          )}
        </div>

        {error !== '' && <p className="text-sm text-red-600">{error}</p>}

        {day !== null && counted.length === 0 && (
          <p className="text-sm text-gray-500" data-testid="stock-history-empty">
            {t('stock.emptyHistory')}
          </p>
        )}

        {counted.length > 0 && (
          <>
            <div className="space-y-3 md:hidden">
              {counted.map((product) => (
                <div key={product.code} className="border rounded-lg p-3 space-y-2" data-testid={`stock-history-card-${product.code}`}>
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="font-medium text-gray-900">{product.name}</span>
                    <span className="text-xs text-gray-500">{product.code}</span>
                  </div>
                  <div className="flex gap-4 text-sm text-gray-600">
                    <span>
                      {t('stock.boxes')}: <strong className="text-gray-900">{product.boxes}</strong>
                    </span>
                    <span>
                      {t('stock.units')}: <strong className="text-gray-900">{product.units}</strong>
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
              <span className="text-base font-semibold text-green-700" data-testid="stock-history-total">
                {counted.reduce((sum, product) => sum + product.boxes, 0)} {t('stock.boxesWord')} ·{' '}
                {counted.reduce((sum, product) => sum + product.units, 0)} {t('stock.unitsWord')}
              </span>
            </div>

            {!canEdit && <p className="text-xs text-gray-500">{t('stock.readOnly')}</p>}
            {note !== '' && <p className="text-xs text-gray-500">{note}</p>}
          </>
        )}
      </div>
    </Dialog>
  );
}
