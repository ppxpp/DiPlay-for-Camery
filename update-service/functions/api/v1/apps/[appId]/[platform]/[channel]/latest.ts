import type { Env } from "../../../../../../_shared/latest";
import { latestManifestResponse } from "../../../../../../_shared/latest";

export const onRequestGet: PagesFunction<Env> = ({ request, env, params }) =>
  latestManifestResponse(request, env, params.appId, params.platform, params.channel);
