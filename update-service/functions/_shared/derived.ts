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
  // Newest full-rollout release per channel. Absent when the snapshot predates
  // the pointer, which readers treat as "not derived yet" rather than "none".
  stableAll?: StoredManifest | null;
  betaAll?: StoredManifest | null;
}

export interface LatestAllSnapshot {
  schemaVersion: 1;
  updatedAt: string;
  manifest: StoredManifest | null;
}

export function releasesIndexKey(appId: string, platform: string, channel: string): string {
  return `apps/${appId}/${platform}/${channel}/releases-index.json`;
}

export function latestAnyKey(appId: string, platform: string): string {
  return `apps/${appId}/${platform}/latest-any.json`;
}

export function latestAllKey(appId: string, platform: string, channel: string): string {
  return `apps/${appId}/${platform}/${channel}/latest-all.json`;
}

// A release whose mode does not hold it back: full rollout, or a percentage
// that already covers every client. `enabled` is deliberately not part of this
// test, because publishing a newer release sets the previous snapshot back to
// `enabled: false` without making it undeliverable history.
export function isUnrestricted(manifest: StoredManifest): boolean {
  const management = manifest.management;
  if (!management) return true;
  if (management.mode === "all") return true;
  return management.mode === "percentage" && (management.percentage ?? 0) >= 100;
}

// What a client that sent no identifier may take: an open release that is not
// switched off.
export function isOpenDelivery(manifest: StoredManifest): boolean {
  return manifest.management?.enabled !== false && isUnrestricted(manifest);
}

// The newest release every client is entitled to. `isPublishedRelease` keeps
// never-delivered candidates out; the caller excludes the current release so a
// switched-off release cannot fall back to itself.
export function pickLatestAll(manifests: StoredManifest[]): StoredManifest | null {
  const candidates = manifests.filter((manifest) => isPublishedRelease(manifest) && isUnrestricted(manifest));
  if (candidates.length === 0) return null;
  return candidates.sort((left, right) =>
    (right.publishedAt ?? "").localeCompare(left.publishedAt ?? "") ||
    right.versionCode - left.versionCode,
  )[0];
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

// undefined means the pointer object does not exist yet, which is different
// from a pointer that says no full-rollout release exists.
export async function readLatestAll(
  bucket: R2Bucket,
  appId: string,
  platform: string,
  channel: string,
): Promise<StoredManifest | null | undefined> {
  const object = await bucket.get(latestAllKey(appId, platform, channel));
  if (!object) return undefined;
  try {
    const value = await object.json<Record<string, unknown>>();
    if (!value || value.schemaVersion !== 1) return undefined;
    if (value.manifest === null) return null;
    return isManifest(value.manifest) ? value.manifest : undefined;
  } catch {
    return undefined;
  }
}

async function rebuildLatestAll(
  env: Env,
  appId: string,
  platform: string,
  channel: string,
  current: StoredManifest | null,
): Promise<StoredManifest | null> {
  const manifests = (await allReleaseManifests(env.META, releasePrefix(appId, platform, channel)))
    .map(({ manifest }) => manifest)
    // The current release is judged on its own; the pointer answers "what is
    // this channel's full release besides the one being delivered now", so a
    // release the operator just switched off never falls back to itself.
    .filter((manifest) => !current || manifest.version !== current.version);
  const manifest = pickLatestAll(manifests);
  const snapshot: LatestAllSnapshot = { schemaVersion: 1, updatedAt: new Date().toISOString(), manifest };
  await putJson(env.META, latestAllKey(appId, platform, channel), snapshot);
  return manifest;
}

async function rebuildLatestAny(
  env: Env,
  appId: string,
  platform: string,
  live: { stable: StoredManifest | null; beta: StoredManifest | null },
  all: { stable: StoredManifest | null | undefined; beta: StoredManifest | null | undefined },
): Promise<void> {
  const snapshot: LatestAnySnapshot = {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    stable: live.stable,
    beta: live.beta,
    // Only write a pointer the rebuild actually resolved; an unknown one must
    // stay absent so readers fall back instead of reporting "no full release".
    ...(all.stable !== undefined ? { stableAll: all.stable } : {}),
    ...(all.beta !== undefined ? { betaAll: all.beta } : {}),
  };
  await putJson(env.META, latestAnyKey(appId, platform), snapshot);
}

export async function rebuildDerived(env: Env, appId: string, platform: string, channel: string): Promise<void> {
  try {
    const [stableLive, betaLive] = await Promise.all([
      readManifest(env.META, `apps/${appId}/${platform}/stable/latest.json`),
      readManifest(env.META, `apps/${appId}/${platform}/beta/latest.json`),
    ]);
    const mutated = await rebuildLatestAll(
      env,
      appId,
      platform,
      channel,
      channel === "stable" ? stableLive : betaLive,
    );
    const otherChannel = channel === "stable" ? "beta" : "stable";
    const other = await readLatestAll(env.META, appId, platform, otherChannel);
    const all = channel === "stable" ? { stable: mutated, beta: other } : { stable: other, beta: mutated };
    await Promise.all([
      rebuildReleasesIndex(env, appId, platform, channel),
      rebuildLatestAny(env, appId, platform, { stable: stableLive, beta: betaLive }, all),
    ]);
  } catch (error) {
    // Derived objects are an optimisation; never fail the mutation because of them.
    console.warn("rebuild derived objects failed", error);
  }
}
