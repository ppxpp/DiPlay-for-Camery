import type { Env } from "../../../../../../_shared/management";
import { allReleaseManifests, assetUrl, json, releasePrefix } from "../../../../../../_shared/management";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;

export const onRequestGet: PagesFunction<Env> = async ({ env, params }) => {
  const appId = first(params.appId);
  const platform = first(params.platform);
  const channel = first(params.channel);
  if (!appId || !platform || !channel || ![appId, platform, channel].every((value) => SLUG.test(value))) return json({ error: "invalid_path" }, 400);
  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return json({ error: "asset_base_url_not_configured" }, 500);
  // Unpublished candidates stay out of the public history until an admin enables delivery.
  const releases = (await allReleaseManifests(env.META, releasePrefix(appId, platform, channel)))
    .filter(({ manifest }) => manifest.management?.enabled !== false);
  return json({ releases: releases.map(({ manifest }) => ({
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
