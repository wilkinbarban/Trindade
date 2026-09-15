import { useEffect, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { api } from '../../api/client';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { LoadingSpinner } from '../../components/ui/loading-spinner';

// ---- Types ----

interface AuditLogEntry {
  id: number;
  user_id: number;
  display_name: string | null;
  action: string;
  entity_type: string;
  entity_id: number | null;
  ip_address: string | null;
  details: string | null;
  created_at: string;
}

interface AuditPageResponse {
  logs: AuditLogEntry[];
  total: number;
  page: number;
  limit: number;
  totalPages: number;
}

const ACTIONS = [
  { value: '', labelKey: 'audit.actions.all' },
  { value: 'login', labelKey: 'audit.actions.login' },
  { value: 'logout', labelKey: 'audit.actions.logout' },
  { value: 'create', labelKey: 'audit.actions.create' },
  { value: 'update', labelKey: 'audit.actions.update' },
  { value: 'delete', labelKey: 'audit.actions.delete' },
  { value: 'upload', labelKey: 'audit.actions.upload' },
];

const ENTITY_TYPES = [
  { value: '', labelKey: 'audit.entities.all' },
  { value: 'auth', labelKey: 'audit.entities.auth' },
  { value: 'report', labelKey: 'audit.entities.report' },
  { value: 'photo', labelKey: 'audit.entities.photo' },
  { value: 'loading', labelKey: 'audit.entities.loading' },
  { value: 'category', labelKey: 'audit.entities.category' },
  { value: 'task', labelKey: 'audit.entities.task' },
  { value: 'product', labelKey: 'audit.entities.product' },
  { value: 'driver', labelKey: 'audit.entities.driver' },
  { value: 'vehicle', labelKey: 'audit.entities.vehicle' },
];

function formatDate(iso: string, locale: string = 'pt-BR'): string {
  try {
    const formattedIso = iso.replace(' ', 'T');
    const withZ = formattedIso.endsWith('Z') ? formattedIso : formattedIso + 'Z';
    const d = new Date(withZ);
    return d.toLocaleString(locale, {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return iso;
  }
}

function parseDetails(details: string | null): Record<string, unknown> | null {
  if (!details) return null;
  try {
    return JSON.parse(details);
  } catch {
    return null;
  }
}

// ---- Empty state for role guard ----

function AccessDenied() {
  const { t } = useTranslation();
  return (
    <div className="flex items-center justify-center py-16">
      <div className="text-center">
        <p className="text-lg font-medium text-gray-500">{t('audit.accessDenied.title')}</p>
        <p className="text-sm text-gray-400 mt-1">{t('audit.accessDenied.description')}</p>
      </div>
    </div>
  );
}

// ---- Main Component ----

export function AuditPage() {
  const { t, i18n } = useTranslation();
  const { user } = useAuth();

  const [logs, setLogs] = useState<AuditLogEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Filters
  const [actionFilter, setActionFilter] = useState('');
  const [entityTypeFilter, setEntityTypeFilter] = useState('');
  const [userIdFilter, setUserIdFilter] = useState('');

  // Temporary inputs to avoid API call spam
  const [actionInput, setActionInput] = useState('');
  const [entityTypeInput, setEntityTypeInput] = useState('');
  const [userIdInput, setUserIdInput] = useState('');

  const limit = 20;

  const fetchLogs = useCallback(
    async (requestedPage: number) => {
      if (!user || user.role !== 'Administrador') return;
      setLoading(true);
      setError('');
      try {
        const params = new URLSearchParams();
        params.set('page', String(requestedPage));
        params.set('limit', String(limit));
        if (actionFilter) params.set('action', actionFilter);
        if (entityTypeFilter) params.set('entityType', entityTypeFilter);
        if (userIdFilter) params.set('userId', userIdFilter);

        const data = await api.get<AuditPageResponse>(
          `/admin/audit?${params.toString()}`
        );
        setLogs(data.logs);
        setTotal(data.total);
        setPage(data.page);
        setTotalPages(data.totalPages);
      } catch (err) {
        setError(err instanceof Error ? err.message : t('audit.errors.fetchLogs'));
      } finally {
        setLoading(false);
      }
    },
    [actionFilter, entityTypeFilter, userIdFilter, user]
  );

  useEffect(() => {
    fetchLogs(1);
  }, [fetchLogs]);

  // Check role
  if (!user || user.role !== 'Administrador') {
    return <AccessDenied />;
  }

  function handleApplyFilters() {
    setActionFilter(actionInput);
    setEntityTypeFilter(entityTypeInput);
    setUserIdFilter(userIdInput);
  }

  function handleClearFilters() {
    setActionInput('');
    setEntityTypeInput('');
    setUserIdInput('');
    setActionFilter('');
    setEntityTypeFilter('');
    setUserIdFilter('');
  }

  return (
    <div>
      <h3 className="text-lg font-semibold text-gray-800 mb-4">{t('audit.title')}</h3>

      {/* Filters */}
      <div className="flex flex-wrap gap-3 mb-4 items-end">
        <div className="space-y-1">
          <label className="block text-xs font-medium text-gray-600">{t('audit.filters.action')}</label>
          <select
            value={actionInput}
            onChange={(e) => setActionInput(e.target.value)}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
          >
            {ACTIONS.map((a) => (
              <option key={a.value} value={a.value}>
                {t(a.labelKey)}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1">
          <label className="block text-xs font-medium text-gray-600">{t('audit.filters.entity')}</label>
          <select
            value={entityTypeInput}
            onChange={(e) => setEntityTypeInput(e.target.value)}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
          >
            {ENTITY_TYPES.map((e) => (
              <option key={e.value} value={e.value}>
                {t(e.labelKey)}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1">
          <label className="block text-xs font-medium text-gray-600">{t('audit.filters.userId')}</label>
          <input
            type="number"
            value={userIdInput}
            onChange={(e) => setUserIdInput(e.target.value)}
            placeholder={t('audit.filters.userIdPlaceholder')}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm w-24"
          />
        </div>

        <Button size="sm" onClick={handleApplyFilters}>
          {t('audit.filters.filter')}
        </Button>
        <Button size="sm" variant="outline" onClick={handleClearFilters}>
          {t('audit.filters.clear')}
        </Button>
      </div>

      {error && (
        <div className="p-3 mb-4 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
          {error}
        </div>
      )}

      {/* Table */}
      <Card>
        <CardContent className="pt-4">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : logs.length === 0 ? (
            <p className="text-center text-gray-400 py-8">{t('audit.table.empty')}</p>
          ) : (
            <>
              <div className="overflow-x-auto w-full">
                <table className="w-full text-sm min-w-[750px]">
                  <thead>
                    <tr className="border-b text-left text-xs text-gray-500 uppercase">
                      <th className="py-2 pr-2 w-36">{t('audit.table.dateTime')}</th>
                      <th className="py-2 pr-2">{t('audit.table.user')}</th>
                      <th className="py-2 pr-2">{t('audit.table.action')}</th>
                      <th className="py-2 pr-2">{t('audit.table.entity')}</th>
                      <th className="py-2 pr-2">{t('audit.table.entityId')}</th>
                      <th className="py-2">{t('audit.table.details')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logs.map((log) => {
                      const details = parseDetails(log.details);
                      return (
                        <tr key={log.id} className="border-b last:border-0 hover:bg-gray-50">
                          <td className="py-2 pr-2 text-xs font-mono whitespace-nowrap">
                            {formatDate(log.created_at, i18n.language)}
                          </td>
                          <td className="py-2 pr-2">
                            <span className="font-medium">{log.display_name ?? `#${log.user_id}`}</span>
                          </td>
                          <td className="py-2 pr-2">
                            <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                              log.action === 'login' ? 'bg-green-100 text-green-800' :
                              log.action === 'logout' ? 'bg-gray-100 text-gray-800' :
                              log.action === 'create' || log.action === 'upload' ? 'bg-blue-100 text-blue-800' :
                              log.action === 'update' ? 'bg-yellow-100 text-yellow-800' :
                              log.action === 'delete' ? 'bg-red-100 text-red-800' :
                              'bg-gray-100 text-gray-800'
                            }`}>
                              {t(`audit.actions.${log.action}`, { defaultValue: log.action })}
                            </span>
                          </td>
                          <td className="py-2 pr-2 text-xs">{t(`audit.entities.${log.entity_type}`, { defaultValue: log.entity_type })}</td>
                          <td className="py-2 pr-2 text-xs font-mono">
                            {log.entity_id ?? '—'}
                          </td>
                          <td className="py-2 text-xs text-gray-500 max-w-xs truncate">
                            {details ? (
                              <span title={JSON.stringify(details)}>
                                {Object.entries(details)
                                  .slice(0, 2)
                                  .map(([k, v]) => `${k}: ${v}`)
                                  .join(', ')}
                              </span>
                            ) : '—'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              <div className="flex items-center justify-between pt-4 border-t mt-2">
                <span className="text-xs text-gray-500">
                  {total} {total === 1 ? t('audit.pagination.record') : t('audit.pagination.records')} — {t('audit.pagination.pageOf', { page, totalPages })}
                </span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={page <= 1}
                    onClick={() => fetchLogs(page - 1)}
                  >
                    {t('audit.pagination.previous')}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={page >= totalPages}
                    onClick={() => fetchLogs(page + 1)}
                  >
                    {t('audit.pagination.next')}
                  </Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
