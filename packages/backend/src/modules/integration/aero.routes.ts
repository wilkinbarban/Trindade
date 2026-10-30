import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { apiError } from '../../utils/api-error.js';
import { AERO_ERROR_CODES, AeroLogBodySchema } from './aero.schema.js';
import { appendAeroLog } from './aero.log.service.js';
import { AERO_MOVEMENTS_ERROR_CODES, AeroMovementsBodySchema } from './aero.movements.schema.js';
import {
  aeroMovementsTableExists,
  listAeroMovements,
  saveAeroMovements,
} from './aero.movements.service.js';
import { movementLenses } from '../reports/receipts.service.js';

/**
 * Integration module — Fastify plugin, registered at /api/integration/aero.
 *
 * Two things live here. The audit trail is write-only, because the log is inspected by an operator over
 * SSH and not by the browser: every signed-in operator appends to it, because every operator can dispatch
 * a launch from their own phone and the plant needs the real author of each line. The ERP mirror is read by
 * the whole plant: the bridge can only run on one phone, and every other device reads the snapshot it left
 * behind.
 */
export async function aeroIntegrationRoutes(fastify: FastifyInstance) {
  /**
   * POST /api/integration/aero/log
   *
   * Appends one line to `logs/aero-integration.log`. Any authenticated user may append: the identity
   * written on the line is the one the token resolved to against the database, never one sent in the
   * body, so opening the write does not open the identity. The body is validated strictly, so a client
   * cannot smuggle credentials either.
   */
  fastify.post(
    '/log',
    { preHandler: [fastify.authenticate] },
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

  /**
   * GET /api/integration/aero/movements?date=YYYY-MM-DD
   *
   * The day's seats as the ERP reported them. Any signed-in operator reads this: the mirror is a copy of
   * what the company already recorded, and hiding it would force each phone to run the shortcut.
   */
  fastify.get(
    '/movements',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const date = (request.query as { date?: unknown } | undefined)?.date;
      if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
        return reply
          .status(400)
          .send(apiError('date must be YYYY-MM-DD', AERO_MOVEMENTS_ERROR_CODES.INVALID_DATE_FORMAT));
      }

      if (!aeroMovementsTableExists(fastify.db)) {
        return reply
          .status(503)
          .send(
            apiError(
              'The ERP mirror table is missing: run db:migrate',
              AERO_MOVEMENTS_ERROR_CODES.NOT_MIGRATED,
            ),
          );
      }

      return reply.send({ date, movements: listAeroMovements(fastify.db, date) });
    },
  );

  /**
   * POST /api/integration/aero/movements
   *
   * Stores the answer the bridge brought back for one day. Authenticated, like the read, and validated
   * strictly: the body is whatever came out of a phone, and a seat this module cannot describe is refused
   * instead of stored. The mirror is a cache of the company's records, never an authority over them.
   */
  fastify.post(
    '/movements',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parsed = AeroMovementsBodySchema.safeParse(request.body);
      if (!parsed.success) {
        return reply
          .status(400)
          .send(
            apiError(
              'Invalid movements snapshot',
              AERO_MOVEMENTS_ERROR_CODES.INVALID_MOVEMENTS,
              parsed.error.flatten(),
            ),
          );
      }

      if (!aeroMovementsTableExists(fastify.db)) {
        return reply
          .status(503)
          .send(
            apiError(
              'The ERP mirror table is missing: run db:migrate',
              AERO_MOVEMENTS_ERROR_CODES.NOT_MIGRATED,
            ),
          );
      }

      const saved = saveAeroMovements(fastify.db, parsed.data);
      // The day is stored whole and counted twice: each screen asks the lens it cares about, so counting
      // here keeps the catalogue in one place instead of a copy on the phone.
      const lenses = movementLenses(parsed.data.movements);
      return reply.status(201).send({ saved, ...lenses });
    },
  );
}
