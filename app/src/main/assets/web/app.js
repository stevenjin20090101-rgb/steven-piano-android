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

  /** An empty state (v1.20 — M53): a 32 px glyph over its line. */
  function emptyLine(id, text) {
    return h('p', { class: 'empty' }, glyph(id), h('span', { text }));
  }

  /** [node], an empty state of the page's own, says [text] under the glyph [id]. */
  function setEmpty(node, id, text) {
    fill(node, glyph(id), h('span', { text }));
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
    // The relay's allowance for pictures (v1.18 — M47), on every answer it relays: the last one seen widens the budget.
    const artLimit = Number(response.headers.get('X-Relay-Art-Limit'));
    if (artLimit > 0) artAllowance(artLimit);
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
   * A read of the views' bytes (v1.13 — M32): `{status: 200, buffer}`, or `{status: 202, retryAfterMs}` while the
   * tablet lays the score out; anything else is an ApiError, as call()'s (the gate on 401, the offline card on 503).
   */
  async function callBinary(path) {
    let response;
    try {
      response = await fetch(path, { method: 'GET', credentials: 'same-origin', cache: 'no-store' });
    } catch (e) {
      throw new ApiError(0, { message: "The tablet can't be reached." });
    }
    const type = response.headers.get('Content-Type') || '';
    if (response.status === 200 && type.startsWith('application/octet-stream')) return { status: 200, buffer: await response.arrayBuffer() };
    let data = null;
    if (type.includes('application/json')) {
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
    if (response.status === 401) {
      showGate();
      throw new ApiError(401, data);
    }
    if (response.status === 202) return { status: 202, retryAfterMs: (data && data.retryAfterMs) || 500 };
    throw new ApiError(response.status, data);
  }

  /**
   * The relay's answer while the piano's tablet isn't connected (v1.10 — M26, `{"error":"offline"}`,
   * 503): before the panel has its state, the offline card in place of the PIN gate (start() looks
   * again every few seconds); after, a line at the head of the window until the tablet answers again,
   * and the rail's pill.
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
    if (connection.offline !== on) {
      connection.offline = on;
      renderStatus();
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

  // ---- Appearance (the panel's own: Dark · Light · Follow system) --------------------------------
  //
  // Dark by default (v1.18 — M47: "Ink", the camera body, never pure black): the page starts with data-theme="dark" and
  // nothing stored keeps it; Follow system removes the attribute. The control is the Settings page's (host.appearance).

  const APPEARANCE = 'steven-piano-appearance';
  const APPEARANCES = [['dark', 'Dark'], ['light', 'Light'], ['system', 'Follow system']];

  function appearance() {
    try {
      const value = localStorage.getItem(APPEARANCE);
      return value === 'light' || value === 'system' ? value : 'dark';
    } catch (e) {
      return 'dark';
    }
  }

  function applyAppearance(value) {
    if (value === 'light' || value === 'dark') document.documentElement.setAttribute('data-theme', value);
    else document.documentElement.removeAttribute('data-theme');
  }

  function setAppearance(value) {
    const chosen = value === 'light' || value === 'system' ? value : 'dark';
    try {
      localStorage.setItem(APPEARANCE, chosen);
    } catch (e) {
      // Private browsing: it holds for this page only.
    }
    applyAppearance(chosen);
    renderAppearance();
  }

  /** A chip: the chosen one carries a check. */
  function chip(label, chosen, onClick, disabled) {
    return h('button', { class: 'chip', type: 'button', 'aria-pressed': chosen ? 'true' : 'false', disabled, onclick: onClick }, chosen ? glyph('i-check') : null, label);
  }

  /** The built-in Settings page's Appearance switch (while settings.js is not there), marked in place. */
  function renderAppearance() {
    const holder = $('appearance-switch');
    if (holder) segmented(holder, APPEARANCES, appearance(), setAppearance);
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
    document.body.classList.toggle('no-backdrop', next.albumBackdrop === false);   // Album colours off on the tablet (v1.15 — M41)
    render(before);
    renderViews();
  }

  function onProgress(message) {
    clockBase.ms = message.positionMs;
    clockBase.at = performance.now();
    if (typeof message.playing === 'boolean') clockBase.running = message.playing;
    if (views) views.progress(message);
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
    connection.socket = on;
    renderStatus();
  }

  // ---- The rail's head (v1.20 — M53): the mark, the name and a status dot; a pill only while something is wrong -----

  /** The socket: null until it first opens (nothing to say yet), then whether it is open; the relay's offline answer. */
  const connection = { socket: null, offline: false };

  /**
   * The dot is live while the socket is open. The pill under the name says what is wrong, and only then: the socket
   * dropped or the piano offline ("Reconnecting…"), else the piano's link down ("Piano not connected"); all well, nothing.
   */
  function renderStatus() {
    const up = connection.socket === true && !connection.offline;
    const dot = $('conn-dot');
    dot.classList.toggle('live', up);
    dot.setAttribute('aria-label', up ? 'Connected to the tablet' : connection.socket === null ? 'Connecting' : 'Reconnecting');
    let text = '';
    if (connection.socket === false || connection.offline) text = 'Reconnecting…';
    else if (up && state && state.link && state.link.state !== 'connected') text = 'Piano not connected';
    else if (up) text = 'Connected';   // in words, nothing else (v1.21, Steven): no address
    const pill = $('conn-pill');
    if (pill.textContent !== text) pill.textContent = text;
    pill.classList.toggle('ok', text === 'Connected');
    pill.hidden = !text;
  }

  // ---- Sections ---------------------------------------------------------------------------------

  // The frame (v1.18 — M47; v1.20 — M53): five places, the same in the rail (from 900 px), the tab strip below it and
  // the phones' bar below 600 px: Now playing, Library, Quiet times, Studio, Settings. Every item names its section in
  // data-section; the one shown carries aria-current. The other places of 1.19 live inside these now (route()): Up next
  // beside Now playing from 1100 px and in a sheet below it, the guests' requests at its top; the channels a segment of
  // the Library and Add music its sheet; System the first page of Settings, Guests a page there. Timed plays are gone.
  const SECTIONS = ['now', 'library', 'quiet', 'studio', 'settings'];
  const besideQuery = window.matchMedia('(min-width: 1100px)');
  const reducedQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  /** Reduced motion: every change is a cut (style.css cuts the rest). */
  const reduced = () => reducedQuery.matches;
  let section = 'now';

  /** An address of 1.19 (a bookmark, a link) opens its new home; [hash] without its '#'. */
  function route(hash) {
    switch (hash) {
      case 'queue':
        show('now');
        if (!besideQuery.matches) openSheet('queue');
        return;
      case 'requests':
        show('now');
        return;
      case 'channels':
        chooseKind('channels');
        show('library');
        return;
      case 'add':
        show('library');
        openSheet('add');
        return;
      case 'system':
        modules.settings.opening = 'system';
        show('settings');
        return;
      case 'piano':
        show('settings');
        return;
      case 'schedule':
        show('quiet');
        return;
      default:
        show(hash);
    }
  }

  function show(name) {
    const next = SECTIONS.includes(name) ? name : 'now';
    const before = section;
    section = next;
    const changed = before !== next;
    closePopover();
    if (changed) {
      closeSheets();
      staggered = new Set();   // a new visit: its lists come in again
    }
    for (const item of document.querySelectorAll('[data-section]')) {
      if (item.dataset.section === section) item.setAttribute('aria-current', 'page');
      else item.removeAttribute('aria-current');
    }
    // Where the section going out stands, before the page changes under it.
    const from = changed ? $(`section-${before}`) : null;
    const box = from && !from.hidden && !reduced() ? { top: from.offsetTop - window.scrollY, left: from.offsetLeft, width: from.offsetWidth } : null;
    for (const page of document.querySelectorAll('[data-page]')) page.hidden = page.dataset.page !== section;
    if (location.hash !== '#' + section) history.replaceState(null, '', '#' + section);
    for (const key of Object.keys(modules)) if (key !== section) moduleHide(modules[key]);
    if (section === 'library') libraryLoad();
    if (section === 'settings') openSettings();
    if (section === 'quiet') openQuiet();
    if (section === 'studio') renderStudio();
    render(state);
    if (views) views.show(section === 'now');
    if (changed) {
      window.scrollTo(0, 0);
      crossFade(from, $(`section-${section}`), box);
    }
    staggerPresent();
  }

  for (const item of document.querySelectorAll('[data-section]')) {
    item.addEventListener('click', () => show(item.dataset.section));
  }

  window.addEventListener('hashchange', () => route(location.hash.slice(1)));

  // ---- Motion (v1.20 — M53): smooth and responsive, transforms and opacity only, none under reduced motion ---------

  /**
   * A section change cross-fades: the new section fades in rising 6 px while the one going out fades where it stood,
   * laid over the page (200 ms, ease-out; style.css › Motion). Under reduced motion a cut.
   */
  let leaving = null;
  let leavingTimer = 0;
  const SECTION_MS = 200;

  function finishLeaving() {
    clearTimeout(leavingTimer);
    if (!leaving) return;
    const node = leaving;
    leaving = null;
    node.classList.remove('leaving');
    node.inert = false;
    node.removeAttribute('aria-hidden');
    for (const property of ['top', 'left', 'width']) node.style.removeProperty(property);
    node.hidden = node.dataset.page !== section;
  }

  function crossFade(from, to, box) {
    finishLeaving();
    // Nothing showed before (the panel opening on an address) or motion is reduced: a cut.
    if (!to || !from || !box || from === to || reduced()) return;
    to.classList.remove('entering');
    void to.offsetWidth;   // the animation starts again for a section shown again at once
    to.classList.add('entering');
    setTimeout(() => to.classList.remove('entering'), SECTION_MS + 40);
    leaving = from;
    from.hidden = false;
    from.inert = true;
    from.setAttribute('aria-hidden', 'true');
    from.style.setProperty('top', `${box.top}px`);
    from.style.setProperty('left', `${box.left}px`);
    from.style.setProperty('width', `${box.width}px`);
    from.classList.add('leaving');
    leavingTimer = setTimeout(finishLeaving, SECTION_MS + 20);
  }

  /**
   * Lists and cover grids come in on their first show of a visit: each item fades in rising, 30 ms after the one
   * before, the eleventh and on with the tenth (300 ms at most in all). [key] names the list; [items] are its nodes.
   */
  let staggered = new Set();
  const STAGGER_STEP_MS = 30;
  const STAGGER_LAST = 10;

  function staggerIn(key, items) {
    if (reduced() || staggered.has(key)) return;
    const nodes = Array.from(items || []).filter((node) => node.nodeType === Node.ELEMENT_NODE);
    if (nodes.length === 0) return;
    staggered.add(key);
    nodes.forEach((node, i) => {
      node.style.setProperty('--i', String(Math.min(i, STAGGER_LAST)));
      node.classList.add('enter');
    });
    setTimeout(() => nodes.forEach((node) => node.classList.remove('enter')), STAGGER_LAST * STAGGER_STEP_MS + 240);
  }

  /** The lists already drawn in the section just shown: they come in as a new list does. */
  function staggerPresent() {
    if (section === 'now') staggerUpNext($('now-side'), 'queue');
    if (section === 'library' && library.loaded && !library.asking) staggerLibrary();
  }

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
    renderStatus();
    renderNow();
    renderUpNext($('now-side'), true);
    if ($('queue-sheet').open) renderUpNext($('queue-full'), false);
    renderRequestsCount();
    if (!before || before.requests.pending !== state.requests.pending) requestsLoad();
    if (section === 'library' && !library.loaded && !library.asking && performance.now() - library.failedAt >= LIBRARY_RETRY_MS) libraryLoad();
    if (section === 'library' && before && library.category === 'channels' && !library.query && channelOf(before) !== channelOf(state)) channelsLoad();
    renderTally();
    if (section === 'studio') renderStudioState();
    if (section === 'settings') {
      if (modules.settings.page) moduleRender(modules.settings);
      else if (modules.settings.failed) renderPiano();   // the built-in page, while settings.js is not there
    }
    if (section === 'quiet') moduleRender(modules.quiet);
  }

  // ---- The sheets (v1.20 — M53): Up next below 1100 px, Add music; the editors' glass, rising as they open --------

  const SHEETS = { queue: { dialog: 'queue-sheet', opener: 'queue-open', close: 'queue-close' }, add: { dialog: 'add-sheet', opener: 'add-open', close: 'add-close' } };

  /** Opens the sheet [name] over the page (one at a time); Escape, its close button or a tap on the scrim closes it. */
  function openSheet(name) {
    const sheet = SHEETS[name];
    if (!sheet || $('panel').hidden) return;
    for (const other of Object.keys(SHEETS)) if (other !== name) closeSheet(other);
    const dialog = $(sheet.dialog);
    if (name === 'queue') {
      requestsLoad();
      renderUpNext($('queue-full'), false);
    }
    if (name === 'add') {
      if (!$('add-body').firstChild) renderAdd();
      renderUploads();
      renderTally();
    }
    if (!dialog.open) dialog.showModal();
    $(sheet.opener).setAttribute('aria-expanded', 'true');
    if (name === 'queue') staggerUpNext($('queue-full'), 'sheet');
  }

  function closeSheet(name) {
    const dialog = $(SHEETS[name].dialog);
    if (dialog.open) dialog.close();
  }

  function closeSheets() {
    for (const name of Object.keys(SHEETS)) closeSheet(name);
  }

  for (const [name, sheet] of Object.entries(SHEETS)) {
    const dialog = $(sheet.dialog);
    $(sheet.opener).addEventListener('click', () => openSheet(name));
    $(sheet.close).addEventListener('click', () => closeSheet(name));
    // Closing gives the focus back to what opened it (the dialog's own rule).
    dialog.addEventListener('close', () => {
      $(sheet.opener).setAttribute('aria-expanded', 'false');
      if (name === 'queue') staggered.delete('sheet');
    });
    // A tap on the scrim (the dialog itself, outside its body) closes it; one that began inside does not.
    let fromScrim = false;
    dialog.addEventListener('pointerdown', (event) => {
      fromScrim = event.target === dialog;
    });
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog && fromScrim) closeSheet(name);
    });
  }

  // From 1100 px Up next stands beside Now playing: its sheet gives way to it.
  besideQuery.addEventListener('change', () => {
    if (besideQuery.matches) closeSheet('queue');
  });

  const channelOf = (s) => (s && s.player.channel ? s.player.channel.key : null);

  // ---- Art ----------------------------------------------------------------------------------------
  //
  // One loader for every picture (DESIGN.md › v1.17 — M45). A box shows its title's monogram at once and its picture
  // over it once loaded, one request each, to a versioned address the browser keeps for good. Only the boxes in sight
  // ask (Now playing's at once, ahead of the rest), a few at a time and, through the relay, within a budget that leaves
  // the page's own requests room in its 120 a minute. A failed address waits before it is asked again.

  /** Loads at once: 4 through the relay, 6 on the tablet's own address. */
  const ART_PARALLEL = ROOT !== '' ? 4 : 6;
  /** Through the relay, a budget: 40 pictures at once, one more each second. */
  const ART_BUDGET = 40;
  const ART_REFILL_MS = 1000;
  /**
   * The relay's own allowance for pictures (v1.18 — M47, `X-Relay-Art-Limit`, a minute's): from 300 a minute the budget
   * through it is 120 at once, six more a second, six loads at a time; below that, or with no such header, M45's numbers.
   */
  const ART_WIDE_FROM = 300;
  const ART_NARROW = { parallel: ART_PARALLEL, budget: ART_BUDGET, refillMs: ART_REFILL_MS };
  const ART_WIDE = { parallel: 6, budget: 120, refillMs: 1000 / 6 };
  /** The numbers in force. */
  let artLimits = ART_NARROW;
  /** A failed address is asked again after 4 s, then 20 s, then 60 s, then no more. */
  const ART_RETRY_MS = [4000, 20000, 60000];
  /** Three failures in a row pause every load for 20 s: the relay's refusal lasts up to a minute. */
  const ART_PAUSE_AFTER = 3;
  const ART_PAUSE_MS = 20000;

  /** Each box's picture: its key and address, and where its load stands. */
  const artBoxes = new WeakMap();
  /** The addresses that failed: how often, and when they may be asked again. */
  const artFailures = new Map();
  /** The loads under way, by address, with the boxes waiting on each. */
  const artLoads = new Map();
  const artQueue = [];
  let artTokens = ART_BUDGET;
  let artTokensAt = performance.now();
  let artFailedInRow = 0;
  let artPausedUntil = 0;
  let artTimer = 0;
  let artTimerAt = 0;

  /** Every box but Now playing's asks once it comes within 200 px of the window; a box not displayed never does. */
  const artSight = 'IntersectionObserver' in window
    ? new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const s = artBoxes.get(entry.target);
        if (!s) continue;
        s.inSight = entry.isIntersecting;
        if (s.inSight) artWant(entry.target, s);
      }
    }, { rootMargin: '200px' })
    : null;

  /** What a box shows, `kind:id-or-composerKey:version:size`: the same key, the same picture. */
  function artKey(piece, size) {
    return `${piece.art}:${piece.art === 'portrait' ? piece.composerKey : piece.id}:${piece.artVersion || 0}:${size}`;
  }

  /** The picture's address, with its version (so it is kept for good); null when the monogram is all there is. */
  function artAddress(piece, size) {
    const id = encodeURIComponent(String(piece.id));
    const v = encodeURIComponent(String(piece.artVersion || 0));
    if (piece.art === 'cover' && Number(piece.id)) return ROOT + `/api/art/piece/${id}?kind=cover&size=${size}&v=${v}`;
    if (piece.art === 'portrait' && piece.composerKey) return ROOT + `/api/art/composer/${encodeURIComponent(piece.composerKey)}?size=${size}&v=${v}`;
    if (piece.art === 'roll' && Number(piece.id)) return ROOT + `/api/art/piece/${id}?kind=roll&v=1`;
    return null;
  }

  /** A composer (the composers list, a channel's mosaic) as [art] takes them: their portrait, else their name's monogram. */
  function portraitOf(composer) {
    return { art: composer.portrait ? 'portrait' : 'roll', composerKey: composer.key, id: 0, title: composer.name, artVersion: composer.artVersion };
  }

  /**
   * Fills an .art box for [piece] at [size] ('row' for the 44 px boxes, 'tile' for the Library's covers and the larger
   * ones, 'full' for Now playing's, v1.18 — M47):
   * its title's monogram at once, then over it its cover, its composer's portrait, or its own roll card tinted as the
   * app tints it. A box already showing that picture is left as it is. [options.priority]: asked for at once, ahead of
   * the rest (Now playing's); [options.onPicture]: given the <img> once it shows, or null for a roll card, a monogram or
   * a failure.
   */
  function art(box, piece, size, options) {
    const key = piece ? artKey(piece, size) : null;
    const known = artBoxes.get(box);
    if (known && known.key === key) {
      if (piece) setMonogram(box, piece.title);   // renamed: the picture stays
      return box;
    }
    if (artSight) artSight.unobserve(box);
    const onPicture = (options && options.onPicture) || null;
    const s = {
      key,
      address: piece ? artAddress(piece, size) : null,
      roll: !!piece && piece.art === 'roll',
      onPicture,
      priority: !!(options && options.priority),
      inSight: !artSight,
      queued: false,
      loading: false,
      done: false,
      timer: 0,
    };
    artBoxes.set(box, s);
    box.classList.remove('pictured');
    box.dataset.kind = piece ? piece.art : '';
    box.replaceChildren(...(piece ? [monogram(piece.title)] : []));
    if (onPicture && (!s.address || s.roll)) onPicture(null);   // a monogram and a roll card have no colours
    if (!s.address) s.done = true;
    else if (s.priority || !artSight) artWant(box, s);
    else artSight.observe(box);
    return box;
  }

  /** [box] in sight (or Now playing's) asks for its picture: queued, unless its address failed and waits, or gave up. */
  function artWant(box, s) {
    if (artBoxes.get(box) !== s || s.done || s.queued || s.loading) return;
    const failure = artFailures.get(s.address);
    if (failure) {
      if (failure.count > ART_RETRY_MS.length) {
        artDone(box, s);   // three tries more failed too: the monogram stays
        return;
      }
      const wait = failure.retryAt - performance.now();
      if (wait > 0) {
        // Asked again at its time, only if its box is still in sight then (else when it comes back into sight).
        if (!s.timer) {
          s.timer = setTimeout(() => {
            s.timer = 0;
            if (box.isConnected && (s.inSight || s.priority)) artWant(box, s);
          }, wait);
        }
        return;
      }
    }
    s.queued = true;
    if (s.priority) artQueue.unshift({ box, s });
    else artQueue.push({ box, s });
    artPump();
  }

  /** Starts what the limits allow, Now playing's first; a box gone from the page or out of sight by now is skipped. */
  function artPump() {
    const now = performance.now();
    if (now < artPausedUntil) {
      artWake(artPausedUntil - now);
      return;
    }
    artTokens = Math.min(artLimits.budget, artTokens + (now - artTokensAt) / artLimits.refillMs);
    artTokensAt = now;
    while (artQueue.length > 0) {
      const { box, s } = artQueue[0];
      if (artBoxes.get(box) !== s || s.done || s.loading || !box.isConnected || !(s.inSight || s.priority)) {
        artQueue.shift();
        s.queued = false;
        continue;
      }
      const under = artLoads.get(s.address);
      if (under) {   // the same picture is on its way: this box shows it too
        artQueue.shift();
        s.queued = false;
        s.loading = true;
        under.add(box);
        continue;
      }
      if (artLoads.size >= artLimits.parallel) return;   // the next starts when one ends
      if (ROOT !== '' && !s.priority && artTokens < 1) {
        artWake((1 - artTokens) * artLimits.refillMs);
        return;
      }
      artQueue.shift();
      s.queued = false;
      if (ROOT !== '') artTokens -= 1;   // Now playing's takes one too, but never waits for it
      artLoad(box, s);
    }
  }

  /**
   * The relay's allowance [limit] (a minute's pictures, from `X-Relay-Art-Limit`): 300 or more through the relay gives
   * the wide numbers, the wider budget there at once; less gives M45's again. On the tablet's own address nothing changes.
   */
  function artAllowance(limit) {
    const next = ROOT !== '' && limit >= ART_WIDE_FROM ? ART_WIDE : ART_NARROW;
    if (next === artLimits) return;
    const now = performance.now();
    artTokens = next === ART_WIDE ? next.budget : Math.min(next.budget, artTokens + (now - artTokensAt) / artLimits.refillMs);
    artTokensAt = now;
    artLimits = next;
    artPump();
  }

  /** Runs [artPump] again in [ms]: a pause's end, or the budget's next picture. */
  function artWake(ms) {
    const at = performance.now() + Math.max(16, ms);
    if (artTimer && artTimerAt <= at) return;
    clearTimeout(artTimer);
    artTimerAt = at;
    artTimer = setTimeout(() => {
      artTimer = 0;
      artPump();
    }, at - performance.now());
  }

  /** One request for [s.address]: every box waiting on it shows the picture, or keeps its monogram and waits to ask again. */
  function artLoad(box, s) {
    const address = s.address;
    const waiting = new Set([box]);
    artLoads.set(address, waiting);
    s.loading = true;
    const img = new Image();
    img.decoding = 'async';
    img.alt = '';
    const settle = (ok) => {
      artLoads.delete(address);
      if (ok) {
        artFailures.delete(address);
        artFailedInRow = 0;
      } else {
        const failure = artFailures.get(address) || { count: 0, retryAt: 0 };
        failure.count += 1;
        failure.retryAt = performance.now() + (ART_RETRY_MS[failure.count - 1] || 0);
        artFailures.set(address, failure);
        artFailedInRow += 1;
        if (artFailedInRow >= ART_PAUSE_AFTER) {
          artFailedInRow = 0;
          artPausedUntil = performance.now() + ART_PAUSE_MS;
        }
      }
      let picture = img;
      for (const each of waiting) {
        const t = artBoxes.get(each);
        if (!t || t.address !== address) continue;   // it shows something else by now
        t.loading = false;
        if (ok) {
          artShow(each, t, picture);
          picture = null;   // a second box with the same picture takes its own <img>, from the cache
        } else {
          if (t.onPicture && !t.roll) t.onPicture(null);
          artWant(each, t);   // waits for its retry, or gives up
        }
      }
      artPump();
    };
    img.addEventListener('load', () => settle(true), { once: true });
    img.addEventListener('error', () => settle(false), { once: true });
    img.src = address;
  }

  /** Lays the picture over the monogram and fades it in: a cover or a portrait as the <img>, a roll card as the .roll span's mask. */
  function artShow(box, s, img) {
    let picture;
    if (s.roll) {
      // The same address, in the cache by now; the mask goes through the CSSOM.
      picture = h('span', { class: 'roll picture' });
      picture.style.webkitMaskImage = `url("${s.address}")`;
      picture.style.maskImage = `url("${s.address}")`;
    } else {
      picture = img || h('img', { alt: '', decoding: 'async', src: s.address });
      picture.className = 'picture';
    }
    box.append(picture);
    void getComputedStyle(picture).opacity;   // where the fade starts (160 ms; none under reduced motion)
    picture.classList.add('shown');
    box.classList.add('pictured');
    artDone(box, s);
    if (s.onPicture && !s.roll) s.onPicture(picture);
  }

  /** Nothing more to load for [box]: shown, or given up. */
  function artDone(box, s) {
    s.done = true;
    if (artSight) artSight.unobserve(box);
  }

  /** A box whose row is gone: nothing more is loaded for it. */
  function artForget(box) {
    if (artSight) artSight.unobserve(box);
    artBoxes.delete(box);
  }

  /** Lets go of every box in [container], whose rows are about to be replaced (the observer itself stays). */
  function artForgetIn(container) {
    for (const box of container.querySelectorAll('.art')) artForget(box);
  }

  /** The first letter or digit of [name], in capitals; a dash when it has none. */
  function initial(name) {
    const letter = (name || '').match(/[\p{L}\p{N}]/u);
    return letter ? letter[0].toUpperCase() : '–';
  }

  function monogram(name) {
    return h('span', { class: 'monogram', text: initial(name) });
  }

  function setMonogram(box, name) {
    const mono = box.querySelector('.monogram');
    if (mono && mono.textContent !== initial(name)) mono.textContent = initial(name);
  }

  // ---- Now playing --------------------------------------------------------------------------------
  //
  // v1.18 — M47: the art view (the cover large, the title, the composer, the scrubber, the transport, a row of glass
  // capsules) or, with Notes or Score, the strip over the views; Up next beside it from 1100 px; the album's colours
  // behind the whole window while it shows (v1.15's backdrop, below). v1.20 — M53: nothing but the piece's words under
  // the cover (the instrument's lines are on Settings › System); the head's capsules say when the piano is quiet, open
  // Up next below 1100 px, and show the piano's link as a dot.

  let seeking = false;
  let shownProblem = null;

  function renderNow() {
    const player = state.player;
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
    // "Piano", a capsule in the page's head: the dot live while the piano is connected, breathing while it plays.
    const connected = state.link.state === 'connected';
    $('link-dot').classList.toggle('live', connected);
    $('link-dot').classList.toggle('breathing', connected && playing);
    $('link-state').textContent = connected ? ', connected' : ', not connected';
    renderQuiet();
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
    const quiet = state && state.quiet;
    return !!(quiet && quiet.now && !quiet.overridden);
  }

  function renderQuiet() {
    const on = quietNow();
    $('quiet-capsule').hidden = !on;
    $('quiet-override').hidden = !on;
    if (!on) return;
    const until = Number(state.quiet.until);
    const text = until > 0 ? `Quiet until ${timeOfDay(until)}` : 'Quiet now';
    if ($('quiet-text').textContent !== text) $('quiet-text').textContent = text;
  }

  $('quiet-override').addEventListener('click', async () => {
    const until = state && state.quiet ? Number(state.quiet.until) : 0;
    try {
      await post(ROOT + '/api/quiet/override');
      toast(until > 0 ? `Quiet lifted until ${timeOfDay(until)}.` : 'Quiet lifted.');
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
    const on = section === 'now' && !!state && !!state.player.piece && !!backdropPalette;
    const backdrop = $('now-backdrop');
    backdrop.hidden = !on;
    backdrop.classList.toggle('playing', on && state.player.status === 'playing');
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
      await post(ROOT + '/api/seek', { ms });
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

  /** Bravura's version: the first eight hex digits of its SHA-256 (WebAssetsTest pins it to the file). */
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
    notes: (rev) => callBinary(ROOT + `/api/now/notes?rev=${encodeURIComponent(rev)}`),
    score: (rev, w, h) => callBinary(ROOT + `/api/now/score?rev=${encodeURIComponent(rev)}&w=${Math.floor(w)}&h=${Math.floor(h)}`),
    page: (id, n) => callBinary(ROOT + `/api/now/score/${Math.floor(id)}/page/${Math.floor(n)}`),
    seek: (ms) => seekTo(Math.max(0, Math.round(ms))),
    settings: (change) => put(ROOT + '/api/settings', change),
    fontUrl: ROOT + '/api/font/bravura.otf?v=' + FONT_VERSION,
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
    if (!state) return;
    const piece = !!(state.player.piece && state.player.views);
    $('now-switch').hidden = !piece;
    if (piece) segmented($('now-switch'), VIEW_CHOICES, nowView, chooseView);
    $('section-now').classList.toggle('views-on', piece && nowView !== 'art');
    if (views) {
      views.state(state);
      return;
    }
    $('now-view').hidden = true;
    if (!piece || nowView === 'art' || viewsLoading) return;
    viewsLoading = true;
    import('./views.js').then((module) => {
      views = module.mountViews($('now-views'), viewsApi, {
        h, glyph, chip, failed, viewButton: $('now-view'), view: () => nowView,
      });
      views.show(section === 'now');
      views.state(state);
    }, () => {
      viewsLoading = false;
      toast('The score and notes could not be loaded. Reload the page.');
    });
  }

  wideQuery.addEventListener('change', renderViews);

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

  // ---- Up next --------------------------------------------------------------------------------------
  //
  // Beside Now playing from 1100 px (a glass card) and below that in a sheet its capsule opens (v1.20 — M53). At its
  // top the guests' requests waiting, each with approve and decline; then the piece playing and what follows.

  async function queueCommand(body) {
    try {
      await post(ROOT + '/api/queue', body);
    } catch (e) {
      failed(e);
    }
  }

  /** Each Up next's two parts, made once: the requests group, then the queue. */
  const upNextParts = new WeakMap();

  function upNextOf(container) {
    let parts = upNextParts.get(container);
    if (!parts) {
      parts = { requests: h('div', { class: 'requests-group', hidden: true }), queue: h('div', { class: 'queue-group' }) };
      container.replaceChildren(parts.requests, parts.queue);
      upNextParts.set(container, parts);
    }
    return parts;
  }

  /** The card beside Now playing ([compact]: no handles, the drop zone at its foot) or the sheet's list. */
  function renderUpNext(container, compact) {
    if (!container || !state) return;
    const parts = upNextOf(container);
    renderRequests(parts.requests);
    renderQueue(parts.queue, compact);
  }

  /** Up next's rows come in as a list does, once a visit (the sheet: once each time it opens). */
  function staggerUpNext(container, key) {
    const parts = upNextParts.get(container);
    if (parts) staggerIn(key, [...parts.requests.querySelectorAll('.row'), ...parts.queue.querySelectorAll('.row')]);
  }

  /** What each Up next list shows: a state message that changes none of it leaves the rows, and their pictures, as they are. */
  const queueShown = new WeakMap();

  /** Up next: the piece playing, then what follows, each row with up, down and remove, and drag where the browser has it. */
  function renderQueue(container, compact) {
    if (!container) return;
    const player = state.player;
    const items = player.queue.items;
    const hasCurrent = player.queue.index >= 0 && items.length > 0;
    const current = hasCurrent ? items[0] : null;
    const upcoming = hasCurrent ? items.slice(1) : items;
    const total = Math.max(0, player.queue.ids.length - (player.queue.index + 1));
    const shows = JSON.stringify([
      !!compact,
      current && [current.uid, current.id, current.title, current.composer, current.composerShort, current.art, current.artVersion],
      upcoming.map((item) => [item.uid, item.id, item.title, item.composerShort, item.durationMs, !!item.requested, item.art, item.artVersion]),
      total,
    ]);
    if (queueShown.get(container) === shows) return;
    queueShown.set(container, shows);
    // The boxes already here move into the new rows where they show the same picture; the rest are let go.
    const boxes = new Map();
    for (const box of container.querySelectorAll('.art')) {
      const known = artBoxes.get(box);
      if (!known || !known.key) {
        artForget(box);
        continue;
      }
      if (!boxes.has(known.key)) boxes.set(known.key, []);
      boxes.get(known.key).push(box);
    }
    const artFor = (piece) => {
      const same = boxes.get(artKey(piece, 'row'));
      return art((same && same.shift()) || h('div', { class: 'art' }), piece, 'row');
    };
    // The card names itself; the sheet's own head says Up next, so its line is the count alone.
    const count = total > 0 ? plural(total, 'piece', 'pieces') : '';
    const words = compact ? (count ? `Up next · ${count}` : 'Up next') : count;
    const head = words || upcoming.length > 0 ? h('div', { class: 'queue-head' },
      h('p', { class: 'eyebrow', text: words }),
      upcoming.length > 0 ? h('button', { class: 'text-button', type: 'button', onclick: () => queueCommand({ action: 'clear' }), text: 'Clear' }) : null) : null;
    const list = h('ul', { class: 'rows' });
    if (current) {
      // The playing row, tinted, with a small three-bar mark (still: nothing loops but the backdrop).
      list.append(h('li', { class: 'row queue-row current' },
        artFor(current),
        h('div', { class: 'text' },
          h('p', { class: 'title', text: current.title }),
          h('p', { class: 'meta', text: `Playing · ${current.composerShort || current.composer || 'Unknown composer'}` })),
        h('span', { class: 'bars', 'aria-hidden': 'true' }, h('i'), h('i'), h('i'))));
    }
    upcoming.forEach((item, index) => {
      // Its length at the right; its move and remove buttons over it on hover or focus (always on a touch screen).
      const row = h('li', { class: 'row queue-row clickable', draggable: 'true', 'data-uid': item.uid },
        compact ? null : h('span', { class: 'handle', 'aria-hidden': 'true' }, glyph('i-handle')),
        artFor(item),
        h('div', { class: 'text' },
          h('p', { class: 'title' }, item.title, item.requested ? h('span', { class: 'tag', text: 'Requested' }) : null),
          h('p', { class: 'meta' }, item.composerShort || 'Unknown composer', h('span', { class: 'meta-time', text: ` · ${clock(item.durationMs)}` }))),
        h('span', { class: 'time', text: clock(item.durationMs) }),
        h('span', { class: 'row-tools' },
          h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Move ${item.title} up`, disabled: index === 0, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'move', uid: item.uid, toIndex: index - 1 }); } }, glyph('g-up')),
          h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Move ${item.title} down`, disabled: index === upcoming.length - 1, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'move', uid: item.uid, toIndex: index + 1 }); } }, glyph('g-down')),
          h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Remove ${item.title} from the queue`, onclick: (e) => { e.stopPropagation(); queueCommand({ action: 'remove', uid: item.uid }); } }, glyph('g-x'))));
      row.addEventListener('click', () => queueCommand({ action: 'skip', uid: item.uid }));
      row.addEventListener('dragstart', (event) => {
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(item.uid));
        row.classList.add('dragging');
      });
      row.addEventListener('dragend', () => row.classList.remove('dragging'));
      // A row moved onto another; files dragged over the rows go on to the page's own drop (Add music).
      row.addEventListener('dragover', (event) => {
        if (draggingFiles(event)) return;
        event.preventDefault();
        row.classList.add('drop-before');
      });
      row.addEventListener('dragleave', () => row.classList.remove('drop-before'));
      row.addEventListener('drop', (event) => {
        if (draggingFiles(event)) return;
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
      upcoming.length === 0 ? emptyLine('g-queue', 'Nothing up next.') : null,
      compact ? dropZone() : null);
    for (const left of boxes.values()) left.forEach(artForget);
  }

  // ---- The guests' requests (v1.20 — M53: at the top of Up next) ---------------------------------------------------

  /** The requests waiting (/api/requests), read again whenever the state's number of them changes or the sheet opens. */
  const requests = { list: [], ticket: 0 };
  const requestsShown = new WeakMap();

  /** The number waiting, on Now playing's rail item and bar item and on Up next's capsule. */
  function renderRequestsCount() {
    const count = state.requests.pending;
    for (const badge of document.querySelectorAll('[data-count="requests"]')) {
      badge.hidden = count === 0;
      badge.textContent = String(count);
    }
  }

  async function requestsLoad() {
    if (!state) return;
    const ticket = ++requests.ticket;
    if (!state.requests.pending) {
      setRequests([]);
      return;
    }
    try {
      const data = await get(ROOT + '/api/requests');
      if (ticket === requests.ticket) setRequests(Array.isArray(data.pending) ? data.pending : []);
    } catch (e) {
      // Quiet: the rows stay as they were and are read again with the next change (a refused approve says why).
    }
  }

  function setRequests(list) {
    requests.list = list;
    for (const container of [$('now-side'), $('queue-full')]) {
      const parts = upNextParts.get(container);
      if (parts) renderRequests(parts.requests);
    }
  }

  /** The requests group: its eyebrow with the number, a row each with approve and decline. Hidden with none. */
  function renderRequests(holder) {
    const list = requests.list;
    const shows = JSON.stringify(list.map((r) => [r.id, r.title, r.composer, r.at]));
    if (requestsShown.get(holder) === shows) return;
    requestsShown.set(holder, shows);
    holder.hidden = list.length === 0;
    fill(holder, list.length === 0 ? null : [
      h('div', { class: 'queue-head' }, h('p', { class: 'eyebrow', text: `Requests · ${list.length}` })),
      h('ul', { class: 'rows' }, list.map(requestRow)),
    ]);
  }

  function requestRow(request) {
    const asked = Number(request.at) > 0 ? new Date(request.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : null;
    const approve = h('button', { class: 'icon-button small approve', type: 'button', 'aria-label': `Approve ${request.title}` }, glyph('g-check'));
    const decline = h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Decline ${request.title}` }, glyph('g-x'));
    approve.addEventListener('click', () => answer(request, 'approve', [approve, decline]));
    decline.addEventListener('click', () => answer(request, 'dismiss', [approve, decline]));
    return h('li', { class: 'row request-row' },
      h('div', { class: 'text' },
        h('p', { class: 'title', text: request.title }),
        h('p', { class: 'meta', text: [request.composer || 'Unknown composer', asked].filter(Boolean).join(' · ') })),
      h('span', { class: 'row-tools' }, approve, decline));
  }

  /** Approve (it joins Up next) or decline a request; its row goes at once, the state's next message says the rest. */
  async function answer(request, action, buttons) {
    for (const button of buttons) button.disabled = true;
    try {
      await post(ROOT + `/api/requests/${request.id}/${action}`);
      setRequests(requests.list.filter((r) => r.id !== request.id));
      if (action === 'approve') toast('Added to Up next.');
    } catch (e) {
      failed(e);
      for (const button of buttons) button.disabled = false;
      requestsLoad();
    }
  }

  // ---- Library -------------------------------------------------------------------------------------

  // The genre switch (v1.14 — M37): All · Classical · Modern above the search, kept per browser as Appearance is.
  // Pieces, playlists, composers and a composer's page follow it; a playlist opens whole.
  const LIBRARY_SCOPE = 'libraryScope';
  const GENRES = [['all', 'All'], ['classical', 'Classical'], ['modern', 'Modern']];
  const SEARCH_WORDS = { all: 'Search titles and composers', classical: 'Search Classical titles and composers', modern: 'Search Modern titles and artists' };

  function storedGenre() {
    try {
      const value = localStorage.getItem(LIBRARY_SCOPE);
      return value === 'classical' || value === 'modern' ? value : 'all';
    } catch (e) {
      return 'all';
    }
  }

  // Covers · List (v1.18 — M47), remembered in this browser: covers from 900 px, the rows below, until one is chosen.
  const LIBRARY_VIEW = 'steven-piano-library-view';
  const LIBRARY_VIEWS = [['covers', 'Covers'], ['list', 'List']];

  function storedLibraryView() {
    try {
      const value = localStorage.getItem(LIBRARY_VIEW);
      if (value === 'covers' || value === 'list') return value;
    } catch (e) {
      // Private browsing: the width decides.
    }
    return window.matchMedia('(min-width: 900px)').matches ? 'covers' : 'list';
  }

  const library = {
    category: 'all', query: '', offset: 0, total: 0, pieces: [], view: null, genre: storedGenre(), shows: storedLibraryView(), emptyText: '',
    emptyGlyph: 'g-note',
    listKey: '',        // the list drawn last, as its stagger names it (v1.20 — M53)
    loaded: false,      // the list asked for last is drawn
    asking: false,      // a list or a group is on its way
    failedAt: -Infinity,
  };
  const PAGE = 50;

  // Every list the Library asks for takes a ticket (v1.18 — M47): its answer is drawn whenever it arrives, unless a newer
  // list was asked for meanwhile, whatever the section or the state. A list that could not be read (refused through the
  // relay, the piano offline, the session ended) leaves the Library to ask again when it next shows, or with a state
  // message while it shows, at most every 5 s: never tools over an empty page that waits for a tap.
  const LIBRARY_RETRY_MS = 5000;
  let libraryAsked = 0;

  /**
   * Asks the tablet with [load] and draws its answer with [draw]. [list]: the Library's own list (a category's first
   * page, a search, the playlists, the composers), which, unread, is asked for again; a page more, a playlist or a
   * composer opened that can't be read leaves what shows as it is.
   */
  async function libraryAsk(load, draw, list) {
    const ticket = ++libraryAsked;
    library.asking = true;
    try {
      const answer = await load();
      if (ticket !== libraryAsked) return;   // a newer list was asked for: its answer is the one drawn
      draw(answer);
      library.loaded = true;
    } catch (e) {
      if (ticket === libraryAsked && list) {
        library.loaded = false;
        library.failedAt = performance.now();
      }
      failed(e);
    } finally {
      if (ticket === libraryAsked) library.asking = false;
    }
  }

  /** Under Modern the Library says artist where it says composer (v1.14 — M37). */
  const artists = () => library.genre === 'modern';
  const unknownName = () => (artists() ? 'Unknown artist' : 'Unknown composer');

  /** [path] as the switch asks for it: with `?genre=` unless it is on All. */
  const scoped = (path) => (library.genre === 'all' ? path : `${path}?genre=${library.genre}`);

  /**
   * A segmented control (v1.14 — M37, the tablet's): [options] as [key, label] pairs (or [key, label, glyph], v1.20 —
   * M53) in [holder]'s capsule, the one [current] names pressed; a press calls [onChoose] with its key. Built once (again
   * when its words change), then marked in place, so the thumb fades.
   */
  const segmentedShown = new WeakMap();

  function segmented(holder, options, current, onChoose) {
    const words = options.map(([key, label, icon]) => `${key}:${label}:${icon || ''}`).join('|');
    if (segmentedShown.get(holder) !== words || holder.childElementCount !== options.length) {
      segmentedShown.set(holder, words);
      holder.replaceChildren(...options.map(([key, label, icon]) => h('button', { type: 'button', 'data-key': key, onclick: () => onChoose(key) },
        icon ? [glyph(icon), h('span', { text: label })] : label)));
    }
    for (const button of holder.children) button.setAttribute('aria-pressed', button.dataset.key === current ? 'true' : 'false');
  }

  // The Library's four lists (v1.20 — M53): Pieces (All · Favorites · Recent under it) · Playlists · Composers (Artists
  // under Modern) · Channels, each with its glyph; a search shows pieces, whichever is chosen.
  const PIECE_CATEGORIES = ['all', 'favorites', 'recent'];
  const PIECE_CHIPS = [['all', 'All'], ['favorites', 'Favorites'], ['recent', 'Recent']];
  const libraryKinds = () => [['pieces', 'Pieces', 'g-note'], ['playlists', 'Playlists', 'g-playlist'], ['composers', artists() ? 'Artists' : 'Composers', 'g-person'], ['channels', 'Channels', 'g-channels']];
  const kindOf = () => (library.query || PIECE_CATEGORIES.includes(library.category) ? 'pieces' : library.category);

  /**
   * One of the four, chosen: the search clears and a playlist or composer open closes; Pieces keeps its chip. Whatever
   * list was on its way is for the one before; the new one is asked for when the Library shows (or at once, by its press).
   */
  function chooseKind(kind) {
    library.category = kind !== 'pieces' ? kind : PIECE_CATEGORIES.includes(library.category) ? library.category : 'all';
    library.query = '';
    library.view = null;
    library.loaded = false;
    library.asking = false;
    libraryAsked += 1;
    $('lib-search').value = '';
  }

  function renderGenre() {
    segmented($('lib-kind'), libraryKinds(), kindOf(), (kind) => {
      chooseKind(kind);
      libraryLoad(true);
    });
    segmented($('lib-genre'), GENRES, library.genre, chooseGenre);
    segmented($('lib-view'), LIBRARY_VIEWS, library.shows, chooseLibraryView);
    $('lib-genre').hidden = kindOf() === 'channels';   // the channels are every genre's
    $('lib-search').placeholder = SEARCH_WORDS[library.genre];
  }

  /** Covers or rows for the lists of pieces: remembered in this browser, the pieces shown laid out again. */
  function chooseLibraryView(value) {
    if (value === library.shows) return;
    try {
      localStorage.setItem(LIBRARY_VIEW, value);
    } catch (e) {
      // Private browsing: it holds for this page only.
    }
    library.shows = value;
    segmented($('lib-view'), LIBRARY_VIEWS, library.shows, chooseLibraryView);
    if (!$('lib-view').hidden) renderPieces(library.pieces, library.emptyText, library.emptyGlyph);
  }

  /** Another genre: remembered in this browser; a playlist or composer open closes, the chip and the search stay. */
  function chooseGenre(value) {
    if (value === library.genre) return;
    try {
      localStorage.setItem(LIBRARY_SCOPE, value);
    } catch (e) {
      // Private browsing: it holds for this page only.
    }
    library.genre = value;
    library.view = null;
    libraryLoad(true);
  }

  /** Under Pieces, with no search: All · Favorites · Recent. */
  function renderChips() {
    const holder = $('lib-chips');
    holder.hidden = kindOf() !== 'pieces' || !!library.query;
    if (holder.hidden) return;
    holder.replaceChildren(...PIECE_CHIPS.map(([key, label]) => chip(label, library.category === key, () => {
      library.category = key;
      library.view = null;
      libraryLoad(true);
    })));
  }

  $('lib-search').addEventListener('input', debounce(() => {
    library.query = $('lib-search').value.trim();
    library.view = null;
    libraryLoad(true);
  }, 250));

  $('lib-more').querySelector('button').addEventListener('click', () => libraryPage(library.offset));

  /** The list the switches and the search say: asked for when forced, else unless drawn or on its way. */
  function libraryLoad(force) {
    if (!force && (library.loaded || library.asking)) return undefined;
    renderGenre();
    renderChips();
    $('lib-crumb').hidden = true;
    if (library.query) return libraryPage(0);
    if (library.category === 'playlists') return playlistsLoad();
    if (library.category === 'composers') return composersLoad();
    if (library.category === 'channels') return channelsLoad();
    return libraryPage(0);
  }

  function libraryPage(offset) {
    const params = new URLSearchParams({ category: library.query ? 'all' : library.category, offset: String(offset), limit: String(PAGE) });
    if (library.query) params.set('q', library.query);
    if (library.genre !== 'all') params.set('genre', library.genre);
    return libraryAsk(() => get(ROOT + `/api/library?${params}`), (page) => {
      library.pieces = offset === 0 ? page.pieces : library.pieces.concat(page.pieces);
      library.total = page.total;
      library.offset = offset + page.pieces.length;
      library.listKey = library.query ? 'search' : library.category;
      renderPieces(library.pieces, library.query ? 'Nothing matches that search.' : 'No pieces here yet.', library.query ? 'g-search' : 'g-note');
      $('lib-more').hidden = library.offset >= library.total;
    }, offset === 0);
  }

  /**
   * The Library's body: the rows (or covers) of a list, or the channels' cards. The one going away lets go of its
   * pictures.
   */
  function showListBody(channels) {
    $('lib-channels').hidden = !channels;
    $('lib-rows').hidden = channels;
    if (channels) {
      artForgetIn($('lib-rows'));
      $('lib-rows').replaceChildren();
      $('lib-more').hidden = true;
      $('lib-view').hidden = true;
    } else {
      artForgetIn($('channel-tiles'));
      $('channel-tiles').replaceChildren();
    }
  }

  /** A list's items come in once a visit (v1.20 — M53): each list by its own name. */
  function staggerLibrary() {
    const items = $('lib-channels').hidden ? $('lib-rows').children : $('channel-tiles').children;
    staggerIn(`library:${library.listKey}`, items);
  }

  /** A list of pieces (a category's, a playlist's, a composer's, a search's): a grid of covers, or the rows. */
  function renderPieces(pieces, emptyText, emptyGlyph) {
    showListBody(false);
    const list = $('lib-rows');
    artForgetIn(list);   // the rows go: nothing keeps watching them
    library.pieces = pieces;
    library.emptyText = emptyText;
    library.emptyGlyph = emptyGlyph || 'g-note';
    const covers = library.shows === 'covers';
    list.className = covers ? 'cover-grid' : 'rows card';
    $('lib-view').hidden = false;
    const ids = pieces.map((p) => p.id);
    list.replaceChildren(...pieces.map((piece) => (covers ? pieceTile(piece, ids) : pieceRow(piece, ids))));
    $('lib-empty').hidden = pieces.length > 0;
    setEmpty($('lib-empty'), library.emptyGlyph, emptyText);
    staggerLibrary();
  }

  /** The playlists' and the composers' lists stay rows; Covers · List is for pieces. */
  function rowsOnly() {
    showListBody(false);
    $('lib-rows').className = 'rows card';
    $('lib-view').hidden = true;
  }

  /**
   * A piece's tile: its cover (the tile's size, 14 px corners), title and composer; a tap plays it (and the list after
   * it) as a row's does; its More button, a small glass circle at the cover's top right, plays it next or adds it.
   */
  function pieceTile(piece, queue) {
    return h('li', { class: 'cover-tile' },
      h('button', { class: 'tile-play', type: 'button', onclick: () => play(piece.id, queue) },
        art(h('span', { class: 'art' }), piece, 'tile'),
        h('span', { class: 'title', text: piece.title }),
        h('span', { class: 'meta', text: piece.composerShort || unknownName() })),
      h('button', { class: 'icon-button tile-more glass', type: 'button', 'aria-label': `More for ${piece.title}`, 'aria-haspopup': 'menu', onclick: (e) => { e.stopPropagation(); openMenu(e.currentTarget, piece, queue); } }, glyph('i-more')));
  }

  /** A piece's row: its art, title and composer with its length; a tap plays it (and the list after it); its menu plays it next or adds it. */
  function pieceRow(piece, queue) {
    const row = h('li', { class: 'row clickable' },
      art(h('div', { class: 'art' }), piece, 'row'),
      h('div', { class: 'text' },
        h('p', { class: 'title', text: piece.title }),
        h('p', { class: 'meta', text: [piece.composerShort || unknownName(), clock(piece.durationMs)].join(' · ') })),
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
      // The cover picker (v1.18 — M48), for a piece from the library: one made here (Studio's, a recording) has no genre.
      ...(piece.genre === 'classical' || piece.genre === 'modern' ? [['Find a cover…', () => findCover(piece, anchor)]] : []),
    ]);
  }

  /**
   * The cover picker (v1.18 — M48, covers.js), loaded the first time it is asked for. When its sheet closes the focus goes
   * back to the piece's More ([anchor]), and a cover chosen or taken away shows at once.
   */
  function findCover(piece, anchor) {
    import('./covers.js').then((m) => m.open(host, piece)).then((changed) => {
      if (anchor && anchor.isConnected) anchor.focus();
      if (changed) coverChanged(piece);
    }, () => toast("The cover picker couldn't be loaded."));
  }

  /**
   * A piece's cover chosen or taken away (v1.18 — M48): the state is read again now, so Now playing and Up next take the
   * new artVersion without waiting for its next message, and the piece is read again (a search for its title holds it),
   * so its rows and tiles in the Library's list show the new art without a reload; the rest keep their pictures.
   */
  async function coverChanged(piece) {
    get(ROOT + '/api/state').then(onState).catch(() => {});
    if ($('lib-view').hidden || !library.pieces.some((p) => p.id === piece.id)) return;
    let fresh = null;
    try {
      const page = await get(ROOT + `/api/library?${new URLSearchParams({ q: piece.title, limit: '200' })}`);
      fresh = page.pieces.find((p) => p.id === piece.id) || null;
    } catch (e) {
      return;   // the list shows what it showed; it is read afresh when it is next asked for
    }
    if (!fresh || $('lib-view').hidden) return;
    const size = library.shows === 'covers' ? 'tile' : 'row';
    const items = $('lib-rows').children;
    library.pieces.forEach((p, i) => {
      if (p.id !== piece.id) return;
      p.art = fresh.art;
      p.artVersion = fresh.artVersion;
      const box = items[i] && items[i].querySelector('.art');
      if (box) art(box, p, size);
    });
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

  function playlistsLoad() {
    return libraryAsk(() => get(scoped(ROOT + '/api/playlists')), ({ playlists }) => {
      $('lib-more').hidden = true;
      artForgetIn($('lib-rows'));
      rowsOnly();
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
      setEmpty($('lib-empty'), 'g-playlist', 'No playlists yet.');
      library.listKey = 'playlists';
      staggerLibrary();
    }, true);
  }

  function openPlaylist(list) {
    return libraryAsk(() => get(ROOT + `/api/playlists/${list.id}`), (detail) => {
      showGroup(detail.playlist.name, [detail.playlist.builtIn ? 'Built in' : null, plural(detail.pieces.length, 'piece', 'pieces')].filter(Boolean).join(' · '), detail.pieces,
        (shuffle) => post(ROOT + '/api/play-all', { playlistId: list.id, shuffle }));
    }, false);
  }

  function composersLoad() {
    return libraryAsk(() => get(scoped(ROOT + '/api/composers')), ({ composers }) => {
      $('lib-more').hidden = true;
      artForgetIn($('lib-rows'));
      rowsOnly();
      $('lib-rows').replaceChildren(...composers.map((composer) => {
        const row = h('li', { class: 'row clickable' },
          art(h('div', { class: 'art' }), portraitOf(composer), 'row'),
          h('div', { class: 'text' },
            h('p', { class: 'title', text: composer.name || unknownName() }),
            h('p', { class: 'meta', text: plural(composer.pieceCount, 'piece', 'pieces') })),
          glyph('i-chevron'));
        row.addEventListener('click', () => openComposer(composer));
        return row;
      }));
      $('lib-empty').hidden = composers.length > 0;
      setEmpty($('lib-empty'), 'g-person', artists() ? 'No artists yet.' : 'No composers yet.');
      library.listKey = 'composers';
      staggerLibrary();
    }, true);
  }

  function openComposer(composer) {
    return libraryAsk(() => get(scoped(ROOT + `/api/composers/${encodeURIComponent(composer.key)}`)), (detail) => {
      const ids = detail.pieces.map((p) => p.id);
      showGroup(detail.composer.name || unknownName(), plural(detail.pieces.length, 'piece', 'pieces'), detail.pieces,
        (shuffle) => post(ROOT + '/api/play-all', { ids, shuffle }));
    }, false);
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
    library.listKey = 'group';
    renderPieces(pieces, 'Nothing in here yet.', 'g-playlist');
  }

  // ---- Channels (v1.20 — M53: the Library's fourth list) --------------------------------------------------------

  /** The channels' cards in the Library's body, and Stop the channel over them while one plays. */
  function channelsLoad() {
    return libraryAsk(() => get(ROOT + '/api/channels'), ({ channels, playing }) => {
      showListBody(true);
      $('channel-bar').hidden = !playing;
      $('lib-empty').hidden = channels.length > 0;
      setEmpty($('lib-empty'), 'g-channels', 'The channels are being worked out from the library.');
      artForgetIn($('channel-tiles'));
      $('channel-tiles').replaceChildren(...channels.map(channelTile));
      library.listKey = 'channels';
      staggerLibrary();
    }, true);
  }

  /** A channel's card: a mosaic of the composers it holds most, its name on the band, and how many pieces, or Playing with the dot. */
  function channelTile(channel) {
    const cells = channel.composers.slice(0, 4);
    const mosaic = h('div', { class: cells.length > 1 ? 'mosaic' : 'mosaic one' });
    if (cells.length === 0) mosaic.append(monogram(channel.name));
    for (const composer of cells.length === 3 ? cells.concat(cells[0]) : cells) mosaic.append(art(h('div', { class: 'art' }), portraitOf(composer), 'tile'));
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

  // ---- Add music (v1.20 — M53: a sheet the Library's head opens) ------------------------------------------------

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
      h('button', { class: 'outlined', type: 'button', onclick: () => input.click() }, glyph('g-plus'), options.button),
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

  // Files dropped anywhere on the page (v1.20 — M53, the Add page's being a sheet now): MIDI files and zips on Now
  // playing or the Library go to the tablet as the sheet's zone sends them, the sheet opening on their progress, and
  // recordings on Studio as its zone does. A drop a zone took is its own; elsewhere a drop is let go, never opened by
  // the browser in the panel's place.
  const draggingFiles = (event) => !!event.dataTransfer && Array.from(event.dataTransfer.types || []).includes('Files');
  const dropsHere = () => section === 'now' || section === 'library' || section === 'studio';

  document.addEventListener('dragover', (event) => {
    if (!draggingFiles(event) || $('panel').hidden) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = dropsHere() ? 'copy' : 'none';
  });

  document.addEventListener('drop', (event) => {
    if (!draggingFiles(event) || $('panel').hidden || event.defaultPrevented) return;   // a zone took it
    event.preventDefault();
    const files = event.dataTransfer.files;
    if (!files || files.length === 0) return;
    if (section === 'studio') addRecordings(files);
    else if (section === 'now' || section === 'library') addFiles(files);
  });

  function renderAdd() {
    $('add-body').replaceChildren(
      dropZone(),
      h('ul', { class: 'rows card uploads', id: 'upload-rows' }),
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
    openSheet('add');   // where they show going
    renderUploads();
    sendNext();
  }

  /** The uploads' lists (the Add sheet's, Studio's): the rows there stay as they are, so their bars ease; new ones come in at the top. */
  function renderUploads() {
    for (const id of ['upload-rows', 'studio-upload-rows']) {
      const list = $(id);
      if (!list) continue;
      const rows = uploads.filter((upload) => upload.list === id).slice(0, 50).map(uploadRow);
      if (rows.length !== list.children.length || rows.some((row, i) => list.children[i] !== row)) list.replaceChildren(...rows);
    }
  }

  /** One file sent, or waiting: its name, its size and how it went, and its bar while it goes; made once, then kept up to date. */
  function uploadRow(upload) {
    if (!upload.row) {
      upload.meta = h('p', { class: 'meta' });
      upload.fill = h('span');
      upload.bar = h('div', { class: 'progress' }, upload.fill);
      upload.row = h('li', { class: 'row' }, h('div', { class: 'text' }, h('p', { class: 'title', text: upload.name }), upload.meta, upload.bar));
    }
    const line = `${size(upload.size)} · ${upload.status}`;
    if (upload.meta.textContent !== line) upload.meta.textContent = line;
    upload.fill.style.setProperty('--fill', `${Math.round(upload.progress * 100)}%`);
    upload.bar.hidden = !(upload.pending || upload.sending);
    return upload.row;
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
    closeSheet('add');
    chooseKind('playlists');
    library.loaded = true;   // the playlist itself shows, not the list beneath it first
    rowsOnly();
    artForgetIn($('lib-rows'));
    $('lib-rows').replaceChildren();
    show('library');
    renderGenre();
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
        h('ul', { class: 'rows card', id: 'studio-models' }),
        h('h2', { class: 'section-head eyebrow', text: 'Transcribe' }),
        zoneFor({
          accept: 'audio/*,' + AUDIO_EXTENSIONS.map((e) => '.' + e).join(','),
          multiple: true,
          title: 'Drop a piano recording here',
          meta: '.wav, .mp3, .m4a, .flac, .ogg and the like, up to 200 MB. About a minute per three minutes of audio.',
          button: 'Choose recordings',
          onFiles: addRecordings,
        }),
        h('ul', { class: 'rows card uploads', id: 'studio-upload-rows' }),
        h('h2', { class: 'section-head eyebrow', text: 'Compose' }),
        h('div', { id: 'studio-compose' }),
        h('h2', { class: 'section-head eyebrow', id: 'studio-jobs-head', text: 'Jobs', hidden: true }),
        h('ul', { class: 'rows card', id: 'studio-jobs' })));
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

  /** The models and the jobs, as the tablet reports them in its state; their rows kept in place, so a bar eases. */
  function renderStudioState() {
    const body = $('studio-parts');
    if (!body || !state || !state.studio) return;
    const studio = state.studio;
    const unavailable = $('studio-unavailable');
    unavailable.hidden = studio.available;
    if (!studio.available) setEmpty(unavailable, 'g-studio', studio.reason || '');
    body.hidden = !studio.available;
    keyedRows($('studio-models'), studio.models, (model) => model.name, studioRow, (row, model) => {
      setStudioRow(row, model.title, model.line, model.progress === null || model.progress === undefined ? undefined : model.progress);
    });
    if (!composing) renderCompose();   // the note follows the model; an open form is left as it is
    $('studio-jobs-head').hidden = studio.jobs.length === 0;
    keyedRows($('studio-jobs'), studio.jobs, (job) => job.id, studioRow, (row, job) => {
      setStudioRow(row, job.state === 'done' && job.title ? job.title : job.name, job.line, job.state === 'running' ? job.progress : undefined);
      const cancellable = job.state === 'queued' || job.state === 'running';
      if (cancellable && !row.cancel) {
        row.cancel = h('button', { class: 'outlined', type: 'button', 'aria-label': `Cancel ${job.name}`, onclick: () => cancelJob(job.id) }, 'Cancel');
        row.node.append(row.cancel);
      } else if (!cancellable && row.cancel) {
        row.cancel.remove();
        row.cancel = null;
      }
    });
  }

  /**
   * [list]'s rows for [items], one per [keyOf], each made once by [make] (→ {node, …}) and brought up to date by
   * [update]: a row that stays keeps its place in the page, so its bar eases to each new value (v1.20 — M53).
   */
  const keyedShown = new WeakMap();

  function keyedRows(list, items, keyOf, make, update) {
    const known = keyedShown.get(list) || new Map();
    const next = new Map();
    const nodes = items.map((item) => {
      const key = keyOf(item);
      const row = known.get(key) || make(item);
      update(row, item);
      next.set(key, row);
      return row.node;
    });
    keyedShown.set(list, next);
    if (nodes.length !== list.children.length || nodes.some((node, i) => list.children[i] !== node)) list.replaceChildren(...nodes);
  }

  /** A Studio row: its title, its line, and a 2 px bar under them. */
  function studioRow() {
    const title = h('p', { class: 'title' });
    const line = h('p', { class: 'meta' });
    const fill = h('span');
    const bar = h('div', { class: 'progress' }, fill);
    return { node: h('li', { class: 'row' }, h('div', { class: 'text' }, title, line, bar)), title, line, bar, fill, cancel: null };
  }

  /** [progress]: undefined, no bar; null, under way with no measure yet (the sweep); else 0–1. */
  function setStudioRow(row, title, line, progress) {
    if (row.title.textContent !== title) row.title.textContent = title;
    if (row.line.textContent !== (line || '')) row.line.textContent = line || '';
    row.bar.hidden = progress === undefined;
    row.bar.classList.toggle('waiting', progress === null);
    row.fill.style.setProperty('--fill', `${Math.round((Number(progress) || 0) * 100)}%`);
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
    const body = $('settings-body');
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
      fill(body, h('div', { class: 'content builtin-settings' }, lines, h('p', { class: 'note inset', text: `Sound and touch, Lights and screen, Pedal and Firmware belong to Steven Piano and are hidden while ${state.instruments.instrument.name} plays.` }), appearanceCard()));
      renderAppearance();
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
    nodes.push(appearanceCard());
    fill(body, h('div', { class: 'content builtin-settings' }, nodes));
    renderAppearance();
  }

  /**
   * The panel's own Appearance (v1.18 — M47), on the built-in page: the frame no longer holds it, and settings.js offers
   * it on its Panel page through host.appearance. Dark · Light · Follow system, kept in this browser.
   */
  function appearanceCard() {
    return h('div', { class: 'card appearance-card' },
      h('div', { class: 'card-head' }, h('h2', { text: 'Panel' })),
      h('div', { class: 'setting' },
        h('div', { class: 'label' }, 'Appearance', h('span', { class: 'meta', text: 'In this browser only' })),
        h('div', { class: 'segmented', id: 'appearance-switch', role: 'group', 'aria-label': 'Appearance' })));
  }

  // ---- The pages' modules: Settings (v1.18 — M47b; System and Guests among its pages, v1.20 — M53) and Quiet times ----
  //
  // settings.js (the section settings) and quiet.js (the section quiet, v1.20 — M54's) are ES modules written apart from
  // this file. Each loads the first time its section shows and exports create(host, body, tools) → { show(), hide(),
  // render(state) }; settings.js may also have open(page), for an address that names one of its pages (#system).
  // system.js loads after the first state, for the rail's foot (vitals(host, node)), and Settings makes its System page
  // with system.js's create(), through host.system(). While settings.js is not there the built-in page shows; while
  // quiet.js is not there Quiet times shows its head alone; while system.js is not there the rail's foot is empty and
  // Settings has no System page. Nothing a module does can stop the panel: every call into one is caught.

  /** What a module may use: the panel's helpers, its calls (every address from ROOT), the art loader, the sections. */
  const host = Object.freeze({
    ROOT,
    RELAYED: ROOT !== '',
    h,
    fill,
    glyph,
    chip,
    get,
    put,
    post,
    toast,
    failed,
    state: () => state,
    clock,
    plural,
    signed,
    debounce,
    art,
    show: (name) => route(name),
    appearance: Object.freeze({ get: appearance, set: setAppearance }),
    confirm,
    /** system.js (v1.20 — M53: Settings' System page): a promise of the module, or of null while it is not there. */
    system: () => loadSystem(),
  });

  const modules = {
    settings: { body: 'settings-body', tools: 'settings-tools', load: () => import('./settings.js'), loading: null, module: null, page: null, showing: false, failed: false, opening: null },
    quiet: { body: 'quiet-body', tools: 'quiet-tools', load: () => import('./quiet.js'), loading: null, module: null, page: null, showing: false, failed: false },
  };

  /** [key]'s module, imported once: it, or null (and failed) while it is not there or not one. */
  function loadModule(key) {
    const m = modules[key];
    if (!m.loading) {
      m.loading = m.load().then((module) => {
        if (!module || typeof module.create !== 'function') throw new Error(`${key}: no create()`);
        m.module = module;
        return module;
      }).catch(() => {
        m.failed = true;
        return null;
      });
    }
    return m.loading;
  }

  /** system.js, imported once for the rail's foot and Settings' System page: it, or null while it is not there. */
  let systemLoading = null;
  function loadSystem() {
    if (!systemLoading) systemLoading = import('./system.js').then((module) => module || null, () => null);
    return systemLoading;
  }

  /** Runs [fn] for a module; what it throws is kept from the panel. */
  function guarded(fn) {
    try {
      return fn();
    } catch (e) {
      if (window.console) console.error(e);
      return undefined;
    }
  }

  /** The module's page, made the first time it shows, in its section's body and head tools; null when it fails. */
  function modulePage(m) {
    if (!m.page && m.module && !m.failed) {
      m.page = guarded(() => m.module.create(host, $(m.body), $(m.tools))) || null;
      if (!m.page) m.failed = true;
    }
    return m.page;
  }

  function moduleShow(m) {
    if (!modulePage(m) || m.showing) return;
    m.showing = true;
    guarded(() => m.page.show());
    moduleRender(m);
  }

  function moduleHide(m) {
    if (!m.page || !m.showing) return;
    m.showing = false;
    guarded(() => m.page.hide());
  }

  function moduleRender(m) {
    if (m.page && m.showing && state) guarded(() => m.page.render(state));
  }

  /** Settings: settings.js's page once it is in (on the page an address named, #system); the built-in page without it. */
  function openSettings() {
    const m = modules.settings;
    if (m.failed) {
      pianoLoad();
      return;
    }
    loadModule('settings').then(() => {
      if (section !== 'settings') return;
      if (!modulePage(m)) {
        pianoLoad();
        return;
      }
      moduleShow(m);
      const page = m.opening;
      m.opening = null;
      if (page && typeof m.page.open === 'function') guarded(() => m.page.open(page));
    });
  }

  /** Quiet times: quiet.js's page once it is in; while it is not there, the section's head alone. */
  function openQuiet() {
    const m = modules.quiet;
    if (m.failed) return;
    loadModule('quiet').then(() => {
      if (section === 'quiet' && modulePage(m)) moduleShow(m);
    });
  }

  /** After the first state: system.js, if it is there, keeps the rail's foot. */
  let systemStarted = false;
  function startSystem() {
    if (systemStarted) return;
    systemStarted = true;
    loadSystem().then((module) => {
      if (module && typeof module.vitals === 'function') guarded(() => module.vitals(host, $('rail-vitals')));
    });
  }

  /**
   * A small glass dialog: [title], [message], Cancel and [action]'s button; [run] is called on the action. Escape, a tap
   * outside and Cancel close it; the focus starts on Cancel and goes back where it was.
   */
  function confirm({ title, message, action, run }) {
    const cancel = h('button', { class: 'outlined', type: 'button', text: 'Cancel' });
    const act = h('button', { class: 'outlined filled', type: 'button', text: action || 'OK' });
    const dialog = h('dialog', { class: 'confirm', 'aria-labelledby': 'confirm-title', 'aria-describedby': message ? 'confirm-message' : null },
      h('div', { class: 'confirm-body' },
        h('h2', { id: 'confirm-title', text: title || '' }),
        message ? h('p', { class: 'note', id: 'confirm-message', text: message }) : null,
        h('div', { class: 'confirm-actions' }, cancel, act)));
    const close = () => {
      if (dialog.open) dialog.close();
    };
    dialog.addEventListener('close', () => dialog.remove());
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) close();   // the scrim
    });
    cancel.addEventListener('click', close);
    act.addEventListener('click', () => {
      close();
      if (typeof run === 'function') Promise.resolve().then(run).catch(failed);
    });
    document.body.append(dialog);
    dialog.showModal();
    cancel.focus();
  }

  // ---- Start ----------------------------------------------------------------------------------------------

  async function start() {
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
    startSystem();
    route(location.hash.slice(1) || 'now');
    openSocket();
  }

  start();
})();
