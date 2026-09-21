import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import { buildAuthTestApp, type TestFixtures } from '../modules/auth/auth-test-helper.js';
import {
  LoginResponseSchema,
  ProfileResponseSchema,
  RefreshResponseSchema,
} from '../modules/auth/auth.schema.js';
import { SuccessResponseSchema } from './common.schema.js';
import { ErrorEnvelopeSchema } from './error.schema.js';
import { buildOpenApiDocument, documentedPaths, normalizeNumericLiteralUnions } from './openapi.js';

/** The surface this contract currently claims to describe. */
const EXPECTED_PATHS = [
  '/api/admin/audit',
  '/api/admin/categories',
  '/api/admin/categories/{id}',
  '/api/admin/drivers',
  '/api/admin/drivers/{id}',
  '/api/admin/tasks',
  '/api/admin/tasks/{id}',
  '/api/admin/time-slots',
  '/api/admin/users',
  '/api/admin/users/{id}',
  '/api/admin/vehicles',
  '/api/admin/vehicles/{id}',
  '/api/auth/change-password',
  '/api/auth/login',
  '/api/auth/logout',
  '/api/auth/me',
  '/api/auth/profile',
  '/api/auth/refresh',
  '/api/auth/setup',
  '/api/auth/setup/status',
  '/api/dashboard/summary',
  '/api/health',
  '/api/loading/drivers',
  '/api/loading/export',
  '/api/loading/schedules',
  '/api/loading/schedules/batch/{date}',
  '/api/loading/schedules/batch/{date}/deactivate',
  '/api/loading/schedules/history',
  '/api/loading/schedules/{id}',
  '/api/loading/schedules/{id}/deactivate',
  '/api/loading/time-slots',
  '/api/loading/vehicles',
  '/api/reports',
  '/api/reports/categories',
  '/api/reports/history',
  '/api/reports/photos/public/{token}',
  '/api/reports/photos/{photoId}',
  '/api/reports/products',
  '/api/reports/turno',
  '/api/reports/{id}',
  '/api/reports/{id}/deactivate',
  '/api/reports/{id}/export',
  '/api/reports/{id}/photos',
  '/api/users/options',
  '/p/{token}',
];

describe('API contract', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildAuthTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  // This is the reason response shapes are schemas rather than interfaces: an interface can
  // describe a response the code never produces and nothing notices. Parsing a live response
  // makes that drift fail here instead of inside a mobile client that cannot report it.
  describe('live auth responses match their declared schemas', () => {
    function expectMatch(schema: { safeParse: (value: unknown) => { success: boolean; error?: { issues: unknown } } }, body: string) {
      const parsed = schema.safeParse(JSON.parse(body));
      assert.ok(parsed.success, `response does not match its schema: ${JSON.stringify(parsed.error?.issues)}`);
    }

    it('login returns the declared login shape', async () => {
      const res = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });
      assert.strictEqual(res.statusCode, 200, res.body);
      expectMatch(LoginResponseSchema, res.body);
    });

    it('refresh returns the declared refresh shape', async () => {
      const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });
      const refreshToken = JSON.parse(login.body).refreshToken;

      const res = await app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
      assert.strictEqual(res.statusCode, 200, res.body);
      expectMatch(RefreshResponseSchema, res.body);
    });

    it('profile returns the declared profile shape', async () => {
      const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });

      const res = await app.inject({
        method: 'GET',
        url: '/api/auth/profile',
        headers: { authorization: `Bearer ${JSON.parse(login.body).token}` },
      });
      assert.strictEqual(res.statusCode, 200, res.body);
      expectMatch(ProfileResponseSchema, res.body);
    });

    it('logout returns the declared success shape', async () => {
      const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });

      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/logout',
        headers: { authorization: `Bearer ${JSON.parse(login.body).token}` },
      });
      assert.strictEqual(res.statusCode, 200, res.body);
      expectMatch(SuccessResponseSchema, res.body);
    });

    it('a failure returns the declared error envelope', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: fixtures.admin.username, password: 'definitely-not-the-password' },
      });
      assert.strictEqual(res.statusCode, 401, res.body);
      expectMatch(ErrorEnvelopeSchema, res.body);
    });

    it('a validation failure returns the error envelope with details', async () => {
      const res = await app.inject({ method: 'POST', url: '/api/auth/login', payload: {} });
      assert.strictEqual(res.statusCode, 400, res.body);

      const parsed = ErrorEnvelopeSchema.safeParse(JSON.parse(res.body));
      assert.ok(parsed.success, 'a validation failure did not match the error envelope');
      assert.strictEqual(parsed.data!.error, 'Invalid input');
      assert.ok(parsed.data!.details, 'the validation failure carried no details');
    });
  });

  describe('the document describes exactly the surface it claims', () => {
    it('is an OpenAPI 3.1 document', () => {
      const document = buildOpenApiDocument();
      assert.strictEqual(document.openapi, '3.1.0');
      assert.ok(document.info.title);
      assert.ok(document.info.version);
    });

    it('documents the declared paths and no others', () => {
      assert.deepStrictEqual(documentedPaths(), EXPECTED_PATHS);
    });

    // An OpenAPI path template has to spell its parameters `{name}`; Fastify spells them
    // `:name`, and the generator does not translate between the two. `:id` is not a valid
    // template, so a client generator would not recognise the parameter at all.
    it('uses OpenAPI path templates rather than Fastify route syntax', () => {
      for (const path of documentedPaths()) {
        assert.ok(!path.includes(':'), `${path} still uses Fastify ':param' syntax`);
      }
    });

    it('declares every path parameter its template uses', () => {
      const document = buildOpenApiDocument();
      for (const [path, operations] of Object.entries(document.paths ?? {})) {
        const templateParams = [...path.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
        for (const [method, operation] of Object.entries(operations as Record<string, { parameters?: { in: string; name: string }[] }>)) {
          const declared = (operation.parameters ?? [])
            .filter((parameter) => parameter.in === 'path')
            .map((parameter) => parameter.name)
            .sort();
          assert.deepStrictEqual(
            declared,
            [...templateParams].sort(),
            `${method.toUpperCase()} ${path} does not declare exactly its path parameters`,
          );
        }
      }
    });

    it('gives every documented operation at least one response', () => {
      const document = buildOpenApiDocument();
      for (const [path, operations] of Object.entries(document.paths ?? {})) {
        for (const [method, operation] of Object.entries(operations as Record<string, { responses?: object }>)) {
          const responses = operation.responses ?? {};
          assert.ok(
            Object.keys(responses).length > 0,
            `${method.toUpperCase()} ${path} declares no responses`,
          );
        }
      }
    });

    it('round-trips through JSON, so it can be committed as an artifact', () => {
      const document = buildOpenApiDocument();
      assert.deepStrictEqual(JSON.parse(JSON.stringify(document)), document);
    });

    // A description built by concatenation can silently degrade: a stray `+` before a string literal
    // makes TypeScript apply unary plus, which coerces the literal to NaN and drops the sentence
    // while leaving valid JSON behind. Nothing else here reads the text, so this is the check that
    // would have caught it before the corrupted string reached the committed artifact.
    it('carries no degenerated text in any string it emits', () => {
      const offenders: string[] = [];

      const walk = (value: unknown, path: string) => {
        if (typeof value === 'string') {
          for (const marker of ['NaN', 'undefined', '[object Object]']) {
            if (value.includes(marker)) offenders.push(`${path} contains ${marker}`);
          }
        } else if (Array.isArray(value)) {
          value.forEach((entry, index) => walk(entry, `${path}[${index}]`));
        } else if (value && typeof value === 'object') {
          for (const [key, entry] of Object.entries(value)) walk(entry, `${path}.${key}`);
        }
      };

      walk(buildOpenApiDocument(), 'document');
      assert.deepStrictEqual(offenders, []);
    });
  });
});

/** A schema position that points at a component rather than repeating it. */
function isReference(schema: unknown): boolean {
  return typeof schema === 'object' && schema !== null && '$ref' in schema;
}

// The document is the input a client generator consumes, and a generator cannot name what the
// document does not name. An inline object repeated at every response yields one unnamed model per
// operation, which is what happened here: 26 components were registered and every response inlined
// its own copy anyway, so the generated Android client carried 81 models where 26 named ones plus
// their envelopes would do.
//
// Nothing caught it because the other tests look at paths, counts and shapes, and an inlined
// document satisfies all three. These two assertions look at reuse itself, which is the property the
// registrations exist to provide and the only one a generator depends on.
describe('the contract document references its own components', () => {
  const document = buildOpenApiDocument();

  it('points every JSON response and request body at a component instead of inlining it', () => {
    const inlined: string[] = [];

    for (const [path, operations] of Object.entries(document.paths ?? {})) {
      for (const [method, rawOperation] of Object.entries(operations)) {
        if (!rawOperation || typeof rawOperation !== 'object') continue;
        const operation = rawOperation as {
          responses?: Record<string, { content?: Record<string, { schema?: unknown }> } | undefined>;
          requestBody?: { content?: Record<string, { schema?: unknown }> };
        };

        for (const [status, response] of Object.entries(operation.responses ?? {})) {
          const schema = response?.content?.['application/json']?.schema;
          if (schema !== undefined && !isReference(schema)) {
            inlined.push(`${method.toUpperCase()} ${path} ${status}`);
          }
        }

        const body = operation.requestBody?.content?.['application/json']?.schema;
        if (body !== undefined && !isReference(body)) {
          inlined.push(`${method.toUpperCase()} ${path} request body`);
        }
      }
    }

    assert.deepStrictEqual(
      inlined,
      [],
      'these JSON shapes are written inline, so every generated client gets an unnamed model for each one. Register the shape as a component and reference it.',
    );
  });

  it('resolves every $ref to a component that exists', () => {
    const components = new Set(Object.keys(document.components?.schemas ?? {}));
    const dangling: string[] = [];

    const walk = (value: unknown, where: string): void => {
      if (Array.isArray(value)) {
        value.forEach((entry, index) => walk(entry, `${where}[${index}]`));
        return;
      }
      if (!value || typeof value !== 'object') return;

      const record = value as Record<string, unknown>;
      const ref = record.$ref;
      if (typeof ref === 'string' && !components.has(ref.split('/').pop() ?? '')) {
        dangling.push(`${where} -> ${ref}`);
      }
      for (const [key, entry] of Object.entries(record)) walk(entry, `${where}.${key}`);
    };

    walk(document, 'document');

    assert.deepStrictEqual(
      dangling,
      [],
      'a reference that resolves to nothing is worse than the repetition it replaces',
    );
  });
});

/**
 * The seven admin flag fields are why the normalization exists, so they are what the tests name:
 * `is_active` on the five update requests and `role_id` on the two user requests. Before the step,
 * each reached the document as an `anyOf` of one-member number enums, which the Kotlin generator
 * turned into an enum whose values are `java.math.BigDecimal` built from String literals -- a model
 * a client cannot assign a plain `1` to. The shapes below are the ones a generated client can use.
 *
 * The positive assertion runs against `buildOpenApiDocument()`, so deleting the step from the
 * builder fails here rather than only in a generated client nobody is compiling in this suite.
 */
describe('the document normalizes numeric literal unions into integer enums', () => {
  const document = buildOpenApiDocument();
  const schemas = (document.components?.schemas ?? {}) as unknown as Record<
    string,
    { properties?: Record<string, unknown> } | undefined
  >;

  /** One named component's field schema, so a test can assert the representation a client receives. */
  function property(component: string, field: string): unknown {
    return schemas[component]?.properties?.[field];
  }

  it('collapses each admin flag union into one integer enum', () => {
    for (const component of [
      'UpdateAdminCategoryRequest',
      'UpdateAdminTaskRequest',
      'UpdateAdminDriverRequest',
      'UpdateAdminVehicleRequest',
      'UpdateAdminUserRequest',
    ]) {
      assert.deepStrictEqual(
        property(component, 'is_active'),
        { type: 'integer', enum: [0, 1] },
        `${component}.is_active is not a single integer enum`,
      );
    }

    for (const component of ['CreateAdminUserRequest', 'UpdateAdminUserRequest']) {
      assert.deepStrictEqual(
        property(component, 'role_id'),
        { type: 'integer', enum: [1, 2] },
        `${component}.role_id is not a single integer enum`,
      );
    }
  });

  it('leaves no union of numeric literals anywhere in the built document', () => {
    const residue: string[] = [];

    const isOneMemberNumberEnum = (member: unknown): boolean => {
      if (!member || typeof member !== 'object') return false;
      const record = member as Record<string, unknown>;
      return (
        record.type === 'number' &&
        Array.isArray(record.enum) &&
        record.enum.length === 1 &&
        typeof record.enum[0] === 'number'
      );
    };

    const walk = (value: unknown, where: string): void => {
      if (Array.isArray(value)) {
        value.forEach((entry, index) => walk(entry, `${where}[${index}]`));
        return;
      }
      if (!value || typeof value !== 'object') return;
      const record = value as Record<string, unknown>;

      for (const key of ['anyOf', 'oneOf']) {
        const members = record[key];
        if (Array.isArray(members) && members.length > 0 && members.every(isOneMemberNumberEnum)) {
          residue.push(`${where}.${key}`);
        }
      }

      for (const [key, entry] of Object.entries(record)) walk(entry, `${where}.${key}`);
    };

    walk(document, 'document');
    assert.deepStrictEqual(
      residue,
      [],
      'these unions still reach a generated client as BigDecimal-valued enums',
    );
  });

  it('leaves a fractional numeric enum exactly as it is', () => {
    const original = {
      components: {
        schemas: {
          Fractional: {
            type: 'object',
            properties: {
              ratio: {
                anyOf: [
                  { type: 'number', enum: [0.5] },
                  { type: 'number', enum: [1.5] },
                ],
              },
            },
          },
        },
      },
    };

    const normalized = normalizeNumericLiteralUnions(structuredClone(original));
    assert.deepStrictEqual(
      normalized,
      original,
      'a union that is not entirely integers must be left untouched',
    );
  });

  it('does not rewrite a union that mixes an integer with a fractional value', () => {
    const original = {
      anyOf: [
        { type: 'number', enum: [0] },
        { type: 'number', enum: [1.5] },
      ],
    };

    const normalized = normalizeNumericLiteralUnions(structuredClone(original));
    assert.deepStrictEqual(normalized, original, 'one fractional member must hold off the whole rewrite');
  });

  it('is idempotent, and a no-op on the already-normalized built document', () => {
    const raw = { anyOf: [{ type: 'number', enum: [0] }, { type: 'number', enum: [1] }] };
    const once = normalizeNumericLiteralUnions(structuredClone(raw));
    const twice = normalizeNumericLiteralUnions(structuredClone(once));
    assert.deepStrictEqual(twice, once, 'a second pass over a normalized union changed it');

    assert.deepStrictEqual(
      normalizeNumericLiteralUnions(structuredClone(document)),
      document,
      'the builder already applied the step, so running it again must change nothing',
    );
  });
});
