import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { requireRole } from '../auth/auth.middleware.js';
import { AuditQuerySchema } from './audit.schema.js';
import * as audit from './audit.service.js';

/**
 * Audit module — Fastify plugin.
 *
 * Registered at /api/admin in server.ts.
 * All routes require Admin role via `preHandler: [fastify.authenticate, requireRole('Administrador')]`.
 */
export async function auditRoutes(fastify: FastifyInstance) {
  const adminGuard = [fastify.authenticate, requireRole('Administrador')];

  /**
   * GET /api/admin/audit
   *
   * Paginated audit log query. Supports optional filters:
   * - action (e.g. 'login', 'create', 'delete')
   * - entityType (e.g. 'report', 'photo', 'loading')
   * - userId (filter by specific user)
   *
   * Returns logs ordered newest-first with total count and pagination metadata.
   */
  fastify.get(
    '/audit',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = AuditQuerySchema.safeParse(request.query);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid query parameters',
          details: parse.error.flatten(),
        });
      }

      const result = audit.queryLogs(fastify.db, {
        page: parse.data.page,
        limit: parse.data.limit,
        action: parse.data.action,
        entityType: parse.data.entityType,
        userId: parse.data.userId,
      });

      return reply.send(result);
    }
  );
}
