import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import {
  ScheduleQuerySchema,
  ExportQuerySchema,
  HistoryQuerySchema,
  CreateScheduleSchema,
  CreateDriverSchema,
  UpdateScheduleSchema,
} from './loading.schema.js';
import * as service from './loading.service.js';
import { generateWhatsAppText } from './loading.export.service.js';
import { log as auditLog } from '../audit/audit.service.js';

/**
 * Loading module — Fastify plugin.
 *
 * Registered at /api/loading in server.ts.
 * All routes require authentication via `preHandler: [fastify.authenticate]`.
 */
export async function loadingRoutes(fastify: FastifyInstance) {
  // ---- Schedules ----


  fastify.get(
    '/schedules/history',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = HistoryQuerySchema.safeParse(request.query);
      if (!parse.success) {
        return reply.status(400).send({ error: 'Invalid query params', details: parse.error.flatten() });
      }
      return reply.send(service.listScheduleHistory(fastify.db, parse.data, request.user!));
    }
  );


  fastify.patch(
    '/schedules/batch/:date/deactivate',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (request.user!.role !== 'Administrador') return reply.status(403).send({ error: 'Insufficient permissions' });
      const { date } = request.params as { date: string };
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return reply.status(400).send({ error: 'Invalid batch date' });
      if (!service.deactivateBatch(fastify.db, date)) return reply.status(404).send({ error: 'Loading batch not found' });
      auditLog(fastify.db, { userId: request.user!.sub, action: 'deactivate', entityType: 'loading_batch', ipAddress: request.ip, details: { scheduleDate: date } });
      return reply.send({ success: true });
    }
  );

  fastify.delete(
    '/schedules/batch/:date',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (request.user!.role !== 'Administrador') return reply.status(403).send({ error: 'Insufficient permissions' });
      const { date } = request.params as { date: string };
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return reply.status(400).send({ error: 'Invalid batch date' });
      if (!service.removeBatch(fastify.db, date)) return reply.status(404).send({ error: 'Loading batch not found' });
      auditLog(fastify.db, { userId: request.user!.sub, action: 'delete', entityType: 'loading_batch', ipAddress: request.ip, details: { scheduleDate: date } });
      return reply.status(204).send();
    }
  );

  fastify.patch(
    '/schedules/:id/deactivate',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (request.user!.role !== 'Administrador') return reply.status(403).send({ error: 'Insufficient permissions' });
      const { id } = request.params as { id: string };
      const scheduleId = parseInt(id, 10);
      if (isNaN(scheduleId)) return reply.status(400).send({ error: 'Invalid schedule ID' });
      if (!service.deactivate(fastify.db, scheduleId)) return reply.status(404).send({ error: 'Schedule entry not found' });
      auditLog(fastify.db, { userId: request.user!.sub, action: 'deactivate', entityType: 'loading', entityId: scheduleId, ipAddress: request.ip });
      return reply.send({ success: true });
    }
  );

  fastify.get(
    '/schedules',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = ScheduleQuerySchema.safeParse(request.query);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid query params',
          details: parse.error.flatten(),
        });
      }

      const schedules = service.listByDate(fastify.db, parse.data.date, request.user!);
      return reply.send({ schedules });
    }
  );

  fastify.post(
    '/schedules',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateScheduleSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const result = service.create(fastify.db, parse.data, request.user!);

      if ('error' in result) {
        const langHeader = request.headers['accept-language'] || 'pt-BR';
        const isEs = String(langHeader).toLowerCase().startsWith('es');

        let errMsg = result.error;
        if (errMsg === 'scheduleAlreadyExists') {
          errMsg = isEs
            ? 'Ya existe un cronograma para esta fecha en el historial.'
            : 'Já existe um cronograma para esta data no histórico.';
        }
        return reply.status(result.status).send({ error: errMsg, message: errMsg });
      }

      // Audit: schedule created
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'create',
        entityType: 'loading',
        entityId: result.id,
        ipAddress: request.ip,
        details: { scheduleDate: result.schedule_date, timeSlot: result.time_slot },
      });

      return reply.status(201).send({ schedule: result });
    }
  );

  fastify.delete(
    '/schedules/:id',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (!['Administrador', 'Trabalhador'].includes(request.user!.role)) return reply.status(403).send({ error: 'Insufficient permissions' });
      const { id } = request.params as { id: string };
      const scheduleId = parseInt(id, 10);

      if (isNaN(scheduleId)) {
        return reply.status(400).send({ error: 'Invalid schedule ID' });
      }

      const schedule = fastify.db.prepare('SELECT user_id FROM loading_schedules WHERE id = ?').get(scheduleId) as { user_id: number | null } | undefined;
      if (!schedule) return reply.status(404).send({ error: 'Schedule entry not found' });
      if (request.user!.role === 'Trabalhador' && schedule.user_id !== request.user!.sub) {
        return reply.status(403).send({ error: 'Insufficient permissions' });
      }

      const deleted = service.remove(fastify.db, scheduleId);

      if (!deleted) {
        return reply.status(404).send({ error: 'Schedule entry not found' });
      }

      // Audit: schedule deleted
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'delete',
        entityType: 'loading',
        entityId: scheduleId,
        ipAddress: request.ip,
      });

      return reply.status(204).send();
    }
  );

  fastify.patch(
    '/schedules/:id',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const scheduleId = parseInt(id, 10);

      if (isNaN(scheduleId)) {
        return reply.status(400).send({ error: 'Invalid schedule ID' });
      }

      const parse = UpdateScheduleSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const result = service.update(fastify.db, scheduleId, parse.data, request.user!);

      if ('error' in result) {
        return reply.status(result.status).send({ error: result.error });
      }

      // Audit: schedule updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'loading',
        entityId: scheduleId,
        ipAddress: request.ip,
      });

      return reply.send({ schedule: result });
    }
  );

  // ---- Export ----

  fastify.get(
    '/export',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = ExportQuerySchema.safeParse(request.query);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid query params',
          details: parse.error.flatten(),
        });
      }

      const text = generateWhatsAppText(fastify.db, parse.data.date);
      return reply.send({ text });
    }
  );

  // ---- Drivers ----

  fastify.get(
    '/drivers',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const drivers = service.listDrivers(fastify.db);
      return reply.send({ drivers });
    }
  );

  fastify.post(
    '/drivers',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateDriverSchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const driver = service.createDriver(fastify.db, parse.data);
      return reply.status(201).send({ driver });
    }
  );

  // ---- Vehicles ----

  fastify.get(
    '/vehicles',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const vehicles = service.listActiveVehicles(fastify.db);
      return reply.send({ vehicles });
    }
  );

  // ---- Time Slots (reference data) ----

  fastify.get(
    '/time-slots',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const timeSlots = service.getTimeSlots(fastify.db);
      return reply.send({ timeSlots });
    }
  );
}
