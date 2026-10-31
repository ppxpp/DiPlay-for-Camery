#!/usr/bin/env node
import { createHash } from "node:crypto";
import { parseArgs } from "node:util";
import {
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from "@aws-sdk/client-s3";

const SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/;
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$/;
const ARTIFACT_NAME = /^[\p{L}\p{N}._-]+$/u;
const ASSET_CACHE = "public, max-age=31536000, immutable";
const METADATA_CACHE = "no-store";

const { values } = parseArgs({
  options: {
    app: { type: "string", default: "diplay" },
    platform: { type: "string", default: "android" },
    channel: { type: "string" },
    version: { type: "string" },
    "version-name": { type: "string" },
    "version-code": { type: "string" },
    "artifact-name": { type: "string" },
    "source-key": { type: "string" },
    "published-at": { type: "string" },
    notes: { type: "string", default: "" },
    arch: { type: "string", default: "universal" },
    "meta-bucket": { type: "string" },
    "assets-bucket": { type: "string" },
    "account-id": { type: "string" },
    "dry-run": { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
});

if (values.help) {
  console.log(`
Restore a release snapshot from an artifact that already exists in R2.

This writes the snapshot and checksums only. It never updates latest.json.

Required:
  --channel <name>         Update channel, for example beta
  --version <version>      Short app version, for example 0.2.21
  --version-name <text>    Full display version, for example
                           0.2.21_凯美瑞A导航适配版_0.1.0
  --version-code <n>       Original Android versionCode, for example 1000

Optional:
  --app <id>               Default: diplay
  --platform <name>        Default: android
  --artifact-name <name>   Default: <version-name>.apk
  --source-key <key>       Existing artifact key. Default:
                           apps/{app}/{platform}/{channel}/{version}/{artifact-name}
  --published-at <iso>     Default: source object Last-Modified
  --notes <text>           Default: empty
  --arch <name>            Default: universal
  --meta-bucket <name>     Default: R2_META_BUCKET
  --assets-bucket <name>   Default: R2_ASSETS_BUCKET
  --account-id <id>        Default: R2_ACCOUNT_ID or CF_ACCOUNT_ID
  --dry-run                Read and hash the source, print the plan, write nothing

Credentials:
  R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_META_BUCKET, R2_ASSETS_BUCKET

Writes:
  assets: apps/{app}/{platform}/{channel}/{version-name}/{artifact}
          apps/{app}/{platform}/{channel}/{version-name}/SHA256SUMS.txt
  meta:   apps/{app}/{platform}/{channel}/releases/{version-name}.json
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

const app = requireValue("--app", values.app);
const platform = requireValue("--platform", values.platform);
const channel = requireValue("--channel", values.channel);
const version = requireValue("--version", values.version);
const versionName = requireValue("--version-name", values["version-name"]);
const artifactName = requireValue("--artifact-name", values["artifact-name"] ?? `${versionName}.apk`);
const arch = requireValue("--arch", values.arch);

for (const [name, value] of [
  ["app", app],
  ["platform", platform],
  ["channel", channel],
]) {
  if (!SLUG.test(value)) fail(`invalid ${name}: ${value}`);
}
if (!VERSION.test(version)) fail(`invalid version: ${version}`);
if (versionName.length > 200) fail("--version-name must be at most 200 characters");
if (versionName === "." || versionName === ".." || versionName.includes("/")) {
  fail(`--version-name cannot be used as an object key: ${versionName}`);
}
if (values.notes.length > 10000) fail("--notes must be at most 10000 characters");
if (!ARTIFACT_NAME.test(artifactName)) {
  fail("artifact name may contain only letters, digits, dot, underscore and dash");
}

let versionCode;
if (values["version-code"] !== undefined) versionCode = Number(values["version-code"]);
if (!Number.isSafeInteger(versionCode) || versionCode < 0) {
  fail("--version-code is required and must be a non-negative integer");
}

const sourceKey = values["source-key"] ?? `apps/${app}/${platform}/${channel}/${version}/${artifactName}`;
const assetPrefix = `apps/${app}/${platform}/${channel}/${versionName}`;
const destinationKey = `${assetPrefix}/${artifactName}`;
const checksumsKey = `${assetPrefix}/SHA256SUMS.txt`;
const snapshotKey = `apps/${app}/${platform}/${channel}/releases/${versionName}.json`;

const accountId = requireValue(
  "--account-id or R2_ACCOUNT_ID/CF_ACCOUNT_ID",
  values["account-id"] ?? process.env.R2_ACCOUNT_ID ?? process.env.CF_ACCOUNT_ID,
);
const metaBucket = requireValue(
  "--meta-bucket or R2_META_BUCKET",
  values["meta-bucket"] ?? process.env.R2_META_BUCKET,
);
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

async function readSource() {
  const object = await client.send(new GetObjectCommand({ Bucket: assetsBucket, Key: sourceKey }));
  const hash = createHash("sha256");
  const chunks = [];
  let size = 0;
  for await (const chunk of object.Body) {
    chunks.push(chunk);
    hash.update(chunk);
    size += chunk.length;
  }
  return {
    body: Buffer.concat(chunks),
    sha256: hash.digest("hex"),
    size,
    lastModified: object.LastModified?.toISOString(),
  };
}

async function exists(bucket, key) {
  try {
    await client.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return true;
  } catch (error) {
    if (error?.$metadata?.httpStatusCode === 404 || error?.name === "NotFound") return false;
    throw error;
  }
}

async function put(bucket, key, body, contentType, cacheControl) {
  await client.send(
    new PutObjectCommand({
      Bucket: bucket,
      Key: key,
      Body: body,
      ContentType: contentType,
      CacheControl: cacheControl,
      ContentLength: body.length,
    }),
  );
}

const source = await readSource();
const publishedAt = values["published-at"]?.trim() || source.lastModified;
if (!publishedAt) fail("--published-at is required when the source has no Last-Modified value");

const manifest = {
  schemaVersion: 1,
  version,
  versionName,
  versionCode,
  publishedAt,
  mandatory: false,
  management: { enabled: false, mode: "all", notes: values.notes },
  artifacts: [
    {
      name: artifactName,
      arch,
      key: destinationKey,
      checksumsKey,
      sha256: source.sha256,
      size: source.size,
    },
  ],
};
const manifestBody = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, "utf8");
const checksums = Buffer.from(`${source.sha256}  ${artifactName}\n`, "utf8");

console.log(`App:          ${app}/${platform}/${channel}`);
console.log(`Version:      ${version} (${versionCode}) / ${versionName}`);
console.log(`Source key:   ${sourceKey}`);
console.log(`Artifact key: ${destinationKey}`);
console.log(`Snapshot key: ${snapshotKey}`);
console.log(`Published at: ${publishedAt}`);
console.log(`SHA-256:      ${source.sha256}`);
console.log(`Size:         ${source.size}`);

if (values["dry-run"]) {
  console.log("\nDry run: no R2 writes performed.");
  process.exit(0);
}

if (await exists(assetsBucket, destinationKey)) {
  console.log(`Artifact already exists at the destination; leaving it unchanged.`);
} else {
  await put(
    assetsBucket,
    destinationKey,
    source.body,
    platform === "android" ? "application/vnd.android.package-archive" : "application/octet-stream",
    ASSET_CACHE,
  );
  console.log("Copied the artifact to the versionName directory.");
}

await put(assetsBucket, checksumsKey, checksums, "text/plain; charset=utf-8", ASSET_CACHE);
console.log("Uploaded SHA256SUMS.txt.");

await put(metaBucket, snapshotKey, manifestBody, "application/json; charset=utf-8", METADATA_CACHE);
console.log("Uploaded the release snapshot. latest.json was not changed.");

const restored = await client.send(new GetObjectCommand({ Bucket: metaBucket, Key: snapshotKey }));
const restoredManifest = JSON.parse(await restored.Body.transformToString());
if (
  restoredManifest.version !== version ||
  restoredManifest.versionName !== versionName ||
  restoredManifest.versionCode !== versionCode ||
  restoredManifest.artifacts?.[0]?.key !== destinationKey ||
  restoredManifest.artifacts?.[0]?.sha256 !== source.sha256
) {
  fail("restored snapshot verification failed");
}
console.log("Verified the restored snapshot.");
