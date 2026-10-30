import { Info } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { Device } from '../../lib/device-detection';

/**
 * Why these controls are disabled, said in the operator's language and naming their system.
 *
 * The banner exists because a disabled button with no explanation is indistinguishable from a broken app. It
 * names the system the person is actually on ("no disponible en Windows"), names the two devices that do
 * work, and says the rest of the web is unaffected -- the three facts somebody standing at a desk needs
 * before they decide whether to call anyone.
 *
 * It renders for the systems where the integration is read-only and for nothing else: on iOS the controls
 * work, and a banner explaining that a feature works is noise.
 */
export function AeroDeviceBanner({ device }: { device: Device }) {
  const { t } = useTranslation();

  if (device.family === 'ios') return null;

  return (
    <div
      role="status"
      data-testid="aero-device-banner"
      className="flex items-start gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-amber-900"
      style={{ minHeight: '44px' }}
    >
      <Info className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
      <div className="space-y-1">
        <p className="text-sm font-medium">
          {t('aeroDevice.title', { os: device.osName || t('aeroDevice.unknownOs') })}
        </p>
        <p className="text-xs leading-relaxed">
          {t('aeroDevice.body', { os: device.osName || t('aeroDevice.unknownOs') })}
        </p>
      </div>
    </div>
  );
}
