import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { WEAK_PASSWORDS, passwordIssue, passwordIssueKey } from './password-policy.js';

const localesDir = join(import.meta.dirname, '../i18n/locales');

function catalogue(name: string): Record<string, Record<string, string>> {
  return JSON.parse(readFileSync(join(localesDir, `${name}.json`), 'utf8'));
}

test('the form accepts 15 visible graphemes, counting combining marks and emoji as one', () => {
  assert.equal(passwordIssue('a'.repeat(14)), 'tooShort');
  assert.equal(passwordIssue('turno-limpo2026'), null);
  assert.equal(passwordIssue('e\u0301'.repeat(14)), 'tooShort', '14 accented graphemes are 28 code units');
  assert.equal(passwordIssue('e\u0301'.repeat(15)), null);
  assert.equal(passwordIssue('\u{1F44D}'.repeat(14)), 'tooShort', '14 emoji are 28 code units and 56 bytes');
  assert.equal(passwordIssue('\u{1F44D}'.repeat(15)), null);
  assert.equal(passwordIssue(''), 'tooShort');
  // A run of one repeated character is only rejected when it is on the curated list; this one is.
  assert.equal(passwordIssue('b'.repeat(15)), null);
  assert.equal(passwordIssue('a'.repeat(15)), 'tooCommon');
});

test('the byte bound is measured on the original text, so nothing is silently truncated', () => {
  assert.equal(passwordIssue('a'.repeat(72)), null);
  assert.equal(passwordIssue('a'.repeat(73)), 'tooLong');
  assert.equal(passwordIssue('a'.repeat(70) + 'é'), null, '72 bytes exactly');
  assert.equal(passwordIssue('a'.repeat(71) + 'é'), 'tooLong', '73 bytes');
  assert.equal(passwordIssue('\u{1F469}\u200D\u{1F4BB}'.repeat(15)), 'tooLong', '60 bytes of text, 5 per emoji');
});

test('the curated weak list is applied case-insensitively to the whole value', () => {
  assert.equal(passwordIssue('qwerty123456789'), 'tooCommon');
  assert.equal(passwordIssue('QWERTY123456789'), 'tooCommon');
  assert.equal(passwordIssue('aaaaaaaaaaaaaaa'), 'tooCommon');
  assert.equal(passwordIssue('Higiene limpa hoje 2026'), null, 'containing a listed word is not enough');
});

test('every issue maps to a translation key that exists in both catalogues', () => {
  for (const name of ['pt-BR', 'es']) {
    const source = catalogue(name);
    for (const issue of ['tooShort', 'tooLong', 'tooCommon'] as const) {
      const key = passwordIssueKey(issue);
      assert.match(key, /^auth\.password/);
      const value = source.auth?.[key.split('.')[1]];
      assert.ok(typeof value === 'string' && value.trim().length > 0, `${name} is missing ${key}`);
    }
  }
});

test('the frontend weak list stays identical to the one the server enforces', () => {
  const backendPath = join(import.meta.dirname, '../../../backend/src/modules/auth/weak-passwords.ts');
  const source = readFileSync(backendPath, 'utf8');
  const block = source.match(/WEAK_PASSWORDS[^=]*=\s*\[([\s\S]*?)\]/);
  assert.ok(block, 'could not locate the backend weak list');
  const backend = [...block[1].matchAll(/'([^']+)'/g)].map((match) => match[1]);
  assert.ok(backend.length > 0, 'the backend list parsed as empty');
  assert.deepEqual(
    [...WEAK_PASSWORDS].sort(),
    backend.sort(),
    'the two copies drifted; update both together or share one source'
  );
});
