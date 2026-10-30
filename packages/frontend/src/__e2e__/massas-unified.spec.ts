import { expect, test } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

/**
 * The plant's day, as one table, on the phone the operator actually holds.
 *
 * Both screens write the same boxes, so the screen has to answer three questions without a second tab: what is
 * already paired, what Delphi still owes, and what the company tablet recorded that Trindade never launched.
 * The snapshot is seeded through the public endpoint because that is exactly what the phone does when the
 * shortcut comes back -- the Atajos round trip cannot run in a browser.
 */
const API = 'http://localhost:3099/api';

/** A date no other spec and no previous run of this one uses. */
function balanceDay(): string {
  const start = Date.UTC(2027, 0, 1);
  const days = Math.floor((Date.now() - start) / 86_400_000) % 3000;
  const day = new Date(start + days * 86_400_000);
  return day.toISOString().slice(0, 10);
}

test.use({ viewport: { width: 375, height: 667 } });

test.describe('unified massas day on a phone screen', () => {
  test('shows one row per box in its real state and imports a company seat', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;
    const mine = identities.worker.username.toUpperCase();

    await page.goto('/massas-launch');
    // A day of its own: every spec shares one database, so "today" is not guaranteed to be empty.
    const date = '2026-09-15';
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    // One table, no tabs: the two lists are gone and the reason is on every row.
    await expect(page.locator('[data-testid="massas-unified"]')).toBeVisible();
    await expect(page.locator('[data-testid="massas-tab-erp"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="massas-unified-empty"]')).toContainText('Nenhuma massa lançada');

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    // What the plant actually sends through the one endpoint: two massas and one box of packaging.
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

    // The same fifteen units Jefferson recorded on the tablet, launched here by the worker: same product,
    // same quantity, same day and still two different rows, because the operator is not the same.
    const local = await page.request.post(`${API}/massas-launch`, {
      headers: { authorization: `Bearer ${token}` },
      data: { date, items: [{ product_code: '5000', boxes: 1 }] },
    });
    expect(local.status()).toBe(201);

    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    const unified = page.locator('[data-testid="massas-unified"]');
    const erpOnly = unified.locator('[data-state="erpOnly"]');
    const pending = unified.locator('[data-state="pendingErp"]');

    // The company seat nobody launched here is importable in whole boxes: 90 units of a 15-unit box.
    const seat = page.locator('[data-testid="massas-unified-row-seat-10542"]');
    await expect(seat).toBeVisible();
    await expect(seat).toContainText('MASSA QUADRADA');
    await expect(seat).toContainText(mine);
    await expect(seat).toContainText('90 un');
    await expect(seat).toContainText('Só ERP');

    // The box of packaging arrived on the same day and belongs to Recebimento, not to production.
    await expect(page.locator('[data-testid="massas-unified-row-seat-35911"]')).toHaveCount(0);
    await expect(page.getByText('CAIXA DE PAPELAO PEQUENA MISTA')).toHaveCount(0);

    // Jefferson's seat is not the worker's launch: both keep their own state.
    await expect(page.locator('[data-testid="massas-unified-row-seat-35914"]')).toHaveAttribute(
      'data-state',
      'erpOnly',
    );
    await expect(pending).toHaveCount(1);
    await expect(pending).toContainText('MASSA ROLO 1KG');
    await expect(pending).toContainText('Pendente ERP');
    expect(await erpOnly.count()).toBe(2);

    // A pending row can be acknowledged on this phone without pretending Delphi has it.
    const ack = pending.locator('[data-testid^="massas-ack-"]');
    expect((await ack.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await ack.click();
    await expect(pending).toContainText('já na tablet');
    await expect(pending).toHaveAttribute('data-state', 'pendingErp');

    // 90 units of a 15-unit box are 6 boxes: the import is offerable, and the control is thumb-sized.
    const importButton = page.locator('[data-testid="massas-import-10542"]');
    await expect(importButton).toBeVisible();
    expect((await importButton.boundingBox())!.height).toBeGreaterThanOrEqual(44);

    const overflowWithSeats = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflowWithSeats).toBeLessThanOrEqual(0);

    await importButton.click();

    // The same boxes, now one reconciled row on both sides, with no duplicate count.
    await expect(page.locator('[data-testid="massas-unified-row-seat-10542"]')).toHaveCount(0);
    const reconciled = unified.locator('[data-state="reconciled"]');
    await expect(reconciled.first()).toBeVisible();
    await expect(reconciled.first()).toContainText('MASSA QUADRADA');
    await expect(reconciled.first()).toContainText('Conciliado');

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
  });
});

/**
 * The bar that answers, at a glance, whether the company knows what the plant made.
 *
 * It sits above the rows because it is the summary of them: a reconciled box counts on both sides, and only the
 * boxes Delphi has not received make the difference negative.
 */
test.describe('the day balance on a phone screen', () => {
  test('says what was produced, what the company has, and what is still missing', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);
    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;
    // A day of its own: every run of this spec adds boxes to the day it names, and a shared date would make the
    // bar sum the seeds of every previous run.
    const date = balanceDay();

    // One launch of fifteen units that never reached Delphi, and one company seat of ninety.
    const local = await page.request.post(`${API}/massas-launch`, {
      headers: { authorization: `Bearer ${token}` },
      data: { date, items: [{ product_code: '5000', boxes: 1 }] },
    });
    expect(local.status()).toBe(201);
    const seeded = await page.request.post(`${API}/integration/aero/movements`, {
      headers: { authorization: `Bearer ${token}` },
      data: {
        date,
        movements: [
          {
            ent_lan: 20116,
            product_code: '001192',
            product_desc: 'MASSA QUADRADA',
            quantity: 90,
            entry_date: date,
            operator_name: 'JEFFERSON',
            movement_type: 'E',
          },
        ],
      },
    });
    expect(seeded.status()).toBe(201);

    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    // The box size belongs to the plant's catalogue, so the spec reads what the launch really weighed
    // instead of hard-coding today's packaging.
    const day = await page.request.get(`${API}/massas-launch?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const launchUnits = ((await day.json()).launches as Array<{ units: number }>).reduce(
      (total, launch) => total + launch.units,
      0,
    );
    const erpDay = await page.request.get(`${API}/integration/aero/movements?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const seatedUnits = ((await erpDay.json()).movements as Array<{ quantity: number; movement_type: string }>)
      .filter((movement) => movement.movement_type === 'E')
      .reduce((total, movement) => total + movement.quantity, 0);
    expect(launchUnits).toBeGreaterThan(0);
    expect(seatedUnits).toBe(90);

    const balance = page.locator('[data-testid="massas-balance"]');
    await expect(balance).toBeVisible();
    await expect(page.locator('[data-testid="massas-balance-produced"]')).toContainText(
      String(launchUnits + seatedUnits),
    );
    await expect(page.locator('[data-testid="massas-balance-registered"]')).toContainText(String(seatedUnits));
    // Delphi still owes the local launch: the difference names it instead of hiding it.
    await expect(page.locator('[data-testid="massas-balance-difference"]')).toContainText(`-${launchUnits}`);

    // Above the rows: the summary is read before the detail, and both fit the phone.
    const bar = (await balance.boundingBox())!;
    const rows = (await page.locator('[data-testid="massas-unified-rows"]').boundingBox())!;
    expect(bar.y).toBeLessThan(rows.y);
    expect(bar.width).toBeLessThanOrEqual(375);

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
