import { createHash } from 'node:crypto';
import { normalizeUsername } from './auth.rate-limiter.js';

export interface AuthAdmissionOptions {
  now?: () => number;
  windowMs?: number;
  maxGlobalLogin?: number;
  maxUsernameLogin?: number;
  maxGlobalRefresh?: number;
  maxTokenRefresh?: number;
  maxConcurrentCompares?: number;
  maxKeys?: number;
}

type Decision = { allowed: true } | { allowed: false; retryAfterSeconds: number };

/** In-process admission, deliberately independent of untrusted forwarding headers. */
export class AuthAdmissionLimiter {
  private readonly now: () => number;
  private readonly windowMs: number;
  private readonly limits: Required<Omit<AuthAdmissionOptions, 'now' | 'windowMs'>>;
  private readonly globalLogin: number[] = [];
  private readonly globalRefresh: number[] = [];
  private readonly usernames = new Map<string, number[]>();
  private readonly tokens = new Map<string, number[]>();
  private activeCompares = 0;

  constructor(options: AuthAdmissionOptions = {}) {
    this.now = options.now ?? Date.now;
    this.windowMs = options.windowMs ?? 15 * 60_000;
    this.limits = {
      maxGlobalLogin: options.maxGlobalLogin ?? 120,
      maxUsernameLogin: options.maxUsernameLogin ?? 5,
      maxGlobalRefresh: options.maxGlobalRefresh ?? 300,
      maxTokenRefresh: options.maxTokenRefresh ?? 10,
      maxConcurrentCompares: options.maxConcurrentCompares ?? 4,
      maxKeys: options.maxKeys ?? 10_000,
    };
  }

  private prune(list: number[], now: number) {
    while (list.length && list[0] <= now - this.windowMs) list.shift();
  }

  private check(global: number[], keys: Map<string, number[]>, key: string, globalMax: number, keyMax: number): Decision {
    const now = this.now();
    this.prune(global, now);
    // Expire idle keys and cap memory under floods of arbitrary usernames/tokens.
    for (const [name, timestamps] of keys) {
      this.prune(timestamps, now);
      if (!timestamps.length) keys.delete(name);
    }
    const local = keys.get(key) ?? [];
    const blocked = global.length >= globalMax ? global : local.length >= keyMax ? local : undefined;
    if (blocked) return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil((blocked[0] + this.windowMs - now) / 1000)) };
    if (!keys.has(key) && keys.size >= this.limits.maxKeys) return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil(this.windowMs / 1000)) };
    global.push(now);
    local.push(now);
    keys.set(key, local);
    return { allowed: true };
  }

  login(username: string): Decision {
    return this.check(this.globalLogin, this.usernames, normalizeUsername(username), this.limits.maxGlobalLogin, this.limits.maxUsernameLogin);
  }

  refresh(token: string): Decision {
    const digest = createHash('sha256').update(token).digest('hex');
    return this.check(this.globalRefresh, this.tokens, digest, this.limits.maxGlobalRefresh, this.limits.maxTokenRefresh);
  }

  acquireCompare(): boolean {
    if (this.activeCompares >= this.limits.maxConcurrentCompares) return false;
    this.activeCompares++;
    return true;
  }

  releaseCompare(): void {
    this.activeCompares--;
  }

  /** Clear test state between isolated test cases; never call during a live request. */
  reset(): void {
    this.globalLogin.length = 0;
    this.globalRefresh.length = 0;
    this.usernames.clear();
    this.tokens.clear();
    this.activeCompares = 0;
  }
}

export const defaultAuthAdmissionLimiter = new AuthAdmissionLimiter();
