/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { describe, expect, it } from 'vitest';
import { Kind } from '../src/shared/protocol';
import { FakeBrowser, FakeTablet, answerJson, panel, room, seedPiano, sleep } from './helpers';

// Audit delta 3: what a tablet (or anything holding its secret) sends that is not the protocol is
// dropped, message by message; the room keeps its connection and goes on carrying requests.
describe('what is not the protocol', () => {
  it('is dropped without taking the room down, and the next request is carried as before', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const junk: string[] = [
      'not json',
      '[]',
      'null',
      '42',
      '{"t":5}',
      '{"t":"nope"}',
      '{"t":"res"}',
      '{"t":"res","id":"1","status":200,"headers":{}}',
      '{"t":"res","id":4294967295,"status":200,"headers":{}}',
      '{"t":"req.credit","id":1,"bytes":-5}',
      '{"t":"req.credit","id":1,"bytes":1e308}',
      '{"t":"req.credit","id":1,"bytes":"lots"}',
      '{"t":"ws.accept","id":"7"}',
      '{"t":"ws.text","id":7,"data":{"a":1}}',
      '{"t":"ws.close","id":7,"code":"x"}',
      '{"t":"cmd.result","id":99,"ok":"yes"}',
      '{"t":"secret.ack"}',
      '{"t":"status","app":"x","player":[1,2],"channels":{"a":1},"library":"all"}',
      '{"t":"status","__proto__":{"polluted":true}}',
      `{"t":"status","pad":"${'x'.repeat(70 * 1024)}"}`,
      '{'.repeat(10_000),
    ];
    for (const text of junk) tablet.ws.send(text);
    const frames: Uint8Array[] = [
      new Uint8Array([1, 2, 3]),
      new Uint8Array(5 + 64 * 1024 + 1),
      Uint8Array.of(0, 0, 0, 9, 9),
      Uint8Array.of(0xff, 0xff, 0xff, 0xff, Kind.ResChunk, 1, 2, 3),
      Uint8Array.of(0, 0, 0, 1, Kind.ResEnd),
    ];
    for (const frame of frames) tablet.ws.send(frame);
    await sleep(200);
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();

    // Still connected, and still carrying: a request and its answer, a browser's socket, a command's status.
    const pending = panel(pianoId, '/api/state');
    const req = await tablet.next('req');
    answerJson(tablet, req.id, 200, { still: 'here' });
    const response = await pending;
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ still: 'here' });
    const browser = await FakeBrowser.open(pianoId);
    expect(browser).toBeInstanceOf(FakeBrowser);
    (browser as FakeBrowser).close();
    const live = await room(pianoId).status(pianoId);
    expect(live.online).toBe(true);
    expect(tablet.closedWith).toBeNull();
    tablet.close();
  });

  it('answers a browser 502 for an answer the relay cannot read, and the room goes on', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    for (const status of [99, 600, 200.5, '200']) {
      const pending = panel(pianoId, '/api/state');
      const req = await tablet.next('req');
      tablet.send({ t: 'res', id: req.id, status, headers: {} });
      const response = await pending;
      expect(response.status, String(status)).toBe(502);
      expect(await response.json()).toMatchObject({ error: 'relay' });
      expect(await tablet.next('req.abort')).toEqual({ t: 'req.abort', id: req.id });
    }
    // An answer's body longer than its length is cut off, not passed on.
    const pending = panel(pianoId, '/api/state');
    const req = await tablet.next('req');
    tablet.send({ t: 'res', id: req.id, status: 200, headers: { 'Content-Type': 'application/json' }, length: 2 });
    tablet.sendFrame(req.id, Kind.ResChunk, new TextEncoder().encode('{}{"more":"than it said"}'));
    tablet.sendFrame(req.id, Kind.ResEnd);
    const response = await pending;
    expect(response.status).toBe(200);
    await expect(response.text()).rejects.toThrow();
    // And the next is carried as before.
    const next = panel(pianoId, '/api/state');
    const again = await tablet.next('req');
    answerJson(tablet, again.id, 200, { ok: true });
    expect(await (await next).json()).toEqual({ ok: true });
    tablet.close();
  });
});
