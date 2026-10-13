import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextFocusIndex } from './dialog.js';

test('forward move advances and wraps from the last element to the first', () => {
  assert.equal(nextFocusIndex(0, 3, 1), 1);
  assert.equal(nextFocusIndex(1, 3, 1), 2);
  assert.equal(nextFocusIndex(2, 3, 1), 0);
});

test('backward move retreats and wraps from the first element to the last', () => {
  assert.equal(nextFocusIndex(2, 3, -1), 1);
  assert.equal(nextFocusIndex(1, 3, -1), 0);
  assert.equal(nextFocusIndex(0, 3, -1), 2);
});

test('a single focusable element stays on itself in both directions', () => {
  assert.equal(nextFocusIndex(0, 1, 1), 0);
  assert.equal(nextFocusIndex(0, 1, -1), 0);
});

test('no current element enters at the matching end and an empty dialog yields no index', () => {
  assert.equal(nextFocusIndex(-1, 3, 1), 0);
  assert.equal(nextFocusIndex(-1, 3, -1), 2);
  assert.equal(nextFocusIndex(0, 0, 1), -1);
  assert.equal(nextFocusIndex(0, 0, -1), -1);
});

test('an out-of-range current index is treated as the matching end', () => {
  assert.equal(nextFocusIndex(5, 3, 1), 0);
  assert.equal(nextFocusIndex(9, 3, -1), 2);
});
