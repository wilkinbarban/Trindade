import { useEffect, useState, useCallback, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideImage, LucideTrash2, LucideUpload } from 'lucide-react';
import { api, ApiClientError } from '../api/client';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import {
  CategorySection,
  type CategoryType,
  type FormState,
} from '../components/reports/CategorySection';


interface PendingPhoto {
  id: string;
  file: File;
  previewUrl: string;
}

const MAX_PHOTOS = 5;
const MAX_PHOTO_SIZE = 5 * 1024 * 1024;
const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

export function ReportsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

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
  const [photoError, setPhotoError] = useState('');
  const [pendingPhotos, setPendingPhotos] = useState<PendingPhoto[]>([]);
  const [createdReportId, setCreatedReportId] = useState<number | null>(null);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  useEffect(() => () => {
    pendingPhotos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
  }, [pendingPhotos]);

  // ---- Fetch reference data ----
  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [catData, turnoData] = await Promise.all([
          api.get<{ categories: CategoryType[] }>('/reports/categories'),
          api.get<{ turno: string }>('/reports/turno'),
        ]);

        if (cancelled) return;

        setCategories(catData.categories);
        setTurno(turnoData.turno as 'tarde' | 'noite');
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : t('reports.loadingError')
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
  }, [t]);

  // ---- Form handlers ----
  const handleItemChange = useCallback((taskId: number, checked: boolean) => {
    setForm((prev) => ({
      ...prev,
      items: { ...prev.items, [taskId]: checked },
    }));
  }, []);

  const handleSelectedProductsChange = useCallback((taskId: number, products: string[]) => {
    setForm((prev) => ({
      ...prev,
      items: { ...prev.items, [taskId]: products.length > 0 },
      selectedProducts: { ...prev.selectedProducts, [taskId]: products },
    }));
  }, []);

  const handleTemperatureChange = useCallback(
    (taskId: number, readingIndex: number, value: string) => {
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
    []
  );

  // ---- Photo handlers ----
  function handlePhotoSelection(files: FileList | null) {
    if (!files) return;
    setPhotoError('');
    const selected = Array.from(files);
    if (pendingPhotos.length + selected.length > MAX_PHOTOS) {
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

  async function uploadPendingPhotos(reportId: number) {
    for (const pendingPhoto of pendingPhotos) {
      const formData = new FormData();
      formData.append('file', pendingPhoto.file);
      await api.upload(`/reports/${reportId}/photos`, formData);
    }
  }

  // ---- Submit ----
  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
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
      let reportId = createdReportId;
      if (!reportId) {
        const data = await api.post<{ report: { id: number } }>('/reports', {
          turno,
          notes: notes.trim() || undefined,
          items,
          temperatures,
        });
        reportId = data.report.id;
        setCreatedReportId(reportId);
      }

      if (pendingPhotos.length > 0) {
        await uploadPendingPhotos(reportId);
      }

      pendingPhotos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl));
      setPendingPhotos([]);
      navigate(`/reports/${reportId}`);
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(err.message === 'reportAlreadyExists' ? t('reports.reportAlreadyExists') : err.message);
      } else {
        setError(t('reports.saveError'));
      }
    } finally {
      setSubmitting(false);
    }
  }

  // ---- Loading / Error states ----
  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  if (error && categories.length === 0) {
    return (
      <div className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
        <p className="font-medium">{t('reports.loadingError')}</p>
        <p className="text-sm mt-1">{error}</p>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <h2 className="text-xl font-bold text-gray-800 mb-1">
        {t('reports.newReport')}
      </h2>
      <p className="text-sm text-gray-500 mb-6">{t('reports.newReportDesc')}</p>

      <form onSubmit={handleSubmit} data-testid="report-builder-form">
        {/* Turno selector */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle>{t('reports.turnoLabel')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="radio"
                  name="turno"
                  value="tarde"
                  checked={turno === 'tarde'}
                  onChange={() => setTurno('tarde')}
                  className="h-4 w-4 text-blue-600 focus:ring-blue-500"
                  data-testid="turno-selector-tarde"
                />
                <span className="text-sm text-gray-700">🌅 {t('reports.turnoTarde')}</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="radio"
                  name="turno"
                  value="noite"
                  checked={turno === 'noite'}
                  onChange={() => setTurno('noite')}
                  className="h-4 w-4 text-blue-600 focus:ring-blue-500"
                  data-testid="turno-selector-noite"
                />
                <span className="text-sm text-gray-700">🌙 {t('reports.turnoNoite')}</span>
              </label>
            </div>
          </CardContent>
        </Card>

        {/* Dynamic category sections */}
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
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-sm placeholder:text-gray-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:border-blue-500 resize-y"
              placeholder={t('reports.notesPlaceholder')}
              data-testid="notes-textarea"
            />
          </CardContent>
        </Card>


        {/* Photos */}
        <Card className="mb-6">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2">
              <LucideImage className="h-4 w-4" />
              {t('reports.photosSection')}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-gray-500 mb-3">
              {t('reports.photoHelp', { count: pendingPhotos.length, max: MAX_PHOTOS })}
            </p>
            {pendingPhotos.length > 0 && (
              <div className="grid grid-cols-3 sm:grid-cols-4 gap-2 mb-3">
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
            <label className={`inline-flex items-center justify-center gap-2 rounded-lg text-sm font-medium h-9 px-3 border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 ${pendingPhotos.length >= MAX_PHOTOS ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}>
              <LucideUpload className="h-4 w-4" />
              {t('reports.photoUploadLabel')}
              <input
                type="file"
                multiple
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                disabled={pendingPhotos.length >= MAX_PHOTOS || submitting}
                onChange={(e) => {
                  handlePhotoSelection(e.target.files);
                  e.target.value = '';
                }}
                data-testid="report-photo-input"
              />
            </label>
            {photoError && <p className="mt-2 text-sm text-red-600">{photoError}</p>}
          </CardContent>
        </Card>

        {/* Error */}
        {error && (
          <div className="mb-4 p-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
            {error}
          </div>
        )}

        {/* Submit */}
        <div className="flex justify-end">
          <Button type="submit" disabled={submitting} size="lg" data-testid="submit-report-btn">
            {submitting ? t('reports.saving') : (createdReportId ? t('reports.retryPhotoUpload') : t('reports.saveReport'))}
          </Button>
        </div>
      </form>
    </div>
  );
}
