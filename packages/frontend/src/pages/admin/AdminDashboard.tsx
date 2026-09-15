import { useEffect, useState, useCallback, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { api } from '../../api/client';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { LoadingSpinner } from '../../components/ui/loading-spinner';

// ---- Types ----

type CategoryType = 'check' | 'temperature' | 'check_assai' | 'check_normal';

interface Category {
  id: number;
  name_pt: string;
  name_es: string;
  category_type: CategoryType;
  sort_order: number;
  is_active: number;
}

interface Task {
  id: number;
  category_id: number;
  name_pt: string;
  name_es: string;
  is_active: number;
  created_by_user_id: number | null;
  category_name?: string;
  task_type?: CategoryType;
  temperature_readings: number;
}

interface Driver {
  id: number;
  name: string;
  license_plate: string | null;
  driver_type: 'casa' | 'fletero';
  is_active: number;
  created_by_user_id: number | null;
}

interface Vehicle {
  id: number;
  description: string;
  license_plate: string;
  is_active: number;
}

interface User {
  id: number;
  username: string;
  display_name: string;
  role_id: number;
  role_name?: string;
  is_active: number;
}

type TabKey = 'categories' | 'tasks' | 'drivers' | 'vehicles' | 'timeSlots' | 'workers' | 'profile';

interface Tab {
  key: TabKey;
  labelKey: string;
}

function getTabs(t: (key: string) => string): Tab[] {
  return [
    { key: 'categories', labelKey: 'admin.tabs.categories' },
    { key: 'tasks', labelKey: 'admin.tabs.tasks' },
    { key: 'drivers', labelKey: 'admin.tabs.drivers' },
    { key: 'vehicles', labelKey: 'admin.tabs.vehicles' },
    { key: 'timeSlots', labelKey: 'admin.tabs.timeSlots' },
    { key: 'workers', labelKey: 'admin.tabs.workers' },
    { key: 'profile', labelKey: 'admin.tabs.profile' },
  ];
}

// ---- Inline form component ----

function InlineForm({
  fields,
  onSubmit,
  onCancel,
  submitLabel,
  cancelLabel,
  loading,
  children,
}: {
  fields: { label: string; name: string; type?: string; value: string; onChange: (v: string) => void; required?: boolean }[];
  onSubmit: (e: FormEvent) => void;
  onCancel: () => void;
  submitLabel: string;
  cancelLabel: string;
  loading: boolean;
  children?: React.ReactNode;
}) {
  return (
    <form onSubmit={onSubmit} className="space-y-3 p-4 bg-gray-50 rounded-lg border mb-4">
      {fields.map((f) => (
        <div key={f.name} className="space-y-1">
          <label className="block text-xs font-medium text-gray-600">{f.label}</label>
          <Input
            type={f.type ?? 'text'}
            value={f.value}
            onChange={(e) => f.onChange(e.target.value)}
            required={f.required !== false}
          />
        </div>
      ))}
      {children}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={loading}>
          {submitLabel}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={onCancel}>
          {cancelLabel}
        </Button>
      </div>
    </form>
  );
}

// ---- Empty state for role guard ----

function AccessDenied({ t }: { t: (key: string) => string }) {
  return (
    <div className="flex items-center justify-center py-16">
      <div className="text-center">
        <p className="text-lg font-medium text-gray-500">{t('admin.accessDenied')}</p>
        <p className="text-sm text-gray-400 mt-1">{t('admin.accessDeniedDesc')}</p>
      </div>
    </div>
  );
}

// ---- Badge component ----

function Badge({ active, activeLabel, inactiveLabel }: { active: number; activeLabel: string; inactiveLabel: string }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
        active ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
    >
      {active ? activeLabel : inactiveLabel}
    </span>
  );
}

// ---- Main Component ----

export function AdminDashboard() {
  const { t, i18n } = useTranslation();
  const { user } = useAuth();
  const isSpanish = i18n.language?.startsWith('es');

  const isAdmin = user?.role === 'Administrador';
  const isWorker = user?.role === 'Trabalhador';
  const TABS = getTabs(t).filter((tab) =>
    isWorker ? ['tasks', 'drivers', 'profile'].includes(tab.key) : tab.key !== 'profile'
  );

  const [activeTab, setActiveTab] = useState<TabKey>(isWorker ? 'tasks' : 'categories');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [reloadCount, setReloadCount] = useState(0);

  // Data stores
  const [categories, setCategories] = useState<Category[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [timeSlots, setTimeSlots] = useState<string[]>([]);
  const [workers, setWorkers] = useState<User[]>([]);

  // Form state
  const [showForm, setShowForm] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const isEditingOwnUser = activeTab === 'workers' && editingId !== null && editingId === user?.id;

  // Category form
  const [catNamePt, setCatNamePt] = useState('');
  const [catNameEs, setCatNameEs] = useState('');
  const [catSortOrder, setCatSortOrder] = useState('0');
  const [catType, setCatType] = useState<CategoryType>('check');

  // Task form
  const [taskCatId, setTaskCatId] = useState('');
  const [taskNamePt, setTaskNamePt] = useState('');
  const [taskNameEs, setTaskNameEs] = useState('');
  const [taskTemperatureReadings, setTaskTemperatureReadings] = useState('1');

  // Driver form
  const [driverName, setDriverName] = useState('');
  const [driverType, setDriverType] = useState<'casa' | 'fletero'>('fletero');

  // Vehicle form
  const [vehDesc, setVehDesc] = useState('');
  const [vehPlate, setVehPlate] = useState('');

  // Time slot form
  const [slotInput, setSlotInput] = useState('');

  // Worker form
  const [workerUsername, setWorkerUsername] = useState('');
  const [workerDisplayName, setWorkerDisplayName] = useState('');
  const [workerPassword, setWorkerPassword] = useState('');
  const [workerRoleId, setWorkerRoleId] = useState('2'); // defaults to role 'Trabalhador' (role_id 2)
  const [workerIsActive, setWorkerIsActive] = useState('1');

  // Profile form
  const [profileUsername, setProfileUsername] = useState('');
  const [profileDisplayName, setProfileDisplayName] = useState('');
  const [profileOriginalDisplayName, setProfileOriginalDisplayName] = useState('');
  const [profileCurrentPassword, setProfileCurrentPassword] = useState('');
  const [profileNewPassword, setProfileNewPassword] = useState('');

  // Delete confirmation state
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [deleteEntity, setDeleteEntity] = useState<'categories' | 'tasks' | 'drivers' | 'vehicles' | 'users' | ''>('');
  const [deleteId, setDeleteId] = useState<number | null>(null);
  const [deleteLabel, setDeleteLabel] = useState('');

  // ---- Data Fetching ----

  const loadTab = useCallback(async (tab: TabKey) => {
    if (!user || (!isAdmin && !isWorker)) return;
    setLoading(true);
    setError('');
    try {
      switch (tab) {
        case 'categories': {
          if (!isAdmin && !isWorker) return;
          const data = await api.get<{ categories: Category[] }>('/admin/categories');
          setCategories(data.categories);
          break;
        }
        case 'tasks': {
          const [taskData, categoryData] = await Promise.all([
            api.get<{ tasks: Task[] }>('/admin/tasks'),
            api.get<{ categories: Category[] }>('/admin/categories'),
          ]);
          setTasks(taskData.tasks);
          setCategories(categoryData.categories);
          break;
        }
        case 'drivers': {
          const data = await api.get<{ drivers: Driver[] }>('/admin/drivers');
          setDrivers(data.drivers);
          break;
        }
        case 'vehicles': {
          if (!isAdmin) return;
          const data = await api.get<{ vehicles: Vehicle[] }>('/admin/vehicles');
          setVehicles(data.vehicles);
          break;
        }
        case 'timeSlots': {
          if (!isAdmin) return;
          const data = await api.get<{ timeSlots: string[] }>('/admin/time-slots');
          setTimeSlots(data.timeSlots);
          break;
        }
        case 'workers': {
          if (!isAdmin) return;
          const data = await api.get<{ users: User[] }>('/admin/users');
          setWorkers(data.users);
          break;
        }
        case 'profile': {
          const data = await api.get<{ user: { username: string; display_name: string } }>('/auth/profile');
          setProfileUsername(data.user.username);
          setProfileDisplayName(data.user.display_name);
          setProfileOriginalDisplayName(data.user.display_name);
          break;
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.loadingError'));
    } finally {
      setLoading(false);
    }
  }, [t, user, isAdmin, isWorker]);

  useEffect(() => {
    if (isWorker && !['tasks', 'drivers', 'profile'].includes(activeTab)) {
      setActiveTab('tasks');
      return;
    }
    loadTab(activeTab);
  }, [activeTab, reloadCount, loadTab, isWorker]);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => setError(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [error]);

  useEffect(() => {
    if (success) {
      const timer = setTimeout(() => setSuccess(''), 10000);
      return () => clearTimeout(timer);
    }
  }, [success]);

  // Check role
  if (!user || (!isAdmin && !isWorker)) {
    return <AccessDenied t={t} />;
  }

  // ---- Helpers ----

  function resetFormFields() {
    setCatNamePt('');
    setCatNameEs('');
    setCatSortOrder('0');
    setTaskCatId('');
    setTaskNamePt('');
    setTaskNameEs('');
    setTaskTemperatureReadings('1');
    setCatType('check');
    setDriverType('fletero');
    setDriverName('');
    setVehDesc('');
    setVehPlate('');
    setSlotInput('');
    setWorkerUsername('');
    setWorkerDisplayName('');
    setWorkerPassword('');
    setWorkerRoleId('2');
    setWorkerIsActive('1');
    setProfileCurrentPassword('');
    setProfileNewPassword('');
  }

  function handleCancel() {
    setShowForm(false);
    setEditingId(null);
    resetFormFields();
  }

  function reloadData() {
    setShowForm(false);
    setEditingId(null);
    resetFormFields();
    setReloadCount((c) => c + 1);
  }

  function startEditCategory(cat: Category) {
    setEditingId(cat.id);
    setCatNamePt(cat.name_pt);
    setCatNameEs(cat.name_es);
    setCatSortOrder(cat.sort_order.toString());
    setCatType(cat.category_type || 'check');
    setShowForm(true);
  }

  function canEditTask(task: Task) {
    return isAdmin || (isWorker && task.created_by_user_id === user.id);
  }

  function canEditDriver(driver: Driver) {
    return isAdmin || (isWorker && driver.driver_type === 'fletero' && driver.created_by_user_id === user.id);
  }

  function startEditTask(task: Task) {
    if (!canEditTask(task)) return;
    setEditingId(task.id);
    setTaskCatId(task.category_id.toString());
    setTaskNamePt(task.name_pt);
    setTaskNameEs(task.name_es);
    setTaskTemperatureReadings(String(task.temperature_readings ?? 1));
    setShowForm(true);
  }

  function startEditDriver(driver: Driver) {
    if (!canEditDriver(driver)) return;
    setEditingId(driver.id);
    setDriverName(driver.name);
    setDriverType(driver.driver_type || 'fletero');
    setShowForm(true);
  }

  function startEditVehicle(veh: Vehicle) {
    setEditingId(veh.id);
    setVehDesc(veh.description);
    setVehPlate(veh.license_plate);
    setShowForm(true);
  }

  function startEditWorker(w: User) {
    setEditingId(w.id);
    setWorkerUsername(w.username);
    setWorkerDisplayName(w.display_name);
    setWorkerPassword('');
    setWorkerRoleId(w.role_id.toString());
    setWorkerIsActive(w.is_active.toString());
    setShowForm(true);
  }

  function triggerDelete(entity: 'categories' | 'tasks' | 'drivers' | 'vehicles' | 'users', id: number, label: string) {
    setDeleteEntity(entity);
    setDeleteId(id);
    setDeleteLabel(label);
    setDeleteConfirmOpen(true);
  }

  async function confirmDelete() {
    if (!deleteEntity || deleteId === null) return;
    setError('');
    setSuccess('');
    try {
      await api.del(`/admin/${deleteEntity}/${deleteId}`);
      setSuccess(t('admin.deleteSuccess'));
      reloadData();
    } catch (err: any) {
      if (err && err.status === 400) {
        setError(t('admin.deleteConstraintError'));
      } else {
        setError(err instanceof Error ? err.message : t('ui.error'));
      }
    } finally {
      setDeleteConfirmOpen(false);
      setDeleteEntity('');
      setDeleteId(null);
      setDeleteLabel('');
    }
  }

  async function toggleActive(entity: string, id: number, currentActive: number) {
    try {
      await api.patch(`/admin/${entity}/${id}`, { is_active: currentActive === 1 ? 0 : 1 });
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.statusError'));
    }
  }

  // ---- Submit Handlers ----

  async function handleCreateCategory(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    try {
      const payload: any = {
        category_type: catType,
        sort_order: parseInt(catSortOrder) || 0,
      };
      if (editingId) {
        payload.name_pt = catNamePt;
        payload.name_es = catNameEs;
      } else if (isSpanish) {
        payload.name_es = catNameEs;
      } else {
        payload.name_pt = catNamePt;
      }

      if (editingId) {
        await api.patch(`/admin/categories/${editingId}`, payload);
      } else {
        await api.post('/admin/categories', payload);
      }
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.saveCategoryError'));
    } finally {
      setFormLoading(false);
    }
  }

  async function handleCreateTask(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    setError('');
    setSuccess('');
    try {
      const payload: any = {
        category_id: parseInt(taskCatId),
      };
      const category = categories.find((item) => item.id === parseInt(taskCatId));
      if (category?.category_type === 'temperature') {
        payload.temperature_readings = parseInt(taskTemperatureReadings);
      }
      const isEditing = Boolean(editingId);
      if (editingId) {
        payload.name_pt = taskNamePt;
        payload.name_es = taskNameEs;
      } else if (isSpanish) {
        payload.name_es = taskNameEs;
      } else {
        payload.name_pt = taskNamePt;
      }

      if (editingId) {
        await api.patch(`/admin/tasks/${editingId}`, payload);
      } else {
        await api.post('/admin/tasks', payload);
      }
      setSuccess(t(isEditing ? 'admin.taskUpdateSuccess' : 'admin.taskCreateSuccess'));
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.saveTaskError'));
    } finally {
      setFormLoading(false);
    }
  }

  async function handleCreateDriver(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    setError('');
    setSuccess('');
    try {
      const isEditing = Boolean(editingId);
      if (editingId) {
        await api.patch(`/admin/drivers/${editingId}`, {
          name: driverName,
          driver_type: isWorker ? 'fletero' : driverType,
        });
      } else {
        await api.post('/admin/drivers', {
          name: driverName,
          driver_type: isWorker ? 'fletero' : driverType,
        });
      }
      setSuccess(t(isEditing ? 'admin.driverUpdateSuccess' : 'admin.driverCreateSuccess'));
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.saveDriverError'));
    } finally {
      setFormLoading(false);
    }
  }

  async function handleCreateVehicle(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    try {
      if (editingId) {
        await api.patch(`/admin/vehicles/${editingId}`, {
          description: vehDesc,
          license_plate: vehPlate,
        });
      } else {
        await api.post('/admin/vehicles', {
          description: vehDesc,
          license_plate: vehPlate,
        });
      }
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.saveVehicleError'));
    } finally {
      setFormLoading(false);
    }
  }

  async function handleCreateWorker(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    setError('');
    setSuccess('');
    try {
      if (editingId) {
        const payload: any = {
          display_name: workerDisplayName,
        };

        if (!isEditingOwnUser) {
          payload.username = workerUsername;
          payload.role_id = parseInt(workerRoleId, 10);
          payload.is_active = parseInt(workerIsActive, 10);
        }
        if (workerPassword) {
          payload.password = workerPassword;
        }
        await api.patch(`/admin/users/${editingId}`, payload);
      } else {
        await api.post('/admin/users', {
          username: workerUsername,
          display_name: workerDisplayName,
          password: workerPassword,
          role_id: parseInt(workerRoleId, 10),
        });
      }
      reloadData();
    } catch (err: any) {
      setError(err instanceof Error ? err.message : t('admin.saveWorkerError'));
    } finally {
      setFormLoading(false);
    }
  }


  async function handleProfileSubmit(e: FormEvent) {
    e.preventDefault();
    setFormLoading(true);
    setError('');
    setSuccess('');
    try {
      const displayNameChanged = profileDisplayName.trim() !== profileOriginalDisplayName.trim();
      const passwordChanged = Boolean(profileCurrentPassword || profileNewPassword);

      if (displayNameChanged) {
        await api.patch('/auth/profile', {
          display_name: profileDisplayName,
        });
      }

      if (passwordChanged) {
        await api.post('/auth/change-password', {
          currentPassword: profileCurrentPassword,
          newPassword: profileNewPassword,
        });
      }

      if (displayNameChanged && passwordChanged) {
        setSuccess(t('admin.profile.profileAndPasswordSuccess'));
      } else if (passwordChanged) {
        setSuccess(t('admin.profile.passwordSuccess'));
      } else if (displayNameChanged) {
        setSuccess(t('admin.profile.saveSuccess'));
      } else {
        setSuccess(t('admin.profile.saveSuccess'));
      }

      setProfileOriginalDisplayName(profileDisplayName);
      setProfileCurrentPassword('');
      setProfileNewPassword('');
      setReloadCount((c) => c + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.profile.saveError'));
    } finally {
      setFormLoading(false);
    }
  }

  async function handleAddTimeSlot(e: FormEvent) {
    e.preventDefault();
    if (!slotInput.trim()) return;
    const updated = [...timeSlots, slotInput.trim()].sort();
    setFormLoading(true);
    try {
      await api.put('/admin/time-slots', { time_slots: updated });
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.updateTimeSlotsError'));
    } finally {
      setFormLoading(false);
      setSlotInput('');
    }
  }

  async function handleRemoveTimeSlot(slot: string) {
    const updated = timeSlots.filter((s) => s !== slot);
    setFormLoading(true);
    try {
      await api.put('/admin/time-slots', { time_slots: updated });
      reloadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('admin.removeTimeSlotError'));
    } finally {
      setFormLoading(false);
    }
  }

  // ---- Render ----

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div>
      <h3 className="text-lg font-semibold text-gray-800 mb-4">{isWorker ? t('admin.limitedTitle') : t('admin.title')}</h3>

      {/* Tabs */}
      <div className="flex overflow-x-auto flex-nowrap gap-2 mb-4 border-b pb-2 -mx-4 px-4 sm:mx-0 sm:px-0 scrollbar-none">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            onClick={() => { setActiveTab(tab.key); handleCancel(); }}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors shrink-0 ${
              activeTab === tab.key
                ? 'bg-blue-100 text-blue-700 font-medium'
                : 'text-gray-600 hover:bg-gray-100'
            }`}
          >
            {t(tab.labelKey)}
          </button>
        ))}
      </div>

      {error && (
        <div role="alert" aria-live="assertive" className="p-3 mb-4 text-sm text-red-700 bg-red-50 border border-red-200 rounded-md">
          {error}
        </div>
      )}
      {success && (
        <div role="alert" aria-live="assertive" className="p-3 mb-4 text-sm text-green-700 bg-green-50 border border-green-200 rounded-md">
          {success}
        </div>
      )}

      {/* Add button */}
      {activeTab !== 'profile' && (
      <div className="flex justify-end mb-3">
        <Button size="sm" onClick={() => {
          if (showForm) {
            handleCancel();
          } else {
            setShowForm(true);
          }
        }}>
          {showForm ? t('admin.cancel') : t('admin.addNew')}
        </Button>
      </div>
      )}

      {/* Category form */}
      {showForm && activeTab === 'categories' && (
        <InlineForm
          fields={[
            ...(editingId
              ? [
                  { label: t('admin.form.namePtBr'), name: 'name_pt', value: catNamePt, onChange: setCatNamePt },
                  { label: t('admin.form.nameEs'), name: 'name_es', value: catNameEs, onChange: setCatNameEs },
                ]
              : [
                  isSpanish
                    ? { label: t('admin.form.nameEs'), name: 'name_es', value: catNameEs, onChange: setCatNameEs }
                    : { label: t('admin.form.namePtBr'), name: 'name_pt', value: catNamePt, onChange: setCatNamePt },
                ]),
            { label: t('admin.form.order'), name: 'sort_order', type: 'number', value: catSortOrder, onChange: setCatSortOrder },
          ]}
          onSubmit={handleCreateCategory}
          onCancel={handleCancel}
          submitLabel={editingId ? t('admin.update') : t('admin.create')}
          cancelLabel={t('admin.cancel')}
          loading={formLoading}
        >
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.form.type') || 'Tipo'}</label>
            <select
              value={catType}
              onChange={(e) => setCatType(e.target.value as CategoryType)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
            >
              <option value="check">{t('admin.taskTypes.check') || 'Check'}</option>
              <option value="temperature">{t('admin.taskTypes.temperature') || 'Temperatura'}</option>
              <option value="check_assai">{t('admin.taskTypes.check_assai') || 'Check Assaí'}</option>
              <option value="check_normal">{t('admin.taskTypes.check_normal') || 'Check Normal'}</option>
            </select>
          </div>
        </InlineForm>
      )}

      {/* Task form */}
      {showForm && activeTab === 'tasks' && (
        <InlineForm
          fields={[
            ...(editingId
              ? [
                  { label: t('admin.form.namePtBr'), name: 'name_pt', value: taskNamePt, onChange: setTaskNamePt },
                  { label: t('admin.form.nameEs'), name: 'name_es', value: taskNameEs, onChange: setTaskNameEs },
                ]
              : [
                  isSpanish
                    ? { label: t('admin.form.nameEs'), name: 'name_es', value: taskNameEs, onChange: setTaskNameEs }
                    : { label: t('admin.form.namePtBr'), name: 'name_pt', value: taskNamePt, onChange: setTaskNamePt },
                ]),
          ]}
          onSubmit={handleCreateTask}
          onCancel={handleCancel}
          submitLabel={editingId ? t('admin.update') : t('admin.create')}
          cancelLabel={t('admin.cancel')}
          loading={formLoading}
        >
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.form.categoryId')}</label>
            <select
              value={taskCatId}
              onChange={(e) => setTaskCatId(e.target.value)}
              required
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
            >
              <option value="">{i18n.language === 'es' ? 'Seleccione una categoría' : 'Selecione uma categoria'}</option>
              {categories.map((c) => {
                const catName = i18n.language === 'es' ? c.name_es : c.name_pt;
                return (
                  <option key={c.id} value={c.id.toString()}>
                    {catName}
                  </option>
                );
              })}
            </select>
          </div>
          {categories.find((category) => category.id === parseInt(taskCatId))?.category_type === 'temperature' && (
            <div className="space-y-1">
              <label className="block text-xs font-medium text-gray-600">{t('admin.form.temperatureReadings')}</label>
              <select
                value={taskTemperatureReadings}
                onChange={(e) => setTaskTemperatureReadings(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
                data-testid="temperature-readings-select"
              >
                <option value="1">1</option>
                <option value="2">2</option>
                <option value="3">3</option>
              </select>
            </div>
          )}
        </InlineForm>
      )}

      {/* Driver form */}
      {showForm && activeTab === 'drivers' && (
        <InlineForm
          fields={[
            { label: t('admin.form.name'), name: 'name', value: driverName, onChange: setDriverName },
          ]}
          onSubmit={handleCreateDriver}
          onCancel={handleCancel}
          submitLabel={editingId ? t('admin.update') : t('admin.create')}
          cancelLabel={t('admin.cancel')}
          loading={formLoading}
        >
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.form.type') || 'Tipo'}</label>
            <select
              value={driverType}
              onChange={(e) => setDriverType(e.target.value as 'casa' | 'fletero')}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
            >
              <option value="fletero">{t('admin.driverTypes.fletero') || 'Terceirizado (Fletero)'}</option>
              {isAdmin && <option value="casa">{t('admin.driverTypes.casa') || 'Próprio (Casa)'}</option>}
            </select>
          </div>
        </InlineForm>
      )}

      {/* Vehicle form */}
      {showForm && activeTab === 'vehicles' && (
        <InlineForm
          fields={[
            { label: t('admin.form.description'), name: 'description', value: vehDesc, onChange: setVehDesc },
            { label: t('admin.form.plate'), name: 'license_plate', value: vehPlate, onChange: setVehPlate },
          ]}
          onSubmit={handleCreateVehicle}
          onCancel={handleCancel}
          submitLabel={editingId ? t('admin.update') : t('admin.create')}
          cancelLabel={t('admin.cancel')}
          loading={formLoading}
        />
      )}

      {/* Time Slot form */}
      {showForm && activeTab === 'timeSlots' && (
        <form onSubmit={handleAddTimeSlot} className="space-y-3 p-4 bg-gray-50 rounded-lg border mb-4">
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.form.newTimeSlot')}</label>
            <Input
              type="text"
              placeholder="HH:MM"
              value={slotInput}
              onChange={(e) => setSlotInput(e.target.value)}
              required
            />
          </div>
          <Button type="submit" size="sm" disabled={formLoading}>
            {formLoading ? t('admin.saving') : t('admin.add')}
          </Button>
        </form>
      )}

      {/* Worker form */}
      {showForm && activeTab === 'workers' && (
        <form onSubmit={handleCreateWorker} className="space-y-3 p-4 bg-gray-50 rounded-lg border mb-4">
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.workers.form.username')}</label>
            <Input
              type="text"
              value={workerUsername}
              onChange={(e) => setWorkerUsername(e.target.value)}
              required
              disabled={isEditingOwnUser}
            />
          </div>
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.workers.form.displayName')}</label>
            <Input
              type="text"
              value={workerDisplayName}
              onChange={(e) => setWorkerDisplayName(e.target.value)}
              required
            />
          </div>
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">
              {t('admin.workers.form.password')}
            </label>
            <Input
              type="password"
              value={workerPassword}
              onChange={(e) => setWorkerPassword(e.target.value)}
              required={!editingId}
              placeholder={editingId ? '••••••••' : ''}
            />
            {workerPassword && !(workerPassword.length >= 8 && /[A-Z]/.test(workerPassword) && /[0-9]/.test(workerPassword)) && (
              <p className="text-xs text-amber-600 bg-amber-50 border border-amber-200 rounded p-2 mt-1">
                ⚠️ {t('auth.passwordStrengthWarning')}
              </p>
            )}
          </div>
          {!isEditingOwnUser && (
            <div className="space-y-1">
              <label className="block text-xs font-medium text-gray-600">{t('admin.workers.form.role')}</label>
              <select
                value={workerRoleId}
                onChange={(e) => setWorkerRoleId(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
              >
                <option value="2">{t('admin.workers.form.roleWorker')}</option>
                <option value="1">{t('admin.workers.form.roleAdmin')}</option>
              </select>
            </div>
          )}
          {editingId && !isEditingOwnUser && (
            <div className="space-y-1">
              <label className="block text-xs font-medium text-gray-600">{t('admin.table.status')}</label>
              <select
                value={workerIsActive}
                onChange={(e) => setWorkerIsActive(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm bg-white"
              >
                <option value="1">{t('admin.statusActive')}</option>
                <option value="0">{t('admin.statusInactive')}</option>
              </select>
            </div>
          )}
          <div className="flex gap-2 pt-2">
            <Button type="submit" size="sm" disabled={formLoading}>
              {editingId ? t('admin.workers.update') : t('admin.workers.create')}
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={handleCancel}>
              {t('admin.cancel')}
            </Button>
          </div>
        </form>
      )}



      {/* Profile form */}
      {activeTab === 'profile' && (
        <form onSubmit={handleProfileSubmit} className="space-y-3 p-4 bg-gray-50 rounded-lg border mb-4">
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('auth.username')}</label>
            <Input value={profileUsername} disabled readOnly />
          </div>
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('admin.workers.form.displayName')}</label>
            <Input value={profileDisplayName} onChange={(e) => setProfileDisplayName(e.target.value)} required />
          </div>
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('auth.currentPassword')}</label>
            <Input type="password" value={profileCurrentPassword} onChange={(e) => setProfileCurrentPassword(e.target.value)} />
          </div>
          <div className="space-y-1">
            <label className="block text-xs font-medium text-gray-600">{t('auth.newPassword')}</label>
            <Input type="password" value={profileNewPassword} onChange={(e) => setProfileNewPassword(e.target.value)} />
            {profileNewPassword && !(profileNewPassword.length >= 8 && /[A-Z]/.test(profileNewPassword) && /[0-9]/.test(profileNewPassword)) && (
              <p className="text-xs text-amber-600 bg-amber-50 border border-amber-200 rounded p-2 mt-1">
                ⚠️ {t('auth.passwordStrengthWarning')}
              </p>
            )}
          </div>
          <Button type="submit" size="sm" disabled={formLoading}>{formLoading ? t('admin.saving') : t('ui.save')}</Button>
        </form>
      )}

      {/* Data tables */}
      <Card>
        <CardContent className="pt-4">
          {/* Categories */}
          {activeTab === 'categories' && (
            <div className="overflow-x-auto w-full">
              <table className="w-full text-sm min-w-[650px]">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('admin.table.namePtBr')}</th>
                    <th className="py-2 pr-2">{t('admin.table.nameEs')}</th>
                    <th className="py-2 pr-2">{t('admin.table.type') || t('admin.form.type')}</th>
                    <th className="py-2 pr-2">{t('admin.table.order')}</th>
                    <th className="py-2 pr-2">{t('admin.table.status')}</th>
                    <th className="py-2">{t('admin.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {categories.map((cat) => (
                    <tr key={cat.id} className="border-b last:border-0 hover:bg-gray-50">
                      <td className="py-2 pr-2">{cat.name_pt}</td>
                      <td className="py-2 pr-2">{cat.name_es}</td>
                      <td className="py-2 pr-2 text-xs">
                        {cat.category_type === 'temperature'
                          ? (t('admin.taskTypes.temperature') || 'Temperatura')
                          : cat.category_type === 'check_assai'
                          ? (t('admin.taskTypes.check_assai') || 'Check Assaí')
                          : cat.category_type === 'check_normal'
                          ? (t('admin.taskTypes.check_normal') || 'Check Normal')
                          : (t('admin.taskTypes.check') || 'Check')}
                      </td>
                      <td className="py-2 pr-2">{cat.sort_order}</td>
                      <td className="py-2 pr-2">
                        <Badge active={cat.is_active} activeLabel={t('admin.statusActive')} inactiveLabel={t('admin.statusInactive')} />
                      </td>
                      <td className="py-2">
                        <div className="flex items-center gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => startEditCategory(cat)}
                          >
                            {t('admin.edit')}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => toggleActive('categories', cat.id, cat.is_active)}
                          >
                            {cat.is_active ? t('admin.deactivate') : t('admin.activate')}
                          </Button>
                          <Button
                            size="sm"
                            variant="destructive"
                            onClick={() => triggerDelete('categories', cat.id, isSpanish && cat.name_es ? cat.name_es : cat.name_pt)}
                          >
                            {t('ui.delete')}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {categories.length === 0 && (
                    <tr><td colSpan={6} className="py-4 text-center text-gray-400">{t('admin.empty.categories')}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Tasks */}
          {activeTab === 'tasks' && (
            <div className="overflow-x-auto w-full">
              <table className="w-full text-sm min-w-[600px]">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('admin.table.category')}</th>
                    <th className="py-2 pr-2">
                      {isSpanish ? t('admin.table.nameEs') : t('admin.table.namePtBr')}
                    </th>
                    <th className="py-2 pr-2">{t('admin.table.type')}</th>
                    <th className="py-2 pr-2">{t('admin.table.temperatureReadings')}</th>
                    <th className="py-2 pr-2">{t('admin.table.status')}</th>
                    <th className="py-2">{t('admin.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.map((task) => {
                    const taskName = isSpanish && task.name_es ? task.name_es : task.name_pt;
                    const cat = categories.find((c) => c.id === task.category_id);
                    const categoryName = cat
                      ? (isSpanish && cat.name_es ? cat.name_es : cat.name_pt)
                      : (task.category_name ?? `#${task.category_id}`);

                    return (
                      <tr key={task.id} className="border-b last:border-0 hover:bg-gray-50">
                        <td className="py-2 pr-2 text-xs text-gray-500">{categoryName}</td>
                        <td className="py-2 pr-2">{taskName}</td>
                        <td className="py-2 pr-2 text-xs">
                          {task.task_type === 'temperature'
                            ? (t('admin.taskTypes.temperature') || 'Temperatura')
                            : task.task_type === 'check_assai'
                            ? (t('admin.taskTypes.check_assai') || 'Check Assaí')
                            : task.task_type === 'check_normal'
                            ? (t('admin.taskTypes.check_normal') || 'Check Normal')
                            : (t('admin.taskTypes.check') || 'Check')}
                        </td>
                        <td className="py-2 pr-2 text-xs">{task.task_type === 'temperature' ? task.temperature_readings : '—'}</td>
                        <td className="py-2 pr-2">
                          <Badge active={task.is_active} activeLabel={t('admin.statusActive')} inactiveLabel={t('admin.statusInactive')} />
                        </td>
                        <td className="py-2">
                          <div className="flex items-center gap-2">
                            {canEditTask(task) ? (
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => startEditTask(task)}
                              >
                                {t('admin.edit')}
                              </Button>
                            ) : (
                              <span className="text-xs text-gray-400">{t('admin.readOnly')}</span>
                            )}
                            {isAdmin && (
                              <>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => toggleActive('tasks', task.id, task.is_active)}
                                >
                                  {task.is_active ? t('admin.deactivate') : t('admin.activate')}
                                </Button>
                                <Button
                                  size="sm"
                                  variant="destructive"
                                  onClick={() => triggerDelete('tasks', task.id, taskName)}
                                >
                                  {t('ui.delete')}
                                </Button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                  {tasks.length === 0 && (
                    <tr><td colSpan={6} className="py-4 text-center text-gray-400">{t('admin.empty.tasks')}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Drivers */}
          {activeTab === 'drivers' && (
            <div className="overflow-x-auto w-full">
              <table className="w-full text-sm min-w-[550px]">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('admin.table.id')}</th>
                    <th className="py-2 pr-2">{t('admin.table.name')}</th>
                    <th className="py-2 pr-2">{t('admin.table.type') || t('admin.form.type')}</th>
                    <th className="py-2 pr-2">{t('admin.table.status')}</th>
                    <th className="py-2">{t('admin.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {drivers.map((d) => (
                    <tr key={d.id} className="border-b last:border-0 hover:bg-gray-50">
                      <td className="py-2 pr-2">{d.id}</td>
                      <td className="py-2 pr-2">{d.name}</td>
                      <td className="py-2 pr-2 text-xs">
                        {d.driver_type === 'casa'
                          ? (t('admin.driverTypes.casa') || 'Casa')
                          : (t('admin.driverTypes.fletero') || 'Fletero')}
                      </td>
                      <td className="py-2 pr-2">
                        <Badge active={d.is_active} activeLabel={t('admin.statusActive')} inactiveLabel={t('admin.statusInactive')} />
                      </td>
                      <td className="py-2">
                        <div className="flex items-center gap-2">
                          {canEditDriver(d) ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => startEditDriver(d)}
                            >
                              {t('admin.edit')}
                            </Button>
                          ) : (
                            <span className="text-xs text-gray-400">{t('admin.readOnly')}</span>
                          )}
                          {isAdmin && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => toggleActive('drivers', d.id, d.is_active)}
                            >
                              {d.is_active ? t('admin.deactivate') : t('admin.activate')}
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {drivers.length === 0 && (
                    <tr><td colSpan={5} className="py-4 text-center text-gray-400">{t('admin.empty.drivers')}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Vehicles */}
          {activeTab === 'vehicles' && (
            <div className="overflow-x-auto w-full">
              <table className="w-full text-sm min-w-[550px]">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('admin.table.id')}</th>
                    <th className="py-2 pr-2">{t('admin.table.description')}</th>
                    <th className="py-2 pr-2">{t('admin.table.plate')}</th>
                    <th className="py-2 pr-2">{t('admin.table.status')}</th>
                    <th className="py-2">{t('admin.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {vehicles.map((v) => (
                    <tr key={v.id} className="border-b last:border-0 hover:bg-gray-50">
                      <td className="py-2 pr-2">{v.id}</td>
                      <td className="py-2 pr-2">{v.description}</td>
                      <td className="py-2 pr-2">{v.license_plate}</td>
                      <td className="py-2 pr-2">
                        <Badge active={v.is_active} activeLabel={t('admin.statusActive')} inactiveLabel={t('admin.statusInactive')} />
                      </td>
                      <td className="py-2">
                        <div className="flex items-center gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => startEditVehicle(v)}
                          >
                            {t('admin.edit')}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => toggleActive('vehicles', v.id, v.is_active)}
                          >
                            {v.is_active ? t('admin.deactivate') : t('admin.activate')}
                          </Button>
                          <Button
                            size="sm"
                            variant="destructive"
                            onClick={() => triggerDelete('vehicles', v.id, v.description)}
                          >
                            {t('ui.delete')}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {vehicles.length === 0 && (
                    <tr><td colSpan={5} className="py-4 text-center text-gray-400">{t('admin.empty.vehicles')}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Time Slots */}
          {activeTab === 'timeSlots' && (
            <div>
              {timeSlots.length === 0 ? (
                <p className="text-center text-gray-400 py-4">{t('admin.empty.timeSlots')}</p>
              ) : (
                <ul className="space-y-1">
                  {timeSlots.map((slot) => (
                    <li key={slot} className="flex items-center justify-between py-2 px-3 bg-gray-50 rounded">
                      <span className="text-sm font-mono">{slot}</span>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="text-red-600 hover:text-red-800"
                        onClick={() => handleRemoveTimeSlot(slot)}
                      >
                        {t('admin.remove')}
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {/* Workers */}
          {activeTab === 'workers' && (
            <div className="overflow-x-auto w-full">
              <table className="w-full text-sm min-w-[600px]">
                <thead>
                  <tr className="border-b text-left text-xs text-gray-500 uppercase">
                    <th className="py-2 pr-2">{t('admin.workers.table.username')}</th>
                    <th className="py-2 pr-2">{t('admin.workers.table.displayName')}</th>
                    <th className="py-2 pr-2">{t('admin.workers.table.role')}</th>
                    <th className="py-2 pr-2">{t('admin.workers.table.status')}</th>
                    <th className="py-2">{t('admin.workers.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {workers.map((w) => (
                    <tr key={w.id} className="border-b last:border-0 hover:bg-gray-50">
                      <td className="py-2 pr-2">{w.username}</td>
                      <td className="py-2 pr-2">{w.display_name}</td>
                      <td className="py-2 pr-2">
                        {w.role_id === 1 ? t('admin.workers.form.roleAdmin') : t('admin.workers.form.roleWorker')}
                      </td>
                      <td className="py-2 pr-2">
                        <Badge active={w.is_active} activeLabel={t('admin.statusActive')} inactiveLabel={t('admin.statusInactive')} />
                      </td>
                      <td className="py-2">
                        <div className="flex items-center gap-2">
                          {(() => {
                            const isCurrentUser = w.id === user.id;
                            return (
                              <>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => startEditWorker(w)}
                                >
                                  {t('admin.edit')}
                                </Button>
                                {!isCurrentUser && (
                                  <>
                                    <Button
                                      size="sm"
                                      variant="outline"
                                      onClick={() => toggleActive('users', w.id, w.is_active)}
                                    >
                                      {w.is_active ? t('admin.deactivate') : t('admin.activate')}
                                    </Button>
                                    <Button
                                      size="sm"
                                      variant="destructive"
                                      onClick={() => triggerDelete('users', w.id, w.username)}
                                    >
                                      {t('ui.delete')}
                                    </Button>
                                  </>
                                )}
                              </>
                            );
                          })()}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {workers.length === 0 && (
                    <tr><td colSpan={5} className="py-4 text-center text-gray-400">{t('admin.workers.empty')}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Delete confirmation modal */}
      {deleteConfirmOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="bg-white rounded-lg p-6 max-w-sm w-full shadow-xl border">
            <h3 className="text-lg font-semibold text-gray-900 mb-2">
              {t('ui.delete')}
            </h3>
            <p className="text-sm text-gray-600 mb-4">
              {t('ui.deleteConfirm')}
              {deleteLabel && <span className="block mt-2 font-medium text-gray-800">"{deleteLabel}"</span>}
            </p>
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setDeleteConfirmOpen(false)}
              >
                {t('ui.cancel')}
              </Button>
              <Button
                type="button"
                variant="destructive"
                size="sm"
                onClick={confirmDelete}
              >
                {t('ui.deletePermanently') || t('ui.delete')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
