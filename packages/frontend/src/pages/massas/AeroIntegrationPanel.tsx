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
  type AeroQuantityUnit,
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
    quantityUnit: settings.quantityUnit,
    entrySeries: settings.entrySeries,
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
      quantityUnit: draft.quantityUnit,
      entrySeries: draft.entrySeries.trim() || DEFAULT_AERO_SETTINGS.entrySeries,
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
        quantityUnit: draft.quantityUnit,
        entrySeries: draft.entrySeries.trim() || DEFAULT_AERO_SETTINGS.entrySeries,
        userCode: outcome.userCode,
      });
      setNotice({ tone: 'ok', text: t('massas.aero.testOk', { name: outcome.userName, code: outcome.userCode }) });
    }

    setBusy(null);
  };

  const handleClear = () => {
    if (!window.confirm(t('massas.aero.clearConfirm'))) return;
    clearAeroSettings();
    setDraft({ wsUrl: '', username: '', password: '', quantityUnit: 'UN', entrySeries: DEFAULT_AERO_SETTINGS.entrySeries });
    setProbe(null);
    setNotice({ tone: 'warn', text: t('massas.aero.cleared') });
    onSettingsSaved(DEFAULT_AERO_SETTINGS);
  };

  const probeMessage = probe ? describeProbe(probe, t) : null;

  return (
    <Card className="border-gray-200 shadow-sm overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-controls="aero-integration-body"
        className="flex w-full min-h-[56px] items-center justify-between gap-3 px-4 py-3 text-left hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-500"
      >
        <span className="flex min-w-0 items-center gap-2">
          <Plug className="h-4 w-4 shrink-0 text-gray-400" />
          <span className="min-w-0">
            <span className="block truncate text-sm font-semibold text-gray-900">
              {t('massas.aero.panelTitle')}
            </span>
            <span className="hidden sm:block text-xs text-gray-500 truncate">{t('massas.aero.panelHint')}</span>
          </span>
        </span>
        <span className="flex shrink-0 items-center gap-2">
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${
              ready ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-500'
            }`}
          >
            {ready && <ShieldCheck className="h-3 w-3" />}
            {ready ? t('massas.aero.configured') : t('massas.aero.notConfigured')}
          </span>
          <ChevronDown className={`h-4 w-4 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`} />
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

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <span className="block text-xs font-semibold text-gray-600">{t('massas.aero.unitLabel')}</span>
              <div role="group" aria-label={t('massas.aero.unitLabel')} className="flex rounded-lg border border-gray-300 p-0.5">
                {(['UN', 'CX'] as AeroQuantityUnit[]).map((unit) => (
                  <button
                    key={unit}
                    type="button"
                    onClick={() => setDraft((current) => ({ ...current, quantityUnit: unit }))}
                    aria-pressed={draft.quantityUnit === unit}
                    className={`flex-1 min-h-[40px] rounded-md text-sm font-semibold transition-colors ${
                      draft.quantityUnit === unit ? 'bg-amber-600 text-white' : 'text-gray-600 hover:bg-gray-50'
                    }`}
                  >
                    {unit === 'UN' ? t('massas.aero.unitUn') : t('massas.aero.unitCx')}
                  </button>
                ))}
              </div>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="aero-ws-series" className="block text-xs font-semibold text-gray-600">
                {t('massas.aero.seriesLabel')}
              </label>
              <Input
                id="aero-ws-series"
                autoCapitalize="characters"
                autoCorrect="off"
                spellCheck={false}
                maxLength={6}
                value={draft.entrySeries}
                onChange={(event) => setDraft((current) => ({ ...current, entrySeries: event.target.value.toUpperCase() }))}
                className="h-11 text-base"
              />
            </div>
          </div>

          {settings.syncEnabled && settings.userCode === null && (
            <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
              {t('massas.aero.missingCode')}
            </p>
          )}

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
              variant="ghost"
              className="min-h-[44px] w-full sm:w-auto font-semibold text-gray-700"
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
