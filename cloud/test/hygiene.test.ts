/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import consoleConfig from '../wrangler.console.jsonc?raw';
import relayConfig from '../wrangler.relay.jsonc?raw';
import packageJson from '../package.json?raw';
import { ART_LIMIT_PER_MINUTE } from '../src/shared/protocol';
import { FakeBrowser, FakeTablet, RELAY, answerJson, newAddress, panel, pianoRow, seedPiano, status } from './helpers';

/** A JSONC file's JSON: its comments taken out (none of the configs has "//" or "/*" inside a string but URLs, kept). */
function jsonc(text: string): Record<string, any> {
  const stripped = text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:"])\/\/.*$/gm, '$1');
  return JSON.parse(stripped.replace(/,(\s*[}\]])/g, '$1'));
}

// Audit delta 3: D1 holds what the console needs and nothing about a panel's visitors; the deployed
// configurations cannot let the console skip Access.
describe("the cloud's hygiene", () => {
  it("keeps nothing of a browser's visit in D1: no row, no address, no cookie, no PIN", async () => {
    const { pianoId, secret } = await seedPiano();
    const tablet = await FakeTablet.connect(pianoId, secret);
    await tablet.next('hello');
    tablet.send(status('Clair de lune', Date.now()));
    const count = async () => (await env.DB.prepare('SELECT COUNT(*) AS n FROM audit_log').first<{ n: number }>())!.n;
    const before = await count();
    const guest = '198.51.100.77';
    // A guest's page, a guest's request, a PIN try, a panel's socket: all through the relay.
    const visits = [
      panel(pianoId, '/request', { address: guest }),
      panel(pianoId, '/api/public/request', { address: guest, method: 'POST', headers: { 'Content-Type': 'application/json', Origin: RELAY, Cookie: 'sp_guest=GGGGGGGGGGGGGGGGGGGGGG' }, body: '{"pieceId":7}' }),
      panel(pianoId, '/api/login', { address: guest, method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Steven-Piano': '1', Origin: RELAY }, body: '{"pin":"482913"}' }),
    ];
    for (let i = 0; i < visits.length; i++) {
      const req = await tablet.next('req');
      expect(req.address).toBe(guest);
      answerJson(tablet, req.id, 202, {}, { 'Set-Cookie': `sp_session=${'S'.repeat(43)}; HttpOnly; SameSite=Strict; Path=/p/${pianoId}/; Secure` });
    }
    await Promise.all(visits.map(async (v) => (await v).arrayBuffer()));
    const browser = await FakeBrowser.open(pianoId, { 'CF-Connecting-IP': guest, Cookie: `sp_session=${'S'.repeat(43)}` });
    const open = await tablet.next('ws.open');
    expect(open.address).toBe(guest);
    tablet.send({ t: 'ws.accept', id: open.id });
    (browser as FakeBrowser).close();

    expect(await count()).toBe(before);
    const everything = JSON.stringify([
      await pianoRow(pianoId),
      (await env.DB.prepare('SELECT * FROM audit_log WHERE piano_id = ?').bind(pianoId).all()).results,
      (await env.DB.prepare('SELECT * FROM enrol_codes WHERE piano_id = ?').bind(pianoId).all()).results,
    ]);
    // The tablet's own address on its networks (the status's panel.host, which it no longer sends) is not kept either.
    for (const trace of [guest, 'GGGGGGGGGGGGGGGGGGGGGG', 'S'.repeat(43), '482913', secret, '100.101.2.3']) {
      expect(everything, trace).not.toContain(trace);
    }
    tablet.close();
  });

  it("keeps a tablet's secret only as its SHA-256, and no hash leaves the console", async () => {
    const { pianoId, secret } = await seedPiano();
    const row = await pianoRow(pianoId);
    expect(JSON.stringify(row)).not.toContain(secret);
    expect(row!.secret_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('has no DEV_BYPASS in either deployed configuration, and sets it only for `wrangler dev`', () => {
    for (const [name, text] of [['relay', relayConfig], ['console', consoleConfig]] as const) {
      const config = jsonc(text);
      expect(Object.keys(config.vars ?? {}), name).not.toContain('DEV_BYPASS');
      expect(JSON.stringify(config), name).not.toContain('DEV_BYPASS');
      expect(config.env, `${name}: no other environment to deploy`).toBeUndefined();
    }
    const scripts = JSON.parse(packageJson).scripts as Record<string, string>;
    for (const [name, script] of Object.entries(scripts)) {
      if (script.includes('DEV_BYPASS')) expect(script, name).toMatch(/^wrangler dev /);
      if (script.includes('wrangler deploy')) expect(script, name).not.toContain('--var');
    }
    expect(jsonc(consoleConfig).assets.run_worker_first).toBe(true);
  });

  it("limits a panel's pictures as the header tells the page (v1.18 — M47b)", () => {
    const limits = jsonc(relayConfig).ratelimits as Array<{ name: string; namespace_id: string; simple: { limit: number; period: number } }>;
    const art = limits.find((l) => l.name === 'ART_LIMIT');
    expect(art?.simple).toEqual({ limit: ART_LIMIT_PER_MINUTE, period: 60 });
    expect(new Set(limits.map((l) => l.namespace_id)).size, 'each limit its own namespace').toBe(limits.length);
  });

  it('answers a request from anywhere but localhost 401 even with DEV_BYPASS set', async () => {
    const { handle } = await import('../src/console/routes');
    const { consoleEnv } = await import('./console-helpers');
    const bypass = consoleEnv({ DEV_BYPASS: '1' });
    for (const url of ['https://steven-piano-console.you.workers.dev/api/pianos', 'https://localhost.evil.example/api/pianos', 'https://127.0.0.1.nip.io/api/pianos']) {
      const response = await handle(new Request(url, { headers: { 'X-Steven-Piano': '1', Host: 'localhost' } }), bypass);
      expect(response.status, url).toBe(401);
    }
    expect(newAddress()).toMatch(/^10\./);
  });
});
