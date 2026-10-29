import { expect, test, type Page } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

const API = 'http://localhost:3099/api';

/**
 * Three products, one trip out of the page.
 *
 * The shortcut cannot run inside a browser, so the spec seeds exactly what the page writes before leaving
 * and feeds back exactly what the phone returns. What it can prove, and what the operator actually felt
 * broken, is the count: three products must leave as one envelope and come back as one dialog, instead of
 * three trips through Safari and Atajos that the plant had no way to follow.
 */
const AERO_SETTINGS_KEY = 'trindade.aero.integration.v1';
const AERO_QUEUE_KEY = 'trindade.aero.queue.v1';

/** A quiet weekday: every spec shares one database, so today is not guaranteed to be empty. */
const DAY = '2026-09-16';

/**
 * The integration panel belongs to one named owner: `isAeroAllowed` asks for `wilkin`, role Administrador.
 *
 * The fixture admin is a random name, so the spec creates the owner it needs. A 409 means a previous spec
 * already did, and the fixed password is the same one, so logging in works either way.
 */
const OWNER = { username: 'wilkin', password: 'e2e-owner-password' };

async function loginAsIntegrationOwner(page: Page) {
  const identities = await getE2EIdentities(page);
  const adminLogin = await page.request.post(`${API}/auth/login`, { data: identities.admin });
  const token = (await adminLogin.json()).token as string;
  await page.request.post(`${API}/admin/users`, {
    headers: { authorization: `Bearer ${token}` },
    data: { ...OWNER, display_name: 'Wilkin', role_id: 1 },
  });
  await loginViaApi(page, OWNER);
}

const SETTINGS = JSON.stringify({
  wsUrl: 'http://192.168.25.250:1453/api',
  username: 'wilkin',
  password: 'senha',
  shortcutName: 'Aero32 Bridge',
  syncEnabled: true,
});

/**
 * The answer the shortcut brings back for a batch: one object per product, the way the ERP replies.
 *
 * `failAtIndex` reproduces the partial batch the plant will eventually hit -- one product refused by the
 * company while the others were recorded.
 */
function batchAnswer(total: number, failAtIndex = -1): unknown[] {
  return Array.from({ length: total }, (_, index) =>
    index === failAtIndex
      ? { success: false, message: 'PRODUTO SEM SALDO' }
      : { success: true, data: { ENT_LAN: 41000 + index, PRO_COD: `c${index}`, PRO_QTY: 20 } },
  );
}

async function openLaunchPage(page: Page) {
  // The configuration is what turns the switch on: seeded before the app reads it.
  await page.addInitScript(
    ({ key, value }) => window.localStorage.setItem(key, value),
    { key: AERO_SETTINGS_KEY, value: SETTINGS },
  );
  await page.goto('/massas-launch');
  await page.locator('[data-testid="massas-launch-date"]').fill(DAY);
  await expect(page.locator('input[type="number"]').first()).toBeVisible({ timeout: 15000 });
}

async function launchProducts(page: Page, count: number, boxes = '2') {
  const quantities = page.locator('input[type="number"]');
  for (let index = 0; index < count; index += 1) {
    await quantities.nth(index).fill(boxes);
    await quantities.nth(index).blur();
  }

  await page.getByRole('button', { name: /Lançar/ }).click();
  const confirm = page.locator('[data-testid="massa-confirm-modal"]');
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: /Confirmar|Lançar/ }).last().click();
  await expect.poll(() => readQueue(page), { timeout: 10000 }).not.toBeNull();
}

async function readQueue(page: Page): Promise<{ items: unknown[]; inFlight: unknown[] } | null> {
  return page.evaluate((key) => {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as { items: unknown[]; inFlight: unknown[] }) : null;
  }, AERO_QUEUE_KEY);
}

async function answer(page: Page, action: string, payload: unknown) {
  await page.goto(`/massas-launch?aero_action=${action}&aero_data=${encodeURIComponent(JSON.stringify(payload))}`);
}

test.use({ viewport: { width: 375, height: 667 } });

test.describe('a launch of many products leaves once', () => {
  test('three products travel in one envelope and come back in one dialog', async ({ page }) => {
    await loginAsIntegrationOwner(page);
    await openLaunchPage(page);
    await launchProducts(page, 3);

    // The three products left together: one trip with three products in flight, nothing left behind for a
    // second trip the operator never asked for.
    const queue = (await readQueue(page))!;
    expect(queue.inFlight).toHaveLength(3);
    expect(queue.items).toHaveLength(0);

    // The shortcut returns once, with the three answers the company gave.
    await answer(page, 'entrada_lote', batchAnswer(3));

    const result = page.locator('[data-testid="massa-result-modal"]');
    await expect(result).toBeVisible({ timeout: 15000 });
    await expect(result).toContainText('3 lançamentos sincronizados');
    await expect(result.locator('[data-testid="massa-result-mirror-hint"]')).toBeVisible();

    // Nothing spills sideways, and the dialog is thumb-sized.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
    const close = result.locator('[data-testid="massa-result-close"]');
    expect((await close.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });

  test('one refused product comes back as a warning with the retry in the operator hand', async ({ page }) => {
    await loginAsIntegrationOwner(page);
    await openLaunchPage(page);
    await launchProducts(page, 3, '1');

    // The ERP records two and refuses the third.
    await answer(page, 'entrada_lote', batchAnswer(3, 2));

    const result = page.locator('[data-testid="massa-result-modal"]');
    await expect(result).toBeVisible({ timeout: 15000 });
    await expect(result).toContainText('Sincronização Parcial');
    await expect(result).toContainText('2 de 3 sincronizados');

    // Exactly the refused product waits for the operator, at the front of the queue, and no second trip was
    // taken on its own.
    const queue = (await readQueue(page))!;
    expect(queue.items).toHaveLength(1);
    expect(queue.inFlight).toHaveLength(0);

    const retry = result.locator('[data-testid="massa-result-retry"]');
    await expect(retry).toContainText('Reenviar falhas (1)');
    expect((await retry.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });

  test('a phone still on the old eight actions does not open a batch dialog for one product', async ({ page }) => {
    await loginAsIntegrationOwner(page);
    await openLaunchPage(page);
    await launchProducts(page, 1);

    // Un producto solo viaja por `entrada`, sin `bodies`, igual que antes del lote.
    const queue = (await readQueue(page))!;
    expect(queue.inFlight).toHaveLength(1);

    // The old shortcut answers a bare object, not an array.
    await answer(page, 'entrada', { success: true, data: {} });

    // Un producto suelto conserva el mensaje de siempre: no hay lote que anunciar.
    const result = page.locator('[data-testid="massa-result-modal"]');
    await expect(result).toBeVisible({ timeout: 15000 });
    await expect(result).toContainText('Sincronizado com o ERP');
  });
});
