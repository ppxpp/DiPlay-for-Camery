import {
  assetUrl,
  isManifest,
  json,
  readManifest,
  type StoredManifest,
} from "./management";

export interface InstallKitEnv {
  META: R2Bucket;
  ASSET_BASE_URL: string;
}

export interface KitFile {
  name: string;
  downloadUrl: string;
  sizeBytes: number;
  sha256: string;
}

export interface KitPostInstall {
  type: string;
  op: string;
  mode: string;
}

export interface KitApp {
  id: string;
  label: string;
  replaces: string;
  packageName: string;
  versionName: string;
  versionCode: number;
  required: boolean;
  order: number;
  installMode: string;
  backupDefault: boolean;
  minSdk: number;
  abis: string[];
  notes?: string;
  files: KitFile[];
  postInstall: KitPostInstall[];
}

export interface DeviceProfile {
  id: string;
  name: string;
  match: {
    modelRegex?: string;
    fingerprintRegex?: string;
    sdkRange?: [number, number];
  };
}

export interface InstallKitResponse {
  schemaVersion: number;
  kitId: string;
  channel: string;
  kitVersion: string;
  publishedAt?: string;
  minToolVersion: number;
  uninstallNotice?: string;
  deviceProfiles: DeviceProfile[];
  apps: KitApp[];
}

// The DiPlay entry is resolved at request time from the app update interface.
// Everything else is authored here and mirrors the validated mock data.
interface DiplayAppTemplate {
  id: string;
  label: string;
  replaces: string;
  packageName: string;
  required: boolean;
  order: number;
  installMode: string;
  backupDefault: boolean;
  minSdk: number;
  abis: string[];
  notes: string;
  postInstall: KitPostInstall[];
  source: { appId: string; platform: string };
}

interface KitConfig {
  kitId: string;
  channel: string;
  kitVersion: string;
  minToolVersion: number;
  uninstallNotice: string;
  deviceProfiles: DeviceProfile[];
  diplay: DiplayAppTemplate;
  apps: KitApp[];
}

const KIT_CHANNELS = ["stable", "beta"] as const;

const CAMRY_A: KitConfig = {
  kitId: "camry-a",
  channel: "stable",
  kitVersion: "2026.10.29-mock-2",
  minToolVersion: 1,
  uninstallNotice:
    "卸载丰云悦享不会影响车联网功能（包括里程、油耗数据显示、远程空调等），这些功能由车机系统提供。",
  deviceProfiles: [
    {
      id: "camry-a-pioneer",
      name: "凯美瑞 A 导航",
      match: {
        modelRegex: ".*(Camry|A导航).*",
        fingerprintRegex: ".*camry.*",
        sdkRange: [24, 25],
      },
    },
  ],
  diplay: {
    id: "diplay",
    label: "DiPlay",
    replaces: "丰云悦享",
    packageName: "com.gtmc.app.fengyunyx.b819",
    required: true,
    order: 1,
    installMode: "auto",
    backupDefault: true,
    minSdk: 23,
    abis: ["armeabi-v7a"],
    notes: "DiPlay 凯美瑞 A 导航适配版",
    postInstall: [{ type: "appops", op: "ACTIVATE_VPN", mode: "allow" }],
    source: { appId: "diplay", platform: "android" },
  },
  apps: [
    {
      id: "wecarflow",
      label: "爱趣听",
      replaces: "爱趣听",
      packageName: "com.tencent.wecarflow",
      versionName: "2.7.1.36971303",
      versionCode: 22101715,
      required: true,
      order: 2,
      installMode: "replace",
      backupDefault: true,
      minSdk: 21,
      abis: ["armeabi-v7a"],
      notes: "替换原车爱趣听的改版包；已上传 R2，并完成包名、版本和 SHA-256 校验。",
      files: [
        {
          name: "wecarflow-2.7.1.36971303-22101715-armeabi-v7a.apk",
          downloadUrl:
            "https://updates-assets.app.gtmc.mgulu.com/install-kits/camry-a/stable/2026.10.29-mock-2/wecarflow-2.7.1.36971303-22101715-armeabi-v7a.apk",
          sizeBytes: 56290138,
          sha256: "c5ec05108bfd4d21f719ea49406201b13cdab1300b004280d32952dbc9441e5a",
        },
      ],
      postInstall: [],
    },
    {
      id: "wechat",
      label: "微信",
      replaces: "微信",
      packageName: "com.tencent.mm",
      versionName: "1.0",
      versionCode: 1,
      required: true,
      order: 3,
      installMode: "replace",
      backupDefault: true,
      minSdk: 25,
      abis: [],
      notes:
        "替换原车微信的改版包（APK 标签为“盒子助手”）；已上传 R2，并完成包名、版本和 SHA-256 校验。",
      files: [
        {
          name: "wechat-1.0-1.apk",
          downloadUrl:
            "https://updates-assets.app.gtmc.mgulu.com/install-kits/camry-a/stable/2026.10.29-mock-2/wechat-1.0-1.apk",
          sizeBytes: 2673165,
          sha256: "0dd5e0be0130fdf051d01effd43b701442c77dabe5dca31fbc185cca57753940",
        },
      ],
      postInstall: [],
    },
  ],
};

const KITS: Record<string, KitConfig> = {
  [CAMRY_A.kitId]: CAMRY_A,
};

function pickArtifact(manifest: StoredManifest) {
  const apks = manifest.artifacts.filter((artifact) => artifact.name.endsWith(".apk"));
  const pool = apks.length ? apks : manifest.artifacts;
  return pool.find((artifact) => artifact.arch === "universal") ?? pool[0] ?? null;
}

async function latestDiplayManifest(
  env: InstallKitEnv,
  appId: string,
  platform: string,
): Promise<{ channel: string; manifest: StoredManifest } | null> {
  const loaded: Array<{ channel: string; manifest: StoredManifest | null }> = await Promise.all(
    KIT_CHANNELS.map(async (channel) => {
      const manifest = await readManifest(env.META, `apps/${appId}/${platform}/${channel}/latest.json`);
      return { channel, manifest };
    }),
  );
  const candidates = loaded.filter(
    (item): item is { channel: string; manifest: StoredManifest } =>
      item.manifest !== null && isManifest(item.manifest) && item.manifest.management?.enabled !== false,
  );
  if (candidates.length === 0) return null;
  candidates.sort((left, right) => {
    if (left.manifest.versionCode !== right.manifest.versionCode) {
      return right.manifest.versionCode - left.manifest.versionCode;
    }
    return left.channel === "stable" ? -1 : 1;
  });
  return candidates[0];
}

async function buildDiplayApp(
  env: InstallKitEnv,
  template: DiplayAppTemplate,
): Promise<{ app: KitApp; publishedAt?: string } | { error: Response }> {
  const base = env.ASSET_BASE_URL?.trim();
  if (!base) return { error: json({ error: "asset_base_url_not_configured" }, 500) };

  const latest = await latestDiplayManifest(env, template.source.appId, template.source.platform);
  if (!latest) return { error: json({ error: "diplay_release_unavailable" }, 503) };

  const artifact = pickArtifact(latest.manifest);
  if (!artifact) return { error: json({ error: "diplay_artifact_not_found" }, 503) };

  const downloadUrl = assetUrl(base, artifact.key);
  if (!downloadUrl) return { error: json({ error: "invalid_manifest" }, 502) };

  const app: KitApp = {
    id: template.id,
    label: template.label,
    replaces: template.replaces,
    packageName: template.packageName,
    versionName: latest.manifest.versionName ?? latest.manifest.version,
    versionCode: latest.manifest.versionCode,
    required: template.required,
    order: template.order,
    installMode: template.installMode,
    backupDefault: template.backupDefault,
    minSdk: template.minSdk,
    abis: template.abis,
    notes: template.notes,
    files: [
      {
        name: artifact.name,
        downloadUrl,
        sizeBytes: artifact.size ?? 0,
        sha256: artifact.sha256 ?? "",
      },
    ],
    postInstall: template.postInstall,
  };
  return { app, publishedAt: latest.manifest.publishedAt };
}

export async function buildInstallKitResponse(
  env: InstallKitEnv,
  kitId: string | undefined,
  channel: string | undefined,
): Promise<Response> {
  const config = kitId ? KITS[kitId] : undefined;
  if (!config) return json({ error: "not_found" }, 404);
  if (channel && channel !== config.channel) return json({ error: "not_found" }, 404);

  const diplay = await buildDiplayApp(env, config.diplay);
  if ("error" in diplay) return diplay.error;

  const apps = [...config.apps, diplay.app].sort((left, right) => left.order - right.order);
  const body: InstallKitResponse = {
    schemaVersion: 1,
    kitId: config.kitId,
    channel: config.channel,
    kitVersion: config.kitVersion,
    ...(diplay.publishedAt ? { publishedAt: diplay.publishedAt } : {}),
    minToolVersion: config.minToolVersion,
    uninstallNotice: config.uninstallNotice,
    deviceProfiles: config.deviceProfiles,
    apps,
  };
  return json(body);
}
