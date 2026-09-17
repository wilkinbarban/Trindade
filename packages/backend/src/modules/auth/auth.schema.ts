import { z } from 'zod';

/**
 * Auth request schemas.
 *
 * These live here rather than inline in the route handlers so that anything outside a route
 * file can import them. An inline schema is invisible to every other module, which is why
 * auth and audit were the only modules whose request shapes could not be described from the
 * outside.
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
 * `refreshToken` is optional on purpose: the web client posts with an Authorization header and
 * no body at all, and that request must keep succeeding. The object is not strict either, so an
 * unexpected extra field cannot turn a logout into an error. A present-but-invalid
 * `refreshToken` does fail, because silently not revoking a session is worse than telling the
 * client its payload was wrong.
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
