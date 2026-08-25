import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { authRoutes } from '../auth/auth.routes.js';
import { createAuthenticate } from '../auth/auth.middleware.js';
import { loadingRoutes } from './loading.routes.js';
import { REFERENCE_DATASET, verifyReferencePrerequisites } from '../../db/reference-data.js';

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

export async function buildLoadingTestApp(): Promise<{
  app: import('fastify').FastifyInstance;
  db: import('better-sqlite3').Database;
  fixtures: TestFixtures;
}> {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  verifyReferencePrerequisites(db);
  db.exec(`
    INSERT INTO drivers (id, name, driver_type) VALUES (4, 'André', 'fletero');
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
  app.decorate('db', db);
  app.decorate('authenticate', createAuthenticate(fixtures.jwtSecret));
  await app.register(authRoutes, { prefix: '/api/auth', jwtSecret: fixtures.jwtSecret });
  await app.register(loadingRoutes, { prefix: '/api/loading' });
  await app.ready();
  return { app, db, fixtures };
}
