/**
 * What kind of machine is opening the plant's web app.
 *
 * The Aero32 integration is not equally possible everywhere: an iPhone reaches the ERP through a Siri
 * shortcut, an Android phone has a native app that talks to it directly, and a desktop browser on the
 * factory LAN could do neither -- the web app was never built to open a socket to the ERP, and the exploit
 * that would let it is exactly what the shortcuts exist to avoid. So the device is decided once, here, and
 * every screen that touches the integration asks this module instead of guessing.
 *
 * Detection is by user agent, which is the only thing the web knows before it renders. It is not a security
 * boundary and it is not trying to be one: it decides which affordances to show a person, and a person who
 * wants the other behaviour on the same device has an explicit way to ask (see `AndroidAppGate`).
 */

export type DeviceFamily = 'ios' | 'android' | 'desktop' | 'unknown';

export interface Device {
  family: DeviceFamily;
  /** The system as a person would say it. Empty when the family is unknown. */
  osName: string;
  isMobile: boolean;
}

/**
 * What the integration may offer on this device.
 *
 * - `full`: the shortcuts are the transport and every control works.
 * - `readOnly`: the integration is visible and disabled, with an explanation of which devices do work.
 * - `nativeApp`: the web has nothing to offer; the operator should be in the native app instead.
 */
export type AeroSupport = 'full' | 'readOnly' | 'nativeApp';

const IOS_PATTERN = /\b(iPhone|iPad|iPod)\b/i;
const ANDROID_PATTERN = /\bAndroid\b/i;

/** Ordered because Android reports `Linux` in its user agent: the phone has to win over the desktop. */
export function detectDevice(userAgent: string): Device {
  const agent = userAgent ?? '';

  if (IOS_PATTERN.test(agent)) {
    return { family: 'ios', osName: 'iOS', isMobile: true };
  }

  if (ANDROID_PATTERN.test(agent)) {
    return { family: 'android', osName: 'Android', isMobile: true };
  }

  const osName = desktopOsName(agent);
  if (osName) {
    return { family: 'desktop', osName, isMobile: false };
  }

  return { family: 'unknown', osName: '', isMobile: false };
}

/**
 * The desktop system's name, because the banner names it.
 *
 * "For Windows, Linux or macOS the direct connection was not built yet" is a sentence an operator can act
 * on; "your device is not supported" is one that leaves them asking which device would be.
 */
function desktopOsName(agent: string): string {
  if (/\bWindows\b/i.test(agent)) return 'Windows';
  if (/\bCrOS\b/i.test(agent)) return 'ChromeOS';
  if (/\bMacintosh\b|\bMac OS X\b/i.test(agent)) return 'macOS';
  if (/\bLinux\b|\bX11\b/i.test(agent)) return 'Linux';
  return '';
}

/** The support level for a device, with the honest answer for systems nobody tested. */
export function aeroSupportFor(device: Device): AeroSupport {
  switch (device.family) {
    case 'ios':
      return 'full';
    case 'android':
      return 'nativeApp';
    // A desktop and an unknown system get the same treatment on purpose: the integration is shown, and
    // shown disabled. Guessing that an unlisted system behaves like iOS is how a control that cannot work
    // ends up in front of an operator.
    case 'desktop':
    case 'unknown':
      return 'readOnly';
  }
}

/** Whether this device may move the plant's integration -- change its address, test it, or dispatch. */
export function canControlAero(device: Device): boolean {
  return aeroSupportFor(device) === 'full';
}

/**
 * The current device, read once.
 *
 * `navigator.userAgent` is absent in some non-browser environments (a test, a prerender); the empty string
 * then lands in `unknown`, which shows the integration disabled rather than pretending it works.
 */
export function currentDevice(): Device {
  if (typeof navigator === 'undefined') return detectDevice('');
  return detectDevice(navigator.userAgent);
}
