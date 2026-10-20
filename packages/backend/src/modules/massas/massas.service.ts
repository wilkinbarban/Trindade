import type Database from 'better-sqlite3';
import { MASSAS, type StockProduct } from '../stock/stock.catalog.js';

export interface MassaProductTotal {
  code: string;
  name: string;
  unitsPerBox: number;
  totalBoxes: number;
  totalUnits: number;
}

export interface MassaIndividualLaunch {
  id: number;
  launch_date: string;
  product_code: string;
  product_name: string;
  boxes: number;
  units: number;
  user_id: number;
  username: string;
  display_name: string;
  created_at: string;
  updated_at: string;
}

export interface MassasLaunchDayView {
  date: string;
  products: MassaProductTotal[];
  launches: MassaIndividualLaunch[];
  totalBoxes: number;
  totalUnits: number;
}

interface RawLaunchRow {
  id: number;
  launch_date: string;
  product_code: string;
  boxes: number;
  user_id: number;
  username: string;
  display_name: string;
  created_at: string;
  updated_at: string;
}

const MASSA_BY_CODE = new Map<string, StockProduct>(MASSAS.map((product) => [product.code, product]));

export function findMassaProduct(code: string): StockProduct | undefined {
  return MASSA_BY_CODE.get(code);
}

function nowIso(): string {
  return new Date().toISOString();
}

/**
 * Returns the day view for the 10 massa products:
 * - Collective sum of boxes and units across all users.
 * - Individual launches made on that date with user metadata.
 */
export function massasLaunchDayView(db: Database.Database, date: string): MassasLaunchDayView {
  const rows = db
    .prepare(
      `SELECT 
        l.id,
        l.launch_date,
        l.product_code,
        l.boxes,
        l.user_id,
        u.username,
        u.display_name,
        l.created_at,
        l.updated_at
      FROM massa_launches l
      JOIN users u ON u.id = l.user_id
      WHERE l.launch_date = ?
      ORDER BY l.id ASC`,
    )
    .all(date) as RawLaunchRow[];

  const launches: MassaIndividualLaunch[] = rows.map((row) => {
    const product = findMassaProduct(row.product_code);
    const unitsPerBox = product?.unitsPerBox ?? 0;
    return {
      id: row.id,
      launch_date: row.launch_date,
      product_code: row.product_code,
      product_name: product?.name ?? row.product_code,
      boxes: row.boxes,
      units: row.boxes * unitsPerBox,
      user_id: row.user_id,
      username: row.username,
      display_name: row.display_name,
      created_at: row.created_at,
      updated_at: row.updated_at,
    };
  });

  const boxesByCode = new Map<string, number>();
  for (const launch of launches) {
    boxesByCode.set(launch.product_code, (boxesByCode.get(launch.product_code) ?? 0) + launch.boxes);
  }

  let totalBoxes = 0;
  let totalUnits = 0;

  const products: MassaProductTotal[] = MASSAS.map((massa) => {
    const productBoxes = boxesByCode.get(massa.code) ?? 0;
    const productUnits = productBoxes * massa.unitsPerBox;
    totalBoxes += productBoxes;
    totalUnits += productUnits;
    return {
      code: massa.code,
      name: massa.name,
      unitsPerBox: massa.unitsPerBox,
      totalBoxes: productBoxes,
      totalUnits: productUnits,
    };
  });

  return {
    date,
    products,
    launches,
    totalBoxes,
    totalUnits,
  };
}

export interface CreateLaunchItem {
  product_code: string;
  boxes: number;
}

export function createMassaLaunches(
  db: Database.Database,
  params: {
    date: string;
    userId: number;
    items: CreateLaunchItem[];
  },
): MassaIndividualLaunch[] {
  if (params.items.length === 0) {
    throw new Error('No items to launch');
  }

  for (const item of params.items) {
    if (!findMassaProduct(item.product_code)) {
      throw new Error(`Unknown massa product code: ${item.product_code}`);
    }
    if (!Number.isInteger(item.boxes) || item.boxes <= 0) {
      throw new Error('Boxes must be a positive integer');
    }
  }

  const now = nowIso();
  const insertStmt = db.prepare(
    `INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  );
  const selectStmt = db.prepare(
    `SELECT 
      l.id,
      l.launch_date,
      l.product_code,
      l.boxes,
      l.user_id,
      u.username,
      u.display_name,
      l.created_at,
      l.updated_at
    FROM massa_launches l
    JOIN users u ON u.id = l.user_id
    WHERE l.id = ?`,
  );

  return db.transaction(() => {
    const created: MassaIndividualLaunch[] = [];
    for (const item of params.items) {
      const result = insertStmt.run(params.date, item.product_code, item.boxes, params.userId, now, now);
      const insertedRow = selectStmt.get(result.lastInsertRowid) as RawLaunchRow;
      const product = findMassaProduct(insertedRow.product_code);
      created.push({
        id: insertedRow.id,
        launch_date: insertedRow.launch_date,
        product_code: insertedRow.product_code,
        product_name: product?.name ?? insertedRow.product_code,
        boxes: insertedRow.boxes,
        units: insertedRow.boxes * (product?.unitsPerBox ?? 0),
        user_id: insertedRow.user_id,
        username: insertedRow.username,
        display_name: insertedRow.display_name,
        created_at: insertedRow.created_at,
        updated_at: insertedRow.updated_at,
      });
    }
    return created;
  }).immediate();
}

export function updateMassaLaunch(
  db: Database.Database,
  params: {
    id: number;
    userId: number;
    boxes: number;
  },
): MassaIndividualLaunch {
  if (!Number.isInteger(params.boxes) || params.boxes <= 0) {
    throw new Error('Boxes must be a positive integer');
  }

  const existing = db
    .prepare('SELECT id, user_id, product_code, launch_date, created_at FROM massa_launches WHERE id = ?')
    .get(params.id) as { id: number; user_id: number; product_code: string; launch_date: string; created_at: string } | undefined;

  if (!existing) {
    const err = new Error('Launch not found');
    (err as unknown as { code: string }).code = 'NOT_FOUND';
    throw err;
  }

  if (existing.user_id !== params.userId) {
    const err = new Error('Forbidden: cannot edit launches belonging to another user');
    (err as unknown as { code: string }).code = 'FORBIDDEN';
    throw err;
  }

  const now = nowIso();
  db.prepare('UPDATE massa_launches SET boxes = ?, updated_at = ? WHERE id = ?').run(params.boxes, now, params.id);

  const row = db
    .prepare(
      `SELECT 
        l.id,
        l.launch_date,
        l.product_code,
        l.boxes,
        l.user_id,
        u.username,
        u.display_name,
        l.created_at,
        l.updated_at
      FROM massa_launches l
      JOIN users u ON u.id = l.user_id
      WHERE l.id = ?`,
    )
    .get(params.id) as RawLaunchRow;

  const product = findMassaProduct(row.product_code);
  return {
    id: row.id,
    launch_date: row.launch_date,
    product_code: row.product_code,
    product_name: product?.name ?? row.product_code,
    boxes: row.boxes,
    units: row.boxes * (product?.unitsPerBox ?? 0),
    user_id: row.user_id,
    username: row.username,
    display_name: row.display_name,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

export function deleteMassaLaunch(
  db: Database.Database,
  params: {
    id: number;
    userId: number;
  },
): void {
  const existing = db
    .prepare('SELECT id, user_id FROM massa_launches WHERE id = ?')
    .get(params.id) as { id: number; user_id: number } | undefined;

  if (!existing) {
    const err = new Error('Launch not found');
    (err as unknown as { code: string }).code = 'NOT_FOUND';
    throw err;
  }

  if (existing.user_id !== params.userId) {
    const err = new Error('Forbidden: cannot delete launches belonging to another user');
    (err as unknown as { code: string }).code = 'FORBIDDEN';
    throw err;
  }

  db.prepare('DELETE FROM massa_launches WHERE id = ?').run(params.id);
}
