import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { writeFile, unlink, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { ASSAI_PRODUCTS, NORMAL_PRODUCTS, CreateReportBodySchema, UpdateReportBodySchema, ReportQuerySchema, HistoryQuerySchema } from './reports.schema.js';
import * as query from './reports.query.service.js';
import * as command from './reports.command.service.js';
import * as lifecycle from './reports.lifecycle.service.js';
import { generateWhatsAppText } from './export.service.js';
import { log as auditLog } from '../audit/audit.service.js';

export interface ReportsRouteOptions {
  photosDir: string;
  publicBaseUrl?: string;
}

const MAX_PHOTO_SIZE = 5 * 1024 * 1024;
const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;

function detectImageMime(data: Buffer): string | null {
  if (data.length >= 3 && data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff) return 'image/jpeg';
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
  if (data.length >= 12 && data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
  return null;
}

function extensionForMime(mimeType: string): string {
  if (mimeType === 'image/png') return '.png';
  if (mimeType === 'image/webp') return '.webp';
  return '.jpg';
}

/**
 * Reports module — Fastify plugin.
 *
 * Registered at /api/reports in server.ts.
 * All routes require authentication via `preHandler: [fastify.authenticate]`.
 */
export async function reportsRoutes(fastify: FastifyInstance, opts: ReportsRouteOptions) {
  const { photosDir, publicBaseUrl } = opts;
  // ---- Reference Data (read-only lookups for the builder UI) ----

  fastify.get(
    '/categories',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      const categories = query.getCategories(fastify.db);
      return reply.send({ categories });
    }
  );

  // ---- Product offers ----

  // The lists a client puts in front of an operator, served from here so a second client does not have
  // to duplicate the SPA's constants and drift from them.
  //
  // Not a constraint on what a report may store: the server accepts any product name, and the suite
  // asserts exactly that with names belonging to no list. This endpoint is the offer, and a report's
  // `selectedProducts` stays an array of strings for that reason.
  fastify.get(
    '/products',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      return reply.send({ assai: ASSAI_PRODUCTS, normal: NORMAL_PRODUCTS });
    }
  );

  // ---- Report CRUD ----

  fastify.post(
    '/',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = CreateReportBodySchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const userId = request.user!.sub;
      try {
        const report = await command.createReport(fastify.db, userId, parse.data);

        // Audit: report created
        auditLog(fastify.db, {
          userId,
          action: 'create',
          entityType: 'report',
          entityId: report.id,
          ipAddress: request.ip,
          details: { turno: report.turno },
        });

        return reply.status(201).send({ report });
      } catch (err: any) {
        const langHeader = request.headers['accept-language'] || 'pt-BR';
        const isEs = String(langHeader).toLowerCase().startsWith('es');

        let errMsg = err.message;
        if (errMsg === 'reportAlreadyExists') {
          errMsg = isEs
            ? 'Ya existe un reporte para este turno hoy.'
            : 'Já existe um relatório para este turno hoje.';
        }
        return reply.status(err.statusCode || 400).send({ error: errMsg, message: errMsg });
      }
    }
  );

  fastify.get(
    '/',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = ReportQuerySchema.safeParse(request.query);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid query params',
          details: parse.error.flatten(),
        });
      }

      const reports = query.listReports(fastify.db, parse.data);
      return reply.send({ reports });
    }
  );


  fastify.get(
    '/history',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const parse = HistoryQuerySchema.safeParse(request.query);
      if (!parse.success) {
        return reply.status(400).send({ error: 'Invalid query params', details: parse.error.flatten() });
      }
      const result = lifecycle.listReportHistory(fastify.db, parse.data, request.user!);
      return reply.send(result);
    }
  );

  fastify.patch(
    '/:id/deactivate',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (request.user!.role !== 'Administrador') return reply.status(403).send({ error: 'Insufficient permissions' });
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);
      if (isNaN(reportId)) return reply.status(400).send({ error: 'Invalid report ID' });
      if (!lifecycle.deactivateReport(fastify.db, reportId)) return reply.status(404).send({ error: 'Report not found' });
      auditLog(fastify.db, { userId: request.user!.sub, action: 'deactivate', entityType: 'report', entityId: reportId, ipAddress: request.ip });
      return reply.send({ success: true });
    }
  );

  fastify.delete(
    '/:id',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      if (request.user!.role !== 'Administrador') return reply.status(403).send({ error: 'Insufficient permissions' });
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);
      if (isNaN(reportId)) return reply.status(400).send({ error: 'Invalid report ID' });
      const photos = lifecycle.deleteReportTransactionally(fastify.db, reportId);
      if (!photos) return reply.status(404).send({ error: 'Report not found' });
      auditLog(fastify.db, { userId: request.user!.sub, action: 'delete', entityType: 'report', entityId: reportId, ipAddress: request.ip });
      for (const photo of photos) {
        const filePath = join(photosDir, photo.file_path);
        try { if (existsSync(filePath)) await unlink(filePath); } catch { fastify.log.warn(`Failed to delete photo file: ${filePath}`); }
      }
      return reply.status(204).send();
    }
  );

  fastify.get(
    '/:id',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);

      if (isNaN(reportId)) {
        return reply.status(400).send({ error: 'Invalid report ID' });
      }

      const report = query.getReport(fastify.db, reportId, request.user!);

      if (!report) {
        return reply.status(404).send({ error: 'Report not found' });
      }

      return reply.send({ report });
    }
  );

  fastify.patch(
    '/:id',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);

      if (isNaN(reportId)) {
        return reply.status(400).send({ error: 'Invalid report ID' });
      }

      const parse = UpdateReportBodySchema.safeParse(request.body);

      if (!parse.success) {
        return reply.status(400).send({
          error: 'Invalid input',
          details: parse.error.flatten(),
        });
      }

      const result = await command.updateReport(fastify.db, reportId, parse.data, request.user!);

      if ('error' in result) {
        return reply.status(result.status).send({ error: result.error });
      }

      // Audit: report updated
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'update',
        entityType: 'report',
        entityId: reportId,
        ipAddress: request.ip,
      });

      return reply.send({ report: result });
    }
  );

  fastify.get(
    '/:id/export',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);

      if (isNaN(reportId)) {
        return reply.status(400).send({ error: 'Invalid report ID' });
      }

      const reportExists = fastify.db
        .prepare('SELECT 1 FROM reports WHERE id = ?')
        .get(reportId);
      if (!reportExists) {
        return reply.status(404).send({ error: 'Report not found' });
      }

      const expectedCount = fastify.db
        .prepare(
          `SELECT COUNT(*) AS count
           FROM report_tasks rt
           JOIN report_categories rc ON rt.category_id = rc.id
           WHERE rc.category_type = 'temperature'
             AND rc.is_active = 1
             AND rt.is_active = 1`
        )
        .get() as { count: number };

      const tempCount = fastify.db
        .prepare('SELECT COUNT(*) AS count FROM report_temperatures WHERE report_id = ?')
        .get(reportId) as { count: number } | undefined;

      if (!tempCount || tempCount.count < expectedCount.count) {
        return reply.status(400).send({ error: 'Falta colocar as temperaturas no relatório.' });
      }

      const text = generateWhatsAppText(fastify.db, reportId, publicBaseUrl)

      if (text === null) {
        return reply.status(404).send({ error: 'Report not found' });
      }

      return reply.send({ text });
    }
  );

  // ---- Turno detection helper (exposed for frontend) ----

  fastify.get(
    '/turno',
    { preHandler: [fastify.authenticate] },
    async (_request: FastifyRequest, reply: FastifyReply) => {
      return reply.send({ turno: query.detectTurno() });
    }
  );

  // ---- Photo Management ----


  // Serve a public photo file by token for WhatsApp links
  fastify.get(
    '/photos/public/:token',
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { token } = request.params as { token: string };
      if (!token || token.length < 16) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      const photo = lifecycle.getPhotoByPublicToken(fastify.db, token);
      if (!photo) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      const filePath = join(photosDir, photo.file_path);
      if (!existsSync(filePath)) {
        return reply.status(404).send({ error: 'Photo file not found on disk' });
      }

      try {
        const data = await readFile(filePath);
        return reply
          .header('Content-Type', photo.mime_type)
          .header('Cache-Control', 'public, max-age=3600')
          .send(data);
      } catch {
        return reply.status(500).send({ error: 'Failed to read photo file' });
      }
    }
  );

  // Serve a photo file
  fastify.get(
    '/photos/:photoId',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { photoId } = request.params as { photoId: string };
      const id = parseInt(photoId, 10);

      if (isNaN(id)) {
        return reply.status(400).send({ error: 'Invalid photo ID' });
      }

      const photo = lifecycle.getPhotoById(fastify.db, id);

      if (!photo) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      const filePath = join(photosDir, photo.file_path);

      if (!existsSync(filePath)) {
        return reply.status(404).send({ error: 'Photo file not found on disk' });
      }

      try {
        const data = await readFile(filePath);
        return reply
          .header('Content-Type', photo.mime_type)
          .header('Cache-Control', 'public, max-age=3600')
          .send(data);
      } catch {
        return reply.status(500).send({ error: 'Failed to read photo file' });
      }
    }
  );

  // Delete a photo
  fastify.delete(
    '/photos/:photoId',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { photoId } = request.params as { photoId: string };
      const id = parseInt(photoId, 10);

      if (isNaN(id)) {
        return reply.status(400).send({ error: 'Invalid photo ID' });
      }

      const photo = lifecycle.getPhotoById(fastify.db, id);

      if (!photo) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      // Enforce edit-window
      if (lifecycle.isReportReadOnly(fastify.db, photo.report_id, request.user!)) {
        return reply.status(403).send({ error: 'Registro somente leitura. Fotos só podem ser alteradas pelo criador no dia atual ou no dia anterior.' });
      }

      const filePath = join(photosDir, photo.file_path);

      // Remove from DB first, then delete file (best-effort file cleanup)
      const deletedPhoto = lifecycle.deletePhoto(fastify.db, id, request.user!);
      if (deletedPhoto && 'error' in deletedPhoto) {
        return reply.status(deletedPhoto.status).send({ error: deletedPhoto.error });
      }

      // Audit: photo deleted
      if (deletedPhoto) {
        auditLog(fastify.db, {
          userId: request.user!.sub,
          action: 'delete',
          entityType: 'photo',
          entityId: id,
          ipAddress: request.ip,
          details: { reportId: deletedPhoto.report_id, fileName: deletedPhoto.file_path },
        });
      }

      try {
        if (existsSync(filePath)) {
          await unlink(filePath);
        }
      } catch {
        fastify.log.warn(`Failed to delete photo file: ${filePath}`);
      }

      return reply.send({ success: true });
    }
  );

  // Upload a photo to a report
  fastify.post(
    '/:id/photos',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);

      if (isNaN(reportId)) {
        return reply.status(400).send({ error: 'Invalid report ID' });
      }

      // Check report exists
      const report = query.getReport(fastify.db, reportId, request.user!);
      if (!report) {
        return reply.status(404).send({ error: 'Report not found' });
      }

      // Enforce edit-window
      if (lifecycle.isReportReadOnly(fastify.db, reportId, request.user!)) {
        return reply.status(403).send({ error: 'Registro somente leitura. Fotos só podem ser alteradas pelo criador no dia atual ou no dia anterior.' });
      }

      // Parse multipart file
      let data: Buffer | undefined;
      let mimeType: string | undefined;
      let fileTooLarge = false;

      try {
        const file = await request.file();

        if (!file) {
          return reply.status(400).send({ error: 'No file uploaded' });
        }

        const chunks: Buffer[] = [];
        for await (const chunk of file.file) {
          chunks.push(chunk);
        }
        fileTooLarge = Boolean((file.file as any).truncated);
        data = Buffer.concat(chunks);
        mimeType = file.mimetype;
      } catch (err: any) {
        // @fastify/multipart throws when file exceeds size limit
        if (err?.code === 'FST_REQ_FILE_TOO_LARGE' || err?.statusCode === 413) {
          return reply.status(413).send({ error: 'File too large. Maximum size is 5MB.' });
        }
        fastify.log.error(err);
        return reply.status(400).send({ error: 'Failed to process uploaded file' });
      }

      if (fileTooLarge) {
        return reply.status(413).send({ error: 'Arquivo muito grande. O tamanho máximo é 5MB.' });
      }

      if (!data || data.length === 0) {
        return reply.status(400).send({ error: 'Empty file' });
      }

      // Validate MIME declaration and magic bytes. Browser MIME alone is not trusted.
      if (!mimeType || !ALLOWED_MIME_TYPES.includes(mimeType as any)) {
        return reply.status(415).send({ error: 'Formato inválido. Envie apenas imagens JPEG, PNG ou WebP.' });
      }

      if (data.length > MAX_PHOTO_SIZE) {
        return reply.status(413).send({ error: 'Arquivo muito grande. O tamanho máximo é 5MB.' });
      }

      const detectedMimeType = detectImageMime(data);
      if (!detectedMimeType || detectedMimeType !== mimeType) {
        return reply.status(415).send({ error: 'Arquivo inválido. O conteúdo não corresponde a uma imagem JPEG, PNG ou WebP.' });
      }

      if (lifecycle.countPhotosByReport(fastify.db, reportId) >= lifecycle.MAX_REPORT_PHOTOS) {
        return reply.status(409).send({ error: 'Limite de 5 fotos por relatório atingido.' });
      }

      // Generate unique filename with extension strictly mapped from detected mimeType to prevent path traversal
      const ext = extensionForMime(detectedMimeType);
      const storedName = `${randomUUID()}${ext}`;

      // Write to disk
      try {
        await writeFile(join(photosDir, storedName), data);
      } catch (err) {
        fastify.log.error(err);
        return reply.status(500).send({ error: 'Failed to save file' });
      }

      // Store metadata in DB
      let photo: lifecycle.ReportPhoto;
      try {
        photo = lifecycle.savePhoto(fastify.db, reportId, storedName, data.length, mimeType, request.user!);
      } catch (err: any) {
        try {
          const writtenPath = join(photosDir, storedName);
          if (existsSync(writtenPath)) await unlink(writtenPath);
        } catch {
          fastify.log.warn(`Failed to delete orphan photo file: ${storedName}`);
        }
        if (err?.status) return reply.status(err.status).send({ error: err.message });
        throw err;
      }

      // Audit: photo uploaded
      auditLog(fastify.db, {
        userId: request.user!.sub,
        action: 'upload',
        entityType: 'photo',
        entityId: photo.id,
        ipAddress: request.ip,
        details: { reportId, fileName: storedName, fileSize: data.length },
      });

      return reply.status(201).send({ photo });
    }
  );

  // List photos for a report
  fastify.get(
    '/:id/photos',
    { preHandler: [fastify.authenticate] },
    async (request: FastifyRequest, reply: FastifyReply) => {
      const { id } = request.params as { id: string };
      const reportId = parseInt(id, 10);

      if (isNaN(reportId)) {
        return reply.status(400).send({ error: 'Invalid report ID' });
      }

      // Check report exists
      const report = query.getReport(fastify.db, reportId, request.user!);
      if (!report) {
        return reply.status(404).send({ error: 'Report not found' });
      }

      const photos = lifecycle.getPhotosByReport(fastify.db, reportId, publicBaseUrl);
      return reply.send({ photos });
    }
  );
}
