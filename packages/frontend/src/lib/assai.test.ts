import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  ASSAI_PRODUCTS,
  assaiHasError,
  assaiLines,
  assaiMessage,
  assaiTotalBoxes,
  boxesFor,
  assaiHasVisibleError,
  assaiVisibleErrors,
} from './assai';

const nhoque = 'Nhoque 1kg';
const roloDois = 'Rolo 2kg';

describe('o catalogo do Assai', () => {
  it('traz os nove produtos com as unidades de cada caixa', () => {
    assert.equal(ASSAI_PRODUCTS.length, 9);
    assert.equal(ASSAI_PRODUCTS[0].name, nhoque);
    assert.equal(ASSAI_PRODUCTS[0].unitsPerBox, 15);
    assert.equal(ASSAI_PRODUCTS[1].name, 'Quadrada');
    assert.equal(ASSAI_PRODUCTS[1].unitsPerBox, 14);
    assert.equal(ASSAI_PRODUCTS[8].name, roloDois);
    assert.equal(ASSAI_PRODUCTS[8].unitsPerBox, 10);
  });
});

describe('boxesFor', () => {
  it('divide, y a tela decide o que fazer com o resto', () => {
    assert.equal(boxesFor(15, 15), 1);
    assert.equal(boxesFor(45, 15), 3);
    assert.equal(boxesFor(16, 15), 16 / 15);
  });
});

describe('assaiLines', () => {
  it('marca a quantidade exata como pedido e o resto como erro', () => {
    const linhas = assaiLines({ [nhoque]: '30', Quadrada: '15' });
    const primeira = linhas[0];
    const segunda = linhas[1];

    assert.equal(primeira.boxes, 2);
    assert.equal(primeira.exact, true);
    assert.equal(segunda.exact, false);
  });

  it('deixa o produto vazio fora do pedido em vez de chama-lo de erro', () => {
    const linhas = assaiLines({ [nhoque]: '' });

    assert.equal(linhas[0].filled, false);
    assert.equal(linhas[0].exact, true);
    assert.equal(assaiHasError(linhas), false);
  });

  it('trata zero como nada pedido e um texto como erro', () => {
    assert.equal(assaiHasError(assaiLines({ [nhoque]: '0' })), false);
    assert.equal(assaiHasError(assaiLines({ [nhoque]: 'abc' })), true);
  });

  it('soma as caixas do pedido inteiro', () => {
    const linhas = assaiLines({ [nhoque]: '30', Quadrada: '28', 'Disco 200g': '80' });
    assert.equal(assaiTotalBoxes(linhas), 5);
  });
});

describe('assaiMessage', () => {
  it('leva so o que foi pedido, com as suas caixas', () => {
    const mensagem = assaiMessage(assaiLines({ [nhoque]: '30', [roloDois]: '20' }));

    assert.ok(mensagem.includes('Nhoque 1kg: 2 caixas'));
    assert.ok(mensagem.includes('Rolo 2kg: 2 caixas'));
    assert.ok(!mensagem.includes(' und'), 'a quantidade por caixa nao viaja no pedido');
    assert.ok(mensagem.includes('Total: 4 caixas'));
    assert.equal(mensagem.includes('Lasanha'), false);
  });

  it('avisa que nao ha pedido em vez de mandar uma mensagem vazia', () => {
    assert.ok(assaiMessage(assaiLines({})).includes('Nenhum produto'));
  });
});

describe('cuando se muestra el error de una linea', () => {
  // El cartel de un campo es una decision sobre el momento y no sobre el numero: la misma linea inexacta
  // se muestra o no segun donde este el operador. Por eso la regla vive en el modulo puro y la pantalla
  // solo pregunta, en vez de llevar su propia copia de la condicion.
  it('no muestra nada mientras el campo esta en foco, aunque la cantidad este mal', () => {
    const lines = assaiLines({ [nhoque]: '20' });
    assert.ok(assaiHasError(lines), 'el pedido sigue siendo inexacto');
    assert.deepStrictEqual(assaiVisibleErrors(lines, { blurred: { [nhoque]: true }, focused: nhoque }), []);
  });

  it('muestra el error de un campo que ya fue dejado', () => {
    const lines = assaiLines({ [nhoque]: '20' });
    assert.deepStrictEqual(assaiVisibleErrors(lines, { blurred: { [nhoque]: true }, focused: null }), [nhoque]);
  });

  it('no muestra el error de un campo que nunca fue dejado', () => {
    const lines = assaiLines({ [nhoque]: '20' });
    assert.deepStrictEqual(assaiVisibleErrors(lines, { blurred: {}, focused: null }), []);
  });

  it('volver a entrar a editar apaga el cartel viejo', () => {
    const lines = assaiLines({ [nhoque]: '20' });
    assert.deepStrictEqual(assaiVisibleErrors(lines, { blurred: { [nhoque]: true }, focused: nhoque }), []);
  });

  it('un campo vacio no es un error visible aunque se haya dejado', () => {
    assert.deepStrictEqual(assaiVisibleErrors(assaiLines({}), { blurred: { [nhoque]: true }, focused: null }), []);
  });

  it('el aviso de abajo sigue a los errores visibles y no al pedido inexacto', () => {
    const lines = assaiLines({ [nhoque]: '20' });
    assert.strictEqual(assaiHasVisibleError(lines, { blurred: { [nhoque]: true }, focused: nhoque }), false);
    assert.strictEqual(assaiHasVisibleError(lines, { blurred: { [nhoque]: true }, focused: null }), true);
    assert.strictEqual(assaiHasVisibleError(lines, { blurred: {}, focused: null }), false);
  });
});
