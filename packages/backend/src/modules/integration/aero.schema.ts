import { z } from 'zod';

/**
 * Codes the integration endpoint answers with, so a client can branch on the reason rather than
 * on the sentence.
 */
export const AERO_ERROR_CODES = {
  INVALID_AERO_LOG: 'INVALID_AERO_LOG',
} as const;

export type AeroErrorCode = (typeof AERO_ERROR_CODES)[keyof typeof AERO_ERROR_CODES];

/**
 * Valid event types for the company Aero WebService integration audit trail.
 */
export const AeroLogEventSchema = z.enum(['test_connection', 'launch_sync', 'read_history']);

/**
 * Operation phase: requested before sending, result upon server response or network outcome.
 */
export const AeroLogPhaseSchema = z.enum(['requested', 'result']);

/**
 * Request body for appending an audit log event.
 * Marked strict to reject smuggled fields such as inline credentials or authorization headers.
 */
export const AeroLogBodySchema = z
  .object({
    event: AeroLogEventSchema,
    phase: AeroLogPhaseSchema.optional(),
    destinationUrl: z.string().min(1).max(2048),
    httpStatus: z.number().int().min(100).max(599).nullable().optional(),
    payload: z.unknown().optional(),
    response: z.string().max(4096).nullable().optional(),
    error: z.string().max(1024).nullable().optional(),
    launchId: z.number().int().positive().nullable().optional(),
    note: z.string().max(256).nullable().optional(),
  })
  .strict();

/**
 * Success response envelope for the audit log endpoint.
 */
export const AeroLogResponseSchema = z
  .object({
    ok: z.literal(true),
  })
  .strict();

/**
 * Error response envelope carrying an explanatory message and machine-readable code.
 */
export const AeroErrorResponseSchema = z
  .object({
    error: z.string(),
    code: z.string(),
    details: z.unknown().optional(),
  })
  .strict();

export type AeroLogEvent = z.infer<typeof AeroLogEventSchema>;
export type AeroLogPhase = z.infer<typeof AeroLogPhaseSchema>;
export type AeroLogBody = z.infer<typeof AeroLogBodySchema>;
export type AeroLogResponse = z.infer<typeof AeroLogResponseSchema>;
