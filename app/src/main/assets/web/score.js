/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The score of the panel's views (BUILD_SPEC.md › v1.13 — M32): the tablet lays the piece out for this panel's size
// and sends each page as a display list (wire.js); this file only draws it. Each page shown is painted once into its
// own canvas; a transparent overlay draws the cursor and replays the sounding heads in the yellow, through the app's
// 120 ms ramp (a cut with reduced motion). Pages turn by themselves as the app's do (PageTurn); the two page buttons
// and the arrow keys look elsewhere, a Follow chip comes back, and a tap on a bar seeks there. Bravura is the
// tablet's own file, unmodified, fetched once through its session-gated route and cached for a year.

import { readIndex, readPage, OP, barAt, cursorX, barStart, barRight, headsOf, HEAD, VersionError } from './wire.js';
import { rampLevel, ramp as makeRamp, scanStart, ROLL } from './roll.js';

/** The pages kept decoded, the page fetches at once, and how long the size must settle before a new layout. */
const KEEP_PAGES = 6;
const FETCHES = 2;
const SETTLE_MS = 150;
const GRID = 16;
const TOO_LARGE = 'This score is too large to show.';

/** PageTurn.pagesShown: the cursor's page, and with two slots the next (on its last system, or at the start) or the previous. */
export function pagesShown(cursorSystem, systemCount, perPage, pages) {
  const slots = Math.min(Math.max(pages, 1), 2);
  const shown = new Array(slots).fill(-1);
  if (systemCount <= 0) return shown;
  const pageCount = Math.ceil(systemCount / perPage);
  const cursor = Math.min(Math.max(cursorSystem, 0), systemCount - 1);
  const page = Math.floor(cursor / perPage);
  if (slots === 1) {
    shown[0] = page;
    return shown;
  }
  const onLastSystem = cursor % perPage === perPage - 1 || cursor === systemCount - 1;
  const next = page + 1;
  const previous = page - 1;
  const other = next < pageCount && (onLastSystem || previous < 0) ? next : previous >= 0 ? previous : -1;
  shown[page % 2] = page;
  shown[1 - (page % 2)] = other;
  return shown;
}

/** PageTurn.browsing: [first] and, with two slots, the page after it (even pages left). */
export function browsingPages(first, pageCount, pages) {
  const slots = Math.min(Math.max(pages, 1), 2);
  const last = Math.max(pageCount - 1, 0);
  let start = Math.min(Math.max(first, 0), last);
  if (slots === 2) start -= start % 2;
  const out = [];
  for (let slot = 0; slot < slots; slot++) out.push(start + slot <= last && pageCount > 0 ? start + slot : -1);
  return out;
}

const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

export function createScore(pane, api, host) {
  const { h, glyph } = host;
  const stage = h('div', { class: 'score-stage', tabindex: '0', role: 'img', 'aria-label': 'Score' });
  const slots = [h('canvas', { class: 'score-page', 'aria-hidden': 'true' }), h('canvas', { class: 'score-page', 'aria-hidden': 'true' })];
  const spine = h('div', { class: 'score-spine', 'aria-hidden': 'true' });
  const overlay = h('canvas', { class: 'score-overlay', 'aria-hidden': 'true' });
  const note = h('p', { class: 'score-note note', role: 'status' });
  const previous = h('button', { class: 'icon-button small score-turn', type: 'button', 'aria-label': 'Previous page' }, glyph('i-chevron'));
  previous.firstChild.classList.add('flip');
  const next = h('button', { class: 'icon-button small score-turn', type: 'button', 'aria-label': 'Next page' }, glyph('i-chevron'));
  const follow = h('button', { class: 'chip score-follow', type: 'button', hidden: true }, 'Follow');
  const live = h('p', { class: 'visually-hidden', 'aria-live': 'polite' });
  stage.append(slots[0], slots[1], spine, overlay);
  pane.append(stage, h('div', { class: 'score-tools' }, previous, next), follow, note, live);

  let notes = null;
  let rev = null;
  let index = null;
  let wanted = null;           // the size asked for: "rev:wxh"
  let token = 0;
  let size = { w: 0, h: 0 };
  let settleTimer = null;
  const pages = new Map();     // page number → decoded page, most recent last
  const fetching = new Set();
  const failed = new Set();     // pages of this layout that could not be read
  let shown = [-1];
  let browsing = null;         // the first page looked at by hand, until Follow or the music's next turn
  let lastFollow = [];
  let painted = [null, null];  // what each slot's canvas holds: "layoutId:page:colours"
  let colours = null;
  let coloursKey = '';
  let fonts = [];
  let fontReady = false;
  let flip = ROLL.FLIP_MS;
  let lastCursorSystem = -1;
  const glyphs = new Map();

  const text = (cp) => {
    let s = glyphs.get(cp);
    if (s === undefined) {
      s = String.fromCodePoint(cp);
      glyphs.set(cp, s);
    }
    return s;
  };

  // The font: the tablet's Bravura, as is.
  if (typeof FontFace === 'function' && document.fonts) {
    const face = new FontFace('Bravura', `url("${api.fontUrl}")`);
    face.load().then((loaded) => {
      document.fonts.add(loaded);
      fontReady = true;
      painted = [null, null];
      paintSlots();
    }, () => {
      say('The score’s font could not be loaded.');
    });
  } else {
    fontReady = true;
  }

  function say(message) {
    note.textContent = message || '';
    note.hidden = !message;
  }

  /** The colours from the page's tokens: lines and numbers tertiary, glyphs and notes secondary, sounding yellow. */
  function setColours(c) {
    const key = [c.tertiary, c.secondary, c.primary, c.sounding].join();
    if (key === coloursKey) return;
    coloursKey = key;
    colours = { roles: [c.tertiary, c.secondary, c.tertiary, c.secondary], cursor: c.primary, ramp: makeRamp(c.secondary, c.sounding), sans: c.sans };
    painted = [null, null];
    if (index) fonts = fontsFor(index);
    paintSlots();
  }

  function fontsFor(ix) {
    const sans = colours ? colours.sans : 'system-ui, sans-serif';
    return [
      `${4 * ix.space}px Bravura`,
      `${4 * ix.tempoSpace}px Bravura`,
      `500 ${ix.numberFont}px ${sans}`,
      `400 ${ix.chordFont}px ${sans}`,
      `500 ${ix.numeralFont}px ${sans}`,
    ];
  }

  /** Draws ops [from, to) of page [p] in their roles' colours, or all in [only]. */
  function draw(ctx, p, from, to, only) {
    const u = p.opU;
    const f = p.opF;
    let font = '';
    for (let k = from; k < to;) {
      const word = u[k];
      const op = word & 255;
      const fill = only || colours.roles[(word >>> 8) & 255] || colours.roles[1];
      switch (op) {
        case OP.RECT:
          ctx.fillStyle = fill;
          ctx.fillRect(f[k + 1], f[k + 2], f[k + 3], f[k + 4]);
          k += 5;
          break;
        case OP.GLYPH:
        case OP.TEXT: {
          const style = (word >>> 16) & 255;
          const want = fonts[style] || fonts[0];
          if (want !== font) {
            ctx.font = want;
            font = want;
          }
          ctx.fillStyle = fill;
          if (op === OP.GLYPH) {
            ctx.textAlign = 'left';
            ctx.fillText(text(u[k + 1]), f[k + 2], f[k + 3]);
            k += 4;
          } else {
            ctx.textAlign = ((word >>> 24) & 255) === 1 ? 'center' : 'left';
            const max = f[k + 4];
            if (max > 0) ctx.fillText(p.text[u[k + 1]], f[k + 2], f[k + 3], max);
            else ctx.fillText(p.text[u[k + 1]], f[k + 2], f[k + 3]);
            k += 5;
          }
          break;
        }
        case OP.QUAD:
          ctx.fillStyle = fill;
          ctx.beginPath();
          ctx.moveTo(f[k + 1], f[k + 2]);
          ctx.lineTo(f[k + 3], f[k + 4]);
          ctx.lineTo(f[k + 5], f[k + 6]);
          ctx.lineTo(f[k + 7], f[k + 8]);
          ctx.closePath();
          ctx.fill();
          k += 9;
          break;
        case OP.CURVE:
          ctx.strokeStyle = fill;
          ctx.lineWidth = index ? index.hair : 1;
          ctx.beginPath();
          ctx.moveTo(f[k + 1], f[k + 2]);
          ctx.quadraticCurveTo(f[k + 3], f[k + 4], f[k + 5], f[k + 6]);
          ctx.stroke();
          k += 7;
          break;
        case OP.CLIP:
          ctx.save();
          ctx.beginPath();
          ctx.rect(-index.pageWidth, f[k + 1], 3 * index.pageWidth, f[k + 2] - f[k + 1]);
          ctx.clip();
          k += 3;
          break;
        case OP.END:
          ctx.restore();
          k += 1;
          break;
        default:
          return;   // wire.js has walked the stream: not reached
      }
    }
  }

  /** Each slot's canvas holds its page, painted once (again only for a new page, colours or font). */
  function paintSlots() {
    if (!index || !colours) {
      for (const canvas of slots) canvas.hidden = true;
      spine.hidden = true;
      return;
    }
    for (let slot = 0; slot < slots.length; slot++) {
      const canvas = slots[slot];
      const page = slot < index.pages ? shown[slot] : -1;
      const p = page >= 0 ? pages.get(page) : null;
      canvas.hidden = slot >= index.pages;
      if (slot >= index.pages) continue;
      const key = p ? `${index.layoutId}:${page}:${coloursKey}:${fontReady}` : `empty:${index.layoutId}`;
      if (painted[slot] === key) continue;
      painted[slot] = key;
      const w = index.pageWidth;
      const ht = index.pageHeight;
      let scale = Math.min(window.devicePixelRatio || 1, 3);
      scale = Math.min(scale, Math.sqrt(4e6 / Math.max(1, w * ht)));   // no page past 4 megapixels
      canvas.width = Math.max(1, Math.round(w * scale));
      canvas.height = Math.max(1, Math.round(ht * scale));
      canvas.style.width = `${w}px`;
      canvas.style.height = `${ht}px`;
      canvas.style.left = `${slot === 0 ? 0 : index.slotLeft1}px`;
      const ctx = canvas.getContext('2d');
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (!p || !fontReady) continue;
      ctx.setTransform(scale, 0, 0, scale, 0, 0);
      ctx.textBaseline = 'alphabetic';
      draw(ctx, p, 0, p.opWords, null);
    }
    spine.hidden = index.pages !== 2;
    if (index.pages === 2) {
      spine.style.left = `${index.slotLeft1 - index.pageGap / 2}px`;
      spine.style.top = `${index.firstSystemTop}px`;
      spine.style.height = `${Math.max(0, index.pageHeight - 2 * index.firstSystemTop)}px`;
    }
  }

  /** Fetches the pages shown and the one after, two at a time; keeps the last six. */
  function fetchPages() {
    if (!index) return;
    const want = shown.filter((p) => p >= 0);
    const after = Math.max(-1, ...want) + 1;
    if (after > 0 && after < index.pageCount) want.push(after);
    for (const page of want) {
      if (pages.has(page) || fetching.has(page) || failed.has(page) || fetching.size >= FETCHES) continue;
      const layoutId = index.layoutId;
      fetching.add(page);
      api.page(layoutId, page).then((answer) => {
        fetching.delete(page);
        if (!index || index.layoutId !== layoutId) return;
        let p;
        try {
          p = readPage(answer.buffer);
        } catch (e) {
          failed.add(page);   // not asked for again for this layout
          say(e instanceof VersionError ? e.message : 'A page of the score could not be read.');
          return;
        }
        if (p.layoutId !== layoutId || p.page !== page) return;
        pages.set(page, p);
        while (pages.size > KEEP_PAGES) {
          const oldest = [...pages.keys()].find((k) => !shown.includes(k));
          if (oldest === undefined) break;
          pages.delete(oldest);
        }
        paintSlots();
        fetchPages();
      }, (e) => {
        fetching.delete(page);
        if (e && e.status === 409) relayout();   // the layout is gone: ask again for this size
        else if (e instanceof VersionError) say(e.message);
      });
    }
  }

  function relayout() {
    wanted = null;
    loadIndex();
  }

  const gridded = (v, lo, hi) => Math.min(Math.max(Math.floor(v / GRID) * GRID, lo), hi);

  /** The index for the piece [rev] shows at the pane's size: 202 waits and asks again, 413 says so, 429 waits. */
  async function loadIndex() {
    if (rev === null || size.w <= 0 || size.h <= 0) return;
    const w = Math.floor(size.w);
    const ht = Math.floor(size.h);
    const key = `${rev}:${gridded(w, 280, 2000)}x${gridded(ht, 160, 2000)}`;
    if (key === wanted) return;
    wanted = key;
    const mine = ++token;
    const asked = rev;
    for (;;) {
      let answer;
      try {
        answer = await api.score(asked, w, ht);
      } catch (e) {
        if (mine !== token) return;
        if (e && e.status === 413) {
          drop();
          say(TOO_LARGE);
        } else if (e && e.status === 429) {
          const wait = ((e.body && e.body.retryAfter) || 5) * 1000;
          await new Promise((resolve) => setTimeout(resolve, wait));
          if (mine !== token) return;
          continue;
        } else if (e && e.status === 409) {
          wanted = null;   // the next state brings the new revision
        } else if (e && e.status !== 404 && e.status !== 401) {
          wanted = null;
          say(e.message);
        }
        return;
      }
      if (mine !== token) return;
      if (answer.status === 202) {
        if (!index) say('Laying out the score…');
        await new Promise((resolve) => setTimeout(resolve, Math.min(Math.max(answer.retryAfterMs || 500, 200), 5000)));
        if (mine !== token) return;
        continue;
      }
      try {
        const next = readIndex(answer.buffer);
        if (next.rev !== asked) return;
        index = next;
      } catch (e) {
        say(e instanceof VersionError ? e.message : 'The score could not be read.');
        return;
      }
      say('');
      pages.clear();
      fetching.clear();
      failed.clear();
      painted = [null, null];
      fonts = fontsFor(index);
      browsing = null;
      lastCursorSystem = -1;
      shown = new Array(index.pages).fill(-1);
      stage.style.setProperty('--page-height', `${index.pageHeight}px`);
      return;
    }
  }

  function drop() {
    index = null;
    pages.clear();
    fetching.clear();
    failed.clear();
    painted = [null, null];
    paintSlots();
    clearOverlay();
  }

  /** The system sounding at [ms]: the last whose first bar starts at or before it. */
  function systemAt(ms) {
    const starts = index.systemStart;
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >>> 1;
      if (starts[mid] <= ms) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }

  /** The first page of the spread [delta] spreads from what is shown, or null when there is none that way. */
  function turned(delta, cursorSystem) {
    const n = index.pages;
    const from = browsing !== null ? browsing : (() => { const p = Math.floor(cursorSystem / index.systemsPerPage); return p - (p % n); })();
    let target = Math.min(Math.max(from + delta * n, 0), index.pageCount - 1);
    target -= target % n;
    return same(browsingPages(target, index.pageCount, n), shown) ? null : target;
  }

  function turn(delta) {
    if (!index) return;
    const target = turned(delta, Math.max(lastCursorSystem, 0));
    if (target === null) return;
    browsing = target;
    live.textContent = `Page ${target + 1} of ${index.pageCount}`;
    update(lastNow);
  }

  previous.addEventListener('click', () => turn(-1));
  next.addEventListener('click', () => turn(1));
  follow.addEventListener('click', () => {
    browsing = null;
    update(lastNow);
  });
  stage.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowRight' || event.key === 'PageDown') turn(1);
    else if (event.key === 'ArrowLeft' || event.key === 'PageUp') turn(-1);
    else return;
    event.preventDefault();
  });

  // A tap on a bar seeks to it (a drag is not a tap).
  let down = null;
  stage.addEventListener('pointerdown', (event) => {
    down = { x: event.clientX, y: event.clientY };
  });
  stage.addEventListener('pointerup', (event) => {
    if (!down || !index) return;
    const moved = Math.hypot(event.clientX - down.x, event.clientY - down.y);
    down = null;
    if (moved > 8) return;
    const box = stage.getBoundingClientRect();
    const x = event.clientX - box.left;
    const y = event.clientY - box.top;
    const slot = index.pages === 2 && x >= index.slotLeft1 ? 1 : 0;
    const p = pages.get(shown[slot]);
    if (!p) return;
    let best = null;
    let bestDistance = Infinity;
    for (const system of p.systems) {
      const top = system.trebleTop - index.numberHeight;
      const d = y < top ? top - y : y > system.bassBottom ? y - system.bassBottom : 0;
      if (d < bestDistance) {
        bestDistance = d;
        best = system;
      }
    }
    if (!best) return;
    const px = x - (slot === 1 ? index.slotLeft1 : 0);
    let bar = best.barFrom + best.barCount - 1;
    for (let b = best.barFrom; b < best.barFrom + best.barCount - 1; b++) {
      if (px < barRight(p, b)) {
        bar = b;
        break;
      }
    }
    api.seek(barStart(p, bar));
  });

  let lastNow = 0;
  let updated = '';

  /** The pages shown at [now]: the music's, or the reader's; fetches and paints what changed (nothing per frame else). */
  function update(now) {
    lastNow = now;
    if (!index) return;
    const cursorSystem = systemAt(Math.max(0, now));
    const key = `${index.layoutId}:${cursorSystem}:${browsing}:${pages.size}:${fetching.size}`;
    if (cursorSystem === lastCursorSystem && key === updated) return;
    lastCursorSystem = cursorSystem;
    const followed = pagesShown(cursorSystem, index.systemCount, index.systemsPerPage, index.pages);
    if (!same(followed, lastFollow)) {
      lastFollow = followed;
      browsing = null;   // the music turned: follow it again
    }
    const next = browsing !== null ? browsingPages(browsing, index.pageCount, index.pages) : followed;
    if (!same(next, shown)) shown = next;
    updated = `${index.layoutId}:${cursorSystem}:${browsing}:${pages.size}:${fetching.size}`;
    follow.hidden = browsing === null;
    const visible = shown.filter((p) => p >= 0).map((p) => p + 1);
    const label = visible.length === 2 ? `Pages ${visible[0]} and ${visible[1]}` : `Page ${visible[0] || 1}`;
    stage.setAttribute('aria-label', `Score, ${label.toLowerCase()} of ${index.pageCount}${browsing !== null ? ', not following' : ''}`);
    previous.disabled = turned(-1, cursorSystem) === null;
    next.disabled = turned(1, cursorSystem) === null;
    paintSlots();
    fetchPages();
  }

  function clearOverlay() {
    const ctx = overlay.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, overlay.width, overlay.height);
  }

  /** The overlay at [now]: the cursor in its system, and every sounding head (and tied head reached) in the ramp. */
  function frame(now) {
    if (!index || !colours) return;
    update(now);
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    const ctx = overlay.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, overlay.width, overlay.height);
    if (now < 0 || !fontReady) return;   // the pause before a piece: no cursor yet
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.textBaseline = 'alphabetic';
    const s = systemAt(now);
    const page = Math.floor(s / index.systemsPerPage);
    const slot = shown.indexOf(page);
    const p = slot >= 0 ? pages.get(page) : null;
    if (p) {
      const system = p.systems[s - page * index.systemsPerPage];
      if (system) {
        const x = (slot === 1 ? index.slotLeft1 : 0) + cursorX(p, barAt(p, system, now), now);
        ctx.fillStyle = colours.cursor;
        ctx.fillRect(x - index.cursorWidth / 2, system.trebleTop - index.space, index.cursorWidth, system.bassBottom - system.trebleTop + 2 * index.space);
      }
    }
    if (!notes) return;
    let drawn = 0;
    for (let i = scanStart(notes, now - flip); i < notes.n && drawn < ROLL.MAX_DRAWS; i++) {
      const start = notes.start[i];
      if (start > now) break;
      const end = notes.end[i];
      if (end + flip < now) continue;
      const level = rampLevel(now, start, end, flip);
      for (let k = 0; k < shown.length; k++) {
        const q = shown[k] >= 0 ? pages.get(shown[k]) : null;
        if (!q) continue;
        const [from, to] = headsOf(q, i);
        for (let hd = from; hd < to; hd++) {
          const o = hd * HEAD.WORDS;
          const tied = q.heads[o + 1];
          const lvl = tied < 0 ? level : tied > now ? 0 : rampLevel(now, tied, end, flip);
          if (lvl === 0) continue;
          const band = q.systems[q.heads[o + 4]];
          ctx.save();
          ctx.translate(k === 1 ? index.slotLeft1 : 0, 0);
          ctx.beginPath();
          ctx.rect(0, band.bandTop, index.pageWidth, band.bandBottom - band.bandTop);
          ctx.clip();
          draw(ctx, q, q.heads[o + 2], q.heads[o + 3], colours.ramp[lvl]);
          ctx.restore();
          drawn++;
        }
      }
    }
  }

  return {
    /** The notes (wire.js) the overlay lights, and their revision: a new one asks for a new layout. */
    setNotes(n, r) {
      notes = n;
      if (r !== rev) {
        rev = r;
        wanted = null;
        drop();
        loadIndex();
      }
    },
    /** No piece: nothing to show. */
    clear() {
      notes = null;
      rev = null;
      wanted = null;
      token++;
      drop();
      say('');
    },
    setColours,
    setReduced(on) {
      flip = on ? 0 : ROLL.FLIP_MS;
    },
    /** The pane's size (CSS px): the overlay follows at once, the layout once it settles. */
    resize(w, ht) {
      size = { w, h: ht };
      const scale = Math.min(window.devicePixelRatio || 1, 2);
      overlay.width = Math.max(1, Math.round(w * scale));
      overlay.height = Math.max(1, Math.round(ht * scale));
      overlay.style.width = `${w}px`;
      overlay.style.height = `${ht}px`;
      clearTimeout(settleTimer);
      settleTimer = setTimeout(loadIndex, SETTLE_MS);
    },
    frame,
    tooLarge() {
      token++;
      drop();
      say(TOO_LARGE);
    },
  };
}
