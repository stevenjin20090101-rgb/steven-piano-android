/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { createExecutionContext, createScheduledController, waitOnExecutionContext } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import relay from '../src/relay/index';
import { newPianoId } from '../src/shared/ids';

const DAY = 24 * 60 * 60 * 1000;

describe('the daily cron', () => {
  it('prunes the audit log past 90 days, dead codes, and pianos no tablet ever took', async () => {
    const now = Date.now();
    const kept = newPianoId();
    const abandoned = newPianoId();
    const waiting = newPianoId();
    await env.DB.batch([
      env.DB.prepare('INSERT INTO pianos (id, name, created_at, enrolled_at) VALUES (?, ?, ?, ?)').bind(kept, 'Hall', now - 200 * DAY, now - 200 * DAY),
      env.DB.prepare('INSERT INTO pianos (id, name, created_at) VALUES (?, ?, ?)').bind(abandoned, 'New piano', now - 2 * DAY),
      env.DB.prepare('INSERT INTO pianos (id, name, created_at) VALUES (?, ?, ?)').bind(waiting, 'New piano', now - 60_000),
      env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(`OLD${kept.slice(0, 1).toUpperCase()}-AAAA`, abandoned, now - 2 * DAY, now - 2 * DAY + 900_000),
      env.DB.prepare('INSERT INTO enrol_codes (code, piano_id, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(`NEW${kept.slice(0, 1).toUpperCase()}-BBBB`, waiting, now - 60_000, now + 840_000),
      env.DB.prepare("INSERT INTO audit_log (at, actor, piano_id, action) VALUES (?, 'tablet', ?, 'enrol')").bind(now - 91 * DAY, kept),
      env.DB.prepare("INSERT INTO audit_log (at, actor, piano_id, action) VALUES (?, 'tablet', ?, 'rotate-commit')").bind(now - 89 * DAY, kept),
    ]);

    const controller = createScheduledController({ scheduledTime: new Date(now), cron: '17 3 * * *' });
    const ctx = createExecutionContext();
    await relay.scheduled!(controller, env, ctx);
    await waitOnExecutionContext(ctx);

    const actions = await env.DB.prepare('SELECT action FROM audit_log WHERE piano_id = ?').bind(kept).all<{ action: string }>();
    expect(actions.results.map((r) => r.action)).toEqual(['rotate-commit']);
    const pianos = await env.DB.prepare('SELECT id FROM pianos WHERE id IN (?, ?, ?)').bind(kept, abandoned, waiting).all<{ id: string }>();
    expect(pianos.results.map((r) => r.id).sort()).toEqual([kept, waiting].sort());
    const codes = await env.DB.prepare('SELECT piano_id FROM enrol_codes WHERE piano_id IN (?, ?)').bind(abandoned, waiting).all<{ piano_id: string }>();
    expect(codes.results.map((r) => r.piano_id)).toEqual([waiting]);
  });
});
