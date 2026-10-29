import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

/**
 * The ERP mirror, as the plant will actually read it: one iPhone-width screen, one tab, the day's massas
 * and the import that turns a company seat into a Trindade launch.
 *
 * The snapshot is seeded through the public endpoint, because that is exactly what the phone does when the
 * shortcut comes back -- the page's own round trip through the Atajos app cannot run in a browser.
 */
const API = 'http://localhost:3099/api';

test.use({ viewport: { width: 375, height: 667 } });

test.describe('ERP mirror on a phone screen', () => {
  test('shows and imports this module seats, and never borrows another operator reconciliation', async ({
    page,
  }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;
    const mine = identities.worker.username.toUpperCase();

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

    // An empty day says the ERP has no massas, instead of showing an empty list with no explanation.
    await erpTab.click();
    await expect(page.locator('[data-testid="erp-mirror-empty"]')).toContainText('Nenhuma massa no ERP');

    // Nothing spills sideways: this is the whole reason the view is mobile-first.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // The phone brings the answer back and the page stores it; seeding it is the same call. The day carries
    // what the plant actually sends through the one endpoint: two massas and one box of packaging.
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
            operator_name: mine,
            movement_type: 'E',
          },
          {
            ent_lan: 35914,
            product_code: '005000',
            product_desc: 'MASSA ROLO 1KG',
            quantity: 15,
            entry_date: date,
            operator_name: 'JEFFERSON',
            movement_type: 'E',
          },
          {
            ent_lan: 35911,
            product_code: '001774',
            product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA',
            quantity: 3606,
            entry_date: date,
            operator_name: 'VIVIANE',
            movement_type: 'E',
          },
        ],
      },
    });
    expect(seeded.status()).toBe(201);

    // The same fifteen units Jefferson recorded on the Delphi tablet, launched here by the worker. Matching
    // on date, code and quantity alone used to mark Jefferson as reconciled with this launch -- a
    // confirmation of something he never did.
    const local = await page.request.post(`${API}/massas-launch`, {
      headers: { authorization: `Bearer ${token}` },
      data: { date, items: [{ product_code: '5000', boxes: 1 }] },
    });
    expect(local.status()).toBe(201);

    // A reload loses the day the test chose, because the page opens on today; the phone's own return would
    // carry it, so the test sets it again before reading the mirror.
    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);
    await page.locator('[data-testid="massas-tab-erp"]').click();

    const seat = page.locator('[data-testid="erp-seat-10542"]');
    await expect(seat).toBeVisible();
    await expect(seat).toContainText('MASSA QUADRADA');
    await expect(seat).toContainText(mine);
    await expect(seat).toContainText('90 un');
    await expect(seat).toContainText('#10542'.replace('#', ''));
    // Trindade has not recorded it yet, and the mirror says so instead of guessing.
    await expect(page.locator('[data-testid="erp-seat-pending-10542"]')).toBeVisible();

    // The box of packaging arrived on the same day and is not this tab's business: it belongs to Recebimento.
    await expect(page.locator('[data-testid="erp-seat-35911"]')).toHaveCount(0);
    await expect(page.getByText('CAIXA DE PAPELAO PEQUENA MISTA')).toHaveCount(0);

    // Jefferson's seat is the same rolo, the same fifteen units, on the same day -- and it is still only in
    // the ERP, because that launch is not the worker's.
    await expect(page.locator('[data-testid="erp-seat-35914"]')).toBeVisible();
    await expect(page.locator('[data-testid="erp-seat-pending-35914"]')).toBeVisible();
    await expect(page.locator('[data-testid="erp-seat-reconciled-35914"]')).toHaveCount(0);

    // 90 units of a 15-unit box are 6 boxes: the import is offerable, and the control is thumb-sized.
    const importButton = page.locator('[data-testid="erp-seat-import-10542"]');
    await expect(importButton).toBeVisible();
    expect((await importButton.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    const overflowWithSeats = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflowWithSeats).toBeLessThanOrEqual(0);

    await importButton.click();
    // The same seat, now answered for on both sides, by the operator who launched it.
    await expect(page.locator('[data-testid="erp-seat-reconciled-10542"]')).toBeVisible();
    await expect(page.locator('[data-testid="erp-seat-import-10542"]')).toHaveCount(0);

    // The company's record is untouched and the local day now carries the boxes.
    const stored = await page.request.get(`${API}/integration/aero/movements?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    expect((await stored.json()).movements).toHaveLength(3);

    const day = await page.request.get(`${API}/massas-launch?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const launches = (await day.json()).launches as Array<{ product_code: string; boxes: number }>;
    expect(launches.some((launch) => launch.product_code === '1192' && launch.boxes === 6)).toBe(true);
    expect(launches.some((launch) => launch.product_code === '5000' && launch.boxes === 1)).toBe(true);

    // And the launch is visible where the plant records production.
    await page.locator('[data-testid="massas-tab-trindade"]').click();
    await expect(page.getByText('MASSA QUADRADA').first()).toBeVisible();
  });
});
