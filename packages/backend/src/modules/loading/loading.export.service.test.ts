import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { batchDateFor, nextLoadingDateString } from './loading.export.service.js';

/**
 * The loading calendar's two directions, tested as a pair because they only make sense together.
 *
 * A batch is prepared on one day and loaded on the next, and the Friday batch is loaded on Monday: that
 * is the rule the WhatsApp text has always announced. The export endpoint takes the batch date and renders
 * the text for the day it is loaded, so a client that knows the day being loaded -- the dashboard's card
 * knows it -- has to turn it back into the batch date, and the turning back has to follow the same calendar
 * instead of being a `-1 day` each client reinvents. One definition, two directions.
 */
describe('loading calendar', () => {
  it('announces the following day, and Monday for a Friday batch', () => {
    assert.equal(nextLoadingDateString('2026-08-24'), '2026-08-25'); // Monday -> Tuesday
    assert.equal(nextLoadingDateString('2026-08-27'), '2026-08-28'); // Thursday -> Friday
    assert.equal(nextLoadingDateString('2026-08-28'), '2026-08-31'); // Friday -> Monday
  });

  it('turns a loading day back into the batch date it comes from', () => {
    assert.equal(batchDateFor('2026-08-25'), '2026-08-24'); // Tuesday <- Monday
    assert.equal(batchDateFor('2026-08-28'), '2026-08-27'); // Friday <- Thursday
    assert.equal(batchDateFor('2026-08-31'), '2026-08-28'); // Monday <- Friday, the weekend is not loading
  });

  it('has no batch date for a day the calendar never loads', () => {
    // A Saturday is skipped: the Friday batch goes to Monday, so no batch announces a Saturday. Saying so
    // is what lets a caller distinguish "the export cannot produce this day's text" from "the date is
    // wrong", instead of being handed the previous Friday and a text about Monday.
    assert.equal(batchDateFor('2026-08-29'), null);
  });

  it('is the exact inverse of itself for every day the calendar can announce', () => {
    const week = ['2026-08-24', '2026-08-25', '2026-08-26', '2026-08-27', '2026-08-28', '2026-08-30', '2026-08-31'];

    for (const loadingDay of week) {
      const batchDate = batchDateFor(loadingDay);
      assert.notEqual(batchDate, null, `${loadingDay} must have a batch date`);
      assert.equal(nextLoadingDateString(batchDate!), loadingDay, `${loadingDay} does not survive the round trip`);
    }
  });
});
