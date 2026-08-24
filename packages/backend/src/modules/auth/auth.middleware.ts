import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import jwt from 'jsonwebtoken';

// --------------- Type Declarations ---------------

/** JWT payload embedded in access tokens */
export interface JwtPayload {
  sub: number;
  username: string;
  role: string;
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

      const user = request.server.db
        .prepare('SELECT is_active FROM users WHERE id = ?')
        .get(decoded.sub) as { is_active: number } | undefined;
      if (!user || user.is_active !== 1) {
        return reply.status(401).send({ error: 'User is inactive or deleted' });
      }

      request.user = decoded;
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
