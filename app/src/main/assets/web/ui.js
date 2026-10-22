/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The helpers the panel's modules share (v1.27 — M63): each written once, here, and imported by the modules that use it.
// Nothing here asks the tablet or keeps anything of the page's.

/** The page's element with [id] (index.html's own: the frame's and its sections'). */
export const $ = (id) => document.getElementById(id);

/** Runs [fn] and gives back what it gives; what it throws is kept from the caller (the frame, Settings, System), logged. */
export function guarded(fn) {
  try {
    return fn();
  } catch (e) {
    if (window.console) console.error(e);
    return undefined;
  }
}

/**
 * A button that acts once when pressed and, held, again every 70 ms after 400 ms. A click no pointer made (Enter or
 * Space, a screen reader's activation: its `detail` is 0) acts once; a pointer's own click was its press.
 */
export function holdToRepeat(button, act) {
  let delay = 0;
  let every = 0;
  const stop = () => {
    clearTimeout(delay);
    clearInterval(every);
    delay = 0;
    every = 0;
  };
  button.addEventListener('pointerdown', (event) => {
    if (button.disabled || event.button !== 0) return;
    stop();
    act();
    delay = setTimeout(() => {
      every = setInterval(() => (button.disabled ? stop() : act()), 70);
    }, 400);
  });
  for (const type of ['pointerup', 'pointerleave', 'pointercancel', 'blur']) button.addEventListener(type, stop);
  button.addEventListener('click', (event) => {
    if (event.detail === 0) act();
  });
  button.addEventListener('contextmenu', (event) => event.preventDefault());
}

/** A whole number from the piano's wire text ("70", " 110 ", "348160"), or null. */
export function whole(text) {
  if (typeof text !== 'string' && typeof text !== 'number') return null;
  const n = Number(String(text).trim());
  return Number.isFinite(n) ? Math.round(n) : null;
}

/** Whether two lists hold the same items in the same order. */
export const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

/** "0:00", "4:31", "1:02:03", as the app's clock. */
export function clock(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(s / 3600);
  const minutes = Math.floor(s / 60) % 60;
  const seconds = String(s % 60).padStart(2, '0');
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
}

export const plural = (n, one, many) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** Calls [fn] once things have been quiet for [ms]. */
export function debounce(fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
