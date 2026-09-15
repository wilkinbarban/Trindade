import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideEye, LucideCalendar, LucideTrash2, LucideBan } from 'lucide-react';
import { api } from '../api/client';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';

interface UserOption { id: number; display_name: string }
interface Pagination { page: number; pageSize: number; total: number; totalPages: number }
interface ReportListItem {
  id: number;
  turno: 'tarde' | 'noite';
  report_date: string;
  notes: string | null;
  created_at: string;
  isActive: boolean;
  readOnly: boolean;
  canEdit: boolean;
  canDeactivate: boolean;
  canDelete: boolean;
  user: { id: number; display_name: string };
}

function formatDate(dateStr: string): string {
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

export function ReportHistoryPage() {
  const { t } = useTranslation();
  const [reports, setReports] = useState<ReportListItem[]>([]);
  const [users, setUsers] = useState<UserOption[]>([]);
  const [pagination, setPagination] = useState<Pagination>({ page: 1, pageSize: 30, total: 0, totalPages: 0 });
  const [date, setDate] = useState('');
  const [month, setMonth] = useState('');
  const [userId, setUserId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchReports = useCallback(async (page = 1) => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: '30' });
      if (date) params.set('date', date);
      if (month) params.set('month', month);
      if (userId) params.set('userId', userId);
      const data = await api.get<{ items: ReportListItem[]; pagination: Pagination }>(`/reports/history?${params}`);
      setReports(data.items);
      setPagination(data.pagination);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('reports.loadingError'));
    } finally {
      setLoading(false);
    }
  }, [date, month, userId, t]);

  useEffect(() => {
    api.get<{ users: UserOption[] }>('/users/options').then((data) => setUsers(data.users)).catch(() => setUsers([]));
  }, []);

  useEffect(() => { fetchReports(1); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function deactivateReport(id: number) {
    await api.patch(`/reports/${id}/deactivate`, {});
    await fetchReports(pagination.page);
  }

  async function deleteReport(id: number) {
    await api.del(`/reports/${id}`);
    await fetchReports(pagination.page);
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-xl font-bold text-gray-800 mb-1">{t('reports.historyTitle')}</h2>
          <p className="text-sm text-gray-500">{t('reports.historyDesc')}</p>
        </div>
        <Link to="/reports" data-testid="history-new-report-btn"><Button variant="outline" size="sm">{t('reports.newReport')}</Button></Link>
      </div>

      <Card className="p-3 mb-4 grid grid-cols-1 sm:grid-cols-4 gap-2">
        <input type="date" value={date} onChange={(e) => { setDate(e.target.value); if (e.target.value) setMonth(''); }} className="rounded-lg border px-3 py-2 text-sm" data-testid="history-date-filter" />
        <input type="month" value={month} onChange={(e) => { setMonth(e.target.value); if (e.target.value) setDate(''); }} className="rounded-lg border px-3 py-2 text-sm" data-testid="history-month-filter" />
        <select value={userId} onChange={(e) => setUserId(e.target.value)} className="rounded-lg border px-3 py-2 text-sm" data-testid="history-user-filter">
          <option value="">{t('history.allUsers')}</option>
          {users.map((u) => <option key={u.id} value={u.id}>{u.display_name}</option>)}
        </select>
        <Button size="sm" onClick={() => fetchReports(1)} data-testid="history-apply-filters">{t('reports.filter')}</Button>
      </Card>

      {loading && <div className="flex items-center justify-center py-12"><LoadingSpinner /></div>}
      {error && <div className="p-4 text-red-700 bg-red-50 border rounded-lg mb-4">{error}</div>}
      {!loading && !error && reports.length === 0 && <div className="text-center py-12"><LucideCalendar className="h-12 w-12 mx-auto text-gray-300 mb-3" /><p className="text-gray-500">{t('reports.noReports')}</p></div>}
      {!loading && !error && reports.length > 0 && (
        <div className="space-y-3">
          {reports.map((r) => (
            <Card key={r.id} className={`p-4 ${!r.isActive ? 'opacity-70' : ''}`} data-testid={`report-card-${r.id}`}>
              <div className="flex items-center justify-between gap-3">
                <Link to={`/reports/${r.id}`} className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 mb-1"><span className="text-sm font-semibold">{formatDate(r.report_date)}</span><span className="text-xs px-2 py-0.5 rounded-full bg-gray-100">{r.turno === 'tarde' ? `🌅 ${t('reports.turnoTarde')}` : `🌙 ${t('reports.turnoNoite')}`}</span>{!r.isActive && <span className="text-xs text-amber-700">{t('history.inactive')}</span>}</div>
                  <p className="text-xs text-gray-500">{r.user.display_name}{r.notes ? ` — ${r.notes.slice(0, 60)}` : ''}</p>
                </Link>
                <Link to={`/reports/${r.id}`}><LucideEye className="h-4 w-4 text-gray-400" /></Link>
                {r.canDeactivate && <button onClick={() => deactivateReport(r.id)} className="text-amber-600" data-testid={`deactivate-report-${r.id}`}><LucideBan className="h-4 w-4" /></button>}
                {r.canDelete && <button onClick={() => deleteReport(r.id)} className="text-red-600" data-testid={`delete-report-${r.id}`}><LucideTrash2 className="h-4 w-4" /></button>}
              </div>
            </Card>
          ))}
        </div>
      )}
      <div className="flex items-center justify-between mt-4 text-sm text-gray-500">
        <span>{t('history.pagination', { page: pagination.page, totalPages: Math.max(1, pagination.totalPages), total: pagination.total })}</span>
        <div className="flex gap-2"><Button variant="outline" size="sm" disabled={pagination.page <= 1} onClick={() => fetchReports(pagination.page - 1)}>{t('history.previousPage')}</Button><Button variant="outline" size="sm" disabled={pagination.page >= pagination.totalPages} onClick={() => fetchReports(pagination.page + 1)}>{t('history.nextPage')}</Button></div>
      </div>
    </div>
  );
}
