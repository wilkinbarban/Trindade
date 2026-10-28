import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, Loader2, Plug, ShieldCheck } from 'lucide-react';
import { Card } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import {
  aeroEndpoint,
  aeroSettingsReady,
  clearAeroSettings,
  DEFAULT_AERO_SETTINGS,
  normalizeAeroWsUrl,
  saveAeroSettings,
  type AeroSettings,
} from '../../lib/aero-integration';
import { probeAeroConnection, type AeroConnectionProbe } from '../../lib/aero-connection';
import { postAeroAuditEvent } from '../../lib/aero-audit';
import { AeroShortcutDialog } from './AeroShortcutDialog';

interface AeroIntegrationPanelProps {
  settings: AeroSettings;
  onSettingsSaved: (settings: AeroSettings) => void;
}

type Notice = { tone: 'ok' | 'warn' | 'error'; text: string };

/**
 * The company WebService settings, kept on the phone and shown to nobody else.
 *
 * Collapsed into one row that states whether the integration is ready, because this is the module an
 * operator opens with wet hands: the configuration is a one-time errand and the day view must not pay
 * for it. It opens by itself only when there is nothing configured yet, which is the state that needs
 * a person.
 */
export function AeroIntegrationPanel({ settings, onSettingsSaved }: AeroIntegrationPanelProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(() => !aeroSettingsReady(settings));
  const [draft, setDraft] = useState({
    wsUrl: settings.wsUrl,
    username: settings.username,
    password: settings.password,
    userCode: settings.userCode === null ? '' : String(settings.userCode),
  });
  const [busy, setBusy] = useState<'save' | 'test' | null>(null);
  const [probe, setProbe] = useState<AeroConnectionProbe | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [shortcutOpen, setShortcutOpen] = useState(false);

  const ready = aeroSettingsReady(settings);

  const normalizedUrl = useMemo(() => normalizeAeroWsUrl(draft.wsUrl), [draft.wsUrl]);
  const destination = useMemo(
    () => (normalizedUrl ? aeroEndpoint(normalizedUrl, 'usuarios/') : draft.wsUrl.trim()),
    [normalizedUrl, draft.wsUrl],
  );

  const persist = (next: AeroSettings) => {
    saveAeroSettings(next);
    onSettingsSaved(next);
  };

  /**
   * Reads the optional code box.
   *
   * Empty is a legitimate answer, not an error: over HTTPS the probe cannot read the code, so most phones will
   * never have one. Anything that is not a positive integer is treated as empty rather than refused, because
   * the field is a convenience and must never be the reason a launch cannot be configured.
   */
  const draftUserCode = (): number | null => {
    const parsed = Number.parseInt(draft.userCode, 10);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
  };

  const handleSave = () => {
    setNotice(null);
    setProbe(null);
    if (!normalizedUrl || !draft.username.trim() || !draft.password) {
      setNotice({ tone: 'error', text: t('massas.aero.invalid') });
      return;
    }

    persist({
      ...settings,
      wsUrl: normalizedUrl,
      username: draft.username.trim(),
      password: draft.password,
      userCode: draftUserCode(),
    });
    setDraft((current) => ({ ...current, wsUrl: normalizedUrl, username: draft.username.trim() }));
    setNotice({ tone: 'ok', text: t('massas.aero.saved') });
  };

  const handleTest = async () => {
    setNotice(null);
    setBusy('test');
    setProbe(null);

    const outcome = await probeAeroConnection({
      settings: { wsUrl: draft.wsUrl, username: draft.username, password: draft.password },
      pageProtocol: typeof window === 'undefined' ? 'http:' : window.location.protocol,
    });

    setProbe(outcome);

    // The audit line is written whether the browser succeeded or hit a wall: the factory test is
    // inspected afterwards from a terminal, and an attempt that never left the phone is the fact that
    // explains the rest.
    await postAeroAuditEvent(probeToAuditEvent(outcome, destination));

    if (outcome.kind === 'ok') {
      persist({
        ...settings,
        wsUrl: normalizeAeroWsUrl(draft.wsUrl) ?? settings.wsUrl,
        username: draft.username.trim(),
        password: draft.password,
        userCode: outcome.userCode,
      });
      setNotice({ tone: 'ok', text: t('massas.aero.testOk', { name: outcome.userName, code: outcome.userCode }) });
    }

    setBusy(null);
  };

  const handleClear = () => {
    if (!window.confirm(t('massas.aero.clearConfirm'))) return;
    clearAeroSettings();
    setDraft({ wsUrl: '', username: '', password: '', userCode: '' });
    setProbe(null);
    setNotice({ tone: 'warn', text: t('massas.aero.cleared') });
    onSettingsSaved(DEFAULT_AERO_SETTINGS);
  };

  const probeMessage = probe ? describeProbe(probe, t) : null;

  return (
    <Card className="border-gray-200 shadow-sm overflow-hidden">
      {/* En un teléfono el título no entra junto a la insignia: se cortaba en "Configuración I..." y
          el panel dejaba de decir qué era. Debajo de `sm` el título ocupa su renglón, la insignia baja
          alineada con el texto y solo el chevron comparte fila. */}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-controls="aero-integration-body"
        className="flex w-full min-h-[56px] flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3 text-left hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-500"
      >
        <span className="flex min-w-0 flex-1 items-center gap-2">
          <Plug className="h-4 w-4 shrink-0 text-gray-500" />
          <span className="min-w-0">
            <span className="block text-sm font-semibold leading-tight text-gray-900 sm:truncate">
              {t('massas.aero.panelTitle')}
            </span>
            <span className="hidden sm:block text-xs text-gray-500 truncate">{t('massas.aero.panelHint')}</span>
          </span>
        </span>
        <span className="order-last flex w-full shrink-0 items-center gap-2 pl-6 sm:order-none sm:w-auto sm:pl-0">
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${
              ready ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-500'
            }`}
          >
            {ready && <ShieldCheck className="h-3 w-3" />}
            {ready ? t('massas.aero.configured') : t('massas.aero.notConfigured')}
          </span>
          <ChevronDown
            className={`h-4 w-4 shrink-0 text-gray-500 transition-transform sm:ml-0 ${open ? 'rotate-180' : ''} max-sm:ml-auto`}
          />
        </span>
      </button>

      {open && (
        <div id="aero-integration-body" className="border-t border-gray-100 px-4 pb-4 pt-4 space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="aero-ws-url" className="block text-xs font-semibold text-gray-600">
              {t('massas.aero.urlLabel')}
            </label>
            <Input
              id="aero-ws-url"
              type="url"
              inputMode="url"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              placeholder={t('massas.aero.urlPlaceholder')}
              value={draft.wsUrl}
              onChange={(event) => setDraft((current) => ({ ...current, wsUrl: event.target.value }))}
              className="h-11 text-base"
              data-testid="aero-ws-url"
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label htmlFor="aero-ws-user" className="block text-xs font-semibold text-gray-600">
                {t('massas.aero.userLabel')}
              </label>
              <Input
                id="aero-ws-user"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                autoComplete="off"
                value={draft.username}
                onChange={(event) => setDraft((current) => ({ ...current, username: event.target.value }))}
                className="h-11 text-base"
                data-testid="aero-ws-user"
              />
            </div>
            <div className="space-y-1.5">
              <label htmlFor="aero-ws-pass" className="block text-xs font-semibold text-gray-600">
                {t('massas.aero.passwordLabel')}
              </label>
              <Input
                id="aero-ws-pass"
                type="password"
                autoComplete="off"
                value={draft.password}
                onChange={(event) => setDraft((current) => ({ ...current, password: event.target.value }))}
                className="h-11 text-base"
                data-testid="aero-ws-pass"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <label htmlFor="aero-user-code" className="block text-xs font-semibold text-gray-600">
              {t('massas.aero.userCodeLabel')}
            </label>
            <Input
              id="aero-user-code"
              type="number"
              inputMode="numeric"
              min={1}
              placeholder="1"
              value={draft.userCode}
              onChange={(event) => setDraft((current) => ({ ...current, userCode: event.target.value }))}
              className="h-11 text-base sm:max-w-[12rem]"
              data-testid="aero-user-code"
            />
            <p className="text-xs text-gray-500 leading-relaxed">{t('massas.aero.userCodeHint')}</p>
          </div>

          <p className="text-xs text-gray-500 leading-relaxed">{t('massas.aero.fieldsHint')}</p>

          {probeMessage && (
            <p
              role="status"
              className={`text-xs sm:text-sm rounded-lg border px-3 py-2 leading-relaxed ${
                probe?.kind === 'ok'
                  ? 'bg-green-50 border-green-200 text-green-800'
                  : 'bg-red-50 border-red-200 text-red-800'
              }`}
            >
              {probeMessage}
            </p>
          )}

          {notice && (
            <p
              role="status"
              className={`text-xs sm:text-sm rounded-lg border px-3 py-2 ${
                notice.tone === 'ok'
                  ? 'bg-green-50 border-green-200 text-green-800'
                  : notice.tone === 'warn'
                    ? 'bg-amber-50 border-amber-200 text-amber-800'
                    : 'bg-red-50 border-red-200 text-red-800'
              }`}
            >
              {notice.text}
            </p>
          )}

          <div className="grid grid-cols-1 sm:flex sm:flex-wrap gap-2">
            <Button
              type="button"
              onClick={handleTest}
              disabled={busy !== null}
              variant="outline"
              className="min-h-[44px] w-full sm:w-auto font-semibold"
              data-testid="aero-test"
            >
              {busy === 'test' ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
              {busy === 'test' ? t('massas.aero.testing') : t('massas.aero.test')}
            </Button>
            <Button
              type="button"
              onClick={handleSave}
              disabled={busy !== null}
              className="min-h-[44px] w-full sm:w-auto bg-amber-600 hover:bg-amber-700 text-white font-semibold"
              data-testid="aero-save"
            >
              {t('massas.aero.save')}
            </Button>
            <Button
              type="button"
              onClick={() => setShortcutOpen(true)}
              variant="outline"
              className="min-h-[44px] w-full sm:w-auto font-semibold"
              data-testid="aero-shortcut-open"
            >
              {t('massas.aero.shortcut')}
            </Button>
            <Button
              type="button"
              onClick={handleClear}
              disabled={busy !== null}
              variant="ghost"
              className="min-h-[44px] w-full sm:w-auto sm:ml-auto text-red-600 hover:bg-red-50 font-semibold"
            >
              {t('massas.aero.clear')}
            </Button>
          </div>
        </div>
      )}

      <AeroShortcutDialog open={shortcutOpen} onClose={() => setShortcutOpen(false)} settings={settings} />
    </Card>
  );
}

/** What the panel says about a probe, in the operator's words rather than the browser's. */
function describeProbe(probe: AeroConnectionProbe, t: (key: string, options?: Record<string, unknown>) => string): string {
  switch (probe.kind) {
    case 'ok':
      return t('massas.aero.testOk', { name: probe.userName, code: probe.userCode });
    case 'http_error':
      return t('massas.aero.testHttpError', { status: probe.httpStatus, message: probe.message });
    case 'reachable_without_cors':
      return t('massas.aero.testNoCors');
    case 'blocked':
      return probe.reason === 'mixed_content' ? t('massas.aero.testBlockedMixed') : t('massas.aero.testBlockedScheme');
    case 'invalid_config':
      return t('massas.aero.testInvalid');
    case 'timeout':
      return t('massas.aero.testTimeout', { seconds: 8 });
    case 'network':
    default:
      return t('massas.aero.testNetwork');
  }
}

function probeToAuditEvent(probe: AeroConnectionProbe, destinationUrl: string) {
  switch (probe.kind) {
    case 'ok':
      return {
        event: 'test_connection' as const,
        phase: 'result' as const,
        destinationUrl,
        httpStatus: probe.httpStatus,
        response: probe.snippet,
        note: `userCode=${probe.userCode}`,
      };
    case 'http_error':
      return {
        event: 'test_connection' as const,
        phase: 'result' as const,
        destinationUrl,
        httpStatus: probe.httpStatus,
        response: probe.snippet,
        error: probe.message,
      };
    case 'reachable_without_cors':
      return {
        event: 'test_connection' as const,
        phase: 'result' as const,
        destinationUrl,
        httpStatus: null,
        error: 'reachable_without_cors',
      };
    case 'blocked':
      return {
        event: 'test_connection' as const,
        phase: 'requested' as const,
        destinationUrl,
        httpStatus: null,
        error: `blocked:${probe.reason}`,
      };
    case 'invalid_config':
      return {
        event: 'test_connection' as const,
        phase: 'requested' as const,
        destinationUrl,
        httpStatus: null,
        error: 'invalid_config',
      };
    case 'timeout':
      return {
        event: 'test_connection' as const,
        phase: 'result' as const,
        destinationUrl,
        httpStatus: null,
        error: 'timeout',
      };
    case 'network':
    default:
      return {
        event: 'test_connection' as const,
        phase: 'result' as const,
        destinationUrl,
        httpStatus: null,
        error: probe.kind === 'network' ? probe.message : 'unknown',
      };
  }
}
