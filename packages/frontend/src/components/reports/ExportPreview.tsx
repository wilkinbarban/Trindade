import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LucideX, LucideCopy, LucideCheck, LucideShare2 } from 'lucide-react';
import { api } from '../../api/client';
import { Button } from '../ui/button';
import { LoadingSpinner } from '../ui/loading-spinner';
import { Dialog } from '../ui/dialog';
import { shareOrCopy } from '../../lib/whatsapp';
import { useAuth } from '../../contexts/AuthContext';

// ---- Props ----

export interface ExportPreviewProps {
  reportId: number;
  open: boolean;
  onClose: () => void;
}

// ---- Component ----

export function ExportPreview({ reportId, open, onClose }: ExportPreviewProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [shareNote, setShareNote] = useState('');

  useEffect(() => {
    if (!open || !reportId) return;

    let cancelled = false;
    setError('');
    setLoading(true);
    setCopied(false);

    async function fetchExport() {
      try {
        const data = await api.get<{ text: string }>(
          `/reports/${reportId}/export`
        );
        if (!cancelled) {
          setText(data.text);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t('reports.exportError')
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
  }, [open, reportId, t]);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback: select the text so the user can copy manually
      const el = document.getElementById('export-text');
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
   * The native share, with the copy as its fallback. The report is the message itself, so the sheet is
   * where an operator picks WhatsApp or any other app; a dismissed sheet says nothing.
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
    <Dialog open={open} onClose={onClose} labelledBy="export-preview-title" panelTestId="export-modal">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200 shrink-0">
          <h3 id="export-preview-title" className="text-lg font-semibold text-gray-800">
            {t('reports.exportTitle')}
          </h3>
          <Button variant="ghost" size="icon" onClick={onClose} aria-label={t('ui.close')} data-testid="close-export-btn">
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
              id="export-text"
              className="bg-gray-50 rounded-lg p-4 text-sm font-mono whitespace-pre-wrap text-gray-800 leading-relaxed select-all"
              data-testid="export-text-content"
            >
              {text}
            </div>
          )}
        </div>

        {/* Footer */}
        {!loading && !error && (
          <div className="flex flex-col gap-2 p-4 border-t border-gray-200 shrink-0">
            {shareNote && (
              <p className="text-xs text-gray-500" data-testid="export-share-note">
                {shareNote}
              </p>
            )}
            <div className="flex items-center justify-end gap-2 flex-wrap">
              <Button variant="outline" onClick={onClose}>
                {t('ui.close')}
              </Button>
              <Button variant="outline" onClick={handleShare} data-testid="share-export-btn">
                <LucideShare2 className="h-4 w-4" />
                {t('ui.share')}
              </Button>
              <Button onClick={handleCopy} disabled={copied} data-testid="copy-export-btn">
                {copied ? (
                  <>
                    <LucideCheck className="h-4 w-4" />
                    {t('reports.copied')}
                  </>
                ) : (
                  <>
                    <LucideCopy className="h-4 w-4" />
                    {t('reports.copyWhatsApp')}
                  </>
                )}
              </Button>
            </div>
          </div>
        )}
    </Dialog>
  );
}
