import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  ASSAI_PRODUCTS,
  assaiHasError,
  assaiLines,
  assaiMessage,
  assaiTotalBoxes,
  boxesFor,
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

    assert.ok(mensagem.includes('Nhoque 1kg: 2 caixas (30 und)'));
    assert.ok(mensagem.includes('Rolo 2kg: 2 caixas (20 und)'));
    assert.ok(mensagem.includes('Total: 4 caixas'));
    assert.equal(mensagem.includes('Lasanha'), false);
  });

  it('avisa que nao ha pedido em vez de mandar uma mensagem vazia', () => {
    assert.ok(assaiMessage(assaiLines({})).includes('Nenhum produto'));
  });
});
