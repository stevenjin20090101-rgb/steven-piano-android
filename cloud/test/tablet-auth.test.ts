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
import { sha256Hex } from '../src/shared/hash';
import { newPianoId, newSecret } from '../src/shared/ids';
import { FakeTablet, RELAY, newAddress, pianoRow, seedPiano, tabletRequest } from './helpers';

describe("a tablet's connection", () => {
  it('is refused 401 with a wrong secret, an unknown piano, or no bearer at all', async () => {
    const { pianoId } = await seedPiano();
    for (const [id, secret] of [[pianoId, newSecret()], [newPianoId(), newSecret()]] as const) {
      const response = await tabletRequest(id, secret);
      expect(response.status).toBe(401);
      expect(response.webSocket).toBeNull();
      expect(await response.json()).toMatchObject({ error: 'auth' });
    }
    for (const authorization of ['', 'Bearer x', `Bearer ${pianoId}`, `Bearer ${pianoId}.short`, `Basic ${pianoId}.${newSecret()}`, `Bearer ${pianoId.toUpperCase()}.${newSecret()}`]) {
      const response = await SELF.fetch(`${RELAY}/tablet`, {
        headers: { Upgrade: 'websocket', Authorization: authorization, 'Sec-WebSocket-Protocol': 'steven-piano-relay-1', 'CF-Connecting-IP': newAddress() },
      });
      expect(response.status, authorization).toBe(401);
    }
  });

  it("leaves the connected tablet alone when another comes with a wrong secret (audit delta 3)", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    for (const wrong of [newSecret(), secret.slice(0, 42) + (secret.endsWith('A') ? 'B' : 'A')]) {
      const impostor = await tabletRequest(pianoId, wrong);
      expect(impostor.status).toBe(401);
      expect(impostor.webSocket).toBeNull();
    }
    await new Promise((r) => setTimeout(r, 100));
    expect(tablet.closedWith).toBeNull();
    expect((await env.ROOMS.getByName(pianoId).status(pianoId)).online).toBe(true);
    const replaced = await env.DB.prepare("SELECT COUNT(*) AS n FROM audit_log WHERE piano_id = ? AND action = 'replaced'").bind(pianoId).first<{ n: number }>();
    expect(replaced!.n).toBe(0);
    tablet.close();
  });

  it('must speak the protocol, over a WebSocket', async () => {
    const { pianoId, secret } = await seedPiano();
    expect((await tabletRequest(pianoId, secret, { protocol: null })).status).toBe(400);
    expect((await tabletRequest(pianoId, secret, { protocol: 'steven-piano-relay-2' })).status).toBe(400);
    const plain = await SELF.fetch(`${RELAY}/tablet`, { headers: { Authorization: `Bearer ${pianoId}.${secret}`, 'CF-Connecting-IP': newAddress() } });
    expect(plain.status).toBe(426);
  });

  it("is refused once the piano's secret is gone (revoked)", async () => {
    const { pianoId, secret } = await seedPiano();
    await env.DB.prepare('UPDATE pianos SET secret_hash = NULL, revoked_at = ? WHERE id = ?').bind(Date.now(), pianoId).run();
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
  });

  it('accepts the pending secret during a rotation (which completes it), and the old one until then', async () => {
    const { pianoId, secret } = await seedPiano();
    const next = newSecret();
    const nextHash = await sha256Hex(next);
    await env.DB.prepare('UPDATE pianos SET pending_secret_hash = ?, pending_until = ? WHERE id = ?').bind(nextHash, Date.now() + 10 * 60 * 1000, pianoId).run();

    const old = await FakeTablet.connect(pianoId, secret);
    await old.next('hello');
    expect((await pianoRow(pianoId))!.pending_secret_hash).toBe(nextHash);

    const renewed = await FakeTablet.connect(pianoId, next);
    await renewed.next('hello');
    expect(await old.closed()).toMatchObject({ code: 4409 });
    const row = await pianoRow(pianoId);
    expect(row).toMatchObject({ secret_hash: nextHash, pending_secret_hash: null, pending_until: null });
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
    renewed.close();
  });

  it('refuses a pending secret whose ten minutes are over', async () => {
    const { pianoId } = await seedPiano();
    const next = newSecret();
    await env.DB.prepare('UPDATE pianos SET pending_secret_hash = ?, pending_until = ? WHERE id = ?').bind(await sha256Hex(next), Date.now() - 1, pianoId).run();
    expect((await tabletRequest(pianoId, next)).status).toBe(401);
  });

  it('replaces an older connection of the same piano (4409), and notes it', async () => {
    const { pianoId, secret } = await seedPiano();
    const first = await FakeTablet.connect(pianoId, secret);
    await first.next('hello');
    const second = await FakeTablet.connect(pianoId, secret);
    await second.next('hello');
    expect(await first.closed()).toEqual({ code: 4409, reason: 'Replaced by a newer connection.' });
    const audit = await env.DB.prepare("SELECT action FROM audit_log WHERE piano_id = ? AND action = 'replaced'").bind(pianoId).first();
    expect(audit).not.toBeNull();
    // Still online through the second.
    expect((await pianoRow(pianoId))!.online).toBe(1);
    second.close();
  });

  it('marks the piano online while connected, and offline when it goes', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    expect((await pianoRow(pianoId))!.online).toBe(1);
    tablet.close();
    await tablet.closed();
    const deadline = Date.now() + 3000;
    while ((await pianoRow(pianoId))!.online !== 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 20));
    expect((await pianoRow(pianoId))!.online).toBe(0);
  });

  it('answers a text "ping" with "pong"', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.ws.send('ping');
    await tablet.waitFor(() => (tablet.raw.includes('pong') ? true : undefined), 'pong');
    tablet.close();
  });
});
