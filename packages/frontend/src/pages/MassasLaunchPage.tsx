import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { Input } from '../components/ui/input';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import { toWhatsAppNumber } from '../lib/phone';
import { shareOrCopy, waMeLink } from '../lib/whatsapp';
import {
  massasMessage,
  saopauloToday,
  type MassasLaunchDayView,
  type MassaIndividualLaunch,
} from '../lib/massas';
import { Layers } from 'lucide-react';
import { MassasLaunchGrid } from './massas/MassasLaunchGrid';
import { MassasCollectiveTable } from './massas/MassasCollectiveTable';
import { MassasWhatsAppShare } from './massas/MassasWhatsAppShare';

export function MassasLaunchPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [date, setDate] = useState(saopauloToday());
  const [dayView, setDayView] = useState<MassasLaunchDayView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // Block 1 state: quantities per product code
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // Block 2 state: inline editing of an individual launch
  const [editingLaunchId, setEditingLaunchId] = useState<number | null>(null);
  const [editingBoxes, setEditingBoxes] = useState('');
  const [editingBusy, setEditingBusy] = useState(false);
  const [deletingLaunchId, setDeletingLaunchId] = useState<number | null>(null);

  // Block 3 state: WhatsApp phone input
  const [phone, setPhone] = useState('');
  const [copyNote, setCopyNote] = useState('');

  const load = useCallback(
    async (targetDate: string) => {
      setError('');
      try {
        const loaded = await api.get<MassasLaunchDayView>(`/massas-launch?date=${targetDate}`);
        setDayView(loaded);
      } catch (err) {
        setError(err instanceof Error ? err.message : t('massas.loadError'));
      } finally {
        setLoading(false);
      }
    },
    [t]
  );

  useEffect(() => {
    void load(date);
  }, [date, load]);

  const handleQuantityChange = (code: string, rawVal: string) => {
    const cleaned = rawVal.replace(/\D/g, '');
    setQuantities((prev) => ({ ...prev, [code]: cleaned }));
  };

  const handleLaunch = async () => {
    if (!dayView || submitting) return;
    const items = dayView.products
      .map((p) => ({
        product_code: p.code,
        boxes: parseInt(quantities[p.code] || '0', 10),
      }))
      .filter((i) => i.boxes > 0);

    if (items.length === 0) return;
    setSubmitting(true);
    setNotice(null);
    try {
      await api.post('/massas-launch', { date, items });
      setQuantities({});
      setNotice({ type: 'success', message: t('massas.launchSuccess') });
      await load(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.launchError') });
    } finally {
      setSubmitting(false);
    }
  };

  const startEditing = (launch: MassaIndividualLaunch) => {
    setEditingLaunchId(launch.id);
    setEditingBoxes(String(launch.boxes));
  };

  const cancelEditing = () => {
    setEditingLaunchId(null);
    setEditingBoxes('');
  };

  const saveEditing = async (launchId: number) => {
    const boxesNum = parseInt(editingBoxes, 10);
    if (!Number.isInteger(boxesNum) || boxesNum <= 0 || editingBusy) return;
    setEditingBusy(true);
    setNotice(null);
    try {
      await api.put(`/massas-launch/${launchId}`, { boxes: boxesNum });
      setEditingLaunchId(null);
      setEditingBoxes('');
      setNotice({ type: 'success', message: t('massas.updateSuccess') });
      await load(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.updateError') });
    } finally {
      setEditingBusy(false);
    }
  };

  const handleDeleteLaunch = async (launchId: number) => {
    if (!window.confirm(t('massas.deleteConfirm'))) return;
    setDeletingLaunchId(launchId);
    setNotice(null);
    try {
      await api.del(`/massas-launch/${launchId}`);
      setNotice({ type: 'success', message: t('massas.deleteSuccess') });
      await load(date);
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof Error ? err.message : t('massas.deleteError') });
    } finally {
      setDeletingLaunchId(null);
    }
  };

  const previewMessage = useMemo(() => {
    if (!dayView) return '';
    return massasMessage(dayView);
  }, [dayView]);

  const handleCopyReport = async () => {
    if (!previewMessage) return;
    setCopyNote('');
    const outcome = await shareOrCopy(previewMessage);
    if (outcome === 'copied' || outcome === 'shared') {
      setCopyNote(t('massas.copied'));
    }
  };

  const handleOpenWhatsApp = () => {
    if (!previewMessage) return;
    const formattedNumber = toWhatsAppNumber(phone);
    if (formattedNumber) {
      const link = waMeLink(formattedNumber, previewMessage);
      if (link) window.open(link, '_blank', 'noopener,noreferrer');
    } else {
      void shareOrCopy(previewMessage);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto space-y-8 pb-12">
      {/* Top Title & Date Selection */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white p-6 rounded-xl border border-gray-200 shadow-sm">
        <div>
          <div className="flex items-center gap-2">
            <span className="p-2 bg-amber-100 text-amber-800 rounded-lg">
              <Layers className="h-6 w-6" />
            </span>
            <h1 className="text-2xl font-bold text-gray-900">{t('massas.title')}</h1>
          </div>
          <p className="text-sm text-gray-500 mt-1">{t('massas.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <label htmlFor="massa-date" className="text-sm font-medium text-gray-700 whitespace-nowrap">
            {t('massas.date')}:
          </label>
          <Input
            id="massa-date"
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className="w-auto font-medium"
          />
        </div>
      </div>

      {/* Global Notices */}
      {error && (
        <div role="alert" className="p-4 text-red-700 bg-red-50 border border-red-200 rounded-lg">
          {error}
        </div>
      )}
      {notice && (
        <div
          role="status"
          className={`p-4 rounded-lg border ${
            notice.type === 'success'
              ? 'bg-green-50 text-green-800 border-green-200'
              : 'bg-red-50 text-red-800 border-red-200'
          }`}
        >
          {notice.message}
        </div>
      )}

      {/* BLOQUE 1 (Superior): Formulario / Grilla de 10 Productos SIN botón resetear */}
      {dayView && (
        <MassasLaunchGrid
          products={dayView.products}
          quantities={quantities}
          onQuantityChange={handleQuantityChange}
          onLaunch={handleLaunch}
          submitting={submitting}
          date={date}
        />
      )}

      {/* BLOQUE 2 (Intermedio): Resumen Colectivo y Desglose RBAC */}
      {dayView && (
        <MassasCollectiveTable
          products={dayView.products}
          launches={dayView.launches}
          totalBoxes={dayView.totalBoxes}
          totalUnits={dayView.totalUnits}
          currentUserId={user?.id}
          editingLaunchId={editingLaunchId}
          editingBoxes={editingBoxes}
          editingBusy={editingBusy}
          onStartEdit={startEditing}
          onCancelEdit={cancelEditing}
          onSaveEdit={saveEditing}
          onEditBoxesChange={setEditingBoxes}
          onDelete={handleDeleteLaunch}
          deletingId={deletingLaunchId}
        />
      )}

      {/* BLOQUE 3 (Inferior): Compartir por WhatsApp con máscara y unidades totales */}
      <MassasWhatsAppShare
        phone={phone}
        onPhoneChange={setPhone}
        previewMessage={previewMessage}
        onCopy={handleCopyReport}
        onOpenWhatsApp={handleOpenWhatsApp}
        copyNote={copyNote}
      />
    </div>
  );
}
