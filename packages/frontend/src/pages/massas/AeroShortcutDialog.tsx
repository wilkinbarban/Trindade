import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { Dialog } from '../../components/ui/dialog';
import {
  aeroCallbackUrl,
  aeroShortcutRecipe,
  aeroTestCallbackPrefix,
  aeroTestShortcutRecipe,
  type AeroSettings,
} from '../../lib/aero-integration';

interface AeroShortcutDialogProps {
  open: boolean;
  onClose: () => void;
  settings: AeroSettings;
}

type CopyKey =
  | 'endpoint'
  | 'authorization'
  | 'callbackUrl'
  | 'testEndpoint'
  | 'testAuthorization'
  | 'testCallback';

interface RecipeValueProps {
  id: CopyKey;
  label: string;
  value: string;
  copyLabel: string;
  copied: boolean;
  onCopy: (id: CopyKey, value: string) => void;
}

/**
 * One value with its own copy button.
 *
 * A base64 header is the part a person gets wrong when copying by hand: forty characters on a phone
 * screen, broken across two lines by the panel itself. Selecting it by hand on iOS is a long press, two
 * handles and a scroll; the button is the whole point of the card. Declared at module level so it keeps
 * its identity across renders instead of remounting every time the parent re-renders on a copy.
 */
function RecipeValue({ id, label, value, copyLabel, copied, onCopy }: RecipeValueProps) {
  return (
    <div className="mt-2 rounded-lg border border-gray-200 bg-gray-50 p-2">
      <span className="block text-[11px] font-semibold uppercase tracking-wide text-gray-500">{label}</span>
      <div className="mt-0.5 flex items-start gap-2">
        <code dir="ltr" className="min-w-0 flex-1 break-all font-mono text-[11px] leading-snug text-gray-800">
          {value}
        </code>
        <Button
          type="button"
          variant="outline"
          onClick={() => onCopy(id, value)}
          aria-label={`${copyLabel}: ${label}`}
          data-testid={`aero-shortcut-copy-${id}`}
          className="h-11 w-11 shrink-0 p-0 sm:h-9 sm:w-9"
        >
          {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
}

function StepItem({ n, children }: { n: number; children: ReactNode }) {
  return (
    <li className="flex gap-2.5 text-sm leading-relaxed text-gray-700">
      <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 text-[11px] font-bold text-amber-800">
        {n}
      </span>
      <div className="min-w-0 flex-1 break-words">{children}</div>
    </li>
  );
}

/**
 * Where the page hands the request to a native process.
 *
 * Safari cannot open an `http://` socket from an `https://` page, so the entry has to leave the browser.
 * The shortcut that does it is built on the phone, by hand: a `.shortcut` file must be signed by the
 * device that installs it, and iOS refuses anything served from the web that is not an iCloud link.
 *
 * It used to be described as seven steps -- dictionary, three keys, then the POST, then the response.
 * Nobody holds all of that in their head while standing in a factory with a phone in one hand, and the
 * shortcut was never built. So the recipe is now two native actions, and each value is presented inside
 * the step that uses it, at the moment it is pasted, instead of in a list the reader has to keep track of.
 */
export function AeroShortcutDialog({ open, onClose, settings }: AeroShortcutDialogProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState<CopyKey | null>(null);

  const { recipe, testRecipe } = useMemo(() => {
    const origin = typeof window === 'undefined' ? '' : window.location.origin;
    const basePath = (import.meta.env?.BASE_URL ?? '/').replace(/\/$/, '');
    const credentials = {
      wsUrl: settings.wsUrl,
      username: settings.username,
      password: settings.password,
    };
    return {
      recipe: aeroShortcutRecipe({
        ...credentials,
        callbackUrl: aeroCallbackUrl({ origin, basePath, status: 'ok' }),
      }),
      testRecipe: aeroTestShortcutRecipe({
        ...credentials,
        callbackPrefix: aeroTestCallbackPrefix({ origin, basePath }),
      }),
    };
  }, [settings.wsUrl, settings.username, settings.password]);

  const copy = async (key: CopyKey, value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(key);
      window.setTimeout(() => setCopied(null), 2000);
    } catch {
      setCopied(null);
    }
  };

  const copyLabel = t('massas.aero.shortcutCopy');
  const name = settings.shortcutName;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      labelledBy="aero-shortcut-title"
      describedBy="aero-shortcut-intro"
      panelTestId="aero-shortcut-dialog"
    >
      {/* Un solo scroll: en un iPhone de 375px el panel se quedaba con las acciones cortadas abajo.
          El título y los dos botones se quedan quietos y el cuerpo se desplaza. */}
      <div className="flex min-h-0 w-full max-w-lg flex-1 flex-col gap-3 p-4 sm:p-6">
        <div className="shrink-0 space-y-1">
          <h2 id="aero-shortcut-title" className="text-lg font-bold text-gray-900">
            {t('massas.aero.shortcutTitle')}
          </h2>
          <p id="aero-shortcut-intro" className="text-xs leading-relaxed text-gray-500 sm:text-sm">
            {t('massas.aero.shortcutIntro')}
          </p>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto pr-1">
          <h3 className="mb-1.5 text-sm font-bold text-gray-900">{t('massas.aero.shortcutLaunchTitle')}</h3>

          {/* El nombre es preparación en la app, no una de las dos acciones: por eso no lleva número y
              la lista numera exactamente las acciones que hay que agregar. */}
          <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2">
            <div className="flex items-baseline gap-2">
              <span className="text-[11px] font-semibold uppercase tracking-wide text-amber-800">
                {t('massas.aero.shortcutName')}
              </span>
              <span className="break-all text-sm font-bold text-gray-900">{name}</span>
            </div>
            <p className="mt-0.5 text-xs leading-relaxed text-amber-900">{t('massas.aero.shortcutNameHint')}</p>
          </div>

          <ol className="space-y-3">
            <StepItem n={1}>
              <span>{t('massas.aero.shortcutStepFetch')}</span>
              <RecipeValue
                id="endpoint"
                label={t('massas.aero.shortcutEndpointLabel')}
                value={recipe.endpoint}
                copyLabel={copyLabel}
                copied={copied === 'endpoint'}
                onCopy={copy}
              />

              <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
                {t('massas.aero.shortcutHeadersTitle')}
              </p>
              <p className="text-xs leading-relaxed text-gray-500">{t('massas.aero.shortcutHeadersHint')}</p>
              <RecipeValue
                id="authorization"
                label={t('massas.aero.shortcutAuthLabel')}
                value={recipe.authorization}
                copyLabel={copyLabel}
                copied={copied === 'authorization'}
                onCopy={copy}
              />

              <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
                {t('massas.aero.shortcutBodyTitle')}
              </p>
              <p className="text-xs leading-relaxed text-gray-500">{t('massas.aero.shortcutBodyHint')}</p>
            </StepItem>

            <StepItem n={2}>
              <span>{t('massas.aero.shortcutStepBack')}</span>
              <RecipeValue
                id="callbackUrl"
                label={t('massas.aero.shortcutCallbackLabel')}
                value={recipe.callbackUrl}
                copyLabel={copyLabel}
                copied={copied === 'callbackUrl'}
                onCopy={copy}
              />
            </StepItem>
          </ol>

          <p className="mt-3 rounded-lg bg-emerald-50 px-3 py-2 text-xs leading-relaxed text-emerald-900">
            {t('massas.aero.shortcutDone')}
          </p>

          {/* El atajo de prueba: la razón por la que existe es la misma que obliga a todo lo demás a
              salir del navegador, y conviene decirla acá, donde se arma, y no en un error después. */}
          <div className="mt-5 border-t border-gray-200 pt-4">
            <h3 className="text-sm font-bold text-gray-900">{t('massas.aero.shortcutTestTitle')}</h3>
            <p className="mt-0.5 text-xs leading-relaxed text-gray-500">
              {t('massas.aero.shortcutTestIntro')}
            </p>

            <div className="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2">
              <div className="flex items-baseline gap-2">
                <span className="text-[11px] font-semibold uppercase tracking-wide text-amber-800">
                  {t('massas.aero.shortcutName')}
                </span>
                <span className="break-all text-sm font-bold text-gray-900">{settings.testShortcutName}</span>
              </div>
              <p className="mt-0.5 text-xs leading-relaxed text-amber-900">
                {t('massas.aero.shortcutTestNameHint')}
              </p>
            </div>

            <ol className="mt-3 space-y-3">
              <StepItem n={1}>
                <span>{t('massas.aero.shortcutTestStepFetch')}</span>
                <RecipeValue
                  id="testEndpoint"
                  label={t('massas.aero.shortcutEndpointLabel')}
                  value={testRecipe.endpoint}
                  copyLabel={copyLabel}
                  copied={copied === 'testEndpoint'}
                  onCopy={copy}
                />

                <p className="mt-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
                  {t('massas.aero.shortcutHeadersTitle')}
                </p>
                <p className="text-xs leading-relaxed text-gray-500">{t('massas.aero.shortcutHeadersHint')}</p>
                <RecipeValue
                  id="testAuthorization"
                  label={t('massas.aero.shortcutAuthLabel')}
                  value={testRecipe.authorization}
                  copyLabel={copyLabel}
                  copied={copied === 'testAuthorization'}
                  onCopy={copy}
                />
              </StepItem>

              <StepItem n={2}>
                <span>{t('massas.aero.shortcutTestStepDict')}</span>
              </StepItem>

              <StepItem n={3}>
                <span>{t('massas.aero.shortcutTestStepBack')}</span>
                <RecipeValue
                  id="testCallback"
                  label={t('massas.aero.shortcutCallbackLabel')}
                  value={testRecipe.callbackPrefix}
                  copyLabel={copyLabel}
                  copied={copied === 'testCallback'}
                  onCopy={copy}
                />
                <p className="mt-2 text-xs leading-relaxed text-gray-500">
                  {t('massas.aero.shortcutTestVariableHint')}
                </p>
              </StepItem>
            </ol>
          </div>

          <p role="status" aria-live="polite" className="mt-2 min-h-[1rem] text-[11px] font-medium text-emerald-700">
            {copied ? t('massas.aero.shortcutCopied') : ''}
          </p>
        </div>

        <div className="flex shrink-0 flex-col gap-2 sm:flex-row">
          <a
            href="shortcuts://"
            className="inline-flex min-h-[44px] w-full items-center justify-center rounded-lg bg-amber-600 px-4 text-sm font-semibold text-white hover:bg-amber-700 sm:flex-1"
          >
            {t('massas.aero.shortcutOpenApp')}
          </a>
          <Button type="button" variant="outline" onClick={onClose} className="min-h-[44px] w-full sm:w-auto">
            {t('massas.aero.close')}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
