import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiClientError } from '../../api/client';
import { Button } from '../ui/button';
import { Input } from '../ui/input';

export interface DriverFormProps {
  onSaved: (driver: { id: number; name: string; license_plate: string | null }) => void;
  onCancel: () => void;
}

export function DriverForm({ onSaved, onCancel }: DriverFormProps) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [plate, setPlate] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setSaving(true);

    try {
      const data = await api.post<{
        driver: { id: number; name: string; license_plate: string | null };
      }>('/loading/drivers', {
        name: name.trim(),
        license_plate: plate.trim() || undefined,
      });
      onSaved(data.driver);
    } catch (err) {
      setError(
        err instanceof ApiClientError ? err.message : t('loading.saveError')
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3" data-testid="driver-form">
      <Input
        type="text"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder={t('loading.driverName')}
        required
        autoFocus
        disabled={saving}
        data-testid="driver-form-name"
      />
      <Input
        type="text"
        value={plate}
        onChange={(e) => setPlate(e.target.value)}
        placeholder={t('loading.licensePlate')}
        disabled={saving}
        data-testid="driver-form-plate"
      />
      {error && (
        <p className="text-sm text-red-600" role="alert" data-testid="driver-form-error">
          {error}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={saving} data-testid="driver-form-submit">
          {saving ? t('ui.loading') : t('loading.saveFletero')}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={saving}>
          {t('ui.cancel')}
        </Button>
      </div>
    </form>
  );
}
