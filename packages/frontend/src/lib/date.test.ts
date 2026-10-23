import assert from 'node:assert/strict';
import test from 'node:test';
import { saoPauloToday } from './date';

test('reads the day in São Paulo, not in UTC', () => {
  // 02:30 UTC on 2 January is still 23:30 on 1 January in São Paulo (UTC-3).
  assert.equal(saoPauloToday(new Date('2026-01-02T02:30:00Z')), '2026-01-01');
});

test('keeps a midday instant on the same day', () => {
  assert.equal(saoPauloToday(new Date('2026-01-02T15:00:00Z')), '2026-01-02');
});

test('pads single-digit months and days', () => {
  assert.equal(saoPauloToday(new Date('2026-03-05T15:00:00Z')), '2026-03-05');
});
