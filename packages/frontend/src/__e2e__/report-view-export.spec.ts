import { test, expect, type Page } from '@playwright/test';
import { fillAllTemperatureInputs, getE2EIdentities, goToProtected } from './auth-helpers';

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

test.describe('Report View & Export', () => {
  test.beforeEach(async ({ page }) => {
    await cleanTodayReports(page);
  });
  test('report detail page shows created report data', async ({ page }) => {
    // 1. Create a report first via the builder
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    // Check some check tasks
    await page.locator('[data-testid="check-task-1"]').check();
    await page.locator('[data-testid="check-task-3"]').check();

    // Fill a temperature
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="temperature-task-5"]').fill('-18');

    // Add notes
    await page.locator('[data-testid="notes-textarea"]').fill('E2E test report');

    // Submit
    await page.locator('[data-testid="submit-report-btn"]').click();

    // Should redirect to the report detail page
    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });
    await expect(page.locator('[data-testid="report-detail"]')).toBeVisible();

    // Verify the report detail header renders
    await expect(page.locator('text=Detalhes do Relatório')).toBeVisible();

    // Notes should be visible
    await expect(page.locator('text=E2E test report')).toBeVisible();

    // Temperature should be visible in some form (it's rendered in a section)
    await expect(page.locator('text=-18°C').first()).toBeVisible();
  });

  test('export button opens export preview dialog', async ({ page }) => {
    // Create a report first
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="notes-textarea"]').fill('Export test');
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Click the export button
    const exportBtn = page.locator('[data-testid="export-whatsapp-btn"]');
    await expect(exportBtn).toBeVisible();
    await exportBtn.click();

    // Export modal should appear
    await page.waitForSelector('[data-testid="export-modal"]', { timeout: 10000 });
    await expect(page.locator('[data-testid="export-modal"]')).toBeVisible();

    // Modal title
    await expect(page.locator('text=Exportar para WhatsApp')).toBeVisible();

    // Export text content should be loaded (not empty, not loading spinner)
    const textContent = page.locator('[data-testid="export-text-content"]');
    await expect(textContent).toBeVisible();

    // Text should contain Portuguese content (has emojis and pt-BR labels)
    const text = await textContent.textContent();
    expect(text).toBeTruthy();
    expect(text!.length).toBeGreaterThan(50);
    // Verify key Portuguese markers
    expect(text).toMatch(/Trindade Massas|RELATÓRIO|Turno|Observações/);

    // Copy button should be present
    const copyBtn = page.locator('[data-testid="copy-export-btn"]');
    await expect(copyBtn).toBeVisible();
    await expect(copyBtn).toBeEnabled();
  });

  test('export modal only shows WhatsApp copy action', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    // Create a report
    await goToProtected(page, '/reports', identities.worker);
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Open export modal
    await page.locator('[data-testid="export-whatsapp-btn"]').click();
    await page.waitForSelector('[data-testid="export-modal"]', { timeout: 10000 });

    await expect(page.locator('[data-testid="export-modal"] [data-testid="export-txt-btn"]')).toHaveCount(0);
    await expect(page.locator('[data-testid="export-modal"] [data-testid="export-pdf-btn"]')).toHaveCount(0);

    const copyBtn = page.locator('[data-testid="copy-export-btn"]');
    await expect(copyBtn).toBeVisible();
    await expect(copyBtn).toBeEnabled();
  });

  test('export dialog can be closed', async ({ page }) => {
    // Create a report
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Open export
    await page.locator('[data-testid="export-whatsapp-btn"]').click();
    await page.waitForSelector('[data-testid="export-modal"]', { timeout: 10000 });
    await expect(page.locator('[data-testid="export-modal"]')).toBeVisible();

    // Close via the X button
    await page.locator('[data-testid="close-export-btn"]').click();

    // Modal should disappear
    await expect(page.locator('[data-testid="export-modal"]')).not.toBeVisible();
    // But report detail should still be visible
    await expect(page.locator('[data-testid="report-detail"]')).toBeVisible();
  });

  test('export generates text in Portuguese regardless of UI locale', async ({ page }) => {
    // Create a report
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="temperature-task-5"]').fill('-15');
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Open export
    await page.locator('[data-testid="export-whatsapp-btn"]').click();
    await page.waitForSelector('[data-testid="export-modal"]', { timeout: 10000 });

    const text = await page.locator('[data-testid="export-text-content"]').textContent();

    // Portuguese-only proof: export MUST contain Portuguese static strings,
    // NOT English or Spanish section labels
    expect(text).toContain('RELATÓRIO');
    expect(text).not.toContain('REPORT');
    expect(text).not.toContain('INFORME');
  });

  test('copy button shows Copied! feedback', async ({ page }) => {
    // Create a report
    await goToProtected(page, '/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

    await page.locator('[data-testid="check-task-1"]').check();
    await fillAllTemperatureInputs(page);
    await page.locator('[data-testid="submit-report-btn"]').click();

    await page.waitForSelector('[data-testid="report-detail"]', { timeout: 10000 });

    // Open export
    await page.locator('[data-testid="export-whatsapp-btn"]').click();
    await page.waitForSelector('[data-testid="export-modal"]', { timeout: 10000 });

    // Click copy — requires clipboard permission in headless mode
    // Grant clipboard permissions for the test context
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);

    const copyBtn = page.locator('[data-testid="copy-export-btn"]');
    await copyBtn.click();

    // Should show "Copiado!" feedback
    await expect(page.locator('text=Copiado!')).toBeVisible();

    // The clipboard should contain the export text
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBeTruthy();
    expect(clipboardText.length).toBeGreaterThan(50);
  });
});
