/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { SELF } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { enrol } from '../src/relay/enrol';
import { CODE_ALPHABET, ENROL_CODE, PIANO_ID, SECRET, newEnrolCode, newPianoId, newSecret } from '../src/shared/ids';
import { FakeTablet, RELAY, newAddress, pianoRow, seedPiano } from './helpers';

/** A piano made by the console's "Enrol a tablet", and its code. */
async function seedCode(options: { expiresIn?: number; name?: string } = {}): Promise<{ pianoId: string; code: string }> {
  const pianoId = newPianoId();
  const code = newEnrolCode();
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('INSERT INTO pianos (id, name, created_at) VALUES (?, ?, ?)').bind(pianoId, options.name ?? 'New piano', now),
    env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(code, pianoId, now, now + (options.expiresIn ?? 15 * 60 * 1000)),
  ]);
  return { pianoId, code };
}

function enrolRequest(body: unknown, address: string): Request {
  const text = JSON.stringify(body);
  return new Request(`${RELAY}/api/enrol`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': String(new TextEncoder().encode(text).byteLength), 'CF-Connecting-IP': address },
    body: text,
  });
}

function post(body: unknown, address = newAddress()): Promise<Response> {
  return SELF.fetch(enrolRequest(body, address));
}

describe('enrolment', () => {
  it('trades a code for the piano id and a secret, once', async () => {
    const { pianoId, code } = await seedCode();
    const response = await post({ code: code.toLowerCase().replace('-', ' '), name: 'Music room', model: 'SM-X200' });
    expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    const body = (await response.json()) as Record<string, string>;
    expect(body).toEqual({ pianoId, secret: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), host: 'relay.test', panelUrl: `https://relay.test/p/${pianoId}/` });

    const row = await pianoRow(pianoId);
    expect(row).toMatchObject({ name: 'Music room', enrolled_at: expect.any(Number), revoked_at: null });
    expect(row!.secret_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(row)).not.toContain(body.secret!);
    const audit = await env.DB.prepare("SELECT actor, action, detail FROM audit_log WHERE piano_id = ? AND action = 'enrol'").bind(pianoId).first<Record<string, string>>();
    expect(audit).toMatchObject({ actor: 'tablet', action: 'enrol' });
    expect(JSON.parse(audit!.detail!)).toMatchObject({ name: 'Music room', model: 'SM-X200' });

    // The secret works; the code doesn't again.
    const tablet = await FakeTablet.connect(pianoId, body.secret!);
    await tablet.next('hello');
    tablet.close();
    const again = await post({ code });
    expect(again.status).toBe(404);
    expect(await again.json()).toMatchObject({ error: 'code' });
  });

  it("keeps a name the console gave, and takes the tablet's only for a new piano", async () => {
    const { pianoId, code } = await seedCode({ name: 'Hall' });
    expect((await post({ code, name: 'Tablet name' })).status).toBe(200);
    expect((await pianoRow(pianoId))!.name).toBe('Hall');
  });

  it('refuses an expired code, and one whose piano was forgotten', async () => {
    const expired = await seedCode({ expiresIn: -1000 });
    const response = await post({ code: expired.code });
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: 'code', message: expect.any(String) });
    expect((await pianoRow(expired.pianoId))!.secret_hash).toBeNull();

    const orphan = await seedCode();
    await env.DB.prepare('DELETE FROM pianos WHERE id = ?').bind(orphan.pianoId).run();
    expect((await post({ code: orphan.code })).status).toBe(404);
    const used = await env.DB.prepare('SELECT used_at FROM enrol_codes WHERE code = ?').bind(orphan.code).first<{ used_at: number | null }>();
    expect(used!.used_at).toBeNull();
  });

  // Audit delta 3: the console makes every code with a new piano, so a code never names one already
  // enrolled; the claim itself now holds that too. A code row naming an enrolled (or a revoked) piano, as a
  // later console feature or a hand-edited database might make, re-keyed it and cleared its revoke.
  it("never re-keys a piano that was enrolled already, revoked or not (audit delta 3)", async () => {
    for (const revoked of [false, true]) {
      const { pianoId, secret } = await seedPiano();
      if (revoked) await env.DB.prepare('UPDATE pianos SET secret_hash = NULL, revoked_at = ? WHERE id = ?').bind(Date.now(), pianoId).run();
      const before = await pianoRow(pianoId);
      const code = newEnrolCode();
      await env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(code, pianoId, Date.now(), Date.now() + 60_000).run();
      const response = await post({ code });
      expect(response.status, `revoked: ${revoked}`).toBe(404);
      expect(await response.json()).toMatchObject({ error: 'code' });
      expect(await pianoRow(pianoId)).toEqual(before);
      const used = await env.DB.prepare('SELECT used_at FROM enrol_codes WHERE code = ?').bind(code).first<{ used_at: number | null }>();
      expect(used!.used_at).toBeNull();
      const notes = await env.DB.prepare("SELECT COUNT(*) AS n FROM audit_log WHERE piano_id = ? AND action = 'enrol'").bind(pianoId).first<{ n: number }>();
      expect(notes!.n).toBe(0);
      if (!revoked) {
        const tablet = await FakeTablet.connect(pianoId, secret);
        await tablet.next('hello');
        tablet.close();
      }
    }
  });

  it('makes codes of 8 symbols from the 32 (40 bits), each symbol as likely as the others (audit delta 3)', () => {
    const counts = new Map<string, number>();
    for (let i = 0; i < 4_000; i++) {
      const code = newEnrolCode();
      expect(code).toMatch(ENROL_CODE);
      for (const c of code.replace('-', '')) counts.set(c, (counts.get(c) ?? 0) + 1);
    }
    expect(CODE_ALPHABET.length).toBe(32);
    expect([...counts.keys()].sort().join('')).toBe([...CODE_ALPHABET].sort().join(''));
    // 32,000 symbols: 1,000 each expected, a standard deviation of about 31.
    for (const [c, n] of counts) expect(Math.abs(n - 1_000), c).toBeLessThan(250);
    expect(newPianoId()).toMatch(PIANO_ID);
    expect(newSecret()).toMatch(SECRET);
  });

  it('takes five tries a minute from an address, then 429', async () => {
    const address = newAddress();
    for (let i = 0; i < 5; i++) {
      const response = await post({ code: 'AAAA-AAAA' }, address);
      expect(response.status).toBe(404);
    }
    const sixth = await post({ code: 'AAAA-AAAA' }, address);
    expect(sixth.status).toBe(429);
    expect(sixth.headers.get('Retry-After')).toBe('60');
    expect(await sixth.json()).toMatchObject({ error: 'wait', retryAfter: 60 });
    // A good code from the same address waits too; another address is not held up.
    const { code } = await seedCode();
    expect((await post({ code }, address)).status).toBe(429);
    expect((await post({ code }, newAddress())).status).toBe(200);
  });

  it('refuses what is not an enrolment: other methods, other fields, not JSON', async () => {
    expect((await SELF.fetch(`${RELAY}/api/enrol`, { headers: { 'CF-Connecting-IP': newAddress() } })).status).toBe(405);
    expect((await post({ code: 'AAAA-AAAA', admin: true })).status).toBe(400);
    const form = await SELF.fetch(`${RELAY}/api/enrol`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'CF-Connecting-IP': newAddress() },
      body: 'code=AAAA-AAAA',
    });
    expect(form.status).toBe(415);
  });

  it('does the same work for an unknown, a used, an expired and a malformed code, and answers alike', async () => {
    const used = await seedCode();
    expect((await post({ code: used.code })).status).toBe(200);
    const expired = await seedCode({ expiresIn: -1000 });
    const cases: Record<string, string> = { unknown: 'ZZZZ-ZZZZ', used: used.code, expired: expired.code, malformed: 'nope!' };

    // What each costs the database: the same statements, in the same order.
    const shapes: Record<string, string[]> = {};
    const bodies: Record<string, string> = {};
    for (const [kind, code] of Object.entries(cases)) {
      const statements: string[] = [];
      const db = countingDb(env.DB, statements);
      const response = await enrol(enrolRequest({ code }, newAddress()), { ...env, DB: db });
      expect(response.status, kind).toBe(404);
      bodies[kind] = await response.text();
      shapes[kind] = statements;
    }
    expect(new Set(Object.values(bodies)).size).toBe(1);
    expect(shapes.unknown!.length).toBe(3);
    for (const kind of Object.keys(cases)) expect(shapes[kind], kind).toEqual(shapes.unknown);

    // And what each takes: the medians of 15 tries stay within a few milliseconds of each other.
    const medians: Record<string, number> = {};
    for (const [kind, code] of Object.entries(cases)) {
      const times: number[] = [];
      for (let i = 0; i < 15; i++) {
        const started = performance.now();
        const response = await enrol(enrolRequest({ code }, newAddress()), env);
        await response.arrayBuffer();
        times.push(performance.now() - started);
      }
      times.sort((a, b) => a - b);
      medians[kind] = times[7]!;
    }
    const spread = Math.max(...Object.values(medians)) - Math.min(...Object.values(medians));
    expect(spread, JSON.stringify(medians)).toBeLessThan(25);
  });
});

/** env.DB, noting each statement it is asked to run (batches included). */
function countingDb(db: D1Database, statements: string[]): D1Database {
  return new Proxy(db, {
    get(target, prop) {
      if (prop === 'prepare') {
        return (sql: string) => {
          statements.push(sql.replace(/\s+/g, ' ').trim());
          return target.prepare(sql);
        };
      }
      const value = Reflect.get(target, prop);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
}
