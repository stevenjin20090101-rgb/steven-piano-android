/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The panel's cover picker (DESIGN.md › v1.18 — M48): an ES module the frame (app.js) imports the first time a piece's
// menu is asked to Find a cover…, open(host, piece) → a promise, once the sheet closes, of whether the piece's cover
// changed. A sheet in the editors' glass (the dialog's classes, as host.confirm's): the piece's title and composer, a
// search of Apple's catalogue that the tablet makes (one search in 4 s), the covers found as a grid of tiles (the
// picture at 96 px, the album, the artist), each a button that makes it the piece's own, and, for a piece with a cover
// of its own, Remove this piece's cover. Every element is built with DOM calls, what Apple and the library say goes in
// as text, the pictures only as the tablet's own data: addresses, sizes go through the CSSOM, and every request is
// built from host.ROOT.

const TITLE = 'Find a cover';
const SEARCHING = 'Searching…';
const FETCHING = 'Fetching the cover…';
const NOTHING = "Nothing found. Try the album's name or the artist's.";
const SLOW_DOWN = 'Apple asked to slow down. Try again in a minute.';
const EXPIRED = 'Those results have expired. Search again.';
const CHANGED = 'Cover changed.';
const REMOVED = 'Cover removed.';

/** The search as the tablet takes it: trimmed, 2 to 80 characters. */
const MIN_TEXT = 2;
const MAX_TEXT = 80;

/** A tile's picture, in CSS px: Apple's 100 px artwork, shown at 96. */
const TILE_PX = 96;

/** The only pictures a tile shows: the tablet's own data: addresses, a JPEG or a PNG. */
const PICTURE = /^data:image\/(?:jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$/;

/** The sheet open now: a second open while it shows does nothing. */
let current = null;

/**
 * Opens the cover picker for [piece] (a piece as the Library lists it: id, title, composerShort, art). Resolves, once
 * the sheet closes, true when the piece's cover was chosen or taken away, else false.
 */
export function open(host, piece) {
  if (current || !piece || !Number(piece.id)) return Promise.resolve(false);
  return new Promise((resolve) => {
    current = picker(host, piece, resolve);
  });
}

function picker(host, piece, resolve) {
  const { h, fill } = host;
  const composer = piece.composerShort || piece.composer || '';
  let searchId = null;
  let working = false;
  let changed = false;

  // A text field, not type=search: its first Escape would clear the words filled in rather than close the sheet.
  const field = h('input', {
    type: 'text', 'aria-label': "Search Apple's catalogue", autocomplete: 'off', spellcheck: 'false', maxlength: String(MAX_TEXT), enterkeyhint: 'search',
  });
  field.value = [piece.title, composer].filter(Boolean).join(' ').slice(0, MAX_TEXT);
  const go = h('button', { class: 'text-button', type: 'button', text: 'Search' });
  go.style.flex = 'none';   // its word whole at a phone's width: the field gives way instead
  const status = h('p', { class: 'note', role: 'status', 'aria-live': 'polite', hidden: true });
  const grid = h('ul', { class: 'cover-grid', 'aria-label': 'Covers found', hidden: true });
  // The Library's tiles at 96 px: fixed columns, spread across the sheet's width.
  grid.style.gridTemplateColumns = `repeat(auto-fill, ${TILE_PX}px)`;
  grid.style.justifyContent = 'space-between';
  const remove = piece.art === 'cover' ? h('button', { class: 'text-button', type: 'button', text: "Remove this piece's cover" }) : null;
  const cancel = h('button', { class: 'outlined', type: 'button', text: 'Cancel' });

  const dialog = h('dialog', { class: 'sheet confirm', 'aria-labelledby': 'cover-picker-title' },
    h('div', { class: 'confirm-body' },
      h('h2', { id: 'cover-picker-title', text: TITLE }),
      h('div', null, h('p', { text: piece.title || '' }), composer ? h('p', { class: 'meta', text: composer }) : null),
      h('div', { class: 'field', role: 'search' }, host.glyph('g-search'), field, go),
      status,
      grid,
      h('div', { class: 'confirm-actions' }, remove, cancel)));

  /** One line of what is happening, or none. */
  function say(text) {
    status.textContent = text || '';
    status.hidden = !text;
  }

  /** Search waits for a search's 2 characters, and for the one under way. */
  function ready() {
    go.disabled = working || field.value.trim().length < MIN_TEXT;
  }

  /** The tablet's refusal in the sheet's words: Apple's stop with when it lifts, the floor, old results, else the tablet's own. */
  function refusal(e) {
    const body = (e && e.body) || {};
    if (e.status === 503 && body.error === 'busy' && Number(body.blockedUntil) > 0) {
      // When it lifts, on the 24-hour clock as the System page says it.
      const at = new Date(Number(body.blockedUntil));
      return `${SLOW_DOWN.replace(' in a minute.', '')} after ${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}.`;
    }
    if (e.status === 429) return SLOW_DOWN;
    if (e.status === 404) return EXPIRED;
    return e && e.message ? e.message : 'That did not work.';
  }

  /** A sign-in that ended: the sheet makes way for the PIN gate (the frame has shown it). */
  function signedOut(e) {
    if (e && e.status === 401) {
      close();
      return true;
    }
    return false;
  }

  async function search() {
    const text = field.value.trim();
    if (working || text.length < MIN_TEXT) return;
    working = true;
    ready();
    say(SEARCHING);
    try {
      const answer = await host.post(host.ROOT + '/api/covers/search', { text });
      searchId = answer && typeof answer.searchId === 'string' ? answer.searchId : null;
      const tiles = (answer && Array.isArray(answer.results) ? answer.results : [])
        .filter((r) => r && Number.isInteger(r.index) && typeof r.picture === 'string' && PICTURE.test(r.picture))
        .map(tile);
      fill(grid, tiles);
      grid.hidden = tiles.length === 0;
      say(tiles.length ? '' : NOTHING);
    } catch (e) {
      if (signedOut(e)) return;
      say(refusal(e));
    } finally {
      working = false;
      ready();
    }
  }

  /** A cover found: its picture, the album and the artist; a tap makes it the piece's own. */
  function tile(result) {
    const album = String(result.album || '');
    const artist = String(result.artist || '');
    const picture = h('img', { src: result.picture, alt: '', width: String(TILE_PX), height: String(TILE_PX), decoding: 'async' });
    const button = h('button', { class: 'tile-play', type: 'button', 'aria-label': artist ? `${album}, ${artist}` : album },
      h('span', { class: 'art', 'data-kind': 'cover' }, picture),
      h('span', { class: 'title', text: album }),
      h('span', { class: 'meta', text: artist }));
    button.addEventListener('click', () => choose(result.index));
    return h('li', { class: 'cover-tile' }, button);
  }

  async function choose(index) {
    if (working || !searchId) return;
    working = true;
    ready();
    say(FETCHING);
    try {
      await host.post(host.ROOT + '/api/covers/choose', { pieceId: piece.id, searchId, index });
      changed = true;
      close();
      host.toast(CHANGED);
    } catch (e) {
      if (signedOut(e)) return;
      say(refusal(e));
    } finally {
      working = false;
      ready();
    }
  }

  /** After a word from host.confirm: the piece's own cover goes, and its portrait or roll card shows. */
  function removeCover() {
    if (working) return;
    const run = async () => {
      working = true;
      ready();
      try {
        await host.post(host.ROOT + '/api/covers/remove', { pieceId: piece.id });
        changed = true;
        close();
        host.toast(REMOVED);
      } catch (e) {
        if (!signedOut(e)) say(e && e.message ? e.message : 'That did not work.');
      } finally {
        working = false;
        ready();
      }
    };
    host.confirm({
      title: "Remove this piece's cover?",
      message: "Its portrait or roll card shows instead, and the tablet won't look for another cover.",
      action: 'Remove',
      run,
    });
  }

  function close() {
    if (dialog.open) dialog.close();
  }

  field.addEventListener('input', ready);
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      search();
    }
  });
  go.addEventListener('click', search);
  if (remove) remove.addEventListener('click', removeCover);
  cancel.addEventListener('click', close);
  // A tap on the scrim closes it, as host.confirm's does; one that began inside (a selection dragged out) does not.
  let fromScrim = false;
  dialog.addEventListener('pointerdown', (event) => {
    fromScrim = event.target === dialog;
  });
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog && fromScrim) close();
  });
  dialog.addEventListener('close', () => {
    dialog.remove();
    current = null;
    resolve(changed);
  });

  document.body.append(dialog);
  dialog.showModal();
  ready();
  field.focus();
  field.setSelectionRange(field.value.length, field.value.length);
  return dialog;
}
