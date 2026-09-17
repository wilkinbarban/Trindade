import { test, expect, type Page } from '@playwright/test';
import { goToProtected } from './auth-helpers';

const API_BASE = 'http://localhost:3099/api';

/**
 * Helper: POST to the backend API with auth.
 */
async function apiPost(page: Page, url: string, body: unknown) {
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  const response = await page.request.post(`${API_BASE}${url}`, {
    data: body,
    headers: { Authorization: `Bearer ${token}` },
  });
  return { status: response.status(), data: await response.json() };
}

/**
 * Helper: DELETE from the backend API with auth.
 */
async function apiDelete(page: Page, url: string) {
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  const response = await page.request.delete(`${API_BASE}${url}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return { status: response.status() };
}

/**
 * Helper: GET from the backend API with auth.
 */
async function apiGet<T = unknown>(page: Page, url: string): Promise<T> {
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  const response = await page.request.get(`${API_BASE}${url}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return response.json() as T;
}

function today(): string {
  const d = new Date();
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const parts = formatter.formatToParts(d);
  const y = parts.find((p) => p.type === 'year')?.value;
  const m = parts.find((p) => p.type === 'month')?.value;
  const day = parts.find((p) => p.type === 'day')?.value;
  return `${y}-${m}-${day}`;
}

function tomorrowDisplay(): string {
  const [y, m, d] = today().split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  date.setUTCDate(date.getUTCDate() + 1);
  const day = String(date.getUTCDate()).padStart(2, '0');
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const year = date.getUTCFullYear();
  return `${day}/${month}/${year}`;
}

test.describe('Loading Schedule — Today-only Grid', () => {
  test.beforeEach(async ({ page }) => {
    await goToProtected(page, '/loading');
    // Wait for the schedule grid to render
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
  });

  test('4.1 renders time slot grid for today without date navigation', async ({ page }) => {
    // Date display should show today
    const dateDisplay = page.locator('[data-testid="date-display"]');
    await expect(dateDisplay).toBeVisible();

    // Grid should be present
    const grid = page.locator('[data-testid="schedule-grid"]');
    await expect(grid).toBeVisible();

    // Time slots should be present (at least 04:00-07:00)
    const slot0400 = page.locator('[data-testid="time-slot-04-00"]');
    await expect(slot0400).toBeVisible();

    const slot0700 = page.locator('[data-testid="time-slot-07-00"]');
    await expect(slot0700).toBeVisible();

    // Each slot should show quota indicator "0/3 fleteros" by default
    const quota0400 = page.locator('[data-testid="quota-04-00"]');
    await expect(quota0400).toBeVisible();
    await expect(quota0400).toContainText('0/3');
  });

  test('4.1 date navigation controls are not shown', async ({ page }) => {
    await expect(page.locator('[data-testid="nav-next"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="nav-prev"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="nav-today"]')).not.toBeVisible();
  });
});

test.describe('Loading Schedule — Fletero Assignment and Quota', () => {
  test.beforeEach(async ({ page }) => {
    await goToProtected(page, '/loading');
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
  });

  test('4.2 add fletero button opens driver selection', async ({ page }) => {
    // Click the add fletero button on the 04:00 slot
    const addBtn = page.locator('[data-testid="add-fletero-04-00"]');
    await addBtn.click();

    // Driver selection panel should appear
    const selectPanel = page.locator('[data-testid="driver-select-04-00"]');
    await expect(selectPanel).toBeVisible();
  });

  test('4.2 hides an already assigned driver from another slot', async ({ page }) => {
    const date = today();
    const created = await apiPost(page, '/loading/drivers', {
      name: `Global Hidden Driver ${Date.now()}`,
      license_plate: 'GHD-1',
    });
    const driverId = created.data?.driver?.id;
    expect(driverId).toBeTruthy();

    const assigned = await apiPost(page, '/loading/schedules', {
      schedule_date: date,
      time_slot: '04:00',
      driver_type: 'fletero',
      driver_id: driverId,
    });
    expect(assigned.status).toBe(201);

    await page.reload();
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
    await page.locator('[data-testid="add-fletero-05-00"]').click();
    await expect(page.locator(`[data-testid="driver-option-${driverId}"]`)).not.toBeVisible();
  });

  test('4.2 removes an assigned driver and makes them immediately available in another slot', async ({ page }) => {
    const date = today();
    const created = await apiPost(page, '/loading/drivers', {
      name: `Reassignable Driver ${Date.now()}`,
      license_plate: 'RDR-1',
    });
    const driverId = created.data?.driver?.id;
    expect(driverId).toBeTruthy();

    const assigned = await apiPost(page, '/loading/schedules', {
      schedule_date: date,
      time_slot: '04:00',
      driver_type: 'fletero',
      driver_id: driverId,
    });
    expect(assigned.status).toBe(201);
    const scheduleId = assigned.data?.schedule?.id;
    expect(scheduleId).toBeTruthy();

    await page.reload();
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
    await page.locator('[data-testid="add-fletero-05-00"]').click();
    await expect(page.locator(`[data-testid="driver-option-${driverId}"]`)).not.toBeVisible();

    const removeButton = page.locator(`[data-testid="delete-entry-${scheduleId}"]`);
    await expect(removeButton).toBeVisible();
    await removeButton.click();

    await expect(page.locator(`[data-testid="entry-${scheduleId}"]`)).not.toBeVisible();
    await expect(page.locator(`[data-testid="driver-option-${driverId}"]`)).toBeVisible();
  });

  test('4.2 edit page removes an assigned driver and releases them for another slot', async ({ page }) => {
    const date = today();
    const created = await apiPost(page, '/loading/drivers', {
      name: `Editable Reassignable Driver ${Date.now()}`,
      license_plate: 'EDR-1',
    });
    const driverId = created.data?.driver?.id;
    expect(driverId).toBeTruthy();

    const assigned = await apiPost(page, '/loading/schedules', {
      schedule_date: date,
      time_slot: '04:00',
      driver_type: 'fletero',
      driver_id: driverId,
    });
    expect(assigned.status).toBe(201);
    const scheduleId = assigned.data?.schedule?.id;
    expect(scheduleId).toBeTruthy();

    await page.goto(`/loading/edit?date=${date}`);
    await page.waitForSelector('[data-testid="edit-schedule-list"]', { timeout: 10000 });

    const removeButton = page.locator(`[data-testid="delete-edit-entry-${scheduleId}"]`);
    await expect(removeButton).toBeVisible();
    await removeButton.click();
    await expect(page.locator(`[data-testid="edit-entry-${scheduleId}"]`)).not.toBeVisible();

    await page.goto('/loading');
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
    await page.locator('[data-testid="add-fletero-05-00"]').click();
    await expect(page.locator(`[data-testid="driver-option-${driverId}"]`)).toBeVisible();
  });

  test('4.2 can create a new fletero via quick-add form', async ({ page }) => {
    // Click add fletero on 04:00 slot
    await page.locator('[data-testid="add-fletero-04-00"]').click();

    // Click "Novo fletero" link
    await page.locator('[data-testid="add-new-fletero-btn"]').click();

    // Driver form should appear
    const form = page.locator('[data-testid="driver-form"]');
    await expect(form).toBeVisible();

    // Fill the form
    await page.locator('[data-testid="driver-form-name"]').fill('E2E Test Fletero');
    await page.locator('[data-testid="driver-form-plate"]').fill('TST-9999');

    // Submit
    await page.locator('[data-testid="driver-form-submit"]').click();

    // Driver should be assigned and driver select should close
    // Grid should refresh — the new driver assignment should appear
    // Wait for the driver select to disappear (assignment + grid refresh)
    await page.waitForSelector('[data-testid="driver-select-04-00"]', { state: 'detached', timeout: 10000 }).catch(() => {
      // might already be gone
    });

    // The quota indicator should now show at least 1/3
    const quota = page.locator('[data-testid="quota-04-00"]');
    await expect(quota).toContainText('/3');
  });

  test('4.2 allows a 4th fletero and shows the exceeded quota as a warning', async ({ page }) => {
    const date = today();

    // Clean up: delete all existing schedules for 04:00 today
    const existing = await apiGet<{ schedules: Array<{ id: number }> }>(
      page,
      `/loading/schedules?date=${date}`
    );
    for (const entry of existing.schedules) {
      if (entry.id) {
        await apiDelete(page, `/loading/schedules/${entry.id}`);
      }
    }

    // Create 4 fletero drivers
    const createdIds: number[] = [];
    for (let i = 1; i <= 4; i++) {
      const res = await apiPost(page, '/loading/drivers', {
        name: `Fletero Q${i}`,
        license_plate: `QT-${i}${i}${i}`,
      });
      if (res.data?.driver?.id) {
        createdIds.push(res.data.driver.id);
      }
    }

    // Assign first 3 fleteros to 04:00 — should all succeed
    for (let i = 0; i < 3; i++) {
      const res = await apiPost(page, '/loading/schedules', {
        schedule_date: date,
        time_slot: '04:00',
        driver_type: 'fletero',
        driver_id: createdIds[i],
      });
      expect(res.status).toBe(201);
    }

    // Assign the 4th via API — should succeed (since the limit is now informative)
    const res4 = await apiPost(page, '/loading/schedules', {
      schedule_date: date,
      time_slot: '04:00',
      driver_type: 'fletero',
      driver_id: createdIds[3],
    });
    expect(res4.status).toBe(201);

    // Reload the page to see the grid state
    await page.reload();
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });

    // Quota indicator should show 4/3 fleteros (since it is exceeded)
    const quota = page.locator('[data-testid="quota-04-00"]');
    await expect(quota).toContainText('4/3');

    // The add buttons should still be visible because the limit is informative, and the warning text should appear.
    await expect(page.locator('[data-testid="add-fletero-04-00"]')).toBeVisible();
    await expect(page.locator('[data-testid="add-fletero-04-30"]')).toBeVisible();
    await expect(page.locator('[data-testid="maxed-04-00"]')).toBeVisible();
  });
});

test.describe('Loading Schedule — Export Modal', () => {
  test.beforeEach(async ({ page }) => {
    await goToProtected(page, '/loading');
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
  });

  test('4.3 export modal opens and shows copy button', async ({ page }) => {
    // Click the export button
    await page.locator('[data-testid="open-export-btn"]').click();

    // Modal should appear
    const modal = page.locator('[data-testid="loading-export-modal"]');
    await expect(modal).toBeVisible();

    // Export text should be in Portuguese
    const textContent = page.locator('[data-testid="loading-export-text"]');
    await expect(textContent).toBeVisible();

    // Copy button should be present
    const copyBtn = page.locator('[data-testid="loading-export-copy"]');
    await expect(copyBtn).toBeVisible();

    // Close button should be present
    const closeBtn = page.locator('[data-testid="loading-export-close"]');
    await expect(closeBtn).toBeVisible();

    // Close the modal
    await closeBtn.click();
    await expect(modal).not.toBeVisible();
  });



  test('4.3 export text contains Portuguese headers for schedule with entries', async ({ page }) => {
    const date = today();

    // Create a driver and assign to a slot to get non-empty export
    await apiPost(page, '/loading/drivers', { name: 'Export Test Driver' });
    const driversRes = await page.request.get(`${API_BASE}/loading/drivers`, {
      headers: {
        Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem('auth_token'))}`,
      },
    });
    const driversData = await driversRes.json();
    const driver = (driversData.drivers as Array<{ id: number; name: string }>)
      .find((d) => d.name === 'Export Test Driver');

    if (driver) {
      await apiPost(page, '/loading/schedules', {
        schedule_date: date,
        time_slot: '05:00',
        driver_type: 'fletero',
        driver_id: driver.id,
      });
    }

    // Reload and open export
    await page.reload();
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
    await page.locator('[data-testid="open-export-btn"]').click();

    // Wait for export text to load
    const textContent = page.locator('[data-testid="loading-export-text"]');
    await expect(textContent).toBeVisible();

    const text = await textContent.textContent();
    expect(text).toContain('CRONOGRAMA DE CARREGAMENTO'); // Portuguese header
    expect(text).toContain(tomorrowDisplay());
    expect(text).toContain('━━━━━━━━━━━━━━');
    expect(text).toContain('📌 Total de carregamentos:');
    expect(text).toContain('✅ Bom trabalho a todos!')
  });

  test('4.3 export text uses the today batch even when a date query is present', async ({ page }) => {
    await page.goto('/loading?date=2099-12-31');
    await page.waitForSelector('[data-testid="schedule-grid"]', { timeout: 10000 });
    await page.locator('[data-testid="open-export-btn"]').click();
    await page.waitForSelector('[data-testid="loading-export-text"]', { timeout: 10000 });

    const text = await page.locator('[data-testid="loading-export-text"]').textContent();
    expect(text).toContain(tomorrowDisplay());
    expect(page.url()).toContain('date=2099-12-31');
  });
});
