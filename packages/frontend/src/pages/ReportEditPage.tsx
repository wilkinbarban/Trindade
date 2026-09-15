import { useEffect, useState, useCallback, type FormEvent } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideArrowLeft, LucideTrash2, LucideUpload, LucideImage } from 'lucide-react';
import { api, apiUrl, ApiClientError } from '../api/client';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import {
  CategorySection,
  type CategoryType,
  type FormState,
} from '../components/reports/CategorySection';

interface ReportDetail {
  id: number;
  turno: 'tarde' | 'noite';
  report_date: string;
  notes: string | null;
  created_at: string;
  updated_at: string;
  items: { task_id: number; checked: boolean; task_type: string; selectedProducts?: string[] }[];
  temperatures: { location: string; readingIndex?: number; value: number }[];
  readOnly?: boolean;
  canEdit?: boolean;
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

interface PendingPhoto {
  id: string;
  file: File;
  previewUrl: string;
}

const MAX_PHOTOS = 5;
const MAX_PHOTO_SIZE = 5 * 1024 * 1024;
const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

function isEditWindowExpired(reportDate: string): boolean {
  const now = new Date();
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const parts = formatter.formatToParts(now);
  const year = parts.find(p => p.type === 'year')?.value || '';
  const month = parts.find(p => p.type === 'month')?.value || '';
  const day = parts.find(p => p.type === 'day')?.value || '';
  const todayStr = `${year}-${month}-${day}`; // "YYYY-MM-DD"
  const [y, m, d] = todayStr.split('-').map(Number);
  const todayDate = new Date(Date.UTC(y, m - 1, d));
  todayDate.setUTCDate(todayDate.getUTCDate() - 1);
  const yYesterday = todayDate.getUTCFullYear();
  const mYesterday = String(todayDate.getUTCMonth() + 1).padStart(2, '0');
  const dYesterday = String(todayDate.getUTCDate()).padStart(2, '0');
  const yesterdayStr = `${yYesterday}-${mYesterday}-${dYesterday}`;
  return reportDate < yesterdayStr;
}

export function ReportEditPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [report, setReport] = useState<ReportDetail | null>(null);
  const [categories, setCategories] = useState<CategoryType[]>([]);
  const [turno, setTurno] = useState<'tarde' | 'noite'>('tarde');
  const [notes, setNotes] = useState('');
  const [form, setForm] = useState<FormState>({
    items: {},
    temperatures: {},
    selectedProducts: {},
  });
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [readOnly, setReadOnly] = useState(false);

  // Photo state
  const [photos, setPhotos] = useState<ReportPhoto[]>([]);
  const [uploading, setUploading] = useState(false);
  const [photoError, setPhotoError] = useState('');
  const [pendingPhotos, setPendingPhotos] = useState<PendingPhoto[]>([]);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  useEffect(() => {
    if (photoError) {
      const timer = setTimeout(() => setPhotoError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [photoError]);

  useEffect(() => () => {
    pendingPhotos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
  }, [pendingPhotos]);

  // ---- Fetch report + reference data ----
  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    async function load() {
      try {
        const [reportData, catData, photosData] = await Promise.all([
          api.get<{ report: ReportDetail }>(`/reports/${id}`),
          api.get<{ categories: CategoryType[] }>('/reports/categories'),
          api.get<{ photos: ReportPhoto[] }>(`/reports/${id}/photos`),
        ]);

        if (cancelled) return;

        const r = reportData.report;
        setReport(r);
        setCategories(catData.categories);
        setTurno(r.turno);
        setNotes(r.notes ?? '');
        setPhotos(photosData.photos);

        setReadOnly(r.readOnly ?? !r.canEdit);

        // Parse report data into FormState
        const items: Record<number, boolean> = {};
        const temperatures: Record<number, string[]> = {};
        const selectedProducts: Record<number, string[]> = {};

        for (const item of r.items) {
          items[item.task_id] = item.checked;
          if (item.selectedProducts) {
            selectedProducts[item.task_id] = item.selectedProducts;
          }
        }

        for (const temp of r.temperatures) {
          // Match temperature by location name to task ID
          const task = catData.categories
            .flatMap((c) => c.tasks)
            .find((t) => t.name_pt === temp.location);
          if (task) {
            const readings = temperatures[task.id] ?? [];
            readings[(temp.readingIndex ?? 1) - 1] = String(temp.value);
            temperatures[task.id] = readings;
          }
        }

        setForm({ items, temperatures, selectedProducts });
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : t('reports.loadError'));
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, [id, t]);



  // ---- Form handlers (only active when not read-only) ----
  const handleItemChange = useCallback(
    (taskId: number, checked: boolean) => {
      if (readOnly) return;
      setForm((prev) => ({
        ...prev,
        items: { ...prev.items, [taskId]: checked },
      }));
    },
    [readOnly]
  );

  const handleSelectedProductsChange = useCallback(
    (taskId: number, products: string[]) => {
      if (readOnly) return;
      setForm((prev) => ({
        ...prev,
        items: { ...prev.items, [taskId]: products.length > 0 },
        selectedProducts: { ...prev.selectedProducts, [taskId]: products },
      }));
    },
    [readOnly]
  );

  const handleTemperatureChange = useCallback(
    (taskId: number, readingIndex: number, value: string) => {
      if (readOnly) return;
      setForm((prev) => ({
        ...prev,
        temperatures: {
          ...prev.temperatures,
          [taskId]: Array.from({ length: Math.max(readingIndex, prev.temperatures[taskId]?.length ?? 0) }, (_, index) =>
            index === readingIndex - 1 ? value : (prev.temperatures[taskId]?.[index] ?? '')
          ),
        },
      }));
    },
    [readOnly]
  );





  // ---- Photo handlers ----
  function handlePhotoSelection(files: FileList | null) {
    if (readOnly || !files) return;
    setPhotoError('');
    const selected = Array.from(files);
    if (photos.length + pendingPhotos.length + selected.length > MAX_PHOTOS) {
      setPhotoError(t('reports.photoCountError', { max: MAX_PHOTOS }));
      return;
    }

    const validPhotos: PendingPhoto[] = [];
    for (const file of selected) {
      if (file.size > MAX_PHOTO_SIZE) {
        setPhotoError(t('reports.photoSizeError'));
        continue;
      }
      if (!ALLOWED_PHOTO_TYPES.includes(file.type)) {
        setPhotoError(t('reports.photoTypeError'));
        continue;
      }
      validPhotos.push({
        id: `${file.name}-${file.lastModified}-${crypto.randomUUID()}`,
        file,
        previewUrl: URL.createObjectURL(file),
      });
    }
    if (validPhotos.length > 0) {
      setPendingPhotos((prev) => [...prev, ...validPhotos]);
    }
  }

  function removePendingPhoto(photoId: string) {
    setPendingPhotos((prev) => {
      const removed = prev.find((photo) => photo.id === photoId);
      if (removed) URL.revokeObjectURL(removed.previewUrl);
      return prev.filter((photo) => photo.id !== photoId);
    });
  }

  async function uploadPendingPhotos() {
    if (!id || pendingPhotos.length === 0) return;
    setUploading(true);
    try {
      for (const pendingPhoto of pendingPhotos) {
        const formData = new FormData();
        formData.append('file', pendingPhoto.file);
        const result = await api.upload<{ photo: ReportPhoto }>(`/reports/${id}/photos`, formData);
        setPhotos((prev) => [result.photo, ...prev]);
      }
      pendingPhotos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
      setPendingPhotos([]);
    } finally {
      setUploading(false);
    }
  }

  async function handlePhotoDelete(photoId: number) {
    if (readOnly) return;
    setPhotoError('');

    try {
      await api.del(`/reports/photos/${photoId}`);
      setPhotos((prev) => prev.filter((p) => p.id !== photoId));
    } catch (err: any) {
      setPhotoError(err instanceof ApiClientError ? err.message : t('reports.photoDeleteError'));
    }
  }

  // ---- Submit ----
  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (readOnly) return;
    setError('');
    setSubmitting(true);

    // Find all active temperature tasks
    const activeTempTasks = categories
      .flatMap((c) => c.tasks)
      .filter((t) => t.task_type === 'temperature');

    const hasMissingTemp = activeTempTasks.some((task) => {
      return Array.from({ length: task.temperature_readings }, (_, index) => form.temperatures[task.id]?.[index])
        .some((value) => value === undefined || value === null || String(value).trim() === '');
    });

    if (hasMissingTemp || activeTempTasks.length === 0) {
      setError(t('reports.temperaturesRequired'));
      setSubmitting(false);
      return;
    }

    // Build temperatures array
    const temperatures = activeTempTasks.flatMap((task) =>
      Array.from({ length: task.temperature_readings }, (_, index) => ({
        location: task.name_pt,
        readingIndex: index + 1,
        value: Number(form.temperatures[task.id][index]),
      }))
    );

    // Build items
    const items = Object.entries(form.items)
      .filter(([, checked]) => checked)
      .map(([taskIdStr]) => {
        const taskId = Number(taskIdStr);
        return {
          taskId,
          checked: true,
          selectedProducts: form.selectedProducts?.[taskId] || [],
        };
      });

    try {
      await api.patch<{ report: { id: number } }>(`/reports/${id}`, {
        turno,
        notes: notes.trim() || null,
        items,
        temperatures,
      });
      await uploadPendingPhotos();
      navigate(`/reports/${id}`);
    } catch (err) {
      const message = err instanceof ApiClientError ? err.message : t('reports.updateError');
      setError(message);
      if (pendingPhotos.length > 0) setPhotoError(message);
    } finally {
      setSubmitting(false);
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

  if (error && !report) {
    return (
      <div className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
        <p>{error}</p>
        <Link to="/reports/history" className="text-sm text-blue-600 hover:underline mt-2 inline-block">
          ← {t('reports.backToHistory')}
        </Link>
      </div>
    );
  }

  if (!report) {
    return (
      <div className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
        <p>{t('reports.notFound')}</p>
        <Link to="/reports/history" className="text-sm text-blue-600 hover:underline mt-2 inline-block">
          ← {t('reports.backToHistory')}
        </Link>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="flex items-center gap-3 mb-4">
        <Link to={`/reports/${id}`} className="text-gray-500 hover:text-gray-700">
          <LucideArrowLeft className="h-5 w-5" />
        </Link>
        <div>
          <h2 className="text-xl font-bold text-gray-800">{t('reports.editTitle')}</h2>
          <p className="text-sm text-gray-500">{t('reports.editDesc')}</p>
        </div>
      </div>

      {readOnly && (
        <div className="mb-4 p-3 text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-md">
          {t('reports.editWindowExpired')}
        </div>
      )}

      <form onSubmit={handleSubmit} data-testid="report-edit-form">
        {/* Turno selector */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle>{t('reports.turnoLabel')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex gap-4">
              <label className={`flex items-center gap-2 ${readOnly ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
                <input
                  type="radio"
                  name="turno"
                  value="tarde"
                  checked={turno === 'tarde'}
                  onChange={() => !readOnly && setTurno('tarde')}
                  disabled={readOnly}
                  className="h-4 w-4 text-blue-600 focus:ring-blue-500"
                  data-testid="turno-selector-tarde"
                />
                <span className="text-sm text-gray-700">🌅 {t('reports.turnoTarde')}</span>
              </label>
              <label className={`flex items-center gap-2 ${readOnly ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
                <input
                  type="radio"
                  name="turno"
                  value="noite"
                  checked={turno === 'noite'}
                  onChange={() => !readOnly && setTurno('noite')}
                  disabled={readOnly}
                  className="h-4 w-4 text-blue-600 focus:ring-blue-500"
                  data-testid="turno-selector-noite"
                />
                <span className="text-sm text-gray-700">🌙 {t('reports.turnoNoite')}</span>
              </label>
            </div>
          </CardContent>
        </Card>

        {/* Category sections */}
        {categories.filter((cat) => !cat.parent_category_id).map((cat) => (
          <Card key={cat.id} className="mb-6">
            <CardContent className="pt-6">
              <CategorySection
                category={cat}
                children={categories.filter((child) => child.parent_category_id === cat.id)}
                form={form}
                onItemChange={handleItemChange}
                onTemperatureChange={handleTemperatureChange}
                onSelectedProductsChange={handleSelectedProductsChange}
                readOnly={readOnly}
              />
            </CardContent>
          </Card>
        ))}

        {/* Notes */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle>{t('reports.notesLabel')}</CardTitle>
          </CardHeader>
          <CardContent>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              disabled={readOnly}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-sm placeholder:text-gray-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:border-blue-500 resize-y disabled:bg-gray-100 disabled:cursor-not-allowed"
              placeholder={t('reports.notesPlaceholder')}
              data-testid="notes-textarea"
            />
          </CardContent>
        </Card>

        {/* Photo gallery + upload */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2">
              <LucideImage className="h-4 w-4" />
              {t('reports.photosSection')}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {photos.length > 0 ? (
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2 mb-3">
                {photos.map((photo) => (
                  <div key={photo.id} className="relative group aspect-square rounded-md overflow-hidden border border-gray-200">
                    <img
                      src={apiUrl(photo.publicUrl ?? photo.url ?? `/reports/photos/${photo.id}`)}
                      alt={`Photo ${photo.id}`}
                      className="w-full h-full object-cover"
                      loading="lazy"
                    />
                    {!readOnly && (
                      <button
                        type="button"
                        onClick={() => handlePhotoDelete(photo.id)}
                        className="absolute top-1 right-1 p-1 bg-red-500 text-white rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
                        title={t('ui.delete')}
                        data-testid="delete-existing-photo-btn"
                      >
                        <LucideTrash2 className="h-3 w-3" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-400 mb-3">{t('reports.photosEmpty')}</p>
            )}

            {!readOnly && (
              <div className="space-y-3">
                <p className="text-sm text-gray-500">
                  {t('reports.photoHelp', { count: photos.length + pendingPhotos.length, max: MAX_PHOTOS })}
                </p>
                {pendingPhotos.length > 0 && (
                  <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
                    {pendingPhotos.map((photo) => (
                      <div key={photo.id} className="relative aspect-square rounded-md overflow-hidden border border-gray-200">
                        <img src={photo.previewUrl} alt={photo.file.name} className="w-full h-full object-cover" />
                        <button
                          type="button"
                          onClick={() => removePendingPhoto(photo.id)}
                          className="absolute top-1 right-1 p-1 bg-red-500 text-white rounded-full"
                          title={t('reports.photoRemove')}
                          data-testid="remove-pending-photo-btn"
                        >
                          <LucideTrash2 className="h-3 w-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <label
                    className={`inline-flex items-center justify-center gap-2 rounded-lg text-sm font-medium transition-colors h-9 px-3 text-xs border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 ${
                      uploading || photos.length + pendingPhotos.length >= MAX_PHOTOS ? 'pointer-events-none opacity-50' : 'cursor-pointer'
                    }`}
                  >
                    <LucideUpload className="h-4 w-4" />
                    {uploading ? t('reports.photoUploading') : t('reports.photoUploadLabel')}
                    <input
                      type="file"
                      multiple
                      accept="image/jpeg,image/png,image/webp"
                      className="hidden"
                      disabled={uploading || photos.length + pendingPhotos.length >= MAX_PHOTOS}
                      onChange={(e) => {
                        handlePhotoSelection(e.target.files);
                        e.target.value = '';
                      }}
                      data-testid="report-photo-input"
                    />
                  </label>
                  {uploading && <LoadingSpinner className="h-4 w-4" />}
                </div>
              </div>
            )}

            {photoError && (
              <p className="mt-2 text-sm text-red-600">{photoError}</p>
            )}
          </CardContent>
        </Card>

        {error && (
          <div className="mb-4 p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
            {error}
          </div>
        )}

        {!readOnly && (
          <div className="flex justify-end gap-3 mb-8">
            <Button type="button" variant="outline" onClick={() => navigate(`/reports/${id}`)}>
              {t('ui.cancel')}
            </Button>
            <Button type="submit" disabled={submitting || uploading} size="lg" data-testid="update-report-btn">
              {submitting || uploading ? t('reports.updating') : t('reports.updateReport')}
            </Button>
          </div>
        )}
      </form>
    </div>
  );
}
