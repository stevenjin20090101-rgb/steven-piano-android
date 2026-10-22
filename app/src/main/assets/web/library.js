/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The Library (DESIGN.md › v1.20 — M53), a section of the panel's frame: Pieces, Playlists, Composers and Channels under
// the genre switch and the search, covers or rows, and its Add music sheet. A module of its own since v1.27 (M63): the
// frame (app.js) makes it once, create(host) → { show(), present(), render(before), fillAdd(), drop(files),
// dropZone() }; the uploads are the frame's (host.addUploads), one at a time with Studio's. It asks the tablet only
// through the host, from host.ROOT.

import { $, clock, plural, debounce, linkDot, paintDot } from './ui.js';

export function create(host) {
  const { h, glyph, chip, setEmpty, segmented, get, post, readState, toast, failed, show, staggerIn, openSheet, closeSheet } = host;
  const { art, artForgetIn, monogram, zoneFor, addUploads, renderUploads, queueCommand } = host;
  /** The panel's state, the frame's: read where it is used, so never an old one. */
  const state = () => host.state();

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
    playingDot: null,   // the playing channel's dot, painted at every state (v1.27 — M64)
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
    return libraryAsk(() => get(host.ROOT + `/api/library?${params}`), (page) => {
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
      await post(host.ROOT + '/api/play', { pieceId, queue: queue && queue.length <= 5000 ? queue : undefined });
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
    readState().catch(() => {});
    if ($('lib-view').hidden || !library.pieces.some((p) => p.id === piece.id)) return;
    let fresh = null;
    try {
      const page = await get(host.ROOT + `/api/library?${new URLSearchParams({ q: piece.title, limit: '200' })}`);
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
    return libraryAsk(() => get(scoped(host.ROOT + '/api/playlists')), ({ playlists }) => {
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
    return libraryAsk(() => get(host.ROOT + `/api/playlists/${list.id}`), (detail) => {
      showGroup(detail.playlist.name, [detail.playlist.builtIn ? 'Built in' : null, plural(detail.pieces.length, 'piece', 'pieces')].filter(Boolean).join(' · '), detail.pieces,
        () => post(host.ROOT + '/api/play-all', { playlistId: list.id }));
    }, false);
  }

  /** A composer (the composers list, a channel's mosaic) as [art] takes them: their portrait, else their name's monogram. */
  function portraitOf(composer) {
    return { art: composer.portrait ? 'portrait' : 'roll', composerKey: composer.key, id: 0, title: composer.name, artVersion: composer.artVersion };
  }

  function composersLoad() {
    return libraryAsk(() => get(scoped(host.ROOT + '/api/composers')), ({ composers }) => {
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
    return libraryAsk(() => get(scoped(host.ROOT + `/api/composers/${encodeURIComponent(composer.key)}`)), (detail) => {
      const ids = detail.pieces.map((p) => p.id);
      showGroup(detail.composer.name || unknownName(), plural(detail.pieces.length, 'piece', 'pieces'), detail.pieces,
        () => post(host.ROOT + '/api/play-all', { ids }));
    }, false);
  }

  /** A playlist or a composer opened: back, its name, Play (from the top, in order: v1.26 — M61), then its pieces. */
  function showGroup(name, meta, pieces, playAll) {
    const crumb = $('lib-crumb');
    const run = async () => {
      try {
        await playAll();
        toast('Playing on the piano.');
      } catch (e) {
        failed(e);
      }
    };
    crumb.replaceChildren(
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Back', onclick: () => libraryLoad(true) }, glyph('i-back')),
      h('div', { class: 'text' }, h('p', { class: 'title', text: name }), h('p', { class: 'eyebrow', text: meta })),
      h('button', { class: 'outlined', type: 'button', onclick: run, disabled: pieces.length === 0 }, glyph('i-play'), 'Play'));
    crumb.hidden = false;
    $('lib-more').hidden = true;
    library.listKey = 'group';
    renderPieces(pieces, 'Nothing in here yet.', 'g-playlist');
  }

  // ---- Channels (v1.20 — M53: the Library's fourth list) --------------------------------------------------------

  /** The channel [s] (a state) plays, or null. */
  const channelOf = (s) => (s && s.player.channel ? s.player.channel.key : null);

  /** The channels' cards in the Library's body, and Stop the channel over them while one plays. */
  function channelsLoad() {
    return libraryAsk(() => get(host.ROOT + '/api/channels'), ({ channels, playing }) => {
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

  /**
   * The playing channel's dot (v1.27 — M64): the piano's link as ui.js's linkDot says it, red only while the channel's notes
   * reach the piano (ink while the 24 V comes up, a ring with no piano), painted again at every state ([render]).
   */
  function playingDot() {
    const dot = h('span', { class: 'dot' });
    paintDot(dot, linkDot(state() && state().link));
    library.playingDot = dot;
    return dot;
  }

  /** A channel's card: a mosaic of the composers it holds most, its name on the band, and how many pieces, or Playing with the dot. */
  function channelTile(channel) {
    const cells = channel.composers.slice(0, 4);
    const mosaic = h('div', { class: cells.length > 1 ? 'mosaic' : 'mosaic one' });
    if (cells.length === 0) mosaic.append(monogram(channel.name));
    for (const composer of cells.length === 3 ? cells.concat(cells[0]) : cells) mosaic.append(art(h('div', { class: 'art' }), portraitOf(composer), 'tile'));
    const meta = channel.playing
      ? h('p', { class: 'eyebrow' }, playingDot(), 'Playing')
      : h('p', { class: 'eyebrow', text: channel.playable ? plural(channel.size, 'piece', 'pieces') : 'Add more pieces' });
    const tile = h('button', { class: 'tile', type: 'button', 'aria-label': `${channel.name} channel${channel.playing ? ', playing' : ''}` },
      mosaic, h('div', { class: 'band' }, h('p', { class: 'name', text: channel.name }), meta));
    tile.addEventListener('click', async () => {
      if (!channel.playable) {
        toast('Add more pieces: a channel needs three at least.');
        return;
      }
      try {
        await post(host.ROOT + `/api/channels/${encodeURIComponent(channel.key)}/play`);
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
      await post(host.ROOT + '/api/channels/stop');
      channelsLoad();
    } catch (e) {
      failed(e);
    }
  });

  // ---- Add music (v1.20 — M53: a sheet the Library's head opens) ------------------------------------------------

  const MIDI_BYTES = 8 * 1024 * 1024;
  const ZIP_BYTES = 64 * 1024 * 1024;

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
    const added = [];
    for (const file of Array.from(files)) {
      const name = file.name;
      const lower = name.toLowerCase();
      const isMidi = lower.endsWith('.mid') || lower.endsWith('.midi');
      const isZip = lower.endsWith('.zip');
      const upload = { file, name, size: file.size, status: 'Waiting', progress: 0, route: host.ROOT + '/api/upload', list: 'upload-rows', sent: 'Sent to the tablet', refused: 'Not added' };
      if (!isMidi && !isZip) upload.status = 'Not added: only .mid, .midi and .zip files';
      else if (isMidi && file.size > MIDI_BYTES) upload.status = 'Not added: a MIDI file can be 8 MB at most';
      else if (isZip && file.size > ZIP_BYTES) upload.status = 'Not added: a zip can be 64 MB at most';
      else if (file.size === 0) upload.status = 'Not added: the file is empty';
      else upload.pending = true;
      added.push(upload);
    }
    openSheet('add');   // where they show going
    addUploads(added);
  }

  /**
   * The import on the tablet: its progress while it runs, then its tally; and under it, when a zip or a loose
   * file was put in a playlist (v1.10.1 — M28), "In the playlist MIDI" with Open the playlist.
   */
  function renderTally() {
    const node = $('import-tally');
    if (!node || !state()) return;
    const run = state().import;
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

  /** The state come (the frame's render): a list that couldn't be read asked for again, the channels when another plays, the import's tally. */
  function render(before) {
    const shows = host.section() === 'library';
    if (shows && !library.loaded && !library.asking && performance.now() - library.failedAt >= LIBRARY_RETRY_MS) libraryLoad();
    if (shows && before && library.category === 'channels' && !library.query && channelOf(before) !== channelOf(state())) channelsLoad();
    if (library.playingDot && library.playingDot.isConnected) paintDot(library.playingDot, linkDot(state() && state().link));
    renderTally();
  }

  /** The Add music sheet opening (the frame's openSheet): its body made the first time, then its lists brought up to date. */
  function fillAdd() {
    if (!$('add-body').firstChild) renderAdd();
    renderUploads();
    renderTally();
  }

  return {
    show: () => libraryLoad(),
    present: () => { if (library.loaded && !library.asking) staggerLibrary(); },
    render,
    fillAdd,
    drop: addFiles,
    dropZone,
  };
}
