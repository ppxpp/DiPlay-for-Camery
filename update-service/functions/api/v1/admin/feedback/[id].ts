import { isAdmin } from "../../../../_shared/admin-auth";
import {
  DEFAULT_FEEDBACK_APP,
  feedbackPointerKey,
  isFeedbackAppId,
  isFeedbackId,
  readFeedbackIndex,
  readFeedbackPointer,
} from "../../../../_shared/feedback";
import type { Env } from "../../../../_shared/management";
import { json } from "../../../../_shared/management";

function appOf(request: Request): string | null {
  const app = new URL(request.url).searchParams.get("app") ?? DEFAULT_FEEDBACK_APP;
  return isFeedbackAppId(app) ? app : null;
}

function idOf(params: Record<string, string | string[]>): string | null {
  const value = Array.isArray(params.id) ? params.id[0] : params.id;
  return isFeedbackId(value) ? value : null;
}

/** GET /api/v1/admin/feedback/{id} — full index including the report preview. */
export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401, { cors: false });
  const app = appOf(request);
  const id = idOf(params);
  if (!app || !id) return json({ error: "invalid_feedback_id" }, 400, { cors: false });

  const pointer = await readFeedbackPointer(env.META, app, id);
  if (!pointer) return json({ error: "feedback_not_found" }, 404, { cors: false });
  const index = await readFeedbackIndex(env.META, pointer.indexKey);
  if (!index) return json({ error: "feedback_not_found" }, 404, { cors: false });
  return json(index, 200, { cors: false });
};

/** DELETE /api/v1/admin/feedback/{id} — removes bundle, index and by-id pointer. */
export const onRequestDelete: PagesFunction<Env> = async ({ request, env, params }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401, { cors: false });
  const app = appOf(request);
  const id = idOf(params);
  if (!app || !id) return json({ error: "invalid_feedback_id" }, 400, { cors: false });

  const pointer = await readFeedbackPointer(env.META, app, id);
  if (!pointer) return json({ error: "feedback_not_found" }, 404, { cors: false });
  const index = await readFeedbackIndex(env.META, pointer.indexKey);
  const bundleKey = index?.bundleKey ?? pointer.bundleKey;

  await env.META.delete(bundleKey);
  await env.META.delete(pointer.indexKey);
  await env.META.delete(feedbackPointerKey(app, id));
  return json({ ok: true }, 200, { cors: false });
};
