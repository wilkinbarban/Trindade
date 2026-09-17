import { describe, it } from 'node:test';
import assert from 'node:assert';
import { loadRuntimeConfig, requireJwtSecret } from './runtime-config.js';

const STRONG_SECRET = 'unique-runtime-secret-32-bytes-minimum';

describe('Runtime configuration', () => {
  it('rejects missing and blank JWT secrets', () => {
    assert.throws(() => requireJwtSecret(undefined), /required/);
    assert.throws(() => requireJwtSecret('   '), /required/);
  });

  it('counts UTF-8 bytes and requires at least 32', () => {
    assert.throws(() => requireJwtSecret('a'.repeat(31)), /32 UTF-8 bytes/);
    assert.strictEqual(requireJwtSecret('á'.repeat(16)), 'á'.repeat(16));
  });

  it('rejects known default and test values without disclosing them', () => {
    for (const value of ['change-me', 'development-secret', 'test-secret']) {
      const error = assert.throws(() => requireJwtSecret(value), /known default or test value/);
      assert.doesNotMatch(String(error), new RegExp(value));
    }
  });

  it('returns a strong explicit JWT secret unchanged', () => {
    const secret = 'unique-runtime-secret-32-bytes-minimum';
    assert.strictEqual(requireJwtSecret(secret), secret);
  });

  it('defaults the refresh-token lifetime to 30 days', () => {
    assert.strictEqual(loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET }).refreshTokenTtlDays, 30);
    assert.strictEqual(loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET, REFRESH_TOKEN_TTL_DAYS: '' }).refreshTokenTtlDays, 30);
    assert.strictEqual(loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET, REFRESH_TOKEN_TTL_DAYS: '   ' }).refreshTokenTtlDays, 30);
  });

  it('accepts an explicit whole-day refresh-token lifetime', () => {
    assert.strictEqual(loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET, REFRESH_TOKEN_TTL_DAYS: '7' }).refreshTokenTtlDays, 7);
    assert.strictEqual(loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET, REFRESH_TOKEN_TTL_DAYS: '365' }).refreshTokenTtlDays, 365);
  });

  // A zero, negative or fractional lifetime would mint refresh tokens that are already
  // expired, which presents to a user as a login that silently does not stick.
  it('rejects refresh-token lifetimes that are not positive whole days', () => {
    for (const value of ['0', '-1', '1.5', 'forever', '30d']) {
      assert.throws(
        () => loadRuntimeConfig({ JWT_SECRET: STRONG_SECRET, REFRESH_TOKEN_TTL_DAYS: value }),
        /positive whole number of days/,
        `accepted ${JSON.stringify(value)}`,
      );
    }
  });
});
