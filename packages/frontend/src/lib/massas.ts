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

export function saopauloToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

export function brazilianDate(date: string): string {
  const [year, month, day] = date.split('-');
  return year && month && day ? `${day}/${month}/${year}` : date;
}

/**
 * Formats WhatsApp message for massas launches showing strictly total units
 * per product (for launched products) and the overall total units.
 */
export function massasMessage(view: MassasLaunchDayView): string {
  const lines: string[] = [`🍝 *Lançamento de Massas — ${brazilianDate(view.date)}*`, ''];

  const launchedProducts = view.products.filter((p) => p.totalUnits > 0);

  if (launchedProducts.length === 0) {
    lines.push('Nenhum lançamento registrado até agora.');
    return lines.join('\n');
  }

  for (const product of launchedProducts) {
    lines.push(`• ${product.name}: ${product.totalUnits} un`);
  }

  lines.push('');
  lines.push(`*Total Geral: ${view.totalUnits} un*`);

  return lines.join('\n');
}
