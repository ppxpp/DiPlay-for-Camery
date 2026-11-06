import { describe, expect, it } from "vitest";
import {
  buildReleaseIndex,
  isOpenDelivery,
  isUnrestricted,
  latestAllKey,
  pickLatestAll,
  pickLatestAny,
  releasesIndexKey,
  rebuildDerived,
} from "../functions/_shared/derived";
import { onRequestGet as publicReleases } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/releases";
import { latestManifestResponse } from "../functions/_shared/latest";
import {
  buildReleaseIndex as scriptBuildReleaseIndex,
  isOpenDelivery as scriptIsOpenDelivery,
  isUnrestricted as scriptIsUnrestricted,
  pickLatestAll as scriptPickLatestAll,
  pickLatestAny as scriptPickLatestAny,
} from "../scripts/derived.mjs";
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

  it("separates an unrestricted mode from an open release", () => {
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, ""))).toBe(true);
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: true, mode: "percentage", percentage: 100, notes: "" }, ""))).toBe(true);
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: true, mode: "percentage", percentage: 30, notes: "" }, ""))).toBe(false);
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: true, mode: "whitelist", ids: ["a"], notes: "" }, ""))).toBe(false);
    expect(isUnrestricted(manifest("0.4.0", 4000, undefined, ""))).toBe(true);
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: true, mode: "none", notes: "" }, ""))).toBe(false);
    // A demoted release keeps its mode but stops being open for delivery.
    expect(isUnrestricted(manifest("0.4.0", 4000, { enabled: false, published: true, mode: "all", notes: "" }, ""))).toBe(true);
    expect(isOpenDelivery(manifest("0.4.0", 4000, { enabled: false, published: true, mode: "all", notes: "" }, ""))).toBe(false);
    expect(isOpenDelivery(manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, ""))).toBe(true);
  });

  it("picks the newest full release and skips grey ones", () => {
    const grey = manifest("0.4.1", 4001, { enabled: true, mode: "whitelist", ids: ["a"], notes: "" }, "2026-11-05T00:00:00Z");
    const full = manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, "2026-11-04T00:00:00Z");
    const older = manifest("0.3.0", 3000, { enabled: true, mode: "all", notes: "" }, "2026-11-01T00:00:00Z");
    expect(pickLatestAll([grey, full, older])?.version).toBe("0.4.0");
    expect(pickLatestAll([grey, older])?.version).toBe("0.3.0");
    expect(pickLatestAll([grey])).toBeNull();
    // Publishing a newer release leaves the previous snapshot enabled:false but
    // published:true; that demoted full release is still the fallback.
    const demoted = manifest("0.3.0", 3000, { enabled: false, published: true, mode: "all", notes: "" }, "2026-11-01T00:00:00Z");
    expect(pickLatestAll([grey, demoted])?.version).toBe("0.3.0");
    // A withdrawn release never becomes the fallback.
    expect(pickLatestAll([grey, manifest("0.3.0", 3000, { enabled: true, published: true, mode: "none", notes: "" }, "2026-11-02T00:00:00Z")])).toBeNull();
    // A candidate that was never delivered is not a fallback.
    expect(pickLatestAll([grey, manifest("0.3.0", 3000, { enabled: false, mode: "all", notes: "" }, "2026-11-01T00:00:00Z")])).toBeNull();
  });

  it("keeps the publisher script's full-rollout rules in sync", () => {
    const inputs = [
      manifest("0.4.1", 4001, { enabled: true, mode: "whitelist", ids: ["a"], notes: "" }, "2026-11-05T00:00:00Z"),
      manifest("0.4.0", 4000, { enabled: true, mode: "percentage", percentage: 100, notes: "" }, "2026-11-04T00:00:00Z"),
      manifest("0.3.0", 3000, { enabled: false, published: true, mode: "all", notes: "" }, "2026-11-01T00:00:00Z"),
    ];
    expect(scriptPickLatestAll(inputs)?.version).toBe(pickLatestAll(inputs)?.version);
    for (const input of inputs) {
      expect(scriptIsUnrestricted(input)).toBe(isUnrestricted(input));
      expect(scriptIsOpenDelivery(input)).toBe(isOpenDelivery(input));
    }
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

  it("writes the full-rollout pointer and mirrors it into latest-any", async () => {
    const grey = manifest("0.4.1", 4001, { enabled: true, mode: "whitelist", ids: ["a"], notes: "" }, "2026-11-05T00:00:00Z");
    const full = manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, "2026-11-04T00:00:00Z");
    const bodies: Record<string, unknown> = {
      "apps/diplay/android/stable/latest.json": grey,
      "apps/diplay/android/beta/latest.json": null,
      "apps/diplay/android/stable/releases/0.4.1.json": grey,
      "apps/diplay/android/stable/releases/0.4.0.json": full,
    };
    const writes = new Map<string, unknown>();
    const env = {
      META: {
        get: async (key: string) => {
          const body = bodies[key];
          return body ? { key, json: async () => body } : null;
        },
        list: async ({ prefix }: { prefix: string }) => ({
          objects: [`${prefix}0.4.1.json`, `${prefix}0.4.0.json`].map((key) => ({ key })),
          truncated: false,
        }),
        put: async (key: string, body: string) => { writes.set(key, JSON.parse(body)); },
      },
    } as never;

    await rebuildDerived(env, "diplay", "android", "stable");

    const pointer = writes.get(latestAllKey("diplay", "android", "stable")) as { manifest: { version: string } | null };
    expect(pointer.manifest?.version).toBe("0.4.0");
    const any = writes.get("apps/diplay/android/latest-any.json") as Record<string, unknown>;
    expect((any.stableAll as { version: string }).version).toBe("0.4.0");
    // The other channel was never derived, so its pointer stays absent rather
    // than claiming the channel has no full release.
    expect("betaAll" in any).toBe(false);
  });

  it("never points a full release at itself", async () => {
    const current = manifest("0.4.0", 4000, { enabled: true, mode: "all", notes: "" }, "2026-11-04T00:00:00Z");
    const demoted = manifest("0.3.0", 3000, { enabled: false, published: true, mode: "all", notes: "" }, "2026-11-01T00:00:00Z");
    const bodies: Record<string, unknown> = {
      "apps/diplay/android/stable/latest.json": current,
      "apps/diplay/android/stable/releases/0.4.0.json": current,
      "apps/diplay/android/stable/releases/0.3.0.json": demoted,
    };
    const writes = new Map<string, unknown>();
    const env = {
      META: {
        get: async (key: string) => {
          const body = bodies[key];
          return body ? { key, json: async () => body } : null;
        },
        list: async ({ prefix }: { prefix: string }) => ({
          objects: [`${prefix}0.4.0.json`, `${prefix}0.3.0.json`].map((key) => ({ key })),
          truncated: false,
        }),
        put: async (key: string, body: string) => { writes.set(key, JSON.parse(body)); },
      },
    } as never;

    await rebuildDerived(env, "diplay", "android", "stable");

    const pointer = writes.get(latestAllKey("diplay", "android", "stable")) as { manifest: { version: string } | null };
    expect(pointer.manifest?.version).toBe("0.3.0");
  });
});
