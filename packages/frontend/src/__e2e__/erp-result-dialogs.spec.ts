import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

/**
 * The verdicts of the two actions that leave the app.
 *
 * The phone runs the Atajos app, so both come back as a URL, not as a screen. What matters to the plant is
 * that returning to the page says what happened and that the window can be closed with a thumb.
 */
const API = 'http://localhost:3099/api';
const OWNER_PASSWORD = 'wilkin-result-dialogs-2026';

/** A movements answer shaped like the ERP's: eleven readable seats plus one the bridge could not read. */
function movementsAnswer(success: boolean) {
  const seats = Array.from({ length: 11 }, (_, index) => ({
    ENT_LAN: 20000 + index,
    PRO_COD: '001192',
    PRO_DES: 'MASSA QUADRADA',
    PRO_QTD: 15,
    ENT_DTA: '2026-10-29',
    ENT_USU: 'WILKIN',
    ENTSAI: 'E',
  }));
  return JSON.stringify(success ? { success: true, data: seats } : { success: false, message: 'Firebird indisponível' });
}

function movementsCallbackUrl(success: boolean) {
  return `/massas-launch?aero_action=movimentos&aero_data=${encodeURIComponent(movementsAnswer(success))}`;
}

test.use({ viewport: { width: 375, height: 667 } });

test.describe('result dialogs on a phone screen', () => {
  test('the movements sync says how many seats came back, and closes with a thumb', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    await page.goto(movementsCallbackUrl(true));

    const dialog = page.locator('[data-testid="aero-sync-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Movimentos Sincronizados');
    await expect(page.locator('[data-testid="aero-result-message"]')).toHaveText(
      /11 movimentos lidos do ERP para \d{2}\/\d{2}\/\d{4}\./,
    );

    const close = page.locator('[data-testid="aero-result-close"]');
    expect((await close.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    // The dialog never spills sideways on the narrowest phone we support.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    await close.click();
    await expect(dialog).toHaveCount(0);
  });

  test('the movements sync reports the failure the ERP gave, not an empty day', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    await page.goto(movementsCallbackUrl(false));

    const dialog = page.locator('[data-testid="aero-sync-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Falha ao Sincronizar');
    await expect(page.locator('[data-testid="aero-result-message"]')).toContainText(
      'Não foi possível ler os movimentos do ERP',
    );
    // The company's own words, kept as written.
    await expect(page.locator('[data-testid="aero-result-hint"]')).toContainText('Firebird indisponível');
  });

  test('the connection test opens its own verdict window', async ({ page }) => {
    const identities = await getE2EIdentities(page);

    // Only the integration owner sees the panel, so the run needs that account to exist.
    const login = await page.request.post(`${API}/auth/login`, { data: identities.admin });
    const token = (await login.json()).token as string;
    const created = await page.request.post(`${API}/admin/users`, {
      headers: { authorization: `Bearer ${token}` },
      data: { username: 'wilkin', password: OWNER_PASSWORD, display_name: 'Wilkin', role_id: 1 },
    });
    expect([201, 400]).toContain(created.status());

    await loginViaApi(page, { username: 'wilkin', password: OWNER_PASSWORD });
    await page.goto('/massas-launch');
    await expect(page.getByRole('heading', { name: 'Lançamento de Massas' })).toBeVisible();

    // An unconfigured panel starts open; its header toggles, so it is only clicked once the fields are known
    // to be missing. Checking too early toggles a panel that was already open and closes it.
    const urlField = page.locator('[data-testid="aero-ws-url"]');
    await urlField
      .waitFor({ state: 'visible', timeout: 3000 })
      .catch(() => page.getByRole('button', { name: /Configuração Integração Empresa/ }).click());
    await urlField.fill('http://127.0.0.1:9/usuarios/');
    await page.locator('[data-testid="aero-ws-user"]').fill('wilkin');
    await page.locator('[data-testid="aero-ws-pass"]').fill('segredo');

    await page.locator('[data-testid="aero-test"]').click();

    // Nothing is listening on that port: the test ends in failure, and the failure is a window.
    const dialog = page.locator('[data-testid="aero-test-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Falha no Teste');

    const close = page.locator('[data-testid="aero-result-close"]');
    expect((await close.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await close.click();
    await expect(dialog).toHaveCount(0);
  });

  test('the launch result points at the mirror once the company confirmed the entry', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const answer = encodeURIComponent(JSON.stringify({ success: true, message: 'Lançamento confirmado no ERP' }));
    await page.goto(`/massas-launch?aero_action=entrada&aero_data=${answer}`);

    const dialog = page.locator('[data-testid="massa-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(page.locator('[data-testid="massa-result-mirror-hint"]')).toContainText(
      'Atualize a aba «Espelho do ERP»',
    );
  });

  test('a launch the company refused does not promise a seat', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const answer = encodeURIComponent(JSON.stringify({ success: false, message: 'Firebird indisponível' }));
    await page.goto(`/massas-launch?aero_action=entrada&aero_data=${answer}`);

    await expect(page.locator('[data-testid="massa-result-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="massa-result-mirror-hint"]')).toHaveCount(0);
  });
});
