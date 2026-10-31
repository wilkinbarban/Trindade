import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { apiError, MASSAS_ERROR_CODES } from '../../utils/api-error.js';
import { getSaoPauloDateString } from '../../utils/date.js';
import {
  createMassaLaunches,
  deleteMassaLaunch,
  massasLaunchDayView,
  massasLaunchHistory,
  updateMassaLaunch,
} from './massas.service.js';
import {
  MassasLaunchCreateBodySchema,
  MassasLaunchUpdateBodySchema,
} from './massas.schema.js';
import { canImportSeat } from './massas-import-policy.js';
import { resolveSeatOwnerUserId } from './massas-operator.js';
import { massasReportDayView } from './massas-report.service.js';

const dateQuery = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD').optional(),
});

const paramId = z.object({
  id: z.coerce.number().int().positive(),
});

export async function massasRoutes(fastify: FastifyInstance) {
  fastify.get('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = dateQuery.safeParse(request.query);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Data inválida', MASSAS_ERROR_CODES.INVALID_DATE_FORMAT));
    }
    const targetDate = parsed.data.date ?? getSaoPauloDateString();
    return reply.send(massasLaunchDayView(fastify.db, targetDate));
  });

  // The day as the report reads it: launches here plus the tablet production that reconciles with nothing.
  // `/` keeps answering the launches alone, because that screen is about typing, not about counting.
  fastify.get('/report', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = dateQuery.safeParse(request.query);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Data inválida', MASSAS_ERROR_CODES.INVALID_DATE_FORMAT));
    }
    const targetDate = parsed.data.date ?? getSaoPauloDateString();
    return reply.send(massasReportDayView(fastify.db, targetDate));
  });

  fastify.get('/history', { preHandler: [fastify.authenticate] }, async (_request: FastifyRequest, reply: FastifyReply) => {
    return reply.send({ days: massasLaunchHistory(fastify.db) });
  });

  fastify.post('/', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsed = MassasLaunchCreateBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send(apiError('Dados de lançamento inválidos', MASSAS_ERROR_CODES.INVALID_LAUNCH_DATA, parsed.error.format()));
    }

    const { date, product_code, boxes, items, ent_lan: bodyEntLan, operator_name: bodyOperatorName } = parsed.data;
    const targetDate = date ?? getSaoPauloDateString();

    const todayInSaoPaulo = getSaoPauloDateString();
    if (targetDate > todayInSaoPaulo) {
      return reply.status(400).send(apiError('Não é possível lançar em datas futuras', MASSAS_ERROR_CODES.FUTURE_DATE));
    }

    const targetItems = items ?? (product_code && boxes ? [{ product_code, boxes }] : []);

    if (targetItems.length === 0) {
      return reply.status(400).send(apiError('Envie uma lista de itens ou informe produto e caixas', MASSAS_ERROR_CODES.EMPTY_ITEMS));
    }

    // One seat is one product line: a request that names a single seat for several items would store the
    // same seat on lines it cannot belong to, so it is refused instead of guessed.
    if (bodyEntLan !== undefined && targetItems.length > 1) {
      return reply
        .status(400)
        .send(
          apiError(
            'Um único ent_lan não pode descrever vários itens: envie ent_lan no item a que pertence',
            MASSAS_ERROR_CODES.INVALID_LAUNCH_DATA,
          ),
        );
    }

    // The operator is the seat's, not the importer's; without a name that resolves, the importer keeps the
    // launch. It is one decision for the whole request because an import carries one operator's boxes.
    const operatorName = bodyOperatorName ?? targetItems.find((item) => item.operator_name)?.operator_name ?? null;
    const seatItems = targetItems.map((item) => ({
      product_code: item.product_code,
      boxes: item.boxes,
      // A body-level seat matches the single item the rule above allowed.
      ent_lan: item.ent_lan ?? bodyEntLan ?? null,
    }));

    // A worker imports only what the ERP already attributes to them; an administrator imports anybody's.
    // The owner comes from the mirrored seat rather than from the body, so naming somebody else in
    // `operator_name` cannot hand a worker boxes that are not theirs. A seat the mirror never saw has no
    // owner here, and an owner nobody can prove is an administrator's to import.
    // Every seat in the body is checked, not just the first: the array is the unit the API accepts, so one
    // authorized seat at the front must not carry somebody else's seats in behind it. No seat means no
    // import, and a manual launch is the asker's own work that the owner rule never touches.
    // A seat with no proven owner is not a permission problem: it is a seat whose operator Trindade cannot
    // name, and the service answers that with its own code so the screen can offer to create the operator.
    // Only somebody else's seat -- an owner the mirror knows and the asker is not -- is refused here.
    const claimedSeats = seatItems.flatMap((item) => (item.ent_lan === null ? [] : [item.ent_lan]));
    const refusedSeat = claimedSeats.find((seat) => {
      const ownerUserId = resolveSeatOwnerUserId(fastify.db, seat);
      return (
        ownerUserId !== null &&
        !canImportSeat({ ownerUserId, userId: request.user!.sub, isAdmin: request.user?.role === 'Administrador' })
      );
    });
    if (refusedSeat !== undefined) {
      return reply
        .status(403)
        .send(apiError('Este lançamento pertence a outro operário', MASSAS_ERROR_CODES.FORBIDDEN));
    }

    try {
      const created = createMassaLaunches(fastify.db, {
        date: targetDate,
        userId: request.user!.sub,
        operatorName,
        items: seatItems,
      });
      return reply.status(201).send({ created });
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'HISTORY_FROZEN') {
        // The day closed at midnight: no role reopens it, and the answer says so instead of blaming the body.
        return reply
          .status(409)
          .send(
            apiError(
              'O histórico de massas é somente leitura: dias passados não podem ser alterados',
              MASSAS_ERROR_CODES.HISTORY_FROZEN,
            ),
          );
      }
      if (code === 'ALREADY_IMPORTED') {
        return reply
          .status(409)
          .send(apiError('Este lançamento do ERP já foi importado', MASSAS_ERROR_CODES.ALREADY_IMPORTED));
      }
      if (code === 'OPERATOR_NOT_LINKED') {
        return reply
          .status(409)
          .send(
            apiError(
              'O operador deste lançamento do ERP não está vinculado no Trindade: cadastre o operário antes de importar',
              MASSAS_ERROR_CODES.OPERATOR_NOT_LINKED,
            ),
          );
      }
      if (code === 'OPERATOR_INACTIVE') {
        return reply
          .status(409)
          .send(
            apiError(
              'O operador deste lançamento do ERP está inativo no Trindade: reative o operário antes de importar',
              MASSAS_ERROR_CODES.OPERATOR_INACTIVE,
            ),
          );
      }
      // The service's sentence is for the log, not for the operator: the payload carries the Portuguese
      // reason and the code, so a refusal never reaches the screen in English.
      return reply.status(400).send(apiError('Não foi possível registrar o lançamento', MASSAS_ERROR_CODES.CREATE_FAILED));
    }
  });

  fastify.put('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send(apiError('Lançamento inválido', MASSAS_ERROR_CODES.INVALID_ID));
    }

    const parsedBody = MassasLaunchUpdateBodySchema.safeParse(request.body);
    if (!parsedBody.success) {
      return reply.status(400).send(apiError('Quantidade de caixas inválida', MASSAS_ERROR_CODES.INVALID_BOXES, parsedBody.error.format()));
    }

    try {
      const updated = updateMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
        userRole: request.user?.role,
        boxes: parsedBody.data.boxes,
      });
      return reply.send(updated);
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send(apiError('Lançamento não encontrado', MASSAS_ERROR_CODES.NOT_FOUND));
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send(apiError('Este lançamento pertence a outro operário', MASSAS_ERROR_CODES.FORBIDDEN));
      }
      if (code === 'RECONCILED') {
        return reply
          .status(409)
          .send(apiError('Esta produção já foi conciliada com o ERP e não pode ser alterada', MASSAS_ERROR_CODES.RECONCILED));
      }
      if (code === 'HISTORY_FROZEN') {
        return reply
          .status(409)
          .send(apiError('O histórico de massas é somente leitura: dias passados não podem ser alterados', MASSAS_ERROR_CODES.HISTORY_FROZEN));
      }
      return reply.status(400).send(apiError('Não foi possível atualizar o lançamento', MASSAS_ERROR_CODES.UPDATE_FAILED));
    }
  });

  fastify.delete('/:id', { preHandler: [fastify.authenticate] }, async (request: FastifyRequest, reply: FastifyReply) => {
    const parsedParam = paramId.safeParse(request.params);
    if (!parsedParam.success) {
      return reply.status(400).send(apiError('Lançamento inválido', MASSAS_ERROR_CODES.INVALID_ID));
    }

    try {
      deleteMassaLaunch(fastify.db, {
        id: parsedParam.data.id,
        userId: request.user!.sub,
        userRole: request.user?.role,
      });
      return reply.send({ ok: true });
    } catch (error) {
      const code = (error as unknown as { code?: string })?.code;
      if (code === 'NOT_FOUND') {
        return reply.status(404).send(apiError('Lançamento não encontrado', MASSAS_ERROR_CODES.NOT_FOUND));
      }
      if (code === 'FORBIDDEN') {
        return reply.status(403).send(apiError('Este lançamento pertence a outro operário', MASSAS_ERROR_CODES.FORBIDDEN));
      }
      if (code === 'RECONCILED') {
        return reply
          .status(409)
          .send(apiError('Esta produção já foi conciliada com o ERP e não pode ser alterada', MASSAS_ERROR_CODES.RECONCILED));
      }
      if (code === 'HISTORY_FROZEN') {
        return reply
          .status(409)
          .send(apiError('O histórico de massas é somente leitura: dias passados não podem ser alterados', MASSAS_ERROR_CODES.HISTORY_FROZEN));
      }
      return reply.status(400).send(apiError('Não foi possível excluir o lançamento', MASSAS_ERROR_CODES.DELETE_FAILED));
    }
  });
}

