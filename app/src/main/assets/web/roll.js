/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The roll and the keyboard strip of the panel's views (BUILD_SPEC.md › v1.13 — M32): the app's NoteCanvas, KeyLayout
// and KeyboardStrip drawn from the notes' typed arrays (wire.js). Paper roll: rounded bars through a tracker bar a
// third up; falling notes: square blocks meeting the strip. Upcoming notes are the secondary grey and brighten to the
// content colour as they sound (12 steps over 120 ms, a cut with reduced motion); the left hand's bars are outlined,
// and with Hand colours on each hand takes its colour. Nothing is allocated per note or per frame. The numbers below
// are the app's (WebAssetsTest pins them to the Kotlin constants).

export const ROLL = {
  PX_PER_SECOND: 120,
  TRACKER_FROM_BOTTOM: 1 / 3,
  INSET: 1,
  MIN_HEIGHT: 2,
  WHITE_KEYS: 49,
  BLACK_RATIO: 0.6,
  STRIP_HEIGHT: 44,
  BLACK_KEY_HEIGHT: 0.62,
  KEY_OUTLINE: 1.5,
  LOWEST: 24,
  KEY_COUNT: 84,
  MAX_DRAWS: 4000,
  MAX_BACKOFF_MS: 30000,
  FLIP_MS: 120,
  RAMP_STEPS: 12,
  HAND_SOUNDING_MIX: 0.45,
  EDGE_MS: 30,
  NUMERALS_TALL: 3,
  NUMERAL_MIN_WIDTH: 0.75,
  CHORD_INSET: 4,
  CHORD_PAD: 4,
  MAX_CHORD_DRAWS: 64,
};

const BLACK_IN_OCTAVE = [false, true, false, true, false, false, true, false, true, false, true, false];
const NO_KEY = 255;
const LEFT = 1;

/** Where each of the 84 keys sits across [width]: 49 whites side by side, blacks 0.6 as wide on the joins. */
export function keyLayout(width) {
  const left = new Float32Array(ROLL.KEY_COUNT);
  const right = new Float32Array(ROLL.KEY_COUNT);
  const black = new Uint8Array(ROLL.KEY_COUNT);
  const white = width / ROLL.WHITE_KEYS;
  let whites = 0;
  for (let i = 0; i < ROLL.KEY_COUNT; i++) {
    if (BLACK_IN_OCTAVE[(ROLL.LOWEST + i) % 12]) {
      const join = whites * white;
      black[i] = 1;
      left[i] = join - (white * ROLL.BLACK_RATIO) / 2;
      right[i] = join + (white * ROLL.BLACK_RATIO) / 2;
    } else {
      left[i] = whites * white;
      right[i] = (whites + 1) * white;
      whites++;
    }
  }
  return { width, white, left, right, black };
}

/** How bright a note is at [now]: 0 upcoming, RAMP_STEPS sounding, easing over [flip] ms both ways (0: a cut). */
export function rampLevel(now, start, end, flip) {
  let bright;
  if (now < start) bright = 0;
  else if (now < end) bright = flip === 0 ? 1 : Math.min(1, (now - start) / flip);
  else bright = flip === 0 ? 0 : Math.max(0, 1 - (now - end) / flip);
  return Math.floor(bright * ROLL.RAMP_STEPS + 0.5);
}

/** [a] to [b] ("#rrggbb") in RAMP_STEPS + 1 css colours. */
export function ramp(a, b) {
  const pa = rgb(a);
  const pb = rgb(b);
  const out = new Array(ROLL.RAMP_STEPS + 1);
  for (let k = 0; k <= ROLL.RAMP_STEPS; k++) out[k] = css(mix(pa, pb, k / ROLL.RAMP_STEPS));
  return out;
}

export function rgb(color) {
  const m = /^\s*#([0-9a-f]{6})\s*$/i.exec(color || '');
  if (!m) return [128, 128, 128];
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];

export const css = (c) => `rgb(${Math.round(c[0])} ${Math.round(c[1])} ${Math.round(c[2])})`;

/** The first note starting at or after [ms] (n when none). */
export function firstAtOrAfter(starts, n, ms) {
  let lo = 0;
  let hi = n;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (starts[mid] < ms) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/** Where a frame's scan starts: the first note that can still sound at [ms], backing off at most 30 s. */
export function scanStart(notes, ms) {
  return firstAtOrAfter(notes.start, notes.n, ms - Math.min(notes.maxDuration, ROLL.MAX_BACKOFF_MS));
}

function bar(ctx, x, y, w, h, round) {
  if (round && w > 0 && h > 0 && ctx.roundRect) {
    ctx.beginPath();
    ctx.roundRect(x, y, w, h, Math.min(w / 2, h / 2));
    ctx.fill();
  } else {
    ctx.fillRect(x, y, w, h);
  }
}

function outline(ctx, x, y, w, h, round) {
  ctx.beginPath();
  if (round && w > 0 && h > 0 && ctx.roundRect) ctx.roundRect(x, y, w, h, Math.min(w / 2, h / 2));
  else ctx.rect(x, y, w, h);
  ctx.stroke();
}

/**
 * The roll for [w] × [h] CSS px. [look]: {paper, colors, hands (the notes' own, when shown), fingers, chords,
 * ramp, leftRamp, numeralFont, chordFont, flip}. Keeps per-size work (the keys, the chord names kept) between
 * frames.
 */
export function createRoll() {
  let keys = keyLayout(1);
  let kept = null;          // the chord names drawn: each clear of the one kept before it, chosen once per scale
  let keptFor = null;

  return function draw(ctx, w, h, notes, now, look) {
    const c = look.colors;
    ctx.clearRect(0, 0, w, h);
    if (!notes) return;
    if (keys.width !== w) keys = keyLayout(w);
    const pxPerMs = ROLL.PX_PER_SECOND / 1000;
    const hitY = look.paper ? h * (1 - ROLL.TRACKER_FROM_BOTTOM) : h;
    const ahead = hitY / pxPerMs;
    const behind = (h - hitY) / pxPerMs;
    // The black keys' lanes, in the surface colour.
    ctx.fillStyle = c.surface;
    for (let i = 0; i < ROLL.KEY_COUNT; i++) if (keys.black[i]) ctx.fillRect(keys.left[i], 0, keys.right[i] - keys.left[i], h);
    const drawn = notesPass(ctx, notes, now, look, keys, hitY, ahead, behind, pxPerMs, 0, ROLL.MAX_DRAWS);
    notesPass(ctx, notes, now, look, keys, hitY, ahead, behind, pxPerMs, 1, ROLL.MAX_DRAWS - drawn);
    if (look.chords && notes.m > 0) {
      if (keptFor !== notes) {
        kept = clearNames(notes, pxPerMs, look.chordFont);
        keptFor = notes;
      }
      chordNames(ctx, notes, kept, now, hitY, behind, pxPerMs, look);
    }
    if (look.paper) {
      ctx.fillStyle = c.tertiary;
      ctx.fillRect(0, hitY - 1 - 6 - 1, w, 1);
      ctx.fillStyle = c.primary;
      ctx.fillRect(0, hitY - 1, w, 2);
    }
  };
}

function notesPass(ctx, notes, now, look, keys, hitY, ahead, behind, pxPerMs, black, budget) {
  const windowStart = now - behind;
  const windowEnd = now + ahead;
  const hands = look.hands ? notes.hand : null;
  const fingers = look.fingers ? notes.finger : null;
  const numeralHeight = 0.71 * look.numeralFont;
  const numeralWidth = 0.65 * look.numeralFont;
  let drawn = 0;
  ctx.lineWidth = 1;
  for (let i = scanStart(notes, windowStart); i < notes.n; i++) {
    if (drawn >= budget) break;
    const start = notes.start[i];
    if (start > windowEnd) break;
    const end = notes.end[i];
    if (end < windowStart) continue;
    const key = notes.key[i];
    if (key === NO_KEY) continue;
    const lane = key - ROLL.LOWEST;
    if (lane < 0 || lane >= ROLL.KEY_COUNT || keys.black[lane] !== black) continue;
    const bottom = hitY - (start - now) * pxPerMs;
    const top = Math.min(hitY - (end - now) * pxPerMs, bottom - ROLL.MIN_HEIGHT);
    const width = keys.right[lane] - keys.left[lane] - 2 * ROLL.INSET;
    const left = keys.left[lane] + ROLL.INSET;
    const outlined = hands !== null && hands[i] === LEFT;
    const level = rampLevel(now, start, end, look.flip);
    const color = (outlined && look.leftRamp ? look.leftRamp : look.ramp)[level];
    if (outlined) {
      ctx.fillStyle = look.colors.elevated;
      bar(ctx, left, top, width, bottom - top, look.paper);
      ctx.strokeStyle = color;
      outline(ctx, left + 0.5, top + 0.5, width - 1, bottom - top - 1, look.paper);
    } else {
      ctx.fillStyle = color;
      bar(ctx, left, top, width, bottom - top, look.paper);
    }
    const finger = fingers ? fingers[i] : 0;
    if (finger > 0 && bottom - top >= ROLL.NUMERALS_TALL * numeralHeight && width >= ROLL.NUMERAL_MIN_WIDTH * numeralWidth) {
      // The leading edge: the bar's bottom, which meets the line first; clear of a perforation's round end.
      const baseline = bottom - 2 - (look.paper ? Math.min(width / 2, numeralHeight / 2) : 0);
      ctx.fillStyle = outlined ? look.colors.secondary : look.colors.elevated;
      ctx.font = `500 ${look.numeralFont}px ${look.sans}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText(String(Math.min(finger, 5)), left + width / 2, baseline);
    }
    drawn++;
  }
  return drawn;
}

/** The chords whose names are drawn: each clear of the one kept before it (its backing's height in time). */
function clearNames(notes, pxPerMs, font) {
  const reach = (font + 5 + 2 * ROLL.CHORD_PAD) / pxPerMs;
  const out = [];
  let clearFrom = -Infinity;
  for (let k = 0; k < notes.m; k++) {
    if (notes.chordStart[k] < clearFrom) continue;
    out.push(k);
    clearFrom = notes.chordStart[k] + reach;
  }
  return Int32Array.from(out);
}

function chordNames(ctx, notes, kept, now, hitY, behind, pxPerMs, look) {
  if (!kept.length) return;
  const lineHeight = look.chordFont + 5;
  const boxHeight = lineHeight + 2 * ROLL.CHORD_PAD;
  const reach = boxHeight / pxPerMs;
  const ahead = hitY / pxPerMs;
  const height = hitY + behind * pxPerMs;
  // The first kept name at or after the window's start.
  let lo = 0;
  let hi = kept.length;
  const from = now - behind;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (notes.chordStart[kept[mid]] < from) lo = mid + 1;
    else hi = mid;
  }
  ctx.font = `500 ${look.chordFont}px ${look.sans}`;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'alphabetic';
  let drawn = 0;
  for (let k = lo; k < kept.length && drawn < ROLL.MAX_CHORD_DRAWS; k++) {
    const i = kept[k];
    const start = notes.chordStart[i];
    if (start > now + ahead + reach) break;
    const bottom = hitY - (start - now) * pxPerMs;
    if (bottom > 0 && bottom - boxHeight < height) {
      const name = notes.chordNames[i];
      const width = ctx.measureText(name).width;
      ctx.fillStyle = look.colors.elevated;
      ctx.fillRect(ROLL.CHORD_INSET, bottom - boxHeight, width + 2 * ROLL.CHORD_PAD, boxHeight);
      ctx.fillStyle = look.colors.secondary;
      ctx.fillText(name, ROLL.CHORD_INSET + ROLL.CHORD_PAD, bottom - ROLL.CHORD_PAD - 5);
      drawn++;
    }
  }
}

/**
 * The keyboard strip ([w] × STRIP_HEIGHT): white keys on the elevated surface split by hairlines, black keys
 * tertiary; a key sounding at [now] (within 30 ms either side) in the content colour, or outlined when only the left
 * hand plays it; with Hand colours, each hand's colour mixed toward the content colour. [lit]: whether keys light
 * (while the piece plays).
 */
export function createStrip() {
  let keys = keyLayout(1);
  const right = new Uint8Array(ROLL.KEY_COUNT);
  const left = new Uint8Array(ROLL.KEY_COUNT);

  return function draw(ctx, w, h, notes, now, look, lit) {
    if (keys.width !== w) keys = keyLayout(w);
    right.fill(0);
    left.fill(0);
    if (notes && lit && now >= 0) {
      for (let i = scanStart(notes, now - ROLL.EDGE_MS); i < notes.n; i++) {
        if (notes.start[i] > now + ROLL.EDGE_MS) break;
        if (notes.end[i] < now - ROLL.EDGE_MS) continue;
        const key = notes.key[i];
        if (key === NO_KEY) continue;
        const lane = key - ROLL.LOWEST;
        if (lane < 0 || lane >= ROLL.KEY_COUNT) continue;
        if (notes.hand && look.hands && notes.hand[i] === LEFT) left[lane] = 1;
        else right[lane] = 1;
      }
    }
    const c = look.colors;
    const pressed = look.pressed;
    const pressedLeft = look.pressedLeft;
    const blackHeight = h * ROLL.BLACK_KEY_HEIGHT;
    const half = ROLL.KEY_OUTLINE / 2;
    ctx.fillStyle = c.elevated;
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = pressed;
    for (let i = 0; i < ROLL.KEY_COUNT; i++) {
      if (!keys.black[i] && right[i]) ctx.fillRect(keys.left[i], 0, keys.right[i] - keys.left[i], h);
    }
    ctx.fillStyle = c.hairline;
    for (let k = 1; k < ROLL.WHITE_KEYS; k++) ctx.fillRect(k * keys.white - 0.5, 0, 1, h);
    ctx.lineWidth = ROLL.KEY_OUTLINE;
    ctx.strokeStyle = pressedLeft;
    for (let i = 0; i < ROLL.KEY_COUNT; i++) {
      if (keys.black[i] || right[i] || !left[i]) continue;
      ctx.strokeRect(keys.left[i] + half, half, keys.right[i] - keys.left[i] - ROLL.KEY_OUTLINE, h - ROLL.KEY_OUTLINE);
    }
    for (let i = 0; i < ROLL.KEY_COUNT; i++) {
      if (!keys.black[i]) continue;
      const kw = keys.right[i] - keys.left[i];
      ctx.fillStyle = c.elevated;
      ctx.fillRect(keys.left[i] - 1, 0, kw + 2, blackHeight + 1);
      const leftOnly = left[i] && !right[i];
      ctx.fillStyle = right[i] ? pressed : c.tertiary;
      ctx.fillRect(keys.left[i], 0, kw, blackHeight);
      if (leftOnly) ctx.strokeRect(keys.left[i] + half, half, kw - ROLL.KEY_OUTLINE, blackHeight - ROLL.KEY_OUTLINE);
    }
  };
}
