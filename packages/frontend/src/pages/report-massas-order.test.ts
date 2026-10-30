import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * Where the production card sits, read as source because it is an order and not a value.
 *
 * Wilkin asked for it right under Temperaturas: the operator reads the morning's temperatures and then
 * counts what came out, and having the two apart meant scrolling past a list of checks to compare the same
 * shift twice. The order is the requirement, so the order is what is asserted.
 */
const view = readFileSync(new URL('./ReportViewPage.tsx', import.meta.url), 'utf8');
const create = readFileSync(new URL('./ReportsPage.tsx', import.meta.url), 'utf8');

describe('Massa do dia follows Temperaturas', () => {
  it('sits after the temperatures card on the report view', () => {
    const temperatures = view.indexOf("reports.temperaturesSection");
    const massas = view.indexOf('data-testid="report-massas-card"');
    assert.ok(temperatures > 0 && massas > temperatures, 'the production card must come after the temperatures card');
  });

  it('renders under the category that reads temperatures, wherever the plant put it', () => {
    assert.ok(create.includes('hasTemperatureTasks'), 'the placement is decided by the category, not by a fixed id');
    assert.ok(create.includes('{hasTemperatureTasks(cat) && massasCard}'));
  });

  it('keeps one card definition, so the two placements cannot drift apart', () => {
    assert.equal(create.match(/data-testid="report-massas-card"/g)?.length, 1);
    assert.ok(create.includes('!massasAfterCategories && massasCard'), 'a report with no temperatures still shows it');
  });

  it('never renders it before the temperatures section in the create form', () => {
    const massasPlacement = create.indexOf('{hasTemperatureTasks(cat) && massasCard}');
    const categoriesMap = create.indexOf('{categories.filter((cat) => !cat.parent_category_id).map((cat) => (');
    assert.ok(categoriesMap > 0 && massasPlacement > categoriesMap);
  });
});
