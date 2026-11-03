/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { ShortEnv } from '../env';
import { API_CSP, withSecurity } from '../shared/http';
import { PIANO_ID } from '../shared/ids';

/**
 * M79: a school's short address (wrangler.short.jsonc, `playerpiano-stevenjin`). Every request is sent on (302) to its
 * piano's panel on the relay, the path and the query kept: `/` opens the panel, `/request` the guest page. No bindings,
 * nothing stored; `no-store`, HSTS and the relay's security headers on every answer. Until `PIANO_ID` names a piano (and
 * `RELAY_URL` is an address), a plain 503.
 */
export default {
  async fetch(request, env): Promise<Response> {
    const relay = relayOrigin(env.RELAY_URL);
    const pianoId = (env.PIANO_ID ?? '').trim();
    if (relay === null || !PIANO_ID.test(pianoId)) {
      const headers = withSecurity(new Headers({ 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' }), API_CSP);
      return new Response(request.method === 'HEAD' ? null : "This piano isn't set up yet.\n", { status: 503, headers });
    }
    const url = new URL(request.url);
    const headers = withSecurity(new Headers({ Location: `${relay}/p/${pianoId}${url.pathname}${url.search}`, 'Cache-Control': 'no-store' }));
    return new Response(null, { status: 302, headers });
  },
} satisfies ExportedHandler<ShortEnv>;

/** The relay's origin from its https address (http only for localhost, in development); null when it is none. */
function relayOrigin(raw: string | undefined): string | null {
  try {
    const url = new URL((raw ?? '').trim());
    const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
    if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) return null;
    return url.origin;
  } catch {
    return null;
  }
}
