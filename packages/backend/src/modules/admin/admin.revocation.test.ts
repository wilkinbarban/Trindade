import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import bcrypt from 'bcryptjs';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import { buildTestApp, type TestFixtures } from '../../test-helper.js';

/**
 * SEC-03 / SEC-04 mutation evidence for `PATCH /api/admin/users/:id`: an administrator changing an
 * account's credential or authority must take effect immediately and atomically. Kept in its own
 * file so the catalogue CRUD assertions cannot drift into this evidence.
 *
 * One case replaces `bcrypt.hashSync` process-wide to reproduce the window between hashing and the
 * write lock, so every case builds an isolated app.
 */
interface Ctx {
  app: FastifyInstance;
  db: Database.Database;
  fixtures: TestFixtures;
}

async function withApp(run: (ctx: Ctx) => Promise<void>): Promise<void> {
  const { app, db, fixtures } = await buildTestApp();
  try {
    await run({ app, db, fixtures });
  } finally {
    await app.close();
    db.close();
  }
}

const login = (ctx: Ctx, username: string, password: string) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/login', payload: { username, password } });

const patchUser = (ctx: Ctx, adminToken: string, id: number, payload: Record<string, unknown>) =>
  ctx.app.inject({
    method: 'PATCH', url: `/api/admin/users/${id}`,
    headers: { authorization: `Bearer ${adminToken}` }, payload,
  });

const refresh = (ctx: Ctx, refreshToken: string) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });

const accessGate = (ctx: Ctx, token: string) =>
  ctx.app.inject({ method: 'GET', url: '/__test_admin_only', headers: { authorization: `Bearer ${token}` } });

/** Any authenticated, active principal passes this one; a stale version or inactive account does not. */
const profileGate = (ctx: Ctx, token: string) =>
  ctx.app.inject({ method: 'GET', url: '/api/auth/profile', headers: { authorization: `Bearer ${token}` } });

const liveSessions = (ctx: Ctx, userId: number) =>
  ctx.db.prepare('SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL')
    .pluck().get(userId) as number;

const versionOf = (ctx: Ctx, id: number) =>
  (ctx.db.prepare('SELECT security_version FROM users WHERE id = ?').get(id) as { security_version: number })
    .security_version;

/** Log in as the fixture admin and return the access token the mutations are authorized with. */
async function adminTokenOf(ctx: Ctx): Promise<string> {
  const response = await login(ctx, ctx.fixtures.admin.username, ctx.fixtures.admin.password);
  assert.equal(response.statusCode, 200, response.body);
  return JSON.parse(response.body).token;
}

/** A second administrator, so a real demotion can be exercised without editing the caller's own row. */
async function insertAdmin(ctx: Ctx): Promise<{ id: number; username: string; password: string }> {
  const identity = { username: `admin2-${randomUUID()}`, password: randomUUID() };
  const result = ctx.db
    .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, 1, 1)')
    .run(identity.username, await bcrypt.hash(identity.password, 4), identity.username);
  return { id: Number(result.lastInsertRowid), ...identity };
}

describe('admin user update revocation', () => {
  it('demotion invalidates the target still-fresh access token and revokes its refresh sessions', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const target = await insertAdmin(ctx);
      const signedIn = await login(ctx, target.username, target.password);
      assert.equal(signedIn.statusCode, 200);
      const { token, refreshToken } = JSON.parse(signedIn.body);
      assert.equal((await accessGate(ctx, token)).statusCode, 200);

      // Administrador (1) -> Trabalhador (2)
      const demoted = await patchUser(ctx, admin, target.id, { role_id: 2 });
      assert.equal(demoted.statusCode, 200, demoted.body);
      assert.equal(JSON.parse(demoted.body).user.role_name, 'Trabalhador');

      assert.equal(versionOf(ctx, target.id), 2, 'an authority change must advance the security version');
      assert.equal((await accessGate(ctx, token)).statusCode, 401, 'the pre-demotion token still opened an admin route');
      assert.equal(liveSessions(ctx, target.id), 0, 'a demotion left a live session behind');
      const stale = await refresh(ctx, refreshToken);
      assert.equal(stale.statusCode, 401);
      assert.equal(JSON.parse(stale.body).token, undefined);
    });
  });

  it('administrative password reset revokes every live refresh family and replaces the credential', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const { id, username, password } = ctx.fixtures.worker;

      // Two devices, two families.
      const first = await login(ctx, username, password);
      const second = await login(ctx, username, password);
      assert.equal(first.statusCode, 200);
      assert.equal(second.statusCode, 200);
      assert.equal(liveSessions(ctx, id), 2);

      const reset = await patchUser(ctx, admin, id, { password: 'admin-reset-secret' });
      assert.equal(reset.statusCode, 200, reset.body);

      assert.equal(versionOf(ctx, id), 2);
      assert.equal(liveSessions(ctx, id), 0, 'the reset left a live refresh session behind');
      for (const response of [first, second]) {
        const rejected = await refresh(ctx, JSON.parse(response.body).refreshToken);
        assert.equal(rejected.statusCode, 401);
        assert.equal(JSON.parse(rejected.body).token, undefined);
      }

      // The replacement is the credential the account now authenticates with; the old one is dead.
      assert.equal((await login(ctx, username, password)).statusCode, 401);
      const reissued = await login(ctx, username, 'admin-reset-secret');
      assert.equal(reissued.statusCode, 200, reissued.body);
      assert.equal(liveSessions(ctx, id), 1, 'the fresh login must establish exactly one new session');
      assert.equal(liveSessions(ctx, ctx.fixtures.admin.id), 1, 'the reset must not sign the administrator out');
    });
  });

  it('deactivation and reactivation both invalidate the token minted before them', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const { id, username, password } = ctx.fixtures.worker;

      const signedIn = await login(ctx, username, password);
      const { token, refreshToken } = JSON.parse(signedIn.body);

      const deactivated = await patchUser(ctx, admin, id, { is_active: 0 });
      assert.equal(deactivated.statusCode, 200, deactivated.body);
      assert.equal(versionOf(ctx, id), 2);
      assert.equal((await profileGate(ctx, token)).statusCode, 401);
      assert.equal(liveSessions(ctx, id), 0);
      assert.equal((await login(ctx, username, password)).statusCode, 401, 'an inactive account must not log in');
      assert.equal((await refresh(ctx, refreshToken)).statusCode, 401);

      const reactivated = await patchUser(ctx, admin, id, { is_active: 1 });
      assert.equal(reactivated.statusCode, 200, reactivated.body);
      assert.equal(versionOf(ctx, id), 3, 'reactivation is itself an authority change');
      assert.equal((await profileGate(ctx, token)).statusCode, 401, 'reactivation must not revive the old token');
      assert.equal((await login(ctx, username, password)).statusCode, 200);
    });
  });

  it('display-name-only and repeated-values updates leave sessions and version untouched', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const { id, username, password } = ctx.fixtures.worker;
      const signedIn = await login(ctx, username, password);
      const { token } = JSON.parse(signedIn.body);
      assert.equal(liveSessions(ctx, id), 1);

      for (const payload of [
        { display_name: 'Renamed Worker' },
        { display_name: 'Renamed Worker' },
        { role_id: 2 },
        { username },
        {},
      ]) {
        const response = await patchUser(ctx, admin, id, payload);
        assert.equal(response.statusCode, 200, `${JSON.stringify(payload)} -> ${response.body}`);
      }
      assert.equal(
        (ctx.db.prepare('SELECT display_name FROM users WHERE id = ?').get(id) as { display_name: string }).display_name,
        'Renamed Worker'
      );

      assert.equal(versionOf(ctx, id), 1, 'a non-security edit must not advance the security version');
      assert.equal(liveSessions(ctx, id), 1, 'a non-security edit must not revoke a session');
      assert.equal((await profileGate(ctx, token)).statusCode, 200, 'a cosmetic edit signed the user out');
    });
  });

  it('a failed session revocation rolls the whole update back', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const { id, username, password } = ctx.fixtures.worker;
      const signedIn = await login(ctx, username, password);
      const { token } = JSON.parse(signedIn.body);
      assert.equal(liveSessions(ctx, id), 1);

      // The revocation half of the transaction is forced to fail; the role change and the version
      // bump must not survive without it.
      ctx.db.exec(
        "CREATE TRIGGER fail_session_revocation BEFORE UPDATE ON auth_sessions BEGIN SELECT RAISE(ABORT, 'revocation unavailable'); END"
      );
      try {
        const response = await patchUser(ctx, admin, id, { role_id: 1 });
        assert.equal(response.statusCode, 500, response.body);
      } finally {
        ctx.db.exec('DROP TRIGGER fail_session_revocation');
      }

      const row = ctx.db.prepare('SELECT role_id, security_version FROM users WHERE id = ?').get(id) as
        { role_id: number; security_version: number };
      assert.equal(row.role_id, 2, 'the role change survived a failed revocation');
      assert.equal(row.security_version, 1, 'the version bump survived a failed revocation');
      assert.equal(liveSessions(ctx, id), 1, 'the session was revoked despite the failure');
      assert.equal((await profileGate(ctx, token)).statusCode, 200);
    });
  });

  it('a version advanced by a competing write between hashing and the lock is not lost', async () => {
    await withApp(async (ctx) => {
      const admin = await adminTokenOf(ctx);
      const { id } = ctx.fixtures.worker;

      // better-sqlite3 is synchronous, so this is the only place a competing writer can land: after
      // the credential is hashed, before the immediate transaction takes the write lock. The service
      // must therefore bump the version it re-reads, not a value captured before the lock.
      const realHashSync = bcrypt.hashSync;
      let injected = false;
      bcrypt.hashSync = ((secret: string, rounds: number | string) => {
        const hash = realHashSync(secret, rounds);
        if (!injected) {
          injected = true;
          ctx.db.prepare('UPDATE users SET security_version = 5, display_name = ? WHERE id = ?')
            .run('Competing Write', id);
        }
        return hash;
      }) as typeof bcrypt.hashSync;

      let response;
      try {
        response = await patchUser(ctx, admin, id, { password: 'raced-new-secret' });
      } finally {
        bcrypt.hashSync = realHashSync;
      }

      assert.equal(response.statusCode, 200, response.body);
      assert.equal(versionOf(ctx, id), 6, 'the version bump did not build on the competing write');
      const row = ctx.db.prepare('SELECT display_name FROM users WHERE id = ?').get(id) as { display_name: string };
      assert.equal(row.display_name, 'Competing Write', 'the interleaved write was clobbered');
      assert.equal((await login(ctx, ctx.fixtures.worker.username, 'raced-new-secret')).statusCode, 200);
    });
  });
});
