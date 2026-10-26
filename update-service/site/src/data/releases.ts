export type ChannelKey = 'stable' | 'beta';

export type ReleaseArtifact = {
  name: string;
  url: string | null;
  size?: number;
  sha256?: string;
};

export type Release = {
  version: string;
  versionName: string;
  versionCode: number;
  publishedAt: string | null;
  notes: string;
  artifacts: ReleaseArtifact[];
};

export async function fetchReleases(channel: ChannelKey, base = ''): Promise<Release[]> {
  const response = await fetch(`${base}/api/v1/apps/diplay/android/${channel}/releases`, {
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`请求失败（${response.status}）`);
  const body = (await response.json()) as { releases?: Release[] };
  return Array.isArray(body.releases) ? body.releases : [];
}

export async function fetchReleasesForBuild(
  base = 'https://diplay.mgulu.com',
): Promise<Record<ChannelKey, Release[]>> {
  const [stable, beta] = await Promise.allSettled([
    fetchReleases('stable', base),
    fetchReleases('beta', base),
  ]);
  return {
    stable: stable.status === 'fulfilled' ? stable.value : [],
    beta: beta.status === 'fulfilled' ? beta.value : [],
  };
}
