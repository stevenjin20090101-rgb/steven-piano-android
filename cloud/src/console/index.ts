/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { ConsoleEnv } from '../env';
import { error } from '../shared/http';
import { handle } from './routes';

/**
 * The console: the owner's Worker (wrangler.console.jsonc), behind Cloudflare Access, which every
 * request also passes here ([requireAccess]). It serves its page (console/static) and this API; every
 * API request carries `X-Steven-Piano: 1` (403 without) and, when it says where it came from, the
 * console's own Origin (403 otherwise). No CORS, ever.
 *
 * | Method | Path | Answer |
 * |---|---|---|
 * | GET | /api/me | who is signed in (Access's email) |
 * | GET | /api/pianos | every piano (D1), with its latest status |
 * | POST | /api/enrol-codes | a new piano ("New piano") and its code, 15 minutes |
 * | GET | /api/pianos/:id | the piano (D1), its room's live status, the audit tail |
 * | PATCH | /api/pianos/:id `{name}` | renamed |
 * | POST | /api/pianos/:id/command `{name, args}` | the tablet's answer (the allow-list is in protocol.ts) |
 * | POST | /api/pianos/:id/rotate | a new secret, two-phase |
 * | POST | /api/pianos/:id/revoke | no secret works any more; the tablet is sent away |
 * | DELETE | /api/pianos/:id | forgotten |
 * | GET | /api/audit?piano= | the audit log's last 50 (one piano's, or all) |
 */
export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch {
      return error(500, 'server', 'Something went wrong.');
    }
  },
} satisfies ExportedHandler<ConsoleEnv>;
