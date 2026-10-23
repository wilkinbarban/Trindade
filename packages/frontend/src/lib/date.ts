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
