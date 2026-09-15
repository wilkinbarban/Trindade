import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideBan, LucideCalendar, LucideTrash2, LucideTruck } from 'lucide-react';
import { api } from '../api/client';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';

interface UserOption { id: number; display_name: string }
interface Pagination { page: number; pageSize: number; total: number; totalPages: number }
interface LoadingBatchHistoryItem {
  batch_date: string;
  loading_date: string;
  total_loadings: number;
  created_at: string | null;
  updated_at: string | null;
  isActive: boolean;
  readOnly: boolean;
  canEdit: boolean;
  canDeactivate: boolean;
  canDelete: boolean;
  creator?: { id: number; display_name: string } | null;
}

function formatDate(dateStr: string): string {
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

export function LoadingHistoryPage() {
  const { t } = useTranslation();
  const [items, setItems] = useState<LoadingBatchHistoryItem[]>([]);
  const [users, setUsers] = useState<UserOption[]>([]);
  const [pagination, setPagination] = useState<Pagination>({ page: 1, pageSize: 30, total: 0, totalPages: 0 });
  const [date, setDate] = useState('');
  const [month, setMonth] = useState('');
  const [userId, setUserId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchItems = useCallback(async (page = 1) => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: '30' });
      if (date) params.set('date', date);
      if (month) params.set('month', month);
      if (userId) params.set('userId', userId);
      const data = await api.get<{ items: LoadingBatchHistoryItem[]; pagination: Pagination }>(`/loading/schedules/history?${params}`);
      setItems(data.items);
      setPagination(data.pagination);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('loading.loadingError'));
    } finally {
      setLoading(false);
    }
  }, [date, month, userId, t]);

  useEffect(() => {
    api.get<{ users: UserOption[] }>('/users/options').then((data) => setUsers(data.users)).catch(() => setUsers([]));
  }, []);

  useEffect(() => { fetchItems(1); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function deactivateBatch(batchDate: string) {
    await api.patch(`/loading/schedules/batch/${batchDate}/deactivate`, {});
    await fetchItems(pagination.page);
  }

  async function deleteBatch(batchDate: string) {
    await api.del(`/loading/schedules/batch/${batchDate}`);
    await fetchItems(pagination.page);
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-xl font-bold text-gray-800">{t('loading.historyTitle')}</h2>
          <p className="text-sm text-gray-500">{t('loading.historyDesc')}</p>
        </div>
        <Link to="/loading" data-testid="back-to-schedule-link"><Button variant="outline" size="sm">{t('loading.title')}</Button></Link>
      </div>

      <Card className="p-3 mb-4 grid grid-cols-1 sm:grid-cols-4 gap-2">
        <input type="date" value={date} onChange={(e) => { setDate(e.target.value); if (e.target.value) setMonth(''); }} className="rounded-lg border px-3 py-2 text-sm" data-testid="loading-history-date-filter" />
        <input type="month" value={month} onChange={(e) => { setMonth(e.target.value); if (e.target.value) setDate(''); }} className="rounded-lg border px-3 py-2 text-sm" data-testid="loading-history-month-filter" />
        <select value={userId} onChange={(e) => setUserId(e.target.value)} className="rounded-lg border px-3 py-2 text-sm" data-testid="loading-history-user-filter">
          <option value="">{t('history.allUsers')}</option>
          {users.map((u) => <option key={u.id} value={u.id}>{u.display_name}</option>)}
        </select>
        <Button size="sm" onClick={() => fetchItems(1)} data-testid="loading-history-apply-filters">{t('reports.filter')}</Button>
      </Card>

      {loading && <div className="flex items-center justify-center py-12"><LoadingSpinner /></div>}
      {error && <div className="p-4 text-red-700 bg-red-50 border rounded-lg mb-4">{error}</div>}
      {!loading && !error && items.length === 0 && (
        <div className="text-center py-12"><LucideTruck className="h-12 w-12 mx-auto text-gray-300 mb-3" /><p className="text-gray-500">{t('loading.emptyDay')}</p></div>
      )}
      {!loading && !error && items.length > 0 && (
        <div className="space-y-3">
          {items.map((batch) => (
            <Card key={batch.batch_date} className={`p-4 ${!batch.isActive ? 'opacity-70' : ''}`} data-testid={`loading-history-card-${batch.batch_date}`}>
              <div className="flex items-center justify-between gap-3">
                <Link to={`/loading/edit?date=${batch.batch_date}`} className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-sm font-semibold">{formatDate(batch.batch_date)}</span>
                    <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100">📦 {batch.total_loadings} carregamentos</span>
                    {!batch.isActive && <span className="text-xs text-amber-700">{t('history.inactive')}</span>}
                  </div>
                  <p className="text-xs text-gray-500">
                    <LucideCalendar className="h-3 w-3 inline mr-1" />
                    Carregamento: {formatDate(batch.loading_date)} — {batch.creator?.display_name ?? t('history.legacy')}
                  </p>
                </Link>
                {batch.canEdit && <Link to={`/loading/edit?date=${batch.batch_date}`}><Button variant="outline" size="sm">{t('loading.editEntry')}</Button></Link>}
                {batch.canDeactivate && <button onClick={() => deactivateBatch(batch.batch_date)} className="text-amber-600" data-testid={`deactivate-loading-batch-${batch.batch_date}`}><LucideBan className="h-4 w-4" /></button>}
                {batch.canDelete && <button onClick={() => deleteBatch(batch.batch_date)} className="text-red-600" data-testid={`delete-loading-batch-${batch.batch_date}`}><LucideTrash2 className="h-4 w-4" /></button>}
              </div>
            </Card>
          ))}
        </div>
      )}
      <div className="flex items-center justify-between mt-4 text-sm text-gray-500">
        <span>{t('history.pagination', { page: pagination.page, totalPages: Math.max(1, pagination.totalPages), total: pagination.total })}</span>
        <div className="flex gap-2"><Button variant="outline" size="sm" disabled={pagination.page <= 1} onClick={() => fetchItems(pagination.page - 1)}>{t('history.previousPage')}</Button><Button variant="outline" size="sm" disabled={pagination.page >= pagination.totalPages} onClick={() => fetchItems(pagination.page + 1)}>{t('history.nextPage')}</Button></div>
      </div>
    </div>
  );
}
