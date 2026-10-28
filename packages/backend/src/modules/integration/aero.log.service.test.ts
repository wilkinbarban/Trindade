import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { mkdtempSync, rmSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  AERO_LOG_FILE,
  AERO_LOG_MAX_BYTES,
  type AeroLogEntry,
  appendAeroLog,
  resolveAeroLogDir,
} from './aero.log.service.js';

describe('Aero integration log service', () => {
  it('resolves aero log directory honoring AERO_LOG_DIR and falling back to default', () => {
    assert.equal(resolveAeroLogDir({ AERO_LOG_DIR: '/var/log/aero' }), '/var/log/aero');
    assert.equal(resolveAeroLogDir({ AERO_LOG_DIR: '  /custom/logs  ' }), '/custom/logs');
    assert.equal(resolveAeroLogDir({ AERO_LOG_DIR: '   ' }), join(process.cwd(), 'logs'));
    assert.equal(resolveAeroLogDir({}), join(process.cwd(), 'logs'));
    assert.equal(resolveAeroLogDir(), resolveAeroLogDir(process.env));
  });

  it('writes one JSON line with expected fields formatted for tail -f and jq', () => {
    const tempDir = mkdtempSync(join(tmpdir(), 'aero-log-test-single-'));
    try {
      const entry: AeroLogEntry = {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: 'requested',
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: { test: true },
        response: '{"success":true}',
        error: null,
        launchId: null,
        note: 'connectivity test ok',
      };

      const returnedPath = appendAeroLog(entry, tempDir);
      assert.equal(returnedPath, join(tempDir, AERO_LOG_FILE));
      assert.ok(existsSync(returnedPath));

      const fileContent = readFileSync(returnedPath, 'utf-8');
      assert.ok(fileContent.endsWith('\n'), 'line must terminate with a newline');
      const lines = fileContent.split('\n').filter(Boolean);
      assert.equal(lines.length, 1);

      const parsed = JSON.parse(lines[0]);
      assert.deepStrictEqual(parsed, {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: 'requested',
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: { test: true },
        response: '{"success":true}',
        error: null,
        launchId: null,
        note: 'connectivity test ok',
      });
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it('appends and preserves previous lines across multiple writes', () => {
    const tempDir = mkdtempSync(join(tmpdir(), 'aero-log-test-multi-'));
    try {
      const entry1: AeroLogEntry = {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: 'requested',
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: null,
        response: null,
        error: null,
        launchId: null,
        note: 'first call',
      };
      const entry2: AeroLogEntry = {
        ts: '2026-10-27T12:01:00.000Z',
        event: 'launch_sync',
        phase: 'result',
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/entradasitem',
        httpStatus: 200,
        payload: { PRO_COD: '1001' },
        response: '{"success":true}',
        error: null,
        launchId: 42,
        note: 'second call',
      };

      appendAeroLog(entry1, tempDir);
      appendAeroLog(entry2, tempDir);

      const filePath = join(tempDir, AERO_LOG_FILE);
      const lines = readFileSync(filePath, 'utf-8').split('\n').filter(Boolean);
      assert.equal(lines.length, 2);

      const parsed1 = JSON.parse(lines[0]);
      const parsed2 = JSON.parse(lines[1]);
      assert.equal(parsed1.note, 'first call');
      assert.equal(parsed2.note, 'second call');
      assert.equal(parsed2.launchId, 42);
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it('rotates to .1 when the log reaches or exceeds AERO_LOG_MAX_BYTES (one generation only)', () => {
    const tempDir = mkdtempSync(join(tmpdir(), 'aero-log-test-rot-'));
    try {
      const logPath = join(tempDir, AERO_LOG_FILE);
      const rotatedPath = `${logPath}.1`;

      // Seed file right at the threshold
      writeFileSync(logPath, Buffer.alloc(AERO_LOG_MAX_BYTES, 'a'));
      assert.equal(existsSync(logPath), true);
      assert.equal(existsSync(rotatedPath), false);

      const entry: AeroLogEntry = {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: null,
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: null,
        response: null,
        error: null,
        launchId: null,
        note: 'after rotation',
      };

      appendAeroLog(entry, tempDir);

      // Previous file must have rotated to .1
      assert.ok(existsSync(rotatedPath), 'rotated .1 file must exist');
      const rotatedContent = readFileSync(rotatedPath);
      assert.equal(rotatedContent.length, AERO_LOG_MAX_BYTES);

      // Active file must have only the new entry
      const activeContent = readFileSync(logPath, 'utf-8');
      const lines = activeContent.split('\n').filter(Boolean);
      assert.equal(lines.length, 1);
      assert.equal(JSON.parse(lines[0]).note, 'after rotation');

      // Second rotation overwrites .1 without creating .2
      writeFileSync(logPath, Buffer.alloc(AERO_LOG_MAX_BYTES + 10, 'b'));
      entry.note = 'second rotation';
      appendAeroLog(entry, tempDir);

      assert.equal(existsSync(`${logPath}.2`), false, 'must not create a second generation');
      assert.equal(readFileSync(rotatedPath).length, AERO_LOG_MAX_BYTES + 10);
      const linesAfterSecond = readFileSync(logPath, 'utf-8').split('\n').filter(Boolean);
      assert.equal(linesAfterSecond.length, 1);
      assert.equal(JSON.parse(linesAfterSecond[0]).note, 'second rotation');
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it('truncates payload over 4096 and response over 2048 with the truncation marker', () => {
    const tempDir = mkdtempSync(join(tmpdir(), 'aero-log-test-trunc-'));
    try {
      const longPayloadString = 'p'.repeat(5000);
      const longResponseString = 'r'.repeat(3000);

      const entry: AeroLogEntry = {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: null,
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: longPayloadString,
        response: longResponseString,
        error: null,
        launchId: null,
        note: null,
      };

      appendAeroLog(entry, tempDir);

      const logPath = join(tempDir, AERO_LOG_FILE);
      const lines = readFileSync(logPath, 'utf-8').split('\n').filter(Boolean);
      const parsed = JSON.parse(lines[0]);

      // Payload check
      assert.ok(typeof parsed.payload === 'string');
      assert.ok(parsed.payload.includes('…[truncated]'));
      assert.ok(parsed.payload.startsWith('p'.repeat(100)));

      // Response check
      assert.ok(typeof parsed.response === 'string');
      assert.ok(parsed.response.includes('…[truncated]'));
      assert.ok(parsed.response.startsWith('r'.repeat(100)));

      // Also verify object payload truncation when serialized
      const longObjectPayload = { data: 'x'.repeat(5000) };
      entry.payload = longObjectPayload;
      entry.response = 'short response';
      appendAeroLog(entry, tempDir);

      const lines2 = readFileSync(logPath, 'utf-8').split('\n').filter(Boolean);
      const parsed2 = JSON.parse(lines2[1]);
      assert.ok(typeof parsed2.payload === 'string');
      assert.ok(parsed2.payload.includes('…[truncated]'));
      assert.equal(parsed2.response, 'short response');
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it('strips authorization and credential fields from payload', () => {
    const tempDir = mkdtempSync(join(tmpdir(), 'aero-log-test-cred-'));
    try {
      const entry: AeroLogEntry = {
        ts: '2026-10-27T12:00:00.000Z',
        event: 'test_connection',
        phase: null,
        user: 'wilkin',
        role: 'Administrador',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        httpStatus: 200,
        payload: {
          authorization: 'Basic dXNlcjpzZWNyZXQ=',
          Authorization: 'Bearer token-123',
          password: 'super-secret-password',
          senha: 'senha-secreta',
          safeField: 'visible-value',
        },
        response: null,
        error: null,
        launchId: null,
        note: null,
      };

      appendAeroLog(entry, tempDir);

      const logPath = join(tempDir, AERO_LOG_FILE);
      const content = readFileSync(logPath, 'utf-8');
      assert.ok(!content.includes('Basic dXNlcjpzZWNyZXQ='));
      assert.ok(!content.includes('Bearer token-123'));
      assert.ok(!content.includes('super-secret-password'));
      assert.ok(!content.includes('senha-secreta'));

      const parsed = JSON.parse(content.trim());
      assert.equal(parsed.payload.safeField, 'visible-value');
      assert.equal(parsed.payload.authorization, undefined);
      assert.equal(parsed.payload.Authorization, undefined);
      assert.equal(parsed.payload.password, undefined);
      assert.equal(parsed.payload.senha, undefined);
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });
});
