import { describe, it, before, after, beforeEach } from 'node:test';
import assert from 'node:assert';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';

import { buildTemperatureReadings, buildTestApp } from '../../test-helper.js';

/**
 * Helper: login as admin and return the JWT token.
 */
async function loginAs(app: FastifyInstance, username: string, password: string): Promise<string> {
  const res = await app.inject({
    method: 'POST',
    url: '/api/auth/login',
    payload: { username, password },
  });
  return JSON.parse(res.body).token;
}

describe('Reports Routes', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let token: string;

  before(async () => {
    const result = await buildTestApp();
    app = result.app;
    db = result.db;
    // photosDir is available as result.photosDir for file-path assertions
    token = await loginAs(app, result.fixtures.admin.username, result.fixtures.admin.password);
  });

  after(async () => {
    await app.close();
    db.close();
  });

  beforeEach(() => {
    if (db) {
      db.prepare('UPDATE reports SET is_active = 0').run();
    }
  });

  // ---- Authentication ----

  it('unauthenticated requests return 401', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      payload: { turno: 'tarde' },
    });
    assert.strictEqual(res.statusCode, 401);
  });

  // ---- Reference Data ----

  it('GET /categories returns categories with tasks', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/categories',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.categories), 'categories should be an array');
    assert.ok(body.categories.length >= 3, 'should have at least 3 categories');

    const first = body.categories[0];
    assert.ok(first.tasks.length > 0, 'category should have tasks');
    assert.strictEqual(typeof first.name_pt, 'string');
    assert.strictEqual(typeof first.name_es, 'string');
  });

  it('GET /categories exposes each temperature task reading count', async () => {
    const categoryId = db.prepare(
      "INSERT INTO report_categories (name_pt, name_es, category_type, sort_order) VALUES ('Temperatura configurada', 'Temperatura configurada', 'temperature', 999)"
    ).run().lastInsertRowid;
    const taskId = db.prepare(
      "INSERT INTO report_tasks (category_id, name_pt, name_es, temperature_readings) VALUES (?, 'Câmara configurada', 'Cámara configurada', 3)"
    ).run(categoryId).lastInsertRowid;

    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/categories',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    const category = body.categories.find((item: { id: number }) => item.id === Number(categoryId));
    const task = category.tasks.find((item: { id: number }) => item.id === Number(taskId));
    assert.strictEqual(task.temperature_readings, 3);
    db.prepare('DELETE FROM report_tasks WHERE id = ?').run(taskId);
    db.prepare('DELETE FROM report_categories WHERE id = ?').run(categoryId);
  });



  // ---- Turno Detection ----

  it('GET /turno returns auto-detected shift', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/turno',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(
      body.turno === 'tarde' || body.turno === 'noite',
      `turno should be tarde or noite, got: ${body.turno}`
    );
  });

  // ---- Create Report ----

  it('POST / creates report and returns 201', async () => {
    const payload = {
      turno: 'tarde',
      notes: 'Test report notes',
      items: [
        { taskId: 1, checked: true },   // Pátio organizado
        { taskId: 2, checked: false },  // Câmaras limpas
        { taskId: 4, checked: true },
      ],
      temperatures: buildTemperatureReadings(db),
    };

    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload,
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(body.report, 'report should be present');
    assert.strictEqual(body.report.turno, 'tarde');
    assert.strictEqual(body.report.notes, 'Test report notes');
    assert.ok(body.report.id, 'report should have an id');
    assert.ok(body.report.report_date, 'report should have a date');
    assert.ok(body.report.created_at, 'report should have created_at');

    // Verify items
    assert.strictEqual(body.report.items.length, 3);
    const patioItem = body.report.items.find((i: any) => i.task_id === 1);
    assert.ok(patioItem, 'should have Pátio organizado item');
    assert.strictEqual(patioItem.checked, true);

    const camarasItem = body.report.items.find((i: any) => i.task_id === 2);
    assert.ok(camarasItem);
    assert.strictEqual(camarasItem.checked, false);

    // Verify temperatures
    assert.deepStrictEqual(
      body.report.temperatures.map(({ location, readingIndex, value }: any) => ({ location, readingIndex, value })),
      buildTemperatureReadings(db)
    );
  });

  it('POST / translates Spanish notes to Portuguese before saving', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (() => Promise.reject(new Error('network unavailable'))) as typeof fetch;
    try {
      const res = await app.inject({
        method: 'POST',
        url: '/api/reports',
        headers: { authorization: `Bearer ${token}` },
        payload: {
          turno: 'tarde',
          notes: 'Todo bien',
          temperatures: buildTemperatureReadings(db),
        },
      });

      assert.strictEqual(res.statusCode, 201);
      const body = JSON.parse(res.body);
      assert.strictEqual(body.report.notes, 'Tudo bem');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('POST / auto-detects turno when not provided', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        notes: 'Auto-detect turno test',
        temperatures: buildTemperatureReadings(db),
      },
    });

    assert.strictEqual(res.statusCode, 201);
    const body = JSON.parse(res.body);
    assert.ok(
      body.report.turno === 'tarde' || body.report.turno === 'noite',
      'turno should be auto-detected'
    );
  });

  it('POST / rejects report creation without temperatures with 400', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'No temperatures',
        temperatures: [],
      },
    });

    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.ok(body.message.toLowerCase().includes('temperatura') || body.message.toLowerCase().includes('temperature'));
  });

  it('PATCH /:id rejects report update with empty temperatures with 400', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'Has temperature',
        temperatures: buildTemperatureReadings(db),
      },
    });
    const reportId = JSON.parse(createRes.body).report.id;

    const res = await app.inject({
      method: 'PATCH',
      url: `/api/reports/${reportId}`,
      headers: { authorization: `Bearer ${token}` },
      payload: {
        temperatures: [],
      },
    });

    assert.strictEqual(res.statusCode, 400);
  });

  it('POST / validates body and returns 400 on bad input', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'manha', // invalid
      },
    });

    assert.strictEqual(res.statusCode, 400);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.error, 'Invalid input');
  });

  // ---- List Reports ----

  it('GET / lists reports with date filter', async () => {
    // Create a few reports first
    await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'Report A',
        temperatures: buildTemperatureReadings(db),
      },
    });

    await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'noite',
        notes: 'Report B',
        temperatures: buildTemperatureReadings(db),
      },
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/reports?period=today',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.reports), 'reports should be an array');
    assert.ok(body.reports.length >= 2, 'should have at least 2 reports');

    const first = body.reports[0];
    assert.strictEqual(typeof first.id, 'number');
    assert.strictEqual(typeof first.turno, 'string');
    assert.ok(first.user, 'report should have user info');
    assert.strictEqual(typeof first.user.display_name, 'string');
  });

  it('GET / supports yesterday period filter', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports?period=yesterday',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(Array.isArray(body.reports));
    // Reports created today won't appear in yesterday filter
    assert.strictEqual(body.reports.length, 0);
  });

  // ---- Get Single Report ----

  it('GET /:id returns report detail', async () => {
    // Create a report first
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        items: [{ taskId: 1, checked: true }],
        temperatures: buildTemperatureReadings(db),
      },
    });
    const reportId = JSON.parse(createRes.body).report.id;

    const res = await app.inject({
      method: 'GET',
      url: `/api/reports/${reportId}`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.strictEqual(body.report.id, reportId);
    assert.ok(body.report.items.length > 0);
  });


  it('preserves selectedProducts for check_assai and check_normal create, reopen, and update', async () => {
    db.prepare(`
      INSERT INTO report_categories (id, name_pt, name_es, category_type, sort_order, is_active)
      VALUES (701, 'Montagem Assaí', 'Montaje Assaí', 'check_assai', 701, 1),
             (702, 'Montagem Normal', 'Montaje Normal', 'check_normal', 702, 1)
    `).run();
    db.prepare(`
      INSERT INTO report_tasks (id, category_id, name_pt, name_es, is_active)
      VALUES (1701, 701, 'Produtos Assaí', 'Productos Assaí', 1),
             (1702, 702, 'Produtos Normal', 'Productos Normal', 1)
    `).run();

    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        items: [
          { taskId: 1701, checked: true, selectedProducts: ['Nhoque Kg', 'Disgo 500g'] },
          { taskId: 1702, checked: true, selectedProducts: ['Nhoque 400g', 'Quadrada'] },
        ],
        temperatures: buildTemperatureReadings(db),
      },
    });

    assert.strictEqual(createRes.statusCode, 201);
    const reportId = JSON.parse(createRes.body).report.id;

    const detailRes = await app.inject({
      method: 'GET',
      url: `/api/reports/${reportId}`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(detailRes.statusCode, 200);
    const detail = JSON.parse(detailRes.body).report;
    assert.deepStrictEqual(
      detail.items.find((item: any) => item.task_id === 1701).selectedProducts,
      ['Nhoque Kg', 'Disgo 500g']
    );
    assert.deepStrictEqual(
      detail.items.find((item: any) => item.task_id === 1702).selectedProducts,
      ['Nhoque 400g', 'Quadrada']
    );

    const updateRes = await app.inject({
      method: 'PATCH',
      url: `/api/reports/${reportId}`,
      headers: { authorization: `Bearer ${token}` },
      payload: {
        items: [
          { taskId: 1701, checked: true, selectedProducts: ['Rolo kg'] },
          { taskId: 1702, checked: true, selectedProducts: ['Nhoque Kg'] },
        ],
      },
    });

    assert.strictEqual(updateRes.statusCode, 200);
    const updated = JSON.parse(updateRes.body).report;
    assert.deepStrictEqual(
      updated.items.find((item: any) => item.task_id === 1701).selectedProducts,
      ['Rolo kg']
    );
    assert.deepStrictEqual(
      updated.items.find((item: any) => item.task_id === 1702).selectedProducts,
      ['Nhoque Kg']
    );
  });

  it('GET /:id returns 404 for non-existent report', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/99999',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  // ---- Update Report ----

  it('PATCH /:id updates report fields', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'Original note',
        temperatures: buildTemperatureReadings(db),
      },
    });
    const reportId = JSON.parse(createRes.body).report.id;

    const updateRes = await app.inject({
      method: 'PATCH',
      url: `/api/reports/${reportId}`,
      headers: { authorization: `Bearer ${token}` },
      payload: {
        notes: 'Updated note',
        items: [{ taskId: 1, checked: true }, { taskId: 4, checked: true }],
      },
    });

    assert.strictEqual(updateRes.statusCode, 200);
    const body = JSON.parse(updateRes.body);
    assert.strictEqual(body.report.notes, 'Updated note');
    assert.strictEqual(body.report.items.length, 2);
  });

  it('PATCH /:id translates Spanish notes to Portuguese before saving', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'Tudo em ordem',
        temperatures: buildTemperatureReadings(db),
      },
    });
    const reportId = JSON.parse(createRes.body).report.id;

    const originalFetch = globalThis.fetch;
    globalThis.fetch = (() => Promise.reject(new Error('network unavailable'))) as typeof fetch;
    try {
      const updateRes = await app.inject({
        method: 'PATCH',
        url: `/api/reports/${reportId}`,
        headers: { authorization: `Bearer ${token}` },
        payload: { notes: 'Todo bien' },
      });

      assert.strictEqual(updateRes.statusCode, 200);
      const body = JSON.parse(updateRes.body);
      assert.strictEqual(body.report.notes, 'Tudo bem');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // ---- Edit Window ----

  it('PATCH /:id blocks edit for old reports (403)', async () => {
    // Insert an old report directly into the DB
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
       VALUES (999, 1, 'tarde', '2020-01-01', 'Old report', datetime('now', '-2 hours'))`
    ).run();

    const res = await app.inject({
      method: 'PATCH',
      url: '/api/reports/999',
      headers: { authorization: `Bearer ${token}` },
      payload: { notes: 'Should be blocked' },
    });

    assert.strictEqual(
      res.statusCode,
      403,
      `Expected 403 for old report, got ${res.statusCode}`
    );
    const body = JSON.parse(res.body);
    assert.ok(
      body.error.includes('somente leitura'),
      'Error should mention edit window'
    );
  });

  // ---- Export ----

  it('GET /:id/export generates WhatsApp text in Portuguese', async () => {
    const createRes = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno: 'tarde',
        notes: 'Tudo em ordem.',
        items: [
          { taskId: 1, checked: true },
          { taskId: 2, checked: false },
        ],
        temperatures: buildTemperatureReadings(db),
      },
    });
    const reportId = JSON.parse(createRes.body).report.id;

    const res = await app.inject({
      method: 'GET',
      url: `/api/reports/${reportId}/export`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(body.text, 'text should be present');
    assert.ok(typeof body.text === 'string');
    assert.ok(body.text.length > 50, 'export text should be substantial');

    // Verify Portuguese content
    assert.ok(body.text.includes('TARDE') || body.text.includes('NOITE'), 'should include turno header');
    const taskName = (db.prepare('SELECT name_pt FROM report_tasks WHERE id = 1').get() as { name_pt: string }).name_pt;
    assert.ok(body.text.includes(taskName), 'should include task name in Portuguese');
    assert.ok(body.text.includes('Tudo em ordem'), 'should include notes');

    // Verify emoji headers
    assert.ok(
      body.text.includes('🌅') || body.text.includes('🌙'),
      'should include turno emoji'
    );
  });

  it('GET /:id/export returns 404 for missing report', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/99999/export',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  it('GET /:id/export/txt is not provided', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/1/export/txt',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  // ---- Photo Management ----

  const fakeJpeg = Buffer.from([
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46,
    0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
    0x00, 0x48, 0x00, 0x00, 0xff, 0xd9,
  ]);

  function multipartBody(
    fieldname: string,
    filename: string,
    mimeType: string,
    data: Buffer
  ): { body: Buffer; boundary: string } {
    const boundary = `----FormBoundary${randomUUID()}`;
    const CRLF = '\r\n';
    const parts: Buffer[] = [];

    parts.push(Buffer.from(`--${boundary}${CRLF}`));
    parts.push(Buffer.from(`Content-Disposition: form-data; name="${fieldname}"; filename="${filename}"${CRLF}`));
    parts.push(Buffer.from(`Content-Type: ${mimeType}${CRLF}${CRLF}`));
    parts.push(data);
    parts.push(Buffer.from(`${CRLF}--${boundary}--${CRLF}`));

    return { body: Buffer.concat(parts), boundary };
  }

  async function createTestReport(turno: 'tarde' | 'noite' = 'tarde'): Promise<number> {
    const res = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: {
        turno,
        notes: 'Photo test report',
        temperatures: buildTemperatureReadings(db),
      },
    });
    return JSON.parse(res.body).report.id;
  }

  it('POST /:id/photos uploads a valid JPEG and returns 201', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'test-photo.jpg', 'image/jpeg', fakeJpeg);

    const res = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 201);
    const result = JSON.parse(res.body);
    assert.ok(result.photo, 'photo should be present');
    assert.strictEqual(result.photo.report_id, reportId);
    assert.strictEqual(result.photo.mime_type, 'image/jpeg');
    assert.ok(result.photo.file_size > 0, 'file_size should be > 0');
    assert.ok(result.photo.file_path, 'file_path should be set');
    assert.ok(result.photo.file_path.endsWith('.jpg'), 'file_path should end with .jpg');
    assert.ok(result.photo.id, 'photo should have an id');
    assert.ok(result.photo.url.includes(`/api/reports/photos/${result.photo.id}`));
    assert.ok(result.photo.publicUrl.includes('/p/'));
  });

  it('POST /:id/photos rejects upload > 5MB with 413', async () => {
    const reportId = await createTestReport();
    // Create a 6MB fake PNG (well over the 5MB limit)
    const bigFile = Buffer.alloc(6 * 1024 * 1024, 0x41);
    // Put PNG header so it passes MIME sniffing check
    bigFile[0] = 0x89;
    bigFile[1] = 0x50; // P
    bigFile[2] = 0x4e; // N
    bigFile[3] = 0x47; // G
    const { body, boundary } = multipartBody('file', 'large.png', 'image/png', bigFile);

    const res = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 413, `Expected 413, got ${res.statusCode}: ${JSON.parse(res.body).error}`);
    const result = JSON.parse(res.body);
    assert.ok(result.error.toLowerCase().includes('large') || result.error.toLowerCase().includes('5mb') || result.error.toLowerCase().includes('too large'),
      `Error should mention file size, got: ${result.error}`);
  });

  it('POST /:id/photos rejects non-image MIME type with 415', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'doc.pdf', 'application/pdf', Buffer.from('PDF content'));

    const res = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 415);
    const result = JSON.parse(res.body);
    assert.ok(result.error.toLowerCase().includes('image'),
      `Error should mention image type, got: ${result.error}`);
  });

  it('POST /:id/photos rejects MIME and magic-byte mismatch with 415', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'fake.png', 'image/png', fakeJpeg);

    const res = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 415);
  });

  it('POST /:id/photos rejects the sixth photo with 409', async () => {
    const reportId = await createTestReport();

    for (let i = 0; i < 5; i += 1) {
      const { body, boundary } = multipartBody('file', `photo-${i}.jpg`, 'image/jpeg', fakeJpeg);
      const upload = await app.inject({
        method: 'POST',
        url: `/api/reports/${reportId}/photos`,
        headers: { authorization: `Bearer ${token}`, 'content-type': `multipart/form-data; boundary=${boundary}` },
        payload: body,
      });
      assert.strictEqual(upload.statusCode, 201);
    }

    const { body, boundary } = multipartBody('file', 'photo-6.jpg', 'image/jpeg', fakeJpeg);
    const res = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: { authorization: `Bearer ${token}`, 'content-type': `multipart/form-data; boundary=${boundary}` },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 409);
  });

  it('POST /:id/photos blocks upload when edit window expired (403)', async () => {
    // Insert an old report directly
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
       VALUES (996, 1, 'tarde', '2020-01-01', 'Old report for photo test', datetime('now', '-2 hours'))`
    ).run();

    const { body, boundary } = multipartBody('file', 'test.jpg', 'image/jpeg', fakeJpeg);

    const res = await app.inject({
      method: 'POST',
      url: '/api/reports/996/photos',
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.strictEqual(res.statusCode, 403);
    const result = JSON.parse(res.body);
    assert.ok(result.error.includes('somente leitura'), `Expected edit window error, got: ${result.error}`);
  });

  it('GET /:id/photos returns photos for a report', async () => {
    const reportId = await createTestReport();

    // Upload two photos
    const { body: b1, boundary: bd1 } = multipartBody('file', 'a.jpg', 'image/jpeg', fakeJpeg);
    await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${bd1}`,
      },
      payload: b1,
    });

    const { body: b2, boundary: bd2 } = multipartBody('file', 'b.jpg', 'image/jpeg', fakeJpeg);
    await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${bd2}`,
      },
      payload: b2,
    });

    const res = await app.inject({
      method: 'GET',
      url: `/api/reports/${reportId}/photos`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const result = JSON.parse(res.body);
    assert.ok(Array.isArray(result.photos), 'photos should be an array');
    assert.strictEqual(result.photos.length, 2, 'should have 2 photos');
    assert.strictEqual(result.photos[0].report_id, reportId);
    // Both have same source data (fakeJpeg), so both may detect as image/jpeg
    const mimeTypes = new Set(result.photos.map((p: any) => p.mime_type));
    assert.ok(mimeTypes.size >= 1, 'should have at least one distinct mime type');
  });

  it('GET /photos/:photoId serves photo file with correct content-type', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'serve-test.jpg', 'image/jpeg', fakeJpeg);

    const uploadRes = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    const photoId = JSON.parse(uploadRes.body).photo.id;

    const res = await app.inject({
      method: 'GET',
      url: `/api/reports/photos/${photoId}`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const ct = res.headers['content-type'];
    assert.ok(ct, 'Content-Type should be present');
    assert.ok(ct.includes('image/jpeg'), `Content-Type should be image/jpeg, got: ${ct}`);
    assert.deepStrictEqual(res.rawPayload, fakeJpeg);
  });

  it('GET /photos/public/:token serves photo without authentication', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'public-test.jpg', 'image/jpeg', fakeJpeg);

    const uploadRes = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: { authorization: `Bearer ${token}`, 'content-type': `multipart/form-data; boundary=${boundary}` },
      payload: body,
    });
    const photo = JSON.parse(uploadRes.body).photo;
    const tokenPart = photo.publicUrl.split('/').pop();

    const res = await app.inject({ method: 'GET', url: `/p/${tokenPart}` });

    assert.strictEqual(res.statusCode, 200);
    assert.ok(String(res.headers['content-type']).includes('image/jpeg'));
    assert.deepStrictEqual(res.rawPayload, fakeJpeg);
  });

  it('GET /photos/:photoId returns 404 for non-existent photo', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/photos/99999',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 404);
  });

  it('GET /photos/:photoId requires authentication (401)', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/reports/photos/1',
    });

    assert.strictEqual(res.statusCode, 401);
  });

  it('DELETE /photos/:photoId removes a photo and returns success', async () => {
    const reportId = await createTestReport();
    const { body, boundary } = multipartBody('file', 'delete-me.jpg', 'image/jpeg', fakeJpeg);

    const uploadRes = await app.inject({
      method: 'POST',
      url: `/api/reports/${reportId}/photos`,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    const photoId = JSON.parse(uploadRes.body).photo.id;

    const res = await app.inject({
      method: 'DELETE',
      url: `/api/reports/photos/${photoId}`,
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 200);
    const result = JSON.parse(res.body);
    assert.strictEqual(result.success, true);

    // Verify it's gone from DB
    const listRes = await app.inject({
      method: 'GET',
      url: `/api/reports/${reportId}/photos`,
      headers: { authorization: `Bearer ${token}` },
    });
    assert.strictEqual(JSON.parse(listRes.body).photos.length, 0);
  });

  it('DELETE /photos/:photoId blocks deletion when edit window expired (403)', async () => {
    // Insert old report + photo
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
       VALUES (997, 1, 'tarde', '2020-01-01', 'Old report', datetime('now', '-2 hours'))`
    ).run();
    db.prepare(
      `INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type)
       VALUES (997, 997, 'old-photo.jpg', 1000, 'image/jpeg')`
    ).run();

    const res = await app.inject({
      method: 'DELETE',
      url: '/api/reports/photos/997',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(res.statusCode, 403);
    const result = JSON.parse(res.body);
    assert.ok(result.error.includes('somente leitura'), `Expected edit window error, got: ${result.error}`);
  });

  it('GET /history filters reports and returns server permission flags', async () => {
    db.prepare(`INSERT INTO reports (id, user_id, turno, report_date, notes, created_at) VALUES (9101, 1, 'tarde', '2026-06-18', 'History A', datetime('now', '-30 minutes'))`).run();
    db.prepare(`INSERT INTO reports (id, user_id, turno, report_date, notes, created_at) VALUES (9102, 2, 'noite', '2026-06-17', 'History B', datetime('now', '-2 hours'))`).run();

    const res = await app.inject({ method: 'GET', url: '/api/reports/history?date=2026-06-18&pageSize=30', headers: { authorization: `Bearer ${token}` } });
    assert.strictEqual(res.statusCode, 200);
    const body = JSON.parse(res.body);
    assert.ok(body.items.some((item: any) => item.id === 9101));
    assert.ok(!body.items.some((item: any) => item.id === 9102));
    const item = body.items.find((entry: any) => entry.id === 9101);
    assert.strictEqual(item.canEdit, true);
    assert.strictEqual(item.canDeactivate, true);
    assert.strictEqual(item.canDelete, true);
  });

  it('GET /history paginates more than 30 reports and combines month/user filters', async () => {
    const insert = db.prepare(`
      INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `);

    for (let i = 0; i < 35; i += 1) {
      insert.run(30000 + i, 1, i % 2 === 0 ? 'tarde' : 'noite', '2026-04-15', `April Wilkin ${i}`, `2026-04-15 08:${String(i).padStart(2, '0')}:00`);
    }
    for (let i = 0; i < 5; i += 1) {
      insert.run(30100 + i, 2, 'tarde', '2026-04-15', `April Other ${i}`, `2026-04-15 09:${String(i).padStart(2, '0')}:00`);
      insert.run(30200 + i, 1, 'tarde', '2026-05-15', `May Wilkin ${i}`, `2026-05-15 09:${String(i).padStart(2, '0')}:00`);
    }

    const firstPage = await app.inject({
      method: 'GET',
      url: '/api/reports/history?month=2026-04&userId=1&page=1&pageSize=30',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(firstPage.statusCode, 200);
    const pageOne = JSON.parse(firstPage.body);
    assert.strictEqual(pageOne.pagination.total, 35);
    assert.strictEqual(pageOne.pagination.totalPages, 2);
    assert.strictEqual(pageOne.items.length, 30);
    assert.ok(pageOne.items.every((item: any) => item.user.id === 1));
    assert.ok(pageOne.items.every((item: any) => item.report_date.startsWith('2026-04')));
    assert.ok(!pageOne.items.some((item: any) => item.id >= 30100 && item.id < 30200), 'other users should be excluded');
    assert.ok(!pageOne.items.some((item: any) => item.id >= 30200 && item.id < 30300), 'other months should be excluded');

    const secondPage = await app.inject({
      method: 'GET',
      url: '/api/reports/history?month=2026-04&userId=1&page=2&pageSize=30',
      headers: { authorization: `Bearer ${token}` },
    });

    assert.strictEqual(secondPage.statusCode, 200);
    const pageTwo = JSON.parse(secondPage.body);
    assert.strictEqual(pageTwo.items.length, 5);
    assert.deepStrictEqual(pageTwo.items.map((item: any) => item.id), [30004, 30003, 30002, 30001, 30000]);
  });

  it('admin deactivate and delete report history records transactionally', async () => {
    db.prepare(`INSERT INTO reports (id, user_id, turno, report_date, notes) VALUES (9103, 1, 'tarde', '2026-06-18', 'Delete me')`).run();
    db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (9103, 1, 1)`).run();
    db.prepare(`INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type) VALUES (9103, 9103, 'x.jpg', 10, 'image/jpeg')`).run();

    const deactivated = await app.inject({ method: 'PATCH', url: '/api/reports/9103/deactivate', headers: { authorization: `Bearer ${token}` } });
    assert.strictEqual(deactivated.statusCode, 200);
    assert.strictEqual((db.prepare('SELECT is_active FROM reports WHERE id = 9103').get() as any).is_active, 0);

    const deleted = await app.inject({ method: 'DELETE', url: '/api/reports/9103', headers: { authorization: `Bearer ${token}` } });
    assert.strictEqual(deleted.statusCode, 204);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM reports WHERE id = 9103').get() as any).count, 0);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM report_items WHERE report_id = 9103').get() as any).count, 0);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS count FROM report_photos WHERE report_id = 9103').get() as any).count, 0);
  });


  it('cleanupExpiredPhotos deletes expired photo rows and files', async () => {
    const { cleanupExpiredPhotos } = await import('./reports.lifecycle.service.js');
    const { writeFileSync, existsSync } = await import('node:fs');
    const { join } = await import('node:path');
    const result = await buildTestApp();
    const localApp = result.app;
    const localDb = result.db;
    try {
      const filePath = join(result.photosDir, 'expired.jpg');
      writeFileSync(filePath, fakeJpeg);
      localDb.prepare(`INSERT INTO reports (id, user_id, turno, report_date, notes, created_at) VALUES (9701, 1, 'tarde', '2026-01-01', 'Expired photo report', datetime('now', '-31 days'))`).run();
      localDb.prepare(`INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type, public_token, created_at) VALUES (9701, 9701, 'expired.jpg', ?, 'image/jpeg', 'expired-token', datetime('now', '-31 days'))`).run(fakeJpeg.length);

      const cleanup = cleanupExpiredPhotos(localDb, result.photosDir, 30);

      assert.strictEqual(cleanup.deleted, 1);
      assert.strictEqual((localDb.prepare('SELECT COUNT(*) AS count FROM report_photos WHERE id = 9701').get() as any).count, 0);
      assert.strictEqual(existsSync(filePath), false);
    } finally {
      await localApp.close();
      localDb.close();
    }
  });


  it('cleanupExpiredPhotos keeps fresh photo rows and files', async () => {
    const { cleanupExpiredPhotos } = await import('./reports.lifecycle.service.js');
    const { writeFileSync, existsSync } = await import('node:fs');
    const { join } = await import('node:path');
    const result = await buildTestApp();
    const localApp = result.app;
    const localDb = result.db;
    try {
      const filePath = join(result.photosDir, 'fresh.jpg');
      writeFileSync(filePath, fakeJpeg);
      localDb.prepare(`INSERT INTO reports (id, user_id, turno, report_date, notes, created_at) VALUES (9702, 1, 'tarde', '2026-01-01', 'Fresh photo report', datetime('now'))`).run();
      localDb.prepare(`INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type, public_token, created_at) VALUES (9702, 9702, 'fresh.jpg', ?, 'image/jpeg', 'fresh-token', datetime('now', '-29 days'))`).run(fakeJpeg.length);

      const cleanup = cleanupExpiredPhotos(localDb, result.photosDir, 30);

      assert.strictEqual(cleanup.deleted, 0);
      assert.strictEqual(cleanup.errors, 0);
      assert.strictEqual((localDb.prepare('SELECT COUNT(*) AS count FROM report_photos WHERE id = 9702').get() as any).count, 1);
      assert.strictEqual(existsSync(filePath), true);
    } finally {
      await localApp.close();
      localDb.close();
    }
  });

  it('POST / prevents creating duplicate reports for the same turno on the same date', async () => {
    // Deactivate any existing active reports for today to avoid foreign key constraints
    db.prepare('UPDATE reports SET is_active = 0').run();

    const payload1 = {
      turno: 'tarde',
      notes: 'First report for tarde',
      temperatures: buildTemperatureReadings(db),
    };

    const payload2 = {
      turno: 'tarde',
      notes: 'Second report for tarde',
      temperatures: buildTemperatureReadings(db),
    };

    const payload3 = {
      turno: 'noite',
      notes: 'First report for noite',
      temperatures: buildTemperatureReadings(db),
    };

    const res1 = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: payload1,
    });
    assert.strictEqual(res1.statusCode, 201);

    const res2 = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}`, 'accept-language': 'es' },
      payload: payload2,
    });
    assert.strictEqual(res2.statusCode, 400);
    const body2 = JSON.parse(res2.body);
    assert.strictEqual(body2.error, 'Ya existe un reporte para este turno hoy.');

    const res2Pt = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}`, 'accept-language': 'pt-BR' },
      payload: payload2,
    });
    assert.strictEqual(res2Pt.statusCode, 400);
    const body2Pt = JSON.parse(res2Pt.body);
    assert.strictEqual(body2Pt.error, 'Já existe um relatório para este turno hoje.');

    const res3 = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: { authorization: `Bearer ${token}` },
      payload: payload3,
    });
    assert.strictEqual(res3.statusCode, 201);
  });

});
