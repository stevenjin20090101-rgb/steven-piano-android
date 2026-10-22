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
// markup. The content security policy allows this file and nothing inline. Since v1.27 (M63) an ES
// module and the frame alone: the sign-in gate, the socket, call(), the router, the rail, the
// sheets, the toasts, the art loader and the uploads. Each section is a module of its own (now.js,
// upnext.js, library.js, studio.js), made here through the host as the pages' modules are; the
// helpers the modules share are ui.js's.

import { $, guarded, linkDot, paintDot } from './ui.js';
import { create as createNowPlaying } from './now.js';
import { create as createUpNext } from './upnext.js';
import { create as createLibrary } from './library.js';
import { create as createStudio } from './studio.js';

(function () {
  // ---- Small helpers --------------------------------------------------------------------------

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

  /** [node], an empty state of the page's own, says [text] under the glyph [id]. */
  function setEmpty(node, id, text) {
    fill(node, glyph(id), h('span', { text }));
  }

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

  /** A number with a true minus sign. */
  const signed = (n) => (n < 0 ? '−' + -n : String(n));

  // ---- The API --------------------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || `The tablet answered ${status}.`);
      this.status = status;
      this.body = body || {};
    }
  }

  /**
   * A request to the tablet: JSON both ways; every change carries the panel's header, which no other site's page can send.
   * Listen, Test every key and Install the update answer 403 `confirm` until the session has confirmed it's you (v1.24 —
   * M56): the PIN is asked for (confirmIt) and the request sent once more; closed without it, the request fails quietly.
   */
  async function call(method, path, body, confirmed) {
    const init = { method, credentials: 'same-origin', cache: 'no-store', headers: {} };
    if (method !== 'GET') init.headers['X-Steven-Piano'] = '1';
    if (method !== 'GET') {
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
    if (response.status === 403 && data && data.error === 'confirm' && !confirmed) {
      if (await confirmIt(path)) return call(method, path, body, true);
      const declined = new ApiError(403, data);
      declined.quiet = true;   // the person closed the PIN's dialog: nothing to say
      throw declined;
    }
    if (!response.ok) throw new ApiError(response.status, data);
    return data;
  }

  /** What "Confirm it's you" names each request it asks for (v1.24 — M56). */
  function confirmFor(path) {
    if (path === ROOT + '/api/listen/start') return 'listen to the room';
    if (path === ROOT + '/api/keytest/start') return 'test every key';
    if (path === ROOT + '/api/system/tool') return 'install the update';
    return 'go on';
  }

  /**
   * "Confirm it's you" (v1.24 — M56): the tablet's PIN in the dialog's PIN mode, sent to /api/confirm; true once the tablet
   * took it. One dialog at a time: a second request asking meanwhile waits for the same answer.
   */
  let confirming = null;
  function confirmIt(path) {
    if (!confirming) {
      confirming = confirm({
        title: "Confirm it's you",
        message: `Enter the tablet's PIN to ${confirmFor(path)}.`,
        action: 'Confirm',
        pin: true,
        run: async (pin) => {
          try {
            await call('POST', ROOT + '/api/confirm', { pin }, true);
          } catch (e) {
            // The third wrong PIN ended the session (call() has shown the gate): the gate says why.
            if (e && e.status === 401 && e.body && e.body.message) $('gate-note').textContent = e.body.message;
            throw e;
          }
        },
      }).catch(() => false).then((ok) => {
        confirming = null;
        if (!ok && !$('gate').hidden) $('pin').focus();   // the third wrong PIN ended the session: the sign-in gate
        return ok;
      });
    }
    return confirming;
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
    if (e && (e.status === 401 || e.quiet)) return;
    toast(e && e.message ? e.message : 'That did not work.');
  }

  // ---- Appearance (the panel's own: Dark · Light · Follow system) --------------------------------
  //
  // Dark by default (v1.18 — M47: "Ink", the camera body, never pure black): the page starts with data-theme="dark" and
  // nothing stored keeps it; Follow system removes the attribute. The control is the Settings page's (host.appearance).

  const APPEARANCE = 'steven-piano-appearance';

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
  }

  /** A chip: the chosen one carries a check. */
  function chip(label, chosen, onClick, disabled) {
    return h('button', { class: 'chip', type: 'button', 'aria-pressed': chosen ? 'true' : 'false', disabled, onclick: onClick }, chosen ? glyph('i-check') : null, label);
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

  function onState(next) {
    const before = state;
    state = next;
    sections.now.received(next);
    document.body.classList.toggle('mono', !!next.monochrome);
    document.body.classList.toggle('no-backdrop', next.albumBackdrop === false);   // Album colours off on the tablet (v1.15 — M41)
    render(before);
    sections.now.renderViews();
  }

  function onProgress(message) {
    sections.now.progress(message);
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
      else if (message.type === 'audio') onAudio(message);   // v1.23 — M55: the tablet's microphone, to this session alone
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

  /**
   * The tablet's microphone (v1.23 — M55): the socket carries its frames only to the sessions that asked to hear it; each
   * goes to the listeners a module gave through host.audio (the System page's Listen and test card), and nowhere else.
   */
  const audioListeners = new Set();

  function onAudio(message) {
    for (const listener of audioListeners) guarded(() => listener(message));
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
   * The dot is the piano's link (v1.27 — M63, ui.js's linkDot: a ring, ink, or red only while notes reach the piano), as is
   * Now playing's "Piano" capsule's, painted here with it; while the tablet can't be reached it is a ring. The pill under
   * the name says what is wrong, and only then: the socket dropped or the piano offline ("Reconnecting…"), else the
   * piano's link down ("Piano not connected"); all well, nothing.
   */
  function renderStatus() {
    const up = connection.socket === true && !connection.offline;
    const dot = $('conn-dot');
    const shown = linkDot(state && state.link, connection.socket !== false && !connection.offline);
    paintDot(dot, shown);
    dot.setAttribute('aria-label', LINK_WORDS[shown]);
    paintDot($('link-dot'), shown);
    $('link-state').textContent = `, ${LINK_WORDS[shown].toLowerCase()}`;
    let text = '';
    if (connection.socket === false || connection.offline) text = 'Reconnecting…';
    else if (up && state && state.link && state.link.state !== 'connected') text = 'Piano not connected';
    else if (up) text = 'Connected';   // in words, nothing else (v1.21, Steven): no address
    const pill = $('conn-pill');
    if (pill.textContent !== text) pill.textContent = text;
    pill.classList.toggle('ok', text === 'Connected');
    pill.hidden = !text;
  }

  /** The dot's words (the tablet's rail reads its mark so). */
  const LINK_WORDS = { off: 'Not connected', on: 'Connected', live: 'Sending notes to the piano' };

  // ---- Sections ---------------------------------------------------------------------------------

  // The frame (v1.18 — M47; v1.20 — M53): five places, the same in the rail (from 900 px), the tab strip below it and
  // the phones' bar below 600 px: Now playing, Library, Quiet times, Studio, Settings. Every item names its section in
  // data-section; the one shown carries aria-current. The other places of 1.19 live inside these now: Up next beside Now
  // playing from 1100 px and in a sheet below it, the guests' requests at its top; the channels a segment of the Library
  // and Add music its sheet; System the first page of Settings, Guests a page there. Timed plays are gone, and 1.19's
  // addresses for those places with them (v1.27 — M63): an address names one of the five, else Now playing.
  const SECTIONS = ['now', 'library', 'quiet', 'studio', 'settings'];
  const besideQuery = window.matchMedia('(min-width: 1100px)');
  const reducedQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  /** Reduced motion: every change is a cut (style.css cuts the rest). */
  const reduced = () => reducedQuery.matches;
  let section = 'now';

  function show(name) {
    const next = SECTIONS.includes(name) ? name : 'now';
    const before = section;
    section = next;
    const changed = before !== next;
    sections.now.closePopover();
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
    if (section === 'library') sections.library.show();
    if (section === 'settings') openSettings();
    if (section === 'quiet') openQuiet();
    if (section === 'studio') sections.studio.show();
    render(state);
    sections.now.shown(section === 'now');
    if (changed) {
      window.scrollTo(0, 0);
      crossFade(from, $(`section-${section}`), box);
    }
    staggerPresent();
  }

  for (const item of document.querySelectorAll('[data-section]')) {
    item.addEventListener('click', () => show(item.dataset.section));
  }

  window.addEventListener('hashchange', () => show(location.hash.slice(1)));

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
    if (section === 'now') sections.upNext.present();
    if (section === 'library') sections.library.present();
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
    sections.now.render();
    sections.upNext.render(before);
    sections.library.render(before);
    if (section === 'studio') sections.studio.render();
    if (section === 'settings') moduleRender(modules.settings);
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
    if (name === 'queue') sections.upNext.fillSheet();
    if (name === 'add') sections.library.fillAdd();
    if (!dialog.open) dialog.showModal();
    $(sheet.opener).setAttribute('aria-expanded', 'true');
    if (name === 'queue') sections.upNext.staggerSheet();
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

  /** The key of the picture [box] shows or waits for ([artKey]'s), or null: Up next keeps the boxes of a picture it shows again. */
  function artKeyOf(box) {
    const s = artBoxes.get(box);
    return s ? s.key : null;
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

  // ---- Uploads (v1.20 — M53; the frame's since v1.27 — M63) ------------------------------------------------------
  //
  // Files sent to the tablet one at a time, as it takes them: MIDI files and zips from Add music (the Library's) and
  // recordings from Studio, each in its own list. The drop zones are made here; a drop anywhere else on the page goes to
  // its section's.

  const uploads = [];
  let uploading = false;

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
    if (section === 'studio') sections.studio.drop(files);
    else if (section === 'now' || section === 'library') sections.library.drop(files);
  });

  /** [list]'s uploads (a section's, each with its route, its list and its words) join the rest at the top and go in turn. */
  function addUploads(list) {
    for (const upload of list) uploads.unshift(upload);
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

  // ---- The pages' modules: Settings (v1.18 — M47b; System and Guests among its pages, v1.20 — M53) and Quiet times ----
  //
  // settings.js (the section settings) and quiet.js (the section quiet, v1.20 — M54's) are ES modules written apart from
  // this file. Each loads the first time its section shows and exports create(host, body, tools) → { show(), hide(),
  // render(state) }. system.js loads after the first state, for the rail's foot (vitals(host, node)), and Settings makes
  // its System page with system.js's create(), through host.system(). While settings.js or quiet.js is not there its
  // section shows its head alone (the built-in Settings page went in v1.27 — M63); while system.js is not there the rail's
  // foot is empty and Settings has no System page. Nothing a module does can stop the panel: every call into one is caught.

  /**
   * What a module may use: the panel's helpers, its calls (every address from ROOT), the art loader, the sections. The
   * sections' own modules (v1.27 — M63) use more of it: the section shown and the router, the motion, the sheets, the
   * uploads, and each other's parts (Up next's queue, Add music's drop zone).
   */
  const host = Object.freeze({
    ROOT,
    RELAYED: ROOT !== '',
    h,
    fill,
    glyph,
    chip,
    setEmpty,
    segmented,
    get,
    put,
    post,
    callBinary,
    toast,
    failed,
    state: () => state,
    /** The state read again now, not waiting for the socket's next (a piece's cover changed). */
    readState: () => get(ROOT + '/api/state').then(onState),
    signed,
    appearance: Object.freeze({ get: appearance, set: setAppearance }),
    confirm,
    section: () => section,
    show,
    reduced,
    staggerIn,
    openSheet,
    closeSheet,
    art,
    artKey,
    artKeyOf,
    artForget,
    artForgetIn,
    monogram,
    zoneFor,
    draggingFiles,
    addUploads,
    renderUploads,
    /** Up next's queue (the Library's Play next and Add to queue). */
    queueCommand: (body) => sections.upNext.queueCommand(body),
    /** Add music's drop zone (the Library's), at the foot of Up next's card. */
    dropZone: () => sections.library.dropZone(),
    /** system.js (v1.20 — M53: Settings' System page): a promise of the module, or of null while it is not there. */
    system: () => loadSystem(),
    /** The microphone's frames (v1.23 — M55): [listener] hears each `audio` message; the function returned lets it go. */
    audio: (listener) => {
      audioListeners.add(listener);
      return () => audioListeners.delete(listener);
    },
  });

  /** The sections (v1.27 — M63): each its own module, made once, here, before the first state. */
  const sections = {
    now: createNowPlaying(host),
    upNext: createUpNext(host),
    library: createLibrary(host),
    studio: createStudio(host),
  };

  const modules = {
    settings: { body: 'settings-body', tools: 'settings-tools', load: () => import('./settings.js'), loading: null, module: null, page: null, showing: false, failed: false },
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

  /** Settings: settings.js's page once it is in; while it is not there, the section's head alone. */
  function openSettings() {
    const m = modules.settings;
    if (m.failed) return;
    loadModule('settings').then(() => {
      if (section === 'settings' && modulePage(m)) moduleShow(m);
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
   * outside and Cancel close it; the focus starts on Cancel and goes back where it was. With [pin] (v1.24 — M56, "Confirm
   * it's you"): a six-digit field, where the focus starts, and [run] is given the PIN and waited for: the dialog closes once
   * it resolves; a wrong PIN is said under the field, with a countdown while the tablet asks for a wait (as the sign-in
   * gate's); an ended session (401) closes it, the gate showing. Resolves true once [run] has run (with [pin], once it
   * succeeded), false when the dialog closed without it.
   */
  function confirm({ title, message, action, run, pin }) {
    return new Promise((resolve) => {
      let ran = false;
      let timer = null;
      const cancel = h('button', { class: 'outlined', type: 'button', text: 'Cancel' });
      const act = h('button', { class: 'outlined filled', type: pin ? 'submit' : 'button', text: action || 'OK' });
      const field = pin
        ? h('input', {
          class: 'pin-field', type: 'password', inputmode: 'numeric', pattern: '[0-9]*', maxlength: '6', autocomplete: 'off', autofocus: true,
          'aria-label': 'PIN', 'aria-describedby': 'confirm-note',
        })
        : null;
      const note = pin ? h('p', { class: 'note', id: 'confirm-note', role: 'status', 'aria-live': 'polite' }) : null;
      const body = h(pin ? 'form' : 'div', { class: 'confirm-body', novalidate: !!pin },
        h('h2', { id: 'confirm-title', text: title || '' }),
        message ? h('p', { class: 'note', id: 'confirm-message', text: message }) : null,
        field,
        note,
        h('div', { class: 'confirm-actions' }, cancel, act));
      const dialog = h('dialog', { class: 'confirm', 'aria-labelledby': 'confirm-title', 'aria-describedby': message ? 'confirm-message' : null }, body);
      const close = () => {
        if (dialog.open) dialog.close();
      };
      dialog.addEventListener('close', () => {
        clearInterval(timer);
        dialog.remove();
        resolve(ran);
      });
      dialog.addEventListener('click', (event) => {
        if (event.target === dialog) close();   // the scrim
      });
      cancel.addEventListener('click', close);
      if (!pin) {
        act.addEventListener('click', () => {
          ran = true;
          close();
          if (typeof run === 'function') Promise.resolve().then(run).catch(failed);
        });
      } else {
        /** The tablet's wait, counted down in seconds under the field, which waits it out. */
        const wait = (seconds, lead) => {
          clearInterval(timer);
          let left = seconds;
          const tick = () => {
            if (left <= 0) {
              clearInterval(timer);
              note.textContent = '';
              field.disabled = false;
              act.disabled = false;
              field.focus();
              return;
            }
            note.textContent = `${lead} Try again in ${left} s.`;
            left -= 1;
          };
          field.disabled = true;
          act.disabled = true;
          tick();
          timer = setInterval(tick, 1000);
        };
        field.addEventListener('input', () => {
          const digits = field.value.replace(/\D/g, '').slice(0, 6);
          if (digits !== field.value) field.value = digits;
        });
        body.addEventListener('submit', async (event) => {
          event.preventDefault();
          if (act.disabled) return;
          const digits = field.value.replace(/\D/g, '');
          if (digits.length !== 6) {
            note.textContent = 'The PIN is six digits.';
            return;
          }
          act.disabled = true;
          try {
            await run(digits);
            ran = true;
            close();
            return;
          } catch (e) {
            field.value = '';
            const status = e && e.status;
            const error = e && e.body && e.body.error;
            const seconds = (e && e.body && e.body.retryAfter) || 0;
            if (status === 401) {
              close();
              return;
            }
            if (status === 403 && error === 'wrong') {
              if (seconds > 0) wait(seconds, "That PIN isn't right.");
              else note.textContent = "That PIN isn't right.";
            } else if (status === 429) {
              wait(seconds || 30, 'Too many tries.');
            } else {
              note.textContent = (e && e.message) || 'That did not work.';
            }
          }
          if (!field.disabled) {
            act.disabled = false;
            field.focus();
          }
        });
      }
      document.body.append(dialog);
      dialog.showModal();
      (field || cancel).focus();
    });
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
    show(location.hash.slice(1) || 'now');
    openSocket();
  }

  start();
})();
