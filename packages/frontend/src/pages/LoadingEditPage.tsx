import { useEffect, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { LucideArrowLeft, LucidePencil, LucideX, LucideCheck, LucideSend, LucideFileDown } from 'lucide-react';
import { api, ApiClientError } from '../api/client';
import { Button } from '../components/ui/button';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { ScheduleExportView } from '../components/loading/ScheduleExportView';
import { exportPdf } from '../lib/exportPdf';
import type { ScheduleRow } from '../components/loading/ScheduleGrid';



function formatDisplayDate(dateStr: string): string {
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

function toMinutes(slot: string): number {
  const [h, m] = slot.split(':').map(Number);
  return h * 60 + m;
}

function getRollingQuotaBySlot(schedules: ScheduleRow[], slot: string, excludeId?: number): number {
  const targetMinutes = toMinutes(slot);
  return schedules.filter((s) => {
    if (s.driver_type !== 'fletero' || s.id === excludeId) return false;
    return Math.abs(toMinutes(s.time_slot) - targetMinutes) < 60;
  }).length;
}

export function LoadingEditPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const dateParam = searchParams.get('date');

  const date = dateParam && /^\d{4}-\d{2}-\d{2}$/.test(dateParam)
    ? dateParam
    : (() => {
        const d = new Date();
        const formatter = new Intl.DateTimeFormat('en-US', {
          timeZone: 'America/Sao_Paulo',
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
        });
        const parts = formatter.formatToParts(d);
        const year = parts.find(p => p.type === 'year')?.value || '';
        const month = parts.find(p => p.type === 'month')?.value || '';
        const day = parts.find(p => p.type === 'day')?.value || '';
        return `${year}-${month}-${day}`;
      })();
  const [schedules, setSchedules] = useState<ScheduleRow[]>([]);
  const [timeSlots, setTimeSlots] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editSlot, setEditSlot] = useState('');
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [exportOpen, setExportOpen] = useState(false);
  const [pdfLoading, setPdfLoading] = useState(false);

  // ---- Export PDF Handler ----
  const handleExportPdf = useCallback(async () => {
    setError('');
    setPdfLoading(true);
    try {
      const data = await api.get<{ text: string }>(`/loading/export?date=${date}`);

      const tempDiv = document.createElement('div');
      tempDiv.id = 'loading-export-text-temp';
      tempDiv.style.position = 'absolute';
      tempDiv.style.left = '-9999px';
      tempDiv.style.top = '-9999px';
      tempDiv.style.width = '450px';
      tempDiv.style.padding = '20px';
      tempDiv.style.backgroundColor = '#f9fafb';
      tempDiv.style.fontFamily = 'monospace';
      tempDiv.style.fontSize = '12px';
      tempDiv.style.whiteSpace = 'pre-wrap';
      tempDiv.style.color = '#1f2937';
      tempDiv.style.lineHeight = '1.6';
      tempDiv.innerText = data.text;
      document.body.appendChild(tempDiv);

      await exportPdf('loading-export-text-temp', `cronograma-${date}`);
      document.body.removeChild(tempDiv);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : t('loading.exportError')
      );
    } finally {
      setPdfLoading(false);
    }
  }, [date, t]);

  const fetchData = useCallback(async () => {
    setError('');
    setLoading(true);
    try {
      const [schedRes, slotsRes] = await Promise.all([
        api.get<{ schedules: ScheduleRow[] }>(`/loading/schedules?date=${date}`),
        api.get<{ timeSlots: string[] }>('/loading/time-slots'),
      ]);
      setSchedules(schedRes.schedules);
      setTimeSlots(slotsRes.timeSlots);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('loading.loadingError'));
    } finally {
      setLoading(false);
    }
  }, [date, t]);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  useEffect(() => { fetchData(); }, [fetchData]);

  function startEdit(entry: ScheduleRow) {
    setEditingId(entry.id);
    setEditSlot(entry.time_slot);
    setError('');
  }

  function cancelEdit() {
    setEditingId(null);
    setEditSlot('');
  }

  async function saveEdit(id: number) {
    if (!editSlot) return;
    setSaving(true);
    setError('');
    try {
      const res = await api.patch<{ schedule: ScheduleRow }>(`/loading/schedules/${id}`, {
        time_slot: editSlot,
      });
      setSchedules((prev) => prev.map((s) => (s.id === id ? res.schedule : s)));
      setEditingId(null);
      setEditSlot('');
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(err.message);
      } else {
        setError(t('loading.updateError'));
      }
    } finally {
      setSaving(false);
    }
  }

  async function deleteEntry(id: number) {
    setDeleting(id);
    setError('');
    try {
      await api.del(`/loading/schedules/${id}`);
      setSchedules((prev) => prev.filter((entry) => entry.id !== id));
    } catch (err) {
      setError(err instanceof Error ? err.message : t('loading.deleteError'));
    } finally {
      setDeleting(null);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="mb-4">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 font-medium transition-colors"
          data-testid="back-button"
        >
          <LucideArrowLeft className="h-4 w-4" />
          {t('ui.back')}
        </button>
      </div>

      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-800">{t('loading.editTitle')}</h2>
        <p className="text-sm text-gray-500">{formatDisplayDate(date)}</p>
      </div>

      {error && (
        <div className="mb-4 p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
          {error}
        </div>
      )}

      {schedules.length === 0 ? (
        <p className="text-sm text-gray-500 italic py-8 text-center">{t('loading.emptyDay')}</p>
      ) : (
        <div className="space-y-2" data-testid="edit-schedule-list">
          {schedules.map((entry) => {
            const isEditing = editingId === entry.id;
            const currentQuota = getRollingQuotaBySlot(schedules, entry.time_slot);

            return (
              <div
                key={entry.id}
                className="bg-white rounded-lg border border-gray-200 p-4 flex items-center justify-between"
                data-testid={`edit-entry-${entry.id}`}
              >
                {isEditing ? (
                  <div className="flex-1 flex items-center gap-3 flex-wrap">
                    <select
                      value={editSlot}
                      onChange={(e) => setEditSlot(e.target.value)}
                      className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                      data-testid={`slot-select-${entry.id}`}
                    >
                      {timeSlots.map((slot) => {
                        const quota = getRollingQuotaBySlot(schedules, slot, slot === entry.time_slot ? undefined : entry.id);
                        const finalCount = entry.driver_type === 'fletero' ? quota + 1 : quota;
                        const isExceeded = finalCount > 3;
                        return (
                          <option key={slot} value={slot}>
                            {slot}
                            {entry.driver_type === 'fletero' && (
                              isExceeded ? ` (${finalCount}/3 - ${t('loading.quotaError')})` : ` (${finalCount}/3)`
                            )}
                          </option>
                        );
                      })}
                    </select>
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => saveEdit(entry.id)}
                        disabled={saving || editSlot === entry.time_slot}
                        className="p-1.5 text-green-600 hover:text-green-700 disabled:opacity-50"
                        data-testid={`save-edit-${entry.id}`}
                      >
                        <LucideCheck className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={cancelEdit}
                        disabled={saving}
                        className="p-1.5 text-gray-400 hover:text-gray-600"
                      >
                        <LucideX className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex-1 min-w-0">
                      <span className="text-sm font-medium text-gray-800">
                        {entry.driver_name || entry.vehicle_description || '-'}
                      </span>
                      <span className="text-xs text-gray-400 ml-2">
                        {entry.time_slot}
                        {entry.driver_type === 'fletero' && (
                          <span className="ml-1 text-yellow-600">({currentQuota}/3 fleteros)</span>
                        )}
                      </span>
                      {(entry.license_plate || entry.vehicle_plate) && (
                        <span className="text-xs text-gray-400 ml-2">
                          {entry.license_plate || entry.vehicle_plate}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-1">
                      {entry.canEdit && (
                        <button
                          type="button"
                          onClick={() => startEdit(entry)}
                          className="p-2 text-blue-500 hover:text-blue-700 transition-colors"
                          aria-label={t('loading.editEntry')}
                          data-testid={`edit-btn-${entry.id}`}
                        >
                          <LucidePencil className="h-4 w-4" />
                        </button>
                      )}
                      {entry.canDelete && (
                        <button
                          type="button"
                          onClick={() => deleteEntry(entry.id)}
                          disabled={deleting === entry.id}
                          className="inline-flex items-center gap-1 rounded px-2 py-1 text-sm text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50"
                          aria-label={t('loading.deleteEntry')}
                          data-testid={`delete-edit-entry-${entry.id}`}
                        >
                          <LucideX className="h-4 w-4" />
                          {t('loading.deleteEntry')}
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Action buttons */}
      <div className="flex justify-end gap-3 mt-6 mb-8">
        <Button
          onClick={handleExportPdf}
          variant="outline"
          size="lg"
          data-testid="loading-export-pdf-btn"
          disabled={schedules.length === 0 || pdfLoading}
        >
          <LucideFileDown className={`h-4 w-4 ${pdfLoading ? 'animate-spin' : ''}`} />
          {t('loading.exportPdf')}
        </Button>
        <Button
          onClick={() => setExportOpen(true)}
          size="lg"
          data-testid="open-export-btn"
          disabled={schedules.length === 0}
        >
          <LucideSend className="h-4 w-4" />
          {t('loading.export')}
        </Button>
      </div>

      {/* Export modal */}
      <ScheduleExportView
        date={date}
        open={exportOpen}
        onClose={() => setExportOpen(false)}
      />
    </div>
  );
}
