import type Database from 'better-sqlite3';
import { MASSAS } from '../stock/stock.catalog.js';
import {
  aeroMovementsTableExists,
  listAeroMovements,
  type AeroMovement,
} from '../integration/aero.movements.service.js';
import { massasLaunchDayView, type MassaProductTotal } from './massas.service.js';
import { codeKey, operatorKey, reconcileMassasDay } from './massas-reconcile.js';

/**
 * The day the plant really produced, read from both sides.
 *
 * Trindade records what an operator launched here; the Delphi tablet records what somebody loaded straight
 * into the company ERP. They describe the same boxes from different ends, so the report cannot add the two
 * tables: a reconciled seat is already in Trindade and counting it again would inflate the day.
 *
 * The rule is one box, one origin. A launch that reconciles with a seat is counted once, as Trindade's; a
 * seat that reconciles with nothing is counted as the plant operator who typed it on the tablet.
 */

/** The whole catalogue counted per product, so the collective summary keeps its shape. */
export interface MassasReportOperatorProduct {
  code: string;
  name: string;
  boxes: number;
  units: number;
}

/**
 * One operator's share of the day, no matter which screen recorded it.
 *
 * Every field has the shape both clients already read: the web names the operator `name`, the generated
 * Android contract names it `displayName`, and the same happens with `erpOnly`/`onlyErp`. Sending one of the
 * two is what made the contract skip the whole day on the phone, so the answer carries both.
 */
export interface MassasReportOperator {
  /** Stable key the screens group by; the name upper-cased and trimmed. */
  operatorKey: string;
  /** The Trindade display name when there is one, the ERP's `ENT_USU` when there is not. */
  name: string;
  /** The same name, as the generated Android contract names it. */
  displayName: string;
  /** The username when the operator exists in Trindade. */
  username: string | null;
  boxes: number;
  units: number;
  products: MassasReportOperatorProduct[];
  /** True when the operator only exists in the ERP, so the screen can say so. */
  erpOnly: boolean;
  /** The same fact as `erpOnly`, under the name the Android contract uses. */
  onlyErp: boolean;
  /** The ERP seats this operator's day came from, in the order they were read. */
  entLans: number[];
}

/**
 * What the day produced against what the ERP recorded.
 *
 * The two numbers are not the same measurement: `produced` is the reconciled plant output and
 * `registered` is everything the company has for the day. A negative difference is production Delphi has
 * not seen; a positive one is something the ERP recorded that the plant never claimed here.
 */
export interface MassasReportBalance {
  producedBoxes: number;
  producedUnits: number;
  registeredBoxes: number;
  registeredUnits: number;
  /** `registeredUnits - producedUnits`, so zero means the day is fully reconciled. */
  differenceUnits: number;
  /** How many ERP seats the plant also recorded here. */
  reconciledSeats: number;
  /** How many ERP seats the plant never claimed, which is what the report adds from the company side. */
  pendingSeats: number;
}

export interface MassasReportDayView {
  date: string;
  operators: MassasReportOperator[];
  products: MassaProductTotal[];
  totalBoxes: number;
  totalUnits: number;
  balance: MassasReportBalance;
  /** `ENT_LAN` of the seats Trindade also has, so the page can show the day is paired. */
  reconciledEntLans: number[];
}

interface UserName {
  username: string;
  displayName: string;
}

/**
 * Every name Trindade knows, keyed the way an ERP operator name has to be compared.
 *
 * A worker who only loaded boxes on the tablet has no launch that day, so the launch rows cannot answer
 * this: the association needs the user table, and it is read once per report.
 */
function loadUserNames(db: Database.Database): Map<string, UserName> {
  const rows = db
    .prepare('SELECT username, display_name FROM users')
    .all() as { username: string; display_name: string }[];

  const names = new Map<string, UserName>();
  for (const row of rows) {
    const user = { username: row.username, displayName: row.display_name };
    names.set(operatorKey(row.username), user);
    names.set(operatorKey(row.display_name), user);
  }
  return names;
}

/** The massas seats of the day, or none when the mirror was never migrated. */
function massasSeats(db: Database.Database, date: string): AeroMovement[] {
  if (!aeroMovementsTableExists(db)) return [];
  const codes = new Set(MASSAS.map((product) => codeKey(product.code)));
  return listAeroMovements(db, date).filter((movement) => codes.has(codeKey(movement.product_code)));
}

function emptyProductTotals(): MassaProductTotal[] {
  return MASSAS.map((product) => ({
    code: product.code,
    name: product.name,
    unitsPerBox: product.unitsPerBox,
    totalBoxes: 0,
    totalUnits: 0,
  }));
}

function addProduct(products: Map<string, MassasReportOperatorProduct>, code: string, name: string, boxes: number, units: number): void {
  const existing = products.get(code);
  if (existing) {
    existing.boxes += boxes;
    existing.units += units;
    return;
  }
  products.set(code, { code, name, boxes, units });
}

/**
 * The day's production, once, whichever screen recorded it.
 *
 * `produced` is this view's own total: every launch, plus only the seats that reconcile with nothing. The
 * balance then compares it against the ERP's raw entry total, which is a different question and is why the
 * difference can be negative without anything being wrong.
 */
export function massasReportDayView(db: Database.Database, date: string): MassasReportDayView {
  const dayView = massasLaunchDayView(db, date);
  const launches = dayView.launches;
  const seats = massasSeats(db, date);
  const reconciliation = reconcileMassasDay(launches, seats);
  const reconciled = new Set(reconciliation.reconciledEntLans);
  const knownNames = loadUserNames(db);

  const products = emptyProductTotals();
  const productsByCode = new Map(products.map((product) => [product.code, product]));
  const operators = new Map<
    string,
    {
      name: string;
      username: string | null;
      erpOnly: boolean;
      products: Map<string, MassasReportOperatorProduct>;
      entLans: number[];
    }
  >();

  const operatorBucket = (name: string, username: string | null, erpOnly: boolean) => {
    const key = operatorKey(name);
    const existing = operators.get(key);
    if (existing) return existing;
    const created = {
      name,
      username,
      erpOnly,
      products: new Map<string, MassasReportOperatorProduct>(),
      entLans: [] as number[],
    };
    operators.set(key, created);
    return created;
  };

  // An imported launch wears the account of whoever imported it, not of the person who produced the boxes.
  // The seat is the only record that names the operator, so it decides whenever it is present.
  const seatByEntLan = new Map<number, (typeof seats)[number]>();
  for (const seat of seats) {
    if (seat.ent_lan != null) seatByEntLan.set(seat.ent_lan, seat);
  }

  for (const launch of launches) {
    if (launch.boxes <= 0) continue;
    const seat = launch.ent_lan != null ? seatByEntLan.get(launch.ent_lan) : undefined;
    let name: string;
    let username: string | null = launch.username;
    let erpOnly = false;
    if (seat) {
      const known = knownNames.get(operatorKey(seat.operator_name));
      name = (known ? known.displayName : seat.operator_name).trim();
      username = known ? known.username : null;
      // No local account means the report credits a person who exists only in the ERP.
      erpOnly = !known;
    } else {
      name = (launch.display_name || launch.username || 'Operador').trim();
    }
    const bucket = operatorBucket(name, username, erpOnly);
    if (launch.ent_lan != null && !bucket.entLans.includes(launch.ent_lan)) {
      bucket.entLans.push(launch.ent_lan);
    }
    addProduct(bucket.products, launch.product_code, launch.product_name, launch.boxes, launch.units);
    const product = productsByCode.get(launch.product_code);
    if (product) {
      product.totalBoxes += launch.boxes;
      product.totalUnits += launch.units;
    }
  }

  for (const seat of seats) {
    // A stock exit is the plant sending product out; only an entry is production coming back.
    if (seat.movement_type !== 'E') continue;
    if (reconciled.has(seat.ent_lan)) continue;
    const product = productsByCode.get(seat.product_code);
    // The catalogue already filtered the seat, so this only guards against a code the catalog changed
    // out from under the snapshot.
    if (!product) continue;

    const known = knownNames.get(operatorKey(seat.operator_name));
    const name = known ? known.displayName : seat.operator_name.trim();
    const bucket = operatorBucket(name, known ? known.username : null, !known);
    const boxes = product.unitsPerBox > 0 ? seat.quantity / product.unitsPerBox : 0;
    if (!bucket.entLans.includes(seat.ent_lan)) bucket.entLans.push(seat.ent_lan);
    addProduct(bucket.products, product.code, product.name, boxes, seat.quantity);
    product.totalBoxes += boxes;
    product.totalUnits += seat.quantity;
  }

  const operatorList: MassasReportOperator[] = [...operators.values()]
    .map((operator) => {
      const items = [...operator.products.values()];
      return {
        operatorKey: operatorKey(operator.name),
        name: operator.name,
        displayName: operator.name,
        username: operator.username,
        boxes: items.reduce((sum, item) => sum + item.boxes, 0),
        units: items.reduce((sum, item) => sum + item.units, 0),
        products: items,
        erpOnly: operator.erpOnly,
        onlyErp: operator.erpOnly,
        entLans: [...operator.entLans].sort((a, b) => a - b),
      };
    })
    .sort((a, b) => b.units - a.units || a.name.localeCompare(b.name));

  const totalUnits = products.reduce((sum, product) => sum + product.totalUnits, 0);
  const totalBoxes = products.reduce((sum, product) => sum + product.totalBoxes, 0);

  // The balance compares the reconciled production against every massas entry the ERP recorded, including
  // the seats that reconciled: they are the same boxes seen from the company side, and hiding them would
  // make a fully reconciled day look like a partial one.
  const registeredUnits = seats
    .filter((seat) => seat.movement_type === 'E')
    .reduce((sum, seat) => sum + seat.quantity, 0);
  const registeredBoxes = seats
    .filter((seat) => seat.movement_type === 'E')
    .reduce((sum, seat) => {
      const product = productsByCode.get(seat.product_code);
      return sum + (product && product.unitsPerBox > 0 ? seat.quantity / product.unitsPerBox : 0);
    }, 0);

  return {
    date,
    operators: operatorList,
    products,
    totalBoxes,
    totalUnits,
    balance: {
      producedBoxes: totalBoxes,
      producedUnits: totalUnits,
      registeredBoxes,
      registeredUnits,
      differenceUnits: registeredUnits - totalUnits,
      reconciledSeats: reconciliation.reconciledEntLans.length,
      pendingSeats: seats.filter((seat) => seat.movement_type === 'E' && !reconciled.has(seat.ent_lan)).length,
    },
    reconciledEntLans: reconciliation.reconciledEntLans,
  };
}

