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
scripts/restore-release-snapshot.mjs                           restore a history snapshot from an existing artifact
test/                                                          Function tests
wrangler.toml                                                  Pages project + R2 binding
```

## API

```
GET /api/v1/apps/{appId}/{platform}/{channel}/latest
GET /api/v1/apps/{appId}/{platform}/{channel}/latest?arch=arm64-v8a
GET /api/v1/apps/{appId}/{platform}/{channel}/public-latest
GET /api/v1/apps/{appId}/{platform}/{channel}/releases
GET /api/v1/install-kits/{kitId}/{channel}/latest
```

The `install-kits` endpoint serves the payload for the DiPlay 装车助手 (installer) app. Its static parts — the device profile, the uninstall notice, and the 爱趣听 / 微信 replacement packages (fixed R2 asset URLs, sizes and SHA-256) — are authored in `functions/_shared/install-kits.ts`. The DiPlay entry is resolved at request time from the app update interface: the Function reads `apps/diplay/android/stable/latest.json` and `apps/diplay/android/beta/latest.json`, skips disabled channels, and uses whichever carries the higher `versionCode` (preferring stable on a tie), so the installer always gets the newest DiPlay build without republishing the kit. If no DiPlay release is available it returns `503 {"error":"diplay_release_unavailable"}`; an unknown `kitId` returns `404`. Responses use `Cache-Control: no-store`.

A stable `latest` request without `id` is treated as the historical DiPlay versionCode 1000 client. Because that client cannot choose a channel, the endpoint reads both the stable and beta manifests and returns the release with the higher `versionCode`, preferring stable on a tie. A disabled release is skipped; if neither channel can serve, the endpoint still returns 200 with `{"schemaVersion":1,"appId":…,"platform":…,"channel":"stable","updateAvailable":false}` so the legacy client shows its up-to-date message instead of a failed check. New clients must always send `id`; the client stores a stable fallback ID if Android ID is unavailable. Whitelist and percentage delivery are bypassed only for this legacy no-id stable request.

The app update endpoint applies the release's enabled/mode/percentage/whitelist policy and accepts the stable client identifier as `?id=...`. `public-latest` serves the current APK for the public homepage regardless of automatic delivery policy. `releases` returns the permanent public history for that channel. The admin endpoints under `/api/v1/admin/` require a signed admin session.

The Function reads this private metadata object:

```
apps/{appId}/{platform}/{channel}/latest.json
```

and expands stored object keys into public asset URLs. Responses use `Cache-Control: no-store`.

`versionCode` is required in every manifest and is the authoritative update value. Clients must compare the manifest `versionCode` with the installed Android `(long)versionCode`; `version` is only a display/tag value.

Download files live in the public assets bucket:

```
apps/{appId}/{platform}/{channel}/{versionName}/{file}
```

Release snapshots are kept for rollback and audit:

```
apps/{appId}/{platform}/{channel}/releases/{versionName}.json
```

Both use the manifest `versionName` (falling back to `version` when no display name is supplied), so builds that share an app version but differ in display name keep separate assets and history entries.

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

`versionName` is the full display version returned by the API and shown by download pages; it is also the object-key segment for assets and release snapshots. When omitted, `version` is used for keys and display. Non-ASCII artifact names and version names are supported and are percent-encoded in public URLs.

The publisher computes SHA-256 and size, uploads the artifact and `SHA256SUMS.txt`, writes a version snapshot, then updates `latest.json` last. It does not read the previous manifest.

The client's default update channel is embedded in the APK manifest. Build a beta artifact with
`-PdiplayUpdateChannel=beta` (the release workflow passes the selected channel automatically);
release builds without that property default to `stable`. A user's explicit in-app channel choice
still overrides the build default.

Use `--dry-run` to print the plan without writing R2.

### Restore a release snapshot

If a snapshot was overwritten, restore it from an artifact that still exists in R2. The script copies the artifact into the versionName directory, writes `SHA256SUMS.txt`, and writes `releases/{versionName}.json`; it does not change `latest.json`:

```sh
set -a; . ./.env.deploy; set +a

node scripts/restore-release-snapshot.mjs \
  --channel beta \
  --version 0.2.21 \
  --version-name 0.2.21_凯美瑞A导航适配版_0.1.0 \
  --version-code 1000 \
  --dry-run
```

Remove `--dry-run` to write. `publishedAt` defaults to the source object's Last-Modified time; release notes can be edited later in `/admin/`.

## GitHub Actions

The DiPlay repository uses `.github/workflows/release.yml`:

- A tag push builds and verifies the signed APK, then stores it only as a short-lived GitHub Actions artifact. It does not write to R2 or deploy the public site.
- Only manually running the workflow (`workflow_dispatch`) and choosing `stable` or `beta` uploads the APK and updates the release platform.
- The workflow builds `:mobile:assembleRelease`, verifies the APK version matches the tag, rejects a `versionCode` that is not greater than the currently published one, uploads to R2 and checks the public endpoint.
- It then installs `update-service` dependencies, rebuilds the Astro site with the newly published manifests baked in, and deploys the Pages project. A site deploy failure fails the workflow after the R2 release is already live.

Manually published releases start with automatic delivery disabled. Sign in to `/admin/` and enable delivery after reviewing the release. Release APKs and APK-derived identity fields are immutable; the console edits only release notes and delivery settings.

### Deploy only the site

Run the **Deploy DiPlay update site** workflow (`.github/workflows/deploy-update-site.yml`) from the Actions tab to rebuild and deploy the Cloudflare Pages project without building or publishing an APK. It runs the update-service tests and typecheck, then `npm --prefix update-service run deploy`, which uploads the static site and its Functions. It uses the `release` environment and requires `CLOUDFLARE_API_TOKEN` and `R2_ACCOUNT_ID`.

Do not use **Publish DiPlay update** for a site-only deploy: it builds and publishes a signed APK first. The legacy **Publish download site (diplay)** workflow deploys the separate GitHub Pages site and does not update this Pages project. The new workflow only appears in the Actions UI for the default branch, so merge it there before running it.

### Release management setup

The Pages Function uses the existing private R2 `META` bucket for release snapshots and delivery settings. `ADMIN_USERNAME` is a non-secret variable managed in `wrangler.toml`. Set only the encrypted values below as Cloudflare Pages secrets in the Production environment; never commit them:

- `ADMIN_PASSWORD_HASH` (secret), generated interactively with `npm run hash-admin-password`
- `ADMIN_SESSION_SECRET` (secret), a random value of at least 32 bytes (for example, `openssl rand -hex 32`)

Set them with `npx wrangler pages secret put ADMIN_PASSWORD_HASH --project-name gtmc-app-updates` (and the same command for `ADMIN_SESSION_SECRET`), or in the Pages project's Settings → Variables and Secrets → Production. Changes require a new deployment. Cloudflare Workers WebCrypto caps PBKDF2 at 100,000 iterations; the hash generator and verifier both use 100,000, and hashes with another iteration count are rejected. Regenerate the hash if it was created with an older script.
The admin console is at `/admin/`. Sessions are signed, expire after eight hours and use Secure, HttpOnly, SameSite=Strict cookies. Automatic delivery settings are stored alongside each release snapshot; admin edits update only its management metadata and, when editing the current version, the `latest.json` pointer as well. Rollback disables delivery for the version being rolled back from, then points `latest.json` at the selected historical snapshot without changing that snapshot's existing policy.

For local login, run `npm run hash-admin-password` and put its output plus a local session secret in `update-service/.dev.vars`:

```dotenv
ADMIN_PASSWORD_HASH=<hash printed by the command>
ADMIN_SESSION_SECRET=<local random value, for example from openssl rand -hex 32>
```

Wrangler loads `.dev.vars` for `npm run dev`; the file is git-ignored. The local username remains the `ADMIN_USERNAME` value in `wrangler.toml` (`admin` by default). Use `http://localhost:8788/admin/` while the dev server is running.

The public `/history/` page lists all permanent release snapshots. It reads a public history API and does not depend on automatic delivery being enabled. The homepage reads `public-latest`; the app update endpoint `latest` applies the release delivery policy and accepts the client's `id` query parameter.

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
| `BAIDU_PUSH_TOKEN` | Optional; Baidu 普通收录 API token for automatic URL push |

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

`versionName` is the full display version, for example `0.3.1_凯美瑞A导航适配版_0.1.0`, and is used for the APK name, asset directory and release snapshot. Builds that reuse a `versionName` overwrite the same release snapshot, so publish each build under a distinct display version. The short `version` (for example `0.3.1`) is used for git tags. Changing only the descriptive suffix creates a separate history entry, but clients still compare `versionCode`, which must increase.

## Adding another app

1. Choose a stable `appId` and a channel, for example `usbcheck` / `stable`.
2. Publish with the same tool or workflow under its own `appId`.
3. Point the app's update client at the same API path.

No Function changes or Pages deployment are needed. Keys are isolated by `appId/platform/channel`, so concurrent releases from different apps are safe. Serialize publishes of the same app and channel with a CI concurrency group.

## Notes

- Assets and checksums are immutable per version; `latest.json` is `no-store`.
- Rollback the pointer to an older snapshot only prevents new clients from seeing the bad release. Clients that already installed a bad version need a higher version with the fix.
- The current publisher uses a single artifact per release. The manifest supports multiple artifacts; add an `arch` value per artifact when per-ABI releases are needed.
