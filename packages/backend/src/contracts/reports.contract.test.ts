import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import type { z } from 'zod';
import {
  CategoriesResponseSchema,
  ReportHistoryResponseSchema,
  ReportResponseSchema,
  ReportsResponseSchema,
  TurnoResponseSchema,
} from '../modules/reports/reports.schema.js';
import { buildTestApp, buildTemperatureReadings, type TestFixtures } from '../test-helper.js';
import { TextResponseSchema } from './common.schema.js';

function expectMatch<T extends z.ZodTypeAny>(schema: T, body: string): z.infer<T> {
  const parsed = schema.safeParse(JSON.parse(body));
  assert.ok(parsed.success, `response does not match its schema: ${JSON.stringify(parsed.error?.issues)}`);
  return parsed.data;
}

describe('reports responses match their declared schemas', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;
  let auth: { authorization: string };
  let reportId: number;
  let temperatureTaskId: number;

  before(async () => {
    const result = await buildTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;

    const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });
    assert.strictEqual(login.statusCode, 200, login.body);
    auth = { authorization: `Bearer ${JSON.parse(login.body).token}` };

    // A report built from the seeded catalog, so the detail and export paths have real content.
    const task = db
      .prepare(
        `SELECT rt.id FROM report_tasks rt
         JOIN report_categories rc ON rc.id = rt.category_id
         WHERE rc.category_type = 'temperature' AND rt.is_active = 1
         ORDER BY rt.id LIMIT 1`,
      )
      .get() as { id: number };
    temperatureTaskId = task.id;

    const otherTask = db
      .prepare(
        `SELECT rt.id FROM report_tasks rt
         JOIN report_categories rc ON rc.id = rt.category_id
         WHERE rc.category_type = 'check' AND rt.is_active = 1
         ORDER BY rt.id LIMIT 1`,
      )
      .get() as { id: number };

    const created = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: auth,
      payload: {
        turno: 'tarde',
        items: [{ taskId: otherTask.id, checked: true }],
        temperatures: buildTemperatureReadings(db),
      },
    });
    assert.strictEqual(created.statusCode, 201, created.body);
    reportId = JSON.parse(created.body).report.id;
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('lists categories with the declared shape', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/reports/categories', headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);

    const body = expectMatch(CategoriesResponseSchema, res.body);
    assert.ok(body.categories.length > 0, 'the seeded catalog came back empty');
    assert.ok(body.categories.some((category) => category.tasks.length > 0), 'no category carried tasks');
  });

  it('creates and reads a report with the declared shape', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/reports/${reportId}`, headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);

    const body = expectMatch(ReportResponseSchema, res.body);
    assert.strictEqual(body.report.id, reportId);
    assert.ok(body.report.items.length > 0, 'the report lost its items');
    assert.ok(body.report.temperatures.length > 0, 'the report lost its temperature readings');
  });

  it('lists and paginates reports with the declared shapes', async () => {
    const list = await app.inject({ method: 'GET', url: '/api/reports?period=30days', headers: auth });
    assert.strictEqual(list.statusCode, 200, list.body);
    expectMatch(ReportsResponseSchema, list.body);

    const history = await app.inject({ method: 'GET', url: '/api/reports/history', headers: auth });
    assert.strictEqual(history.statusCode, 200, history.body);
    const body = expectMatch(ReportHistoryResponseSchema, history.body);
    assert.ok(body.items.length > 0, 'history returned nothing for a report just created');
  });

  it('reports the server-detected turno with the declared shape', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/reports/turno', headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);
    expectMatch(TurnoResponseSchema, res.body);
  });

  it('renders the WhatsApp text on the server', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/reports/${reportId}/export`, headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);

    const body = expectMatch(TextResponseSchema, res.body);
    assert.ok(body.text.length > 0, 'the export produced no text');
  });

  // The finding B2.3a fixes: `report_items.selected_products` was fed to an unguarded JSON.parse, so
  // one corrupt row turned reading a report into a driver error. The parse is now validated and
  // fails soft, which is what this pins.
  it('reads a report whose stored product list is corrupt instead of failing', async () => {
    db.prepare('UPDATE report_items SET selected_products = ? WHERE report_id = ?')
      .run('{ this is not json', reportId);

    const res = await app.inject({ method: 'GET', url: `/api/reports/${reportId}`, headers: auth });

    assert.notStrictEqual(res.statusCode, 500, `a corrupt row broke the read: ${res.body}`);
    const body = expectMatch(ReportResponseSchema, res.body);
    const corrupted = body.report.items.find((item) => item.selectedProducts !== undefined);
    assert.ok(corrupted, 'the corrupt row disappeared from the response');
    assert.deepStrictEqual(corrupted.selectedProducts, [], 'a corrupt list must degrade to empty, not throw');
  });

  it('exports a report whose stored product list is corrupt instead of failing', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/reports/${reportId}/export`, headers: auth });

    assert.notStrictEqual(res.statusCode, 500, `a corrupt row broke the export: ${res.body}`);
    expectMatch(TextResponseSchema, res.body);
  });

  it('uses the report shape for a temperature task detail too', async () => {
    // A second report that carries a temperature task, to exercise the location_es path.
    const created = await app.inject({
      method: 'POST',
      url: '/api/reports',
      headers: auth,
      payload: {
        turno: 'noite',
        items: [{ taskId: temperatureTaskId, checked: true }],
        temperatures: buildTemperatureReadings(db),
      },
    });
    assert.strictEqual(created.statusCode, 201, created.body);

    const res = await app.inject({
      method: 'GET',
      url: `/api/reports/${JSON.parse(created.body).report.id}`,
      headers: auth,
    });
    assert.strictEqual(res.statusCode, 200, res.body);
    const body = expectMatch(ReportResponseSchema, res.body);
    assert.ok(
      body.report.items.some((item) => item.task_type === 'temperature'),
      'the temperature task did not come back with its type',
    );
  });

  // `location_es` is resolved through a LEFT JOIN, so it is the one `_es` field the schema declares
  // nullable. A reading whose location matches no task is what produces the null, and this is the
  // only place that path is exercised. Placed last because it adds a reading the export tests above
  // would otherwise see.
  it('returns a null Spanish name for a reading whose location matches no task', async () => {
    db.prepare('INSERT INTO report_temperatures (report_id, location, reading_index, value) VALUES (?,?,?,?)')
      .run(reportId, 'Câmara Sem Tarefa Correspondente', 1, -5);

    const res = await app.inject({ method: 'GET', url: `/api/reports/${reportId}`, headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);

    const body = expectMatch(ReportResponseSchema, res.body);
    const orphan = body.report.temperatures.find(
      (reading) => reading.location === 'Câmara Sem Tarefa Correspondente',
    );
    assert.ok(orphan, 'the orphaned reading is missing from the response');
    assert.strictEqual(orphan.location_es, null, 'location_es must be null when no task matches');
    assert.ok(
      body.report.temperatures.some((reading) => reading.location_es !== null),
      'a reading that DOES match a task must still carry its Spanish name',
    );
  });
});
