import type Database from 'better-sqlite3';
import { aeroMovementsTableExists, listAeroMovements } from '../integration/aero.movements.service.js';
import { MASSAS, type StockProduct } from '../stock/stock.catalog.js';
import { reconcileMassasDay } from './massas-reconcile.js';

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


/**
 * Refuses to touch a launch the Delphi tablet already recorded.
 *
 * The rule is about consistency, not about who is asking: once the ERP has a seat describing these same
 * boxes, editing or deleting the launch here would leave the two databases counting different production
 * with nobody the wiser. An administrator is refused too, and if the seat itself is wrong it is corrected in
 * Aero32 — Trindade cannot be the origin of that correction.
 *
 * The whole day is compared, not the row alone, because reconciliation is a decision about pairs: the same
 * launch can be reconciled or not depending on which seats arrived.
 */
function assertLaunchNotReconciled(db: Database.Database, launchId: number, launchDate: string): void {
  if (!aeroMovementsTableExists(db)) return;
  const seats = listAeroMovements(db, launchDate);
  if (seats.length === 0) return;
  const launch = massasLaunchDayView(db, launchDate).launches.find((row) => row.id === launchId);
  if (!launch) return;
  const { matchedLaunchIds } = reconcileMassasDay([launch], seats);
  if (!matchedLaunchIds.includes(launchId)) return;
  const err = new Error('Esta produção já foi conciliada com o ERP e não pode ser alterada');
  (err as unknown as { code: string }).code = 'RECONCILED';
  throw err;
}

export function updateMassaLaunch(
  db: Database.Database,
  params: {
    id: number;
    userId: number;
    boxes: number;
    userRole?: string;
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

  const isAdmin = params.userRole === 'Administrador';
  if (!isAdmin && existing.user_id !== params.userId) {
    const err = new Error('Forbidden: cannot edit launches belonging to another user');
    (err as unknown as { code: string }).code = 'FORBIDDEN';
    throw err;
  }

  assertLaunchNotReconciled(db, params.id, existing.launch_date);

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
    userRole?: string;
  },
): void {
  const existing = db
    .prepare('SELECT id, user_id, launch_date FROM massa_launches WHERE id = ?')
    .get(params.id) as { id: number; user_id: number; launch_date: string } | undefined;

  if (!existing) {
    const err = new Error('Launch not found');
    (err as unknown as { code: string }).code = 'NOT_FOUND';
    throw err;
  }

  const isAdmin = params.userRole === 'Administrador';
  if (!isAdmin && existing.user_id !== params.userId) {
    const err = new Error('Forbidden: cannot delete launches belonging to another user');
    (err as unknown as { code: string }).code = 'FORBIDDEN';
    throw err;
  }

  assertLaunchNotReconciled(db, params.id, existing.launch_date);

  db.prepare('DELETE FROM massa_launches WHERE id = ?').run(params.id);
}

export interface MassaHistoryDay {
  date: string;
  productsCount: number;
  totalBoxes: number;
  totalUnits: number;
  operatorsCount: number;
}

export function massasLaunchHistory(db: Database.Database): MassaHistoryDay[] {
  const rows = db
    .prepare(
      `SELECT 
        launch_date,
        product_code,
        boxes,
        user_id
      FROM massa_launches
      ORDER BY launch_date DESC, id ASC`,
    )
    .all() as Array<{ launch_date: string; product_code: string; boxes: number; user_id: number }>;

  const byDate = new Map<
    string,
    {
      products: Set<string>;
      boxes: number;
      units: number;
      operators: Set<number>;
    }
  >();

  for (const row of rows) {
    let day = byDate.get(row.launch_date);
    if (!day) {
      day = {
        products: new Set<string>(),
        boxes: 0,
        units: 0,
        operators: new Set<number>(),
      };
      byDate.set(row.launch_date, day);
    }
    day.products.add(row.product_code);
    day.boxes += row.boxes;
    const product = findMassaProduct(row.product_code);
    day.units += row.boxes * (product?.unitsPerBox ?? 0);
    day.operators.add(row.user_id);
  }

  const result: MassaHistoryDay[] = [];
  for (const [date, data] of byDate.entries()) {
    result.push({
      date,
      productsCount: data.products.size,
      totalBoxes: data.boxes,
      totalUnits: data.units,
      operatorsCount: data.operators.size,
    });
  }

  return result;
}

