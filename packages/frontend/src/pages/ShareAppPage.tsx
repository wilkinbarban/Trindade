import { useEffect, useId, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import QRCode from 'qrcode';
import {
  Share2,
  Smartphone,
  Globe,
  Layers,
  Copy,
  Check,
  MessageCircle,
  QrCode,
  KeyRound,
  Download,
} from 'lucide-react';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { waMeLink, shareOrCopy } from '../lib/whatsapp';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import {
  formatPhoneMask,
  toWhatsAppNumber,
  buildShareMessage,
  type SharePlatform,
  type ShareReleaseInfo,
} from '../lib/share-app';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';

interface UserOptionItem {
  id: number;
  display_name: string;
  username?: string;
}

const DEFAULT_RELEASE: ShareReleaseInfo = {
  version: 'v0.5.0',
  apkUrl: 'https://trindademasas.duckdns.org/api/app/download-apk',
  webUrl: 'https://trindademasas.duckdns.org',
};

export function ShareAppPage() {
  const { t, i18n } = useTranslation();
  const { user: currentUser } = useAuth();
  const isAdmin = currentUser?.role === 'Administrador';

  const userSelectId = useId();
  const usernameInputId = useId();
  const passwordInputId = useId();
  const phoneInputId = useId();
  const credToggleId = useId();

  const [platform, setPlatform] = useState<SharePlatform>('android');
  const [release, setRelease] = useState<ShareReleaseInfo>(DEFAULT_RELEASE);
  const [users, setUsers] = useState<UserOptionItem[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string>('');
  const [displayName, setDisplayName] = useState<string>('');
  const [username, setUsername] = useState<string>('');
  const [password, setPassword] = useState<string>('123456789');
  const [includeCredentials, setIncludeCredentials] = useState<boolean>(true);
  const [phone, setPhone] = useState<string>('');
  const [copied, setCopied] = useState<boolean>(false);
  const [sharingApk, setSharingApk] = useState<boolean>(false);
  const [downloadedNotice, setDownloadedNotice] = useState<boolean>(false);
  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [releaseError, setReleaseError] = useState(false);
  const [qrError, setQrError] = useState(false);
  const [usersError, setUsersError] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let active = true;
    setReleaseError(false);
    void api.get<{ version: string; apkUrl: string; webUrl: string }>('/app/latest-release')
      .then((data) => {
        if (active && data?.apkUrl) {
          setRelease({
            version: data.version || DEFAULT_RELEASE.version,
            apkUrl: data.apkUrl || DEFAULT_RELEASE.apkUrl,
            webUrl: data.webUrl || DEFAULT_RELEASE.webUrl,
          });
        }
      })
      .catch(() => { if (active) setReleaseError(true); });
    return () => { active = false; };
  }, [reloadToken]);

  useEffect(() => {
    let active = true;
    if (!isAdmin) return;
    const fetchUsers = async () => {
      try {
        const res = await api.get<{ users: UserOptionItem[] }>('/admin/users');
        if (active && Array.isArray(res?.users)) {
          setUsers(res.users);
          return;
        }
      } catch {}
      try {
        const res = await api.get<{ users: UserOptionItem[] }>('/users/options');
        if (active && Array.isArray(res?.users)) setUsers(res.users);
      } catch {
        if (active) {
          setUsers([]);
          setUsersError(true);
        }
      }
    };
    setUsersError(false);
    void fetchUsers();
    return () => { active = false; };
  }, [isAdmin]);

  const handleSelectUser = (idStr: string) => {
    setSelectedUserId(idStr);
    if (!idStr) {
      setDisplayName('');
      setUsername('');
      return;
    }
    const found = users.find((u) => String(u.id) === idStr);
    if (found) {
      setDisplayName(found.display_name);
      const derived = found.username || found.display_name.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/\s+/g, '.');
      setUsername(derived);
    }
  };

  const currentLocale = (i18n.resolvedLanguage || i18n.language || 'pt-BR').startsWith('es') ? 'es' : 'pt';

  const message = useMemo(() => buildShareMessage({
    platform,
    release,
    credentials: isAdmin && includeCredentials ? {
      enabled: true,
      displayName,
      username,
      password: password || '123456789',
    } : { enabled: false },
    locale: currentLocale,
  }), [platform, release, isAdmin, includeCredentials, displayName, username, password, currentLocale]);

  const rawDialDigits = toWhatsAppNumber(phone);
  const waLink = useMemo(() => rawDialDigits ? waMeLink(rawDialDigits, message) : null, [rawDialDigits, message]);

  useEffect(() => {
    let active = true;
    const qrTarget = waLink || (platform === 'android' ? release.apkUrl : release.webUrl);
    setQrError(false);
    setQrDataUrl('');
    void QRCode.toDataURL(qrTarget, { width: 220, margin: 2 })
      .then((url) => { if (active) setQrDataUrl(url); })
      .catch(() => { if (active) setQrError(true); });
    return () => { active = false; };
  }, [waLink, platform, release, reloadToken]);

  const handleCopy = async () => {
    const outcome = await shareOrCopy(message);
    if (outcome === 'copied' || outcome === 'shared') {
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    }
  };

  const handleShareApk = async () => {
    setSharingApk(true);
    setDownloadedNotice(false);
    try {
      const res = await fetch('/api/app/download-apk');
      if (!res.ok) throw new Error('Download failed');
      const blob = await res.blob();
      const apkFile = new File([blob], 'trindade.apk', {
        type: 'application/vnd.android.package-archive',
      });

      const canShareFiles =
        typeof navigator !== 'undefined' &&
        typeof navigator.canShare === 'function' &&
        navigator.canShare({ files: [apkFile] });

      if (canShareFiles && typeof navigator.share === 'function') {
        await navigator.share({
          files: [apkFile],
          text: message,
          title: 'Trindade APK',
        });
      } else {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'trindade.apk';
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        setDownloadedNotice(true);
      }
    } catch (err) {
      if ((err as Error)?.name !== 'AbortError') {
        await handleCopy();
      }
    } finally {
      setSharingApk(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-12">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
              <Share2 className="h-6 w-6 text-blue-600" />
              {t('share.title', 'Compartilhar Trindade')}
            </h1>
            <ModuleHelpModal
              title={t('moduleHelp.share.title')}
              subtitle={t('moduleHelp.share.subtitle')}
              description={t('moduleHelp.share.description')}
              sections={t('moduleHelp.share.sections', { returnObjects: true }) as any}
            />
          </div>
          <p className="text-sm text-gray-500 mt-1">
            {t('share.subtitle', 'Envie acessos e credenciais por WhatsApp para operadores e motoristas')}
          </p>
        </div>
        <div className="inline-flex items-center gap-1.5 px-3 py-1 bg-blue-50 text-blue-700 text-xs font-semibold rounded-full border border-blue-200 self-start sm:self-auto">
          <span>{release.version}</span>
        </div>
      </div>

      {releaseError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800"
        >
          <span>{t('share.releaseError')}</span>
          <Button variant="outline" size="sm" onClick={() => setReloadToken((n) => n + 1)}>
            {t('ui.retry')}
          </Button>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-7 space-y-6">
          {/* 1. Platform */}
          <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm space-y-3">
            <label className="text-sm font-semibold text-gray-800 block">
              {t('share.platformLabel', '1. Plataforma de Acesso')}
            </label>
            <div className="grid grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => setPlatform('android')}
                className={`flex flex-col items-center justify-center p-3 rounded-lg border text-center transition-all ${
                  platform === 'android' ? 'border-blue-600 bg-blue-50/70 text-blue-800 font-semibold shadow-xs' : 'border-gray-200 hover:bg-gray-50 text-gray-700'
                }`}
              >
                <Smartphone className="h-5 w-5 mb-1.5 text-emerald-600" />
                <span className="text-xs">Android (APK)</span>
              </button>
              <button
                type="button"
                onClick={() => setPlatform('ios')}
                className={`flex flex-col items-center justify-center p-3 rounded-lg border text-center transition-all ${
                  platform === 'ios' ? 'border-blue-600 bg-blue-50/70 text-blue-800 font-semibold shadow-xs' : 'border-gray-200 hover:bg-gray-50 text-gray-700'
                }`}
              >
                <Globe className="h-5 w-5 mb-1.5 text-blue-600" />
                <span className="text-xs">iPhone / Web</span>
              </button>
              <button
                type="button"
                onClick={() => setPlatform('both')}
                className={`flex flex-col items-center justify-center p-3 rounded-lg border text-center transition-all ${
                  platform === 'both' ? 'border-blue-600 bg-blue-50/70 text-blue-800 font-semibold shadow-xs' : 'border-gray-200 hover:bg-gray-50 text-gray-700'
                }`}
              >
                <Layers className="h-5 w-5 mb-1.5 text-purple-600" />
                <span className="text-xs">{t('share.bothPlatforms', 'Ambos (Híbrido)')}</span>
              </button>
            </div>
          </div>

          {/* 2. Phone */}
          <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm space-y-3">
            <label htmlFor={phoneInputId} className="text-sm font-semibold text-gray-800 block">
              {t('share.phoneLabel', '2. Telefone WhatsApp do Destinatário')}
            </label>
            <Input
              id={phoneInputId}
              type="tel"
              placeholder="(11) 9XXXX-XXXX"
              value={phone}
              onChange={(e) => setPhone(formatPhoneMask(e.target.value))}
              className="h-11 text-base pl-3 font-mono"
            />
            <p className="text-xs text-gray-500">
              {t('share.phoneHint', 'Com DDD brasileiro. Digite o número para abrir o chat direto no WhatsApp.')}
            </p>
          </div>

          {/* 3. Credentials (ADMIN ONLY, ReadOnly) */}
          {isAdmin && (
            <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between">
                <label htmlFor={credToggleId} className="text-sm font-semibold text-gray-800 flex items-center gap-2 cursor-pointer">
                  <KeyRound className="h-4 w-4 text-amber-600" />
                  {t('share.includeCredentials', '3. Incluir Credenciais na Mensagem')}
                </label>
                <input
                  id={credToggleId}
                  type="checkbox"
                  checked={includeCredentials}
                  onChange={(e) => setIncludeCredentials(e.target.checked)}
                  className="h-5 w-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                />
              </div>

              {includeCredentials && (
                <div className="space-y-4 pt-2 border-t border-gray-100">
                  <div>
                    <label htmlFor={userSelectId} className="text-xs font-medium text-gray-600 block mb-1">
                      {t('share.selectUser', 'Selecionar Usuário Cadastrado')}
                    </label>
                    <select
                      id={userSelectId}
                      value={selectedUserId}
                      onChange={(e) => handleSelectUser(e.target.value)}
                      className="w-full h-11 px-3 rounded-lg border border-gray-300 bg-white text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    >
                      <option value="">{t('share.customOrNewUser', '-- Escolher da lista --')}</option>
                      {users.map((u) => (
                        <option key={u.id} value={u.id}>
                          {u.display_name} {u.username ? `(@${u.username})` : ''}
                        </option>
                      ))}
                    </select>
                    {usersError && (
                      <p className="mt-1 text-xs text-red-600" role="alert">
                        {t('share.usersError')}
                      </p>
                    )}
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="text-xs font-medium text-gray-600 block mb-1">
                        {t('share.displayName', 'Nome de Exibição')}
                      </label>
                      <Input
                        type="text"
                        readOnly
                        value={displayName}
                        placeholder="--"
                        className="h-10 text-sm bg-gray-50 text-gray-700 cursor-not-allowed border-gray-200"
                      />
                    </div>
                    <div>
                      <label htmlFor={usernameInputId} className="text-xs font-medium text-gray-600 block mb-1">
                        {t('share.username', 'Usuário de Login')}
                      </label>
                      <Input
                        id={usernameInputId}
                        type="text"
                        readOnly
                        value={username}
                        placeholder="--"
                        className="h-10 text-sm bg-gray-50 text-gray-700 cursor-not-allowed border-gray-200"
                      />
                    </div>
                  </div>

                  <div>
                    <label htmlFor={passwordInputId} className="text-xs font-medium text-gray-600 block mb-1">
                      {t('share.password', 'Senha Temporária')}
                    </label>
                    <Input
                      id={passwordInputId}
                      type="text"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="123456789"
                      className="h-10 text-sm font-mono border-gray-300"
                    />
                    <p className="text-[11px] text-gray-500 mt-1">
                      {t('share.adminPasswordHint', 'Você pode informar a senha atribuída a este usuário ou manter a sugerida (123456789).')}
                    </p>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Live Preview & Actions */}
        <div className="lg:col-span-5 space-y-6">
          <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-gray-800 flex items-center gap-1.5">
                <MessageCircle className="h-4 w-4 text-emerald-600" />
                {t('share.previewTitle', 'Pré-visualização do WhatsApp')}
              </span>
              <span className="text-xs text-gray-400 font-mono">
                {message.length} {t('share.chars', 'caracteres')}
              </span>
            </div>

            <div className="bg-emerald-50/70 border border-emerald-200 rounded-xl p-3.5 text-xs text-gray-800 font-sans whitespace-pre-wrap leading-relaxed max-h-72 overflow-y-auto break-words shadow-inner">
              {message}
            </div>

            <div className="space-y-2 pt-2">
              {downloadedNotice && (
                <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-900 space-y-2">
                  <div className="font-semibold flex items-center gap-1.5 text-amber-800">
                    <Check className="h-4 w-4 text-emerald-600 shrink-0" />
                    <span>{t('share.apkDownloadedTitle', 'APK baixado com sucesso!')}</span>
                  </div>
                  <p className="text-[11px] leading-relaxed text-amber-800">
                    {t('share.apkDownloadedDesc', 'O arquivo trindade.apk foi salvo no seu aparelho. Abra o WhatsApp e anexe o arquivo na conversa.')}
                  </p>
                  {waLink && (
                    <a
                      href={waLink}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white font-medium rounded text-xs transition-colors"
                    >
                      <MessageCircle className="h-3.5 w-3.5" />
                      {t('share.openWaAttach', 'Abrir WhatsApp e Anexar')}
                    </a>
                  )}
                </div>
              )}

              {platform !== 'ios' && (
                <Button
                  type="button"
                  disabled={sharingApk}
                  onClick={handleShareApk}
                  className="w-full min-h-[48px] bg-emerald-600 hover:bg-emerald-700 text-white font-medium text-sm flex items-center justify-center gap-2 shadow-sm"
                >
                  {sharingApk ? (
                    <span>{t('share.preparingApk', 'Preparando arquivo APK...')}</span>
                  ) : (
                    <>
                      <Download className="h-5 w-5" />
                      <span>{t('share.sendApkFile', 'Compartilhar Arquivo APK')}</span>
                    </>
                  )}
                </Button>
              )}

              {waLink ? (
                <a
                  href={waLink}
                  target="_blank"
                  rel="noopener noreferrer"
                  className={`w-full min-h-[44px] inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium text-sm transition-colors ${
                    platform === 'ios'
                      ? 'bg-emerald-600 hover:bg-emerald-700 text-white shadow-sm'
                      : 'border border-emerald-600 text-emerald-700 hover:bg-emerald-50'
                  }`}
                >
                  <MessageCircle className="h-4 w-4" />
                  {t('share.openTextChat', 'Abrir Chat no WhatsApp')}
                </a>
              ) : (
                <Button
                  disabled
                  variant="outline"
                  className="w-full min-h-[44px] border-gray-200 text-gray-400 bg-gray-50 opacity-70"
                >
                  <MessageCircle className="h-4 w-4 mr-1" />
                  {t('share.enterPhoneToSend', 'Informe o celular para abrir chat')}
                </Button>
              )}

              <Button
                type="button"
                onClick={handleCopy}
                variant="outline"
                className="w-full min-h-[44px] text-gray-700 border-gray-300 hover:bg-gray-50 flex items-center justify-center gap-2"
              >
                {copied ? (
                  <>
                    <Check className="h-4 w-4 text-emerald-600" />
                    <span className="text-emerald-700 font-semibold">{t('share.copied', 'Copiado para a Área de Transferência!')}</span>
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4 text-gray-500" />
                    <span>{t('share.copyMessage', 'Copiar Mensagem')}</span>
                  </>
                )}
              </Button>
            </div>
          </div>

          <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm text-center space-y-3">
            <div className="flex items-center justify-center gap-1.5 text-sm font-semibold text-gray-800">
              <QrCode className="h-4 w-4 text-blue-600" />
              <span>{t('share.qrCodeTitle', 'Escanear Código QR')}</span>
            </div>
            <p className="text-xs text-gray-500 max-w-xs mx-auto">
              {waLink
                ? t('share.qrWithPhone', 'Aponte a câmera do celular para abrir a conversa com mensagem preenchida.')
                : t('share.qrWithoutPhone', 'Aponte a câmera para acessar o link de download ou aplicativo web.')}
            </p>

            <div className="flex justify-center py-1">
              {qrDataUrl ? (
                <div className="p-2.5 bg-white border border-gray-200 rounded-xl shadow-xs inline-block">
                  <img
                    src={qrDataUrl}
                    alt={t('share.qrCodeAlt', 'Código QR de Acesso')}
                    className="w-48 h-48 sm:w-52 sm:h-52 object-contain"
                  />
                </div>
              ) : qrError ? (
                <div className="w-48 h-48 flex flex-col items-center justify-center gap-2 bg-red-50 rounded-xl border border-dashed border-red-300 text-xs text-red-600 px-3" role="alert">
                  <span>{t('share.qrError')}</span>
                  <Button variant="outline" size="sm" onClick={() => setReloadToken((n) => n + 1)}>
                    {t('ui.retry')}
                  </Button>
                </div>
              ) : (
                <div className="w-48 h-48 flex items-center justify-center bg-gray-50 rounded-xl border border-dashed border-gray-300 text-xs text-gray-400">
                  {t('share.generatingQr', 'Gerando QR Code...')}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
