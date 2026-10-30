import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { isClosedDay } from '../../lib/massas-day';

/**
 * A closed day is a record: no new boxes, no new row.
 *
 * The API refuses a `POST` on a past day, and the screen must not offer the gesture either. The launch
 * grid is the only place a box count can be typed, so closing the day there is what keeps the operator
 * from filling ten fields and learning about the rule from a `409`.
 */
const launchPage = readFileSync(new URL('../MassasLaunchPage.tsx', import.meta.url), 'utf8');
const grid = readFileSync(new URL('./MassasLaunchGrid.tsx', import.meta.url), 'utf8');

describe('a closed day offers no launch', () => {
  it('closes every day that is not today', () => {
    assert.equal(isClosedDay('2026-10-29', '2026-10-30'), true, 'yesterday is closed');
    assert.equal(isClosedDay('2026-09-01', '2026-10-30'), true, 'last month is closed');
    assert.equal(isClosedDay('2026-10-30', '2026-10-30'), false, 'today is the working day');
  });

  it('hands the grid the closed day, and the grid stops drawing the launch gesture', () => {
    assert.match(launchPage, /readOnly=\{dayIsClosed\}/, 'the page must close the grid on a closed day');
    assert.match(launchPage, /const dayIsClosed = isClosedDay\(date, saoPauloToday\(\)\)/);

    // The button and the quantity inputs are the gesture; a read-only grid draws neither.
    assert.match(grid, /readOnly\?: boolean/);
    assert.match(grid, /\{!readOnly && \([\s\S]*?onLaunch/, 'the launch button is behind the read-only guard');
    assert.match(grid, /\{readOnly \?[\s\S]*?:[\s\S]*?<Input/, 'the box input is replaced on a closed day');
  });

  it('refuses to ask the API for a launch on a closed day', () => {
    // Defence in depth: even a handler reached by keyboard or an old render returns before the POST.
    assert.match(launchPage, /if \(dayIsClosed\) return;/);
  });
});
