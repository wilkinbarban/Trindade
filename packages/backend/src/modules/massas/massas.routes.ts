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

    const { date, product_code, boxes, items } = parsed.data;
    const targetDate = date ?? getSaoPauloDateString();

    const todayInSaoPaulo = getSaoPauloDateString();
    if (targetDate > todayInSaoPaulo) {
      return reply.status(400).send(apiError('Future launch dates are not allowed', MASSAS_ERROR_CODES.FUTURE_DATE));
    }

    const targetItems = items ?? (product_code && boxes ? [{ product_code, boxes }] : []);

    if (targetItems.length === 0) {
      return reply.status(400).send(apiError('Must provide either items array or product_code and boxes', MASSAS_ERROR_CODES.EMPTY_ITEMS));
    }

    try {
      const created = createMassaLaunches(fastify.db, {
        date: targetDate,
        userId: request.user!.sub,
        items: targetItems,
      });
      return reply.status(201).send({ created });
    } catch (error) {
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
      const message = error instanceof Error ? error.message : 'Failed to delete launch';
      return reply.status(400).send(apiError(message, MASSAS_ERROR_CODES.DELETE_FAILED));
    }
  });
}

