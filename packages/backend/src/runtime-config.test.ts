import { describe, it } from 'node:test';
import assert from 'node:assert';
import { requireJwtSecret } from './runtime-config.js';

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
});
