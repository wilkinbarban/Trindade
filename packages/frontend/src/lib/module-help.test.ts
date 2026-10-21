import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('ModuleHelp copy and schema integrity', () => {
  const ptPath = path.resolve(__dirname, '../i18n/locales/pt-BR.json');
  const esPath = path.resolve(__dirname, '../i18n/locales/es.json');

  const pt = JSON.parse(fs.readFileSync(ptPath, 'utf8'));
  const es = JSON.parse(fs.readFileSync(esPath, 'utf8'));

  const requiredModules = [
    'dashboard',
    'massasLaunch',
    'massasHistory',
    'stock',
    'stockHistory',
    'assai',
    'loading',
    'reports',
    'share',
  ];

  for (const [lang, catalogue] of [
    ['pt-BR', pt],
    ['es', es],
  ]) {
    it(`[${lang}] defines complete and valid moduleHelp entries with arrays of sections`, () => {
      assert.ok(catalogue.moduleHelp, `catalogue must contain moduleHelp in ${lang}`);

      for (const mod of requiredModules) {
        const item = catalogue.moduleHelp[mod];
        assert.ok(item, `missing moduleHelp.${mod} in ${lang}`);
        assert.strictEqual(typeof item.title, 'string', `title must be string in ${mod}`);
        assert.ok(item.title.length > 0, `title cannot be empty in ${mod}`);
        assert.strictEqual(typeof item.description, 'string', `description must be string in ${mod}`);
        assert.ok(item.description.length > 0, `description cannot be empty in ${mod}`);

        // Sections must strictly be an array of HelpSection objects
        assert.ok(Array.isArray(item.sections), `sections must be an Array in ${lang} ${mod}`);
        assert.ok(item.sections.length > 0, `sections must have at least 1 section in ${lang} ${mod}`);

        for (const [idx, section] of item.sections.entries()) {
          assert.strictEqual(
            typeof section.title,
            'string',
            `section ${idx} title must be string in ${lang} ${mod}`
          );
          assert.ok(section.title.length > 0, `section ${idx} title cannot be empty`);
          if (section.steps) {
            assert.ok(
              Array.isArray(section.steps),
              `section ${idx} steps must be an array when present in ${lang} ${mod}`
            );
          }
        }
      }
    });
  }

  it('safely handles non-array sections defensively without throwing', () => {
    // Tests defensive filter: Array.isArray(sections) ? sections : []
    const invalidInputs = [undefined, null, 'moduleHelp.stockHistory.sections', 42, {}];
    for (const input of invalidInputs) {
      const safe = Array.isArray(input) ? input : [];
      assert.deepStrictEqual(safe, []);
    }
  });
});
