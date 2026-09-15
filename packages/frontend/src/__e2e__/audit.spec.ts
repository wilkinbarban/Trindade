import { test, expect } from '@playwright/test';
import { goToProtected } from './auth-helpers';

/**
 * Audit Page E2E tests (PR2: Audit Service + Visual UI)
 *
 * Tests:
 * - 6.1 Admin can view audit log page with paginated table
 * - 6.2 Admin can filter logs by action and entity type
 * - 6.3 Pagination controls work
 * - 6.4 Sidebar navigation shows "Auditoria" link for admin
 */

test.describe('Audit Page (Phase 6)', () => {
  test('6.1 admin can view audit page with log entries', async ({ page }) => {
    await goToProtected(page, '/admin/audit');

    // Page title
    await expect(page.locator('h3:has-text("Auditoria")')).toBeVisible();

    // Table should be present with log entries
    // Since the login itself generates an audit entry, there should be at least one
    await page.waitForSelector('table', { timeout: 10000 });

    // Check that the table headers exist
    await expect(page.locator('text=Data/Hora')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Usuário' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Ação' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Entidade' })).toBeVisible();

    // Should show total count
    await expect(page.locator('text=registro')).toBeVisible();
  });

  test('6.2 admin can filter audit logs by action', async ({ page }) => {
    await goToProtected(page, '/admin/audit');

    // Wait for page to load
    await page.waitForSelector('table', { timeout: 10000 });

    // Select "Login" from action dropdown
    const actionSelect = page.locator('select').first();
    await actionSelect.selectOption('login');

    // Click "Filtrar" button
    await page.locator('button:has-text("Filtrar")').click();

    // Wait for the table to update
    await page.waitForTimeout(1000);

    // All visible action badges should show "login"
    const actionBadges = page.locator('td span:has-text("login")');
    const count = await actionBadges.count();
    expect(count).toBeGreaterThan(0);
  });

  test('6.3 pagination controls work', async ({ page }) => {
    await goToProtected(page, '/admin/audit');

    await page.waitForSelector('table', { timeout: 10000 });

    // Check pagination info is visible
    await expect(page.locator('text=Página')).toBeVisible();

    // "Anterior" button should be disabled on page 1
    const prevBtn = page.locator('button:has-text("Anterior")');
    await expect(prevBtn).toBeDisabled();

    // "Próximo" button should exist
    const nextBtn = page.locator('button:has-text("Próximo")');
    await expect(nextBtn).toBeVisible();
  });

  test('6.4 sidebar shows Auditoria nav link for admin', async ({ page }) => {
    await goToProtected(page, '/admin/audit');

    // Wait for sidebar to be visible
    await page.waitForSelector('text=Trindade Massas', { timeout: 10000 });

    // Open sidebar on mobile (or just check)
    // The sidebar nav should be visible on desktop
    // Check for "Auditoria" link in the admin section
    const auditLink = page.locator('a:has-text("Auditoria")');
    await expect(auditLink).toBeVisible();

    // Click the audit link and verify we stay on the audit page
    await auditLink.click();
    await expect(page.locator('h3:has-text("Auditoria")')).toBeVisible();
  });

  test('6.5 accessing audit page requires authentication', async ({ page }) => {
    // Try to access audit page without login
    await page.goto('/admin/audit');

    // Should be redirected to login
    await page.waitForURL('**/login', { timeout: 10000 });
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  });
});
