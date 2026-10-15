import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { addStockEntry, resetStockProduct, stockDayView, stockHistory } from './stock.service.js';

/**
 * The counting endpoints.
 *
 * Every one of them sits behind the same guard as the rest of the API: a count is a rewriting of the
 * day's numbers, and an unauthenticated caller could otherwise zero a count somebody is standing next
 * to. The service owns the arithmetic; this file owns the wire, including turning its refusals into
 * 400s, because an unknown product and a box count that adds nothing are bad requests and not crashes.
 */
const dateQuery = z.object({ date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD') });
const addEntryBody = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD'),
  product_code: z.string().min(1),
  boxes_added: z.number(),
});
const resetBody = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD'),
  product_code: z.string().min(1),
});

export async function stockRoutes(fastify: FastifyInstance) {
  fastify.get('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = dateQuery.safeParse(request.query);
    if (!parsed.success) return reply.status(400).send({ error: 'Invalid date' });
    return reply.send(stockDayView(fastify.db, parsed.data.date));
  });

  fastify.post('/add-entry', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    // A count is a rewriting of the day's numbers, so the role decides who may rewrite it. The
    // reads stay open to every session: a worker needs to see the counter, not to move it.
    if (request.user!.role !== 'Administrador') {
      return reply.status(403).send({ error: 'Insufficient permissions' });
    }
    const parsed = addEntryBody.safeParse(request.body);
    if (!parsed.success) return reply.status(400).send({ error: 'Invalid entry' });
    try {
      return reply.send(addStockEntry(fastify.db, {
        date: parsed.data.date,
        productCode: parsed.data.product_code,
        boxesAdded: parsed.data.boxes_added,
        userId: request.user!.sub,
      }));
    } catch (error) {
      return reply.status(400).send({ error: error instanceof Error ? error.message : 'Invalid entry' });
    }
  });

  fastify.post('/reset-product', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    // A count is a rewriting of the day's numbers, so the role decides who may rewrite it. The
    // reads stay open to every session: a worker needs to see the counter, not to move it.
    if (request.user!.role !== 'Administrador') {
      return reply.status(403).send({ error: 'Insufficient permissions' });
    }
    const parsed = resetBody.safeParse(request.body);
    if (!parsed.success) return reply.status(400).send({ error: 'Invalid reset' });
    try {
      return reply.send(resetStockProduct(fastify.db, {
        date: parsed.data.date,
        productCode: parsed.data.product_code,
        userId: request.user!.sub,
      }));
    } catch (error) {
      return reply.status(400).send({ error: error instanceof Error ? error.message : 'Invalid reset' });
    }
  });

  fastify.get('/history', { preHandler: [fastify.authenticate] }, async (_request: FastifyRequest, reply: FastifyReply) => {
    return reply.send({ days: stockHistory(fastify.db) });
  });
}
