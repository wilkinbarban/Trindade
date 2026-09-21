import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import Fastify, { type FastifyInstance } from 'fastify';
import { createAuthenticate } from '../modules/auth/auth.middleware.js';
import { registerApiRoutes } from '../routes.js';
import { buildTestApp } from '../test-helper.js';
import { documentedOperations } from './openapi.js';

/**
 * Routes the contract deliberately does not describe, and why.
 *
 * The set is empty, and that emptiness is a decision rather than an oversight: the document now
 * describes every route the server registers. It held `/api/admin` until a mobile client needed the
 * admin and audit surfaces, which is the condition the old entry itself named as its removal
 * trigger. The constant stays so the emptiness is asserted rather than assumed, and this paragraph
 * is where a future exclusion records its reason: a route left undescribed has to be a decision
 * someone writes down, not a gap that opens silently.
 */
const OUT_OF_SCOPE_PREFIXES: string[] = [];

/** The document writes OpenAPI templates; Fastify registers `:param` routes. Compare in one form. */
function toFastifyPath(documentPath: string): string {
  return documentPath.replace(/\{([^}]+)\}/g, ':$1');
}

/**
 * A method and path in one comparable key.
 *
 * Comparing paths alone would let the document name the right path with the wrong verb and still
 * pass, which is half of the drift this test exists to catch.
 */
function routeKey(method: string, url: string): string {
  return `${method.toUpperCase()} ${url}`;
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

  it('describes every registered route, method included, outside the declared out-of-scope set', () => {
    assert.ok(registered.length > 0, 'no routes were registered, so this proves nothing');

    const documented = new Set(
      documentedOperations().map((operation) => routeKey(operation.method, toFastifyPath(operation.path))),
    );
    const undescribed = registered
      .filter((route) => !OUT_OF_SCOPE_PREFIXES.some((prefix) => route.url.startsWith(prefix)))
      .filter((route) => !documented.has(routeKey(route.method, route.url)))
      .map((route) => routeKey(route.method, route.url));

    assert.deepStrictEqual(undescribed, [], 'these routes are served but not described with that verb');
  });

  it('describes no operation the server does not serve', () => {
    const served = new Set(registered.map((route) => routeKey(route.method, route.url)));
    const phantom = documentedOperations()
      .map((operation) => routeKey(operation.method, toFastifyPath(operation.path)))
      .filter((key) => !served.has(key));

    assert.deepStrictEqual(phantom, [], 'the document describes operations that do not exist');
  });

  // The exclusion set is empty, and the assertion says so deliberately. The previous form asserted
  // that something *was* excluded, which is exactly what would have turned this correct change red:
  // an empty set fails `excluded.length > 0`. Asserting the emptiness keeps the guarantee the old
  // test provided -- a scope exclusion cannot appear unnoticed -- in the form the new state allows.
  it('has nothing out of scope any more, and says so deliberately', () => {
    assert.deepStrictEqual(
      OUT_OF_SCOPE_PREFIXES,
      [],
      'the out-of-scope set is no longer empty: a new exclusion needs its own reason written here',
    );
  });
});
