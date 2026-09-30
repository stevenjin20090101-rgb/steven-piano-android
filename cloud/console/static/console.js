/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// Steven Piano Cloud's console: every piano, and one piano at a time. No framework and no build
// step; the page talks to the console's API with fetch (the X-Steven-Piano header on every call)
// and builds every element with DOM calls. What the tablets report (titles, names) only ever goes
// in as text, never as markup. The content security policy allows this file and nothing inline.

'use strict';

(function () {
  // ---- Small helpers ------------------------------------------------------------------------------

  const $ = (id) => document.getElementById(id);
  const SVG = 'http://www.w3.org/2000/svg';

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

  function glyph(id) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('class', 'glyph');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS(SVG, 'use');
    use.setAttribute('href', '#' + id);
    svg.append(use);
    return svg;
  }

  function button(label, onClick, options = {}) {
    return h('button', { class: options.text ? 'text-button' : 'outlined', type: 'button', onclick: onClick, disabled: options.disabled, 'aria-label': options.aria }, options.glyph ? glyph(options.glyph) : null, label);
  }

  const plural = (n, one, many) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

  /** "0:00", "4:31", "1:02:03". */
  function clock(ms) {
    const s = Math.max(0, Math.floor((ms || 0) / 1000));
    const hours = Math.floor(s / 3600);
    const minutes = Math.floor(s / 60) % 60;
    const seconds = String(s % 60).padStart(2, '0');
    return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
  }

  // The server's clock: times are the database's, "ago" is counted from the server's now.
  let skew = 0;
  const serverNow = () => Date.now() + skew;

  function ago(at) {
    if (!at) return 'never';
    const s = Math.max(0, Math.round((serverNow() - at) / 1000));
    if (s < 60) return 'just now';
    const m = Math.round(s / 60);
    if (m < 60) return `${m} min ago`;
    const hrs = Math.round(m / 60);
    if (hrs < 48) return `${hrs} h ago`;
    const days = Math.round(hrs / 24);
    if (days < 14) return `${days} days ago`;
    return new Date(at).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
  }

  // ---- The API --------------------------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, body) {
      super((body && body.message) || `The console answered ${status}.`);
      this.status = status;
      this.body = body || {};
    }
  }

  let signedOut = false;

  /** A call to the console: JSON both ways, and the console's header, which no other site's page can send. */
  async function call(method, path, body) {
    const init = { method, credentials: 'same-origin', cache: 'no-store', headers: { 'X-Steven-Piano': '1' } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch(path, init);
    } catch (e) {
      throw new ApiError(0, { message: "The console can't be reached." });
    }
    let data = null;
    if ((response.headers.get('Content-Type') || '').includes('application/json')) {
      try {
        data = await response.json();
      } catch (e) {
        data = null;
      }
    }
    if (response.status === 401 && !signedOut) {
      signedOut = true;
      stopPolling();
      main.replaceChildren(
        h('div', { class: 'empty' },
          h('p', { text: 'Your sign-in has ended.' }),
          h('p', { class: 'note', text: 'Reload the page to sign in with Cloudflare Access again.' })),
      );
    }
    if (!response.ok) throw new ApiError(response.status, data);
    if (data && typeof data.now === 'number') skew = data.now - Date.now();
    return data;
  }

  let toastTimer = null;
  function toast(text) {
    const node = $('toast');
    node.textContent = text;
    node.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      node.hidden = true;
    }, 5000);
  }

  function failed(e) {
    if (e && e.status === 401) return;
    toast(e && e.message ? e.message : 'That did not work.');
  }

  // ---- Screens -----------------------------------------------------------------------------------------

  const main = $('main');
  let poll = null;
  let pollFn = null;

  function startPolling(fn, ms) {
    stopPolling();
    pollFn = fn;
    poll = setInterval(() => {
      if (!document.hidden) fn();
    }, ms);
  }

  function stopPolling() {
    clearInterval(poll);
    poll = null;
    pollFn = null;
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && pollFn) pollFn();
  });

  function route() {
    if (signedOut) return;
    closeSheet();
    const match = /^#\/piano\/([a-z2-7]{12})$/.exec(location.hash);
    if (match) showPiano(match[1]);
    else showPianos();
    window.scrollTo(0, 0);
  }

  window.addEventListener('hashchange', route);

  /** What the piano is doing, in a line: "Playing · Clair de lune — Claude Debussy", "Idle", "Offline". */
  function doing(piano, status, online) {
    if (piano.revokedAt && !piano.enrolledAt) return 'Revoked';
    if (!piano.enrolledAt) return 'Waiting for its tablet';
    if (piano.revokedAt && piano.revokedAt >= piano.enrolledAt) return 'Revoked';
    if (!online) return 'Offline';
    const player = status && status.player;
    if (!player || !player.title || player.status === 'stopped' || player.status === 'idle') {
      if (player && player.channel && player.channel.name) return `Channel · ${player.channel.name}`;
      return 'Idle';
    }
    const what = player.composer ? `${player.title} — ${player.composer}` : player.title;
    return `${player.status === 'paused' ? 'Paused' : 'Playing'} · ${what}`;
  }

  /** "App 1.10 (18) · Firmware 2.0.0 · Seen 2 min ago". */
  function versions(piano, online) {
    const parts = [];
    if (piano.appVersion) parts.push(`App ${piano.appVersion}${piano.appCode ? ` (${piano.appCode})` : ''}`);
    if (piano.firmware) parts.push(`Firmware ${piano.firmware}`);
    if (piano.enrolledAt) parts.push(`Seen ${ago(piano.lastSeen)}`);
    else parts.push(`Made ${ago(piano.createdAt)}`);
    return parts.join(' · ');
  }

  // ---- Pianos -------------------------------------------------------------------------------------------

  async function showPianos() {
    stopPolling();
    const load = async () => {
      try {
        renderPianos(await call('GET', '/api/pianos'));
      } catch (e) {
        failed(e);
      }
    };
    await load();
    startPolling(load, 10000);
  }

  function renderPianos(data) {
    if (location.hash.startsWith('#/piano/')) return;
    const pianos = data.pianos || [];
    const rows = pianos.map((piano) => {
      const online = piano.online;
      const href = `#/piano/${piano.id}`;
      return h('li', { class: 'row clickable' },
        h('a', { class: 'cover', href },
          h('span', { class: online ? 'dot live' : 'dot', 'aria-hidden': 'true' }),
          h('span', { class: 'text' },
            h('p', { class: 'title', text: piano.name }),
            h('p', { class: 'meta', text: doing(piano, piano.status, online) }),
            h('p', { class: 'meta', text: versions(piano, online) })),
          h('span', { class: 'chevron' }, glyph('i-chevron'))));
    });
    main.replaceChildren(
      h('div', { class: 'page-head' },
        h('h1', { text: 'Pianos' }),
        button('Enrol a tablet', enrol, { glyph: 'i-add' })),
      rows.length
        ? h('ul', { class: 'rows', 'aria-label': 'Pianos' }, rows)
        : h('div', { class: 'empty' },
          h('p', { text: 'No pianos yet.' }),
          h('p', { class: 'note', text: 'Enrol a tablet: the console makes a code, and the tablet types it with the relay’s address.' })),
      relayFoot(data.relay),
    );
  }

  function relayFoot(relay) {
    return h('div', { class: 'foot' },
      h('p', { class: 'eyebrow', text: 'Relay' }),
      h('p', { class: 'meta', text: relay && relay.host ? relay.host : 'Not set: RELAY_URL in wrangler.console.jsonc' }));
  }

  // ---- Enrolling a tablet -----------------------------------------------------------------------------------

  const sheet = $('sheet');
  let sheetTimer = null;

  function openSheet(...children) {
    clearInterval(sheetTimer);
    sheet.replaceChildren(...children);
    if (!sheet.open) sheet.showModal();
  }

  function closeSheet() {
    clearInterval(sheetTimer);
    sheetTimer = null;
    if (sheet.open) sheet.close();
  }

  sheet.addEventListener('close', () => {
    clearInterval(sheetTimer);
    sheetTimer = null;
  });

  async function enrol() {
    let made;
    try {
      made = await call('POST', '/api/enrol-codes');
    } catch (e) {
      failed(e);
      return;
    }
    const relay = made.relay || {};
    const expiry = h('p', { class: 'meta', role: 'timer' });
    const code = h('p', { class: 'code', id: 'enrol-code', text: made.code });
    const waiting = h('p', { class: 'note', role: 'status', text: 'Waiting for the tablet…' });
    const again = button('New code', () => {
      closeSheet();
      enrol();
    }, { text: true });
    const address = relay.host || 'the relay’s address (RELAY_URL is not set)';
    openSheet(
      h('div', { class: 'sheet-body' },
        h('p', { class: 'eyebrow', id: 'sheet-title', text: 'Enrol a tablet' }),
        code,
        expiry,
        h('div', { class: 'pair' },
          h('p', { class: 'eyebrow', text: 'Relay address' }),
          h('p', { class: 'address', text: address }),
          relay.host && /^localhost(:|$)/.test(relay.host) ? h('p', { class: 'meta', text: 'From the Android emulator: 10.0.2.2' + relay.host.slice('localhost'.length) }) : null),
        h('p', { class: 'note', text: 'On the tablet: Piano › Remote control › CLOUD › Enrol with code. Type the relay address and this code. The code works once, for 15 minutes.' }),
        waiting),
      h('div', { class: 'sheet-actions' }, again, button('Done', () => {
        closeSheet();
        route();
      })),
    );
    let checking = false;
    const tick = async () => {
      const left = made.expiresAt - serverNow();
      if (left <= 0) {
        expiry.textContent = 'Expired. Make a new code.';
        code.classList.add('spent');
        waiting.textContent = '';
        clearInterval(sheetTimer);
        return;
      }
      expiry.textContent = `Expires in ${clock(left)}`;
      if (checking) return;
      checking = true;
      try {
        const detail = await call('GET', `/api/pianos/${made.pianoId}`);
        if (detail.piano.enrolledAt) {
          clearInterval(sheetTimer);
          expiry.textContent = 'Used';
          code.classList.add('spent');
          waiting.replaceChildren(glyph('i-check'), ` Enrolled: ${detail.piano.name}.`);
          again.hidden = true;
        }
      } catch (e) {
        // Checked again in a moment.
      } finally {
        checking = false;
      }
    };
    tick();
    sheetTimer = setInterval(tick, 1000);
  }

  /** Asks before something that can't be undone; resolves true to go ahead. */
  function confirm(title, text, action) {
    return new Promise((resolve) => {
      let answered = false;
      const answer = (value) => {
        if (answered) return;
        answered = true;
        closeSheet();
        resolve(value);
      };
      openSheet(
        h('div', { class: 'sheet-body' },
          h('h1', { id: 'sheet-title', text: title }),
          h('p', { class: 'note', text })),
        h('div', { class: 'sheet-actions' },
          button('Cancel', () => answer(false), { text: true }),
          button(action, () => answer(true))),
      );
      sheet.addEventListener('close', () => answer(false), { once: true });
    });
  }

  // ---- One piano ------------------------------------------------------------------------------------------

  let current = null; // { id, data, editing }

  async function showPiano(id) {
    stopPolling();
    current = { id, data: null, editing: false };
    const load = async () => {
      try {
        const data = await call('GET', `/api/pianos/${id}`);
        if (!current || current.id !== id) return;
        current.data = data;
        if (!current.editing) renderPiano();
      } catch (e) {
        if (e.status === 404) {
          stopPolling();
          main.replaceChildren(backLink(), h('div', { class: 'empty' }, h('p', { text: 'No such piano.' })));
          return;
        }
        failed(e);
      }
    };
    await load();
    startPolling(load, 5000);
  }

  function backLink() {
    return h('div', { class: 'crumb' },
      h('a', { class: 'text-button', href: '#/' }, glyph('i-back'), 'Pianos'));
  }

  /** A console command; its answer at the foot of the window, and the piano read again once the tablet has had a moment. */
  async function command(name, args) {
    const id = current && current.id;
    try {
      const result = await call('POST', `/api/pianos/${id}/command`, { name, args });
      toast(result.message);
    } catch (e) {
      failed(e);
    }
    setTimeout(() => {
      if (current && current.id === id && pollFn) pollFn();
    }, 1200);
  }

  function renderPiano() {
    const { piano, live, audit, relay } = current.data;
    const online = live ? live.online : piano.online;
    const status = (live && live.status) || piano.status;
    const player = status && status.player;
    const guests = status && status.guests;
    const channels = (status && status.channels) || [];
    const enrolled = !!piano.enrolledAt;
    const revoked = !!piano.revokedAt && (!piano.enrolledAt || piano.revokedAt >= piano.enrolledAt);
    const usable = online && enrolled && !revoked;

    // The head: the name, and renaming it.
    const head = h('div', { class: 'page-head' });
    if (current.editing) {
      const field = h('input', { class: 'field', type: 'text', maxlength: '60', value: piano.name, 'aria-label': 'Name' });
      const save = async () => {
        try {
          await call('PATCH', `/api/pianos/${piano.id}`, { name: field.value });
          current.editing = false;
          toast('Renamed.');
          pollFn && pollFn();
        } catch (e) {
          failed(e);
        }
      };
      field.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') save();
        if (event.key === 'Escape') {
          current.editing = false;
          renderPiano();
        }
      });
      head.append(h('div', { class: 'name-edit' }, field), button('Save', save), button('Cancel', () => {
        current.editing = false;
        renderPiano();
      }, { text: true }));
      setTimeout(() => field.focus(), 0);
    } else {
      head.append(h('h1', { text: piano.name }), button('Rename', () => {
        current.editing = true;
        renderPiano();
      }, { text: true }));
    }

    // Where it is.
    let line;
    if (revoked) line = `Revoked ${ago(piano.revokedAt)}`;
    else if (!enrolled) line = 'Waiting for its tablet';
    else if (online) line = live && live.connectedAt ? `Online · connected ${ago(live.connectedAt)}` : 'Online';
    else line = `Offline · seen ${ago(piano.lastSeen)}`;
    const facts = [];
    if (piano.appVersion) facts.push(`App ${piano.appVersion}${piano.appCode ? ` (${piano.appCode})` : ''}`);
    if (piano.firmware) facts.push(`Firmware ${piano.firmware}`);
    if (status && status.library && typeof status.library.pieces === 'number') {
      facts.push(`Library ${plural(status.library.pieces, 'piece', 'pieces')}${status.library.pack ? ` · pack ${status.library.pack}` : ''}`);
    }
    if (live && live.browsers) facts.push(plural(live.browsers, 'panel open', 'panels open'));

    const where = h('div', { class: 'inset' },
      h('p', { class: 'online' }, h('span', { class: online ? 'dot live' : 'dot', 'aria-hidden': 'true' }), line),
      facts.length ? h('p', { class: 'meta', text: facts.join(' · ') }) : null,
      piano.rotatingUntil ? h('p', { class: 'meta', text: 'A new secret is waiting for the tablet to confirm it.' }) : null);

    // Now playing, and the transport. The time runs on from the status's (it comes every 30 s).
    const playing = online && player && player.title && player.status !== 'stopped';
    let position = player ? player.positionMs || 0 : 0;
    if (playing && player.status === 'playing' && online && live && live.statusAt) position += Math.max(0, serverNow() - live.statusAt);
    if (player && player.durationMs) position = Math.min(position, player.durationMs);
    const nowBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Now playing' })),
      h('div', { class: 'inset' },
        h('p', { class: 'now-title', text: playing ? player.title : online ? 'Nothing playing' : 'Not connected' }),
        !online && player && player.title ? h('p', { class: 'meta', text: `Last seen with ${player.composer ? `${player.title} — ${player.composer}` : player.title}` }) : null,
        playing && player.composer ? h('p', { class: 'note', text: player.composer }) : null,
        playing ? h('p', { class: 'meta', text: `${player.status === 'paused' ? 'Paused' : 'Playing'} · ${clock(position)} of ${clock(player.durationMs)}` }) : null,
        playing && player.durationMs ? progress(position / player.durationMs) : null),
      h('div', { class: 'transport' },
        button(player && player.status === 'playing' ? 'Pause' : 'Play', () => command('transport', { action: 'toggle' }), { glyph: player && player.status === 'playing' ? 'i-pause' : 'i-play', disabled: !usable }),
        button('Next', () => command('transport', { action: 'next' }), { glyph: 'i-next', disabled: !usable }),
        button('Stop', () => command('transport', { action: 'stop' }), { glyph: 'i-stop', disabled: !usable })),
    ];

    // Channels, as the tablet names them.
    const playingChannel = player && player.channel ? player.channel.key : null;
    const channelBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Channels' })),
      channels.length
        ? h('ul', { class: 'rows' }, channels.map((c) => h('li', { class: 'row' },
          h('span', { class: 'text' },
            h('p', { class: 'title', text: c.name }),
            c.key === playingChannel ? h('p', { class: 'meta', text: 'Playing' }) : null),
          c.key === playingChannel
            ? button('Stop', () => command('stopChannel'), { text: true, disabled: !usable, aria: `Stop the ${c.name} channel` })
            : button('Play', () => command('playChannel', { key: c.key }), { text: true, disabled: !usable, aria: `Play the ${c.name} channel` }))))
        : h('p', { class: 'inset note', text: usable ? 'The tablet hasn’t named its channels.' : 'The channels show while the piano is online.' }),
    ];

    // Guests.
    const guestSwitch = (label, meta, checked, onChange, disabled) => h('div', { class: disabled ? 'setting disabled' : 'setting' },
      h('span', { class: 'label' }, label, meta ? h('span', { class: 'meta', text: meta }) : null),
      h('button', { class: 'switch', type: 'button', role: 'switch', 'aria-checked': checked ? 'true' : 'false', 'aria-label': label, disabled, onclick: onChange }));
    const guestsOpen = !!(guests && guests.open);
    const guestBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Guests' })),
      guestSwitch('Guests can request', 'The request page takes pieces from passers-by', guestsOpen, () => command('guests', { open: !guestsOpen }), !usable || !guests),
      guestSwitch('Approve first', 'A request waits for Approve on the panel or the tablet', !!(guests && guests.approveFirst), () => command('guests', { approveFirst: !(guests && guests.approveFirst) }), !usable || !guests || !guestsOpen),
    ];

    // The panel and the library.
    const panelUrl = relay && relay.url ? `${relay.url}/p/${piano.id}/` : null;
    const panelBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Panel' })),
      h('div', { class: 'actions' },
        panelUrl
          ? h('a', { class: 'outlined', href: panelUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Open the panel')
          : h('span', { class: 'outlined', 'aria-disabled': 'true', text: 'Open the panel' }),
        button('Load Steven’s library', () => command('library.load'), { disabled: !usable }),
        h('p', { class: 'meta', text: panelUrl ? panelUrl : 'The relay’s address is not set (RELAY_URL).' })),
    ];

    // Access.
    const accessBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Access' })),
      h('div', { class: 'actions' },
        button('Rotate secret', rotate, { disabled: !usable }),
        button('Revoke', revoke, { disabled: !enrolled || revoked }),
        button('Forget', forget),
        h('p', { class: 'note', text: 'Rotate gives the tablet a new secret. Revoke disconnects it for good: it needs a new enrolment. Forget removes the piano and its code from the console.' })),
    ];

    // What happened lately.
    const auditBlock = [
      h('div', { class: 'section-head' }, h('p', { class: 'eyebrow', text: 'Recent' })),
      audit && audit.length
        ? h('ul', { class: 'rows audit' }, audit.map((entry) => h('li', { class: 'row' },
          h('span', { class: 'text' },
            h('p', { class: 'title', text: describe(entry) }),
            h('p', { class: 'meta', text: `${entry.actor || 'relay'} · ${ago(entry.at)}` })))))
        : h('p', { class: 'inset note', text: 'Nothing yet.' }),
    ];

    main.replaceChildren(backLink(), head, where, ...nowBlock, ...channelBlock, ...guestBlock, ...panelBlock, ...accessBlock, ...auditBlock);
  }

  function progress(fraction) {
    const bar = h('span');
    bar.style.setProperty('--fill', `${Math.max(0, Math.min(1, fraction || 0)) * 100}%`);
    return h('div', { class: 'progress', role: 'presentation' }, bar);
  }

  const COMMAND_NAMES = {
    transport: 'Transport',
    play: 'Play a piece',
    playChannel: 'Play a channel',
    stopChannel: 'Stop the channel',
    guests: 'Guests',
    'library.load': 'Load Steven’s library',
    status: 'Status',
  };

  /** An audit row in words. */
  function describe(entry) {
    const d = entry.detail || {};
    switch (entry.action) {
      case 'enrol-code':
        return 'Enrolment code made';
      case 'enrol':
        return d.model ? `Enrolled (${d.model})` : 'Enrolled';
      case 'rename':
        return `Renamed to “${d.to}”`;
      case 'command': {
        const name = COMMAND_NAMES[d.name] || d.name;
        const what = d.name === 'transport' && d.args ? `${name}: ${d.args.action}` : name;
        return `${what}${d.ok ? '' : ' · not done'}`;
      }
      case 'rotate':
        return 'Secret rotation started';
      case 'rotate-commit':
        return 'The tablet took its new secret';
      case 'revoke':
        return 'Revoked';
      case 'replaced':
        return 'A newer connection took over';
      default:
        return entry.action;
    }
  }

  async function rotate() {
    if (!(await confirm('Rotate the secret?', 'The tablet is sent a new secret and confirms it; its old one stops working then. Nothing to do on the tablet.', 'Rotate'))) return;
    try {
      const result = await call('POST', `/api/pianos/${current.id}/rotate`);
      toast(result.message);
    } catch (e) {
      failed(e);
    }
    pollFn && pollFn();
  }

  async function revoke() {
    if (!(await confirm('Revoke this tablet?', 'It is disconnected at once and can’t come back without a new enrolment. The piano’s page stays here.', 'Revoke'))) return;
    try {
      const result = await call('POST', `/api/pianos/${current.id}/revoke`);
      toast(result.message);
    } catch (e) {
      failed(e);
    }
    pollFn && pollFn();
  }

  async function forget() {
    if (!(await confirm('Forget this piano?', 'Its tablet is disconnected and the piano is removed from the console, with its codes. Its history stays in the audit log for 90 days.', 'Forget'))) return;
    try {
      const result = await call('DELETE', `/api/pianos/${current.id}`);
      toast(result.message);
      location.hash = '#/';
    } catch (e) {
      failed(e);
    }
  }

  // ---- Start ---------------------------------------------------------------------------------------------

  async function who() {
    try {
      const me = await call('GET', '/api/me');
      $('who').textContent = me.email;
    } catch (e) {
      // The bar stays without it.
    }
  }

  route();
  who();
})();
