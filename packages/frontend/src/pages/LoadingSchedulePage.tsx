import { useEffect, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { LucideSend, LucideFileDown } from 'lucide-react';
import { api } from '../api/client';
import { Button } from '../components/ui/button';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { ScheduleGrid } from '../components/loading/ScheduleGrid';
import { ScheduleExportView } from '../components/loading/ScheduleExportView';
import { exportPdf } from '../lib/exportPdf';
import type { ScheduleRow, DriverRow } from '../components/loading/ScheduleGrid';



function formatDisplayDate(dateStr: string): string {
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

function todayDate(): string {
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
}

export function LoadingSchedulePage() {
  const { t } = useTranslation();
  const date = todayDate();

  // Data
  const [schedules, setSchedules] = useState<ScheduleRow[]>([]);
  const [timeSlots, setTimeSlots] = useState<string[]>([]);
  const [drivers, setDrivers] = useState<DriverRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Export modal
  const [exportOpen, setExportOpen] = useState(false);

  // ---- Fetch data for current date ----
  const fetchData = useCallback(async () => {
    setError('');
    setLoading(true);

    try {
      const [schedRes, slotsRes, driversRes] = await Promise.all([
        api.get<{ schedules: ScheduleRow[] }>(`/loading/schedules?date=${date}`),
        api.get<{ timeSlots: string[] }>('/loading/time-slots'),
        api.get<{ drivers: DriverRow[] }>('/loading/drivers'),
      ]);

      setSchedules(schedRes.schedules);
      setTimeSlots(slotsRes.timeSlots);
      setDrivers(driversRes.drivers);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : t('loading.loadingError')
      );
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

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // ---- Delete handler ----
  async function handleDelete(id: number) {
    try {
      await api.del(`/loading/schedules/${id}`);
      setSchedules((prev) => prev.filter((s) => s.id !== id));
    } catch (err) {
      setError(err instanceof Error ? err.message : t('loading.deleteError'));
    }
  }

  // ---- Export PDF Handler ----
  async function handleExportPdf() {
    setError('');
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
    }
  }

  // ---- Loading state ----
  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      {/* Page header */}
      <div className="mb-4">
        <h2 className="text-xl font-bold text-gray-800">
          {t('loading.title')}
        </h2>
        <p className="text-sm text-gray-500 mt-1">
          {t('loading.description')}
        </p>
      </div>

      {/* Batch date */}
      <div className="mb-4 bg-white rounded-lg border border-gray-200 px-4 py-3 text-center">
        <span className="text-lg font-semibold text-gray-800" data-testid="date-display">
          {formatDisplayDate(date)}
        </span>
      </div>

      {/* Error banner */}
      {error && (
        <div className="mb-4 p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
          {error}
        </div>
      )}

      {/* Grid */}
      <ScheduleGrid
        schedules={schedules}
        timeSlots={timeSlots}
        date={date}
        drivers={drivers}
        loading={loading}
        onDelete={handleDelete}
        onRefresh={fetchData}
      />

      {/* Action buttons */}
      <div className="flex justify-end gap-3 mt-6 mb-8">
        <Button
          onClick={handleExportPdf}
          variant="outline"
          size="lg"
          data-testid="loading-export-pdf-btn"
          disabled={schedules.length === 0}
        >
          <LucideFileDown className="h-4 w-4" />
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
