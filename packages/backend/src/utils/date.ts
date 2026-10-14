export function getSaoPauloDateString(date: Date = new Date()): string {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const parts = formatter.formatToParts(date);
  const year = parts.find((part) => part.type === 'year')?.value;
  const month = parts.find((part) => part.type === 'month')?.value;
  const day = parts.find((part) => part.type === 'day')?.value;
  return `${year}-${month}-${day}`;
}

/**
 * The hour of the day in São Paulo, as a number from 0 to 23.
 *
 * `hourCycle: 'h23'` rather than `hour12: false`: the latter renders midnight as `24` in some ICU
 * versions, which would put midnight at the end of the day instead of its start. Anything that compares
 * the time of day against a cutoff has to read the operators' clock and not the server's, because the
 * server may be in any zone.
 */
export function getSaoPauloHour(date: Date = new Date()): number {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Sao_Paulo',
    hour: '2-digit',
    hourCycle: 'h23',
  });
  return Number(formatter.format(date));
}
