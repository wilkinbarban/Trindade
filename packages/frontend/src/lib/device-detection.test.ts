import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { aeroSupportFor, detectDevice } from './device-detection';

/**
 * The device decides what the Aero32 integration can do, so this reads as a table of real devices rather
 * than as a parser test: each user agent below is one that reaches the plant's web app, and the pair of
 * answers it must produce is what every other decision in this feature is built on.
 */
describe('detectDevice', () => {
  it('reads an iPhone as iOS and keeps the shortcut path', () => {
    const device = detectDevice(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
    );
    assert.deepEqual(device, { family: 'ios', osName: 'iOS', isMobile: true });
  });

  it('reads an iPad as iOS even though its user agent says Macintosh', () => {
    const device = detectDevice(
      'Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
    );
    assert.equal(device.family, 'ios');
    assert.equal(device.osName, 'iOS');
  });

  it('reads a phone browser on Android as Android', () => {
    const device = detectDevice(
      'Mozilla/5.0 (Linux; Android 14; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    );
    assert.deepEqual(device, { family: 'android', osName: 'Android', isMobile: true });
  });

  it('names the desktop system, because the banner has to name it', () => {
    assert.deepEqual(
      detectDevice(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
      ),
      { family: 'desktop', osName: 'Windows', isMobile: false },
    );

    assert.deepEqual(
      detectDevice(
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
      ),
      { family: 'desktop', osName: 'macOS', isMobile: false },
    );

    assert.deepEqual(
      detectDevice(
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
      ),
      { family: 'desktop', osName: 'Linux', isMobile: false },
    );
  });

  it('treats a system it does not know as unknown, never as a desktop it can describe', () => {
    assert.deepEqual(detectDevice(''), { family: 'unknown', osName: '', isMobile: false });
    assert.deepEqual(detectDevice('curl/8.5.0'), { family: 'unknown', osName: '', isMobile: false });
  });

  it('is case insensitive, because user agents are not consistent about it', () => {
    assert.equal(detectDevice('mozilla/5.0 (iphone; cpu iphone os 18_1)').family, 'ios');
    assert.equal(detectDevice('mozilla/5.0 (linux; android 14)').family, 'android');
  });
});

describe('aeroSupportFor', () => {
  it('gives iOS the full integration over the Siri shortcuts', () => {
    assert.equal(aeroSupportFor({ family: 'ios', osName: 'iOS', isMobile: true }), 'full');
  });

  it('sends Android to the native app instead of the web controls', () => {
    assert.equal(aeroSupportFor({ family: 'android', osName: 'Android', isMobile: true }), 'nativeApp');
  });

  it('leaves a desktop with the integration in read-only mode', () => {
    assert.equal(aeroSupportFor({ family: 'desktop', osName: 'Windows', isMobile: false }), 'readOnly');
    assert.equal(aeroSupportFor({ family: 'desktop', osName: 'Linux', isMobile: false }), 'readOnly');
    assert.equal(aeroSupportFor({ family: 'desktop', osName: 'macOS', isMobile: false }), 'readOnly');
  });

  it('does not hand an unknown system controls it was never tested with', () => {
    assert.equal(aeroSupportFor({ family: 'unknown', osName: '', isMobile: false }), 'readOnly');
  });
});
