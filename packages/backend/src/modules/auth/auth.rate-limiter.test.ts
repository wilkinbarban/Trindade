import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  normalizeDisplayName,
  normalizeUsername,
  RegistrationRateLimiter,
} from './auth.rate-limiter.js';

describe('RegistrationRateLimiter and Normalizers', () => {
  describe('normalization helpers', () => {
    it('normalizeUsername trims whitespace and converts to lowercase', () => {
      assert.strictEqual(normalizeUsername('  JoãoWorker  '), 'joãoworker');
      assert.strictEqual(normalizeUsername('ADMIN_123'), 'admin_123');
      assert.strictEqual(normalizeUsername('\tworker\n'), 'worker');
    });

    it('normalizeDisplayName trims whitespace and collapses internal multiple spaces', () => {
      assert.strictEqual(normalizeDisplayName('  João   da   Silva  '), 'João da Silva');
      assert.strictEqual(normalizeDisplayName('SingleName'), 'SingleName');
      assert.strictEqual(normalizeDisplayName('Maria \t\n Ferreira'), 'Maria Ferreira');
    });
  });

  describe('username attempt limits', () => {
    it('bounds registration attempts for a specific normalized username', () => {
      const limiter = new RegistrationRateLimiter({
        windowMs: 60_000,
        maxGlobalAttempts: 10,
        maxUsernameAttempts: 3,
      });

      const now = 1_000_000;
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker1', now), { allowed: true });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker1', now + 100), { allowed: true });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker1', now + 200), { allowed: true });

      // 4th attempt for the same username is blocked
      const blocked = limiter.checkAndRecordAttempt('worker1', now + 300);
      assert.strictEqual(blocked.allowed, false);
      if (!blocked.allowed) {
        assert.strictEqual(blocked.reason, 'username_rate_limit');
        assert.ok(blocked.retryAfterSeconds > 0);
      }

      // Another username is still allowed under its own limit
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker2', now + 400), { allowed: true });
    });

    it('normalizes usernames so casing and whitespace share the same limit bucket', () => {
      const limiter = new RegistrationRateLimiter({
        windowMs: 60_000,
        maxGlobalAttempts: 10,
        maxUsernameAttempts: 2,
      });

      const now = 1_000_000;
      assert.deepStrictEqual(limiter.checkAndRecordAttempt(normalizeUsername('  WorkerTest  '), now), {
        allowed: true,
      });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt(normalizeUsername('WORKERTEST'), now + 100), {
        allowed: true,
      });

      // 3rd attempt with lowercase is blocked
      const blocked = limiter.checkAndRecordAttempt(normalizeUsername('workertest'), now + 200);
      assert.strictEqual(blocked.allowed, false);
      if (!blocked.allowed) {
        assert.strictEqual(blocked.reason, 'username_rate_limit');
      }
    });

    it('allows new attempts after the time window expires', () => {
      const windowMs = 60_000;
      const limiter = new RegistrationRateLimiter({
        windowMs,
        maxGlobalAttempts: 10,
        maxUsernameAttempts: 2,
      });

      const t0 = 1_000_000;
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker', t0), { allowed: true });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker', t0 + 100), { allowed: true });
      assert.strictEqual(limiter.checkAndRecordAttempt('worker', t0 + 200).allowed, false);

      // Past window: attempts are pruned and allowed again
      const t1 = t0 + windowMs + 1_000;
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('worker', t1), { allowed: true });
    });
  });

  describe('global attempt limits', () => {
    it('bounds total registration attempts across all usernames', () => {
      const limiter = new RegistrationRateLimiter({
        windowMs: 60_000,
        maxGlobalAttempts: 3,
        maxUsernameAttempts: 2,
      });

      const now = 1_000_000;
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('u1', now), { allowed: true });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('u2', now + 100), { allowed: true });
      assert.deepStrictEqual(limiter.checkAndRecordAttempt('u3', now + 200), { allowed: true });

      // 4th attempt globally is blocked, even though u4 has 0 prior attempts
      const blocked = limiter.checkAndRecordAttempt('u4', now + 300);
      assert.strictEqual(blocked.allowed, false);
      if (!blocked.allowed) {
        assert.strictEqual(blocked.reason, 'global_rate_limit');
        assert.ok(blocked.retryAfterSeconds > 0);
      }
    });
  });

  describe('concurrent hash cap', () => {
    it('enforces maximum concurrent hashing operations and releases cleanly', () => {
      const limiter = new RegistrationRateLimiter({ maxConcurrentHashes: 2 });

      assert.strictEqual(limiter.getActiveHashes(), 0);
      assert.strictEqual(limiter.tryAcquireHashSlot(), true);
      assert.strictEqual(limiter.getActiveHashes(), 1);

      assert.strictEqual(limiter.tryAcquireHashSlot(), true);
      assert.strictEqual(limiter.getActiveHashes(), 2);

      // Exceeded max concurrent cap
      assert.strictEqual(limiter.tryAcquireHashSlot(), false);
      assert.strictEqual(limiter.getActiveHashes(), 2);

      // Releasing allows next acquisition
      limiter.releaseHashSlot();
      assert.strictEqual(limiter.getActiveHashes(), 1);
      assert.strictEqual(limiter.tryAcquireHashSlot(), true);
      assert.strictEqual(limiter.getActiveHashes(), 2);

      limiter.releaseHashSlot();
      limiter.releaseHashSlot();
      assert.strictEqual(limiter.getActiveHashes(), 0);

      // Releasing when zero does not underflow
      limiter.releaseHashSlot();
      assert.strictEqual(limiter.getActiveHashes(), 0);
    });
  });

  describe('reset', () => {
    it('clears all recorded attempts and active hash counts', () => {
      const limiter = new RegistrationRateLimiter({
        windowMs: 60_000,
        maxGlobalAttempts: 1,
        maxUsernameAttempts: 1,
        maxConcurrentHashes: 1,
      });

      limiter.checkAndRecordAttempt('u1');
      limiter.tryAcquireHashSlot();
      assert.strictEqual(limiter.checkAndRecordAttempt('u1').allowed, false);
      assert.strictEqual(limiter.tryAcquireHashSlot(), false);

      limiter.reset();
      assert.strictEqual(limiter.getActiveHashes(), 0);
      assert.strictEqual(limiter.checkAndRecordAttempt('u1').allowed, true);
      assert.strictEqual(limiter.tryAcquireHashSlot(), true);
    });
  });
});
