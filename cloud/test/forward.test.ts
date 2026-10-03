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
import { Kind } from '../src/shared/protocol';
import { FakeTablet, RELAY, answerJson, newAddress, panel, room, seedPiano } from './helpers';

describe('forwarding a browser request to the tablet', () => {
  it('says hello with the piano, its prefix and the caps', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    expect(tablet.response.headers.get('Sec-WebSocket-Protocol')).toBe('steven-piano-relay-1');
    const hello = await tablet.next('hello');
    expect(hello).toEqual({
      t: 'hello',
      pianoId,
      host: 'relay.test',
      prefix: `/p/${pianoId}`,
      caps: { maxBody: 104857600, chunk: 65536, window: 1048576 },
      at: expect.any(Number),
    });
    tablet.close();
  });

  it('carries the request with its allowed headers only, and streams the answer back', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    const address = newAddress();
    const pending = panel(pianoId, '/api/composers/Debussy%20Claude?size=row&q=a%26b', {
      address,
      headers: {
        Cookie: 'sp_session=abc',
        Origin: RELAY,
        Accept: 'application/json',
        'X-Steven-Piano': '1',
        'X-Relay-Address': '6.6.6.6',
        'X-Relay-Piano': 'aaaaaaaaaaaa',
        Authorization: 'Bearer something',
        'X-Forwarded-For': '7.7.7.7',
        'User-Agent': 'test',
      },
    });
    const req = await tablet.next('req');
    expect(req).toEqual({
      t: 'req',
      id: expect.any(Number),
      method: 'GET',
      path: '/api/composers/Debussy%20Claude',
      query: 'size=row&q=a%26b',
      headers: { host: 'relay.test', cookie: 'sp_session=abc', origin: RELAY, accept: 'application/json', 'x-steven-piano': '1' },
      address,
      prefix: `/p/${pianoId}`,
      body: false,
    });
    answerJson(tablet, req.id, 200, { composer: 'Debussy' }, {
      'Cache-Control': 'no-store',
      'Set-Cookie': `sp_session=new; HttpOnly; Secure; SameSite=Strict; Path=/p/${pianoId}/`,
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Credentials': 'true',
      'Content-Security-Policy': "default-src 'self'",
      Connection: 'keep-alive',
    });
    const response = await pending;
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ composer: 'Debussy' });
    expect(response.headers.get('Content-Type')).toBe('application/json; charset=utf-8');
    expect(response.headers.get('Set-Cookie')).toBe(`sp_session=new; HttpOnly; Secure; SameSite=Strict; Path=/p/${pianoId}/`);
    expect(response.headers.get('Strict-Transport-Security')).toBe('max-age=31536000');
    expect(response.headers.get('Content-Security-Policy')).toBe("default-src 'self'");
    expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(response.headers.get('X-Frame-Options')).toBe('DENY');
    expect([...response.headers.keys()].filter((k) => k.startsWith('access-control-'))).toEqual([]);
    tablet.close();
  });

  it('never trusts an address the client sends: the tablet sees CF-Connecting-IP', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    const pending = panel(pianoId, '/api/state', { address: '203.0.113.9', headers: { 'X-Relay-Address': '6.6.6.6', 'x-relay-host': 'evil.test' } });
    const req = await tablet.next('req');
    expect(req.address).toBe('203.0.113.9');
    expect(req.headers.host).toBe('relay.test');
    answerJson(tablet, req.id, 200, {});
    const response = await pending;
    expect(response.status).toBe(200);
    await response.arrayBuffer();
    tablet.close();
  });

  it("drops a cookie that would leave the piano's prefix", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    for (const cookie of ['sp_session=x; Path=/', 'sp_session=x; Path=/p/', 'sp_session=x; Domain=relay.test; Path=/p/' + pianoId + '/', 'sp_session=x; Path=/p/other']) {
      const pending = panel(pianoId, '/api/login', { method: 'POST' });
      const req = await tablet.next('req');
      answerJson(tablet, req.id, 204 === 204 ? 200 : 200, {}, { 'Set-Cookie': cookie });
      const response = await pending;
      expect(response.headers.get('Set-Cookie'), cookie).toBeNull();
      await response.arrayBuffer();
    }
    tablet.close();
  });

  // Audit delta 3: every piano's panel shares the relay's origin, so a header one tablet's answer carries
  // can reach the others'. Service-Worker-Allowed let an answer under /p/<id>/ install a service worker for
  // the whole origin (every piano's panel, and every PIN typed there); Clear-Site-Data, Refresh, Link,
  // Location and the reporting headers are no more the panel's. Only the headers the app sends pass.
  it("passes on only the headers the app's panel sends", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const pending = panel(pianoId, '/sw.js');
    const req = await tablet.next('req');
    const script = new TextEncoder().encode('self.addEventListener("fetch", () => {});');
    tablet.send({
      t: 'res',
      id: req.id,
      status: 200,
      headers: {
        // What the app sends (RelayedResponse.HEADERS and its Content-Type): kept.
        'Content-Type': 'text/javascript; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Set-Cookie': `sp_guest=${'G'.repeat(22)}; HttpOnly; SameSite=Strict; Path=/p/${pianoId}/; Secure`,
        'Retry-After': '30',
        Allow: 'GET',
        'X-Content-Type-Options': 'nosniff',
        'X-Frame-Options': 'DENY',
        'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': "default-src 'self'",
        'Cross-Origin-Resource-Policy': 'same-origin',
        // Anything else: dropped.
        'Service-Worker-Allowed': '/',
        'Clear-Site-Data': '"cookies", "storage"',
        Refresh: '0; url=https://evil.example/',
        Link: '<https://evil.example/x.js>; rel=preload; as=script',
        Location: 'https://evil.example/',
        'Content-Disposition': 'attachment; filename="panel.html"',
        'Report-To': '{"group":"x","max_age":86400,"endpoints":[{"url":"https://evil.example/r"}]}',
        'Reporting-Endpoints': 'x="https://evil.example/r"',
        NEL: '{"report_to":"x","max_age":86400}',
        'Permissions-Policy': 'camera=*',
        'Origin-Agent-Cluster': '?0',
        'Cross-Origin-Opener-Policy': 'unsafe-none',
        'Content-Encoding': 'identity',
        'X-Anything': 'else',
        // The pictures' limit is the relay's to say (v1.18 — M47b), never a tablet's.
        'X-Relay-Art-Limit': '9999',
      },
      length: script.byteLength,
    });
    tablet.sendFrame(req.id, Kind.ResChunk, script);
    tablet.sendFrame(req.id, Kind.ResEnd);
    const response = await pending;
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('self.addEventListener("fetch", () => {});');
    const names = [...response.headers.keys()].sort();
    expect(names).toEqual([
      'allow', 'cache-control', 'content-length', 'content-security-policy', 'content-type', 'cross-origin-resource-policy',
      'referrer-policy', 'retry-after', 'set-cookie', 'strict-transport-security', 'x-content-type-options', 'x-frame-options',
      'x-relay-art-limit',
    ]);
    expect(response.headers.get('Content-Security-Policy')).toBe("default-src 'self'");
    expect(response.headers.get('X-Relay-Art-Limit'), "the relay's own figure").toBe('600');
    tablet.close();
  });

  it('strips the prefix, redirects the bare piano path, and answers a HEAD and a 204 without a body', async () => {
    const { pianoId, secret } = await seedPiano();
    const redirect = await panel(pianoId, '?a=1', { redirect: 'manual' });
    expect(redirect.status).toBe(308);
    await redirect.arrayBuffer();
    expect(redirect.headers.get('Location')).toBe(`/p/${pianoId}/?a=1`);

    const tablet = await FakeTablet.connect(pianoId, secret);
    const page = panel(pianoId, '/');
    const req = await tablet.next('req');
    expect(req.path).toBe('/');
    expect(req.query).toBe('');
    const html = new TextEncoder().encode('<!doctype html><title>Panel</title>');
    tablet.send({ t: 'res', id: req.id, status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8' }, length: html.byteLength });
    tablet.sendFrame(req.id, Kind.ResChunk, html);
    tablet.sendFrame(req.id, Kind.ResEnd);
    const pageResponse = await page;
    expect(await pageResponse.text()).toBe('<!doctype html><title>Panel</title>');
    expect(pageResponse.headers.get('Content-Length')).toBe(String(html.byteLength));

    const noContent = panel(pianoId, '/api/transport', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"action":"toggle"}' });
    const post = await tablet.next('req');
    expect(post).toMatchObject({ method: 'POST', path: '/api/transport', body: true, headers: { 'content-type': 'application/json', 'content-length': '19' } });
    const chunk = await tablet.nextFrame(post.id);
    expect(chunk.kind).toBe(Kind.ReqChunk);
    expect(new TextDecoder().decode(chunk.payload)).toBe('{"action":"toggle"}');
    expect((await tablet.nextFrame(post.id)).kind).toBe(Kind.ReqEnd);
    tablet.send({ t: 'res', id: post.id, status: 204, headers: { 'Cache-Control': 'no-store' } });
    tablet.sendFrame(post.id, Kind.ResEnd);
    const done = await noContent;
    expect(done.status).toBe(204);
    expect(await done.text()).toBe('');
    tablet.close();
  });

  it('answers 504 when the tablet says nothing, and tells it to stop', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    await runInDurableObject(room(pianoId), (instance) => {
      instance.timing.res = 300;
    });
    const response = await panel(pianoId, '/api/state');
    const req = await tablet.next('req');
    expect(response.status).toBe(504);
    expect(await response.json()).toMatchObject({ error: 'timeout' });
    expect(await tablet.next('req.abort')).toEqual({ t: 'req.abort', id: req.id });
    tablet.close();
  });

  it('works on 8 requests at once, queues 32 more, and answers the next one 503 busy', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const address = newAddress();
    const pending: Promise<Response>[] = [];
    for (let i = 0; i < 40; i++) pending.push(panel(pianoId, `/api/library?n=${i}`, { address }));
    const first: Record<string, any>[] = [];
    for (let i = 0; i < 8; i++) first.push(await tablet.next('req'));
    await tablet.nothing('req', 300);
    const busy = await panel(pianoId, '/api/state', { address });
    expect(busy.status).toBe(503);
    expect(await busy.json()).toMatchObject({ error: 'busy' });
    // Each answer lets one waiting request through.
    const queue = [...first];
    for (let answered = 0; answered < 40; answered++) {
      const req = queue.shift() ?? (await tablet.next('req'));
      answerJson(tablet, req.id, 200, { n: req.query });
    }
    const all = await Promise.all(pending);
    expect(all.map((r) => r.status)).toEqual(new Array(40).fill(200));
    await Promise.all(all.map((r) => r.arrayBuffer()));
    tablet.close();
  });
});
