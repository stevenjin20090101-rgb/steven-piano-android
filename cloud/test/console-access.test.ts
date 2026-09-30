/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { beforeEach, describe, expect, it } from 'vitest';
import { forgetKeys } from '../src/console/access';
import { AUD, CONSOLE, TEAM, certs, claims, consoleCall, consoleEnv, newSigner } from './console-helpers';

describe("the console's own Access check", () => {
  beforeEach(() => forgetKeys());

  it('lets in a token Access signed for this application', async () => {
    const signer = await newSigner();
    const { fetch } = certs(signer);
    const response = await consoleCall('/api/pianos', { token: await signer.sign(claims()), fetch });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ pianos: expect.any(Array), relay: { url: 'https://relay.test', host: 'relay.test' } });
    const page = await consoleCall('/', { token: await signer.sign(claims()), fetch, header: false });
    expect(page.status).toBe(200);
    expect(page.headers.get('Content-Security-Policy')).toContain("default-src 'self'");
    expect(page.headers.get('Strict-Transport-Security')).toBe('max-age=31536000');
    expect([...page.headers.keys()].some((k) => k.startsWith('access-control-'))).toBe(false);
  });

  it('answers 401 without a token, the page and the API alike', async () => {
    const signer = await newSigner();
    const { fetch, served } = certs(signer);
    for (const path of ['/', '/console.js', '/api/pianos']) {
      const response = await consoleCall(path, { token: null, fetch });
      expect(response.status, path).toBe(401);
      expect(await response.json()).toMatchObject({ error: 'access' });
    }
    expect(served.fetches).toBe(0);
  });

  it('answers 401 for another audience, another issuer, an old or future token, or none of the team\'s keys', async () => {
    const signer = await newSigner();
    const stranger = await newSigner(signer.kid); // the same kid, not the team's key
    const { fetch } = certs(signer);
    const now = Math.floor(Date.now() / 1000);
    const tokens: Record<string, string> = {
      'wrong aud': await signer.sign(claims({ aud: ['someone-else'] })),
      'wrong aud (string)': await signer.sign(claims({ aud: 'someone-else' })),
      'wrong issuer': await signer.sign(claims({ iss: 'https://other.cloudflareaccess.com' })),
      expired: await signer.sign(claims({ exp: now - 120 })),
      'not yet': await signer.sign(claims({ nbf: now + 600 })),
      'no exp': await signer.sign(claims({ exp: undefined })),
      'signed by a stranger': await stranger.sign(claims()),
      'unknown kid': await signer.sign(claims(), { kid: 'nobody' }),
      'alg none': (await signer.sign(claims(), { alg: 'none' })).replace(/\.[^.]+$/, '.'),
      HS256: await signer.sign(claims(), { alg: 'HS256' }),
      garbage: 'not.a.jwt',
    };
    for (const [what, token] of Object.entries(tokens)) {
      const response = await consoleCall('/api/pianos', { token, fetch });
      expect(response.status, what).toBe(401);
    }
  });

  it('answers 401 when it is not set up, whatever the token', async () => {
    const signer = await newSigner();
    const { fetch } = certs(signer);
    const token = await signer.sign(claims());
    expect((await consoleCall('/api/pianos', { token, fetch, env: consoleEnv({ ACCESS_AUD: '' }) })).status).toBe(401);
    expect((await consoleCall('/api/pianos', { token, fetch, env: consoleEnv({ ACCESS_TEAM_DOMAIN: '' }) })).status).toBe(401);
    // The team may be given as its name or its address.
    expect((await consoleCall('/api/pianos', { token, fetch, env: consoleEnv({ ACCESS_TEAM_DOMAIN: `https://${TEAM}.cloudflareaccess.com` }) })).status).toBe(200);
    expect(AUD).toBeTruthy();
  });

  it('lets DEV_BYPASS through only for localhost', async () => {
    const env = consoleEnv({ DEV_BYPASS: '1' });
    expect((await consoleCall('/api/pianos', { env })).status).toBe(401);
    const { handle } = await import('../src/console/routes');
    const local = await handle(new Request('http://localhost:8788/api/pianos', { headers: { 'X-Steven-Piano': '1' } }), env);
    expect(local.status).toBe(200);
    const noBypass = await handle(new Request('http://localhost:8788/api/pianos', { headers: { 'X-Steven-Piano': '1' } }), consoleEnv());
    expect(noBypass.status).toBe(401);
  });

  it("wants the console's header and its own origin on the API", async () => {
    const signer = await newSigner();
    const { fetch } = certs(signer);
    const token = await signer.sign(claims());
    const bare = await consoleCall('/api/pianos', { token, fetch, header: false });
    expect(bare.status).toBe(403);
    expect(await bare.json()).toMatchObject({ error: 'header' });
    expect((await consoleCall('/api/pianos', { token, fetch, origin: 'https://evil.test' })).status).toBe(403);
    expect((await consoleCall('/api/pianos', { token, fetch, origin: CONSOLE })).status).toBe(200);
  });
});
