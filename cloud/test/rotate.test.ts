/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { runInDurableObject } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { sha256Hex } from '../src/shared/hash';
import { owner } from './console-helpers';
import { FakeTablet, pianoRow, room, seedPiano, tabletRequest } from './helpers';
import { env } from 'cloudflare:workers';

describe('rotating a secret', () => {
  it('is two-phase: the new secret is pending until the tablet confirms, then the only one', async () => {
    const { pianoId, secret } = await seedPiano();
    const oldHash = await sha256Hex(secret);
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const { call } = await owner();

    const pending = call(`/api/pianos/${pianoId}/rotate`, { method: 'POST' });
    const message = await tablet.next('secret');
    expect(message).toEqual({ t: 'secret', secret: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/) });
    const next = message.secret as string;

    // Phase one: both hashes are accepted; the old one is still the piano's.
    const during = await pianoRow(pianoId);
    expect(during).toMatchObject({ secret_hash: oldHash, pending_secret_hash: await sha256Hex(next) });
    expect(during!.pending_until).toBeGreaterThan(Date.now() + 9 * 60 * 1000);

    // Phase two: the tablet has kept it.
    tablet.send({ t: 'secret.ack' });
    const response = await pending;
    expect(await response.json()).toEqual({ ok: true, committed: true, message: 'The tablet has its new secret; the old one no longer works.' });
    expect(await pianoRow(pianoId)).toMatchObject({ secret_hash: await sha256Hex(next), pending_secret_hash: null, pending_until: null });
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
    const renewed = await FakeTablet.connect(pianoId, next);
    await renewed.next('hello');
    const actions = await env.DB.prepare("SELECT action, actor FROM audit_log WHERE piano_id = ? AND action LIKE 'rotate%' ORDER BY id").bind(pianoId).all();
    expect(actions.results).toEqual([
      { action: 'rotate', actor: 'steven@example.com' },
      { action: 'rotate-commit', actor: 'tablet' },
    ]);
    renewed.close();
  });

  it('keeps the old secret when the tablet never confirms, and lets the pending one lapse', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    await runInDurableObject(room(pianoId), (instance) => {
      instance.timing.answer = 300;
    });
    const { call } = await owner();
    const response = await call(`/api/pianos/${pianoId}/rotate`, { method: 'POST' });
    const next = (await tablet.next('secret')).secret as string;
    expect(await response.json()).toMatchObject({ ok: true, committed: false });

    // A second rotation waits for the first.
    const again = await call(`/api/pianos/${pianoId}/rotate`, { method: 'POST' });
    expect(await again.json()).toMatchObject({ ok: false, committed: false });

    // The ten minutes pass: a late confirmation changes nothing, the old secret goes on working.
    await env.DB.prepare('UPDATE pianos SET pending_until = ? WHERE id = ?').bind(Date.now() - 1, pianoId).run();
    tablet.send({ t: 'secret.ack' });
    await new Promise((r) => setTimeout(r, 200));
    expect(await pianoRow(pianoId)).toMatchObject({ secret_hash: await sha256Hex(secret) });
    expect((await tabletRequest(pianoId, next)).status).toBe(401);
    tablet.close();
    const back = await FakeTablet.connect(pianoId, secret);
    await back.next('hello');
    back.close();
  });

  it('needs the tablet online', async () => {
    const { pianoId } = await seedPiano();
    const { call } = await owner();
    const response = await call(`/api/pianos/${pianoId}/rotate`, { method: 'POST' });
    expect(await response.json()).toMatchObject({ ok: false, committed: false });
    expect((await pianoRow(pianoId))!.pending_secret_hash).toBeNull();
  });
});
