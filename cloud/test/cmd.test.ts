/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { runInDurableObject } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { checkCommand } from '../src/shared/protocol';
import { owner } from './console-helpers';
import { FakeTablet, room, seedPiano } from './helpers';

describe("the console's commands", () => {
  it('reach the tablet only from the allow-list, with exactly their arguments', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const { call } = await owner();
    const refused: Array<[string, unknown]> = [
      ['reboot', {}],
      ['transport', { action: 'explode' }],
      ['transport', { action: 'next', extra: 1 }],
      ['play', { pieceId: '12' }],
      ['play', { pieceId: 0 }],
      ['playChannel', { key: '' }],
      ['guests', {}],
      ['guests', { open: 'yes' }],
      ['status', { verbose: true }],
      ['__proto__', {}],
    ];
    for (const [name, args] of refused) {
      const response = await call(`/api/pianos/${pianoId}/command`, { method: 'POST', body: { name, args } });
      expect(response.status).toBe(200);
      expect(await response.json(), name).toMatchObject({ ok: false });
    }
    await tablet.nothing('cmd');

    const pending = call(`/api/pianos/${pianoId}/command`, { method: 'POST', body: { name: 'transport', args: { action: 'toggle' } } });
    const cmd = await tablet.next('cmd');
    expect(cmd).toEqual({ t: 'cmd', id: expect.any(Number), name: 'transport', args: { action: 'toggle' } });
    tablet.send({ t: 'cmd.result', id: cmd.id, ok: true, message: 'Paused.' });
    const response = await pending;
    expect(await response.json()).toEqual({ ok: true, message: 'Paused.' });

    const audit = await env.DB.prepare("SELECT actor, detail FROM audit_log WHERE piano_id = ? AND action = 'command'").bind(pianoId).all<{ actor: string; detail: string }>();
    expect(audit.results.length).toBe(1);
    expect(audit.results[0]!.actor).toBe('steven@example.com');
    expect(JSON.parse(audit.results[0]!.detail)).toEqual({ name: 'transport', args: { action: 'toggle' }, ok: true, message: 'Paused.' });
    tablet.close();
  });

  it("relays the tablet's refusal, and says so when it is offline or silent", async () => {
    const { pianoId, secret } = await seedPiano();
    const { call } = await owner();
    const offline = await call(`/api/pianos/${pianoId}/command`, { method: 'POST', body: { name: 'library.load' } });
    expect(await offline.json()).toEqual({ ok: false, message: 'The piano is offline.' });

    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const pending = call(`/api/pianos/${pianoId}/command`, { method: 'POST', body: { name: 'play', args: { pieceId: 42 } } });
    const cmd = await tablet.next('cmd');
    expect(cmd.args).toEqual({ pieceId: 42 });
    tablet.send({ t: 'cmd.result', id: cmd.id, ok: false, message: 'No such piece.' });
    expect(await (await pending).json()).toEqual({ ok: false, message: 'No such piece.' });

    await runInDurableObject(room(pianoId), (instance) => {
      instance.timing.answer = 300;
    });
    const silent = await call(`/api/pianos/${pianoId}/command`, { method: 'POST', body: { name: 'guests', args: { open: true, approveFirst: false } } });
    expect((await tablet.next('cmd')).args).toEqual({ open: true, approveFirst: false });
    expect(await silent.json()).toEqual({ ok: false, message: "The piano didn't answer." });
    tablet.close();
  });

  it('checks every command the same way the room does', () => {
    expect(checkCommand('transport', { action: 'next' })).toEqual({ ok: true, name: 'transport', args: { action: 'next' } });
    expect(checkCommand('playChannel', { key: 'debussy' })).toEqual({ ok: true, name: 'playChannel', args: { key: 'debussy' } });
    expect(checkCommand('stopChannel', undefined)).toEqual({ ok: true, name: 'stopChannel', args: {} });
    expect(checkCommand('library.load', null)).toEqual({ ok: true, name: 'library.load', args: {} });
    expect(checkCommand('status', {})).toEqual({ ok: true, name: 'status', args: {} });
    expect(checkCommand('playChannel', { key: 'a\u0000b' }).ok).toBe(false);
    expect(checkCommand('play', { pieceId: 2 ** 60 }).ok).toBe(false);
    expect(checkCommand('transport', ['next']).ok).toBe(false);
  });
});
