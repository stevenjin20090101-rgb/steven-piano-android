/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// Up next (DESIGN.md › v1.20 — M53), a section of the panel's frame: beside Now playing from 1100 px and in its sheet
// below that, the guests' requests at its top. A module of its own since v1.27 (M63): the frame (app.js) makes it once,
// create(host) → { render(before), present(), fillSheet(), staggerSheet(), queueCommand(body) }; the Library plays next
// and adds through the host's queueCommand. It asks the tablet only through the host, from host.ROOT.

import { $, clock, plural } from './ui.js';

export function create(host) {
  const { h, fill, glyph, get, post, toast, failed, staggerIn, art, artKey, artKeyOf, artForget, draggingFiles, dropZone } = host;
  /** The panel's state, the frame's: read where it is used, so never an old one. */
  const state = () => host.state();

  // ---- Up next --------------------------------------------------------------------------------------
  //
  // Beside Now playing from 1100 px (a glass card) and below that in a sheet its capsule opens (v1.20 — M53). At its
  // top the guests' requests waiting, each with approve and decline; then the piece playing and what follows.

  /** An empty state (v1.20 — M53): a 32 px glyph over its line. */
  function emptyLine(id, text) {
    return h('p', { class: 'empty' }, glyph(id), h('span', { text }));
  }

  async function queueCommand(body) {
    try {
      await post(host.ROOT + '/api/queue', body);
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
    if (!container || !state()) return;
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
    const player = state().player;
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
      const key = artKeyOf(box);
      if (!key) {
        artForget(box);
        continue;
      }
      if (!boxes.has(key)) boxes.set(key, []);
      boxes.get(key).push(box);
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
    const count = state().requests.pending;
    for (const badge of document.querySelectorAll('[data-count="requests"]')) {
      badge.hidden = count === 0;
      badge.textContent = String(count);
    }
  }

  async function requestsLoad() {
    if (!state()) return;
    const ticket = ++requests.ticket;
    if (!state().requests.pending) {
      setRequests([]);
      return;
    }
    try {
      const data = await get(host.ROOT + '/api/requests');
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
      await post(host.ROOT + `/api/requests/${request.id}/${action}`);
      setRequests(requests.list.filter((r) => r.id !== request.id));
      if (action === 'approve') toast('Added to Up next.');
    } catch (e) {
      failed(e);
      for (const button of buttons) button.disabled = false;
      requestsLoad();
    }
  }

  /** The state come (the frame's render): the card, the sheet's list while it is open, the count, the requests read again when their number changed. */
  function render(before) {
    renderUpNext($('now-side'), true);
    if ($('queue-sheet').open) renderUpNext($('queue-full'), false);
    renderRequestsCount();
    if (!before || before.requests.pending !== state().requests.pending) requestsLoad();
  }

  /** The sheet opening (the frame's openSheet): the requests read again and its list drawn, before it shows. */
  function fillSheet() {
    requestsLoad();
    renderUpNext($('queue-full'), false);
  }

  return {
    render,
    present: () => staggerUpNext($('now-side'), 'queue'),
    fillSheet,
    staggerSheet: () => staggerUpNext($('queue-full'), 'sheet'),
    queueCommand,
  };
}
