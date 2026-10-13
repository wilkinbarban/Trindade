import { test } from 'node:test';
import assert from 'node:assert/strict';
import { translateText } from './translator.js';

test('translation stays offline for both directions with or without a provider key', async (t) => {
  let requests = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    requests += 1;
    throw new Error('Network access forbidden in this test');
  });
  const previous = process.env.GOOGLE_TRANSLATE_API_KEY;
  try {
    for (const key of [undefined, 'synthetic-unused-provider-key']) {
      if (key === undefined) delete process.env.GOOGLE_TRANSLATE_API_KEY;
      else process.env.GOOGLE_TRANSLATE_API_KEY = key;
      assert.equal(await translateText('Producto pendiente xyz', 'es'), 'Produto pendente xyz');
      assert.equal(await translateText('Produto pendente xyz', 'pt'), 'Producto pendiente xyz');
      assert.equal(await translateText('  xyz  ', 'es'), 'xyz');
      assert.equal(await translateText('  xyz  ', 'pt'), 'xyz');
    }
    assert.equal(requests, 0, 'Operational text must never reach an external provider');
  } finally {
    if (previous === undefined) delete process.env.GOOGLE_TRANSLATE_API_KEY;
    else process.env.GOOGLE_TRANSLATE_API_KEY = previous;
  }
});

test('local dictionary, whitespace and empty-input behavior remain unchanged', async (t) => {
  let requests = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    requests += 1;
    throw new Error('Network access forbidden in this test');
  });
  assert.equal(await translateText('  TODO BIEN  ', 'es'), 'Tudo bem');
  assert.equal(await translateText('Tudo bem', 'pt'), 'todo bien');
  assert.equal(await translateText('se realizo limpieza', 'es'), 'Foi realizada limpeza');
  assert.equal(await translateText(' \t\n ', 'es'), '');
  assert.equal(await translateText('', 'pt'), '');
  assert.equal(requests, 0);
});
