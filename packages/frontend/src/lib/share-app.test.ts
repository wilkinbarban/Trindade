import { describe, it } from 'node:test';
import assert from 'node:assert';
import {
  formatPhoneMask,
  toWhatsAppNumber,
  buildShareMessage,
  type SharePlatform,
} from './share-app.js';

describe('formatPhoneMask', () => {
  it('formats 11 digits into (XX) 9XXXX-XXXX', () => {
    assert.strictEqual(formatPhoneMask('11988887777'), '(11) 98888-7777');
  });

  it('formats partial input progressively', () => {
    assert.strictEqual(formatPhoneMask('1'), '(1');
    assert.strictEqual(formatPhoneMask('11'), '(11) ');
    assert.strictEqual(formatPhoneMask('119'), '(11) 9');
    assert.strictEqual(formatPhoneMask('119888'), '(11) 9888');
    assert.strictEqual(formatPhoneMask('1198888'), '(11) 98888');
    assert.strictEqual(formatPhoneMask('11988887777'), '(11) 98888-7777');
  });

  it('strips non-digits from input before formatting', () => {
    assert.strictEqual(formatPhoneMask('+55 (11) 98888-7777'), '(11) 98888-7777');
  });
});

describe('toWhatsAppNumber', () => {
  it('prepends 55 to an 11-digit Brazilian number if missing', () => {
    assert.strictEqual(toWhatsAppNumber('(11) 98888-7777'), '5511988887777');
  });

  it('keeps 55 if already present', () => {
    assert.strictEqual(toWhatsAppNumber('+55 11 98888-7777'), '5511988887777');
  });

  it('returns empty string if no digits are provided', () => {
    assert.strictEqual(toWhatsAppNumber(''), '');
  });
});

describe('buildShareMessage', () => {
  const release = {
    version: 'v0.5.0',
    apkUrl: 'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk',
    webUrl: 'https://trindademasas.duckdns.org',
  };

  it('builds Android-only message without credentials', () => {
    const msg = buildShareMessage({
      platform: 'android',
      release,
      credentials: { enabled: false },
    });

    assert.ok(msg.includes('Android'));
    assert.ok(msg.includes(release.apkUrl));
    assert.ok(!msg.includes(release.webUrl));
    assert.ok(!msg.includes('Credenciais'));
  });

  it('builds iPhone/Web message without credentials', () => {
    const msg = buildShareMessage({
      platform: 'ios',
      release,
      credentials: { enabled: false },
    });

    assert.ok(!msg.includes('Android'));
    assert.ok(!msg.includes(release.apkUrl));
    assert.ok(msg.includes(release.webUrl));
  });

  it('builds Both (hybrid) message with credentials and personalized greeting', () => {
    const msg = buildShareMessage({
      platform: 'both',
      release,
      credentials: {
        enabled: true,
        displayName: 'João Silva',
        username: 'joao.silva',
        password: 'customPassword123',
      },
    });

    assert.ok(msg.includes('João Silva'));
    assert.ok(msg.includes(release.apkUrl));
    assert.ok(msg.includes(release.webUrl));
    assert.ok(msg.includes('joao.silva'));
    assert.ok(msg.includes('customPassword123'));
  });
});
