import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ANDROID_BYPASS_KEY, androidGateDecision, apkDownloadUrl } from './android-gate';
import type { Device } from './device-detection';

/**
 * The Android gate is the one decision in this feature that can lock somebody out of the app, so its rules
 * are tested as rules: what happens with no choice stored, with one stored, and on every device that is not
 * Android. The choice has to survive a reload, or the screen becomes a loop.
 */
const android: Device = { family: 'android', osName: 'Android', isMobile: true };
const ios: Device = { family: 'ios', osName: 'iOS', isMobile: true };
const desktop: Device = { family: 'desktop', osName: 'Windows', isMobile: false };
const unknown: Device = { family: 'unknown', osName: '', isMobile: false };

describe('androidGateDecision', () => {
  it('sends an Android phone to the download screen when it never chose otherwise', () => {
    assert.equal(androidGateDecision(android, false), 'showDownload');
  });

  it('lets an Android phone into the web once it asked to continue', () => {
    assert.equal(androidGateDecision(android, true), 'renderWeb');
  });

  it('never intercepts a device that is not Android', () => {
    assert.equal(androidGateDecision(ios, false), 'renderWeb');
    assert.equal(androidGateDecision(desktop, false), 'renderWeb');
    assert.equal(androidGateDecision(unknown, false), 'renderWeb');
  });

  it('keeps the choice under a key that names this app, so a shared origin cannot collide', () => {
    assert.equal(ANDROID_BYPASS_KEY, 'trindade.android.bypass');
  });
});

describe('apkDownloadUrl', () => {
  it('points at the share module route the backend already serves', () => {
    assert.equal(apkDownloadUrl({ BASE_URL: '/' }), '/api/app/download-apk');
  });

  it('respects a base path, because the app is served under one', () => {
    assert.equal(apkDownloadUrl({ BASE_URL: '/trindade/' }), '/trindade/api/app/download-apk');
  });
});
