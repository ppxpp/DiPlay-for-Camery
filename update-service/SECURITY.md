# Update-service security playbook

Scope: the Cloudflare Pages Functions + R2 service in this directory (`gtmc-app-updates`).
This document is the operating manual for the controls added in the hardening
branch; the threat model drives the order.

## Threat model

| Goal | Vector | Primary controls |
| --- | --- | --- |
| Take over publishing | brute-force `/api/v1/admin/login` | edge rate limit + login failure counter + strong password; optional Cloudflare Access |
| Cost amplification | high-frequency public API calls | `s-maxage` caching on public endpoints + edge rate limits + derived one-read models |
| Availability | L7 flood | Cloudflare DDoS protection + WAF rate limits |
| Rollout bypass | forged `?id=` probes | accepted by design (binaries are public); see “Known residual risks” |
| Asset abuse | hotlinking APKs | immutable cache headers + edge rate limiting on the asset host |
| Storage/cost abuse | anonymous feedback uploads | 6MB request + 5MB zip caps, zip entry/expansion limits, per-IP KV rate limit (5/h, 20/day), WAF upload rule, 90-day lifecycle |
| Privacy leak | feedback archive exposure | private `META` bucket, admin-session-only reads, no public download URLs, no IP or device identifier stored |
| Enumeration | walk appId/channel | low sensitivity; use unguessable appIds if needed |

## Layer 1 — Edge (one-time, needs a Cloudflare token)

```sh
# Dry run first: prints the rules that will be written.
node scripts/apply-edge-security.mjs --with-cache-rules

# Apply (rules created by the script are tagged "[update-service]"; other rules are kept).
CF_ZONE_ID=<zone_id> CLOUDFLARE_API_TOKEN=<token> \
  node scripts/apply-edge-security.mjs --apply --with-cache-rules
```

Plan notes (current zone is on the Free plan):

- Free allows **one** rate limiting rule and a **10-second** mitigation timeout, so the
  default rule set is a single block rule on `/api/v1/admin/` (20 requests / 10s per
  IP+colo). Pass `--full-rate-rules` after upgrading to get the public-API and
  heavy-endpoint rules too.
- The feedback upload rule (`POST /api/v1/apps/*/feedback`, 5 requests / 60s per
  IP+colo) is part of the full set only. On the Free plan the single rule is spent
  on the admin surface, so anonymous feedback relies on the in-Function KV limiter
  (5/hour, 20/day) plus Bot Fight Mode. Enable `--full-rate-rules` when the plan
  allows more rules.
- Rate limiting counts per `(ip.src, cf.colo.id)` — Cloudflare requires the colo
  characteristic, so counters are per data centre.
- Cache rules are not affected by these limits.

Token permissions: Zone → WAF → Edit and Zone → Cache Settings → Edit (permission
groups are named "WAF" and "Cache Settings"; there is no separate "Cache Rules"
entry in the token editor), plus Zone → Zone → Read. The token must be scoped to
`mgulu.com`.

Manual console steps that cannot be scripted:

1. **Cloudflare Access** (optional; currently **not enabled** — it was tried and
   removed because the extra login step was considered too heavy for a single
   operator). To enable it: Zero Trust → Access → Applications → Add a
   self-hosted application for `diplay.mgulu.com/admin` and another for
   `diplay.mgulu.com/api/v1/admin`, each with an allow policy limited to your
   email (one-time PIN). Unauthenticated requests then redirect to the Access
   login page while public endpoints stay open. Scripts that need the admin API
   would require an Access service token (`CF-Access-Client-Id` /
   `CF-Access-Client-Secret`).
2. **Bot Fight Mode** (Security → Bots): enable.
3. **WAF managed rules** (Security → WAF): enable the free managed ruleset.
4. **Alerts** (Notifications): alert on 4xx/5xx spikes for `/api/*`, on R2
   Class B operations, and on Workers/Functions invocation anomalies; set a
   billing threshold alert.

## Layer 2 — Application

- Public endpoint responses now carry `Cache-Control: public, max-age=60, s-maxage=60`
  (public-latest, releases, install-kits) and a weak `ETag`; everything else stays
  `no-store` (per-client manifests, admin data).
- `/api/v1/apps/.../latest` validates `id` (≤64 chars, `[A-Za-z0-9_-]`) and `arch`
  (`[a-z0-9_-]{1,32}`); malformed values return 400 before any bucket read.
- Admin responses no longer send `Access-Control-Allow-Origin: *`.
- Admin sessions expire after 4 hours; rotating `ADMIN_SESSION_SECRET` invalidates
  every session immediately (redeploy required for the secret change to take effect).
- `/admin` and `/admin/*` responses send a CSP plus `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy`, and `Permissions-Policy`
  (see `site/public/_headers`).
- Login throttling backstop: 10 failures per client IP per 10 minutes → 429. It
  needs a KV binding; without it the limiter is skipped and the edge rules remain
  the only control.

### Anonymous feedback upload

`POST /api/v1/apps/{appId}/feedback` is the only unauthenticated write path. Its
abuse surface is bounded at several layers:

- Request 6MB / zip 5MB, description 2000 characters, category whitelist, 32-hex
  feedback id, and `feedback.json` identity must match the multipart `meta`.
- The zip is inspected with `fflate`: at most 64 entries, at most 20MB expanded,
  and any absolute, `.`/`..` or backslash path rejects the upload. Only
  `feedback.json`, `report.txt` (must be UTF-8 text) and `manifest.json` are
  decompressed; log entries are never expanded.
- The declared `bundleSha256` and `bundleBytes` must match the received bytes.
- Per-IP limiting is 5/hour and 20/day via `RATE_KV` (TTL 2h/2d). The client
  address is SHA-256 hashed before use as a KV key, so raw IPs are not stored.
  Without the binding the limiter is skipped; keep `RATE_KV` bound in production.
- Idempotency: an existing feedback id returns `200 duplicate` without a write,
  so retries cannot overwrite or amplify storage.
- Writes are bundle → index → pointer; an index failure deletes the bundle. An
  index object always resolves to an existing zip, and admin deletes remove all
  three keys.
- The archive lives in the private `META` bucket under `feedback/{appId}/` and is
  readable only through `/api/v1/admin/feedback/*` after `isAdmin`. Downloads set
  `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`; no
  public or pre-signed URL is generated.
- Retain for 90 days with an R2 lifecycle rule on the `feedback/` prefix. The
  console can delete earlier; nothing is kept past that window.

### Enable the login failure counter (optional but recommended)

```sh
cd update-service
npx wrangler kv namespace create UPDATE_SECURITY   # prints the namespace id
```

Add the binding to `wrangler.toml`:

```toml
[[kv_namespaces]]
binding = "RATE_KV"
id = "<namespace id from the command above>"
```

Redeploy. The functions read `env.RATE_KV` defensively: a missing binding does not
break login.

## Layer 3 — Derived read models

`releases-index.json`, `latest-all.json` and `latest-any.json` are written by:

- `scripts/publish-update.mjs` after each publish (`scripts/derived.mjs`), and
- the admin API after enable/disable, rollback, and delete.

Readers use them when present and fall back to the legacy multi-read paths when
they are missing or invalid. If a derived object is ever stale, delete it; the
fallback serves correct data until the next publish or admin action rebuilds it.

## Secrets and rotation

| Secret | Where | Rotation |
| --- | --- | --- |
| `ADMIN_PASSWORD_HASH` | Pages Production secret | quarterly or on staff change (`npm run hash-admin-password`) |
| `ADMIN_SESSION_SECRET` | Pages Production secret | on any suspicion; ≥32 random bytes (`openssl rand -hex 32`) |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | CI `release` environment | quarterly |
| `CLOUDFLARE_API_TOKEN` | CI `release` environment | yearly; Pages Edit only |
| `SIGNING_KEYSTORE_*` | CI `release` environment | on compromise only (changes the APK signature) |

`ADMIN_USERNAME` lives in `wrangler.toml` and is not a secret, but use a strong
password hash regardless. `.dev.vars` and `.env.deploy` stay git-ignored.

## Verification checklist

```sh
# Rate limiting: 6 rapid login attempts should end in 429 (edge or KV).
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    -H 'content-type: application/json' \
    -d '{"username":"admin","password":"wrong"}' \
    https://diplay.mgulu.com/api/v1/admin/login
done

# Public caching headers.
curl -sI https://diplay.mgulu.com/api/v1/apps/diplay/android/beta/public-latest | grep -iE 'cache-control|etag'
curl -sI https://diplay.mgulu.com/api/v1/install-kits/camry-a/stable/latest | grep -i cache-control

# Input validation.
curl -s 'https://diplay.mgulu.com/api/v1/apps/diplay/android/stable/latest?id=bad%20id'   # invalid_client_id
curl -s 'https://diplay.mgulu.com/api/v1/apps/diplay/android/stable/latest?arch=arm64,x86' # invalid_arch

# Derived objects (private bucket; check via a publish or the admin API).

# Anonymous feedback: validation then rate limiting.
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -F 'meta={"schemaVersion":1}' -F 'bundle=@/dev/null' \
  https://diplay.mgulu.com/api/v1/apps/diplay/feedback                  # 422 invalid_meta
curl -s -o /dev/null -w "%{http_code}\n" https://diplay.mgulu.com/api/v1/admin/feedback  # 401

# Feedback archive is private: use the admin console or a session cookie.
# Confirm the R2 lifecycle rule on the feedback/ prefix keeps 90 days.
```

## Known residual risks

- **Rollout gating is not secrecy.** `percentage`/`whitelist` rely on a
  self-reported `?id=`; an attacker who knows a pool id can ask for the grey
  build. Severity is limited because grey builds are not published anywhere
  else: the legacy no-id `latest` path only sees full releases, a client outside
  a pool is served the channel's newest full release, and `public-latest`,
  `install-kits` and the public history all point at the newest full release.
  Accepted because APKs are public artifacts. If gating must be enforced, move
  to server-issued tickets.
- **Asset bandwidth** is not rate limited by these rules unless you add a rule
  for the asset host; prefer Cloudflare edge caching (already `immutable`).
- **No WAF managed rules by default** — enable them manually (Layer 1 step 3).
- **Derived objects are eventually consistent** with the latest pointer during a
  publish; both are written in the same script run and readers fall back safely.
- **Anonymous feedback can still be polluted** by a determined sender rotating
  addresses within the edge/KV budgets. It contains no device identity by design,
  so the only response is deletion from the admin console, tightening the KV or
  edge limits, and reviewing R2 `feedback/` volume.

## Incident runbook

1. **Suspected admin compromise**: rotate `ADMIN_SESSION_SECRET` and
   `ADMIN_PASSWORD_HASH`, redeploy, review `/api/v1/admin/*` logs and released
   versions, then re-enable the known-good release.
2. **Cost spike**: enable/raise edge rate limits, verify public endpoints serve
   cache hits, check R2 Class B metrics for the offending key prefix.
3. **Rollback of a bad release**: use the admin console (POST
   `/api/v1/admin/releases` with the previous release key) or run
   `scripts/restore-release-snapshot.mjs`; derived objects are rebuilt by both.
4. **Feedback upload abuse**: check the R2 `feedback/` object count and the
   `/api/v1/apps/*/feedback` 4xx/5xx ratio; delete offending records in the admin
   console, lower `RATE_LIMIT_PER_HOUR`/`RATE_LIMIT_PER_DAY` in
   `functions/_shared/feedback.ts`, and apply the `--full-rate-rules` WAF set if the
   plan allows it.
