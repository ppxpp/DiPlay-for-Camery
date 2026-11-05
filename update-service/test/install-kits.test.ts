import { describe, expect, it } from "vitest";
import { onRequestGet } from "../functions/api/v1/install-kits/[kitId]/[channel]/latest";

const diplayBetaKey = "apps/diplay/android/beta/latest.json";
const diplayStableKey = "apps/diplay/android/stable/latest.json";

function manifest(version: string, versionCode: number, channel: string) {
  return {
    schemaVersion: 1,
    version,
    versionName: `${version}_凯美瑞A导航适配版`,
    versionCode,
    publishedAt: "2026-10-26T07:39:36Z",
    artifacts: [
      {
        name: `${version}.apk`,
        arch: "universal",
        key: `apps/diplay/android/${channel}/${version}/${version}.apk`,
        checksumsKey: `apps/diplay/android/${channel}/${version}/SHA256SUMS.txt`,
        sha256: "a".repeat(64),
        size: 21369258,
      },
    ],
  };
}

function context(bodies: Record<string, unknown>, params: Record<string, string>) {
  return {
    env: {
      ASSET_BASE_URL: "https://dl.example.com",
      META: {
        get: async (key: string) => {
          const body = bodies[key];
          return body === undefined ? null : { key, json: async () => body };
        },
      },
    },
    params,
  } as never;
}

const kitParams = { kitId: "camry-a", channel: "stable" };

describe("install-kits latest", () => {
  it("injects the newest DiPlay release and keeps the static apps", async () => {
    const response = await onRequestGet(
      context({ [diplayBetaKey]: manifest("0.2.21", 2001, "beta") }, kitParams),
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toContain("s-maxage=60");

    const body = (await response.json()) as any;
    expect(body.kitId).toBe("camry-a");
    expect(body.channel).toBe("stable");
    expect(body.apps.map((app: { id: string }) => app.id)).toEqual([
      "diplay",
      "wecarflow",
      "wechat",
    ]);

    const diplay = body.apps[0];
    expect(diplay.packageName).toBe("com.gtmc.app.fengyunyx.b819");
    expect(diplay.versionCode).toBe(2001);
    expect(diplay.versionName).toBe("0.2.21_凯美瑞A导航适配版");
    expect(diplay.files[0].downloadUrl).toBe(
      "https://dl.example.com/apps/diplay/android/beta/0.2.21/0.2.21.apk",
    );
    expect(diplay.files[0].sizeBytes).toBe(21369258);
    expect(diplay.files[0].sha256).toBe("a".repeat(64));
    expect(diplay.postInstall).toEqual([{ type: "appops", op: "ACTIVATE_VPN", mode: "allow" }]);

    const wecarflow = body.apps.find((app: { id: string }) => app.id === "wecarflow");
    expect(wecarflow.versionCode).toBe(22101715);
    expect(wecarflow.files[0].sha256).toBe(
      "c5ec05108bfd4d21f719ea49406201b13cdab1300b004280d32952dbc9441e5a",
    );
  });

  it("picks the newer channel across stable and beta", async () => {
    const response = await onRequestGet(
      context(
        {
          [diplayStableKey]: manifest("0.3.0", 2100, "stable"),
          [diplayBetaKey]: manifest("0.2.21", 2001, "beta"),
        },
        kitParams,
      ),
    );
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.apps[0].versionCode).toBe(2100);
    expect(body.apps[0].files[0].downloadUrl).toContain("/stable/");
  });

  it("skips a disabled channel and serves the enabled one", async () => {
    const response = await onRequestGet(
      context(
        {
          [diplayStableKey]: { ...manifest("0.4.0", 3000, "stable"), management: { enabled: false, mode: "all", notes: "" } },
          [diplayBetaKey]: manifest("0.2.21", 2001, "beta"),
        },
        kitParams,
      ),
    );
    expect(response.status).toBe(200);
    const body = (await response.json()) as any;
    expect(body.apps[0].versionCode).toBe(2001);
  });

  it("returns 503 when no DiPlay release is available", async () => {
    const response = await onRequestGet(context({}, kitParams));
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: "diplay_release_unavailable" });
  });

  it("returns 404 for an unknown kit", async () => {
    const response = await onRequestGet(
      context({ [diplayBetaKey]: manifest("0.2.21", 2001, "beta") }, { kitId: "nope", channel: "stable" }),
    );
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
  });

  it("fails clearly when the asset base URL is missing", async () => {
    const response = await onRequestGet({
      env: {
        ASSET_BASE_URL: " ",
        META: { get: async (key: string) => (key === diplayBetaKey ? { json: async () => manifest("0.2.21", 2001, "beta") } : null) },
      },
      params: kitParams,
    } as never);
    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ error: "asset_base_url_not_configured" });
  });
});
