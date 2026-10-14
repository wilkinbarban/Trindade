import assert from 'node:assert/strict';
import { describe, it, afterEach } from 'node:test';
import { shareOrCopy, waMeLink, waMeNumber } from './whatsapp';

/**
 * The WhatsApp actions, kept pure so the three screens that need them share one implementation.
 *
 * The link is the one WhatsApp documents for a one-on-one chat: `wa.me` takes the number in
 * international form with no punctuation and the text percent-encoded, which is what the mobile app's
 * intent and the web client both accept.
 */
describe('waMeNumber', () => {
  it('keeps only the digits, which is the form the link needs', () => {
    assert.equal(waMeNumber('+55 (11) 98888-7777'), '5511988887777');
    assert.equal(waMeNumber('5511988887777'), '5511988887777');
  });

  it('answers empty when there are no digits to dial', () => {
    assert.equal(waMeNumber(''), '');
    assert.equal(waMeNumber('ramal'), '');
  });
});

describe('waMeLink', () => {
  it('builds the chat link with the text percent-encoded', () => {
    assert.equal(
      waMeLink('+55 (11) 98888-7777', 'Cronograma\n04:00 — Ana'),
      'https://wa.me/5511988887777?text=Cronograma%0A04%3A00%20%E2%80%94%20Ana',
    );
  });

  it('answers null for a driver with no number, so no action is offered', () => {
    assert.equal(waMeLink('', 'qualquer texto'), null);
    assert.equal(waMeLink('   ', 'qualquer texto'), null);
  });
});

/** A stand-in for the browser globals the helper reaches for, restored after each case. */
const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');

function stubNavigator(value: unknown): void {
  Object.defineProperty(globalThis, 'navigator', { value, configurable: true, writable: true });
}

afterEach(() => {
  if (originalNavigator) Object.defineProperty(globalThis, 'navigator', originalNavigator);
  else delete (globalThis as { navigator?: unknown }).navigator;
});

/**
 * What the share button does, and why the outcomes are four and not two.
 *
 * A share sheet the operator dismissed is neither a success nor a failure, and copying the text behind
 * their back would be exactly the opposite of what they asked for: cancelling is its own answer.
 */
describe('shareOrCopy', () => {
  it('hands the text to the platform share sheet when there is one', async () => {
    let shared: { text?: string } | undefined;
    stubNavigator({ share: async (data: { text?: string }) => { shared = data; } });

    assert.equal(await shareOrCopy('hola'), 'shared');
    assert.deepEqual(shared, { text: 'hola' });
  });

  it('reports a cancelled share as cancelled, without copying behind the operator', async () => {
    let copied = false;
    stubNavigator({
      share: async () => { throw Object.assign(new Error('dismissed'), { name: 'AbortError' }); },
      clipboard: { writeText: async () => { copied = true; } },
    });

    assert.equal(await shareOrCopy('hola'), 'cancelled');
    assert.equal(copied, false, 'a cancelled share must not copy the text');
  });

  it('copies the text when the share sheet fails for any other reason', async () => {
    let copied: string | undefined;
    stubNavigator({
      share: async () => { throw new Error('no share target'); },
      clipboard: { writeText: async (text: string) => { copied = text; } },
    });

    assert.equal(await shareOrCopy('hola'), 'copied');
    assert.equal(copied, 'hola');
  });

  it('copies the text when the browser has no share sheet at all', async () => {
    let copied: string | undefined;
    stubNavigator({ clipboard: { writeText: async (text: string) => { copied = text; } } });

    assert.equal(await shareOrCopy('hola'), 'copied');
    assert.equal(copied, 'hola');
  });

  it('reports a failure when the browser can neither share nor copy', async () => {
    stubNavigator({});

    assert.equal(await shareOrCopy('hola'), 'failed');
  });
});
