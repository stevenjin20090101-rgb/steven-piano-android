/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The request page (DESIGN.md › v1.5.1 — M18): the pieces guests may ask for, each with Request.
// No PIN and no free text: a request is a piece's id from the list, one every five minutes. Titles
// go into the page as text only. Since v1.14 (M37) the lists name their genre: a switch (All ·
// Classical · Modern) shows when there are both, and a search over the rows already here when there
// are more than a few; neither asks the tablet anything. A list shows 200 rows at a time. Since
// v1.20 (M54), while a quiet time holds the piano (the catalogue's `quiet`), a line under the head
// says until when: a request still goes in, and waits in the queue.

'use strict';

(function () {
  const $ = (id) => document.getElementById(id);

  // Where the page lives (v1.10 — M26): '' on the tablet's own address, '/p/<id>' through Steven Piano
  // Cloud's relay; the requests are built from it.
  const ROOT = location.pathname.replace(/\/request$/, '');

  function h(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? '' : String(value));
    }
    for (const child of children.flat()) {
      if (child !== null && child !== undefined && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  let noteTimer = null;

  /**
   * A line at the foot of the screen, where it is seen wherever the list is scrolled (a Request
   * tapped far down the list must not answer out of sight). It goes after a few seconds, unless
   * [stay]: the page has nothing else to show (the piano can't be reached).
   */
  function note(text, stay) {
    const node = $('guest-note');
    node.textContent = text;
    node.hidden = !text;
    clearTimeout(noteTimer);
    if (text && !stay) noteTimer = setTimeout(() => { node.hidden = true; }, NOTE_MS);
  }

  const NOTE_MS = 8000;

  const GENRES = [['all', 'All'], ['classical', 'Classical'], ['modern', 'Modern']];

  /** More pieces than this: the search shows. */
  const SEARCH_FROM = 20;

  /** Rows a list shows at first, and each Show more adds. */
  const PAGE = 200;

  /** The catalogue as it came, and the switch's choice. */
  let lists = [];
  let genre = 'all';

  /** "30 s", "4 min". */
  function wait(seconds) {
    return seconds < 60 ? `${seconds} s` : `${Math.ceil(seconds / 60)} min`;
  }

  /** "9:30" on this phone's clock; a weekday before it when it is a day or more away. */
  function clock(ms) {
    const at = new Date(ms);
    const time = `${at.getHours()}:${String(at.getMinutes()).padStart(2, '0')}`;
    return ms - Date.now() >= 24 * 3600 * 1000 ? `${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][at.getDay()]} ${time}` : time;
  }

  /**
   * While a quiet time holds the piano (v1.20 — M54, `quiet: {until}` in the catalogue; null otherwise): a line under
   * the head, "The piano is resting until 9:30. Your request will wait until then." It stays with the thanks.
   */
  function resting(quiet) {
    let line = $('guest-resting');
    if (!quiet || !quiet.until) {
      if (line) line.hidden = true;
      return;
    }
    if (!line) {
      line = h('p', { id: 'guest-resting', class: 'note', role: 'status' });
      const head = document.querySelector('.guest-head');
      if (head) head.append(line);
      else $('catalogue').before(line);
    }
    line.textContent = `The piano is resting until ${clock(quiet.until)}. Your request will wait until then.`;
    line.hidden = false;
  }

  async function ask(piece, button) {
    button.disabled = true;
    note('');
    let response;
    try {
      response = await fetch(ROOT + '/api/public/request', {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pieceId: piece.id }),
      });
    } catch (e) {
      note("The piano can't be reached. Try again in a moment.");
      button.disabled = false;
      return;
    }
    let body = {};
    try {
      body = await response.json();
    } catch (e) {
      body = {};
    }
    if (response.status === 202) {
      $('guest-tools').hidden = true;
      $('catalogue').hidden = true;
      $('thanks').hidden = false;
      $('thanks-title').textContent = body.status === 'pending' ? "Thanks — it joins the queue once it's approved." : "Thanks — it's in the queue.";
      $('thanks-piece').textContent = [piece.title, piece.composer].filter(Boolean).join(' · ');
      window.scrollTo(0, 0);
      return;
    }
    button.disabled = false;
    if (response.status === 503 && body.error === 'offline') note("The piano is offline just now. Try again in a moment.");
    else if (response.status === 429) note(`One request every five minutes. Try again in ${wait(body.retryAfter || 300)}.`);
    else if (response.status === 403) showClosed();
    else if (response.status === 503) note('The list of requests is full for now. Try again later.');
    else note(body.message || 'That request did not go through.');
  }

  function showClosed() {
    $('guest-tools').hidden = true;
    $('catalogue').hidden = true;
    $('closed').hidden = false;
  }

  /** Text as the search compares it: lower case, accents set aside. */
  function fold(text) {
    return String(text || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  }

  /** The switch's three buttons, built once, then pressed as the choice is (the panel's segmented control). */
  function renderSwitch() {
    const group = $('genre');
    if (!group.childElementCount) {
      group.append(...GENRES.map(([key, label]) => h('button', { type: 'button', 'data-key': key, text: label, onclick: () => choose(key) })));
    }
    for (const button of group.children) button.setAttribute('aria-pressed', button.dataset.key === genre ? 'true' : 'false');
  }

  function choose(key) {
    if (key === genre) return;
    genre = key;
    renderSwitch();
    renderLists();
  }

  /** The lists the switch shows (all of them under All), each cut to the search's matches; a list with none is left out. */
  function renderLists() {
    const wanted = fold($('search').value.trim());
    const sections = [];
    for (const list of lists) {
      if (genre !== 'all' && list.genre && list.genre !== genre) continue;
      const pieces = wanted ? list.pieces.filter((piece) => piece.words.some((words) => words.includes(wanted))) : list.pieces;
      if (pieces.length > 0) sections.push(section(list.name, pieces));
    }
    $('catalogue').replaceChildren(...(sections.length > 0 ? sections
      : [h('p', { class: 'empty', text: wanted ? 'Nothing matches that search.' : 'There is nothing to ask for yet.' })]));
  }

  /** A list's heading and its rows, [PAGE] at a time: Show more adds the next ones. */
  function section(name, pieces) {
    const rows = h('ul', { class: 'rows' });
    const more = h('div', { class: 'more-button' });
    let shown = 0;
    const next = () => {
      rows.append(...pieces.slice(shown, shown + PAGE).map(row));
      shown = Math.min(pieces.length, shown + PAGE);
      more.hidden = shown >= pieces.length;
    };
    more.append(h('button', { class: 'outlined', type: 'button', text: 'Show more', onclick: next }));
    next();
    return h('section', null, h('h2', { class: 'section-head eyebrow', text: name }), rows, more);
  }

  function row(piece) {
    const button = h('button', { class: 'outlined', type: 'button', 'aria-label': `Request ${piece.title}`, text: 'Request' });
    button.addEventListener('click', () => ask(piece, button));
    return h('li', { class: 'row' },
      h('div', { class: 'text' },
        h('p', { class: 'title', text: piece.title }),
        h('p', { class: 'meta', text: piece.composer || 'Unknown composer' })),
      button);
  }

  let searchTimer = null;
  $('search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(renderLists, 150);
  });

  async function load() {
    let data;
    try {
      const response = await fetch(ROOT + '/api/public/catalogue', { credentials: 'same-origin', cache: 'no-store' });
      data = await response.json();
    } catch (e) {
      note("The piano can't be reached. Try again in a moment.", true);
      return;
    }
    if (data.error === 'offline') {
      note('The piano is offline just now. This page looks again in a moment.', true);
      setTimeout(load, 10000);
      return;
    }
    note('');
    if (!data.open) {
      showClosed();
      return;
    }
    resting(data.quiet);
    lists = data.lists || [];
    if (lists.length === 0) {
      $('closed').textContent = 'There is nothing to ask for yet.';
      showClosed();
      return;
    }
    for (const list of lists) {
      for (const piece of list.pieces) piece.words = [fold(piece.title), fold(piece.composer)];
    }
    const genres = new Set(lists.map((list) => list.genre));
    $('genre').hidden = !(genres.has('classical') && genres.has('modern'));
    $('search').hidden = lists.reduce((n, list) => n + list.pieces.length, 0) <= SEARCH_FROM;
    $('guest-tools').hidden = $('genre').hidden && $('search').hidden;
    renderSwitch();
    renderLists();
  }

  load();
})();
