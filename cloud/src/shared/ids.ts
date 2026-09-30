/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

/** Piano ids, enrolment codes and secrets: all from the platform's cryptographic random source. */

/** RFC 4648 base32 in lower case: a piano id's letters. */
const BASE32 = 'abcdefghijklmnopqrstuvwxyz234567';

/** A piano id: 12 characters of base32 (60 bits). */
export const PIANO_ID = /^[a-z2-7]{12}$/;

/** An enrolment code's 32 letters: no I, O, 0 or 1, which read alike. */
export const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** An enrolment code as shown and stored: XXXX-XXXX (40 bits). */
export const ENROL_CODE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$/;

/** A tablet's bearer secret: 32 random bytes, base64url without padding (43 characters). */
export const SECRET = /^[A-Za-z0-9_-]{43}$/;

/** How long an enrolment code lasts. */
export const CODE_LIFE_MS = 15 * 60 * 1000;

/** How long a rotation's new secret is accepted before the tablet confirms it. */
export const PENDING_LIFE_MS = 10 * 60 * 1000;

function randomBytes(n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  return bytes;
}

/** [n] symbols of a 32-symbol [alphabet]: each byte's low five bits (256 is a multiple of 32, so uniform). */
function symbols(n: number, alphabet: string): string {
  let out = '';
  for (const b of randomBytes(n)) out += alphabet[b & 31];
  return out;
}

export function newPianoId(): string {
  return symbols(12, BASE32);
}

export function newEnrolCode(): string {
  const s = symbols(8, CODE_ALPHABET);
  return `${s.slice(0, 4)}-${s.slice(4)}`;
}

/** A code as someone typed it (any case, spaces or a dash), in its stored form; null when it can't be one. */
export function normalizeCode(input: unknown): string | null {
  if (typeof input !== 'string' || input.length > 32) return null;
  const bare = input.toUpperCase().replace(/[\s-]/g, '');
  if (bare.length !== 8) return null;
  for (const c of bare) if (!CODE_ALPHABET.includes(c)) return null;
  return `${bare.slice(0, 4)}-${bare.slice(4)}`;
}

export function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function base64urlDecode(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) return null;
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
  try {
    const binary = atob(padded);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

export function newSecret(): string {
  return base64url(randomBytes(32));
}

/** `Authorization: Bearer <pianoId>.<secret>`, or null when it isn't exactly that. */
export function parseBearer(header: string | null): { pianoId: string; secret: string } | null {
  if (!header) return null;
  const match = /^Bearer ([a-z2-7]{12})\.([A-Za-z0-9_-]{43})$/.exec(header.trim());
  return match ? { pianoId: match[1]!, secret: match[2]! } : null;
}

/** A random unsigned 32-bit number. */
export function randomU32(): number {
  return crypto.getRandomValues(new Uint32Array(1))[0]!;
}
