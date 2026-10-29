import {
  buildInstallKitResponse,
  type InstallKitEnv,
} from "../../../../../_shared/install-kits";

const first = (value: string | string[] | undefined) =>
  Array.isArray(value) ? value[0] : value;

export const onRequestGet: PagesFunction<InstallKitEnv> = ({ env, params }) =>
  buildInstallKitResponse(env, first(params.kitId), first(params.channel));
