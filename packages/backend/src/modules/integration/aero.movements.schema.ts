import { z } from 'zod';

/**
 * Codes this endpoint answers with, so the page branches on the reason instead of on the sentence.
 */
export const AERO_MOVEMENTS_ERROR_CODES = {
  INVALID_DATE_FORMAT: 'INVALID_DATE_FORMAT',
  INVALID_MOVEMENTS: 'INVALID_MOVEMENTS',
  NOT_MIGRATED: 'AERO_MOVEMENTS_NOT_MIGRATED',
} as const;

const dateOnly = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD');

/**
 * One seat as the company WebService answers it, already mapped by the page.
 *
 * Strict on purpose: the bridge carries whatever the ERP said, and a shape this module does not know is
 * a bug worth refusing rather than storing. `quantity` is in units -- the request travels scaled by 100
 * because Firebird needs the integer, but the answer comes back human (`90`, not `9000`).
 */
export const AeroMovementItemSchema = z
  .object({
    ent_lan: z.number().int().positive(),
    product_code: z.string().trim().min(1).max(32),
    product_desc: z.string().trim().min(1).max(160),
    quantity: z.number().finite().min(0).max(1_000_000),
    entry_date: dateOnly,
    operator_name: z.string().trim().min(1).max(120),
    movement_type: z.enum(['E', 'S']),
  })
  .strict();

/**
 * A whole day's answer, stamped as one snapshot.
 *
 * The list is capped: a day of the factory is a few dozen seats, and a callback that arrives with more
 * is not a day the plant produced.
 */
export const AeroMovementsBodySchema = z
  .object({
    date: dateOnly,
    movements: z.array(AeroMovementItemSchema).max(1000),
  })
  .strict();

/**
 * One stored seat as the mirror answers it.
 *
 * `synced_at` is when Trindade heard from the ERP, not when the movement happened: the seat belongs to
 * the company and `entry_date` is what the factory did.
 */
export const AeroMovementSchema = AeroMovementItemSchema.extend({
  synced_at: z.string(),
}).strict();

export const AeroMovementsListResponseSchema = z
  .object({
    date: dateOnly,
    movements: z.array(AeroMovementSchema),
  })
  .strict();

export const AeroMovementsSavedResponseSchema = z
  .object({
    saved: z.number().int().min(0),
  })
  .strict();

export type AeroMovementItemInput = z.infer<typeof AeroMovementItemSchema>;
export type AeroMovementsBodyInput = z.infer<typeof AeroMovementsBodySchema>;
