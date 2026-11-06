import type { Env } from "../../../../../_shared/latest";
import { latestManifestResponse } from "../../../../../_shared/latest";

// A client that does not name a channel is served the test channel.
export const onRequestGet: PagesFunction<Env> = async ({ request, env, params }) =>
  latestManifestResponse(request, env, params.appId, params.platform, undefined);
