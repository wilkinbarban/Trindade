import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { requireRole, requireUsername } from '../auth/auth.middleware.js';
import { apiError } from '../../utils/api-error.js';
import { AERO_ERROR_CODES, AeroLogBodySchema } from './aero.schema.js';
import { appendAeroLog } from './aero.log.service.js';

/**
 * The only identity the company integration exists for.
 *
 * The owner is compared against the username the authentication middleware read from the database, so
 * a token that carries `wilkin` for a user that no longer is `wilkin` writes nothing. This is a
 * deliberate narrowing of the admin role: the factory asked for one person during the trial, and an
 * operator with the same role must not be able to read or write this trail.
 */
export const AERO_INTEGRATION_OWNER = 'wilkin';

/**
 * Integration module — Fastify plugin, registered at /api/integration/aero.
 *
 * The audit trail is one endpoint because the client is one page: it records the attempt to talk to the
 * company WebService and the outcome, and nothing else. There is no read endpoint on purpose — the log
 * is inspected by an operator over SSH, not by the browser.
 */
export async function aeroIntegrationRoutes(fastify: FastifyInstance) {
  const ownerGuard = [
    fastify.authenticate,
    requireRole('Administrador'),
    requireUsername(AERO_INTEGRATION_OWNER),
  ];

  /**
   * POST /api/integration/aero/log
   *
   * Appends one line to `logs/aero-integration.log`. The body is validated strictly, so a client cannot
   * smuggle credentials or an identity: the user written on the line is the one the token resolved to.
   */
  fastify.post(
    '/log',
    { preHandler: ownerGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parsed = AeroLogBodySchema.safeParse(request.body);
      if (!parsed.success) {
        return reply
          .status(400)
          .send(
            apiError(
              'Invalid integration log event',
              AERO_ERROR_CODES.INVALID_AERO_LOG,
              parsed.error.flatten(),
            ),
          );
      }

      const body = parsed.data;
      appendAeroLog({
        ts: new Date().toISOString(),
        event: body.event,
        phase: body.phase ?? null,
        user: request.user!.username,
        role: request.user!.role,
        destinationUrl: body.destinationUrl,
        httpStatus: body.httpStatus ?? null,
        payload: body.payload ?? null,
        response: body.response ?? null,
        error: body.error ?? null,
        launchId: body.launchId ?? null,
        note: body.note ?? null,
      });

      return reply.status(200).send({ ok: true });
    },
  );
}
