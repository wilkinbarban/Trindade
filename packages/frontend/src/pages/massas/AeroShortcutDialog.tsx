import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { Dialog } from '../../components/ui/dialog';
import { aeroCallbackUrl, type AeroSettings } from '../../lib/aero-integration';

interface AeroShortcutDialogProps {
  open: boolean;
  onClose: () => void;
  settings: AeroSettings;
}

/**
 * Where the page hands the request to a native process.
 *
 * Safari cannot open an `http://` socket from an `https://` page, so the entry has to leave the browser.
 * The shortcut that does it is built on the phone, by hand, because a `.shortcut` file must be signed by
 * the device that installs it -- a template the server assembles would arrive unsigned and fail at the
 * worst possible moment. What the page can do is make the by-hand part mechanical: the exact actions, the
 * exact keys, and the return URL already pointing at this deployment.
 */
export function AeroShortcutDialog({ open, onClose, settings }: AeroShortcutDialogProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  const callback = useMemo(() => {
    const origin = typeof window === 'undefined' ? '' : window.location.origin;
    const basePath = (import.meta.env?.BASE_URL ?? '/').replace(/\/$/, '');
    return aeroCallbackUrl({ origin, basePath, status: 'ok' });
  }, []);

  const steps = useMemo(
    () =>
      (
        t('massas.aero.shortcutSteps', {
          returnObjects: true,
          name: settings.shortcutName,
          callback,
          defaultValue: [],
        }) as unknown as string[]
      ).map((step) => String(step).replace(/\{\{callback\}\}/g, callback).replace(/\{\{name\}\}/g, settings.shortcutName)),
    [t, settings.shortcutName, callback],
  );

  const copyRecipe = async () => {
    const text = steps.map((step, index) => `${index + 1}. ${step}`).join('\n');
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} labelledBy="aero-shortcut-title" describedBy="aero-shortcut-intro" panelTestId="aero-shortcut-dialog">
      {/* Un solo scroll: la receta es larga y en un iPhone de 375px el panel se quedaba con las acciones
          cortadas abajo, sin forma de llegar a "Abrir Atajos" ni a "Cerrar" salvo tocando el fondo. El
          título y los botones se quedan quietos y solo la lista de pasos se desplaza. */}
      <div className="flex min-h-0 w-full max-w-lg flex-1 flex-col gap-4 p-5 sm:p-6">
        <div className="shrink-0 space-y-1">
          <h2 id="aero-shortcut-title" className="text-lg font-bold text-gray-900">
            {t('massas.aero.shortcutTitle')}
          </h2>
          <p id="aero-shortcut-intro" className="text-xs sm:text-sm text-gray-500 leading-relaxed">
            {t('massas.aero.shortcutIntro')}
          </p>
        </div>

        <div className="shrink-0 rounded-lg bg-gray-50 border border-gray-200 px-3 py-2">
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-gray-500">
            {t('massas.aero.shortcutName')}
          </span>
          <span className="text-sm font-bold text-gray-800 break-all">{settings.shortcutName}</span>
        </div>

        <ol className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
          {steps.map((step, index) => (
            <li key={step} className="flex gap-2.5 text-sm text-gray-700 leading-relaxed">
              <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-[11px] font-bold text-amber-800">
                {index + 1}
              </span>
              <span className="min-w-0 break-words">{step}</span>
            </li>
          ))}
        </ol>

        <div className="shrink-0 space-y-2">
          <div className="grid grid-cols-1 gap-2 sm:flex">
            <Button
              type="button"
              onClick={copyRecipe}
              className="w-full sm:flex-1 min-h-[44px] bg-amber-600 hover:bg-amber-700 text-white font-semibold"
            >
              {copied ? t('massas.aero.shortcutCopied') : t('massas.aero.shortcutCopy')}
            </Button>
            <a
              href="shortcuts://"
              className="inline-flex w-full sm:w-auto min-h-[44px] items-center justify-center rounded-lg border border-gray-300 bg-white px-4 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              {t('massas.aero.shortcutOpenApp')}
            </a>
          </div>

          <div className="flex justify-end">
            <Button type="button" variant="outline" onClick={onClose} className="min-h-[44px] w-full sm:w-auto">
              {t('massas.aero.close')}
            </Button>
          </div>
        </div>
      </div>
    </Dialog>
  );
}
