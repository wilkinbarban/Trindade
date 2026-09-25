import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import bcrypt from 'bcryptjs';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { defaultAuthAdmissionLimiter } from './auth.admission-limiter.js';
import { buildAuthTestApp } from './auth-test-helper.js';
import { REGISTER_NEUTRAL_MESSAGE } from './auth.schema.js';
import { defaultRegistrationRateLimiter, normalizeUsername } from './auth.rate-limiter.js';
import { registerWorkerUser } from './auth.service.js';

// Registration stores the normalized (lowercase) username, while administrator and provisioning
// tooling store the spelling verbatim, and /login must resolve both without ever conflating two
// accounts that differ only by case. These cases pin the JS-side normalized scan that closes the
// account-twin gap and the login resolution order (exact spelling first, normalized fallback
// second).
describe('Registration identity resolution (account twins and mixed-case login)', () => {
  let app: FastifyInstance;
  let db: Database.Database;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db;
  });

  beforeEach(() => defaultAuthAdmissionLimiter.reset());
  beforeEach(() => defaultRegistrationRateLimiter.reset());

  after(async () => {
    await app.close();
    db.close();
  });

  // The rules below close the account-twin gap. Public registration stores the normalized
  // (lowercase) username, while administrator/provisioning tooling stores the spelling verbatim,
  // so `Admin` and `admin` are one identity but two byte strings. SQLite's UNIQUE constraint on
  // users.username is BINARY collation and `lower()` folds only ASCII A-Z, so neither stops the
  // twin, let alone a Unicode one. These cases pin the JS-side normalized scan that does, and
  // prove it stays inside the same immediate transaction as the INSERT.
  describe('case-variant account twin protection', () => {
    function insertOperator(
      username: string,
      roleName: 'Administrador' | 'Trabalhador' = 'Administrador'
    ) {
      const roleId = db.prepare('SELECT id FROM roles WHERE name = ?').pluck().get(roleName) as number;
      db.prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)'
      ).run(username, 'operator-hash-placeholder', `Operator ${username}`, roleId);
    }

    /** Counts accounts whose stored username normalizes like the candidate, the way the service does. */
    function countNormalizedMatches(candidate: string): number {
      const normalized = normalizeUsername(candidate);
      return (db.prepare('SELECT username FROM users').all() as { username: string }[]).filter(
        (row) => normalizeUsername(row.username) === normalized
      ).length;
    }

    it('treats a case variant of a preexisting operator username as duplicate with no insert and no audit', async () => {
      insertOperator('Admin');

      const usersBefore = db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number;
      const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: 'admin', display_name: 'Imposter Worker', password: 'password123' },
      });

      assert.strictEqual(res.statusCode, 200, res.body);
      const body = JSON.parse(res.body);
      assert.strictEqual(body.message, REGISTER_NEUTRAL_MESSAGE);
      assert.strictEqual(body.error, undefined);
      assert.strictEqual(body.token, undefined);
      assert.strictEqual(body.refreshToken, undefined);

      assert.strictEqual(countNormalizedMatches('admin'), 1, 'a normalized twin account was inserted');
      assert.strictEqual(
        db.prepare('SELECT COUNT(*) FROM users').pluck().get(),
        usersBefore,
        'the users table mutated on a case-variant duplicate'
      );
      assert.strictEqual(
        db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get(),
        auditsBefore,
        'a rejected case-variant wrote an audit row'
      );

      const operator = db
        .prepare('SELECT username, display_name FROM users WHERE username = ?')
        .get('Admin') as { username: string; display_name: string };
      assert.strictEqual(operator.username, 'Admin', 'the operator spelling was rewritten');
      assert.strictEqual(operator.display_name, 'Operator Admin');
    });

    it('detects a Unicode case variant that SQLite binary comparison and ASCII lower() cannot match', async () => {
      const stored = 'ÜnïcodeÖperator';
      const candidate = stored.toLowerCase();
      insertOperator(stored, 'Trabalhador');

      // Premise: SQLite's default BINARY collation does not equate the folded spelling, so the
      // UNIQUE constraint alone would let the twin through and only the JS scan can stop it.
      assert.notStrictEqual(candidate, stored);
      assert.strictEqual(
        db.prepare('SELECT COUNT(*) FROM users WHERE username = ?').pluck().get(candidate),
        0,
        'SQLite binary comparison unexpectedly matched the Unicode variant'
      );

      const usersBefore = db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number;
      const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: candidate, display_name: 'Unicode Imposter', password: 'password123' },
      });

      assert.strictEqual(res.statusCode, 200, res.body);
      assert.strictEqual(JSON.parse(res.body).message, REGISTER_NEUTRAL_MESSAGE);
      assert.strictEqual(countNormalizedMatches(stored), 1, 'a Unicode case twin was inserted');
      assert.strictEqual(db.prepare('SELECT COUNT(*) FROM users').pluck().get(), usersBefore);
      assert.strictEqual(db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get(), auditsBefore);
    });

    it('still creates a genuinely new username that shares no normalized twin', async () => {
      const fresh = `freshregion${Date.now()}`;

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: fresh, display_name: 'Fresh Worker', password: 'password123' },
      });

      assert.strictEqual(res.statusCode, 200, res.body);
      assert.strictEqual(countNormalizedMatches(fresh), 1, 'a genuinely new username was not created');

      const created = db
        .prepare('SELECT id, is_active FROM users WHERE username = ?')
        .get(fresh) as { id: number; is_active: number };
      assert.strictEqual(created.is_active, 0, 'self-registration must stay pending approval');
      assert.strictEqual(
        db.prepare('SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = ?').pluck().get(created.id, 'register'),
        1
      );
    });

    it('refuses a case variant issued concurrently with another registration', async () => {
      // better-sqlite3 runs its transactions synchronously on one thread, so a true in-process
      // interleave cannot be produced; issuing both calls concurrently exercises the same
      // serialization the BEGIN IMMEDIATE lock gives across connections, where whichever
      // transaction commits first is seen by the second. Before the normalized scan both
      // spellings inserted and the account twin survived.
      const base = `raceworker${Date.now()}`;
      const upper = base.toUpperCase();
      assert.notStrictEqual(upper, base);

      const auditsBefore = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;

      const results = await Promise.all([
        Promise.resolve().then(() =>
          registerWorkerUser(db, {
            username: upper,
            displayName: 'Race Upper',
            passwordHash: 'race-hash-upper',
          })
        ),
        Promise.resolve().then(() =>
          registerWorkerUser(db, {
            username: base,
            displayName: 'Race Lower',
            passwordHash: 'race-hash-lower',
          })
        ),
      ]);

      assert.deepStrictEqual(
        results.map((result) => result.outcome).sort(),
        ['created', 'duplicate'],
        'two case variants were not serialized into one account'
      );

      const survivors = (db.prepare('SELECT id, username FROM users').all() as {
        id: number;
        username: string;
      }[]).filter((row) => normalizeUsername(row.username) === base);
      assert.strictEqual(survivors.length, 1, 'concurrent case variants produced an account twin');

      const auditsAfter = db.prepare('SELECT COUNT(*) FROM audit_logs').pluck().get() as number;
      assert.strictEqual(auditsAfter - auditsBefore, 1, 'only the created account must be audited');
    });
  });

  // --- Mixed-case username login resolution (registration normalizes, login must still match) ---

  describe('Mixed-case username login resolution', () => {
    beforeEach(() => defaultRegistrationRateLimiter.reset());

    it('lets a self-registered mixed-case username log in after approval with the same typed spelling', async () => {
      // Registration stores the normalized (lowercase) form. Before the fix, /login looked up the
      // typed spelling verbatim, so an approved worker who typed `MixedCaseWorker` was never found.
      const typedUsername = 'MixedCaseWorker';
      const password = 'mixed-case-password-2026';

      const registerRes = await app.inject({
        method: 'POST',
        url: '/api/auth/register',
        payload: { username: typedUsername, display_name: 'Mixed Case Worker', password },
      });
      assert.strictEqual(registerRes.statusCode, 200, registerRes.body);

      const stored = db
        .prepare('SELECT username FROM users WHERE username = ?')
        .pluck()
        .get('mixedcaseworker') as string | undefined;
      assert.strictEqual(stored, 'mixedcaseworker', 'registration must store the normalized form');

      // Still pending admin approval: no session, no token.
      const pending = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: typedUsername, password },
      });
      assert.strictEqual(pending.statusCode, 401);

      db.prepare('UPDATE users SET is_active = 1 WHERE username = ?').run('mixedcaseworker');

      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: typedUsername, password },
      });
      assert.strictEqual(loginRes.statusCode, 200, loginRes.body);
      const body = JSON.parse(loginRes.body);
      assert.ok(body.token, 'token must be present');
      assert.strictEqual(body.user.username, 'mixedcaseworker');
      assert.strictEqual(body.user.role, 'Trabalhador');
    });

    it('preserves exact-match login for a legacy uppercase username', async () => {
      const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
      const legacyUsername = `LEGACY_${Date.now()}`;
      const password = 'legacy-uppercase-password-2026';
      db.prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)'
      ).run(legacyUsername, await bcrypt.hash(password, 4), legacyUsername, roleId);

      // Administrator tooling stored the spelling verbatim; exact-match first reaches it unchanged.
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: legacyUsername, password },
      });
      assert.strictEqual(loginRes.statusCode, 200, loginRes.body);
      assert.strictEqual(JSON.parse(loginRes.body).user.username, legacyUsername);
    });

    it('does not confuse two accounts that differ only by case', async () => {
      const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
      const upper = `CASEUSER_${Date.now()}`;
      const lower = upper.toLowerCase();
      const upperPassword = 'upper-case-password-2026';
      const lowerPassword = 'lower-case-password-2026';
      const insert = db.prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)'
      );
      insert.run(upper, await bcrypt.hash(upperPassword, 4), upper, roleId);
      insert.run(lower, await bcrypt.hash(lowerPassword, 4), lower, roleId);

      // Each exact spelling resolves to its own account.
      const upperLogin = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: upper, password: upperPassword },
      });
      assert.strictEqual(upperLogin.statusCode, 200, upperLogin.body);
      assert.strictEqual(JSON.parse(upperLogin.body).user.username, upper);

      const lowerLogin = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: lower, password: lowerPassword },
      });
      assert.strictEqual(lowerLogin.statusCode, 200, lowerLogin.body);
      assert.strictEqual(JSON.parse(lowerLogin.body).user.username, lower);

      // Crossing the spellings must fail: because an exact match exists, login never falls back to
      // the case twin, so neither password authenticates the other account.
      const upperIntoLower = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: lower, password: upperPassword },
      });
      assert.strictEqual(upperIntoLower.statusCode, 401);

      const lowerIntoUpper = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: upper, password: lowerPassword },
      });
      assert.strictEqual(lowerIntoUpper.statusCode, 401);
    });

    it('falls back to the normalized form only when no exact spelling exists', async () => {
      const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
      const normalized = `fallback_${Date.now()}`;
      const password = 'fallback-password-2026';
      db.prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)'
      ).run(normalized, await bcrypt.hash(password, 4), normalized, roleId);

      // A typed spelling with no exact account still resolves through the normalized form.
      const loginRes = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: normalized.toUpperCase(), password },
      });
      assert.strictEqual(loginRes.statusCode, 200, loginRes.body);
      assert.strictEqual(JSON.parse(loginRes.body).user.username, normalized);
    });
  });
});
