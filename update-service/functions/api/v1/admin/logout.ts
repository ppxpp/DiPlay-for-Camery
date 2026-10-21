import { cookieHeader } from "../../../_shared/admin-auth";
import type { Env } from "../../../_shared/management";
import { json } from "../../../_shared/management";

export const onRequestPost: PagesFunction<Env> = async () => {
  const response = json({ ok: true });
  response.headers.set("set-cookie", cookieHeader("", 0));
  return response;
};
