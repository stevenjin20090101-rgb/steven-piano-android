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
import type { PianoRoom } from '../src/relay/room';
import { owner } from './console-helpers';
import { FakeBrowser, FakeTablet, pianoRow, room, seedPiano, sleep, status, tabletRequest } from './helpers';

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

  it('refuses a connection whose check was overtaken by a revoke', async () => {
    const { pianoId, secret } = await seedPiano();
    const stub = room(pianoId);
    // Hold the connection's look at its secret until the revoke is done.
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    let reached!: () => void;
    const atCheck = new Promise<void>((resolve) => {
      reached = resolve;
    });
    await runInDurableObject(stub, (instance: PianoRoom) => {
      const room = instance as unknown as { env: typeof env };
      const db = room.env.DB;
      room.env = {
        ...room.env,
        DB: new Proxy(db, {
          get(target, prop) {
            if (prop !== 'prepare') return Reflect.get(target, prop).bind(target);
            return (sql: string) => {
              const statement = target.prepare(sql);
              if (!sql.startsWith('SELECT secret_hash, pending_secret_hash')) return statement;
              return {
                bind: (...values: unknown[]) => ({
                  first: async () => {
                    const row = await statement.bind(...values).first();
                    reached();
                    await held;
                    return row;
                  },
                }),
              };
            };
          },
        }),
      };
    });
    const connecting = tabletRequest(pianoId, secret);
    await atCheck;
    const { call } = await owner();
    expect(await (await call(`/api/pianos/${pianoId}/revoke`, { method: 'POST' })).json()).toMatchObject({ ok: true });
    release();
    const response = await connecting;
    expect(response.status).toBe(503);
    expect(response.webSocket).toBeNull();
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
  });

  // Audit delta 3: the R1 fix re-checked the epoch after the secret's look only. A connection that
  // replaces another awaited its "replaced" note in D1 after that check, and a revoke or a forget
  // landing meanwhile was missed: the revoked tablet was accepted (101) and stayed connected.
  it('refuses a replacing connection whose note was overtaken by a revoke (audit delta 3)', async () => {
    const { pianoId, secret } = await seedPiano();
    const first = await FakeTablet.connect(pianoId, secret);
    await first.next('hello');
    const hold = await holdReplacedNote(pianoId);
    const connecting = tabletRequest(pianoId, secret);
    await hold.reached(1);
    const { call } = await owner();
    expect(await (await call(`/api/pianos/${pianoId}/revoke`, { method: 'POST' })).json()).toMatchObject({ ok: true });
    expect(await first.closed()).toMatchObject({ code: 4401 });
    hold.release();
    const response = await connecting;
    expect(response.status).toBe(503);
    expect(response.webSocket).toBeNull();
    expect((await room(pianoId).status(pianoId)).online).toBe(false);
    expect((await tabletRequest(pianoId, secret)).status).toBe(401);
    expect((await pianoRow(pianoId))!.online).toBe(0);
  });

  it('refuses a replacing connection whose note was overtaken by a forget (audit delta 3)', async () => {
    const { pianoId, secret } = await seedPiano();
    const first = await FakeTablet.connect(pianoId, secret);
    await first.next('hello');
    const hold = await holdReplacedNote(pianoId);
    const connecting = tabletRequest(pianoId, secret);
    await hold.reached(1);
    const { call } = await owner();
    expect(await (await call(`/api/pianos/${pianoId}`, { method: 'DELETE' })).json()).toEqual({ ok: true, message: 'Forgotten.' });
    expect(await first.closed()).toMatchObject({ code: 4403 });
    hold.release();
    const response = await connecting;
    expect(response.status).toBe(503);
    expect(response.webSocket).toBeNull();
    expect((await room(pianoId).status(pianoId)).online).toBe(false);
  });

  it("never marks a piano without a secret online, whatever arrives late on a socket (audit delta 3)", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    // As if revoked by a statement the room has not acted on yet: the secret gone, the flag down.
    await env.DB.prepare('UPDATE pianos SET secret_hash = NULL, revoked_at = ?, online = 0, status_json = NULL WHERE id = ?').bind(Date.now(), pianoId).run();
    await runInDurableObject(room(pianoId), (instance: PianoRoom) => {
      instance.clock = () => Date.now() + 120_000; // a status that is due for D1
    });
    tablet.send(status('Clair de lune', Date.now()));
    await sleep(200);
    expect(await pianoRow(pianoId)).toMatchObject({ online: 0, status_json: null });
    tablet.close();
  });

  it('lets only the newest of two replacing connections stay (audit delta 3)', async () => {
    const { pianoId, secret } = await seedPiano();
    const first = await FakeTablet.connect(pianoId, secret);
    await first.next('hello');
    const hold = await holdReplacedNote(pianoId);
    const second = tabletRequest(pianoId, secret);
    await hold.reached(1);
    const third = tabletRequest(pianoId, secret);
    await hold.reached(2);
    hold.release();
    const [a, b] = await Promise.all([second, third]);
    expect([a.status, b.status]).toEqual([101, 101]);
    a.webSocket!.accept();
    b.webSocket!.accept();
    const secondTablet = new FakeTablet(a.webSocket!, a);
    const thirdTablet = new FakeTablet(b.webSocket!, b);
    expect(await first.closed()).toMatchObject({ code: 4409 });
    // One of the two replaced the other: exactly one tablet socket is left open in the room.
    const closed = await Promise.race([secondTablet.closed(), thirdTablet.closed()]);
    expect(closed).toMatchObject({ code: 4409 });
    await sleep(100);
    const open = await runInDurableObject(room(pianoId), (instance: PianoRoom) =>
      (instance as unknown as { ctx: DurableObjectState }).ctx.getWebSockets('tablet').filter((ws) => ws.readyState === 1).length);
    expect(open).toBe(1);
    secondTablet.close();
    thirdTablet.close();
  });
});


/**
 * Holds the room's "replaced" audit note in D1 (the await between a replacing connection's checks
 * and its accept) until [release]; [reached] waits until [n] connections are held there.
 */
async function holdReplacedNote(pianoId: string): Promise<{ reached: (n: number) => Promise<void>; release: () => void }> {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let count = 0;
  await runInDurableObject(room(pianoId), (instance: PianoRoom) => {
    const target = instance as unknown as { env: typeof env };
    const db = target.env.DB;
    target.env = {
      ...target.env,
      DB: new Proxy(db, {
        get(inner, prop) {
          if (prop !== 'prepare') return Reflect.get(inner, prop).bind(inner);
          return (sql: string) => {
            const statement = inner.prepare(sql);
            if (!sql.startsWith('INSERT INTO audit_log')) return statement;
            return new Proxy(statement, {
              get(s, p) {
                if (p !== 'bind') return Reflect.get(s, p).bind(s);
                return (...values: unknown[]) => {
                  const bound = s.bind(...values);
                  if (values[3] !== 'replaced') return bound;
                  return new Proxy(bound, {
                    get(b, q) {
                      if (q !== 'run') return Reflect.get(b, q).bind(b);
                      return async () => {
                        count++;
                        await held;
                        return b.run();
                      };
                    },
                  });
                };
              },
            });
          };
        },
      }),
    };
  });
  // The test looks at the count from its own context (a promise resolved from inside the room would
  // carry the room's I/O context into the test).
  return {
    reached: async (n) => {
      const deadline = Date.now() + 5000;
      while (count < n) {
        if (Date.now() > deadline) throw new Error(`Timed out waiting for ${n} held notes`);
        await sleep(5);
      }
    },
    release,
  };
}
