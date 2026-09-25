import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { AuthAdmissionLimiter } from './auth.admission-limiter.js';

describe('auth admission', () => {
  it('enforces conservative default per-target and route-wide budgets over 15 minutes', () => {
    let now = 0;
    const perUser = new AuthAdmissionLimiter({ now: () => now });
    for (let i = 0; i < 5; i++) assert.equal(perUser.login(' ADMIN ').allowed, true);
    assert.deepEqual(perUser.login('admin'), { allowed: false, retryAfterSeconds: 900 });
    now = 900_000;
    assert.equal(perUser.login('admin').allowed, true);

    const globalLogin = new AuthAdmissionLimiter({ now: () => now });
    for (let i = 0; i < 120; i++) assert.equal(globalLogin.login(`user-${i}`).allowed, true);
    assert.deepEqual(globalLogin.login('another'), { allowed: false, retryAfterSeconds: 900 });

    const perToken = new AuthAdmissionLimiter({ now: () => now });
    for (let i = 0; i < 10; i++) assert.equal(perToken.refresh('token').allowed, true);
    assert.deepEqual(perToken.refresh('token'), { allowed: false, retryAfterSeconds: 900 });
    now += 900_000;
    assert.equal(perToken.refresh('token').allowed, true);

    const globalRefresh = new AuthAdmissionLimiter({ now: () => now });
    for (let i = 0; i < 300; i++) assert.equal(globalRefresh.refresh(`token-${i}`).allowed, true);
    assert.deepEqual(globalRefresh.refresh('another'), { allowed: false, retryAfterSeconds: 900 });
  });

  it('limits normalized usernames, then reopens at the window boundary', () => {
    let now = 0;
    const limiter = new AuthAdmissionLimiter({ now: () => now, windowMs: 2000, maxUsernameLogin: 1 });
    assert.deepEqual(limiter.login(' Alice '), { allowed: true });
    assert.deepEqual(limiter.login('alice'), { allowed: false, retryAfterSeconds: 2 });
    now = 2000;
    assert.deepEqual(limiter.login('ALICE'), { allowed: true });
  });

  it('bounds route-wide attempts independently of IP and token-specific attempts by digest', () => {
    const limiter = new AuthAdmissionLimiter({ maxGlobalLogin: 1, maxGlobalRefresh: 2, maxTokenRefresh: 1 });
    assert.equal(limiter.login('a').allowed, true);
    assert.equal(limiter.login('b').allowed, false);
    assert.equal(limiter.refresh('secret').allowed, true);
    assert.equal(limiter.refresh('secret').allowed, false);
    assert.equal(limiter.refresh('other').allowed, true);
    assert.equal(limiter.refresh('third').allowed, false);
    assert.equal(JSON.stringify(limiter).includes('secret'), false);
  });

  it('caps simultaneous password comparisons and releases capacity', () => {
    const limiter = new AuthAdmissionLimiter({ maxConcurrentCompares: 1 });
    assert.equal(limiter.acquireCompare(), true);
    assert.equal(limiter.acquireCompare(), false);
    limiter.releaseCompare();
    assert.equal(limiter.acquireCompare(), true);
    limiter.releaseCompare();
  });
});
