import type { Env } from "../../../../_shared/management";
import { json, putJson } from "../../../../_shared/management";
import type { FeedbackIndex, FeedbackPointer } from "../../../../_shared/feedback";
import {
  FEEDBACK_SCHEMA_VERSION,
  MAX_BUNDLE_BYTES,
  MAX_DESCRIPTION_CHARS,
  MAX_REQUEST_BYTES,
  bundleMatchesMeta,
  feedbackKeys,
  isFeedbackAppId,
  parseBundle,
  parseMeta,
  rateLimit,
  sha256Hex,
} from "../../../../_shared/feedback";

function rejected(error: string, status = 422): Response {
  return json({ error }, status, { cors: false });
}

/**
 * POST /api/v1/apps/{appId}/feedback
 *
 * Accepts multipart form-data with `meta` (JSON) and `bundle` (zip). The upload
 * is anonymous; ordering is size checks, idempotency, per-IP rate limit, zip
 * inspection and integrity checks, then R2 writes.
 */
export const onRequestPost: PagesFunction<Env> = async ({ request, env, params }) => {
  const appId = Array.isArray(params.appId) ? params.appId[0] : params.appId;
  if (!isFeedbackAppId(appId)) return rejected("invalid_app", 400);

  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_REQUEST_BYTES) return rejected("request_too_large", 413);
  if (!env.META) return rejected("storage_unavailable", 503);

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return rejected("invalid_request", 400);
  }

  const metaValue = form.get("meta");
  if (typeof metaValue !== "string") return rejected("invalid_meta");
  let metaInput: unknown;
  try {
    metaInput = JSON.parse(metaValue);
  } catch {
    return rejected("invalid_meta");
  }
  const rawDescription = metaInput && typeof metaInput === "object"
    ? (metaInput as Record<string, unknown>).description
    : undefined;
  if (typeof rawDescription === "string" && rawDescription.length > MAX_DESCRIPTION_CHARS) {
    return rejected("description_too_long");
  }
  const meta = parseMeta(metaInput);
  if (!meta) return rejected("invalid_meta");

  const bundle = form.get("bundle");
  if (!bundle || typeof bundle === "string") return rejected("invalid_bundle");
  if (bundle.size === 0) return rejected("invalid_bundle");
  if (bundle.size > MAX_BUNDLE_BYTES) return rejected("bundle_too_large", 413);
  if (metaValue.length + bundle.size > MAX_REQUEST_BYTES) return rejected("request_too_large", 413);

  // Idempotency: a client retry of the same feedback ID is a success and must
  // never overwrite an archived bundle.
  const receivedAt = Date.now();
  const keys = feedbackKeys(appId, meta.id, receivedAt);
  if (await env.META.head(keys.pointerKey)) {
    return json({ ok: true, id: meta.id, duplicate: true }, 200, { cors: false });
  }

  const verdict = await rateLimit(env, request);
  if (!verdict.allowed) {
    const response = json({ error: "rate_limited" }, 429, { cors: false });
    response.headers.set("retry-after", String(verdict.retryAfter));
    return response;
  }

  const bytes = new Uint8Array(await bundle.arrayBuffer());
  if (bytes.byteLength !== meta.bundleBytes) return rejected("bundle_size_mismatch");
  const digest = await sha256Hex(bytes);
  if (digest !== meta.bundleSha256) return rejected("bundle_sha256_mismatch");

  const parsed = parseBundle(bytes);
  if (!parsed.ok) return rejected(parsed.error);
  if (!bundleMatchesMeta(parsed.feedbackJson, meta)) return rejected("bundle_meta_mismatch");

  const index: FeedbackIndex = {
    schemaVersion: FEEDBACK_SCHEMA_VERSION,
    id: meta.id,
    receivedAt,
    category: meta.category,
    description: meta.description,
    entry: meta.entry,
    ...(meta.failureStage === undefined ? {} : { failureStage: meta.failureStage }),
    app: meta.app,
    device: meta.device,
    session: meta.session,
    bundleKey: keys.objectKey,
    bundleBytes: bytes.byteLength,
    bundleSha256: digest,
    reportPreview: parsed.reportPreview,
    previewTruncated: parsed.previewTruncated,
  };
  const pointer: FeedbackPointer = {
    schemaVersion: FEEDBACK_SCHEMA_VERSION,
    id: meta.id,
    receivedAt,
    indexKey: keys.indexKey,
    bundleKey: keys.objectKey,
  };

  try {
    await env.META.put(keys.objectKey, bytes, {
      httpMetadata: { contentType: "application/zip", cacheControl: "no-store" },
    });
    await putJson(env.META, keys.indexKey, index);
    await putJson(env.META, keys.pointerKey, pointer);
  } catch {
    // Write the bundle before the index; if the index or pointer fails, remove
    // the zip so the admin list never shows a dangling record.
    await env.META.delete(keys.objectKey).catch(() => undefined);
    return rejected("storage_failed", 500);
  }

  return json({ ok: true, id: meta.id, receivedAt }, 201, { cors: false });
};
