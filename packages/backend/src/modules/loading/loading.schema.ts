import { z } from 'zod';

// ---- Query Params ----

export const ScheduleQuerySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.'),
});

export const HistoryQuerySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  month: z.string().regex(/^\d{4}-\d{2}$/).optional(),
  userId: z.coerce.number().int().positive().optional(),
  page: z.coerce.number().int().positive().optional().default(1),
  pageSize: z.coerce.number().int().positive().max(100).optional().default(30),
});
export type HistoryQuery = z.infer<typeof HistoryQuerySchema>;

export const ExportQuerySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.'),
});

// ---- Request Bodies ----

export const CreateScheduleSchema = z
  .object({
    schedule_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.'),
    time_slot: z.string().min(1, 'Time slot is required'),
    driver_type: z.enum(['fletero', 'casa']),
    driver_id: z.number().int().positive('Driver ID is required'),
    vehicle_id: z.number().int().positive().nullable().optional(),
  })
  .refine(
    (data) => {
      if (data.driver_type === 'casa') {
        return !!data.vehicle_id;
      }
      if (data.driver_type === 'fletero') {
        return !data.vehicle_id;
      }
      return true;
    },
    { message: 'Vehicle is required for casa drivers and must be null/empty for fleteros', path: ['vehicle_id'] }
  );

export type CreateScheduleBody = z.infer<typeof CreateScheduleSchema>;

export const CreateDriverSchema = z.object({
  name: z.string().min(1, 'Driver name is required'),
  license_plate: z.string().optional(),
});

export type CreateDriverBody = z.infer<typeof CreateDriverSchema>;

export const UpdateScheduleSchema = z.object({
  schedule_date: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format. Use YYYY-MM-DD.')
    .optional(),
  time_slot: z.string().min(1, 'Time slot is required').optional(),
  driver_type: z.enum(['fletero', 'casa']).optional(),
  driver_id: z.number().int().positive().optional(),
  vehicle_id: z.number().int().positive().optional().nullable(),
});

export type UpdateScheduleBody = z.infer<typeof UpdateScheduleSchema>;

// ---- Response Types (TypeScript only, not validated) ----

export interface LoadingBatchHistoryItem {
  batch_date: string;
  loading_date: string;
  total_loadings: number;
  created_at: string | null;
  updated_at: string | null;
  isActive: boolean;
  canEdit: boolean;
  canDeactivate: boolean;
  canDelete: boolean;
  readOnly: boolean;
  creator: { id: number; display_name: string } | null;
}

export interface ScheduleRow {
  id: number;
  schedule_date: string;
  time_slot: string;
  driver_type: 'fletero' | 'casa';
  driver_id: number;
  driver_name: string | null;
  license_plate: string | null;
  vehicle_id: number | null;
  vehicle_description: string | null;
  vehicle_plate: string | null;
  user_id?: number | null;
  created_at?: string;
  updated_at?: string;
  isActive?: boolean;
  readOnly?: boolean;
  canEdit?: boolean;
  canDeactivate?: boolean;
  canDelete?: boolean;
  creator?: { id: number; display_name: string } | null;
}

export interface DriverRow {
  id: number;
  name: string;
  license_plate: string | null;
  driver_type: 'casa' | 'fletero';
}
