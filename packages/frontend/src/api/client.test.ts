import { test } from 'node:test';
import assert from 'node:assert/strict';

// Set up mock browser storage and window globals before importing client.ts
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

const mockLocalStorage = new MockStorage();
const mockSessionStorage = new MockStorage();

globalThis.localStorage = mockLocalStorage;
globalThis.sessionStorage = mockSessionStorage;
globalThis.window = {
  location: { pathname: '/dashboard', href: '/dashboard' } as any,
  dispatchEvent: () => true,
  addEventListener: () => {},
  removeEventListener: () => {},
} as any;

// Dynamic import of client after globals are configured
const { api, consumeSessionExpiredNotice } = await import('./client');

interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: any;
}

const originalFetch = globalThis.fetch;
let recordedRequests: RecordedRequest[] = [];

function resetEnvironment() {
  mockLocalStorage.clear();
  mockSessionStorage.clear();
  recordedRequests = [];
  globalThis.window.location.pathname = '/dashboard';
  globalThis.window.location.href = '/dashboard';
  globalThis.fetch = originalFetch;
}

test('session rotation and refresh token handling', async (t) => {
  await t.test('expired access token -> silent renewal -> original request retried once with new token', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'valid-refresh-token');
    mockLocalStorage.setItem('auth_user', JSON.stringify({ id: 1, username: 'test' }));

    let normalCallCount = 0;
    let refreshCallCount = 0;

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method || 'GET';
      const headers = (init?.headers as Record<string, string>) || {};
      let parsedBody = null;
      if (init?.body && typeof init.body === 'string') {
        try {
          parsedBody = JSON.parse(init.body);
        } catch {
          parsedBody = init.body;
        }
      }
      recordedRequests.push({ url, method, headers, body: parsedBody });

      if (url.endsWith('/api/users/options')) {
        normalCallCount++;
        if (normalCallCount === 1) {
          assert.equal(headers['Authorization'], 'Bearer expired-access-token');
          return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        assert.equal(headers['Authorization'], 'Bearer fresh-access-token');
        return new Response(JSON.stringify({ users: [{ id: 1, name: 'Wilkin' }] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.endsWith('/api/auth/refresh')) {
        refreshCallCount++;
        assert.equal(method, 'POST');
        assert.equal(parsedBody?.refreshToken, 'valid-refresh-token');
        return new Response(
          JSON.stringify({
            token: 'fresh-access-token',
            refreshToken: 'fresh-refresh-token',
            expiresIn: 7200,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      }

      return new Response('Not found', { status: 404 });
    };

    const result = await api.get<{ users: Array<{ id: number; name: string }> }>('/users/options');

    assert.deepEqual(result, { users: [{ id: 1, name: 'Wilkin' }] });
    assert.equal(normalCallCount, 2, 'expected original request to be called then retried');
    assert.equal(refreshCallCount, 1, 'expected exactly one refresh call');
    assert.equal(mockLocalStorage.getItem('auth_token'), 'fresh-access-token');
    assert.equal(mockLocalStorage.getItem('auth_refresh_token'), 'fresh-refresh-token');
  });

  await t.test('failed renewal -> session cleared and redirected to login', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'revoked-refresh-token');
    mockLocalStorage.setItem('auth_user', JSON.stringify({ id: 1, username: 'test' }));

    globalThis.fetch = async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith('/api/users/options')) {
        return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.endsWith('/api/auth/refresh')) {
        return new Response(JSON.stringify({ error: 'Invalid or expired session' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('Not found', { status: 404 });
    };

    await assert.rejects(
      async () => {
        await api.get('/users/options');
      },
      (err: any) => {
        assert.equal(err.name, 'ApiClientError');
        assert.equal(err.status, 401);
        return true;
      }
    );

    assert.equal(mockLocalStorage.getItem('auth_token'), null);
    assert.equal(mockLocalStorage.getItem('auth_refresh_token'), null);
    assert.equal(mockLocalStorage.getItem('auth_user'), null);
    assert.equal(consumeSessionExpiredNotice(), true);
    assert.equal(globalThis.window.location.href, '/login');
  });

  await t.test('refresh token is never attached to a normal request', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'valid-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'secret-refresh-token');

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method || 'GET';
      const headers = (init?.headers as Record<string, string>) || {};
      let parsedBody = null;
      if (init?.body && typeof init.body === 'string') {
        try {
          parsedBody = JSON.parse(init.body);
        } catch {
          parsedBody = init.body;
        }
      }
      recordedRequests.push({ url, method, headers, body: parsedBody });

      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    };

    await api.get('/reports');
    await api.post('/stock/add-entry', { product_id: 1, quantity: 10 });

    for (const req of recordedRequests) {
      assert.notEqual(req.url, '/api/auth/refresh');
      // Authorization header must ONLY carry access token, never refresh token
      assert.equal(req.headers['Authorization'], 'Bearer valid-access-token');
      // Body must never contain refresh token
      if (req.body && typeof req.body === 'object') {
        assert.equal(req.body.refreshToken, undefined);
        assert.equal(JSON.stringify(req.body).includes('secret-refresh-token'), false);
      }
    }
  });

  await t.test('two parallel 401s produce one single refresh call (no stampede)', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'valid-refresh-token');

    let refreshCallCount = 0;
    let reportsCallCount = 0;
    let productsCallCount = 0;

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const headers = (init?.headers as Record<string, string>) || {};

      if (url.endsWith('/api/reports')) {
        reportsCallCount++;
        if (headers['Authorization'] === 'Bearer expired-access-token') {
          return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(JSON.stringify({ reports: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.endsWith('/api/products')) {
        productsCallCount++;
        if (headers['Authorization'] === 'Bearer expired-access-token') {
          return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(JSON.stringify({ products: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.endsWith('/api/auth/refresh')) {
        refreshCallCount++;
        // Simulate network latency for refresh
        await new Promise((resolve) => setTimeout(resolve, 30));
        return new Response(
          JSON.stringify({
            token: 'renewed-access-token',
            refreshToken: 'renewed-refresh-token',
            expiresIn: 7200,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      }

      return new Response('Not found', { status: 404 });
    };

    const [reportsRes, productsRes] = await Promise.all([
      api.get<{ reports: any[] }>('/reports'),
      api.get<{ products: any[] }>('/products'),
    ]);

    assert.deepEqual(reportsRes, { reports: [] });
    assert.deepEqual(productsRes, { products: [] });
    assert.equal(refreshCallCount, 1, 'two concurrent 401s must trigger only ONE refresh call');
    assert.equal(reportsCallCount, 2, 'reports request retried once with new token');
    assert.equal(productsCallCount, 2, 'products request retried once with new token');
    assert.equal(mockLocalStorage.getItem('auth_token'), 'renewed-access-token');
    assert.equal(mockLocalStorage.getItem('auth_refresh_token'), 'renewed-refresh-token');
  });

  await t.test('retried request that still returns 401 fails without infinite loop', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'valid-refresh-token');

    let callCount = 0;
    let refreshCount = 0;

    globalThis.fetch = async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith('/api/users/options')) {
        callCount++;
        return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.endsWith('/api/auth/refresh')) {
        refreshCount++;
        return new Response(
          JSON.stringify({
            token: 'token-that-also-fails',
            refreshToken: 'refresh-2',
            expiresIn: 7200,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      }
      return new Response('Not found', { status: 404 });
    };

    await assert.rejects(
      async () => {
        await api.get('/users/options');
      },
      (err: any) => {
        assert.equal(err.name, 'ApiClientError');
        assert.equal(err.status, 401);
        return true;
      }
    );

    assert.equal(refreshCount, 1, 'must refresh at most once');
    assert.equal(callCount, 2, 'must retry at most once');
    assert.equal(consumeSessionExpiredNotice(), true);
    assert.equal(mockLocalStorage.getItem('auth_token'), null);
  });

  await t.test('login 401 with Invalid credentials never triggers refresh', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_refresh_token', 'stale-refresh-token');

    let refreshCalled = false;
    globalThis.fetch = async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith('/api/auth/login')) {
        return new Response(JSON.stringify({ error: 'Invalid credentials' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.endsWith('/api/auth/refresh')) {
        refreshCalled = true;
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }
      return new Response('Not found', { status: 404 });
    };

    await assert.rejects(
      async () => {
        await api.post('/auth/login', { username: 'test', password: 'bad' });
      },
      (err: any) => {
        assert.equal(err.status, 401);
        return true;
      }
    );

    assert.equal(refreshCalled, false, 'login 401 must never call refresh');
  });

  await t.test('a 401 whose message names no token still renews once', async () => {
    // The backend answers with a semantic code, and a 401 with a token in hand means the session, not
    // the request, is what expired. Reading the prose to decide would skip the renewal the moment the
    // sentence changed.
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'valid-refresh-token');

    let refreshCalled = false;
    let reportsCallCount = 0;

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const headers = (init?.headers as Record<string, string>) || {};

      if (url.endsWith('/api/auth/refresh')) {
        refreshCalled = true;
        return new Response(JSON.stringify({ token: 'renewed-access-token', refreshToken: 'renewed-refresh-token' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.endsWith('/api/reports')) {
        reportsCallCount++;
        if (headers['Authorization'] === 'Bearer expired-access-token') {
          return new Response(JSON.stringify({ error: 'Request denied by policy', code: 'DENIED' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(JSON.stringify({ reports: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      return new Response('Not found', { status: 404 });
    };

    assert.deepEqual(await api.get<{ reports: any[] }>('/reports'), { reports: [] });
    assert.equal(refreshCalled, true, 'the renewal does not depend on how the 401 was worded');
    assert.equal(reportsCallCount, 2);
    assert.equal(mockLocalStorage.getItem('auth_token'), 'renewed-access-token');
  });

  await t.test('successful login stores refresh token in localStorage', async () => {
    resetEnvironment();
    globalThis.fetch = async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith('/api/auth/login')) {
        return new Response(
          JSON.stringify({
            token: 'logged-in-access-token',
            refreshToken: 'logged-in-refresh-token',
            expiresIn: 7200,
            user: { id: 10, username: 'testuser', role: 'Administrador' },
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      }
      return new Response('Not found', { status: 404 });
    };

    await api.post('/auth/login', { username: 'testuser', password: 'secretpassword123' });

    assert.equal(mockLocalStorage.getItem('auth_token'), 'logged-in-access-token');
    assert.equal(mockLocalStorage.getItem('auth_refresh_token'), 'logged-in-refresh-token');
  });

  await t.test('401 with no stored refresh token immediately redirects without calling refresh endpoint', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'some-access-token');
    // NO auth_refresh_token set

    let refreshCalled = false;
    globalThis.fetch = async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      if (url.endsWith('/api/users/options')) {
        return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.endsWith('/api/auth/refresh')) {
        refreshCalled = true;
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }
      return new Response('Not found', { status: 404 });
    };

    await assert.rejects(
      async () => {
        await api.get('/users/options');
      },
      (err: any) => {
        assert.equal(err.status, 401);
        return true;
      }
    );

    assert.equal(refreshCalled, false, 'must not call refresh endpoint without refresh token');
    assert.equal(mockLocalStorage.getItem('auth_token'), null);
    assert.equal(consumeSessionExpiredNotice(), true);
  });

  await t.test('api.upload transparently renews session and retries once on 401', async () => {
    resetEnvironment();
    mockLocalStorage.setItem('auth_token', 'expired-access-token');
    mockLocalStorage.setItem('auth_refresh_token', 'valid-refresh-token');

    let uploadCallCount = 0;
    let refreshCallCount = 0;

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const headers = (init?.headers as Record<string, string>) || {};

      if (url.endsWith('/api/reports/upload')) {
        uploadCallCount++;
        if (headers['Authorization'] === 'Bearer expired-access-token') {
          return new Response(JSON.stringify({ error: 'Invalid or expired token' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        assert.equal(headers['Authorization'], 'Bearer fresh-access-token');
        return new Response(JSON.stringify({ url: '/photo.jpg' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }

      if (url.endsWith('/api/auth/refresh')) {
        refreshCallCount++;
        return new Response(
          JSON.stringify({
            token: 'fresh-access-token',
            refreshToken: 'fresh-refresh-token',
            expiresIn: 7200,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        );
      }

      return new Response('Not found', { status: 404 });
    };

    const fakeFormData = {} as FormData;
    const result = await api.upload<{ url: string }>('/reports/upload', fakeFormData);

    assert.deepEqual(result, { url: '/photo.jpg' });
    assert.equal(uploadCallCount, 2, 'upload should retry once after refresh');
    assert.equal(refreshCallCount, 1);
    assert.equal(mockLocalStorage.getItem('auth_token'), 'fresh-access-token');
    assert.equal(mockLocalStorage.getItem('auth_refresh_token'), 'fresh-refresh-token');
  });

  resetEnvironment();
});
