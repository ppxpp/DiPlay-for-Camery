import { describe, expect, it } from "vitest";
import { onRequestGet as publicLatest } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/public-latest";
import { onRequestGet as publicReleases } from "../functions/api/v1/apps/[appId]/[platform]/[channel]/releases";

const params = { appId: "diplay", platform: "android", channel: "beta" };
const latestKey = "apps/diplay/android/beta/latest.json";
const snapshots = "apps/diplay/android/beta/releases/";

function release(version: string, versionCode: number, enabled: boolean | undefined, publishedAt: string) {
  return {
    schemaVersion: 1,
    version,
    versionName: `${version}_凯美瑞A导航适配版_0.3.1`,
    versionCode,
    publishedAt,
    ...(enabled === undefined ? {} : { management: { enabled, mode: "all", notes: "" } }),
    artifacts: [{
      name: "DiPlay凯美瑞A导航适配版_0.3.1-beta.apk",
      arch: "universal",
      key: `apps/diplay/android/beta/${version}/DiPlay凯美瑞A导航适配版_0.3.1-beta.apk`,
      checksumsKey: `apps/diplay/android/beta/${version}/SHA256SUMS.txt`,
      sha256: "a".repeat(64),
      size: 123,
    }],
  };
}

function context(bodies: Record<string, unknown>, url = "https://upd.example.com/api/v1/apps/diplay/android/beta/public-latest") {
  return {
    request: new Request(url),
    env: {
      ASSET_BASE_URL: "https://dl.example.com/",
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

describe("public release visibility", () => {
  it("serves an enabled latest release", async () => {
    const current = release("0.3.0", 3001, true, "2026-10-29T07:25:14Z");

    const response = await publicLatest(context({ [latestKey]: current }));

    expect(response.status).toBe(200);
    const body = await response.json() as { versionCode: number; artifacts: Array<{ url: string }> };
    expect(body.versionCode).toBe(3001);
    expect(body.artifacts[0].url).toContain("https://dl.example.com/apps/diplay/android/beta/0.3.0/");
  });

  it("keeps legacy manifests without delivery metadata visible", async () => {
    const legacy = release("0.2.21", 2001, undefined, "2026-10-26T07:39:36Z");

    const response = await publicLatest(context({ [latestKey]: legacy }));

    expect(response.status).toBe(200);
    expect((await response.json() as { versionCode: number }).versionCode).toBe(2001);
  });

  it("falls back to the last published release while a candidate waits for review", async () => {
    const candidate = release("0.3.0", 3001, false, "2026-10-29T07:25:14Z");
    const published = release("0.2.21", 2001, true, "2026-10-26T07:39:36Z");

    const response = await publicLatest(context({
      [latestKey]: candidate,
      [`${snapshots}0.3.0.json`]: candidate,
      [`${snapshots}0.2.21.json`]: published,
    }));

    expect(response.status).toBe(200);
    expect((await response.json() as { versionCode: number }).versionCode).toBe(2001);
  });

  it("returns 404 when nothing has been published", async () => {
    const candidate = release("0.3.0", 3001, false, "2026-10-29T07:25:14Z");

    const response = await publicLatest(context({
      [latestKey]: candidate,
      [`${snapshots}0.3.0.json`]: candidate,
    }));

    expect(response.status).toBe(404);
  });

  it("keeps unpublished candidates out of the public history", async () => {
    const candidate = release("0.3.0", 3001, false, "2026-10-29T07:25:14Z");
    const published = release("0.2.21", 2001, true, "2026-10-26T07:39:36Z");

    const response = await publicReleases(context({
      [latestKey]: candidate,
      [`${snapshots}0.3.0.json`]: candidate,
      [`${snapshots}0.2.21.json`]: published,
    }));

    expect(response.status).toBe(200);
    const body = await response.json() as { releases: Array<{ versionCode: number }> };
    expect(body.releases.map((item) => item.versionCode)).toEqual([2001]);
  });
});