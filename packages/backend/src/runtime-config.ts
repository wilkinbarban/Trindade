const REJECTED_JWT_SECRETS = new Set([
  'change-me',
  'changeme',
  'development-secret',
  'test-secret',
]);

const DEFAULT_REFRESH_TOKEN_TTL_DAYS = 30;

export interface RuntimeConfig {
  jwtSecret: string;
  databasePath: string;
  refreshTokenTtlDays: number;
}

/**
 * Refresh-token lifetime in days. An unset value takes the default; anything that is not a
 * positive whole number is refused, because a zero or negative lifetime would silently
 * mint refresh tokens that are already expired and look like a broken login.
 */
function parseRefreshTokenTtlDays(raw: string | undefined): number {
  if (raw === undefined || raw.trim() === '') return DEFAULT_REFRESH_TOKEN_TTL_DAYS;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error('REFRESH_TOKEN_TTL_DAYS must be a positive whole number of days');
  }
  return parsed;
}

export function loadRuntimeConfig(environment = process.env): RuntimeConfig {
  const jwtSecret = environment.JWT_SECRET;
  if (!jwtSecret?.trim()) {
    throw new Error('JWT_SECRET is required; set a unique secret of at least 32 UTF-8 bytes');
  }
  if (REJECTED_JWT_SECRETS.has(jwtSecret.trim().toLowerCase())) {
    throw new Error('JWT_SECRET uses a known default or test value; generate a unique secret');
  }
  if (Buffer.byteLength(jwtSecret, 'utf8') < 32) {
    throw new Error('JWT_SECRET must contain at least 32 UTF-8 bytes');
  }
  return {
    jwtSecret,
    databasePath: environment.DATABASE_PATH ?? new URL('../data/trindade.db', import.meta.url).pathname,
    refreshTokenTtlDays: parseRefreshTokenTtlDays(environment.REFRESH_TOKEN_TTL_DAYS),
  };
}

export function requireJwtSecret(value = process.env.JWT_SECRET): string {
  return loadRuntimeConfig({ JWT_SECRET: value }).jwtSecret;
}
