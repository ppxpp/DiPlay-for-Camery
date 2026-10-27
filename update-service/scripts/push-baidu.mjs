#!/usr/bin/env node
const SITE = (process.env.BAIDU_PUSH_SITE || "https://diplay.mgulu.com").replace(/\/+$/, "");
const TOKEN = process.env.BAIDU_PUSH_TOKEN?.trim();

if (!TOKEN) {
  console.log("BAIDU_PUSH_TOKEN is not set; skipping Baidu URL push.");
  process.exit(0);
}

async function fetchText(url, attempts = 3) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: { "cache-control": "no-cache" },
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.text();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await new Promise((resolve) => setTimeout(resolve, 2000 * attempt));
    }
  }
  throw lastError;
}

function locations(xml) {
  return [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1].trim());
}

async function collectUrls() {
  const stamp = Date.now();
  const index = await fetchText(`${SITE}/sitemap-index.xml?t=${stamp}`);
  const children = locations(index);
  const sitemaps = children.length ? children : [`${SITE}/sitemap-0.xml`];
  const urls = [];
  for (const sitemap of sitemaps) {
    urls.push(...locations(await fetchText(`${sitemap}?t=${stamp}`)));
  }
  return [...new Set(urls)].filter((url) => url.startsWith(`${SITE}/`));
}

async function push(urls) {
  const endpoint = `http://data.zz.baidu.com/urls?site=${encodeURIComponent(SITE)}&token=${encodeURIComponent(TOKEN)}`;
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "content-type": "text/plain" },
        body: urls.join("\n"),
        signal: AbortSignal.timeout(30_000),
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 300)}`);
      return text;
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, 2000 * attempt));
    }
  }
  throw lastError;
}

try {
  const urls = await collectUrls();
  if (!urls.length) {
    console.log("No sitemap URLs found; nothing to push to Baidu.");
    process.exit(0);
  }
  const result = await push(urls);
  let parsed;
  try { parsed = JSON.parse(result); } catch { parsed = null; }
  if (parsed?.error) {
    console.log(`::warning::Baidu push rejected: ${result.slice(0, 300)}`);
  } else {
    console.log(`Baidu push accepted ${urls.length} URL(s): ${result}`);
  }
} catch (error) {
  console.log(`::warning::Baidu push failed: ${error instanceof Error ? error.message : error}`);
}
