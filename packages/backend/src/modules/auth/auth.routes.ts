import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import jwt from 'jsonwebtoken';
import { AuthAdmissionLimiter, defaultAuthAdmissionLimiter } from './auth.admission-limiter.js';
import bcrypt from 'bcryptjs';
import { log as auditLog } from '../audit/audit.service.js';
import {
  changePasswordSchema,
  loginSchema,
  logoutSchema,
  refreshSessionSchema,
  registerSchema,
  REGISTER_NEUTRAL_MESSAGE,
  updateProfileSchema,
} from './auth.schema.js';
import {
  defaultRegistrationRateLimiter,
  normalizeDisplayName,
  normalizeUsername,
  type RegistrationRateLimiter,
} from './auth.rate-limiter.js';
import {
  isBootstrapRequired,
  registerWorkerUser,
} from './auth.service.js';
import {
  issueSession,
  revokeSession,
  revokeUserSessions,
  rotateSession,
} from './auth.sessions.service.js';

/**
 * Access-token lifetime in seconds. Short by design: an access token is stateless and
 * cannot be revoked, so the refresh token carries the session and this short lifetime caps
 * how long a leaked access token stays useful.
 */
const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;

const DEFAULT_REFRESH_TOKEN_TTL_DAYS = 30;

// Public dummy credential, not an account secret; match the production bcrypt work factor.
const DUMMY_PASSWORD_HASH = '$2a$10$.5mQdDAjVaLkw/49P038a.zFFLfibA5Tzv3x2ycHqc4RXjDtjBtv2';

export interface AuthRoutesOptions {
  jwtSecret: string;
  /** Refresh-token lifetime in days. Optional because test helpers register without it. */
  refreshTokenTtlDays?: number;
  /**
   * Resolves whether the database has the session table, called on the paths that need it.
   * Defaults to a positive answer so test helpers that register without it exercise the normal
   * path; `server.ts` passes `createSessionStoreCheck`, which cannot answer "absent" for a table
   * that has since appeared.
   */
  sessionStoreAvailable?: () => boolean;
  /** Rate limiter and admission controller for public worker registration. */
  rateLimiter?: RegistrationRateLimiter;
  /** Shared login/refresh admission controller; override for isolated tests. */
  authAdmissionLimiter?: AuthAdmissionLimiter;
}

interface UserRow {
  id: number;
  username: string;
  password_hash: string;
  display_name: string;
  is_active: number;
  role: string;
  security_version: number;
}

export async function authRoutes(fastify: FastifyInstance, options: AuthRoutesOptions) {
  const refreshTokenTtlDays = options.refreshTokenTtlDays ?? DEFAULT_REFRESH_TOKEN_TTL_DAYS;
  const sessionStoreAvailable = options.sessionStoreAvailable ?? (() => true);
  const rateLimiter = options.rateLimiter ?? defaultRegistrationRateLimiter;
  const admission = options.authAdmissionLimiter ?? defaultAuthAdmissionLimiter;

  function tooMany(reply: FastifyReply, retryAfterSeconds: number) {
    return reply.header('Retry-After', String(retryAfterSeconds)).status(429).send({ error: 'Too many requests' });
  }

  /**
   * Refuse a session request with an operator-facing reason instead of letting the driver error
   * escape: on `/api/auth/refresh`, which is unauthenticated, that error would reach an anonymous
   * caller and name an internal table.
   */
  function refuseWithoutSessionStore(request: FastifyRequest, reply: FastifyReply) {
    request.log.error(
      'auth_sessions is missing: refusing a session request instead of surfacing a driver error',
    );
    return reply.status(503).send({
      error: 'This database has no session store yet. Run db:migrate, then sign in again.',
    });
  }

  fastify.post(
    '/login',
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = loginSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const { username, password } = parse.data;
      const decision = admission.login(username);
      if (!decision.allowed) return tooMany(reply, decision.retryAfterSeconds);
      const db = fastify.db;

      if (!sessionStoreAvailable()) return refuseWithoutSessionStore(request, reply);

      const user = db
        .prepare(
          `SELECT u.id, u.username, u.password_hash, u.display_name,
                  u.is_active, u.security_version, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.username = ?`
        )
        .get(username) as UserRow | undefined;

      if (!admission.acquireCompare()) return tooMany(reply, 1);
      let valid: boolean;
      try {
        valid = await bcrypt.compare(password, user?.password_hash ?? DUMMY_PASSWORD_HASH);
      } finally {
        admission.releaseCompare();
      }

      if (!user || !user.is_active || !valid) {
        return reply.status(401).send({ error: 'Invalid credentials' });
      }

      // bcrypt yields: re-check the credential and authority under the same write lock
      // that creates the session. No old hash or role may survive this boundary.
      const issued = db.transaction(() => {
        const current = db.prepare(`SELECT u.id, u.username, u.password_hash, u.is_active,
          u.security_version, r.name AS role FROM users u
          JOIN roles r ON r.id = u.role_id WHERE u.id = ?`).get(user.id) as UserRow | undefined;
        if (!current || current.is_active !== 1 || current.password_hash !== user.password_hash ||
            !Number.isSafeInteger(current.security_version) || current.security_version <= 0) return null;
        const refreshToken = issueSession(db, current.id, refreshTokenTtlDays, request.ip);
        const token = jwt.sign({ sub: current.id, username: current.username, role: current.role,
          security_version: current.security_version }, options.jwtSecret,
          { expiresIn: ACCESS_TOKEN_TTL_SECONDS });
        return { current, refreshToken, token };
      }).immediate();
      if (!issued) return reply.status(401).send({ error: 'Invalid credentials' });
      const { current, refreshToken, token } = issued;

      try {
        auditLog(fastify.db, {
          userId: user.id,
          action: 'login',
          entityType: 'auth',
          ipAddress: request.ip,
        });
      } catch (err) {
        request.log.warn({ err, userId: user.id }, 'Login succeeded but its audit record could not be persisted');
      }

      return reply.send({
        token,
        refreshToken,
        expiresIn: ACCESS_TOKEN_TTL_SECONDS,
        user: {
          id: user.id,
          username: current.username,
          role: current.role,
        },
      });
    }
  );

  // POST /api/auth/refresh — exchange a refresh token for a new session pair.
  //
  // Deliberately unauthenticated: a client refreshes precisely because its access token
  // has expired, so demanding a valid one would make the endpoint unusable.
  fastify.post(
    '/refresh',
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = refreshSessionSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const decision = admission.refresh(parse.data.refreshToken);
      if (!decision.allowed) return tooMany(reply, decision.retryAfterSeconds);
      if (!sessionStoreAvailable()) return refuseWithoutSessionStore(request, reply);

      const result = rotateSession(fastify.db, parse.data.refreshToken, refreshTokenTtlDays, request.ip);

      if (result.outcome !== 'rotated') {
        if (result.outcome === 'reused-within-grace') {
          // A retry of a rotation whose response was lost, or a double-submitted refresh. The
          // family is left alone on purpose: this device authenticates again, and no other
          // device is signed out by a transient network failure.
          request.log.warn('Refresh token presented shortly after rotation; treated as a retry');
          return reply.status(401).send({ error: 'Invalid or expired session' });
        }
        // Every failure answers identically so a caller cannot probe whether a token
        // existed, had expired, or had been revoked. The reason is logged, not written to
        // the audit table: this path is unauthenticated, and persisting a row per attempt
        // would let one leaked token amplify into unbounded writes.
        request.log.warn({ outcome: result.outcome }, 'Refresh token rejected');

        return reply.status(401).send({ error: 'Invalid or expired session' });
      }

      const token = jwt.sign(
        { sub: result.userId, username: result.username, role: result.role,
          security_version: result.securityVersion },
        options.jwtSecret,
        { expiresIn: ACCESS_TOKEN_TTL_SECONDS }
      );

      try {
        auditLog(fastify.db, {
          userId: result.userId,
          action: 'refresh',
          entityType: 'auth',
          ipAddress: request.ip,
        });
      } catch (err) {
        request.log.warn(
          { err, userId: result.userId },
          'Session refreshed but its audit record could not be persisted'
        );
      }

      return reply.send({
        token,
        refreshToken: result.refreshToken,
        expiresIn: ACCESS_TOKEN_TTL_SECONDS,
      });
    }
  );

  // GET /api/auth/me — returns the current authenticated user
  fastify.get(
    '/me',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const db = fastify.db;
      const { sub } = request.user!;

      const user = db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // GET /api/auth/profile — returns only the current authenticated user's profile
  fastify.get(
    '/profile',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = fastify.db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(request.user!.sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // PATCH /api/auth/profile — updates only the current authenticated user's profile
  fastify.patch(
    '/profile',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = updateProfileSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const updates: string[] = [];
      const params: unknown[] = [];
      if (parse.data.display_name !== undefined) {
        updates.push('display_name = ?');
        params.push(parse.data.display_name);
      }

      if (updates.length > 0) {
        updates.push("updated_at = datetime('now')");
        params.push(request.user!.sub);
        try {
          fastify.db
            .prepare(`UPDATE users SET ${updates.join(', ')} WHERE id = ?`)
            .run(...params);
        } catch (err: any) {
          throw err;
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'update',
          entityType: 'user',
          entityId: request.user!.sub,
          ipAddress: request.ip,
          details: { profile: true },
        });
      }

      const user = fastify.db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(request.user!.sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // POST /api/auth/change-password — change current user's password
  fastify.post(
    '/change-password',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = changePasswordSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      // Guarded for the same reason as login and refresh, and placed before the password UPDATE
      // on purpose: without it the handler would change the credential and only then throw on the
      // missing session table, leaving the password replaced with no session revoked.
      if (!sessionStoreAvailable()) return refuseWithoutSessionStore(request, reply);

      const { currentPassword, newPassword } = parse.data;
      const userId = request.user!.sub;
      const db = fastify.db;

      const user = db
        .prepare('SELECT password_hash, security_version FROM users WHERE id = ?')
        .get(userId) as { password_hash: string; security_version: number } | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      const valid = await bcrypt.compare(currentPassword, user.password_hash);
      if (!valid) {
        return reply.status(400).send({ error: 'Invalid current password' });
      }

      const newHash = bcrypt.hashSync(newPassword, 10);
      const changed = db.transaction(() => {
        const current = db.prepare('SELECT password_hash, security_version, is_active FROM users WHERE id = ?')
          .get(userId) as { password_hash: string; security_version: number; is_active: number } | undefined;
        if (!current || current.is_active !== 1 || current.password_hash !== user.password_hash ||
            current.security_version !== user.security_version || current.security_version !== request.user!.security_version) return false;
        db.prepare("UPDATE users SET password_hash = ?, security_version = security_version + 1, updated_at = datetime('now') WHERE id = ?")
          .run(newHash, userId);
        revokeUserSessions(db, userId);
        return true;
      }).immediate();
      if (!changed) return reply.status(409).send({ error: 'Password or account changed; sign in again' });

      // Audit is best-effort: a failed audit write must not report a successful credential
      // replacement as failed and encourage an unsafe retry with the old session.
      try {
        auditLog(db, { userId, action: 'update', entityType: 'user', entityId: userId, ipAddress: request.ip });
      } catch (err) {
        request.log.warn({ err, userId }, 'Password changed but its audit record could not be persisted');
      }
      return reply.send({ success: true });
    }
  );

  // POST /api/auth/logout — end this session and record the logout event.
  fastify.post(
    '/logout',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      // A body is optional: the web client sends none at all.
      const parse = logoutSchema.safeParse(request.body ?? {});
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      // Ends only the session this client is holding. Other devices stay logged in, which is why
      // this is not `revokeUserSessions`: logging out on a phone must not end the desktop's session.
      if (parse.data.refreshToken) {
        if (sessionStoreAvailable()) {
          revokeSession(fastify.db, parse.data.refreshToken);
        } else {
          // Nothing to revoke without the table, so the caller still gets a success: the state it
          // wants already holds. The skip is logged rather than silent, because a client that
          // believes it signed out should not leave an operator with no trace of the opposite.
          request.log.warn('Logout skipped session revocation: this database has no session store');
        }
      }

      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'logout',
        entityType: 'auth',
        ipAddress: request.ip,
      });

      return reply.send({ success: true });
    }
  );

  // POST /api/auth/register — public worker self-registration pending admin approval
  fastify.post(
    '/register',
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = registerSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const { username, display_name, password } = parse.data;
      const normalizedUsername = normalizeUsername(username);

      // 1. Admission control: bounded global and normalized username rate limits
      // before CPU-intensive work. Never trusts X-Forwarded-For or wildcard trustProxy.
      const rateLimitCheck = rateLimiter.checkAndRecordAttempt(normalizedUsername);
      if (!rateLimitCheck.allowed) {
        reply.header('Retry-After', rateLimitCheck.retryAfterSeconds);
        return reply.status(429).send({
          error: 'Limite de solicitações excedido. Tente novamente mais tarde.',
        });
      }

      // 2. Critical bootstrap invariant: when users table is empty, reject registration
      // without inserting so first-run administrator setup cannot be preempted.
      if (isBootstrapRequired(fastify.db)) {
        return reply.status(409).send({
          error: 'A configuração inicial do administrador é necessária antes de registrar usuários.',
        });
      }

      // 3. Admission control: concurrent hash cap to bound bcrypt CPU load
      if (!rateLimiter.tryAcquireHashSlot()) {
        return reply.status(429).send({
          error: 'Muitas solicitações simultâneas. Tente novamente em instantes.',
        });
      }

      try {
        const passwordHash = await bcrypt.hash(password, 10);
        const result = registerWorkerUser(fastify.db, {
          username: normalizedUsername,
          displayName: normalizeDisplayName(display_name),
          passwordHash,
          ipAddress: request.ip,
          logger: request.log,
        });

        if (result.outcome === 'bootstrap_required') {
          return reply.status(409).send({
            error: 'A configuração inicial do administrador é necessária antes de registrar usuários.',
          });
        }
      } catch (err: any) {
        request.log.error({ err }, 'Unexpected database error during worker registration');
        return reply.status(500).send({
          error: 'Erro interno ao processar a solicitação.',
        });
      } finally {
        rateLimiter.releaseHashSlot();
      }

      // Both newly created accounts and duplicate usernames return the same neutral
      // conditional response without falsely promising creation.
      return reply.status(200).send({
        message: REGISTER_NEUTRAL_MESSAGE,
      });
    }
  );
}
