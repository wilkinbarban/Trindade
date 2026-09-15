import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LucidePlus, LucideX, LucideClock } from 'lucide-react';
import { DriverSelect } from './DriverSelect';

export interface ScheduleRow {
  id: number;
  schedule_date: string;
  time_slot: string;
  driver_type: 'fletero' | 'casa';
  driver_id: number | null;
  driver_name: string | null;
  license_plate: string | null;
  vehicle_id: number | null;
  vehicle_description: string | null;
  vehicle_plate: string | null;
  readOnly?: boolean;
  canEdit?: boolean;
  canDeactivate?: boolean;
  canDelete?: boolean;
  isActive?: boolean;
  creator?: { id: number; display_name: string } | null;
}

export interface DriverRow {
  id: number;
  name: string;
  license_plate: string | null;
  driver_type: 'casa' | 'fletero';
}

export interface ScheduleGridProps {
  schedules: ScheduleRow[];
  timeSlots: string[];
  date: string;
  drivers: DriverRow[];
  loading: boolean;
  onDelete: (id: number) => Promise<void>;
  onRefresh: () => void;
}

export function ScheduleGrid({
  schedules,
  timeSlots,
  date,
  drivers,
  loading,
  onDelete,
  onRefresh,
}: ScheduleGridProps) {
  const { t } = useTranslation();
  const [addingSlot, setAddingSlot] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);

  const assignedVehicleIds = schedules
    .filter((e) => e.vehicle_id != null)
    .map((e) => e.vehicle_id as number);

  function getSlotEntries(slot: string): ScheduleRow[] {
    return schedules.filter((s) => s.time_slot === slot);
  }

  function getFleteroCount(entries: ScheduleRow[]): number {
    return entries.filter((e) => e.driver_type === 'fletero').length;
  }

  function getAssignedIds(): number[] {
    return schedules
      .filter((e) => e.driver_id != null)
      .map((e) => e.driver_id as number);
  }

  function isRollingQuotaMaxed(slot: string): boolean {
    const existingTimes = schedules
      .filter((s) => s.driver_type === 'fletero')
      .map((s) => s.time_slot);

    const times = [...existingTimes, slot];
    const minutesList = times
      .map((t) => {
        const [h, m] = t.split(':').map(Number);
        return h * 60 + m;
      })
      .sort((a, b) => a - b);

    for (let i = 0; i <= minutesList.length - 4; i++) {
      if (minutesList[i + 3] - minutesList[i] < 60) {
        return true;
      }
    }
    return false;
  }

  function toMinutes(slot: string): number {
    const [h, m] = slot.split(':').map(Number);
    return h * 60 + m;
  }

  function isRollingQuotaExceeded(slot: string): boolean {
    const targetMinutes = toMinutes(slot);
    const matching = schedules.filter((s) => {
      if (s.driver_type !== 'fletero') return false;
      return Math.abs(toMinutes(s.time_slot) - targetMinutes) < 60;
    });
    return matching.length > 3;
  }

  function getDisplayName(entry: ScheduleRow): string {
    if (entry.driver_type === 'casa' && entry.driver_name && entry.vehicle_description) {
      return `${entry.driver_name} (${entry.vehicle_description})`;
    }
    if (entry.driver_name) return entry.driver_name;
    if (entry.vehicle_description) return entry.vehicle_description;
    return '-';
  }

  function getDisplayPlate(entry: ScheduleRow): string {
    if (entry.driver_type === 'casa') {
      return entry.vehicle_plate || '';
    }
    if (entry.license_plate) return entry.license_plate;
    if (entry.vehicle_plate) return entry.vehicle_plate;
    return '';
  }

  async function handleDelete(id: number) {
    setDeleting(id);
    try {
      await onDelete(id);
    } finally {
      setDeleting(null);
    }
  }

  function handleAssigned() {
    setAddingSlot(null);
    onRefresh();
  }

  return (
    <div className="space-y-3" data-testid="schedule-grid">
      {timeSlots.map((slot) => {
        const entries = getSlotEntries(slot);
        const fleteroCount = getFleteroCount(entries);
        const isExceeded = isRollingQuotaExceeded(slot);
        const isAdding = addingSlot === slot;
        const assignedIds = getAssignedIds();

        return (
          <div
            key={slot}
            className="bg-white rounded-lg border border-gray-200 shadow-sm overflow-hidden"
            data-testid={`time-slot-${slot.replace(':', '-')}`}
          >
            {/* Slot header */}
            <div className="flex items-center justify-between px-4 py-3 bg-gray-50 border-b border-gray-100">
              <div className="flex items-center gap-2">
                <LucideClock className="h-4 w-4 text-gray-400" />
                <span className="font-semibold text-gray-800">{slot}</span>
              </div>
              <div className="flex items-center gap-3">
                {/* Quota indicator */}
                <span
                  className={`text-xs font-medium px-2 py-1 rounded-full ${
                    isExceeded
                      ? 'bg-red-100 text-red-700'
                      : fleteroCount > 0
                        ? 'bg-yellow-100 text-yellow-700'
                        : 'bg-gray-100 text-gray-500'
                  }`}
                  data-testid={`quota-${slot.replace(':', '-')}`}
                >
                  {t('loading.fleterosCount', { count: fleteroCount })}
                </span>
              </div>
            </div>

            {/* Entries list */}
            <div className="divide-y divide-gray-100">
              {entries.length === 0 ? (
                <div className="px-4 py-3 text-sm text-gray-400 italic">
                  {t('loading.emptyDay').split('.')[0]}
                </div>
              ) : (
                entries.map((entry) => (
                  <div
                    key={entry.id}
                    className="flex items-center justify-between px-4 py-2.5 hover:bg-gray-50"
                    data-testid={`entry-${entry.id}`}
                  >
                    <div className="flex-1 min-w-0">
                      <span className="text-sm font-medium text-gray-800">
                        {getDisplayName(entry)}
                      </span>
                      {getDisplayPlate(entry) && (
                        <span className="text-xs text-gray-400 ml-2">
                          {getDisplayPlate(entry)}
                        </span>
                      )}
                      {entry.driver_type === 'casa' && (
                        <span className="text-xs text-blue-500 ml-2 font-medium">
                          (Casa)
                        </span>
                      )}
                    </div>
                    {entry.canDelete && (
                      <button
                        type="button"
                        onClick={() => handleDelete(entry.id)}
                        disabled={deleting === entry.id}
                        className="inline-flex items-center gap-1 rounded px-2 py-1 text-sm text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50"
                        aria-label={t('loading.deleteEntry')}
                        data-testid={`delete-entry-${entry.id}`}
                      >
                        <LucideX className="h-4 w-4" />
                        {t('loading.deleteEntry')}
                      </button>
                    )}
                  </div>
                ))
              )}
            </div>

            {/* Add fletero area */}
            <div className="px-4 py-2 border-t border-gray-100 bg-white">
              {isAdding ? (
                <DriverSelect
                  timeSlot={slot}
                  date={date}
                  drivers={drivers}
                  assignedIds={assignedIds}
                  assignedVehicleIds={assignedVehicleIds}
                  onAssigned={handleAssigned}
                  onClose={() => setAddingSlot(null)}
                />
              ) : (
                <div className="flex flex-col gap-1">
                  <button
                    type="button"
                    onClick={() => setAddingSlot(slot)}
                    disabled={loading}
                    className="flex items-center gap-1 text-sm text-blue-600 hover:text-blue-700 font-medium py-1 disabled:opacity-50"
                    data-testid={`add-fletero-${slot.replace(':', '-')}`}
                  >
                    <LucidePlus className="h-4 w-4" />
                    {t('loading.addFletero')}
                  </button>
                  {isExceeded && (
                    <p className="text-xs text-red-500 py-0.5" data-testid={`maxed-${slot.replace(':', '-')}`}>
                      ⚠️ {t('loading.quotaError')}
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
