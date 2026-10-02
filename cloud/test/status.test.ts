/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { describe, expect, it } from 'vitest';
import { sanitizeStatus } from '../src/shared/protocol';
import { FakeTablet, room, seedPiano, status } from './helpers';

async function until(check: () => Promise<boolean>, what: string): Promise<void> {
  const deadline = Date.now() + 5000;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

// v1.11 — M29: what plays and what is played from, read-only, and never a device's name.
describe("a tablet's instruments in its status", () => {
  it('keeps the kinds, transports, states and the two switches, and drops any name', () => {
    const kept = sanitizeStatus({
      instruments: {
        instrument: { kind: 'midi', name: 'Roland FP-30X', state: 'connected', address: 'C8:2E:18:00:11:22' },
        keyboard: { name: "Kim's KeyStep", transport: 'bluetooth', state: 'pairing' },
        live: true,
        recording: 'yes',
        liveToPiano: true,
      },
    })!;
    expect(kept.instruments).toEqual({
      instrument: { kind: 'midi', state: 'connected' },
      keyboard: { transport: 'bluetooth', state: 'pairing' },
      live: true,
      recording: false,
    });
    const text = JSON.stringify(kept);
    for (const name of ['Roland', 'FP-30X', 'KeyStep', 'C8:2E:18', 'liveToPiano']) expect(text).not.toContain(name);
  });

  it('cuts what is long, refuses what is not an object, and reads an older tablet as none', () => {
    const long = sanitizeStatus({ instruments: { instrument: { kind: 'k'.repeat(100), state: 's\u0000'.repeat(100) }, keyboard: null } })!;
    expect(long.instruments!.instrument!.kind!.length).toBe(16);
    expect(long.instruments!.instrument!.state).toBe('s'.repeat(24));
    expect(long.instruments!.keyboard).toBeNull();
    expect(sanitizeStatus({ instruments: 'midi' })!.instruments).toBeNull();
    expect(sanitizeStatus({ instruments: [1, 2] })!.instruments).toBeNull();
    expect(sanitizeStatus({ app: { version: '1.10', code: 18 } })!.instruments).toBeNull();
  });

  it('reaches the room as sent, names left behind', async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.send(status('Clair de lune', Date.now(), {
      instruments: { instrument: { kind: 'steven', state: 'connected' }, keyboard: { name: 'FP-30X', transport: 'usb', state: 'connected' }, live: true, recording: false },
    }));
    await until(async () => (await room(pianoId).status(pianoId)).status !== null, 'the status');
    const kept = (await room(pianoId).status(pianoId)).status!;
    expect(kept.instruments).toEqual({ instrument: { kind: 'steven', state: 'connected' }, keyboard: { transport: 'usb', state: 'connected' }, live: true, recording: false });
    expect(JSON.stringify(kept)).not.toContain('FP-30X');
    tablet.close();
  });
});
