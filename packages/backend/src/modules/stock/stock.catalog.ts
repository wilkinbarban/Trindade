/**
 * The catalogue the counting is done against, transcribed from the list the plant handed over.
 *
 * It lives in code and not in a table because it describes the product, not the count: a row of
 * `stock_counts` records what was counted on a day, and it keeps the name and the boxes-per-box of that
 * moment, so a change to this list tomorrow cannot rewrite what a count said yesterday.
 */
export type StockCategory = 'Terceirizados' | 'Massas';

export interface StockProduct {
  code: string;
  name: string;
  unitsPerBox: number;
  category: StockCategory;
}

export const TERCEIRIZADOS: readonly StockProduct[] = [
  { code: '1667', name: 'BATATA EXTRA FINA', unitsPerBox: 20, category: 'Terceirizados' },
  { code: '1567', name: 'BATATA PALHA 400G', unitsPerBox: 25, category: 'Terceirizados' },
  { code: '1566', name: 'BATATA PALHA 80G', unitsPerBox: 25, category: 'Terceirizados' },
  { code: '1461', name: 'BRINQUEDO GELADO', unitsPerBox: 100, category: 'Terceirizados' },
  { code: '787', name: 'CANUDO', unitsPerBox: 30, category: 'Terceirizados' },
  { code: '1438', name: 'NHOQUE 1 kg', unitsPerBox: 20, category: 'Terceirizados' },
  { code: '1436', name: 'NHOQUE 400g', unitsPerBox: 42, category: 'Terceirizados' },
  { code: '1236', name: 'PÃO PICANTE', unitsPerBox: 10, category: 'Terceirizados' },
  { code: '1237', name: 'PÃO TRADICIONAL', unitsPerBox: 10, category: 'Terceirizados' },
  { code: '1595', name: 'PÃO CALABRESA', unitsPerBox: 10, category: 'Terceirizados' },
  { code: '1591', name: 'PIZZA FAMILIA 200g', unitsPerBox: 20, category: 'Terceirizados' },
  { code: '107', name: 'PIZZA KIT 160g', unitsPerBox: 20, category: 'Terceirizados' },
  { code: '1753', name: '150g - PIZZA KIT', unitsPerBox: 20, category: 'Terceirizados' },
  { code: '1800', name: 'PIZZA BROTINHO 55G', unitsPerBox: 50, category: 'Terceirizados' },
  { code: '2108', name: 'QUEIJO RALADO', unitsPerBox: 25, category: 'Terceirizados' },
];

export const MASSAS: readonly StockProduct[] = [
  { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10, category: 'Massas' },
  { code: '39', name: 'MASSA DISCO 200G', unitsPerBox: 10, category: 'Massas' },
  { code: '60', name: 'MASSA DISCO 400G', unitsPerBox: 20, category: 'Massas' },
  { code: '84', name: 'MASSA DISCO 500G', unitsPerBox: 20, category: 'Massas' },
  { code: '91', name: 'MASSA LASANHA 500G', unitsPerBox: 20, category: 'Massas' },
  { code: '1192', name: 'MASSA QUADRADA', unitsPerBox: 15, category: 'Massas' },
  { code: '5000', name: 'MASSA ROLO 1KG', unitsPerBox: 10, category: 'Massas' },
  { code: '1047', name: 'MASSA ROLO 2KG', unitsPerBox: 6, category: 'Massas' },
  { code: '22', name: 'MASSA ROLO 500G', unitsPerBox: 15, category: 'Massas' },
  { code: '1616', name: 'MASSA TOKO', unitsPerBox: 10, category: 'Massas' },
];

export const STOCK_CATALOG: readonly StockProduct[] = [...TERCEIRIZADOS, ...MASSAS];

const BY_CODE = new Map(STOCK_CATALOG.map((product) => [product.code, product]));

export function findStockProduct(code: string): StockProduct | undefined {
  return BY_CODE.get(code);
}
