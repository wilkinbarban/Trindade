import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { SETUP_COPY_KEYS, SETUP_SPEC_LITERALS, SETUP_SHARED_SPELLING_KEYS } from './setup-copy.js';

const localesDir = join(import.meta.dirname, '../i18n/locales');

function catalogue(name: string): Record<string, Record<string, string>> {
  return JSON.parse(readFileSync(join(localesDir, `${name}.json`), 'utf8'));
}

function valueAt(source: Record<string, Record<string, string>>, key: string): string | undefined {
  const [section, name] = key.split('.');
  return source[section]?.[name];
}

test('both catalogues carry every setup string with intentional text', () => {
  for (const name of ['pt-BR', 'es']) {
    const source = catalogue(name);
    for (const key of SETUP_COPY_KEYS) {
      const value = valueAt(source, key);
      assert.ok(typeof value === 'string' && value.trim().length > 0, `${name} is missing ${key}`);
    }
  }
});

test('the Spanish setup copy is actually translated, not a copy of the Portuguese', () => {
  const pt = catalogue('pt-BR');
  const es = catalogue('es');
  const shared = new Set<string>(SETUP_SHARED_SPELLING_KEYS);
  const same = SETUP_COPY_KEYS.filter(
    (key) => !shared.has(key) && valueAt(pt, key) === valueAt(es, key)
  );
  assert.deepEqual(same, [], `these keys still read as untranslated Portuguese: ${same.join(', ')}`);
});

test('the Portuguese setup copy still matches what the end-to-end spec looks for', () => {
  const spec = readFileSync(join(import.meta.dirname, '../__e2e__/setup-provisioning.spec.ts'), 'utf8');
  const pt = catalogue('pt-BR');
  const values = SETUP_COPY_KEYS.map((key) => valueAt(pt, key));
  for (const literal of SETUP_SPEC_LITERALS) {
    assert.ok(spec.includes(literal), `the spec no longer looks for ${literal}; update this contract`);
    assert.ok(values.includes(literal), `${literal} is asserted by the spec but no setup key carries it`);
  }
});
