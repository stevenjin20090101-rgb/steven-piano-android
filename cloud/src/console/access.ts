/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { ConsoleEnv } from '../env';
import { base64urlDecode } from '../shared/ids';

/**
 * The console's own check of Cloudflare Access (defence in depth: Access already stands in front of
 * the whole Worker). Every request must carry `Cf-Access-Jwt-Assertion`, an RS256 JWT signed by one
 * of the team's keys (`https://<team>.cloudflareaccess.com/cdn-cgi/access/certs`, kept 10 minutes),
 * issued by that team, for this application's audience (`ACCESS_AUD`), and in date. Nothing is
 * configured: nothing gets in. `DEV_BYPASS=1` lets `wrangler dev` through, and only for a request
 * to localhost (which never reaches a deployed Worker).
 */

export interface AccessDeps {
  /** How the team's keys are fetched (a test gives its own). */
  fetch?: (url: string) => Promise<Response>;
  /** Now, in milliseconds. */
  now?: () => number;
}

export type AccessResult = { ok: true; email: string } | { ok: false; message: string };

interface Jwk {
  kid?: string;
  kty?: string;
  n?: string;
  e?: string;
}

/** The team's keys by kid, and when they were fetched. */
const keyCache = new Map<string, { keys: Map<string, CryptoKey>; fetchedAt: number }>();
const KEYS_KEPT_MS = 10 * 60 * 1000;
const REFETCH_GAP_MS = 60 * 1000;
/** Clocks differ a little. */
const LEEWAY_S = 30;

const encoder = new TextEncoder();

export async function requireAccess(request: Request, env: ConsoleEnv, deps: AccessDeps = {}): Promise<AccessResult> {
  const url = new URL(request.url);
  if (env.DEV_BYPASS === '1' && isLocal(url.hostname)) return { ok: true, email: 'dev@localhost' };

  const token = request.headers.get('cf-access-jwt-assertion');
  if (!token) return { ok: false, message: 'Sign in through Cloudflare Access.' };
  const team = teamName(env.ACCESS_TEAM_DOMAIN);
  const audience = env.ACCESS_AUD?.trim();
  if (!team || !audience) return { ok: false, message: 'The console is not set up: it needs ACCESS_TEAM_DOMAIN and the ACCESS_AUD secret.' };

  const refused: AccessResult = { ok: false, message: 'Cloudflare Access did not vouch for this request.' };
  const parts = token.split('.');
  if (parts.length !== 3) return refused;
  const header = decodeJson(parts[0]!);
  const payload = decodeJson(parts[1]!);
  const signature = base64urlDecode(parts[2]!);
  if (!header || !payload || !signature) return refused;
  if (header.alg !== 'RS256' || typeof header.kid !== 'string') return refused;

  const key = await keyFor(team, header.kid, deps);
  if (!key) return refused;
  let valid = false;
  try {
    valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, encoder.encode(`${parts[0]}.${parts[1]}`));
  } catch {
    valid = false;
  }
  if (!valid) return refused;

  const now = (deps.now ?? Date.now)() / 1000;
  if (payload.iss !== `https://${team}.cloudflareaccess.com`) return refused;
  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(audience)) return refused;
  if (typeof payload.exp !== 'number' || payload.exp < now - LEEWAY_S) return refused;
  if (typeof payload.nbf === 'number' && payload.nbf > now + LEEWAY_S) return refused;
  if (typeof payload.iat === 'number' && payload.iat > now + LEEWAY_S) return refused;

  const email = typeof payload.email === 'string' && payload.email ? payload.email : typeof payload.common_name === 'string' ? payload.common_name : 'access';
  return { ok: true, email: email.slice(0, 200) };
}

/** "steven", "steven.cloudflareaccess.com" or its https address: the team's name, or null. */
export function teamName(value: string | undefined): string | null {
  if (!value) return null;
  const name = value.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '').replace(/\.cloudflareaccess\.com$/, '');
  return /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(name) ? name : null;
}

function isLocal(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
}

function decodeJson(part: string): Record<string, any> | null {
  const bytes = base64urlDecode(part);
  if (!bytes) return null;
  try {
    const value = JSON.parse(new TextDecoder().decode(bytes));
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

async function keyFor(team: string, kid: string, deps: AccessDeps): Promise<CryptoKey | null> {
  const now = (deps.now ?? Date.now)();
  let entry = keyCache.get(team);
  const stale = !entry || now - entry.fetchedAt > KEYS_KEPT_MS;
  const unknown = entry !== undefined && !entry.keys.has(kid) && now - entry.fetchedAt > REFETCH_GAP_MS;
  if (stale || unknown) {
    const keys = await fetchKeys(team, deps);
    if (keys) {
      entry = { keys, fetchedAt: now };
      keyCache.set(team, entry);
    }
  }
  return entry?.keys.get(kid) ?? null;
}

async function fetchKeys(team: string, deps: AccessDeps): Promise<Map<string, CryptoKey> | null> {
  const get = deps.fetch ?? ((url: string) => fetch(url));
  try {
    const response = await get(`https://${team}.cloudflareaccess.com/cdn-cgi/access/certs`);
    if (!response.ok) return null;
    const body = (await response.json()) as { keys?: Jwk[] };
    const keys = new Map<string, CryptoKey>();
    for (const jwk of body.keys ?? []) {
      if (jwk.kty !== 'RSA' || typeof jwk.kid !== 'string' || typeof jwk.n !== 'string' || typeof jwk.e !== 'string') continue;
      try {
        const key = await crypto.subtle.importKey('jwk', { kty: 'RSA', n: jwk.n, e: jwk.e, alg: 'RS256', ext: true }, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
        keys.set(jwk.kid, key);
      } catch {
        // Not a key this check can use.
      }
    }
    return keys;
  } catch {
    return null;
  }
}

/** Forgets the keys (tests). */
export function forgetKeys(): void {
  keyCache.clear();
}
