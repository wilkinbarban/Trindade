import { describe, it } from 'node:test';
import assert from 'node:assert';
import { mailtoLink, groupByCategory, saopauloToday, stockEmailBody, stockMessage } from './stock';

/**
 * The counting export rules, tested where they can be tested: as text.
 *
 * The screen collects numbers and the API owns the arithmetic, but what leaves the plant is a message,
 * and a message is where a wrong total becomes somebody else's order. The two things asserted hardest
 * here are the day and the filter: the day because a device clock in another timezone would label a
 * count with the wrong date, and the filter because a line of zero boxes is noise in a chat that is read
 * at the loading dock.
 */
const day = {
  date: '2026-10-15',
  totalBoxes: 7,
  totalUnits: 250,
  products: [
    { code: '787', name: 'CANUDO', category: 'Terceirizados', unitsPerBox: 30, boxes: 5, units: 150 },
    { code: '1800', name: 'PIZZA BROTINHO 55G', category: 'Terceirizados', unitsPerBox: 50, boxes: 0, units: 0 },
    { code: '1047', name: 'MASSA ROLO 2KG', category: 'Massas', unitsPerBox: 6, boxes: 2, units: 12 },
    { code: '1616', name: 'MASSA TOKO', category: 'Massas', unitsPerBox: 10, boxes: 0, units: 0 },
  ],
};

describe('the day that is being counted', () => {
  it('takes the date from São Paulo and not from the device clock', () => {
    // 02:00 UTC on the 15th is still 23:00 on the 14th in São Paulo.
    assert.strictEqual(saopauloToday(new Date('2026-10-15T02:00:00Z')), '2026-10-14');
    assert.strictEqual(saopauloToday(new Date('2026-10-15T12:00:00Z')), '2026-10-15');
  });
});

describe('the catalogue as it arrives from the API', () => {
  it('splits the products into the two sections of the counter, keeping the order the API sent', () => {
    const grouped = groupByCategory(day.products);
    assert.deepStrictEqual(grouped.map((g) => g.category), ['Terceirizados', 'Massas']);
    assert.deepStrictEqual(grouped[0].products.map((p) => p.code), ['787', '1800']);
    assert.deepStrictEqual(grouped[1].products.map((p) => p.code), ['1047', '1616']);
  });
});

describe('the message that leaves the plant', () => {
  it('lists only what was counted, with its boxes and its units, and closes with the totals', () => {
    const message = stockMessage(day);
    assert.ok(message.includes('787') && message.includes('CANUDO'), 'el código y el nombre');
    assert.ok(message.includes('5') && message.includes('150'), 'bultos y unidades de esa línea');
    assert.ok(message.includes('2') && message.includes('12'), 'la línea de Massas');
    assert.ok(!message.includes('PIZZA BROTINHO'), 'un producto sin contar no ensucia el mensaje');
    assert.ok(!message.includes('MASSA TOKO'), 'ni el otro');
    assert.ok(message.includes('7') && message.includes('250'), 'los totales');
    assert.ok(message.includes('2026-10-15') || message.includes('15/10'), 'la fecha del conteo');
  });

  it('says nothing was counted instead of leaving an empty message', () => {
    const message = stockMessage({ ...day, products: day.products.map((p) => ({ ...p, boxes: 0, units: 0 })), totalBoxes: 0, totalUnits: 0 });
    assert.ok(message.trim().length > 0);
    assert.match(message, /nada|nenhum|ningún|sin contar|nada foi/i);
  });

  it('separates the two sections so the reader knows what belongs to each', () => {
    const message = stockMessage(day);
    assert.match(message, /Terceirizados/i);
    assert.match(message, /Massas/i);
  });
});

describe('the email that leaves the plant', () => {
  it('carries a plain table of what was counted, addressed to the plant', () => {
    const body = stockEmailBody(day);
    assert.ok(body.includes('787') && body.includes('CANUDO'));
    assert.ok(!body.includes('MASSA TOKO'), 'solo lo contado');
    assert.ok(body.includes('7') && body.includes('250'), 'los totales');
    const link = mailtoLink('wilkin.barban@gmail.com', 'Contagem 2026-10-15', body);
    assert.ok(link.startsWith('mailto:wilkin.barban@gmail.com'), 'el destinatario preconfigurado');
    assert.ok(link.includes(encodeURIComponent('Contagem 2026-10-15').slice(0, 6)), 'el asunto viaja codificado');
    assert.ok(link.includes('%0A') || link.includes('%20'), 'el cuerpo viaja codificado');
  });
});
