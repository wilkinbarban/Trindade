import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import { z } from 'zod';
import {
  CategoriesResponseSchema,
  CategoryResponseSchema,
  DriversResponseSchema,
  DriverResponseSchema,
  TasksResponseSchema,
  TaskResponseSchema,
  TimeSlotsResponseSchema,
  UsersResponseSchema,
  UserResponseSchema,
  VehiclesResponseSchema,
  VehicleResponseSchema,
} from '../modules/admin/admin.schema.js';
import { AuditResponseSchema } from '../modules/audit/audit.schema.js';
import { buildTestApp, type TestFixtures } from '../test-helper.js';

function expectMatch<T extends z.ZodTypeAny>(schema: T, body: string): z.infer<T> {
  const parsed = schema.safeParse(JSON.parse(body));
  assert.ok(parsed.success, `response does not match its schema: ${JSON.stringify(parsed.error?.issues)}`);
  return parsed.data;
}

/**
 * The envelope every delete handler sends.
 *
 * Declared here rather than in `admin.schema.ts` because the admin module declares no delete
 * response: the envelope is what the handlers send today, and `.strict()` keeps this test honest
 * about a shape that no schema of the module currently promises.
 */
const DeleteResponseSchema = z.object({ success: z.literal(true) }).strict();

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

  /**
   * Write to an endpoint with an authenticated admin, asserting the status the handler documents
   * before parsing the body. `payload` is last and optional because a delete sends none.
   */
  async function adminSend(
    method: 'POST' | 'PATCH' | 'DELETE',
    url: string,
    expectedStatus: number,
    payload?: object,
  ): Promise<string> {
    const res = await app.inject({ method, url, headers: auth, payload });
    assert.strictEqual(res.statusCode, expectedStatus, res.body);
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

  // Both handles are optional here on purpose: if `buildTestApp()` rejects above, neither was ever
  // assigned, and a teardown that throws its own `TypeError` would replace the real setup failure
  // with noise -- `node:test` would report this file as failed for the wrong reason.
  after(async () => {
    await app?.close();
    db?.close();
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

  // ---- Write envelopes ----
  //
  // The seven tests above parse read responses out of a running app. The five write envelopes the
  // admin module declares had no such test, so a mutation answering a shape other than the one it
  // documents failed nothing. Each test below creates the rows it writes: these tests mutate the
  // database, and one that borrowed another test's row would break when that test changed.

  it('creates and updates a category through the declared write envelope', async () => {
    // Both names are sent, as in every write below that has two. `translateOrCopy` translates only
    // when one is missing, and a response whose `name_es` came off the wire would answer
    // differently on a laptop than on a runner.
    const { category: created } = expectMatch(
      CategoryResponseSchema,
      await adminSend('POST', '/api/admin/categories', 201, {
        name_pt: 'Categoria de contrato',
        name_es: 'Categoría de contrato',
        sort_order: 91,
      }),
    );
    assert.strictEqual(created.sort_order, 91, 'the create envelope lost the order it was sent');

    const { category: updated } = expectMatch(
      CategoryResponseSchema,
      await adminSend('PATCH', `/api/admin/categories/${created.id}`, 200, { sort_order: 92 }),
    );
    assert.strictEqual(updated.id, created.id, 'the update answered about another category');
    assert.strictEqual(updated.sort_order, 92, 'the update envelope did not carry the new order');
  });

  it('creates and updates a task through the declared write envelope', async () => {
    const { category } = expectMatch(
      CategoryResponseSchema,
      await adminSend('POST', '/api/admin/categories', 201, {
        name_pt: 'Categoria de contrato com tarefas',
        name_es: 'Categoría de contrato con tareas',
        sort_order: 93,
      }),
    );

    const { task: created } = expectMatch(
      TaskResponseSchema,
      await adminSend('POST', '/api/admin/tasks', 201, {
        category_id: category.id,
        name_pt: 'Tarefa de contrato',
        name_es: 'Tarea de contrato',
      }),
    );
    // `task_type` belongs to the CATEGORY and arrives on the JOIN. The strict schema above already
    // requires the field and admits any of the four category types, so naming the fixture category's
    // type here is what fails a response that projected some other category's.
    assert.strictEqual(created.task_type, category.category_type, 'the create envelope lost the category type');

    const { task: updated } = expectMatch(
      TaskResponseSchema,
      await adminSend('PATCH', `/api/admin/tasks/${created.id}`, 200, { name_es: 'Tarea de contrato editada' }),
    );
    assert.strictEqual(updated.id, created.id, 'the update answered about another task');
    assert.strictEqual(updated.name_es, 'Tarea de contrato editada', 'the update envelope did not carry the new name');
    assert.strictEqual(updated.task_type, created.task_type, 'the update envelope lost the category type');
  });

  // The reason the task projection is unified. An empty body leaves `updateTask` with no assignment
  // to build an UPDATE from, so it answers from its early return -- and that branch used to answer
  // with `SELECT * FROM report_tasks`, a row without `task_type`, because only the JOIN to
  // `report_categories` has that field. This is the one case that failed against the branch while
  // every other write test passed, so delete it and the branch that is not a write goes back to
  // being unproved.
  it('answers a no-op task PATCH with the same declared shape as a real update', async () => {
    const { category } = expectMatch(
      CategoryResponseSchema,
      await adminSend('POST', '/api/admin/categories', 201, {
        name_pt: 'Categoria de contrato sem edição',
        name_es: 'Categoría de contrato sin edición',
        sort_order: 94,
      }),
    );
    const { task } = expectMatch(
      TaskResponseSchema,
      await adminSend('POST', '/api/admin/tasks', 201, {
        category_id: category.id,
        name_pt: 'Tarefa sem edição',
        name_es: 'Tarea sin edición',
      }),
    );

    const { task: unchanged } = expectMatch(
      TaskResponseSchema,
      await adminSend('PATCH', `/api/admin/tasks/${task.id}`, 200, {}),
    );
    // Field-for-field, not merely schema-shaped: the no-op must answer the task it left alone, and a
    // schema alone would accept any other task that happened to fit.
    assert.deepStrictEqual(unchanged, task, 'the no-op PATCH did not answer with the task it left alone');
  });

  it('creates and updates a driver through the declared write envelope', async () => {
    const { driver: created } = expectMatch(
      DriverResponseSchema,
      await adminSend('POST', '/api/admin/drivers', 201, {
        name: 'Chofer de contrato',
        license_plate: 'CTR-01',
        driver_type: 'fletero',
      }),
    );
    assert.strictEqual(created.driver_type, 'fletero', 'the create envelope lost the driver type');

    const { driver: updated } = expectMatch(
      DriverResponseSchema,
      await adminSend('PATCH', `/api/admin/drivers/${created.id}`, 200, { driver_type: 'casa' }),
    );
    assert.strictEqual(updated.id, created.id, 'the update answered about another driver');
    assert.strictEqual(updated.driver_type, 'casa', 'the update envelope did not carry the new driver type');
  });

  it('creates and updates a vehicle through the declared write envelope', async () => {
    const { vehicle: created } = expectMatch(
      VehicleResponseSchema,
      await adminSend('POST', '/api/admin/vehicles', 201, {
        description: 'Vehículo de contrato',
        license_plate: 'CTR-02',
      }),
    );
    assert.strictEqual(created.is_active, 1, 'a created vehicle arrived inactive');

    const { vehicle: updated } = expectMatch(
      VehicleResponseSchema,
      await adminSend('PATCH', `/api/admin/vehicles/${created.id}`, 200, { description: 'Vehículo de contrato editado' }),
    );
    assert.strictEqual(updated.id, created.id, 'the update answered about another vehicle');
    assert.strictEqual(updated.description, 'Vehículo de contrato editado', 'the update envelope did not carry the new description');
  });

  it('creates and updates a user through the declared write envelope', async () => {
    const { user: created } = expectMatch(
      UserResponseSchema,
      await adminSend('POST', '/api/admin/users', 201, {
        username: 'usuario-de-contrato',
        password: 'contrato-1234',
        display_name: 'Usuario de contrato',
        role_id: 2,
      }),
    );
    // `role_name` arrives from the JOIN to `roles` and `password_hash` is projected away by the same
    // query: a response that dropped the joined field or leaked the hash would fail the parse above,
    // not this line.
    assert.strictEqual(created.role_name, 'Trabalhador', 'the create envelope lost the role name');
    assert.strictEqual(created.role_id, 2, 'the create envelope lost the role it was sent');

    const { user: updated } = expectMatch(
      UserResponseSchema,
      await adminSend('PATCH', `/api/admin/users/${created.id}`, 200, { display_name: 'Usuario de contrato editado' }),
    );
    assert.strictEqual(updated.id, created.id, 'the update answered about another user');
    assert.strictEqual(updated.display_name, 'Usuario de contrato editado', 'the update envelope did not carry the new name');
    assert.strictEqual(updated.role_name, 'Trabalhador', 'the update envelope lost the role name');
  });

  // The four delete handlers answer `{ success: true }` and the module declares no schema for it, so
  // these parse the envelope the handlers send against the local strict schema above. `drivers` has
  // no delete handler, which is why it has no test here.
  it('deletes a category and answers with the success envelope', async () => {
    const { category } = expectMatch(
      CategoryResponseSchema,
      await adminSend('POST', '/api/admin/categories', 201, {
        name_pt: 'Categoria de contrato descartável',
        name_es: 'Categoría de contrato descartable',
        sort_order: 95,
      }),
    );

    const { success } = expectMatch(DeleteResponseSchema, await adminSend('DELETE', `/api/admin/categories/${category.id}`, 200));
    assert.strictEqual(success, true, 'the delete envelope did not confirm the deletion');
  });

  it('deletes a task and answers with the success envelope', async () => {
    const { category } = expectMatch(
      CategoryResponseSchema,
      await adminSend('POST', '/api/admin/categories', 201, {
        name_pt: 'Categoria de contrato descartável com tarefa',
        name_es: 'Categoría de contrato descartable con tarea',
        sort_order: 96,
      }),
    );
    const { task } = expectMatch(
      TaskResponseSchema,
      await adminSend('POST', '/api/admin/tasks', 201, {
        category_id: category.id,
        name_pt: 'Tarefa descartável',
        name_es: 'Tarea descartable',
      }),
    );

    const { success } = expectMatch(DeleteResponseSchema, await adminSend('DELETE', `/api/admin/tasks/${task.id}`, 200));
    assert.strictEqual(success, true, 'the delete envelope did not confirm the deletion');
  });

  it('deletes a vehicle and answers with the success envelope', async () => {
    const { vehicle } = expectMatch(
      VehicleResponseSchema,
      await adminSend('POST', '/api/admin/vehicles', 201, {
        description: 'Vehículo descartable',
        license_plate: 'CTR-03',
      }),
    );

    const { success } = expectMatch(DeleteResponseSchema, await adminSend('DELETE', `/api/admin/vehicles/${vehicle.id}`, 200));
    assert.strictEqual(success, true, 'the delete envelope did not confirm the deletion');
  });

  it('deletes a user and answers with the success envelope', async () => {
    const { user } = expectMatch(
      UserResponseSchema,
      await adminSend('POST', '/api/admin/users', 201, {
        username: 'usuario-de-contrato-descartavel',
        password: 'contrato-1234',
        display_name: 'Usuario descartable',
        role_id: 2,
      }),
    );

    const { success } = expectMatch(DeleteResponseSchema, await adminSend('DELETE', `/api/admin/users/${user.id}`, 200));
    assert.strictEqual(success, true, 'the delete envelope did not confirm the deletion');
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
