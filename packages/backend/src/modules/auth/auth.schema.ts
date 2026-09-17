import { z } from 'zod';

/**
 * Auth request schemas.
 *
 * These live here rather than inline in the route handlers because the contract
 * generator imports them. An inline schema is invisible outside its route file, which is
 * exactly why auth was the one module with no machine-readable request description while
 * admin, loading and reports already had their own `*.schema.ts`.
 */

export const loginSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
});

export type LoginBody = z.infer<typeof loginSchema>;

export const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, 'Current password is required'),
  newPassword: z.string().min(8, 'New password must be at least 8 characters long'),
});

export type ChangePasswordBody = z.infer<typeof changePasswordSchema>;

export const updateProfileSchema = z
  .object({
    display_name: z.string().min(1, 'Display name is required').optional(),
  })
  .strict();

export type UpdateProfileBody = z.infer<typeof updateProfileSchema>;

export const refreshSessionSchema = z.object({
  refreshToken: z.string().min(1, 'Refresh token is required'),
});

export type RefreshSessionBody = z.infer<typeof refreshSessionSchema>;

/**
 * Logout carries the refresh token of the session being ended, and only that.
 *
 * `refreshToken` is optional on purpose: the web client posts with an Authorization
 * header and no body at all, and that request must keep succeeding. The object is not
 * strict either, so an unexpected extra field cannot turn a logout into an error. A
 * present-but-invalid `refreshToken` does fail, because silently not revoking a session
 * is worse than telling the client its payload was wrong.
 */
export const logoutSchema = z.object({
  refreshToken: z.string().min(1).optional(),
});

export type LogoutBody = z.infer<typeof logoutSchema>;

export const setupSchema = z
  .object({
    username: z.string().trim().min(1),
    displayName: z.string().trim().min(1),
    password: z.string().min(8, 'Password must be at least 8 characters long'),
  })
  .strict();

export type SetupBody = z.infer<typeof setupSchema>;

// ---- Response Schemas ----
//
// Response shapes are schemas rather than TypeScript interfaces so the contract generator
// can read them and so tests can validate what a route actually returns. An interface can
// describe a response the code never produces and nothing would notice, which is the drift
// a machine-readable contract exists to prevent. They are `.strict()`, so a route returning
// an undocumented field fails a test instead of drifting past the document.
//
// `SuccessResponseSchema` lives in `contracts/common.schema.ts` because loading returns it
// too.

export const AuthUserSchema = z
  .object({
    id: z.number().int(),
    username: z.string(),
    role: z.string(),
  })
  .strict();

export const AuthProfileSchema = z
  .object({
    id: z.number().int(),
    username: z.string(),
    display_name: z.string(),
    role: z.string(),
  })
  .strict();

export const LoginResponseSchema = z
  .object({
    token: z.string(),
    refreshToken: z.string(),
    expiresIn: z.number().int().positive(),
    user: AuthUserSchema,
  })
  .strict();

export const RefreshResponseSchema = z
  .object({
    token: z.string(),
    refreshToken: z.string(),
    expiresIn: z.number().int().positive(),
  })
  .strict();

export const ProfileResponseSchema = z.object({ user: AuthProfileSchema }).strict();

export const SetupStatusResponseSchema = z.object({ setupRequired: z.boolean() }).strict();

export const SetupResponseSchema = z
  .object({
    success: z.literal(true),
    userId: z.number().int(),
  })
  .strict();
