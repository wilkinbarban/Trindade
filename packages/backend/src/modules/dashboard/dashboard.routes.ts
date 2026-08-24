import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { getSaoPauloDateString } from '../../utils/date.js';

interface CountRow {
  count: number;
}

export async function dashboardRoutes(fastify: FastifyInstance) {
  fastify.get(
    '/summary',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const db = fastify.db;
      const today = getSaoPauloDateString();

      const reportsToday = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM reports
           WHERE report_date = ? AND is_active = 1`
        )
        .get(today) as CountRow;

      const latestReport = db
        .prepare(
          `SELECT id FROM reports
           WHERE report_date = ? AND is_active = 1
           ORDER BY created_at DESC
           LIMIT 1`
        )
        .get(today) as { id: number } | undefined;

      const schedulesTomorrow = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM loading_schedules
           WHERE schedule_date = ? AND is_active = 1`
        )
        .get(today) as CountRow;

      const activeUsers = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM users
           WHERE is_active = 1`
        )
        .get() as CountRow;

      const reportsTotal = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM reports
           WHERE is_active = 1`
        )
        .get() as CountRow;

      const schedulesTotal = db
        .prepare(
          `SELECT COUNT(DISTINCT schedule_date) AS count
           FROM loading_schedules
           WHERE is_active = 1`
        )
        .get() as CountRow;

      const higieneDone = db
        .prepare(
          `SELECT COUNT(DISTINCT ri.task_id) AS count
           FROM report_items ri
           JOIN reports r ON ri.report_id = r.id
           JOIN report_tasks rt ON ri.task_id = rt.id
           WHERE r.report_date = ? AND r.is_active = 1 AND rt.category_id = 1 AND ri.checked = 1`
        )
        .get(today) as CountRow;

      const higieneTotal = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM report_tasks
           WHERE category_id = 1 AND is_active = 1`
        )
        .get() as CountRow;

      const recepcionDone = db
        .prepare(
          `SELECT COUNT(DISTINCT ri.task_id) AS count
           FROM report_items ri
           JOIN reports r ON ri.report_id = r.id
           JOIN report_tasks rt ON ri.task_id = rt.id
           WHERE r.report_date = ? AND r.is_active = 1 AND rt.category_id = 4 AND ri.checked = 1`
        )
        .get(today) as CountRow;

      const recepcionTotal = db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM report_tasks
           WHERE category_id = 4 AND is_active = 1`
        )
        .get() as CountRow;

      return reply.send({
        reportsToday: reportsToday.count,
        schedulesTomorrow: schedulesTomorrow.count,
        activeUsers: activeUsers.count,
        reportsTotal: reportsTotal.count,
        schedulesTotal: schedulesTotal.count,
        latestReportId: latestReport?.id || null,
        higieneDone: higieneDone.count,
        higieneTotal: higieneTotal.count,
        recepcionDone: recepcionDone.count,
        recepcionTotal: recepcionTotal.count,
      });
    }
  );
}
