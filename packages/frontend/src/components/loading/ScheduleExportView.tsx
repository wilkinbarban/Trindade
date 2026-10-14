import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  LucideX,
  LucideCopy,
  LucideCheck,
  LucideShare2,
  LucideMessageCircle,
} from 'lucide-react';
import { api } from '../../api/client';
import { Button } from '../ui/button';
import { LoadingSpinner } from '../ui/loading-spinner';
import { Dialog } from '../ui/dialog';
import { shareOrCopy, waMeLink } from '../../lib/whatsapp';
import type { DriverRow, ScheduleRow } from './ScheduleGrid';

export interface ScheduleExportViewProps {
  date: string;
  open: boolean;
  onClose: () => void;
}

export function ScheduleExportView({ date, open, onClose }: ScheduleExportViewProps) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [shareNote, setShareNote] = useState('');
  const [schedules, setSchedules] = useState<ScheduleRow[]>([]);
  const [drivers, setDrivers] = useState<DriverRow[]>([]);

  /**
   * The fleteros of this batch whose number is known, which are the ones a private message can reach.
   *
   * Built from the batch rather than from the whole fleet: sending a day's schedule to a driver who is
   * not on it is a message about a day that does not concern them.
   */
  const fleterosWithPhone = useMemo(() => {
    const assigned = new Set(
      schedules.map((row) => row.driver_id).filter((id): id is number => typeof id === 'number'),
    );
    return drivers.filter(
      (driver) => driver.driver_type === 'fletero' && Boolean(driver.phone?.trim()) && assigned.has(driver.id),
    );
  }, [schedules, drivers]);

  useEffect(() => {
    if (!open || !date) return;

    let cancelled = false;
    setError('');
    setShareNote('');
    setLoading(true);
    setCopied(false);

    // The private actions need the batch and the fleet. Neither can hide the text: the text is what the
    // dialog is for, so a fleet read that fails leaves the list empty and the rest of the dialog working.
    api
      .get<{ schedules: ScheduleRow[] }>(`/loading/schedules?date=${date}`)
      .then((res) => { if (!cancelled) setSchedules(res.schedules); })
      .catch(() => { if (!cancelled) setSchedules([]); });
    api
      .get<{ drivers: DriverRow[] }>('/loading/drivers')
      .then((res) => { if (!cancelled) setDrivers(res.drivers); })
      .catch(() => { if (!cancelled) setDrivers([]); });

    async function fetchExport() {
      try {
        const data = await api.get<{ text: string }>(
          `/loading/export?date=${date}`
        );
        if (!cancelled) {
          setText(data.text);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t('loading.exportError')
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    fetchExport();
    return () => {
      cancelled = true;
    };
  }, [open, date, t]);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      const el = document.getElementById('loading-export-text');
      if (el) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const sel = window.getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
      }
    }
  }

  /**
   * The native share, with the copy as its fallback: the outcome tells the operator which one happened,
   * and a dismissed sheet says nothing rather than pretending the text went somewhere.
   */
  async function handleShare() {
    setShareNote('');
    const outcome = await shareOrCopy(text);
    if (outcome === 'copied') {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
      setShareNote(t('loading.shareCopied'));
    } else if (outcome === 'failed') {
      setShareNote(t('loading.shareUnavailable'));
    }
  }


  if (!open) return null;

  return (
    <Dialog open={open} onClose={onClose} labelledBy="loading-export-title" panelTestId="loading-export-modal">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200 shrink-0">
          <h3 id="loading-export-title" className="text-lg font-semibold text-gray-800">
            {t('loading.exportTitle')}
          </h3>
          <Button
            variant="ghost"
            size="icon"
            onClick={onClose}
            aria-label={t('ui.close')}
            data-testid="loading-export-close"
          >
            <LucideX className="h-5 w-5" />
          </Button>
        </div>

        {/* Body */}
        <div className="p-4 overflow-auto flex-1">
          {loading && (
            <div className="flex items-center justify-center py-12">
              <LoadingSpinner />
            </div>
          )}

          {error && (
            <div role="alert" aria-live="assertive" className="p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
              {error}
            </div>
          )}

          {!loading && !error && (
            <div
              id="loading-export-text"
              className="bg-gray-50 rounded-lg p-4 text-sm font-mono whitespace-pre-wrap text-gray-800 leading-relaxed select-all"
              data-testid="loading-export-text"
            >
              {text}
            </div>
          )}
        </div>

        {/* Footer */}
        {!loading && !error && (
          <div className="flex flex-col gap-2 p-4 border-t border-gray-200 shrink-0">
            {shareNote && (
              <p className="text-xs text-gray-500" data-testid="loading-export-share-note">
                {shareNote}
              </p>
            )}
            {fleterosWithPhone.length > 0 && (
              <div data-testid="loading-export-fleteros">
                <p className="text-sm font-medium text-gray-700 mb-2">{t('loading.sendToFletero')}</p>
                <div className="flex flex-wrap gap-2">
                  {fleterosWithPhone.map((driver) => {
                    const href = waMeLink(driver.phone ?? '', text);
                    if (!href) return null;
                    return (
                      <a
                        key={driver.id}
                        href={href}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 rounded-md border border-green-300 bg-green-50 px-3 py-1.5 text-sm text-green-800 hover:bg-green-100"
                        data-testid={`loading-export-fletero-${driver.id}`}
                      >
                        <LucideMessageCircle className="h-4 w-4" />
                        {driver.name}
                      </a>
                    );
                  })}
                </div>
              </div>
            )}
            <div className="flex items-center justify-end gap-2 flex-wrap">
              <Button variant="outline" onClick={onClose}>
                {t('ui.close')}
              </Button>
              <Button variant="outline" onClick={handleShare} data-testid="loading-export-share">
                <LucideShare2 className="h-4 w-4" />
                {t('ui.share')}
              </Button>
              <Button onClick={handleCopy} disabled={copied} data-testid="loading-export-copy">
                {copied ? (
                  <>
                    <LucideCheck className="h-4 w-4" />
                    {t('loading.copied')}
                  </>
                ) : (
                  <>
                    <LucideCopy className="h-4 w-4" />
                    {t('loading.copy')}
                  </>
                )}
              </Button>
            </div>
          </div>
        )}
    </Dialog>
  );
}
