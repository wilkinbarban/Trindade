export type SharePlatform = 'android' | 'ios' | 'both';

export interface ShareReleaseInfo {
  version: string;
  apkUrl: string;
  apkName?: string;
  webUrl: string;
}

export interface ShareCredentials {
  enabled: boolean;
  displayName?: string;
  username?: string;
  password?: string;
}

export interface ShareMessageParams {
  platform: SharePlatform;
  release: ShareReleaseInfo;
  credentials?: ShareCredentials;
  locale?: 'pt' | 'es';
}

export { formatPhoneMask, toWhatsAppNumber } from './phone';

/** The role a worker created from the share screen gets, and nothing else. */
export const QUICK_WORKER_ROLE_ID = 2;

/** The digits a generated password is padded with, in order, as long as they are needed. */
const PASSWORD_SEQUENCE = '1234567890123456789012345678901234567890';

/** The floor every generated password meets. */
const PASSWORD_MIN_LENGTH = 15;

/**
 * The temporary password handed to a new operator: the login plus the running digit sequence.
 *
 * A password nobody has to invent is one nobody reuses: the admin shares `pedro1234567890` with the operator
 * and the operator changes it. The sequence is written out rather than randomized so the same login always
 * yields the same password, which is what lets the screen show the value before the account exists.
 */
export function quickWorkerPassword(username: string): string {
  const base = String(username ?? '').trim().toLowerCase().slice(0, PASSWORD_MIN_LENGTH);
  if (base.length >= PASSWORD_MIN_LENGTH) return base;
  return base + PASSWORD_SEQUENCE.slice(0, PASSWORD_MIN_LENGTH - base.length);
}

/**
 * The login a display name suggests: lower-case, no accents, words glued together.
 *
 * It is a suggestion, not a rule — the admin can type over it — so it stays deliberately simple and strips
 * anything a login cannot carry instead of trying to transliterate it. The words are glued rather than joined
 * with a dot because the name often arrives from an ERP seat written in capitals (`JOAO H`), and a person
 * told their login is `joao.h` types a dot the phone keypad hides behind a second screen.
 */
export function deriveUsername(displayName: string): string {
  return String(displayName ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
}

export function buildShareMessage(params: ShareMessageParams): string {
  const { platform, release, credentials, locale = 'pt' } = params;
  const isEs = locale === 'es';

  const parts: string[] = [];

  const title = isEs ? '🥐 *Acceso a Trindade Massas*' : '🥐 *Acesso ao Trindade Massas*';
  parts.push(title);

  if (credentials?.displayName?.trim()) {
    const greeting = isEs
      ? `Hola, *${credentials.displayName.trim()}*! A continuación tienes tus accesos al sistema:`
      : `Olá, *${credentials.displayName.trim()}*! Seguem os seus acessos ao sistema:`;
    parts.push(greeting);
  } else {
    const intro = isEs
      ? 'A continuación tienes los accesos oficiales al sistema:'
      : 'Seguem os acessos oficiais ao sistema:';
    parts.push(intro);
  }

  if (platform === 'android' || platform === 'both') {
    const androidTitle = isEs ? '📱 *Aplicación Android:*' : '📱 *Aplicativo Android:*';
    const androidAction = isEs
      ? `Descarga e instala el APK oficial (${release.version}):`
      : `Baixe e instale o APK oficial (${release.version}):`;
    parts.push(`${androidTitle}\n${androidAction}\n${release.apkUrl}`);
  }

  if (platform === 'ios' || platform === 'both') {
    const webTitle = isEs
      ? '🌐 *Acceso Web (iPhone / Navegador):*'
      : '🌐 *Acesso Web (iPhone e Navegador):*';
    const webAction = isEs ? 'Ingresa por el enlace oficial:' : 'Acesse pelo link oficial:';
    parts.push(`${webTitle}\n${webAction}\n${release.webUrl}`);
  }

  if (credentials?.enabled && credentials.username?.trim()) {
    const credTitle = isEs ? '🔐 *Tus Credenciales:*' : '🔐 *Suas Credenciais:*';
    const userLabel = isEs ? 'Usuario' : 'Usuário';
    const passLabel = isEs ? 'Contraseña' : 'Senha';
    const passValue = credentials.password?.trim() || '123456789';

    parts.push(`${credTitle}\n${userLabel}: *${credentials.username.trim()}*\n${passLabel}: *${passValue}*`);
  }

  const footer = isEs
    ? 'Cualquier duda o soporte, consulta a la administración.'
    : 'Qualquer dúvida ou suporte, procure a administração.';
  parts.push(footer);

  return parts.join('\n\n');
}
