/**
 * The plant's day, always read in São Paulo.
 *
 * The browser's own clock is not a source of truth for "today": an operator travelling, or a device
 * set to another zone, would otherwise be offered tomorrow or yesterday in a date input and could
 * file a count or a launch under a day the plant is not living. The backend reads the same zone, so
 * the two agree on what today means.
 */
export function saoPauloToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const pick = (type: string): string => parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')}`;
}

/**
 * The plant's day N days before `now`, still read in São Paulo.
 *
 * The day is first read in the plant's zone and only then shifted, and the shift happens in UTC so
 * that no local-time rule can move it: the date has no clock attached, and adding 24 hours to a
 * midnight somewhere is how a helper like this ends up off by one on the days DST changes.
 */
export function saoPauloDaysAgo(daysAgo: number, now: Date = new Date()): string {
  if (!Number.isInteger(daysAgo) || daysAgo < 0) {
    throw new RangeError(`daysAgo must be a whole number of past days, got ${daysAgo}`);
  }
  const [year, month, day] = saoPauloToday(now).split('-').map(Number);
  const shifted = new Date(Date.UTC(year ?? 0, (month ?? 1) - 1, (day ?? 1) - daysAgo));
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`;
}

/** Yesterday on the plant's clock: the last day a count can have been closed. */
export function saoPauloYesterday(now: Date = new Date()): string {
  return saoPauloDaysAgo(1, now);
}
