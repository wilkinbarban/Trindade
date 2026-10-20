import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import { buildTestApp } from '../../test-helper.js';
import {
  FALLBACK_RELEASE,
  TRINDADE_APK_DOWNLOAD_URL,
  resetReleaseCache,
  setReleaseFetcherForTest,
} from './app.service.js';

describe('App Routes - /api/app', () => {
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

  it('GET /latest-release answers 200 with fallback when GitHub is unreachable', async () => {
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
    assert.strictEqual(body.apkUrl, TRINDADE_APK_DOWNLOAD_URL);
    assert.strictEqual(body.apkName, 'trindade.apk');
    assert.strictEqual(body.webUrl, 'https://trindademasas.duckdns.org');
  });

  it('GET /latest-release points apkUrl to own domain endpoint rather than raw GitHub', async () => {
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
    assert.strictEqual(body.apkUrl, TRINDADE_APK_DOWNLOAD_URL);
    assert.strictEqual(body.apkName, 'trindade.apk');
    assert.strictEqual(body.apkSize, 26500000);
    assert.strictEqual(body.publishedAt, '2026-10-18T10:00:00Z');
    assert.strictEqual(body.webUrl, 'https://trindademasas.duckdns.org');
    assert.strictEqual(callCount, 1);
  });

  it('GET /latest-release uses in-memory cache for 15 minutes', async () => {
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

    const res1 = await app.inject({ method: 'GET', url: '/api/app/latest-release' });
    assert.strictEqual(res1.statusCode, 200);
    assert.strictEqual(fetchCount, 1);

    const res2 = await app.inject({ method: 'GET', url: '/api/app/latest-release' });
    assert.strictEqual(res2.statusCode, 200);
    assert.strictEqual(fetchCount, 1);
    assert.strictEqual(JSON.parse(res2.body).version, 'v0.5.1');
  });

  it('GET /download-apk serves binary APK with attachment headers', async () => {
    const fakeApkBytes = Buffer.from('PK\x03\x04fake-apk-binary-content');
    setReleaseFetcherForTest(async (url) => {
      const urlStr = String(url);
      if (urlStr.includes('releases/latest')) {
        return new Response(
          JSON.stringify({
            tag_name: 'v0.5.0',
            assets: [
              {
                name: 'trindade-0.5.0.apk',
                browser_download_url: 'https://github.com/download/test.apk',
                size: fakeApkBytes.length,
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response(fakeApkBytes, {
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': String(fakeApkBytes.length),
        },
      });
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/app/download-apk',
    });

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.headers['content-type'], 'application/vnd.android.package-archive');
    assert.strictEqual(res.headers['content-disposition'], 'attachment; filename="trindade.apk"');
    assert.deepStrictEqual(res.rawPayload, fakeApkBytes);
  });

  it('GET /download-apk returns 502 when upstream source is unreachable', async () => {
    setReleaseFetcherForTest(async () => {
      throw new Error('Upstream unreachable');
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/app/download-apk',
    });

    assert.strictEqual(res.statusCode, 502);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.message, 'APK download temporarily unavailable');
  });
});
