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

/** The pagination block returned by the history endpoints. */
export const PaginationSchema = z
  .object({
    page: z.number().int(),
    pageSize: z.number().int(),
    total: z.number().int(),
    totalPages: z.number().int(),
  })
  .strict();
