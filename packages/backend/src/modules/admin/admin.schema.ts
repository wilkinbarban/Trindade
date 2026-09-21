import { z } from 'zod';

// ---- Category ----

export const CATEGORY_TYPES = ['check', 'temperature', 'check_assai', 'check_normal'] as const;
export type CategoryType = typeof CATEGORY_TYPES[number];

export const CreateCategorySchema = z.object({
  parent_category_id: z.number().int().positive().nullable().optional(),
  name_pt: z.string().min(1).optional(),
  name_es: z.string().min(1).optional(),
  category_type: z.enum(CATEGORY_TYPES).optional().default('check'),
  sort_order: z.number().int().nonnegative().optional().default(0),
}).refine(data => data.name_pt || data.name_es, {
  message: 'Name in Portuguese or Spanish is required',
  path: ['name_pt'],
});

export const UpdateCategorySchema = z.object({
  parent_category_id: z.number().int().positive().nullable().optional(),
  name_pt: z.string().min(1).optional(),
  name_es: z.string().min(1).optional(),
  category_type: z.enum(CATEGORY_TYPES).optional(),
  sort_order: z.number().int().nonnegative().optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
});

export type CreateCategoryBody = z.infer<typeof CreateCategorySchema>;
export type UpdateCategoryBody = z.infer<typeof UpdateCategorySchema>;

// ---- Task ----

export const CreateTaskSchema = z.object({
  category_id: z.number().int().positive(),
  name_pt: z.string().min(1).optional(),
  name_es: z.string().min(1).optional(),
  temperature_readings: z.number().int().min(1).max(3).optional().default(1),
}).refine(data => data.name_pt || data.name_es, {
  message: 'Name in Portuguese or Spanish is required',
  path: ['name_pt'],
});

export const UpdateTaskSchema = z.object({
  category_id: z.number().int().positive().optional(),
  name_pt: z.string().min(1).optional(),
  name_es: z.string().min(1).optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
  temperature_readings: z.number().int().min(1).max(3).optional(),
});

export type CreateTaskBody = z.infer<typeof CreateTaskSchema>;
export type UpdateTaskBody = z.infer<typeof UpdateTaskSchema>;

// ---- Driver ----

export const CreateDriverSchema = z.object({
  name: z.string().min(1, 'Driver name is required'),
  license_plate: z.string().optional(),
  driver_type: z.enum(['casa', 'fletero']).optional().default('fletero'),
});

export const UpdateDriverSchema = z.object({
  name: z.string().min(1).optional(),
  license_plate: z.string().optional(),
  driver_type: z.enum(['casa', 'fletero']).optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
});

export type CreateDriverBody = z.infer<typeof CreateDriverSchema>;
export type UpdateDriverBody = z.infer<typeof UpdateDriverSchema>;

// ---- Vehicle ----

export const CreateVehicleSchema = z.object({
  description: z.string().min(1, 'Description is required'),
  license_plate: z.string().min(1, 'License plate is required'),
});

export const UpdateVehicleSchema = z.object({
  description: z.string().min(1).optional(),
  license_plate: z.string().min(1).optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
});

export type CreateVehicleBody = z.infer<typeof CreateVehicleSchema>;
export type UpdateVehicleBody = z.infer<typeof UpdateVehicleSchema>;

// ---- Time Slots ----

export const UpdateTimeSlotsSchema = z.object({
  time_slots: z.array(z.string().min(1)).min(1, 'At least one time slot is required'),
});

export type UpdateTimeSlotsBody = z.infer<typeof UpdateTimeSlotsSchema>;

// ---- Users ----

export const CreateUserSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(4, 'Password must be at least 4 characters long'),
  display_name: z.string().min(1, 'Display name is required'),
  role_id: z.union([z.literal(1), z.literal(2)]),
});

export const UpdateUserSchema = z.object({
  username: z.string().min(1).optional(),
  password: z.string().min(4).optional(),
  display_name: z.string().min(1).optional(),
  role_id: z.union([z.literal(1), z.literal(2)]).optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
});

export type CreateUserBody = z.infer<typeof CreateUserSchema>;
export type UpdateUserBody = z.infer<typeof UpdateUserSchema>;

// ---- Response Schemas ----
//
// Schemas rather than interfaces, so the contract generator can read them and a contract test can
// validate what the routes really return. The interfaces these replace typed the ids as `number`
// and marked `task_type` and `role_name` optional, while the queries return those fields on every
// row: an interface can describe a response the code never produces and nothing would notice, which
// is the drift a machine-readable contract exists to prevent. Every schema is `.strict()`, so a
// route returning an undocumented field fails a test instead of drifting past the document.

/** A category as `SELECT * FROM report_categories` returns it, which every category route sends. */
export const CategorySchema = z
  .object({
    id: z.number().int(),
    parent_category_id: z.number().int().nullable(),
    name_pt: z.string(),
    name_es: z.string(),
    category_type: z.enum(CATEGORY_TYPES),
    sort_order: z.number().int(),
    is_active: z.number().int(),
    created_at: z.string(),
  })
  .strict();

/**
 * A task as the create and update queries return it.
 *
 * `task_type` is the CATEGORY's type, which the query selects as `task_type`: a task inherits the
 * type of the category it belongs to rather than carrying one of its own.
 */
export const TaskSchema = z
  .object({
    id: z.number().int(),
    category_id: z.number().int(),
    name_pt: z.string(),
    name_es: z.string(),
    temperature_readings: z.number().int(),
    is_active: z.number().int(),
    created_by_user_id: z.number().int().nullable(),
    created_at: z.string(),
    task_type: z.enum(CATEGORY_TYPES),
  })
  .strict();

/**
 * A task as the listing returns it: the same row plus the category name, which only `listTasks`
 * selects. Two schemas rather than one optional field, because `category_name` is on every listed
 * task and on none of the written ones, and an optional field would describe neither.
 */
export const TaskListItemSchema = TaskSchema.extend({ category_name: z.string() }).strict();

/** A driver as `SELECT * FROM drivers` returns it. */
export const DriverSchema = z
  .object({
    id: z.number().int(),
    name: z.string(),
    license_plate: z.string().nullable(),
    driver_type: z.enum(['casa', 'fletero']),
    is_active: z.number().int(),
    created_by_user_id: z.number().int().nullable(),
    created_at: z.string(),
  })
  .strict();

/** A vehicle as `SELECT * FROM vehicles` returns it. */
export const VehicleSchema = z
  .object({
    id: z.number().int(),
    description: z.string(),
    license_plate: z.string(),
    is_active: z.number().int(),
    created_at: z.string(),
  })
  .strict();

/**
 * A user as `listUsers` and the user mutations return it.
 *
 * The projection deliberately omits `password_hash` and `updated_at`, and `role_name` arrives from an
 * INNER JOIN, so every listed row carries it rather than the field being optional.
 */
export const UserSchema = z
  .object({
    id: z.number().int(),
    username: z.string(),
    display_name: z.string(),
    role_id: z.number().int(),
    role_name: z.string(),
    is_active: z.number().int(),
    created_at: z.string(),
  })
  .strict();

// The admin time-slot route serves the same `settings.loading_time_slots` value under the same
// envelope as the loading module, so the shape is reused instead of re-declared. Its single
// definition lives in `contracts/common.schema.ts`, next to the other shapes more than one module
// returns, and it is re-exported here because the admin contract test imports it from this module.
export { TimeSlotsResponseSchema } from '../../contracts/common.schema.js';

// ---- Response Envelopes ----
//
// Objects rather than bare arrays, because that is what the handlers send. `.strict()` on a bare
// array would accept nothing, and the envelope is what a generated client deserialises.

export const CategoriesResponseSchema = z.object({ categories: z.array(CategorySchema) }).strict();
export const CategoryResponseSchema = z.object({ category: CategorySchema }).strict();
export const TasksResponseSchema = z.object({ tasks: z.array(TaskListItemSchema) }).strict();
export const TaskResponseSchema = z.object({ task: TaskSchema }).strict();
export const DriversResponseSchema = z.object({ drivers: z.array(DriverSchema) }).strict();
export const DriverResponseSchema = z.object({ driver: DriverSchema }).strict();
export const VehiclesResponseSchema = z.object({ vehicles: z.array(VehicleSchema) }).strict();
export const VehicleResponseSchema = z.object({ vehicle: VehicleSchema }).strict();
export const UsersResponseSchema = z.object({ users: z.array(UserSchema) }).strict();
export const UserResponseSchema = z.object({ user: UserSchema }).strict();

// The types the service keeps importing, derived from the schemas so they cannot drift away from
// the contract.
export type CategoryRow = z.infer<typeof CategorySchema>;
export type TaskRow = z.infer<typeof TaskSchema>;
export type DriverRow = z.infer<typeof DriverSchema>;
export type VehicleRow = z.infer<typeof VehicleSchema>;
export type UserRow = z.infer<typeof UserSchema>;
