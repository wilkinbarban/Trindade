import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The Spanish and Portuguese dictionaries have to say the same things.
 *
 * A key that exists in only one language is not a missing translation: it is a screen that shows the raw
 * key name, or a Portuguese sentence, to whoever reads the app in the other language.
 */
const LOCALES = ['pt-BR', 'es'] as const;

type Dictionary = { [key: string]: string | Dictionary };

function read(locale: string): Dictionary {
  const url = new URL(`../i18n/locales/${locale}.json`, import.meta.url);
  return JSON.parse(readFileSync(url, 'utf8')) as Dictionary;
}

function flatten(node: Dictionary, prefix = ''): Map<string, string> {
  const flat = new Map<string, string>();
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') flat.set(path, value);
    else for (const [inner, text] of flatten(value, path)) flat.set(inner, text);
  }
  return flat;
}

const dictionaries = new Map(LOCALES.map((locale) => [locale, flatten(read(locale))]));

describe('locale parity', () => {
  it('has the same keys in Portuguese and Spanish', () => {
    const [pt, es] = [dictionaries.get('pt-BR')!, dictionaries.get('es')!];
    assert.deepEqual([...pt.keys()].filter((key) => !es.has(key)), [], 'keys only in pt-BR');
    assert.deepEqual([...es.keys()].filter((key) => !pt.has(key)), [], 'keys only in es');
  });

  it('keeps the placeholders of the ERP results in both languages', () => {
    for (const locale of LOCALES) {
      const flat = dictionaries.get(locale)!;
      for (const key of ['massas.erp.syncOk_one', 'massas.erp.syncOk_other']) {
        assert.match(flat.get(key) ?? '', /\{\{count\}\}/, `${locale} ${key} lost {{count}}`);
        assert.match(flat.get(key) ?? '', /\{\{date\}\}/, `${locale} ${key} lost {{date}}`);
      }
      for (const key of ['massas.erp.syncEmpty']) {
        assert.match(flat.get(key) ?? '', /\{\{date\}\}/, `${locale} ${key} lost {{date}}`);
      }
      for (const key of [
        'massas.erp.syncOkTitle',
        'massas.erp.syncErrorTitle',
        'massas.erp.syncError',
        'massas.erp.mirrorHint',
        'massas.aero.resultTestOkTitle',
        'massas.aero.resultTestErrorTitle',
        'massas.aero.resultTestWarnTitle',
        'massas.close',
      ]) {
        assert.ok((flat.get(key) ?? '').trim().length > 0, `${locale} is missing ${key}`);
      }
    }
  });
});
