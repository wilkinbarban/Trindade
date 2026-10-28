import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { mailtoLink, stockEmailBody, stockMessage, stockSummary, type StockDay } from '../lib/stock';
import { saoPauloDaysAgo, saoPauloYesterday } from '../lib/date';
import { formatPhoneMask, toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { StockCollectiveSummary } from '../components/stock/StockCollectiveSummary';

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers "what is on the shelf now"; this answers "what did we count that day", which is a
 * different question asked at a different moment, so it gets its own route rather than a panel floating
 * over the counter. What it answers with is the day's collective summary and nothing else: a reading,
 * not a second counter. That is why there is no quantity field here, why the newest day it will open is
 * yesterday — a day still being counted is the counter's business — and why the picker stops thirty days
 * back instead of pretending the whole archive is one scroll away.
 */
const EMAIL_TO = 'xmitrindade@hotmail.com';

export function StockHistoryPage() {
  const { t } = useTranslation();
  const [date, setDate] = useState(saoPauloYesterday());
  const [day, setDay] = useState<StockDay | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [freeNumber, setFreeNumber] = useState('');

  const load = useCallback(
    async (target: string) => {
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
    void load(date);
  }, [date, load]);

  const summary = day === null ? [] : stockSummary(day);
  const message = day === null ? '' : stockMessage(day);
  const emailBody = day === null ? '' : stockEmailBody(day);
  const targetNumber = toWhatsAppNumber(freeNumber);

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

  return (
    <div className="p-4 space-y-4" data-testid="stock-history-page">
      {/* Debajo de `sm` el encabezado se apila: con el botón al lado, "Histórico de Contagem" se
          partía en tres renglones y la ayuda quedaba colgada entre ellos. */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-xl font-semibold text-gray-800">{t('stock.historyTitle')}</h1>
            <ModuleHelpModal
              title={t('moduleHelp.stockHistory.title')}
              subtitle={t('moduleHelp.stockHistory.subtitle')}
              description={t('moduleHelp.stockHistory.description')}
              sections={t('moduleHelp.stockHistory.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500">{t('stock.historySubtitle')}</p>
        </div>
        {/* Como en el calculador: apilados en el teléfono, porque el campo de fecha de Safari no cabe en
            media pantalla, y en fila desde `sm`. Una sola fecha y dos topes: la última noche cerrada hacia
            atrás y treinta días de archivo. */}
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-end sm:shrink-0">
          <div className="w-full sm:w-auto">
            <label className="block text-xs font-medium text-gray-600" htmlFor="stock-history-date">
              {t('stock.date')}
            </label>
            <Input
              id="stock-history-date"
              type="date"
              value={date}
              min={saoPauloDaysAgo(30)}
              max={saoPauloYesterday()}
              onChange={(e) => setDate(e.target.value)}
              className="w-full sm:w-auto"
              data-testid="stock-history-date"
            />
          </div>
          <Link to="/stock-calculator" className="w-full sm:w-auto">
            <Button variant="outline" className="w-full sm:w-auto" data-testid="stock-history-back">
              {t('stock.backToCounter')}
            </Button>
          </Link>
        </div>
      </div>

      {error !== '' && (
        <p role="alert" className="p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg">
          {error}
        </p>
      )}

      <StockCollectiveSummary
        products={summary}
        testIdPrefix="stock-history"
        totalBoxes={day?.totalBoxes}
        totalUnits={day?.totalUnits}
      />

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
              data-testid="stock-history-number"
            />
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
