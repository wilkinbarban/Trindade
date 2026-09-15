import { test, expect } from '@playwright/test';
import { goToProtected } from './auth-helpers';

const API_BASE = 'http://localhost:3099/api';

async function apiPost(page: import('@playwright/test').Page, url: string, body: unknown) {
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  const response = await page.request.post(`${API_BASE}${url}`, {
    data: body,
    headers: { Authorization: `Bearer ${token}` },
  });
  return { status: response.status(), data: await response.json() };
}

async function createProductTemplateTask(
  page: import('@playwright/test').Page,
  categoryType: 'check_assai' | 'check_normal',
  suffix: string
) {
  await goToProtected(page, '/');

  const categoryResponse = await apiPost(page, '/admin/categories', {
    name_pt: `Categoria ${suffix}`,
    name_es: `Categoría ${suffix}`,
    category_type: categoryType,
    sort_order: 500,
  });
  expect(categoryResponse.status).toBe(201);

  const taskResponse = await apiPost(page, '/admin/tasks', {
    category_id: categoryResponse.data.category.id,
    name_pt: `Montaje ${suffix}`,
    name_es: `Montaje ${suffix}`,
  });
  expect(taskResponse.status).toBe(201);

  await page.goto('/reports');
  await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });

  return taskResponse.data.task.id as number;
}

test.describe('Report Builder — Element Types', () => {
  test.beforeEach(async ({ page }) => {
    await goToProtected(page, '/reports');
    // Wait for categories to load (form appears)
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });
  });

  test('renders check tasks as toggle checkboxes', async ({ page }) => {
    // Check tasks are from "Higiene e Organização" category (task IDs 1-4)
    const check1 = page.locator('[data-testid="check-task-1"]');
    await expect(check1).toBeVisible();
    await expect(check1).not.toBeChecked();

    // Toggle it
    await check1.check();
    await expect(check1).toBeChecked();

    // Verify other check tasks exist
    await expect(page.locator('[data-testid="check-task-2"]')).toBeVisible();
    await expect(page.locator('[data-testid="check-task-3"]')).toBeVisible();
    await expect(page.locator('[data-testid="check-task-4"]')).toBeVisible();
  });
  test('renders temperature tasks with numeric inputs', async ({ page }) => {
    // Temperature tasks: task IDs 5 (Câmara Principal), 6 (Câmara Resfriamento)
    const temp5 = page.locator('[data-testid="temperature-task-5"]');
    await expect(temp5).toBeVisible();
    await expect(temp5).toHaveAttribute('type', 'number');

    // Enter a temperature value
    await temp5.fill('-18');
    await expect(temp5).toHaveValue('-18');

    // Second temperature reading (Task 5 has 2 readings in seed data)
    const temp5_2 = page.locator('[data-testid="temperature-task-5-2"]');
    await expect(temp5_2).toBeVisible();
    await temp5_2.fill('-5');
    await expect(temp5_2).toHaveValue('-5');
  });

  test('renders the configured number of temperature readings', async ({ page }) => {
    const categoryResponse = await apiPost(page, '/admin/categories', {
      name_pt: 'Temperatura E2E', name_es: 'Temperatura E2E', category_type: 'temperature', sort_order: 501,
    });
    expect(categoryResponse.status).toBe(201);
    const taskResponse = await apiPost(page, '/admin/tasks', {
      category_id: categoryResponse.data.category.id,
      name_pt: 'Câmara E2E', name_es: 'Cámara E2E', temperature_readings: 2,
    });
    expect(taskResponse.status).toBe(201);

    await page.goto('/reports');
    await page.waitForSelector('[data-testid="report-builder-form"]', { timeout: 10000 });
    const taskId = taskResponse.data.task.id;
    await expect(page.getByTestId(`temperature-task-${taskId}`)).toBeVisible();
    await expect(page.getByTestId(`temperature-task-${taskId}-2`)).toBeVisible();
    await expect(page.locator(`[data-testid^="temperature-task-${taskId}"]`)).toHaveCount(2);
  });

  test('renders check_assai tasks as fixed Assaí product checkboxes', async ({ page }) => {
    const taskId = await createProductTemplateTask(page, 'check_assai', 'Assaí E2E');
    const expectedProducts = [
      'Nhoque Kg',
      'Quadrada',
      'Rolo 500',
      'Rolo kg',
      'Rolo 2kg',
      'Lashana',
      'Disco 200g',
      'Disco 400g',
      'Disgo 500g',
    ];

    await expect(page.locator(`text=Montaje Assaí E2E`)).toBeVisible();
    await expect(page.locator(`input[data-testid^="product-task-${taskId}-"]`)).toHaveCount(expectedProducts.length);

    for (const product of expectedProducts) {
      const checkbox = page.getByTestId(`product-task-${taskId}-${product}`);
      await expect(checkbox).toBeVisible();
      await expect(checkbox).not.toBeChecked();
    }

    await page.getByTestId(`product-task-${taskId}-Nhoque Kg`).check();
    await page.getByTestId(`product-task-${taskId}-Rolo 2kg`).check();
    await expect(page.getByTestId(`product-task-${taskId}-Nhoque Kg`)).toBeChecked();
    await expect(page.getByTestId(`product-task-${taskId}-Rolo 2kg`)).toBeChecked();
  });

  test('renders check_normal tasks as fixed Normal product checkboxes', async ({ page }) => {
    const taskId = await createProductTemplateTask(page, 'check_normal', 'Normal E2E');
    const expectedProducts = ['Nhoque 400g', 'Nhoque Kg', 'Quadrada'];

    await expect(page.locator(`text=Montaje Normal E2E`)).toBeVisible();
    await expect(page.locator(`input[data-testid^="product-task-${taskId}-"]`)).toHaveCount(expectedProducts.length);

    for (const product of expectedProducts) {
      const checkbox = page.getByTestId(`product-task-${taskId}-${product}`);
      await expect(checkbox).toBeVisible();
      await expect(checkbox).not.toBeChecked();
    }

    await page.getByTestId(`product-task-${taskId}-Nhoque 400g`).check();
    await page.getByTestId(`product-task-${taskId}-Quadrada`).check();
    await expect(page.getByTestId(`product-task-${taskId}-Nhoque 400g`)).toBeChecked();
    await expect(page.getByTestId(`product-task-${taskId}-Quadrada`)).toBeChecked();
  });

  test('turno selector is present and defaults to auto-detected value', async ({ page }) => {
    const tardeRadio = page.locator('[data-testid="turno-selector-tarde"]');
    const noiteRadio = page.locator('[data-testid="turno-selector-noite"]');

    await expect(tardeRadio).toBeVisible();
    await expect(noiteRadio).toBeVisible();

    // One of them should be checked (auto-detected)
    const tardeChecked = await tardeRadio.isChecked();
    const noiteChecked = await noiteRadio.isChecked();
    expect(tardeChecked || noiteChecked).toBe(true);
    expect(tardeChecked !== noiteChecked).toBe(true); // exactly one is checked
  });

  test('notes textarea is present', async ({ page }) => {
    const notes = page.locator('[data-testid="notes-textarea"]');
    await expect(notes).toBeVisible();
    await notes.fill('Turno sem novidades.');
    await expect(notes).toHaveValue('Turno sem novidades.');
  });

  test('submit button is present', async ({ page }) => {
    const submitBtn = page.locator('[data-testid="submit-report-btn"]');
    await expect(submitBtn).toBeVisible();
  });
});
