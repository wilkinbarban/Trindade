import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { isWithinOneHour, projectHistoryPermissions } from './history-permissions.js';

const now = new Date('2026-08-24T12:00:00.000Z');

describe('history permissions', () => {
  it('allows only non-future timestamps inside the one-hour edit window', () => {
    const cases = [
      ['under one hour', '2026-08-24 11:00:00.001', true],
      ['exactly one hour', '2026-08-24 11:00:00.000Z', false],
      ['over one hour', '2026-08-24T10:59:59.999Z', false],
      ['future', '2026-08-24T12:00:00.001Z', false],
      ['invalid', 'not-a-timestamp', false],
      ['explicit timezone', '2026-08-24T08:30:00-03:00', true],
    ] as const;

    for (const [name, createdAt, expected] of cases) {
      assert.strictEqual(isWithinOneHour(createdAt, now), expected, name);
    }
  });

  it('fails closed for future history rows without changing delete permission', () => {
    const permissions = projectHistoryPermissions(
      { sub: 1, role: 'Trabalhador' },
      { user_id: 1, created_at: '2026-08-24T12:00:00.001Z', is_active: 1 }
    );

    assert.strictEqual(permissions.canEdit, false);
    assert.strictEqual(permissions.readOnly, true);
    assert.strictEqual(permissions.canDelete, true);
  });
});
