import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import jwt from 'jsonwebtoken';
import { buildAuthTestApp, type TestFixtures } from './auth-test-helper.js';

/**
 * SEC-04 access-gate tests. Each case builds its own in-memory app, so a mutation made by one
 * case can never leak into the next one's verdict. `/__test_admin_only` requires the
 * Administrador role, so a 403 proves the middleware authorized the stored role and a 200
 * proves it authorized the current account state.
 */
async function withApp(run: (ctx: {
  app: Awaited<ReturnType<typeof buildAuthTestApp>>['app'];
  db: Awaited<ReturnType<typeof buildAuthTestApp>>['db'];
  fixtures: TestFixtures;
  access: (claims: Record<string, unknown>) => string;
  call: (token: string) => Promise<{ statusCode: number }>;
}) => Promise<void>): Promise<void> {
  const { app, db, fixtures } = await buildAuthTestApp();
  const access = (claims: Record<string, unknown>) =>
    jwt.sign({ sub: fixtures.admin.id, username: fixtures.admin.username,
      role: 'Administrador', security_version: 1, ...claims }, fixtures.jwtSecret);
  const call = (token: string) =>
    app.inject({ method: 'GET', url: '/__test_admin_only', headers: { authorization: `Bearer ${token}` } });
  try {
    await run({ app, db, fixtures, access, call });
  } finally {
    await app.close();
    db.close();
  }
}

describe('access gate: security version', () => {
  it('rejects a pre-cutover token that carries no version', async () => {
    await withApp(async ({ access, call }) => {
      assert.equal((await call(access({ security_version: undefined }))).statusCode, 401);
    });
  });

  it('rejects malformed and non-positive versions', async () => {
    await withApp(async ({ access, call }) => {
      for (const version of [null, 0, -1, '1', 1.5]) {
        assert.equal((await call(access({ security_version: version }))).statusCode, 401,
          `security_version ${String(version)} was accepted`);
      }
    });
  });

  it('rejects a token whose version is behind the stored one, and accepts it again once they match', async () => {
    await withApp(async ({ db, fixtures, access, call }) => {
      const token = access({ security_version: 1 });
      assert.equal((await call(token)).statusCode, 200);

      db.prepare('UPDATE users SET security_version = 4 WHERE id = ?').run(fixtures.admin.id);
      assert.equal((await call(token)).statusCode, 401);

      assert.equal((await call(access({ security_version: 4 }))).statusCode, 200);
    });
  });

  it('rejects a version the account never had', async () => {
    await withApp(async ({ access, call }) => {
      assert.equal((await call(access({ security_version: 7 }))).statusCode, 401);
    });
  });
});

describe('access gate: stored authority', () => {
  it('applies a demotion immediately even though the token still claims the old role', async () => {
    await withApp(async ({ db, fixtures, access, call }) => {
      const token = access({ role: 'Administrador' });
      assert.equal((await call(token)).statusCode, 200);

      db.prepare('UPDATE users SET role_id = 2 WHERE id = ?').run(fixtures.admin.id);

      assert.equal((await call(token)).statusCode, 403);
    });
  });

  it('rejects a deactivated account and accepts it again after a plain reactivation', async () => {
    await withApp(async ({ db, fixtures, access, call }) => {
      const token = access({ security_version: 1 });
      assert.equal((await call(token)).statusCode, 200);

      db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(fixtures.admin.id);
      assert.equal((await call(token)).statusCode, 401);

      // No version bump: the active flag alone is read from the database on every request.
      db.prepare('UPDATE users SET is_active = 1 WHERE id = ?').run(fixtures.admin.id);
      assert.equal((await call(token)).statusCode, 200);
    });
  });

  it('keeps a pre-cutover token refused after a reactivation bumped the version', async () => {
    await withApp(async ({ db, fixtures, access, call }) => {
      const token = access({ security_version: 1 });
      db.prepare('UPDATE users SET is_active = 0, security_version = security_version + 1 WHERE id = ?')
        .run(fixtures.admin.id);
      assert.equal((await call(token)).statusCode, 401);

      db.prepare('UPDATE users SET is_active = 1 WHERE id = ?').run(fixtures.admin.id);
      assert.equal((await call(token)).statusCode, 401, 'reactivation must not revive the old token');
    });
  });
});
