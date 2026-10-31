import type { Env } from "../../../../../../_shared/management";
import {
  allReleaseManifests,
  assetUrl,
  isPublishedRelease,
  jsonWithEtag,
  releasePrefix,
} from "../../../../../../_shared/management";
import { releasesIndexKey, type ReleasesIndex } from "../../../../../../_shared/derived";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 500;
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;

function readLimit(request: Request): number {
  const raw = new URL(request.url).searchParams.get("limit")?.trim();
  if (!raw || !/^[0-9]{1,4}$/.test(raw)) return DEFAULT_LIMIT;
  return Math.min(MAX_LIMIT, Math.max(1, Number(raw)));
}

// The index is written by the publisher and by admin mutations; its absence
// only costs the legacy multi-read fallback.
async function readReleasesIndex(
  env: Env,
  appId: string,
  platform: string,
  channel: string,
): Promise<ReleasesIndex | null> {
  const object = await env.META.get(releasesIndexKey(appId, platform, channel));
  if (!object) return null;
  try {
    const value = await object.json<ReleasesIndex>();
    if (!value || value.schemaVersion !== 1 || !Array.isArray(value.releases)) return null;
    return value;
  } catch {
    return null;
  }
}

export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) => {
  const appId = first(params.appId);
  const platform = first(params.platform);
  const channel = first(params.channel);
  if (!appId || !platform || !channel || ![appId, platform, channel].every((value) => SLUG.test(value))) return jsonWithEtag(request, { error: "invalid_path" }, 400);
  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return jsonWithEtag(request, { error: "asset_base_url_not_configured" }, 500);
  const limit = readLimit(request);

  const index = await readReleasesIndex(env, appId, platform, channel);
  if (index) {
    const releases = index.releases.slice(0, limit).map((entry) => ({
      version: entry.version,
      versionName: entry.versionName,
      versionCode: entry.versionCode,
      publishedAt: entry.publishedAt,
      notes: entry.notes,
      artifacts: entry.artifacts.map((artifact) => ({
        name: artifact.name,
        arch: artifact.arch,
        url: assetUrl(base, artifact.key),
        sha256: artifact.sha256,
        size: artifact.size,
      })),
    }));
    return jsonWithEtag(request, { releases });
  }

  // Unpublished candidates stay out of the public history; published versions keep their entry.
  const releases = (await allReleaseManifests(env.META, releasePrefix(appId, platform, channel)))
    .filter(({ manifest }) => isPublishedRelease(manifest))
    .slice(0, limit);
  return jsonWithEtag(request, { releases: releases.map(({ manifest }) => ({
    version: manifest.version,
    versionName: manifest.versionName ?? manifest.version,
    versionCode: manifest.versionCode,
    publishedAt: manifest.publishedAt ?? null,
    notes: manifest.management?.notes ?? "",
    artifacts: manifest.artifacts.map((artifact) => ({
      name: artifact.name,
      arch: artifact.arch,
      url: assetUrl(base, artifact.key),
      sha256: artifact.sha256,
      size: artifact.size,
    })),
  })) });
};
