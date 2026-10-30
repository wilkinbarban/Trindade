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
// La receta está en cuatro bloques numerados: el corte va del primer bloque al último. No sirve cortar por
// `<ol>`, porque el helper `StepBlock` también los declara y el corte quedaría en el componente vacío.
const root = source.slice(source.indexOf('<StepBlock'), source.lastIndexOf('</StepBlock>'));

const catalogues = ['pt-BR', 'es'].map((lang) => ({
  lang,
  aero: JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../i18n/locales', `${lang}.json`), 'utf8')).massas
    .aero as Record<string, string>,
}));

describe('aero-shortcut dialog: the one bridge the phone has to build', () => {
  it('numbers exactly the twenty actions the bridge takes, and nothing else', () => {
    const steps = [...source.matchAll(/<StepItem n=\{(\d+)\}/g)].map((match) => Number(match[1]));
    // Cuatro bloques: desempaquetar (1-5), lote (6-14), individual (15-18) y retorno (19-20). Es el número
    // que el atajo tiene de verdad: una receta más corta deja el `Si` sin cerrar y no vuelve a Safari.
    assert.deepEqual(steps, Array.from({ length: 20 }, (_, index) => index + 1));
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

  it('offers the certified iCloud shortcut above the manual recipe, and a way back to it', () => {
    // La tarjeta va antes de la lista: instalar en un toque es el camino principal, armar veinte acciones
    // a mano es el respaldo.
    const icloud = source.indexOf('data-testid="aero-shortcut-icloud-link"');
    assert.ok(icloud > 0, 'the card offers a link to the certified shortcut');
    assert.ok(icloud < source.indexOf('<StepBlock'), 'the one-tap card comes before the manual steps');

    const card = source.slice(icloud - 700, icloud + 700);
    assert.ok(card.includes('href={AERO_SHORTCUT_ICLOUD_URL}'), 'the link is the certified one, not a copy');
    assert.ok(card.includes('target="_blank"'));
    assert.ok(card.includes('rel="noopener noreferrer"'));
    // Un enlace que se abre en otra app no puede ser un renglón de texto: es un botón de 44 px.
    assert.ok(/min-h-\[44px\]/.test(card), 'the install control is thumb-sized');
  });

  it('groups the manual recipe in four blocks so the operator knows where each step belongs', () => {
    for (const block of ['shortcutBlockUnpack', 'shortcutBlockBatch', 'shortcutBlockSingle', 'shortcutBlockReturn']) {
      assert.ok(source.includes(`t('massas.aero.${block}')`), `missing block title ${block}`);
    }
  });

  it('warns in the body step that the variable is renamed to body_item', () => {
    // iOS resuelve variables por nombre: `body` en la misma receta que `bodies` termina mandando el último
    // elemento de la repetición como cuerpo del producto suelto.
    assert.ok(root.includes("t('massas.aero.shortcutStepBodyItem')"));
    for (const { lang, aero } of catalogues) {
      assert.ok(
        aero.shortcutStepBodyItem.includes('body_item'),
        `massas.aero.shortcutStepBodyItem in ${lang} must name the renamed variable`,
      );
      assert.ok(
        aero.shortcutStepBodyItem.toLowerCase().includes('bodies'),
        `massas.aero.shortcutStepBodyItem in ${lang} must say what it is not`,
      );
    }
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
