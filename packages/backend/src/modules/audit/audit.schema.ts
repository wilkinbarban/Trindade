import { z } from 'zod';

/**
 * Audit request schemas.
 *
 * `AuditQuerySchema` lived inline in `audit.routes.ts`, where nothing outside that file could see
 * it. It lives here for the same reason the other modules' schemas do: an inline schema cannot be
 * read by the contract generator or reused by a validation test.
 */
export const AuditQuerySchema = z.object({
  page: z.coerce.number().int().positive().optional().default(1),
  limit: z.coerce.number().int().min(1).max(100).optional().default(20),
  action: z.string().optional(),
  entityType: z.string().optional(),
  userId: z.coerce.number().int().positive().optional(),
});

export type AuditQuery = z.infer<typeof AuditQuerySchema>;

// ---- Response Schemas ----
//
// Schemas rather than interfaces, for the same reason as every other module. The `AuditLogRow`
// interface this mirrors typed `user_id` as a non-nullable number, while the column is nullable and
// `deleteUser` nulls it before removing a user, so the LEFT JOIN can serve a log whose author is
// gone. A schema copied from the interface would have documented a value the query can contradict.
// They are `.strict()`, so an undocumented field fails a contract test instead of drifting past the
// document.

export const AuditLogSchema = z
  .object({
    id: z.number().int(),
    // nullable, not `number`: the column is nullable and `deleteUser` nulls it before deleting the
    // author, so a log can outlive the user it names.
    user_id: z.number().int().nullable(),
    display_name: z.string().nullable(),
    action: z.string(),
    entity_type: z.string(),
    entity_id: z.number().int().nullable(),
    ip_address: z.string().nullable(),
    // The stored JSON text, not a parsed object: the query selects `a.details` exactly as written.
    details: z.string().nullable(),
    created_at: z.string(),
  })
  .strict();

/**
 * The paginated envelope `queryLogs` returns.
 *
 * Its pagination block is spelled out rather than reusing `PaginationSchema` from
 * `contracts/common.schema.ts`: that one names its size field `pageSize`, and this response calls
 * the same concept `limit`. Reusing it would document a field the handler never sends.
 */
export const AuditResponseSchema = z
  .object({
    logs: z.array(AuditLogSchema),
    total: z.number().int(),
    page: z.number().int(),
    limit: z.number().int(),
    totalPages: z.number().int(),
  })
  .strict();

export type AuditLog = z.infer<typeof AuditLogSchema>;
export type AuditLogPage = z.infer<typeof AuditResponseSchema>;
