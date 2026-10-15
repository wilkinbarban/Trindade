import assert from 'node:assert/strict';
import { after, describe, it } from 'node:test';
import jwt from 'jsonwebtoken';
import { buildAuthTestApp } from './auth-test-helper.js';

const { app, db, fixtures } = await buildAuthTestApp();
after(async () => { await app.close(); db.close(); });
const access = (claims: Record<string, unknown>) => jwt.sign({ sub: fixtures.admin.id, username: fixtures.admin.username, role: 'Administrador', ...claims }, fixtures.jwtSecret);
const call = (token: string) => app.inject({ method: 'GET', url: '/__test_admin_only', headers: { authorization: `Bearer ${token}` } });

describe('security version access gate', () => {
  it('rejects pre-cutover tokens and malformed or mismatched versions', async () => {
    for (const claims of [{}, { security_version: 0 }, { security_version: -1 }, { security_version: '1' }, { security_version: 2 }]) {
      assert.equal((await call(access(claims))).statusCode, 401);
    }
  });

  it('uses current role, and rejects deactivation even after reactivation', async () => {
    const token = access({ security_version: 1 });
    assert.equal((await call(token)).statusCode, 200);
    db.prepare('UPDATE users SET role_id = 2 WHERE id = ?').run(fixtures.admin.id);
    assert.equal((await call(token)).statusCode, 403);
    db.prepare('UPDATE users SET is_active = 0, security_version = security_version + 1 WHERE id = ?').run(fixtures.admin.id);
    assert.equal((await call(token)).statusCode, 401);
    db.prepare('UPDATE users SET is_active = 1, role_id = 1 WHERE id = ?').run(fixtures.admin.id);
    assert.equal((await call(token)).statusCode, 401);
  });
});
