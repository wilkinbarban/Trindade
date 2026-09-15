import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { Card, CardContent } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';

interface DashboardSummary {
  reportsToday: number;
  schedulesTomorrow: number;
  activeUsers: number;
  reportsTotal: number;
  schedulesTotal: number;
  latestReportId: number | null;
  higieneDone: number;
  higieneTotal: number;
  recepcionDone: number;
  recepcionTotal: number;
}

interface SummaryCardProps {
  title: string;
  value: React.ReactNode;
  icon: string;
  to?: string;
}

function SummaryCard({ title, value, icon, to }: SummaryCardProps) {
  const cardContent = (
    <Card className={to ? "transition-all duration-200 hover:scale-[1.02] hover:shadow-md cursor-pointer border hover:border-gray-300" : ""}>
      <CardContent className="pt-6">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-gray-500">{title}</p>
            <div className="mt-1">{value}</div>
          </div>
          <span className="text-3xl">{icon}</span>
        </div>
      </CardContent>
    </Card>
  );

  if (to) {
    return (
      <Link to={to} className="block no-underline">
        {cardContent}
      </Link>
    );
  }

  return cardContent;
}

export function DashboardPage() {
  const { t } = useTranslation();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function fetchSummary() {
      try {
        const data = await api.get<DashboardSummary>('/dashboard/summary');
        if (!cancelled) {
          setSummary(data);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t('dashboard.loadingError')
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    fetchSummary();

    return () => {
      cancelled = true;
    };
  }, [t]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  if (error) {
    return (
      <div role="alert" aria-live="assertive" className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
        <p className="font-medium">{t('dashboard.loadingError')}</p>
        <p className="text-sm mt-1">{error}</p>
      </div>
    );
  }

  return (
    <div>
      <h3 className="text-lg font-semibold text-gray-800 mb-4">
        {t('dashboard.overview')}
      </h3>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4">
        <SummaryCard
          title={t('dashboard.reportsToday')}
          value={
            summary ? (
              <div className="text-xs sm:text-sm font-medium text-gray-700 space-y-0.5">
                <div>{t('dashboard.higieneCount', { done: summary.higieneDone, total: summary.higieneTotal })}</div>
                <div>{t('dashboard.recepcionCount', { done: summary.recepcionDone, total: summary.recepcionTotal })}</div>
              </div>
            ) : (
              <span className="text-3xl font-bold text-blue-600">--</span>
            )
          }
          icon="📋"
          to={summary?.latestReportId ? `/reports/${summary.latestReportId}` : '/reports'}
        />
        <SummaryCard
          title={t('dashboard.schedulesTomorrow')}
          value={<span className="text-3xl font-bold text-green-600">{summary?.schedulesTomorrow ?? 0}</span>}
          icon="🚛"
          to="/loading"
        />
        <SummaryCard
          title={t('dashboard.activeUsers')}
          value={<span className="text-3xl font-bold text-purple-600">{summary?.activeUsers ?? 0}</span>}
          icon="👥"
        />
        <SummaryCard
          title={t('dashboard.reportsTotal')}
          value={<span className="text-3xl font-bold text-indigo-600">{summary?.reportsTotal ?? 0}</span>}
          icon="📊"
        />
        <SummaryCard
          title={t('dashboard.schedulesTotal')}
          value={<span className="text-3xl font-bold text-teal-600">{summary?.schedulesTotal ?? 0}</span>}
          icon="📂"
        />
      </div>

      {/* Deferred modules notice */}
      <div className="mt-8 p-6 bg-white rounded-lg shadow-sm border border-dashed border-gray-300">
        <p className="text-gray-400 text-center text-sm">
          {t('dashboard.deferredNotice')}
        </p>
      </div>
    </div>
  );
}
