import { getSaoPauloDateString, getSaoPauloHour } from '../../utils/date.js';
import { nextLoadingDateString } from '../loading/loading.export.service.js';

/**
 * The hour, in São Paulo, at which the day's loading is over and the next one is being prepared.
 *
 * The window this closes is the operator's own working day. A batch is prepared on the day before it is
 * loaded and the trucks start at 04:00, so until ten in the morning the day being worked on is today's,
 * prepared yesterday; from ten on it is the next loading day. Between six in the evening, when the
 * preparation is done, and nine the following morning, fifteen hours pass, which is why one schedule is
 * the active one for the whole of that stretch.
 */
export const ACTIVE_WINDOW_CUTOFF_HOUR = 10;

/**
 * The loading day an operator is working on at `now`, as a date string.
 *
 * The next loading day is asked of the loading module's own rule instead of being computed again here:
 * a Friday batch is loaded on Monday, and the card has to name the same day the WhatsApp text it offers
 * announces. Two definitions of "the next loading day" would eventually disagree, and the disagreement
 * would be invisible until an operator read a message about a day the card was not showing.
 *
 * `now` is a parameter so the boundary is testable without waiting for ten o'clock: the caller passes the
 * real clock and the tests pass the edge cases.
 */
export function activeScheduleDate(now: Date = new Date()): string {
  const today = getSaoPauloDateString(now);
  return getSaoPauloHour(now) < ACTIVE_WINDOW_CUTOFF_HOUR ? today : nextLoadingDateString(today);
}
