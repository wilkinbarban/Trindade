import { test, expect, type Page } from '@playwright/test';
import { fillAllTemperatureInputs, getE2EIdentities, goToProtected } from './auth-helpers';

/**
 * The Massas card on both report screens.
 *
 * The unit tests already know how the day is counted; what the browser has to prove is that the card is on
 * the report being filed and on the report being read, and that it reads the day the report describes.
 */

async function launchBoxesToday(page: Page, boxes: number): Promise<void> {
  const identities = await getE2EIdentities(page);
  const loginRes = await page.request.post('http://localhost:3099/api/auth/login', {
    data: identities.admin,
  });
  expect(loginRes.ok()).toBeTruthy();
  const token = (await loginRes.json()).token;
  const res = await page.request.post('http://localhost:3099/api/massas-launch', {
    headers: { Authorization: `Bearer ${token}` },
    data: { product_code: '1045', boxes },
  });
  expect(res.ok()).toBeTruthy();
}

test.describe('Massas report section', () => {
  test('the report being filed already shows the day it will report', async ({ page }) => {
    await launchBoxesToday(page, 3);

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    const card = page.locator('[data-testid="report-massas-card"]');
    await expect(card).toBeVisible();
    const section = card.locator('[data-testid="massas-report-section"]');
    await expect(section).toBeVisible();

    // The launch is counted once, on its operator, with the product's units.
    await expect(card.locator('[data-testid="massas-report-operators"]')).toBeVisible();
    await expect(card.locator('[data-testid="massas-report-products"]')).toContainText('CASEIRA');
    await expect(card.locator('[data-testid="massas-report-products"]')).toContainText('30 un');
  });

  test('the saved report shows the same day on its own screen', async ({ page }) => {
    await launchBoxesToday(page, 1);

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

  test('the card fits a 375px phone with 44px controls and no sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await launchBoxesToday(page, 1);

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-massas-card"]', { timeout: 10000 });

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    const refresh = page.locator('[data-testid="massas-report-refresh"]');
    const box = await refresh.boundingBox();
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44);
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  });
});
