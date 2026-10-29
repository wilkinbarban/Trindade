import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { Dialog } from '../../components/ui/dialog';
import { aeroBridgeShortcutRecipe, type AeroSettings } from '../../lib/aero-integration';

interface AeroShortcutDialogProps {
  open: boolean;
  onClose: () => void;
  settings: AeroSettings;
}

type CopyKey = 'url' | 'headers' | 'authorization' | 'body' | 'bodies' | 'callback' | 'sample';

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
 * What the operator copies here is a dictionary key -- `url`, `headers`, `Authorization`, `body`, `bodies`,
 * `callback` -- which has to be typed exactly, on a phone, in the middle of a recipe. Selecting a short word by
 * hand on iOS is a long press, two handles and a scroll; the button is the whole point of the card. Declared
 * at module level so it keeps its identity across renders instead of remounting every time the parent
 * re-renders on a copy.
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
 * Safari cannot open an `http://` socket from an `https://` page, so every write has to leave the browser.
 * The shortcut that does it is built on the phone, by hand: a `.shortcut` file must be signed by the device
 * that installs it, and iOS refuses anything served from the web that is not an iCloud link.
 *
 * One bridge replaces the two shortcuts the factory used to need. It knows nothing about massas: it receives
 * `{ url, method, headers, body, callback }`, posts it, and opens the callback with the answer. When the
 * envelope also carries `bodies`, it repeats that POST once per element and brings every answer back at once.
 * That is what lets the same shortcut carry the entry, the stock exit, the movement query and the connection
 * test -- and what keeps the iPhone untouched when a credential or an address changes in the panel.
 *
 * Credentials travel inside the envelope on purpose ("opción B"): the price is two more dictionary reads on
 * the phone, and what it buys is a password the factory can rotate without anyone editing a shortcut. The body
 * travels as a JSON string, because a dictionary dropped into "Request Body: Text" is rendered as a property
 * list and the ERP would refuse it while the header still claimed JSON.
 */
export function AeroShortcutDialog({ open, onClose, settings }: AeroShortcutDialogProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState<CopyKey | null>(null);

  const recipe = useMemo(() => {
    const origin = typeof window === 'undefined' ? '' : window.location.origin;
    const basePath = (import.meta.env?.BASE_URL ?? '/').replace(/\/$/, '');
    return aeroBridgeShortcutRecipe({
      wsUrl: settings.wsUrl,
      username: settings.username,
      password: settings.password,
      origin,
      basePath,
    });
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
  const keyLabel = t('massas.aero.shortcutKeyLabel');
  const name = settings.shortcutName;

  const endpoints: { label: string; url: string }[] = [
    { label: t('massas.aero.shortcutEndpointEntrada'), url: recipe.endpoints.entrada },
    { label: t('massas.aero.shortcutEndpointSaida'), url: recipe.endpoints.saida },
    { label: t('massas.aero.shortcutEndpointMovimentos'), url: recipe.endpoints.movimentos },
    { label: t('massas.aero.shortcutEndpointTest'), url: recipe.endpoints.test },
  ];

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
          <h3 className="mb-1.5 text-sm font-bold text-gray-900">
            {t('massas.aero.shortcutLaunchTitle', { count: recipe.actionCount })}
          </h3>

          {/* El nombre es preparación en la app, no una de las diecisiete acciones: por eso no lleva número y
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
              <span>{t('massas.aero.shortcutStepInput')}</span>
            </StepItem>

            <StepItem n={2}>
              <span>{t('massas.aero.shortcutStepUrl')}</span>
              <RecipeValue
                id="url"
                label={keyLabel}
                value={recipe.keys.url}
                copyLabel={copyLabel}
                copied={copied === 'url'}
                onCopy={copy}
              />
            </StepItem>

            {/* Opción (a): el Authorization sale del diccionario `headers`, que es lo que el sobre trae.
                La receta anterior pedía una clave `auth` que nunca viajó. */}
            <StepItem n={3}>
              <span>{t('massas.aero.shortcutStepHeaders')}</span>
              <RecipeValue
                id="headers"
                label={keyLabel}
                value={recipe.keys.headers}
                copyLabel={copyLabel}
                copied={copied === 'headers'}
                onCopy={copy}
              />
            </StepItem>

            <StepItem n={4}>
              <span>{t('massas.aero.shortcutStepAuthorization')}</span>
              <RecipeValue
                id="authorization"
                label={keyLabel}
                value={recipe.keys.authorization}
                copyLabel={copyLabel}
                copied={copied === 'authorization'}
                onCopy={copy}
              />
            </StepItem>

            <StepItem n={5}>
              <span>{t('massas.aero.shortcutStepCallback')}</span>
              <RecipeValue
                id="callback"
                label={keyLabel}
                value={recipe.keys.callback}
                copyLabel={copyLabel}
                copied={copied === 'callback'}
                onCopy={copy}
              />
            </StepItem>

            <StepItem n={6}>
              <span>{t('massas.aero.shortcutStepBodiesCheck')}</span>
              <RecipeValue
                id="bodies"
                label={keyLabel}
                value={recipe.keys.bodies}
                copyLabel={copyLabel}
                copied={copied === 'bodies'}
                onCopy={copy}
              />
            </StepItem>

            <StepItem n={7}>
              <span>{t('massas.aero.shortcutStepRepeat')}</span>
            </StepItem>

            <StepItem n={8}>
              <span>{t('massas.aero.shortcutStepBatchFetch')}</span>
              <p className="mt-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
                {t('massas.aero.shortcutHeadersTitle')}
              </p>
              <p className="text-xs leading-relaxed text-gray-500">{t('massas.aero.shortcutHeadersHint')}</p>
            </StepItem>

            <StepItem n={9}>
              <span>{t('massas.aero.shortcutStepBatchCollect')}</span>
            </StepItem>

            <StepItem n={10}>
              <span>{t('massas.aero.shortcutStepRepeatEnd')}</span>
            </StepItem>

            <StepItem n={11}>
              <span>{t('massas.aero.shortcutStepJoin')}</span>
            </StepItem>

            <StepItem n={12}>
              <span>{t('massas.aero.shortcutStepBracket')}</span>
            </StepItem>

            <StepItem n={13}>
              <span>{t('massas.aero.shortcutStepBatchEncode')}</span>
            </StepItem>

            <StepItem n={14}>
              <span>{t('massas.aero.shortcutStepElse')}</span>
            </StepItem>

            <StepItem n={15}>
              <span>{t('massas.aero.shortcutStepSingleFetch')}</span>
              <RecipeValue
                id="body"
                label={keyLabel}
                value={recipe.keys.body}
                copyLabel={copyLabel}
                copied={copied === 'body'}
                onCopy={copy}
              />
            </StepItem>

            <StepItem n={16}>
              <span>{t('massas.aero.shortcutStepSingleEncode')}</span>
            </StepItem>

            <StepItem n={17}>
              <span>{t('massas.aero.shortcutStepBack')}</span>
            </StepItem>
          </ol>

          <p className="mt-3 rounded-lg bg-emerald-50 px-3 py-2 text-xs leading-relaxed text-emerald-900">
            {t('massas.aero.shortcutDone')}
          </p>

          {/* Lo que el mismo atajo alcanza: la razón por la que se instala una vez y no cuatro. */}
          <div className="mt-5 border-t border-gray-200 pt-4">
            <h3 className="text-sm font-bold text-gray-900">{t('massas.aero.shortcutEndpointsTitle')}</h3>
            <ul className="mt-2 space-y-1">
              {endpoints.map((endpoint) => (
                <li key={endpoint.url} className="text-xs leading-relaxed text-gray-600">
                  <span className="font-semibold text-gray-800">{endpoint.label}</span>
                  <code dir="ltr" className="mt-0.5 block break-all font-mono text-[11px] text-gray-500">
                    {endpoint.url}
                  </code>
                </li>
              ))}
            </ul>
          </div>

          {/* El sobre de la prueba de conexión, ya armado: es el único que no depende de un lanzamiento. */}
          <div className="mt-4 border-t border-gray-200 pt-4">
            <h3 className="text-sm font-bold text-gray-900">{t('massas.aero.shortcutSampleTitle')}</h3>
            <p className="mt-0.5 text-xs leading-relaxed text-gray-500">{t('massas.aero.shortcutSampleHint')}</p>
            <RecipeValue
              id="sample"
              label={t('massas.aero.shortcutSampleTitle')}
              value={JSON.stringify(recipe.sampleEnvelope, null, 2)}
              copyLabel={copyLabel}
              copied={copied === 'sample'}
              onCopy={copy}
            />
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
