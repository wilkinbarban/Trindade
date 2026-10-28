import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatDateLabel, saoPauloDaysAgo, saoPauloToday, saoPauloYesterday } from './date.js';

// 2026-10-23T15:00:00Z is midday in São Paulo, the quiet case.
const NOON_SP = new Date('2026-10-23T15:00:00Z');
// 2026-01-01T02:30:00Z is still 31 December in São Paulo: the case a UTC-only helper gets wrong.
const LATE_NIGHT_SP = new Date('2026-01-01T02:30:00Z');

test('yesterday is the previous day on the plant clock, not the browser clock', () => {
  assert.equal(saoPauloYesterday(NOON_SP), '2026-10-22');
  assert.equal(saoPauloYesterday(LATE_NIGHT_SP), '2025-12-30');
});

test('days ago walks back across month and year boundaries', () => {
  assert.equal(saoPauloDaysAgo(0, NOON_SP), '2026-10-23');
  assert.equal(saoPauloDaysAgo(30, NOON_SP), '2026-09-23');
  assert.equal(saoPauloDaysAgo(1, LATE_NIGHT_SP), '2025-12-30');
  assert.equal(saoPauloDaysAgo(32, LATE_NIGHT_SP), '2025-11-29');
});

test('days ago keeps the leap day instead of sliding to March', () => {
  assert.equal(saoPauloDaysAgo(1, new Date('2028-03-01T15:00:00Z')), '2028-02-29');
  assert.equal(saoPauloDaysAgo(30, new Date('2028-03-01T15:00:00Z')), '2028-01-31');
});

test('the three helpers agree with each other and with today', () => {
  assert.equal(saoPauloYesterday(NOON_SP), saoPauloDaysAgo(1, NOON_SP));
  assert.equal(saoPauloToday(NOON_SP), saoPauloDaysAgo(0, NOON_SP));
});

test('days ago refuses a value that is not a whole past day', () => {
  assert.throws(() => saoPauloDaysAgo(-1, NOON_SP), RangeError);
  assert.throws(() => saoPauloDaysAgo(1.5, NOON_SP), RangeError);
});

// El trigger del campo de fecha muestra el día en un formato corto y propio, porque el texto que WebKit
// dibuja dentro de un <input type="date"> nativo lo decide el locale del sistema y no se puede formatear.
test('the date label is day/month/year with both parts padded', () => {
  assert.equal(formatDateLabel('2026-10-28'), '28/10/2026');
  assert.equal(formatDateLabel('2026-01-05'), '05/01/2026');
});

test('the date label never invents a day when the value is empty or malformed', () => {
  assert.equal(formatDateLabel(''), '');
  assert.equal(formatDateLabel('2026-10'), '2026-10');
  assert.equal(formatDateLabel('not-a-date'), 'not-a-date');
});
