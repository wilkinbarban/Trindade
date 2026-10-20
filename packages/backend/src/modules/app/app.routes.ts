import type { FastifyInstance } from 'fastify';
import { getLatestRelease, downloadApkBinary } from './app.service.js';

export async function appRoutes(fastify: FastifyInstance): Promise<void> {
  fastify.get('/latest-release', async (_request, reply) => {
    const release = await getLatestRelease();
    return reply.status(200).send(release);
  });

  fastify.get('/download-apk', async (_request, reply) => {
    const binary = await downloadApkBinary();
    if (!binary) {
      return reply.status(502).send({ message: 'APK download temporarily unavailable' });
    }
    return reply
      .header('Content-Type', 'application/vnd.android.package-archive')
      .header('Content-Disposition', 'attachment; filename="trindade.apk"')
      .send(binary.buffer);
  });
}
