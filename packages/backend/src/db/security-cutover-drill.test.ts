import assert from 'node:assert/strict';
import { copyFileSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import jwt from 'jsonwebtoken';
import { createAuthenticate } from '../modules/auth/auth.middleware.js';
import {
  hashRefreshToken,
  issueSession,
  revokeSession,
  rotateSession,
} from '../modules/auth/auth.sessions.service.js';
import { migrateDatabase } from './migrations.js';

const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');

/**
 * The revision 2 shape, produced by OMITTING the `users.security_version` column the
 * revision 3 step adds, rather than dropping it after the fact. A revision 2 database
 * never had that column, so removing it from the shipped schema is the honest fixture:
 * it proves the cutover works against the shape the previous build actually wrote.
 *
 * The only occurrence in `schema.sql` is the users column, so a single line removal is
 * exact. The fixture builder asserts the omission really happened, so a future schema
 * edit cannot silently turn this drill into a no-op.
 */
const revisionTwoSchema = schema.replace(/^[ \t]*security_version[^\n]*\n/m, '');

type Authenticate = ReturnType<typeof createAuthenticate>;
type DrillReply = {
  statusCode: number;
  body: unknown;
  status(code: number): DrillReply;
  send(payload: unknown): DrillReply;
};

/**
 * Drive the real access-token gate without booting a server. The gate only reads
 * `request.headers`, `request.server.db`, and the reply's `status`/`send`, so a minimal
 * fake exercises the same signature and `security_version` checks production uses.
 */
async function callAuthenticate(
  authenticate: Authenticate,
  db: Database.Database,
  token: string,
): Promise<DrillReply> {
  const reply: DrillReply = {
    statusCode: 200,
    body: undefined,
    status(code) {
      reply.statusCode = code;
      return reply;
    },
    send(payload) {
      reply.body = payload;
      return reply;
    },
  };
  const request = {
    headers: { authorization: `Bearer ${token}` },
    server: { db },
  } as unknown as Parameters<Authenticate>[0];
  await authenticate(request, reply as unknown as Parameters<Authenticate>[1]);
  return reply;
}

function sessionRow(
  db: Database.Database,
  refreshToken: string,
): { revoked_at: string | null; replaced_by: string | null } {
  const row = db
    .prepare('SELECT revoked_at, replaced_by FROM auth_sessions WHERE token_hash = ?')
    .get(hashRefreshToken(refreshToken)) as
    | { revoked_at: string | null; replaced_by: string | null }
    | undefined;
  assert.ok(row, 'the fixture refresh session is missing');
  return row;
}

describe('synthetic revision 2 → 3 security cutover drill', () => {
  // Every artifact lives inside this process's own mkdtemp directory. The drill never
  // reads, writes, or restores an existing path, and never runs the production restore
  // path, Docker, or `db-restore`: the rollback rehearsal copies a closed, disposable
  // SQLite file. `recovery.ts` is deliberately not used — its manifest, asset-tree, and
  // allowlist ceremony is aimed at a real recovery set, not at a single temp database.
  const roots: string[] = [];
  const tempRoot = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-cutover-drill-'));
    roots.push(root);
    return root;
  };
  after(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  /**
   * Write the revision 2 database the cutover starts from: the shipped schema minus the
   * revision 3 column, stamped revision 2, with one active administrator and `liveSessions`
   * live refresh sessions. The connection is closed (and WAL flushed) before returning, so
   * the caller may snapshot or migrate the file directly. Returns each raw refresh token so
   * later assertions can address those exact sessions.
   */
  function buildPreCutoverDatabase(path: string, liveSessions = 1): string[] {
    assert.notEqual(
      revisionTwoSchema,
      schema,
      'schema.sql no longer contains the revision 3 users.security_version column this fixture omits',
    );
    const db = new Database(path);
    try {
      db.pragma('foreign_keys = ON');
      db.exec(revisionTwoSchema);
      const columns = (db.prepare('PRAGMA table_info(users)').all() as { name: string }[]).map(
        (column) => column.name,
      );
      assert.ok(!columns.includes('security_version'), 'the revision 2 fixture must not carry the revision 3 column');

      db.exec("INSERT INTO roles (id, name) VALUES (1, 'Administrador')");
      db.prepare(
        "INSERT INTO users (id, username, password_hash, display_name, role_id, is_active) VALUES (1, 'pre-cutover-owner', 'fixture-hash', 'Pre-cutover owner', 1, 1)",
      ).run();
      db.pragma('user_version = 2');
      const refreshTokens = Array.from({ length: liveSessions }, () =>
        issueSession(db, 1, 30, '198.51.100.7'),
      );
      db.pragma('wal_checkpoint(TRUNCATE)');
      return refreshTokens;
    } finally {
      db.close();
    }
  }

  it('runs the native 2 → 3 migration and revokes the live refresh session', () => {
    const dbPath = join(tempRoot(), 'cutover.db');
    const [refreshToken] = buildPreCutoverDatabase(dbPath);

    const db = new Database(dbPath);
    try {
      assert.equal(db.pragma('user_version', { simple: true }), 2);
      assert.equal(sessionRow(db, refreshToken).revoked_at, null, 'the fixture session must start live');

      const result = migrateDatabase(db);
      if (result.outcome !== 'migrated') assert.fail(`expected migrated, got ${result.outcome}`);
      assert.equal(result.from, 2);
      assert.equal(result.to, 3);
      assert.equal(db.pragma('user_version', { simple: true }), 3);

      // The row survives with a revocation stamp, so the refresh gate reports `revoked`
      // (session ended) rather than `unknown` (token never existed).
      assert.ok(sessionRow(db, refreshToken).revoked_at, 'the cutover must revoke the live session');
      assert.deepEqual(rotateSession(db, refreshToken, 30), { outcome: 'revoked' });

      // The added column carries its constrained default for pre-existing users.
      assert.equal(db.prepare('SELECT security_version FROM users WHERE id = 1').pluck().get(), 1);
      assert.throws(() => db.prepare('UPDATE users SET security_version = 0 WHERE id = 1').run(), /CHECK constraint/);
    } finally {
      db.close();
    }
  });

  it('rehearses the documented rollback gate: restored rev2 sessions resurrect, then the bulk revocation ends every one', () => {
    const root = tempRoot();
    const preCutoverPath = join(root, 'pre-cutover.db');
    const refreshTokens = buildPreCutoverDatabase(preCutoverPath, 2);
    assert.ok(refreshTokens.length >= 2, 'the rollback rehearsal needs at least two live sessions');

    // The cutover runs against the live database; the snapshot is taken beforehand so a
    // rollback has an untouched revision 2 copy to restore.
    const livePath = join(root, 'live.db');
    copyFileSync(preCutoverPath, livePath);
    const live = new Database(livePath);
    try {
      assert.equal(migrateDatabase(live).outcome, 'migrated');
      for (const token of refreshTokens) {
        assert.ok(sessionRow(live, token).revoked_at, 'the cutover must revoke every live session');
      }
    } finally {
      live.close();
    }

    // Rollback rehearsal: restore the closed snapshot into a fresh disposable file. This is
    // a revision 2 database with no security_version column: revision 3 middleware is never
    // exercised here, and the assertions below do not depend on it. `rotateSession` on these
    // rows resolves at the revocation check, before it would read the revision 3 column.
    const restoredPath = join(root, 'restored.db');
    copyFileSync(preCutoverPath, restoredPath);
    const restored = new Database(restoredPath);
    try {
      assert.equal(restored.pragma('user_version', { simple: true }), 2, 'the snapshot must predate the cutover');
      const restoredColumns = (restored.prepare('PRAGMA table_info(users)').all() as { name: string }[]).map(
        (column) => column.name,
      );
      assert.ok(!restoredColumns.includes('security_version'), 'the restored snapshot must remain revision 2');

      // Every session the cutover had revoked is live again in the restored copy.
      for (const token of refreshTokens) {
        assert.equal(
          sessionRow(restored, token).revoked_at,
          null,
          'restoring the snapshot must bring the revoked session back to life',
        );
      }

      // docs/deployment.md rollback gate step 3, run verbatim against the restored database:
      // revoke every restored refresh session before bringing traffic back.
      const bulkRevocation = restored
        .prepare("UPDATE auth_sessions SET revoked_at = datetime('now') WHERE revoked_at IS NULL")
        .run();
      assert.equal(bulkRevocation.changes, refreshTokens.length, 'the bulk revocation must end every restored session');

      for (const token of refreshTokens) {
        assert.ok(sessionRow(restored, token).revoked_at, 'the bulk revocation must end every restored session');
        assert.deepEqual(rotateSession(restored, token, 30), { outcome: 'revoked' });
        assert.equal(revokeSession(restored, token), false, 'revoking an already-ended session is a no-op');
      }
    } finally {
      restored.close();
    }
  });

  it('rejects pre-cutover access by secret rotation and keeps the migrated revision 3 version gate', async () => {
    const dbPath = join(tempRoot(), 'cutover-jwt.db');
    buildPreCutoverDatabase(dbPath, 1);

    const db = new Database(dbPath);
    try {
      assert.equal(migrateDatabase(db).outcome, 'migrated');

      // Rotation gate signs with 32+ byte secrets, matching the JWT_SECRET minimum.
      const preCutoverSecret = 'drill-pre-cutover-jwt-secret-0123456789';
      const postCutoverSecret = 'drill-post-cutover-jwt-secret-9876543210';
      assert.ok(preCutoverSecret.length >= 32 && postCutoverSecret.length >= 32);

      const claims = {
        sub: 1,
        username: 'pre-cutover-owner',
        role: 'Administrador',
        security_version: 1,
      };
      const preCutoverAccess = jwt.sign(claims, preCutoverSecret, { expiresIn: 900 });
      const postCutoverAccess = jwt.sign(claims, postCutoverSecret, { expiresIn: 900 });
      const staleVersionAccess = jwt.sign({ ...claims, security_version: 2 }, postCutoverSecret, {
        expiresIn: 900,
      });

      // Rotation alone invalidates pre-cutover access: verification under the new secret
      // fails on the signature, before any middleware, database, or version check runs.
      assert.throws(() => jwt.verify(preCutoverAccess, postCutoverSecret), /invalid signature/i);
      assert.equal((jwt.verify(postCutoverAccess, postCutoverSecret) as jwt.JwtPayload).sub, 1);

      // On the migrated revision 3 database, the middleware additionally enforces the stored
      // security_version, independently of the signature.
      const authenticate = createAuthenticate(postCutoverSecret);
      assert.equal(
        (await callAuthenticate(authenticate, db, preCutoverAccess)).statusCode,
        401,
        'access signed with the pre-cutover secret must not survive the rotation',
      );
      assert.equal(
        (await callAuthenticate(authenticate, db, postCutoverAccess)).statusCode,
        200,
        'access re-issued with the rotated secret must be accepted',
      );
      assert.equal(
        (await callAuthenticate(authenticate, db, staleVersionAccess)).statusCode,
        401,
        'a correctly signed token carrying a stale security_version must still be refused',
      );
    } finally {
      db.close();
    }
  });
});
