import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import jwt from 'jsonwebtoken';
import { GUEST_MUTATION_MESSAGE, GUEST_USERNAME } from '../../guest-user.js';

/** El invitado es de solo lectura y este es el mensaje que la directiva fija para él. */


/** Los métodos que no cambian nada: el invitado puede usarlos con la visibilidad del Administrador. */
const READ_ONLY_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

// --------------- Type Declarations ---------------

/**
 * JWT payload embedded in access tokens. `security_version` is the persisted
 * `users.security_version` the token was minted under; role and username are convenience
 * copies that the middleware always overwrites from the database.
 */
export interface JwtPayload {
  sub: number;
  username: string;
  role: string;
  security_version: number;
}

/** Fastify instance augmentation — adds db and authenticate */
declare module 'fastify' {
  interface FastifyInstance {
    db: import('better-sqlite3').Database;
    authenticate: (
      request: FastifyRequest,
      reply: FastifyReply
    ) => Promise<void>;
  }

  interface FastifyRequest {
    user?: JwtPayload;
  }
}

// --------------- Plugin ---------------

/**
 * Creates the authenticate preHandler function.
 *
 * Call `app.decorate('authenticate', createAuthenticate())` at the top level
 * (NOT inside a plugin) so the decoration is available globally.
 *
 * Usage in routes:
 *   fastify.get('/protected', { preHandler: [fastify.authenticate] }, handler)
 */
export function createAuthenticate(jwtSecret: string) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    const authHeader = request.headers.authorization;

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return reply
        .status(401)
        .send({ error: 'Missing or invalid authorization header' });
    }

    const token = authHeader.slice(7);

    try {
      const decoded = jwt.verify(token, jwtSecret) as unknown as JwtPayload;

      // A token minted before the rev3 cutover carries no version, and a tampered one can carry
      // anything. Either is refused before a query runs.
      if (!Number.isSafeInteger(decoded.sub) || decoded.sub <= 0 ||
          !Number.isSafeInteger(decoded.security_version) || decoded.security_version <= 0) {
        return reply.status(401).send({ error: 'Invalid or expired token' });
      }

      // Authority comes from the database, never from the token body, so a stale role in the
      // token is harmless and a version that no longer matches means the credential changed.
      const user = request.server.db
        .prepare(`SELECT u.is_active, u.security_version, u.username, r.name AS role
                  FROM users u JOIN roles r ON r.id = u.role_id WHERE u.id = ?`)
        .get(decoded.sub) as { is_active: number; security_version: number; username: string; role: string } | undefined;
      if (!user || user.is_active !== 1 || user.security_version !== decoded.security_version) {
        return reply.status(401).send({ error: 'Invalid or expired token' });
      }

      request.user = { sub: decoded.sub, username: user.username, role: user.role,
        security_version: user.security_version };

      // La guarda del invitado: es una lista de permitos —solo las rutas de autenticación— para que
      // un endpoint nuevo quede protegido por omisión en vez de por acordarse de decorarlo. Vive acá
      // porque este es el punto por el que pasa toda ruta protegida, y del lado del servidor: el
      // cliente no es la frontera.
      if (
        user.username === GUEST_USERNAME &&
        !READ_ONLY_METHODS.has(request.method) &&
        !request.url.startsWith('/api/auth/')
      ) {
        return reply.code(403).send({ message: GUEST_MUTATION_MESSAGE });
      }
    } catch {
      return reply.status(401).send({ error: 'Invalid or expired token' });
    }
  };
}

// --------------- Role Guard ---------------

/**
 * Returns a `preHandler` that requires the authenticated user to have one
 * of the specified roles.
 *
 * Usage:
 *   fastify.get('/admin', {
 *     preHandler: [fastify.authenticate, requireRole('Administrador')]
 *   }, handler)
 */
export function requireRole(...roles: string[]) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    if (!request.user) {
      return reply.status(401).send({ error: 'Authentication required' });
    }

    if (!roles.includes(request.user.role)) {
      return reply
        .status(403)
        .send({ error: 'Insufficient permissions' });
    }
  };
}

// --------------- Username Guard ---------------

/**
 * Returns a `preHandler` that requires the authenticated user to have one
 * of the specified usernames.
 *
 * Usage:
 *   fastify.post('/wilkin-only', {
 *     preHandler: [fastify.authenticate, requireUsername('wilkin')]
 *   }, handler)
 */
export function requireUsername(...usernames: string[]) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    if (!request.user) {
      return reply.status(401).send({ error: 'Authentication required' });
    }

    if (!usernames.includes(request.user.username)) {
      return reply
        .status(403)
        .send({ error: 'Insufficient permissions' });
    }
  };
}
