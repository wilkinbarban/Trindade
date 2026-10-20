import type { LatestReleaseResponse } from './app.schema.js';

export const TRINDADE_APK_DOWNLOAD_URL =
  'https://trindademasas.duckdns.org/api/app/download-apk';

export const FALLBACK_UPSTREAM_APK_URL =
  'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk';

export const FALLBACK_RELEASE: LatestReleaseResponse = {
  version: 'v0.5.0',
  apkUrl: TRINDADE_APK_DOWNLOAD_URL,
  apkName: 'trindade.apk',
  apkSize: null,
  publishedAt: null,
  webUrl: 'https://trindademasas.duckdns.org',
};

export const GITHUB_LATEST_RELEASE_URL =
  'https://api.github.com/repos/wilkinbarban/Trindade/releases/latest';

export const RELEASE_CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutos en memoria

interface CachedRelease {
  data: LatestReleaseResponse;
  upstreamApkUrl: string;
  expiresAt: number;
}

let cachedRelease: CachedRelease | null = null;
let testFetcher: typeof fetch | null = null;

export function setReleaseFetcherForTest(fetcher: typeof fetch | null): void {
  testFetcher = fetcher;
}

export function resetReleaseCache(): void {
  cachedRelease = null;
}

interface RawGitHubAsset {
  name?: string;
  browser_download_url?: string;
  size?: number;
}

interface RawGitHubRelease {
  tag_name?: string;
  name?: string;
  published_at?: string;
  assets?: RawGitHubAsset[];
}

export async function getLatestRelease(options: { forceRefresh?: boolean } = {}): Promise<LatestReleaseResponse> {
  const now = Date.now();
  if (cachedRelease && !options.forceRefresh && now < cachedRelease.expiresAt) {
    return cachedRelease.data;
  }

  const fetcher = testFetcher ?? fetch;

  try {
    const res = await fetcher(GITHUB_LATEST_RELEASE_URL, {
      headers: {
        Accept: 'application/vnd.github.v3+json',
        'User-Agent': 'Trindade-Backend',
      },
    });

    if (!res.ok) {
      if (cachedRelease) return cachedRelease.data;
      return FALLBACK_RELEASE;
    }

    const payload = (await res.json()) as RawGitHubRelease;
    const tagName = payload.tag_name?.trim() || FALLBACK_RELEASE.version;
    const cleanTag = tagName.replace(/^v/, '');

    const apkAsset = payload.assets?.find(
      (a) => typeof a.name === 'string' && a.name.toLowerCase().endsWith('.apk'),
    );

    const upstreamApkUrl =
      apkAsset?.browser_download_url ||
      `https://github.com/wilkinbarban/Trindade/releases/download/${tagName}/trindade-${cleanTag}.apk`;

    const apkSize = typeof apkAsset?.size === 'number' ? apkAsset.size : null;
    const publishedAt = payload.published_at || null;

    const releaseData: LatestReleaseResponse = {
      version: tagName,
      apkUrl: TRINDADE_APK_DOWNLOAD_URL,
      apkName: 'trindade.apk',
      apkSize,
      publishedAt,
      webUrl: 'https://trindademasas.duckdns.org',
    };

    cachedRelease = {
      data: releaseData,
      upstreamApkUrl,
      expiresAt: now + RELEASE_CACHE_TTL_MS,
    };

    return releaseData;
  } catch {
    if (cachedRelease) return cachedRelease.data;
    return FALLBACK_RELEASE;
  }
}

export async function downloadApkBinary(): Promise<{ buffer: Buffer; filename: string } | null> {
  const release = await getLatestRelease();
  const upstreamUrl = cachedRelease?.upstreamApkUrl || FALLBACK_UPSTREAM_APK_URL;
  const fetcher = testFetcher ?? fetch;

  try {
    const res = await fetcher(upstreamUrl, {
      headers: {
        'User-Agent': 'Trindade-Backend',
      },
    });

    if (!res.ok) return null;
    const arrayBuffer = await res.arrayBuffer();
    return {
      buffer: Buffer.from(arrayBuffer),
      filename: release.apkName || 'trindade.apk',
    };
  } catch {
    return null;
  }
}
