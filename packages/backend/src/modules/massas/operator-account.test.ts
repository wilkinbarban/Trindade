import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveOperatorUsername,
  operatorDisplayName,
  quickWorkerPassword,
} from './operator-account.js';

describe('operator account', () => {
  it('derives the login without the dot and without the surname initial', () => {
    // The plant writes `JOAO H`; the login is `joaoh`, exactly like the screen's preview says.
    assert.equal(deriveOperatorUsername('JOAO H'), 'joaoh');
    assert.equal(deriveOperatorUsername('JEFFERSON'), 'jefferson');
    assert.equal(deriveOperatorUsername('  Maria   da Silva '), 'mariadasilva');
    assert.equal(deriveOperatorUsername('JOSÉ'), 'jose');
  });

  it('gives every derived login a password of at least fifteen characters', () => {
    assert.equal(quickWorkerPassword(deriveOperatorUsername('JOAO H')), 'joaoh1234567890');
    assert.equal(quickWorkerPassword(deriveOperatorUsername('JEFFERSON')), 'jefferson123456'.slice(0, 15));
    assert.ok(quickWorkerPassword('a').length >= 15);
    assert.equal(quickWorkerPassword('umloginbemgrande'), 'umloginbemgrand');
  });

  it('presents the operator as a person, not as a shouted seat name', () => {
    assert.equal(operatorDisplayName('JOAO H'), 'Joao H');
    assert.equal(operatorDisplayName('  JEFFERSON  '), 'Jefferson');
  });
});
