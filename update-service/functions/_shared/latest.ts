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

  const clientId = new URL(request.url).searchParams.get("id")?.trim() || null;
  // The historical versionCode 1000 client queried stable without an id or a
  // channel choice. New clients always send their client id, including the
  // persisted fallback when Android ID is unavailable.
  const legacyStable = requestedChannel === "stable" && !clientId;
  const channel = legacyStable ? "beta" : requestedChannel;

  const object = await env.META.get(`apps/${appId}/${platform}/${channel}/latest.json`);
  if (!object) return json({ error: "not_found" }, 404);

  let stored: unknown;
  try {
    stored = await object.json();
  } catch {
    return json({ error: "invalid_manifest" }, 502);
  }
  if (!isManifest(stored) || !isManagedManifest(stored)) return json({ error: "invalid_manifest" }, 502);

  const management = stored.management ?? { enabled: true, mode: "all" as const, notes: "" };
  if (!management.enabled) return json({ error: "update_unavailable" }, 404);

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

  const arch = new URL(request.url).searchParams.get("arch")?.trim() || null;
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
