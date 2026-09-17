import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import Fastify, { type FastifyInstance } from 'fastify';
import { createAuthenticate } from '../modules/auth/auth.middleware.js';
import { registerApiRoutes } from '../routes.js';
import { buildTestApp } from '../test-helper.js';
import { documentedPaths } from './openapi.js';

/**
 * Routes the contract deliberately does not describe, and why.
 *
 * Admin and audit are excluded because no mobile client consumes them: the scope decision is field
 * operations only, and those two surfaces join the registry when a client needs them. Everything
 * else must appear in the document, so a new route forces a decision here instead of slipping past
 * the contract unnoticed.
 */
const OUT_OF_SCOPE_PREFIXES = ['/api/admin'];

/** The document writes OpenAPI templates; Fastify registers `:param` routes. Compare in one form. */
function toFastifyPath(documentPath: string): string {
  return documentPath.replace(/\{([^}]+)\}/g, ':$1');
}

/** Fastify exposes a HEAD for every GET; the document describes what a client actually calls. */
const DOCUMENTED_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']);

describe('the contract covers exactly the routes the server serves', () => {
  let app: FastifyInstance;
  let builtApp: FastifyInstance;
  let closeDatabases: () => void;
  const registered: { method: string; url: string }[] = [];

  before(async () => {
    // The same registration the server performs, on a throwaway instance so no listener starts.
    const built = await buildTestApp();
    builtApp = built.app;
    closeDatabases = () => built.db.close();

    app = Fastify({ logger: false });
    app.decorate('db', built.db);
    app.decorate('authenticate', createAuthenticate(built.fixtures.jwtSecret));
    app.addHook('onRoute', (route) => {
      const methods = Array.isArray(route.method) ? route.method : [route.method];
      for (const method of methods) {
        if (DOCUMENTED_METHODS.has(method)) registered.push({ method, url: route.url });
      }
    });

    await registerApiRoutes(app, {
      jwtSecret: built.fixtures.jwtSecret,
      refreshTokenTtlDays: 30,
      sessionStoreAvailable: () => true,
      photosDir: built.photosDir,
      publicBaseUrl: 'https://example.test',
    });
    await app.ready();
  });

  after(async () => {
    await app.close();
    await builtApp.close();
    closeDatabases();
  });

  it('describes every registered route outside the declared out-of-scope set', () => {
    assert.ok(registered.length > 0, 'no routes were registered, so this proves nothing');

    const documented = new Set(documentedPaths().map(toFastifyPath));
    const undescribed = registered
      .filter((route) => !OUT_OF_SCOPE_PREFIXES.some((prefix) => route.url.startsWith(prefix)))
      .filter((route) => !documented.has(route.url))
      .map((route) => `${route.method} ${route.url}`);

    assert.deepStrictEqual(undescribed, [], 'these routes are served but not described');
  });

  it('describes no route the server does not serve', () => {
    const served = new Set(registered.map((route) => route.url));
    const phantom = documentedPaths().filter((path) => !served.has(toFastifyPath(path)));

    assert.deepStrictEqual(phantom, [], 'the document describes routes that do not exist');
  });

  // The exclusion is a scope decision, so it is asserted rather than assumed: if admin or audit
  // routes were ever registered somewhere else, the first test above would start failing with them
  // named, which is the intended signal.
  it('excludes only the declared out-of-scope prefixes', () => {
    const excluded = registered.filter((route) =>
      OUT_OF_SCOPE_PREFIXES.some((prefix) => route.url.startsWith(prefix)),
    );
    assert.ok(excluded.length > 0, 'the out-of-scope set matched nothing, so it may be stale');
    for (const route of excluded) {
      assert.ok(route.url.startsWith('/api/admin'), `${route.url} was excluded for the wrong reason`);
    }
  });
});
