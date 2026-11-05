import {
  allReleaseManifests,
  isManifest,
  isPublishedRelease,
  putJson,
  readManifest,
  releasePrefix,
  type Env,
  type StoredManifest,
} from "./management";

// Derived read models that keep public endpoints at one bucket read per
// request. They are rebuilt on every publish, enable/disable, rollback and
// delete; readers fall back to the legacy multi-read paths when missing.

export interface ReleaseIndexArtifact {
  name: string;
  arch?: string;
  key: string;
  sha256?: string;
  size?: number;
}

export interface ReleaseIndexEntry {
  version: string;
  versionName: string;
  versionCode: number;
  publishedAt: string | null;
  notes: string;
  artifacts: ReleaseIndexArtifact[];
}

export interface ReleasesIndex {
  schemaVersion: 1;
  updatedAt: string;
  appId: string;
  platform: string;
  channel: string;
  releases: ReleaseIndexEntry[];
}

export interface LatestAnySnapshot {
  schemaVersion: 1;
  updatedAt: string;
  stable: StoredManifest | null;
  beta: StoredManifest | null;
}

export function releasesIndexKey(appId: string, platform: string, channel: string): string {
  return `apps/${appId}/${platform}/${channel}/releases-index.json`;
}

export function latestAnyKey(appId: string, platform: string): string {
  return `apps/${appId}/${platform}/latest-any.json`;
}

export function buildReleaseIndex(appId: string, platform: string, channel: string, manifests: StoredManifest[]): ReleasesIndex {
  const releases = manifests
    .filter(isPublishedRelease)
    .sort((left, right) =>
      (right.publishedAt ?? "").localeCompare(left.publishedAt ?? "") ||
      right.versionCode - left.versionCode,
    )
    .map<ReleaseIndexEntry>((manifest) => ({
      version: manifest.version,
      versionName: manifest.versionName ?? manifest.version,
      versionCode: manifest.versionCode,
      publishedAt: manifest.publishedAt ?? null,
      notes: manifest.management?.notes ?? "",
      artifacts: manifest.artifacts.map((artifact) => ({
        name: artifact.name,
        ...(artifact.arch ? { arch: artifact.arch } : {}),
        key: artifact.key,
        ...(artifact.sha256 ? { sha256: artifact.sha256 } : {}),
        ...(artifact.size !== undefined ? { size: artifact.size } : {}),
      })),
    }));
  return {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    appId,
    platform,
    channel,
    releases,
  };
}

export function pickLatestAny(
  stable: StoredManifest | null,
  beta: StoredManifest | null,
): { channel: string; manifest: StoredManifest } | null {
  const candidates: Array<{ channel: string; manifest: StoredManifest }> = [];
  for (const [channel, manifest] of [["stable", stable], ["beta", beta]] as const) {
    if (manifest && isManifest(manifest) && manifest.management?.enabled !== false) {
      candidates.push({ channel, manifest });
    }
  }
  if (candidates.length === 0) return null;
  candidates.sort((left, right) => {
    if (left.manifest.versionCode !== right.manifest.versionCode) {
      return right.manifest.versionCode - left.manifest.versionCode;
    }
    return left.channel === "stable" ? -1 : 1;
  });
  return candidates[0];
}

export async function rebuildReleasesIndex(env: Env, appId: string, platform: string, channel: string): Promise<void> {
  const manifests = (await allReleaseManifests(env.META, releasePrefix(appId, platform, channel)))
    .map(({ manifest }) => manifest);
  const index = buildReleaseIndex(appId, platform, channel, manifests);
  await putJson(env.META, releasesIndexKey(appId, platform, channel), index);
}

export async function rebuildLatestAny(env: Env, appId: string, platform: string): Promise<void> {
  const [stable, beta] = await Promise.all([
    readManifest(env.META, `apps/${appId}/${platform}/stable/latest.json`),
    readManifest(env.META, `apps/${appId}/${platform}/beta/latest.json`),
  ]);
  const snapshot: LatestAnySnapshot = {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    stable,
    beta,
  };
  await putJson(env.META, latestAnyKey(appId, platform), snapshot);
}

export async function rebuildDerived(env: Env, appId: string, platform: string, channel: string): Promise<void> {
  try {
    await Promise.all([
      rebuildReleasesIndex(env, appId, platform, channel),
      rebuildLatestAny(env, appId, platform),
    ]);
  } catch (error) {
    // Derived objects are an optimisation; never fail the mutation because of them.
    console.warn("rebuild derived objects failed", error);
  }
}
