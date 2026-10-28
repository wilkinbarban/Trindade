import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

class MockStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string): string | null {
    return this.store.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }
  removeItem(key: string): void {
    this.store.delete(key);
  }
  key(index: number): string | null {
    return Array.from(this.store.keys())[index] ?? null;
  }
}

globalThis.localStorage = new MockStorage();
globalThis.sessionStorage = new MockStorage();
globalThis.window = {
  location: { pathname: '/massas-launch', href: '/massas-launch' },
  dispatchEvent: () => true,
  addEventListener: () => {},
  removeEventListener: () => {},
} as any;

const { postAeroAuditEvent } = await import('./aero-audit');

interface Call {
  url: string;
  init: RequestInit;
}

function stubFetch(answer: Response | Error): { calls: Call[]; restore: () => void } {
  const calls: Call[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    if (answer instanceof Error) throw answer;
    return answer;
  }) as unknown as typeof fetch;
  return { calls, restore: () => (globalThis.fetch = original) };
}

describe('postAeroAuditEvent', () => {
  test('posts the event to the integration log route the server serves', async () => {
    const stub = stubFetch(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    try {
      const kept = await postAeroAuditEvent({
        event: 'launch_sync',
        phase: 'requested',
        destinationUrl: 'http://192.168.1.50:8000/api/entradasitem',
        httpStatus: null,
        payload: { PRO_COD: '5000', PRO_QTD: 20 },
      });

      assert.equal(kept, true);
      assert.equal(stub.calls.length, 1);
      assert.equal(stub.calls[0]!.url, '/api/integration/aero/log');
      assert.equal(stub.calls[0]!.init.method, 'POST');
      assert.deepEqual(JSON.parse(String(stub.calls[0]!.init.body)), {
        event: 'launch_sync',
        phase: 'requested',
        destinationUrl: 'http://192.168.1.50:8000/api/entradasitem',
        httpStatus: null,
        payload: { PRO_COD: '5000', PRO_QTD: 20 },
      });
    } finally {
      stub.restore();
    }
  });

  test('answers false instead of throwing when the audit trail is unavailable', async () => {
    const stub = stubFetch(new TypeError('Failed to fetch'));
    try {
      const kept = await postAeroAuditEvent({
        event: 'test_connection',
        destinationUrl: 'http://192.168.1.50:8000/api/usuarios/',
        error: 'blocked: mixed_content',
      });

      assert.equal(kept, false, 'a launch must never fail because the audit trail failed');
    } finally {
      stub.restore();
    }
  });
});
