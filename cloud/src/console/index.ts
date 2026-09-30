/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { ConsoleEnv } from '../env';
import { DEFAULT_NAME, PUBLIC_COLUMNS, auditStatement, auditView, pianoView, type AuditRow, type PianoRow } from '../shared/db';
import { error, json, readJson, withSecurity } from '../shared/http';
import { CODE_LIFE_MS, PIANO_ID, newEnrolCode, newPianoId } from '../shared/ids';
import { hasControl, isObject } from '../shared/protocol';
import { requireAccess, type AccessDeps } from './access';

/**
 * The console: the owner's Worker (wrangler.console.jsonc), behind Cloudflare Access, which every
 * request also passes here ([requireAccess]). It serves its page (console/static) and this API; every
 * API request carries `X-Steven-Piano: 1` (403 without) and, when it says where it came from, the
 * console's own Origin (403 otherwise). No CORS, ever.
 *
 * | Method | Path | Answer |
 * |---|---|---|
 * | GET | /api/pianos | every piano (D1), with its latest status |
 * | POST | /api/enrol-codes | a new piano ("New piano") and its code, 15 minutes |
 * | GET | /api/pianos/:id | the piano (D1), its room's live status, the audit tail |
 * | PATCH | /api/pianos/:id `{name}` | renamed |
 * | POST | /api/pianos/:id/command `{name, args}` | the tablet's answer (the allow-list is in protocol.ts) |
 * | POST | /api/pianos/:id/rotate | a new secret, two-phase |
 * | POST | /api/pianos/:id/revoke | no secret works any more; the tablet is sent away |
 * | DELETE | /api/pianos/:id | forgotten |
 * | GET | /api/audit?piano= | the audit log's last 50 (one piano's, or all) |
 */
export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch {
      return error(500, 'server', 'Something went wrong.');
    }
  },
} satisfies ExportedHandler<ConsoleEnv>;

/** The page's policy: this origin only, no inline anything, no frames. */
export const CONSOLE_CSP = "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

export async function handle(request: Request, env: ConsoleEnv, deps: AccessDeps = {}): Promise<Response> {
  const access = await requireAccess(request, env, deps);
  if (!access.ok) return error(401, 'access', access.message);
  const url = new URL(request.url);
  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) return api(request, env, url, access.email, deps);
  if (request.method !== 'GET' && request.method !== 'HEAD') return error(405, 'method', 'Not allowed here.', { Allow: 'GET, HEAD' });
  const asset = await env.ASSETS.fetch(request);
  const response = new Response(asset.body, asset);
  withSecurity(response.headers, CONSOLE_CSP);
  if (!response.headers.has('Cache-Control')) response.headers.set('Cache-Control', 'no-cache');
  return response;
}

const PIANO_PATH = /^\/api\/pianos\/([a-z2-7]{12})(?:\/(command|rotate|revoke))?$/;

async function api(request: Request, env: ConsoleEnv, url: URL, actor: string, deps: AccessDeps): Promise<Response> {
  if (request.headers.get('x-steven-piano') !== '1') return error(403, 'header', "This request didn't come from the console.");
  const origin = request.headers.get('origin');
  if (origin !== null && origin !== url.origin) return error(403, 'origin', 'This request came from another site.');
  const now = (deps.now ?? Date.now)();
  const method = request.method;
  const path = url.pathname;

  if (path === '/api/pianos') return method === 'GET' ? listPianos(env, now) : notAllowed('GET');
  if (path === '/api/enrol-codes') return method === 'POST' ? createCode(env, now, actor) : notAllowed('POST');
  if (path === '/api/audit') return method === 'GET' ? auditLog(env, url) : notAllowed('GET');

  const match = PIANO_PATH.exec(path);
  if (!match) return error(404, 'not-found', 'Not here.');
  const pianoId = match[1]!;
  const action = match[2];
  const row = await env.DB.prepare(`SELECT ${PUBLIC_COLUMNS} FROM pianos WHERE id = ?`).bind(pianoId).first<PianoRow>();
  if (!row) return error(404, 'not-found', 'No such piano.');

  if (!action) {
    if (method === 'GET') return getPiano(env, row, now);
    if (method === 'PATCH') return rename(request, env, row, now, actor);
    if (method === 'DELETE') return viaRoom(env, pianoId, (room) => room.forget(pianoId, actor));
    return notAllowed('GET, PATCH, DELETE');
  }
  if (method !== 'POST') return notAllowed('POST');
  if (action === 'command') {
    const body = await readJson(request, 8192);
    if (!body.ok) return body.response;
    if (!isObject(body.value)) return error(400, 'body', 'The body must be {name, args}.');
    const { name, args } = body.value;
    return viaRoom(env, pianoId, (room) => room.command(pianoId, name, args ?? {}, actor));
  }
  if (action === 'rotate') return viaRoom(env, pianoId, (room) => room.rotate(pianoId, actor));
  return viaRoom(env, pianoId, (room) => room.revoke(pianoId, actor));
}

function notAllowed(allow: string): Response {
  return error(405, 'method', 'Not allowed here.', { Allow: allow });
}

function relay(env: ConsoleEnv): { url: string | null; host: string | null } {
  try {
    const url = new URL(env.RELAY_URL ?? '');
    return { url: url.origin, host: url.host };
  } catch {
    return { url: null, host: null };
  }
}

async function listPianos(env: ConsoleEnv, now: number): Promise<Response> {
  const { results } = await env.DB.prepare(`SELECT ${PUBLIC_COLUMNS} FROM pianos ORDER BY created_at ASC`).all<PianoRow>();
  return json(200, { pianos: results.map((row) => pianoView(row, now)), relay: relay(env), now });
}

/** A new piano and its one-time code; its row is named "New piano" until the tablet or the owner names it. */
async function createCode(env: ConsoleEnv, now: number, actor: string): Promise<Response> {
  const pianoId = newPianoId();
  const expiresAt = now + CODE_LIFE_MS;
  for (let attempt = 0; attempt < 3; attempt++) {
    const code = newEnrolCode();
    try {
      await env.DB.batch([
        env.DB.prepare('INSERT INTO pianos (id, name, created_at) VALUES (?, ?, ?)').bind(pianoId, DEFAULT_NAME, now),
        env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(code, pianoId, now, expiresAt),
        auditStatement(env.DB, { at: now, actor, pianoId, action: 'enrol-code', detail: { expiresAt } }),
      ]);
      return json(201, { pianoId, code, expiresAt, relay: relay(env), now });
    } catch {
      // A code already taken (one in 2^40): another.
    }
  }
  return error(500, 'server', 'No code could be made. Try again.');
}

async function getPiano(env: ConsoleEnv, row: PianoRow, now: number): Promise<Response> {
  let live = null;
  try {
    live = await env.ROOMS.getByName(row.id).status(row.id);
  } catch {
    live = null;
  }
  const { results } = await env.DB.prepare('SELECT id, at, actor, piano_id, action, detail FROM audit_log WHERE piano_id = ? ORDER BY at DESC, id DESC LIMIT 20')
    .bind(row.id)
    .all<AuditRow>();
  return json(200, { piano: pianoView(row, now), live, audit: results.map(auditView), relay: relay(env), now });
}

async function rename(request: Request, env: ConsoleEnv, row: PianoRow, now: number, actor: string): Promise<Response> {
  const body = await readJson(request, 4096);
  if (!body.ok) return body.response;
  if (!isObject(body.value) || Object.keys(body.value).some((k) => k !== 'name')) return error(400, 'body', 'The body must be {name}.');
  const raw = body.value.name;
  const name = typeof raw === 'string' ? raw.trim() : '';
  if (name.length === 0 || name.length > 60 || hasControl(name)) return error(400, 'field', 'A name is 1 to 60 characters.');
  await env.DB.batch([
    env.DB.prepare('UPDATE pianos SET name = ? WHERE id = ?').bind(name, row.id),
    auditStatement(env.DB, { at: now, actor, pianoId: row.id, action: 'rename', detail: { from: row.name, to: name } }),
  ]);
  return json(200, { piano: pianoView({ ...row, name }, now) });
}

async function auditLog(env: ConsoleEnv, url: URL): Promise<Response> {
  const piano = url.searchParams.get('piano');
  if (piano !== null && !PIANO_ID.test(piano)) return error(400, 'field', 'piano must be a piano id.');
  const statement = piano
    ? env.DB.prepare('SELECT id, at, actor, piano_id, action, detail FROM audit_log WHERE piano_id = ? ORDER BY at DESC, id DESC LIMIT 50').bind(piano)
    : env.DB.prepare('SELECT id, at, actor, piano_id, action, detail FROM audit_log ORDER BY at DESC, id DESC LIMIT 50');
  const { results } = await statement.all<AuditRow>();
  return json(200, { entries: results.map(auditView) });
}

type Room = ReturnType<ConsoleEnv['ROOMS']['getByName']>;

/** A call on the piano's room, in the relay; 502 when the relay doesn't answer. */
async function viaRoom(env: ConsoleEnv, pianoId: string, call: (room: Room) => Promise<unknown>): Promise<Response> {
  let result: unknown;
  try {
    result = await call(env.ROOMS.getByName(pianoId));
  } catch {
    return error(502, 'relay', "The relay didn't answer. Is it deployed?");
  }
  return json(200, result);
}
