import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const clientPath = join(import.meta.dirname, '../api/client.ts');

function catalogue(name: string): Record<string, Record<string, string>> {
  return JSON.parse(readFileSync(join(import.meta.dirname, `../i18n/locales/${name}.json`), 'utf8'));
}

function clientSource(): string {
  return readFileSync(clientPath, 'utf8');
}

/** Keys the client resolves through the catalogue, read from the client itself rather than listed. */
function referencedKeys(): string[] {
  return [...clientSource().matchAll(/i18n\.t\(\s*['"]([^'"]+)['"]/g)].map((match) => match[1]);
}

/**
 * The text of every `new ApiClientError(...)` call, with its parentheses balanced.
 *
 * Reading whole calls instead of whole literals is the point: what matters is the message that
 * reaches the operator, and that message is an argument of this constructor. Judging the file
 * literal by literal would miss a message that nothing else distinguishes from a header name.
 */
function apiClientErrorCalls(): string[] {
  const source = clientSource();
  const marker = 'new ApiClientError(';
  const calls: string[] = [];
  let index = source.indexOf(marker);
  while (index !== -1) {
    let depth = 0;
    let end = source.length - 1;
    for (let position = index + marker.length - 1; position < source.length; position += 1) {
      if (source[position] === '(') depth += 1;
      else if (source[position] === ')') {
        depth -= 1;
        if (depth === 0) {
          end = position;
          break;
        }
      }
    }
    calls.push(source.slice(index, end + 1));
    index = source.indexOf(marker, end);
  }
  return calls;
}

test('the HTTP client resolves its own messages through the catalogue', () => {
  const keys = referencedKeys();
  assert.ok(keys.length >= 4, 'the client should not keep sending unconditional literals');
  for (const name of ['pt-BR', 'es']) {
    const source = catalogue(name);
    for (const key of keys) {
      const [section, message] = key.split('.');
      const value = source[section]?.[message];
      assert.ok(typeof value === 'string' && value.trim().length > 0, `${name} is missing ${key}`);
    }
  }
});

test('no message the client reports is written in the source instead of the catalogue', () => {
  const calls = apiClientErrorCalls();
  assert.ok(calls.length >= 5, 'expected the client to raise its failures through this constructor');
  for (const call of calls) {
    for (const literal of [...call.matchAll(/['"]([^'"\n]{4,})['"]/g)].map((match) => match[1])) {
      assert.ok(
        call.includes(`i18n.t('${literal}')`) || call.includes(`i18n.t("${literal}")`),
        `"${literal}" is reported verbatim; it must be resolved through the catalogue`
      );
    }
  }
});

test('the client no longer carries its own language in source', () => {
  const source = clientSource();
  for (const literal of ['Sessão expirada', "'Unauthorized'", "'Request failed'", "'Upload failed'"]) {
    assert.ok(!source.includes(literal), `${literal} must come from the catalogue, not from the client`);
  }
});

test('a Spanish reader is not shown Portuguese for a client-authored failure', () => {
  const es = catalogue('es');
  const pt = catalogue('pt-BR');
  for (const key of referencedKeys()) {
    const [section, message] = key.split('.');
    assert.notEqual(es[section]?.[message], pt[section]?.[message], `${key} is the same in both languages`);
  }
});
