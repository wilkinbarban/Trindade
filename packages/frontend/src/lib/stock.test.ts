import { describe, it } from 'node:test';
import assert from 'node:assert';
import { saoPauloToday } from './date';
import {
  mailtoLink,
  groupByCategory,
  stockAddDisabled,
  stockBoxesInvalid,
  stockEmailBody,
  stockMessage,
  stockVisibleErrors,
} from './stock';

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
  totalUnits: 162,
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
    assert.strictEqual(saoPauloToday(new Date('2026-10-15T02:00:00Z')), '2026-10-14');
    assert.strictEqual(saoPauloToday(new Date('2026-10-15T12:00:00Z')), '2026-10-15');
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
    assert.ok(message.includes('CANUDO'), 'el nombre');
    assert.ok(!message.includes('787'), 'el código ya no viaja: la vista queda limpia');
    assert.ok(message.includes('  • CANUDO — 150 un'), 'la fila lleva el producto y sus unidades');
    assert.ok(!message.includes(' cx'), 'los bultos no viajan en el detalle');
    assert.ok(message.includes('  • MASSA ROLO 2KG — 12 un'), 'la línea de Massas, también sin código');
    assert.ok(!message.includes('PIZZA BROTINHO'), 'un producto sin contar no ensucia el mensaje');
    assert.ok(!message.includes('MASSA TOKO'), 'ni el otro');
    assert.ok(message.includes('7') && message.includes('162'), 'los totales');
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
    assert.ok(body.includes('CANUDO'), 'el nombre del producto contado');
    assert.ok(!body.includes('MASSA TOKO'), 'solo lo contado');
    assert.ok(body.includes('Categoria') && body.includes('Unidades'), 'las columnas del correo');
    assert.ok(!body.includes('Código'), 'la columna de código se fue del correo');
    assert.ok(!body.includes('787') && !body.includes('1047'), 'y con ella los códigos de cada fila');
    assert.ok(/CANUDO\s*\|/.test(body) && body.includes('MASSA ROLO 2KG'), 'las filas conservan el nombre');
    assert.ok(/^-+\+-+\+-+$/m.test(body), 'una linea separadora entre la cabecera y las filas, con las tres columnas');
    assert.ok(!body.includes('Caixas'), 'el correo ya no lleva la columna de bultos');
    // La alineacion es la propiedad que se pide: las barras caen siempre en la misma columna.
    const lineas = body.split('\n').filter((l) => l.includes('|'));
    const columnas = lineas.map((l) => l.split('|').map((c) => c.length));
    assert.ok(new Set(columnas.map((c) => c.length)).size === 1, 'todas las filas tienen la misma cantidad de columnas');
    assert.strictEqual(new Set(columnas.map((c) => c[0])).size, 1, 'la primera columna mide igual en todas las filas');
    assert.strictEqual(new Set(columnas.map((c) => c[1])).size, 1, 'la segunda tambien');
    assert.ok(lineas.some((l) => /\|\s+150\s*$/.test(l)), 'las unidades van alineadas a la derecha');
    assert.ok(lineas.some((l) => /^Total\b/.test(l) && /\|\s+162\s*$/.test(l)), 'el total cae bajo la columna de unidades');
    assert.ok(body.includes('162'), 'el total general al final');
    const link = mailtoLink('xmitrindade@hotmail.com', 'Contagem 2026-10-15', body);
    assert.ok(link.startsWith('mailto:xmitrindade@hotmail.com'), 'el destinatario preconfigurado');
    assert.ok(link.includes(encodeURIComponent('Contagem 2026-10-15').slice(0, 6)), 'el asunto viaja codificado');
    assert.ok(link.includes('%0A') || link.includes('%20'), 'el cuerpo viaja codificado');
  });
});

describe('cuando lo tipeado no puede ser una cantidad de cajas', () => {
  // Misma regla que en el pedido de Assai y por la misma razon: si una caja tiene bultos enteros, lo que
  // no es un entero positivo no es una cantidad, y decirlo mientras el operador esta escribiendo es
  // acusarlo de un numero que todavia no termino.
  it('un campo vacio no es un error: es un campo que nadie toco', () => {
    assert.strictEqual(stockBoxesInvalid(''), false);
    assert.strictEqual(stockBoxesInvalid('   '), false);
  });

  it('lo que no es un entero positivo, si lo es', () => {
    assert.strictEqual(stockBoxesInvalid('0'), true);
    assert.strictEqual(stockBoxesInvalid('-2'), true);
    assert.strictEqual(stockBoxesInvalid('2.5'), true);
    assert.strictEqual(stockBoxesInvalid('abc'), true);
    assert.strictEqual(stockBoxesInvalid('3'), false);
    assert.strictEqual(stockBoxesInvalid(' 4 '), false);
  });

  it('el boton de agregar esta apagado sin nada que agregar o con algo que no se puede agregar', () => {
    assert.strictEqual(stockAddDisabled(''), true);
    assert.strictEqual(stockAddDisabled('   '), true);
    assert.strictEqual(stockAddDisabled('0'), true);
    assert.strictEqual(stockAddDisabled('x'), true);
    assert.strictEqual(stockAddDisabled('3'), false);
  });
});

describe('cuando se muestra el cartel de una caja', () => {
  it('no muestra nada mientras el campo esta en foco', () => {
    assert.deepStrictEqual(stockVisibleErrors({ '787': '0' }, { blurred: { '787': true }, focused: '787' }), []);
  });

  it('lo muestra cuando el campo fue dejado', () => {
    assert.deepStrictEqual(stockVisibleErrors({ '787': '0' }, { blurred: { '787': true }, focused: null }), ['787']);
  });

  it('no lo muestra en un campo que nunca fue dejado ni en uno vacio', () => {
    assert.deepStrictEqual(stockVisibleErrors({ '787': '0' }, { blurred: {}, focused: null }), []);
    assert.deepStrictEqual(stockVisibleErrors({ '787': '' }, { blurred: { '787': true }, focused: null }), []);
  });

  it('volver a entrar a corregir apaga el cartel viejo', () => {
    assert.deepStrictEqual(stockVisibleErrors({ '787': '0' }, { blurred: { '787': true }, focused: '787' }), []);
  });

  it('nombra solo los productos incorrectos, y en orden', () => {
    const cantidades = { '787': '0', '1800': '3', '1047': 'abc' };
    assert.deepStrictEqual(
      stockVisibleErrors(cantidades, { blurred: { '787': true, '1800': true, '1047': true }, focused: null }),
      ['1047', '787']
    );
  });
});
