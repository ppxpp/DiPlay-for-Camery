import type { Env, StoredManifest } from "../../../../../../_shared/management";
import { assetUrl, isManifest, json } from "../../../../../../_shared/management";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;

export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) => {
  const appId = first(params.appId);
  const platform = first(params.platform);
  const channel = first(params.channel);
  if (!appId || !platform || !channel || ![appId, platform, channel].every((value) => SLUG.test(value))) return json({ error: "invalid_path" }, 400);
  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return json({ error: "asset_base_url_not_configured" }, 500);
  const object = await env.META.get(`apps/${appId}/${platform}/${channel}/latest.json`);
  if (!object) return json({ error: "not_found" }, 404);
  let manifest: unknown;
  try { manifest = await object.json(); } catch { return json({ error: "invalid_manifest" }, 502); }
  if (!isManifest(manifest)) return json({ error: "invalid_manifest" }, 502);
  const arch = new URL(request.url).searchParams.get("arch")?.trim() || null;
  const selected = arch ? manifest.artifacts.filter((artifact) => !artifact.arch || artifact.arch === "universal" || artifact.arch === arch) : manifest.artifacts;
  if (!selected.length) return json({ error: "artifact_not_found" }, 404);
  const artifacts = selected.map((artifact) => ({
    name: artifact.name,
    ...(artifact.arch ? { arch: artifact.arch } : {}),
    url: assetUrl(base, artifact.key),
    checksumsUrl: assetUrl(base, artifact.checksumsKey),
    ...(artifact.sha256 ? { sha256: artifact.sha256 } : {}),
    ...(artifact.size !== undefined ? { size: artifact.size } : {}),
  }));
  if (artifacts.some((artifact) => !artifact.url || !artifact.checksumsUrl)) return json({ error: "invalid_manifest" }, 502);
  const stored = manifest as StoredManifest;
  return json({ schemaVersion: stored.schemaVersion, appId, platform, channel, version: stored.version,
    versionName: stored.versionName ?? stored.version, versionCode: stored.versionCode, publishedAt: stored.publishedAt,
    mandatory: stored.mandatory ?? false, artifacts });
};
