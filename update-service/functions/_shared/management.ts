export interface ReleaseManagement {
  enabled: boolean;
  /** Set once an admin first enables delivery; stays true so history keeps every published version. */
  published?: boolean;
  mode: "all" | "percentage" | "whitelist";
  percentage?: number;
  ids?: string[];
  notes: string;
}

/** Published releases stay visible after delivery is turned off; candidates never were published. */
export function isPublishedRelease(manifest: StoredManifest): boolean {
  return manifest.management?.published === true || manifest.management?.enabled !== false;
}

export interface StoredArtifact {
  name: string;
  arch?: string;
  key: string;
  checksumsKey: string;
  sha256?: string;
  size?: number;
}

export interface StoredManifest {
  schemaVersion: number;
  version: string;
  versionName?: string;
  versionCode: number;
  publishedAt?: string;
  mandatory?: boolean;
  notesUrl?: string;
  management?: ReleaseManagement;
  artifacts: StoredArtifact[];
}

export interface Env {
  META: R2Bucket;
  RELEASE_ASSETS: R2Bucket;
  ASSET_BASE_URL: string;
  ADMIN_USERNAME: string;
  ADMIN_PASSWORD_HASH: string;
  ADMIN_SESSION_SECRET: string;
  RATE_KV?: KVNamespace;
}

export function json(body: unknown, status = 200, options: { cors?: boolean } = {}): Response {
  const headers: Record<string, string> = {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "cdn-cache-control": "no-store",
    "x-content-type-options": "nosniff",
  };
  if (options.cors !== false) headers["access-control-allow-origin"] = "*";
  return new Response(JSON.stringify(body), { status, headers });
}

// Serves a JSON body with a weak ETag so conditional clients and edge caches
// can revalidate instead of re-downloading the whole payload.
export async function jsonWithEtag(
  request: Request,
  body: unknown,
  status = 200,
  options: { cors?: boolean } = {},
): Promise<Response> {
  const text = JSON.stringify(body);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  const etag = `W/"${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")}"`;
  if (request.headers.get("if-none-match") === etag) {
    return new Response(null, {
      status: 304,
      headers: { etag, "cache-control": "no-store", "cdn-cache-control": "no-store" },
    });
  }
  const response = json(body, status, options);
  response.headers.set("etag", etag);
  return response;
}

export function releasePrefix(appId: string, platform: string, channel: string): string {
  return `apps/${appId}/${platform}/${channel}/releases/`;
}

export function defaultManagement(): ReleaseManagement {
  return { enabled: false, mode: "all", notes: "" };
}

export function isManifest(value: unknown): value is StoredManifest {
  if (!value || typeof value !== "object") return false;
  const manifest = value as Record<string, unknown>;
  if (manifest.schemaVersion !== 1 || typeof manifest.version !== "string" || !manifest.version) return false;
  if (manifest.versionName !== undefined && (typeof manifest.versionName !== "string" || !manifest.versionName)) return false;
  if (typeof manifest.versionCode !== "number" || !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode < 0) return false;
  if (manifest.management !== undefined) {
    const management = manifest.management as Record<string, unknown> | null;
    if (!management || typeof management !== "object" || typeof management.enabled !== "boolean" ||
      (management.published !== undefined && typeof management.published !== "boolean") ||
      !["all", "percentage", "whitelist"].includes(String(management.mode)) ||
      typeof management.notes !== "string" || management.notes.length > 10000) return false;
    if (management.mode === "percentage" && (!Number.isInteger(management.percentage) || Number(management.percentage) < 0 || Number(management.percentage) > 100)) return false;
    if (management.mode === "whitelist" && (!Array.isArray(management.ids) || management.ids.some((id) => typeof id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(id)))) return false;
  }
  if (!Array.isArray(manifest.artifacts) || !manifest.artifacts.length) return false;
  return manifest.artifacts.every((item) => {
    if (!item || typeof item !== "object") return false;
    const artifact = item as Record<string, unknown>;
    return typeof artifact.name === "string" && typeof artifact.key === "string" &&
      typeof artifact.checksumsKey === "string";
  });
}

export function assetUrl(base: string, key: string): string | null {
  const segments = key.split("/");
  if (key.startsWith("/") || segments.some((segment) => segment === ".." || segment === "")) return null;
  return `${base.replace(/\/+$/, "")}/${segments.map(encodeURIComponent).join("/")}`;
}

export async function readManifest(bucket: R2Bucket, key: string): Promise<StoredManifest | null> {
  const object = await bucket.get(key);
  if (!object) return null;
  try {
    const value: unknown = await object.json();
    return isManifest(value) ? value : null;
  } catch {
    return null;
  }
}

export async function putJson(bucket: R2Bucket, key: string, value: unknown): Promise<void> {
  await bucket.put(key, `${JSON.stringify(value, null, 2)}\n`, {
    httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" },
  });
}

export async function writeManifest(bucket: R2Bucket, key: string, manifest: StoredManifest): Promise<void> {
  await putJson(bucket, key, manifest);
}

export async function allReleaseManifests(
  bucket: R2Bucket,
  prefix: string,
): Promise<Array<{ key: string; manifest: StoredManifest }>> {
  const results: Array<{ key: string; manifest: StoredManifest }> = [];
  let cursor: string | undefined;
  do {
    const page = await bucket.list({ prefix, cursor, limit: 1000 });
    for (const object of page.objects) {
      if (!object.key.endsWith(".json")) continue;
      const manifest = await readManifest(bucket, object.key);
      if (manifest) results.push({ key: object.key, manifest });
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return results.sort((a, b) => (b.manifest.publishedAt ?? "").localeCompare(a.manifest.publishedAt ?? ""));
}

export function parseManagement(value: unknown): ReleaseManagement | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  if (typeof input.enabled !== "boolean" || !["all", "percentage", "whitelist"].includes(String(input.mode))) return null;
  if (typeof input.notes !== "string" || input.notes.length > 10000) return null;
  if (input.mode === "percentage" && (!Number.isInteger(input.percentage) || Number(input.percentage) < 0 || Number(input.percentage) > 100)) return null;
  if (input.mode === "whitelist" && (!Array.isArray(input.ids) || input.ids.length > 50000 || input.ids.some((id) => typeof id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(id)))) return null;
  return {
    enabled: input.enabled,
    ...(typeof input.published === "boolean" ? { published: input.published } : {}),
    mode: input.mode as ReleaseManagement["mode"],
    ...(input.mode === "percentage" ? { percentage: Number(input.percentage) } : {}),
    ...(input.mode === "whitelist" ? { ids: [...new Set(input.ids as string[])] } : {}),
    notes: input.notes,
  };
}

export async function mayDeliver(management: ReleaseManagement, id: string | null): Promise<boolean> {
  if (!management.enabled) return false;
  if (management.mode === "all") return true;
  if (!id) return false;
  if (management.mode === "whitelist") return management.ids?.includes(id) ?? false;
  if ((management.percentage ?? 0) >= 100) return true;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(id));
  const bucket = new DataView(digest).getUint32(0, false) % 100;
  return bucket < (management.percentage ?? 0);
}
