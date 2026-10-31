import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

const API = 'http://localhost:3099/api';

/**
 * One snapshot, two lenses.
 *
 * The plant's day carries both what it produced and what came in from outside, and both screens read the
 * same stored snapshot. What must never travel between them is the count: the masses screen that says
 * "eleven" would tell the operator eleven boxes of dough arrived, and the report that says the same would
 * celebrate a delivery of dough it produced itself.
 *
 * The payload below is a real day: ten massas of the catalogue plus one box of packaging.
 */
const MASSAS = ['001045', '000039', '000060', '000084', '000091', '001192', '005000', '001047', '000022', '001616'];

function daySeats(today: string) {
  return [
    ...MASSAS.map((code, index) => ({
      ENT_LAN: 36100 + index,
      PRO_COD: code,
      PRO_DES: `MASSA ${index}`,
      PRO_QTD: 20,
      ENT_DTA: today,
      ENT_USU: 'VINICIUS R',
      ENTSAI: 'E',
    })),
    {
      ENT_LAN: 36111,
      PRO_COD: '001774',
      PRO_DES: 'CAIXA DE PAPELAO PEQUENA MISTA',
      PRO_QTD: 3606,
      ENT_DTA: today,
      ENT_USU: 'VIVIANE',
      ENTSAI: 'E',
    },
  ];
}

function callbackUrl(path: string, seats: unknown[]) {
  const answer = JSON.stringify({ success: true, data: seats });
  return `${path}?aero_action=movimentos&aero_data=${encodeURIComponent(answer)}`;
}

/** The day the server answers for, so the test never disagrees with São Paulo's calendar at midnight. */
async function serverToday(page: import('@playwright/test').Page): Promise<string> {
  const identities = await getE2EIdentities(page);
  const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
  const token = (await login.json()).token as string;
  const day = await page.request.get(`${API}/reports/receipts`, {
    headers: { authorization: `Bearer ${token}` },
  });
  return (await day.json()).date as string;
}

test.use({ viewport: { width: 375, height: 667 } });

test.describe('the ERP sync, read through each module lens', () => {
  test('the masses window counts ten massas and never mentions the box', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);
    const today = await serverToday(page);

    await page.goto(callbackUrl('/massas-launch', daySeats(today)));

    const dialog = page.locator('[data-testid="aero-sync-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Massas Sincronizadas');

    const message = page.locator('[data-testid="aero-result-message"]');
    await expect(message).toHaveText(/10 massas sincronizadas do ERP para \d{2}\/\d{2}\/\d{4}\./);
    // The box arrived on the same day and is none of this screen's business.
    await expect(message).not.toContainText('insumo');
    await expect(message).not.toContainText('11');

    const close = page.locator('[data-testid="aero-result-close"]');
    expect((await close.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });

  test('the report window counts the one supply and lists it', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);
    const today = await serverToday(page);

    // The shortcut came back to the report page it left from, which is what receives the answer.
    await page.goto(callbackUrl('/reports', daySeats(today)));

    const dialog = page.locator('[data-testid="receipts-sync-result-modal"]');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Insumos Sincronizados');
    await expect(page.locator('[data-testid="aero-result-message"]')).toHaveText(
      '1 insumo recebido sincronizado do ERP.',
    );

    await page.locator('[data-testid="aero-result-close"]').click();
    await expect(dialog).toBeHidden();

    // The section behind the window now reads the same day from the mirror.
    await expect(page.locator('[data-testid="receipts-list"]')).toBeVisible();
    await expect(page.getByText('CAIXA DE PAPELAO PEQUENA MISTA', { exact: true })).toBeVisible();

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('the single sync button on the report is thumb-sized and honest without company access', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    await page.goto('/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 15000 });

    // One control for the whole day, on the one card: the per-block buttons are gone.
    await expect(page.locator('[data-testid="receipts-sync"]')).toHaveCount(0);
    const sync = page.locator('[data-testid="erp-movements-sync"]');
    await expect(sync).toBeVisible();
    expect((await sync.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    // This browser holds no company access, so the button says what it will really do: read the day again.
    await expect(sync).toHaveText(/Atualizar/);
  });
});
