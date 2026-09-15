import { useTranslation } from 'react-i18next';
import { Input } from '../ui/input';
import { ASSAI_PRODUCTS, NORMAL_PRODUCTS } from './productTemplates';

// ---- Types ----

export interface CategoryType {
  id: number;
  parent_category_id?: number | null;
  name_pt: string;
  name_es: string;
  sort_order: number;
  tasks: TaskType[];
}

export interface TaskType {
  id: number;
  category_id: number;
  name_pt: string;
  name_es: string;
  task_type: 'check' | 'temperature' | 'check_assai' | 'check_normal';
  sort_order: number;
  temperature_readings: number;
}

export interface FormState {
  items: Record<number, boolean>;
  temperatures: Record<number, string[]>;
  selectedProducts?: Record<number, string[]>;
}

// ---- Props ----

interface CategorySectionProps {
  category: CategoryType;
  children?: CategoryType[];
  form: FormState;
  onItemChange: (taskId: number, checked: boolean) => void;
  onTemperatureChange: (taskId: number, readingIndex: number, value: string) => void;
  onSelectedProductsChange?: (taskId: number, products: string[]) => void;
  readOnly?: boolean;
}

// ---- Sub-components ----

function ProductCheckTask({
  task,
  products,
  selectedProducts,
  onChange,
  readOnly,
}: {
  task: TaskType;
  products: readonly string[];
  selectedProducts: string[];
  onChange: (selected: string[]) => void;
  readOnly?: boolean;
}) {
  const { i18n } = useTranslation();
  const isSpanish = i18n.language?.startsWith('es');
  const taskName = isSpanish && task.name_es ? task.name_es : task.name_pt;

  const handleProductToggle = (product: string, checked: boolean) => {
    if (readOnly) return;
    if (checked) {
      onChange([...selectedProducts, product]);
    } else {
      onChange(selectedProducts.filter((p) => p !== product));
    }
  };

  return (
    <div className="py-2 border-b border-gray-100 last:border-0">
      <span className="text-sm font-semibold text-gray-700 block mb-1">{taskName}</span>
      <div className="pl-4 space-y-1.5 mt-1">
        {products.map((prod) => {
          const isChecked = selectedProducts.includes(prod);
          return (
            <label key={prod} className={`flex items-center gap-3 py-1 ${readOnly ? 'cursor-not-allowed opacity-75' : 'cursor-pointer'} hover:bg-gray-50 rounded px-2 -mx-2`}>
              <input
                type="checkbox"
                checked={isChecked}
                disabled={readOnly}
                onChange={(e) => handleProductToggle(prod, e.target.checked)}
                className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                data-testid={`product-task-${task.id}-${prod}`}
              />
              <span className="text-sm text-gray-600 select-none">{prod}</span>
            </label>
          );
        })}
      </div>
    </div>
  );
}

function CheckTask({
  task,
  checked,
  onChange,
}: {
  task: TaskType;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const { i18n } = useTranslation();
  const isSpanish = i18n.language?.startsWith('es');
  const taskName = isSpanish && task.name_es ? task.name_es : task.name_pt;
  return (
    <label className="flex items-center gap-3 py-2 cursor-pointer hover:bg-gray-50 rounded px-3 -mx-3">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="h-5 w-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        data-testid={`check-task-${task.id}`}
      />
      <span className="text-sm text-gray-800 select-none">{taskName}</span>
    </label>
  );
}

function TemperatureTask({
  task,
  values,
  onChange,
}: {
  task: TaskType;
  values: string[];
  onChange: (readingIndex: number, value: string) => void;
}) {
  const { i18n } = useTranslation();
  const isSpanish = i18n.language?.startsWith('es');
  const taskName = isSpanish && task.name_es ? task.name_es : task.name_pt;
  return (
    <div className="py-2">
      <label className="block text-sm font-medium text-gray-700 mb-1">{taskName}</label>
      {Array.from({ length: task.temperature_readings }, (_, index) => {
        const readingIndex = index + 1;
        return (
          <div key={readingIndex} className="flex items-center gap-2 max-w-xs mb-1">
            {task.temperature_readings > 1 && <span className="text-xs text-gray-500 w-16">{`${readingIndex}º`}</span>}
            <Input
              type="number"
              value={values[index] ?? ''}
              onChange={(e) => onChange(readingIndex, e.target.value)}
              placeholder="-18"
              step="0.1"
              className="w-28"
              data-testid={readingIndex === 1 ? `temperature-task-${task.id}` : `temperature-task-${task.id}-${readingIndex}`}
            />
            <span className="text-sm text-gray-400">°C</span>
          </div>
        );
      })}
    </div>
  );
}

// ---- Main Component ----

export function CategorySection({
  category,
  children,
  form,
  onItemChange,
  onTemperatureChange,
  onSelectedProductsChange,
  readOnly,
}: CategorySectionProps) {
  const { i18n } = useTranslation();
  const isSpanish = i18n.language?.startsWith('es');
  const catName = isSpanish && category.name_es ? category.name_es : category.name_pt;

  return (
    <div className="mb-6">
      <h3 className="text-md font-semibold text-gray-800 mb-3 pb-2 border-b border-gray-200">
        {catName}
      </h3>
      <div className="space-y-1">
        {category.tasks.map((task) => {
          switch (task.task_type) {
            case 'check':
              return (
                <CheckTask
                  key={task.id}
                  task={task}
                  checked={form.items[task.id] ?? false}
                  onChange={(checked) => onItemChange(task.id, checked)}
                />
              );

            case 'check_assai':
              return (
                <ProductCheckTask
                  key={task.id}
                  task={task}
                  products={ASSAI_PRODUCTS}
                  selectedProducts={form.selectedProducts?.[task.id] ?? []}
                  onChange={(selected) => onSelectedProductsChange && onSelectedProductsChange(task.id, selected)}
                  readOnly={readOnly}
                />
              );

            case 'check_normal':
              return (
                <ProductCheckTask
                  key={task.id}
                  task={task}
                  products={NORMAL_PRODUCTS}
                  selectedProducts={form.selectedProducts?.[task.id] ?? []}
                  onChange={(selected) => onSelectedProductsChange && onSelectedProductsChange(task.id, selected)}
                  readOnly={readOnly}
                />
              );

            case 'temperature':
              return (
                <TemperatureTask
                  key={task.id}
                  task={task}
                  values={form.temperatures[task.id] ?? []}
                  onChange={(readingIndex, value) => onTemperatureChange(task.id, readingIndex, value)}
                />
              );

            default:
              return null;
          }
        })}
      </div>
      {children?.map((child) => (
        <div key={child.id} className="mt-5 ml-2 border-l-2 border-blue-100 pl-4">
          <CategorySection
            category={child}
            form={form}
            onItemChange={onItemChange}
            onTemperatureChange={onTemperatureChange}
            onSelectedProductsChange={onSelectedProductsChange}
            readOnly={readOnly}
          />
        </div>
      ))}
    </div>
  );
}
