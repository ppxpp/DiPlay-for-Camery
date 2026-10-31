import { isAdmin } from "../../../_shared/admin-auth";
import type { Env } from "../../../_shared/management";
import { json } from "../../../_shared/management";

export const onRequestGet: PagesFunction<Env> = async ({ request, env }) =>
  json({ authenticated: await isAdmin(request, env) }, 200, { cors: false });
