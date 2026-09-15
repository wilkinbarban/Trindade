import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LucideX, LucideCopy, LucideCheck } from 'lucide-react';
import { api } from '../../api/client';
import { Button } from '../ui/button';
import { LoadingSpinner } from '../ui/loading-spinner';

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

  useEffect(() => {
    if (!open || !date) return;

    let cancelled = false;
    setError('');
    setLoading(true);
    setCopied(false);

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


  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Modal */}
      <div className="relative bg-white rounded-lg shadow-xl max-w-lg w-full mx-4 max-h-[85vh] flex flex-col" data-testid="loading-export-modal">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200 shrink-0">
          <h3 className="text-lg font-semibold text-gray-800">
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
          <div className="flex items-center justify-end gap-2 p-4 border-t border-gray-200 shrink-0 flex-wrap">
            <Button variant="outline" onClick={onClose}>
              {t('ui.close')}
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
        )}
      </div>
    </div>
  );
}
