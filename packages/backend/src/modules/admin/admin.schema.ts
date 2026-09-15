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

// ---- Response Types (TypeScript only) ----

export interface CategoryRow {
  id: number;
  parent_category_id: number | null;
  name_pt: string;
  name_es: string;
  category_type: CategoryType;
  sort_order: number;
  is_active: number;
  created_at: string;
}

export interface TaskRow {
  id: number;
  category_id: number;
  name_pt: string;
  name_es: string;
  is_active: number;
  created_by_user_id: number | null;
  temperature_readings: number;
  created_at: string;
  task_type?: CategoryType;
}

export interface DriverRow {
  id: number;
  name: string;
  license_plate: string | null;
  driver_type: 'casa' | 'fletero';
  is_active: number;
  created_by_user_id: number | null;
  created_at: string;
}

export interface VehicleRow {
  id: number;
  description: string;
  license_plate: string;
  is_active: number;
  created_at: string;
}

export interface UserRow {
  id: number;
  username: string;
  display_name: string;
  role_id: number;
  role_name?: string;
  is_active: number;
  created_at: string;
}
