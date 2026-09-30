/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { env } from 'cloudflare:workers';
import { handle } from '../src/console/index';
import type { ConsoleEnv } from '../src/env';
import { base64url } from '../src/shared/ids';

/** The console as the tests call it: its handler, with the relay's bindings, a stand-in page, and Access's keys served here. */

export const CONSOLE = 'https://console.test';
export const TEAM = 'steven-test';
export const AUD = 'aud-4c2f6a';

export interface Signer {
  kid: string;
  sign(payload: Record<string, unknown>, header?: Record<string, unknown>): Promise<string>;
  jwk: JsonWebKey & { kid: string };
}

export async function newSigner(kid = `kid-${crypto.randomUUID()}`): Promise<Signer> {
  const pair = (await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  )) as CryptoKeyPair;
  const jwk = (await crypto.subtle.exportKey('jwk', pair.publicKey)) as JsonWebKey;
  const encode = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));
  return {
    kid,
    jwk: { ...jwk, kid },
    async sign(payload, header = {}) {
      const head = encode({ alg: 'RS256', kid, typ: 'JWT', ...header });
      const body = encode(payload);
      const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(`${head}.${body}`)));
      return `${head}.${body}.${base64url(signature)}`;
    },
  };
}

/** A token as Access issues one for the console (of [team]). */
export function claims(overrides: Record<string, unknown> = {}, team = TEAM): Record<string, unknown> {
  const now = Math.floor(Date.now() / 1000);
  return {
    aud: [AUD],
    email: 'steven@example.com',
    exp: now + 3600,
    iat: now,
    nbf: now,
    iss: `https://${team}.cloudflareaccess.com`,
    type: 'app',
    sub: 'user-1',
    ...overrides,
  };
}

export function consoleEnv(overrides: Partial<ConsoleEnv> = {}): ConsoleEnv {
  return {
    ROOMS: env.ROOMS,
    DB: env.DB,
    ASSETS: {
      fetch: async () => new Response('<!doctype html><title>Steven Piano Cloud</title>', { headers: { 'Content-Type': 'text/html; charset=utf-8' } }),
      connect: () => {
        throw new Error('no');
      },
    } as unknown as Fetcher,
    ACCESS_TEAM_DOMAIN: TEAM,
    ACCESS_AUD: AUD,
    RELAY_URL: 'https://relay.test',
    ...overrides,
  };
}

/** Serves [team]'s keys from [signers] at its certs address; counts the fetches. */
export function certs(...signers: Signer[]) {
  return teamCerts(TEAM, ...signers);
}

export function teamCerts(team: string, ...signers: Signer[]) {
  const served = { fetches: 0 };
  const fetch = async (url: string): Promise<Response> => {
    served.fetches++;
    if (url !== `https://${team}.cloudflareaccess.com/cdn-cgi/access/certs`) return new Response('no', { status: 404 });
    return Response.json({ keys: signers.map((s) => ({ ...s.jwk, alg: 'RS256', use: 'sig' })) });
  };
  return { fetch, served };
}

/** A request to the console by the signed-in owner (unless told otherwise), with the console's header. */
export async function consoleCall(
  path: string,
  init: { method?: string; body?: unknown; token?: string | null; header?: boolean; origin?: string; env?: ConsoleEnv; fetch?: (url: string) => Promise<Response> } = {},
): Promise<Response> {
  const headers = new Headers();
  if (init.token) headers.set('Cf-Access-Jwt-Assertion', init.token);
  if (init.header !== false) headers.set('X-Steven-Piano', '1');
  if (init.origin) headers.set('Origin', init.origin);
  let body: string | undefined;
  if (init.body !== undefined) {
    body = JSON.stringify(init.body);
    headers.set('Content-Type', 'application/json');
    headers.set('Content-Length', String(new TextEncoder().encode(body).byteLength));
  }
  const request = new Request(`${CONSOLE}${path}`, { method: init.method ?? 'GET', headers, body });
  return handle(request, init.env ?? consoleEnv(), { fetch: init.fetch });
}

/**
 * The owner, signed in: a team of this test's own (so no two tests share the console's key cache),
 * its key served at its certs address, and a call that carries its token.
 */
export async function owner() {
  const team = `team-${crypto.randomUUID().slice(0, 8)}`;
  const signer = await newSigner();
  const { fetch } = teamCerts(team, signer);
  const token = await signer.sign(claims({}, team));
  const env = consoleEnv({ ACCESS_TEAM_DOMAIN: team });
  return {
    call: (path: string, init: Parameters<typeof consoleCall>[1] = {}) => consoleCall(path, { token, fetch, env, ...init }),
  };
}
