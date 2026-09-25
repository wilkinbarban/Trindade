import type { FastifyInstance } from 'fastify';

function setupRequired(fastify: FastifyInstance): boolean {
  return (fastify.db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number) === 0;
}

export async function bootstrapRoutes(fastify: FastifyInstance) {
  fastify.get('/setup/status', async () => ({ setupRequired: setupRequired(fastify) }));

  // Retain the legacy route so clients receive a deterministic refusal rather than
  // attempting to provision through an unknown endpoint. Never parse its body.
  fastify.post('/setup', async (_request, reply) =>
    reply.status(410).send({ error: 'Administrator provisioning is available only to the operator' }));
}
