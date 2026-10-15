/**
 * What the dashboard's active-schedule card offers: which door it opens and what it calls that door.
 *
 * The summary carries two dates and they are different days. `activeScheduleDate` is the loading day --
 * the trucks that leave tomorrow -- while `activeScheduleBatchDate` is the day the batch was registered in
 * `loading_schedules`, and only the second names rows in the grid. The card offered the first, so the
 * operator landed on a day with no records and the grid answered "Nenhuma carga programada para este dia":
 * a link that looked right and went to an empty day.
 *
 * So the rule is the batch date, and when there is nothing to show -- the count is zero because the
 * fifteen-hour window closed without a batch being created, or the date never arrived -- the card stops
 * pretending to be a door to a day and invites the operator to create today's schedule instead. A count
 * with no batch date is treated the same way rather than guessing a day: the alternative is opening an
 * empty grid, which is the defect this module exists to close.
 */
export interface ActiveScheduleSummary {
  activeScheduleCount?: number | null;
  activeScheduleDate?: string | null;
  activeScheduleBatchDate?: string | null;
}

export interface ActiveScheduleLink {
  /** The catalogue key for the link's label, so the screen names the action it is offering. */
  labelKey: 'dashboard.viewSchedule' | 'dashboard.createSchedule';
  /** Where the operator goes. */
  to: string;
}

export function activeScheduleLink(summary: ActiveScheduleSummary | null | undefined): ActiveScheduleLink {
  const count = summary?.activeScheduleCount ?? 0;
  const batchDate = summary?.activeScheduleBatchDate ?? null;

  if (count > 0 && batchDate) {
    return { labelKey: 'dashboard.viewSchedule', to: `/loading/edit?date=${batchDate}` };
  }

  return { labelKey: 'dashboard.createSchedule', to: '/loading' };
}
