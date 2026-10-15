import type Database from 'better-sqlite3';
import { STOCK_CATALOG, findStockProduct } from './stock.catalog.js';

/**
 * The counting rules.
 *
 * The operator counts boxes; the units are a multiplication of what was counted, recomputed on every
 * write, so the two numbers can never disagree. The log is the audit trail: it keeps every entry and
 * every reset with the running total after it, which is why a reset zeroes the totals without erasing
 * what had been counted before it.
 */
export interface StockLogEntry {
  kind: 'add' | 'reset';
  boxesAdded: number;
  boxesAfter: number;
  unitsAfter: number;
  userId: number;
  at: string;
}

export interface StockProductView {
  code: string;
  name: string;
  category: string;
  unitsPerBox: number;
  boxes: number;
  units: number;
}

export interface StockDayView {
  date: string;
  products: StockProductView[];
  totalBoxes: number;
  totalUnits: number;
}

export interface StockHistoryDay {
  date: string;
  products: number;
  boxes: number;
  units: number;
}

interface StockRow {
  product_code: string;
  boxes_total: number;
  units_total: number;
  entries_log: string;
}

function nowIso(): string {
  return new Date().toISOString();
}

function readRow(db: Database.Database, date: string, code: string): StockRow | undefined {
  return db
    .prepare('SELECT product_code, boxes_total, units_total, entries_log FROM stock_counts WHERE count_date = ? AND product_code = ?')
    .get(date, code) as StockRow | undefined;
}

function readLog(text: string): StockLogEntry[] {
  try {
    const parsed = JSON.parse(text) as StockLogEntry[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // A log that cannot be read is treated as empty rather than as a reason to refuse a count: the
    // totals are what the counter is looking at, and they live in their own columns.
    return [];
  }
}

export function stockDayView(db: Database.Database, date: string): StockDayView {
  const rows = db
    .prepare('SELECT product_code, boxes_total, units_total, entries_log FROM stock_counts WHERE count_date = ?')
    .all(date) as StockRow[];
  const byCode = new Map(rows.map((row) => [row.product_code, row]));

  const products: StockProductView[] = STOCK_CATALOG.map((product) => {
    const row = byCode.get(product.code);
    return {
      code: product.code,
      name: product.name,
      category: product.category,
      unitsPerBox: product.unitsPerBox,
      boxes: row?.boxes_total ?? 0,
      units: row?.units_total ?? 0,
    };
  });

  return {
    date,
    products,
    totalBoxes: products.reduce((sum, product) => sum + product.boxes, 0),
    totalUnits: products.reduce((sum, product) => sum + product.units, 0),
  };
}

export function addStockEntry(
  db: Database.Database,
  input: { date: string; productCode: string; boxesAdded: number; userId: number },
): StockProductView {
  const product = findStockProduct(input.productCode);
  if (product === undefined) {
    throw new Error(`unknown product: ${input.productCode}`);
  }
  if (!Number.isInteger(input.boxesAdded) || input.boxesAdded <= 0) {
    throw new Error(`boxes must be a positive whole number, got ${input.boxesAdded}`);
  }

  db.transaction(() => {
    const existing = readRow(db, input.date, input.productCode);
    const boxes = (existing?.boxes_total ?? 0) + input.boxesAdded;
    const units = boxes * product.unitsPerBox;
    const entries = readLog(existing?.entries_log ?? '[]');
    entries.push({
      kind: 'add',
      boxesAdded: input.boxesAdded,
      boxesAfter: boxes,
      unitsAfter: units,
      userId: input.userId,
      at: nowIso(),
    });
    const timestamp = nowIso();

    if (existing === undefined) {
      db.prepare(
        `INSERT INTO stock_counts
           (count_date, category, product_code, product_name, units_per_box, boxes_total, units_total, entries_log, created_at, updated_at, user_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).run(
        input.date,
        product.category,
        product.code,
        product.name,
        product.unitsPerBox,
        boxes,
        units,
        JSON.stringify(entries),
        timestamp,
        timestamp,
        input.userId,
      );
      return;
    }

    db.prepare(
      'UPDATE stock_counts SET boxes_total = ?, units_total = ?, entries_log = ?, updated_at = ?, user_id = ? WHERE count_date = ? AND product_code = ?',
    ).run(boxes, units, JSON.stringify(entries), timestamp, input.userId, input.date, input.productCode);
  }).immediate();

  const view = stockDayView(db, input.date).products.find((entry) => entry.code === input.productCode);
  if (view === undefined) {
    throw new Error(`product vanished from the catalog during the write: ${input.productCode}`);
  }
  return view;
}

export function resetStockProduct(
  db: Database.Database,
  input: { date: string; productCode: string; userId: number },
): StockProductView {
  const product = findStockProduct(input.productCode);
  if (product === undefined) {
    throw new Error(`unknown product: ${input.productCode}`);
  }

  db.transaction(() => {
    const existing = readRow(db, input.date, input.productCode);
    const entries = readLog(existing?.entries_log ?? '[]');
    entries.push({
      kind: 'reset',
      boxesAdded: 0,
      boxesAfter: 0,
      unitsAfter: 0,
      userId: input.userId,
      at: nowIso(),
    });
    const timestamp = nowIso();

    if (existing === undefined) {
      db.prepare(
        `INSERT INTO stock_counts
           (count_date, category, product_code, product_name, units_per_box, boxes_total, units_total, entries_log, created_at, updated_at, user_id)
         VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)`,
      ).run(input.date, product.category, product.code, product.name, product.unitsPerBox, JSON.stringify(entries), timestamp, timestamp, input.userId);
      return;
    }

    db.prepare(
      'UPDATE stock_counts SET boxes_total = 0, units_total = 0, entries_log = ?, updated_at = ?, user_id = ? WHERE count_date = ? AND product_code = ?',
    ).run(JSON.stringify(entries), timestamp, input.userId, input.date, input.productCode);
  }).immediate();

  const view = stockDayView(db, input.date).products.find((entry) => entry.code === input.productCode);
  if (view === undefined) {
    throw new Error(`product vanished from the catalog during the write: ${input.productCode}`);
  }
  return view;
}

export function stockHistory(db: Database.Database): StockHistoryDay[] {
  const rows = db
    .prepare(
      `SELECT count_date,
              COUNT(*) AS products,
              SUM(boxes_total) AS boxes,
              SUM(units_total) AS units
         FROM stock_counts
        GROUP BY count_date
        ORDER BY count_date DESC`,
    )
    .all() as Array<{ count_date: string; products: number; boxes: number; units: number }>;
  return rows.map((row) => ({ date: row.count_date, products: row.products, boxes: row.boxes, units: row.units }));
}
