/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import type { RelayEnv } from '../env';
import { DEFAULT_NAME } from '../shared/db';
import { sha256Hex } from '../shared/hash';
import { clientAddress, error, json, readJson, tooMany } from '../shared/http';
import { newSecret, normalizeCode } from '../shared/ids';
import { isObject, text } from '../shared/protocol';

/** Stands in for a code that can't be one, so a malformed code costs what a wrong one does. */
const NO_CODE = '----';

/**
 * `POST /api/enrol {code, name?, model?}`: a tablet trades a one-time code from the console for its
 * piano's id and a bearer secret, shown once and kept only as its hash.
 *
 * At most [ENROL_LIMIT] a minute per address. The code is claimed in one D1 transaction: it must be
 * unused and unexpired, and its piano must still exist and never have been enrolled (audit delta 3:
 * a code can make a new piano's first secret, never re-key or un-revoke one); the piano takes the new
 * secret's hash (and the tablet's name, while it still has the default one), then the audit row and the
 * code's use follow that very write (the piano holds this hash). A wrong, expired, used or malformed
 * code, or one naming an enrolled piano, gets the same 404 after the same work: a secret made and
 * hashed, the same three statements run.
 */
export async function enrol(request: Request, env: RelayEnv, now: () => number = Date.now): Promise<Response> {
  const address = clientAddress(request);
  const gate = await env.ENROL_LIMIT.limit({ key: `enrol:${address}` });
  if (!gate.success) return tooMany('Too many tries. Try again in a minute.');
  const body = await readJson(request, 4096);
  if (!body.ok) return body.response;
  if (!isObject(body.value)) return error(400, 'body', 'The body must be an object.');
  const extra = Object.keys(body.value).find((k) => !['code', 'name', 'model'].includes(k));
  if (extra !== undefined) return error(400, 'field', `Unknown field "${extra.slice(0, 40)}".`);
  const code = normalizeCode(body.value.code);
  const name = text(body.value.name, 60);
  const model = text(body.value.model, 80);

  const secret = newSecret();
  const hash = await sha256Hex(secret);
  const at = now();
  const probe = code ?? NO_CODE;
  const detail = JSON.stringify({ name, model, address });
  const db = env.DB;
  const valid = 'code = ? AND used_at IS NULL AND expires_at > ?';
  // The first statement claims a piano never enrolled; the other two act only when it did (the piano now
  // holds this request's hash, which no other request can have).
  const claimed = 'EXISTS (SELECT 1 FROM pianos WHERE pianos.id = enrol_codes.piano_id AND pianos.secret_hash = ?)';
  const results = await db.batch<{ piano_id: string }>([
    db
      .prepare(
        `UPDATE pianos SET secret_hash = ?, pending_secret_hash = NULL, pending_until = NULL, enrolled_at = ?, revoked_at = NULL, online = 0,
           name = CASE WHEN name = ? AND ? IS NOT NULL THEN ? ELSE name END
         WHERE id = (SELECT piano_id FROM enrol_codes WHERE ${valid}) AND enrolled_at IS NULL AND secret_hash IS NULL`,
      )
      .bind(hash, at, DEFAULT_NAME, name, name, probe, at),
    db
      .prepare(
        `INSERT INTO audit_log (at, actor, piano_id, action, detail)
         SELECT ?, 'tablet', piano_id, 'enrol', ? FROM enrol_codes
         WHERE ${valid} AND ${claimed}`,
      )
      .bind(at, detail, probe, at, hash),
    db
      .prepare(
        `UPDATE enrol_codes SET used_at = ?
         WHERE ${valid} AND ${claimed}
         RETURNING piano_id`,
      )
      .bind(at, probe, at, hash),
  ]);
  const pianoId = results[2]?.results?.[0]?.piano_id;
  if (!code || !pianoId) return error(404, 'code', "That code isn't right, or it has expired. Make a new one in the console.");

  const url = new URL(request.url);
  const publicHost = env.PUBLIC_HOST || url.host;
  return json(200, { pianoId, secret, host: url.host, panelUrl: `${url.protocol}//${publicHost}/p/${pianoId}/` });
}
