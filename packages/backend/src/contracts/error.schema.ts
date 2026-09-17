import { z } from 'zod';

/**
 * The error envelope every route in this API answers with.
 *
 * `details` is deliberately left unconstrained. It carries Zod's `flatten()` output for
 * input validation failures, and pinning its shape in the contract would mean the document
 * has to change every time a route reports a field error differently. A client should treat
 * it as diagnostic text for a human, never as something to parse against.
 *
 * `message` is present only on routes that echo a localised business message alongside the
 * error code, which the loading module does for duplicate schedules.
 */
export const ErrorEnvelopeSchema = z
  .object({
    error: z.string(),
    details: z.unknown().optional(),
    message: z.string().optional(),
  })
  .strict();
