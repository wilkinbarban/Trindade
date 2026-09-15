import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiClientError } from '../../api/client';
import { Button } from '../ui/button';
import { DriverForm } from './DriverForm';

interface DriverRow {
  id: number;
  name: string;
  license_plate: string | null;
  driver_type: 'casa' | 'fletero';
}

export interface DriverSelectProps {
  timeSlot: string;
  date: string;
  drivers: DriverRow[];
  assignedIds: number[];
  assignedVehicleIds?: number[];
  onAssigned: () => void; // callback to refresh schedules
  onClose: () => void;
}

export function DriverSelect({
  timeSlot,
  date,
  drivers,
  assignedIds,
  assignedVehicleIds = [],
  onAssigned,
  onClose,
}: DriverSelectProps) {
  const { t } = useTranslation();
  const [showForm, setShowForm] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [error, setError] = useState('');

  // States for vehicle selection (casa driver)
  const [selectedDriver, setSelectedDriver] = useState<DriverRow | null>(null);
  const [selectedVehicleId, setSelectedVehicleId] = useState<string>('');
  const [vehicles, setVehicles] = useState<{ id: number; description: string; license_plate: string }[]>([]);

  useEffect(() => {
    api.get<{ vehicles: { id: number; description: string; license_plate: string }[] }>('/loading/vehicles')
      .then((data) => setVehicles(data.vehicles))
      .catch((err) => console.error('Error fetching vehicles:', err));
  }, []);

  const available = drivers.filter((d) => !assignedIds.includes(d.id));

  async function assignDriver(driverId: number, vehicleId: number | null = null) {
    setError('');
    setAssigning(true);

    const driver = drivers.find((d) => d.id === driverId);
    const driverType = driver?.driver_type || 'fletero';

    try {
      await api.post('/loading/schedules', {
        schedule_date: date,
        time_slot: timeSlot,
        driver_type: driverType,
        driver_id: driverId,
        vehicle_id: vehicleId,
      });
      onAssigned();
    } catch (err) {
      setError(
        err instanceof ApiClientError
          ? (err.message === 'scheduleAlreadyExists' ? t('loading.scheduleAlreadyExists') : err.message)
          : t('loading.saveError')
      );
    } finally {
      setAssigning(false);
    }
  }

  function handleSelectDriver(d: DriverRow) {
    if (d.driver_type === 'casa') {
      setSelectedDriver(d);
      setSelectedVehicleId('');
      setError('');
    } else {
      assignDriver(d.id, null);
    }
  }

  function handleDriverSaved(
    driver: { id: number; name: string; license_plate: string | null }
  ) {
    // Immediately assign the newly created driver
    assignDriver(driver.id, null);
  }

  return (
    <div
      className="mt-2 p-3 bg-blue-50 rounded-lg border border-blue-200"
      data-testid={`driver-select-${timeSlot.replace(':', '-')}`}
    >
      {error && (
        <p className="text-sm text-red-600 mb-2" role="alert" data-testid="driver-select-error">
          {error}
        </p>
      )}

      {selectedDriver ? (
        <div data-testid="vehicle-select-container">
          <p className="text-sm font-medium text-gray-700 mb-2">
            {t('loading.selectVehicleFor', { name: selectedDriver.name })}
          </p>
          <select
            value={selectedVehicleId}
            onChange={(e) => setSelectedVehicleId(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white mb-3"
            data-testid="vehicle-select"
            required
            disabled={assigning}
          >
            <option value="">{t('loading.selectVehicle')}</option>
            {vehicles
              .filter((v) => !assignedVehicleIds.includes(v.id))
              .map((v) => (
                <option key={v.id} value={v.id}>
                  {v.description} ({v.license_plate})
                </option>
              ))}
          </select>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              disabled={assigning || !selectedVehicleId}
              onClick={() => assignDriver(selectedDriver.id, Number(selectedVehicleId))}
              data-testid="confirm-assign-btn"
            >
              {assigning ? t('ui.loading') : t('ui.save')}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setSelectedDriver(null)}
              disabled={assigning}
            >
              {t('ui.cancel')}
            </Button>
          </div>
        </div>
      ) : showForm ? (
        <div>
          <p className="text-sm font-medium text-gray-700 mb-2">
            {t('loading.newFletero')}
          </p>
          <DriverForm
            onSaved={handleDriverSaved}
            onCancel={() => setShowForm(false)}
          />
          <button
            type="button"
            onClick={() => setShowForm(false)}
            className="text-xs text-blue-600 hover:underline mt-2 inline-block"
            disabled={assigning}
          >
            {t('loading.backToList')}
          </button>
        </div>
      ) : (
        <div>
          <p className="text-xs font-medium text-gray-500 mb-2">
            {t('loading.selectFletero')}
          </p>

          {available.length === 0 ? (
            <p className="text-sm text-gray-500 mb-2">{t('loading.noFleteros')}</p>
          ) : (
            <ul className="space-y-1 mb-3" data-testid="driver-select-list">
              {available.map((d) => (
                <li key={d.id}>
                  <button
                    type="button"
                    onClick={() => handleSelectDriver(d)}
                    disabled={assigning}
                    className="w-full text-left px-3 py-2 text-sm rounded-md hover:bg-blue-100 transition-colors disabled:opacity-50"
                    data-testid={`driver-option-${d.id}`}
                  >
                    <span className="font-medium">{d.name}</span>
                    {d.driver_type === 'casa' && (
                      <span className="text-xs text-blue-500 ml-2 font-medium">
                        (Casa)
                      </span>
                    )}
                    {d.license_plate && (
                      <span className="text-gray-500 ml-2 text-xs">
                        {d.license_plate}
                      </span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}

          <button
            type="button"
            onClick={() => setShowForm(true)}
            disabled={assigning}
            className="text-sm text-blue-600 hover:underline font-medium"
            data-testid="add-new-fletero-btn"
          >
            + {t('loading.newFletero')}
          </button>
        </div>
      )}

      {!selectedDriver && (
        <div className="mt-2 pt-2 border-t border-blue-200">
          <Button
            variant="ghost"
            size="sm"
            onClick={onClose}
            disabled={assigning}
            data-testid="driver-select-close"
          >
            {t('ui.cancel')}
          </Button>
        </div>
      )}
    </div>
  );
}
