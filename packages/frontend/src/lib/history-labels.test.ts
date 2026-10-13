import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  HISTORY_ACTIONS,
  HISTORY_ACTION_LABEL_KEYS,
  HISTORY_CONFIRM_KEYS,
  HISTORY_FILTER_LABEL_KEYS,
  historyActionLabelKey,
  type HistoryDomain,
} from './history-labels.js';

function catalogue(name: string): Record<string, Record<string, string>> {
  return JSON.parse(readFileSync(join(import.meta.dirname, `../i18n/locales/${name}.json`), 'utf8'));
}

function valueAt(source: Record<string, Record<string, string>>, key: string): string | undefined {
  const [section, name] = key.split('.');
  return source[section]?.[name];
}

test('every icon action resolves to its own key instead of a shared generic one', () => {
  assert.equal(historyActionLabelKey('report', 'view'), 'history.viewReport');
  assert.equal(historyActionLabelKey('report', 'delete'), 'history.deleteReport');
  assert.equal(historyActionLabelKey('loading', 'deactivate'), 'history.deactivateLoading');
  const all = (Object.keys(HISTORY_ACTIONS) as HistoryDomain[]).flatMap((domain) =>
    HISTORY_ACTIONS[domain].map((action) => historyActionLabelKey(domain, action))
  );
  assert.equal(new Set(all).size, all.length, 'a duplicated key would name two different actions alike');
  assert.equal(all.length, 5, 'report offers three icon actions and loading two');
});

test('every key the history screens ask for exists and is intentional in both catalogues', () => {
  const keys = [
    ...HISTORY_FILTER_LABEL_KEYS,
    ...HISTORY_ACTION_LABEL_KEYS,
    ...HISTORY_CONFIRM_KEYS,
  ];
  for (const name of ['pt-BR', 'es']) {
    const source = catalogue(name);
    for (const key of keys) {
      const value = valueAt(source, key);
      assert.ok(typeof value === 'string' && value.trim().length > 0, `${name} is missing ${key}`);
    }
  }
});

test('the row action names carry the record context, not only the verb', () => {
  for (const name of ['pt-BR', 'es']) {
    const source = catalogue(name);
    for (const key of HISTORY_ACTION_LABEL_KEYS) {
      assert.match(valueAt(source, key) ?? '', /\{\{\s*date\s*\}\}/, `${name} ${key} must interpolate the date`);
    }
  }
});

test('no icon-action key is left declared but unused, or used but undeclared', () => {
  assert.deepEqual(
    [...HISTORY_ACTION_LABEL_KEYS].sort(),
    ['history.viewReport', 'history.deactivateReport', 'history.deleteReport',
      'history.deactivateLoading', 'history.deleteLoading'].sort(),
    'the loading history deliberately has no icon-only view action'
  );
});
