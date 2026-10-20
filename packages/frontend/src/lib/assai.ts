/**
 * The Assaí order calculator: the catalogue, the rule that decides whether a quantity is an order, and the
 * message that goes out. Pure, so the page holds no arithmetic of its own.
 *
 * A box holds a whole number of units, so an order only exists in whole boxes: fifteen units of the
 * 15-per-box product is one box, and sixteen is not an order anybody can place. It is a typo, or a quantity
 * the plant cannot serve, and rounding it would produce a number that does not match what arrives at the
 * gate -- so the rule is strict, an inexact quantity is an error, and the screen says so per product and
 * refuses to export.
 */

/** One line of the catalogue: what it is called and how many units its box holds. */
export interface AssaiProduct {
  name: string;
  unitsPerBox: number;
}

/** The nine products, as the plant counts them. */
export const ASSAI_PRODUCTS: readonly AssaiProduct[] = [
  { name: 'Nhoque 1kg', unitsPerBox: 15 },
  { name: 'Quadrada', unitsPerBox: 14 },
  { name: 'Disco 200g', unitsPerBox: 80 },
  { name: 'Disco 400g', unitsPerBox: 50 },
  { name: 'Disco 500g', unitsPerBox: 40 },
  { name: 'Lasanha', unitsPerBox: 45 },
  { name: 'Rolo 500g', unitsPerBox: 30 },
  { name: 'Rolo 1kg', unitsPerBox: 24 },
  { name: 'Rolo 2kg', unitsPerBox: 10 },
];

/** One product's line in the order, with the verdict the screen needs. */
export interface AssaiLine {
  product: string;
  unitsPerBox: number;
  /** What the operator typed, as a number. Zero when the field is empty. */
  units: number;
  /** Units over units per box; whole when [exact]. */
  boxes: number;
  /** Something was typed and it is a number. */
  valid: boolean;
  /** A number greater than zero was typed. */
  filled: boolean;
  /** The quantity is a whole number of boxes, which is the only kind of order there is. */
  exact: boolean;
}

export function boxesFor(units: number, unitsPerBox: number): number {
  return units / unitsPerBox;
}

/**
 * The whole order, one line per catalogue product.
 *
 * An empty field is not an error: it means the product is not being ordered today, and the list is long
 * enough that most days most of it is empty. A field with something that is not a number, or with units
 * that do not fill whole boxes, is an error -- and the two are the same error to the operator, who has to
 * fix the number either way.
 */
export function assaiLines(inputs: Record<string, string>): AssaiLine[] {
  return ASSAI_PRODUCTS.map(({ name, unitsPerBox }) => {
    const raw = (inputs[name] ?? '').trim();
    const parsed = raw === '' ? 0 : Number(raw);
    const valid = raw === '' || (Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 0);
    const filled = valid && parsed > 0;
    const exact = valid && (!filled || parsed % unitsPerBox === 0);

    return {
      product: name,
      unitsPerBox,
      units: valid ? parsed : 0,
      boxes: valid ? boxesFor(parsed, unitsPerBox) : 0,
      valid,
      filled,
      exact,
    };
  });
}

/** Where the operator is: which fields were left, and which one is being edited right now. */
export interface AssaiFieldState {
  blurred: Record<string, boolean>;
  focused: string | null;
}

/**
 * The products whose error should be shown right now.
 *
 * Whether a quantity can be ordered is a fact about the number; whether to say so is a fact about the
 * moment. While the operator is typing, a quantity is allowed to be half written, and coming back into a
 * field has to clear the warning it left behind, or the first keystroke of a correction accuses the
 * operator of the very mistake they are in the middle of fixing.
 */
export function assaiVisibleErrors(lines: readonly AssaiLine[], state: AssaiFieldState): string[] {
  return lines
    .filter(
      (line) =>
        line.filled && !line.exact && state.blurred[line.product] === true && state.focused !== line.product
    )
    .map((line) => line.product);
}

/** True when some error is being shown, which is what the warning under the table follows. */
export function assaiHasVisibleError(lines: readonly AssaiLine[], state: AssaiFieldState): boolean {
  return assaiVisibleErrors(lines, state).length > 0;
}

/** True when any line stops the order, which is what disables the export. */
export function assaiHasError(lines: readonly AssaiLine[]): boolean {
  return lines.some((line) => !line.exact);
}

/** How many boxes the whole order needs. Only the lines that are orders count. */
export function assaiTotalBoxes(lines: readonly AssaiLine[]): number {
  return lines
    .filter((line) => line.filled && line.exact)
    .reduce((total, line) => total + line.boxes, 0);
}

/** Valid weekdays for Assaí order reporting */
export const ASSAI_WEEKDAYS = [
  'Terça-feira',
  'Quinta-feira',
  'Sexta-feira',
  'Sábado',
] as const;

export type AssaiWeekday = (typeof ASSAI_WEEKDAYS)[number];

export interface AssaiWeekdayOption {
  id: string;
  value: AssaiWeekday;
  labelKey: string;
}

export const ASSAI_WEEKDAY_OPTIONS: readonly AssaiWeekdayOption[] = [
  { id: 'tuesday', value: 'Terça-feira', labelKey: 'assai.weekdayTuesday' },
  { id: 'thursday', value: 'Quinta-feira', labelKey: 'assai.weekdayThursday' },
  { id: 'friday', value: 'Sexta-feira', labelKey: 'assai.weekdayFriday' },
  { id: 'saturday', value: 'Sábado', labelKey: 'assai.weekdaySaturday' },
];

/**
 * The message that goes out, which is the order and nothing else: one line per product being ordered, with
 * its boxes and its units, and the total at the end. A product nobody is ordering is not named -- a list of
 * zeros is not an order, and the driver reading it would have to work out which lines matter.
 *
 * When an optional weekday is specified, it is attached to the header (e.g. "🛒 Pedido Assaí Sexta-feira").
 */
export function assaiMessage(lines: readonly AssaiLine[], day?: string | null): string {
  const ordered = lines.filter((line) => line.filled && line.exact);
  const trimmedDay = day?.trim();
  const header = trimmedDay ? `🛒 Pedido Assaí ${trimmedDay}` : '🛒 Pedido Assaí';

  if (ordered.length === 0) {
    return `${header}\nNenhum produto com quantidade informada.`;
  }

  const rows = ordered.map((line) => `${line.product}: ${line.boxes} caixas`);
  return [
    header,
    '',
    ...rows,
    '',
    `Total: ${assaiTotalBoxes(lines)} caixas`,
  ].join('\n');
}
