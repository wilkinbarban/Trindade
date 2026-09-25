import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import bcrypt from 'bcryptjs';
import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

/**
 * SEC-03 / SEC-04 mutation evidence for `POST /api/auth/change-password`: the credential change,
 * the version bump, and the revocation of every session must be one atomic decision taken against
 * the state re-read after the password comparison yields. Several cases replace `bcrypt.compare`
 * process-wide to reproduce that yield, so each builds an isolated app.
 */
interface Ctx {
  app: FastifyInstance;
  db: Database.Database;
  fixtures: TestFixtures;
}

async function withApp(run: (ctx: Ctx) => Promise<void>): Promise<void> {
  const { app, db, fixtures } = await buildAuthTestApp();
  try {
    await run({ app, db, fixtures });
  } finally {
    await app.close();
    db.close();
  }
}

const login = (ctx: Ctx, username: string, password: string) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/login', payload: { username, password } });

const changePassword = (ctx: Ctx, token: string, currentPassword: string, newPassword: string) =>
  ctx.app.inject({
    method: 'POST', url: '/api/auth/change-password',
    headers: { authorization: `Bearer ${token}` }, payload: { currentPassword, newPassword },
  });

const refresh = (ctx: Ctx, refreshToken: string) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });

const accessGate = (ctx: Ctx, token: string) =>
  ctx.app.inject({ method: 'GET', url: '/__test_admin_only', headers: { authorization: `Bearer ${token}` } });

const liveSessions = (ctx: Ctx, userId: number) =>
  ctx.db.prepare('SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL')
    .pluck().get(userId) as number;

const storedCredential = (ctx: Ctx, userId: number) =>
  ctx.db.prepare('SELECT password_hash, security_version FROM users WHERE id = ?').get(userId) as
    { password_hash: string; security_version: number };

/**
 * Run `mutate` at the one instant a competing write can land between the password check and the
 * transaction that acts on it: just after the real comparison resolves. Reproducing that window is
 * the only way to prove the post-comparison re-check is real rather than incidental.
 */
async function duringPasswordComparison<T>(
  ctx: Ctx, mutate: (context: Ctx) => void, action: () => Promise<T>,
): Promise<T> {
  const compare = bcrypt.compare;
  try {
    bcrypt.compare = (async (password: string, hash: string) => {
      const matched = await compare(password, hash);
      mutate(ctx);
      return matched;
    }) as typeof bcrypt.compare;
    return await action();
  } finally {
    bcrypt.compare = compare;
  }
}

async function signedInAdmin(ctx: Ctx): Promise<{ token: string; refreshToken: string }> {
  const response = await login(ctx, ctx.fixtures.admin.username, ctx.fixtures.admin.password);
  assert.equal(response.statusCode, 200, response.body);
  return JSON.parse(response.body);
}

describe('self change-password atomicity', () => {
  it('replaces the credential and ends every session in one commit', async () => {
    await withApp(async (ctx) => {
      const { id } = ctx.fixtures.admin;
      const first = await signedInAdmin(ctx);
      const second = await signedInAdmin(ctx);
      assert.equal(liveSessions(ctx, id), 2);

      const response = await changePassword(ctx, first.token, ctx.fixtures.admin.password, 'changed-password-1');
      assert.equal(response.statusCode, 200, response.body);
      assert.deepEqual(JSON.parse(response.body), { success: true });

      assert.equal(storedCredential(ctx, id).security_version, 2);
      assert.equal(liveSessions(ctx, id), 0, 'the change left a live session behind');
      assert.equal((await accessGate(ctx, first.token)).statusCode, 401, 'the pre-change access token survived');
      assert.equal((await refresh(ctx, first.refreshToken)).statusCode, 401);
      assert.equal((await refresh(ctx, second.refreshToken)).statusCode, 401);
      assert.equal((await login(ctx, ctx.fixtures.admin.username, ctx.fixtures.admin.password)).statusCode, 401);
      assert.equal((await login(ctx, ctx.fixtures.admin.username, 'changed-password-1')).statusCode, 200);
    });
  });

  it('refuses a wrong current password with the unchanged error contract', async () => {
    await withApp(async (ctx) => {
      const { token } = await signedInAdmin(ctx);
      const before = storedCredential(ctx, ctx.fixtures.admin.id);

      const response = await changePassword(ctx, token, 'not-the-password', 'changed-password-1');
      assert.equal(response.statusCode, 400);
      assert.equal(JSON.parse(response.body).error, 'Invalid current password');
      assert.deepEqual(storedCredential(ctx, ctx.fixtures.admin.id), before);
    });
  });

  it('rejects a change whose password was replaced while the comparison was pending', async () => {
    await withApp(async (ctx) => {
      const { id, username, password } = ctx.fixtures.admin;
      const { token } = await signedInAdmin(ctx);
      const competingHash = bcrypt.hashSync('competing-secret', 4);

      const response = await duringPasswordComparison(ctx, (inner) => {
        inner.db.prepare('UPDATE users SET password_hash = ?, security_version = security_version + 1 WHERE id = ?')
          .run(competingHash, id);
      }, () => changePassword(ctx, token, password, 'changed-password-1'));

      assert.equal(response.statusCode, 409, response.body);
      assert.equal(JSON.parse(response.body).error, 'Password or account changed; sign in again');
      // The loser must not overwrite the winner's credential, and the winner's version stands.
      assert.equal(storedCredential(ctx, id).password_hash, competingHash);
      assert.equal(storedCredential(ctx, id).security_version, 2);
      assert.equal(bcrypt.compareSync('changed-password-1', storedCredential(ctx, id).password_hash), false);
      assert.equal((await login(ctx, username, 'competing-secret')).statusCode, 200);
    });
  });

  it('rejects a change when the account was deactivated while the comparison was pending', async () => {
    await withApp(async (ctx) => {
      const { id, password } = ctx.fixtures.admin;
      const { token } = await signedInAdmin(ctx);
      const before = storedCredential(ctx, id);

      const response = await duringPasswordComparison(ctx, (inner) => {
        inner.db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(id);
      }, () => changePassword(ctx, token, password, 'changed-password-1'));

      assert.equal(response.statusCode, 409, response.body);
      assert.deepEqual(storedCredential(ctx, id), before, 'a deactivated account had its password replaced');
    });
  });

  it('a failed session revocation rolls the credential change back', async () => {
    await withApp(async (ctx) => {
      const { id, username, password } = ctx.fixtures.admin;
      const { token } = await signedInAdmin(ctx);
      const before = storedCredential(ctx, id);

      ctx.db.exec(
        "CREATE TRIGGER fail_session_revocation BEFORE UPDATE ON auth_sessions BEGIN SELECT RAISE(ABORT, 'revocation unavailable'); END"
      );
      try {
        const response = await changePassword(ctx, token, password, 'changed-password-1');
        assert.equal(response.statusCode, 500, response.body);
      } finally {
        ctx.db.exec('DROP TRIGGER fail_session_revocation');
      }

      assert.deepEqual(storedCredential(ctx, id), before, 'the password change survived a failed revocation');
      assert.equal(liveSessions(ctx, id), 1, 'the session was revoked despite the failure');
      assert.equal((await accessGate(ctx, token)).statusCode, 200);
      assert.equal((await login(ctx, username, password)).statusCode, 200);
    });
  });
});
