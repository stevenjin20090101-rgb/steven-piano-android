-- ============================================================================
--  Steven Piano - Android player for the self-playing acoustic piano
--  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
--  Original author & creator: Steven Jin.
--  Licensed under the MIT License (see LICENSE). This copyright and attribution
--  notice MUST be preserved in all copies or substantial portions of the work.
--  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
-- ============================================================================
--
-- Steven Piano Cloud's one database (D1 "steven-piano"), shared by the relay and the console.
-- Times are milliseconds since 1970 (UTC). Secrets are never stored: only their SHA-256 (hex).

-- One row per piano: made by the console's "Enrol a tablet", filled in by the tablet's enrolment
-- and by its status (at most once a minute, and when it connects or goes).
CREATE TABLE pianos (
  id TEXT PRIMARY KEY,                -- 12 characters of base32 (a-z, 2-7)
  name TEXT NOT NULL,
  secret_hash TEXT,                   -- SHA-256 of the tablet's bearer secret; NULL once revoked
  pending_secret_hash TEXT,           -- a rotation under way: the new secret's hash, until the tablet confirms
  pending_until INTEGER,              -- the pending hash is accepted until then (10 minutes)
  created_at INTEGER NOT NULL,
  enrolled_at INTEGER,
  revoked_at INTEGER,
  last_seen INTEGER,
  online INTEGER NOT NULL DEFAULT 0,
  app_version TEXT,
  app_code INTEGER,
  firmware TEXT,
  guests INTEGER,                     -- Guests can request (1/0)
  approve_first INTEGER,              -- Approve requests first (1/0)
  status_json TEXT                    -- the tablet's latest status, as the relay keeps it
);

-- One-time enrolment codes, XXXX-XXXX, 15 minutes each.
CREATE TABLE enrol_codes (
  code TEXT PRIMARY KEY,
  piano_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);

-- What was done, by whom (the console's signed-in email, or "tablet"); pruned after 90 days.
CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  actor TEXT,
  piano_id TEXT,
  action TEXT NOT NULL,
  detail TEXT
);

CREATE INDEX pianos_last_seen ON pianos (last_seen);
CREATE INDEX audit_log_piano_at ON audit_log (piano_id, at);
CREATE INDEX audit_log_at ON audit_log (at);   -- the console's whole tail, and the 90-day prune
