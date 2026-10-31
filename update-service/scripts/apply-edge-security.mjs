#!/usr/bin/env node
// Applies the update-service edge controls: WAF rate limiting rules and,
// optionally, cache rules for the public JSON API. Rules created by this
// script are tagged; other rules on the same phases are preserved.
//
// Dry run (default): prints the planned rules.
// Apply:   CF_ZONE_ID=... CLOUDFLARE_API_TOKEN=... node scripts/apply-edge-security.mjs --apply
//
// Token needs: Zone > WAF > Edit, and (with --with-cache-rules) Zone > Cache Rules > Edit.
import { parseArgs } from "node:util";

const TAG = "[update-service]";
const RATE_PHASE = "http_ratelimit";
const CACHE_PHASE = "http_request_cache_settings";

const { values } = parseArgs({
  options: {
    zone: { type: "string" },
    apply: { type: "boolean", default: false },
    "with-cache-rules": { type: "boolean", default: false },
    "full-rate-rules": { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
});

if (values.help) {
  console.log(`
Usage: node scripts/apply-edge-security.mjs [--apply] [--with-cache-rules] [--full-rate-rules] [--zone <zone_id>]

Rate limiting defaults to ONE rule (admin API), which fits the Free plan's single
rate limiting rule. Pass --full-rate-rules on plans that allow more rules.

Environment:
  CF_ZONE_ID            Cloudflare zone id (or pass --zone)
  CLOUDFLARE_API_TOKEN  token with Zone WAF Edit (and Cache Rules Edit for --with-cache-rules)

Without --apply this is a dry run: it prints the rules that would be written.
`);
  process.exit(0);
}

const zone = values.zone?.trim() || process.env.CF_ZONE_ID?.trim();
const token = process.env.CLOUDFLARE_API_TOKEN?.trim();

const fullRateRules = [
  {
    action: "block",
    description: `${TAG} admin login`,
    enabled: true,
    expression: 'http.request.uri.path eq "/api/v1/admin/login"',
    ratelimit: {
      characteristics: ["ip.src", "cf.colo.id"],
      period: 10,
      requests_per_period: 5,
      mitigation_timeout: 600,
    },
  },
  {
    action: "block",
    description: `${TAG} admin api`,
    enabled: true,
    expression: 'starts_with(http.request.uri.path, "/api/v1/admin/")',
    ratelimit: {
      characteristics: ["ip.src", "cf.colo.id"],
      period: 60,
      requests_per_period: 20,
      mitigation_timeout: 600,
    },
  },
  {
    action: "managed_challenge",
    description: `${TAG} public api`,
    enabled: true,
    expression: 'starts_with(http.request.uri.path, "/api/")',
    ratelimit: {
      characteristics: ["ip.src", "cf.colo.id"],
      period: 60,
      requests_per_period: 120,
      mitigation_timeout: 60,
    },
  },
  {
    action: "managed_challenge",
    description: `${TAG} heavy endpoints`,
    enabled: true,
    expression:
      'starts_with(http.request.uri.path, "/api/") and (http.request.uri.path contains "/releases" or http.request.uri.path contains "/install-kits")',
    ratelimit: {
      characteristics: ["ip.src", "cf.colo.id"],
      period: 60,
      requests_per_period: 30,
      mitigation_timeout: 60,
    },
  },
];

// Free plans allow a single rate limiting rule; protect the admin surface with
// it and keep public-API abuse behind Bot Fight Mode plus response caching.
const liteRateRules = [
  {
    action: "block",
    description: `${TAG} admin api`,
    enabled: true,
    expression: 'starts_with(http.request.uri.path, "/api/v1/admin/")',
    ratelimit: {
      characteristics: ["ip.src", "cf.colo.id"],
      period: 10,
      requests_per_period: 20,
      mitigation_timeout: 10,
    },
  },
];

const rateRules = values["full-rate-rules"] ? fullRateRules : liteRateRules;

const cacheRules = [
  {
    action: "set_cache_settings",
    description: `${TAG} cache public manifests`,
    enabled: true,
    expression:
      'starts_with(http.request.uri.path, "/api/v1/apps/") or starts_with(http.request.uri.path, "/api/v1/install-kits/")',
    action_parameters: {
      cache: true,
      edge_ttl: { mode: "override_origin", default: 60 },
      browser_ttl: { mode: "respect_origin" },
    },
  },
];

function printRules(title, rules) {
  console.log(`\n${title}`);
  for (const rule of rules) {
    const limit = rule.ratelimit
      ? `${rule.ratelimit.requests_per_period} req / ${rule.ratelimit.period}s per ${rule.ratelimit.characteristics.join(",")}`
      : "cache settings";
    console.log(`  - [${rule.action}] ${rule.description} :: ${rule.expression} :: ${limit}`);
  }
}

printRules(`Rate limiting rules (phase ${RATE_PHASE}, ${values["full-rate-rules"] ? "full" : "lite"})`, rateRules);
if (values["with-cache-rules"]) printRules(`Cache rules (phase ${CACHE_PHASE})`, cacheRules);

if (!values.apply) {
  console.log("\nDry run: nothing was written. Add --apply with CF_ZONE_ID and CLOUDFLARE_API_TOKEN.");
  process.exit(0);
}
if (!zone) {
  console.error("error: CF_ZONE_ID (or --zone) is required with --apply");
  process.exit(1);
}
if (!token) {
  console.error("error: CLOUDFLARE_API_TOKEN is required with --apply");
  process.exit(1);
}

const base = `https://api.cloudflare.com/client/v4/zones/${zone}`;

async function api(method, path, body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  return { status: response.status, json };
}

async function replaceTaggedRules(phase, rules) {
  const current = await api("GET", `/rulesets/phases/${phase}/entrypoint`);
  let existing = [];
  if (current.status === 200) {
    existing = (current.json?.result?.rules ?? []).filter(
      (rule) => !String(rule.description ?? "").startsWith(TAG),
    );
  } else if (current.status !== 404) {
    throw new Error(`reading ${phase} entrypoint failed: HTTP ${current.status} ${JSON.stringify(current.json)}`);
  }
  const merged = [...existing, ...rules];
  const put = await api("PUT", `/rulesets/phases/${phase}/entrypoint`, { rules: merged });
  if (put.status >= 300) {
    throw new Error(`writing ${phase} entrypoint failed: HTTP ${put.status} ${JSON.stringify(put.json)}`);
  }
  console.log(`Applied ${rules.length} rule(s) to ${phase} (kept ${existing.length} other rule(s)).`);
}

try {
  await replaceTaggedRules(RATE_PHASE, rateRules);
  if (values["with-cache-rules"]) await replaceTaggedRules(CACHE_PHASE, cacheRules);
  console.log("Done.");
} catch (error) {
  console.error(`error: ${error.message}`);
  console.error("If this is a plan limit, reduce the rule count or upgrade the Cloudflare plan.");
  process.exit(1);
}
