import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { hasTemperatureTasks, type CategoryLike } from './report-sections';

/**
 * Where "Massa do dia" belongs is decided by which category carries the temperature readings, so that
 * question has an answer of its own and is tested as one: the report has no fixed category ids, a plant can
 * name its categories what it wants, and the section has to sit under the one that asks for temperatures
 * wherever that is in the day's order.
 */
const category = (overrides: Partial<CategoryLike> & { id: number }): CategoryLike => ({
  parent_category_id: null,
  tasks: [],
  ...overrides,
});

describe('hasTemperatureTasks', () => {
  it('recognises the category that asks for temperature readings', () => {
    assert.equal(
      hasTemperatureTasks(
        category({
          id: 1,
          tasks: [{ task_type: 'check' }, { task_type: 'temperature' }],
        }),
      ),
      true,
    );
  });

  it('does not claim a category of plain checks', () => {
    assert.equal(hasTemperatureTasks(category({ id: 2, tasks: [{ task_type: 'check' }] })), false);
  });

  it('never treats a child category as the place to sit under', () => {
    assert.equal(
      hasTemperatureTasks(
        category({ id: 3, parent_category_id: 1, tasks: [{ task_type: 'temperature' }] }),
      ),
      false,
    );
  });

  it('answers for a category with no tasks at all', () => {
    assert.equal(hasTemperatureTasks(category({ id: 4 })), false);
  });
});
