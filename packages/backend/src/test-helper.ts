import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import Fastify from 'fastify';
import multipart from '@fastify/multipart';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { randomUUID } from 'node:crypto';

import { createAuthenticate } from './modules/auth/auth.middleware.js';
import { authRoutes } from './modules/auth/auth.routes.js';
import { bootstrapRoutes } from './modules/auth/bootstrap.routes.js';
import { dashboardRoutes } from './modules/dashboard/dashboard.routes.js';
import { reportsRoutes } from './modules/reports/reports.routes.js';
import { loadingRoutes } from './modules/loading/loading.routes.js';
import { adminRoutes } from './modules/admin/admin.routes.js';
import { auditRoutes } from './modules/audit/audit.routes.js';
import { getPhotoByPublicToken } from './modules/reports/reports.lifecycle.service.js';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from './db/reference-data.js';

/**
 * Build a Fastify instance backed by an in-memory SQLite database
 * with the full schema and seed data loaded.
 */
export interface TestIdentity {
  id: number;
  username: string;
  password: string;
  role: 'Administrador' | 'Trabalhador';
}

export interface TestFixtures {
  admin: TestIdentity;
  worker: TestIdentity;
  jwtSecret: string;
}

export function buildTemperatureReadings(db: import('better-sqlite3').Database) {
  const tasks = db.prepare(
    `SELECT rt.name_pt, rt.temperature_readings
     FROM report_tasks rt
     JOIN report_categories rc ON rc.id = rt.category_id
     WHERE rc.category_type = 'temperature' AND rc.is_active = 1 AND rt.is_active = 1
     ORDER BY rt.id`
  ).all() as { name_pt: string; temperature_readings: number }[];
  assertTemperatureTasks(tasks);
  return tasks.flatMap((task) => Array.from({ length: task.temperature_readings }, (_, index) => ({
    location: task.name_pt,
    readingIndex: index + 1,
    value: -18 - index,
  })));
}

function assertTemperatureTasks(tasks: { name_pt: string }[]): asserts tasks is { name_pt: string; temperature_readings: number }[] {
  if (tasks.length === 0) throw new Error('Test fixture requires at least one active temperature task');
}

export async function buildTestApp(): Promise<{
  app: import('fastify').FastifyInstance;
  db: import('better-sqlite3').Database;
  photosDir: string;
  fixtures: TestFixtures;
}> {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');

  // Load schema + seed
  const schemaPath = join(import.meta.dirname, 'db/schema.sql');
  const seedPath = join(import.meta.dirname, 'db/seed.sql');
  db.exec(readFileSync(schemaPath, 'utf-8'));
  db.exec(readFileSync(seedPath, 'utf-8'));
  verifyReferencePrerequisites(db);
  db.exec(`
    INSERT INTO drivers (id, name, driver_type) VALUES (4, 'André', 'fletero');
    INSERT INTO drivers (id, name, driver_type) VALUES (5, 'Fixture Casa', 'casa');
    INSERT INTO drivers (id, name, driver_type) VALUES (6, 'Fixture Fletero', 'fletero');
    INSERT INTO vehicles (id, description, license_plate) VALUES (2, 'Casa BDG', 'BDG');
  `);

  const fixtures: TestFixtures = {
    admin: { id: 0, username: `admin-${randomUUID()}`, password: randomUUID(), role: 'Administrador' },
    worker: { id: 0, username: `worker-${randomUUID()}`, password: randomUUID(), role: 'Trabalhador' },
    jwtSecret: randomUUID(),
  };
  const insertUser = db.prepare(
    'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)'
  );
  for (const identity of [fixtures.admin, fixtures.worker]) {
    const result = insertUser.run(
      identity.username,
      await bcrypt.hash(identity.password, 4),
      identity.username,
      REFERENCE_DATASET.roles.find((role) => role.name === identity.role)!.id
    );
    identity.id = Number(result.lastInsertRowid);
  }

  const app = Fastify({ logger: false });

  // Register multipart with 5MB limit
  await app.register(multipart, {
    limits: {
      fileSize: 5 * 1024 * 1024,
    },
  });

  // Create temp directory for photo storage in tests
  const photosDir = join(tmpdir(), `trindade-test-photos-${Date.now()}`);
  mkdirSync(photosDir, { recursive: true });

  // Decorate at top level (NOT inside plugins — Fastify encapsulation prevents propagation)
  app.decorate('db', db);
  app.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));

  // Register routes
  await app.register(authRoutes, { prefix: '/api/auth', jwtSecret: fixtures.jwtSecret });
  await app.register(bootstrapRoutes, { prefix: '/api/auth' });
  await app.register(dashboardRoutes, { prefix: '/api/dashboard' });
  await app.register(reportsRoutes, { prefix: '/api/reports', photosDir, publicBaseUrl: 'https://example.test' });
  await app.register(loadingRoutes, { prefix: '/api/loading' });
  await app.register(adminRoutes, { prefix: '/api/admin' });
  await app.register(auditRoutes, { prefix: '/api/admin' });


// Authenticated user options for history filters
app.get('/api/users/options', { preHandler: [app.authenticate] }, async (_request, reply) => {
  const users = app.db
    .prepare('SELECT id, display_name FROM users WHERE is_active = 1 ORDER BY display_name ASC')
    .all();
  return reply.send({ users });
});

  // Shortened route for serving public report photos (e.g. for WhatsApp copy-paste links)
  app.get(
    '/p/:token',
    async (request, reply) => {
      const { token } = request.params as { token: string };
      if (!token || token.length < 8) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      const photo = getPhotoByPublicToken(app.db, token);
      if (!photo) {
        return reply.status(404).send({ error: 'Photo not found' });
      }

      const filePath = join(photosDir, photo.file_path);
      if (!existsSync(filePath)) {
        return reply.status(404).send({ error: 'Photo file not found on disk' });
      }

      const contentType = photo.mime_type || 'image/jpeg';
      reply.header('Content-Type', contentType);
      reply.header('Cache-Control', 'public, max-age=31536000, immutable');

      const buffer = await import('node:fs/promises').then(fs => fs.readFile(filePath));
      return reply.send(buffer);
    }
  );

  app.get('/api/health', async () => ({ status: 'ok', timestamp: new Date().toISOString() }));

  // Register a test-only admin route for role guard verification
  const { requireRole } = await import('./modules/auth/auth.middleware.js');
  app.get(
    '/__test_admin_only',
    { preHandler: [app.authenticate, requireRole('Administrador')] },
    async () => 'ok'
  );

  app.get('/__test_fixtures', async () => ({
    admin: fixtures.admin,
    worker: fixtures.worker,
  }));

  await app.ready();

  return { app, db, photosDir, fixtures };
}
