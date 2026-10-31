import { expect, test } from '@playwright/test';
import { IPHONE_UA, getE2EIdentities, loginViaApi } from './auth-helpers';

/**
 * The plant's day, as one table, on the phone the operator actually holds.
 *
 * Both screens write the same boxes, so the screen has to answer three questions without a second tab: what is
 * already paired, what Delphi still owes, and what the company tablet recorded that Trindade never launched.
 * The snapshot is seeded through the public endpoint because that is exactly what the phone does when the
 * shortcut comes back -- the Atajos round trip cannot run in a browser.
 */
const API = 'http://localhost:3099/api';

/**
 * The day the plant can still write: today, in São Paulo.
 *
 * The API freezes yesterday and refuses tomorrow, so this spec cannot take a day of its own. It isolates itself
 * with operators and `ENT_LAN`s instead, and reads the day before judging it, because every spec shares one
 * server and the day may already hold seats another one mirrored.
 */
function today(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date());
}

// The screen's owner is an iPhone: on a desktop browser the integration is shown read-only by design, and the
// import this spec walks through would be a disabled button.
test.use({ viewport: { width: 375, height: 667 }, userAgent: IPHONE_UA });

// This spec walks the whole day twice (seed, reload, import): the default 30s budget is spent on the walk, not
// on an assertion that would catch a regression.
test.describe.configure({ timeout: 60_000 });

test.describe('unified massas day on a phone screen', () => {
  test('shows one row per box in its real state and imports a company seat', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await loginViaApi(page, identities.worker);

    const login = await page.request.post(`${API}/auth/login`, { data: identities.worker });
    const token = (await login.json()).token as string;
    const mine = identities.worker.username.toUpperCase();

    await page.goto('/massas-launch');
    const date = today();
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    // One table, no tabs: the two lists are gone and the reason is on every row.
    await expect(page.locator('[data-testid="massas-unified"]')).toBeVisible();
    await expect(page.locator('[data-testid="massas-tab-erp"]')).toHaveCount(0);

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
    const localBody = await local.text();
    expect(local.status(), localBody).toBe(201);
    const ownLaunchId = (JSON.parse(localBody).created as Array<{ id: number }>)[0].id;

    await page.goto('/massas-launch');
    await page.locator('[data-testid="massas-launch-date"]').fill(date);
    await expect(page.locator('[data-testid="massas-launch-date"]')).toHaveValue(date);

    const unified = page.locator('[data-testid="massas-unified"]');
    const erpOnly = unified.locator('[data-state="erpOnly"]');

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
    // The worker's own launch is the pending row, whether or not the day already held other operators'
    // launches: the row is found by its id, not by being the only one on the table.
    const ownPending = page.locator(`[data-testid="massas-unified-row-launch-${ownLaunchId}"]`);
    await expect(ownPending).toHaveAttribute('data-state', 'pendingErp');
    await expect(ownPending).toContainText('MASSA ROLO 1KG');
    await expect(ownPending).toContainText('Pendente ERP');
    // The day is shared, so what belongs to this run is counted, not the table: two seats and the worker's
    // launch are here, whatever else the day already held.
    expect(await erpOnly.count()).toBeGreaterThanOrEqual(2);

    // A pending row can be acknowledged on this phone without pretending Delphi has it.
    const ack = ownPending.locator('[data-testid^="massas-ack-"]');
    expect((await ack.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await ack.click();
    await expect(ownPending).toContainText('já na tablet');
    await expect(ownPending).toHaveAttribute('data-state', 'pendingErp');

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
    const entLans = ((await stored.json()).movements as Array<{ ent_lan: number }>).map((row) => row.ent_lan);
    expect(entLans).toEqual(expect.arrayContaining([10542, 35914, 35911]));

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
    const date = today();

    // One launch of fifteen units that never reached Delphi, and one company seat of ninety.
    const local = await page.request.post(`${API}/massas-launch`, {
      headers: { authorization: `Bearer ${token}` },
      data: { date, items: [{ product_code: '5000', boxes: 1 }] },
    });
    const localBody = await local.text();
    expect(local.status(), localBody).toBe(201);
    const ownLaunchId = (JSON.parse(localBody).created as Array<{ id: number }>)[0].id;
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
    const launches = (await day.json()).launches as Array<{ units: number; ent_lan: number | null }>;
    // A launch imported from a seat is the same box the company already counts: it moves to the registered
    // side and only what Delphi still owes stays as pending work.
    const launchUnits = launches.reduce((total, launch) => total + launch.units, 0);
    const reconciledUnits = launches
      .filter((launch) => launch.ent_lan !== null)
      .reduce((total, launch) => total + launch.units, 0);
    const pendingUnits = launchUnits - reconciledUnits;
    // The seats the company has for the day, massas only: the box of packaging that arrives is a supply and
    // never counts as production.
    const report = await page.request.get(`${API}/massas-launch/report?date=${date}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    const seatedUnits = ((await report.json()).products as Array<{ totalUnits: number }>).reduce(
      (total, product) => total + product.totalUnits,
      0,
    );
    expect(launchUnits).toBeGreaterThan(0);
    expect(seatedUnits).toBeGreaterThanOrEqual(90);

    const balance = page.locator('[data-testid="massas-balance"]');
    await expect(balance).toBeVisible();
    // Every box somebody counted, whichever screen counted it, and each one once: the seat the launch came
    // from is the registration and the launch is the work.
    await expect(page.locator('[data-testid="massas-balance-produced"]')).toContainText(
      String(seatedUnits + pendingUnits),
    );
    await expect(page.locator('[data-testid="massas-balance-registered"]')).toContainText(String(seatedUnits));
    // Delphi still owes the local launch: the difference names it instead of hiding it.
    await expect(page.locator('[data-testid="massas-balance-difference"]')).toContainText(`-${pendingUnits}`);

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
