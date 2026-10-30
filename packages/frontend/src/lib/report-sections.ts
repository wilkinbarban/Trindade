/**
 * Where a section of the report belongs.
 *
 * The report is built from categories the plant defines, so "under Temperaturas" cannot mean a fixed id or a
 * fixed position: it means under whichever category asks for temperature readings, wherever the plant put it
 * that day. These two questions are separated from the page because they are decided by data, not by markup.
 */

interface TaskLike {
  task_type: string;
}

export interface CategoryLike {
  id: number;
  parent_category_id?: number | null;
  tasks: TaskLike[];
}

/**
 * Whether this category is the one the operator reads temperatures into.
 *
 * Only top-level categories count: a child with a temperature task is rendered inside its parent, so placing
 * the production card under it would put the card in the middle of another section.
 */
export function hasTemperatureTasks(category: CategoryLike): boolean {
  if (category.parent_category_id != null) return false;
  return category.tasks.some((task) => task.task_type === 'temperature');
}
