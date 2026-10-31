import type { Env, StoredManifest } from "../../../../../../_shared/management";
import {
  allReleaseManifests,
  assetUrl,
  isManifest,
  isPublishedRelease,
  jsonWithEtag,
  releasePrefix,
} from "../../../../../../_shared/management";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const ARCH = /^[a-z0-9_-]{1,32}$/;
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;

export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) => {
  const appId = first(params.appId);
  const platform = first(params.platform);
  const channel = first(params.channel);
  if (!appId || !platform || !channel || ![appId, platform, channel].every((value) => SLUG.test(value))) return jsonWithEtag(request, { error: "invalid_path" }, 400);
  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return jsonWithEtag(request, { error: "asset_base_url_not_configured" }, 500);
  const object = await env.META.get(`apps/${appId}/${platform}/${channel}/latest.json`);
  if (!object) return jsonWithEtag(request, { error: "not_found" }, 404);
  let manifest: unknown;
  try { manifest = await object.json(); } catch { return jsonWithEtag(request, { error: "invalid_manifest" }, 502); }
  if (!isManifest(manifest)) return jsonWithEtag(request, { error: "invalid_manifest" }, 502);

  let stored: StoredManifest = manifest;
  if (!isPublishedRelease(stored)) {
    // Keep the last published release visible while a new candidate waits for review.
    const fallback = (await allReleaseManifests(env.META, releasePrefix(appId, platform, channel)))
      .filter(({ manifest: release }) => isPublishedRelease(release))
      .sort((left, right) =>
        (right.manifest.publishedAt ?? "").localeCompare(left.manifest.publishedAt ?? "") ||
        right.manifest.versionCode - left.manifest.versionCode,
      )[0];
    if (!fallback) return jsonWithEtag(request, { error: "not_found" }, 404);
    stored = fallback.manifest;
  }

  const archParam = new URL(request.url).searchParams.get("arch")?.trim() || null;
  if (archParam !== null && !ARCH.test(archParam)) return jsonWithEtag(request, { error: "invalid_arch" }, 400);
  const arch = archParam;
  const selected = arch ? stored.artifacts.filter((artifact) => !artifact.arch || artifact.arch === "universal" || artifact.arch === arch) : stored.artifacts;
  if (!selected.length) return jsonWithEtag(request, { error: "artifact_not_found" }, 404);
  const artifacts = selected.map((artifact) => ({
    name: artifact.name,
    ...(artifact.arch ? { arch: artifact.arch } : {}),
    url: assetUrl(base, artifact.key),
    checksumsUrl: assetUrl(base, artifact.checksumsKey),
    ...(artifact.sha256 ? { sha256: artifact.sha256 } : {}),
    ...(artifact.size !== undefined ? { size: artifact.size } : {}),
  }));
  if (artifacts.some((artifact) => !artifact.url || !artifact.checksumsUrl)) return jsonWithEtag(request, { error: "invalid_manifest" }, 502);
  return jsonWithEtag(request, { schemaVersion: stored.schemaVersion, appId, platform, channel, version: stored.version,
    versionName: stored.versionName ?? stored.version, versionCode: stored.versionCode, publishedAt: stored.publishedAt,
    mandatory: stored.mandatory ?? false, artifacts }, 200, { cacheTtl: 60 });
};