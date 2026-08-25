import { z } from 'zod';

export const TASK_TYPES = ['check', 'temperature', 'check_assai', 'check_normal'] as const;
export type TaskType = typeof TASK_TYPES[number];

// ---- Request Bodies ----

export const ReportItemSchema = z.object({
  taskId: z.number().int().positive(),
  checked: z.boolean(),
  selectedProducts: z.array(z.string()).optional(),
});

export const ReportTemperatureSchema = z.object({
  location: z.string().min(1, 'Location is required'),
  readingIndex: z.number().int().min(1).max(3).optional().default(1),
  value: z.number(),
});

export const CreateReportBodySchema = z.object({
  turno: z.enum(['tarde', 'noite']).optional(),
  notes: z.string().optional(),
  items: z.array(ReportItemSchema).optional().default([]),
  temperatures: z.array(ReportTemperatureSchema).optional().default([]),
});

export type CreateReportBody = z.infer<typeof CreateReportBodySchema>;

export const UpdateReportBodySchema = z.object({
  turno: z.enum(['tarde', 'noite']).optional(),
  notes: z.string().nullable().optional(),
  items: z.array(ReportItemSchema).optional(),
  temperatures: z.array(ReportTemperatureSchema).optional(),
});

export type UpdateReportBody = z.infer<typeof UpdateReportBodySchema>;

// ---- Query Params ----

export const ReportQuerySchema = z.object({
  period: z.enum(['today', 'yesterday', '7days', '30days', 'custom']).optional().default('today'),
  from: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.')
    .optional(),
  to: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.')
    .optional(),
});

export type ReportQuery = z.infer<typeof ReportQuerySchema>;

export const HistoryQuerySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  month: z.string().regex(/^\d{4}-\d{2}$/).optional(),
  userId: z.coerce.number().int().positive().optional(),
  page: z.coerce.number().int().positive().optional().default(1),
  pageSize: z.coerce.number().int().positive().max(100).optional().default(30),
});

export type HistoryQuery = z.infer<typeof HistoryQuerySchema>;

export interface HistoryPagination {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}


// ---- Response Types (TypeScript only, not validated) ----

export interface CategoryResponse {
  id: number;
  parent_category_id: number | null;
  name_pt: string;
  name_es: string;
  sort_order: number;
  tasks: TaskResponse[];
}

export interface TaskResponse {
  id: number;
  category_id: number;
  name_pt: string;
  name_es: string;
  task_type: TaskType;
  temperature_readings: number;
  sort_order: number;
}

export interface ReportListItem {
  id: number;
  turno: 'tarde' | 'noite';
  report_date: string;
  notes: string | null;
  created_at: string;
  isActive?: boolean;
  readOnly?: boolean;
  canEdit?: boolean;
  canDeactivate?: boolean;
  canDelete?: boolean;
  user: {
    id: number;
    display_name: string;
  };
}

export interface ReportDetail extends ReportListItem {
  updated_at: string;
  items: ReportItemDetail[];
  temperatures: ReportTemperatureDetail[];
}

export interface ReportItemDetail {
  task_id: number;
  task_name: string;
  task_name_es?: string;
  task_type: string;
  category_name: string;
  category_name_es?: string;
  checked: boolean;
  selectedProducts?: string[];
}

export interface ReportTemperatureDetail {
  location: string;
  location_es?: string;
  readingIndex: number;
  value: number;
}
