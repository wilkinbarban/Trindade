import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  AERO_PENDING_TEST_STORAGE_KEY,
  aeroTestNoVerdictEvent,
  aeroTestRequestedEvent,
  aeroTestResultEvent,
  aeroTestVerdictFromToken,
  clearAeroPendingTest,
  loadAeroPendingTest,
  saveAeroPendingTest,
  type AeroPendingTest,
} from './aero-test';
import type { StorageLike } from './aero-integration';

class FakeStorage implements StorageLike {
  private store = new Map<string, string>();

  getItem(key: string): string | null {
    return this.store.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.store.set(key, value);
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }
}

const DESTINATION = 'http://192.168.25.250:1453/api/usuarios/';

describe('aero-test: verdict from the shortcut token', () => {
  it('maps every rendering Shortcuts can produce for a boolean', () => {
    for (const token of ['true', 'ok', '1', 'sim', 'yes', 'TRUE']) {
      assert.equal(aeroTestVerdictFromToken(token), 'ok');
    }
    for (const token of ['false', 'error', '0', 'nao', 'no', 'FALSE']) {
      assert.equal(aeroTestVerdictFromToken(token), 'error');
    }
  });

  it('refuses to invent a verdict out of nothing', () => {
    for (const token of ['', '   ', null, undefined, 'talvez']) {
      assert.equal(aeroTestVerdictFromToken(token), 'unknown');
    }
  });
});

describe('aero-test: pending test storage', () => {
  const pending: AeroPendingTest = {
    destinationUrl: DESTINATION,
    startedAt: '2026-10-28T18:00:00.000Z',
    reason: 'mixed_content',
  };

  it('round-trips and clears the attempt that went to the phone', () => {
    const storage = new FakeStorage();
    saveAeroPendingTest(pending, storage);
    assert.deepEqual(loadAeroPendingTest(storage), pending);

    clearAeroPendingTest(storage);
    assert.equal(loadAeroPendingTest(storage), null);
  });

  it('tolerates a corrupt or foreign pending test', () => {
    const storage = new FakeStorage();
    storage.setItem(AERO_PENDING_TEST_STORAGE_KEY, '{ bad json');
    assert.equal(loadAeroPendingTest(storage), null);

    storage.setItem(AERO_PENDING_TEST_STORAGE_KEY, JSON.stringify({ destinationUrl: 42 }));
    assert.equal(loadAeroPendingTest(storage), null);
  });
});

describe('aero-test: audit lines', () => {
  it('writes the attempt before the phone leaves, naming the reason', () => {
    assert.deepEqual(aeroTestRequestedEvent({ destinationUrl: DESTINATION, reason: 'mixed_content' }), {
      event: 'test_connection',
      phase: 'requested',
      destinationUrl: DESTINATION,
      httpStatus: null,
      error: null,
      note: 'via=shortcut reason=mixed_content',
    });
  });

  it('writes the blocked-browser attempt as a browser one', () => {
    assert.equal(
      aeroTestRequestedEvent({ destinationUrl: DESTINATION, reason: null }).note,
      'via=browser',
    );
  });

  it('records the verdict the endpoint gave, keeping the raw token', () => {
    assert.deepEqual(aeroTestResultEvent({ verdict: 'ok', raw: 'true', destinationUrl: DESTINATION }), {
      event: 'test_connection',
      phase: 'result',
      destinationUrl: DESTINATION,
      httpStatus: null,
      response: null,
      error: null,
      note: 'via=shortcut aero_test=true',
    });

    assert.deepEqual(aeroTestResultEvent({ verdict: 'error', raw: 'false', destinationUrl: DESTINATION }), {
      event: 'test_connection',
      phase: 'result',
      destinationUrl: DESTINATION,
      httpStatus: null,
      response: null,
      error: 'shortcut:error',
      note: 'via=shortcut aero_test=false',
    });

    assert.equal(
      aeroTestResultEvent({ verdict: 'unknown', raw: '', destinationUrl: DESTINATION }).error,
      'shortcut:unknown',
    );
  });

  it('records an operator who came back without any verdict', () => {
    assert.deepEqual(aeroTestNoVerdictEvent({ destinationUrl: DESTINATION }), {
      event: 'test_connection',
      phase: 'result',
      destinationUrl: DESTINATION,
      httpStatus: null,
      error: 'shortcut:no_verdict',
      note: 'the operator returned without a shortcut result',
    });
  });
});
