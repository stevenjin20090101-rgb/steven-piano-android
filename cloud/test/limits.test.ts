/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { SELF } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { newPianoId } from '../src/shared/ids';
import { ART_LIMIT_PER_MINUTE } from '../src/shared/protocol';
import { FakeTablet, RELAY, answerJson, newAddress, panel, seedPiano, sleep } from './helpers';

/**
 * The local limiter counts in windows aligned to the clock's minutes (miniflare's RateLimiterObject): a test that needs
 * [ms] of one window waits for the next when fewer are left, so its count never straddles two.
 */
async function freshWindow(ms: number): Promise<void> {
  const left = 60_000 - (Date.now() % 60_000);
  if (left < ms) await sleep(left + 100);
}

// Audit delta 3: the relay's brakes per client address (wrangler.relay.jsonc › ratelimits), each shown
// to hold: 120 tablet connections a minute, 120 panel requests a minute, 10 PIN tries a minute (enrolment's
// five are in enrol.test.ts). Each 429 comes before anything reaches a room or the tablet.
describe("the relay's limits per address", () => {
  it('takes 120 tablet connections a minute from an address, then 429', async () => {
    await freshWindow(10_000);
    const address = newAddress();
    const bare = (from: string) => SELF.fetch(`${RELAY}/tablet`, { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': from } });
    for (let i = 0; i < 120; i++) expect((await bare(address)).status).toBe(400);
    const refused = await bare(address);
    expect(refused.status).toBe(429);
    expect(refused.headers.get('Retry-After')).toBe('60');
    expect(await refused.json()).toMatchObject({ error: 'wait', retryAfter: 60 });
    // A real tablet from that address waits too; another address is not held up.
    const { pianoId, secret } = await seedPiano();
    const tablet = await SELF.fetch(`${RELAY}/tablet`, {
      headers: { Upgrade: 'websocket', Authorization: `Bearer ${pianoId}.${secret}`, 'Sec-WebSocket-Protocol': 'steven-piano-relay-1', 'CF-Connecting-IP': address },
    });
    expect(tablet.status).toBe(429);
    expect((await bare(newAddress())).status).toBe(400);
  });

  it("takes 120 panel requests a minute from an address, then 429, before the piano's room", async () => {
    await freshWindow(10_000);
    const pianoId = newPianoId();
    const address = newAddress();
    for (let i = 0; i < 120; i++) {
      const response = await panel(pianoId, '/api/state', { address });
      expect(response.status).toBe(503);
      await response.arrayBuffer();
    }
    const refused = await panel(pianoId, '/api/state', { address });
    expect(refused.status).toBe(429);
    expect(await refused.json()).toMatchObject({ error: 'wait', message: 'Too many requests. Try again in a minute.' });
    expect((await panel(pianoId, '/api/state', { address: newAddress() })).status).toBe(503);
  });

  // v1.18 — M47b: a panel's pictures on a limit of their own, so a page of covers never spends the panel's 120. Most of
  // each minute's allowance is spent at the limiter itself, on the keys the relay uses; the requests that matter are real.
  it("takes 600 pictures a minute from an address on their own limit, then 429, and leaves the panel's 120 alone", async () => {
    await freshWindow(15_000);
    const pianoId = newPianoId();
    const address = newAddress();
    const status = async (path: string, init: RequestInit = {}) => {
      const answer = await panel(pianoId, path, { address, ...init });
      await answer.arrayBuffer();
      return answer.status;
    };
    const picture = (n: number) => status(`/api/art/piece/${n}?kind=cover&size=row&v=1`);
    // The panel's 120 all but spent: pictures still pass, never counted against it; then its last, then 429.
    for (let i = 0; i < 119; i++) expect((await env.PANEL_LIMIT.limit({ key: `panel:${address}` })).success).toBe(true);
    for (let n = 1; n <= 10; n++) expect(await picture(n)).toBe(503);
    expect(await status('/api/state')).toBe(503);
    expect(await status('/api/state')).toBe(429);
    // The pictures' 600: 10 above, the rest spent here; the next is refused, the minute's wait said.
    for (let i = 10; i < ART_LIMIT_PER_MINUTE; i++) expect((await env.ART_LIMIT.limit({ key: `art:${address}` })).success).toBe(true);
    const refused = await panel(pianoId, '/api/art/piece/11?kind=cover&size=row&v=1', { address });
    expect(refused.status).toBe(429);
    expect(refused.headers.get('Retry-After')).toBe('60');
    await refused.arrayBuffer();
    // Only a GET is a picture: anything else under /api/art/ is the panel's, spent here.
    expect(await status('/api/art/piece/1', { method: 'POST' })).toBe(429);
    // Another address has its own 600.
    const other = await panel(pianoId, '/api/art/piece/1', { address: newAddress() });
    expect(other.status).toBe(503);
    await other.arrayBuffer();
  });

  it("tells the page the pictures' limit on every answer the tablet gives through it", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const pending = panel(pianoId, '/api/state');
    const req = await tablet.next('req');
    answerJson(tablet, req.id, 200, { ok: true });
    const response = await pending;
    expect(response.headers.get('X-Relay-Art-Limit')).toBe(String(ART_LIMIT_PER_MINUTE));
    await response.arrayBuffer();
    tablet.close();
  });

  it('takes 10 PIN tries a minute from an address, then 429, and none of the refused reaches the tablet', async () => {
    await freshWindow(15_000);
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const address = newAddress();
    const login = () =>
      panel(pianoId, '/api/login', {
        address,
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Steven-Piano': '1', Origin: RELAY },
        body: '{"pin":"000000"}',
      });
    for (let i = 0; i < 10; i++) {
      const pending = login();
      const req = await tablet.next('req');
      expect(req.path).toBe('/api/login');
      tablet.send({ t: 'res', id: req.id, status: 401, headers: { 'Content-Type': 'application/json' }, length: 2 });
      tablet.sendFrame(req.id, 3, new TextEncoder().encode('{}'));
      tablet.sendFrame(req.id, 4);
      expect((await pending).status).toBe(401);
    }
    const refused = await login();
    expect(refused.status).toBe(429);
    expect(await refused.json()).toMatchObject({ error: 'wait', message: 'Too many tries. Try again in a minute.' });
    await tablet.nothing('req');
    // The rest of the panel still answers that address (its own 120 a minute).
    const state = panel(pianoId, '/api/state', { address });
    const req = await tablet.next('req');
    expect(req.path).toBe('/api/state');
    tablet.send({ t: 'res', id: req.id, status: 401, headers: { 'Content-Type': 'application/json' }, length: 2 });
    tablet.sendFrame(req.id, 3, new TextEncoder().encode('{}'));
    tablet.sendFrame(req.id, 4);
    expect((await state).status).toBe(401);
    tablet.close();
  });
});
