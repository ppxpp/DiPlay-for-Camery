// Publisher-side rebuild of the derived read models consumed by the public
// functions: releases-index.json (one read instead of list + N) and
// latest-any.json (one read instead of two channel manifests).
import { GetObjectCommand, ListObjectsV2Command, PutObjectCommand } from "@aws-sdk/client-s3";

export function isPublishedManifest(manifest) {
  return manifest?.management?.published === true || manifest?.management?.enabled !== false;
}

function isManifest(value) {
  return Boolean(value) &&
    value.schemaVersion === 1 &&
    typeof value.version === "string" &&
    value.version.length > 0 &&
    typeof value.versionCode === "number" &&
    Array.isArray(value.artifacts) &&
    value.artifacts.length > 0;
}

export function buildReleaseIndex(appId, platform, channel, manifests, now = new Date()) {
  const releases = manifests
    // Grey releases join the public history only once they reach everyone.
    .filter((manifest) => isPublishedManifest(manifest) && isUnrestricted(manifest))
    .sort((left, right) =>
      (right.publishedAt ?? "").localeCompare(left.publishedAt ?? "") ||
      right.versionCode - left.versionCode,
    )
    .map((manifest) => ({
      version: manifest.version,
      versionName: manifest.versionName ?? manifest.version,
      versionCode: manifest.versionCode,
      publishedAt: manifest.publishedAt ?? null,
      notes: manifest.management?.notes ?? "",
      artifacts: manifest.artifacts.map((artifact) => ({
        name: artifact.name,
        ...(artifact.arch ? { arch: artifact.arch } : {}),
        key: artifact.key,
        ...(artifact.sha256 ? { sha256: artifact.sha256 } : {}),
        ...(artifact.size !== undefined ? { size: artifact.size } : {}),
      })),
    }));
  return {
    schemaVersion: 1,
    updatedAt: now.toISOString(),
    appId,
    platform,
    channel,
    releases,
  };
}

export function pickLatestAny(stable, beta) {
  const candidates = [];
  for (const [channel, manifest] of [["stable", stable], ["beta", beta]]) {
    if (isManifest(manifest) && manifest.management?.enabled !== false) candidates.push({ channel, manifest });
  }
  if (candidates.length === 0) return null;
  candidates.sort((left, right) => {
    if (left.manifest.versionCode !== right.manifest.versionCode) {
      return right.manifest.versionCode - left.manifest.versionCode;
    }
    return left.channel === "stable" ? -1 : 1;
  });
  return candidates[0];
}

// Mode holds nothing back: full rollout, or a percentage that already covers
// every client. `enabled` stays out of this test because publishing a newer
// release sets the previous snapshot to `enabled: false` without erasing it as
// deliverable history.
export function isUnrestricted(manifest) {
  const management = manifest?.management;
  if (!management) return true;
  if (management.mode === "none") return false;
  if (management.mode === "all") return true;
  return management.mode === "percentage" && (management.percentage ?? 0) >= 100;
}

export function isOpenDelivery(manifest) {
  return manifest?.management?.enabled !== false && isUnrestricted(manifest);
}

export function pickLatestAll(manifests) {
  const candidates = manifests.filter((manifest) => isPublishedManifest(manifest) && isUnrestricted(manifest));
  if (candidates.length === 0) return null;
  return candidates.sort((left, right) =>
    (right.publishedAt ?? "").localeCompare(left.publishedAt ?? "") ||
    right.versionCode - left.versionCode,
  )[0];
}

async function getJson(client, bucket, key) {
  try {
    const response = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
    const text = await response.Body.transformToString();
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function putJson(client, bucket, key, value) {
  await client.send(new PutObjectCommand({
    Bucket: bucket,
    Key: key,
    Body: Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8"),
    ContentType: "application/json; charset=utf-8",
    CacheControl: "no-store",
  }));
}

async function listKeys(client, bucket, prefix) {
  const keys = [];
  let token;
  do {
    const page = await client.send(new ListObjectsV2Command({
      Bucket: bucket,
      Prefix: prefix,
      ContinuationToken: token,
    }));
    for (const object of page.Contents ?? []) {
      if (object.Key?.endsWith(".json")) keys.push(object.Key);
    }
    token = page.IsTruncated ? page.NextContinuationToken : undefined;
  } while (token);
  return keys;
}

export async function rebuildDerived({ client, metaBucket, appId, platform, channel, log = console.log }) {
  const prefix = `apps/${appId}/${platform}/${channel}/releases/`;
  const manifests = [];
  for (const key of await listKeys(client, metaBucket, prefix)) {
    const manifest = await getJson(client, metaBucket, key);
    if (isManifest(manifest)) manifests.push(manifest);
  }
  const index = buildReleaseIndex(appId, platform, channel, manifests);
  await putJson(client, metaBucket, `apps/${appId}/${platform}/${channel}/releases-index.json`, index);

  const stable = await getJson(client, metaBucket, `apps/${appId}/${platform}/stable/latest.json`);
  const beta = await getJson(client, metaBucket, `apps/${appId}/${platform}/beta/latest.json`);
  const live = isManifest(stable) ? stable : null;
  const liveBeta = isManifest(beta) ? beta : null;
  // The current release is judged on its own, so a release that was just
  // switched off never falls back to itself.
  const current = channel === "stable" ? live : liveBeta;
  const fullRollout = pickLatestAll(
    manifests.filter((manifest) => !current || manifest.version !== current.version),
  );
  await putJson(client, metaBucket, `apps/${appId}/${platform}/${channel}/latest-all.json`, {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    manifest: fullRollout,
  });

  const otherChannel = channel === "stable" ? "beta" : "stable";
  const otherAll = await getJson(client, metaBucket, `apps/${appId}/${platform}/${otherChannel}/latest-all.json`);
  const all = {
    ...(otherAll?.schemaVersion === 1 ? { [`${otherChannel}All`]: otherAll.manifest ?? null } : {}),
    [`${channel}All`]: fullRollout,
  };
  await putJson(client, metaBucket, `apps/${appId}/${platform}/latest-any.json`, {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    stable: live,
    beta: liveBeta,
    ...all,
  });
  log(`Rebuilt derived objects for ${appId}/${platform}/${channel} (${index.releases.length} published releases).`);
}
