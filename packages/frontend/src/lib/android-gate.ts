import type { Device } from './device-detection';

/**
 * Whether to show an Android visitor the download screen instead of the web app.
 *
 * The native app is the only way an Android phone reaches the ERP directly, so a phone that lands on the web
 * is a phone that cannot dispatch a launch. The download screen says so and offers the APK.
 *
 * The gate is not a wall. A stored choice, written when somebody taps "continue in the browser", keeps the
 * web reachable: the native app does not cover the admin panel yet, and an administrator with an Android
 * phone needs that panel. Without this escape the rule would quietly remove access for the people who
 * configure the integration.
 */

/** Where the choice is remembered. Namespaced so a shared origin cannot collide with another app's flag. */
export const ANDROID_BYPASS_KEY = 'trindade.android.bypass';

export type AndroidGateDecision = 'showDownload' | 'renderWeb';

export function androidGateDecision(device: Device, bypassed: boolean): AndroidGateDecision {
  if (device.family !== 'android') return 'renderWeb';
  return bypassed ? 'renderWeb' : 'showDownload';
}

/**
 * The APK the share module serves.
 *
 * Built from the app's base path rather than written as an absolute URL: the same bundle is served under a
 * different prefix in development, and a hardcoded host would send a phone to production from a laptop.
 */
export function apkDownloadUrl(env: { BASE_URL?: string } = import.meta.env ?? {}): string {
  const base = (env.BASE_URL ?? '/').replace(/\/$/, '');
  return `${base}/api/app/download-apk`;
}

/** Reads the stored choice, tolerating a browser that refuses storage (private mode, blocked cookies). */
export function readAndroidBypass(): boolean {
  try {
    return window.localStorage.getItem(ANDROID_BYPASS_KEY) === '1';
  } catch {
    return false;
  }
}

export function rememberAndroidBypass(): void {
  try {
    window.localStorage.setItem(ANDROID_BYPASS_KEY, '1');
  } catch {
    // The choice is not remembered, and the screen comes back next time. That is a worse experience than
    // the alternative of crashing on a device whose storage the browser has locked.
  }
}
