import type { FastifyInstance } from 'fastify';
import { GUEST_USERNAME } from '../../guest-user.js';

function setupRequired(fastify: FastifyInstance): boolean {
  // El invitado no cuenta: si contara, el sistema diría que ya está inicializado.
  return (fastify.db.prepare('SELECT COUNT(*) FROM users WHERE username != ?').pluck().get(GUEST_USERNAME) as number) === 0;
}

export async function bootstrapRoutes(fastify: FastifyInstance) {
  fastify.get('/setup/status', async () => ({ setupRequired: setupRequired(fastify) }));

  // Retain the legacy route so clients receive a deterministic refusal rather than
  // attempting to provision through an unknown endpoint. Never parse its body.
  fastify.post('/setup', async (_request, reply) =>
    reply.status(410).send({ error: 'Administrator provisioning is available only to the operator' }));
}
