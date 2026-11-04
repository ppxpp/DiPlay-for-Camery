import { isAdmin } from "../../../../../_shared/admin-auth";
import {
  DEFAULT_FEEDBACK_APP,
  isFeedbackAppId,
  isFeedbackId,
  readFeedbackIndex,
  readFeedbackPointer,
} from "../../../../../_shared/feedback";
import type { Env } from "../../../../../_shared/management";
import { json } from "../../../../../_shared/management";

/**
 * GET /api/v1/admin/feedback/{id}/bundle
 *
 * Streams the archived zip through the admin session. No public URL is ever
 * generated; the download is always an attachment and never sniffed.
 */
export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401, { cors: false });
  const app = new URL(request.url).searchParams.get("app") ?? DEFAULT_FEEDBACK_APP;
  if (!isFeedbackAppId(app)) return json({ error: "invalid_app" }, 400, { cors: false });
  const idValue = Array.isArray(params.id) ? params.id[0] : params.id;
  if (!isFeedbackId(idValue)) return json({ error: "invalid_feedback_id" }, 400, { cors: false });

  const pointer = await readFeedbackPointer(env.META, app, idValue);
  if (!pointer) return json({ error: "feedback_not_found" }, 404, { cors: false });
  const index = await readFeedbackIndex(env.META, pointer.indexKey);
  if (!index) return json({ error: "feedback_not_found" }, 404, { cors: false });

  const object = await env.META.get(index.bundleKey);
  if (!object) return json({ error: "feedback_not_found" }, 404, { cors: false });

  const headers: Record<string, string> = {
    "content-type": "application/zip",
    "content-disposition": `attachment; filename="diplay-feedback-${idValue}.zip"`,
    "content-length": String(object.size),
    "cache-control": "no-store",
    "cdn-cache-control": "no-store",
    "x-content-type-options": "nosniff",
  };
  return new Response(object.body, { status: 200, headers });
};
