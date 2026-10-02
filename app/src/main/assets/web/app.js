/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The web panel (DESIGN.md › v1.5.1 — M18). No framework and no build step: the page talks to the
// tablet's API with fetch, hears its changes over the socket, and builds every element with DOM
// calls; text from the library (titles come from MIDI files) only ever goes in as text, never as
// markup. The content security policy allows this file and nothing inline.

'use strict';

(function () {
  // ---- Small helpers --------------------------------------------------------------------------

  const $ = (id) => document.getElementById(id);
  const SVG = 'http://www.w3.org/2000/svg';

  // Where the panel lives (v1.10 — M26): '' on the tablet's own address, '/p/<id>' through Steven Piano
  // Cloud's relay. Every request and the socket are built from it; the page's own files are relative.
  const ROOT = location.pathname.replace(/\/(index\.html)?$/, '');

  /** An element with its properties (class, text, on…, aria and data attributes) and children. */
  function h(tag, props, ...children) {
    const node = document.createElement(tag);
    if (props) {
      for (const [key, value] of Object.entries(props)) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
        else node.setAttribute(key, value === true ? '' : String(value));
      }
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  /** Replaces [node]'s children with [children], leaving out the ones that are not there (null, false). */
  function fill(node, ...children) {
    node.replaceChildren(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  }

  /** One of the app's glyphs from the page's sprite. */
  function glyph(id) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('class', 'glyph');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS(SVG, 'use');
    use.setAttribute('href', '#' + id);
    svg.append(use);
    return svg;
  }

  /** "0:00", "4:31", "1:02:03", as the app's clock. */
  function clock(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const hours = Math.floor(s / 3600);
    const minutes = Math.floor(s / 60) % 60;
    const seconds = String(s % 60).padStart(2, '0');
    return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
  }

  /** A number with a true minus sign. */
  const signed = (n) => (n < 0 ? '−' + -n : String(n));

  const plural = (n, one, many) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

  /** Calls [fn] once things have been quiet for [ms]. */
  function debounce(fn, ms) {
    let timer = null;
    return (...args) => {
      clearTimeout(timer);
      timer = setTimeout(() => fn(...args), ms);
    };
  }

  // ---- The API --------------------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || `The tablet answered ${status}.`);
      this.status = status;
      this.body = body || {};
    }
  }

  /** A request to the tablet: JSON both ways; every change carries the panel's header, which no other site's page can send. */
  async function call(method, path, body) {
    const init = { method, credentials: 'same-origin', cache: 'no-store', headers: {} };
    if (method !== 'GET') init.headers['X-Steven-Piano'] = '1';
    if (method !== 'GET' && method !== 'DELETE') {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body === undefined ? {} : body);
    }
    let response;
    try {
      response = await fetch(path, init);
    } catch (e) {
      throw new ApiError(0, { message: "The tablet can't be reached." });
    }
    let data = null;
    if ((response.headers.get('Content-Type') || '').includes('application/json')) {
      try {
        data = await response.json();
      } catch (e) {
        data = null;
      }
    }
    if (response.status === 503 && data && data.error === 'offline') {
      offline(true);
      throw new ApiError(503, data);
    }
    offline(false);
    if (response.status === 401 && path !== ROOT + '/api/login') {
      showGate();
      throw new ApiError(401, data);
    }
    if (!response.ok) throw new ApiError(response.status, data);
    return data;
  }

  /**
   * The relay's answer while the piano's tablet isn't connected (v1.10 — M26, `{"error":"offline"}`,
   * 503): before the panel has its state, the offline card in place of the PIN gate (start() looks
   * again every few seconds); after, a line at the head of the window until the tablet answers again.
   */
  function offline(on) {
    if (state === null) {
      $('offline').hidden = !on;
      if (on) {
        $('gate').hidden = true;
        $('panel').hidden = true;
      }
      return;
    }
    let node = $('offline-note');
    if (!on) {
      if (node) node.hidden = true;
      return;
    }
    if (!node) {
      node = h('p', { id: 'offline-note', class: 'banner toast offline', role: 'status', 'aria-live': 'polite' });
      document.body.append(node);
    }
    node.textContent = 'The piano is offline. The panel comes back when its tablet does.';
    node.hidden = false;
    $('conn-dot').classList.remove('live');
    $('conn-text').textContent = 'Offline';
  }

  const get = (path) => call('GET', path);
  const post = (path, body) => call('POST', path, body);
  const put = (path, body) => call('PUT', path, body);
  const del = (path) => call('DELETE', path);

  /** A short line at the foot of the window for what a tap did or could not do. */
  let toastTimer = null;
  function toast(text) {
    let node = $('toast');
    if (!node) {
      node = h('p', { id: 'toast', class: 'banner toast', role: 'status', 'aria-live': 'polite' });
      document.body.append(node);
    }
    node.textContent = text;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      node.hidden = true;
    }, 4000);
  }

  function failed(e) {
    if (e && e.status === 401) return;
    toast(e && e.message ? e.message : 'That did not work.');
  }

  // ---- Appearance (the panel's own, as the app's: Follow system · Light · Dark) ----------------

  const APPEARANCE = 'steven-piano-appearance';

  function appearance() {
    try {
      const value = localStorage.getItem(APPEARANCE);
      return value === 'light' || value === 'dark' ? value : 'system';
    } catch (e) {
      return 'system';
    }
  }

  function applyAppearance(value) {
    if (value === 'light' || value === 'dark') document.documentElement.setAttribute('data-theme', value);
    else document.documentElement.removeAttribute('data-theme');
  }

  function setAppearance(value) {
    try {
      localStorage.setItem(APPEARANCE, value);
    } catch (e) {
      // Private browsing: it holds for this page only.
    }
    applyAppearance(value);
    renderAppearance();
  }

  /** A chip: the chosen one carries a check. */
  function chip(label, chosen, onClick, disabled) {
    return h('button', { class: 'chip', type: 'button', 'aria-pressed': chosen ? 'true' : 'false', disabled, onclick: onClick }, chosen ? glyph('i-check') : null, label);
  }

  function renderAppearance() {
    const current = appearance();
    for (const holder of document.querySelectorAll('[data-appearance]')) {
      holder.replaceChildren(
        h('p', { class: 'eyebrow', text: 'Appearance' }),
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Appearance' },
          chip('Follow system', current === 'system', () => setAppearance('system')),
          chip('Light', current === 'light', () => setAppearance('light')),
          chip('Dark', current === 'dark', () => setAppearance('dark'))),
      );
    }
  }

  applyAppearance(appearance());

  // ---- The PIN gate ---------------------------------------------------------------------------

  let gateTimer = null;

  function showGate() {
    closeSocket();
    $('panel').hidden = true;
    $('gate').hidden = false;
    $('pin').value = '';
    $('pin').focus();
  }

  /** The guard's delay, counted down in seconds; the field waits it out. */
  function gateWait(seconds, lead) {
    clearInterval(gateTimer);
    let left = seconds;
    const tick = () => {
      if (left <= 0) {
        clearInterval(gateTimer);
        $('gate-note').textContent = '';
        $('pin').disabled = false;
        $('gate-open').disabled = false;
        $('pin').focus();
        return;
      }
      $('gate-note').textContent = `${lead} Try again in ${left} s.`;
      left -= 1;
    };
    $('pin').disabled = true;
    $('gate-open').disabled = true;
    tick();
    gateTimer = setInterval(tick, 1000);
  }

  $('gate-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const pin = $('pin').value.replace(/\D/g, '');
    if (pin.length !== 6) {
      $('gate-note').textContent = 'The PIN is six digits.';
      return;
    }
    $('gate-open').disabled = true;
    try {
      await call('POST', ROOT + '/api/login', { pin });
      $('gate-note').textContent = '';
      $('gate').hidden = true;
      await start();
    } catch (e) {
      $('pin').value = '';
      const wait = (e.body && e.body.retryAfter) || 0;
      if (e.status === 401 && wait > 0) gateWait(wait, "That PIN isn't right.");
      else if (e.status === 401) $('gate-note').textContent = "That PIN isn't right.";
      else if (e.status === 429) gateWait(wait || 30, 'Too many tries.');
      else if (e.status === 403 && e.body.error === 'no-pin') $('gate-note').textContent = 'Set a PIN on the tablet first: Piano › Web panel.';
      else $('gate-note').textContent = e.message;
    }
    if (!$('pin').disabled) $('gate-open').disabled = false;   // a countdown keeps it off until the wait is over
  });

  $('pin').addEventListener('input', () => {
    const digits = $('pin').value.replace(/\D/g, '').slice(0, 6);
    if (digits !== $('pin').value) $('pin').value = digits;
  });

  // ---- State, and the socket that keeps it ------------------------------------------------------

  let state = null;
  let socket = null;
  let socketTimer = null;
  let pingTimer = null;
  let retryMs = 1000;

  /** How often a page that opened while the piano was offline looks again (v1.10 — M26). */
  const OFFLINE_LOOK_MS = 10000;

  /** The position the page shows between the tablet's messages: where it was, when (this page's clock), and how fast it runs. */
  const clockBase = { ms: 0, at: 0, running: false, tempo: 100, duration: 0 };

  function positionNow() {
    if (!clockBase.running) return clockBase.ms;
    const ms = clockBase.ms + ((performance.now() - clockBase.at) * clockBase.tempo) / 100;
    return Math.min(ms, clockBase.duration);
  }

  function onState(next) {
    const before = state;
    state = next;
    const player = next.player;
    clockBase.ms = player.positionMs;
    clockBase.at = performance.now();
    clockBase.running = player.status === 'playing';
    clockBase.tempo = player.tempoPct;
    clockBase.duration = player.piece ? player.piece.durationMs : 0;
    document.body.classList.toggle('mono', !!next.monochrome);
    render(before);
  }

  function onProgress(message) {
    clockBase.ms = message.positionMs;
    clockBase.at = performance.now();
  }

  function openSocket() {
    closeSocket();
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${ROOT}/ws`);
    socket = ws;
    ws.addEventListener('open', () => {
      retryMs = 1000;
      setConnected(true);
      // The page's side of keeping awake: a word now and then, which the tablet drops.
      pingTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send('ping');
      }, 25000);
    });
    ws.addEventListener('message', (event) => {
      let message;
      try {
        message = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      if (message.type === 'state') onState(message);
      else if (message.type === 'progress') onProgress(message);
    });
    ws.addEventListener('close', () => {
      if (socket !== ws) return;
      socket = null;
      clearInterval(pingTimer);
      setConnected(false);
      socketTimer = setTimeout(reconnect, retryMs);
      retryMs = Math.min(retryMs * 2, 15000);
    });
  }

  /** Back after a drop: the state is asked for first, which also says when the session has ended (the gate then shows). */
  async function reconnect() {
    try {
      onState(await get(ROOT + '/api/state'));
      openSocket();
    } catch (e) {
      if (e.status !== 401) {
        socketTimer = setTimeout(reconnect, retryMs);
        retryMs = Math.min(retryMs * 2, 15000);
      }
    }
  }

  function closeSocket() {
    clearTimeout(socketTimer);
    clearInterval(pingTimer);
    if (socket) {
      const ws = socket;
      socket = null;
      ws.close();
    }
  }

  function setConnected(on) {
    $('conn-dot').classList.toggle('live', on);
    $('conn-text').textContent = on ? `Connected · ${location.host}` : 'Reconnecting…';
  }

  // ---- Sections ---------------------------------------------------------------------------------

  const SECTIONS = ['now', 'queue', 'library', 'channels', 'schedule', 'requests', 'add', 'studio', 'piano'];
  let section = 'now';

  function show(name) {
    section = SECTIONS.includes(name) ? name : 'now';
    for (const tab of document.querySelectorAll('.section-tab')) tab.setAttribute('aria-selected', tab.dataset.section === section ? 'true' : 'false');
    for (const page of document.querySelectorAll('[data-page]')) page.hidden = page.dataset.page !== section;
    if (location.hash !== '#' + section) history.replaceState(null, '', '#' + section);
    if (section === 'library') libraryLoad();
    if (section === 'channels') channelsLoad();
    if (section === 'schedule') scheduleLoad();
    if (section === 'requests') requestsLoad();
    if (section === 'piano') pianoLoad();
    if (section === 'add') renderAdd();
    if (section === 'studio') renderStudio();
    render(state);
  }

  for (const tab of document.querySelectorAll('.section-tab')) {
    tab.addEventListener('click', () => show(tab.dataset.section));
  }

  window.addEventListener('hashchange', () => show(location.hash.slice(1)));

  // The scroll-edge effect (DESIGN.md › v1.9): once the page is scrolled, the content fades into the
  // tab strip's glass (style.css, .scrolled). At the top of the page there is no band.
  let edgeFrame = 0;
  function markScrolled() {
    edgeFrame = 0;
    $('panel').classList.toggle('scrolled', window.scrollY > 0);
  }
  window.addEventListener('scroll', () => { if (!edgeFrame) edgeFrame = requestAnimationFrame(markScrolled); }, { passive: true });

  function render(before) {
    if (!state) return;
    renderNow();
    if (section === 'queue') renderQueue($('queue-full'));
    renderQueue($('now-side'), true);
    renderRequestsCount();
    if (section === 'channels' && (!before || channelOf(before) !== channelOf(state))) channelsLoad();
    if (section === 'requests' && (!before || before.requests.pending !== state.requests.pending)) requestsLoad();
    if (section === 'requests') renderGuestSwitches();
    if (section === 'schedule' && before && before.schedule.revision !== state.schedule.revision && !scheduling.editing) scheduleLoad();
    if (section === 'add') renderTally();
    if (section === 'studio') renderStudioState();
    if (section === 'piano') renderPiano();
  }

  const channelOf = (s) => (s && s.player.channel ? s.player.channel.key : null);

  // ---- Art ----------------------------------------------------------------------------------------

  /** Art is fetched only once its row is in sight: a roll card costs the tablet a parse of the piece. */
  const lazyArt = 'IntersectionObserver' in window
    ? new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        lazyArt.unobserve(entry.target);
        loadArt(entry.target);
      }
    }, { rootMargin: '200px' })
    : null;

  /**
   * Fills an .art box for [piece]: the composer's portrait, else the piece's own roll card tinted as
   * the app tints it, else (no card to be had) the monogram of its title, so no frame stands empty
   * (v1.10.1). [lazy]: only once the box is in sight (the library's long lists).
   */
  function art(box, piece, size, lazy) {
    box.replaceChildren();
    box.dataset.kind = '';
    if (!piece) return box;
    box.dataset.kind = piece.art;
    box.dataset.key = piece.composerKey;
    box.dataset.id = String(piece.id);
    box.dataset.size = size;
    box.dataset.title = piece.title || '';
    box.dataset.version = String(piece.artVersion || 0);
    if (lazy && lazyArt) lazyArt.observe(box);
    else loadArt(box);
    return box;
  }

  function loadArt(box) {
    if (box.dataset.kind === 'cover') {
      // A Studio piece's own cover (v1.12): drawn from its music; its version keeps the hour-long cache honest.
      const img = h('img', { alt: '', decoding: 'async', src: ROOT + `/api/art/piece/${encodeURIComponent(box.dataset.id)}?v=${encodeURIComponent(box.dataset.version || '0')}` });
      img.addEventListener('error', () => {
        box.dataset.kind = 'roll';
        loadArt(box);
      });
      box.replaceChildren(img);
    } else if (box.dataset.kind === 'portrait') {
      const img = h('img', { alt: '', decoding: 'async', src: ROOT + `/api/art/composer/${encodeURIComponent(box.dataset.key)}?size=${box.dataset.size}` });
      img.addEventListener('error', () => {
        box.dataset.kind = 'roll';
        loadArt(box);
      });
      box.replaceChildren(img);
    } else if (box.dataset.kind === 'roll') {
      if (!Number(box.dataset.id)) {
        box.replaceChildren(monogram(box.dataset.title));
        return;
      }
      const src = ROOT + `/api/art/piece/${encodeURIComponent(box.dataset.id)}`;
      const roll = h('span', { class: 'roll' });
      roll.style.webkitMaskImage = `url("${src}")`;
      roll.style.maskImage = `url("${src}")`;
      box.replaceChildren(roll);
      // A mask that fails to load draws nothing: the same address (from the cache) says whether the card came.
      const probe = new Image();
      probe.addEventListener('error', () => {
        if (roll.parentNode === box) box.replaceChildren(monogram(box.dataset.title));
      });
      probe.src = src;
    }
  }

  function monogram(name) {
    const letter = (name || '').match(/[\p{L}\p{N}]/u);
    return h('span', { class: 'monogram', text: letter ? letter[0].toUpperCase() : '–' });
  }

  // ---- Now playing --------------------------------------------------------------------------------

  let seeking = false;
  let shownArt = null;
  let shownProblem = null;

  function renderNow() {
    const player = state.player;
    const piece = player.piece;
    const playing = player.status === 'playing';
    const artKey = piece ? `${piece.id}:${piece.art}:${piece.artVersion || 0}` : null;
    if (artKey !== shownArt) {
      shownArt = artKey;
      art($('now-art'), piece, 'tile');
    }
    $('now-art').hidden = !piece;
    $('now-title').textContent = piece ? piece.title : 'Choose a piece from the library.';
    $('now-eyebrow').textContent = piece
      ? [piece.composer, player.channel && player.channel.name, player.channel && 'Channel'].filter(Boolean).join(' · ')
      : state.schedule.next || '';   // with nothing loaded, the next schedule (DESIGN.md › v1.6.2 — M19)
    const play = $('now-play');
    play.disabled = !piece && !player.loading;
    play.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    $('now-play-glyph').setAttribute('href', playing ? '#i-pause' : '#i-play');
    const queue = player.queue;
    $('now-previous').disabled = !piece;
    $('now-next').disabled = !piece || (queue.index + 1 >= queue.ids.length && queue.repeat !== 'all');
    $('now-shuffle').setAttribute('aria-pressed', queue.shuffle ? 'true' : 'false');
    $('now-shuffle').setAttribute('aria-label', queue.shuffle ? 'Shuffle on' : 'Shuffle off');
    $('now-repeat').setAttribute('aria-pressed', queue.repeat !== 'off' ? 'true' : 'false');
    $('now-repeat').setAttribute('aria-label', { off: 'Repeat off', all: 'Repeat all', one: 'Repeat one' }[queue.repeat]);
    $('now-repeat-glyph').setAttribute('href', queue.repeat === 'one' ? '#i-repeat-one' : '#i-repeat');
    $('now-seek').disabled = !piece;
    $('now-duration').textContent = clock(piece ? piece.durationMs : 0);
    $('tempo-value').textContent = `${player.tempoPct}%`;
    $('tempo-down').disabled = !piece || player.tempoPct <= 25;
    $('tempo-up').disabled = !piece || player.tempoPct >= 200;
    const channel = player.channel;
    $('channel-volume').hidden = !channel;
    if (channel && !volumeDragging) {
      $('channel-volume-label').textContent = `${channel.name} volume`;
      setRange($('channel-volume-range'), channel.volume, 100);
      $('channel-volume-value').textContent = `${channel.volume}%`;
    }
    // The tablet's piano sound (v1.8 — M25): its volume, while its mode isn't Off; the mode is set on the tablet.
    const tablet = player.tablet;
    $('tablet-volume').hidden = !tablet || tablet.mode === 'off';
    if (tablet && !tabletDragging) {
      setRange($('tablet-volume-range'), tablet.volume, 100);
      $('tablet-volume-value').textContent = `${tablet.volume}%`;
    }
    if (tablet) $('tablet-volume-note').textContent = tabletLine(tablet);
    const connected = state.link.state === 'connected';
    $('link-dot').classList.toggle('live', connected);
    $('link-dot').classList.toggle('breathing', connected && playing);
    $('link-text').textContent = connected ? 'Sent to piano' : 'Not connected';
    const [instrumentLine, keyboardLine] = instrumentLines(state.instruments);
    $('instrument-line').textContent = instrumentLine || '';
    $('instrument-line').hidden = !instrumentLine;
    $('keyboard-line').textContent = keyboardLine || '';
    $('keyboard-line').hidden = !keyboardLine;
    if (player.problem && player.problem !== shownProblem) toast(player.problem);
    shownProblem = player.problem;
    tick();
  }

  /**
   * What plays and what is played from (v1.11 — M29), in two read-only lines: "Instrument: Steven Piano" and
   * "Keyboard: FP-30X · Live · Recording" ("None", or "· not connected"). Live, recording and the choice of a
   * device stay the tablet's: the panel only shows them.
   */
  function instrumentLines(instruments) {
    if (!instruments || !instruments.instrument) return [null, null];
    const keyboard = instruments.keyboard;
    const on = [instruments.live ? 'Live' : null, instruments.recording ? 'Recording' : null].filter(Boolean);
    const named = keyboard ? keyboard.name + (keyboard.state === 'connected' ? '' : ' · not connected') : 'None';
    return [`Instrument: ${instruments.instrument.name}`, [`Keyboard: ${named}`, ...on].join(' · ')];
  }

  function setRange(input, value, max) {
    input.max = String(max);
    input.value = String(value);
    input.style.setProperty('--fill', `${max > 0 ? (value / max) * 100 : 0}%`);
  }

  /** The clock and the scrubber, a few times a second between the tablet's messages. */
  function tick() {
    if (!state) return;
    const piece = state.player.piece;
    const ms = positionNow();
    $('now-starting').textContent = state.player.status === 'playing' && ms < 0 ? 'Starting' : '';
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

  $('now-seek').addEventListener('change', async () => {
    const ms = Number($('now-seek').value);
    clockBase.ms = ms;
    clockBase.at = performance.now();
    seeking = false;
    try {
      await post(ROOT + '/api/seek', { ms });
    } catch (e) {
      failed(e);
    }
  });

  function transport(action) {
    return async () => {
      try {
        await post(ROOT + '/api/transport', { action });
      } catch (e) {
        failed(e);
      }
    };
  }

  $('now-play').addEventListener('click', transport('toggle'));
  $('now-previous').addEventListener('click', transport('previous'));
  $('now-next').addEventListener('click', transport('next'));
  $('now-shuffle').addEventListener('click', async () => {
    try {
      await post(ROOT + '/api/shuffle', { on: !state.player.queue.shuffle });
    } catch (e) {
      failed(e);
    }
  });
  $('now-repeat').addEventListener('click', async () => {
    const next = { off: 'all', all: 'one', one: 'off' }[state.player.queue.repeat];
    try {
      await post(ROOT + '/api/repeat', { mode: next });
    } catch (e) {
      failed(e);
    }
  });

  function tempoBy(delta) {
    return async () => {
      const pct = Math.min(200, Math.max(25, state.player.tempoPct + delta));
      try {
        await post(ROOT + '/api/tempo', { pct });
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
      await put(ROOT + `/api/channels/${encodeURIComponent(key)}/volume`, { pct });
    } catch (e) {
      failed(e);
    }
    volumeDragging = false;
  }, 150);

  /** What the tablet's piano sound is doing, as the tablet's popover says it. */
  function tabletLine(tablet) {
    if (!tablet.installed) return "The piano sound isn't on the tablet yet: download it there, in Piano › Tablet sound.";
    if (!tablet.active) return 'Silent while the piano is connected.';
    return tablet.mode === 'always' ? 'Playing on the tablet with the piano.' : "Playing on the tablet while the piano isn't connected.";
  }

  let tabletDragging = false;
  const sendTabletVolume = debounce(async (pct) => {
    try {
      await put(ROOT + '/api/settings', { tabletVolume: pct });
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
    if (state.player.channel) sendVolume(state.player.channel.key, pct);
  });

  // ---- Up next --------------------------------------------------------------------------------------

  async function queueCommand(body) {
    try {
      await post(ROOT + '/api/queue', body);
    } catch (e) {
      failed(e);
    }
  }

  /** Up next: the piece playing, then what follows, each row with up, down and remove, and drag where the browser has it. */
  function renderQueue(container, compact) {
    if (!container) return;
    const player = state.player;
    const items = player.queue.items;
    const hasCurrent = player.queue.index >= 0 && items.length > 0;
    const current = hasCurrent ? items[0] : null;
    const upcoming = hasCurrent ? items.slice(1) : items;
    const total = Math.max(0, player.queue.ids.length - (player.queue.index + 1));
    const head = h('div', { class: 'queue-head' },
      h('p', { class: 'eyebrow', text: total > 0 ? `Up next · ${plural(total, 'piece', 'pieces')}` : 'Up next' }),
      upcoming.length > 0 ? h('button', { class: 'text-button', type: 'button', onclick: () => queueCommand({ action: 'clear' }), text: 'Clear' }) : null);
    const list = h('ul', { class: 'rows' });
    if (current) {
      list.append(h('li', { class: 'row queue-row current' },
        art(h('div', { class: 'art' }), current, 'row'),
        h('div', { class: 'text' },
          h('p', { class: 'title', text: current.title }),
          h('p', { class: 'meta', text: `Playing · ${current.composerShort || current.composer || 'Unknown composer'}` }))));
    }
    upcoming.forEach((item, index) => {
      const row = h('li', { class: 'row queue-row clickable', draggable: 'true', 'data-uid': item.uid },
        compact ? null : h('span', { class: 'handle', 'aria-hidden': 'true' }, glyph('i-handle')),
        art(h('div', { class: 'art' }), item, 'row'),
        h('div', { class: 'text' },
          h('p', { class: 'title' }, item.title, item.requested ? h('span', { class: 'tag', text: 'Requested' }) : null),
          h('p', { class: 'meta', text: [item.composerShort || 'Unknown composer', clock(item.durationMs)].join(' · ') })),
        h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Move ${item.title} up`, disabled: index === 0, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'move', uid: item.uid, toIndex: index - 1 }); } }, glyph('i-up')),
        h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Move ${item.title} down`, disabled: index === upcoming.length - 1, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'move', uid: item.uid, toIndex: index + 1 }); } }, glyph('i-down')),
        h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Remove ${item.title} from the queue`, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'remove', uid: item.uid }); } }, glyph('i-close')));
      row.addEventListener('click', () => queueCommand({ action: 'skip', uid: item.uid }));
      row.addEventListener('dragstart', (event) => {
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(item.uid));
        row.classList.add('dragging');
      });
      row.addEventListener('dragend', () => row.classList.remove('dragging'));
      row.addEventListener('dragover', (event) => {
        event.preventDefault();
        row.classList.add('drop-before');
      });
      row.addEventListener('dragleave', () => row.classList.remove('drop-before'));
      row.addEventListener('drop', (event) => {
        event.preventDefault();
        row.classList.remove('drop-before');
        const uid = Number(event.dataTransfer.getData('text/plain'));
        const from = upcoming.findIndex((it) => it.uid === uid);
        if (!uid || from < 0 || from === index) return;
        queueCommand({ action: 'move', uid, toIndex: from < index ? index - 1 : index });
      });
      list.append(row);
    });
    fill(container,
      head,
      list,
      upcoming.length === 0 ? h('p', { class: 'empty', text: 'Nothing up next.' }) : null,
      compact ? dropZone() : null);
  }

  // ---- Library -------------------------------------------------------------------------------------

  const library = { category: 'all', query: '', offset: 0, total: 0, pieces: [], view: null, loaded: false };
  const PAGE = 50;

  function renderChips() {
    const chips = [['all', 'All'], ['playlists', 'Playlists'], ['composers', 'Composers'], ['favorites', 'Favorites'], ['recent', 'Recent']];
    $('lib-chips').replaceChildren(...chips.map(([key, label]) => chip(label, library.category === key && !library.query, () => {
      library.category = key;
      library.query = '';
      library.view = null;
      $('lib-search').value = '';
      libraryLoad(true);
    })));
  }

  $('lib-search').addEventListener('input', debounce(() => {
    library.query = $('lib-search').value.trim();
    library.view = null;
    libraryLoad(true);
  }, 250));

  $('lib-more').querySelector('button').addEventListener('click', () => libraryPage(library.offset));

  async function libraryLoad(force) {
    if (library.loaded && !force) return;
    library.loaded = true;
    renderChips();
    $('lib-crumb').hidden = true;
    if (library.query) return libraryPage(0);
    if (library.category === 'playlists') return playlistsLoad();
    if (library.category === 'composers') return composersLoad();
    return libraryPage(0);
  }

  async function libraryPage(offset) {
    const params = new URLSearchParams({ category: library.query ? 'all' : library.category, offset: String(offset), limit: String(PAGE) });
    if (library.query) params.set('q', library.query);
    try {
      const page = await get(ROOT + `/api/library?${params}`);
      library.pieces = offset === 0 ? page.pieces : library.pieces.concat(page.pieces);
      library.total = page.total;
      library.offset = offset + page.pieces.length;
      renderPieces(library.pieces, library.query ? 'Nothing matches that search.' : 'No pieces here yet.');
      $('lib-more').hidden = library.offset >= library.total;
    } catch (e) {
      failed(e);
    }
  }

  function renderPieces(pieces, emptyText) {
    if (lazyArt) lazyArt.disconnect();   // the rows go: nothing keeps watching them
    const ids = pieces.map((p) => p.id);
    $('lib-rows').replaceChildren(...pieces.map((piece) => pieceRow(piece, ids)));
    $('lib-empty').hidden = pieces.length > 0;
    $('lib-empty').textContent = emptyText;
  }

  /** A piece's row: its art, title and composer with its length; a tap plays it (and the list after it); its menu plays it next or adds it. */
  function pieceRow(piece, queue) {
    const row = h('li', { class: 'row clickable' },
      art(h('div', { class: 'art' }), piece, 'row', true),
      h('div', { class: 'text' },
        h('p', { class: 'title', text: piece.title }),
        h('p', { class: 'meta', text: [piece.composerShort || 'Unknown composer', clock(piece.durationMs)].join(' · ') })),
      h('button', { class: 'icon-button', type: 'button', 'aria-label': `More for ${piece.title}`, 'aria-haspopup': 'menu', onclick: (e) => { e.stopPropagation(); openMenu(e.currentTarget, piece, queue); } }, glyph('i-more')));
    row.addEventListener('click', () => play(piece.id, queue));
    return row;
  }

  async function play(pieceId, queue) {
    try {
      await post(ROOT + '/api/play', { pieceId, queue: queue && queue.length <= 5000 ? queue : undefined });
      toast('Playing on the piano.');
    } catch (e) {
      failed(e);
    }
  }

  let menu = null;

  function closeMenu() {
    if (menu) menu.remove();
    menu = null;
  }

  function openMenu(anchor, piece, queue) {
    showMenu(anchor, piece.title, [
      ['Play', () => play(piece.id, queue)],
      ['Play next', () => queueCommand({ action: 'playNext', ids: [piece.id] }).then(() => toast('It plays next.'))],
      ['Add to queue', () => queueCommand({ action: 'add', ids: [piece.id] }).then(() => toast('Added to Up next.'))],
    ]);
  }

  /** A small menu under [anchor] (above it near the window's foot): [items] are [label, action] pairs. */
  function showMenu(anchor, label, items) {
    closeMenu();
    const item = ([text, action]) => h('button', { type: 'button', role: 'menuitem', text, onclick: () => { closeMenu(); action(); } });
    menu = h('div', { class: 'menu', role: 'menu', 'aria-label': label }, items.map(item));
    document.body.append(menu);
    const box = anchor.getBoundingClientRect();
    const width = menu.offsetWidth;
    const height = menu.offsetHeight;
    menu.style.left = `${Math.max(8, Math.min(box.right - width, window.innerWidth - width - 8))}px`;
    menu.style.top = `${box.bottom + height + 8 > window.innerHeight ? Math.max(8, box.top - height) : box.bottom}px`;
    menu.querySelector('button').focus();
  }

  document.addEventListener('click', (event) => {
    if (menu && !menu.contains(event.target)) closeMenu();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeMenu();
  });

  async function playlistsLoad() {
    try {
      const { playlists } = await get(ROOT + '/api/playlists');
      $('lib-more').hidden = true;
      $('lib-rows').replaceChildren(...playlists.map((list) => {
        const row = h('li', { class: 'row clickable' },
          h('div', { class: 'art' }, monogram(list.name)),
          h('div', { class: 'text' },
            h('p', { class: 'title', text: list.name }),
            h('p', { class: 'meta', text: [list.builtIn ? 'Built in' : null, plural(list.pieceCount, 'piece', 'pieces'), list.pieceCount ? clock(list.durationMs) : null].filter(Boolean).join(' · ') })),
          glyph('i-chevron'));
        row.addEventListener('click', () => openPlaylist(list));
        return row;
      }));
      $('lib-empty').hidden = playlists.length > 0;
      $('lib-empty').textContent = 'No playlists yet.';
    } catch (e) {
      failed(e);
    }
  }

  async function openPlaylist(list) {
    try {
      const detail = await get(ROOT + `/api/playlists/${list.id}`);
      showGroup(detail.playlist.name, [detail.playlist.builtIn ? 'Built in' : null, plural(detail.pieces.length, 'piece', 'pieces')].filter(Boolean).join(' · '), detail.pieces,
        (shuffle) => post(ROOT + '/api/play-all', { playlistId: list.id, shuffle }));
    } catch (e) {
      failed(e);
    }
  }

  async function composersLoad() {
    try {
      const { composers } = await get(ROOT + '/api/composers');
      $('lib-more').hidden = true;
      $('lib-rows').replaceChildren(...composers.map((composer) => {
        const box = h('div', { class: 'art' });
        if (composer.portrait) art(box, { art: 'portrait', composerKey: composer.key, id: 0, title: composer.name }, 'row');
        else box.append(monogram(composer.name));
        const row = h('li', { class: 'row clickable' },
          box,
          h('div', { class: 'text' },
            h('p', { class: 'title', text: composer.name || 'Unknown composer' }),
            h('p', { class: 'meta', text: plural(composer.pieceCount, 'piece', 'pieces') })),
          glyph('i-chevron'));
        row.addEventListener('click', () => openComposer(composer));
        return row;
      }));
      $('lib-empty').hidden = composers.length > 0;
      $('lib-empty').textContent = 'No composers yet.';
    } catch (e) {
      failed(e);
    }
  }

  async function openComposer(composer) {
    try {
      const detail = await get(ROOT + `/api/composers/${encodeURIComponent(composer.key)}`);
      const ids = detail.pieces.map((p) => p.id);
      showGroup(detail.composer.name || 'Unknown composer', plural(detail.pieces.length, 'piece', 'pieces'), detail.pieces,
        (shuffle) => post(ROOT + '/api/play-all', { ids, shuffle }));
    } catch (e) {
      failed(e);
    }
  }

  /** A playlist or a composer opened: back, its name, Play and Shuffle, then its pieces. */
  function showGroup(name, meta, pieces, playAll) {
    const crumb = $('lib-crumb');
    const run = (shuffle) => async () => {
      try {
        await playAll(shuffle);
        toast('Playing on the piano.');
      } catch (e) {
        failed(e);
      }
    };
    crumb.replaceChildren(
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Back', onclick: () => libraryLoad(true) }, glyph('i-back')),
      h('div', { class: 'text' }, h('p', { class: 'title', text: name }), h('p', { class: 'eyebrow', text: meta })),
      h('button', { class: 'outlined', type: 'button', onclick: run(false), disabled: pieces.length === 0 }, glyph('i-play'), 'Play'),
      h('button', { class: 'outlined', type: 'button', onclick: run(true), disabled: pieces.length === 0 }, 'Shuffle'));
    crumb.hidden = false;
    $('lib-more').hidden = true;
    renderPieces(pieces, 'Nothing in here yet.');
  }

  // ---- Channels --------------------------------------------------------------------------------------

  async function channelsLoad() {
    try {
      const { channels, playing } = await get(ROOT + '/api/channels');
      $('channel-stop').hidden = !playing;
      $('channels-empty').hidden = channels.length > 0;
      $('channel-tiles').replaceChildren(...channels.map(channelTile));
    } catch (e) {
      failed(e);
    }
  }

  /** A channel's card: a mosaic of the composers it holds most, its name on the band, and how many pieces, or Playing with the dot. */
  function channelTile(channel) {
    const cells = channel.composers.slice(0, 4);
    const mosaic = h('div', { class: cells.length > 1 ? 'mosaic' : 'mosaic one' });
    if (cells.length === 0) mosaic.append(monogram(channel.name));
    for (const composer of cells.length === 3 ? cells.concat(cells[0]) : cells) {
      if (!composer.portrait) {
        mosaic.append(monogram(composer.name));
        continue;
      }
      const img = h('img', { alt: '', loading: 'lazy', src: ROOT + `/api/art/composer/${encodeURIComponent(composer.key)}?size=tile` });
      img.addEventListener('error', () => img.replaceWith(monogram(composer.name)));
      mosaic.append(img);
    }
    const meta = channel.playing
      ? h('p', { class: 'eyebrow' }, h('span', { class: 'dot live' }), 'Playing')
      : h('p', { class: 'eyebrow', text: channel.playable ? plural(channel.size, 'piece', 'pieces') : 'Add more pieces' });
    const tile = h('button', { class: 'tile', type: 'button', 'aria-label': `${channel.name} channel${channel.playing ? ', playing' : ''}` },
      mosaic, h('div', { class: 'band' }, h('p', { class: 'name', text: channel.name }), meta));
    tile.addEventListener('click', async () => {
      if (!channel.playable) {
        toast('Add more pieces: a channel needs three at least.');
        return;
      }
      try {
        await post(ROOT + `/api/channels/${encodeURIComponent(channel.key)}/play`);
        toast(`${channel.name} is playing.`);
        channelsLoad();
      } catch (e) {
        failed(e);
      }
    });
    return tile;
  }

  $('channel-stop').addEventListener('click', async () => {
    try {
      await post(ROOT + '/api/channels/stop');
      channelsLoad();
    } catch (e) {
      failed(e);
    }
  });

  // ---- Schedule ------------------------------------------------------------------------------------

  // The tablet's Piano › Schedule (DESIGN.md › v1.6.2 — M19): the next start and what the last one did,
  // a row a schedule with its switch, and an editor with the tablet's fields. The tablet checks every
  // save again (the same rules) and keeps the one alarm; its words come back when it refuses one.

  const DAYS = [['Mon', 'Monday'], ['Tue', 'Tuesday'], ['Wed', 'Wednesday'], ['Thu', 'Thursday'], ['Fri', 'Friday'], ['Sat', 'Saturday'], ['Sun', 'Sunday']];
  const WEEKDAYS = 31;
  const EVERY_DAY = 127;
  const scheduling = { data: null, editing: null, tab: 'channel', query: '', deleting: null, error: null, channels: null, playlists: null, pieces: null };

  async function scheduleLoad() {
    try {
      scheduling.data = await get(ROOT + '/api/schedules');
      renderSchedule();
    } catch (e) {
      failed(e);
    }
  }

  /** Why the schedule being edited can't be saved, as the tablet says it; null when it can. */
  function scheduleProblem(d) {
    if (!d.days) return 'Choose at least one day.';
    if (d.endMinute !== null && d.endMinute === d.startMinute) return 'The end must differ from the start.';
    if (!d.kind || !d.target) return 'Choose what to play.';
    return null;
  }

  function renderSchedule() {
    const data = scheduling.data;
    const body = $('schedule-body');
    if (!data || !body) return;
    $('schedule-add').hidden = !!scheduling.editing;
    fill(body,
      data.next ? h('p', { class: 'eyebrow inset schedule-next', text: data.next }) : null,
      data.last && data.schedules.length ? h('p', { class: 'note inset', text: data.last }) : null,
      data.exactAlarms ? null : h('div', { class: 'banner', role: 'status', text: 'Exact alarms are off on the tablet, so no schedule will start. Allow them there: Piano › Schedule › Allow exact alarms.' }),
      scheduling.editing ? scheduleEditor() : null,
      h('ul', { class: 'rows schedule-list' }, data.schedules.map(scheduleRow)),
      data.schedules.length === 0 && !scheduling.editing ? h('p', { class: 'empty', text: 'No schedules yet. The piano can play by itself at set times: a channel, a playlist or a piece.' }) : null,
      h('p', { class: 'note inset', text: 'The tablet starts them: keep it on, charged and near the piano.' }));
  }

  /** What a schedule's fields are, as the tablet takes them. */
  const fields = (s) => ({ days: s.days, startMinute: s.startMinute, kind: s.kind, target: s.target, endMinute: s.endMinute, volumePct: s.volumePct, enabled: s.enabled });

  function scheduleRow(s) {
    if (scheduling.deleting === s.id) {
      return h('li', { class: 'row' },
        h('div', { class: 'text' }, h('p', { class: 'title', text: 'Delete this schedule?' }), h('p', { class: 'meta', text: `${s.when} · ${s.what}. It won't play again.` })),
        h('button', { class: 'text-button', type: 'button', onclick: () => { scheduling.deleting = null; renderSchedule(); } }, 'Cancel'),
        h('button', { class: 'outlined', type: 'button', onclick: () => deleteSchedule(s) }, 'Delete schedule'));
    }
    const toggle = h('button', { class: 'switch', role: 'switch', type: 'button', 'aria-checked': s.enabled ? 'true' : 'false', 'aria-label': `${s.when}, ${s.what}` });
    toggle.addEventListener('click', (event) => {
      event.stopPropagation();
      toggle.setAttribute('aria-checked', s.enabled ? 'false' : 'true');
      sendSchedule({ ...fields(s), enabled: !s.enabled }, s.id);
    });
    const row = h('li', { class: 'row clickable schedule-row' },
      h('div', { class: 'text' },
        h('p', { class: s.enabled ? 'title' : 'title off', text: s.when }),
        h('p', { class: 'meta', text: s.what })),
      toggle,
      h('button', { class: 'icon-button', type: 'button', 'aria-label': `More for ${s.when}`, 'aria-haspopup': 'menu', onclick: (event) => {
        event.stopPropagation();
        showMenu(event.currentTarget, s.when, [
          ['Edit', () => editSchedule(s)],
          ['Delete', () => { scheduling.deleting = s.id; renderSchedule(); }],
        ]);
      } }, glyph('i-more')));
    row.addEventListener('click', () => editSchedule(s));
    return row;
  }

  function editSchedule(s) {
    scheduling.editing = { id: s.id, ...fields(s), name: s.name };
    scheduling.tab = s.kind;
    scheduling.error = null;
    renderSchedule();
    $('schedule-body').scrollIntoView({ block: 'start' });
  }

  $('schedule-add').addEventListener('click', () => {
    const now = new Date();
    const start = ((now.getHours() + 1) % 24) * 60;
    scheduling.editing = { id: null, days: WEEKDAYS, startMinute: start, kind: null, target: null, endMinute: (start + 60) % 1440, volumePct: 70, enabled: true, name: null };
    scheduling.tab = 'channel';
    scheduling.error = null;
    renderSchedule();
  });

  /** The editor: DAYS, TIME, PLAYS and VOLUME as on the tablet, then what keeps it from saving, Cancel and Save. */
  function scheduleEditor() {
    const d = scheduling.editing;
    const again = () => {
      scheduling.error = null;
      renderSchedule();
    };
    const dayChips = DAYS.map(([short, full], i) => {
      const bit = 1 << i;
      const node = chip(short, (d.days & bit) !== 0, () => {
        d.days ^= bit;
        again();
      });
      node.setAttribute('aria-label', full);
      return node;
    });
    const problem = scheduling.error || scheduleProblem(d);
    return h('div', { class: 'schedule-editor' },
      h('p', { class: 'eyebrow inset', text: 'Schedule' }),
      h('h2', { class: 'editor-title', text: d.id ? 'Edit schedule' : 'Add schedule' }),
      h('h3', { class: 'section-head eyebrow', text: 'Days' }),
      h('div', { class: 'actions' },
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Days' }, dayChips),
        h('div', { class: 'chips' },
          chip('Weekdays', d.days === WEEKDAYS, () => { d.days = WEEKDAYS; again(); }),
          chip('Every day', d.days === EVERY_DAY, () => { d.days = EVERY_DAY; again(); }))),
      h('h3', { class: 'section-head eyebrow', text: 'Time' }),
      timeSetting('Starts', d.startMinute, null, (m) => { d.startMinute = m; again(); }),
      switchSetting('Until the end', d.endMinute === null, 'A playlist or a piece plays to its end; a channel plays until someone stops it', (on) => {
        d.endMinute = on ? null : (d.startMinute + 60) % 1440;
        again();
      }),
      d.endMinute === null ? null : timeSetting('Ends', d.endMinute, d.endMinute < d.startMinute ? 'The next day' : null, (m) => { d.endMinute = m; again(); }),
      h('h3', { class: 'section-head eyebrow', text: 'Plays' }),
      h('div', { class: 'actions' }, h('div', { class: 'chips', role: 'group', 'aria-label': 'Plays' },
        [['channel', 'Channels'], ['playlist', 'Playlists'], ['piece', 'Pieces']].map(([kind, label]) => chip(label, scheduling.tab === kind, () => {
          scheduling.tab = kind;
          renderSchedule();
        })))),
      scheduleChoices(d),
      h('h3', { class: 'section-head eyebrow', text: 'Volume' }),
      switchSetting('Set the volume', d.volumePct !== null, 'Off: the piano plays as it is set, and a channel at its own volume', (on) => {
        d.volumePct = on ? 70 : null;
        again();
      }),
      d.volumePct === null ? null : volumeSetting(d),
      problem ? h('p', { class: 'note inset', role: 'status', text: problem }) : null,
      h('div', { class: 'actions editor-actions' },
        h('button', { class: 'text-button', type: 'button', onclick: () => { scheduling.editing = null; renderSchedule(); } }, 'Cancel'),
        h('button', { class: 'outlined', type: 'button', disabled: !!scheduleProblem(d), onclick: () => sendSchedule(fields(d), d.id, true) }, 'Save')));
  }

  /** A time of day on the 24-hour clock, as the tablet shows it whatever the browser's own clock: the hour and the minutes. */
  function timeSetting(label, minute, note, onChange) {
    const select = (count, value, name) => {
      const node = h('select', { class: 'field time-part', 'aria-label': `${label}, ${name}` },
        Array.from({ length: count }, (_, i) => h('option', { value: String(i), text: String(i).padStart(2, '0') })));
      node.value = String(value);
      return node;
    };
    const hour = select(24, Math.floor(minute / 60), 'hour');
    const minutes = select(60, minute % 60, 'minutes');
    const changed = () => onChange(Number(hour.value) * 60 + Number(minutes.value));
    hour.addEventListener('change', changed);
    minutes.addEventListener('change', changed);
    return h('div', { class: 'setting' },
      h('div', { class: 'label' }, label, note ? h('span', { class: 'meta', text: note }) : null),
      h('div', { class: 'time-field' }, hour, h('span', { class: 'time-colon', text: ':' }), minutes));
  }

  function switchSetting(label, on, note, onChange) {
    const button = h('button', { class: 'switch', role: 'switch', type: 'button', 'aria-checked': on ? 'true' : 'false', 'aria-label': label });
    button.addEventListener('click', () => onChange(!on));
    return h('div', { class: 'setting' }, h('div', { class: 'label' }, label, h('span', { class: 'meta', text: note })), button);
  }

  function volumeSetting(d) {
    const range = h('input', { class: 'range', type: 'range', min: '0', max: '100', step: '1', 'aria-label': 'Schedule volume' });
    const value = h('span', { class: 'value', text: `${d.volumePct}%` });
    setRange(range, d.volumePct, 100);
    range.addEventListener('input', () => {
      d.volumePct = Number(range.value);
      setRange(range, d.volumePct, 100);
      value.textContent = `${d.volumePct}%`;
    });
    return h('div', { class: 'setting stacked' },
      h('div', { class: 'label' }, 'Schedule volume', h('span', { class: 'eyebrow', text: '%' })),
      h('div', { class: 'with-value' }, range, value),
      h('p', { class: 'meta', text: "The piano's own volume while it plays, or how hard its keys are struck where the piano has none. What was there comes back when it ends." }));
  }

  /** The choices for what to play, as rows with a check on the chosen one: the channels, the playlists, or pieces searched. */
  function scheduleChoices(d) {
    const list = h('ul', { class: 'rows', role: 'radiogroup', 'aria-label': 'What to play' });
    const choose = (kind, target, name) => {
      d.kind = kind;
      d.target = String(target);
      d.name = name;
      scheduling.error = null;
      renderSchedule();
    };
    const row = (kind, target, title, meta, enabled) => {
      const chosen = d.kind === kind && d.target === String(target);
      const node = h('li', { class: enabled ? 'row clickable choice' : 'row choice off', role: 'radio', tabindex: enabled ? '0' : '-1', 'aria-checked': chosen ? 'true' : 'false', 'aria-disabled': enabled ? null : 'true' },
        h('div', { class: 'text' }, h('p', { class: 'title', text: title }), meta ? h('p', { class: 'meta', text: meta }) : null),
        chosen ? glyph('i-check') : null);
      if (enabled) {
        node.addEventListener('click', () => choose(kind, target, title));
        node.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            choose(kind, target, title);
          }
        });
      }
      return node;
    };
    const fillRows = (rows, empty) => fill(list, rows.length ? rows : h('li', { class: 'row' }, h('p', { class: 'meta', text: empty })));
    const wrap = h('div', { class: 'choices' });
    if (scheduling.tab === 'channel') {
      const show = () => fillRows(scheduling.channels.map((c) => row('channel', c.key, c.name, c.playable ? plural(c.size, 'piece', 'pieces') : 'Add more pieces', c.playable)), 'The channels are being worked out from the library.');
      if (scheduling.channels) show();
      else get(ROOT + '/api/channels').then((r) => { scheduling.channels = r.channels; show(); }).catch(failed);
      wrap.append(list);
    } else if (scheduling.tab === 'playlist') {
      const show = () => fillRows(scheduling.playlists
        .slice().sort((a, b) => Number(b.builtIn) - Number(a.builtIn))
        .map((p) => row('playlist', p.id, p.name, [p.builtIn ? 'Built in' : null, plural(p.pieceCount, 'piece', 'pieces')].filter(Boolean).join(' · '), true)), 'No playlists yet.');
      if (scheduling.playlists) show();
      else get(ROOT + '/api/playlists').then((r) => { scheduling.playlists = r.playlists; show(); }).catch(failed);
      wrap.append(list);
    } else {
      const search = h('input', { class: 'field', type: 'search', placeholder: 'Search titles and composers', 'aria-label': 'Search pieces', autocomplete: 'off', maxlength: '200' });
      search.value = scheduling.query;
      const show = (pieces) => {
        const rows = pieces.map((p) => row('piece', p.id, p.title, [p.composerShort || 'Unknown composer', clock(p.durationMs)].join(' · '), true));
        const chosenShown = pieces.some((p) => d.kind === 'piece' && String(p.id) === d.target);
        if (d.kind === 'piece' && d.target && !chosenShown) rows.unshift(row('piece', d.target, d.name || 'The piece chosen', null, true));
        fillRows(rows, scheduling.query ? 'Nothing matches that search.' : 'No pieces yet.');
      };
      const load = () => {
        const params = new URLSearchParams({ limit: '30' });
        if (scheduling.query) params.set('q', scheduling.query);
        else params.set('category', 'recent');
        get(ROOT + `/api/library?${params}`).then((page) => show(page.pieces)).catch(failed);
      };
      search.addEventListener('input', debounce(() => {
        scheduling.query = search.value.trim();
        load();
      }, 250));
      load();
      wrap.append(h('div', { class: 'inset' }, search), list);
    }
    return wrap;
  }

  /** Saves a schedule: a new one (POST) or [id]'s (PUT); the tablet's refusal comes back in its own words. */
  async function sendSchedule(body, id, fromEditor) {
    try {
      if (id) await put(ROOT + `/api/schedules/${id}`, body);
      else await post(ROOT + '/api/schedules', body);
      if (fromEditor) {
        scheduling.editing = null;
        toast('Saved. The tablet starts it on time.');
      }
      await scheduleLoad();
    } catch (e) {
      if (fromEditor && e.status && e.status !== 401) {
        scheduling.error = e.message;
        renderSchedule();
      } else {
        failed(e);
        scheduleLoad();
      }
    }
  }

  async function deleteSchedule(s) {
    scheduling.deleting = null;
    try {
      await del(ROOT + `/api/schedules/${s.id}`);
      toast('Schedule deleted.');
    } catch (e) {
      failed(e);
    }
    scheduleLoad();
  }

  // ---- Requests ------------------------------------------------------------------------------------

  function renderRequestsCount() {
    const count = state.requests.pending;
    $('requests-count').hidden = count === 0;
    $('requests-count').textContent = String(count);
  }

  async function requestsLoad() {
    try {
      const data = await get(ROOT + '/api/requests');
      $('requests-empty').hidden = data.pending.length > 0;
      $('requests-rows').replaceChildren(...data.pending.map((request) => h('li', { class: 'row' },
        h('div', { class: 'text' },
          h('p', { class: 'title', text: request.title }),
          h('p', { class: 'meta', text: [request.composer || 'Unknown composer', new Date(request.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })].join(' · ') })),
        h('button', { class: 'outlined', type: 'button', onclick: () => answer(request.id, 'approve') }, 'Approve'),
        h('button', { class: 'text-button', type: 'button', onclick: () => answer(request.id, 'dismiss') }, 'Dismiss'))));
      renderGuestSwitches();
    } catch (e) {
      failed(e);
    }
  }

  async function answer(id, action) {
    try {
      await post(ROOT + `/api/requests/${id}/${action}`);
      requestsLoad();
    } catch (e) {
      failed(e);
    }
  }

  function renderGuestSwitches() {
    if (!state) return;
    setSwitch($('guests-open'), state.requests.guests);
    setSwitch($('guests-approve'), state.requests.approveFirst, !state.requests.guests);
    const guest = state.web.guestAddress;
    $('guests-address').textContent = guest ? `Guests ask at ${guest}, or with the poster's code.` : '';
  }

  function setSwitch(button, on, disabled) {
    button.setAttribute('aria-checked', on ? 'true' : 'false');
    button.disabled = !!disabled;
  }

  $('guests-open').addEventListener('click', async () => {
    try {
      await put(ROOT + '/api/settings', { webGuests: !state.requests.guests });
    } catch (e) {
      failed(e);
    }
  });

  $('guests-approve').addEventListener('click', async () => {
    try {
      await put(ROOT + '/api/settings', { webApproveFirst: !state.requests.approveFirst });
    } catch (e) {
      failed(e);
    }
  });

  // ---- Add ---------------------------------------------------------------------------------------------

  const MIDI_BYTES = 8 * 1024 * 1024;
  const ZIP_BYTES = 64 * 1024 * 1024;
  const uploads = [];
  let uploading = false;

  /** The drop zone, with a file chooser for touch screens. */
  function dropZone() {
    return zoneFor({
      accept: '.mid,.midi,.zip',
      multiple: true,
      title: 'Drop MIDI files or a zip here',
      meta: '.mid and .midi up to 8 MB, .zip up to 64 MB',
      button: 'Choose files',
      onFiles: addFiles,
    });
  }

  /** A drop zone: [title] and [meta], a chooser for touch screens ([button]), files dropped or chosen to [onFiles]. */
  function zoneFor(options) {
    const input = h('input', { type: 'file', multiple: options.multiple, accept: options.accept, hidden: true });
    input.addEventListener('change', () => {
      options.onFiles(input.files);
      input.value = '';
    });
    const zone = h('div', { class: 'drop' },
      h('p', { text: options.title }),
      h('p', { class: 'meta', text: options.meta }),
      h('button', { class: 'outlined', type: 'button', onclick: () => input.click() }, glyph('i-add'), options.button),
      input);
    zone.addEventListener('dragover', (event) => {
      if (!event.dataTransfer || !Array.from(event.dataTransfer.types).includes('Files')) return;
      event.preventDefault();
      zone.classList.add('over');
    });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', (event) => {
      if (!event.dataTransfer || event.dataTransfer.files.length === 0) return;
      event.preventDefault();
      zone.classList.remove('over');
      options.onFiles(event.dataTransfer.files);
    });
    return zone;
  }

  function renderAdd() {
    $('add-body').replaceChildren(
      dropZone(),
      h('ul', { class: 'rows uploads', id: 'upload-rows' }),
      h('p', { class: 'note inset', id: 'import-tally' }),
      h('div', { class: 'import-playlist inset', id: 'import-playlist', hidden: true }));
    renderUploads();
    renderTally();
  }

  function addFiles(files) {
    for (const file of Array.from(files)) {
      const name = file.name;
      const lower = name.toLowerCase();
      const isMidi = lower.endsWith('.mid') || lower.endsWith('.midi');
      const isZip = lower.endsWith('.zip');
      const upload = { file, name, size: file.size, status: 'Waiting', progress: 0, route: ROOT + '/api/upload', list: 'upload-rows', sent: 'Sent to the tablet', refused: 'Not added' };
      if (!isMidi && !isZip) upload.status = 'Not added: only .mid, .midi and .zip files';
      else if (isMidi && file.size > MIDI_BYTES) upload.status = 'Not added: a MIDI file can be 8 MB at most';
      else if (isZip && file.size > ZIP_BYTES) upload.status = 'Not added: a zip can be 64 MB at most';
      else if (file.size === 0) upload.status = 'Not added: the file is empty';
      else upload.pending = true;
      uploads.unshift(upload);
    }
    if (section !== 'add') show('add');
    renderUploads();
    sendNext();
  }

  function renderUploads() {
    for (const id of ['upload-rows', 'studio-upload-rows']) {
      const list = $(id);
      if (list) list.replaceChildren(...uploads.filter((upload) => upload.list === id).slice(0, 50).map(uploadRow));
    }
  }

  /** One file sent, or waiting: its name, its size and how it went, and its bar while it goes. */
  function uploadRow(upload) {
    const bar = h('div', { class: 'progress' }, h('span'));
    bar.firstChild.style.setProperty('--fill', `${Math.round(upload.progress * 100)}%`);
    return h('li', { class: 'row' },
      h('div', { class: 'text' },
        h('p', { class: 'title', text: upload.name }),
        h('p', { class: 'meta', text: `${size(upload.size)} · ${upload.status}` }),
        upload.pending || upload.sending ? bar : null));
  }

  /** "1.7 KB", "2.4 MB": decimal units to one place, as the app writes sizes (UpdateCopy). */
  function size(bytes) {
    const [value, unit] = bytes < 1e6 ? [bytes / 1e3, 'KB'] : [bytes / 1e6, 'MB'];
    return `${value.toLocaleString(undefined, { maximumFractionDigits: 1 })} ${unit}`;
  }

  /** One upload at a time, as the tablet takes them: the file is the request's whole body. */
  function sendNext() {
    if (uploading) return;
    const upload = uploads.slice().reverse().find((u) => u.pending);
    if (!upload) return;
    upload.pending = false;
    upload.sending = true;
    upload.status = 'Sending';
    uploading = true;
    renderUploads();
    const request = new XMLHttpRequest();
    request.open('PUT', `${upload.route}?name=${encodeURIComponent(upload.name)}`);
    request.setRequestHeader('X-Steven-Piano', '1');
    request.upload.addEventListener('progress', (event) => {
      if (!event.lengthComputable) return;
      upload.progress = event.loaded / event.total;
      upload.status = `Sending ${Math.round(upload.progress * 100)}%`;
      renderUploads();
    });
    request.addEventListener('loadend', () => {
      upload.sending = false;
      uploading = false;
      let answer = null;
      try {
        answer = JSON.parse(request.responseText);
      } catch (e) {
        // Not JSON: the status says enough.
      }
      const busy = request.status === 409 && (!answer || answer.error === 'busy');
      if (request.status === 202) {
        upload.progress = 1;
        upload.status = upload.sent;
      } else if (request.status === 401) {
        upload.status = `${upload.refused}: enter the PIN again`;
        showGate();
      } else if (busy) {
        upload.pending = true;
        upload.status = 'Waiting';
        setTimeout(sendNext, 1500);
      } else {
        let reason = request.status === 0 ? "the tablet can't be reached" : `the tablet answered ${request.status}`;
        try {
          reason = JSON.parse(request.responseText).message || reason;
        } catch (e) {
          // Not JSON: the status says enough.
        }
        upload.status = `${upload.refused}: ${reason}`;
      }
      renderUploads();
      if (!busy) sendNext();
    });
    request.send(upload.file);
  }

  /**
   * The import on the tablet: its progress while it runs, then its tally; and under it, when a zip or a loose
   * file was put in a playlist (v1.10.1 — M28), "In the playlist MIDI" with Open the playlist.
   */
  function renderTally() {
    const node = $('import-tally');
    if (!node || !state) return;
    const run = state.import;
    if (run.running) {
      node.textContent = `Importing ${run.done.toLocaleString()} of ${run.total.toLocaleString()}${run.current ? ` · ${run.current}` : ''}`;
    } else if (run.total > 0) {
      node.textContent = [
        `Imported ${plural(run.imported, 'piece', 'pieces')}`,
        run.duplicates ? `${run.duplicates.toLocaleString()} already there` : null,
        run.failed ? `${run.failed.toLocaleString()} couldn't be read` : null,
      ].filter(Boolean).join(' · ');
    } else {
      node.textContent = '';
    }
    renderImportedPlaylist(!run.running && run.total > 0 ? run.playlist : null);
  }

  let shownPlaylist = null;

  /** "In the playlist MIDI" and Open the playlist, for the playlist the last import filled; nothing without one. */
  function renderImportedPlaylist(playlist) {
    const node = $('import-playlist');
    if (!node) return;
    const key = playlist ? `${playlist.id}:${playlist.name}` : null;
    if (key === shownPlaylist && node.childElementCount === (playlist ? 2 : 0)) return;
    shownPlaylist = key;
    node.hidden = !playlist;
    node.replaceChildren(...(playlist ? [
      h('p', { class: 'note', text: `In the playlist ${playlist.name}` }),
      h('button', { class: 'outlined', type: 'button', onclick: () => openImported(playlist) }, 'Open the playlist'),
    ] : []));
  }

  /** The Library section with the imported playlist open, as if chosen from its Playlists. */
  function openImported(playlist) {
    library.category = 'playlists';
    library.query = '';
    library.view = null;
    library.loaded = true;   // the playlist itself shows, not the list beneath it first
    $('lib-search').value = '';
    show('library');
    renderChips();
    openPlaylist(playlist);
  }

  // ---- Studio (v1.7 — M23) ---------------------------------------------------------------------------------

  const AUDIO_BYTES = 200 * 1024 * 1024;
  const AUDIO_EXTENSIONS = ['wav', 'wave', 'mp3', 'm4a', 'mp4', 'aac', 'flac', 'ogg', 'oga', 'opus', 'webm', '3gp', 'amr'];

  /** Studio's page: MODELS, TRANSCRIBE (the drop zone for recordings and what was sent), COMPOSE (v1.7 — M24), JOBS; or why Studio can't run on the tablet. */
  function renderStudio() {
    $('studio-body').replaceChildren(
      h('p', { class: 'empty', id: 'studio-unavailable', hidden: true }),
      h('div', { id: 'studio-parts' },
        h('h2', { class: 'section-head eyebrow', text: 'Models' }),
        h('ul', { class: 'rows', id: 'studio-models' }),
        h('h2', { class: 'section-head eyebrow', text: 'Transcribe' }),
        zoneFor({
          accept: 'audio/*,' + AUDIO_EXTENSIONS.map((e) => '.' + e).join(','),
          multiple: true,
          title: 'Drop a piano recording here',
          meta: '.wav, .mp3, .m4a, .flac, .ogg and the like, up to 200 MB. About a minute per three minutes of audio.',
          button: 'Choose recordings',
          onFiles: addRecordings,
        }),
        h('ul', { class: 'rows uploads', id: 'studio-upload-rows' }),
        h('h2', { class: 'section-head eyebrow', text: 'Compose' }),
        h('div', { id: 'studio-compose' }),
        h('h2', { class: 'section-head eyebrow', id: 'studio-jobs-head', text: 'Jobs', hidden: true }),
        h('ul', { class: 'rows', id: 'studio-jobs' })));
    renderUploads();
    renderCompose();
    renderStudioState();
  }

  /** Recordings sent one at a time, as the tablet takes them, each checked here first as the tablet will. */
  function addRecordings(files) {
    for (const file of Array.from(files)) {
      const name = file.name;
      const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
      const upload = { file, name, size: file.size, status: 'Waiting', progress: 0, route: ROOT + '/api/studio/audio', list: 'studio-upload-rows', sent: 'Sent to the tablet · transcribing there', refused: 'Not sent' };
      if (!AUDIO_EXTENSIONS.includes(extension)) upload.status = 'Not sent: only recordings (.wav, .mp3, .m4a, .flac, .ogg…)';
      else if (file.size > AUDIO_BYTES) upload.status = 'Not sent: a recording can be 200 MB at most';
      else if (file.size === 0) upload.status = 'Not sent: the file is empty';
      else upload.pending = true;
      uploads.unshift(upload);
    }
    if (section !== 'studio') show('studio');
    renderUploads();
    sendNext();
  }

  /** The models and the jobs, as the tablet reports them in its state. */
  function renderStudioState() {
    const body = $('studio-parts');
    if (!body || !state || !state.studio) return;
    const studio = state.studio;
    const unavailable = $('studio-unavailable');
    unavailable.hidden = studio.available;
    unavailable.textContent = studio.reason || '';
    body.hidden = !studio.available;
    $('studio-models').replaceChildren(...studio.models.map((model) => h('li', { class: 'row' },
      h('div', { class: 'text' },
        h('p', { class: 'title', text: model.title }),
        h('p', { class: 'meta', text: model.line }),
        model.progress === null ? null : progressBar(model.progress)))));
    if (!composing) renderCompose();   // the note follows the model; an open form is left as it is
    $('studio-jobs-head').hidden = studio.jobs.length === 0;
    $('studio-jobs').replaceChildren(...studio.jobs.map((job) => h('li', { class: 'row' },
      h('div', { class: 'text' },
        h('p', { class: 'title', text: job.state === 'done' && job.title ? job.title : job.name }),
        h('p', { class: 'meta', text: job.line }),
        job.state === 'running' ? progressBar(job.progress) : null),
      job.state === 'queued' || job.state === 'running'
        ? h('button', { class: 'outlined', type: 'button', 'aria-label': `Cancel ${job.name}`, onclick: () => cancelJob(job.id) }, 'Cancel')
        : null)));
  }

  /** A 2 px bar; [fraction] null: under way, no measure yet. */
  function progressBar(fraction) {
    const bar = h('div', { class: fraction === null ? 'progress waiting' : 'progress' }, h('span'));
    bar.firstChild.style.setProperty('--fill', `${Math.round((fraction === null ? 0 : fraction) * 100)}%`);
    return bar;
  }

  async function cancelJob(id) {
    try {
      await post(ROOT + `/api/studio/jobs/${id}/cancel`);
    } catch (e) {
      failed(e);
    }
  }

  // ---- Composing (v1.7 — M24) ------------------------------------------------------------------------------

  const MOODS = [['calm', 'Calm'], ['bright', 'Bright'], ['wild', 'Wild'], ['melancholy', 'Melancholy']];
  const MAJOR_NAMES = ['C', 'D♭', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
  const MINOR_NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'B♭', 'B'];
  const COMPOSE_NOTE = 'Runs on this tablet. About a minute for a two-minute piece.';
  const TEMPO = { min: 40, max: 200 };
  const LENGTH = { min: 1, max: 5 };

  /** The compose form: null while closed; else its seed (as the tablet sent it), the choices made here, the search. */
  let composing = null;

  const keyName = (key) => `${(key.minor ? MINOR_NAMES : MAJOR_NAMES)[key.tonic]} ${key.minor ? 'minor' : 'major'}`;
  const spokenKey = (key) => keyName(key).replace('♯', ' sharp').replace('♭', ' flat');
  const manner = (seed) => (seed.composer ? `${seed.title} (${seed.composer})` : seed.title);

  /** The key the form holds: the one chosen here, else the seed's (for Melancholy its minor, as on the tablet). */
  function composeKey(c) {
    if (c.key) return c.key;
    if (!c.seed) return null;
    const own = c.seed.key;
    return c.mood === 'melancholy' && !own.minor ? { tonic: (own.tonic + 9) % 12, minor: true } : { tonic: own.tonic, minor: own.minor };
  }

  const composeBpm = (c) => (c.bpm !== null ? c.bpm : c.seed ? c.seed.bpm : null);

  /** Under the button and the form: what it takes, and that the model downloads first while it isn't on the tablet. */
  function composeNote() {
    const model = state && state.studio ? state.studio.models.find((m) => m.name === 'composer') : null;
    return model && !model.installed ? `${COMPOSE_NOTE} Downloads the composing model (${Math.round(model.sizeBytes / 1e6)} MB) first.` : COMPOSE_NOTE;
  }

  /** COMPOSE: the button and its note, or the form. */
  function renderCompose() {
    const holder = $('studio-compose');
    if (!holder) return;
    if (!composing) {
      holder.replaceChildren(h('div', { class: 'actions compose-start' },
        h('button', { class: 'outlined', type: 'button', onclick: () => openCompose(null) }, 'Compose a piece…'),
        h('p', { class: 'meta', text: composeNote() })));
      return;
    }
    holder.replaceChildren(composeEditor());
  }

  function openCompose(pieceId) {
    composing = { seed: null, reading: true, mood: 'calm', key: null, bpm: null, minutes: 2, choosing: false, query: '', error: null, sending: false };
    renderCompose();
    loadSeed(pieceId);
  }

  /** The seed from the tablet: the piece chosen, else the one played last; its key and tempo become the form's until changed. */
  async function loadSeed(pieceId) {
    const c = composing;
    c.reading = true;
    try {
      const seed = await get(pieceId ? ROOT + `/api/studio/seed?piece=${pieceId}` : ROOT + '/api/studio/seed');
      if (composing !== c) return;
      c.seed = seed;
      c.key = null;
      c.bpm = null;
      c.error = null;
    } catch (e) {
      if (composing !== c) return;
      if (e.status === 404 && !c.seed) c.error = 'A composition starts from a piece in the library. Add one first.';
      else if (e.status !== 401) c.error = e.message;
    }
    c.reading = false;
    renderCompose();
  }

  /** The tablet's sheet laid flat: MOOD, KEY, TEMPO, LENGTH, IN THE MANNER OF, the note, Cancel and Compose. */
  function composeEditor() {
    const c = composing;
    const again = () => {
      c.error = null;
      renderCompose();
    };
    const key = composeKey(c);
    const keyChips = Array.from({ length: 12 }, (_, tonic) => {
      const option = { tonic, minor: key ? key.minor : false };
      const node = chip((option.minor ? MINOR_NAMES : MAJOR_NAMES)[tonic], !!key && key.tonic === tonic, () => { c.key = option; again(); }, !key);
      node.setAttribute('aria-label', spokenKey(option));
      return node;
    });
    const modeChips = [false, true].map((minor) => chip(minor ? 'Minor' : 'Major', !!key && key.minor === minor, () => { c.key = { tonic: key.tonic, minor }; again(); }, !key));
    const ready = !!c.seed && !c.reading && !!key && composeBpm(c) !== null;
    return h('div', { class: 'compose-editor' },
      h('p', { class: 'eyebrow inset', text: c.seed ? `In the manner of ${manner(c.seed)}` : 'Studio' }),
      h('h2', { class: 'editor-title', text: 'Compose a piece' }),
      h('h3', { class: 'section-head eyebrow', text: 'Mood' }),
      h('div', { class: 'actions' }, h('div', { class: 'chips', role: 'group', 'aria-label': 'Mood' },
        MOODS.map(([name, label]) => chip(label, c.mood === name, () => { c.mood = name; again(); })))),
      h('h3', { class: 'section-head eyebrow', text: 'Key' }),
      h('div', { class: 'actions' },
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Key' }, keyChips),
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Major or minor' }, modeChips)),
      h('h3', { class: 'section-head eyebrow', text: 'Tempo' }),
      stepperField('Tempo', 'BPM', TEMPO, () => composeBpm(c), (v) => { c.bpm = v; }, () => (c.seed ? (composeBpm(c) === c.seed.bpm ? "The piece's own tempo" : `The piece's own: ${c.seed.bpm} bpm`) : null), (v) => `${v} beats a minute`),
      h('h3', { class: 'section-head eyebrow', text: 'Length' }),
      stepperField('Length', 'MIN', LENGTH, () => c.minutes, (v) => { c.minutes = v; }, () => null, (v) => (v === 1 ? '1 minute' : `${v} minutes`)),
      h('h3', { class: 'section-head eyebrow', text: 'In the manner of' }),
      seedPart(c),
      c.error ? h('p', { class: 'note inset', role: 'status', text: c.error }) : null,
      h('p', { class: 'note inset', text: composeNote() }),
      h('div', { class: 'actions editor-actions' },
        h('button', { class: 'text-button', type: 'button', onclick: () => { composing = null; renderCompose(); } }, 'Cancel'),
        h('button', { class: 'outlined', type: 'button', disabled: !ready || c.sending, onclick: sendCompose }, 'Compose')));
  }

  /**
   * A number between [range]'s ends with − and +, which repeat while held: the value and its note change in
   * place, so a held button keeps its hold. [read] gives it, [write] keeps it, [note] and [spoken] say it.
   */
  function stepperField(label, unit, range, read, write, note, spoken) {
    const value = h('span', { class: 'value' });
    const noteLine = h('span', { class: 'meta' });
    const down = h('button', { class: 'icon-button', type: 'button' }, glyph('i-remove'));
    const up = h('button', { class: 'icon-button', type: 'button' }, glyph('i-add'));
    const refresh = () => {
      const v = read();
      value.textContent = v === null ? '—' : String(v);
      down.disabled = v === null || v <= range.min;
      up.disabled = v === null || v >= range.max;
      down.setAttribute('aria-label', `Less, ${v === null ? '' : spoken(v)}`);
      up.setAttribute('aria-label', `More, ${v === null ? '' : spoken(v)}`);
      const n = note();
      noteLine.textContent = n || '';
      noteLine.hidden = !n;
    };
    const step = (delta) => () => {
      const v = read();
      if (v === null) return;
      write(Math.min(range.max, Math.max(range.min, v + delta)));
      refresh();
    };
    holdToRepeat(down, step(-1));
    holdToRepeat(up, step(1));
    refresh();
    return h('div', { class: 'setting' },
      h('div', { class: 'label' }, label, h('span', { class: 'eyebrow', text: unit }), noteLine),
      h('div', { class: 'stepper' }, down, value, up));
  }

  /** A button that acts once when pressed and, held, again every 70 ms after 400 ms; Enter and Space act once. */
  function holdToRepeat(button, act) {
    let delay = null;
    let every = null;
    const stop = () => {
      clearTimeout(delay);
      clearInterval(every);
      delay = null;
      every = null;
    };
    button.addEventListener('pointerdown', (event) => {
      if (button.disabled || event.button !== 0) return;
      event.preventDefault();
      act();
      delay = setTimeout(() => { every = setInterval(() => { if (button.disabled) stop(); else act(); }, 70); }, 400);
    });
    for (const type of ['pointerup', 'pointerleave', 'pointercancel', 'blur']) button.addEventListener(type, stop);
    button.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        act();
      }
    });
  }

  /** The seed: its title, then its composer, key and tempo, and Change; or the search over the library. */
  function seedPart(c) {
    if (c.reading && !c.seed) return h('ul', { class: 'rows' }, h('li', { class: 'row' }, h('p', { class: 'meta', text: 'Reading the piece…' })));
    const rows = [];
    if (c.seed) {
      rows.push(h('li', { class: 'row' },
        h('div', { class: 'text' },
          h('p', { class: 'title', text: c.seed.title }),
          h('p', { class: 'meta', text: [c.seed.composer, c.seed.key.label, `${c.seed.bpm} bpm`].filter(Boolean).join(' · ') })),
        h('button', { class: 'outlined', type: 'button', 'aria-label': c.choosing ? 'Close the search' : 'Choose another piece', onclick: () => { c.choosing = !c.choosing; renderCompose(); } }, c.choosing ? 'Close' : 'Change')));
    }
    const list = h('ul', { class: 'rows' }, rows);
    if (!c.choosing) return list;
    const choices = h('ul', { class: 'rows', role: 'radiogroup', 'aria-label': 'In the manner of' });
    const search = h('input', { class: 'field', type: 'search', placeholder: 'Search titles and composers', 'aria-label': 'Search pieces', autocomplete: 'off', maxlength: '200' });
    search.value = c.query;
    const choose = (id) => {
      c.choosing = false;
      c.query = '';
      loadSeed(id);
    };
    const show = (pieces) => {
      const items = pieces.map((p) => {
        const chosen = c.seed && p.id === c.seed.pieceId;
        const node = h('li', { class: 'row clickable choice', role: 'radio', tabindex: '0', 'aria-checked': chosen ? 'true' : 'false' },
          h('div', { class: 'text' }, h('p', { class: 'title', text: p.title }), h('p', { class: 'meta', text: [p.composerShort || 'Unknown composer', clock(p.durationMs)].join(' · ') })),
          chosen ? glyph('i-check') : null);
        node.addEventListener('click', () => choose(p.id));
        node.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            choose(p.id);
          }
        });
        return node;
      });
      fill(choices, items.length ? items : h('li', { class: 'row' }, h('p', { class: 'meta', text: c.query ? 'Nothing matches that search.' : 'No pieces yet.' })));
    };
    const load = () => {
      const params = new URLSearchParams({ limit: '30' });
      if (c.query) params.set('q', c.query);
      else params.set('category', 'recent');
      get(ROOT + `/api/library?${params}`).then((page) => { if (composing === c) show(page.pieces); }).catch(failed);
    };
    search.addEventListener('input', debounce(() => {
      c.query = search.value.trim();
      load();
    }, 250));
    load();
    return h('div', { class: 'choices' }, list, h('div', { class: 'inset' }, search), choices);
  }

  /** Sends the form's choices; the job shows under JOBS as the tablet takes it. The tablet's refusal comes back in its own words. */
  async function sendCompose() {
    const c = composing;
    const key = composeKey(c);
    if (!c || !c.seed || !key) return;
    c.sending = true;
    renderCompose();
    try {
      await post(ROOT + '/api/studio/compose', { pieceId: c.seed.pieceId, mood: c.mood, key: { tonic: key.tonic, minor: key.minor }, bpm: composeBpm(c), minutes: c.minutes });
      if (composing === c) composing = null;
      renderCompose();
      toast('Composing on the tablet. It shows under Jobs.');
    } catch (e) {
      c.sending = false;
      if (e.status && e.status !== 401) {
        c.error = e.message;
        renderCompose();
      } else {
        failed(e);
      }
    }
  }

  // ---- Piano -------------------------------------------------------------------------------------------

  let pianoTable = null;
  const openGroups = new Set(['feel']);
  const pianoDrafts = {};
  let pianoDragging = false;

  async function pianoLoad() {
    try {
      pianoTable = await get(ROOT + '/api/piano');
      renderPiano();
    } catch (e) {
      failed(e);
    }
  }

  /** How a setting's wire value reads, as the app's table shows it. */
  function shown(setting, wire) {
    if (wire === undefined || wire === null) return '—';
    const kind = setting.kind;
    const number = Number(wire);
    if (kind.type === 'switch') return wire.trim() !== '0' ? 'On' : 'Off';
    if (kind.type === 'choice') return kind.options[number] !== undefined ? kind.options[number] : wire;
    if (Number.isNaN(number)) return wire;
    if (number === 0 && setting.zero) return setting.zero;
    if (kind.type === 'slider') {
      if (setting.percentOf255) return String(Math.floor((Math.round(number) * 100) / 255));
      if (kind.decimals > 0) return number.toFixed(kind.decimals);
      return signed(Math.round(number));
    }
    return signed(Math.round(number));
  }

  const sendSetting = debounce(async (name, value) => {
    try {
      await put(ROOT + `/api/piano/${encodeURIComponent(name)}`, { value });
    } catch (e) {
      failed(e);
    }
    delete pianoDrafts[name];
  }, 150);

  function control(setting, wire, enabled) {
    const kind = setting.kind;
    const label = h('div', { class: 'label' }, setting.label, setting.unit ? h('span', { class: 'eyebrow', text: setting.unit }) : null, setting.note ? h('span', { class: 'meta', text: setting.note }) : null);
    if (kind.type === 'switch') {
      const on = wire !== undefined && wire.trim() !== '0';
      const button = h('button', { class: 'switch', role: 'switch', type: 'button', 'aria-checked': on ? 'true' : 'false', 'aria-label': setting.label, disabled: !enabled || wire === undefined });
      button.addEventListener('click', () => {
        button.setAttribute('aria-checked', on ? 'false' : 'true');
        sendSetting(setting.name, !on);
      });
      return h('div', { class: enabled ? 'setting' : 'setting disabled' }, label, button);
    }
    if (kind.type === 'choice') {
      const chosen = wire === undefined ? -1 : Number(wire);
      return h('div', { class: enabled ? 'setting stacked' : 'setting stacked disabled' }, label,
        h('div', { class: 'chips', role: 'group', 'aria-label': setting.label }, kind.options.map((option, index) => chip(option, index === chosen, () => sendSetting(setting.name, index), !enabled))));
    }
    if (kind.type === 'stepper') {
      const value = wire === undefined ? null : Math.round(Number(wire));
      const step = (up) => () => {
        let next = Math.min(kind.max, Math.max(kind.min, value + (up ? kind.step : -kind.step)));
        if (kind.lowestOn && next > 0 && next < kind.lowestOn) next = up || value > kind.lowestOn ? kind.lowestOn : 0;
        sendSetting(setting.name, next);
      };
      return h('div', { class: enabled ? 'setting' : 'setting disabled' }, label,
        h('div', { class: 'stepper' },
          h('button', { class: 'icon-button', type: 'button', 'aria-label': `Less ${setting.label}`, disabled: !enabled || value === null || value <= kind.min, onclick: step(false) }, glyph('i-remove')),
          h('span', { class: 'value', text: shown(setting, wire) }),
          h('button', { class: 'icon-button', type: 'button', 'aria-label': `More ${setting.label}`, disabled: !enabled || value === null || value >= kind.max, onclick: step(true) }, glyph('i-add'))));
    }
    const draft = pianoDrafts[setting.name];
    const value = draft !== undefined ? draft : wire === undefined ? kind.min : Number(wire);
    const range = h('input', { class: 'range', type: 'range', min: kind.min, max: kind.max, step: kind.step, 'aria-label': setting.label, disabled: !enabled || wire === undefined });
    range.value = String(value);
    range.style.setProperty('--fill', `${((value - kind.min) / (kind.max - kind.min)) * 100}%`);
    const valueText = h('span', { class: 'value', text: draft === undefined && wire === undefined ? '—' : shown(setting, String(value)) });
    range.addEventListener('pointerdown', () => {
      pianoDragging = true;
    });
    range.addEventListener('change', () => {
      pianoDragging = false;
    });
    range.addEventListener('input', () => {
      const next = Number(range.value);
      pianoDrafts[setting.name] = next;
      range.style.setProperty('--fill', `${((next - kind.min) / (kind.max - kind.min)) * 100}%`);
      valueText.textContent = shown(setting, String(next));
      sendSetting(setting.name, next);
    });
    return h('div', { class: enabled ? 'setting stacked' : 'setting stacked disabled' }, label, h('div', { class: 'with-value' }, range, valueText));
  }

  function renderPiano() {
    const body = $('piano-body');
    if (!pianoTable || !state || !body || pianoDragging) return;   // a slider being dragged keeps its place
    const piano = state.piano;
    const connected = state.link.state === 'connected';
    const ready = piano.state === 'ready';
    const [instrumentLine, keyboardLine] = instrumentLines(state.instruments);
    const lines = [
      h('p', { class: 'link-line inset' }, h('span', { class: connected ? 'dot live' : 'dot' }), connected ? `Connected to ${state.link.name || 'the piano'}` : 'Not connected'),
      instrumentLine ? h('p', { class: 'meta inset', text: instrumentLine }) : null,
      keyboardLine ? h('p', { class: 'meta inset', text: keyboardLine }) : null,
    ];
    // Another MIDI piano plays (v1.11 — M29): the piano's pages and actions are Steven Piano's, hidden meanwhile.
    if (state.instruments && state.instruments.instrument && state.instruments.instrument.kind === 'midi') {
      fill(body, lines, h('p', { class: 'note inset', text: `Sound and touch, Lights and screen, Pedal and Firmware belong to Steven Piano and are hidden while ${state.instruments.instrument.name} plays.` }));
      return;
    }
    const status = !connected
      ? 'Connect to the piano to adjust its settings.'
      : piano.state === 'unsupported'
        ? "This piano's firmware doesn't offer settings over Bluetooth yet."
        : ready ? null : "Reading the piano's settings…";
    const nodes = [
      ...lines,
      status ? h('p', { class: 'note inset', text: status }) : null,
      piano.lastError ? h('div', { class: 'banner', role: 'status', text: `The piano said: ${piano.lastError}` }) : null,
    ];
    if (piano.state !== 'unsupported') {
      for (const page of pianoTable.pages) {
        const details = h('details', { class: 'disclosure', open: openGroups.has(page.key) });
        details.addEventListener('toggle', () => {
          if (details.open) openGroups.add(page.key);
          else openGroups.delete(page.key);
        });
        const children = [h('summary', null, h('span', { text: page.title }), glyph('i-chevron'))];
        if (page.key === 'feel') {
          children.push(h('h3', { class: 'section-head eyebrow', text: 'Presets' }),
            h('div', { class: 'actions' }, h('div', { class: 'chips', role: 'group', 'aria-label': 'Presets' },
              pianoTable.presets.map((preset) => chip(preset.label, false, async () => {
                try {
                  await post(ROOT + '/api/piano/preset', { name: preset.command });
                  toast(`${preset.label} applied on the piano.`);
                } catch (e) {
                  failed(e);
                }
              }, !ready)))));
        }
        for (const part of page.sections) {
          if (part.title) children.push(h('h3', { class: 'section-head eyebrow', text: part.title }));
          for (const setting of part.settings) children.push(control(setting, piano.values[setting.name], ready));
        }
        details.append(...children);
        nodes.push(details);
      }
    }
    const act = (name, label) => h('button', { class: 'outlined', type: 'button', disabled: !ready, onclick: async () => {
      try {
        await post(ROOT + '/api/piano/action', { name });
        if (name === 'status') setTimeout(pianoLoad, 2500);
        else toast(name === 'off' ? 'Every key let go.' : 'Saved on the piano.');
      } catch (e) {
        failed(e);
      }
    } }, label);
    nodes.push(h('h3', { class: 'section-head eyebrow', text: 'Actions' }), h('div', { class: 'actions' }, act('status', 'Read status'), act('off', 'All keys off'), act('save', 'Save to the piano now')));
    if (pianoTable.statusText) nodes.push(h('pre', { class: 'status-report', text: pianoTable.statusText }));
    fill(body, nodes);
  }

  // ---- Start ----------------------------------------------------------------------------------------------

  async function start() {
    renderAppearance();
    try {
      onState(await get(ROOT + '/api/state'));
    } catch (e) {
      if (e.status === 503 && e.body.error === 'offline') {
        setTimeout(start, OFFLINE_LOOK_MS);   // the offline card shows meanwhile
        return;
      }
      if (e.status !== 401) $('gate-note').textContent = e.message;
      if (e.status !== 401) showGate();
      return;
    }
    $('offline').hidden = true;
    $('gate').hidden = true;
    $('panel').hidden = false;
    show(location.hash.slice(1) || 'now');
    openSocket();
  }

  start();
})();
