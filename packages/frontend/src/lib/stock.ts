/**
 * What leaves the plant when a count is done: a message and an email.
 *
 * The arithmetic belongs to the API and the screen only collects numbers, but the text is a rule of its
 * own. Two of them are worth naming. The day comes from São Paulo and not from the device, because a
 * phone set to another timezone would label a count with the wrong date, and a count is a document about
 * a day. And only what was counted leaves: a line of zero boxes in a chat that is read at the loading
 * dock is noise that hides the real line.
 */
export interface StockProduct {
  code: string;
  name: string;
  category: string;
  unitsPerBox: number;
  boxes: number;
  units: number;
}

export interface StockDay {
  date: string;
  products: StockProduct[];
  totalBoxes: number;
  totalUnits: number;
}

export interface StockCategoryGroup {
  category: string;
  products: StockProduct[];
}

/** The catalog sections, derived from what the API sent rather than from a copy kept here. */
export function groupByCategory(products: StockProduct[]): StockCategoryGroup[] {
  const groups: StockCategoryGroup[] = [];
  for (const product of products) {
    const found = groups.find((group) => group.category === product.category);
    if (found === undefined) {
      groups.push({ category: product.category, products: [product] });
      continue;
    }
    found.products.push(product);
  }
  return groups;
}

/** Where the operator is, per product: the fields that were left, and the one being edited now. */
export interface StockFieldState {
  blurred: Record<string, boolean>;
  focused: string | null;
}

/**
 * True when what was typed cannot become a number of boxes.
 *
 * An empty field is not a mistake, it is a field nobody touched yet. The rule is the one the order
 * calculator follows, and for the same reason: a box holds whole units, so half a box is not a quantity
 * and neither is zero boxes.
 */
export function stockBoxesInvalid(raw: string): boolean {
  const text = raw.trim();
  if (text === '') return false;
  const value = Number(text);
  return !Number.isInteger(value) || value <= 0;
}

/** The add button is off when there is nothing to add, or what is there cannot be added. */
export function stockAddDisabled(raw: string): boolean {
  return raw.trim() === '' || stockBoxesInvalid(raw);
}

/**
 * The products whose error should be shown right now.
 *
 * Whether a quantity can be counted is a fact about the number; whether to say so is a fact about the
 * moment. While the operator is typing, half a number is allowed to be half written, and coming back
 * into a field has to clear the warning it left behind, or the first keystroke of a correction accuses
 * the operator of the mistake they are in the middle of fixing.
 */
export function stockVisibleErrors(quantities: Record<string, string>, state: StockFieldState): string[] {
  return Object.keys(quantities)
    .filter((code) => state.blurred[code] === true && state.focused !== code && stockBoxesInvalid(quantities[code]))
    .sort();
}

function counted(products: StockProduct[]): StockProduct[] {
  return products.filter((product) => product.units > 0);
}

function brazilianDate(date: string): string {
  const [year, month, day] = date.split('-');
  return year && month && day ? `${day}/${month}/${year}` : date;
}

const EMOJI_BY_CATEGORY: Record<string, string> = {
  Terceirizados: '📦',
  Massas: '🍝',
};

export function stockMessage(day: StockDay): string {
  const countedProducts = counted(day.products);
  const lines: string[] = [`🗒️ *Contagem de estoque* — ${brazilianDate(day.date)}`, ''];

  if (countedProducts.length === 0) {
    lines.push('Nenhuma caixa contada até agora.');
    return lines.join('\n');
  }

  for (const group of groupByCategory(countedProducts)) {
    const emoji = EMOJI_BY_CATEGORY[group.category] ?? '•';
    lines.push(`${emoji} *${group.category}*`);
    for (const product of group.products) {
      lines.push(`  • ${product.name} (${product.code}) — ${product.units} un`);
    }
    lines.push('');
  }

  lines.push(`📊 Total: ${day.totalBoxes} caixas · ${day.totalUnits} unidades`);
  return lines.join('\n');
}

export function stockEmailBody(day: StockDay): string {
  const countedProducts = counted(day.products);
  const lines: string[] = [`Contagem de estoque — ${brazilianDate(day.date)}`, ''];

  if (countedProducts.length === 0) {
    lines.push('Nenhuma caixa contada até agora.');
    return lines.join('\n');
  }

  // The columns are measured from the data rather than fixed, because a product name is as long as it
  // is and a table that wraps inside a mail client is worse than one that is merely wide. The last
  // column is padded from the left so the quantities line up under each other, which is the only
  // column anybody reads down.
  const headers = ['Categoria', 'Código', 'Produto', 'Unidades'];
  const rows = countedProducts.map((product) => [product.category, product.code, product.name, String(product.units)]);
  const totals = ['Total', '', '', String(day.totalUnits)];
  const widths = headers.map((header, index) =>
    Math.max(header.length, ...rows.map((row) => row[index].length), totals[index].length)
  );

  const row = (cells: string[]): string =>
    cells
      .map((cell, index) => (index === cells.length - 1 ? cell.padStart(widths[index]) : cell.padEnd(widths[index])))
      .join(' | ');
  const separator = widths.map((width) => '-'.repeat(width)).join('-+-');

  lines.push(row(headers));
  lines.push(separator);
  for (const line of rows) lines.push(row(line));
  lines.push(separator);
  lines.push(row(totals));
  return lines.join('\n');
}

export function mailtoLink(to: string, subject: string, body: string): string {
  return `mailto:${to}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}
