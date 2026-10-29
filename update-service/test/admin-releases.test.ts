import { describe, expect, it } from "vitest";
import { createSession, sessionCookieName } from "../functions/_shared/admin-auth";
import { onRequestDelete, onRequestGet, onRequestPost, onRequestPut } from "../functions/api/v1/admin/releases";

const ADMIN_SECRET = "test-session-secret";
const ADMIN_USERNAME = "admin";
const BASE_URL = "https://upd.example.com/api/v1/admin/releases";
const PREFIX = "apps/diplay/android/beta/releases/";
const LATEST_KEY = "apps/diplay/android/beta/latest.json";
const OLD_NAME = "0.2.21_凯美瑞A导航适配版_0.2.0";
const NEW_NAME = "0.2.21_凯美瑞A导航适配版_0.2.1";

function manifest(versionName: string, versionCode: number, notes = "") {
  return {
    schemaVersion: 1,
    version: "0.2.21",
    versionName,
    versionCode,
    publishedAt: "2026-10-26T07:39:36Z",
    mandatory: false,
    management: { enabled: false, mode: "all", notes },
    artifacts: [{
      name: `${versionName}.apk`,
      arch: "universal",
      key: `${PREFIX}${versionName}/${versionName}.apk`,
      checksumsKey: `${PREFIX}${versionName}/SHA256SUMS.txt`,
      sha256: "a".repeat(64),
      size: 1,
    }],
  };
}

function memoryBucket(initial: Record<string, unknown>) {
  const store = new Map(Object.entries(initial).map(([key, value]) => [key, JSON.stringify(value)]));
  return {
    store,
    get: async (key: string) => store.has(key)
      ? { key, json: async () => JSON.parse(store.get(key)!) }
      : null,
    put: async (key: string, body: string) => { store.set(key, body); },
    delete: async (key: string) => { store.delete(key); },
    list: async ({ prefix }: { prefix: string }) => ({
      objects: [...store.keys()].filter((key) => key.startsWith(prefix)).sort().map((key) => ({ key })),
      truncated: false,
    }),
  };
}

async function context(bucket: ReturnType<typeof memoryBucket>, assets = memoryBucket({})) {
  const env = {
    META: bucket as never,
    RELEASE_ASSETS: assets as never,
    ASSET_BASE_URL: "https://dl.example.com",
    ADMIN_USERNAME,
    ADMIN_PASSWORD_HASH: "unused",
    ADMIN_SESSION_SECRET: ADMIN_SECRET,
  };
  const token = await createSession(env as never);
  return {
    env: env as never,
    headers: { cookie: `${sessionCookieName}=${token}`, "content-type": "application/json" },
  };
}

function call(handler: typeof onRequestPut, env: never, headers: Record<string, string>, body?: unknown) {
  const init: RequestInit = { method: "GET", headers };
  if (body !== undefined) {
    init.method = "PUT";
    init.body = JSON.stringify(body);
  }
  return handler({ request: new Request(`${BASE_URL}?channel=beta`, init), env, params: {} } as never);
}

function stored(bucket: ReturnType<typeof memoryBucket>, key: string) {
  return JSON.parse(bucket.store.get(key)!);
}

function deleteCall(env: never, headers: Record<string, string>, query: string) {
  return onRequestDelete({
    request: new Request(`${BASE_URL}?${query}`, { method: "DELETE", headers }),
    env,
    params: {},
  } as never);
}

describe("admin release management", () => {
  it("exposes the versionName release key and uses it for the current release", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${OLD_NAME}.json`]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestGet as never, env, headers);
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.currentVersion).toBe(OLD_NAME);
    expect(body.releases.map((release: { releaseKey: string }) => release.releaseKey).sort())
      .toEqual([NEW_NAME, OLD_NAME].sort());
  });

  it("saves a non-current release under its own key without touching latest", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${OLD_NAME}.json`]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestPut as never, env, headers, {
      channel: "beta",
      releaseKey: NEW_NAME,
      version: "0.2.21",
      management: { enabled: true, mode: "all", notes: "新版本说明" },
    });
    expect(response.status).toBe(200);

    expect(stored(bucket, `${PREFIX}${NEW_NAME}.json`).management)
      .toEqual({ enabled: true, published: true, mode: "all", notes: "新版本说明" });
    expect(stored(bucket, `${PREFIX}${OLD_NAME}.json`).management)
      .toEqual({ enabled: false, mode: "all", notes: "" });
    expect(stored(bucket, LATEST_KEY).versionName).toBe(OLD_NAME);
    expect(stored(bucket, LATEST_KEY).management.notes).toBe("");
  });

  it("updates the latest pointer only when the edited release is current", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}${OLD_NAME}.json`]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestPut as never, env, headers, {
      channel: "beta",
      releaseKey: NEW_NAME,
      version: "0.2.21",
      management: { enabled: true, mode: "all", notes: "当前版本说明" },
    });
    expect(response.status).toBe(200);
    expect(stored(bucket, LATEST_KEY).management.notes).toBe("当前版本说明");
  });

  it("saves a release whose snapshot still uses the legacy version key", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}0.2.21.json`]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestPut as never, env, headers, {
      channel: "beta",
      releaseKey: OLD_NAME,
      version: "0.2.21",
      management: { enabled: true, mode: "all", notes: "旧版本补充说明" },
    });
    expect(response.status).toBe(200);
    expect(stored(bucket, `${PREFIX}0.2.21.json`).management.notes).toBe("旧版本补充说明");
    expect(stored(bucket, LATEST_KEY).versionName).toBe(NEW_NAME);
  });

  it("rolls back to a versionName release and disables the previous snapshot", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}${OLD_NAME}.json`]: manifest(OLD_NAME, 2000, "旧版本说明"),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestPost as never, env, headers, {
      channel: "beta",
      releaseKey: OLD_NAME,
      version: "0.2.21",
    });
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.currentVersion).toBe(OLD_NAME);
    expect(stored(bucket, LATEST_KEY).versionName).toBe(OLD_NAME);
    expect(stored(bucket, LATEST_KEY).management).toMatchObject({ enabled: true, published: true });
    expect(stored(bucket, `${PREFIX}${OLD_NAME}.json`).management).toMatchObject({ enabled: true, published: true });
    expect(stored(bucket, `${PREFIX}${NEW_NAME}.json`).management.enabled).toBe(false);
  });

  it("deletes a non-current release and its uploaded files", async () => {
    const assets = memoryBucket({
      [`${PREFIX}${OLD_NAME}/${OLD_NAME}.apk`]: "apk",
      [`${PREFIX}${OLD_NAME}/SHA256SUMS.txt`]: "sums",
    });
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}${OLD_NAME}.json`]: manifest(OLD_NAME, 2000),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket, assets);

    const response = await deleteCall(env, headers, `channel=beta&releaseKey=${encodeURIComponent(OLD_NAME)}&version=0.2.21`);

    expect(response.status).toBe(200);
    expect(bucket.store.has(`${PREFIX}${OLD_NAME}.json`)).toBe(false);
    expect(stored(bucket, LATEST_KEY).versionName).toBe(NEW_NAME);
    expect(assets.store.size).toBe(0);
  });

  it("deletes the current release and repoints to the newest remaining release", async () => {
    const previous = { ...manifest(OLD_NAME, 2000), management: { enabled: true, mode: "all", notes: "" } };
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}${OLD_NAME}.json`]: previous,
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await deleteCall(env, headers, `channel=beta&releaseKey=${encodeURIComponent(NEW_NAME)}&version=0.2.21`);

    expect(response.status).toBe(200);
    expect(bucket.store.has(`${PREFIX}${NEW_NAME}.json`)).toBe(false);
    expect(stored(bucket, LATEST_KEY).versionName).toBe(OLD_NAME);
  });

  it("removes the pointer when the last release is deleted", async () => {
    const bucket = memoryBucket({
      [LATEST_KEY]: manifest(NEW_NAME, 2001),
      [`${PREFIX}${NEW_NAME}.json`]: manifest(NEW_NAME, 2001),
    });
    const { env, headers } = await context(bucket);

    const response = await deleteCall(env, headers, `channel=beta&releaseKey=${encodeURIComponent(NEW_NAME)}&version=0.2.21`);

    expect(response.status).toBe(200);
    expect(bucket.store.has(LATEST_KEY)).toBe(false);
    expect(bucket.store.has(`${PREFIX}${NEW_NAME}.json`)).toBe(false);
  });

  it("rejects deleting an unknown release", async () => {
    const bucket = memoryBucket({ [LATEST_KEY]: manifest(NEW_NAME, 2001) });
    const { env, headers } = await context(bucket);

    const response = await deleteCall(env, headers, `channel=beta&releaseKey=missing`);

    expect(response.status).toBe(404);
  });

  it("keeps a previously enabled release published after delivery is turned off", async () => {
    const current = { ...manifest(NEW_NAME, 2001), management: { enabled: true, mode: "all", notes: "" } };
    const bucket = memoryBucket({
      [LATEST_KEY]: current,
      [`${PREFIX}${NEW_NAME}.json`]: current,
    });
    const { env, headers } = await context(bucket);

    const response = await call(onRequestPut as never, env, headers, {
      channel: "beta",
      releaseKey: NEW_NAME,
      version: "0.2.21",
      management: { enabled: false, mode: "all", notes: "" },
    });

    expect(response.status).toBe(200);
    expect(stored(bucket, `${PREFIX}${NEW_NAME}.json`).management)
      .toEqual({ enabled: false, published: true, mode: "all", notes: "" });
    expect(stored(bucket, LATEST_KEY).management)
      .toEqual({ enabled: false, published: true, mode: "all", notes: "" });
  });
});
