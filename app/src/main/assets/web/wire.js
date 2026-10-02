/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The views' three binary formats (BUILD_SPEC.md › v1.13 — M32; score/ScoreDisplayList.kt writes them, and
// NowWireTest reads them as this file does). Little-endian; every section 4-byte aligned. Every count and length is
// checked against the bytes before a typed array is made over them, and a version this page doesn't know asks for
// a reload instead of guessing.

export const NOTES_MAGIC = 0x544E5053;
export const INDEX_MAGIC = 0x49535053;
export const PAGE_MAGIC = 0x50535053;
export const VERSION = 1;

export const OP = { RECT: 1, GLYPH: 2, TEXT: 3, QUAD: 4, CURVE: 5, CLIP: 6, END: 7 };
const OP_WORDS = [0, 5, 4, 5, 9, 7, 3, 1];

/** Upper bounds a reader holds the counts to, whatever the header says (the tablet's own caps, with room). */
const MAX_NOTES = 200000;
const MAX_CHORDS = 20000;
const MAX_SYSTEMS = 100000;
const MAX_PAGE_SYSTEMS = 64;
const BAR_POINTS = 9;
const BAR_WORDS = 2 + 2 * BAR_POINTS;
const SYSTEM_WORDS = 10;
const HEAD_WORDS = 5;

export class WireError extends Error {}

/** A reload is the answer to a version this page doesn't know. */
export class VersionError extends WireError {}

function header(buffer, magic, bytes) {
  if (!(buffer instanceof ArrayBuffer)) throw new WireError('Not bytes.');
  if (buffer.byteLength < bytes) throw new WireError('Cut short.');
  const view = new DataView(buffer);
  if (view.getUint32(0, true) !== magic) throw new WireError('Not this format.');
  if (view.getUint16(4, true) !== VERSION) throw new VersionError('Reload the page: the tablet has a newer version.');
  return view;
}

/** That [bytes] from [offset] lie within [buffer]. */
function need(buffer, offset, bytes) {
  if (!Number.isInteger(offset) || !Number.isInteger(bytes) || offset < 0 || bytes < 0 || offset + bytes > buffer.byteLength) {
    throw new WireError('Cut short.');
  }
}

const align = (n) => (n + 3) & ~3;

const decoder = new TextDecoder('utf-8', { fatal: true });

/** [count] strings ending at [ends] (cumulative) in the [bytes] after [offset]. */
function strings(buffer, offset, ends, count, total) {
  need(buffer, offset, total);
  const bytes = new Uint8Array(buffer, offset, total);
  const out = new Array(count);
  let from = 0;
  for (let k = 0; k < count; k++) {
    const to = ends[k];
    if (to < from || to > total) throw new WireError('A string runs past its table.');
    try {
      out[k] = decoder.decode(bytes.subarray(from, to));
    } catch (e) {
      throw new WireError('A string is not UTF-8.');
    }
    from = to;
  }
  return out;
}

/**
 * Notes ("SPNT"): each note's start and end (ms), its key as played (255: none), the hands and fingers when sent,
 * the chords' starts and names.
 */
export function readNotes(buffer) {
  const v = header(buffer, NOTES_MAGIC, 32);
  const flags = v.getUint16(6, true);
  const rev = v.getUint32(8, true);
  const n = v.getUint32(12, true);
  const m = v.getUint32(16, true);
  const durationMs = v.getUint32(20, true);
  const nameBytes = v.getUint32(24, true);
  if (n > MAX_NOTES || m > MAX_CHORDS) throw new WireError('Too many notes.');
  let at = 32;
  need(buffer, at, n * 8);
  const start = new Uint32Array(buffer, at, n);
  const end = new Uint32Array(buffer, at + n * 4, n);
  at += n * 8;
  need(buffer, at, align(n));
  const key = new Uint8Array(buffer, at, n);
  at += align(n);
  let hand = null;
  if (flags & 1) {
    need(buffer, at, align(n));
    hand = new Uint8Array(buffer, at, n);
    at += align(n);
  }
  let finger = null;
  if (flags & 2) {
    need(buffer, at, align(n));
    finger = new Uint8Array(buffer, at, n);
    at += align(n);
  }
  need(buffer, at, m * 8);
  const chordStart = new Uint32Array(buffer, at, m);
  const nameEnd = new Uint32Array(buffer, at + m * 4, m);
  at += m * 8;
  const chordNames = strings(buffer, at, nameEnd, m, nameBytes);
  if (at + nameBytes !== buffer.byteLength) throw new WireError('Bytes left over.');
  let maxDuration = 0;
  for (let i = 0; i < n; i++) {
    if (end[i] < start[i]) throw new WireError('A note ends before it starts.');
    if (i > 0 && start[i] < start[i - 1]) throw new WireError('The notes are out of order.');
    const d = end[i] - start[i];
    if (d > maxDuration) maxDuration = d;
  }
  return { rev, n, m, durationMs, start, end, key, hand, finger, chordStart, chordNames, chordsCut: (flags & 4) !== 0, maxDuration };
}

/** The score's index ("SPSI"): one layout's geometry (CSS px) and when each system starts. */
export function readIndex(buffer) {
  const v = header(buffer, INDEX_MAGIC, 96);
  const flags = v.getUint16(6, true);
  const u = (k) => v.getUint32(k * 4, true);
  const f = (k) => v.getFloat32(k * 4, true);
  const index = {
    engraved: (flags & 1) !== 0,
    rev: u(2),
    layoutId: u(3),
    pages: u(4),
    systemsPerPage: u(5),
    systemCount: u(6),
    pageCount: u(7),
    barsPerSystem: u(8),
    pageWidth: f(10),
    pageHeight: f(11),
    pageGap: f(12),
    slotLeft1: f(13),
    firstSystemTop: f(14),
    space: f(15),
    hair: f(16),
    cursorWidth: f(17),
    numberHeight: f(18),
    tempoSpace: f(19),
    numberFont: f(20),
    chordFont: f(21),
    numeralFont: f(22),
  };
  if (index.pages < 1 || index.pages > 2 || index.systemsPerPage < 1 || index.systemCount > MAX_SYSTEMS) throw new WireError('Not a layout.');
  if (index.pageCount !== Math.ceil(index.systemCount / index.systemsPerPage)) throw new WireError('Not a layout.');
  for (const k of ['pageWidth', 'pageHeight', 'space', 'tempoSpace', 'numberFont', 'chordFont', 'numeralFont']) {
    if (!(index[k] > 0 && index[k] < 10000)) throw new WireError('Not a layout.');
  }
  need(buffer, 96, index.systemCount * 4);
  if (96 + index.systemCount * 4 !== buffer.byteLength) throw new WireError('Bytes left over.');
  index.systemStart = new Uint32Array(buffer, 96, index.systemCount);
  return index;
}

/**
 * A page ("SPSP"): its systems, bars (each with its cursor's nine points), heads (note, tied start or -1, ops from
 * and to, system row), the ops and the strings. The op stream is walked once here: every op known, every argument
 * inside the stream, every string index in the table, every head's ops on op boundaries.
 */
export function readPage(buffer) {
  const v = header(buffer, PAGE_MAGIC, 40);
  const flags = v.getUint16(6, true);
  const layoutId = v.getUint32(8, true);
  const page = v.getUint32(12, true);
  const systemCount = v.getUint32(16, true);
  const barCount = v.getUint32(20, true);
  const headCount = v.getUint32(24, true);
  const opWords = v.getUint32(28, true);
  const stringCount = v.getUint32(32, true);
  const stringBytes = v.getUint32(36, true);
  if (systemCount > MAX_PAGE_SYSTEMS || barCount > systemCount * 64 || headCount > 1000000 || opWords > 4000000 || stringCount > 1000000) {
    throw new WireError('Too large a page.');
  }
  let at = 40;
  need(buffer, at, systemCount * SYSTEM_WORDS * 4);
  const sysU = new Uint32Array(buffer, at, systemCount * SYSTEM_WORDS);
  const sysF = new Float32Array(buffer, at, systemCount * SYSTEM_WORDS);
  at += systemCount * SYSTEM_WORDS * 4;
  const systems = [];
  let bars = 0;
  for (let s = 0; s < systemCount; s++) {
    const o = s * SYSTEM_WORDS;
    const system = {
      index: sysU[o], firstBar: sysU[o + 1], barCount: sysU[o + 2], final: sysU[o + 3] === 1,
      left: sysF[o + 4], right: sysF[o + 5], trebleTop: sysF[o + 6], bassBottom: sysF[o + 7], bandTop: sysF[o + 8], bandBottom: sysF[o + 9],
      barFrom: bars,
    };
    if (system.barCount < 1 || system.barCount > 64) throw new WireError('Not a system.');
    bars += system.barCount;
    systems.push(system);
  }
  if (bars !== barCount) throw new WireError('The bars do not add up.');
  need(buffer, at, barCount * BAR_WORDS * 4);
  const barU = new Uint32Array(buffer, at, barCount * BAR_WORDS);
  const barF = new Float32Array(buffer, at, barCount * BAR_WORDS);
  at += barCount * BAR_WORDS * 4;
  need(buffer, at, headCount * HEAD_WORDS * 4);
  const heads = new Int32Array(buffer, at, headCount * HEAD_WORDS);
  at += headCount * HEAD_WORDS * 4;
  need(buffer, at, opWords * 4);
  const opU = new Uint32Array(buffer, at, opWords);
  const opF = new Float32Array(buffer, at, opWords);
  at += opWords * 4;
  need(buffer, at, stringCount * 4);
  const ends = new Uint32Array(buffer, at, stringCount);
  at += stringCount * 4;
  const text = strings(buffer, at, ends, stringCount, stringBytes);
  if (at + stringBytes !== buffer.byteLength) throw new WireError('Bytes left over.');
  // Walk the ops: every one known and whole; its string in the table; boundaries noted for the heads.
  const boundary = new Uint8Array(opWords + 1);
  for (let k = 0; k < opWords;) {
    boundary[k] = 1;
    const op = opU[k] & 0xff;
    const words = OP_WORDS[op] || 0;
    if (!words || k + words > opWords) throw new WireError('An op is cut short.');
    if (op === OP.TEXT && opU[k + 1] >= stringCount) throw new WireError('A string is missing.');
    k += words;
  }
  boundary[opWords] = 1;
  for (let h = 0; h < headCount; h++) {
    const o = h * HEAD_WORDS;
    const from = heads[o + 2];
    const to = heads[o + 3];
    if (from < 0 || to < from || to > opWords || !boundary[from] || !boundary[to] || heads[o + 4] < 0 || heads[o + 4] >= systemCount) {
      throw new WireError('A head points outside the page.');
    }
    if (h > 0 && heads[o] < heads[o - HEAD_WORDS]) throw new WireError('The heads are out of order.');
  }
  return { layoutId, page, truncated: (flags & 1) !== 0, systems, barU, barF, heads, headCount, opU, opF, opWords, text };
}

/** The bar of page [p]'s [system] whose start is the last at or before [ms] (its first bar before then). */
export function barAt(p, system, ms) {
  let found = system.barFrom;
  for (let b = system.barFrom; b < system.barFrom + system.barCount; b++) {
    if (p.barU[b * BAR_WORDS] <= ms) found = b;
    else break;
  }
  return found;
}

/** The cursor's x in bar [b] of page [p] at [ms], through the bar's nine points, held to the bar. */
export function cursorX(p, b, ms) {
  const o = b * BAR_WORDS + 2;
  if (ms <= p.barU[o]) return p.barF[o + 1];
  for (let k = 1; k < BAR_POINTS; k++) {
    const t1 = p.barU[o + 2 * k];
    if (ms <= t1) {
      const t0 = p.barU[o + 2 * (k - 1)];
      const x0 = p.barF[o + 2 * (k - 1) + 1];
      const x1 = p.barF[o + 2 * k + 1];
      return t1 > t0 ? x0 + ((ms - t0) / (t1 - t0)) * (x1 - x0) : x1;
    }
  }
  return p.barF[o + 2 * (BAR_POINTS - 1) + 1];
}

/** Bar [b]'s start (ms) and its right edge (its tap target's end). */
export const barStart = (p, b) => p.barU[b * BAR_WORDS];
export const barRight = (p, b) => p.barF[b * BAR_WORDS + 1];

/** The heads of note [note] on page [p]: the first and one past the last, by binary search on the sorted table. */
export function headsOf(p, note) {
  let lo = 0;
  let hi = p.headCount;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (p.heads[mid * HEAD_WORDS] < note) lo = mid + 1;
    else hi = mid;
  }
  let to = lo;
  while (to < p.headCount && p.heads[to * HEAD_WORDS] === note) to++;
  return [lo, to];
}

export const HEAD = { WORDS: HEAD_WORDS };
