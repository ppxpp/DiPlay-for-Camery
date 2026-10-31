import { isManifest as isManagedManifest } from "./management";

export interface Env {
  META: R2Bucket;
  ASSET_BASE_URL: string;
}

interface StoredArtifact {
  name: string;
  arch?: string;
  key: string;
  checksumsKey: string;
  sha256?: string;
  size?: number;
}

interface StoredManifest {
  schemaVersion: number;
  version: string;
  versionName?: string;
  versionCode: number;
  publishedAt?: string;
  mandatory?: boolean;
  notesUrl?: string;
  management?: {
    enabled: boolean;
    mode: "all" | "percentage" | "whitelist";
    percentage?: number;
    ids?: string[];
    notes?: string;
  };
  artifacts: StoredArtifact[];
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
): Promise<{ stable: StoredManifest | null; beta: StoredManifest | null } | null> {
  const object = await env.META.get(`apps/${appId}/${platform}/latest-any.json`);
  if (!object) return null;
  try {
    const value = await object.json<Record<string, unknown>>();
    if (!value || value.schemaVersion !== 1) return null;
    return {
      stable: isManagedManifest(value.stable) ? value.stable : null,
      beta: isManagedManifest(value.beta) ? value.beta : null,
    };
  } catch {
    return null;
  }
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
    // The legacy client cannot choose a channel, so serve whichever published
    // channel carries the newer version. Equal versions keep stable first.
    let stable: ManifestLoad;
    let beta: ManifestLoad;
    const snapshot = await readLatestAny(env, appId, platform);
    if (snapshot) {
      stable = snapshot.stable ? { kind: "ok", stored: snapshot.stable } : { kind: "missing" };
      beta = snapshot.beta ? { kind: "ok", stored: snapshot.beta } : { kind: "missing" };
    } else {
      [stable, beta] = await Promise.all([
        loadManifest(env, `apps/${appId}/${platform}/stable/latest.json`),
        loadManifest(env, `apps/${appId}/${platform}/beta/latest.json`),
      ]);
    }
    const candidates: Array<{ channel: string; stored: StoredManifest }> = [];
    for (const [candidateChannel, loaded] of [
      ["stable", stable],
      ["beta", beta],
    ] as const) {
      if (loaded.kind === "ok" && loaded.stored.management?.enabled !== false) {
        candidates.push({ channel: candidateChannel, stored: loaded.stored });
      }
    }
    if (candidates.length === 0) {
      if (stable.kind === "invalid" || beta.kind === "invalid") {
        return json({ error: "invalid_manifest" }, 502);
      }
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

  const management = stored.management ?? { enabled: true, mode: "all" as const, notes: "" };
  if (!legacyStable && !management.enabled) return json({ error: "update_unavailable" }, 404);

  if (!legacyStable) {
    if (management.mode === "whitelist" && (!clientId || !management.ids?.includes(clientId))) {
      return json({ error: "update_unavailable" }, 404);
    }
    if (management.mode === "percentage") {
      const percentage = management.percentage ?? 0;
      if (percentage < 100) {
        if (!clientId) return json({ error: "update_unavailable" }, 404);
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(clientId));
        const bucket = new DataView(digest).getUint32(0, false) % 100;
        if (bucket >= percentage) return json({ error: "update_unavailable" }, 404);
      }
    }
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
