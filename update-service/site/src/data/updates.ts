export type ChannelKey = 'stable' | 'beta';

export type UpdateArtifact = {
  name: string;
  arch?: string;
  url: string;
  checksumsUrl?: string;
  sha256?: string;
  size?: number;
};

export type UpdateManifest = {
  version: string;
  versionName?: string;
  versionCode: number;
  publishedAt?: string;
  artifacts?: UpdateArtifact[];
};

export type UpdateChannel = {
  key: ChannelKey;
  label: string;
  manifest: UpdateManifest;
  artifact: UpdateArtifact;
};

export const UPDATE_API_BASE = 'https://diplay.mgulu.com';

export async function fetchUpdateChannel(key: ChannelKey): Promise<UpdateChannel | null> {
  const response = await fetch(`${UPDATE_API_BASE}/api/v1/apps/diplay/android/${key}/public-latest`, {
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Update API HTTP ${response.status}`);

  const manifest = (await response.json()) as UpdateManifest;
  const artifacts = Array.isArray(manifest.artifacts) ? manifest.artifacts : [];
  const artifact =
    artifacts.find((item) => item.name?.endsWith('.apk') && item.arch === 'universal') ??
    artifacts.find((item) => item.name?.endsWith('.apk'));
  if (!manifest.version || typeof manifest.versionCode !== 'number' || !artifact?.url) {
    throw new Error('Malformed update manifest');
  }

  return {
    key,
    label: key === 'stable' ? '稳定版' : '测试版',
    manifest,
    artifact,
  };
}

export function displayVersion(manifest: UpdateManifest): string {
  const apk = manifest.artifacts?.find((item) => item.name?.toLowerCase().endsWith('.apk'));
  return apk?.name?.replace(/\.apk$/i, '') || manifest.versionName?.trim() || manifest.version;
}

export function displayShortVersion(manifest: UpdateManifest): string {
  const versionName = manifest.versionName?.trim() ?? '';
  const adaptationVersion = versionName.match(/(?:^|_)(\d+\.\d+\.\d+)$/)?.[1];
  return adaptationVersion || manifest.version;
}

export function formatUpdateSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return '';
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
