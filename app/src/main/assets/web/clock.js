/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The playback clock of the panel's views (BUILD_SPEC.md › v1.13 — M32). The tablet sends samples: where the piece
// is (positionMs) and when that was on its own monotonic clock (at, ms), whether it runs and at what tempo. The
// smallest gap seen between a sample's arrival and its `at` (over the last 30) is the two clocks' offset with the
// least delay in it, so the network's jitter never moves the picture. A new sample far from the prediction (a seek,
// a tempo change) snaps; a near one folds into a correction that fades over a quarter of a second, which also gives
// a resume the app's ease-in. With reduced motion every change is a cut.

const LAGS = 30;
const SNAP_MS = 120;
const EASE_MS = 250;

export function createClock() {
  const lags = [];
  let known = false;
  let offset = 0;        // this page's time minus the tablet's, at the least delay seen
  let positionMs = 0;
  let at = 0;             // the tablet's time of positionMs
  let running = false;
  let tempo = 100;
  let durationMs = 0;
  let correction = 0;     // added to the prediction, fading out from correctionAt
  let correctionAt = 0;
  let reduced = false;

  function predicted(t) {
    if (!known) return 0;
    if (!running) return positionMs;
    return positionMs + ((t - offset - at) * tempo) / 100;
  }

  /** Where the piece is at this page's time [t] (performance.now()), never past its end. */
  function now(t = performance.now()) {
    let ms = predicted(t);
    if (correction !== 0) {
      const left = 1 - (t - correctionAt) / EASE_MS;
      if (left > 0) ms += correction * left;
      else correction = 0;
    }
    return durationMs > 0 ? Math.min(ms, durationMs) : ms;
  }

  /** A sample from the tablet: {positionMs, at, playing, tempoPct, durationMs}. */
  function sample(s) {
    const t = performance.now();
    const before = known ? now(t) : null;
    if (typeof s.at === 'number' && s.at > 0) {
      lags.push(t - s.at);
      if (lags.length > LAGS) lags.shift();
      offset = Math.min(...lags);
      at = s.at;
    } else {
      at = t - offset;   // an older tablet: the sample is now
    }
    positionMs = s.positionMs;
    running = !!s.playing;
    if (typeof s.tempoPct === 'number' && s.tempoPct > 0) tempo = s.tempoPct;
    if (typeof s.durationMs === 'number') durationMs = s.durationMs;
    known = true;
    const after = predicted(t);
    if (before !== null && !reduced && Math.abs(before - after) < SNAP_MS) {
      correction = before - after;
      correctionAt = t;
    } else {
      correction = 0;
    }
  }

  /** A seek made here: the picture goes there at once, until the tablet's own sample says otherwise. */
  function jump(ms) {
    const t = performance.now();
    positionMs = ms;
    at = t - offset;
    correction = 0;
  }

  return {
    now,
    sample,
    jump,
    get running() { return running && known; },
    get tempo() { return tempo; },
    setReduced(on) { reduced = !!on; if (reduced) correction = 0; },
  };
}
