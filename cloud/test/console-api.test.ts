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
import { owner } from './console-helpers';
import { FakeTablet, RELAY, newAddress, pianoRow, status } from './helpers';

describe("the console's API", () => {
  it('makes a piano and its code, which a tablet then enrols with; lists, names and shows it', async () => {
    const { call } = await owner();
    const made = await call('/api/enrol-codes', { method: 'POST' });
    expect(made.status).toBe(201);
    const { pianoId, code, expiresAt, relay, now } = (await made.json()) as Record<string, any>;
    expect(code).toMatch(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$/);
    expect(expiresAt - now).toBe(15 * 60 * 1000);
    expect(relay).toEqual({ url: 'https://relay.test', host: 'relay.test' });
    expect(await pianoRow(pianoId)).toMatchObject({ name: 'New piano', secret_hash: null, enrolled_at: null });
    const noted = await env.DB.prepare("SELECT actor, detail FROM audit_log WHERE piano_id = ? AND action = 'enrol-code'").bind(pianoId).first<{ actor: string; detail: string }>();
    expect(noted!.actor).toBe('steven@example.com');
    expect(noted!.detail).not.toContain(code);

    // The tablet enrols at the relay with that code.
    const body = JSON.stringify({ code, name: 'Music room', model: 'SM-X200' });
    const enrolled = await SELF.fetch(`${RELAY}/api/enrol`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': String(body.length), 'CF-Connecting-IP': newAddress() },
      body,
    });
    const { secret } = (await enrolled.json()) as { secret: string };
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.send(status('Clair de lune', Date.now(), { channels: [{ key: 'debussy', name: 'Debussy' }, { key: 'chopin', name: 'Chopin' }] }));
    const deadline = Date.now() + 5000;
    while (!((await pianoRow(pianoId))!.status_json ?? '').includes('Clair de lune') && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));

    const list = (await (await call('/api/pianos')).json()) as { pianos: Array<Record<string, any>> };
    const listed = list.pianos.find((p) => p.id === pianoId)!;
    expect(listed).toMatchObject({ name: 'Music room', online: true, appVersion: '1.10', appCode: 18, firmware: '2.0.0', guests: true, approveFirst: false, status: { player: { title: 'Clair de lune', composer: 'Claude Debussy' } } });
    expect(listed.enrolledAt).toEqual(expect.any(Number));

    const renamed = await call(`/api/pianos/${pianoId}`, { method: 'PATCH', body: { name: '  Hall piano ' } });
    expect(renamed.status).toBe(200);
    expect(((await renamed.json()) as any).piano.name).toBe('Hall piano');
    for (const name of ['', ' ', 'x'.repeat(61), 'a\u0007b', 42]) {
      expect((await call(`/api/pianos/${pianoId}`, { method: 'PATCH', body: { name } })).status, String(name)).toBe(400);
    }
    expect((await call(`/api/pianos/${pianoId}`, { method: 'PATCH', body: { name: 'x', secret_hash: null } })).status).toBe(400);

    const shown = (await (await call(`/api/pianos/${pianoId}`)).json()) as Record<string, any>;
    expect(shown.piano).toMatchObject({ id: pianoId, name: 'Hall piano' });
    expect(shown.live).toMatchObject({ online: true, browsers: 0, status: { channels: [{ key: 'debussy', name: 'Debussy' }, { key: 'chopin', name: 'Chopin' }] } });
    expect(shown.audit.map((a: any) => a.action)).toEqual(['rename', 'enrol', 'enrol-code']);
    expect(shown.audit[0]).toMatchObject({ actor: 'steven@example.com', detail: { from: 'Music room', to: 'Hall piano' } });

    // No secret's hash ever leaves: not in the list, not in the piano.
    const row = await pianoRow(pianoId);
    for (const text of [JSON.stringify(list), JSON.stringify(shown)]) {
      expect(text).not.toContain(row!.secret_hash);
      expect(text).not.toContain('secret_hash');
    }

    const audit = (await (await call(`/api/audit?piano=${pianoId}`)).json()) as { entries: Array<Record<string, any>> };
    expect(audit.entries.map((a) => a.action)).toEqual(['rename', 'enrol', 'enrol-code']);
    expect((await call('/api/audit?piano=nope')).status).toBe(400);
    expect((await call('/api/audit')).status).toBe(200);
    tablet.close();
  });

  it('answers 404 for a piano it does not know, and 405 for a method a route does not take', async () => {
    const { call } = await owner();
    expect((await call('/api/pianos/aaaaaaaaaaaa')).status).toBe(404);
    expect((await call('/api/pianos/aaaaaaaaaaaa/rotate', { method: 'POST' })).status).toBe(404);
    expect((await call('/api/nothing')).status).toBe(404);
    expect((await call('/api/pianos', { method: 'POST' })).status).toBe(405);
    expect((await call('/api/enrol-codes')).status).toBe(405);
    const made = (await (await call('/api/enrol-codes', { method: 'POST' })).json()) as { pianoId: string };
    expect((await call(`/api/pianos/${made.pianoId}/rotate`)).status).toBe(405);
    expect((await call(`/api/pianos/${made.pianoId}`, { method: 'PUT' })).status).toBe(405);
    expect((await call('/', { method: 'POST', header: false })).status).toBe(405);
  });
});
