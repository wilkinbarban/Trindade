import { z } from 'zod';
import { PaginationSchema, TimeSlotsResponseSchema } from '../../contracts/common.schema.js';

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

// ---- Response Schemas ----
//
// Schemas rather than interfaces, so the contract generator can read them and tests can
// validate what the routes really return. The interfaces these replace marked nearly every
// schedule field optional, which is exactly what hid the fact that `create` and `update`
// returned a different shape from `listByDate`: the mutation responses carried neither
// `creator` nor the permission flags, and casting both to the same type suppressed the
// difference. Every schema here is `.strict()`, so a route returning an undocumented field
// fails a test instead of drifting past the document.

/** The creator summary attached to a schedule and to a loading batch. */
const CreatorSchema = z.object({ id: z.number().int(), display_name: z.string() }).strict();

export const ScheduleSchema = z
  .object({
    id: z.number().int(),
    schedule_date: z.string(),
    time_slot: z.string(),
    driver_type: z.enum(['fletero', 'casa']),
    driver_id: z.number().int().nullable(),
    driver_name: z.string().nullable(),
    license_plate: z.string().nullable(),
    vehicle_id: z.number().int().nullable(),
    vehicle_description: z.string().nullable(),
    vehicle_plate: z.string().nullable(),
    user_id: z.number().int().nullable(),
    created_at: z.string(),
    updated_at: z.string(),
    // The raw `is_active` and `creator_name` columns are deliberately NOT part of this response.
    // They used to be, because the projection spreads the row through, and they were kept for a
    // while as documented duplication. They are gone because they make the contract unusable for a
    // generated client: `is_active` and `isActive` differ only by case, so a generator that converts
    // snake_case to camelCase emits two properties with the same name, and Kotlin -- like Java --
    // cannot declare both. The first consumer that tried turned a tolerated wart into a blocker.
    // The projection still reads them as input; it no longer returns them.
    isActive: z.boolean(),
    readOnly: z.boolean(),
    canEdit: z.boolean(),
    canDeactivate: z.boolean(),
    canDelete: z.boolean(),
    creator: CreatorSchema.nullable(),
  })
  .strict();

export const LoadingBatchHistoryItemSchema = z
  .object({
    batch_date: z.string(),
    loading_date: z.string(),
    total_loadings: z.number().int(),
    created_at: z.string().nullable(),
    updated_at: z.string().nullable(),
    isActive: z.boolean(),
    canEdit: z.boolean(),
    canDeactivate: z.boolean(),
    canDelete: z.boolean(),
    readOnly: z.boolean(),
    creator: CreatorSchema.nullable(),
  })
  .strict();

export const DriverSchema = z
  .object({
    id: z.number().int(),
    name: z.string(),
    license_plate: z.string().nullable(),
    driver_type: z.enum(['casa', 'fletero']),
  })
  .strict();

export const VehicleSchema = z
  .object({
    id: z.number().int(),
    description: z.string(),
    license_plate: z.string(),
  })
  .strict();

// ---- Response Envelopes ----

export const SchedulesResponseSchema = z.object({ schedules: z.array(ScheduleSchema) }).strict();

export const ScheduleResponseSchema = z.object({ schedule: ScheduleSchema }).strict();

export const ScheduleHistoryResponseSchema = z
  .object({ items: z.array(LoadingBatchHistoryItemSchema), pagination: PaginationSchema })
  .strict();

export const DriversResponseSchema = z.object({ drivers: z.array(DriverSchema) }).strict();

export const DriverResponseSchema = z.object({ driver: DriverSchema }).strict();

export const VehiclesResponseSchema = z.object({ vehicles: z.array(VehicleSchema) }).strict();

// Defined in `contracts/common.schema.ts`, which holds the shapes more than one module returns, and
// re-exported here because the loading contract test and the contract generator already import it
// from this module. Reusing the shared definition is what keeps the loading and admin responses the
// same shape instead of two declarations that can drift apart.
export { TimeSlotsResponseSchema };

// The types the service and the routes keep importing, derived from the schemas so they cannot
// drift away from the contract.
export type ScheduleRow = z.infer<typeof ScheduleSchema>;
export type LoadingBatchHistoryItem = z.infer<typeof LoadingBatchHistoryItemSchema>;
export type DriverRow = z.infer<typeof DriverSchema>;
export type Vehicle = z.infer<typeof VehicleSchema>;
