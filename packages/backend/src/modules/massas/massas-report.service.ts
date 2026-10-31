import type Database from 'better-sqlite3';
import { MASSAS } from '../stock/stock.catalog.js';
import {
  aeroMovementsTableExists,
  listAeroMovements,
  type AeroMovement,
} from '../integration/aero.movements.service.js';
import type { MassaProductTotal } from './massas.service.js';
import { codeKey, operatorKey } from './massas-reconcile.js';

/**
 * The day the company recorded, read from one side only.
 *
 * Trindade used to reconcile the launches recorded here against the Delphi seats and report the sum. The two
 * tables describe the same boxes from different ends, so any launch the plant typed but never sent to the ERP
 * still entered the report — a day showed boxes the company had never seen — and a seat the plant did send
 * looked half counted.
 *
 * The report now answers one question: what does the company have for the day? Its only source is
 * `aero_movements` with `movement_type = 'E'`. The reconciliation between the plant and the ERP still exists,
 * but it belongs to the launch screen, where both sides are in scope and a difference can be explained.
 */

/** The whole catalogue counted per product, so the collective summary keeps its shape. */
export interface MassasReportOperatorProduct {
  code: string;
  name: string;
  boxes: number;
  units: number;
}

/**
 * One operator's share of the day, as the ERP recorded it.
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
 * The day against itself, kept so the clients that already read it keep working.
 *
 * The report has no local side any more, so `produced` and `registered` are the same measurement and the
 * difference is always zero. The comparison bar moved to the launch screen; these numbers stay for the
 * contract's sake and never invent a difference the report could not explain.
 */
export interface MassasReportBalance {
  producedBoxes: number;
  producedUnits: number;
  registeredBoxes: number;
  registeredUnits: number;
  /** Always zero here: the ERP total compared against itself. */
  differenceUnits: number;
  /** Always zero here: the report no longer reconciles against the plant. */
  reconciledSeats: number;
  /** Always zero here, for the same reason. */
  pendingSeats: number;
}

export interface MassasReportDayView {
  date: string;
  operators: MassasReportOperator[];
  products: MassaProductTotal[];
  totalBoxes: number;
  totalUnits: number;
  balance: MassasReportBalance;
  /** Always empty here: reconciliation is the launch screen's question, not the report's. */
  reconciledEntLans: number[];
}

interface UserName {
  username: string;
  displayName: string;
}

/**
 * Every name Trindade knows, keyed the way an ERP operator name has to be compared.
 *
 * A worker who only loaded boxes on the tablet has no local account, so the user table is what tells the
 * report whether a seat names an operator Trindade can show with their own display name.
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

function addProduct(
  products: Map<string, MassasReportOperatorProduct>,
  code: string,
  name: string,
  boxes: number,
  units: number,
): void {
  const existing = products.get(code);
  if (existing) {
    existing.boxes += boxes;
    existing.units += units;
    return;
  }
  products.set(code, { code, name, boxes, units });
}

/**
 * The day the company recorded, credited to the operator the seat names.
 *
 * A stock exit is the plant sending product out, so only an entry is production coming back. The catalogue
 * decides what belongs to massas; every other entry is a supply receipt and belongs to the other block.
 */
export function massasReportDayView(db: Database.Database, date: string): MassasReportDayView {
  const seats = massasSeats(db, date).filter((seat) => seat.movement_type === 'E');
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

  for (const seat of seats) {
    // The ERP pads its codes (`000084`); the catalogue does not. The key is the identity, not the string.
    const product = productsByCode.get(codeKey(seat.product_code));
    // The catalogue already filtered the seat, so this only guards against a code the catalog changed
    // out from under the snapshot.
    if (!product) continue;

    const known = knownNames.get(operatorKey(seat.operator_name));
    const name = (known ? known.displayName : seat.operator_name).trim();
    const key = operatorKey(name);
    let bucket = operators.get(key);
    if (!bucket) {
      bucket = {
        name,
        username: known ? known.username : null,
        // No local account means the report credits a person who exists only in the ERP.
        erpOnly: !known,
        products: new Map<string, MassasReportOperatorProduct>(),
        entLans: [],
      };
      operators.set(key, bucket);
    }

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

  return {
    date,
    operators: operatorList,
    products,
    totalBoxes,
    totalUnits,
    balance: {
      producedBoxes: totalBoxes,
      producedUnits: totalUnits,
      registeredBoxes: totalBoxes,
      registeredUnits: totalUnits,
      differenceUnits: 0,
      reconciledSeats: 0,
      pendingSeats: 0,
    },
    reconciledEntLans: [],
  };
}
