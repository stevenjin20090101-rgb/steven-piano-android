/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// Now playing (DESIGN.md › v1.18 — M47, v1.20 — M53), a section of the panel's frame: a module of its own since v1.27
// (M63), as each of the four sections is. The frame (app.js) makes it once, create(host) → { received(state),
// progress(message), render(), renderViews(), shown(on), closePopover() }, and calls it as the state and the tablet's
// progress come and as the sections change. It asks the tablet only through the host, from host.ROOT; the views
// (views.js and its modules) are imported the first time they show.

import { $, clock, debounce } from './ui.js';

export function create(host) {
  const { h, glyph, chip, post, put, callBinary, toast, failed, reduced, art, artKey, segmented } = host;
  /** The panel's state, the frame's: read where it is used, so never an old one. */
  const state = () => host.state();

  // ---- Now playing --------------------------------------------------------------------------------
  //
  // v1.18 — M47: the art view (the cover large, the title, the composer, the scrubber, the transport, a row of glass
  // capsules) or, with Notes or Score, the strip over the views; Up next beside it from 1100 px; the album's colours
  // behind the whole window while it shows (v1.15's backdrop, below). v1.20 — M53: nothing but the piece's words under
  // the cover (the instrument's lines are on Settings › System); the head's capsules say when the piano is quiet, open
  // Up next below 1100 px, and show the piano's link as a dot.

  /** The position the page shows between the tablet's messages: where it was, when (this page's clock), and how fast it runs. */
  const clockBase = { ms: 0, at: 0, running: false, tempo: 100, duration: 0 };

  function positionNow() {
    if (!clockBase.running) return clockBase.ms;
    const ms = clockBase.ms + ((performance.now() - clockBase.at) * clockBase.tempo) / 100;
    return Math.min(ms, clockBase.duration);
  }

  /** The state come, before it is drawn (the frame's onState): where its piece stands, from which the clock counts. */
  function received(next) {
    const player = next.player;
    clockBase.ms = player.positionMs;
    clockBase.at = performance.now();
    clockBase.running = player.status === 'playing';
    clockBase.tempo = player.tempoPct;
    clockBase.duration = player.piece ? player.piece.durationMs : 0;
  }

  /** The tablet's progress, between its states: the clock's base again, and the views'. */
  function progress(message) {
    clockBase.ms = message.positionMs;
    clockBase.at = performance.now();
    if (typeof message.playing === 'boolean') clockBase.running = message.playing;
    if (views) views.progress(message);
  }

  let seeking = false;
  let shownProblem = null;

  function renderNow() {
    const player = state().player;
    const piece = player.piece;
    const playing = player.status === 'playing';
    // Asked for at once, ahead of the rest, at the full size; its picture gives the backdrop its colours (the same
    // picture: left as it is).
    nowArt(piece);
    $('now-art').hidden = !piece;
    $('section-now').classList.toggle('unloaded', !piece && !player.loading);
    $('now-title').textContent = piece ? piece.title : 'Choose a piece from the library.';
    $('now-eyebrow').textContent = piece ? [piece.composer, player.channel && `${player.channel.name} channel`].filter(Boolean).join(' · ') : '';
    const play = $('now-play');
    play.disabled = !piece && !player.loading;
    play.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    play.classList.toggle('playing', playing);   // the two glyphs cross-fade (style.css)
    const queue = player.queue;
    $('now-previous').disabled = !piece;
    $('now-next').disabled = !piece || (queue.index + 1 >= queue.ids.length && queue.repeat !== 'all');
    $('now-repeat').setAttribute('aria-pressed', queue.repeat !== 'off' ? 'true' : 'false');
    $('now-repeat').setAttribute('aria-label', { off: 'Repeat off', all: 'Repeat all', one: 'Repeat one' }[queue.repeat]);
    $('now-repeat-glyph').setAttribute('href', queue.repeat === 'one' ? '#i-repeat-one' : '#i-repeat');
    $('now-seek').disabled = !piece;
    $('now-duration').textContent = clock(piece ? piece.durationMs : 0);
    $('tempo-value').textContent = `${player.tempoPct}%`;
    $('tempo-down').disabled = !piece || player.tempoPct <= 25;
    $('tempo-up').disabled = !piece || player.tempoPct >= 200;
    // The volumes there are, each a capsule opening its slider: the channel's while one plays, and (v1.8 — M25) the
    // tablet's piano sound while its mode isn't Off (the mode is set on the tablet).
    const channel = player.channel;
    $('channel-volume').hidden = !channel;
    if (channel && !volumeDragging) {
      $('channel-volume-label').textContent = `${channel.name} volume`;
      setRange($('channel-volume-range'), channel.volume, 100);
      $('channel-volume-value').textContent = `${channel.volume}%`;
    }
    const tablet = player.tablet;
    $('tablet-volume').hidden = !tablet || tablet.mode === 'off';
    if (tablet && !tabletDragging) {
      setRange($('tablet-volume-range'), tablet.volume, 100);
      $('tablet-volume-value').textContent = `${tablet.volume}%`;
    }
    if (popoverOpen && popoverOpen.button.hidden) closePopover();
    // "Piano", a capsule in the page's head: the link's dot, painted with the rail's (renderStatus, v1.27 — M63).
    renderQuiet();
    renderSafety();
    renderPower();
    if (player.problem && player.problem !== shownProblem) toast(player.problem);
    shownProblem = player.problem;
    updateBackdrop();
    tick();
  }

  /**
   * Now playing's cover. A new piece (or a new cover) cross-fades: the picture there was fades out over the new one
   * (300 ms) while the cover settles from 0.96 (style.css › Motion); the same picture is left as it is. A cut under
   * reduced motion, and none the first time the page draws.
   */
  let nowArtKey;

  function nowArt(piece) {
    const box = $('now-art');
    const key = piece ? artKey(piece, 'full') : null;
    const changed = nowArtKey !== undefined && key !== nowArtKey && !!piece && !reduced();
    // Only a cover or a portrait (an <img>) is laid over as it fades; a roll card's mask is the CSSOM's, not copied.
    const old = changed ? box.querySelector('img.picture.shown:not(.ghost)') : null;
    nowArtKey = key;
    art(box, piece, 'full', { priority: true, onPicture: backdropFrom });
    if (!changed) return;
    if (old) {
      // The same <img>, already decoded, laid back over the new monogram (or picture) while it fades out.
      old.classList.add('ghost');
      box.append(old);
      setTimeout(() => old.remove(), 340);
    }
    box.classList.remove('settle');
    void box.offsetWidth;
    box.classList.add('settle');
    setTimeout(() => box.classList.remove('settle'), 340);
  }

  // ---- Quiet now (v1.20 — M54's quiet times) -----------------------------------------------------------------------
  //
  // The state's `quiet` ({now, until, overridden, next}; absent on older tablets: not quiet). While a quiet time holds
  // and nobody has lifted it, Now playing's head says "Quiet until 9:30" and offers Play anyway, which lifts it until
  // the block ends. A Play the tablet refuses meanwhile (409 quiet) says so in its own words, as every refusal does.

  /** A moment as this browser's clock says it: "9:30", "21:00". */
  function timeOfDay(epochMs) {
    const d = new Date(epochMs);
    return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  function quietNow() {
    const quiet = state() && state().quiet;
    return !!(quiet && quiet.now && !quiet.overridden);
  }

  function renderQuiet() {
    const on = quietNow();
    $('quiet-capsule').hidden = !on;
    $('quiet-override').hidden = !on;
    if (!on) return;
    const until = Number(state().quiet.until);
    const text = until > 0 ? `Quiet until ${timeOfDay(until)}` : 'Quiet now';
    if ($('quiet-text').textContent !== text) $('quiet-text').textContent = text;
  }

  // ---- The safety check (v1.26 — M61) ----------------------------------------------------------------------------
  //
  // The state's `safety` ({running, step, of, locked, failedKeys, coil, stopped, text}; absent on older tablets: nothing).
  // While a check runs on the tablet, or Play is locked until one passes there, Now playing's head says so in the tablet's
  // own words: the dot on while it runs, amber while keys lock Play, red only for a key still on; and (v1.27 — M64) for the
  // moment the tablet's card says why a check stopped ("Safety check stopped · The piano didn't report its keys. Reconnect
  // it."), with a plain ring. A Play the tablet refuses meanwhile (409 safety) says why, as every refusal does. The panel
  // can't start a check: the microphone needs the tablet in front.

  function renderSafety() {
    const safety = state() && state().safety;
    const on = !!(safety && safety.text && (safety.running || safety.locked || safety.stopped));
    $('safety-capsule').hidden = !on;
    if (!on) return;
    const dot = $('safety-dot');
    dot.classList.toggle('on', !!safety.running);
    dot.classList.toggle('attention', !safety.running && !!safety.locked && !safety.coil);
    dot.classList.toggle('live', !safety.running && !!safety.coil);
    if ($('safety-text').textContent !== safety.text) $('safety-text').textContent = safety.text;
  }

  // ---- The 24 V power plug (v1.26 — M62; the tablet's M60) -------------------------------------------------------
  //
  // The state's `power` ({paired, on, rail, waking, message, attention}; absent on older tablets: nothing). While the
  // tablet brings the 24 V up for a piece (its power plug), Now playing's head says "Waking the piano…" beside the safety
  // check's capsule, with the plug's bolt: calm, not an error, and gone by itself once every board answers. The plug's
  // readings and its failures are on Settings › System.

  function renderPower() {
    const power = state() && state().power;
    $('power-capsule').hidden = !(power && power.waking === true);
  }

  $('quiet-override').addEventListener('click', async () => {
    const until = state() && state().quiet ? Number(state().quiet.until) : 0;
    try {
      await post(host.ROOT + '/api/quiet/override');
      toast(until > 0 ? `Lifted until ${timeOfDay(until)}.` : 'Lifted.');   // the tablet's words (v1.27 — M63)
    } catch (e) {
      failed(e);
    }
  });

  // ---- Now playing's album colours (v1.15 — M41; v1.18 — M47: the whole window) --------------------------------

  /** The colours of the picture Now playing shows ([hue, saturation] × 4), or null: grey art, a roll card, a monogram. */
  let backdropPalette = null;

  /**
   * The art's colours behind Now playing, as the tablet has them: read from the picture Now playing's box shows, which
   * the art loader hands over once it is in ([img]: a cover or a portrait), the last colours kept meanwhile, and none
   * ([img] null) for a roll card, a monogram or a failure. They go in as --bd1 … --bd4 on the backdrop (hue and
   * saturation; the stylesheet gives the lightness and the veil of the page's appearance).
   */
  function backdropFrom(img) {
    const backdrop = $('now-backdrop');
    const show = (palette) => {
      if (palette) palette.forEach(([hue, saturation], i) => backdrop.style.setProperty(`--bd${i + 1}`, `${hue.toFixed(1)} ${(saturation * 100).toFixed(1)}%`));
      backdropPalette = palette;
      updateBackdrop();
    };
    if (!img) {
      show(null);
      return;
    }
    const read = () => {
      if (img.isConnected) show(artPalette(img));
    };
    if (img.complete && img.naturalWidth > 0) read();
    else {
      img.addEventListener('load', read, { once: true });
      img.addEventListener('error', () => show(null), { once: true });
    }
  }

  /**
   * The colours behind the whole window while Now playing shows a piece whose art has them, drifting while it plays;
   * .has-backdrop puts the words standing on them in the primary colour. The stylesheet takes them away in black and
   * white, with Album colours off, with reduced transparency or more contrast.
   */
  function updateBackdrop() {
    const on = host.section() === 'now' && !!state() && !!state().player.piece && !!backdropPalette;
    const backdrop = $('now-backdrop');
    backdrop.hidden = !on;
    backdrop.classList.toggle('playing', on && state().player.status === 'playing');
    $('panel').classList.toggle('has-backdrop', on);
  }

  /**
   * The backdrop's four colours of a loaded picture, [hue, saturation] each, or null for grey art: the tablet's rule
   * (data/art/ArtPalette.kt) over a 32 × 32 sample. A 4-bit histogram, near-black and near-white skipped (lightness
   * outside 0.08–0.92); each bin scored by its count × (0.3 + its saturation); the best kept, each 25° from the others
   * in hue or 0.25 in saturation, four at most; saturation × 1.35, at most 1; fewer than four padded from the first
   * hue ± 30°, then + 60°; none when the best bin's chroma is under 0.12. The picture comes from this page's own
   * address, so the canvas may read it; one it can't read gives none.
   */
  function artPalette(img) {
    const size = 32;
    const canvas = h('canvas', { width: size, height: size });
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return null;
    let data;
    try {
      context.drawImage(img, 0, 0, size, size);
      data = context.getImageData(0, 0, size, size).data;
    } catch (e) {
      return null;
    }
    const counts = new Uint32Array(4096);
    const sums = new Float64Array(4096 * 3);
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      const r = data[i];
      const g = data[i + 1];
      const b = data[i + 2];
      const lightness = (Math.max(r, g, b) + Math.min(r, g, b)) / 510;
      if (lightness < 0.08 || lightness > 0.92) continue;
      const bin = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
      counts[bin] += 1;
      sums[bin * 3] += r;
      sums[bin * 3 + 1] += g;
      sums[bin * 3 + 2] += b;
    }
    const bins = [];
    for (let bin = 0; bin < 4096; bin++) {
      const n = counts[bin];
      if (!n) continue;
      const r = sums[bin * 3] / n / 255;
      const g = sums[bin * 3 + 1] / n / 255;
      const b = sums[bin * 3 + 2] / n / 255;
      const hi = Math.max(r, g, b);
      const lo = Math.min(r, g, b);
      const chroma = hi - lo;
      const saturation = chroma === 0 ? 0 : Math.min(1, chroma / (1 - Math.abs(hi + lo - 1)));
      let hue = 0;
      if (chroma > 0) hue = hi === r ? 60 * ((g - b) / chroma) : hi === g ? 60 * ((b - r) / chroma + 2) : 60 * ((r - g) / chroma + 4);
      bins.push({ bin, chroma, hue: ((hue % 360) + 360) % 360, saturation, score: n * (0.3 + saturation) });
    }
    bins.sort((a, b) => b.score - a.score || a.bin - b.bin);
    if (bins.length === 0 || bins[0].chroma < 0.12) return null;
    const apart = (a, b) => {
      const d = Math.abs(a.hue - b.hue) % 360;
      return Math.min(d, 360 - d) >= 25 || Math.abs(a.saturation - b.saturation) >= 0.25;
    };
    const picks = [];
    for (const bin of bins) {
      if (picks.length === 4) break;
      if (picks.every((p) => apart(p, bin))) picks.push(bin);
    }
    const colours = picks.map((p) => [p.hue, Math.min(1, p.saturation * 1.35)]);
    for (const turn of [30, -30, 60]) {
      if (colours.length < 4) colours.push([(((colours[0][0] + turn) % 360) + 360) % 360, colours[0][1]]);
    }
    return colours;
  }

  function setRange(input, value, max) {
    input.max = String(max);
    input.value = String(value);
    input.style.setProperty('--fill', `${max > 0 ? (value / max) * 100 : 0}%`);
  }

  /** The clock and the scrubber, a few times a second between the tablet's messages. */
  function tick() {
    if (!state()) return;
    const piece = state().player.piece;
    const ms = positionNow();
    $('now-starting').textContent = state().player.status === 'playing' && ms < 0 ? 'Starting' : '';
    if (!seeking) {
      $('now-position').textContent = clock(Math.max(0, ms));
      setRange($('now-seek'), Math.max(0, Math.round(ms)), piece ? piece.durationMs : 0);
    }
  }

  setInterval(tick, 250);

  $('now-seek').addEventListener('input', () => {
    seeking = true;
    const input = $('now-seek');
    $('now-position').textContent = clock(Number(input.value));
    input.style.setProperty('--fill', `${(Number(input.value) / Math.max(1, Number(input.max))) * 100}%`);
  });

  $('now-seek').addEventListener('change', () => {
    seeking = false;
    seekTo(Number($('now-seek').value));
  });

  /** Seeks there: the clock, the scrubber and the views go at once, the tablet follows. */
  async function seekTo(ms) {
    clockBase.ms = ms;
    clockBase.at = performance.now();
    if (views) views.seeked(ms);
    tick();
    try {
      await post(host.ROOT + '/api/seek', { ms });
    } catch (e) {
      failed(e);
    }
  }

  // ---- Now playing's views (v1.13 — M32) ----------------------------------------------------------------------
  //
  // The score and the moving notes, as the tablet shows them (views.js and its modules, imported the first time they
  // show). The views' switch, Art · Notes · Score, is a glass capsule under the transport at every width (v1.18 — M47):
  // Art is the cover; Notes the roll alone; Score, from 900 px, the score beside or over the notes with the divider, and
  // below 900 px the score alone. A browser that never leaves Art loads nothing for them.

  /** Bravura's version: the first eight hex digits of its SHA-256, WebAssets.FONT_VERSION (which WebAssetsTest pins to the file). */
  const FONT_VERSION = 'cdf0f893';
  const NOW_VIEW = 'steven-piano-now-view';
  const VIEW_CHOICES = [['art', 'Art'], ['notes', 'Notes'], ['score', 'Score']];
  const wideQuery = window.matchMedia('(min-width: 900px)');
  let views = null;
  let viewsLoading = false;
  let nowView = (() => {
    try {
      const value = localStorage.getItem(NOW_VIEW);
      return value === 'notes' || value === 'score' ? value : 'art';
    } catch (e) {
      return 'art';
    }
  })();

  /** What the views' modules may ask of the tablet: every address built here, from ROOT. */
  const viewsApi = {
    notes: (rev) => callBinary(host.ROOT + `/api/now/notes?rev=${encodeURIComponent(rev)}`),
    score: (rev, w, h) => callBinary(host.ROOT + `/api/now/score?rev=${encodeURIComponent(rev)}&w=${Math.floor(w)}&h=${Math.floor(h)}`),
    page: (id, n) => callBinary(host.ROOT + `/api/now/score/${Math.floor(id)}/page/${Math.floor(n)}`),
    seek: (ms) => seekTo(Math.max(0, Math.round(ms))),
    settings: (change) => put(host.ROOT + '/api/settings', change),
    fontUrl: host.ROOT + '/api/font/bravura.otf?v=' + FONT_VERSION,
  };

  function chooseView(view) {
    nowView = view;
    try {
      localStorage.setItem(NOW_VIEW, view);
    } catch (e) {
      // Private browsing: it holds for this page only.
    }
    renderViews();
  }

  /** The switch and the views: the art view or the strip over the views; the module loaded the first time a view shows. */
  function renderViews() {
    if (!state()) return;
    const piece = !!(state().player.piece && state().player.views);
    $('now-switch').hidden = !piece;
    if (piece) segmented($('now-switch'), VIEW_CHOICES, nowView, chooseView);
    $('section-now').classList.toggle('views-on', piece && nowView !== 'art');
    if (views) {
      views.state(state());
      return;
    }
    $('now-view').hidden = true;
    if (!piece || nowView === 'art' || viewsLoading) return;
    viewsLoading = true;
    import('./views.js').then((module) => {
      views = module.mountViews($('now-views'), viewsApi, {
        h, glyph, chip, failed, viewButton: $('now-view'), view: () => nowView,
      });
      views.show(host.section() === 'now');
      views.state(state());
    }, () => {
      viewsLoading = false;
      toast('The score and notes could not be loaded. Reload the page.');
    });
  }

  wideQuery.addEventListener('change', renderViews);

  function transport(action) {
    return async () => {
      try {
        await post(host.ROOT + '/api/transport', { action });
      } catch (e) {
        failed(e);
      }
    };
  }

  $('now-play').addEventListener('click', transport('toggle'));
  $('now-previous').addEventListener('click', transport('previous'));
  $('now-next').addEventListener('click', transport('next'));
  $('now-repeat').addEventListener('click', async () => {
    const next = { off: 'all', all: 'one', one: 'off' }[state().player.queue.repeat];
    try {
      await post(host.ROOT + '/api/repeat', { mode: next });
    } catch (e) {
      failed(e);
    }
  });

  function tempoBy(delta) {
    return async () => {
      const pct = Math.min(200, Math.max(25, state().player.tempoPct + delta));
      try {
        await post(host.ROOT + '/api/tempo', { pct });
      } catch (e) {
        failed(e);
      }
    };
  }

  $('tempo-down').addEventListener('click', tempoBy(-5));
  $('tempo-up').addEventListener('click', tempoBy(5));

  let volumeDragging = false;
  const sendVolume = debounce(async (key, pct) => {
    try {
      await put(host.ROOT + `/api/channels/${encodeURIComponent(key)}/volume`, { pct });
    } catch (e) {
      failed(e);
    }
    volumeDragging = false;
  }, 150);

  let tabletDragging = false;
  const sendTabletVolume = debounce(async (pct) => {
    try {
      await put(host.ROOT + '/api/settings', { tabletVolume: pct });
    } catch (e) {
      failed(e);
    }
    tabletDragging = false;
  }, 150);

  $('tablet-volume-range').addEventListener('input', () => {
    const input = $('tablet-volume-range');
    const pct = Number(input.value);
    tabletDragging = true;
    setRange(input, pct, 100);
    $('tablet-volume-value').textContent = `${pct}%`;
    sendTabletVolume(pct);
  });

  $('channel-volume-range').addEventListener('input', () => {
    const input = $('channel-volume-range');
    const pct = Number(input.value);
    volumeDragging = true;
    setRange(input, pct, 100);
    $('channel-volume-value').textContent = `${pct}%`;
    if (state().player.channel) sendVolume(state().player.channel.key, pct);
  });

  // The volumes' popovers (v1.18 — M47): a capsule opens its slider in a small glass popover over it (under it near the
  // window's top); the capsule again, Escape, a tap outside, a scroll or another section closes it.
  let popoverOpen = null;

  function openPopover(button, panel) {
    closePopover();
    popoverOpen = { button, panel };
    panel.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    const box = button.getBoundingClientRect();
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    panel.style.left = `${Math.max(8, Math.min(box.left + box.width / 2 - width / 2, window.innerWidth - width - 8))}px`;
    panel.style.top = `${box.top - height - 8 >= 8 ? box.top - height - 8 : box.bottom + 8}px`;
    const range = panel.querySelector('input');
    if (range) range.focus();
  }

  function closePopover(refocus) {
    if (!popoverOpen) return;
    const { button, panel } = popoverOpen;
    popoverOpen = null;
    panel.hidden = true;
    button.setAttribute('aria-expanded', 'false');
    if (refocus) button.focus();
  }

  for (const [button, panel] of [[$('channel-volume'), $('channel-volume-pop')], [$('tablet-volume'), $('tablet-volume-pop')]]) {
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      if (popoverOpen && popoverOpen.panel === panel) closePopover();
      else openPopover(button, panel);
    });
  }

  document.addEventListener('click', (event) => {
    if (popoverOpen && !popoverOpen.panel.contains(event.target)) closePopover();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && popoverOpen) closePopover(true);
  });
  window.addEventListener('scroll', () => closePopover(), { passive: true });

  /** Whether Now playing shows (the frame's show()): the views draw only while it does. */
  function shown(on) {
    if (views) views.show(on);
  }

  return { received, progress, render: renderNow, renderViews, shown, closePopover };
}
