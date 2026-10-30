import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { LatestReleaseResponse } from './app.schema.js';

export const TRINDADE_APK_DOWNLOAD_URL =
  'https://trindademasas.duckdns.org/api/app/download-apk';

export const FALLBACK_UPSTREAM_APK_URL =
  'https://github.com/wilkinbarban/Trindade/releases/download/v0.6.1/trindade-0.6.1.apk';

/**
 * The signed APK this project ships.
 *
 * Releases are not published to GitHub while the plant is still testing them, so the file lives next to the
 * backend and is served from disk. Resolving it from this module and not from `process.cwd()` keeps the same
 * path in `tsx` (src/modules/app) and in the image (dist/modules/app), three levels below `packages/backend`.
 */
export const LOCAL_APK_FILENAME = 'trindade-0.6.1.apk';

const LOCAL_APK_PATH = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  '..',
  'apk',
  LOCAL_APK_FILENAME,
);

let localApkPath: string | null = LOCAL_APK_PATH;

/** A test decides whether a local APK is in play; the default is the file the project ships. */
export function setLocalApkPathForTest(next: string | null): void {
  localApkPath = next;
}

/** The APK on disk, with the size the page reports, or nothing when it is not there yet. */
function localApk(): { path: string; size: number; modifiedAt: string } | null {
  if (!localApkPath) return null;
  try {
    const stat = statSync(localApkPath);
    if (!stat.isFile() || stat.size === 0) return null;
    return { path: localApkPath, size: stat.size, modifiedAt: stat.mtime.toISOString() };
  } catch {
    return null;
  }
}

export const FALLBACK_RELEASE: LatestReleaseResponse = {
  version: 'v0.6.1',
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

  // The file the project ships wins over the release feed: there is no published release to ask about, and a
  // phone that downloads an older binary because GitHub still answers v0.5.1 is the failure this avoids.
  const local = localApk();
  if (local) {
    return {
      ...FALLBACK_RELEASE,
      apkSize: local.size,
      publishedAt: local.modifiedAt,
    };
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
  const local = localApk();
  if (local) {
    try {
      return { buffer: readFileSync(local.path), filename: LOCAL_APK_FILENAME };
    } catch {
      // The file was there a moment ago: fall through to the release feed instead of failing the download.
    }
  }

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
