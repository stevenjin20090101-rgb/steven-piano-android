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
import { FakeBrowser, FakeTablet, RELAY, room, seedPiano, sleep } from './helpers';

async function online() {
  const { pianoId, secret } = await seedPiano();
  const tablet = await FakeTablet.connect(pianoId, secret);
  await tablet.next('hello');
  return { pianoId, tablet };
}

async function openBrowser(pianoId: string, headers: Record<string, string> = {}): Promise<FakeBrowser> {
  const browser = await FakeBrowser.open(pianoId, headers);
  if (browser instanceof Response) throw new Error(`Refused: ${browser.status}`);
  return browser;
}

describe("a browser's socket", () => {
  it('is bridged: ws.open with its headers, ws.accept, the tablet\'s text verbatim; the browser\'s own frames dropped', async () => {
    const { pianoId, tablet } = await online();
    const browser = await openBrowser(pianoId, { Cookie: 'sp_session=abc', Origin: RELAY, 'CF-Connecting-IP': '198.51.100.7', 'User-Agent': 'x' });
    const open = await tablet.next('ws.open');
    expect(open).toEqual({ t: 'ws.open', id: expect.any(Number), headers: { cookie: 'sp_session=abc', origin: RELAY, host: 'relay.test' }, address: '198.51.100.7' });

    // Before the tablet accepts, its text is not passed on.
    tablet.send({ t: 'ws.text', id: open.id, data: 'early' });
    tablet.send({ t: 'ws.accept', id: open.id });
    const state = JSON.stringify({ type: 'state', player: { status: 'playing' } });
    tablet.send({ t: 'ws.text', id: open.id, data: state });
    await browser.waitFor(() => (browser.raw.length > 0 ? true : undefined), 'the state');
    expect(browser.raw).toEqual([state]);

    browser.send('ping');
    await browser.waitFor(() => (browser.raw.includes('pong') ? true : undefined), 'pong');
    browser.send('{"type":"anything"}');
    await tablet.nothing('ws.text');
    expect(tablet.texts.filter((m) => m.t !== 'status')).toEqual([]);

    browser.close(1000, 'Tab closed');
    expect(await tablet.next('ws.close')).toEqual({ t: 'ws.close', id: open.id, code: 1000, reason: 'Tab closed' });
    tablet.close();
  });

  it('is closed 1008 when the tablet refuses it', async () => {
    const { pianoId, tablet } = await online();
    const browser = await openBrowser(pianoId);
    const open = await tablet.next('ws.open');
    tablet.send({ t: 'ws.refuse', id: open.id, status: 401 });
    expect(await browser.closed()).toEqual({ code: 1008, reason: 'Enter the PIN first.' });
    await tablet.nothing('ws.close');
    tablet.close();
  });

  it("is closed with the tablet's code when the tablet closes it", async () => {
    const { pianoId, tablet } = await online();
    const browser = await openBrowser(pianoId);
    const open = await tablet.next('ws.open');
    tablet.send({ t: 'ws.accept', id: open.id });
    tablet.send({ t: 'ws.close', id: open.id, code: 4001, reason: 'Session ended' });
    expect(await browser.closed()).toEqual({ code: 4001, reason: 'Session ended' });
    tablet.close();
  });

  it('is one of four at most: a fifth is refused 503, and room comes back when one closes', async () => {
    const { pianoId, tablet } = await online();
    const browsers: FakeBrowser[] = [];
    for (let i = 0; i < 4; i++) {
      browsers.push(await openBrowser(pianoId));
      tablet.send({ t: 'ws.accept', id: (await tablet.next('ws.open')).id });
    }
    const fifth = await FakeBrowser.open(pianoId);
    expect(fifth).toBeInstanceOf(Response);
    expect((fifth as Response).status).toBe(503);
    expect(await (fifth as Response).json()).toEqual({ error: 'sockets', message: 'Too many panels are open.' });

    browsers[0]!.close();
    await tablet.next('ws.close');
    await sleep(50);
    const again = await openBrowser(pianoId);
    await tablet.next('ws.open');
    again.close();
    for (const b of browsers) b.close();
    tablet.close();
  });

  it('is closed when the tablet goes offline, and when the tablet never answers', async () => {
    const { pianoId, tablet } = await online();
    await runInDurableObject(room(pianoId), (instance) => {
      instance.timing.answer = 300;
    });
    const unanswered = await openBrowser(pianoId);
    const open = await tablet.next('ws.open');
    expect(await unanswered.closed()).toEqual({ code: 1011, reason: "The piano didn't answer." });
    expect(await tablet.next('ws.close')).toMatchObject({ id: open.id, code: 1011 });

    const browser = await openBrowser(pianoId);
    tablet.send({ t: 'ws.accept', id: (await tablet.next('ws.open')).id });
    await sleep(50);
    tablet.close(1001, 'Going away');
    expect(await browser.closed()).toEqual({ code: 1001, reason: 'The piano went offline.' });
  });

  it('is closed 1009 when the browser sends something large', async () => {
    const { pianoId, tablet } = await online();
    const browser = await openBrowser(pianoId);
    const open = await tablet.next('ws.open');
    tablet.send({ t: 'ws.accept', id: open.id });
    browser.send('x'.repeat(5000));
    expect(await browser.closed()).toMatchObject({ code: 1009 });
    expect(await tablet.next('ws.close')).toMatchObject({ id: open.id, code: 1009 });
    tablet.close();
  });
});
