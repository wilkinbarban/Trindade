import { test } from 'node:test';
import assert from 'node:assert/strict';
import Fastify from 'fastify';
import cors from '@fastify/cors';
import { corsPolicy } from './cors-policy.js';

test('CORS permits only the approved HTTPS origin without breaking native requests', async (t) => {
  const app = Fastify();
  t.after(() => app.close());
  await app.register(cors, corsPolicy);
  app.get('/probe', async () => ({ ok: true }));
  const allowed = 'https://trindademassas.com.br';
  const response = await app.inject({ url: '/probe', headers: { origin: allowed } });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { ok: true });
  assert.equal(response.headers['access-control-allow-origin'], allowed);
  for (const origin of [
    'https://attacker.example', 'null', 'http://trindademassas.com.br',
    'https://www.trindademassas.com.br', 'https://trindademassas.com.br:8443',
    'https://trindademassas.com.br.attacker.example', 'http://localhost:5173',
  ]) {
    const denied = await app.inject({ url: '/probe', headers: { origin } });
    assert.equal(denied.statusCode, 200, 'CORS is not route authorization');
    assert.deepEqual(denied.json(), { ok: true });
    assert.equal(denied.headers['access-control-allow-origin'], undefined, origin);
  }
  const native = await app.inject({ url: '/probe' });
  assert.equal(native.statusCode, 200);
  assert.deepEqual(native.json(), { ok: true });
  assert.equal(native.headers['access-control-allow-origin'], undefined);
  const preflight = await app.inject({ method: 'OPTIONS', url: '/probe', headers: {
    origin: allowed, 'access-control-request-method': 'POST',
    'access-control-request-headers': 'authorization,content-type',
  } });
  assert.equal(preflight.statusCode, 204);
  assert.equal(preflight.body, '');
  assert.equal(preflight.headers['access-control-allow-origin'], allowed);
  assert.equal(preflight.headers['access-control-allow-headers'], 'authorization,content-type');
});
