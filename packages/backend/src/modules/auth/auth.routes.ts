import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { z } from 'zod';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { log as auditLog } from '../audit/audit.service.js';

const JWT_EXPIRY = '15m';

export interface AuthRoutesOptions {
  jwtSecret: string;
}

const loginSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
});

const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, 'Current password is required'),
  newPassword: z.string().min(8, 'New password must be at least 8 characters long'),
});

const updateProfileSchema = z.object({
  display_name: z.string().min(1, 'Display name is required').optional(),
}).strict();

interface UserRow {
  id: number;
  username: string;
  password_hash: string;
  display_name: string;
  is_active: number;
  role: string;
}

export async function authRoutes(fastify: FastifyInstance, options: AuthRoutesOptions) {
  fastify.post(
    '/login',
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = loginSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const { username, password } = parse.data;
      const db = fastify.db;

      const user = db
        .prepare(
          `SELECT u.id, u.username, u.password_hash, u.display_name,
                  u.is_active, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.username = ?`
        )
        .get(username) as UserRow | undefined;

      if (!user || !user.is_active) {
        return reply.status(401).send({ error: 'Invalid credentials' });
      }

      const valid = await bcrypt.compare(password, user.password_hash);

      if (!valid) {
        return reply.status(401).send({ error: 'Invalid credentials' });
      }

      const token = jwt.sign(
        { sub: user.id, username: user.username, role: user.role },
        options.jwtSecret,
        { expiresIn: JWT_EXPIRY }
      );

      try {
        auditLog(fastify.db, {
          userId: user.id,
          action: 'login',
          entityType: 'auth',
          ipAddress: request.ip,
        });
      } catch (err) {
        request.log.warn({ err, userId: user.id }, 'Login succeeded but its audit record could not be persisted');
      }

      return reply.send({
        token,
        user: {
          id: user.id,
          username: user.username,
          role: user.role,
        },
      });
    }
  );

  // GET /api/auth/me — returns the current authenticated user
  fastify.get(
    '/me',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const db = fastify.db;
      const { sub } = request.user!;

      const user = db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // GET /api/auth/profile — returns only the current authenticated user's profile
  fastify.get(
    '/profile',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const user = fastify.db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(request.user!.sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // PATCH /api/auth/profile — updates only the current authenticated user's profile
  fastify.patch(
    '/profile',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = updateProfileSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const updates: string[] = [];
      const params: unknown[] = [];
      if (parse.data.display_name !== undefined) {
        updates.push('display_name = ?');
        params.push(parse.data.display_name);
      }

      if (updates.length > 0) {
        updates.push("updated_at = datetime('now')");
        params.push(request.user!.sub);
        try {
          fastify.db
            .prepare(`UPDATE users SET ${updates.join(', ')} WHERE id = ?`)
            .run(...params);
        } catch (err: any) {
          throw err;
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'update',
          entityType: 'user',
          entityId: request.user!.sub,
          ipAddress: request.ip,
          details: { profile: true },
        });
      }

      const user = fastify.db
        .prepare(
          `SELECT u.id, u.username, u.display_name, r.name AS role
           FROM users u
           JOIN roles r ON u.role_id = r.id
           WHERE u.id = ?`
        )
        .get(request.user!.sub) as
        | { id: number; username: string; display_name: string; role: string }
        | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      return reply.send({ user });
    }
  );

  // POST /api/auth/change-password — change current user's password
  fastify.post(
    '/change-password',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = changePasswordSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const { currentPassword, newPassword } = parse.data;
      const userId = request.user!.sub;
      const db = fastify.db;

      const user = db
        .prepare('SELECT password_hash FROM users WHERE id = ?')
        .get(userId) as { password_hash: string } | undefined;

      if (!user) {
        return reply.status(404).send({ error: 'User not found' });
      }

      const valid = await bcrypt.compare(currentPassword, user.password_hash);
      if (!valid) {
        return reply.status(400).send({ error: 'Invalid current password' });
      }

      const newHash = bcrypt.hashSync(newPassword, 10);
      db.prepare("UPDATE users SET password_hash = ?, updated_at = datetime('now') WHERE id = ?").run(newHash, userId);

      auditLog(fastify.db, {
        userId: userId,
        action: 'update',
        entityType: 'user',
        entityId: userId,
        ipAddress: request.ip,
      });

      return reply.send({ success: true });
    }
  );

  // POST /api/auth/logout — record the logout event for audit trail
  fastify.post(
    '/logout',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'logout',
        entityType: 'auth',
        ipAddress: request.ip,
      });

      return reply.send({ success: true });
    }
  );
}
