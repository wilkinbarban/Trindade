import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { lastSyncLabel } from './aero-mirror';

/**
 * The header's "last sync" line.
 *
 * It is the only thing on the screen that tells the operator whether the day he is looking at came back from
 * Delphi or is a stale read, so the two answers that matter are: a real clock time in the plant's own zone,
 * and an honest dash when nothing has ever arrived.
 */
describe('lastSyncLabel', () => {
  it('has nothing to show before the first sync ever arrived', () => {
    assert.equal(lastSyncLabel(null), '—');
    assert.equal(lastSyncLabel(''), '—');
    assert.equal(lastSyncLabel('not-a-date'), '—');
  });

  it('shows the plant clock, not the server clock', () => {
    // 12:34 UTC is 09:34 in São Paulo, where the factory reads it.
    assert.equal(lastSyncLabel('2026-06-12T12:34:00.000Z', 'America/Sao_Paulo'), '09:34');
    assert.equal(lastSyncLabel('2026-06-12T12:34:00.000Z', 'UTC'), '12:34');
  });
});
