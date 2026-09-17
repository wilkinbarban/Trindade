import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseSelectedProducts } from './reports.schema.js';

/**
 * The contract suite exercises this helper through the API, by corrupting a stored row and then
 * reading and exporting the report. These cover the branches directly, which is where the fallback
 * policy itself is defined.
 */
describe('parseSelectedProducts', () => {
  it('reports nothing stored as undefined, so the field is absent rather than empty', () => {
    assert.equal(parseSelectedProducts(null), undefined);
    assert.equal(parseSelectedProducts(undefined), undefined);
    assert.equal(parseSelectedProducts(''), undefined);
  });

  it('returns the stored list when it is valid', () => {
    assert.deepEqual(parseSelectedProducts('["Nhoque 1kg","Rolo 2kg"]'), ['Nhoque 1kg', 'Rolo 2kg']);
    assert.deepEqual(parseSelectedProducts('[]'), []);
  });

  // Both of these used to be an unguarded JSON.parse, which turned a corrupt row into a driver
  // error on a read. Degrading to an empty selection keeps the report readable.
  it('degrades to an empty list when the content does not parse', () => {
    assert.deepEqual(parseSelectedProducts('{ this is not json'), []);
    assert.deepEqual(parseSelectedProducts('["unterminated'), []);
    assert.deepEqual(parseSelectedProducts('null'), []);
  });

  it('degrades to an empty list when the content parses but is the wrong shape', () => {
    assert.deepEqual(parseSelectedProducts('{"product":"Nhoque"}'), []);
    assert.deepEqual(parseSelectedProducts('[1,2,3]'), []);
    assert.deepEqual(parseSelectedProducts('"a bare string"'), []);
  });
});
