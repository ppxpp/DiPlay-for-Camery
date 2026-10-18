# App Updates

Generic app update manifest service for Cloudflare Pages Functions + R2, located at `update-service/` in the DiPlay repository. One deployment serves every app; each app only owns its own keys under `apps/{appId}/{platform}/{channel}/`. The bundled Astro download site is rebuilt and deployed by the release workflow after each publish.

## Layout

```
functions/api/v1/apps/[appId]/[platform]/[channel]/latest.ts   read-only manifest API
site/                                                          Astro download site
public/404.html                                                static 404 override
dist/                                                          generated Pages output
schema/                                                        v1 manifest and response contracts
scripts/build-site.mjs                                         build site and assemble dist
scripts/publish-update.mjs                                     generic publisher
test/                                                          Function tests
wrangler.toml                                                  Pages project + R2 binding
```

## API

```
GET /api/v1/apps/{appId}/{platform}/{channel}/latest
GET /api/v1/apps/{appId}/{platform}/{channel}/latest?arch=arm64-v8a
```

The Function reads this private metadata object:

```
apps/{appId}/{platform}/{channel}/latest.json
```

and expands stored object keys into public asset URLs. Responses use `Cache-Control: no-store`.

`versionCode` is required in every manifest and is the authoritative update value. Clients must compare the manifest `versionCode` with the installed Android `(long)versionCode`; `version` is only a display/tag value.

Download files live in the public assets bucket:

```
apps/{appId}/{platform}/{channel}/{version}/{file}
```

Release snapshots are kept for rollback and audit:

```
apps/{appId}/{platform}/{channel}/releases/{version}.json
```

## Initial setup

Requirements: Node.js, a Cloudflare account, a Pages project and two R2 buckets.

```sh
npm install

npx wrangler r2 bucket create gtmc-app-updates-meta-prod
npx wrangler r2 bucket create gtmc-app-updates-assets-prod
```

Only these two buckets are required. The `beta` channel uses the same buckets under a different object prefix; it does not need separate buckets.

Set the public asset base URL in `wrangler.toml` or in the Pages project environment:

```toml
[vars]
ASSET_BASE_URL = "https://dl.example.com"
```

Bind the metadata bucket `gtmc-app-updates-meta-prod` as `META`. The assets bucket is read directly through its custom domain and is not bound to the Function.

Deploy the Pages project (builds the Astro site into `dist/`, then uploads it with the Functions):

```sh
npm run deploy
```

The download site is served at `/`; the manifest API stays under `/api/v1/...` on the same origin.

Use custom domains such as `upd.example.com` and `dl.example.com`. Do not use `*.pages.dev` or `*.r2.dev` for clients in mainland China.

The site fetches the public stable/beta manifests at build time and bakes the version data into the HTML, so the first paint has no loading state. After hydration it refreshes both channels silently; a failed refresh leaves the baked data in place. A build therefore needs network access to the update API.

Publishing a release writes R2 objects and then `.github/workflows/release.yml` rebuilds and deploys the Pages project, so the download page and the API move together. Function or site code changes also require a deployment; `npm run deploy` is the manual path.

## Publish a release

```sh
export R2_ACCOUNT_ID=...
export R2_ACCESS_KEY_ID=...
export R2_SECRET_ACCESS_KEY=...
export R2_META_BUCKET=gtmc-app-updates-meta-prod
export R2_ASSETS_BUCKET=gtmc-app-updates-assets-prod

node scripts/publish-update.mjs \
  --app diplay \
  --platform android \
  --channel stable \
  --version 0.3.0 \
  --version-name 0.3.0_凯美瑞A导航适配版_0.1.0 \
  --version-code 99 \
  --artifact ./0.3.0_凯美瑞A导航适配版_0.1.0.apk \
  --arch universal \
  --verify-url https://upd.example.com/api/v1/apps/diplay/android/stable/latest
```

`version` is the short tag used in object keys and release snapshots. The optional `versionName` is the full display version returned by the API and shown by download pages; when omitted, clients fall back to `version`. Non-ASCII artifact names are supported and are percent-encoded in public URLs.

The publisher computes SHA-256 and size, uploads the artifact and `SHA256SUMS.txt`, writes a version snapshot, then updates `latest.json` last. It does not read the previous manifest.

Use `--dry-run` to print the plan without writing R2.

## GitHub Actions

The DiPlay repository publishes with `.github/workflows/release.yml`:

- Push a tag such as `v0.3.0`, or run the workflow manually and choose `stable` or `beta`.
- The workflow builds `:mobile:assembleRelease`, verifies the APK version matches the tag, rejects a `versionCode` that is not greater than the currently published one, uploads to R2 and checks the public endpoint.
- It then installs `update-service` dependencies, rebuilds the Astro site with the newly published manifests baked in, and deploys the Pages project. A site deploy failure fails the workflow after the R2 release is already live.

Repository secrets:

| Name | Purpose |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | Shared signing keystore |
| `SIGNING_KEYSTORE_PASSWORD` | Signing keystore password |
| `SIGNING_KEY_ALIAS` | Signing key alias |
| `SIGNING_KEY_PASSWORD` | Signing key password |
| `DIPLAY_IDENTITY_PK8_BASE64` | Runtime CarPlay identity |
| `DIPLAY_CERTIFICATE_P7B_BASE64` | Runtime CarPlay certificate |
| `R2_ACCOUNT_ID` | R2 S3 endpoint account |
| `R2_ACCESS_KEY_ID` | R2 write access key |
| `R2_SECRET_ACCESS_KEY` | R2 write secret |
| `CLOUDFLARE_API_TOKEN` | Cloudflare Pages deployment (Pages Edit) |

Repository variables:

| Name | Example |
| --- | --- |
| `R2_ASSETS_BUCKET` | `gtmc-app-updates-assets-prod` |
| `R2_META_BUCKET` | `gtmc-app-updates-meta-prod` |
| `UPDATE_API_BASE` | `https://upd.example.com` |

`UPDATE_API_BASE` is the public origin used by the verification step; the workflow appends the `/api/v1/...` path itself.

The workflow uses the `release` GitHub Environment, so secrets can be scoped there and protected with required reviewers.

### Version rule

`versionCode` is the authoritative update value. Every release for an app/channel must have a higher `versionCode` than the currently published manifest; the workflow refuses to publish otherwise.

`versionName` is the full display version, for example `0.3.1_凯美瑞A导航适配版_0.1.0`, and the release APK is published under that name. The short `version` (for example `0.3.1`) is used for tags and object keys. Changing only the descriptive suffix does not create a new update; `versionCode` must still increase.

## Adding another app

1. Choose a stable `appId` and a channel, for example `usbcheck` / `stable`.
2. Publish with the same tool or workflow under its own `appId`.
3. Point the app's update client at the same API path.

No Function changes or Pages deployment are needed. Keys are isolated by `appId/platform/channel`, so concurrent releases from different apps are safe. Serialize publishes of the same app and channel with a CI concurrency group.

## Notes

- Assets and checksums are immutable per version; `latest.json` is `no-store`.
- Rollback the pointer to an older snapshot only prevents new clients from seeing the bad release. Clients that already installed a bad version need a higher version with the fix.
- The current publisher uses a single artifact per release. The manifest supports multiple artifacts; add an `arch` value per artifact when per-ABI releases are needed.
