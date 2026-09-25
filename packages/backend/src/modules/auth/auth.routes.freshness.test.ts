import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';

/**
 * SEC-04 issuance and freshness tests for `/api/auth/login` and `/api/auth/refresh`, kept in
 * their own file so the unrelated public-registration and mutation cases owned by later slices
 * cannot drift into this evidence. Several cases replace `bcrypt.compare` process-wide to
 * reproduce the window in which the password check yields, so each builds an isolated app.
 */
interface Ctx {
  app: Awaited<ReturnType<typeof buildAuthTestApp>>['app'];
  db: Awaited<ReturnType<typeof buildAuthTestApp>>['db'];
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

const login = (ctx: Ctx, identity: { username: string; password: string }) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/login', payload: identity });
const refresh = (ctx: Ctx, refreshToken: string) =>
  ctx.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
const accessGate = (ctx: Ctx, token: string) =>
  ctx.app.inject({ method: 'GET', url: '/__test_admin_only',
    headers: { authorization: `Bearer ${token}` } });
const liveSessions = (ctx: Ctx) =>
  ctx.db.prepare('SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NULL').pluck().get() as number;
const claimsOf = (ctx: Ctx, token: string) =>
  jwt.verify(token, ctx.fixtures.jwtSecret) as jwt.JwtPayload & { security_version: number };

/**
 * Run `mutate` at the one instant a competing write can land between the password check and the
 * session it authorizes: just after the real comparison resolves. Reproducing that window is the
 * only way to prove the post-comparison re-check is real rather than incidental to the request path.
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

describe('login issuance freshness', () => {
  it('issues a versioned access token that the access gate accepts', async () => {
    await withApp(async (ctx) => {
      const response = await login(ctx, ctx.fixtures.admin);
      assert.equal(response.statusCode, 200);
      const body = JSON.parse(response.body);
      const claims = claimsOf(ctx, body.token);
      assert.equal(claims.security_version, 1);
      assert.equal(claims.role, 'Administrador');
      assert.equal(claims.username, ctx.fixtures.admin.username);
      assert.equal(body.user.role, 'Administrador');
      assert.equal((await accessGate(ctx, body.token)).statusCode, 200);
    });
  });

  it('rejects a password hash replaced while the bcrypt comparison was pending', async () => {
    await withApp(async (ctx) => {
      const response = await duringPasswordComparison(ctx, (inner) => {
        inner.db.prepare('UPDATE users SET password_hash = ? WHERE id = ?')
          .run(bcrypt.hashSync('replacement-password', 4), inner.fixtures.admin.id);
      }, () => login(ctx, ctx.fixtures.admin));

      assert.equal(response.statusCode, 401);
      assert.equal(liveSessions(ctx), 0, 'a refused login must not leave a session behind');
    });
  });

  it('signs the authority it re-read after the comparison, not the one it read before', async () => {
    await withApp(async (ctx) => {
      const response = await duringPasswordComparison(ctx, (inner) => {
        inner.db.prepare('UPDATE users SET role_id = 2, security_version = security_version + 1 WHERE id = ?')
          .run(inner.fixtures.admin.id);
      }, () => login(ctx, ctx.fixtures.admin));

      assert.equal(response.statusCode, 200);
      const claims = claimsOf(ctx, JSON.parse(response.body).token);
      assert.equal(claims.role, 'Trabalhador');
      assert.equal(claims.security_version, 2);
      assert.equal(JSON.parse(response.body).user.role, 'Trabalhador');
    });
  });

  it('rejects an account deactivated while the bcrypt comparison was pending', async () => {
    await withApp(async (ctx) => {
      const response = await duringPasswordComparison(ctx, (inner) => {
        inner.db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(inner.fixtures.admin.id);
      }, () => login(ctx, ctx.fixtures.admin));

      assert.equal(response.statusCode, 401);
      assert.equal(liveSessions(ctx), 0);
    });
  });
});

describe('refresh freshness and cutoff', () => {
  it('signs the role and version captured by the rotation and retires the previous token', async () => {
    await withApp(async (ctx) => {
      const signedIn = await login(ctx, ctx.fixtures.admin);
      assert.equal(signedIn.statusCode, 200);
      const old = JSON.parse(signedIn.body);

      ctx.db.prepare('UPDATE users SET role_id = 2, security_version = security_version + 1 WHERE id = ?')
        .run(ctx.fixtures.admin.id);

      const response = await refresh(ctx, old.refreshToken);
      assert.equal(response.statusCode, 200);
      const claims = claimsOf(ctx, JSON.parse(response.body).token);
      assert.equal(claims.username, ctx.fixtures.admin.username);
      assert.equal(claims.role, 'Trabalhador');
      assert.equal(claims.security_version, 2);

      // The access token minted before the change opens nothing, which is the window SEC-04 closes.
      assert.equal((await accessGate(ctx, old.token)).statusCode, 401);
    });
  });

  it('refuses a refresh session that the rev3 cutover revoked', async () => {
    await withApp(async (ctx) => {
      const signedIn = await login(ctx, ctx.fixtures.admin);
      const preCutover = JSON.parse(signedIn.body);

      // The cutover revokes live sessions instead of deleting them, so the row reaches this path.
      ctx.db.exec("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE revoked_at IS NULL");

      const response = await refresh(ctx, preCutover.refreshToken);
      assert.equal(response.statusCode, 401);
      assert.equal(JSON.parse(response.body).token, undefined);
      assert.equal(liveSessions(ctx), 0, 'a refused refresh must not mint a replacement session');
    });
  });

  it('revokes the family when the account was deactivated after login', async () => {
    await withApp(async (ctx) => {
      const signedIn = await login(ctx, ctx.fixtures.admin);
      ctx.db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(ctx.fixtures.admin.id);

      const response = await refresh(ctx, JSON.parse(signedIn.body).refreshToken);
      assert.equal(response.statusCode, 401);
      assert.equal(liveSessions(ctx), 0, 'a deactivated account left a live session family');
    });
  });
});
