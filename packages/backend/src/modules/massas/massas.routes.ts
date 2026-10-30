import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { apiError, MASSAS_ERROR_CODES } from '../../utils/api-error.js';
import { getSaoPauloDateString } from '../../utils/date.js';
import {
  createMassaLaunches,
  deleteMassaLaunch,
  massasLaunchDayView,
  massasLaunchHistory,
  updateMassaLaunch,
} from './massas.service.js';
import {
  MassasLaunchCreateBodySchema,
  MassasLaunchUpdateBodySchema,
} from './massas.schema.js';
import { canImportSeat } from './massas-import-policy.js';
import { resolveSeatOwnerUserId } from './massas-operator.js';
import { massasReportDayView } from './massas-report.service.js';

const dateQuery = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD').optional(),
});

const paramId = z.object({
  id: z.coerce.number().int().positive(),
});

export async function massasRoutes(fastify: FastifyInstance) {
  fastify.get('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = dateQuery.safeParse(request.query);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Invalid date format', MASSAS_ERROR_CODES.INVALID_DATE_FORMAT));
    }
    const targetDate = parsed.data.date ?? getSaoPauloDateString();
    return reply.send(massasLaunchDayView(fastify.db, targetDate));
  });

  // The day as the report reads it: launches here plus the tablet production that reconciles with nothing.
  // `/` keeps answering the launches alone, because that screen is about typing, not about counting.
  fastify.get('/report', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = dateQuery.safeParse(request.query);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Invalid date format', MASSAS_ERROR_CODES.INVALID_DATE_FORMAT));
    }
    const targetDate = parsed.data.date ?? getSaoPauloDateString();
    return reply.send(massasReportDayView(fastify.db, targetDate));
  });

  fastify.get('/history', { preHandler: [fastify.authenticate] }, async (_request: FastifyRequest, reply: FastifyReply) => {
    return reply.send({ days: massasLaunchHistory(fastify.db) });
  });

  fastify.post('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = MassasLaunchCreateBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Invalid launch data', MASSAS_ERROR_CODES.INVALID_LAUNCH_DATA, parsed.error.format()));
    }

    const { date, product_code, boxes, items, ent_lan: bodyEntLan, operator_name: bodyOperatorName } = parsed.data;
    const targetDate = date ?? getSaoPauloDateString();

    const todayInSaoPaulo = getSaoPauloDateString();
    if (targetDate > todayInSaoPaulo) {
      return reply.status(400).send(apiError('Future launch dates are not allowed', MASSAS_ERROR_CODES.FUTURE_DATE));
    }

    const targetItems = items ?? (product_code && boxes ? [{ product_code, boxes }] : []);

    if (targetItems.length === 0) {
      return reply.status(400).send(apiError('Must provide either items array or product_code and boxes', MASSAS_ERROR_CODES.EMPTY_ITEMS));
    }

    // One seat is one product line: a request that names a single seat for several items would store the
    // same seat on lines it cannot belong to, so it is refused instead of guessed.
    if (bodyEntLan !== undefined && targetItems.length > 1) {
      return reply
        .status(400)
        .send(
          apiError(
            'A single ent_lan cannot describe several items; send ent_lan on the item it belongs to',
            MASSAS_ERROR_CODES.INVALID_LAUNCH_DATA,
          ),
        );
    }

    // The operator is the seat's, not the importer's; without a name that resolves, the importer keeps the
    // launch. It is one decision for the whole request because an import carries one operator's boxes.
    const operatorName = bodyOperatorName ?? targetItems.find((item) => item.operator_name)?.operator_name ?? null;
    const seatItems = targetItems.map((item) => ({
      product_code: item.product_code,
      boxes: item.boxes,
      // A body-level seat matches the single item the rule above allowed.
      ent_lan: item.ent_lan ?? bodyEntLan ?? null,
    }));

    // A worker imports only what the ERP already attributes to them; an administrator imports anybody's.
    // The owner comes from the mirrored seat rather than from the body, so naming somebody else in
    // `operator_name` cannot hand a worker boxes that are not theirs. A seat the mirror never saw has no
    // owner here, and an owner nobody can prove is an administrator's to import.
    const claimedSeat = seatItems.find((item) => item.ent_lan !== null)?.ent_lan ?? null;
    // No seat, no import: a manual launch is the asker's own work and keeps the owner rule out of it.
    if (
      claimedSeat !== null &&
      !canImportSeat({
        ownerUserId: resolveSeatOwnerUserId(fastify.db, claimedSeat),
        userId: request.user!.sub,
        isAdmin: request.user?.role === 'Administrador',
      })
    ) {
      return reply
        .status(403)
        .send(apiError('This ERP seat belongs to another operator', MASSAS_ERROR_CODES.FORBIDDEN));
    }

    try {
      const created = createMassaLaunches(fastify.db, {
        date: targetDate,
        userId: request.user!.sub,
        operatorName,
        items: seatItems,
      });
      return reply.status(201).send({ created });
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'ALREADY_IMPORTED') {
        return reply
          .status(409)
          .send(apiError('This ERP seat was already imported', MASSAS_ERROR_CODES.ALREADY_IMPORTED));
      }
      const message = error instanceof Error ? error.message : 'Failed to create launches';
      return reply.status(400).send(apiError(message, MASSAS_ERROR_CODES.CREATE_FAILED));
    }
  });

  fastify.put('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send(apiError('Invalid launch ID', MASSAS_ERROR_CODES.INVALID_ID));
    }

    const parsedBody = MassasLaunchUpdateBodySchema.safeParse(request.body);
    if (!parsedBody.success) {
      return reply.status(400).send(apiError('Invalid boxes count', MASSAS_ERROR_CODES.INVALID_BOXES, parsedBody.error.format()));
    }

    try {
      const updated = updateMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
        userRole: request.user?.role,
        boxes: parsedBody.data.boxes,
      });
      return reply.send(updated);
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send(apiError('Launch not found', MASSAS_ERROR_CODES.NOT_FOUND));
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send(apiError('Forbidden: cannot edit launches belonging to another user', MASSAS_ERROR_CODES.FORBIDDEN));
      }
      if (code === 'RECONCILED') {
        return reply
          .status(409)
          .send(apiError('This production was already reconciled with the ERP and cannot change', MASSAS_ERROR_CODES.RECONCILED));
      }
      const message = error instanceof Error ? error.message : 'Failed to update launch';
      return reply.status(400).send(apiError(message, MASSAS_ERROR_CODES.UPDATE_FAILED));
    }
  });

  fastify.delete('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send(apiError('Invalid launch ID', MASSAS_ERROR_CODES.INVALID_ID));
    }

    try {
      deleteMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
        userRole: request.user?.role,
      });
      return reply.send({ ok: true });
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send(apiError('Launch not found', MASSAS_ERROR_CODES.NOT_FOUND));
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send(apiError('Forbidden: cannot delete launches belonging to another user', MASSAS_ERROR_CODES.FORBIDDEN));
      }
      if (code === 'RECONCILED') {
        return reply
          .status(409)
          .send(apiError('This production was already reconciled with the ERP and cannot change', MASSAS_ERROR_CODES.RECONCILED));
      }
      const message = error instanceof Error ? error.message : 'Failed to delete launch';
      return reply.status(400).send(apiError(message, MASSAS_ERROR_CODES.DELETE_FAILED));
    }
  });
}

