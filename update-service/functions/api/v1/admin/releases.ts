import { isAdmin } from "../../../_shared/admin-auth";
import type { Env, StoredManifest } from "../../../_shared/management";
import { allReleaseManifests, json, parseManagement, readManifest, releasePrefix, writeManifest } from "../../../_shared/management";

const SEGMENT = /^[a-z0-9][a-z0-9-]{0,63}$/;

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
  return json({ currentVersion: current?.version ?? null, releases: releases.map(({ manifest }) => manifest) });
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
  if (![app, platform, channel].every((part) => SEGMENT.test(part)) || !/^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$/.test(version)) return json({ error: "invalid_release" }, 400);
  const management = parseManagement(input.management);
  if (!management) return json({ error: "invalid_management" }, 400);
  const releaseKey = `${releasePrefix(app, platform, channel)}${version}.json`;
  const manifest = await readManifest(env.META, releaseKey);
  if (!manifest) return json({ error: "release_not_found" }, 404);
  manifest.management = management;
  await writeManifest(env.META, releaseKey, manifest);
  const latestKey = `apps/${app}/${platform}/${channel}/latest.json`;
  const latest = await readManifest(env.META, latestKey);
  if (latest?.version === version) await writeManifest(env.META, latestKey, manifest);
  return json({ ok: true, release: manifest });
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
  if (![app, platform, channel].every((part) => SEGMENT.test(part)) || !/^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$/.test(version)) return json({ error: "invalid_release" }, 400);

  const latestKey = `apps/${app}/${platform}/${channel}/latest.json`;
  const previous = await readManifest(env.META, latestKey);
  const target = await readManifest(env.META, `${releasePrefix(app, platform, channel)}${version}.json`);
  if (!target) return json({ error: "release_not_found" }, 404);
  if (previous && previous.version !== target.version) {
    const previousKey = `${releasePrefix(app, platform, channel)}${previous.version}.json`;
    const previousSnapshot: StoredManifest = { ...previous, management: { ...(previous.management ?? { enabled: false, mode: "all", notes: "" }), enabled: false } };
    await writeManifest(env.META, previousKey, previousSnapshot);
  }
  await writeManifest(env.META, latestKey, target);
  return json({ ok: true, currentVersion: target.version, previousVersion: previous?.version ?? null });
};
