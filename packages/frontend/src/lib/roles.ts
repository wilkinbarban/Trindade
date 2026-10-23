/**
 * The role ids the backend assigns, named once.
 *
 * `role_id` is a contract with `admin.routes.ts`: 1 is the administrator and 2 the worker. Comparing
 * against a bare literal scattered across a screen makes a meaning-bearing value look like an
 * arbitrary number, and the next role the plant adds would be a search-and-replace.
 */
export const ROLE_ID = {
  admin: 1,
  worker: 2,
} as const;
