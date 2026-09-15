import { afterEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { createReport, updateReport } from './reports.command.service.js';
import { closeReportDatabases, completeTemperatures, setupReportsDb } from './reports-test-helper.js';

afterEach(closeReportDatabases);

describe('reports command service characterization', () => {
  it('creates selected products with the complete required temperature set and rejects duplicates', async () => {
    const db = setupReportsDb();
    const taskId = (db.prepare("SELECT id FROM report_tasks WHERE category_id = 1 LIMIT 1").get() as { id: number }).id;
    const body = { turno: 'tarde' as const, items: [{ taskId, checked: true, selectedProducts: ['Item A'] }], temperatures: completeTemperatures(db) };
    const report = await createReport(db, 1, body);
    assert.deepEqual(report.items[0].selectedProducts, ['Item A']);
    await assert.rejects(createReport(db, 1, body), { message: 'reportAlreadyExists' });
  });

  it('rejects incomplete temperatures and maps invalid item references to 400', async () => {
    const db = setupReportsDb();
    await assert.rejects(
      createReport(db, 1, { turno: 'tarde', items: [], temperatures: completeTemperatures(db).slice(1) }),
      { message: 'Falta colocar as temperaturas no relatório.' }
    );
    await assert.rejects(
      createReport(db, 1, { turno: 'tarde', items: [{ taskId: 9999, checked: true }], temperatures: completeTemperatures(db) }),
      { message: 'Violação de restrição de banco de dados (ID inválido ou duplicado).' }
    );
  });

  it('updates selected products and rejects non-owner updates without network translation', async () => {
    const db = setupReportsDb();
    const taskId = (db.prepare("SELECT id FROM report_tasks WHERE category_id = 1 LIMIT 1").get() as { id: number }).id;
    const report = await createReport(db, 1, { turno: 'tarde', notes: '  local note  ', items: [], temperatures: completeTemperatures(db) });
    const updated = await updateReport(db, report.id, { items: [{ taskId, checked: false, selectedProducts: ['Item B'] }] }, { sub: 1, role: 'Administrador' });
    assert.ok(!('error' in updated));
    if (!('error' in updated)) assert.deepEqual(updated.items[0].selectedProducts, ['Item B']);
    const denied = await updateReport(db, report.id, { notes: 'unchanged' }, { sub: 2, role: 'Trabalhador' });
    assert.deepEqual(denied, { error: 'Registro somente leitura. Apenas o criador pode editar durante a primeira hora.', status: 403 });
  });
});
