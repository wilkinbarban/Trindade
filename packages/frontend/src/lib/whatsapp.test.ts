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
      'https://api.whatsapp.com/send?phone=5511988887777&text=Cronograma%0A04%3A00%20%E2%80%94%20Ana',
    );
  });

  it('names the endpoint that answers without redirecting, which is what keeps an emoji whole', () => {
    // `wa.me` answers with a 302 to `api.whatsapp.com/send`, and that hop is where a four-byte character
    // comes back as U+FFFD. The schedule's announcement carries 🚛 and 🕟, so the link names the endpoint
    // that answers directly instead of the one that redirects to it: same payload, one hop fewer, no
    // chance for the relay to mangle it.
    const link = waMeLink('+55 (11) 98888-7777', '🚛 Cronograma\n🕟 04:00');

    assert.equal(
      link,
      'https://api.whatsapp.com/send?phone=5511988887777&text=%F0%9F%9A%9B%20Cronograma%0A%F0%9F%95%9F%2004%3A00',
    );
    assert.ok(link?.startsWith('https://api.whatsapp.com/send?phone=5511988887777&text='));
    assert.ok(!link?.includes('wa.me'), 'the redirecting host must not be what the browser opens');
    // Percent-encoded UTF-8 is what travels: four bytes for 🚛, and none of them replaced.
    assert.ok(link?.includes('%F0%9F%9A%9B') && !link.includes('%EF%BF%BD'));
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
