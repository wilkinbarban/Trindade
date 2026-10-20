import { z } from 'zod';

/**
 * Zod schemas for wire contracts of the Massas Launch endpoints.
 */
export const MassaProductTotalSchema = z
  .object({
    code: z.string(),
    name: z.string(),
    unitsPerBox: z.number().int(),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
  })
  .strict();

export const MassaIndividualLaunchSchema = z
  .object({
    id: z.number().int(),
    launch_date: z.string(),
    product_code: z.string(),
    product_name: z.string(),
    boxes: z.number().int(),
    units: z.number().int(),
    user_id: z.number().int(),
    username: z.string(),
    display_name: z.string(),
    created_at: z.string(),
    updated_at: z.string(),
  })
  .strict();

export const MassasLaunchDayViewSchema = z
  .object({
    date: z.string(),
    products: z.array(MassaProductTotalSchema),
    launches: z.array(MassaIndividualLaunchSchema),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
  })
  .strict();

export const MassasLaunchCreateItemSchema = z
  .object({
    product_code: z.string().min(1),
    boxes: z.number().int().min(1),
  })
  .strict();

export const MassasLaunchCreateBodySchema = z
  .object({
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD').optional(),
    product_code: z.string().min(1).optional(),
    boxes: z.number().int().min(1).optional(),
    items: z.array(MassasLaunchCreateItemSchema).optional(),
  })
  .strict();

export const MassasLaunchUpdateBodySchema = z
  .object({
    boxes: z.number().int().min(1),
  })
  .strict();

export const MassasLaunchCreateResponseSchema = z
  .object({
    created: z.array(MassaIndividualLaunchSchema),
  })
  .strict();

export const MassasLaunchDeleteResponseSchema = z
  .object({
    ok: z.literal(true),
  })
  .strict();

export const MassaHistoryDaySchema = z
  .object({
    date: z.string(),
    productsCount: z.number().int(),
    totalBoxes: z.number().int(),
    totalUnits: z.number().int(),
    operatorsCount: z.number().int(),
  })
  .strict();

export const MassasHistoryResponseSchema = z
  .object({
    days: z.array(MassaHistoryDaySchema),
  })
  .strict();

