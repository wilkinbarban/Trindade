import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The wiring of the two result windows, read as source.
 *
 * The behaviour is covered by the browser run in `__e2e__/erp-result-dialogs.spec.ts`; what is asserted here
 * is that the windows stay wired to the code that decides their verdict, and that no window loses the 44px
 * close target the phone needs.
 */
function read(...parts: string[]): string {
  return readFileSync(new URL(parts.join('/'), import.meta.url), 'utf8');
}

const page = read('..', 'MassasLaunchPage.tsx');
const panel = read('AeroIntegrationPanel.tsx');
const dialog = read('AeroResultDialog.tsx');

describe('result dialogs wiring', () => {
  it('opens the movements window with the count the ERP brought back', () => {
    assert.ok(page.includes('movementsSyncFeedback(read)'));
    assert.ok(page.includes('testId="aero-sync-result-modal"'));
    assert.ok(page.includes("t('massas.erp.syncOkTitle'") || page.includes("'massas.erp.syncOkTitle'"));
  });

  it('opens the connection test window from both channels', () => {
    assert.ok(panel.includes('connectionVerdictTone(testOutcome.verdict)'));
    assert.ok(panel.includes('describeProbe(outcome, t)'));
    assert.ok(panel.includes('testId="aero-test-result-modal"'));
  });

  it('points at the mirror only when the launch reached the company', () => {
    assert.ok(page.includes('launchMirrorHint(resultModal)'));
    assert.ok(page.includes("t('massas.erp.mirrorHint')"));
    assert.ok(page.includes('data-testid="massa-result-mirror-hint"'));
  });

  it('keeps one thumb-sized close button for every verdict', () => {
    assert.ok(dialog.includes('min-h-[44px]'));
    assert.ok(dialog.includes('data-testid="aero-result-close"'));
    assert.ok(dialog.includes("t('massas.close'"));
    // Ningún texto de la ventana se escribe en un idioma fijo.
    assert.equal(/<Button[\s\S]*?>\s*[A-Za-zÁÉÍÓÚáéíóúñÑ]{4,}\s*<\/Button>/.test(dialog), false);
  });
});
