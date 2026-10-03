/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The panel's System page (DESIGN.md › v1.18 — M47b): an ES module the frame (app.js) imports after the first state,
// create(host, body, tools) → { show(), hide(), render(state) }, and vitals(host, node), the rail's foot. Three cards
// (the tablet, the controller, the piano), then Running now beside Today and Tools, from /api/system (every 5 s while
// the page shows, 10 s through the relay), /api/system/refresh (every 15 s while the piano is ready) and
// /api/system/history (on opening, then every minute); nothing while the page is closed or the window hidden. Built
// once with DOM calls and brought up to date in place: a dial's arc eases to its new value (480 ms, none under reduced
// motion) and nothing loops. Amber, `--attention`, only where attention() says something needs it, and never alone.

const NEEDS_FIRMWARE = 'Needs newer firmware';

/** Android's thermal status (SystemReading.THERMAL_WORDS), 0–6, and the dial's word for each. */
const THERMAL = ['none', 'light', 'moderate', 'severe', 'critical', 'emergency', 'shutdown'];
const THERMAL_WORDS = ['Normal', 'Warm', 'Warm', 'Hot', 'Hot', 'Hot', 'Hot'];

/** The tablet's heat in a word: Android's status (none Normal, light and moderate Warm, severe and above Hot), Hot from 42 °C. */
function heatWord(status, celsius) {
  const level = THERMAL.indexOf(status);
  if (celsius !== null && celsius >= BATTERY_HOT_C) return 'Hot';
  if (level >= 0) return THERMAL_WORDS[level];
  return celsius === null ? '' : 'Normal';
}

/** A battery's health that needs attention ('unknown' is a tablet that doesn't say, not a fault). */
const BAD_HEALTH = { overheat: 'Overheating', cold: 'Too cold', dead: 'Failed', overVoltage: 'Over voltage' };

/** The controller's words (firmware/docs/BLE_SETTINGS.md › 4, BLE_DIAG.md). */
const OTA_WORDS = { none: 'Flashed over USB', pending: 'Waiting to confirm', confirmed: 'Confirmed' };
const RESET_WORDS = {
  poweron: 'Power on', software: 'Restarted', panic: 'After a crash', watchdog: 'Watchdog', brownout: 'Brownout', usb: 'USB', other: 'Other',
};
const LINK_WORDS = {
  connected: 'Connected', disconnected: 'Not connected', scanning: 'Looking for it…', connecting: 'Connecting…', reconnecting: 'Reconnecting…', error: "Couldn't connect",
};
const TRANSPORTS = { wifi: 'Wi-Fi', ethernet: 'Ethernet', cellular: 'Mobile data', vpn: 'VPN', other: 'Connected' };

/** Running now's words for a row's state (RunningNow: running, waiting, idle, off, problem). */
const RUNNING_WORDS = {
  player: 'Playing', link: 'Connected', web: 'Serving', relay: 'Online', covers: 'Fetching', import: 'Importing', studio: 'Working',
  pack: 'Loading', update: 'Working', firmware: 'Updating', sound: 'On',
};
const WAITING_WORDS = { player: 'Paused', link: 'Connecting', relay: 'Reconnecting' };

/** The facts the cards show by name; every other fact the piano gives is a plain row of its own. */
const SHOWN_FACTS = new Set([
  'temp', 'heap', 'heapmin', 'heapblock', 'heapsize', 'tasks', 'stack', 'looprate', 'loopms', 'rssi', 'active', 'trips', 'crashes',
  'i2cfails', 'uptime', 'repeatms', 'reset', 'ota', 'fw', 'boards', 'pedalboard',
  // The protocol's own markers (BLE_SETTINGS.md › 4), not readings.
  'proto', 'ble',
]);

/** Where attention starts (the brief's rule, one place). */
const BATTERY_LOW_PCT = 20;
const BATTERY_HOT_C = 42;
const STORAGE_LOW_BYTES = 1e9;
const CHIP_HOT_C = 70;
const HEAP_LOW_BYTES = 30 * 1024;

/** How often each part is read while the page shows, and the rail's foot without it. */
const SYSTEM_MS = 5000;
const SYSTEM_RELAYED_MS = 10000;
const REFRESH_MS = 15000;
const HISTORY_MS = 60000;
const VITALS_MS = 60000;
const DAY_MS = 24 * 60 * 60 * 1000;

const SVG_NS = 'http://www.w3.org/2000/svg';

// ---- Needs attention: one pure function, for the dials, the head's capsule and the rail ------------------------------

/**
 * What needs attention now, most pressing first, from /api/system's object [sys] and the panel's state [state] (for
 * the cloud address): each `{ part, text, short }`, [part] naming what it colours (battery, heat, memory, storage,
 * boards, chip, heap, relay, running), [text] the head's sentence and [short] the rail's word. Empty when all is well.
 */
export function attention(sys, state) {
  const items = [];
  if (!sys || typeof sys !== 'object') return items;
  const tablet = sys.tablet || {};
  const battery = tablet.battery || {};
  const thermal = tablet.thermal || {};
  const memory = tablet.memory || {};
  const storage = tablet.storage || {};
  const percent = finite(battery.percent);
  if (percent !== null && percent < BATTERY_LOW_PCT && battery.charging !== true) {
    items.push({ part: 'battery', text: `The tablet's battery is low · ${Math.round(percent)}%`, short: 'low' });
  }
  if (typeof battery.health === 'string' && BAD_HEALTH[battery.health]) {
    items.push({ part: 'battery', text: `The tablet's battery: ${BAD_HEALTH[battery.health].toLowerCase()}`, short: 'check' });
  }
  const heat = heatWord(thermal.status, finite(battery.tempC));
  if (heat === 'Hot' || THERMAL.indexOf(thermal.status) >= 2) {
    items.push({ part: 'heat', text: heat === 'Hot' ? 'The tablet is hot' : 'The tablet is warm', short: heat === 'Hot' ? 'hot' : 'warm' });
  }
  if (memory.low === true) items.push({ part: 'memory', text: 'The tablet is low on memory', short: 'low' });
  const free = finite(storage.free);
  if (free !== null && free < STORAGE_LOW_BYTES) items.push({ part: 'storage', text: "The tablet's storage is almost full", short: 'full' });

  const piano = sys.piano || {};
  const diag = piano.diag || {};
  const connected = piano.link && piano.link.state === 'connected';
  if (connected) {
    const missing = Array.isArray(diag.boards) ? diag.boards.filter((b) => b === 'missing').length : 0;
    if (missing > 0) {
      items.push({ part: 'boards', text: missing === 1 ? 'A power board is missing' : `${missing} power boards are missing`, short: missing === 1 ? 'board missing' : `${missing} boards missing` });
    }
    const chip = finite(diag.temp);
    if (chip !== null && chip >= CHIP_HOT_C) items.push({ part: 'chip', text: `The controller is hot · ${Math.round(chip)} °C`, short: 'hot' });
    const heapmin = finite(diag.heapmin);
    if (heapmin !== null && heapmin < HEAP_LOW_BYTES) items.push({ part: 'heap', text: 'The controller ran low on memory', short: 'low memory' });
  }

  const relay = sys.web && sys.web.relay;
  const cloud = state && state.web && state.web.cloud;
  if (cloud && relay && relay.state !== 'connected') items.push({ part: 'relay', text: 'The internet link is not connected', short: 'offline' });

  const running = Array.isArray(sys.running) ? sys.running : [];
  for (const item of running) {
    if (item && item.state === 'problem') items.push({ part: 'running', key: item.key, text: `${item.title} needs attention`, short: 'problem' });
  }
  // Apple's hour-long stop: its row's dot is amber, so the head says it too (system-today.png), last of all.
  for (const item of running) {
    if (item && item.key === 'covers' && item.state === 'waiting') items.push({ part: 'running', key: 'covers', text: 'Album covers are waiting', short: 'waiting' });
  }
  return items;
}

// ---- One read of /api/system, shared by the page and the rail's foot ------------------------------------------------

const shared = { host: null, sys: null, at: 0, loading: null, listeners: new Set() };

function bind(host) {
  if (!shared.host && host) shared.host = host;
}

/** /api/system now (one request at a time); every listener hears the answer. A failure leaves the last one standing. */
function loadSystem() {
  const host = shared.host;
  if (!host) return Promise.resolve(null);
  if (shared.loading) return shared.loading;
  shared.loading = host.get(host.ROOT + '/api/system')
    .then((sys) => {
      if (sys && typeof sys === 'object') {
        shared.sys = sys;
        shared.at = Date.now();
        for (const listener of shared.listeners) guarded(listener);
      }
      return sys;
    })
    .catch(() => null)
    .finally(() => {
      shared.loading = null;
    });
  return shared.loading;
}

// ---- The rail's foot ----------------------------------------------------------------------------------------------

/**
 * Three small lines in the rail's foot (Tablet 82%, Temperature 31 °C, Piano Connected), amber where attention() says
 * so, with its word. Read once a minute, or from the page's own reads while it is open; the piano's line follows the
 * state between them.
 */
export function vitals(host, node) {
  bind(host);
  if (!node || !host || typeof host.h !== 'function') return;
  const { h } = host;
  const line = (glyphId, label) => {
    const value = h('b', { text: '—' });
    return { value, node: h('p', { class: 'vital' }, host.glyph(glyphId), h('span', { text: label }), value) };
  };
  const tablet = line('g-battery', 'Tablet');
  const heat = line('g-temp', 'Temperature');
  const piano = line('g-keys', 'Piano');
  host.fill(node, tablet.node, heat.node, piano.node);

  const set = (part, text, flagged) => {
    if (part.value.textContent !== text) part.value.textContent = text;
    part.node.classList.toggle('attention', flagged);
  };
  const paint = () => {
    const sys = shared.sys;
    const state = host.state() || null;
    const items = attention(sys, state);
    const first = (names) => items.find((item) => names.includes(item.part)) || null;
    const battery = (sys && sys.tablet && sys.tablet.battery) || {};
    const percent = finite(battery.percent);
    const low = first(['battery']);
    set(tablet, percent === null ? '—' : low ? (low.short === 'low' ? `${Math.round(percent)}% low` : 'Check') : `${Math.round(percent)}%`, !!low);
    const celsius = finite(battery.tempC);
    const hot = first(['heat']);
    set(heat, celsius === null ? '—' : hot ? capital(hot.short) : `${Math.round(celsius)} °C`, !!hot);
    const instrument = state && state.instruments && state.instruments.instrument;
    const linkState = (state && state.link && state.link.state) || (sys && sys.piano && sys.piano.link && sys.piano.link.state) || null;
    const trouble = first(['boards', 'chip', 'heap']);
    let word = linkState ? LINK_WORDS[linkState] || 'Not connected' : '—';
    if (instrument && instrument.kind === 'midi') word = instrument.name || word;
    else if (trouble && linkState === 'connected') word = capital(trouble.short);
    set(piano, word, !!trouble && linkState === 'connected');
  };
  shared.listeners.add(paint);
  paint();

  const look = () => {
    if (document.hidden) return;
    if (Date.now() - shared.at >= VITALS_MS - 5000) loadSystem();
    else paint();
  };
  look();
  setInterval(look, VITALS_MS);
  // The piano's line between reads: the state is the panel's, at hand.
  setInterval(() => {
    if (!document.hidden) paint();
  }, SYSTEM_MS);
  document.addEventListener('visibilitychange', look);
}

// ---- The page ---------------------------------------------------------------------------------------------------

export function create(host, body, tools) {
  bind(host);
  const { h, fill } = host;
  const glyph = (id) => host.glyph(id);

  let st = host.state() || null;
  let showing = false;
  let history = null;
  let historyAt = 0;
  const timers = { system: 0, refresh: 0, history: 0, tick: 0, soon: 0 };

  // ---- The head's tools: what needs attention first, and how old the figures are ----------------------------------

  const statusDot = h('span', { class: 'dot' });
  const statusText = h('span', { text: 'Reading the system…' });
  const statusMore = h('span', { class: 'more' });
  const statusCapsule = h('span', { class: 'capsule glass', role: 'status' }, statusDot, statusText, statusMore);
  const updated = h('span', { class: 'capsule glass wide', text: 'Not updated yet' });
  if (tools) fill(tools, statusCapsule, updated);

  // ---- The tablet --------------------------------------------------------------------------------------------------

  const tabletMeta = h('span', { class: 'meta' });
  const batteryDial = makeDial(h, 'Battery', '0', '100');
  const heatDial = makeDial(h, 'Temperature', '0', '60');
  const memoryLevel = makeLevel(h, 'Memory');
  const storageLevel = makeLevel(h, 'Storage');
  const networkFact = makeFact(h, fill, 'Wi-Fi');
  const tabletUptime = makeFact(h, fill, 'Running for');
  const screenFact = makeFact(h, fill, 'Screen');
  const tabletCard = h('section', { class: 'card', 'aria-label': 'Tablet' },
    h('div', { class: 'card-head' }, glyph('g-tablet'), h('h2', { text: 'Tablet' }), tabletMeta),
    h('div', { class: 'dials' }, batteryDial.node, heatDial.node),
    memoryLevel.node, storageLevel.node,
    h('div', { class: 'facts' }, networkFact.node, tabletUptime.node, screenFact.node));

  // ---- The controller ------------------------------------------------------------------------------------------------

  const controllerMeta = h('span', { class: 'meta' });
  const chipDial = makeDial(h, 'Chip temperature', '0', '80');
  const heapDial = makeDial(h, 'Memory', '0', '');
  const controllerUptime = makeFact(h, fill, 'Running for');
  const otaFact = makeFact(h, fill, 'Update state');
  const tasksFact = makeFact(h, fill, 'Tasks');
  const speedFact = makeFact(h, fill, 'Speed');
  const startFact = makeFact(h, fill, 'Last start');
  const controllerCard = h('section', { class: 'card', 'aria-label': 'Controller' },
    h('div', { class: 'card-head' }, glyph('g-chip'), h('h2', { text: 'Controller' }), controllerMeta),
    h('div', { class: 'dials' }, chipDial.node, heapDial.node),
    h('div', { class: 'facts' }, controllerUptime.node, otaFact.node, tasksFact.node, speedFact.node, startFact.node));

  // ---- The piano -----------------------------------------------------------------------------------------------------

  const pianoMeta = h('span', { class: 'meta' });
  const boards = makeBoards(h);
  const firmwareFact = makeFact(h, fill, 'Firmware');
  const boardsFact = makeFact(h, fill, 'Power boards');
  const bluetoothFact = makeFact(h, fill, 'Bluetooth');
  const pedalFact = makeFact(h, fill, 'Pedal board');
  const i2cFact = makeFact(h, fill, 'I²C errors');
  const repeatFact = makeFact(h, fill, 'Repeat period');
  const activeFact = makeFact(h, fill, 'Keys held now');
  const tripsFact = makeFact(h, fill, 'Hold watchdog');
  const extraFacts = h('div', { class: 'facts extra' });
  const extras = new Map();
  let extraNames = null;
  const pianoCard = h('section', { class: 'card', 'aria-label': 'Piano' },
    h('div', { class: 'card-head' }, glyph('g-keys'), h('h2', { text: 'Piano' }), pianoMeta),
    boards.node,
    h('div', { class: 'facts' }, firmwareFact.node, boardsFact.node, bluetoothFact.node, pedalFact.node, i2cFact.node, repeatFact.node, activeFact.node, tripsFact.node),
    extraFacts);

  // ---- Running now ---------------------------------------------------------------------------------------------------

  const appLine = h('span', { class: 'meta' });
  const tasks = h('div', { class: 'tasks' });
  const rows = new Map();
  let rowKeys = null;
  const runningCard = h('section', { class: 'card', 'aria-label': 'Running now' },
    h('div', { class: 'card-head' }, h('h2', { text: 'Running now' }), appLine),
    tasks);

  // ---- Today ---------------------------------------------------------------------------------------------------------

  const todayMeta = h('span', { class: 'meta', text: 'Last 24 hours' });
  const pianoKey = h('span', { hidden: true }, h('i', { class: 'dot-line' }), 'Piano temperature');
  const legend = h('div', { class: 'legend', 'aria-hidden': 'true' },
    h('span', null, h('i'), 'Battery'), h('span', null, h('i', { class: 'dash' }), 'Tablet temperature'), pianoKey);
  const chartSvg = svgNode('svg', { 'aria-hidden': 'true', focusable: 'false' });
  const chartBox = h('div', { class: 'chart' }, chartSvg);
  const chartSays = h('p', { class: 'meta chart-says', text: 'No readings yet: the tablet adds one a minute.' });
  const todayCard = h('section', { class: 'card', 'aria-label': 'Today' },
    h('div', { class: 'card-head' }, h('h2', { text: 'Today' }), todayMeta),
    legend, chartBox, chartSays);
  let chartWidth = 0;

  // ---- Tools ---------------------------------------------------------------------------------------------------------

  const tool = (glyphId, label, run) => {
    const button = h('button', { class: 'outlined', type: 'button' }, glyph(glyphId), h('span', { text: label }));
    button.addEventListener('click', () => run(button));
    return button;
  };
  const report = h('pre', { class: 'tools-report', hidden: true });
  const readStatus = tool('g-refresh', 'Read status', readStatusNow);
  const covers = tool('g-image', 'Find missing covers', findCovers);
  const reconnectButton = tool('g-link', 'Reconnect the piano', reconnect);
  const keysOff = tool('g-off', 'All keys off', allKeysOff);
  const download = h('a', { class: 'outlined', href: host.ROOT + '/api/system/diagnostics', download: 'steven-piano-diagnostics.zip' },
    glyph('g-download'), h('span', { text: 'Download diagnostics' }));
  download.addEventListener('click', () => host.toast('Gathering the diagnostics…'));
  const toolsCard = h('section', { class: 'card', 'aria-label': 'Tools' },
    h('div', { class: 'card-head' }, h('h2', { text: 'Tools' })),
    h('div', { class: 'tools' }, readStatus, covers, reconnectButton, keysOff, download),
    report);

  const root = h('div', { class: 'system-page' },
    h('div', { class: 'sys-grid' }, tabletCard, controllerCard, pianoCard),
    h('div', { class: 'sys-lower' }, runningCard, h('div', { class: 'stack' }, todayCard, toolsCard)));
  fill(body, root);

  // ---- What the state says -----------------------------------------------------------------------------------------

  const instrument = () => (st && st.instruments && st.instruments.instrument) || null;
  const linkState = () => (st && st.link && st.link.state) || null;
  const pianoReady = () => linkState() === 'connected' && !!st && !!st.piano && st.piano.state === 'ready';

  /** The controller's and the piano's cards: another instrument, not connected, reading, too old to say, or ready. */
  function mode(sys) {
    const other = instrument();
    if (other && other.kind === 'midi') return 'midi';
    const link = linkState() || (sys && sys.piano && sys.piano.link && sys.piano.link.state) || 'disconnected';
    if (link !== 'connected') return 'off';
    const piano = sys && sys.piano;
    if (!piano) return 'reading';
    if (piano.state === 'unsupported') return 'old';
    if (piano.state === 'ready' && piano.facts && typeof piano.facts === 'object') return 'ready';
    return 'reading';
  }

  /** A dial's word while it has no figure, by [mode]. */
  const quiet = (m) => ({ midi: 'Not in use', off: 'Not connected', reading: 'Reading…', old: NEEDS_FIRMWARE })[m] || NEEDS_FIRMWARE;

  // ---- Painting ------------------------------------------------------------------------------------------------------

  function paint() {
    if (!showing) return;
    const sys = shared.sys;
    const items = attention(sys, st);
    const parts = new Set(items.map((item) => item.part));
    paintHead(items);
    paintTablet(sys, parts);
    const m = mode(sys);
    paintController(sys, m, parts);
    paintPiano(sys, m, parts);
    paintRunning(sys);
    paintTools();
  }

  function paintHead(items) {
    if (!shared.sys) {
      statusDot.className = 'dot';
      statusText.textContent = 'Reading the system…';
      statusMore.textContent = '';
    } else if (items.length === 0) {
      statusDot.className = 'dot on';
      setText(statusText, 'Everything is running normally');
      statusMore.textContent = '';
    } else {
      statusDot.className = 'dot attention';
      setText(statusText, items[0].text);
      statusMore.textContent = items.length > 1 ? `+${items.length - 1}` : '';
    }
    paintUpdated();
  }

  function paintUpdated() {
    setText(updated, ago(shared.at));
  }

  function paintTablet(sys, parts) {
    const tablet = (sys && sys.tablet) || {};
    setText(tabletMeta, [tablet.model, tablet.android ? `Android ${tablet.android}` : null].filter(Boolean).join(' · '));

    const battery = tablet.battery || {};
    const percent = finite(battery.percent);
    if (percent === null) batteryDial.set({ waiting: true, word: sys ? 'Not known' : '' });
    else {
      const health = typeof battery.health === 'string' ? BAD_HEALTH[battery.health] : null;
      const low = percent < BATTERY_LOW_PCT && battery.charging !== true;
      const word = health || (low ? 'Low' : battery.charging === true ? (percent >= 100 ? 'Full' : 'Charging') : battery.charging === false ? 'On battery' : '');
      const shown = Math.round(percent);
      batteryDial.set({
        share: percent / 100, number: String(shown), unit: '%', word, attention: parts.has('battery'),
        now: shown, max: 100, spoken: `${shown} percent${word ? `, ${word.toLowerCase()}` : ''}`,
      });
    }

    const celsius = finite(battery.tempC);
    if (celsius === null) heatDial.set({ waiting: true, word: sys ? 'Not known' : '' });
    else {
      const word = heatWord((tablet.thermal || {}).status, celsius);
      const shown = Math.round(celsius);
      heatDial.set({
        share: celsius / 60, number: String(shown), unit: '°C', word, attention: parts.has('heat'),
        now: shown, max: 60, spoken: `${shown} degrees, ${word.toLowerCase()}`,
      });
    }

    const memory = tablet.memory || {};
    const total = finite(memory.total);
    const available = finite(memory.available);
    if (total && available !== null) {
      const used = Math.max(0, total - available);
      memoryLevel.set(`${gb(used)} of ${gb(total)} GB in use${memory.low === true ? ' · low' : ''}`, used / total, parts.has('memory'));
    } else memoryLevel.set(null, null, false);

    const storage = tablet.storage || {};
    const size = finite(storage.total);
    const free = finite(storage.free);
    if (size && free !== null) {
      const used = Math.max(0, size - free);
      storageLevel.set(`${gb(used)} of ${gb(size)} GB in use${parts.has('storage') ? ' · almost full' : ''}`, used / size, parts.has('storage'));
    } else storageLevel.set(null, null, false);

    const network = tablet.network || {};
    networkFact.label(network.transport && network.transport !== 'wifi' ? 'Network' : 'Wi-Fi');
    if (network.online === false) networkFact.set('Offline');
    else if (network.transport === 'wifi') {
      const signal = finite(network.signalDbm);
      const down = finite(network.downKbps);
      networkFact.set([signal !== null ? `${host.signed(Math.round(signal))} dBm` : null, down !== null ? `${Math.round(down / 1000)} Mbps` : null]
        .filter(Boolean).join(' · ') || 'Connected');
    } else if (network.transport) networkFact.set(TRANSPORTS[network.transport] || String(network.transport));
    else networkFact.set(null);
    tabletUptime.set(duration(finite(tablet.uptimeMs) === null ? null : tablet.uptimeMs / 1000));
    screenFact.set(tablet.screenOn === true ? 'On' : tablet.screenOn === false ? 'Off' : null);
  }

  function paintController(sys, m, parts) {
    const piano = (sys && sys.piano) || {};
    const diag = piano.diag || {};
    const facts = piano.facts && typeof piano.facts === 'object' ? piano.facts : {};
    const fw = typeof diag.fw === 'string' ? diag.fw.split('+')[0].trim() : '';
    const other = instrument();
    setText(controllerMeta, m === 'midi' ? `${(other && other.name) || 'Another piano'} is the instrument`
      : m === 'off' ? 'Not connected' : fw ? `ESP32-S3 · firmware ${fw}` : 'ESP32-S3');
    const ready = m === 'ready';
    // A fact the piano doesn't give: its own line ready, the mode's quiet word or a dash otherwise.
    const missing = m === 'ready' || m === 'old' ? NEEDS : null;

    const chip = ready ? finite(diag.temp) : null;
    if (chip === null) chipDial.set({ waiting: true, word: ready ? NEEDS_FIRMWARE : quiet(m) });
    else {
      const shown = Math.round(chip);
      const word = chip >= CHIP_HOT_C ? 'Hot' : 'Normal';
      chipDial.set({ share: chip / 80, number: String(shown), unit: '°C', word, attention: parts.has('chip'), now: shown, max: 80, spoken: `${shown} degrees, ${word.toLowerCase()}` });
    }

    const heap = ready ? finite(diag.heap) : null;
    if (heap === null) heapDial.set({ waiting: true, word: ready ? NEEDS_FIRMWARE : quiet(m), high: '' });
    else {
      const size = whole(facts.heapsize);
      const low = parts.has('heap');
      const heapmin = finite(diag.heapmin);
      const lowest = low && heapmin !== null ? `, ran as low as ${kb(heapmin)} kilobytes` : '';
      if (size && size > 0) {
        const share = Math.min(1, Math.max(0, (size - heap) / size));
        const shown = Math.round(share * 100);
        heapDial.set({
          share, number: String(shown), unit: '%', word: low ? 'Ran low' : `${kb(heap)} KB free`, attention: low, high: `${kb(size)} KB`,
          now: shown, max: 100, spoken: `${shown} percent in use, ${kb(heap)} kilobytes free${lowest}`,
        });
      } else {
        // Without the heap's size: the free figure alone, no arc.
        heapDial.set({
          share: null, number: kb(heap), unit: 'KB', word: low ? 'Ran low' : 'free', attention: low, high: '',
          now: Math.round(heap / 1024), max: Math.max(1, Math.round(heap / 1024)), spoken: `${kb(heap)} kilobytes free${lowest}`,
        });
      }
    }

    const ota = typeof diag.ota === 'string' ? diag.ota : null;
    controllerUptime.set(ready && finite(diag.uptime) !== null ? duration(diag.uptime) : missing);
    otaFact.set(ready && ota ? OTA_WORDS[ota] || capital(ota) : missing);
    const taskCount = ready ? finite(diag.tasks) : null;
    const stack = ready ? finite(diag.stack) : null;
    tasksFact.set(taskCount !== null || stack !== null
      ? [taskCount !== null ? String(Math.round(taskCount)) : null, stack !== null ? `loop stack ${kb(stack)} KB spare` : null].filter(Boolean).join(' · ')
      : missing);
    const rate = ready ? finite(diag.looprate) : null;
    const longest = ready ? finite(diag.loopms) : null;
    speedFact.set(rate !== null || longest !== null
      ? [rate !== null ? `${Math.round(rate).toLocaleString()} passes a second` : null, longest !== null ? `longest ${longest.toFixed(1)} ms` : null].filter(Boolean).join(' · ')
      : missing);
    const reset = ready && typeof diag.reset === 'string' ? RESET_WORDS[diag.reset] || capital(diag.reset) : null;
    const crashes = ready ? finite(diag.crashes) : null;
    startFact.set(reset !== null || crashes !== null
      ? [reset, crashes !== null ? `${Math.round(crashes)} ${Math.round(crashes) === 1 ? 'crash' : 'crashes'}` : null].filter(Boolean).join(' · ')
      : missing);
  }

  function paintPiano(sys, m, parts) {
    const piano = (sys && sys.piano) || {};
    const diag = piano.diag || {};
    const facts = piano.facts && typeof piano.facts === 'object' ? piano.facts : {};
    const ready = m === 'ready';
    const missing = m === 'ready' || m === 'old' ? NEEDS : null;
    const list = ready && Array.isArray(diag.boards) ? diag.boards : null;
    const other = instrument();
    boards.set(list);
    setText(pianoMeta, list ? `${list.length * 12} keys · ${list.length} power boards`
      : m === 'midi' ? `${(other && other.name) || 'Another piano'} is the instrument` : m === 'off' ? 'Not connected' : '');

    const fw = ready && typeof diag.fw === 'string' ? diag.fw.split('+')[0].trim() : null;
    const ota = ready && typeof diag.ota === 'string' ? diag.ota : null;
    firmwareFact.set(fw ? [fw, ota].filter(Boolean).join(' · ') : missing);
    if (list) {
      const answering = list.filter((b) => b === 'ok').length;
      const absent = list.map((b, i) => (b === 'missing' ? `C${i + 1}` : null)).filter(Boolean);
      boardsFact.set(`${answering} of ${list.length} answering${absent.length ? ` · ${absent.join(', ')} missing` : ''}`, parts.has('boards'));
    } else boardsFact.set(missing);

    // Bluetooth: the link's state with the live dot, and the piano's own reading of the signal when it gives one.
    if (m === 'midi') bluetoothFact.set(null);
    else {
      const state = linkState() || (piano.link && piano.link.state) || 'disconnected';
      const on = state === 'connected';
      const rssi = ready ? finite(diag.rssi) : null;
      const text = [LINK_WORDS[state] || capital(state), rssi !== null ? `${host.signed(Math.round(rssi))} dBm` : null].filter(Boolean).join(' · ');
      bluetoothFact.set([h('span', { class: on ? 'dot live' : 'dot' }), h('span', { text })], false, `${on}:${text}`);
    }
    const pedal = ready && typeof diag.pedalboard === 'string' ? capital(diag.pedalboard) : null;
    pedalFact.set(pedal || missing);
    const i2c = ready ? finite(diag.i2cfails) : null;
    i2cFact.set(i2c !== null ? Math.round(i2c).toLocaleString() : missing);
    const repeat = ready ? finite(diag.repeatms) : null;
    repeatFact.set(repeat !== null ? `${Math.round(repeat)} ms` : missing);
    const active = ready ? finite(diag.active) : null;
    activeFact.set(active !== null ? Math.round(active).toLocaleString() : missing);
    const trips = ready ? finite(diag.trips) : null;
    tripsFact.set(trips !== null ? `${Math.round(trips).toLocaleString()} ${Math.round(trips) === 1 ? 'release' : 'releases'} since start` : missing);

    // Every fact the page has no row for, as a plain row: its name as sent, its value as sent.
    const others = ready ? Object.keys(facts).filter((name) => !SHOWN_FACTS.has(name) && typeof facts[name] === 'string').sort() : [];
    const key = others.join('|');
    if (extraNames !== key) {
      extraNames = key;
      extras.clear();
      fill(extraFacts, others.map((name) => {
        const row = makeFact(h, fill, name);
        extras.set(name, row);
        return row.node;
      }));
    }
    for (const [name, row] of extras) row.set(String(facts[name]));
    extraFacts.hidden = others.length === 0;
  }

  /** Running now: every row the tablet lists, in its order: a dot, the title, the detail (and its bar), the state's word. */
  function paintRunning(sys) {
    const app = (sys && sys.app) || {};
    const heapMb = finite(app.heapUsed);
    const cpu = finite(app.cpuPct);
    const threads = finite(app.threads);
    setText(appLine, [
      app.version ? `Steven Piano ${app.version}` : 'Steven Piano',
      threads !== null ? `${Math.round(threads)} threads` : null,
      heapMb !== null ? `${Math.round(heapMb / 1048576)} MB` : null,
      cpu !== null ? `${cpu < 1 && cpu > 0 ? cpu.toFixed(1) : Math.round(cpu)}% of a core` : null,
    ].filter(Boolean).join(' · '));

    const list = (sys && Array.isArray(sys.running) ? sys.running : []).filter((item) => item && typeof item.key === 'string');
    const keys = list.map((item) => item.key).join('|');
    if (keys !== rowKeys) {
      rowKeys = keys;
      rows.clear();
      fill(tasks, list.length === 0
        ? h('p', { class: 'note task-empty', text: sys ? 'Nothing to show.' : 'Reading what is running…' })
        : list.map((item) => {
          const row = makeTask(h);
          rows.set(item.key, row);
          return row.node;
        }));
    }
    const playing = !!(st && st.player && st.player.status === 'playing') && linkState() === 'connected';
    for (const item of list) {
      const row = rows.get(item.key);
      if (!row) continue;
      let dot = 'dot';
      if (item.key === 'player' && item.state === 'running' && playing) dot = 'dot live';
      else if (item.state === 'problem' || (item.state === 'waiting' && (item.key === 'covers' || item.key === 'relay'))) dot = 'dot attention';
      else if (item.state === 'running') dot = 'dot on';
      row.set({
        dot,
        title: String(item.title || item.key),
        detail: typeof item.detail === 'string' ? item.detail : '',
        progress: finite(item.progress),
        word: stateWord(item),
        idle: item.state === 'idle' || item.state === 'off',
      });
    }
  }

  function stateWord(item) {
    switch (item.state) {
      case 'running':
        if (item.key === 'player' && st && st.player && st.player.loading) return 'Loading';
        return RUNNING_WORDS[item.key] || 'Running';
      case 'waiting':
        return WAITING_WORDS[item.key] || 'Waiting';
      case 'problem':
        return 'Problem';
      case 'off':
        return 'Off';
      default:
        return item.key === 'player' ? 'Stopped' : 'Idle';
    }
  }

  function paintTools() {
    const ready = pianoReady();
    if (!working.has(readStatus)) readStatus.disabled = !ready;
    if (!working.has(keysOff)) keysOff.disabled = !ready;
  }

  // ---- Today: the day's battery and temperatures -----------------------------------------------------------------------

  function drawChart() {
    const width = Math.round(chartBox.clientWidth);
    if (!width) return;
    chartWidth = width;
    const height = 190;
    chartSvg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    chartSvg.setAttribute('width', String(width));
    chartSvg.setAttribute('height', String(height));
    const left = 28;
    const right = 32;
    const top = 8;
    const bottom = 22;
    const plotW = Math.max(1, width - left - right);
    const plotH = height - top - bottom;
    const every = finite(history && history.everyMs) || 60000;
    const samples = (history && Array.isArray(history.samples) ? history.samples : [])
      .filter((s) => Array.isArray(s) && finite(s[0]) !== null)
      .sort((a, b) => a[0] - b[0]);
    const now = Date.now();
    const start = samples.length ? Math.max(samples[0][0], now - DAY_MS) : now - DAY_MS;
    const end = Math.max(now, start + every);
    const shown = samples.filter((s) => s[0] >= start && s[0] <= end + every);
    const x = (t) => left + (plotW * (t - start)) / (end - start);
    const yBattery = (v) => top + plotH * (1 - clamp(v, 0, 100) / 100);
    const yHeat = (c) => top + plotH * (1 - (clamp(c, 10, 50) - 10) / 40);
    const nodes = [];
    for (const v of [0, 50, 100]) {
      const y = yBattery(v);
      nodes.push(svgNode('line', { class: 'grid-line', x1: left, x2: width - right, y1: n1(y), y2: n1(y) }));
      nodes.push(svgText(String(v), { x: left - 6, y: n1(y + 3), 'text-anchor': 'end' }));
      nodes.push(svgText(`${10 + (40 * v) / 100}°`, { x: width - right + 6, y: n1(y + 3), 'text-anchor': 'start' }));
    }
    for (let i = 0; i <= 4; i++) {
      const t = start + ((end - start) * i) / 4;
      nodes.push(svgText(i === 4 ? 'Now' : clockOf(t), { x: n1(x(t)), y: height - 6, 'text-anchor': i === 0 ? 'start' : i === 4 ? 'end' : 'middle' }));
    }
    const gap = every * 3;
    const battery = runs(shown, 1, gap);
    const tablet = runs(shown, 2, gap);
    const piano = runs(shown, 5, gap);
    const points = (run, y) => run.map(([t, v]) => `${n1(x(t))},${n1(y(v))}`).join(' ');
    for (const run of battery) {
      if (run.length < 2) continue;
      const base = n1(yBattery(0));
      nodes.push(svgNode('polygon', { class: 's1-area', points: `${n1(x(run[0][0]))},${base} ${points(run, yBattery)} ${n1(x(run[run.length - 1][0]))},${base}` }));
    }
    for (const run of tablet) if (run.length >= 2) nodes.push(svgNode('polyline', { class: 's2', points: points(run, yHeat) }));
    for (const run of piano) if (run.length >= 2) nodes.push(svgNode('polyline', { class: 's3', points: points(run, yHeat) }));
    for (const run of battery) if (run.length >= 2) nodes.push(svgNode('polyline', { class: 's1', points: points(run, yBattery) }));
    fill(chartSvg, nodes);

    pianoKey.hidden = !piano.some((run) => run.length > 0);
    setText(todayMeta, samples.length && now - samples[0][0] < DAY_MS - 30 * 60000 ? `Since ${clockOf(samples[0][0])}` : 'Last 24 hours');
    setText(chartSays, sentence(shown));
  }

  // ---- Tools ---------------------------------------------------------------------------------------------------------

  /** The tools at work: each waits for its answer before it can be pressed again. */
  const working = new Set();

  async function busy(button, work) {
    working.add(button);
    button.disabled = true;
    try {
      await work();
    } catch (e) {
      host.failed(e);
    } finally {
      working.delete(button);
      button.disabled = false;
      paintTools();
    }
  }

  function readStatusNow(button) {
    return busy(button, async () => {
      await host.post(host.ROOT + '/api/piano/action', { name: 'status' });
      host.toast('Asked the piano for its report.');
      let answer = null;
      for (let i = 0; i < 6; i++) {
        await wait(i === 0 ? 2500 : 1500);
        answer = await host.get(host.ROOT + '/api/piano');
        if (!answer || !answer.statusReading) break;
      }
      if (answer && typeof answer.statusText === 'string') {
        report.textContent = answer.statusText === '' ? "The piano didn't answer." : answer.statusText;
        report.hidden = false;
      }
    });
  }

  function findCovers(button) {
    return busy(button, async () => {
      await host.post(host.ROOT + '/api/system/tool', { name: 'covers' });
      host.toast('Looking again for the missing covers.');
      soon();
    });
  }

  function reconnect(button) {
    const run = () => busy(button, async () => {
      await host.post(host.ROOT + '/api/system/tool', { name: 'reconnect' });
      host.toast('Reconnecting to the piano…');
      soon();
    });
    const title = 'Reconnect the piano?';
    const message = 'The link drops and connects again: the music stops for a few seconds.';
    if (typeof host.confirm === 'function') host.confirm({ title, message, action: 'Reconnect', run });
    else if (window.confirm(`${title} ${message}`)) run();
  }

  function allKeysOff(button) {
    return busy(button, async () => {
      await host.post(host.ROOT + '/api/piano/action', { name: 'off' });
      host.toast('Every key let go.');
    });
  }

  // ---- Reading -------------------------------------------------------------------------------------------------------

  async function loadHistory() {
    try {
      const answer = await host.get(host.ROOT + '/api/system/history');
      if (answer && Array.isArray(answer.samples)) {
        history = answer;
        historyAt = Date.now();
        if (showing) drawChart();
      }
    } catch (e) {
      // The next minute tries again; the chart keeps what it has.
    }
  }

  /** The piano's live facts, at most once in 10 s on the tablet's side; the page reads them a moment later. */
  async function refreshPiano() {
    if (!pianoReady()) return;
    try {
      const answer = await host.post(host.ROOT + '/api/system/refresh', {});
      if (answer && answer.refreshed) soon();
    } catch (e) {
      // Quiet: the figures stay as they were, and the head says how old they are.
    }
  }

  /** /api/system again in a moment, after something was asked of the tablet. */
  function soon() {
    clearTimeout(timers.soon);
    timers.soon = setTimeout(loadSystem, 1500);
  }

  /** Every read, at once and then on its own beat; the day too when the page opens ([opening]), else once it is a minute old. */
  function start(opening) {
    stop();
    if (!showing || document.hidden) return;
    loadSystem();
    refreshPiano();
    if (opening || Date.now() - historyAt >= HISTORY_MS - 5000) loadHistory();
    else drawChart();
    timers.system = setInterval(loadSystem, host.RELAYED ? SYSTEM_RELAYED_MS : SYSTEM_MS);
    timers.refresh = setInterval(refreshPiano, REFRESH_MS);
    timers.history = setInterval(loadHistory, HISTORY_MS);
    timers.tick = setInterval(paintUpdated, 1000);
  }

  function stop() {
    clearInterval(timers.system);
    clearInterval(timers.refresh);
    clearInterval(timers.history);
    clearInterval(timers.tick);
    clearTimeout(timers.soon);
    timers.system = timers.refresh = timers.history = timers.tick = timers.soon = 0;
  }

  document.addEventListener('visibilitychange', () => {
    if (!showing) return;
    if (document.hidden) stop();
    else start(false);
  });

  const listener = () => paint();
  shared.listeners.add(listener);

  // The chart is drawn at its own width (its words never stretch): again when the card's width changes.
  let resizeFrame = 0;
  const resized = () => {
    if (resizeFrame) return;
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = 0;
      if (showing && Math.round(chartBox.clientWidth) !== chartWidth) drawChart();
    });
  };
  if (typeof ResizeObserver === 'function') new ResizeObserver(resized).observe(chartBox);
  else window.addEventListener('resize', resized);

  return {
    show() {
      showing = true;
      st = host.state() || st;
      paint();
      drawChart();
      start(true);
    },
    hide() {
      showing = false;
      stop();
    },
    render(state) {
      if (state) st = state;
      paint();
    },
  };
}

// ---- The parts ------------------------------------------------------------------------------------------------------

/** A fact that needs newer piano firmware. */
const NEEDS = Object.freeze({ needs: true });

/** A row of a card: its name, and a value as text, as nodes, a dash, or the firmware's tag. */
function makeFact(h, fill, label) {
  const k = h('span', { class: 'k', text: label });
  const v = h('span', { class: 'v' });
  const node = h('div', { class: 'fact' }, k, v);
  let shown = null;
  return {
    node,
    label(text) {
      if (k.textContent !== text) k.textContent = text;
    },
    /** [content]: a string, NEEDS, null (a dash) or nodes with their [key]; [flagged]: amber, and the words say why. */
    set(content, flagged, key) {
      node.classList.toggle('attention', !!flagged);
      const next = content === NEEDS ? 'needs' : content === null || content === undefined ? 'none' : typeof content === 'string' ? `text:${content}` : `nodes:${key}`;
      if (next === shown) return;
      shown = next;
      if (content === NEEDS) fill(v, h('span', { class: 'tag', text: NEEDS_FIRMWARE }));
      else if (content === null || content === undefined) v.textContent = '—';
      else if (typeof content === 'string') v.textContent = content;
      else fill(v, content);
    },
  };
}

/** Memory or storage: what is in use, and a meter of it. */
function makeLevel(h, label) {
  const v = h('span', { class: 'v', text: '—' });
  const bar = h('i');
  const node = h('div', { class: 'level' }, h('div', { class: 'top' }, h('span', { class: 'k', text: label }), v), h('div', { class: 'meter', 'aria-hidden': 'true' }, bar));
  return {
    node,
    set(text, share, flagged) {
      setText(v, text || '—');
      node.classList.toggle('attention', !!flagged);
      bar.style.setProperty('--fill', `${share === null || share === undefined ? 0 : Math.round(clamp(share, 0, 1) * 1000) / 10}%`);
    },
  };
}

/** A row of Running now. */
function makeTask(h) {
  const dot = h('span', { class: 'dot' });
  const title = h('span', { class: 'name' });
  const detailText = h('span', { class: 'detail-text' });
  const fillBar = h('i');
  const bar = h('div', { class: 'bar', hidden: true, 'aria-hidden': 'true' }, fillBar);
  const word = h('span', { class: 'state' });
  const node = h('div', { class: 'task' }, dot, title, h('div', { class: 'detail' }, detailText, bar), word);
  return {
    node,
    set({ dot: dotClass, title: titleText, detail, progress, word: wordText, idle }) {
      if (dot.className !== dotClass) dot.className = dotClass;
      setText(title, titleText);
      setText(detailText, detail);
      if (detailText.title !== detail) detailText.title = detail;
      setText(word, wordText);
      node.classList.toggle('idle', !!idle);
      bar.hidden = progress === null;
      if (progress !== null) fillBar.style.setProperty('--fill', `${Math.round(clamp(progress, 0, 1) * 1000) / 10}%`);
    },
  };
}

/**
 * A dial: a 270° arc over ticks every 9° (major every 45°), the figure and its word inside, the two ends under the arc,
 * its name below; `role="meter"`. The arc eases to a new value (system.css: 480 ms, none under reduced motion).
 */
function makeDial(h, label, low, high) {
  const cx = 66;
  const cy = 68;
  const r = 52;
  const ring = 2 * Math.PI * r;
  const sweep = ring * 0.75;
  const svgEl = svgNode('svg', { viewBox: '0 0 132 132', 'aria-hidden': 'true', focusable: 'false' });
  for (let i = 0; i <= 30; i++) {
    const a = ((135 + i * 9) * Math.PI) / 180;
    const major = i % 5 === 0;
    const r1 = r + 9;
    const r2 = r + (major ? 15 : 12.5);
    svgEl.append(svgNode('line', {
      class: major ? 'tick major' : 'tick',
      x1: n2(cx + r1 * Math.cos(a)), y1: n2(cy + r1 * Math.sin(a)), x2: n2(cx + r2 * Math.cos(a)), y2: n2(cy + r2 * Math.sin(a)),
    }));
  }
  const arc = { cx, cy, r, transform: `rotate(135 ${cx} ${cy})` };
  svgEl.append(svgNode('circle', { ...arc, class: 'track', 'stroke-dasharray': `${n2(sweep)} ${n2(ring)}` }));
  const fillArc = svgNode('circle', { ...arc, class: 'fill' });
  fillArc.style.setProperty('stroke-dasharray', `0.01 ${n2(ring)}`);
  svgEl.append(fillArc);

  const figure = h('span', { text: '—' });
  const unit = h('small');
  const word = h('div', { class: 'word' });
  const lowEnd = h('span', { text: low });
  const highEnd = h('span', { text: high });
  const node = h('div', { class: 'dial waiting', role: 'meter', 'aria-label': label, 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': '0' },
    svgEl,
    h('div', { class: 'read' }, h('div', { class: 'num' }, figure, unit), word),
    h('div', { class: 'ends', 'aria-hidden': 'true' }, lowEnd, highEnd),
    h('p', { class: 'eyebrow', 'aria-hidden': 'true', text: label }));
  let dash = null;
  return {
    node,
    /**
     * [waiting]: no figure, the arc empty, [word] saying why. Otherwise [share] 0–1 for the arc (null: none), [number] and
     * [unit], [word], [attention], and for the meter [now] of [max] and [spoken].
     */
    set(d) {
      const waiting = !!d.waiting;
      node.classList.toggle('waiting', waiting);
      node.classList.toggle('attention', !waiting && !!d.attention);
      node.classList.toggle('no-arc', !waiting && (d.share === null || d.share === undefined));
      if (d.high !== undefined) setText(highEnd, d.high);
      highEnd.hidden = !highEnd.textContent;
      if (waiting) {
        setText(figure, '—');
        setText(unit, '');
        setText(word, d.word || '');
        node.setAttribute('aria-valuenow', '0');
        node.setAttribute('aria-valuetext', d.word ? d.word.toLowerCase() : 'not known');
        return;
      }
      setText(figure, d.number);
      setText(unit, d.unit || '');
      setText(word, d.word || '');
      node.setAttribute('aria-valuemax', String(d.max));
      node.setAttribute('aria-valuenow', String(d.now));
      node.setAttribute('aria-valuetext', d.spoken);
      if (d.share !== null && d.share !== undefined) {
        const next = `${n2(Math.max(0.01, sweep * clamp(d.share, 0, 1)))} ${n2(ring)}`;
        if (next !== dash) {
          dash = next;
          fillArc.style.setProperty('stroke-dasharray', next);
        }
      }
    },
  };
}

/** The seven power boards as seven octaves of keys, C1 to C7: each answering, or hollow and amber when missing. */
function makeBoards(h) {
  const svgEl = svgNode('svg', { class: 'octaves', viewBox: '0 0 420 62', role: 'img', 'aria-label': 'Power boards: not known', focusable: 'false' });
  const octaves = [];
  for (let o = 0; o < 7; o++) {
    const x = o * 60;
    const group = svgNode('g', { class: 'octave unknown' });
    group.append(svgNode('rect', { class: 'case', x: x + 2, y: 2, width: 56, height: 44, rx: 6 }));
    for (let k = 0; k < 7; k++) group.append(svgNode('rect', { class: 'natural', x: n2(x + 4 + k * 7.5), y: 5, width: 6.3, height: 38, rx: 1.5 }));
    for (const k of [0, 1, 3, 4, 5]) group.append(svgNode('rect', { class: 'sharp', x: n2(x + 4 + k * 7.5 + 4.6), y: 5, width: 4.4, height: 23, rx: 1.2 }));
    group.append(svgNode('rect', { class: 'rail', x: x + 2, y: 54, width: 56, height: 6, rx: 3 }));
    svgEl.append(group);
    octaves.push(group);
  }
  const labels = octaves.map((_, i) => h('span', { text: `C${i + 1}` }));
  const node = h('div', { class: 'boards' }, svgEl, h('div', { class: 'board-labels', 'aria-hidden': 'true' }, labels));
  let shown = null;
  return {
    node,
    /** [list]: `ok` / `missing` per board, C1 first; null while not known. */
    set(list) {
      const key = list ? list.join(',') : '';
      if (key === shown) return;
      shown = key;
      const spoken = [];
      octaves.forEach((group, i) => {
        const b = list ? list[i] : undefined;
        const state = b === 'ok' ? 'ok' : b === 'missing' ? 'missing' : 'unknown';
        group.setAttribute('class', `octave ${state}`);
        if (state === 'missing') labels[i].replaceChildren(`C${i + 1}`, h('span', { class: 'word', text: 'Missing' }));
        else labels[i].textContent = state === 'ok' ? `C${i + 1} · OK` : `C${i + 1}`;
        labels[i].className = state === 'missing' ? 'missing' : '';
        if (state !== 'unknown') spoken.push(`C${i + 1} ${state === 'ok' ? 'OK' : 'missing'}`);
      });
      svgEl.setAttribute('aria-label', spoken.length ? `Power boards: ${spoken.join(', ')}` : 'Power boards: not known');
    },
  };
}

// ---- Small helpers --------------------------------------------------------------------------------------------------

function svgNode(tag, attrs) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value !== null && value !== undefined && value !== false) node.setAttribute(key, String(value));
  }
  return node;
}

function svgText(text, attrs) {
  const node = svgNode('text', attrs);
  node.textContent = text;
  return node;
}

/** The runs of a series ([index] of each sample) with a value, split where one is missing or the samples stop for a while. */
function runs(samples, index, gap) {
  const out = [];
  let run = [];
  let last = null;
  for (const sample of samples) {
    const value = finite(sample[index]);
    if (value === null || (last !== null && sample[0] - last > gap)) {
      if (run.length) out.push(run);
      run = [];
    }
    if (value !== null) run.push([sample[0], value]);
    last = sample[0];
  }
  if (run.length) out.push(run);
  return out;
}

/** The day in one sentence: "Battery between 60 and 100 %, tablet between 27 and 35 °C". */
function sentence(samples) {
  const span = (index) => {
    const values = samples.map((s) => finite(s[index])).filter((v) => v !== null);
    if (values.length === 0) return null;
    return [Math.round(Math.min(...values)), Math.round(Math.max(...values))];
  };
  const say = (name, range, unit) => {
    if (!range) return null;
    return range[0] === range[1] ? `${name} at ${range[0]} ${unit}` : `${name} between ${range[0]} and ${range[1]} ${unit}`;
  };
  const parts = [say('battery', span(1), '%'), say('tablet', span(2), '°C'), say('piano', span(5), '°C')].filter(Boolean);
  if (parts.length === 0) return 'No readings yet: the tablet adds one a minute.';
  const text = parts.join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** "Updated 4 s ago", from when the figures were read on this page. */
function ago(at) {
  if (!at) return 'Not updated yet';
  const s = Math.max(0, Math.round((Date.now() - at) / 1000));
  if (s < 5) return 'Updated just now';
  if (s < 60) return `Updated ${s} s ago`;
  const m = Math.floor(s / 60);
  return m < 60 ? `Updated ${m} min ago` : `Updated at ${clockOf(at)}`;
}

/** "3 days 4 h", "2 h 5 min", "5 min", "40 s"; null when not known. */
function duration(seconds) {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return null;
  const s = Math.floor(seconds);
  const days = Math.floor(s / 86400);
  const hours = Math.floor((s % 86400) / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  if (days > 0) return `${days} ${days === 1 ? 'day' : 'days'} ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return `${minutes} min`;
  return `${s} s`;
}

/** Gigabytes as the cards say them: "2.4", "4", "24". */
function gb(bytes) {
  const v = bytes / 1e9;
  return v >= 10 ? String(Math.round(v)) : trimmed(v.toFixed(1));
}

/** Kilobytes: "13.9", "212". */
function kb(bytes) {
  const v = bytes / 1024;
  return v >= 100 ? String(Math.round(v)) : trimmed(v.toFixed(1));
}

const trimmed = (text) => text.replace(/\.0$/, '');

/** "14:05" in this browser's time. */
function clockOf(epochMs) {
  const d = new Date(epochMs);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const finite = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const n1 = (v) => Math.round(v * 10) / 10;
const n2 = (v) => Math.round(v * 100) / 100;
const capital = (word) => (word ? word.charAt(0).toUpperCase() + word.slice(1) : word);
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A whole number from the piano's text ("348160"), or null. */
function whole(text) {
  if (typeof text !== 'string' && typeof text !== 'number') return null;
  const n = Number(String(text).trim());
  return Number.isFinite(n) ? Math.round(n) : null;
}

function setText(node, text) {
  const value = text === null || text === undefined ? '' : String(text);
  if (node.textContent !== value) node.textContent = value;
}

function guarded(fn) {
  try {
    fn();
  } catch (e) {
    if (window.console) console.error(e);
  }
}
