/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { evictDurableObject, runInDurableObject } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import type { PianoRoom } from '../src/relay/room';
import { FakeBrowser, FakeTablet, answerJson, panel, pianoRow, room, seedPiano, status } from './helpers';

describe('hibernation', () => {
  it('keeps the tablet, its browsers and the status when the room is evicted and made again', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const browser = await FakeBrowser.open(pianoId);
    if (browser instanceof Response) throw new Error('refused');
    const open = await tablet.next('ws.open');
    tablet.send({ t: 'ws.accept', id: open.id });
    tablet.send(status('Clair de lune', Date.now()));
    const stub = room(pianoId);
    const deadline = Date.now() + 5000;
    while ((await stub.status(pianoId)).status?.player?.title !== 'Clair de lune' && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));

    const instanceBefore = await runInDurableObject(stub, (instance: PianoRoom) => instance);
    await evictDurableObject(stub);

    // A new instance, the same state: from storage and the sockets' attachments.
    const live = await stub.status(pianoId);
    expect(live).toMatchObject({ online: true, browsers: 1, status: { player: { title: 'Clair de lune' } } });
    const seen = await runInDurableObject(stub, (instance: PianoRoom, state) => ({
      fresh: instance !== instanceBefore,
      tablets: state.getWebSockets('tablet').map((ws) => ws.deserializeAttachment()),
      browsers: state.getWebSockets('browser').map((ws) => ws.deserializeAttachment()),
    }));
    expect(seen.fresh).toBe(true);
    expect(seen.tablets).toEqual([{ role: 'tablet', pianoId, session: expect.any(Number), connectedAt: expect.any(Number) }]);
    expect(seen.browsers).toEqual([{ role: 'browser', wsId: open.id, session: seen.tablets[0].session, accepted: true, dropped: false, openedAt: expect.any(Number) }]);

    // The tablet's socket still carries requests...
    const pending = panel(pianoId, '/api/state');
    const req = await tablet.next('req');
    answerJson(tablet, req.id, 200, { player: { status: 'playing' } });
    expect((await pending).status).toBe(200);
    // ...and its text to the browser, whose "ping" is still answered.
    tablet.send({ t: 'ws.text', id: open.id, data: '{"type":"progress","positionMs":2000}' });
    await browser.waitFor(() => (browser.raw.includes('{"type":"progress","positionMs":2000}') ? true : undefined), 'the progress');
    browser.send('ping');
    await browser.waitFor(() => (browser.raw.includes('pong') ? true : undefined), 'pong');

    // The D1 throttle survived too: a status within the minute is kept, not written.
    tablet.send(status('Arabesque No. 1', Date.now()));
    const until = Date.now() + 5000;
    while ((await stub.status(pianoId)).status?.player?.title !== 'Arabesque No. 1' && Date.now() < until) await new Promise((r) => setTimeout(r, 10));
    expect((await pianoRow(pianoId))!.status_json).toContain('Clair de lune');
    browser.close();
    tablet.close();
  });

  it('answers a request in flight 502 when the room restarts', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const pending = panel(pianoId, '/api/library');
    await tablet.next('req');
    await runInDurableObject(room(pianoId), (_instance, state) => {
      state.abort('restart');
    }).catch(() => undefined);
    const response = await pending;
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ error: 'relay' });
  });
});
