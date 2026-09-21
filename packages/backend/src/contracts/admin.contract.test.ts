import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import type { z } from 'zod';
import {
  CategoriesResponseSchema,
  DriversResponseSchema,
  TasksResponseSchema,
  TimeSlotsResponseSchema,
  UsersResponseSchema,
  VehiclesResponseSchema,
} from '../modules/admin/admin.schema.js';
import { AuditResponseSchema } from '../modules/audit/audit.schema.js';
import { buildTestApp, type TestFixtures } from '../test-helper.js';

function expectMatch<T extends z.ZodTypeAny>(schema: T, body: string): z.infer<T> {
  const parsed = schema.safeParse(JSON.parse(body));
  assert.ok(parsed.success, `response does not match its schema: ${JSON.stringify(parsed.error?.issues)}`);
  return parsed.data;
}

/**
 * The admin surface's read endpoints, and the role each one admits.
 *
 * Two guards, measured from `admin.routes.ts` rather than assumed:
 *
 * - `catalogGuard` admits `Administrador` and `Trabalhador`. It covers the read endpoints below for
 *   `categories`, `tasks` and `drivers`. `tasks` and `drivers` are the two a `Trabalhador` also
 *   writes -- within limits the handlers enforce -- and the SPA draws both tabs for a worker, so a
 *   worker reading them is the surface's real shape rather than an accident. Documenting these three
 *   as admin-only would describe a server that does not exist, which is why the schema work must not
 *   obscure the guard split.
 * - `adminGuard` admits `Administrador` only, and covers `vehicles`, `time-slots`, `users` and
 *   `audit`.
 *
 * The last test in this file asserts that split, so the comment above cannot quietly stop being true.
 */
describe('admin responses match their declared schemas', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;
  let auth: { authorization: string };
  let workerAuth: { authorization: string };

  async function login(credentials: { username: string; password: string }): Promise<string> {
    const res = await app.inject({ method: 'POST', url: '/api/auth/login', payload: credentials });
    assert.strictEqual(res.statusCode, 200, res.body);
    return JSON.parse(res.body).token as string;
  }

  /** GET a read endpoint with an authenticated admin, asserting it answered before parsing it. */
  async function adminGet(url: string): Promise<string> {
    const res = await app.inject({ method: 'GET', url, headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);
    return res.body;
  }

  before(async () => {
    const result = await buildTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
    auth = { authorization: `Bearer ${await login(fixtures.admin)}` };
    workerAuth = { authorization: `Bearer ${await login(fixtures.worker)}` };
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('lists categories with the declared shape', async () => {
    const body = expectMatch(CategoriesResponseSchema, await adminGet('/api/admin/categories')) as {
      categories: { id: number }[];
    };
    assert.ok(body.categories.length > 0, 'the seeded categories came back empty');
  });

  // The listing carries `category_name` because `listTasks` joins the category, while the create and
  // update queries select only the task's own row plus `task_type`. Asserting the field here is what
  // stops the two real shapes from being documented as one.
  it('lists tasks with the category name the write responses omit', async () => {
    const body = expectMatch(TasksResponseSchema, await adminGet('/api/admin/tasks')) as {
      tasks: { category_name: string }[];
    };
    assert.ok(body.tasks.length > 0, 'the seeded tasks came back empty');
    assert.ok(
      body.tasks.every((task) => task.category_name.length > 0),
      'a listed task arrived without its category name',
    );
  });

  it('lists drivers with the declared shape', async () => {
    const body = expectMatch(DriversResponseSchema, await adminGet('/api/admin/drivers')) as {
      drivers: { id: number }[];
    };
    assert.ok(body.drivers.length > 0, 'the seeded drivers came back empty');
  });

  it('lists vehicles with the declared shape', async () => {
    const body = expectMatch(VehiclesResponseSchema, await adminGet('/api/admin/vehicles')) as {
      vehicles: { id: number }[];
    };
    assert.ok(body.vehicles.length > 0, 'the seeded vehicles came back empty');
  });

  it('returns the configured time slots with the declared shape', async () => {
    const body = expectMatch(TimeSlotsResponseSchema, await adminGet('/api/admin/time-slots')) as {
      timeSlots: string[];
    };
    // `getTimeSlots` falls back to its default set when the setting row is missing, so the response
    // is never empty; an empty list here would mean the fallback itself broke.
    assert.ok(body.timeSlots.length > 0, 'the time slots came back empty');
  });

  it('lists users with the role name the projection joins', async () => {
    const body = expectMatch(UsersResponseSchema, await adminGet('/api/admin/users')) as {
      users: { role_name: string }[];
    };
    assert.ok(body.users.length > 0, 'the fixture users came back empty');
    assert.ok(
      body.users.every((user) => user.role_name.length > 0),
      'a listed user arrived without its role name',
    );
  });

  it('lists the audit log with its pagination envelope', async () => {
    const body = expectMatch(AuditResponseSchema, await adminGet('/api/admin/audit')) as {
      logs: unknown[];
      page: number;
      limit: number;
      total: number;
      totalPages: number;
    };
    // Signing in is itself audited, so the page holds at least the two logins made above.
    assert.ok(body.logs.length > 0, 'the audit page came back empty after two logins');
    assert.ok(body.total >= body.logs.length, 'the total is smaller than the page it describes');
    assert.strictEqual(body.page, 1, 'the default page is not the first one');
    assert.strictEqual(body.limit, 20, 'the default page size is not the handler default');
  });

  // The schema above documents `tasks` and `drivers` as readable by a Trabalhador, which is only
  // honest because the guard admits one. This asserts the split in both directions: the three
  // catalog reads answer a worker, and the four admin-only ones refuse. A hidden control in a client
  // would prove nothing; this is the server's own answer.
  it('admits a worker to the catalog reads and refuses the admin-only ones', async () => {
    for (const url of ['/api/admin/categories', '/api/admin/tasks', '/api/admin/drivers']) {
      const res = await app.inject({ method: 'GET', url, headers: workerAuth });
      assert.strictEqual(res.statusCode, 200, `a Trabalhador must be able to read ${url}: ${res.body}`);
    }
    for (const url of ['/api/admin/vehicles', '/api/admin/time-slots', '/api/admin/users', '/api/admin/audit']) {
      const res = await app.inject({ method: 'GET', url, headers: workerAuth });
      assert.strictEqual(res.statusCode, 403, `a Trabalhador must not read ${url}: ${res.body}`);
    }
  });
});
