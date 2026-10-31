import { cookieHeader, createSession, sessionSeconds, verifyPassword } from "../../../_shared/admin-auth";
import type { Env } from "../../../_shared/management";
import { json } from "../../../_shared/management";

const MAX_FAILURES = 10;
const FAILURE_WINDOW_SECONDS = 600;

export const onRequestPost: PagesFunction<Env> = async ({ request, env }) => {
  if (!env.ADMIN_USERNAME || !env.ADMIN_PASSWORD_HASH || !env.ADMIN_SESSION_SECRET) {
    return json({ error: "admin_not_configured" }, 503, { cors: false });
  }

  // The edge rate limiting rules are the primary control; this counter is a
  // binding-based backstop that also survives behind shared NAT addresses.
  const failureKey = `admin-login-fail:${request.headers.get("cf-connecting-ip") ?? "unknown"}`;
  const failures = Number((await env.RATE_KV?.get(failureKey)) ?? "0");
  if (Number.isFinite(failures) && failures >= MAX_FAILURES) {
    return json({ error: "too_many_attempts" }, 429, { cors: false });
  }

  let body: unknown;
  try { body = await request.json(); } catch { return json({ error: "invalid_request" }, 400, { cors: false }); }
  if (!body || typeof body !== "object") return json({ error: "invalid_request" }, 400, { cors: false });
  const input = body as Record<string, unknown>;
  const valid = typeof input.username === "string" &&
    typeof input.password === "string" &&
    input.username === env.ADMIN_USERNAME &&
    await verifyPassword(input.password, env.ADMIN_PASSWORD_HASH);
  if (!valid) {
    await env.RATE_KV?.put(failureKey, String(failures + 1), { expirationTtl: FAILURE_WINDOW_SECONDS });
    return json({ error: "invalid_credentials" }, 401, { cors: false });
  }

  await env.RATE_KV?.delete(failureKey);
  return new Response(JSON.stringify({ ok: true }), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "set-cookie": cookieHeader(await createSession(env), sessionSeconds),
    },
  });
};
