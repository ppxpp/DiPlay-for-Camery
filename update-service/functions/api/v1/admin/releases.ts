import { isAdmin } from "../../../_shared/admin-auth";
import type { Env, StoredManifest } from "../../../_shared/management";
import { allReleaseManifests, json, parseManagement, readManifest, releasePrefix, writeManifest } from "../../../_shared/management";

const SEGMENT = /^[a-z0-9][a-z0-9-]{0,63}$/;
// Snapshot keys use the display version when present, so releases that share an
// app version stay independently addressable.
const RELEASE_KEY = /^[^\u0000-\u001f\u007f/\\]{1,200}$/;

function releaseKeyOf(manifest: StoredManifest): string {
  return manifest.versionName ?? manifest.version;
}

function requestedReleaseKey(input: Record<string, unknown>): string | null {
  const value = typeof input.releaseKey === "string" ? input.releaseKey
    : typeof input.versionName === "string" ? input.versionName
      : typeof input.version === "string" ? input.version : "";
  if (!RELEASE_KEY.test(value) || value === "." || value === "..") return null;
  return value;
}

async function findSnapshotKey(bucket: R2Bucket, prefix: string, releaseKey: string): Promise<string | null> {
  const direct = `${prefix}${releaseKey}.json`;
  if (await readManifest(bucket, direct)) return direct;
  // Older releases were stored under the app version instead of versionName.
  const releases = await allReleaseManifests(bucket, prefix);
  return releases.find(({ manifest }) => releaseKeyOf(manifest) === releaseKey)?.key ?? null;
}

async function snapshotKeyFor(bucket: R2Bucket, prefix: string, manifest: StoredManifest): Promise<string> {
  return await findSnapshotKey(bucket, prefix, releaseKeyOf(manifest)) ?? `${prefix}${manifest.version}.json`;
}

export const onRequestGet: PagesFunction<Env> = async ({ request, env }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  const url = new URL(request.url);
  const app = url.searchParams.get("app") ?? "diplay";
  const platform = url.searchParams.get("platform") ?? "android";
  const channel = url.searchParams.get("channel") ?? "stable";
  if (![app, platform, channel].every((part) => SEGMENT.test(part))) return json({ error: "invalid_path" }, 400);
  const prefix = releasePrefix(app, platform, channel);
  const current = await readManifest(env.META, `apps/${app}/${platform}/${channel}/latest.json`);
  const releases = await allReleaseManifests(env.META, prefix);
  return json({
    currentVersion: current ? releaseKeyOf(current) : null,
    releases: releases.map(({ manifest }) => ({ ...manifest, releaseKey: releaseKeyOf(manifest) })),
  });
};

export const onRequestPut: PagesFunction<Env> = async ({ request, env }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  let body: unknown;
  try { body = await request.json(); } catch { return json({ error: "invalid_request" }, 400); }
  if (!body || typeof body !== "object") return json({ error: "invalid_request" }, 400);
  const input = body as Record<string, unknown>;
  const app = typeof input.app === "string" ? input.app : "diplay";
  const platform = typeof input.platform === "string" ? input.platform : "android";
  const channel = typeof input.channel === "string" ? input.channel : "stable";
  const version = typeof input.version === "string" ? input.version : "";
  const releaseKey = requestedReleaseKey(input);
  if (![app, platform, channel].every((part) => SEGMENT.test(part)) || !releaseKey) return json({ error: "invalid_release" }, 400);
  const management = parseManagement(input.management);
  if (!management) return json({ error: "invalid_management" }, 400);
  const releaseKeyPath = await findSnapshotKey(env.META, releasePrefix(app, platform, channel), releaseKey);
  if (!releaseKeyPath) return json({ error: "release_not_found" }, 404);
  const manifest = await readManifest(env.META, releaseKeyPath);
  if (!manifest) return json({ error: "release_not_found" }, 404);
  if (version && manifest.version !== version) return json({ error: "release_not_found" }, 404);
  const wasPublished = manifest.management?.published === true || manifest.management?.enabled === true;
  const nextManagement = { ...management };
  delete nextManagement.published;
  if (wasPublished || management.enabled) nextManagement.published = true;
  manifest.management = nextManagement;
  await writeManifest(env.META, releaseKeyPath, manifest);
  const latestKey = `apps/${app}/${platform}/${channel}/latest.json`;
  const latest = await readManifest(env.META, latestKey);
  if (latest && releaseKeyOf(latest) === releaseKey) await writeManifest(env.META, latestKey, manifest);
  return json({ ok: true, releaseKey, release: manifest });
};

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401);
  let body: unknown;
  try { body = await request.json(); } catch { return json({ error: "invalid_request" }, 400); }
  if (!body || typeof body !== "object") return json({ error: "invalid_request" }, 400);
  const input = body as Record<string, unknown>;
  const app = typeof input.app === "string" ? input.app : "diplay";
  const platform = typeof input.platform === "string" ? input.platform : "android";
  const channel = typeof input.channel === "string" ? input.channel : "stable";
  const version = typeof input.version === "string" ? input.version : "";
  const releaseKey = requestedReleaseKey(input);
  if (![app, platform, channel].every((part) => SEGMENT.test(part)) || !releaseKey) return json({ error: "invalid_release" }, 400);

  const prefix = releasePrefix(app, platform, channel);
  const latestKey = `apps/${app}/${platform}/${channel}/latest.json`;
  const previous = await readManifest(env.META, latestKey);
  const targetKey = await findSnapshotKey(env.META, prefix, releaseKey);
  if (!targetKey) return json({ error: "release_not_found" }, 404);
  const target = await readManifest(env.META, targetKey);
  if (!target) return json({ error: "release_not_found" }, 404);
  if (version && target.version !== version) return json({ error: "release_not_found" }, 404);
  if (previous && releaseKeyOf(previous) !== releaseKey) {
    const previousSnapshot: StoredManifest = { ...previous, management: { ...(previous.management ?? { enabled: false, mode: "all", notes: "" }), enabled: false } };
    await writeManifest(env.META, await snapshotKeyFor(env.META, prefix, previous), previousSnapshot);
  }
  await writeManifest(env.META, latestKey, target);
  return json({ ok: true, currentVersion: releaseKey, previousVersion: previous ? releaseKeyOf(previous) : null });
};
