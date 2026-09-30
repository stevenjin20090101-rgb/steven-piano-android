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
import { CHUNK, Kind, WINDOW } from '../src/shared/protocol';
import { FakeTablet, answerJson, panel, room, seedPiano, sleep } from './helpers';

const MB = 1024 * 1024;

/** A body of [length] bytes that says so, written by [write] (the byte at i is i mod 251). */
function sizedBody(length: number): { body: ReadableStream<Uint8Array>; writer: WritableStreamDefaultWriter<Uint8Array> } {
  const stream = new FixedLengthStream(length);
  return { body: stream.readable, writer: stream.writable.getWriter() };
}

function pattern(offset: number, n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  for (let i = 0; i < n; i++) bytes[i] = (offset + i) % 251;
  return bytes;
}

async function writePattern(writer: WritableStreamDefaultWriter<Uint8Array>, from: number, to: number, piece = MB): Promise<void> {
  for (let offset = from; offset < to; offset += piece) await writer.write(pattern(offset, Math.min(piece, to - offset)));
}

describe('request bodies', () => {
  it('streams 64 MB to the tablet in 64 KB chunks, never more than the credit ahead', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const size = 64 * MB;
    const { body, writer } = sizedBody(size);
    const writing = writePattern(writer, 0, size).then(() => writer.close());
    const pending = panel(pianoId, '/api/upload?name=big.zip', { method: 'PUT', body });

    const req = await tablet.next('req');
    expect(req).toMatchObject({ method: 'PUT', path: '/api/upload', query: 'name=big.zip', body: true, headers: { 'content-length': String(size) } });

    let received = 0;
    let granted = WINDOW;
    let largest = 0;
    let overdrawn = false;
    let corrupt = false;
    let ended = false;
    tablet.onFrame = (frame) => {
      if (frame.id !== req.id) return;
      if (frame.kind === Kind.ReqEnd) {
        ended = true;
        return;
      }
      const n = frame.payload.byteLength;
      largest = Math.max(largest, n);
      for (let i = 0; i < n; i += 4099) if (frame.payload[i] !== (received + i) % 251) corrupt = true;
      received += n;
      if (received > granted) overdrawn = true;
    };

    // The first window arrives; then nothing more until the tablet grants credit.
    await tablet.waitFor(() => (received >= WINDOW ? true : undefined), 'the first window', 20_000);
    await sleep(200);
    expect(received).toBe(WINDOW);

    // The tablet takes what it gets, 256 KB at a time.
    let credited = 0;
    while (!ended) {
      if (received - credited >= 256 * 1024 || (received === size && credited < received)) {
        const bytes = Math.min(256 * 1024, received - credited);
        credited += bytes;
        granted += bytes;
        tablet.send({ t: 'req.credit', id: req.id, bytes });
      }
      await tablet.waitFor(() => (ended || received - credited >= 256 * 1024 || (received === size && credited < received) ? true : undefined), 'more body', 20_000);
    }
    await writing;
    expect(received).toBe(size);
    expect(largest).toBe(CHUNK);
    expect(overdrawn).toBe(false);
    expect(corrupt).toBe(false);

    answerJson(tablet, req.id, 202, { name: 'big.zip' });
    const response = await pending;
    expect(response.status).toBe(202);
    expect(await response.json()).toEqual({ name: 'big.zip' });
    tablet.close();
  });

  it('refuses 100 MB + 1 at the edge (413), before a byte reaches the tablet', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const { body, writer } = sizedBody(100 * MB + 1);
    const response = await panel(pianoId, '/api/upload?name=huge.zip', { method: 'PUT', body });
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: 'size', message: 'A request can be 100 MB at most.' });
    expect(response.headers.get('Strict-Transport-Security')).toBe('max-age=31536000');
    await writer.abort().catch(() => undefined);
    await tablet.nothing('req');
    tablet.close();
  });

  it('refuses a body that does not say its length (411)', async () => {
    const { pianoId } = await seedPiano();
    const chunked = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(10));
        controller.close();
      },
    });
    const response = await panel(pianoId, '/api/upload?name=a.mid', { method: 'PUT', body: chunked });
    expect(response.status).toBe(411);
    expect(await response.json()).toEqual({ error: 'length', message: 'The upload must say how long it is.' });
  });

  it('tells the tablet (req.abort) when the browser goes away mid-upload', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const { body, writer } = sizedBody(4 * MB);
    const pending = panel(pianoId, '/api/upload?name=gone.zip', { method: 'PUT', body }).catch((e) => e);
    const req = await tablet.next('req');
    let received = 0;
    tablet.onFrame = (frame) => {
      if (frame.id === req.id) received += frame.payload.byteLength;
    };
    await writePattern(writer, 0, 200 * 1024, 64 * 1024);
    await tablet.waitFor(() => (received >= 3 * CHUNK ? true : undefined), 'the first bytes');
    await writer.abort(new Error('The browser closed the tab.')).catch(() => undefined);
    expect(await tablet.next('req.abort')).toEqual({ t: 'req.abort', id: req.id });
    await pending;
    tablet.close();
  });

  it('gives up on an upload that stalls (408), and tells the tablet', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    await runInDurableObject(room(pianoId), (instance) => {
      instance.timing.idle = 300;
    });
    const { body, writer } = sizedBody(2 * MB);
    const pending = panel(pianoId, '/api/upload?name=slow.zip', { method: 'PUT', body });
    const req = await tablet.next('req');
    tablet.onFrame = () => undefined;
    await writePattern(writer, 0, 100 * 1024, 50 * 1024);
    const response = await pending;
    expect(response.status).toBe(408);
    expect(await response.json()).toMatchObject({ error: 'timeout' });
    expect(await tablet.next('req.abort')).toEqual({ t: 'req.abort', id: req.id });
    await writer.abort().catch(() => undefined);
    tablet.close();
  });

  it('takes one upload at a time (409 for another), and stops sending once the tablet has answered', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const first = sizedBody(8 * MB);
    const writing = writePattern(first.writer, 0, 8 * MB).catch(() => undefined);
    const pending = panel(pianoId, '/api/upload?name=one.zip', { method: 'PUT', body: first.body });
    const req = await tablet.next('req');
    let received = 0;
    tablet.onFrame = (frame) => {
      if (frame.id === req.id && frame.kind === Kind.ReqChunk) received += frame.payload.byteLength;
    };

    const second = sizedBody(MB);
    const busy = await panel(pianoId, '/api/upload?name=two.zip', { method: 'PUT', body: second.body });
    expect(busy.status).toBe(409);
    expect(await busy.json()).toEqual({ error: 'busy', message: 'Another file is being added. Try again in a moment.' });
    await second.writer.abort().catch(() => undefined);

    // A small JSON body is not an upload: it goes through meanwhile.
    const small = panel(pianoId, '/api/seek', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"ms":1000}' });
    const seek = await tablet.next('req', (m) => m.path === '/api/seek');
    answerJson(tablet, seek.id, 200, {});
    expect((await small).status).toBe(200);

    // The tablet refuses the first after its window: the relay stops sending it.
    await tablet.waitFor(() => (received >= WINDOW ? true : undefined), 'the window');
    answerJson(tablet, req.id, 507, { error: 'space', message: 'The tablet is short of space.' });
    const refused = await pending;
    expect(refused.status).toBe(507);
    tablet.send({ t: 'req.credit', id: req.id, bytes: 4 * MB });
    await sleep(300);
    expect(received).toBe(WINDOW);
    await first.writer.abort().catch(() => undefined);
    await writing;

    // The place is free again.
    const third = sizedBody(128 * 1024);
    const thirdWriting = writePattern(third.writer, 0, 128 * 1024).then(() => third.writer.close());
    const again = panel(pianoId, '/api/upload?name=three.zip', { method: 'PUT', body: third.body });
    const req3 = await tablet.next('req', (m) => m.query === 'name=three.zip');
    tablet.onFrame = () => undefined;
    await thirdWriting;
    answerJson(tablet, req3.id, 202, { name: 'three.zip' });
    expect((await again).status).toBe(202);
    tablet.close();
  });
});
