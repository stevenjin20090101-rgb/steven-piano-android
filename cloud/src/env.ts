/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { PianoRoom } from './relay/room';

/** The relay's bindings (wrangler.relay.jsonc). */
export interface RelayEnv {
  ROOMS: DurableObjectNamespace<PianoRoom>;
  DB: D1Database;
  ENROL_LIMIT: RateLimit;
  PANEL_LIMIT: RateLimit;
  /** A panel's pictures (v1.18 — M47b): GETs under /api/art/, 600 a minute per address, apart from PANEL_LIMIT. */
  ART_LIMIT: RateLimit;
  LOGIN_LIMIT: RateLimit;
  MAX_BODY_BYTES: number | string;
  /** The host browsers use, when it isn't the one the tablet connected to (local development). */
  PUBLIC_HOST?: string;
}

/** The console's bindings (wrangler.console.jsonc; ACCESS_AUD is a secret). */
export interface ConsoleEnv {
  ROOMS: DurableObjectNamespace<PianoRoom>;
  DB: D1Database;
  ASSETS: Fetcher;
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;
  RELAY_URL?: string;
  /** "1" lets `wrangler dev` skip Access, and only for a request to localhost (never set in the config). */
  DEV_BYPASS?: string;
}
