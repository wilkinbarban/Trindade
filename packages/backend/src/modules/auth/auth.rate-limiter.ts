export interface RateLimiterOptions {
  windowMs?: number;
  maxGlobalAttempts?: number;
  maxUsernameAttempts?: number;
  maxConcurrentHashes?: number;
}

export type AdmissionCheckResult =
  | { allowed: true }
  | {
      allowed: false;
      reason: 'global_rate_limit' | 'username_rate_limit';
      retryAfterSeconds: number;
    };

/**
 * Normalizes a username for storage, comparison, and rate-limiting.
 * Trims leading/trailing whitespace and converts to lowercase.
 */
export function normalizeUsername(username: string): string {
  return username.trim().toLowerCase();
}

/**
 * Normalizes a display name by trimming leading/trailing whitespace
 * and collapsing internal multiple whitespace characters into single spaces.
 */
export function normalizeDisplayName(displayName: string): string {
  return displayName.trim().replace(/\s+/g, ' ');
}

/**
 * Admission controller and rate limiter for public worker registration.
 *
 * Enforces:
 * 1. Global rate limit across the entire registration route.
 * 2. Per-target rate limit on normalized usernames.
 * 3. Concurrent hash cap to prevent CPU-intensive bcrypt bursts.
 *
 * Security invariant: Never trusts X-Forwarded-For or wildcard trustProxy.
 * Rate limiting keys are global and normalized-username only; spoofed
 * headers cannot bypass either bound.
 */
export class RegistrationRateLimiter {
  readonly windowMs: number;
  readonly maxGlobalAttempts: number;
  readonly maxUsernameAttempts: number;
  readonly maxConcurrentHashes: number;

  private globalAttempts: number[] = [];
  private usernameAttempts: Map<string, number[]> = new Map();
  private activeHashes = 0;

  constructor(options: RateLimiterOptions = {}) {
    this.windowMs = options.windowMs ?? 15 * 60 * 1000; // 15 minutes default
    this.maxGlobalAttempts = options.maxGlobalAttempts ?? 60; // 60 attempts / 15 min default
    this.maxUsernameAttempts = options.maxUsernameAttempts ?? 5; // 5 attempts / username / 15 min default
    this.maxConcurrentHashes = options.maxConcurrentHashes ?? 2; // max 2 concurrent hashes default
  }

  private pruneTimestamps(timestamps: number[], now: number): number[] {
    const threshold = now - this.windowMs;
    return timestamps.filter((t) => t > threshold);
  }

  /**
   * Check rate limits and record the attempt if allowed.
   * Both global and username-specific bounds are evaluated before hashing.
   */
  checkAndRecordAttempt(normalizedUsername: string, now: number = Date.now()): AdmissionCheckResult {
    this.globalAttempts = this.pruneTimestamps(this.globalAttempts, now);
    if (this.globalAttempts.length >= this.maxGlobalAttempts) {
      const oldest = this.globalAttempts[0];
      const retryAfterSeconds = Math.max(1, Math.ceil((oldest + this.windowMs - now) / 1000));
      return { allowed: false, reason: 'global_rate_limit', retryAfterSeconds };
    }

    const userTimestamps = this.pruneTimestamps(
      this.usernameAttempts.get(normalizedUsername) ?? [],
      now,
    );
    if (userTimestamps.length >= this.maxUsernameAttempts) {
      const oldest = userTimestamps[0];
      const retryAfterSeconds = Math.max(1, Math.ceil((oldest + this.windowMs - now) / 1000));
      return { allowed: false, reason: 'username_rate_limit', retryAfterSeconds };
    }

    this.globalAttempts.push(now);
    userTimestamps.push(now);
    this.usernameAttempts.set(normalizedUsername, userTimestamps);

    return { allowed: true };
  }

  /**
   * Attempt to acquire a concurrency slot for CPU-intensive password hashing.
   */
  tryAcquireHashSlot(): boolean {
    if (this.activeHashes >= this.maxConcurrentHashes) {
      return false;
    }
    this.activeHashes++;
    return true;
  }

  /**
   * Release an acquired concurrency slot.
   */
  releaseHashSlot(): void {
    if (this.activeHashes > 0) {
      this.activeHashes--;
    }
  }

  getActiveHashes(): number {
    return this.activeHashes;
  }

  reset(): void {
    this.globalAttempts = [];
    this.usernameAttempts.clear();
    this.activeHashes = 0;
  }
}

export const defaultRegistrationRateLimiter = new RegistrationRateLimiter();
