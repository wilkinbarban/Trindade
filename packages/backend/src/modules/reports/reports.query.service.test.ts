import { afterEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { getCategories, getReport, listReports } from './reports.query.service.js';
import { closeReportDatabases, setupReportsDb } from './reports-test-helper.js';

afterEach(closeReportDatabases);

describe('reports query service characterization', () => {
  it('returns active categories with task arrays and excludes inactive categories', () => {
    const db = setupReportsDb();
    db.prepare("UPDATE report_categories SET is_active = 0 WHERE id = 1").run();
    const categories = getCategories(db);
    assert.ok(categories.length > 0);
    assert.ok(categories.every((category) => category.id !== 1 && Array.isArray(category.tasks)));
    assert.ok(categories.some((category) => category.tasks.length > 0));
  });

  it('enriches report details and projects owner versus future permissions', () => {
    const db = setupReportsDb();
    const result = db.prepare(
      `INSERT INTO reports (user_id, turno, report_date)
       VALUES (1, 'tarde', '2026-01-01')`
    ).run();
    const reportId = Number(result.lastInsertRowid);
    const owned = getReport(db, reportId, { sub: 1, role: 'Administrador' })!;
    assert.equal(owned.canEdit, true);
    db.prepare("UPDATE reports SET created_at = datetime('now', '+1 hour') WHERE id = ?").run(reportId);
    const future = getReport(db, reportId, { sub: 1, role: 'Administrador' })!;
    assert.equal(future.canEdit, false);
    assert.equal(future.readOnly, true);
  });

  it('lists custom date range reports in descending creation order', () => {
    const db = setupReportsDb();
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, created_at)
       VALUES (10, 1, 'tarde', '2026-01-01', '2026-01-01 10:00:00'),
              (11, 2, 'noite', '2026-01-02', '2026-01-02 12:00:00')`
    ).run();
    const reports = listReports(db, { period: 'custom', from: '2026-01-01', to: '2026-01-02' });
    assert.deepEqual(reports.map((report) => report.id), [11, 10]);
    assert.equal(reports[0].user.display_name, 'Other');
  });
});
