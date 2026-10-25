#!/usr/bin/env node
import { createHash } from "node:crypto";
import { createReadStream, statSync } from "node:fs";
import { basename, resolve } from "node:path";
import { parseArgs } from "node:util";
import { PutObjectCommand, S3Client } from "@aws-sdk/client-s3";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$/;
const ASSET_CACHE = "public, max-age=31536000, immutable";
const METADATA_CACHE = "no-store";

const { values } = parseArgs({
  options: {
    app: { type: "string" },
    platform: { type: "string", default: "android" },
    channel: { type: "string", default: "stable" },
    version: { type: "string" },
    "version-name": { type: "string" },
    "version-code": { type: "string" },
    artifact: { type: "string" },
    arch: { type: "string", default: "universal" },
    "notes-url": { type: "string" },
    "meta-bucket": { type: "string" },
    "assets-bucket": { type: "string" },
    "account-id": { type: "string" },
    "verify-url": { type: "string" },
    "dry-run": { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
});

if (values.help) {
  console.log(`
Publish a release manifest for one app/platform/channel.

Required:
  --app <id>              Stable app id, for example diplay
  --version <version>     Release version used for keys/tags, for example 0.3.0
  --version-code <n>      Build number used by update clients, for example 98
  --artifact <file>       Local release file, usually an APK

Optional:
  --version-name <text>   Display version used for release keys, for example
                          0.3.0_凯美瑞A导航适配版_0.1.0 (defaults to --version)
  --platform <name>       Default: android
  --channel <name>        Default: stable
  --arch <name>           Default: universal
  --notes-url <url>       Release notes URL
  --meta-bucket <name>    Default: R2_META_BUCKET
  --assets-bucket <name>  Default: R2_ASSETS_BUCKET
  --account-id <id>       Default: R2_ACCOUNT_ID
  --verify-url <url>      Public API URL checked after publishing
  --dry-run               Print the plan without writing R2

Credentials:
  R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY

Layout:
  assets: apps/{app}/{platform}/{channel}/{versionName}/{file}
  meta:   apps/{app}/{platform}/{channel}/latest.json
          apps/{app}/{platform}/{channel}/releases/{versionName}.json
`);
  process.exit(0);
}

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function requireValue(name, value) {
  if (!value || value.trim() === "") fail(`${name} is required`);
  return value.trim();
}

function sha256(file) {
  return new Promise((resolveHash, reject) => {
    const hash = createHash("sha256");
    createReadStream(file)
      .on("data", (chunk) => hash.update(chunk))
      .on("error", reject)
      .on("end", () => resolveHash(hash.digest("hex")));
  });
}

function manifestJson(manifest) {
  return Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function put(client, bucket, key, body, contentType, cacheControl, contentLength) {
  await client.send(
    new PutObjectCommand({
      Bucket: bucket,
      Key: key,
      Body: body,
      ContentType: contentType,
      CacheControl: cacheControl,
      ...(contentLength === undefined ? {} : { ContentLength: contentLength }),
    }),
  );
}

const app = requireValue("--app", values.app);
const platform = requireValue("--platform", values.platform);
const channel = requireValue("--channel", values.channel);
const version = requireValue("--version", values.version);
const artifactPath = resolve(requireValue("--artifact", values.artifact));
const arch = requireValue("--arch", values.arch);

for (const [name, value] of [
  ["app", app],
  ["platform", platform],
  ["channel", channel],
]) {
  if (!SLUG.test(value)) fail(`invalid ${name}: ${value}`);
}
if (!VERSION.test(version)) fail(`invalid version: ${version}`);

const versionName = values["version-name"]?.trim();
if (
  versionName !== undefined &&
  (!versionName || versionName.length > 200 || /[\u0000-\u001f\u007f/]/.test(versionName))
) {
  fail("--version-name must be a non-empty single-line display version");
}

let versionCode;
if (values["version-code"] !== undefined) {
  versionCode = Number(values["version-code"]);
}
if (!Number.isSafeInteger(versionCode) || versionCode < 0) {
  fail("--version-code is required and must be a non-negative integer");
}

let stats;
try {
  stats = statSync(artifactPath);
} catch {
  fail(`artifact does not exist: ${artifactPath}`);
}
if (!stats.isFile()) fail(`artifact is not a file: ${artifactPath}`);

const artifactName = basename(artifactPath);
if (!/^[\p{L}\p{N}._-]+$/u.test(artifactName)) {
  fail("artifact name may contain only letters, digits, dot, underscore and dash");
}

const releaseKey = versionName || version;
if (releaseKey === "." || releaseKey === "..") fail("--version-name cannot be used as an object key");

const versionBase = `apps/${app}/${platform}/${channel}/${releaseKey}`;
const artifactKey = `${versionBase}/${artifactName}`;
const checksumsKey = `${versionBase}/SHA256SUMS.txt`;
const snapshotKey = `apps/${app}/${platform}/${channel}/releases/${releaseKey}.json`;
const latestKey = `apps/${app}/${platform}/${channel}/latest.json`;

const hash = await sha256(artifactPath);
const checksums = Buffer.from(`${hash}  ${artifactName}\n`, "utf8");
const manifest = {
  schemaVersion: 1,
  version,
  ...(versionName ? { versionName } : {}),
  versionCode,
  publishedAt: new Date().toISOString(),
  mandatory: false,
  management: { enabled: false, mode: "all", notes: "" },
  ...(values["notes-url"] ? { notesUrl: values["notes-url"] } : {}),
  artifacts: [
    {
      name: artifactName,
      arch,
      key: artifactKey,
      checksumsKey,
      sha256: hash,
      size: stats.size,
    },
  ],
};
const manifestBody = manifestJson(manifest);

console.log(`App:        ${app}/${platform}/${channel}`);
console.log(`Version:    ${version} (${versionCode})${versionName ? ` / ${versionName}` : ""}`);
console.log(`Release key: ${releaseKey}`);
console.log(`Artifact:   ${artifactPath}`);
console.log(`SHA-256:    ${hash}`);
console.log(`Size:       ${stats.size}`);
console.log(`Assets key: ${artifactKey}`);
console.log(`Latest key: ${latestKey}`);

if (values["dry-run"]) {
  console.log("\nDry run: no R2 writes performed.");
  process.exit(0);
}

const accountId = requireValue(
  "--account-id or R2_ACCOUNT_ID/CF_ACCOUNT_ID",
  values["account-id"] ?? process.env.R2_ACCOUNT_ID ?? process.env.CF_ACCOUNT_ID,
);
const metaBucket = requireValue("--meta-bucket or R2_META_BUCKET", values["meta-bucket"] ?? process.env.R2_META_BUCKET);
const assetsBucket = requireValue(
  "--assets-bucket or R2_ASSETS_BUCKET",
  values["assets-bucket"] ?? process.env.R2_ASSETS_BUCKET,
);
const accessKeyId = requireValue("R2_ACCESS_KEY_ID", process.env.R2_ACCESS_KEY_ID);
const secretAccessKey = requireValue("R2_SECRET_ACCESS_KEY", process.env.R2_SECRET_ACCESS_KEY);

const client = new S3Client({
  region: "auto",
  endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
  credentials: { accessKeyId, secretAccessKey },
  requestChecksumCalculation: "WHEN_REQUIRED",
  responseChecksumValidation: "WHEN_REQUIRED",
});

await put(
  client,
  assetsBucket,
  artifactKey,
  createReadStream(artifactPath),
  platform === "android" ? "application/vnd.android.package-archive" : "application/octet-stream",
  ASSET_CACHE,
  stats.size,
);
await put(client, assetsBucket, checksumsKey, checksums, "text/plain; charset=utf-8", ASSET_CACHE);
console.log("Uploaded artifact and checksums to the assets bucket.");

await put(client, metaBucket, snapshotKey, manifestBody, "application/json; charset=utf-8", METADATA_CACHE);
console.log("Uploaded the release snapshot.");

// The latest pointer is written last so clients never see a manifest before its artifacts exist.
await put(client, metaBucket, latestKey, manifestBody, "application/json; charset=utf-8", METADATA_CACHE);
console.log("Updated the latest pointer.");

if (values["verify-url"]) {
  let lastError;
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      const response = await fetch(values["verify-url"], { headers: { accept: "application/json" } });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const body = await response.json();
      if (body.version !== version) throw new Error(`expected ${version}, got ${body.version}`);
      console.log(`Verified ${values["verify-url"]} -> ${body.version}`);
      lastError = undefined;
      break;
    } catch (error) {
      lastError = error;
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 2000));
    }
  }
  if (lastError) fail(`verification failed: ${lastError.message}`);
}
