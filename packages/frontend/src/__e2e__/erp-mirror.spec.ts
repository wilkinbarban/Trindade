import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

/**
 * The ERP mirror, as the plant will actually read it: one iPhone-width screen, one tab, the day's seats
 * and the import that turns a company seat into a Trindade launch.
 *
 * The snapshot is seeded through the public endpoint, because that is exactly what the phone does when the
 * shortcut comes back -- the page's own round trip through the Atajos app cannot run in a browser.
 */
const API = 'http://localhost:3099/api';

test.use({ viewport: { width: 375, height: 667 } });

test.describe('ERP mirror on a phone screen', () => {
  test('shows the mirrored day, imports a seat, and reconciles it', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;

    await page.goto('/massas-launch');
    // A day of its own: every spec shares one database, so "today" is not guaranteed to be empty by the
    // time this runs. A quiet weekday carries no seats and no launches to reconcile against.
    const date = '2026-09-14';
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    // The two readings of the day, next to each other, both reachable with a thumb.
    const trindadeTab = page.locator('[data-testid="massas-tab-trindade"]');
    const erpTab = page.locator('[data-testid="massas-tab-erp"]');
    await expect(trindadeTab).toBeVisible();
    await expect(erpTab).toBeVisible();
    for (const tab of [trindadeTab, erpTab]) {
      expect((await tab.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    // An empty day says the ERP has nothing, instead of showing an empty list with no explanation.
    await erpTab.click();
    await expect(page.locator('[data-testid="erp-mirror-empty"]')).toContainText('Nenhum movimento no ERP');

    // Nothing spills sideways: this is the whole reason the view is mobile-first.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // The phone brings the answer back and the page stores it; seeding it is the same call.
    const seeded = await page.request.post(`${API}/integration/aero/movements`, {
      headers: { authorization: `Bearer ${token}` },
      data: {
        date,
        movements: [
          {
            ent_lan: 10542,
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

    // A reload loses the day the test chose, because the page opens on today; the phone's own return would
    // carry it, so the test sets it again before reading the mirror.
    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);
    await page.locator('[data-testid="massas-tab-erp"]').click();

    const seat = page.locator('[data-testid="erp-seat-10542"]');
    await expect(seat).toBeVisible();
    await expect(seat).toContainText('MASSA QUADRADA');
    await expect(seat).toContainText('WILKIN');
    await expect(seat).toContainText('90 un');
    await expect(seat).toContainText('#10542'.replace('#', ''));
    // Trindade has not recorded it yet, and the mirror says so instead of guessing.
    await expect(page.locator('[data-testid="erp-seat-pending-10542"]')).toBeVisible();

    // 90 units of a 15-unit box are 6 boxes: the import is offerable, and the control is thumb-sized.
    const importButton = page.locator('[data-testid="erp-seat-import-10542"]');
    await expect(importButton).toBeVisible();
    expect((await importButton.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    const overflowWithSeats = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflowWithSeats).toBeLessThanOrEqual(0);

    await importButton.click();
    // The same seat, now answered for on both sides.
    await expect(page.locator('[data-testid="erp-seat-reconciled-10542"]')).toBeVisible();
    await expect(page.locator('[data-testid="erp-seat-import-10542"]')).toHaveCount(0);

    // The company's record is untouched and the local day now carries the boxes.
    const stored = await page.request.get(`${API}/integration/aero/movements?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect((await stored.json()).movements).toHaveLength(1);

    const local = await page.request.get(`${API}/massas-launch?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const launches = (await local.json()).launches as Array<{ product_code: string; boxes: number }>;
    expect(launches.some((launch) => launch.product_code === '1192' && launch.boxes === 6)).toBe(true);

    // And the launch is visible where the plant records production.
    await page.locator('[data-testid="massas-tab-trindade"]').click();
    await expect(page.getByText('MASSA QUADRADA').first()).toBeVisible();
  });
});
