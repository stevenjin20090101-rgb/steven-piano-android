/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { RelayEnv } from '../env';
import { prune } from '../shared/db';
import { sha256Hex } from '../shared/hash';
import { clientAddress, error, tooMany, withSecurity } from '../shared/http';
import { parseBearer } from '../shared/ids';
import { DEFAULT_MAX_BODY, SUBPROTOCOL } from '../shared/protocol';
import { enrol } from './enrol';

export { PianoRoom } from './room';

/**
 * The relay: the public Worker (wrangler.relay.jsonc).
 *
 * - `POST /api/enrol`: a tablet enrols with a code from the console (enrol.ts).
 * - `GET /tablet` (WebSocket, `Authorization: Bearer <pianoId>.<secret>`, subprotocol
 *   `steven-piano-relay-1`): a tablet connects to its piano's room, which checks the secret.
 * - `ANY /p/<pianoId>/…`: a browser reaches the piano's panel. Per address, [PANEL_LIMIT] a minute,
 *   a `GET …/api/art/…` (a picture, v1.18 — M47b) [ART_LIMIT] instead, and [LOGIN_LIMIT] for
 *   `…/api/login` too; a body must say its length (411; `Transfer-Encoding` 411)
 *   and be at most `MAX_BODY_BYTES` (413), before a byte of it is read; then the room carries the
 *   request to the tablet with the prefix taken off. `/p/<pianoId>/ws` is the panel's socket.
 *
 * The client's address is always `CF-Connecting-IP`; no header the client sends reaches the room
 * except the few the tablet reads. Every answer carries HSTS and the security headers; none carries
 * a CORS header. A daily cron prunes the audit log (90 days).
 */
export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await route(request, env);
    } catch {
      return error(500, 'server', 'Something went wrong.');
    }
  },

  async scheduled(_controller, env, ctx): Promise<void> {
    ctx.waitUntil(prune(env.DB, Date.now()).then(() => undefined));
  },
} satisfies ExportedHandler<RelayEnv>;

const PIANO_PATH = /^\/p\/([a-z2-7]{12})(\/.*)?$/;

/** The request headers the room passes on to the tablet (host and content-length are the relay's own). */
const PASSED = ['cookie', 'origin', 'content-type', 'x-steven-piano', 'accept'];

async function route(request: Request, env: RelayEnv): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname === '/api/enrol') {
    if (request.method !== 'POST') return error(405, 'method', 'Not allowed here.', { Allow: 'POST' });
    return enrol(request, env);
  }
  if (url.pathname === '/tablet') return tablet(request, env, url);
  const match = PIANO_PATH.exec(url.pathname);
  if (match) {
    const pianoId = match[1]!;
    const rest = match[2];
    if (rest === undefined) {
      // The panel builds its URLs from its own folder: /p/<id> must be /p/<id>/.
      return withHeaders(new Response(null, { status: 308, headers: { Location: `/p/${pianoId}/${url.search}`, 'Cache-Control': 'no-store' } }));
    }
    return panel(request, env, url, pianoId, rest);
  }
  return error(404, 'not-found', 'Not here.');
}

/** A tablet's socket: the subprotocol and a well-formed bearer here; the room checks the secret. */
async function tablet(request: Request, env: RelayEnv, url: URL): Promise<Response> {
  if (request.method !== 'GET' || request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
    return error(426, 'upgrade', 'This address takes a WebSocket.', { Upgrade: 'websocket' });
  }
  const address = clientAddress(request);
  const gate = await env.PANEL_LIMIT.limit({ key: `tablet:${address}` });
  if (!gate.success) return tooMany('Too many connections. Try again in a minute.');
  const offered = (request.headers.get('sec-websocket-protocol') ?? '').split(',').map((p) => p.trim());
  if (!offered.includes(SUBPROTOCOL)) return error(400, 'protocol', `A tablet must speak ${SUBPROTOCOL}.`);
  const bearer = parseBearer(request.headers.get('authorization'));
  if (!bearer) return error(401, 'auth', "This tablet isn't enrolled here, or its access was revoked.");
  const headers = new Headers({
    Upgrade: 'websocket',
    'x-relay-piano': bearer.pianoId,
    'x-relay-secret-hash': await sha256Hex(bearer.secret),
    'x-relay-host': url.host,
    'x-relay-address': address,
  });
  try {
    return await env.ROOMS.getByName(bearer.pianoId).fetch('https://room/tablet', { headers });
  } catch {
    return error(502, 'relay', "The relay couldn't reach the piano's room. Try again.");
  }
}

/** A browser's request or socket for a piano's panel. */
async function panel(request: Request, env: RelayEnv, url: URL, pianoId: string, rest: string): Promise<Response> {
  const address = clientAddress(request);
  // A picture counts against the pictures' own limit (v1.18 — M47b), so a page of covers leaves the panel its 120.
  const picture = request.method === 'GET' && rest.startsWith('/api/art/');
  const gate = picture
    ? await env.ART_LIMIT.limit({ key: `art:${address}` })
    : await env.PANEL_LIMIT.limit({ key: `panel:${address}` });
  if (!gate.success) return tooMany('Too many requests. Try again in a minute.');
  if (rest === '/api/login') {
    const login = await env.LOGIN_LIMIT.limit({ key: `login:${address}` });
    if (!login.success) return tooMany('Too many tries. Try again in a minute.');
  }
  const room = env.ROOMS.getByName(pianoId);
  const headers = new Headers({
    'x-relay-piano': pianoId,
    'x-relay-host': url.host,
    'x-relay-address': address,
    'x-relay-path': rest,
    'x-relay-query': url.search.slice(1),
  });
  for (const name of PASSED) {
    const value = request.headers.get(name);
    if (value !== null) headers.set(name, value);
  }

  if (rest === '/ws' && request.headers.get('upgrade')?.toLowerCase() === 'websocket') {
    if (request.method !== 'GET') return error(405, 'method', 'Not allowed here.', { Allow: 'GET' });
    headers.set('Upgrade', 'websocket');
    try {
      return await room.fetch('https://room/ws', { headers });
    } catch {
      return error(502, 'relay', "The relay couldn't reach the piano. Try again.");
    }
  }

  // The body rule, before a byte is read: its length said, not chunked, at most MAX_BODY_BYTES.
  const max = maxBody(env);
  if (request.headers.has('transfer-encoding')) return error(411, 'length', 'The upload must say how long it is.');
  const declared = request.headers.get('content-length');
  let length = 0;
  if (declared !== null) {
    if (!/^\d{1,16}$/.test(declared.trim())) return error(400, 'length', "The upload's length is not a length.");
    length = Number(declared.trim());
  } else if (request.body !== null) {
    return error(411, 'length', 'The upload must say how long it is.');
  }
  if (length > max) return error(413, 'size', `A request can be ${Math.floor(max / 1048576)} MB at most.`);
  const bodied = length > 0 && request.body !== null && request.method !== 'GET' && request.method !== 'HEAD';
  if (declared !== null) headers.set('x-relay-length', String(bodied ? length : 0));
  try {
    return await room.fetch('https://room/http', { method: request.method, headers, body: bodied ? request.body : null });
  } catch {
    return error(502, 'relay', "The relay lost the piano's answer. Try again.");
  }
}

function maxBody(env: RelayEnv): number {
  const value = Number(env.MAX_BODY_BYTES);
  return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_MAX_BODY;
}

function withHeaders(response: Response): Response {
  withSecurity(response.headers);
  return response;
}
