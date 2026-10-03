/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// Now playing's views on the panel (DESIGN.md › v1.13): the score and the moving notes, as the tablet shows them, in
// step with the playback. app.js's switch chooses (v1.18 — M47): Notes, the roll alone; Score, from 900 px the score
// and the notes split by a divider (stacked, or side by side once the column is 840 px wide), its share remembered in
// this browser per arrangement, below 900 px the score alone; Art, neither (a browser that never leaves Art loads
// nothing). While the panel is immersive (the cover behind it) the roll has no card: its notes are drawn light straight
// over the backdrop and the keys as keys. The View control sets the tablet's own display settings. Frames run only
// while Now playing shows, the page is visible and the piece plays, and for 400 ms after a change; paused, one frame.
// Styles are written only through the CSSOM.

import { createClock } from './clock.js';
import { readNotes, VersionError } from './wire.js';
import { createRoll, createStrip, ramp, fade, rgb, mix, css, cssAlpha, ROLL } from './roll.js';
import { createScore } from './score.js';

/** The split's rules, as the tablet's SplitRules: stops, minimums, hiding past them, the 2 % nudge. */
const SPLIT = {
  STOPS: [1 / 3, 1 / 2, 2 / 3],
  SNAP: 12,
  HIDE: 56,
  GAP: 8,
  NUDGE: 0.02,
  SIDE_FROM: 840,
  MIN: { stacked: { score: 200, roll: 165 }, side: { score: 240, roll: 240 } },
  DEFAULT: { stacked: 1 / 3, side: 1 / 2 },
};
const SETTLE_MS = 400;
const SPLIT_KEY = 'steven-piano-split-';

function stored(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : value;
  } catch (e) {
    return fallback;
  }
}

function store(key, value) {
  try {
    localStorage.setItem(key, String(value));
  } catch (e) {
    // Private browsing: it holds for this page only.
  }
}

export function mountViews(holder, api, host) {
  const { h, chip } = host;
  const clock = createClock();
  const drawRoll = createRoll();
  const drawStrip = createStrip();
  const wide = window.matchMedia('(min-width: 900px)');
  const reducedQuery = window.matchMedia('(prefers-reduced-motion: reduce)');

  // ---- The markup -----------------------------------------------------------------------------------------------

  const viewButton = host.viewButton;   // the View control, beside app.js's Art · Notes · Score
  const scorePane = h('div', { class: 'pane pane-score', id: 'now-pane-score' });
  const rollCanvas = h('canvas', { class: 'roll-canvas', role: 'img', 'aria-label': 'Notes, as they play' });
  const stripCanvas = h('canvas', { class: 'strip-canvas', 'aria-hidden': 'true' });
  const rollPane = h('div', { class: 'pane pane-roll', id: 'now-pane-roll' }, rollCanvas, stripCanvas);
  const handle = h('div', {
    class: 'split-handle',
    role: 'separator',
    tabindex: '0',
    'aria-label': 'Sheet music and notes divider',
    'aria-controls': 'now-pane-score now-pane-roll',
    'aria-valuemin': '0',
    'aria-valuemax': '100',
  });
  const panes = h('div', { class: 'views-panes' }, scorePane, handle, rollPane);
  const message = h('p', { class: 'note views-note', role: 'status', hidden: true });
  holder.replaceChildren(panes, message);

  const score = createScore(scorePane, api, host);

  // ---- State ----------------------------------------------------------------------------------------------------

  let state = null;
  let rev = null;
  let notes = null;
  let loadingRev = null;
  let showing = false;          // Now playing is the section shown
  let axis = 'stacked';
  let share = { stacked: null, side: null };
  for (const a of ['stacked', 'side']) {
    const value = Number(stored(SPLIT_KEY + a, NaN));
    share[a] = Number.isFinite(value) ? Math.min(Math.max(value, 0), 1) : null;
  }
  let colours = null;
  let look = null;
  let immersive = !!(host.immersive && host.immersive());
  let reduced = reducedQuery.matches;
  clock.setReduced(reduced);
  score.setReduced(reduced);
  const sizes = { roll: { w: 0, h: 0 }, strip: { w: 0, h: 0 }, panes: { w: 0, h: 0 } };

  // ---- Colours: the page's tokens, read again when the appearance changes ---------------------------------------

  function readColours() {
    const cs = getComputedStyle(document.documentElement);
    const v = (name) => cs.getPropertyValue(name).trim();
    colours = {
      surface: v('--surface'), elevated: v('--elevated'), hairline: v('--hairline'), primary: v('--primary'),
      secondary: v('--secondary'), tertiary: v('--tertiary'), sounding: v('--sounding'),
      handLeft: v('--hand-left'), handRight: v('--hand-right'), sans: v('--sans') || 'system-ui, sans-serif',
      // Over the cover the roll is the camera body's whatever the appearance (v1.18 — M47).
      ink: {
        surface: v('--ink-surface'), elevated: v('--ink-elevated'), primary: v('--ink-primary'),
        sounding: v('--ink-sounding'), handLeft: v('--ink-hand-left'), handRight: v('--ink-hand-right'),
      },
    };
    score.setColours(colours);
    look = null;
    kick();
  }

  /** How the roll looks now: its style, the hands, fingering and chords sent, Hand colours, over the cover or not. */
  function lookNow() {
    if (look) return look;
    const display = (state && state.display) || {};
    const handsKnown = !!(notes && notes.hand);
    const tinted = !!display.handColours && handsKnown;
    if (immersive) {
      // No card: the notes light over the backdrop (the right hand, or every note when the hands aren't known, at 92 %,
      // the left at 50 %; with Hand colours the ink's two), a sounding key yellow, a light line where notes land.
      const ink = colours.ink;
      const light = rgb(ink.primary);
      const right = rgb(ink.handRight);
      const left = rgb(ink.handLeft);
      look = {
        immersive: true,
        paper: display.rollStyle !== 'falling',
        colors: { ...colours, surface: ink.surface, elevated: ink.elevated, primary: ink.primary },
        hands: handsKnown,
        fingers: !!(notes && notes.finger),
        chords: !!(notes && notes.m > 0),
        ramp: tinted ? ramp(ink.handRight, css(mix(right, light, ROLL.HAND_SOUNDING_MIX))) : fade(light, ROLL.LIGHT_RIGHT, 1),
        leftRamp: tinted ? ramp(ink.handLeft, css(mix(left, light, ROLL.HAND_SOUNDING_MIX))) : fade(light, ROLL.LIGHT_LEFT, ROLL.LIGHT_LEFT_SOUNDING),
        pressed: tinted ? css(mix(right, light, ROLL.HAND_SOUNDING_MIX)) : ink.sounding,
        pressedLeft: tinted ? css(mix(left, light, ROLL.HAND_SOUNDING_MIX)) : ink.sounding,
        line: cssAlpha(light, ROLL.LIGHT_LINE),
        keyWhite: ink.primary,
        keyBlack: ink.surface,
        chordBox: cssAlpha(rgb(ink.surface), ROLL.CHORD_SHADE),
        numeralFont: 11,
        chordFont: 11,
        sans: colours.sans,
        flip: reduced ? 0 : ROLL.FLIP_MS,
      };
      return look;
    }
    const primary = rgb(colours.primary);
    const right = rgb(colours.handRight);
    const left = rgb(colours.handLeft);
    look = {
      paper: display.rollStyle !== 'falling',
      colors: colours,
      hands: handsKnown,
      fingers: !!(notes && notes.finger),
      chords: !!(notes && notes.m > 0),
      ramp: tinted ? ramp(colours.handRight, css(mix(right, primary, ROLL.HAND_SOUNDING_MIX))) : ramp(colours.secondary, colours.primary),
      leftRamp: tinted ? ramp(colours.handLeft, css(mix(left, primary, ROLL.HAND_SOUNDING_MIX))) : null,
      pressed: tinted ? css(mix(right, primary, ROLL.HAND_SOUNDING_MIX)) : colours.primary,
      pressedLeft: tinted ? css(mix(left, primary, ROLL.HAND_SOUNDING_MIX)) : colours.primary,
      numeralFont: 11,
      chordFont: 11,
      sans: colours.sans,
      flip: reduced ? 0 : ROLL.FLIP_MS,
    };
    return look;
  }

  new MutationObserver(readColours).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', readColours);
  reducedQuery.addEventListener('change', () => {
    reduced = reducedQuery.matches;
    clock.setReduced(reduced);
    score.setReduced(reduced);
    look = null;
    kick();
  });

  // ---- Which views show -----------------------------------------------------------------------------------------

  function hasPiece() {
    return !!(state && state.player.piece && state.player.views);
  }

  /** What shows, as app.js's switch says: the notes; the score (from 900 px with the notes, the split); or the art. */
  function layout() {
    const piece = hasPiece();
    const view = host.view();
    const on = piece && view !== 'art';
    holder.hidden = !on;
    holder.closest('.now').classList.toggle('views-on', on);
    viewButton.hidden = !on;
    panes.dataset.mode = view !== 'score' ? 'notes' : wide.matches ? 'split' : 'score';
    applySplit();
    if (!on) closeMenu();
  }

  wide.addEventListener('change', () => {
    layout();
    kick();
  });

  // ---- The split ------------------------------------------------------------------------------------------------

  function shareNow() {
    const s = share[axis];
    return s === null ? SPLIT.DEFAULT[axis] : s;
  }

  function length() {
    return (axis === 'side' ? sizes.panes.w : sizes.panes.h) - SPLIT.GAP;
  }

  /** The share [s] as the panes take it: 0 and 1 hide one, otherwise each keeps its minimum. */
  function held(s) {
    if (s <= 0 || s >= 1) return s <= 0 ? 0 : 1;
    const room = length();
    const min = SPLIT.MIN[axis];
    if (room <= 0) return s;
    const lo = min.score / room;
    const hi = 1 - min.roll / room;
    return lo > hi ? 0.5 : Math.min(Math.max(s, lo), hi);
  }

  function stateText(s) {
    if (s <= 0) return 'sheet music hidden';
    if (s >= 1) return 'notes hidden';
    return `sheet music ${Math.round(s * 100)} percent`;
  }

  let live = null;   // the share while a finger or the mouse moves the divider

  function applySplit() {
    const split = panes.dataset.mode === 'split';
    const s = live !== null ? live : held(shareNow());
    panes.dataset.axis = axis;
    panes.style.setProperty('--split', String(s));
    const hidden = !split ? null : s <= 0 ? 'score' : s >= 1 ? 'roll' : null;
    if (hidden) panes.dataset.hidden = hidden;
    else delete panes.dataset.hidden;
    handle.hidden = !split;
    handle.setAttribute('aria-orientation', axis === 'side' ? 'vertical' : 'horizontal');
    handle.setAttribute('aria-valuenow', String(Math.round(s * 100)));
    handle.setAttribute('aria-valuetext', stateText(s));
  }

  function commit(s) {
    share[axis] = s;
    store(SPLIT_KEY + axis, s);
    live = null;
    applySplit();
    kick();
  }

  /** On release: a stop within 12 px, hiding 56 px past a minimum, else the share held to the minimums. */
  function settle(raw) {
    const room = length();
    if (room <= 0) return shareNow();
    const px = raw * room;
    const min = SPLIT.MIN[axis];
    if (px < min.score - SPLIT.HIDE) return 0;
    if (room - px < min.roll - SPLIT.HIDE) return 1;
    for (const stop of SPLIT.STOPS) if (Math.abs(px - stop * room) <= SPLIT.SNAP) return held(stop);
    return held(raw);
  }

  let dragging = false;
  let dragFrom = 0;
  handle.addEventListener('pointerdown', (event) => {
    if (panes.dataset.mode !== 'split') return;
    dragging = true;
    handle.setPointerCapture(event.pointerId);
    const box = panes.getBoundingClientRect();
    dragFrom = axis === 'side' ? box.left : box.top;
    event.preventDefault();
  });
  handle.addEventListener('pointermove', (event) => {
    if (!dragging) return;
    const room = length();
    if (room <= 0) return;
    const at = (axis === 'side' ? event.clientX : event.clientY) - dragFrom - SPLIT.GAP / 2;
    live = Math.min(Math.max(at / room, 0), 1);
    applySplit();
    kick();
  });
  const release = (event) => {
    if (!dragging) return;
    dragging = false;
    if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    if (live !== null) commit(settle(live));
  };
  handle.addEventListener('pointerup', release);
  handle.addEventListener('pointercancel', release);
  handle.addEventListener('dblclick', () => commit(SPLIT.DEFAULT[axis]));
  handle.addEventListener('keydown', (event) => {
    const s = held(shareNow());
    const forward = axis === 'side' ? 'ArrowRight' : 'ArrowDown';
    const back = axis === 'side' ? 'ArrowLeft' : 'ArrowUp';
    let next = null;
    if (event.key === forward) next = held(Math.min(1, s + SPLIT.NUDGE));
    else if (event.key === back) next = held(Math.max(0, s - SPLIT.NUDGE));
    else if (event.key === 'PageDown') next = SPLIT.STOPS.find((stop) => stop > s + 0.001) ?? 1;
    else if (event.key === 'PageUp') next = [...SPLIT.STOPS].reverse().find((stop) => stop < s - 0.001) ?? 0;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = 1;
    else if (event.key === 'Enter') next = SPLIT.DEFAULT[axis];
    if (next === null) return;
    event.preventDefault();
    commit(next <= 0 || next >= 1 ? next : held(next));
  });

  // ---- The View control: the tablet's own display settings ------------------------------------------------------

  let menu = null;

  function closeMenu() {
    if (!menu) return;
    menu.remove();
    menu = null;
    viewButton.setAttribute('aria-expanded', 'false');
  }

  async function set(change) {
    try {
      await api.settings(change);
    } catch (e) {
      host.failed(e);
    }
  }

  function switchRow(label, on, key, note) {
    const button = h('button', { class: 'switch', role: 'switch', type: 'button', 'aria-checked': on ? 'true' : 'false', 'aria-label': label });
    button.addEventListener('click', () => set({ [key]: !on }));
    return h('div', { class: 'setting' }, h('div', { class: 'label' }, label, note ? h('span', { class: 'meta', text: note }) : null), button);
  }

  function renderMenu() {
    if (!menu || !state) return;
    const d = state.display || {};
    menu.replaceChildren(
      h('p', { class: 'eyebrow', text: 'Notes' }),
      h('div', { class: 'chips', role: 'group', 'aria-label': 'Notes' },
        chip('Paper roll', d.rollStyle !== 'falling', () => set({ noteDisplay: 'paperRoll' })),
        chip('Falling notes', d.rollStyle === 'falling', () => set({ noteDisplay: 'falling' }))),
      switchRow('Fingering', !!d.fingering, 'fingering'),
      switchRow('Chord names', !!d.chordNames, 'chordNames'),
      switchRow('Hand colours', !!d.handColours, 'handColours', 'Each hand in its own colour on the notes and the keys.'),
    );
  }

  viewButton.addEventListener('click', (event) => {
    event.stopPropagation();
    if (menu) {
      closeMenu();
      return;
    }
    menu = h('div', { class: 'view-menu', role: 'dialog', 'aria-label': 'View' });
    renderMenu();
    document.body.append(menu);
    const box = viewButton.getBoundingClientRect();
    menu.style.left = `${Math.max(8, Math.min(box.right - menu.offsetWidth, window.innerWidth - menu.offsetWidth - 8))}px`;
    menu.style.top = `${Math.min(box.bottom + 4, Math.max(8, window.innerHeight - menu.offsetHeight - 8))}px`;
    viewButton.setAttribute('aria-expanded', 'true');
    const first = menu.querySelector('button');
    if (first) first.focus();
  });
  document.addEventListener('click', (event) => {
    if (menu && !menu.contains(event.target) && event.target !== viewButton) closeMenu();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu) {
      closeMenu();
      viewButton.focus();
    }
  });

  // ---- Sizes ----------------------------------------------------------------------------------------------------

  function fit(canvas, w, ht) {
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(w * scale));
    canvas.height = Math.max(1, Math.round(ht * scale));
  }

  const observer = new ResizeObserver((entries) => {
    for (const entry of entries) {
      const box = entry.contentRect;
      if (entry.target === panes) {
        sizes.panes = { w: box.width, h: box.height };
        const next = box.width >= SPLIT.SIDE_FROM ? 'side' : 'stacked';
        if (next !== axis) axis = next;
        applySplit();
      } else if (entry.target === rollCanvas) {
        sizes.roll = { w: box.width, h: box.height };
        fit(rollCanvas, box.width, box.height);
      } else if (entry.target === stripCanvas) {
        sizes.strip = { w: box.width, h: box.height };
        fit(stripCanvas, box.width, box.height);
      } else if (entry.target === scorePane) {
        score.resize(box.width, box.height);
      }
    }
    kick();
  });
  for (const node of [panes, rollCanvas, stripCanvas, scorePane]) observer.observe(node);

  // ---- Frames ---------------------------------------------------------------------------------------------------

  let raf = 0;
  let until = 0;

  function active() {
    return showing && !document.hidden && hasPiece() && !holder.hidden;
  }

  /** Frames for [ms] more (a change, a seek, a resize), or while the piece plays. */
  function kick(ms = SETTLE_MS) {
    until = Math.max(until, performance.now() + ms);
    if (!raf && active()) raf = requestAnimationFrame(loop);
  }

  function loop() {
    raf = 0;
    if (!active()) return;
    drawFrame();
    if (clock.running || dragging || performance.now() < until) raf = requestAnimationFrame(loop);
  }

  function drawFrame() {
    if (!colours) readColours();
    const now = clock.now();
    const l = lookNow();
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    if (sizes.roll.w > 0 && sizes.roll.h > 0 && rollPane.offsetParent !== null) {
      const ctx = rollCanvas.getContext('2d');
      ctx.setTransform(scale, 0, 0, scale, 0, 0);
      drawRoll(ctx, sizes.roll.w, sizes.roll.h, notes, now, l);
    }
    if (sizes.strip.w > 0 && sizes.strip.h > 0 && rollPane.offsetParent !== null) {
      const ctx = stripCanvas.getContext('2d');
      ctx.setTransform(scale, 0, 0, scale, 0, 0);
      drawStrip(ctx, sizes.strip.w, sizes.strip.h, notes, now, l, clock.running);
    }
    if (scorePane.offsetParent !== null) score.frame(now);
  }

  document.addEventListener('visibilitychange', () => kick());

  // ---- The notes ------------------------------------------------------------------------------------------------

  async function loadNotes(r) {
    loadingRev = r;
    try {
      const answer = await api.notes(r);
      if (loadingRev !== r) return;
      notes = readNotes(answer.buffer);
      look = null;
      say('');
      score.setNotes(notes, r);
      if (state && state.player.views && !state.player.views.score) score.tooLarge();
      kick();
    } catch (e) {
      if (loadingRev !== r) return;
      loadingRev = null;
      if (e instanceof VersionError) say(e.message);
      else if (e && e.status === 409) rev = null;   // the next state names the revision the tablet has
      else if (e && e.status === 413) say('This piece has too many notes to show here.');
      else if (e && e.status !== 404 && e.status !== 401) say(e.message || 'The notes could not be read.');
    }
  }

  function say(text) {
    message.textContent = text || '';
    message.hidden = !text;
  }

  // ---- From the app ---------------------------------------------------------------------------------------------

  return {
    /** A state message: the clock's sample, the revision (new notes), the display settings. */
    state(next) {
      state = next;
      const player = next.player;
      const piece = player.piece;
      clock.sample({ positionMs: player.positionMs, at: player.at, playing: player.status === 'playing', tempoPct: player.tempoPct, durationMs: piece ? piece.durationMs : 0 });
      const views = player.views;
      if (!piece || !views) {
        rev = null;
        notes = null;
        loadingRev = null;
        score.clear();
      } else if (views.rev !== rev) {
        rev = views.rev;
        loadNotes(rev);
      }
      look = null;
      layout();
      renderMenu();
      kick();
    },
    /** A progress message (once a second while playing, and at once when the position jumps). */
    progress(message) {
      if (!state) return;
      const piece = state.player.piece;
      clock.sample({ positionMs: message.positionMs, at: message.at, playing: message.playing !== undefined ? message.playing : state.player.status === 'playing', tempoPct: message.tempoPct, durationMs: piece ? piece.durationMs : 0 });
      kick();
    },
    /** The switch's choice changed (Art · Notes · Score), or the window crossed 900 px. */
    layout() {
      layout();
      kick();
    },
    /** Whether the panel is immersive (the cover behind it): the roll is drawn for it, or on its card. */
    immersive(on) {
      if (on === immersive) return;
      immersive = on;
      look = null;
      kick();
    },
    /** Whether Now playing is the section shown. */
    show(on) {
      showing = on;
      if (!on) closeMenu();
      kick();
    },
    /** A seek made here: the views go there at once. */
    seeked(ms) {
      clock.jump(ms);
      kick();
    },
    /** The clock's position, for the scrubber and the time between messages. */
    now() {
      return clock.now();
    },
  };
}
