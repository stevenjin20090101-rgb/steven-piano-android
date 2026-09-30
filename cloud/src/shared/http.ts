/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

/** Answers both Workers build themselves: JSON errors in the tablet's own shape, and the security headers. */

/** On every answer: no sniffing, no framing, no referrer, this origin's resources only. Never any CORS header. */
export const SECURITY_HEADERS: ReadonlyArray<readonly [string, string]> = [
  ['X-Content-Type-Options', 'nosniff'],
  ['X-Frame-Options', 'DENY'],
  ['Referrer-Policy', 'no-referrer'],
  ['Cross-Origin-Resource-Policy', 'same-origin'],
];

/** HTTPS only, for a year: on every answer the relay and the console give. */
export const HSTS = ['Strict-Transport-Security', 'max-age=31536000'] as const;

/** A JSON answer's policy: nothing may load from it, nothing may frame it. */
export const API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

/** Sets each of the security headers (and HSTS) that [headers] doesn't carry already. */
export function withSecurity(headers: Headers, csp?: string): Headers {
  for (const [name, value] of SECURITY_HEADERS) if (!headers.has(name)) headers.set(name, value);
  if (!headers.has(HSTS[0])) headers.set(HSTS[0], HSTS[1]);
  if (csp && !headers.has('Content-Security-Policy')) headers.set('Content-Security-Policy', csp);
  return headers;
}

/** A JSON answer, `no-store`, with the security headers. */
export function json(status: number, body: unknown, extra?: HeadersInit): Response {
  const headers = new Headers(extra);
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: withSecurity(headers, API_CSP) });
}

/** `{"error": code, "message": text}`, as the tablet words its own. */
export function error(status: number, code: string, message: string, extra?: HeadersInit, more?: Record<string, unknown>): Response {
  return json(status, { error: code, message, ...more }, extra);
}

/** 429 with how long to wait, in the body (`retryAfter`, which the panel counts down) and in `Retry-After`. */
export function tooMany(message: string, seconds = 60): Response {
  return error(429, 'wait', message, { 'Retry-After': String(seconds) }, { retryAfter: seconds });
}

/** A request's JSON body, at most [max] bytes, declared and of type JSON; or an answer refusing it. */
export async function readJson(request: Request, max: number): Promise<{ ok: true; value: unknown } | { ok: false; response: Response }> {
  const type = (request.headers.get('content-type') ?? '').split(';')[0]!.trim().toLowerCase();
  if (type !== 'application/json') return { ok: false, response: error(415, 'type', 'The body must be JSON.') };
  const declared = request.headers.get('content-length');
  if (declared === null || !/^\d{1,15}$/.test(declared)) return { ok: false, response: error(411, 'length', 'The request must say how long it is.') };
  if (Number(declared) > max) return { ok: false, response: error(413, 'size', `The body can be ${max} bytes at most.`) };
  let text: string;
  try {
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.byteLength > max) return { ok: false, response: error(413, 'size', `The body can be ${max} bytes at most.`) };
    text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes);
  } catch {
    return { ok: false, response: error(400, 'body', 'The body is not UTF-8.') };
  }
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch {
    return { ok: false, response: error(400, 'body', 'The body is not JSON.') };
  }
}

/** The client's address as Cloudflare saw it (never a header the client chose). */
export function clientAddress(request: Request): string {
  return request.headers.get('cf-connecting-ip')?.trim() || 'unknown';
}
