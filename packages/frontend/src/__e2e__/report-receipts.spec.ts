import { test, expect } from '@playwright/test';
import { getE2EIdentities, goToProtected, loginViaApi } from './auth-helpers';

const API = 'http://localhost:3099/api';

/**
 * Recebimento on a phone: the report no longer asks the operator to tick what arrived. It reads the day's
 * supplies from the ERP mirror, and the three states -- pending sync, nothing received, a list of lines --
 * each have their own sentence.
 */
test.use({ viewport: { width: 375, height: 667 } });

test.describe('Report receipts on a phone screen', () => {
  test('replaces the checklist with the mirrored supplies', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 15000 });

    // The old fifteen checkboxes are gone: today's report has nothing to tick for Recebimento.
    await expect(page.locator('[data-testid="check-task-43"]')).toHaveCount(0);

    const section = page.locator('[data-testid="receipts-section"]');
    await expect(section).toBeVisible();

    // Nothing swept sideways.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // The refresh control is thumb-sized. Whether the day was already synced is not asserted here: every
    // spec shares one database, and another one may have mirrored today before this runs.
    await expect(page.locator('[data-testid="receipts-refresh"]')).toBeVisible();
    expect((await page.locator('[data-testid="receipts-refresh"]').boundingBox())!.height).toBeGreaterThanOrEqual(44);

    // The phone brings the answer back; seeding it is the same call the shortcut makes. The day comes from
    // the server, so the test never disagrees with São Paulo's calendar around midnight.
    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;
    const day = await page.request.get(`${API}/reports/receipts`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const today = (await day.json()).date as string;
    const seeded = await page.request.post(`${API}/integration/aero/movements`, {
      headers: { authorization: `Bearer ${token}` },
      data: {
        date: today,
        movements: [
          {
            ent_lan: 35911,
            product_code: '001774',
            product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA',
            quantity: 3606,
            entry_date: today,
            operator_name: 'VIVIANE',
            movement_type: 'E',
          },
          {
            ent_lan: 35911,
            product_code: '000084',
            product_desc: 'MASSA PARA PASTEL DISCO 500G',
            quantity: 500,
            entry_date: today,
            operator_name: 'VIVIANE',
            movement_type: 'E',
          },
        ],
      },
    });
    expect(seeded.status()).toBe(201);

    await page.locator('[data-testid="receipts-refresh"]').click();

    const list = page.locator('[data-testid="receipts-list"]');
    await expect(list).toBeVisible();

    // The supply is listed and the massa the plant produces is not: it has its own screen.
    await expect(page.locator('[data-testid="receipt-line-35911-001774"]')).toContainText(
      'CAIXA DE PAPELAO PEQUENA MISTA',
    );
    await expect(page.locator('[data-testid="receipt-line-35911-001774"]')).toContainText('3606 un');
    // Only the operator: the seat number is the ERP's bookkeeping, not something the plant reads.
    await expect(page.locator('[data-testid="receipt-line-35911-001774"]')).toContainText('VIVIANE');
    await expect(page.locator('[data-testid="receipt-line-35911-001774"]')).not.toContainText('Lançamento #');
    await expect(page.locator('[data-testid="receipt-line-35911-000084"]')).toHaveCount(0);

    // A long product name wraps instead of pushing the card wider than the phone.
    const line = page.locator('[data-testid="receipt-line-35911-001774"]');
    const box = (await line.boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(375);
  });
});
