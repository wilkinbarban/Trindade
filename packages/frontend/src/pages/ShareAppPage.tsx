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
} from 'lucide-react';
import { api } from '../api/client';
import { useAuth } from '../contexts/AuthContext';
import { waMeLink, shareOrCopy } from '../lib/whatsapp';
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
  apkUrl: 'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk',
  webUrl: 'https://trindademasas.duckdns.org',
};

export function ShareAppPage() {
  const { t, i18n } = useTranslation();
  const { user: currentUser } = useAuth();

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
  const [qrDataUrl, setQrDataUrl] = useState<string>('');

  useEffect(() => {
    let active = true;
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
      .catch(() => {});
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    const fetchUsers = async () => {
      try {
        if (currentUser?.role === 'Administrador') {
          const res = await api.get<{ users: UserOptionItem[] }>('/admin/users');
          if (active && Array.isArray(res?.users)) {
            setUsers(res.users);
            return;
          }
        }
      } catch {}
      try {
        const res = await api.get<{ users: UserOptionItem[] }>('/users/options');
        if (active && Array.isArray(res?.users)) setUsers(res.users);
      } catch {
        if (active) setUsers([]);
      }
    };
    void fetchUsers();
    return () => { active = false; };
  }, [currentUser]);

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
    credentials: { enabled: includeCredentials, displayName, username, password },
    locale: currentLocale,
  }), [platform, release, includeCredentials, displayName, username, password, currentLocale]);

  const rawDialDigits = toWhatsAppNumber(phone);
  const waLink = useMemo(() => rawDialDigits ? waMeLink(rawDialDigits, message) : null, [rawDialDigits, message]);

  useEffect(() => {
    let active = true;
    const qrTarget = waLink || (platform === 'android' ? release.apkUrl : release.webUrl);
    void QRCode.toDataURL(qrTarget, { width: 220, margin: 2 })
      .then((url) => { if (active) setQrDataUrl(url); })
      .catch(() => { if (active) setQrDataUrl(''); });
    return () => { active = false; };
  }, [waLink, platform, release]);

  const handleCopy = async () => {
    const outcome = await shareOrCopy(message);
    if (outcome === 'copied' || outcome === 'shared') {
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-12">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
            <Share2 className="h-6 w-6 text-blue-600" />
            {t('share.title', 'Compartilhar Trindade')}
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            {t('share.subtitle', 'Envie acessos e credenciais por WhatsApp para operadores e motoristas')}
          </p>
        </div>
        <div className="inline-flex items-center gap-1.5 px-3 py-1 bg-blue-50 text-blue-700 text-xs font-semibold rounded-full border border-blue-200 self-start sm:self-auto">
          <span>{release.version}</span>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-7 space-y-6">
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

          <div className="bg-white rounded-xl border border-gray-200 p-4 sm:p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
              <label htmlFor={credToggleId} className="text-sm font-semibold text-gray-800 flex items-center gap-2 cursor-pointer">
                <KeyRound className="h-4 w-4 text-amber-600" />
                {t('share.includeCredentials', '3. Incluir Credenciais no Mensagem')}
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
                    <option value="">{t('share.customOrNewUser', '-- Digitar manualmente ou escolher --')}</option>
                    {users.map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.display_name} {u.username ? `(@${u.username})` : ''}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="text-xs font-medium text-gray-600 block mb-1">
                      {t('share.displayName', 'Nome de Exibição')}
                    </label>
                    <Input
                      type="text"
                      placeholder="Ex: Carlos Oliveira"
                      value={displayName}
                      onChange={(e) => setDisplayName(e.target.value)}
                      className="h-10 text-sm"
                    />
                  </div>
                  <div>
                    <label htmlFor={usernameInputId} className="text-xs font-medium text-gray-600 block mb-1">
                      {t('share.username', 'Usuário de Login')}
                    </label>
                    <Input
                      id={usernameInputId}
                      type="text"
                      placeholder="Ex: carlos.oliveira"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      className="h-10 text-sm"
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
                    className="h-10 text-sm font-mono"
                  />
                  <p className="text-[11px] text-gray-400 mt-1">
                    {t('share.passwordHint', 'Pre-carregada por padrão como 123456789. Pode ser alterada livremente.')}
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>

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
              {waLink ? (
                <a
                  href={waLink}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="w-full min-h-[48px] inline-flex items-center justify-center gap-2 px-4 py-3 bg-emerald-600 hover:bg-emerald-700 text-white font-medium text-sm rounded-lg transition-colors shadow-sm"
                >
                  <MessageCircle className="h-5 w-5" />
                  {t('share.sendWhatsApp', 'Abrir Chat no WhatsApp')}
                </a>
              ) : (
                <Button
                  disabled
                  variant="outline"
                  className="w-full min-h-[48px] border-emerald-200 text-emerald-800 bg-emerald-50/40 opacity-70"
                >
                  <MessageCircle className="h-5 w-5 mr-1" />
                  {t('share.enterPhoneToSend', 'Informe o celular para enviar')}
                </Button>
              )}

              <Button
                type="button"
                onClick={handleCopy}
                variant="outline"
                className="w-full min-h-[48px] text-gray-700 border-gray-300 hover:bg-gray-50 flex items-center justify-center gap-2"
              >
                {copied ? (
                  <>
                    <Check className="h-5 w-5 text-emerald-600" />
                    <span className="text-emerald-700 font-semibold">{t('share.copied', 'Copiado para a Área de Transferência!')}</span>
                  </>
                ) : (
                  <>
                    <Copy className="h-5 w-5 text-gray-500" />
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
