const REJECTED_JWT_SECRETS = new Set([
  'change-me',
  'changeme',
  'development-secret',
  'test-secret',
]);

export interface RuntimeConfig {
  jwtSecret: string;
  databasePath: string;
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
  return { jwtSecret, databasePath: environment.DATABASE_PATH ?? new URL('../data/trindade.db', import.meta.url).pathname };
}

export function requireJwtSecret(value = process.env.JWT_SECRET): string {
  return loadRuntimeConfig({ JWT_SECRET: value }).jwtSecret;
}
