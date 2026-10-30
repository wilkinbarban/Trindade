import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { mkdtempSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { buildTestApp, type TestFixtures } from '../../test-helper.js';
import { REFERENCE_DATASET } from '../../db/reference-data.js';
import { AERO_LOG_FILE } from './aero.log.service.js';

describe('Aero integration routes', () => {
  let originalLogDir: string | undefined;
  let tempLogDir: string;
  let app: import('fastify').FastifyInstance;
  let db: import('better-sqlite3').Database;
  let fixtures: TestFixtures;
  let wilkinId: number;
  let wilkinToken: string;
  let adminToken: string;
  let workerToken: string;

  before(async () => {
    originalLogDir = process.env.AERO_LOG_DIR;
    tempLogDir = mkdtempSync(join(tmpdir(), 'aero-route-test-'));
    process.env.AERO_LOG_DIR = tempLogDir;

    const built = await buildTestApp();
    app = built.app;
    db = built.db;
    fixtures = built.fixtures;

    const adminRoleId = REFERENCE_DATASET.roles.find((r) => r.name === 'Administrador')!.id;
    const passwordHash = await bcrypt.hash('wilkin-pass', 4);
    const insertResult = db.prepare(
      'INSERT INTO users (username, password_hash, display_name, role_id, is_active, security_version) VALUES (?, ?, ?, ?, 1, 1)'
    ).run('wilkin', passwordHash, 'Wilkin Admin', adminRoleId);
    wilkinId = Number(insertResult.lastInsertRowid);

    wilkinToken = jwt.sign(
      { sub: wilkinId, username: 'wilkin', role: 'Administrador', security_version: 1 },
      fixtures.jwtSecret
    );

    adminToken = jwt.sign(
      { sub: fixtures.admin.id, username: fixtures.admin.username, role: 'Administrador', security_version: 1 },
      fixtures.jwtSecret
    );

    workerToken = jwt.sign(
      { sub: fixtures.worker.id, username: fixtures.worker.username, role: 'Trabalhador', security_version: 1 },
      fixtures.jwtSecret
    );
  });

  after(async () => {
    await app.close();
    db.close();
    if (originalLogDir !== undefined) {
      process.env.AERO_LOG_DIR = originalLogDir;
    } else {
      delete process.env.AERO_LOG_DIR;
    }
    rmSync(tempLogDir, { recursive: true, force: true });
  });

  it('answers 401 without a token', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      payload: {
        event: 'test_connection',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
      },
    });
    assert.equal(res.statusCode, 401);
  });

  it('answers 200 for an admin who is not the owner, stamping their own identity', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        event: 'test_connection',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        note: 'client=web · via=bridge',
      },
    });
    assert.equal(res.statusCode, 200);
    assert.deepStrictEqual(JSON.parse(res.body), { ok: true });

    const logFilePath = join(tempLogDir, AERO_LOG_FILE);
    assert.ok(existsSync(logFilePath), 'log file must be created on disk');
    const lines = readFileSync(logFilePath, 'utf-8').split('\n').filter(Boolean);
    const lastLine = JSON.parse(lines[lines.length - 1]);
    assert.equal(lastLine.user, fixtures.admin.username);
    assert.equal(lastLine.role, 'Administrador');
    assert.equal(lastLine.note, 'client=web · via=bridge');
  });

  it('answers 200 for a worker, stamping the true username and role of the operator who dispatched', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        event: 'launch_sync',
        phase: 'result',
        destinationUrl: 'http://192.168.1.50:8000/api/entradasitem',
        httpStatus: 200,
        launchId: 42,
        note: 'client=android · via=native',
      },
    });
    assert.equal(res.statusCode, 200);
    assert.deepStrictEqual(JSON.parse(res.body), { ok: true });

    const logFilePath = join(tempLogDir, AERO_LOG_FILE);
    const lines = readFileSync(logFilePath, 'utf-8').split('\n').filter(Boolean);
    const lastLine = JSON.parse(lines[lines.length - 1]);
    assert.equal(lastLine.user, fixtures.worker.username);
    assert.equal(lastLine.role, 'Trabalhador');
    assert.equal(lastLine.event, 'launch_sync');
    assert.equal(lastLine.phase, 'result');
    assert.equal(lastLine.launchId, 42);
    assert.equal(lastLine.note, 'client=android · via=native');
  });

  it('answers 200 for wilkin with role Administrador, appending line with user wilkin, event, and httpStatus', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      headers: { authorization: `Bearer ${wilkinToken}` },
      payload: {
        event: 'test_connection',
        phase: 'result',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: { test: true },
        response: '{"success":true}',
        note: 'connectivity ok',
      },
    });

    assert.equal(res.statusCode, 200);
    assert.deepStrictEqual(JSON.parse(res.body), { ok: true });

    const logFilePath = join(tempLogDir, AERO_LOG_FILE);
    assert.ok(existsSync(logFilePath), 'log file must be created on disk');
    const content = readFileSync(logFilePath, 'utf-8');
    const lines = content.split('\n').filter(Boolean);
    assert.ok(lines.length >= 1);

    const lastLine = JSON.parse(lines[lines.length - 1]);
    assert.equal(lastLine.user, 'wilkin');
    assert.equal(lastLine.role, 'Administrador');
    assert.equal(lastLine.event, 'test_connection');
    assert.equal(lastLine.phase, 'result');
    assert.equal(lastLine.httpStatus, 200);
    assert.equal(lastLine.destinationUrl, 'http://192.168.1.50:8000/api/usuarios/');
    assert.equal(lastLine.note, 'connectivity ok');
  });

  it('answers 400 with code INVALID_AERO_LOG for an unknown event', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      headers: { authorization: `Bearer ${wilkinToken}` },
      payload: {
        event: 'unknown_nonexistent_event',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
      },
    });

    assert.equal(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.equal(body.code, 'INVALID_AERO_LOG');
    // The screen that logs an event is the operator's, so the sentence is Portuguese too.
    assert.equal(body.error, 'Evento de integração inválido');
  });

  it('answers 400 with code INVALID_AERO_LOG for a body that smuggles an authorization field', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/integration/aero/log',
      headers: { authorization: `Bearer ${wilkinToken}` },
      payload: {
        event: 'test_connection',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        authorization: 'Basic dXNlcjpzZWNyZXQ=',
      },
    });

    assert.equal(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.equal(body.code, 'INVALID_AERO_LOG');
  });
});
