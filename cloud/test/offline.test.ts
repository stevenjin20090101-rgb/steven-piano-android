/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { OFFLINE_PAGE } from '../src/relay/offline';
import { FakeBrowser, FakeTablet, newAddress, panel, seedPiano } from './helpers';

async function sha256Base64(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)));
  return btoa(String.fromCharCode(...digest));
}

describe('a piano whose tablet is not connected', () => {
  it('answers the API 503 in JSON', async () => {
    const { pianoId } = await seedPiano();
    for (const [path, method] of [['/api/state', 'GET'], ['/api/transport', 'POST'], ['/api', 'GET']] as const) {
      const response = await panel(pianoId, path, method === 'POST' ? { method, headers: { 'Content-Type': 'application/json' }, body: '{}' } : { method });
      expect(response.status, path).toBe(503);
      expect(response.headers.get('Content-Type')).toBe('application/json; charset=utf-8');
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(response.headers.get('Retry-After')).toBe('30');
      expect(response.headers.get('Strict-Transport-Security')).toBe('max-age=31536000');
      expect(await response.json()).toEqual({ error: 'offline', message: 'The piano is offline.' });
    }
  });

  it('answers a page with offline.html (503), its one style allowed by hash', async () => {
    const { pianoId } = await seedPiano();
    for (const path of ['/', '/app.js', '/request']) {
      const response = await panel(pianoId, path);
      expect(response.status, path).toBe(503);
      expect(response.headers.get('Content-Type')).toBe('text/html; charset=utf-8');
      const html = await response.text();
      expect(html).toBe(OFFLINE_PAGE);
      expect(html).toContain('The piano is offline');
      expect(html).not.toContain('<script');
      const style = /<style>([\s\S]*?)<\/style>/.exec(html)![1]!;
      const csp = response.headers.get('Content-Security-Policy')!;
      expect(csp).toContain(`style-src 'sha256-${await sha256Base64(style)}'`);
      expect(csp).toContain("default-src 'none'");
      expect(response.headers.get('X-Frame-Options')).toBe('DENY');
    }
    const head = await panel(pianoId, '/', { method: 'HEAD' });
    expect(head.status).toBe(503);
    expect(await head.text()).toBe('');
  });

  it("refuses a browser's socket (503)", async () => {
    const { pianoId } = await seedPiano();
    const refused = await FakeBrowser.open(pianoId);
    expect(refused).toBeInstanceOf(Response);
    expect((refused as Response).status).toBe(503);
    expect(await (refused as Response).json()).toMatchObject({ error: 'offline' });
  });

  it('answers 502 to a request in flight when the tablet goes', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const pending = panel(pianoId, '/api/library');
    await tablet.next('req');
    tablet.close(1001, 'Going away');
    const response = await pending;
    expect(response.status).toBe(502);
    expect(await response.json()).toEqual({ error: 'offline', message: 'The piano went offline.' });
    // And afterwards, offline.
    expect((await panel(pianoId, '/api/state')).status).toBe(503);
  });

  it('knows no other path: 404 outside /p/<id>/, /tablet and /api/enrol', async () => {
    const { pianoId } = await seedPiano();
    for (const path of ['/', '/p/', `/p/${pianoId.toUpperCase()}/`, '/p/short/', '/api/state', '/tablet/x']) {
      const response = await SELF.fetch(`https://relay.test${path}`, { headers: { 'CF-Connecting-IP': newAddress() } });
      expect(response.status, path).toBe(404);
      expect([...response.headers.keys()].some((k) => k.startsWith('access-control-'))).toBe(false);
    }
  });
});
