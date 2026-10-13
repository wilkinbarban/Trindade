import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import Fastify from 'fastify';
import multipart from '@fastify/multipart';
import jwt from 'jsonwebtoken';
import { registerApiRoutes } from '../../routes.js';
import { createAuthenticate } from '../auth/auth.middleware.js';
import { closeReportDatabases, setupReportsDb } from './reports-test-helper.js';

test('all real photo download routes prohibit public caching and preserve bytes and authentication', async (t) => {
  const app = Fastify({ logger: false });
  t.after(async () => { await app.close(); closeReportDatabases(); });
  const db = setupReportsDb();
  const photosDir = mkdtempSync(resolve(process.cwd(), '../../.t08-photo-fixtures-'));
  const bytes = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
  writeFileSync(join(photosDir, 'fixture.jpg'), bytes);
  const publicToken = '0123456789abcdef0123456789abcdef';
  db.prepare("INSERT INTO reports (id, user_id, turno, report_date) VALUES (1, 1, 'tarde', '2026-01-01')").run();
  db.prepare(`INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type, public_token)
    VALUES (1, 1, 'fixture.jpg', 4, 'image/jpeg', ?)`).run(publicToken);
  const jwtSecret = 'synthetic-photo-cache-test-secret-32-bytes';
  app.decorate('db', db);
  app.decorate('authenticate', createAuthenticate(jwtSecret));
  await app.register(multipart);
  await registerApiRoutes(app, {
    jwtSecret, refreshTokenTtlDays: 30, sessionStoreAvailable: () => true,
    photosDir, publicBaseUrl: 'https://trindademassas.com.br',
  });
  const token = jwt.sign({ sub: 1, security_version: 1 }, jwtSecret);
  for (const url of [`/p/${publicToken}`, `/api/reports/photos/public/${publicToken}`, '/api/reports/photos/1']) {
    const response = await app.inject({ url, headers: { authorization: `Bearer ${token}` } });
    assert.equal(response.statusCode, 200, url);
    assert.equal(response.headers['content-type'], 'image/jpeg');
    assert.deepEqual(response.rawPayload, bytes);
    assert.equal(response.headers['cache-control'], 'private, no-store', url);
  }
  const denied = await app.inject({ url: '/api/reports/photos/1' });
  assert.equal(denied.statusCode, 401);
  assert.deepEqual(denied.json(), { error: 'Missing or invalid authorization header' });
});
