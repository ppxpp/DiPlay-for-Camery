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
- Rate limiting counts per `(ip.src, cf.colo.id)` — Cloudflare requires the colo
  characteristic, so counters are per data centre.
- Cache rules are not affected by these limits.

Token permissions: Zone → WAF → Edit and Zone → Cache Settings → Edit (permission
groups are named "WAF" and "Cache Settings"; there is no separate "Cache Rules"
entry in the token editor), plus Zone → Zone → Read. The token must be scoped to
`mgulu.com`.

Manual console steps that cannot be scripted:

1. **Cloudflare Access** (Zero Trust → Access → Applications): protect
   `diplay.mgulu.com/admin` and `diplay.mgulu.com/api/v1/admin` with a policy
   limited to your email/IdP group. This removes the brute-force surface entirely.
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

`releases-index.json` and `latest-any.json` are written by:

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
```

## Known residual risks

- **Rollout gating is not secrecy.** `percentage`/`whitelist` rely on a
  self-reported `?id=`; an attacker can probe ids, and `public-latest`/legacy
  paths expose the newest build regardless. Accepted because APKs are public
  artifacts. If gating must be enforced, move to server-issued tickets.
- **Asset bandwidth** is not rate limited by these rules unless you add a rule
  for the asset host; prefer Cloudflare edge caching (already `immutable`).
- **No WAF managed rules by default** — enable them manually (Layer 1 step 3).
- **Derived objects are eventually consistent** with the latest pointer during a
  publish; both are written in the same script run and readers fall back safely.

## Incident runbook

1. **Suspected admin compromise**: rotate `ADMIN_SESSION_SECRET` and
   `ADMIN_PASSWORD_HASH`, redeploy, review `/api/v1/admin/*` logs and released
   versions, then re-enable the known-good release.
2. **Cost spike**: enable/raise edge rate limits, verify public endpoints serve
   cache hits, check R2 Class B metrics for the offending key prefix.
3. **Rollback of a bad release**: use the admin console (POST
   `/api/v1/admin/releases` with the previous release key) or run
   `scripts/restore-release-snapshot.mjs`; derived objects are rebuilt by both.
