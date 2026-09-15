import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildTestApp, type TestFixtures } from '../../test-helper.js';

async function loginAs(
  app: FastifyInstance,
  username: string,
  password: string
): Promise<string> {
  const res = await app.inject({
    method: 'POST',
    url: '/api/auth/login',
    payload: { username, password },
  });
  return JSON.parse(res.body).token;
}

describe('Admin Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let adminToken: string;
  let workerToken: string;
  let categoryId: number;
  let driverId: number;
  let vehicleId: number;
  let fixtures: TestFixtures;

  before(async () => {
    const result = await buildTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;
    adminToken = await loginAs(app, fixtures.admin.username, fixtures.admin.password);
    workerToken = await loginAs(app, fixtures.worker.username, fixtures.worker.password);
    categoryId = (db.prepare("SELECT id FROM report_categories WHERE name_pt = 'Temperaturas'").get() as { id: number }).id;
    driverId = (db.prepare("SELECT id FROM drivers WHERE name = 'André'").get() as { id: number }).id;
    vehicleId = (db.prepare("SELECT id FROM vehicles WHERE license_plate = 'BDG'").get() as { id: number }).id;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  // ============================================================
  // Authentication & Authorization
  // ============================================================

  it('unauthenticated requests return 401', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/categories',
    });
    assert.strictEqual(res.statusCode, 401);
  });

  it('non-admin (worker) requests can read categories for the limited panel', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.categories));
  });

  it('admin requests succeed with 200', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
    });
    assert.strictEqual(res.statusCode, 200);
  });

  // ============================================================
  // Categories CRUD
  // ============================================================

  it('POST /admin/categories creates a new category', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name_pt: 'Nova Categoria Teste',
        name_es: 'Nueva Categoría Test',
        sort_order: 10,
      },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(body.category, 'category should be present');
    assert.strictEqual(body.category.name_pt, 'Nova Categoria Teste');
    assert.strictEqual(body.category.name_es, 'Nueva Categoría Test');
    assert.strictEqual(body.category.sort_order, 10);
    assert.strictEqual(body.category.is_active, 1);
    assert.ok(body.category.id, 'category should have an id');
  });

  it('GET /admin/categories lists all categories including inactive', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.categories));
    assert.ok(body.categories.length >= 7, 'should include the seed catalog and the test-created category');
  });

  it('PATCH /admin/categories/:id updates a category', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/admin/categories/1',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_pt: 'Higiene Modificado', sort_order: 99 },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.category.name_pt, 'Higiene Modificado');
    assert.strictEqual(body.category.sort_order, 99);
    // name_es should be unchanged
    assert.strictEqual(body.category.name_es, 'Higiene y Organización');
  });

  it('PATCH /admin/categories/:id toggles is_active (soft-delete)', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/admin/categories/${categoryId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 0 },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.category.is_active, 0);

    // Toggle back
    const res2 = await app.inject({
      method: 'PATCH',
      url: `/api/admin/categories/${categoryId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 1 },
    });
    assert.strictEqual(res2.statusCode, 200);
    assert.strictEqual(JSON.parse(res2.body).category.is_active, 1);
  });

  it('PATCH /admin/categories/:id returns 404 for missing category', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/admin/categories/99999',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_pt: 'Nope' },
    });
    assert.strictEqual(res.statusCode, 404);
  });


  it('POST /admin/categories accepts PT-BR-only and ES-only names with safe counterpart fill', async () => {
    const ptOnly = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name_pt: 'Higiene e Organização',
        category_type: 'check_assai',
        sort_order: 20,
      },
    });

    assert.strictEqual(ptOnly.statusCode, 201);
    const ptCategory = JSON.parse(ptOnly.body).category;
    assert.strictEqual(ptCategory.name_pt, 'Higiene e Organização');
    assert.strictEqual(ptCategory.name_es, 'higiene y organización');
    assert.strictEqual(ptCategory.category_type, 'check_assai');

    const esOnly = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name_es: 'higiene y organización',
        category_type: 'check_normal',
        sort_order: 21,
      },
    });

    assert.strictEqual(esOnly.statusCode, 201);
    const esCategory = JSON.parse(esOnly.body).category;
    assert.strictEqual(esCategory.name_es, 'higiene y organización');
    assert.strictEqual(esCategory.name_pt, 'Higiene e Organização');
    assert.strictEqual(esCategory.category_type, 'check_normal');
  });

  it('PATCH /admin/categories preserves existing counterpart when one locale is submitted', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name_pt: 'Português Original',
        name_es: 'Español Curado',
        sort_order: 22,
      },
    });
    const categoryId = JSON.parse(createRes.body).category.id;

    const patchRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/categories/${categoryId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_pt: 'Português Editado' },
    });

    assert.strictEqual(patchRes.statusCode, 200);
    const category = JSON.parse(patchRes.body).category;
    assert.strictEqual(category.name_pt, 'Português Editado');
    assert.strictEqual(category.name_es, 'Español Curado');
  });

  it('POST /admin/categories supports all category types', async () => {
    const types = ['check', 'temperature', 'check_assai', 'check_normal'] as const;

    for (const [index, categoryType] of types.entries()) {
      const res = await app.inject({
        method: 'POST',
        url: '/api/admin/categories',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name_pt: `Tipo ${categoryType}`,
          name_es: `Tipo ES ${categoryType}`,
          category_type: categoryType,
          sort_order: 30 + index,
        },
      });

      assert.strictEqual(res.statusCode, 201, `should accept ${categoryType}`);
      assert.strictEqual(JSON.parse(res.body).category.category_type, categoryType);
    }
  });

  it('configures one to three readings only for temperature tasks', async () => {
    const categoryRes = await app.inject({
      method: 'POST', url: '/api/admin/categories', headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_pt: 'Temperatura teste', name_es: 'Temperatura prueba', category_type: 'temperature', sort_order: 90 },
    });
    const categoryId = JSON.parse(categoryRes.body).category.id;
    const createRes = await app.inject({
      method: 'POST', url: '/api/admin/tasks', headers: { authorization: `Bearer ${adminToken}` },
      payload: { category_id: categoryId, name_pt: 'Câmara de teste', name_es: 'Cámara de prueba', temperature_readings: 3 },
    });
    assert.strictEqual(createRes.statusCode, 201);
    assert.strictEqual(JSON.parse(createRes.body).task.temperature_readings, 3);

    const invalidRes = await app.inject({
      method: 'PATCH', url: `/api/admin/tasks/${JSON.parse(createRes.body).task.id}`, headers: { authorization: `Bearer ${adminToken}` },
      payload: { temperature_readings: 4 },
    });
    assert.strictEqual(invalidRes.statusCode, 400);
  });

  it('POST /admin/categories falls back safely when translation fetch fails', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (() => Promise.reject(new Error('network unavailable'))) as typeof fetch;
    try {
      const res = await app.inject({
        method: 'POST',
        url: '/api/admin/categories',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name_pt: 'Categoria Sem Tradução',
          sort_order: 40,
        },
      });

      assert.strictEqual(res.statusCode, 201);
      const category = JSON.parse(res.body).category;
      assert.strictEqual(category.name_pt, 'Categoria Sem Tradução');
      assert.ok(category.name_es, 'Spanish fallback should be populated');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // ============================================================
  // Tasks CRUD
  // ============================================================

  it('GET /admin/tasks lists all tasks', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.tasks));
    assert.ok(body.tasks.length >= 14, 'should have at least 14 seed tasks');
    // Check that category_name is populated
    const first = body.tasks[0];
    assert.ok(first.category_name, 'task should have category_name');
  });

  it('POST /admin/tasks creates a new task', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        category_id: 1,
        name_pt: 'Nova Tarefa Teste',
        name_es: 'Nueva Tarea Test',
      },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(body.task);
    assert.strictEqual(body.task.name_pt, 'Nova Tarefa Teste');
    assert.strictEqual(body.task.category_id, 1);
    assert.strictEqual(body.task.task_type, 'check');
  });


  it('POST /admin/tasks accepts single-locale names and fills the missing locale safely', async () => {
    const ptOnly = await app.inject({
      method: 'POST',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        category_id: 1,
        name_pt: 'Pátio organizado',
      },
    });

    assert.strictEqual(ptOnly.statusCode, 201);
    const ptTask = JSON.parse(ptOnly.body).task;
    assert.strictEqual(ptTask.name_pt, 'Pátio organizado');
    assert.strictEqual(ptTask.name_es, 'patio organizado');

    const esOnly = await app.inject({
      method: 'POST',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        category_id: 1,
        name_es: 'patio organizado',
      },
    });

    assert.strictEqual(esOnly.statusCode, 201);
    const esTask = JSON.parse(esOnly.body).task;
    assert.strictEqual(esTask.name_es, 'patio organizado');
    assert.strictEqual(esTask.name_pt, 'Pátio organizado');
  });

  it('PATCH /admin/tasks preserves existing counterpart when one locale is submitted', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        category_id: 1,
        name_pt: 'Tarefa PT Original',
        name_es: 'Tarea ES Curada',
      },
    });
    const taskId = JSON.parse(createRes.body).task.id;

    const patchRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/tasks/${taskId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_es: 'Tarea ES Editada' },
    });

    assert.strictEqual(patchRes.statusCode, 200);
    const task = JSON.parse(patchRes.body).task;
    assert.strictEqual(task.name_pt, 'Tarefa PT Original');
    assert.strictEqual(task.name_es, 'Tarea ES Editada');
  });

  it('PATCH /admin/tasks/:id toggles is_active', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/admin/tasks/5',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 0 },
    });

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(JSON.parse(res.body).task.is_active, 0);
  });

  it('PATCH /admin/tasks/:id returns 404 for missing task', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/admin/tasks/99999',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name_pt: 'Nope' },
    });
    assert.strictEqual(res.statusCode, 404);
  });

  it('worker can create and edit only owned tasks, without deactivation or legacy edits', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/admin/tasks',
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        category_id: 1,
        name_pt: 'Tarefa Worker',
        name_es: 'Tarea Worker',
      },
    });
    assert.strictEqual(createRes.statusCode, 201);
    const createdTask = JSON.parse(createRes.body).task;
    assert.strictEqual(createdTask.created_by_user_id, fixtures.worker.id);

    const editOwnRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/tasks/${createdTask.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { name_pt: 'Tarefa Worker Editada' },
    });
    assert.strictEqual(editOwnRes.statusCode, 200);
    assert.strictEqual(JSON.parse(editOwnRes.body).task.name_pt, 'Tarefa Worker Editada');

    const deactivateOwnRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/tasks/${createdTask.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { is_active: 0 },
    });
    assert.strictEqual(deactivateOwnRes.statusCode, 403);

    const legacyEditRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/tasks/${(db.prepare('SELECT id FROM report_tasks WHERE created_by_user_id IS NULL LIMIT 1').get() as { id: number }).id}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { name_pt: 'Tentativa legado' },
    });
    assert.strictEqual(legacyEditRes.statusCode, 403);

    const deleteRes = await app.inject({
      method: 'DELETE',
      url: `/api/admin/tasks/${createdTask.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.strictEqual(deleteRes.statusCode, 200);

    const casaDriver = db
      .prepare("INSERT INTO drivers (name, driver_type, is_active) VALUES ('Casa Para Eliminar', 'casa', 1)")
      .run().lastInsertRowid as number;
    const deleteCasaRes = await app.inject({
      method: 'DELETE',
      url: `/api/admin/drivers/${casaDriver}`,
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.strictEqual(deleteCasaRes.statusCode, 404);
  });

  // ============================================================
  // Drivers CRUD
  // ============================================================

  it('GET /admin/drivers lists all drivers', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/drivers',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.drivers));
    assert.ok(body.drivers.length >= 3, 'should have at least 3 seed drivers');
  });

  it('POST /admin/drivers creates a new driver', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/drivers',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name: 'Admin Driver Test',
        license_plate: 'ADM-0001',
      },
    });

    assert.strictEqual(res.statusCode, 201);
    assert.strictEqual(JSON.parse(res.body).driver.name, 'Admin Driver Test');
  });

  it('PATCH /admin/drivers/:id toggles is_active', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/admin/drivers/${driverId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 0 },
    });

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(JSON.parse(res.body).driver.is_active, 0);

    // Toggle back
    const res2 = await app.inject({
      method: 'PATCH',
      url: `/api/admin/drivers/${driverId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 1 },
    });
    assert.strictEqual(res2.statusCode, 200);
    assert.strictEqual(JSON.parse(res2.body).driver.is_active, 1);
  });

  it('worker can create and edit only owned fletero drivers', async () => {
    const casaRes = await app.inject({
      method: 'POST',
      url: '/api/admin/drivers',
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        name: 'Casa Worker Bloqueado',
        driver_type: 'casa',
      },
    });
    assert.strictEqual(casaRes.statusCode, 403);

    const createRes = await app.inject({
      method: 'POST',
      url: '/api/admin/drivers',
      headers: { authorization: `Bearer ${workerToken}` },
      payload: {
        name: 'Fletero Worker',
        driver_type: 'fletero',
      },
    });
    assert.strictEqual(createRes.statusCode, 201);
    const driver = JSON.parse(createRes.body).driver;
    assert.strictEqual(driver.created_by_user_id, fixtures.worker.id);
    assert.strictEqual(driver.driver_type, 'fletero');

    const editOwnRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/drivers/${driver.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { name: 'Fletero Worker Editado' },
    });
    assert.strictEqual(editOwnRes.statusCode, 200);
    assert.strictEqual(JSON.parse(editOwnRes.body).driver.name, 'Fletero Worker Editado');

    const deactivateOwnRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/drivers/${driver.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { is_active: 0 },
    });
    assert.strictEqual(deactivateOwnRes.statusCode, 403);

    const legacyEditRes = await app.inject({
      method: 'PATCH',
      url: `/api/admin/drivers/${driverId}`,
      headers: { authorization: `Bearer ${workerToken}` },
      payload: { name: 'Tentativa legado' },
    });
    assert.strictEqual(legacyEditRes.statusCode, 403);

    const deleteRes = await app.inject({
      method: 'DELETE',
      url: `/api/admin/drivers/${driver.id}`,
      headers: { authorization: `Bearer ${workerToken}` },
    });
    assert.strictEqual(deleteRes.statusCode, 404);
  });

  // ============================================================
  // Vehicles CRUD
  // ============================================================

  it('GET /admin/vehicles lists all vehicles', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/vehicles',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.vehicles));
    assert.ok(body.vehicles.length >= 1, 'should have at least 1 seed vehicle');
  });

  it('POST /admin/vehicles creates a new vehicle', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/vehicles',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        description: 'Van de Entrega',
        license_plate: 'VAN-1234',
      },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.vehicle.description, 'Van de Entrega');
    assert.strictEqual(body.vehicle.license_plate, 'VAN-1234');
  });

  it('PATCH /admin/vehicles/:id toggles is_active', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/admin/vehicles/${vehicleId}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 0 },
    });

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(JSON.parse(res.body).vehicle.is_active, 0);
  });

  it('PATCH /admin/vehicles/:id returns 404 for missing vehicle', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: '/api/admin/vehicles/99999',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { description: 'Nope' },
    });
    assert.strictEqual(res.statusCode, 404);
  });

  // ============================================================
  // Time Slots CRUD
  // ============================================================

  it('GET /admin/time-slots returns configured time slots', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/time-slots',
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.timeSlots));
    assert.ok(body.timeSlots.length >= 7, 'should have at least 7 default time slots');
    assert.ok(body.timeSlots.includes('04:00'));
    assert.ok(body.timeSlots.includes('07:00'));
  });

  it('PUT /admin/time-slots updates time slots', async () => {
    const newSlots = ['04:00', '04:30', '08:00'];
    const res = await app.inject({
      method: 'PUT',
      url: '/api/admin/time-slots',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { time_slots: newSlots },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.deepStrictEqual(body.timeSlots, newSlots);

    // Restore defaults for other tests
    const defaults = ['04:00', '04:30', '05:00', '05:30', '06:00', '06:30', '07:00'];
    await app.inject({
      method: 'PUT',
      url: '/api/admin/time-slots',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { time_slots: defaults },
    });
  });

  it('PUT /admin/time-slots validates input and returns 400 on empty array', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: '/api/admin/time-slots',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { time_slots: [] },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  // ============================================================
  // Validation
  // ============================================================

  it('POST /admin/categories validates body and returns 400 on missing fields', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {},
    });
    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, 'Invalid input');
  });



  it('POST /admin/drivers returns 400 on empty name', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/drivers',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { name: '' },
    });
    assert.strictEqual(res.statusCode, 400);
  });

  // ============================================================
  // Users CRUD
  // ============================================================

  describe('Users CRUD', () => {
    it('GET /admin/users lists all users', async () => {
      const res = await app.inject({
        method: 'GET',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(res.statusCode, 200);
      const body = JSON.parse(res.body);
      assert.ok(Array.isArray(body.users));
      // The test app creates one administrator and one worker.
      const initialCount = body.users.length;
      assert.strictEqual(initialCount, 2);

      // Verify structure of first user
      const u = body.users[0];
      assert.ok(u.id);
      assert.ok(u.username);
      assert.ok(u.display_name);
      assert.ok(u.role_name);
      assert.strictEqual(typeof u.is_active, 'number');
      // Password hash should not be exposed
      assert.strictEqual(u.password_hash, undefined);
    });

    it('POST /admin/users creates a new user, PATCH updates it, and DELETE deletes it', async () => {
      // 1. Create user
      const createRes = await app.inject({
        method: 'POST',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          username: 'testworker',
          password: 'password123',
          display_name: 'Test Worker',
          role_id: 2, // Trabalhador
        },
      });

      assert.strictEqual(createRes.statusCode, 201);
      const createBody = JSON.parse(createRes.body);
      assert.ok(createBody.user);
      const newUserId = createBody.user.id;
      assert.strictEqual(createBody.user.username, 'testworker');
      assert.strictEqual(createBody.user.display_name, 'Test Worker');
      assert.strictEqual(createBody.user.role_name, 'Trabalhador');
      assert.strictEqual(createBody.user.is_active, 1);

      // Verify database count increased to 5
      const listRes1 = await app.inject({
        method: 'GET',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
      });
      assert.strictEqual(JSON.parse(listRes1.body).users.length, 3);

      // 2. Update user (patch display name and deactivate)
      const updateRes = await app.inject({
        method: 'PATCH',
        url: `/api/admin/users/${newUserId}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          display_name: 'Updated Worker',
          is_active: 0,
        },
      });

      assert.strictEqual(updateRes.statusCode, 200);
      const updateBody = JSON.parse(updateRes.body);
      assert.strictEqual(updateBody.user.display_name, 'Updated Worker');
      assert.strictEqual(updateBody.user.is_active, 0);

      // 3. Delete user physically
      const deleteRes = await app.inject({
        method: 'DELETE',
        url: `/api/admin/users/${newUserId}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(deleteRes.statusCode, 200);
      assert.deepStrictEqual(JSON.parse(deleteRes.body), { success: true });

      // Verify database count is back to 4
      const listRes2 = await app.inject({
        method: 'GET',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
      });
      assert.strictEqual(JSON.parse(listRes2.body).users.length, 2);
    });

    it('DELETE /admin/users/:id removes a user with audit and nullable ownership references', async () => {
      const createRes = await app.inject({
        method: 'POST',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          username: 'delete-demo',
          password: 'password123',
          display_name: 'Delete Demo',
          role_id: 2,
        },
      });

      assert.strictEqual(createRes.statusCode, 201);
      const userId = JSON.parse(createRes.body).user.id;

      db.prepare(
        "INSERT INTO audit_logs (user_id, action, entity_type) VALUES (?, 'login', 'auth')"
      ).run(userId);
      db.prepare(
        "INSERT INTO loading_schedules (schedule_date, time_slot, driver_type, user_id) VALUES ('2026-06-19', '04:00', 'fletero', ?)"
      ).run(userId);
      db.prepare(
        "INSERT INTO report_tasks (category_id, name_pt, name_es, created_by_user_id) VALUES (1, 'Owned task', 'Tarea propia', ?)"
      ).run(userId);
      db.prepare(
        "INSERT INTO drivers (name, driver_type, created_by_user_id) VALUES ('Owned Fletero', 'fletero', ?)"
      ).run(userId);

      const deleteRes = await app.inject({
        method: 'DELETE',
        url: `/api/admin/users/${userId}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(deleteRes.statusCode, 200);
      assert.deepStrictEqual(JSON.parse(deleteRes.body), { success: true });
      assert.strictEqual(db.prepare('SELECT id FROM users WHERE id = ?').get(userId), undefined);
      assert.strictEqual(
        (db.prepare('SELECT COUNT(*) AS count FROM audit_logs WHERE user_id = ?').get(userId) as { count: number }).count,
        0
      );
      assert.strictEqual(
        (db.prepare('SELECT COUNT(*) AS count FROM report_tasks WHERE created_by_user_id = ?').get(userId) as { count: number }).count,
        0
      );
      assert.strictEqual(
        (db.prepare('SELECT COUNT(*) AS count FROM drivers WHERE created_by_user_id = ?').get(userId) as { count: number }).count,
        0
      );
      assert.strictEqual(
        (db.prepare('SELECT COUNT(*) AS count FROM loading_schedules WHERE user_id = ?').get(userId) as { count: number }).count,
        0
      );
    });

    it('prevents an admin from deleting, deactivating, or changing their own access profile', async () => {
      const selfDeleteRes = await app.inject({
        method: 'DELETE',
        url: `/api/admin/users/${fixtures.admin.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(selfDeleteRes.statusCode, 403);

      const selfDeactivateRes = await app.inject({
        method: 'PATCH',
        url: `/api/admin/users/${fixtures.admin.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { is_active: 0 },
      });

      assert.strictEqual(selfDeactivateRes.statusCode, 403);

      const selfRoleChangeRes = await app.inject({
        method: 'PATCH',
        url: `/api/admin/users/${fixtures.admin.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { role_id: 2 },
      });

      assert.strictEqual(selfRoleChangeRes.statusCode, 403);

      const selfDataUpdateRes = await app.inject({
        method: 'PATCH',
        url: `/api/admin/users/${fixtures.admin.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { display_name: 'Wilkin Updated' },
      });

      assert.strictEqual(selfDataUpdateRes.statusCode, 200);
      assert.strictEqual(JSON.parse(selfDataUpdateRes.body).user.display_name, 'Wilkin Updated');
    });

    it('POST /admin/users returns 400 for duplicate username', async () => {
      const res = await app.inject({
        method: 'POST',
        url: '/api/admin/users',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          username: fixtures.admin.username,
          password: 'newpassword',
          display_name: 'Wilkin Dup',
          role_id: 1,
        },
      });

      assert.strictEqual(res.statusCode, 400);
      const body = JSON.parse(res.body);
      assert.ok(body.error.includes('usuário já existe'));
    });
  });

  // ============================================================
  // Physical Deletes Constraint Violations
  // ============================================================

  describe('Physical Delete Constraints', () => {
    it('DELETE /admin/categories/:id returns 400 when category has tasks referenced in reports', async () => {
      // Create a report and report item referencing Task 1 (which belongs to Category 1)
      const reportResult = db.prepare(
        "INSERT INTO reports (user_id, turno, report_date, is_editable) VALUES (1, 'tarde', '2026-06-16', 1)"
      ).run();
      const reportId = reportResult.lastInsertRowid;

      db.prepare(
        "INSERT INTO report_items (report_id, task_id, checked) VALUES (?, 1, 1)"
      ).run(reportId);

      // Now attempt to delete Category 1
      const res = await app.inject({
        method: 'DELETE',
        url: '/api/admin/categories/1',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(res.statusCode, 400);
      const body = JSON.parse(res.body);
      assert.ok(body.error.includes('Não é possível excluir'));
    });

    it('DELETE /admin/categories/:id deletes category and its tasks when no task has report references', async () => {
      // Category 3 (Estoque) has tasks but they have no report items. Let's delete it.
      const res = await app.inject({
        method: 'DELETE',
        url: `/api/admin/categories/${categoryId}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(res.statusCode, 200);

      // Verify Category 3 is deleted
      const cat = db.prepare('SELECT id FROM report_categories WHERE id = 3').get();
      assert.strictEqual(cat, undefined);

      // Verify tasks of Category 3 are deleted
      const tasks = db.prepare('SELECT id FROM report_tasks WHERE category_id = 3').all();
      assert.strictEqual(tasks.length, 0);
    });

    it('DELETE /admin/tasks/:id returns 400 when task is referenced in reports', async () => {
      // First, create a report with items referencing a task to create a constraint violation.
      // Task 1 exists in seed. Let's create a report.
      // We can use db directly since we are in integration tests and have access to db
      const reportResult = db.prepare(
        "INSERT INTO reports (user_id, turno, report_date, is_editable) VALUES (1, 'tarde', '2026-06-16', 1)"
      ).run();
      const reportId = reportResult.lastInsertRowid;

      db.prepare(
        "INSERT INTO report_items (report_id, task_id, checked) VALUES (?, 1, 1)"
      ).run(reportId);

      // Now attempt to delete task 1
      const res = await app.inject({
        method: 'DELETE',
        url: '/api/admin/tasks/1',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.strictEqual(res.statusCode, 400);
      const body = JSON.parse(res.body);
      assert.ok(body.error.includes('Não é possível excluir'));

      // Clean up the created report/items so it does not affect other tests
      db.prepare('DELETE FROM report_items WHERE report_id = ?').run(reportId);
      db.prepare('DELETE FROM reports WHERE id = ?').run(reportId);
    });
  });
});
