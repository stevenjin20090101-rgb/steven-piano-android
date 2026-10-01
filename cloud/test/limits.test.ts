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
import { newPianoId } from '../src/shared/ids';
import { FakeTablet, RELAY, newAddress, panel, seedPiano } from './helpers';

// Audit delta 3: the relay's brakes per client address (wrangler.relay.jsonc › ratelimits), each shown
// to hold: 120 tablet connections a minute, 120 panel requests a minute, 10 PIN tries a minute (enrolment's
// five are in enrol.test.ts). Each 429 comes before anything reaches a room or the tablet.
describe("the relay's limits per address", () => {
  it('takes 120 tablet connections a minute from an address, then 429', async () => {
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

  it('takes 10 PIN tries a minute from an address, then 429, and none of the refused reaches the tablet', async () => {
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
