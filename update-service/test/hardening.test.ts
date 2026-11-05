import { describe, expect, it } from "vitest";
import { latestManifestResponse } from "../functions/_shared/latest";
import { onRequestPost as login } from "../functions/api/v1/admin/login";
import { onRequestGet as publicReleases } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/releases";

const PASSWORD = "correct horse battery staple";
const HASH = "000102030405060708090a0b0c0d0e0f:49d49c25f597846209f0d92e7770ab64e1c75e94b4ce6c509265ee67175d2a1e:100000";

function latestCall(url: string) {
  return {
    request: new Request(url),
    env: { ASSET_BASE_URL: "https://dl.example.com", META: { get: async () => null } },
  };
}

async function latest(url: string) {
  const { request, env } = latestCall(url);
  return latestManifestResponse(request, env as never, "diplay", "android", "stable");
}

function loginContext(body: unknown, rateKv?: { get: (k: string) => Promise<string | null>; put: (k: string, v: string, o?: unknown) => Promise<void>; delete: (k: string) => Promise<void> }) {
  const request = new Request("https://upd.example.com/api/v1/admin/login", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.9" },
    body: JSON.stringify(body),
  });
  return {
    request,
    env: {
      ADMIN_USERNAME: "admin",
      ADMIN_PASSWORD_HASH: HASH,
      ADMIN_SESSION_SECRET: "s".repeat(48),
      ...(rateKv ? { RATE_KV: rateKv } : {}),
    },
  } as never;
}

function memoryKv(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial));
  return {
    store,
    get: async (key: string) => store.get(key) ?? null,
    put: async (key: string, value: string) => { store.set(key, value); },
    delete: async (key: string) => { store.delete(key); },
  };
}

describe("latest input validation", () => {
  it("rejects malformed client ids before touching the bucket", async () => {
    const response = await latest("https://upd.example.com/api/v1/apps/diplay/android/stable/latest?id=%20bad%20id");
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid_client_id" });
  });

  it("accepts a bounded client id and proceeds", async () => {
    const response = await latest("https://upd.example.com/api/v1/apps/diplay/android/stable/latest?id=abcDEF_123-456");
    expect(response.status).toBe(404);
  });

  it("rejects malformed architecture filters", async () => {
    const response = await latest("https://upd.example.com/api/v1/apps/diplay/android/stable/latest?arch=arm64%2Cx86");
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid_arch" });
  });
});

describe("admin login rate limiting", () => {
  it("returns 429 once the failure budget is spent", async () => {
    const kv = memoryKv();
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await login(loginContext({ username: "admin", password: "wrong" }, kv));
      expect(response.status).toBe(401);
    }
    const blocked = await login(loginContext({ username: "admin", password: "wrong" }, kv));
    expect(blocked.status).toBe(429);
    expect(await blocked.json()).toEqual({ error: "too_many_attempts" });
  });

  it("clears the failure counter on a successful login", async () => {
    const kv = memoryKv({ "admin-login-fail:203.0.113.9": "9" });
    const response = await login(loginContext({ username: "admin", password: PASSWORD }, kv));
    expect(response.status).toBe(200);
    expect(kv.store.has("admin-login-fail:203.0.113.9")).toBe(false);
  });

  it("works without a KV binding (limiter disabled)", async () => {
    const response = await login(loginContext({ username: "admin", password: PASSWORD }));
    expect(response.status).toBe(200);
  });

  it("does not leak credentials through CORS headers", async () => {
    const response = await login(loginContext({ username: "admin", password: "wrong" }, memoryKv()));
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
  });
});

describe("public releases limit and revalidation", () => {
  const params = { appId: "diplay", platform: "android", channel: "stable" };
  const bodies: Record<string, unknown> = {
    "apps/diplay/android/stable/releases/0.2.0.json": release("0.2.0", 2000, true, "2026-10-02T00:00:00Z"),
    "apps/diplay/android/stable/releases/0.3.0.json": release("0.3.0", 3000, true, "2026-10-03T00:00:00Z"),
    "apps/diplay/android/stable/releases/0.4.0.json": release("0.4.0", 4000, true, "2026-10-04T00:00:00Z"),
  };

  function context(url: string, headers: Record<string, string> = {}) {
    return {
      request: new Request(url, { headers }),
      env: {
        ASSET_BASE_URL: "https://dl.example.com",
        META: {
          get: async (key: string) => bodies[key] === undefined ? null : { key, json: async () => bodies[key] },
          list: async ({ prefix }: { prefix: string }) => ({
            objects: Object.keys(bodies).filter((key) => key.startsWith(prefix)).map((key) => ({ key })),
            truncated: false,
          }),
        },
      },
      params,
    } as never;
  }

  it("honours the limit parameter", async () => {
    const response = await publicReleases(context("https://upd.example.com/api/v1/apps/diplay/android/stable/releases?limit=2"));
    expect(response.status).toBe(200);
    const body = (await response.json()) as { releases: unknown[] };
    expect(body.releases).toHaveLength(2);
  });

  it("answers conditional requests with 304", async () => {
    const first = await publicReleases(context("https://upd.example.com/api/v1/apps/diplay/android/stable/releases"));
    const etag = first.headers.get("etag");
    expect(etag).toBeTruthy();
    const second = await publicReleases(context("https://upd.example.com/api/v1/apps/diplay/android/stable/releases", { "if-none-match": etag! }));
    expect(second.status).toBe(304);
  });
});

function release(version: string, versionCode: number, enabled: boolean, publishedAt: string) {
  return {
    schemaVersion: 1,
    version,
    versionName: version,
    versionCode,
    publishedAt,
    management: { enabled, mode: "all", notes: "" },
    artifacts: [{
      name: `${version}.apk`,
      arch: "universal",
      key: `apps/diplay/android/stable/${version}/${version}.apk`,
      checksumsKey: `apps/diplay/android/stable/${version}/SHA256SUMS.txt`,
      sha256: "a".repeat(64),
      size: 1,
    }],
  };
}
