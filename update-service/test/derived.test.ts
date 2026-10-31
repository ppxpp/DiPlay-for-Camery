import { describe, expect, it } from "vitest";
import {
  buildReleaseIndex,
  pickLatestAny,
  releasesIndexKey,
  rebuildDerived,
} from "../functions/_shared/derived";
import { onRequestGet as publicReleases } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/releases";
import { latestManifestResponse } from "../functions/_shared/latest";
import { buildReleaseIndex as scriptBuildReleaseIndex, pickLatestAny as scriptPickLatestAny } from "../scripts/derived.mjs";
import type { StoredManifest } from "../functions/_shared/management";

function manifest(version: string, versionCode: number, management: Record<string, unknown> | undefined, publishedAt: string): StoredManifest {
  return {
    schemaVersion: 1,
    version,
    versionName: `${version}_test`,
    versionCode,
    publishedAt,
    ...(management ? { management: management as never } : {}),
    artifacts: [{
      name: `${version}.apk`,
      arch: "universal",
      key: `apps/diplay/android/stable/${version}/${version}.apk`,
      checksumsKey: `apps/diplay/android/stable/${version}/SHA256SUMS.txt`,
      sha256: "a".repeat(64),
      size: 10,
    }],
  };
}

describe("derived models", () => {
  it("indexes only published releases, newest first", () => {
    const index = buildReleaseIndex("diplay", "android", "stable", [
      manifest("0.2.0", 2000, { enabled: true, mode: "all", notes: "old" }, "2026-10-01T00:00:00Z"),
      manifest("0.3.0", 3000, { enabled: false, mode: "all", notes: "" }, "2026-10-03T00:00:00Z"),
      manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "new" }, "2026-10-04T00:00:00Z"),
    ]);
    expect(index.releases.map((entry) => entry.version)).toEqual(["0.4.0", "0.2.0"]);
    expect(index.releases[0].notes).toBe("new");
    expect(index.releases[0].artifacts[0].key).toContain("0.4.0");
  });

  it("picks the newer enabled channel and prefers stable on a tie", () => {
    const stable = manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, "2026-10-04T00:00:00Z");
    const beta = manifest("0.4.1", 4001, { enabled: true, mode: "all", notes: "" }, "2026-10-05T00:00:00Z");
    expect(pickLatestAny(stable, beta)?.channel).toBe("beta");
    expect(pickLatestAny(stable, manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, ""))?.channel).toBe("stable");
    expect(pickLatestAny(stable, manifest("0.5.0", 5000, { enabled: false, mode: "all", notes: "" }, ""))?.channel).toBe("stable");
    expect(pickLatestAny(null, null)).toBeNull();
  });

  it("keeps the publisher script and the function logic in sync", () => {
    const inputs = [
      manifest("0.2.0", 2000, { enabled: true, mode: "all", notes: "x" }, "2026-10-01T00:00:00Z"),
      manifest("0.3.0", 3000, { enabled: false, mode: "all", notes: "" }, "2026-10-03T00:00:00Z"),
    ];
    const tsIndex = buildReleaseIndex("diplay", "android", "stable", inputs);
    const jsIndex = scriptBuildReleaseIndex("diplay", "android", "stable", inputs);
    expect(jsIndex.releases.map((entry: { version: string }) => entry.version))
      .toEqual(tsIndex.releases.map((entry) => entry.version));
    const stable = inputs[0];
    const beta = manifest("0.3.0", 3000, { enabled: true, mode: "all", notes: "" }, "2026-10-03T00:00:00Z");
    expect(scriptPickLatestAny(stable, beta)?.channel).toBe(pickLatestAny(stable, beta)?.channel);
  });
});

describe("releases index read path", () => {
  const params = { appId: "diplay", platform: "android", channel: "stable" };
  const indexKey = releasesIndexKey("diplay", "android", "stable");
  const indexBody = {
    schemaVersion: 1,
    updatedAt: "2026-10-05T00:00:00Z",
    appId: "diplay",
    platform: "android",
    channel: "stable",
    releases: [{
      version: "0.4.0",
      versionName: "0.4.0_test",
      versionCode: 4000,
      publishedAt: "2026-10-04T00:00:00Z",
      notes: "from index",
      artifacts: [{ name: "0.4.0.apk", arch: "universal", key: "apps/diplay/android/stable/0.4.0/0.4.0.apk", sha256: "b".repeat(64), size: 42 }],
    }],
  };

  it("serves the index with a single bucket read", async () => {
    const reads: string[] = [];
    const response = await publicReleases({
      request: new Request("https://upd.example.com/api/v1/apps/diplay/android/stable/releases"),
      env: {
        ASSET_BASE_URL: "https://dl.example.com",
        META: {
          get: async (key: string) => {
            reads.push(key);
            return key === indexKey ? { key, json: async () => indexBody } : null;
          },
          list: async () => { throw new Error("index path must not list"); },
        },
      },
      params,
    } as never);
    expect(response.status).toBe(200);
    expect(reads).toEqual([indexKey]);
    const body = (await response.json()) as { releases: Array<{ notes: string; artifacts: Array<{ url: string; key?: string }> }> };
    expect(body.releases[0].notes).toBe("from index");
    expect(body.releases[0].artifacts[0].url).toBe("https://dl.example.com/apps/diplay/android/stable/0.4.0/0.4.0.apk");
    expect(body.releases[0].artifacts[0].key).toBeUndefined();
  });
});

describe("legacy latest read path", () => {
  it("uses latest-any with a single bucket read", async () => {
    const reads: string[] = [];
    const anyKey = "apps/diplay/android/latest-any.json";
    const beta = manifest("0.4.1", 4001, { enabled: true, mode: "all", notes: "" }, "2026-10-05T00:00:00Z");
    const response = await latestManifestResponse(
      new Request("https://upd.example.com/api/v1/apps/diplay/android/stable/latest"),
      {
        ASSET_BASE_URL: "https://dl.example.com",
        META: {
          get: async (key: string) => {
            reads.push(key);
            return key === anyKey ? { key, json: async () => ({ schemaVersion: 1, stable: null, beta }) } : null;
          },
        },
      } as never,
      "diplay",
      "android",
      "stable",
    );
    expect(response.status).toBe(200);
    expect(reads).toEqual([anyKey]);
    const body = (await response.json()) as { channel: string; versionCode: number };
    expect(body.channel).toBe("beta");
    expect(body.versionCode).toBe(4001);
  });
});

describe("admin mutation keeps derived objects fresh", () => {
  it("writes the releases index after a rebuild", async () => {
    const writes = new Map<string, unknown>();
    const env = {
      META: {
        get: async (key: string) => {
          if (key.endsWith("latest.json") || key.includes("/releases/")) {
            const m = manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, "2026-10-04T00:00:00Z");
            return { key, json: async () => m };
          }
          return null;
        },
        list: async ({ prefix }: { prefix: string }) => ({
          objects: [{ key: `${prefix}0.4.0_test.json` }],
          truncated: false,
        }),
        put: async (key: string, body: string) => { writes.set(key, JSON.parse(body)); },
      },
    } as never;
    await rebuildDerived(env, "diplay", "android", "stable");
    expect(writes.has(releasesIndexKey("diplay", "android", "stable"))).toBe(true);
    expect(writes.has("apps/diplay/android/latest-any.json")).toBe(true);
    const index = writes.get(releasesIndexKey("diplay", "android", "stable")) as { releases: unknown[] };
    expect(index.releases).toHaveLength(1);
  });
});
