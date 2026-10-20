import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import { buildTestApp } from '../../test-helper.js';
import {
  FALLBACK_RELEASE,
  getLatestRelease,
  resetReleaseCache,
  setReleaseFetcherForTest,
} from './app.service.js';

describe('App Routes - GET /api/app/latest-release', () => {
  let app: FastifyInstance;
  let closeDatabases: () => void;

  before(async () => {
    const built = await buildTestApp();
    closeDatabases = () => built.db.close();
    app = built.app;
  });

  after(async () => {
    setReleaseFetcherForTest(null);
    resetReleaseCache();
    await app.close();
    closeDatabases();
  });

  beforeEach(() => {
    resetReleaseCache();
    setReleaseFetcherForTest(null);
  });

  it('answers 200 with the fallback release when GitHub is unreachable', async () => {
    setReleaseFetcherForTest(async () => {
      throw new Error('Network offline');
    });

    const response = await app.inject({
      method: 'GET',
      url: '/api/app/latest-release',
    });

    assert.strictEqual(response.statusCode, 200);
    const body = JSON.parse(response.body);
    assert.strictEqual(body.version, FALLBACK_RELEASE.version);
    assert.strictEqual(body.apkUrl, FALLBACK_RELEASE.apkUrl);
    assert.strictEqual(body.apkName, FALLBACK_RELEASE.apkName);
    assert.strictEqual(body.webUrl, 'https://trindademasas.duckdns.org');
  });

  it('answers 200 with parsed release when GitHub API responds with release and assets', async () => {
    let callCount = 0;
    setReleaseFetcherForTest(async (url) => {
      callCount++;
      assert.ok(String(url).includes('releases/latest'));
      return new Response(
        JSON.stringify({
          tag_name: 'v0.5.0',
          name: 'Trindade v0.5.0',
          published_at: '2026-10-18T10:00:00Z',
          assets: [
            {
              name: 'trindade-0.5.0.apk',
              browser_download_url:
                'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk',
              size: 26500000,
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });

    const response = await app.inject({
      method: 'GET',
      url: '/api/app/latest-release',
    });

    assert.strictEqual(response.statusCode, 200);
    const body = JSON.parse(response.body);
    assert.strictEqual(body.version, 'v0.5.0');
    assert.strictEqual(
      body.apkUrl,
      'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk',
    );
    assert.strictEqual(body.apkName, 'trindade-0.5.0.apk');
    assert.strictEqual(body.apkSize, 26500000);
    assert.strictEqual(body.publishedAt, '2026-10-18T10:00:00Z');
    assert.strictEqual(body.webUrl, 'https://trindademasas.duckdns.org');
    assert.strictEqual(callCount, 1);
  });

  it('uses in-memory cache for 15 minutes and avoids duplicate external fetches', async () => {
    let fetchCount = 0;
    setReleaseFetcherForTest(async () => {
      fetchCount++;
      return new Response(
        JSON.stringify({
          tag_name: 'v0.5.1',
          assets: [
            {
              name: 'trindade-0.5.1.apk',
              browser_download_url:
                'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.1/trindade-0.5.1.apk',
              size: 27000000,
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });

    // First request - should fetch
    const res1 = await app.inject({ method: 'GET', url: '/api/app/latest-release' });
    assert.strictEqual(res1.statusCode, 200);
    assert.strictEqual(fetchCount, 1);
    assert.strictEqual(JSON.parse(res1.body).version, 'v0.5.1');

    // Second request immediately after - should serve from cache
    const res2 = await app.inject({ method: 'GET', url: '/api/app/latest-release' });
    assert.strictEqual(res2.statusCode, 200);
    assert.strictEqual(fetchCount, 1);
    assert.strictEqual(JSON.parse(res2.body).version, 'v0.5.1');
  });

  it('derives default apk download url if assets array contains no .apk', async () => {
    setReleaseFetcherForTest(async () => {
      return new Response(
        JSON.stringify({
          tag_name: 'v0.5.2',
          published_at: '2026-10-20T12:00:00Z',
          assets: [{ name: 'checksums.txt', browser_download_url: 'https://example.com/sums' }],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    });

    const response = await app.inject({
      method: 'GET',
      url: '/api/app/latest-release',
    });

    assert.strictEqual(response.statusCode, 200);
    const body = JSON.parse(response.body);
    assert.strictEqual(body.version, 'v0.5.2');
    assert.strictEqual(
      body.apkUrl,
      'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.2/trindade-0.5.2.apk',
    );
    assert.strictEqual(body.apkName, 'trindade-0.5.2.apk');
    assert.strictEqual(body.apkSize, null);
  });
});
