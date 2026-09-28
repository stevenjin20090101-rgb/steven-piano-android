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
// go into the page as text only.

'use strict';

(function () {
  const $ = (id) => document.getElementById(id);

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

  /** "30 s", "4 min". */
  function wait(seconds) {
    return seconds < 60 ? `${seconds} s` : `${Math.ceil(seconds / 60)} min`;
  }

  async function ask(piece, button) {
    button.disabled = true;
    note('');
    let response;
    try {
      response = await fetch('/api/public/request', {
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
      $('catalogue').hidden = true;
      $('thanks').hidden = false;
      $('thanks-title').textContent = body.status === 'pending' ? "Thanks — it joins the queue once it's approved." : "Thanks — it's in the queue.";
      $('thanks-piece').textContent = [piece.title, piece.composer].filter(Boolean).join(' · ');
      window.scrollTo(0, 0);
      return;
    }
    button.disabled = false;
    if (response.status === 429) note(`One request every five minutes. Try again in ${wait(body.retryAfter || 300)}.`);
    else if (response.status === 403) showClosed();
    else if (response.status === 503) note('The list of requests is full for now. Try again later.');
    else note(body.message || 'That request did not go through.');
  }

  function showClosed() {
    $('catalogue').hidden = true;
    $('closed').hidden = false;
  }

  async function load() {
    let data;
    try {
      const response = await fetch('/api/public/catalogue', { credentials: 'same-origin', cache: 'no-store' });
      data = await response.json();
    } catch (e) {
      note("The piano can't be reached. Try again in a moment.", true);
      return;
    }
    if (!data.open) {
      showClosed();
      return;
    }
    const lists = data.lists || [];
    if (lists.length === 0) {
      $('closed').textContent = 'There is nothing to ask for yet.';
      showClosed();
      return;
    }
    $('catalogue').replaceChildren(...lists.map((list) => h('section', null,
      h('h2', { class: 'section-head eyebrow', text: list.name }),
      h('ul', { class: 'rows' }, list.pieces.map((piece) => {
        const button = h('button', { class: 'outlined', type: 'button', 'aria-label': `Request ${piece.title}`, text: 'Request' });
        button.addEventListener('click', () => ask(piece, button));
        return h('li', { class: 'row' },
          h('div', { class: 'text' },
            h('p', { class: 'title', text: piece.title }),
            h('p', { class: 'meta', text: piece.composer || 'Unknown composer' })),
          button);
      })))));
  }

  load();
})();
