import { unzipSync } from "fflate";
import type { Env } from "./management";

// Anonymous, one-click feedback intake. The upload endpoint is public, so every
// limit here is a defensive boundary: request size, bundle size, expanded size,
// entry count, description length and per-IP rate limiting.
export const FEEDBACK_SCHEMA_VERSION = 1;
export const MAX_REQUEST_BYTES = 6 * 1024 * 1024;
export const MAX_BUNDLE_BYTES = 5 * 1024 * 1024;
export const MAX_DESCRIPTION_CHARS = 2000;
export const MAX_ZIP_ENTRIES = 64;
export const MAX_EXPANDED_BYTES = 20 * 1024 * 1024;
export const MAX_PREVIEW_BYTES = 64 * 1024;
export const MAX_META_FILE_BYTES = 64 * 1024;
export const DEFAULT_FEEDBACK_APP = "diplay";
export const RATE_LIMIT_PER_HOUR = 5;
export const RATE_LIMIT_PER_DAY = 20;

export const FEEDBACK_CATEGORIES = ["connection", "display", "audio", "navigation", "vehicle", "other"] as const;
export type FeedbackCategory = (typeof FEEDBACK_CATEGORIES)[number];

export const FEEDBACK_ENTRIES = ["settings", "connection_failure"] as const;
export type FeedbackEntry = (typeof FEEDBACK_ENTRIES)[number];

export interface FeedbackAppInfo {
  versionName: string;
  versionCode: number;
  channel: string;
}

export interface FeedbackDeviceInfo {
  manufacturer: string;
  model: string;
  board: string;
  androidRelease: string;
  api: number;
}

export interface FeedbackSessionInfo {
  mode: string;
  state: string;
}

export interface FeedbackMeta {
  schemaVersion: 1;
  id: string;
  createdAt: number;
  category: FeedbackCategory;
  description: string;
  entry: FeedbackEntry;
  failureStage?: string;
  app: FeedbackAppInfo;
  device: FeedbackDeviceInfo;
  session: FeedbackSessionInfo;
  bundleBytes: number;
  bundleSha256: string;
}

export interface FeedbackIndex {
  schemaVersion: 1;
  id: string;
  receivedAt: number;
  category: FeedbackCategory;
  description: string;
  entry: FeedbackEntry;
  failureStage?: string;
  app: FeedbackAppInfo;
  device: FeedbackDeviceInfo;
  session: FeedbackSessionInfo;
  bundleKey: string;
  bundleBytes: number;
  bundleSha256: string;
  reportPreview: string;
  previewTruncated: boolean;
}

export interface FeedbackPointer {
  schemaVersion: 1;
  id: string;
  receivedAt: number;
  indexKey: string;
  bundleKey: string;
}

const FEEDBACK_ID = /^[0-9a-f]{32}$/;
const SHA256_HEX = /^[0-9a-f]{64}$/;
// 13 digits is enough for any millisecond timestamp; subtracting from the
// maximum makes lexicographically ascending keys list newest first.
const DESCENDING_BASE = 9_999_999_999_999;

export function isFeedbackId(value: unknown): value is string {
  return typeof value === "string" && FEEDBACK_ID.test(value);
}

export function isFeedbackAppId(value: unknown): value is string {
  return typeof value === "string" && /^[a-z0-9][a-z0-9-]{0,63}$/.test(value);
}

export function feedbackKeys(appId: string, id: string, receivedAt: number): {
  objectKey: string;
  indexKey: string;
  pointerKey: string;
} {
  const stamp = String(DESCENDING_BASE - receivedAt).padStart(13, "0");
  return {
    objectKey: `feedback/${appId}/objects/${id}/bundle.zip`,
    indexKey: `feedback/${appId}/index/${stamp}-${id}.json`,
    pointerKey: `feedback/${appId}/by-id/${id}.json`,
  };
}

export function feedbackIndexPrefix(appId: string): string {
  return `feedback/${appId}/index/`;
}

export function feedbackPointerKey(appId: string, id: string): string {
  return `feedback/${appId}/by-id/${id}.json`;
}

export function feedbackPointerPrefix(appId: string): string {
  return `feedback/${appId}/by-id/`;
}

function text(value: unknown, max: number, min = 0): string | null {
  if (typeof value !== "string" || value.length < min || value.length > max) return null;
  return value;
}

function integer(value: unknown, min = 0): number | null {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min) return null;
  return value;
}

function parseAppInfo(value: unknown): FeedbackAppInfo | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  const versionName = text(input.versionName, 64, 1);
  const versionCode = integer(input.versionCode);
  const channel = text(input.channel, 32, 1);
  if (versionName === null || versionCode === null || channel === null) return null;
  return { versionName, versionCode, channel };
}

function parseDeviceInfo(value: unknown): FeedbackDeviceInfo | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  const manufacturer = text(input.manufacturer, 128, 1);
  const model = text(input.model, 128, 1);
  const board = text(input.board, 128, 1);
  const androidRelease = text(input.androidRelease, 64, 1);
  const api = integer(input.api);
  if (manufacturer === null || model === null || board === null || androidRelease === null || api === null) return null;
  return { manufacturer, model, board, androidRelease, api };
}

function parseSessionInfo(value: unknown): FeedbackSessionInfo | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  const mode = text(input.mode, 64, 1);
  const state = text(input.state, 64, 1);
  if (mode === null || state === null) return null;
  return { mode, state };
}

/**
 * Parses the multipart `meta` JSON into a strict, normalized object. Unknown
 * fields are dropped so nothing unexpected is copied into the index.
 */
export function parseMeta(value: unknown): FeedbackMeta | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  if (input.schemaVersion !== FEEDBACK_SCHEMA_VERSION) return null;
  if (!isFeedbackId(input.id)) return null;
  const createdAt = integer(input.createdAt);
  if (createdAt === null) return null;
  if (!FEEDBACK_CATEGORIES.includes(input.category as FeedbackCategory)) return null;
  const description = text(input.description, MAX_DESCRIPTION_CHARS);
  if (description === null) return null;
  if (!FEEDBACK_ENTRIES.includes(input.entry as FeedbackEntry)) return null;
  const failureStage = input.failureStage === undefined ? undefined : text(input.failureStage, 200);
  if (failureStage === null) return null;
  const app = parseAppInfo(input.app);
  const device = parseDeviceInfo(input.device);
  const session = parseSessionInfo(input.session);
  if (!app || !device || !session) return null;
  const bundleBytes = integer(input.bundleBytes, 1);
  // The hard 5MB ceiling is enforced against the actual upload in the handler so
  // that an oversized request gets 413 rather than 422.
  if (bundleBytes === null) return null;
  if (typeof input.bundleSha256 !== "string" || !SHA256_HEX.test(input.bundleSha256)) return null;
  return {
    schemaVersion: FEEDBACK_SCHEMA_VERSION,
    id: input.id,
    createdAt,
    category: input.category as FeedbackCategory,
    description,
    entry: input.entry as FeedbackEntry,
    ...(failureStage === undefined ? {} : { failureStage }),
    app,
    device,
    session,
    bundleBytes,
    bundleSha256: input.bundleSha256,
  };
}

/** Reads and validates the by-id pointer that maps a random feedback ID to its index. */
export async function readFeedbackPointer(bucket: R2Bucket, appId: string, id: string): Promise<FeedbackPointer | null> {
  const object = await bucket.get(feedbackPointerKey(appId, id));
  if (!object) return null;
  let value: unknown;
  try {
    value = await object.json();
  } catch {
    return null;
  }
  if (!value || typeof value !== "object") return null;
  const input = value as Record<string, unknown>;
  if (input.schemaVersion !== FEEDBACK_SCHEMA_VERSION || input.id !== id) return null;
  if (typeof input.indexKey !== "string" || !input.indexKey.startsWith(feedbackIndexPrefix(appId))) return null;
  if (typeof input.bundleKey !== "string" || !input.bundleKey.startsWith(`feedback/${appId}/objects/`)) return null;
  if (integer(input.receivedAt) === null) return null;
  return {
    schemaVersion: FEEDBACK_SCHEMA_VERSION,
    id,
    receivedAt: input.receivedAt as number,
    indexKey: input.indexKey,
    bundleKey: input.bundleKey,
  };
}

/** Reads an index object and rejects anything that does not look like a feedback record. */
export async function readFeedbackIndex(bucket: R2Bucket, key: string): Promise<FeedbackIndex | null> {
  const object = await bucket.get(key);
  if (!object) return null;
  try {
    const value: unknown = await object.json();
    return isFeedbackIndex(value) ? value : null;
  } catch {
    return null;
  }
}

export function isFeedbackIndex(value: unknown): value is FeedbackIndex {
  if (!value || typeof value !== "object") return false;
  const input = value as Record<string, unknown>;
  if (input.schemaVersion !== FEEDBACK_SCHEMA_VERSION || !isFeedbackId(input.id)) return false;
  if (integer(input.receivedAt) === null) return false;
  if (!FEEDBACK_CATEGORIES.includes(input.category as FeedbackCategory)) return false;
  if (typeof input.description !== "string") return false;
  if (typeof input.bundleKey !== "string" || !input.bundleKey.endsWith(`/objects/${input.id}/bundle.zip`)) return false;
  if (typeof input.bundleSha256 !== "string" || !SHA256_HEX.test(input.bundleSha256)) return false;
  if (typeof input.reportPreview !== "string") return false;
  if (typeof input.previewTruncated !== "boolean") return false;
  return parseAppInfo(input.app) !== null && parseDeviceInfo(input.device) !== null && parseSessionInfo(input.session) !== null;
}

type BundleParseResult =
  | {
    ok: true;
    feedbackJson: Record<string, unknown>;
    reportPreview: string;
    previewTruncated: boolean;
  }
  | { ok: false; error: string };

const REQUIRED_ENTRIES = ["feedback.json", "report.txt", "manifest.json"] as const;

// Paths must stay relative and inside the archive. Directory entries (a
// trailing slash) are tolerated; `..`, `.`, empty segments, backslashes and
// control characters are not.
function unsafeEntryName(name: string): boolean {
  if (!name || name.startsWith("/")) return true;
  if (name.includes("\\") || /[\u0000-\u001f\u007f]/.test(name)) return true;
  const body = name.endsWith("/") ? name.slice(0, -1) : name;
  if (!body) return true;
  return body.split("/").some((segment) => segment === "" || segment === "." || segment === "..");
}

function countEntries(bytes: Uint8Array): { entries: Array<{ name: string; originalSize: number }> } | null {
  const entries: Array<{ name: string; originalSize: number }> = [];
  try {
    // The first pass only walks the zip directory; nothing is decompressed.
    unzipSync(bytes, {
      filter: (file) => {
        entries.push({ name: file.name, originalSize: file.originalSize });
        return false;
      },
    });
  } catch {
    return null;
  }
  return { entries };
}

/**
 * Validates the bundle structure and extracts only `feedback.json`, `report.txt`
 * and `manifest.json`. Log files are never decompressed on the server.
 */
export function parseBundle(bytes: Uint8Array): BundleParseResult {
  if (bytes.byteLength === 0) return { ok: false, error: "empty_bundle" };
  const directory = countEntries(bytes);
  if (!directory) return { ok: false, error: "invalid_zip" };
  const { entries } = directory;
  if (entries.length === 0) return { ok: false, error: "invalid_zip" };
  if (entries.length > MAX_ZIP_ENTRIES) return { ok: false, error: "too_many_entries" };
  if (entries.some((entry) => unsafeEntryName(entry.name))) return { ok: false, error: "unsafe_entry_path" };
  const expanded = entries.reduce((total, entry) => total + Math.max(0, entry.originalSize), 0);
  if (expanded > MAX_EXPANDED_BYTES) return { ok: false, error: "bundle_expands_too_large" };
  const names = new Set(entries.map((entry) => entry.name));
  for (const required of REQUIRED_ENTRIES) {
    if (!names.has(required)) return { ok: false, error: `missing_${required.replace(".", "_")}` };
  }

  let extracted: Record<string, Uint8Array>;
  try {
    extracted = unzipSync(bytes, { filter: (file) => (REQUIRED_ENTRIES as readonly string[]).includes(file.name) });
  } catch {
    return { ok: false, error: "invalid_zip" };
  }

  const feedbackBytes = extracted["feedback.json"];
  const reportBytes = extracted["report.txt"];
  const manifestBytes = extracted["manifest.json"];
  if (!feedbackBytes || !reportBytes || !manifestBytes) return { ok: false, error: "invalid_zip" };
  if (feedbackBytes.byteLength > MAX_META_FILE_BYTES) return { ok: false, error: "feedback_json_too_large" };

  let feedbackJson: unknown;
  try {
    feedbackJson = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(feedbackBytes));
  } catch {
    return { ok: false, error: "invalid_feedback_json" };
  }
  if (!feedbackJson || typeof feedbackJson !== "object" || Array.isArray(feedbackJson)) {
    return { ok: false, error: "invalid_feedback_json" };
  }
  try {
    const manifest: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(manifestBytes));
    if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) return { ok: false, error: "invalid_manifest_json" };
  } catch {
    return { ok: false, error: "invalid_manifest_json" };
  }

  let reportText: string;
  try {
    reportText = new TextDecoder("utf-8", { fatal: true }).decode(reportBytes);
  } catch {
    return { ok: false, error: "report_not_text" };
  }
  if (reportText.includes("\u0000")) return { ok: false, error: "report_not_text" };

  let reportPreview = reportText;
  let previewTruncated = false;
  if (reportBytes.byteLength > MAX_PREVIEW_BYTES) {
    // Cut at a UTF-8 character boundary so the preview stays valid text.
    let cut = MAX_PREVIEW_BYTES;
    while (cut > 0 && (reportBytes[cut] & 0xc0) === 0x80) cut -= 1;
    reportPreview = new TextDecoder("utf-8").decode(reportBytes.subarray(0, cut));
    previewTruncated = true;
  }

  return { ok: true, feedbackJson: feedbackJson as Record<string, unknown>, reportPreview, previewTruncated };
}

/** `feedback.json` repeats identity fields; they must agree with the multipart meta. */
export function bundleMatchesMeta(feedbackJson: Record<string, unknown>, meta: FeedbackMeta): boolean {
  if (feedbackJson.id !== meta.id) return false;
  if (feedbackJson.category !== meta.category) return false;
  if (feedbackJson.schemaVersion !== undefined && feedbackJson.schemaVersion !== FEEDBACK_SCHEMA_VERSION) return false;
  if (feedbackJson.app !== undefined) {
    if (!feedbackJson.app || typeof feedbackJson.app !== "object") return false;
    const app = feedbackJson.app as Record<string, unknown>;
    if (typeof app.versionName === "string" && app.versionName !== meta.app.versionName) return false;
    if (typeof app.channel === "string" && app.channel !== meta.app.channel) return false;
  }
  return true;
}

export async function sha256Hex(bytes: Uint8Array | ArrayBuffer): Promise<string> {
  const source = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const digest = await crypto.subtle.digest("SHA-256", source as BufferSource);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export interface RateLimitVerdict {
  allowed: boolean;
  retryAfter: number;
}

async function readCount(kv: KVNamespace, key: string): Promise<number> {
  const value = Number((await kv.get(key)) ?? "0");
  return Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Per-IP fixed-window limiting: 5 per hour and 20 per day. The client address is
 * hashed before it becomes a KV key, so raw IPs are never persisted; without the
 * KV binding the limiter is skipped and WAF/app limits remain the only control.
 */
export async function rateLimit(env: Env, request: Request, now = Date.now()): Promise<RateLimitVerdict> {
  const kv = env.RATE_KV;
  if (!kv) return { allowed: true, retryAfter: 0 };
  const ip = request.headers.get("cf-connecting-ip")?.trim() || "unknown";
  const client = (await sha256Hex(new TextEncoder().encode(`feedback-rate:${ip}`))).slice(0, 32);
  const hour = Math.floor(now / 3_600_000);
  const day = Math.floor(now / 86_400_000);
  const hourKey = `feedback-rate:h:${client}:${hour}`;
  const dayKey = `feedback-rate:d:${client}:${day}`;
  const [hourCount, dayCount] = await Promise.all([readCount(kv, hourKey), readCount(kv, dayKey)]);
  if (hourCount >= RATE_LIMIT_PER_HOUR) {
    return { allowed: false, retryAfter: 3600 - Math.floor((now % 3_600_000) / 1000) };
  }
  if (dayCount >= RATE_LIMIT_PER_DAY) {
    return { allowed: false, retryAfter: 86_400 - Math.floor((now % 86_400_000) / 1000) };
  }
  await Promise.all([
    kv.put(hourKey, String(hourCount + 1), { expirationTtl: 7200 }),
    kv.put(dayKey, String(dayCount + 1), { expirationTtl: 172_800 }),
  ]);
  return { allowed: true, retryAfter: 0 };
}
