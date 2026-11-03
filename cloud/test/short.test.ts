/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { describe, expect, it } from 'vitest';
import shortConfig from '../wrangler.short.jsonc?raw';
import short from '../src/short/index';
import type { ShortEnv } from '../src/env';

const RELAY_URL = 'https://steven-piano-relay.sjengineering.workers.dev';
const PIANO = 'abcdefgh2345';

function ask(path: string, env: ShortEnv, method = 'GET'): Promise<Response> {
  return short.fetch(new Request(`https://playerpiano-stevenjin.sjengineering.workers.dev${path}`, { method }), env);
}

// M79: a school's short address sends every request on to its piano's panel on the relay.
describe('the short address', () => {
  it('sends / to the panel and /request?x=1 to the guest page, never cached, with the security headers', async () => {
    for (const [path, to] of [
      ['/', `${RELAY_URL}/p/${PIANO}/`],
      ['/request?x=1', `${RELAY_URL}/p/${PIANO}/request?x=1`],
    ] as const) {
      const response = await ask(path, { RELAY_URL, PIANO_ID: PIANO });
      expect(response.status, path).toBe(302);
      expect(response.headers.get('Location'), path).toBe(to);
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(response.headers.get('Strict-Transport-Security')).toBe('max-age=31536000');
      expect(response.headers.get('X-Frame-Options')).toBe('DENY');
      expect(response.headers.get('Referrer-Policy')).toBe('no-referrer');
    }
  });

  it("answers 503 until it names a piano (or a relay)", async () => {
    for (const env of [{ RELAY_URL, PIANO_ID: '' }, { RELAY_URL }, { RELAY_URL, PIANO_ID: 'ABC/../x' }, { RELAY_URL: 'not a url', PIANO_ID: PIANO }]) {
      const response = await ask('/', env);
      expect(response.status, JSON.stringify(env)).toBe(503);
      expect(await response.text()).toBe("This piano isn't set up yet.\n");
      expect(response.headers.get('Content-Type')).toBe('text/plain; charset=utf-8');
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(response.headers.get('Location')).toBeNull();
    }
  });

  it('is configured as playerpiano-stevenjin, on workers.dev, with no bindings and the two vars', () => {
    const config = JSON.parse(shortConfig.replace(/(^|[^:"])\/\/.*$/gm, '$1').replace(/,(\s*[}\]])/g, '$1')) as Record<string, any>;
    expect(config.name).toBe('playerpiano-stevenjin');
    expect(config.main).toBe('src/short/index.ts');
    expect(config.workers_dev).toBe(true);
    expect(config.vars).toEqual({ RELAY_URL, PIANO_ID: '' });
    for (const binding of ['durable_objects', 'd1_databases', 'kv_namespaces', 'r2_buckets', 'services', 'ratelimits', 'assets']) expect(config[binding], binding).toBeUndefined();
  });
});
