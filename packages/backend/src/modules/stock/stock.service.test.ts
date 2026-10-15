import { describe, it } from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { verifyReferencePrerequisites } from '../../db/reference-data.js';
import { MASSAS, STOCK_CATALOG, TERCEIRIZADOS } from './stock.catalog.js';
import { addStockEntry, resetStockProduct, stockDayView, stockHistory } from './stock.service.js';

/**
 * The counting rules, asserted against the database the service actually writes.
 *
 * The unit that matters here is the box: the operator counts boxes at the counter, and the units are a
 * multiplication of what was counted, never a second number that could drift away from it. Every case
 * below checks the effect on the stored row, not just the value handed back, because a service that
 * answered correctly while writing something else would pass a test that only read its return value.
 */
function freshDb() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  verifyReferencePrerequisites(db);
  // Two counters, so a case can tell one operator's entry from another's in the log.
  const insertCounter = db.prepare(
    'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, (SELECT id FROM roles WHERE name = ?), 1)'
  );
  insertCounter.run('counter-one', 'not-a-hash', 'counter-one', 'Administrador');
  insertCounter.run('counter-two', 'not-a-hash', 'counter-two', 'Administrador');
  return db;
}

describe('stock catalog', () => {
  it('carries the fifteen tercerizados and the ten massas of the approved catalog', () => {
    assert.strictEqual(TERCEIRIZADOS.length, 15);
    assert.strictEqual(MASSAS.length, 10);
    assert.strictEqual(STOCK_CATALOG.length, 25);
  });

  it('keeps the codes and boxes per box the catalogue was handed over with', () => {
    const byCode = new Map(STOCK_CATALOG.map((p) => [p.code, p]));
    assert.deepStrictEqual(byCode.get('787'), { code: '787', name: 'CANUDO', unitsPerBox: 30, category: 'Terceirizados' });
    assert.deepStrictEqual(byCode.get('1800'), { code: '1800', name: 'PIZZA BROTINHO 55G', unitsPerBox: 50, category: 'Terceirizados' });
    assert.deepStrictEqual(byCode.get('1047'), { code: '1047', name: 'MASSA ROLO 2KG', unitsPerBox: 6, category: 'Massas' });
    assert.deepStrictEqual(byCode.get('1616'), { code: '1616', name: 'MASSA TOKO', unitsPerBox: 10, category: 'Massas' });
  });
});

describe('a day of counting', () => {
  it('answers every product of the catalog at zero before anything was counted', () => {
    const view = stockDayView(freshDb(), '2026-10-15');
    assert.strictEqual(view.date, '2026-10-15');
    assert.strictEqual(view.products.length, 25);
    assert.deepStrictEqual(view.products.filter((p) => p.boxes !== 0), []);
    assert.strictEqual(view.totalBoxes, 0);
    assert.strictEqual(view.totalUnits, 0);
  });

  it('adds boxes cumulatively and derives the units from the boxes counted', () => {
    const db = freshDb();
    addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 3, userId: 1 });
    addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 2, userId: 1 });
    const canudo = stockDayView(db, '2026-10-15').products.find((p) => p.code === '787')!;
    assert.strictEqual(canudo.boxes, 5);
    assert.strictEqual(canudo.units, 150);
    const stored = db.prepare('SELECT boxes_total, units_total FROM stock_counts WHERE product_code = ?').get('787') as { boxes_total: number; units_total: number };
    assert.deepStrictEqual(stored, { boxes_total: 5, units_total: 150 });
  });

  it('keeps a log of what was added, with the running total of each entry', () => {
    const db = freshDb();
    addStockEntry(db, { date: '2026-10-15', productCode: '1047', boxesAdded: 4, userId: 1 });
    addStockEntry(db, { date: '2026-10-15', productCode: '1047', boxesAdded: 1, userId: 2 });
    const row = db.prepare('SELECT entries_log FROM stock_counts WHERE product_code = ?').get('1047') as { entries_log: string };
    const entries = JSON.parse(row.entries_log) as Array<{ boxesAdded: number; boxesAfter: number; userId: number }>;
    assert.strictEqual(entries.length, 2);
    assert.deepStrictEqual(entries.map((e) => [e.boxesAdded, e.boxesAfter, e.userId]), [[4, 4, 1], [1, 5, 2]]);
  });

  it('starts a different day at zero even when another day already has counts', () => {
    const db = freshDb();
    addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 9, userId: 1 });
    assert.strictEqual(stockDayView(db, '2026-10-16').totalBoxes, 0);
  });

  it('refuses a product the catalog does not carry and a box count that adds nothing', () => {
    const db = freshDb();
    assert.throws(() => addStockEntry(db, { date: '2026-10-15', productCode: '999999', boxesAdded: 1, userId: 1 }), /product/i);
    assert.throws(() => addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 0, userId: 1 }), /boxes/i);
    assert.throws(() => addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 2.5, userId: 1 }), /boxes/i);
    assert.strictEqual((db.prepare('SELECT COUNT(*) AS n FROM stock_counts').get() as { n: number }).n, 0);
  });

  it('resets one product to zero without forgetting what had been counted', () => {
    const db = freshDb();
    addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 5, userId: 1 });
    addStockEntry(db, { date: '2026-10-15', productCode: '1800', boxesAdded: 2, userId: 1 });
    resetStockProduct(db, { date: '2026-10-15', productCode: '787', userId: 1 });
    const view = stockDayView(db, '2026-10-15');
    const canudo = view.products.find((p) => p.code === '787')!;
    const brotinho = view.products.find((p) => p.code === '1800')!;
    assert.strictEqual(canudo.boxes, 0);
    assert.strictEqual(canudo.units, 0);
    assert.strictEqual(brotinho.boxes, 2);
    const row = db.prepare('SELECT entries_log FROM stock_counts WHERE product_code = ?').get('787') as { entries_log: string };
    const entries = JSON.parse(row.entries_log) as Array<{ kind: string }>;
    assert.deepStrictEqual(entries.map((e) => e.kind), ['add', 'reset']);
  });

  it('lists the days that were counted, newest first', () => {
    const db = freshDb();
    addStockEntry(db, { date: '2026-10-14', productCode: '787', boxesAdded: 1, userId: 1 });
    addStockEntry(db, { date: '2026-10-15', productCode: '787', boxesAdded: 2, userId: 1 });
    addStockEntry(db, { date: '2026-10-15', productCode: '1800', boxesAdded: 3, userId: 1 });
    const history = stockHistory(db);
    assert.deepStrictEqual(history.map((h) => h.date), ['2026-10-15', '2026-10-14']);
    assert.deepStrictEqual([history[0].products, history[0].boxes, history[0].units], [2, 5, 210]);
  });
});
