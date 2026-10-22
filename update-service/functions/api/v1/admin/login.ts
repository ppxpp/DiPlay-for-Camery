import { cookieHeader, createSession, sessionSeconds, verifyPassword } from "../../../_shared/admin-auth";
import type { Env } from "../../../_shared/management";
import { json } from "../../../_shared/management";

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
  if (!env.ADMIN_USERNAME || !env.ADMIN_PASSWORD_HASH || !env.ADMIN_SESSION_SECRET) return json({ error: "admin_not_configured" }, 503);
  let body: unknown;
  try { body = await request.json(); } catch { return json({ error: "invalid_request" }, 400); }
  if (!body || typeof body !== "object") return json({ error: "invalid_request" }, 400);
  const input = body as Record<string, unknown>;
  if (typeof input.username !== "string" || typeof input.password !== "string" || input.username !== env.ADMIN_USERNAME || !await verifyPassword(input.password, env.ADMIN_PASSWORD_HASH)) {
    return json({ error: "invalid_credentials" }, 401);
  }
  return new Response(JSON.stringify({ ok: true }), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "set-cookie": cookieHeader(await createSession(env), sessionSeconds),
    },
  });
};
