import type { FastifyInstance } from 'fastify';
import bcrypt from 'bcryptjs';
import { setupSchema } from './auth.schema.js';

function setupRequired(fastify: FastifyInstance): boolean {
  return (fastify.db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number) === 0;
}

export async function bootstrapRoutes(fastify: FastifyInstance) {
  fastify.get('/setup/status', async () => ({ setupRequired: setupRequired(fastify) }));

  fastify.post('/setup', async (request, reply) => {
    const parsed = setupSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send({ error: 'Invalid input', details: parsed.error.flatten() });
    }

    const passwordHash = await bcrypt.hash(parsed.data.password, 10);
    const createAdministrator = fastify.db.transaction(() => {
      if (!setupRequired(fastify)) return null;
      const role = fastify.db.prepare("SELECT id FROM roles WHERE name = 'Administrador'").get() as { id: number } | undefined;
      if (!role) throw new Error('Missing required reference: role Administrador');
      const result = fastify.db.prepare(
        'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)'
      ).run(parsed.data.username, passwordHash, parsed.data.displayName, role.id);
      return Number(result.lastInsertRowid);
    });

    try {
      const userId = createAdministrator.immediate();
      if (userId === null) return reply.status(409).send({ error: 'Initial setup is already complete' });
      return reply.status(201).send({ success: true, userId });
    } catch (error: any) {
      if (error?.code?.startsWith('SQLITE_CONSTRAINT')) {
        return reply.status(409).send({ error: 'Initial setup could not create the administrator' });
      }
      throw error;
    }
  });
}
