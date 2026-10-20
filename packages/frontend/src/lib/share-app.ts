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
