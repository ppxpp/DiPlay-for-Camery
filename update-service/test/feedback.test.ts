import { strToU8, zipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { createSession, sessionCookieName } from "../functions/_shared/admin-auth";
import { feedbackKeys, sha256Hex } from "../functions/_shared/feedback";
import { onRequestPost as upload } from "../functions/api/v1/apps/[appId]/feedback";
import { onRequestGet as listFeedback } from "../functions/api/v1/admin/feedback/index";
import { onRequestDelete, onRequestGet as detailFeedback } from "../functions/api/v1/admin/feedback/[id]";
import { onRequestGet as downloadBundle } from "../functions/api/v1/admin/feedback/[id]/bundle";

const APP = "diplay";
const BASE = "https://upd.example.com";
const ADMIN_USERNAME = "admin";
const ADMIN_SECRET = "feedback-test-session-secret";

function feedbackId(seed: number): string {
  return seed.toString(16).padStart(32, "0");
}

// Blob/File want an ArrayBuffer, while fflate returns Uint8Array<ArrayBufferLike>.
function arrayBufferOf(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer as ArrayBuffer;
}

type Bucket = ReturnType<typeof memoryBucket>;

// Binary-safe in-memory R2 stand-in: get/put/delete/list/head, with the parts
// of R2ObjectBody the handlers use (body, arrayBuffer, json, size).
function memoryBucket(initial: Record<string, Uint8Array> = {}) {
  const store = new Map<string, Uint8Array>(Object.entries(initial));
  const bytesOf = async (value: unknown): Promise<Uint8Array> => {
    if (typeof value === "string") return new TextEncoder().encode(value);
    if (value instanceof Uint8Array) return value.slice();
    if (value instanceof ArrayBuffer) return new Uint8Array(value.slice(0));
    if (value instanceof Blob) return new Uint8Array(await value.arrayBuffer());
    if (value && typeof value === "object" && "arrayBuffer" in value) {
      return new Uint8Array(await (value as Blob).arrayBuffer());
    }
    throw new Error("unsupported put value");
  };
  return {
    store,
    async get(key: string) {
      const bytes = store.get(key);
      if (!bytes) return null;
      return {
        key,
        size: bytes.byteLength,
        body: new Blob([arrayBufferOf(bytes)]).stream(),
        async arrayBuffer() { return bytes.slice().buffer; },
        async text() { return new TextDecoder().decode(bytes); },
        async json() { return JSON.parse(new TextDecoder().decode(bytes)); },
      };
    },
    async head(key: string) {
      const bytes = store.get(key);
      return bytes ? { key, size: bytes.byteLength } : null;
    },
    async put(key: string, value: unknown) {
      store.set(key, await bytesOf(value));
    },
    async delete(key: string) { store.delete(key); },
    async list({ prefix, cursor, limit }: { prefix: string; cursor?: string; limit?: number }) {
      const keys = [...store.keys()].filter((key) => key.startsWith(prefix)).sort();
      const start = cursor ? Number(cursor) : 0;
      const size = limit ?? 1000;
      const page = keys.slice(start, start + size);
      const truncated = start + page.length < keys.length;
      return {
        objects: page.map((key) => ({ key })),
        truncated,
        ...(truncated ? { cursor: String(start + page.length) } : {}),
      };
    },
  };
}

function memoryKv() {
  const store = new Map<string, string>();
  return {
    store,
    get: async (key: string) => store.get(key) ?? null,
    put: async (key: string, value: string) => { store.set(key, value); },
    delete: async (key: string) => { store.delete(key); },
  };
}

function bundleBytes(
  id: string,
  overrides: Record<string, unknown> = {},
  extraEntries: Record<string, Uint8Array> = {},
  report = "DiPlay diagnostic report\nstate: failed\n",
): Uint8Array {
  const feedback = {
    schemaVersion: 1,
    id,
    category: "connection",
    description: "cannot connect",
    entry: "settings",
    app: { versionName: "0.3.0_beta_0.1.0", versionCode: 4000, channel: "beta" },
    device: { manufacturer: "BYD", model: "Camry", board: "k6789v1_64", androidRelease: "12", api: 31 },
    session: { mode: "wireless", state: "failed" },
    ...overrides,
  };
  return zipSync({
    "feedback.json": strToU8(JSON.stringify(feedback)),
    "report.txt": strToU8(report),
    "manifest.json": strToU8(JSON.stringify({ schemaVersion: 1, entries: [{ name: "report.txt", status: "included" }] })),
    ...extraEntries,
  });
}

async function metaFor(id: string, bytes: Uint8Array, overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: 1,
    id,
    createdAt: 1_760_000_000_000,
    category: "connection",
    description: "cannot connect",
    entry: "settings",
    app: { versionName: "0.3.0_beta_0.1.0", versionCode: 4000, channel: "beta" },
    device: { manufacturer: "BYD", model: "Camry", board: "k6789v1_64", androidRelease: "12", api: 31 },
    session: { mode: "wireless", state: "failed" },
    bundleBytes: bytes.byteLength,
    bundleSha256: await sha256Hex(bytes),
    ...overrides,
  };
}

interface CallOptions {
  ip?: string;
  kv?: ReturnType<typeof memoryKv>;
  appId?: string;
  meta?: unknown;
  bytes?: Uint8Array | null;
  skipMeta?: boolean;
}

async function post(bucket: Bucket, options: CallOptions = {}): Promise<Response> {
  const appId = options.appId ?? APP;
  const form = new FormData();
  if (!options.skipMeta && options.meta !== undefined) {
    form.set("meta", typeof options.meta === "string" ? options.meta : JSON.stringify(options.meta));
  }
  if (options.bytes) {
    form.set("bundle", new File([arrayBufferOf(options.bytes)], "bundle.zip", { type: "application/zip" }));
  }
  const headers: Record<string, string> = {};
  if (options.ip) headers["cf-connecting-ip"] = options.ip;
  const request = new Request(`${BASE}/api/v1/apps/${appId}/feedback`, { method: "POST", headers, body: form });
  return upload({
    request,
    env: { META: bucket, ...(options.kv ? { RATE_KV: options.kv } : {}) },
    params: { appId },
  } as never);
}

async function uploadValid(bucket: Bucket, seed: number, options: CallOptions = {}): Promise<{ id: string; bytes: Uint8Array; response: Response }> {
  const id = feedbackId(seed);
  const bytes = bundleBytes(id);
  const response = await post(bucket, { ...options, meta: await metaFor(id, bytes), bytes });
  return { id, bytes, response };
}

async function adminContext(bucket: Bucket) {
  const env = {
    META: bucket,
    ADMIN_USERNAME,
    ADMIN_PASSWORD_HASH: "unused",
    ADMIN_SESSION_SECRET: ADMIN_SECRET,
  };
  const token = await createSession(env as never);
  return {
    env: env as never,
    headers: { cookie: `${sessionCookieName}=${token}` },
  };
}

function call(
  handler: unknown,
  env: never,
  options: { url?: string; method?: string; headers?: Record<string, string>; params?: Record<string, string> },
) {
  return (handler as (context: never) => Promise<Response>)({
    request: new Request(options.url ?? `${BASE}/api/v1/admin/feedback`, {
      method: options.method ?? "GET",
      headers: options.headers ?? {},
    }),
    env,
    params: options.params ?? {},
  } as never);
}

describe("feedback upload", () => {
  it("archives a valid bundle under objects/index/by-id keys", async () => {
    const bucket = memoryBucket();
    const { id, bytes, response } = await uploadValid(bucket, 1);

    expect(response.status).toBe(201);
    const body = (await response.json()) as any;
    expect(body.ok).toBe(true);
    expect(body.id).toBe(id);
    expect(typeof body.receivedAt).toBe("number");

    const keys = feedbackKeys(APP, id, body.receivedAt);
    expect(bucket.store.has(keys.objectKey)).toBe(true);
    expect(bucket.store.has(keys.indexKey)).toBe(true);
    expect(bucket.store.has(keys.pointerKey)).toBe(true);
    expect(bucket.store.get(keys.objectKey)).toEqual(bytes);
    expect(keys.objectKey).toBe(`feedback/${APP}/objects/${id}/bundle.zip`);
    expect(keys.indexKey.startsWith(`feedback/${APP}/index/`)).toBe(true);

    const index = JSON.parse(new TextDecoder().decode(bucket.store.get(keys.indexKey)!));
    expect(index.id).toBe(id);
    expect(index.category).toBe("connection");
    expect(index.reportPreview).toContain("state: failed");
    expect(index.previewTruncated).toBe(false);
    expect(index.bundleBytes).toBe(bytes.byteLength);
  });

  it("treats a repeated feedback id as an idempotent success", async () => {
    const bucket = memoryBucket();
    const first = await uploadValid(bucket, 2);
    expect(first.response.status).toBe(201);

    const form = new FormData();
    form.set("meta", JSON.stringify(await metaFor(first.id, first.bytes)));
    form.set("bundle", new File([arrayBufferOf(first.bytes)], "bundle.zip"));
    const response = await upload({
      request: new Request(`${BASE}/api/v1/apps/${APP}/feedback`, { method: "POST", body: form }),
      env: { META: bucket },
      params: { appId: APP },
    } as never);

    expect(response.status).toBe(200);
    expect((await response.json()) as any).toMatchObject({ ok: true, id: first.id, duplicate: true });
    expect([...bucket.store.keys()].filter((key) => key.startsWith(`feedback/${APP}/objects/`))).toHaveLength(1);
  });

  it("rejects a bundle over 5MB with 413", async () => {
    const bucket = memoryBucket();
    const bytes = new Uint8Array(5 * 1024 * 1024 + 1);
    bytes.set([0x50, 0x4b, 0x03, 0x04]);
    const response = await post(bucket, { bytes, meta: await metaFor(feedbackId(3), bytes) });
    expect(response.status).toBe(413);
    expect((await response.json()) as any).toMatchObject({ error: "bundle_too_large" });
  });

  it("rejects malformed meta fields with 422", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(4);
    const bytes = bundleBytes(id);

    const upper = await post(bucket, { bytes, meta: await metaFor(id, bytes, { id: "A".repeat(32) }) });
    expect(upper.status).toBe(422);

    const shortId = await post(bucket, { bytes, meta: await metaFor(id, bytes, { id: "abc" }) });
    expect(shortId.status).toBe(422);

    const category = await post(bucket, { bytes, meta: await metaFor(id, bytes, { category: "wifi" }) });
    expect(category.status).toBe(422);

    const entry = await post(bucket, { bytes, meta: await metaFor(id, bytes, { entry: "crash" }) });
    expect(entry.status).toBe(422);

    const longDescription = await post(bucket, {
      bytes,
      meta: await metaFor(id, bytes, { description: "x".repeat(2001) }),
    });
    expect(longDescription.status).toBe(422);
    expect((await longDescription.json()) as any).toMatchObject({ error: "description_too_long" });

    const malformed = await post(bucket, { bytes, meta: "{not json" });
    expect(malformed.status).toBe(422);

    const missingMeta = await post(bucket, { bytes, skipMeta: true });
    expect(missingMeta.status).toBe(422);
  });

  it("rejects sha and size mismatches", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(5);
    const bytes = bundleBytes(id);

    const badSha = await post(bucket, { bytes, meta: await metaFor(id, bytes, { bundleSha256: "0".repeat(64) }) });
    expect(badSha.status).toBe(422);
    expect((await badSha.json()) as any).toMatchObject({ error: "bundle_sha256_mismatch" });

    const badSize = await post(bucket, { bytes, meta: await metaFor(id, bytes, { bundleBytes: bytes.byteLength + 1 }) });
    expect(badSize.status).toBe(422);
    expect((await badSize.json()) as any).toMatchObject({ error: "bundle_size_mismatch" });
  });

  it("rejects a bundle whose feedback.json disagrees with meta", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(6);
    const bytes = bundleBytes(id, { id: feedbackId(600) });
    const response = await post(bucket, { bytes, meta: await metaFor(id, bytes) });
    expect(response.status).toBe(422);
    expect((await response.json()) as any).toMatchObject({ error: "bundle_meta_mismatch" });
  });

  it("rejects non-zip payloads and missing required entries", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(7);

    const random = new Uint8Array(512).fill(7);
    const notZip = await post(bucket, { bytes: random, meta: await metaFor(id, random) });
    expect(notZip.status).toBe(422);
    expect((await notZip.json()) as any).toMatchObject({ error: "invalid_zip" });

    const feedback = { schemaVersion: 1, id, category: "connection" };
    const withoutReport = zipSync({
      "feedback.json": strToU8(JSON.stringify(feedback)),
      "manifest.json": strToU8("{}"),
    });
    const missingReport = await post(bucket, { bytes: withoutReport, meta: await metaFor(id, withoutReport) });
    expect(missingReport.status).toBe(422);
    expect((await missingReport.json()) as any).toMatchObject({ error: "missing_report_txt" });

    const withoutFeedback = zipSync({ "report.txt": strToU8("x"), "manifest.json": strToU8("{}") });
    const missingFeedback = await post(bucket, { bytes: withoutFeedback, meta: await metaFor(id, withoutFeedback) });
    expect(missingFeedback.status).toBe(422);
    expect((await missingFeedback.json()) as any).toMatchObject({ error: "missing_feedback_json" });
  });

  it("rejects path traversal and excessive entry counts", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(8);

    const traversal = bundleBytes(id, {}, { "../evil.txt": strToU8("x") });
    const traversalResponse = await post(bucket, { bytes: traversal, meta: await metaFor(id, traversal) });
    expect(traversalResponse.status).toBe(422);
    expect((await traversalResponse.json()) as any).toMatchObject({ error: "unsafe_entry_path" });

    const nested = bundleBytes(id, {}, { "logs/../secret.txt": strToU8("x") });
    const nestedResponse = await post(bucket, { bytes: nested, meta: await metaFor(id, nested) });
    expect(nestedResponse.status).toBe(422);

    const many: Record<string, Uint8Array> = {};
    for (let index = 0; index < 62; index += 1) many[`logs/${index}.log`] = strToU8(String(index));
    const tooMany = bundleBytes(id, {}, many);
    const tooManyResponse = await post(bucket, { bytes: tooMany, meta: await metaFor(id, tooMany) });
    expect(tooManyResponse.status).toBe(422);
    expect((await tooManyResponse.json()) as any).toMatchObject({ error: "too_many_entries" });
  });

  it("rejects a bundle that expands beyond 20MB", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(9);
    const expanded = bundleBytes(id, {}, { "logs/huge.log": new Uint8Array(21 * 1024 * 1024) });
    expect(expanded.byteLength).toBeLessThan(5 * 1024 * 1024);
    const response = await post(bucket, { bytes: expanded, meta: await metaFor(id, expanded) });
    expect(response.status).toBe(422);
    expect((await response.json()) as any).toMatchObject({ error: "bundle_expands_too_large" });
  });

  it("truncates long report previews at 64KB", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(10);
    const report = "a".repeat(80 * 1024);
    const bytes = bundleBytes(id, {}, {}, report);
    const response = await post(bucket, { bytes, meta: await metaFor(id, bytes) });
    expect(response.status).toBe(201);
    const receivedAt = ((await response.json()) as any).receivedAt as number;

    const index = JSON.parse(new TextDecoder().decode(bucket.store.get(feedbackKeys(APP, id, receivedAt).indexKey)!));
    expect(index.previewTruncated).toBe(true);
    expect(index.reportPreview.length).toBeLessThanOrEqual(64 * 1024);
    expect(index.reportPreview).toBe("a".repeat(64 * 1024));
  });

  it("returns 429 with Retry-After after five uploads per IP per hour", async () => {
    const bucket = memoryBucket();
    const kv = memoryKv();
    for (let seed = 20; seed < 25; seed += 1) {
      const result = await uploadValid(bucket, seed, { kv, ip: "203.0.113.7" });
      expect(result.response.status).toBe(201);
    }
    const blocked = await uploadValid(bucket, 25, { kv, ip: "203.0.113.7" });
    expect(blocked.response.status).toBe(429);
    expect(blocked.response.headers.get("retry-after")).toBeTruthy();
    expect((await blocked.response.json()) as any).toMatchObject({ error: "rate_limited" });
    expect([...bucket.store.keys()].some((key) => key.includes(feedbackId(25)))).toBe(false);

    // A different client address keeps its own budget.
    const other = await uploadValid(bucket, 26, { kv, ip: "203.0.113.8" });
    expect(other.response.status).toBe(201);
  });

  it("rejects an invalid app id", async () => {
    const bucket = memoryBucket();
    const id = feedbackId(11);
    const bytes = bundleBytes(id);
    const response = await post(bucket, { appId: "Bad App", bytes, meta: await metaFor(id, bytes) });
    expect(response.status).toBe(400);
  });
});

describe("admin feedback API", () => {
  it("requires an admin session", async () => {
    const bucket = memoryBucket();
    const env = { META: bucket, ADMIN_USERNAME, ADMIN_PASSWORD_HASH: "unused", ADMIN_SESSION_SECRET: ADMIN_SECRET } as never;
    const id = feedbackId(30);
    expect((await call(listFeedback, env, { url: `${BASE}/api/v1/admin/feedback` })).status).toBe(401);
    expect((await call(detailFeedback, env, { url: `${BASE}/api/v1/admin/feedback/${id}`, params: { id } })).status).toBe(401);
    expect((await call(downloadBundle, env, { url: `${BASE}/api/v1/admin/feedback/${id}/bundle`, params: { id } })).status).toBe(401);
    expect((await call(onRequestDelete, env, { method: "DELETE", url: `${BASE}/api/v1/admin/feedback/${id}`, params: { id } })).status).toBe(401);
  });

  it("lists newest first without the report preview", async () => {
    const bucket = memoryBucket();
    const first = await uploadValid(bucket, 31);
    await new Promise((resolve) => setTimeout(resolve, 2));
    const second = await uploadValid(bucket, 32);
    const { env, headers } = await adminContext(bucket);

    const response = await call(listFeedback, env, { headers, url: `${BASE}/api/v1/admin/feedback?limit=50` });
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.items).toHaveLength(2);
    expect(body.items[0].id).toBe(second.id);
    expect(body.items[1].id).toBe(first.id);
    expect(body.items[0].reportPreview).toBeUndefined();
    expect(body.items[0].previewTruncated).toBe(false);
    expect(body.truncated).toBe(false);
  });

  it("returns the index with the report preview on detail", async () => {
    const bucket = memoryBucket();
    const { id } = await uploadValid(bucket, 33);
    const { env, headers } = await adminContext(bucket);

    const response = await call(detailFeedback, env, { headers, url: `${BASE}/api/v1/admin/feedback/${id}`, params: { id } });
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.id).toBe(id);
    expect(body.reportPreview).toContain("state: failed");
    expect(body.description).toBe("cannot connect");

    const missing = feedbackId(3333);
    const notFound = await call(detailFeedback, env, { headers, url: `${BASE}/api/v1/admin/feedback/${missing}`, params: { id: missing } });
    expect(notFound.status).toBe(404);
  });

  it("streams the bundle as an attachment with nosniff", async () => {
    const bucket = memoryBucket();
    const { id, bytes } = await uploadValid(bucket, 34);
    const { env, headers } = await adminContext(bucket);

    const response = await call(downloadBundle, env, { headers, url: `${BASE}/api/v1/admin/feedback/${id}/bundle`, params: { id } });
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/zip");
    expect(response.headers.get("content-disposition")).toBe(`attachment; filename="diplay-feedback-${id}.zip"`);
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.get("cache-control")).toBe("no-store");
    const downloaded = new Uint8Array(await response.arrayBuffer());
    expect(downloaded).toEqual(bytes);
  });

  it("deletes the bundle, index and pointer and then reports 404", async () => {
    const bucket = memoryBucket();
    const uploaded = await uploadValid(bucket, 35);
    const { env, headers } = await adminContext(bucket);
    const receivedAt = ((await uploaded.response.json()) as any).receivedAt as number;
    const keys = feedbackKeys(APP, uploaded.id, receivedAt);

    const response = await call(onRequestDelete, env, {
      method: "DELETE",
      headers,
      url: `${BASE}/api/v1/admin/feedback/${uploaded.id}`,
      params: { id: uploaded.id },
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(bucket.store.has(keys.objectKey)).toBe(false);
    expect(bucket.store.has(keys.indexKey)).toBe(false);
    expect(bucket.store.has(keys.pointerKey)).toBe(false);

    const again = await call(onRequestDelete, env, {
      method: "DELETE",
      headers,
      url: `${BASE}/api/v1/admin/feedback/${uploaded.id}`,
      params: { id: uploaded.id },
    });
    expect(again.status).toBe(404);
  });
});
