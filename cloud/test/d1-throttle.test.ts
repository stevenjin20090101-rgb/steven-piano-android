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
import type { PianoRoom } from '../src/relay/room';
import { FakeTablet, pianoRow, room, seedPiano, status } from './helpers';

async function until(check: () => Promise<boolean>, what: string): Promise<void> {
  const deadline = Date.now() + 5000;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

describe("the status's writes to D1", () => {
  it('reach D1 at most once a minute per piano, while the room keeps the latest', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const stub = room(pianoId);
    let now = Date.now();
    await runInDurableObject(stub, (instance: PianoRoom) => {
      instance.clock = () => now;
    });
    const writes = () => runInDurableObject(stub, (instance: PianoRoom) => instance.d1StatusWrites);

    tablet.send(status('Clair de lune', now));
    await until(async () => ((await pianoRow(pianoId))!.status_json ?? '').includes('Clair de lune'), 'the first status in D1');
    expect(await pianoRow(pianoId)).toMatchObject({ app_version: '1.10', app_code: 18, firmware: '2.0.0', guests: 1, approve_first: 0, online: 1, last_seen: now });

    // Three more within the minute: the room has the latest, D1 still the first.
    for (const title of ['Arabesque No. 1', 'Rêverie', 'La fille aux cheveux de lin']) {
      now += 10_000;
      tablet.send(status(title, now, { firmware: '2.0.1' }));
    }
    await until(async () => (await stub.status(pianoId)).status?.player?.title === 'La fille aux cheveux de lin', 'the latest in the room');
    const row = await pianoRow(pianoId);
    expect(row!.status_json).toContain('Clair de lune');
    expect(row!.firmware).toBe('2.0.0');
    expect(await writes()).toBe(1);

    // A minute on, the next one goes through.
    now += 31_000;
    tablet.send(status("Children's Corner", now, { firmware: '2.0.1' }));
    await until(async () => ((await pianoRow(pianoId))!.status_json ?? '').includes("Children's Corner"), 'the next write');
    expect(await pianoRow(pianoId)).toMatchObject({ firmware: '2.0.1', last_seen: now });
    expect(await writes()).toBe(2);
    tablet.close();
  });

  it('keeps only what it knows of a status: no device ids, strings cut', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.send(status('x'.repeat(500), Date.now(), { serial: 'R58N12345', deviceId: 'abc', player: { status: 'paused', title: 'x'.repeat(500), composer: 'A\u0000B' } }));
    await until(async () => (await stub(pianoId)).status !== null, 'the status');
    const kept = (await stub(pianoId)).status!;
    expect(JSON.stringify(kept)).not.toContain('R58N12345');
    expect(Object.keys(kept)).toEqual(['app', 'firmware', 'link', 'player', 'guests', 'panel', 'library', 'channels', 'at']);
    expect(kept.player!.title!.length).toBe(200);
    expect(kept.player!.composer).toBe('AB');
    tablet.close();
  });
});

function stub(pianoId: string) {
  return room(pianoId).status(pianoId);
}
