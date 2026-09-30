/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { TabletStatus } from './protocol';

/** The name the console gives a piano until the tablet or the owner names it. */
export const DEFAULT_NAME = 'New piano';

/** The database both Workers share (migrations/0001_init.sql), and what they read and write in it. */

export interface PianoRow {
  id: string;
  name: string;
  secret_hash: string | null;
  pending_secret_hash: string | null;
  pending_until: number | null;
  created_at: number;
  enrolled_at: number | null;
  revoked_at: number | null;
  last_seen: number | null;
  online: number;
  app_version: string | null;
  app_code: number | null;
  firmware: string | null;
  guests: number | null;
  approve_first: number | null;
  status_json: string | null;
}

export interface AuditRow {
  id: number;
  at: number;
  actor: string | null;
  piano_id: string | null;
  action: string;
  detail: string | null;
}

/** The columns that may leave the database: never a secret's hash. */
export const PUBLIC_COLUMNS =
  'id, name, created_at, enrolled_at, revoked_at, last_seen, online, app_version, app_code, firmware, guests, approve_first, status_json, pending_until';

/** A piano as the console shows it. */
export interface PianoView {
  id: string;
  name: string;
  /** Online by the database: its flag, and seen in the last [FRESH_MS]. */
  online: boolean;
  lastSeen: number | null;
  createdAt: number;
  enrolledAt: number | null;
  revokedAt: number | null;
  appVersion: string | null;
  appCode: number | null;
  firmware: string | null;
  guests: boolean | null;
  approveFirst: boolean | null;
  status: TabletStatus | null;
  /** A rotation's new secret is waiting for the tablet (until then). */
  rotatingUntil: number | null;
}

/**
 * How recently a piano must have been seen to count as online by the database alone: its status
 * reaches the database at most once a minute, every 30 s at most apart, so 3 minutes is a margin.
 */
export const FRESH_MS = 3 * 60 * 1000;

export function pianoView(row: Omit<PianoRow, 'secret_hash' | 'pending_secret_hash'>, now: number): PianoView {
  let status: TabletStatus | null = null;
  if (row.status_json) {
    try {
      status = JSON.parse(row.status_json) as TabletStatus;
    } catch {
      status = null;
    }
  }
  return {
    id: row.id,
    name: row.name,
    online: row.online === 1 && row.last_seen !== null && now - row.last_seen < FRESH_MS,
    lastSeen: row.last_seen,
    createdAt: row.created_at,
    enrolledAt: row.enrolled_at,
    revokedAt: row.revoked_at,
    appVersion: row.app_version,
    appCode: row.app_code,
    firmware: row.firmware,
    guests: row.guests === null ? null : row.guests === 1,
    approveFirst: row.approve_first === null ? null : row.approve_first === 1,
    status,
    rotatingUntil: row.pending_until !== null && row.pending_until > now ? row.pending_until : null,
  };
}

/** One audit row, as a statement to run alone or in a batch. [detail] is kept as JSON, cut to 2 KB. */
export function auditStatement(
  db: D1Database,
  entry: { at: number; actor: string | null; pianoId: string | null; action: string; detail?: unknown },
): D1PreparedStatement {
  let detail: string | null = null;
  if (entry.detail !== undefined && entry.detail !== null) {
    detail = JSON.stringify(entry.detail);
    if (detail.length > 2048) detail = detail.slice(0, 2048);
  }
  return db
    .prepare('INSERT INTO audit_log (at, actor, piano_id, action, detail) VALUES (?, ?, ?, ?, ?)')
    .bind(entry.at, entry.actor ? entry.actor.slice(0, 200) : null, entry.pianoId, entry.action, detail);
}

export interface AuditView {
  at: number;
  actor: string | null;
  pianoId: string | null;
  action: string;
  detail: unknown;
}

export function auditView(row: AuditRow): AuditView {
  let detail: unknown = null;
  if (row.detail) {
    try {
      detail = JSON.parse(row.detail);
    } catch {
      detail = row.detail;
    }
  }
  return { at: row.at, actor: row.actor, pianoId: row.piano_id, action: row.action, detail };
}

/** The audit log keeps 90 days. */
export const AUDIT_DAYS = 90;

/**
 * The daily prune: audit rows older than 90 days; codes that expired a day ago; pianos made for a
 * tablet that never enrolled, a day on (nothing of theirs was ever used).
 */
export async function prune(db: D1Database, now: number): Promise<{ audit: number; codes: number; pianos: number }> {
  const day = 24 * 60 * 60 * 1000;
  const results = await db.batch([
    db.prepare('DELETE FROM audit_log WHERE at < ?').bind(now - AUDIT_DAYS * day),
    db.prepare('DELETE FROM enrol_codes WHERE expires_at < ?').bind(now - day),
    db.prepare('DELETE FROM pianos WHERE enrolled_at IS NULL AND created_at < ? AND NOT EXISTS (SELECT 1 FROM enrol_codes WHERE enrol_codes.piano_id = pianos.id)').bind(now - day),
  ]);
  return { audit: results[0]!.meta.changes, codes: results[1]!.meta.changes, pianos: results[2]!.meta.changes };
}
