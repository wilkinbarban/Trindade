import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideArrowLeft, LucideSend, LucidePencil, LucideImage, LucideFileDown } from 'lucide-react';
import { api, apiUrl } from '../api/client';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { ExportPreview } from '../components/reports/ExportPreview';

// ---- Types ----

interface ReportDetail {
  id: number;
  turno: 'tarde' | 'noite';
  report_date: string;
  notes: string | null;
  created_at: string;
  updated_at: string;
  user: {
    id: number;
    display_name: string;
  };
  isActive?: boolean;
  readOnly?: boolean;
  canEdit?: boolean;
  canDeactivate?: boolean;
  canDelete?: boolean;
  items: ReportItemDetail[];
  temperatures: ReportTemperatureDetail[];
}

interface ReportItemDetail {
  task_id: number;
  task_name: string;
  task_name_es?: string;
  task_type: string;
  category_name: string;
  category_name_es?: string;
  checked: boolean;
  selectedProducts?: string[];
}

interface ReportTemperatureDetail {
  location: string;
  location_es?: string;
  value: number;
}



interface ReportPhoto {
  id: number;
  report_id: number;
  file_path: string;
  file_size: number;
  mime_type: string;
  created_at: string;
  url?: string;
  publicUrl?: string;
}

// ---- Helpers ----

function formatDate(dateStr: string, _locale?: string): string {
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

function formatDateTime(dateStr: string, locale: string = 'pt-BR'): string {
  let formatted = dateStr.replace(' ', 'T');
  if (!formatted.endsWith('Z') && !formatted.includes('+') && !/T.*-\d{2}/.test(formatted)) {
    formatted += 'Z';
  }
  const d = new Date(formatted);
  return d.toLocaleString(locale, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// ---- Component ----

export function ReportViewPage() {
  const { t, i18n } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [report, setReport] = useState<ReportDetail | null>(null);
  const [photos, setPhotos] = useState<ReportPhoto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showExport, setShowExport] = useState(false);
  const [pdfLoading, setPdfLoading] = useState(false);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    async function load() {
      try {
        const [reportData, photosData] = await Promise.all([
          api.get<{ report: ReportDetail }>(`/reports/${id}`),
          api.get<{ photos: ReportPhoto[] }>(`/reports/${id}/photos`),
        ]);
        if (!cancelled) {
          setReport(reportData.report);
          setPhotos(photosData.photos);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t('reports.loadError')
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, [id, t]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  if (error || !report) {
    return (
      <div className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
        <p className="font-medium">{t('reports.loadError')}</p>
        <p className="text-sm mt-1">{error || t('reports.notFound')}</p>
        <Link
          to="/reports/history"
          className="inline-block mt-3 text-sm text-blue-600 hover:underline"
        >
          ← {t('reports.backToHistory')}
        </Link>
      </div>
    );
  }
  async function handleExportPdf() {
    if (!report) return;
    setPdfLoading(true);
    try {
      const data = await api.get<{ text: string }>(`/reports/${report.id}/export`);

      const tempDiv = document.createElement('div');
      tempDiv.id = 'temp-pdf-export-text';
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

      const { exportPdf } = await import('../lib/exportPdf');
      await exportPdf('temp-pdf-export-text', `relatorio-${report.id}`);

      document.body.removeChild(tempDiv);
    } catch (err: any) {
      setError(err.message || t('reports.exportError'));
    } finally {
      setPdfLoading(false);
    }
  }

  const isSpanish = i18n.language?.startsWith('es');

  // Group items by category
  const itemsByCategory = new Map<string, { displayCatName: string; items: ReportItemDetail[] }>();
  for (const item of report.items) {
    const ptCat = item.category_name || 'Geral';
    const esCat = item.category_name_es || ptCat;
    const catKey = ptCat; // stable key
    const displayCatName = isSpanish ? esCat : ptCat;

    const group = itemsByCategory.get(catKey) ?? { displayCatName, items: [] };
    group.items.push(item);
    itemsByCategory.set(catKey, group);
  }  return (
    <div className="max-w-2xl mx-auto" data-testid="report-detail">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <Link
          to="/reports/history"
          className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
        >
          <LucideArrowLeft className="h-4 w-4" />
          {t('reports.backToHistory')}
        </Link>
      </div>

      {report.temperatures.length === 0 && (
        <div className="mb-4 p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
          {t('reports.temperaturesRequired')}
        </div>
      )}

      <h2 className="text-xl font-bold text-gray-800 mb-1">
        {t('reports.reportDetail')}
      </h2>
      <p className="text-sm text-gray-500 mb-6">
        {formatDate(report.report_date, i18n.language)} — {report.turno === 'tarde' ? <>🌅 {t('reports.turnoTarde')}</> : <>🌙 {t('reports.turnoNoite')}</>}
      </p>

      {/* Items by category */}
      {Array.from(itemsByCategory.entries()).map(([catKey, group]) => (
        <Card key={catKey} className="mb-4">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-semibold text-gray-700">
              {group.displayCatName}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1">
              {group.items.map((item) => {
                const displayName = isSpanish && item.task_name_es ? item.task_name_es : item.task_name;
                const hasSelectedProducts = item.selectedProducts && item.selectedProducts.length > 0;
                return (
                  <li
                    key={item.task_id}
                    className="py-1 border-b border-gray-50 last:border-0"
                  >
                    <div className="flex items-center gap-2 text-sm">
                      <span
                        className={`flex-shrink-0 ${
                          item.checked ? 'text-green-600' : 'text-gray-300'
                        }`}
                      >
                        {item.checked ? '✅' : '⬜'}
                      </span>
                      <span
                        className={
                          item.checked ? 'text-gray-800' : 'text-gray-400'
                        }
                      >
                        {displayName}
                      </span>
                    </div>
                    {item.checked && hasSelectedProducts && (
                      <ul className="pl-6 mt-1 space-y-0.5 list-disc text-xs text-gray-500">
                        {item.selectedProducts!.map((prod) => (
                          <li key={prod}>{prod}</li>
                        ))}
                      </ul>
                    )}
                  </li>
                );
              })}
            </ul>
          </CardContent>
        </Card>
      ))}

      {/* Temperatures */}
      {report.temperatures.length > 0 && (
        <Card className="mb-4">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-semibold text-gray-700">
              🌡️ {t('reports.temperaturesSection')}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1">
              {report.temperatures.map((t, i) => {
                const displayLocation = isSpanish && t.location_es ? t.location_es : t.location;
                return (
                  <li key={i} className="text-sm text-gray-700">
                    {displayLocation}: <span className="font-medium">{t.value}°C</span>
                  </li>
                );
              })}
            </ul>
          </CardContent>
        </Card>
      )}



      {/* Notes */}
      {report.notes && (
        <Card className="mb-4">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-semibold text-gray-700">
              📝 {t('reports.notesLabel')}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-gray-700 whitespace-pre-wrap">
              {report.notes}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Photos Gallery */}
      <Card className="mb-4">
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-semibold text-gray-700 flex items-center gap-2">
            <LucideImage className="h-4 w-4" />
            {t('reports.photosSection')}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {photos.length === 0 ? (
            <p className="text-sm text-gray-400">{t('reports.photosEmpty')}</p>
          ) : (
            <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
              {photos.map((photo) => {
                const imgUrl = apiUrl(photo.publicUrl ?? photo.url ?? `/reports/photos/${photo.id}`);
                return (
                  <a
                    key={photo.id}
                    href={imgUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block aspect-square rounded-md overflow-hidden border border-gray-200 hover:border-blue-400 transition-colors"
                  >
                    <img
                      src={imgUrl}
                      alt={`Photo ${photo.id}`}
                      className="w-full h-full object-cover"
                      loading="lazy"
                    />
                  </a>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Meta info */}
      <Card className="mb-6">
        <CardContent className="pt-4">
          <div className="text-xs text-gray-400 space-y-1">
            <p>
              {t('reports.createdBy')}: {report.user.display_name}
            </p>
            <p>
              {t('reports.createdAt')}: {formatDateTime(report.created_at, i18n.language)}
            </p>
            {report.updated_at !== report.created_at && (
              <p>
                {t('reports.updatedAt')}: {formatDateTime(report.updated_at, i18n.language)}
              </p>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Action buttons */}
      <div className="flex flex-row w-full sm:w-auto justify-end gap-2 mb-8">
        {report.canEdit && (
          <Link to={`/reports/${report.id}/edit`} className="flex-1 sm:flex-initial">
            <Button
              variant="outline"
              className="w-full h-9 sm:h-11 px-2 sm:px-6 text-xs sm:text-base gap-1 sm:gap-2"
              data-testid="edit-report-btn"
            >
              <LucidePencil className="h-3.5 w-3.5 sm:h-4 sm:w-4" />
              {t('reports.editTitle')}
            </Button>
          </Link>
        )}
        <Button
          onClick={handleExportPdf}
          disabled={pdfLoading || report.temperatures.length === 0}
          className="flex-1 sm:flex-initial h-9 sm:h-11 px-2 sm:px-6 text-xs sm:text-base gap-1 sm:gap-2"
          data-testid="export-pdf-btn"
        >
          <LucideFileDown className={`h-3.5 w-3.5 sm:h-4 sm:w-4 ${pdfLoading ? 'animate-spin' : ''}`} />
          {t('reports.exportPdf')}
        </Button>
        <Button
          onClick={() => setShowExport(true)}
          disabled={report.temperatures.length === 0}
          className="flex-1 sm:flex-initial h-9 sm:h-11 px-2 sm:px-6 text-xs sm:text-base gap-1 sm:gap-2"
          data-testid="export-whatsapp-btn"
        >
          <LucideSend className="h-3.5 w-3.5 sm:h-4 sm:w-4" />
          {t('reports.exportWhatsApp')}
        </Button>
      </div>

      {/* Export Preview Dialog */}
      <ExportPreview
        reportId={report.id}
        open={showExport}
        onClose={() => setShowExport(false)}
      />
    </div>
  );
}
