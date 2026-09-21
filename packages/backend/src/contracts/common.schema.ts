import { z } from 'zod';

/**
 * Response shapes shared by more than one module.
 *
 * Every response schema in this codebase is `.strict()`, which is what makes the contract
 * tests meaningful: a plain `z.object` accepts and silently strips extra fields, so a route
 * could return something the document does not describe and nothing would notice. Strict
 * objects make the test prove the server sends *exactly* the documented fields. The cost is
 * deliberate: adding a field to a response now requires regenerating the contract instead of
 * drifting past it.
 */

export const SuccessResponseSchema = z.object({ success: z.literal(true) }).strict();

/** The `{ text }` envelope used by the server-generated WhatsApp exports. */
export const TextResponseSchema = z.object({ text: z.string() }).strict();

/**
 * The liveness answer, which reports whether the process is serving at all. It does not check the
 * database: a client uses it to tell a dead host from a failing request, and nothing more.
 */
export const HealthResponseSchema = z
  .object({ status: z.literal('ok'), timestamp: z.string() })
  .strict();

/**
 * The configured loading time slots, sent under the `timeSlots` envelope.
 *
 * The loading module serves this setting and the admin module serves the same setting, so the shape
 * lives here rather than in either module: two declarations of one response is exactly the drift the
 * strict schemas exist to prevent. Both modules re-export it so their existing importers keep
 * working, but this is the single definition.
 */
export const TimeSlotsResponseSchema = z.object({ timeSlots: z.array(z.string()) }).strict();

/** The active users the history filters offer, sent as a bare id and display name. */
export const UserOptionsResponseSchema = z
  .object({
    users: z.array(z.object({ id: z.number().int(), display_name: z.string() }).strict()),
  })
  .strict();

/** The pagination block returned by the history endpoints. */
export const PaginationSchema = z
  .object({
    page: z.number().int(),
    pageSize: z.number().int(),
    total: z.number().int(),
    totalPages: z.number().int(),
  })
  .strict();
