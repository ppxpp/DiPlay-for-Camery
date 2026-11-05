import {
  isManifest as isManagedManifest,
  mayDeliver,
  type StoredManifest,
} from "./management";
import { isFullRollout, readLatestAll } from "./derived";

export interface Env {
  META: R2Bucket;
  ASSET_BASE_URL: string;
}

interface LegacySnapshot {
  stable: StoredManifest | null;
  beta: StoredManifest | null;
  stableAll?: StoredManifest | null;
  betaAll?: StoredManifest | null;
  invalid?: boolean;
}

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const CLIENT_ID = /^[A-Za-z0-9_-]{1,64}$/;
const ARCH = /^[a-z0-9_-]{1,32}$/;

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "cdn-cache-control": "no-store",
      "access-control-allow-origin": "*",
      "x-content-type-options": "nosniff",
    },
  });
}

function assetUrl(base: string, key: string): string | null {
  const segments = key.split("/");
  if (key.startsWith("/") || segments.some((segment) => segment === ".." || segment === "")) {
    return null;
  }
  return `${base.replace(/\/+$/, "")}/${segments.map(encodeURIComponent).join("/")}`;
}

function isManifest(value: unknown): value is StoredManifest {
  if (!value || typeof value !== "object") return false;
  const manifest = value as Record<string, unknown>;
  if (manifest.schemaVersion !== 1) return false;
  if (typeof manifest.version !== "string" || manifest.version.length === 0) return false;
  if (
    manifest.versionName !== undefined &&
    (typeof manifest.versionName !== "string" || manifest.versionName.length === 0)
  ) {
    return false;
  }
  if (
    typeof manifest.versionCode !== "number" ||
    !Number.isSafeInteger(manifest.versionCode) ||
    manifest.versionCode < 0
  ) {
    return false;
  }
  if (!Array.isArray(manifest.artifacts) || manifest.artifacts.length === 0) return false;
  return manifest.artifacts.every((item) => {
    if (!item || typeof item !== "object") return false;
    const artifact = item as Record<string, unknown>;
    return (
      typeof artifact.name === "string" &&
      artifact.name.length > 0 &&
      typeof artifact.key === "string" &&
      artifact.key.length > 0 &&
      typeof artifact.checksumsKey === "string" &&
      artifact.checksumsKey.length > 0
    );
  });
}

type ManifestLoad =
  | { kind: "ok"; stored: StoredManifest }
  | { kind: "missing" }
  | { kind: "invalid" };

async function loadManifest(env: Env, key: string): Promise<ManifestLoad> {
  const object = await env.META.get(key);
  if (!object) return { kind: "missing" };

  let stored: unknown;
  try {
    stored = await object.json();
  } catch {
    return { kind: "invalid" };
  }
  if (!isManifest(stored) || !isManagedManifest(stored)) return { kind: "invalid" };
  return { kind: "ok", stored };
}

// One derived object keeps the legacy cross-channel lookup at a single read.
async function readLatestAny(
  env: Env,
  appId: string,
  platform: string,
): Promise<LegacySnapshot | null> {
  const object = await env.META.get(`apps/${appId}/${platform}/latest-any.json`);
  if (!object) return null;
  try {
    const value = await object.json<Record<string, unknown>>();
    if (!value || value.schemaVersion !== 1) return null;
    return {
      stable: isManagedManifest(value.stable) ? value.stable : null,
      beta: isManagedManifest(value.beta) ? value.beta : null,
      ...("stableAll" in value ? { stableAll: isManagedManifest(value.stableAll) ? value.stableAll : null } : {}),
      ...("betaAll" in value ? { betaAll: isManagedManifest(value.betaAll) ? value.betaAll : null } : {}),
    };
  } catch {
    return null;
  }
}

// The newest release every client may take, so a client outside a gray pool
// still gets the current full release instead of nothing. A live release that
// is already full needs no extra read; only a grey one has to consult the
// per-channel pointer, and a pointer written by an older deploy falls back to
// scanning the live manifest.
async function fullRolloutRelease(
  env: Env,
  appId: string,
  platform: string,
  channel: string,
  hints: { live?: StoredManifest | null; all?: StoredManifest | null } = {},
): Promise<StoredManifest | null> {
  if (hints.all !== undefined) return hints.all;
  if (hints.live && isFullRollout(hints.live)) return hints.live;
  const pointer = await readLatestAll(env.META, appId, platform, channel);
  if (pointer !== undefined) return pointer;
  const live = await loadManifest(env, `apps/${appId}/${platform}/${channel}/latest.json`);
  return live.kind === "ok" && isFullRollout(live.stored) ? live.stored : null;
}

export async function latestManifestResponse(
  request: Request,
  env: Env,
  appIdValue: string | string[] | undefined,
  platformValue: string | string[] | undefined,
  channelValue: string | string[] | undefined,
): Promise<Response> {
  const appId = first(appIdValue);
  const platform = first(platformValue);
  const requestedChannel = first(channelValue);
  if (!appId || !platform || !requestedChannel || ![appId, platform, requestedChannel].every((value) => SLUG.test(value))) {
    return json({ error: "invalid_path" }, 400);
  }

  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return json({ error: "asset_base_url_not_configured" }, 500);

  const clientIdParam = new URL(request.url).searchParams.get("id")?.trim() || null;
  if (clientIdParam !== null && !CLIENT_ID.test(clientIdParam)) {
    return json({ error: "invalid_client_id" }, 400);
  }
  const clientId = clientIdParam;
  const archParam = new URL(request.url).searchParams.get("arch")?.trim() || null;
  if (archParam !== null && !ARCH.test(archParam)) {
    return json({ error: "invalid_arch" }, 400);
  }
  // The historical versionCode 1000 client queried stable without an id or a
  // channel choice. New clients always send their client id, including the
  // persisted fallback when Android ID is unavailable.
  const legacyStable = requestedChannel === "stable" && !clientId;

  let channel = requestedChannel;
  let stored: StoredManifest;

  if (legacyStable) {
    // The legacy client cannot choose a channel, so serve whichever channel
    // carries the newer full release. Equal versions keep stable first. A
    // release still held to a whitelist or a percentage is never offered here.
    const snapshot = await readLatestAny(env, appId, platform)
      ?? await (async (): Promise<LegacySnapshot> => {
        const [stableLive, betaLive] = await Promise.all([
          loadManifest(env, `apps/${appId}/${platform}/stable/latest.json`),
          loadManifest(env, `apps/${appId}/${platform}/beta/latest.json`),
        ]);
        return {
          stable: stableLive.kind === "ok" ? stableLive.stored : null,
          beta: betaLive.kind === "ok" ? betaLive.stored : null,
          invalid: stableLive.kind === "invalid" || betaLive.kind === "invalid",
        };
      })();
    const [stableRelease, betaRelease] = await Promise.all([
      // A channel without a live manifest has no published release to fall
      // back to, so it is skipped instead of paying for a pointer read.
      snapshot.stable
        ? fullRolloutRelease(env, appId, platform, "stable", { live: snapshot.stable, all: snapshot.stableAll })
        : null,
      snapshot.beta
        ? fullRolloutRelease(env, appId, platform, "beta", { live: snapshot.beta, all: snapshot.betaAll })
        : null,
    ]);
    const candidates: Array<{ channel: string; stored: StoredManifest }> = [];
    if (stableRelease) candidates.push({ channel: "stable", stored: stableRelease });
    if (betaRelease) candidates.push({ channel: "beta", stored: betaRelease });
    if (candidates.length === 0) {
      if (snapshot.invalid) return json({ error: "invalid_manifest" }, 502);
      // The legacy client renders a 404 as a failed check. A 200 response
      // without release fields is parsed as "no update available".
      return json({
        schemaVersion: 1,
        appId,
        platform,
        channel: requestedChannel,
        updateAvailable: false,
      });
    }
    candidates.sort((left, right) => {
      if (left.stored.versionCode !== right.stored.versionCode) {
        return right.stored.versionCode - left.stored.versionCode;
      }
      return left.channel === "stable" ? -1 : 1;
    });
    channel = candidates[0].channel;
    stored = candidates[0].stored;
  } else {
    const loaded = await loadManifest(env, `apps/${appId}/${platform}/${channel}/latest.json`);
    if (loaded.kind === "missing") return json({ error: "not_found" }, 404);
    if (loaded.kind === "invalid") return json({ error: "invalid_manifest" }, 502);
    stored = loaded.stored;
  }

  let management = stored.management ?? { enabled: true, mode: "all" as const, notes: "" };
  if (!management.enabled) return json({ error: "update_unavailable" }, 404);
  if (!legacyStable && !await mayDeliver(management, clientId)) {
    // Outside the gray pool the client falls back to this channel's newest
    // full release, and only reports no update when the channel has none.
    const fallback = await fullRolloutRelease(env, appId, platform, channel);
    if (!fallback) return json({ error: "update_unavailable" }, 404);
    stored = fallback;
    management = stored.management ?? { enabled: true, mode: "all" as const, notes: "" };
  }

  const arch = archParam;
  const selected = arch
    ? stored.artifacts.filter((artifact) => !artifact.arch || artifact.arch === "universal" || artifact.arch === arch)
    : stored.artifacts;
  if (selected.length === 0) return json({ error: "artifact_not_found" }, 404);

  const artifacts = [];
  for (const artifact of selected) {
    const url = assetUrl(base, artifact.key);
    const checksumsUrl = assetUrl(base, artifact.checksumsKey);
    if (!url || !checksumsUrl) return json({ error: "invalid_manifest" }, 502);
    artifacts.push({
      name: artifact.name,
      ...(artifact.arch ? { arch: artifact.arch } : {}),
      url,
      checksumsUrl,
      ...(artifact.sha256 ? { sha256: artifact.sha256 } : {}),
      ...(artifact.size !== undefined ? { size: artifact.size } : {}),
    });
  }

  return json({
    schemaVersion: stored.schemaVersion,
    appId,
    platform,
    channel,
    version: stored.version,
    ...(stored.versionName ? { versionName: stored.versionName } : {}),
    versionCode: stored.versionCode,
    ...(stored.publishedAt ? { publishedAt: stored.publishedAt } : {}),
    mandatory: stored.mandatory ?? false,
    ...(stored.notesUrl ? { notesUrl: stored.notesUrl } : {}),
    ...(management.notes ? { releaseNotes: management.notes } : {}),
    artifacts,
  });
}
