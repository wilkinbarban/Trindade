import { test, expect, type Page } from '@playwright/test';
import { fillAllTemperatureInputs, getActiveTemperatureReadings, getE2EIdentities, goToProtected, loginViaApi, VALID_TEMPERATURES } from './auth-helpers';

/**
 * Photo Gallery UI E2E tests (PR1 remediation)
 *
 * Tests:
 * - 3.1 Report view page shows photos section with empty state
 * - 3.2 Upload photo on edit page and verify it appears in edit gallery
 * - 3.3 Uploaded photo thumbnail is visible on report view page
 */

function generateTestPng(): { name: string; mimeType: string; buffer: Buffer } {
  // Minimal valid 1x1 pixel PNG
  return {
    name: 'test-photo.png',
    mimeType: 'image/png',
    buffer: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
      'base64'
    ),
  };
}

async function cleanTodayReports(page: Page) {
  const identities = await getE2EIdentities(page);
  const loginRes = await page.request.post('http://localhost:3099/api/auth/login', {
    data: identities.admin,
  });
  if (!loginRes.ok()) return;
  const token = (await loginRes.json()).token;

  const listRes = await page.request.get('http://localhost:3099/api/reports?period=today', {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!listRes.ok()) return;
  const { reports } = await listRes.json();

  for (const r of reports) {
    await page.request.delete(`http://localhost:3099/api/reports/${r.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }
}

test.describe('Photo Gallery UI (Phase 3)', () => {
  test.beforeEach(async ({ page }) => {
    await cleanTodayReports(page);
  });
  test('3.1 report view shows photos section with empty state', async ({ page }) => {
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    // Create a minimal report
    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    // Should redirect to report detail
    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Photos section heading
    await expect(page.locator('text=Fotos do Relatório')).toBeVisible();

    // Empty state message (no photos uploaded yet)
    await expect(page.locator('text=Nenhuma foto anexada.')).toBeVisible();
  });

  test('3.2 upload photo on edit page renders in gallery', async ({ page }) => {
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Navigate to edit page
    await page.locator('[data-testid="edit-report-btn"]').click();
    await page.waitForSelector('[data-testid="report-edit-form"]', { timeout: 10000 });

    // Upload a test photo via the hidden file input
    const fileInput = page.locator('input[type="file"][accept*="image"]');
    await fileInput.setInputFiles(generateTestPng());

    // Pending photo should appear in the edit page preview gallery
    await expect(page.locator('[data-testid="remove-pending-photo-btn"]').first()).toBeVisible({ timeout: 10000 });
  });

  test('3.3 uploaded photo thumbnail appears on report view page', async ({ page }) => {
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });
    const reportViewUrl = page.url();

    // Go to edit page
    await page.locator('[data-testid="edit-report-btn"]').click();
    await page.waitForSelector('[data-testid="report-edit-form"]', { timeout: 10000 });

    // Upload a test photo
    const fileInput = page.locator('input[type="file"][accept*="image"]');
    await fileInput.setInputFiles(generateTestPng());

    // Pending preview should be visible before save, then save uploads it.
    await expect(page.locator('[data-testid="remove-pending-photo-btn"]').first()).toBeVisible({ timeout: 10000 });
    const reportId = reportViewUrl.match(/\/reports\/(\d+)/)?.[1];
    if (!reportId) throw new Error(`Could not extract report ID from ${reportViewUrl}`);

    const patchResponse = page.waitForResponse((response) =>
      response.url().endsWith(`/api/reports/${reportId}`) &&
      response.request().method() === 'PATCH'
    );
    await page.locator('[data-testid="update-report-btn"]').click();
    expect((await patchResponse).status()).toBe(200);
    await page.waitForURL(reportViewUrl, { timeout: 10000 });
    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Photo thumbnail should be visible in the view page gallery grid
    const viewPhotos = page.locator('[data-testid="report-detail"] img[alt^="Photo"]');
    await expect(viewPhotos.first()).toBeVisible({ timeout: 10000 });
    const photoCount = await viewPhotos.count();
    expect(photoCount).toBeGreaterThanOrEqual(1);
  });

  test('3.4 create page supports multiple pending previews and removal before save', async ({ page }) => {
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    const fileInput = page.locator('[data-testid="report-photo-input"]');
    await fileInput.setInputFiles([generateTestPng(), { ...generateTestPng(), name: 'test-photo-2.png' }]);

    await expect(page.locator('[data-testid="remove-pending-photo-btn"]')).toHaveCount(2);
    await page.locator('[data-testid="remove-pending-photo-btn"]').first().click();
    await expect(page.locator('[data-testid="remove-pending-photo-btn"]')).toHaveCount(1);
  });


  test('3.5 upload error preserves create form data and can be retried', async ({ page }) => {
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="notes-textarea"]').fill('Retry keeps this note');
    await page.locator('[data-testid="report-photo-input"]').setInputFiles(generateTestPng());
    await expect(page.locator('[data-testid="remove-pending-photo-btn"]')).toHaveCount(1);

    const uploadPattern = '**/api/reports/*/photos';
    await page.route(uploadPattern, async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Injected upload failure' }),
      });
    });

    await page.locator('[data-testid="submit-report-btn"]').click();
    await expect(page.locator('text=Injected upload failure')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="report-builder-form"]')).toBeVisible();
    await expect(page.locator('[data-testid="check-task-1"]')).toBeChecked();
    await expect(page.locator('[data-testid="notes-textarea"]')).toHaveValue('Retry keeps this note');
    await expect(page.locator('[data-testid="remove-pending-photo-btn"]')).toHaveCount(1);

    await page.unroute(uploadPattern);

    await Promise.all([
      page.waitForResponse((response) =>
        response.url().includes('/api/reports/') &&
        response.url().endsWith('/photos') &&
        response.request().method() === 'POST' &&
        response.status() === 201
      ),
      page.waitForURL(/\/reports\/\d+$/),
      page.locator('[data-testid="submit-report-btn"]').click(),
    ]);

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });
    await expect(page.locator('text=Retry keeps this note')).toBeVisible();
    await expect(page.locator('[data-testid="report-detail"] img[alt^="Photo"]').first()).toBeVisible();
  });

  test('3.6 non-creator sees read-only photo controls on edit page', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    const creatorLogin = await page.request.post('http://localhost:3099/api/auth/login', {
      data: identities.admin,
    });
    expect(creatorLogin.ok()).toBeTruthy();
    const creatorToken = (await creatorLogin.json()).token as string;

    const temperatures = await getActiveTemperatureReadings(page.request, creatorToken);
    const createReport = await page.request.post('http://localhost:3099/api/reports', {
      headers: { authorization: `Bearer ${creatorToken}` },
      data: {
        turno: 'tarde',
        notes: 'Creator-owned photo report',
        temperatures,
      },
    });
    expect(createReport.ok()).toBeTruthy();
    const reportId = (await createReport.json()).report.id as number;

    const upload = await page.request.post(`http://localhost:3099/api/reports/${reportId}/photos`, {
      headers: { authorization: `Bearer ${creatorToken}` },
      multipart: { file: generateTestPng() },
    });
    expect(upload.status()).toBe(201);

    await loginViaApi(page, identities.worker);
    await page.goto(`/reports/${reportId}/edit`);
    await page.waitForSelector('[data-testid="report-edit-form"]', { timeout: 10000 });

    await expect(page.locator('text=Período de edição expirado')).toBeVisible();
    await expect(page.locator('[data-testid="report-photo-input"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="delete-existing-photo-btn"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="update-report-btn"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="report-edit-form"] img[alt^="Photo"]').first()).toBeVisible();
  });

});
