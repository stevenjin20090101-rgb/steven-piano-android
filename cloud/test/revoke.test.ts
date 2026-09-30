/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { owner } from './console-helpers';
import { FakeBrowser, FakeTablet, pianoRow, seedPiano, tabletRequest } from './helpers';

describe('revoking and forgetting', () => {
  it('revoke: the tablet is sent away with 4401, its browsers closed, and it cannot come back', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const browser = await FakeBrowser.open(pianoId);
    if (browser instanceof Response) throw new Error('refused');
    tablet.send({ t: 'ws.accept', id: (await tablet.next('ws.open')).id });

    const { call } = await owner();
    const response = await call(`/api/pianos/${pianoId}/revoke`, { method: 'POST' });
    expect(await response.json()).toMatchObject({ ok: true });
    expect(await tablet.closed()).toEqual({ code: 4401, reason: 'Revoked from the console.' });
    expect(await browser.closed()).toMatchObject({ code: 1008 });

    const row = await pianoRow(pianoId);
    expect(row).toMatchObject({ secret_hash: null, pending_secret_hash: null, online: 0 });
    expect(row!.revoked_at).toEqual(expect.any(Number));
    const refused = await tabletRequest(pianoId, secret);
    expect(refused.status).toBe(401);
    const audit = await env.DB.prepare("SELECT actor FROM audit_log WHERE piano_id = ? AND action = 'revoke'").bind(pianoId).first();
    expect(audit).toEqual({ actor: 'steven@example.com' });
  });

  it('forget: the tablet is sent away with 4403, and the piano, its codes and its room are gone', async () => {
    const { pianoId, secret } = await seedPiano();
    await env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind('TEST-CODE', pianoId, Date.now(), Date.now() + 60_000).run();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.send({ t: 'status', app: { version: '1.10', code: 18 }, at: Date.now() });

    const { call } = await owner();
    const response = await call(`/api/pianos/${pianoId}`, { method: 'DELETE' });
    expect(await response.json()).toEqual({ ok: true, message: 'Forgotten.' });
    expect(await tablet.closed()).toEqual({ code: 4403, reason: 'Removed from the console.' });
    expect(await pianoRow(pianoId)).toBeNull();
    expect(await env.DB.prepare('SELECT code FROM enrol_codes WHERE piano_id = ?').bind(pianoId).first()).toBeNull();
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
    expect((await call(`/api/pianos/${pianoId}`)).status).toBe(404);
    const live = await env.ROOMS.getByName(pianoId).status(pianoId);
    expect(live).toMatchObject({ online: false, status: null });
  });
});
