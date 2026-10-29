import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const dialogPath = path.resolve(__dirname, 'AeroShortcutDialog.tsx');
const source = fs.readFileSync(dialogPath, 'utf8');
/** La parte del diálogo que describe la receta; el resto es el marco del `Dialog`. */
const root = source.slice(source.indexOf('<ol'), source.indexOf('</ol>'));

const catalogues = ['pt-BR', 'es'].map((lang) => ({
  lang,
  aero: JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../i18n/locales', `${lang}.json`), 'utf8')).massas
    .aero as Record<string, string>,
}));

describe('aero-shortcut dialog: the one bridge the phone has to build', () => {
  it('numbers exactly the seventeen actions the bridge takes, and nothing else', () => {
    const steps = [...source.matchAll(/<StepItem n=\{(\d+)\}/g)].map((match) => Number(match[1]));
    // Dos ramas: el lote (6-13) y el producto suelto (14-16), con el mismo cierre para las dos.
    assert.deepEqual(steps, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17]);
    // El título no escribe el número a mano: lo lee del recipe, así que no puede quedar desactualizado.
    assert.ok(source.includes("t('massas.aero.shortcutLaunchTitle', { count: recipe.actionCount })"));
    for (const { lang, aero } of catalogues) {
      assert.ok(
        aero.shortcutLaunchTitle.includes('{{count}}'),
        `massas.aero.shortcutLaunchTitle in ${lang} must read the count it is given`,
      );
    }
  });

  it('reads the dictionary keys the envelope carries, and takes the Authorization from inside headers', () => {
    // Las claves las arma `aeroBridgeShortcutRecipe`; el diálogo las muestra con su botón de copiar. El
    // Authorization no es una clave del sobre: se lee del diccionario `headers`, que es lo que viaja.
    for (const key of ['url', 'headers', 'authorization', 'body', 'bodies', 'callback']) {
      assert.ok(root.includes(`id="${key}"`), `missing copyable key ${key}`);
      assert.ok(
        root.includes(`value={recipe.keys.${key}}`),
        `the ${key} step must show the key name it copies`,
      );
    }
    // La receta anterior pedía una clave `auth` que el sobre nunca trajo.
    assert.equal(root.includes('id="auth"'), false);
    assert.equal(root.includes('value="auth"'), false);
    assert.equal(root.includes('id="endpoint"'), false);
  });

  it('never hardcodes the address or the credential the envelope carries', () => {
    // Es la razón de ser del puente: cambiar la clave o la IP es un cambio en la web, no en el iPhone.
    assert.equal(/Authorization: Basic/.test(source), false);
    assert.equal(source.includes('basicAuthHeader'), false);
    assert.equal(/https?:\/\/\d+\.\d+\.\d+\.\d+/.test(source), false);
    // Y el sobre de prueba se muestra serializado, no como diccionario que el teléfono tenga que armar.
    assert.ok(source.includes('JSON.stringify(recipe.sampleEnvelope'));
  });

  it('no longer mentions a second shortcut nor a user code to fill in', () => {
    assert.equal(source.includes('testShortcutName'), false);
    assert.equal(source.includes('shortcutTest'), false);
    assert.equal(source.includes('userCode'), false);
    assert.equal(/t\('massas\.aero\.userCode(Label|Hint)'\)/.test(source), false);
  });

  it('defines every copy it renders in both catalogues', () => {
    const used = new Set([...source.matchAll(/t\('massas\.aero\.([A-Za-z0-9_]+)'/g)].map((match) => match[1]));
    assert.ok(used.size > 15, `expected the dialog to render its copy from the catalogue, saw ${used.size} keys`);
    for (const { lang, aero } of catalogues) {
      for (const key of used) {
        assert.equal(typeof aero[key], 'string', `missing massas.aero.${key} in ${lang}`);
        assert.ok(aero[key].length > 0, `empty massas.aero.${key} in ${lang}`);
      }
    }
  });

  it('keeps the two catalogues free of the copy the split design needed', () => {
    for (const { lang, aero } of catalogues) {
      for (const key of ['shortcutTestTitle', 'shortcutTestIntro', 'userCodeLabel', 'userCodeHint'] as const) {
        assert.equal(key in aero, false, `massas.aero.${key} is still in ${lang}`);
      }
      // Sólo sobreviven los avisos del panel, que siguen nombrando el resultado de la prueba.
      for (const key of ['testShortcutOpening', 'testShortcutOk', 'testShortcutError', 'testShortcutUnknown']) {
        assert.equal(typeof aero[key], 'string', `missing massas.aero.${key} in ${lang}`);
      }
    }
  });
});
