import { expect, test } from '@playwright/test';
import { loginAsAeroOwner } from './aero-owner';

/**
 * The recipe the plant builds on an iPhone, on the narrowest screen it will be read from.
 *
 * The dialog is documentation the operator follows step by step while holding the phone, so what matters is
 * that the seventeen steps fit without spilling sideways and that every copy button is thumb-sized. The
 * Authorization step reads it from `headers`, which is the only place the envelope carries it.
 */
test.use({ viewport: { width: 375, height: 667 } });

test.describe('the shortcut recipe on a phone screen', () => {
  test('shows the seventeen steps without spilling, with a thumb-sized copy on every key', async ({ page }) => {
    await loginAsAeroOwner(page);
    await page.goto('/massas-launch');
    await expect(page.getByRole('heading', { name: 'Lançamento de Massas' })).toBeVisible();

    const open = page.getByRole('button', { name: /Instalar Atalho no iPhone/ });
    await expect(open).toBeVisible({ timeout: 15000 });
    await open.click();

    const dialog = page.locator('[data-testid="aero-shortcut-dialog"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Atalho único · 17 ações');

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // The last action is reachable by scrolling inside the dialog, not by scrolling the page.
    await dialog.getByText('Ação 17').scrollIntoViewIfNeeded();
    await expect(dialog.getByText('Ação 17')).toBeVisible();

    const copy = page.locator('[data-testid^="aero-shortcut-copy-"]');
    expect(await copy.count()).toBeGreaterThanOrEqual(6);
    for (const handle of await copy.all()) {
      expect((await handle.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }
  });
});
