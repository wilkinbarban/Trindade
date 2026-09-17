import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { authRoutes } from './modules/auth/auth.routes.js';
import { bootstrapRoutes } from './modules/auth/bootstrap.routes.js';
import { dashboardRoutes } from './modules/dashboard/dashboard.routes.js';
import { reportsRoutes } from './modules/reports/reports.routes.js';
import { getPhotoByPublicToken } from './modules/reports/reports.lifecycle.service.js';
import { loadingRoutes } from './modules/loading/loading.routes.js';
import { adminRoutes } from './modules/admin/admin.routes.js';
import { auditRoutes } from './modules/audit/audit.routes.js';

export interface ApiRouteOptions {
  jwtSecret: string;
  refreshTokenTtlDays: number;
  /** Resolves whether the database has the session table, on the paths that need it. */
  sessionStoreAvailable: () => boolean;
  photosDir: string;
  publicBaseUrl: string;
}

/**
 * Every route the API serves, registered from one place.
 *
 * This is separated from `server.ts` so the contract test and the server cannot disagree about
 * what the surface is. The test registers this same set, enumerates what Fastify actually
 * accepted, and compares that against the generated document; had the routes stayed in
 * `server.ts`, the test would have had to repeat the list by hand, and a list repeated by hand is
 * precisely the drift the coverage test exists to catch.
 *
 * `server.ts` keeps everything that is about running a process rather than serving a request:
 * configuration, the database handle, CORS, multipart, the startup notices, the retention
 * cleanups, and the listener.
 */
export async function registerApiRoutes(
  fastify: FastifyInstance,
  options: ApiRouteOptions,
): Promise<void> {
  await fastify.register(authRoutes, {
    prefix: '/api/auth',
    jwtSecret: options.jwtSecret,
    refreshTokenTtlDays: options.refreshTokenTtlDays,
    sessionStoreAvailable: options.sessionStoreAvailable,
  });
  await fastify.register(bootstrapRoutes, { prefix: '/api/auth' });
  await fastify.register(dashboardRoutes, { prefix: '/api/dashboard' });
  await fastify.register(reportsRoutes, {
    prefix: '/api/reports',
    photosDir: options.photosDir,
    publicBaseUrl: options.publicBaseUrl,
  });
  await fastify.register(loadingRoutes, { prefix: '/api/loading' });
  await fastify.register(adminRoutes, { prefix: '/api/admin' });
  await fastify.register(auditRoutes, { prefix: '/api/admin' });

  // The short public photo link, which is what WhatsApp exports point at. It needs no
  // authentication because the token is the credential.
  fastify.get('/p/:token', async (request, reply) => {
    const { token } = request.params as { token: string };
    if (!token || token.length < 8) {
      return reply.status(404).send({ error: 'Photo not found' });
    }

    const photo = getPhotoByPublicToken(fastify.db, token);
    if (!photo) {
      return reply.status(404).send({ error: 'Photo not found' });
    }

    const filePath = join(options.photosDir, photo.file_path);
    if (!existsSync(filePath)) {
      return reply.status(404).send({ error: 'Photo file not found on disk' });
    }

    reply.header('Content-Type', photo.mime_type || 'image/jpeg');
    reply.header('Cache-Control', 'public, max-age=31536000, immutable');

    return reply.send(await readFile(filePath));
  });

  // The user list the history filters offer.
  fastify.get('/api/users/options', { preHandler: [fastify.authenticate] }, async (_request, reply) => {
    const users = fastify.db
      .prepare('SELECT id, display_name FROM users WHERE is_active = 1 ORDER BY display_name ASC')
      .all();
    return reply.send({ users });
  });

  // Liveness only: it reports that the process is serving, not that the database is reachable.
  fastify.get('/api/health', async () => ({
    status: 'ok',
    timestamp: new Date().toISOString(),
  }));
}
