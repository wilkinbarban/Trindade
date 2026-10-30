import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';
import { AERO_OWNER, loginAsAeroOwner } from './aero-owner';

/**
 * The company integration, where the plant keeps it now.
 *
 * The address is the plant's, so it ships written and only the account that owns the integration may move
 * it; the credentials belong to whoever holds the phone and stay editable for every worker. This spec reads
 * both halves from the screen, because that is where an operator will look for them.
 */
test.use({ viewport: { width: 375, height: 667 } });

test.describe('integration tab in the admin panel', () => {
  test('the owner finds the plant address already written and may change it', async ({ page }) => {
    await loginAsAeroOwner(page);
    await page.goto('/admin');
    await page.locator('[data-testid="admin-tab-integration"]').click();

    await expect(page.locator('[data-testid="admin-integration-tab"]')).toBeVisible();
    const url = page.locator('[data-testid="aero-ws-url"]');
    await expect(url).toBeVisible();
    await expect(url).toHaveValue('http://192.168.25.250:1453/api');
    await expect(url).toBeEditable();

    // The shortcut recipe and the connection test live here too, at thumb size.
    await expect(page.getByRole('button', { name: /Instalar Atalho no iPhone/ })).toBeVisible();

    // Nothing to create on this tab, and nothing to unfold: the panel is the page.
    await expect(page.getByRole('button', { name: '+ Novo' })).toHaveCount(0);
    await expect(page.locator('[data-testid="admin-integration-tab"] [aria-expanded]')).toHaveCount(0);
    await expect(page.locator('[data-testid="admin-integration-tab"] svg.lucide-chevron-down')).toHaveCount(0);
    // The body is there on arrival, without a tap: the address is already on screen.
    await expect(page.locator('[data-testid="aero-ws-pass"]')).toBeVisible();

    // The button is absent here, not from the panel: the tabs that do create still offer it.
    await page.locator('[data-testid="admin-tab-categories"]').click();
    await expect(page.getByRole('button', { name: '+ Novo' })).toBeVisible();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('a worker keeps his own credentials and never the plant address', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);
    await page.goto('/admin');

    // Every role sees the tab (L2), and the address is readable but locked for all of them but the owner.
    await page.locator('[data-testid="admin-tab-integration"]').click();
    const url = page.locator('[data-testid="aero-ws-url"]');
    await expect(url).toBeVisible();
    await expect(url).toBeDisabled();
    await expect(url).toHaveValue('http://192.168.25.250:1453/api');

    await expect(page.locator('[data-testid="aero-ws-user"]')).toBeEditable();
    await expect(page.locator('[data-testid="aero-ws-pass"]')).toBeEditable();

    // Production no longer carries the technical settings.
    await page.goto('/massas-launch');
    await expect(page.locator('[data-testid="aero-ws-url"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="massas-sync-erp"]')).toHaveCount(0);
  });

  test('the owner account is the only one that can move the address', async ({ page }) => {
    // The rule is the username, not the role: an administrator who is not the owner keeps it locked.
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.admin);
    await page.goto('/admin');
    await page.locator('[data-testid="admin-tab-integration"]').click();
    await expect(page.locator('[data-testid="aero-ws-url"]')).toBeDisabled();

    await loginViaApi(page, AERO_OWNER);
    await page.goto('/admin');
    await page.locator('[data-testid="admin-tab-integration"]').click();
    await expect(page.locator('[data-testid="aero-ws-url"]')).toBeEditable();
  });
});
