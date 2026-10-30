import { expect, test } from '@playwright/test';
import { loginAsAeroOwner } from './aero-owner';

/**
 * The header's sync action, at the width the plant holds it.
 *
 * The shortcut itself cannot run in a browser, so what is testable here is the part the operator relies on
 * before pressing anything: the button exists where the day is chosen, it is thumb-sized, and the hour under
 * it says when this day last came back from Delphi instead of looking like a fresh read forever.
 */
const API = 'http://localhost:3099/api';

test.use({ viewport: { width: 375, height: 667 } });

test.describe('sync in the header', () => {
  test('offers the sync next to the day and shows the hour the ERP last answered', async ({ page }) => {
    // The integration belongs to the owner admin, so this is the identity that has the button at all.
    await loginAsAeroOwner(page);
    const login = await page.request.post(`${API}/auth/login`, {
      data: { username: 'wilkin', password: 'wilkin-result-dialogs-2026' },
    });
    const token = (await login.json()).token as string;

    await page.goto('/massas-launch');
    const date = '2026-09-16';
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    const sync = page.locator('[data-testid="massas-sync-erp"]');
    await expect(sync).toBeVisible();
    await expect(sync).toContainText('Sincronizar');
    expect((await sync.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    const lastSync = page.locator('[data-testid="massas-last-sync"]');
    await expect(lastSync).toBeVisible();
    // Nothing came back yet for this day: the header says so instead of inventing a time.
    await expect(lastSync).toContainText('—');

    // The phone brings the answer back and the page stores it; seeding it is the same call.
    const seeded = await page.request.post(`${API}/integration/aero/movements`, {
      headers: { authorization: `Bearer ${token}` },
      data: {
        date,
        movements: [
          {
            ent_lan: 10777,
            product_code: '001192',
            product_desc: 'MASSA QUADRADA',
            quantity: 90,
            entry_date: date,
            operator_name: 'WILKIN',
            movement_type: 'E',
          },
        ],
      },
    });
    expect(seeded.status()).toBe(201);

    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-last-sync"]')).toContainText(/\d{2}:\d{2}/);

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
