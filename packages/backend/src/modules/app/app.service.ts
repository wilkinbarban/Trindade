import type { LatestReleaseResponse } from './app.schema.js';

export const FALLBACK_RELEASE: LatestReleaseResponse = {
  version: 'v0.5.0',
  apkUrl: 'https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk',
  apkName: 'trindade-0.5.0.apk',
  apkSize: null,
  publishedAt: null,
  webUrl: 'https://trindademasas.duckdns.org',
};

export const GITHUB_LATEST_RELEASE_URL =
  'https://api.github.com/repos/wilkinbarban/Trindade/releases/latest';

export const RELEASE_CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutos en memoria

interface CachedRelease {
  data: LatestReleaseResponse;
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

    const apkUrl =
      apkAsset?.browser_download_url ||
      `https://github.com/wilkinbarban/Trindade/releases/download/${tagName}/trindade-${cleanTag}.apk`;

    const apkName = apkAsset?.name || `trindade-${cleanTag}.apk`;
    const apkSize = typeof apkAsset?.size === 'number' ? apkAsset.size : null;
    const publishedAt = payload.published_at || null;

    const releaseData: LatestReleaseResponse = {
      version: tagName,
      apkUrl,
      apkName,
      apkSize,
      publishedAt,
      webUrl: 'https://trindademasas.duckdns.org',
    };

    cachedRelease = {
      data: releaseData,
      expiresAt: now + RELEASE_CACHE_TTL_MS,
    };

    return releaseData;
  } catch {
    if (cachedRelease) return cachedRelease.data;
    return FALLBACK_RELEASE;
  }
}
