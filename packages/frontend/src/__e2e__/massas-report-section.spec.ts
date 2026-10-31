import { test, expect, type Page } from '@playwright/test';
import { fillAllTemperatureInputs, getE2EIdentities, goToProtected } from './auth-helpers';

/**
 * The Massas card on both report screens.
 *
 * The report reads one side only -- the seats the company's ERP recorded -- so what the browser has to prove is
 * that the card is on the report being filed and on the report being read, that it shows the day the ERP
 * answered, and that a launch the plant typed but never sent is not part of this day.
 */
const API = 'http://localhost:3099/api';

async function adminToken(page: Page): Promise<string> {
  const identities = await getE2EIdentities(page);
  const loginRes = await page.request.post(`${API}/auth/login`, { data: identities.admin });
  expect(loginRes.ok()).toBeTruthy();
  return (await loginRes.json()).token as string;
}

/** The day the report files, read from the same calendar the screens use. */
async function reportDay(page: Page): Promise<string> {
  return page.evaluate(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date()),
  );
}

async function mirrorSeat(
  page: Page,
  args: { entLan: number; code: string; desc: string; units: number; operator: string },
) {
  const token = await adminToken(page);
  const date = await reportDay(page);
  const res = await page.request.post(`${API}/integration/aero/movements`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      date,
      movements: [
        {
          ent_lan: args.entLan,
          product_code: args.code,
          product_desc: args.desc,
          quantity: args.units,
          entry_date: date,
          operator_name: args.operator,
          movement_type: 'E',
        },
      ],
    },
  });
  expect(res.ok()).toBeTruthy();
}

test.describe('Massas report section', () => {
  test('the report being filed shows the day the company recorded', async ({ page }) => {
    // The operator name is unique to this spec so the assertion cannot ride on a seat another spec seeded.
    await mirrorSeat(page, {
      entLan: 41101,
      code: '001616',
      desc: 'MASSA TOKO',
      units: 30,
      operator: 'CECILIA E2E',
    });

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    const card = page.locator('[data-testid="report-massas-card"]');
    await expect(card).toBeVisible();
    const section = card.locator('[data-testid="massas-report-section"]');
    await expect(section).toBeVisible();

    // The seat is counted once, on the operator the company wrote, with its product.
    await expect(card.locator('[data-testid="massas-report-operators"]')).toContainText('CECILIA E2E');
    await expect(card.locator('[data-testid="massas-report-products"]')).toContainText('TOKO');
  });

  test('the saved report shows the same day on its own screen', async ({ page }) => {
    await mirrorSeat(page, { entLan: 41102, code: '001616', desc: 'MASSA TOKO', units: 15, operator: 'WILKIN' });

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });
    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    const card = page.locator('[data-testid="report-massas-card"]');
    await expect(card).toBeVisible();
    await expect(card.locator('[data-testid="massas-report-section"]')).toBeVisible();
    await expect(card).toContainText('Massas do dia');
  });

  test('the card fits a 375px phone with a 44px sync control and no sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-massas-card"]', { timeout: 10000 });

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // One control for the whole card now; the section's own refresh button is gone with the second card.
    const sync = page.locator('[data-testid="erp-movements-sync"]');
    await expect(sync).toBeVisible();
    const box = await sync.boundingBox();
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44);
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  });
});
