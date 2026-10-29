import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { probeAeroConnection } from './aero-connection';
import { basicAuthHeader } from './aero-integration';

const settings = {
  wsUrl: 'http://192.168.1.50:8000/api',
  username: 'wilkin',
  password: 'senha',
};

interface Call {
  url: string;
  init: RequestInit;
}

/** A fetch double that records what it was asked and answers from a queue. */
function stubFetch(...answers: Array<Response | Error>): { calls: Call[]; impl: typeof fetch } {
  const calls: Call[] = [];
  const queue = [...answers];
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    const next = queue.shift();
    if (!next) throw new Error(`unexpected extra request: ${String(input)}`);
    if (next instanceof Error) throw next;
    return next;
  }) as unknown as typeof fetch;
  return { calls, impl };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function abortError(): Error {
  const error = new Error('The operation was aborted.');
  error.name = 'AbortError';
  return error;
}

describe('probeAeroConnection', () => {
  it('refuses to probe an incomplete configuration instead of guessing', async () => {
    const { calls, impl } = stubFetch();
    assert.deepEqual(
      await probeAeroConnection({ settings: { ...settings, wsUrl: '' }, pageProtocol: 'https:', fetchImpl: impl }),
      { kind: 'invalid_config' },
    );
    assert.deepEqual(
      await probeAeroConnection({ settings: { ...settings, password: '' }, pageProtocol: 'https:', fetchImpl: impl }),
      { kind: 'invalid_config' },
    );
    assert.equal(calls.length, 0, 'an invalid configuration must not reach the network');
  });

  it('says the page itself blocks a plain-HTTP target before spending a request', async () => {
    const { calls, impl } = stubFetch();
    const probe = await probeAeroConnection({ settings, pageProtocol: 'https:', fetchImpl: impl });
    assert.deepEqual(probe, { kind: 'blocked', reason: 'mixed_content' });
    assert.equal(calls.length, 0, 'Safari refuses before the request exists, and so does the probe');
  });

  it('reads the documented /usuarios/ answer as the numeric ERP code', async () => {
    const { calls, impl } = stubFetch(jsonResponse({ success: true, data: { CODIGO: 12, SENHA: 'x', NOME: 'WILKIN' } }));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.equal(probe.kind, 'ok');
    if (probe.kind !== 'ok') return;
    assert.equal(probe.userCode, 12);
    assert.equal(probe.userName, 'WILKIN');
    assert.equal(probe.httpStatus, 200);

    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.url, 'http://192.168.1.50:8000/api/usuarios');
    // The factory routes /usuarios/ as POST only: a GET comes back as a 405 from Laravel.
    assert.equal(calls[0]!.init.method, 'POST');
    const headers = calls[0]!.init.headers as Record<string, string>;
    assert.equal(headers.Authorization, basicAuthHeader('wilkin', 'senha'));
    assert.equal(headers.Accept, 'application/json');
  });

  it('reports a refused login with the status the server sent', async () => {
    const { impl } = stubFetch(jsonResponse({ success: false, message: 'Senha ou usuario não conferem' }, 401));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.equal(probe.kind, 'http_error');
    if (probe.kind !== 'http_error') return;
    assert.equal(probe.httpStatus, 401);
    assert.match(probe.message, /Senha ou usuario/);
  });

  it('turns a body that is not the documented answer into a sentence, not a crash', async () => {
    const { impl } = stubFetch(new Response('<html>nao e json</html>', { status: 200 }));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.equal(probe.kind, 'http_error');
    if (probe.kind !== 'http_error') return;
    assert.equal(probe.httpStatus, 200);
    assert.match(probe.message, /Invalid response body/);
    assert.match(probe.snippet, /nao e json/);
  });

  it('distinguishes "the server is there without CORS" from "the network is wrong"', async () => {
    const { calls, impl } = stubFetch(new TypeError('Failed to fetch'), new Response('', { status: 200 }));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.deepEqual(probe, { kind: 'reachable_without_cors', snippet: '' });
    assert.equal(calls.length, 2);
    assert.equal(calls[1]!.init.mode, 'no-cors', 'only the opaque retry can separate CORS from an unreachable host');
    assert.equal(calls[1]!.init.method, 'POST', 'the opaque retry must knock on the same door as the real probe');
  });

  it('reports a network failure when the opaque retry is refused too', async () => {
    const { impl } = stubFetch(new TypeError('Failed to fetch'), new TypeError('Failed to fetch'));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.equal(probe.kind, 'network');
  });

  it('reports a timeout as a timeout, not as a network failure', async () => {
    const { calls, impl } = stubFetch(abortError());
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.deepEqual(probe, { kind: 'timeout' });
    assert.equal(calls.length, 1, 'an abort is not ambiguous, so the opaque retry would be noise');
  });

  it('keeps the quoted body short enough to read in a log line', async () => {
    const long = { success: true, data: { CODIGO: 12, NOME: 'W'.repeat(1000) } };
    const { impl } = stubFetch(jsonResponse(long));
    const probe = await probeAeroConnection({ settings, pageProtocol: 'http:', fetchImpl: impl });

    assert.equal(probe.kind, 'ok');
    if (probe.kind !== 'ok') return;
    assert.ok(probe.snippet.length <= 301, 'the snippet is capped so a log line stays a line');
    assert.ok(probe.snippet.endsWith('…'));
  });
});
