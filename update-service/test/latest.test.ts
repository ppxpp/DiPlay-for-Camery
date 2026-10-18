import { describe, expect, it } from "vitest";
import { onRequestGet } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/latest";

const manifest = {
  schemaVersion: 1,
  version: "0.3.0",
  versionName: "0.3.0_凯美瑞A导航适配版_0.1.0",
  versionCode: 97,
  publishedAt: "2026-10-18T08:00:00Z",
  mandatory: false,
  artifacts: [
    {
      name: "DiPlay-0.3.0.apk",
      arch: "universal",
      key: "apps/diplay/android/stable/0.3.0/DiPlay-0.3.0.apk",
      checksumsKey: "apps/diplay/android/stable/0.3.0/SHA256SUMS.txt",
      sha256: "a".repeat(64),
      size: 123,
    },
    {
      name: "DiPlay-0.3.0-arm64.apk",
      arch: "arm64-v8a",
      key: "apps/diplay/android/stable/0.3.0/DiPlay-0.3.0-arm64.apk",
      checksumsKey: "apps/diplay/android/stable/0.3.0/SHA256SUMS.txt",
    },
    {
      name: "DiPlay-0.3.0-x86_64.apk",
      arch: "x86_64",
      key: "apps/diplay/android/stable/0.3.0/DiPlay-0.3.0-x86_64.apk",
      checksumsKey: "apps/diplay/android/stable/0.3.0/SHA256SUMS.txt",
    },
  ],
};

function context(
  params: Record<string, string>,
  options: { body?: unknown; url?: string; base?: string } = {},
) {
  return {
    request: new Request(options.url ?? "https://upd.example.com/api/v1/apps/diplay/android/stable/latest"),
    env: {
      ASSET_BASE_URL: options.base ?? "https://dl.example.com/",
      META: {
        get: async (key: string) => {
          if (options.body === undefined) return null;
          return { key, json: async () => options.body };
        },
      },
    },
    params,
  } as never;
}

const params = { appId: "diplay", platform: "android", channel: "stable" };

describe("latest update manifest", () => {
  it("expands stored keys into asset URLs without leaking keys", async () => {
    const response = await onRequestGet(context(params, { body: manifest }));
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(response.headers.get("cdn-cache-control")).toContain("no-store");

    const body = (await response.json()) as any;
    expect(body.appId).toBe("diplay");
    expect(body.version).toBe("0.3.0");
    expect(body.versionName).toBe("0.3.0_凯美瑞A导航适配版_0.1.0");
    expect(body.versionCode).toBe(97);
    expect(body.mandatory).toBe(false);
    expect(body.artifacts).toHaveLength(3);
    expect(body.artifacts[0].url).toBe(
      "https://dl.example.com/apps/diplay/android/stable/0.3.0/DiPlay-0.3.0.apk",
    );
    expect(body.artifacts[0].checksumsUrl).toBe(
      "https://dl.example.com/apps/diplay/android/stable/0.3.0/SHA256SUMS.txt",
    );
    expect(body.artifacts[0].key).toBeUndefined();
    expect(body.artifacts[0].checksumsKey).toBeUndefined();
  });

  it("encodes non-ASCII artifact names in public URLs", async () => {
    const unicodeArtifact = {
      ...manifest.artifacts[0],
      name: "0.3.0_凯美瑞A导航适配版_0.1.0.apk",
      key: "apps/diplay/android/stable/0.3.0/0.3.0_凯美瑞A导航适配版_0.1.0.apk",
    };
    const response = await onRequestGet(context(params, {
      body: { ...manifest, artifacts: [unicodeArtifact] },
    }));
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.artifacts[0].name).toBe("0.3.0_凯美瑞A导航适配版_0.1.0.apk");
    expect(body.artifacts[0].url).toBe(
      "https://dl.example.com/apps/diplay/android/stable/0.3.0/0.3.0_%E5%87%AF%E7%BE%8E%E7%91%9EA%E5%AF%BC%E8%88%AA%E9%80%82%E9%85%8D%E7%89%88_0.1.0.apk",
    );
  });

  it("filters by architecture while keeping universal artifacts", async () => {
    const response = await onRequestGet(context(params, {
      body: manifest,
      url: "https://upd.example.com/api/v1/apps/diplay/android/stable/latest?arch=arm64-v8a",
    }));
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.artifacts.map((artifact: { arch: string }) => artifact.arch)).toEqual([
      "universal",
      "arm64-v8a",
    ]);
  });

  it("returns 404 when the requested architecture is absent", async () => {
    const response = await onRequestGet(context(params, {
      body: { ...manifest, artifacts: manifest.artifacts.filter((artifact) => artifact.arch !== "universal") },
      url: "https://upd.example.com/api/v1/apps/diplay/android/stable/latest?arch=mips",
    }));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "artifact_not_found" });
  });

  it("reads the manifest for the requested app, platform and channel", async () => {
    let requestedKey = "";
    const response = await onRequestGet({
      request: new Request("https://upd.example.com/api/v1/apps/usbcheck/android/beta/latest"),
      env: {
        ASSET_BASE_URL: "https://dl.example.com",
        META: {
          get: async (key: string) => {
            requestedKey = key;
            return { json: async () => manifest };
          },
        },
      },
      params: { appId: "usbcheck", platform: "android", channel: "beta" },
    } as never);
    expect(response.status).toBe(200);
    expect(requestedKey).toBe("apps/usbcheck/android/beta/latest.json");
    expect(((await response.json()) as any).appId).toBe("usbcheck");
  });

  it("rejects invalid path segments", async () => {
    const response = await onRequestGet(context({ ...params, appId: "../diplay" }, { body: manifest }));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid_path" });
  });

  it("returns 404 when the app/channel has no manifest", async () => {
    const response = await onRequestGet(context(params, { body: undefined }));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
  });

  it("returns 502 for an invalid stored manifest", async () => {
    const response = await onRequestGet(context(params, { body: { schemaVersion: 1, version: "0.3.0" } }));
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: "invalid_manifest" });
  });

  it("requires a numeric versionCode", async () => {
    const { versionCode: _versionCode, ...withoutVersionCode } = manifest;
    const response = await onRequestGet(context(params, { body: withoutVersionCode }));
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: "invalid_manifest" });
  });

  it("rejects manifests with path-traversal asset keys", async () => {
    const response = await onRequestGet(context(params, {
      body: {
        ...manifest,
        artifacts: [{ ...manifest.artifacts[0], key: "../secret.apk" }],
      },
    }));
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: "invalid_manifest" });
  });

  it("fails clearly when the asset base URL is missing", async () => {
    const response = await onRequestGet(context(params, { body: manifest, base: " " }));
    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ error: "asset_base_url_not_configured" });
  });
});
