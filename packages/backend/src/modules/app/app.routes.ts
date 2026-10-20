import type { FastifyInstance } from 'fastify';
import { getLatestRelease } from './app.service.js';

export async function appRoutes(fastify: FastifyInstance): Promise<void> {
  fastify.get('/latest-release', async (_request, reply) => {
    const release = await getLatestRelease();
    return reply.status(200).send(release);
  });
}
