import type { Env } from "./management";

const COOKIE = "diplay_admin_session";
const SESSION_SECONDS = 8 * 60 * 60;
const ITERATIONS = 210_000;

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function fromHex(value: string): Uint8Array<ArrayBuffer> | null {
  if (!/^(?:[0-9a-f]{2})+$/i.test(value)) return null;
  const pairs = value.match(/.{2}/g) ?? [];
  const bytes = new Uint8Array(pairs.length);
  pairs.forEach((pair, index) => { bytes[index] = Number.parseInt(pair, 16); });
  return bytes;
}

async function hmac(secret: string, value: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)));
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
  let difference = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) difference |= (left[index] ?? 0) ^ (right[index] ?? 0);
  return difference === 0;
}

export async function verifyPassword(password: string, encoded: string): Promise<boolean> {
  const [saltHex, hashHex, iterationsText] = encoded.split(":");
  const salt = fromHex(saltHex ?? "");
  const expected = fromHex(hashHex ?? "");
  const iterations = Number(iterationsText ?? ITERATIONS);
  if (!salt || !expected || salt.length < 16 || expected.length !== 32 || !Number.isInteger(iterations) || iterations < ITERATIONS || iterations > 1_000_000) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256);
  return constantTimeEqual(new Uint8Array(bits), expected);
}

export function cookieHeader(token: string, maxAge: number): string {
  return `${COOKIE}=${token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`;
}

export async function createSession(env: Env): Promise<string> {
  const expires = Math.floor(Date.now() / 1000) + SESSION_SECONDS;
  const payload = toBase64Url(new TextEncoder().encode(`${env.ADMIN_USERNAME}:${expires}`));
  return `${payload}.${toBase64Url(await hmac(env.ADMIN_SESSION_SECRET, payload))}`;
}

export async function isAdmin(request: Request, env: Env): Promise<boolean> {
  if (!env.ADMIN_SESSION_SECRET || !env.ADMIN_USERNAME) return false;
  const cookie = request.headers.get("cookie")?.split(";").map((part) => part.trim()).find((part) => part.startsWith(`${COOKIE}=`));
  const token = cookie?.slice(COOKIE.length + 1);
  if (!token) return false;
  const [payload, signature] = token.split(".");
  if (!payload || !signature) return false;
  let actual: Uint8Array;
  try {
    actual = Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/")), (char) => char.charCodeAt(0));
  } catch {
    return false;
  }
  if (!constantTimeEqual(await hmac(env.ADMIN_SESSION_SECRET, payload), actual)) return false;
  try {
    const decoded = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const [username, expiry] = new TextDecoder().decode(Uint8Array.from(decoded, (char) => char.charCodeAt(0))).split(":");
    return username === env.ADMIN_USERNAME && Number(expiry) > Math.floor(Date.now() / 1000);
  } catch {
    return false;
  }
}

export const sessionSeconds = SESSION_SECONDS;
export const sessionCookieName = COOKIE;
