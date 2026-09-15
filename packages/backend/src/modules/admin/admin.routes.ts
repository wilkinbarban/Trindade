import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { requireRole } from '../auth/auth.middleware.js';
import { log as auditLog } from '../audit/audit.service.js';
import {
  CreateCategorySchema,
  UpdateCategorySchema,
  CreateTaskSchema,
  UpdateTaskSchema,
  CreateDriverSchema,
  UpdateDriverSchema,
  CreateVehicleSchema,
  UpdateVehicleSchema,
  UpdateTimeSlotsSchema,
  CreateUserSchema,
  UpdateUserSchema,
} from './admin.schema.js';
import * as service from './admin.service.js';

/**
 * Admin module — Fastify plugin.
 *
 * Registered at /api/admin in server.ts.
 * All routes require Admin role via `preHandler: [fastify.authenticate, requireRole('Administrador')]`.
 */
export async function adminRoutes(fastify: FastifyInstance) {
  // Shared preHandler for all admin routes
  const adminGuard = [fastify.authenticate, requireRole('Administrador')];
  const catalogGuard = [fastify.authenticate, requireRole('Administrador', 'Trabalhador')];

  const isAdmin = (request: FastifyRequest) => request.user?.role === 'Administrador';
  const isWorker = (request: FastifyRequest) => request.user?.role === 'Trabalhador';

  // ============================================================
  // Categories
  // ============================================================

  fastify.get(
    '/categories',
    { preHandler: catalogGuard },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const categories = service.listCategories(fastify.db);
      return reply.send({ categories });
    }
  );

  fastify.post(
    '/categories',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateCategorySchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }
      const category = await service.createCategory(fastify.db, parse.data);
      // Audit: category created
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'create',
        entityType: 'category',
        entityId: category.id,
        ipAddress: request.ip,
        details: { name_pt: category.name_pt },
      });
      return reply.status(201).send({ category });
    }
  );

  fastify.patch(
    '/categories/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const categoryId = parseInt(id, 10);
      if (isNaN(categoryId)) {
        return reply.status(400).send({ error: 'Invalid category ID' });
      }

      const parse = UpdateCategorySchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const category = await service.updateCategory(fastify.db, categoryId, parse.data);
      if (!category) {
        return reply.status(404).send({ error: 'Category not found' });
      }

      // Audit: category updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'category',
        entityId: categoryId,
        ipAddress: request.ip,
      });

      return reply.send({ category });
    }
  );

  fastify.delete(
    '/categories/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const categoryId = parseInt(id, 10);
      if (isNaN(categoryId)) {
        return reply.status(400).send({ error: 'Invalid category ID' });
      }

      try {
        const deleted = service.deleteCategory(fastify.db, categoryId);
        if (!deleted) {
          return reply.status(404).send({ error: 'Category not found' });
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'delete',
          entityType: 'category',
          entityId: categoryId,
          ipAddress: request.ip,
        });

        return reply.send({ success: true });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({
            error: 'Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.',
          });
        }
        throw err;
      }
    }
  );

  // ============================================================
  // Tasks
  // ============================================================

  fastify.get(
    '/tasks',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const tasks = service.listTasks(fastify.db);
      return reply.send({
        tasks: isWorker(request) ? tasks.filter((task) => task.is_active === 1) : tasks,
      });
    }
  );

  fastify.post(
    '/tasks',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateTaskSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }
      const task = await service.createTask(
        fastify.db,
        parse.data,
        isWorker(request) ? request.user!.sub : null
      );
      // Audit: task created
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'create',
        entityType: 'task',
        entityId: task.id,
        ipAddress: request.ip,
        details: { name_pt: task.name_pt },
      });
      return reply.status(201).send({ task });
    }
  );

  fastify.patch(
    '/tasks/:id',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const taskId = parseInt(id, 10);
      if (isNaN(taskId)) {
        return reply.status(400).send({ error: 'Invalid task ID' });
      }

      const parse = UpdateTaskSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      if (isWorker(request)) {
        if (parse.data.is_active !== undefined) {
          return reply.status(403).send({ error: 'Insufficient permissions' });
        }
        const existing = fastify.db
          .prepare('SELECT created_by_user_id FROM report_tasks WHERE id = ?')
          .get(taskId) as { created_by_user_id: number | null } | undefined;
        if (!existing) {
          return reply.status(404).send({ error: 'Task not found' });
        }
        if (existing.created_by_user_id !== request.user!.sub) {
          return reply.status(403).send({ error: 'Insufficient permissions' });
        }
      }

      const task = await service.updateTask(fastify.db, taskId, parse.data);
      if (!task) {
        return reply.status(404).send({ error: 'Task not found' });
      }

      // Audit: task updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'task',
        entityId: taskId,
        ipAddress: request.ip,
      });

      return reply.send({ task });
    }
  );

  fastify.delete(
    '/tasks/:id',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const taskId = parseInt(id, 10);
      if (isNaN(taskId)) {
        return reply.status(400).send({ error: 'Invalid task ID' });
      }

      if (isWorker(request)) {
        const existing = fastify.db
          .prepare('SELECT created_by_user_id FROM report_tasks WHERE id = ?')
          .get(taskId) as { created_by_user_id: number | null } | undefined;
        if (!existing) {
          return reply.status(404).send({ error: 'Task not found' });
        }
        if (existing.created_by_user_id !== request.user!.sub) {
          return reply.status(403).send({ error: 'Insufficient permissions' });
        }
      }

      try {
        const deleted = service.deleteTask(fastify.db, taskId);
        if (!deleted) {
          return reply.status(404).send({ error: 'Task not found' });
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'delete',
          entityType: 'task',
          entityId: taskId,
          ipAddress: request.ip,
        });

        return reply.send({ success: true });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({
            error: 'Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.',
          });
        }
        throw err;
      }
    }
  );

  // ============================================================
  // Drivers
  // ============================================================

  fastify.get(
    '/drivers',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const drivers = service.listDrivers(fastify.db);
      return reply.send({
        drivers: isWorker(request) ? drivers.filter((driver) => driver.is_active === 1) : drivers,
      });
    }
  );

  fastify.post(
    '/drivers',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateDriverSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }
      if (isWorker(request) && parse.data.driver_type !== 'fletero') {
        return reply.status(403).send({ error: 'Insufficient permissions' });
      }
      const driver = service.createDriver(
        fastify.db,
        parse.data,
        isWorker(request) ? request.user!.sub : null
      );
      // Audit: driver created
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'create',
        entityType: 'driver',
        entityId: driver.id,
        ipAddress: request.ip,
        details: { name: parse.data.name },
      });
      return reply.status(201).send({ driver });
    }
  );

  fastify.patch(
    '/drivers/:id',
    { preHandler: catalogGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const driverId = parseInt(id, 10);
      if (isNaN(driverId)) {
        return reply.status(400).send({ error: 'Invalid driver ID' });
      }

      const parse = UpdateDriverSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      if (isWorker(request)) {
        if (parse.data.is_active !== undefined || parse.data.driver_type === 'casa') {
          return reply.status(403).send({ error: 'Insufficient permissions' });
        }
        const existing = fastify.db
          .prepare('SELECT driver_type, created_by_user_id FROM drivers WHERE id = ?')
          .get(driverId) as { driver_type: 'casa' | 'fletero'; created_by_user_id: number | null } | undefined;
        if (!existing) {
          return reply.status(404).send({ error: 'Driver not found' });
        }
        if (existing.driver_type !== 'fletero' || existing.created_by_user_id !== request.user!.sub) {
          return reply.status(403).send({ error: 'Insufficient permissions' });
        }
      }

      const driver = service.updateDriver(fastify.db, driverId, parse.data);
      if (!driver) {
        return reply.status(404).send({ error: 'Driver not found' });
      }

      // Audit: driver updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'driver',
        entityId: driverId,
        ipAddress: request.ip,
      });

      return reply.send({ driver });
    }
  );

  // ============================================================
  // Vehicles
  // ============================================================

  fastify.get(
    '/vehicles',
    { preHandler: adminGuard },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const vehicles = service.listVehicles(fastify.db);
      return reply.send({ vehicles });
    }
  );

  fastify.post(
    '/vehicles',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateVehicleSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }
      const vehicle = service.createVehicle(fastify.db, parse.data);
      // Audit: vehicle created
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'create',
        entityType: 'vehicle',
        entityId: vehicle.id,
        ipAddress: request.ip,
        details: { description: parse.data.description },
      });
      return reply.status(201).send({ vehicle });
    }
  );

  fastify.patch(
    '/vehicles/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const vehicleId = parseInt(id, 10);
      if (isNaN(vehicleId)) {
        return reply.status(400).send({ error: 'Invalid vehicle ID' });
      }

      const parse = UpdateVehicleSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const vehicle = service.updateVehicle(fastify.db, vehicleId, parse.data);
      if (!vehicle) {
        return reply.status(404).send({ error: 'Vehicle not found' });
      }

      // Audit: vehicle updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'vehicle',
        entityId: vehicleId,
        ipAddress: request.ip,
      });

      return reply.send({ vehicle });
    }
  );

  fastify.delete(
    '/vehicles/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const vehicleId = parseInt(id, 10);
      if (isNaN(vehicleId)) {
        return reply.status(400).send({ error: 'Invalid vehicle ID' });
      }

      try {
        const deleted = service.deleteVehicle(fastify.db, vehicleId);
        if (!deleted) {
          return reply.status(404).send({ error: 'Vehicle not found' });
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'delete',
          entityType: 'vehicle',
          entityId: vehicleId,
          ipAddress: request.ip,
        });

        return reply.send({ success: true });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({
            error: 'Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo.',
          });
        }
        throw err;
      }
    }
  );

  // ============================================================
  // Time Slots
  // ============================================================

  fastify.get(
    '/time-slots',
    { preHandler: adminGuard },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const timeSlots = service.getTimeSlots(fastify.db);
      return reply.send({ timeSlots });
    }
  );

  fastify.put(
    '/time-slots',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = UpdateTimeSlotsSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const timeSlots = service.updateTimeSlots(fastify.db, parse.data);
      return reply.send({ timeSlots });
    }
  );

  // ============================================================
  // Users
  // ============================================================

  fastify.get(
    '/users',
    { preHandler: adminGuard },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const users = service.listUsers(fastify.db);
      return reply.send({ users });
    }
  );

  fastify.post(
    '/users',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateUserSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }
      try {
        const user = service.createUser(fastify.db, parse.data);
        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'create',
          entityType: 'user',
          entityId: user.id,
          ipAddress: request.ip,
          details: { username: parse.data.username },
        });
        return reply.status(201).send({ user });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({ error: err.message });
        }
        throw err;
      }
    }
  );

  fastify.patch(
    '/users/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const userId = parseInt(id, 10);
      if (isNaN(userId)) {
        return reply.status(400).send({ error: 'Invalid user ID' });
      }

      const parse = UpdateUserSchema.safeParse(request.body);
      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      try {
        if (userId === request.user!.sub) {
          const selfRestrictedFields = parse.data.role_id !== undefined || parse.data.is_active !== undefined;
          if (selfRestrictedFields) {
            return reply.status(403).send({
              error: 'Não é possível alterar seu próprio perfil de acesso. Atualize apenas seus dados e senha.',
            });
          }
        }

        const user = service.updateUser(fastify.db, userId, parse.data);
        if (!user) {
          return reply.status(404).send({ error: 'User not found' });
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'update',
          entityType: 'user',
          entityId: userId,
          ipAddress: request.ip,
        });

        return reply.send({ user });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({ error: err.message });
        }
        throw err;
      }
    }
  );

  fastify.delete(
    '/users/:id',
    { preHandler: adminGuard },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const userId = parseInt(id, 10);
      if (isNaN(userId)) {
        return reply.status(400).send({ error: 'Invalid user ID' });
      }

      try {
        if (userId === request.user!.sub) {
          return reply.status(403).send({
            error: 'Não é possível excluir seu próprio usuário. Atualize apenas seus dados e senha.',
          });
        }

        const deleted = service.deleteUser(fastify.db, userId);
        if (!deleted) {
          return reply.status(404).send({ error: 'User not found' });
        }

        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'delete',
          entityType: 'user',
          entityId: userId,
          ipAddress: request.ip,
        });

        return reply.send({ success: true });
      } catch (err: any) {
        if (err && err.statusCode === 400) {
          return reply.status(400).send({ error: err.message });
        }
        throw err;
      }
    }
  );
}
