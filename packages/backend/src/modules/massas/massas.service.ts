import type Database from 'better-sqlite3';
import { aeroMovementsTableExists, listAeroMovements } from '../integration/aero.movements.service.js';
import { MASSAS, type StockProduct } from '../stock/stock.catalog.js';
import { resolveOperatorUserId } from './massas-operator.js';
import { reconcileMassasDay } from './massas-reconcile.js';
import { getSaoPauloDateString } from '../../utils/date.js';

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
  /** The ERP seat this launch was imported from, or null when it was typed here. */
  ent_lan: number | null;
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
  ent_lan: number | null;
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
        l.ent_lan,
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
      ent_lan: row.ent_lan ?? null,
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
  /** The ERP seat this line came from, when the launch is an import. */
  ent_lan?: number | null;
}

export function createMassaLaunches(
  db: Database.Database,
  params: {
    date: string;
    userId: number;
    /** The operator the tablet wrote on the seat; the importer is only the fallback owner. */
    operatorName?: string | null;
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

  // The seat decides the owner when it names somebody here; otherwise the importer keeps the launch. Both
  // facts travel separately, because the seat is what reconciles and the person is who gets the credit.
  const ownerId = resolveOperatorUserId(db, params.operatorName) ?? params.userId;

  const now = nowIso();
  const insertStmt = db.prepare(
    `INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, ent_lan, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  );
  const seatStmt = db.prepare('SELECT id FROM massa_launches WHERE ent_lan = ? LIMIT 1');
  const selectStmt = db.prepare(
    `SELECT 
      l.id,
      l.launch_date,
      l.product_code,
      l.boxes,
      l.user_id,
      u.username,
      u.display_name,
      l.ent_lan,
      l.created_at,
      l.updated_at
    FROM massa_launches l
    JOIN users u ON u.id = l.user_id
    WHERE l.id = ?`,
  );

  return db.transaction(() => {
    const created: MassaIndividualLaunch[] = [];
    for (const item of params.items) {
      const entLan = item.ent_lan ?? null;
      if (entLan !== null && seatStmt.get(entLan) !== undefined) {
        const err = new Error('This ERP seat was already imported');
        (err as unknown as { code: string }).code = 'ALREADY_IMPORTED';
        throw err;
      }
      const result = insertStmt.run(params.date, item.product_code, item.boxes, ownerId, entLan, now, now);
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
        ent_lan: insertedRow.ent_lan ?? null,
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
/**
 * A day the plant already closed.
 *
 * Yesterday is a record, not a draft: editing or deleting it after midnight turns the book into something
 * nobody recorded, and a role that can rewrite it can erase the plant's trail. This is asserted before every
 * other check -- before roles, before reconciliation -- because nobody, an administrator included, may
 * rewrite a past day. Today is still open while the plant is working.
 */
function assertDayStillOpen(launchDate: string): void {
  if (launchDate === getSaoPauloDateString()) return;
  const err = new Error('O histórico de massas é somente leitura: dias passados não podem ser alterados');
  (err as unknown as { code: string }).code = 'HISTORY_FROZEN';
  throw err;
}

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

  assertDayStillOpen(existing.launch_date);

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
        l.ent_lan,
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
    ent_lan: row.ent_lan ?? null,
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

  assertDayStillOpen(existing.launch_date);

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

