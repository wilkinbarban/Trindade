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

/** The São Paulo day, as YYYY-MM-DD, whatever the device's clock is set to. */
export function saopauloToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
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
      lines.push(`  • ${product.name} (${product.code}) — ${product.boxes} cx · ${product.units} un`);
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

  lines.push('Categoria | Código | Produto | Caixas | Unidades');
  for (const product of countedProducts) {
    lines.push(`${product.category} | ${product.code} | ${product.name} | ${product.boxes} | ${product.units}`);
  }
  lines.push('');
  lines.push(`Total | | | ${day.totalBoxes} | ${day.totalUnits}`);
  return lines.join('\n');
}

export function mailtoLink(to: string, subject: string, body: string): string {
  return `mailto:${to}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}
