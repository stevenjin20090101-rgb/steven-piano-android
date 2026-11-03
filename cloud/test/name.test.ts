/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { describe, expect, it } from 'vitest';
import { owner } from './console-helpers';
import { FakeTablet, panel, seedPiano } from './helpers';

// M79: each school's panel shows its piano's name from the console.
describe("a piano's name", () => {
  it("comes with the tablet's hello, and none while the piano is still the console's New piano", async () => {
    const named = await seedPiano({ name: "Steven Jin's Piano · Fessenden School" });
    const tablet = await FakeTablet.connect(named.pianoId, named.secret);
    expect((await tablet.next('hello')).name).toBe("Steven Jin's Piano · Fessenden School");
    tablet.close();

    const fresh = await seedPiano({ name: 'New piano' });
    const other = await FakeTablet.connect(fresh.pianoId, fresh.secret);
    expect((await other.next('hello')).name).toBeNull();
    other.close();
  });

  it('reaches a connected tablet at once when the console renames the piano, and names the offline page', async () => {
    const { call } = await owner();
    const { pianoId, secret } = await seedPiano({ name: 'Brooks Arts Center Piano' });
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    const renamed = await call(`/api/pianos/${pianoId}`, { method: 'PATCH', body: { name: 'Fessenden <School>' } });
    expect(renamed.status).toBe(200);
    expect(await tablet.next('name')).toEqual({ t: 'name', name: 'Fessenden <School>' });

    tablet.close();
    const deadline = Date.now() + 5000;
    let page = '';
    while (Date.now() < deadline) {
      const response = await panel(pianoId, '/');
      page = await response.text();
      if (response.status === 503) break;
    }
    expect(page).toContain('<title>Fessenden &lt;School&gt; · offline</title>');
    expect(page).toContain('<p class="eyebrow">Fessenden &lt;School&gt;</p>');
  });
});
