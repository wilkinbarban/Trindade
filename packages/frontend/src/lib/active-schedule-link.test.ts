import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { activeScheduleLink } from './active-schedule-link';

/**
 * What the dashboard's active-schedule card offers, which is a decision worth testing on its own.
 *
 * The summary carries two dates and they are not the same day: `activeScheduleDate` is the loading day --
 * tomorrow's trucks -- while `activeScheduleBatchDate` is the day the batch was registered in
 * `loading_schedules`, and only the second one names rows in the grid. The card offered the first, so it
 * opened a day with no records and the grid answered "Nenhuma carga programada para este dia".
 */
describe('activeScheduleLink', () => {
  it('opens the day the batch was registered when there is a schedule to show', () => {
    assert.deepEqual(
      activeScheduleLink({ activeScheduleCount: 3, activeScheduleBatchDate: '2026-10-14' }),
      { labelKey: 'dashboard.viewSchedule', to: '/loading/edit?date=2026-10-14' },
    );
  });

  it('invites the operator to create the day when the window closed with nothing registered', () => {
    assert.deepEqual(
      activeScheduleLink({ activeScheduleCount: 0, activeScheduleBatchDate: null }),
      { labelKey: 'dashboard.createSchedule', to: '/loading' },
    );
  });

  it('invites to create when the summary never arrived, rather than opening a day nobody knows', () => {
    assert.deepEqual(activeScheduleLink(null), { labelKey: 'dashboard.createSchedule', to: '/loading' });
  });

  it('asks for the batch date and not the loading day, which is the defect this closes', () => {
    const link = activeScheduleLink({
      activeScheduleCount: 2,
      activeScheduleDate: '2026-10-15',
      activeScheduleBatchDate: '2026-10-14',
    });

    assert.equal(link.to, '/loading/edit?date=2026-10-14');
    assert.ok(!link.to.includes('2026-10-15'), 'the loading day has no rows to show');
  });

  it('opens the day of the batch when the count arrived but the batch date did not', () => {
    // A count without the batch date cannot be opened as a day; inviting to create is the honest answer,
    // because the alternative is guessing a date and landing the operator on an empty grid.
    assert.deepEqual(
      activeScheduleLink({ activeScheduleCount: 4, activeScheduleBatchDate: null }),
      { labelKey: 'dashboard.createSchedule', to: '/loading' },
    );
  });
});
