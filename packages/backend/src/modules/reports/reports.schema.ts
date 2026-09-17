import { z } from 'zod';
import { PaginationSchema } from '../../contracts/common.schema.js';

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

// ---- Response Schemas ----
//
// Schemas rather than interfaces, so the contract generator can read them and tests can validate
// what the routes really return. They are `.strict()`, which is what makes a test prove the
// server sends exactly the documented fields instead of accepting anything extra.

/** The four element types a category can define. Reused from the request-side list above. */
export const TaskTypeSchema = z.enum(TASK_TYPES);

export const SelectedProductsSchema = z.array(z.string());

export const TaskResponseSchema = z
  .object({
    id: z.number().int(),
    category_id: z.number().int(),
    name_pt: z.string(),
    name_es: z.string(),
    // The CATEGORY's type, which is what the query selects as `task_type`: a task inherits the
    // type of the category it belongs to rather than carrying one of its own.
    task_type: TaskTypeSchema,
    temperature_readings: z.number().int(),
  })
  .strict();

export const CategoryResponseSchema = z
  .object({
    id: z.number().int(),
    parent_category_id: z.number().int().nullable(),
    name_pt: z.string(),
    name_es: z.string(),
    sort_order: z.number().int(),
    // Selected and returned by the query even though the interface this replaces omitted it, so
    // it is documented rather than dropped: it is what a client keys its rendering off.
    category_type: TaskTypeSchema,
    tasks: z.array(TaskResponseSchema),
  })
  .strict();

export const ReportUserSchema = z
  .object({ id: z.number().int(), display_name: z.string() })
  .strict();

export const ReportListItemSchema = z
  .object({
    id: z.number().int(),
    turno: z.enum(['tarde', 'noite']),
    report_date: z.string(),
    notes: z.string().nullable(),
    created_at: z.string(),
    user: ReportUserSchema,
    // Server-computed lifecycle flags, present only on the endpoints that pass an actor.
    isActive: z.boolean().optional(),
    readOnly: z.boolean().optional(),
    canEdit: z.boolean().optional(),
    canDeactivate: z.boolean().optional(),
    canDelete: z.boolean().optional(),
  })
  .strict();

export const ReportItemDetailSchema = z
  .object({
    task_id: z.number().int(),
    task_name: z.string(),
    task_name_es: z.string(),
    task_type: TaskTypeSchema,
    category_name: z.string(),
    category_name_es: z.string(),
    checked: z.boolean(),
    selectedProducts: SelectedProductsSchema.optional(),
  })
  .strict();

export const ReportTemperatureDetailSchema = z
  .object({
    location: z.string(),
    // Resolved through a LEFT JOIN, so a reading whose location no longer matches a task has no
    // Spanish name. That is the only reason this one is nullable where the other `_es` fields are not.
    location_es: z.string().nullable(),
    readingIndex: z.number().int(),
    value: z.number(),
  })
  .strict();

export const ReportDetailSchema = ReportListItemSchema.extend({
  updated_at: z.string(),
  items: z.array(ReportItemDetailSchema),
  temperatures: z.array(ReportTemperatureDetailSchema),
}).strict();

/**
 * Read a stored selected-product list out of `report_items.selected_products`.
 *
 * The column is written only from request input the API already validated, so content that does
 * not parse means corruption from outside this application. Returning an empty selection keeps the
 * report readable instead of turning a read into a driver error, which is what an unguarded
 * `JSON.parse` did here.
 */
export function parseSelectedProducts(value: string | null | undefined): string[] | undefined {
  if (!value) return undefined;

  let decoded: unknown;
  try {
    decoded = JSON.parse(value);
  } catch {
    return [];
  }

  const parsed = SelectedProductsSchema.safeParse(decoded);
  return parsed.success ? parsed.data : [];
}

export const PhotoSchema = z
  .object({
    id: z.number().int(),
    report_id: z.number().int(),
    file_path: z.string(),
    file_size: z.number().int(),
    mime_type: z.string(),
    public_token: z.string(),
    created_at: z.string(),
    // Built by the projection rather than stored in the table, so the retention cleanup and the
    // delete path, which read rows straight from the table, do not carry them.
    url: z.string().optional(),
    publicUrl: z.string().optional(),
  })
  .strict();

// ---- Response Envelopes ----

export const CategoriesResponseSchema = z.object({ categories: z.array(CategoryResponseSchema) }).strict();
export const ReportsResponseSchema = z.object({ reports: z.array(ReportListItemSchema) }).strict();
export const ReportResponseSchema = z.object({ report: ReportDetailSchema }).strict();
export const ReportHistoryResponseSchema = z
  .object({ items: z.array(ReportListItemSchema), pagination: PaginationSchema })
  .strict();
export const TurnoResponseSchema = z.object({ turno: z.enum(['tarde', 'noite']) }).strict();
export const PhotosResponseSchema = z.object({ photos: z.array(PhotoSchema) }).strict();
export const PhotoResponseSchema = z.object({ photo: PhotoSchema }).strict();

// The types the services keep importing, derived from the schemas so they cannot drift away from
// the contract.
export type CategoryResponse = z.infer<typeof CategoryResponseSchema>;
export type TaskResponse = z.infer<typeof TaskResponseSchema>;
export type ReportListItem = z.infer<typeof ReportListItemSchema>;
export type ReportDetail = z.infer<typeof ReportDetailSchema>;
export type ReportItemDetail = z.infer<typeof ReportItemDetailSchema>;
export type ReportTemperatureDetail = z.infer<typeof ReportTemperatureDetailSchema>;
export type ReportPhoto = z.infer<typeof PhotoSchema>;

/** Shared with the loading module, so it lives in `contracts/common.schema.ts`. */
export type HistoryPagination = z.infer<typeof PaginationSchema>;
