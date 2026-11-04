import { isAdmin } from "../../../../_shared/admin-auth";
import type { FeedbackIndex } from "../../../../_shared/feedback";
import {
  DEFAULT_FEEDBACK_APP,
  feedbackIndexPrefix,
  isFeedbackAppId,
  readFeedbackIndex,
} from "../../../../_shared/feedback";
import type { Env } from "../../../../_shared/management";
import { json } from "../../../../_shared/management";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;

/**
 * GET /api/v1/admin/feedback?app=diplay&limit=50&cursor=...
 *
 * Lists the index objects newest first. Keys embed a descending timestamp, so
 * ascending key order is already reverse chronological. The `reportPreview`
 * body is omitted here (it can be 64KB per record) and served by the detail
 * endpoint.
 */
export const onRequestGet: PagesFunction<Env> = async ({ request, env }) => {
  if (!await isAdmin(request, env)) return json({ error: "unauthorized" }, 401, { cors: false });
  const url = new URL(request.url);
  const app = url.searchParams.get("app") ?? DEFAULT_FEEDBACK_APP;
  if (!isFeedbackAppId(app)) return json({ error: "invalid_app" }, 400, { cors: false });

  const requested = Number(url.searchParams.get("limit") ?? DEFAULT_LIMIT);
  const limit = Number.isFinite(requested)
    ? Math.min(Math.max(Math.trunc(requested), 1), MAX_LIMIT)
    : DEFAULT_LIMIT;
  const cursor = url.searchParams.get("cursor") ?? undefined;

  const page = await env.META.list({ prefix: feedbackIndexPrefix(app), cursor, limit });
  const items: Array<Omit<FeedbackIndex, "reportPreview">> = [];
  for (const object of page.objects) {
    if (!object.key.endsWith(".json")) continue;
    const index = await readFeedbackIndex(env.META, object.key);
    if (!index) continue;
    const { reportPreview: _preview, ...summary } = index;
    items.push(summary);
  }

  return json({
    items,
    cursor: page.truncated ? page.cursor ?? null : null,
    truncated: page.truncated,
  }, 200, { cors: false });
};
