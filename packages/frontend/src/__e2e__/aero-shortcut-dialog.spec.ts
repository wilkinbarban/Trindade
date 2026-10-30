import { expect, test } from '@playwright/test';
import { loginAsAeroOwner } from './aero-owner';

/**
 * The two ways the plant gets the shortcut, on the narrowest screen it will be read from.
 *
 * The certified iCloud link is the main path: one tap and a new phone is done. The manual recipe is the
 * backup, and it is documentation the operator follows with the phone in one hand, so the twenty actions
 * have to fit without spilling sideways and every copy button must be thumb-sized.
 */
const ICLOUD_URL = 'https://www.icloud.com/shortcuts/3216695a428f42539fec7abbd5419f34';

test.use({ viewport: { width: 375, height: 667 } });

async function openShortcutDialog(page: import('@playwright/test').Page) {
  await loginAsAeroOwner(page);
  // La receta del atajo vive ahora en el Panel Admin: producción quedó sin configuración técnica (S11).
  await page.goto('/admin');
  await page.locator('[data-testid="admin-tab-integration"]').click();
  await expect(page.locator('[data-testid="admin-integration-tab"]')).toBeVisible();

  const open = page.getByRole('button', { name: /Instalar Atalho no iPhone/ });
  await expect(open).toBeVisible({ timeout: 15000 });
  await open.click();

  const dialog = page.locator('[data-testid="aero-shortcut-dialog"]');
  await expect(dialog).toBeVisible();
  return dialog;
}

test.describe('the shortcut install screen on a phone', () => {
  test('installs the certified shortcut with one thumb tap, and opens it outside the app', async ({ page }) => {
    const dialog = await openShortcutDialog(page);

    const link = page.locator('[data-testid="aero-shortcut-icloud-link"]');
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute('href', ICLOUD_URL);
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    await expect(link).toContainText('Instalar Atalho Pronto (1 toque)');

    // Its own window on iOS, its own tab anywhere else: leaving this app is the point.
    await expect(link).toHaveAttribute('rel', /noopener/);

    const box = (await link.boundingBox())!;
    expect(box.height).toBeGreaterThanOrEqual(44);
    // The whole card is reachable without scrolling: the tap that matters is the first thing on screen.
    expect(box.y).toBeLessThan(667);

    // It is the first thing in the dialog, above the manual recipe.
    const card = page.locator('[data-testid="aero-shortcut-icloud"]');
    const unpack = dialog.getByText('A · Desempacotar o envelope', { exact: false });
    expect((await card.boundingBox())!.y).toBeLessThan((await unpack.boundingBox())!.y);
  });

  test('keeps the manual recipe in four blocks and twenty numbered actions', async ({ page }) => {
    const dialog = await openShortcutDialog(page);

    await expect(dialog).toContainText('Atalho único · 20 ações');
    for (const block of ['Desempacotar o envelope', 'Lote ·', 'Individual ·', 'Retorno ao Safari']) {
      await expect(dialog).toContainText(block);
    }

    // Twenty actions, numbered once each: a recipe that stops early leaves the `Si` unclosed.
    for (const n of [1, 6, 14, 15, 16, 19, 20]) {
      // Anclado al número: sin `^Ação 1 —`, «Ação 10» también entra.
      await expect(dialog.getByText(new RegExp(`^Ação ${n} —`)).first()).toBeAttached();
    }

    // The step the plant got wrong by hand names the renamed variable and what it must not be.
    const bodyStep = dialog.getByText(/^Ação 16 —/).first();
    await bodyStep.scrollIntoViewIfNeeded();
    await expect(bodyStep).toBeVisible();
    await expect(dialog).toContainText('body_item');
    await expect(dialog).toContainText('bodies');

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // The last action is reachable by scrolling inside the dialog, not by scrolling the page.
    await dialog.getByText(/^Ação 20 —/).scrollIntoViewIfNeeded();
    await expect(dialog.getByText(/^Ação 20 —/)).toBeVisible();

    const copy = page.locator('[data-testid^="aero-shortcut-copy-"]');
    expect(await copy.count()).toBeGreaterThanOrEqual(6);
    for (const handle of await copy.all()) {
      expect((await handle.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }
  });
});
