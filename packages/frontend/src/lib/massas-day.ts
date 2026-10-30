/**
 * Whether a day belongs to the history instead of the shift.
 *
 * Trindade records production on the day it happens. A launch dated yesterday is a claim nobody typed
 * yesterday, and the backend refuses it with `409 MASSAS_HISTORY_FROZEN`; the screen closes the same door
 * with the same rule, so the operator never fills a grid that cannot be sent.
 */
export function isClosedDay(date: string, today: string): boolean {
  return date !== today;
}
