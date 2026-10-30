import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Download, RotateCcw } from 'lucide-react';
import { apkDownloadUrl, rememberAndroidBypass } from '../../lib/android-gate';

/**
 * What an Android visitor gets instead of the web app.
 *
 * The download is started automatically, because an operator who opened the link on a phone came here to
 * work and should not have to find a button first. Browsers are free to refuse a navigation that no gesture
 * initiated, so the automatic attempt is a courtesy and the button is the real path: if the first one is
 * blocked, nothing is lost and the screen still says what to do.
 *
 * The failure message is its own state rather than a toast, because a download that does not start looks
 * exactly like one that is still thinking, and the difference matters at the factory door.
 */
export function AndroidAppGate({ onContinueInBrowser }: { onContinueInBrowser: () => void }) {
  const { t } = useTranslation();
  const [attempted, setAttempted] = useState(false);
  const [failed, setFailed] = useState(false);

  const startDownload = useCallback(() => {
    setAttempted(true);
    setFailed(false);
    try {
      window.location.assign(apkDownloadUrl());
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    if (attempted) return;
    startDownload();
  }, [attempted, startDownload]);

  return (
    <main
      className="flex min-h-screen flex-col items-center justify-center gap-6 bg-slate-50 px-6 py-10 text-center"
      data-testid="android-download-gate"
    >
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-slate-900 text-white">
        <Download className="h-8 w-8" aria-hidden="true" />
      </div>

      <div className="max-w-sm space-y-2">
        <h1 className="text-xl font-semibold text-slate-900">{t('aeroDevice.downloadTitle')}</h1>
        <p className="text-sm leading-relaxed text-slate-600">{t('aeroDevice.downloadBody')}</p>
      </div>

      <div className="w-full max-w-sm space-y-3">
        <button
          type="button"
          onClick={startDownload}
          className="flex min-h-[44px] w-full items-center justify-center gap-2 rounded-lg bg-slate-900 px-4 text-sm font-medium text-white"
          data-testid="android-download-start"
        >
          {failed ? <RotateCcw className="h-4 w-4" aria-hidden="true" /> : <Download className="h-4 w-4" aria-hidden="true" />}
          {failed ? t('aeroDevice.downloadRetry') : t('aeroDevice.downloadAction')}
        </button>

        {failed ? (
          <p role="alert" className="text-xs text-red-700" data-testid="android-download-error">
            {t('aeroDevice.downloadFailed')}
          </p>
        ) : (
          <p className="text-xs text-slate-500" data-testid="android-download-status">
            {t('aeroDevice.downloadStarting')}
          </p>
        )}

        <button
          type="button"
          onClick={() => {
            rememberAndroidBypass();
            onContinueInBrowser();
          }}
          className="min-h-[44px] w-full rounded-lg px-4 text-xs font-medium text-slate-600 underline"
          data-testid="android-download-continue"
        >
          {t('aeroDevice.downloadContinue')}
        </button>
      </div>
    </main>
  );
}
