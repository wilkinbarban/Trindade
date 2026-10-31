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
  UserPlus,
  CheckCircle2,
} from 'lucide-react';
import { api, ApiClientError } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { waMeLink, shareOrCopy } from '../lib/whatsapp';
import { ModuleHelpModal } from '../components/ui/ModuleHelpModal';
import { isNetworkError } from '../lib/api-error-normalizer';
import { LoadingSpinner } from '../components/ui/loading-spinner';
import {
  formatPhoneMask,
  toWhatsAppNumber,
  buildShareMessage,
  deriveUsername,
  quickWorkerPassword,
  QUICK_WORKER_ROLE_ID,
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
  const quickNameInputId = useId();
  const quickUsernameInputId = useId();
  const quickPasswordInputId = useId();

  const [platform, setPlatform] = useState<SharePlatform>('android');
  const [release, setRelease] = useState<ShareReleaseInfo>(DEFAULT_RELEASE);
  const [users, setUsers] = useState<UserOptionItem[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string>('');
  const [displayName, setDisplayName] = useState<string>('');
  const [username, setUsername] = useState<string>('');
  const [password, setPassword] = useState<string>('123456789');
  const [includeCredentials, setIncludeCredentials] = useState<boolean>(true);
  /**
   * A worker is created from this screen by default: the admin already knows the person's name, and asking them
   * to leave, open the admin panel and come back is where the sharing stops happening. Picking an account that
   * already exists stays one tap away for the case that already had a user.
   */
  const [credentialMode, setCredentialMode] = useState<'create' | 'existing'>('create');
  const [quickName, setQuickName] = useState<string>('');
  const [quickUsername, setQuickUsername] = useState<string>('');
  /** The login is derived from the name until the admin types over it, and never after. */
  const [usernameEdited, setUsernameEdited] = useState<boolean>(false);
  /** The login whose account was already created in this visit, so sharing twice does not create twice. */
  const [createdUsername, setCreatedUsername] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string>('');
  const [creating, setCreating] = useState<boolean>(false);
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

  const quickLogin = quickUsername || deriveUsername(quickName);
  const quickPassword = quickWorkerPassword(quickLogin);

  const handleQuickNameChange = (value: string) => {
    setQuickName(value);
    setCreateError('');
    if (!usernameEdited) setQuickUsername(deriveUsername(value));
  };

  const handleQuickUsernameChange = (value: string) => {
    setUsernameEdited(true);
    setCreateError('');
    setQuickUsername(deriveUsername(value));
  };

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
      displayName: credentialMode === 'create' ? quickName : displayName,
      username: credentialMode === 'create' ? quickLogin : username,
      password: credentialMode === 'create' ? quickPassword : password || '123456789',
    } : { enabled: false },
    locale: currentLocale,
  }), [
    platform,
    release,
    isAdmin,
    includeCredentials,
    credentialMode,
    quickName,
    quickLogin,
    quickPassword,
    displayName,
    username,
    password,
    currentLocale,
  ]);

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

  /**
   * Creates the worker explicitly when the administrator clicks "+ Cadastrar Operário".
   *
   * The role is fixed: this screen hands out workers, and a select here would be a quiet way to grant an admin.
   */
  const handleCreateWorker = async (): Promise<boolean> => {
    if (!isAdmin || !includeCredentials || credentialMode !== 'create') return true;
    const display_name = quickName.trim();
    const username = quickLogin.trim().toLowerCase();
    if (!display_name) {
      setCreateError(t('share.createNameRequired', 'Informe o nome de exibição do novo operário.'));
      return false;
    }
    if (!username) {
      setCreateError(t('share.createUsernameRequired', 'Informe o nome de usuário do novo operário.'));
      return false;
    }
    setCreating(true);
    setCreateError('');
    try {
      await api.post('/admin/users', {
        username,
        display_name,
        password: quickPassword,
        role_id: QUICK_WORKER_ROLE_ID,
      });
      setCreatedUsername(username);
      return true;
    } catch (err) {
      if (isNetworkError(err)) {
        setCreateError(t('share.createNetworkError', 'Servidor inalcançável. Verifique sua conexão e tente novamente.'));
      } else if (
        err instanceof ApiClientError &&
        (err.status === 409 ||
          err.status === 400 ||
          err.message.includes('userAlreadyExists') ||
          err.message.includes('UNIQUE') ||
          err.message.toLowerCase().includes('already'))
      ) {
        setCreateError(t('share.createUserAlreadyExists', 'Este nome de usuário já está em uso. Escolha outro login.'));
      } else {
        const detail = err instanceof Error && err.message ? `: ${err.message}` : '';
        setCreateError(t('share.createServerError', 'Não foi possível cadastrar o operário.') + detail);
      }
      return false;
    } finally {
      setCreating(false);
    }
  };

  /**
   * Verifies that the worker account was explicitly created before sharing credentials (S12).
   * WhatsApp, Copy, and Share APK do not create accounts on their own.
   */
  const checkWorkerCreatedBeforeAction = (): boolean => {
    if (!isAdmin || !includeCredentials || credentialMode !== 'create') return true;
    const username = quickLogin.trim().toLowerCase();
    if (createdUsername !== username) {
      setCreateError(t('share.createRequiredBeforeSend', 'Cadastre o operário antes de compartilhar o acesso.'));
      return false;
    }
    return true;
  };

  const handleOpenWhatsApp = async () => {
    if (!waLink) return;
    if (!checkWorkerCreatedBeforeAction()) return;
    window.open(waLink, '_blank', 'noopener,noreferrer');
  };

  const handleCopy = async () => {
    if (!checkWorkerCreatedBeforeAction()) return;
    const outcome = await shareOrCopy(message);
    if (outcome === 'copied' || outcome === 'shared') {
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    }
  };

  const handleShareApk = async () => {
    if (!checkWorkerCreatedBeforeAction()) return;
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
                  {/* Two ways to hand over an account, never both: a new worker, or one that already exists. */}
                  <div role="radiogroup" aria-label={t('share.credentialMode', 'Modo das credenciais')} className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      role="radio"
                      aria-checked={credentialMode === 'create'}
                      onClick={() => { setCredentialMode('create'); setCreateError(''); }}
                      className={`min-h-[44px] rounded-lg border px-3 text-xs font-semibold transition-colors ${
                        credentialMode === 'create'
                          ? 'border-blue-600 bg-blue-50/70 text-blue-800'
                          : 'border-gray-200 text-gray-700 hover:bg-gray-50'
                      }`}
                      data-testid="share-credential-mode-create"
                    >
                      {t('share.modeCreate', 'Criar Novo Operário')}
                    </button>
                    <button
                      type="button"
                      role="radio"
                      aria-checked={credentialMode === 'existing'}
                      onClick={() => { setCredentialMode('existing'); setCreateError(''); }}
                      className={`min-h-[44px] rounded-lg border px-3 text-xs font-semibold transition-colors ${
                        credentialMode === 'existing'
                          ? 'border-blue-600 bg-blue-50/70 text-blue-800'
                          : 'border-gray-200 text-gray-700 hover:bg-gray-50'
                      }`}
                      data-testid="share-credential-mode-existing"
                    >
                      {t('share.modeExisting', 'Selecionar Existente')}
                    </button>
                  </div>

                  {credentialMode === 'create' ? (
                    <div className="space-y-3" data-testid="share-quick-create">
                      <div>
                        <label htmlFor={quickNameInputId} className="text-xs font-medium text-gray-600 block mb-1">
                          {t('share.displayName', 'Nome de Exibição')}
                        </label>
                        <Input
                          id={quickNameInputId}
                          type="text"
                          value={quickName}
                          onChange={(e) => handleQuickNameChange(e.target.value)}
                          placeholder="Pedro"
                          className="h-11 text-sm border-gray-300"
                          data-testid="share-quick-name"
                        />
                      </div>
                      <div>
                        <label htmlFor={quickUsernameInputId} className="text-xs font-medium text-gray-600 block mb-1">
                          {t('share.username', 'Usuário de Login')}
                        </label>
                        <Input
                          id={quickUsernameInputId}
                          type="text"
                          value={quickUsername}
                          onChange={(e) => handleQuickUsernameChange(e.target.value)}
                          placeholder="pedro"
                          autoCapitalize="none"
                          autoCorrect="off"
                          spellCheck={false}
                          className="h-11 text-sm font-mono border-gray-300"
                          data-testid="share-quick-username"
                        />
                        <p className="text-[11px] text-gray-500 mt-1">
                          {t('share.quickUsernameHint', 'Sugerido a partir do nome; você pode corrigir.')}
                        </p>
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                          <span className="text-xs font-medium text-gray-600 block mb-1">
                            {t('share.role', 'Função')}
                          </span>
                          {/* Fixed on purpose: this screen creates workers, and a control here would be a quiet
                              way to hand out an admin account. */}
                          <div className="flex h-11 items-center rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm text-gray-700" data-testid="share-quick-role">
                            {t('share.roleWorker', 'Trabalhador')}
                          </div>
                        </div>
                        <div>
                          <label htmlFor={quickPasswordInputId} className="text-xs font-medium text-gray-600 block mb-1">
                            {t('share.password', 'Senha Temporária')}
                          </label>
                          <Input
                            id={quickPasswordInputId}
                            type="text"
                            readOnly
                            value={quickPassword}
                            className="h-11 text-sm font-mono bg-gray-50 text-gray-700 cursor-not-allowed border-gray-200"
                            data-testid="share-quick-password"
                          />
                        </div>
                      </div>
                      <p className="text-[11px] text-gray-500">
                        {t('share.quickPasswordHint', 'A senha é gerada com o usuário e enviada junto com as instruções.')}
                      </p>

                      <div className="pt-1">
                        <Button
                          type="button"
                          onClick={() => void handleCreateWorker()}
                          disabled={creating || (Boolean(createdUsername) && createdUsername === quickLogin.trim().toLowerCase())}
                          className="w-full sm:w-auto min-h-[44px] bg-blue-600 hover:bg-blue-700 text-white font-semibold"
                          data-testid="share-create-worker-btn"
                        >
                          {creating ? (
                            <>
                              <LoadingSpinner className="h-4 w-4 mr-2" />
                              {t('share.creatingWorker', 'Criando operário...')}
                            </>
                          ) : (
                            <>
                              <UserPlus className="h-4 w-4 mr-2" />
                              {t('share.createWorkerAction', '+ Cadastrar Operário')}
                            </>
                          )}
                        </Button>
                      </div>

                      {createdUsername === quickLogin.trim().toLowerCase() && (
                        <div
                          role="status"
                          className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-xs font-semibold text-emerald-800"
                          data-testid="share-create-success"
                        >
                          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" />
                          <span>{t('share.createSuccess', 'Operário cadastrado com sucesso! A conta está habilitada para uso.')}</span>
                        </div>
                      )}

                      {createError && (
                        <p className="text-xs text-red-600" role="alert" data-testid="share-quick-error">
                          {createError}
                        </p>
                      )}
                    </div>
                  ) : (
                  <>
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
                  </>
                  )}
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
                // A button, not a link: when the credentials belong to a worker this screen is about to create,
                // the account has to exist before WhatsApp opens, and an anchor cannot wait.
                <button
                  type="button"
                  onClick={() => void handleOpenWhatsApp()}
                  disabled={creating}
                  className={`w-full min-h-[44px] inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium text-sm transition-colors disabled:opacity-60 ${
                    platform === 'ios'
                      ? 'bg-emerald-600 hover:bg-emerald-700 text-white shadow-sm'
                      : 'border border-emerald-600 text-emerald-700 hover:bg-emerald-50'
                  }`}
                  data-testid="share-open-whatsapp"
                >
                  <MessageCircle className="h-4 w-4" />
                  {creating
                    ? t('share.creatingWorker', 'Criando operário...')
                    : t('share.openTextChat', 'Abrir Chat no WhatsApp')}
                </button>
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
