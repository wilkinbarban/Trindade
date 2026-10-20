import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { getSaoPauloDateString } from '../../utils/date.js';
import {
  createMassaLaunches,
  deleteMassaLaunch,
  massasLaunchDayView,
  updateMassaLaunch,
} from './massas.service.js';
import {
  MassasLaunchCreateBodySchema,
  MassasLaunchUpdateBodySchema,
} from './massas.schema.js';

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
      return reply.status(400).send({ error: 'Invalid date format' });
    }
    const targetDate = parsed.data.date ?? getSaoPauloDateString();
    return reply.send(massasLaunchDayView(fastify.db, targetDate));
  });

  fastify.post('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = MassasLaunchCreateBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send({ error: 'Invalid launch data', details: parsed.error.format() });
    }

    const { date, product_code, boxes, items } = parsed.data;
    const targetDate = date ?? getSaoPauloDateString();
    const targetItems = items ?? (product_code && boxes ? [{ product_code, boxes }] : []);

    if (targetItems.length === 0) {
      return reply.status(400).send({ error: 'Must provide either items array or product_code and boxes' });
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
      return reply.status(400).send({ error: message });
    }
  });

  fastify.put('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send({ error: 'Invalid launch ID' });
    }

    const parsedBody = MassasLaunchUpdateBodySchema.safeParse(request.body);
    if (!parsedBody.success) {
      return reply.status(400).send({ error: 'Invalid boxes count', details: parsedBody.error.format() });
    }

    try {
      const updated = updateMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
        boxes: parsedBody.data.boxes,
      });
      return reply.send(updated);
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send({ error: 'Launch not found' });
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send({ error: 'Forbidden: cannot edit launches belonging to another user' });
      }
      const message = error instanceof Error ? error.message : 'Failed to update launch';
      return reply.status(400).send({ error: message });
    }
  });

  fastify.delete('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send({ error: 'Invalid launch ID' });
    }

    try {
      deleteMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
      });
      return reply.send({ ok: true });
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send({ error: 'Launch not found' });
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send({ error: 'Forbidden: cannot delete launches belonging to another user' });
      }
      const message = error instanceof Error ? error.message : 'Failed to delete launch';
      return reply.status(400).send({ error: message });
    }
  });
}
