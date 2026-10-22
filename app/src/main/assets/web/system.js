/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The panel's System page (DESIGN.md › v1.18 — M47b): an ES module the frame (app.js) imports after the first state,
// create(host, body, tools) → { show(), hide(), render(state) }, which Settings calls for its first page (v1.20 — M53,
// through host.system()), and vitals(host, node), the rail's foot. Three cards
// (the tablet, the controller, the piano), then Running now beside Today and Tools, from /api/system (every 5 s while
// the page shows, 10 s through the relay), /api/system/refresh (every 15 s while the piano is ready) and
// /api/system/history (on opening, then every minute); nothing while the page is closed or the window hidden. Built
// once with DOM calls and brought up to date in place: a dial's arc eases to its new value (480 ms, none under reduced
// motion) and nothing loops. Amber, `--attention`, only where attention() says something needs it, and never alone.

const NEEDS_FIRMWARE = 'Needs newer firmware';

/** A key held on (v1.23 — M55; the tablet's Attention.KEY_HELD). */
const KEY_HELD = 'A key is held on';

/** The 24 V power plug's words (v1.26 — M62): the tablet's Attention.PLUG_STUCK and PowerCopy's. */
const PLUG_STUCK = "The power plug's relay may be stuck on";
const PLUG_WELDED = 'After the 24 V was cut the plug still reads on: its relay may be stuck. Unplug the supply and check the plug.';
const PLUG_NOT_ANSWERING = 'Not answering';
const RAIL_OFF = '24 V off';

/**
 * Whether the power plug switches the 24 V and it isn't up now (v1.26 — M62; the tablet's Attention.railOff): off, coming
 * up or going off. The power boards are unpowered then, so a board that doesn't answer is the rail off, not a fault:
 * 0 of 7 is normal with the 24 V off. From /api/system's [sys], the same read as the boards it excuses.
 */
function railOff(sys) {
  const power = sys && sys.power;
  return !!power && power.paired === true && power.on === true && power.rail !== 'on' && power.rail !== 'kept';
}

/**
 * The plug as the tablet's System page says it (PowerCopy.fact): "On · 23 s left · 6.4 W", "On · 6.4 W", "Off · 0.0 W",
 * or "Not answering" while there is no reading. [tabletNow]: the tablet's clock now (ms), against which its timer runs.
 */
function plugFact(power, tabletNow) {
  if (typeof power.output !== 'boolean') return PLUG_NOT_ANSWERING;
  const watts = finite(power.watts);
  const draw = watts === null ? '—' : `${watts.toFixed(1)} W`;
  if (!power.output) return `Off · ${draw}`;
  const ends = finite(power.timerEndsAt);
  const left = ends === null ? 0 : Math.ceil((ends - tabletNow) / 1000);
  return left > 0 ? `On · ${left} s left · ${draw}` : `On · ${draw}`;
}

/**
 * Debugging left on (v1.27 — M63; the tablet's v1.25 — M58): wireless debugging is an open port with full control of the
 * tablet. What needs attention says it as the tablet's Attention.debugging does; the Tablet card's row as SystemCopy's.
 */
const DEBUGGING = 'Debugging';

function debuggingOn(debug) {
  const d = debug || {};
  if (d.usb === true && d.wireless === true) return 'USB and wireless debugging are on';
  if (d.usb === true) return 'USB debugging is on';
  if (d.wireless === true) return 'Wireless debugging is on';
  if (d.wireless === null && d.developer === true) return 'Developer options are on';
  return null;
}

/** The Tablet card's Debugging row (SystemCopy.debugging): "USB", "Wireless", "USB and wireless", "Developer options on", "Off"; null before a reading. */
function debuggingWord(debug) {
  if (!debug) return null;
  if (debug.usb === true && debug.wireless === true) return 'USB and wireless';
  if (debug.usb === true) return 'USB';
  if (debug.wireless === true) return 'Wireless';
  if (debug.wireless === null && debug.developer === true) return 'Developer options on';
  if (debug.usb === null && debug.wireless === null && debug.developer === null) return null;
  return 'Off';
}

/**
 * The piano's Bluetooth lock (v1.27 — M63; firmware 2.1.0, the tablet's v1.25 — M58) as the tablet's Firmware page says
 * it (ui/LockCopy.kt), from the state's `piano.lock`: null while the piano has no lock, or before it has said.
 */
const LOCK = 'Bluetooth lock';
const LOCK_WORDS = { open: 'Open: any device can claim it', pairing: 'Pairing open for 5 min', claimed: 'Locked to this tablet' };

function lockWords(lock) {
  if (!lock) return null;
  if (lock.state === 'lockedOut' || lock.state === 'notAccepted') return 'Locked to another device';
  if (lock.state === 'proving') return "Checking this tablet's key…";
  if (lock.state !== 'trusted') return null;
  return LOCK_WORDS[lock.piano] || null;
}

/** Guest play while it lasts (LockCopy.GUEST and timeLeft): "52 min left", rounded up; its last minute in words. */
const GUEST = 'Let anyone play for 1 hour';

function guestLeft(seconds) {
  return seconds < 60 ? 'Less than a minute left' : `${Math.ceil(seconds / 60)} min left`;
}

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
 * the cloud address): each `{ part, text, short }`, [part] naming what it colours (held, plug, battery, heat, memory,
 * storage, boards, chip, heap, debugging, relay, running), [text] the head's sentence and [short] the rail's word. Empty
 * when all is well. Boards that don't answer while the power plug keeps the 24 V off are no item (v1.26 — M62: [railOff]);
 * debugging left on is one, in the tablet's place (v1.27 — M63).
 */
export function attention(sys, state) {
  const items = [];
  if (!sys || typeof sys !== 'object') return items;
  const piano = sys.piano || {};
  const connected = piano.link && piano.link.state === 'connected';
  // A key held on (v1.23 — M55): the piano still holds a key 5 s after anything last played. First of all: it can overheat.
  if (connected && piano.keyHeld === true) items.push({ part: 'held', text: KEY_HELD, short: 'key held' });
  // The 24 V plug's relay still on after a cut (v1.26 — M62, the tablet's order): the tablet can't cut the 24 V.
  if (sys.power && sys.power.welded === true) items.push({ part: 'plug', text: PLUG_STUCK, short: 'relay stuck' });
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

  const diag = piano.diag || {};
  if (connected) {
    const missing = Array.isArray(diag.boards) ? diag.boards.filter((b) => b === 'missing').length : 0;
    // Not while the power plug keeps the 24 V off (v1.26 — M62): unpowered boards don't answer.
    if (missing > 0 && !railOff(sys)) {
      items.push({ part: 'boards', text: missing === 1 ? 'A power board is missing' : `${missing} power boards are missing`, short: missing === 1 ? 'board missing' : `${missing} boards missing` });
    }
    const chip = finite(diag.temp);
    if (chip !== null && chip >= CHIP_HOT_C) items.push({ part: 'chip', text: `The controller is hot · ${Math.round(chip)} °C`, short: 'hot' });
    const heapmin = finite(diag.heapmin);
    if (heapmin !== null && heapmin < HEAP_LOW_BYTES) items.push({ part: 'heap', text: 'The controller ran low on memory', short: 'low memory' });
  }
  const debugging = debuggingOn(tablet.debugging);
  if (debugging) items.push({ part: 'debugging', text: debugging, short: 'debugging on' });

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
 * The rail's foot (v1.20 — M53: no words while all is well): the tablet's battery and its temperature as a glyph and a
 * figure (82%, 31 °C), their names for screen readers alone, and the piano's line only while it needs attention. Amber
 * where attention() says so, with its word ("15% low", "Hot", "Board missing"). Read once a minute, or from the page's
 * own reads while it is open; the piano's line follows the state between them.
 */
export function vitals(host, node) {
  bind(host);
  if (!node || !host || typeof host.h !== 'function') return;
  const { h } = host;
  const line = (glyphId, label) => {
    const value = h('b', { text: '—' });
    return { value, node: h('p', { class: 'vital' }, host.glyph(glyphId), h('span', { class: 'visually-hidden', text: label }), value) };
  };
  const tablet = line('g-battery', "The tablet's battery");
  const heat = line('g-temp', "The tablet's temperature");
  const piano = line('g-keys', 'The piano');
  piano.node.hidden = true;
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
    const trouble = instrument && instrument.kind === 'midi' ? null : first(['held', 'plug', 'boards', 'chip', 'heap']);
    const flagged = !!trouble && linkState === 'connected';
    set(piano, flagged ? capital(trouble.short) : '—', flagged);
    piano.node.hidden = !flagged;
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
  const timers = { system: 0, refresh: 0, history: 0, tick: 0, soon: 0, left: 0 };

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
  const debuggingFact = makeFact(h, fill, DEBUGGING);
  const tabletCard = h('section', { class: 'card', 'aria-label': 'Tablet' },
    h('div', { class: 'card-head' }, glyph('g-tablet'), h('h2', { text: 'Tablet' }), tabletMeta),
    h('div', { class: 'dials' }, batteryDial.node, heatDial.node),
    memoryLevel.node, storageLevel.node,
    h('div', { class: 'facts' }, networkFact.node, tabletUptime.node, screenFact.node, debuggingFact.node));

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
  const lockFact = makeFact(h, fill, LOCK);
  const guestFact = makeFact(h, fill, GUEST);
  const pedalFact = makeFact(h, fill, 'Pedal board');
  const i2cFact = makeFact(h, fill, 'I²C errors');
  const repeatFact = makeFact(h, fill, 'Repeat period');
  const activeFact = makeFact(h, fill, 'Keys held now');
  const tripsFact = makeFact(h, fill, 'Hold watchdog');
  const extraFacts = h('div', { class: 'facts extra' });
  const extras = new Map();
  let extraNames = null;
  // The 24 V power plug (v1.26 — M62), last as on the tablet's card, only with a plug paired: its reading, and under it
  // in amber a relay that stayed on after a cut, and the tablet's words for a failure ("Only 6 of 7 power boards answered").
  const powerFact = makeFact(h, fill, 'Power plug');
  const plugWelded = h('p', { class: 'plug-note', hidden: true, text: PLUG_WELDED });
  const plugMessage = h('p', { class: 'plug-note', hidden: true });
  const plugFacts = h('div', { class: 'facts extra', hidden: true }, powerFact.node, plugWelded, plugMessage);
  const pianoCard = h('section', { class: 'card', 'aria-label': 'Piano' },
    h('div', { class: 'card-head' }, glyph('g-keys'), h('h2', { text: 'Piano' }), pianoMeta),
    boards.node,
    h('div', { class: 'facts' }, firmwareFact.node, boardsFact.node, bluetoothFact.node, lockFact.node, guestFact.node, pedalFact.node, i2cFact.node, repeatFact.node, activeFact.node, tripsFact.node),
    extraFacts, plugFacts);

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
  const updateApp = tool('g-download', 'Install the update', installUpdate);
  const download = h('a', { class: 'outlined', href: host.ROOT + '/api/system/diagnostics', download: 'steven-piano-diagnostics.zip' },
    glyph('g-download'), h('span', { text: 'Download diagnostics' }));
  download.addEventListener('click', () => host.toast('Gathering the diagnostics…'));
  const toolsCard = h('section', { class: 'card', 'aria-label': 'Tools' },
    h('div', { class: 'card-head' }, h('h2', { text: 'Tools' })),
    h('div', { class: 'tools' }, readStatus, covers, reconnectButton, keysOff, updateApp, download),
    report);

  // ---- Listen and test (v1.23 — M55): the tablet's microphone live in this browser, and every key struck and heard -----

  const ear = makeEar();
  /** Whether this panel asked to hear: its Listen, its test. Only then does it play the frames its session is sent. */
  const hear = { live: false, test: false };
  /** What the last state said, so a hearing's end (on → off) lets this panel's ears go. */
  const seen = { live: false, running: false };
  /** Whether the ear plays now: it rests only when this panel stops hearing, never on a state that came before its own. */
  let hearing = false;
  const refused = { live: '', test: '' };
  const listenOff = h('p', { class: 'note listen-off', hidden: true, text: LISTEN_OFF });
  const listenButton = h('button', { class: 'outlined', type: 'button' });
  const listenLeft = h('span', { class: 'listen-line' });
  const levelFill = h('i');
  const level = h('div', { class: 'listen-level', hidden: true, 'aria-hidden': 'true' }, levelFill);
  const volume = h('input', { class: 'range', type: 'range', min: '0', max: '100', step: '1', 'aria-label': 'Volume in this browser' });
  const volumeBox = h('label', { class: 'listen-volume', hidden: true }, h('span', { class: 'meta', text: 'Volume' }), volume);
  const listenRow = h('div', { class: 'listen-row' }, listenButton, listenLeft, level, volumeBox);
  const testButton = h('button', { class: 'outlined', type: 'button' });
  const testLine = h('span', { class: 'listen-line' });
  const testRow = h('div', { class: 'listen-row' }, testButton, testLine);
  const testFill = h('i');
  const testBar = h('div', { class: 'bar listen-progress', hidden: true, 'aria-hidden': 'true' }, testFill);
  const keymap = makeKeyMap(h);
  const result = h('p', { class: 'listen-result', role: 'status' });
  const listenCard = h('section', { class: 'card listen-card', 'aria-label': 'Listen and test' },
    h('div', { class: 'card-head' }, glyph('g-mic'), h('h2', { text: 'Listen and test' }), h('span', { class: 'meta', text: "The tablet's microphone" })),
    listenOff, listenRow, testRow, testBar, keymap.node, result,
    h('p', { class: 'meta listen-note', text: "Played live in this browser from the tablet's microphone. Nothing is recorded." }));
  volume.value = String(Math.round(ear.volume() * 100));
  volume.style.setProperty('--fill', `${volume.value}%`);
  volume.addEventListener('input', () => {
    volume.style.setProperty('--fill', `${volume.value}%`);
    ear.setVolume(Number(volume.value) / 100);
  });
  listenButton.addEventListener('click', () => toggleListen());
  testButton.addEventListener('click', () => toggleTest());
  // The frames the socket brings this session (app.js, host.audio), for as long as the page lives: played only when asked.
  if (typeof host.audio === 'function') {
    host.audio((message) => {
      if (!hear.live && !hear.test) return;
      const heard = ear.play(message, host.RELAYED ? LEAD_RELAYED_S : LEAD_S);
      if (heard !== null) levelFill.style.setProperty('--fill', `${Math.round(heard * 100)}%`);
    });
  }

  const root = h('div', { class: 'system-page' },
    h('div', { class: 'sys-grid' }, tabletCard, controllerCard, pianoCard),
    h('div', { class: 'sys-lower' }, runningCard, h('div', { class: 'stack' }, listenCard, todayCard, toolsCard)));
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
    paintListen();
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
    // v1.27 — M63: USB or wireless debugging left on, amber with the head's words (the tablet's System page).
    debuggingFact.set(debuggingWord(tablet.debugging), parts.has('debugging'));
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
    // With the power plug keeping the 24 V off (v1.26 — M62), a board that doesn't answer is unpowered: never amber.
    const off = railOff(sys);
    boards.set(list, off);
    setText(pianoMeta, list ? `${list.length * 12} keys · ${list.length} power boards`
      : m === 'midi' ? `${(other && other.name) || 'Another piano'} is the instrument` : m === 'off' ? 'Not connected' : '');

    const fw = ready && typeof diag.fw === 'string' ? diag.fw.split('+')[0].trim() : null;
    const ota = ready && typeof diag.ota === 'string' ? diag.ota : null;
    firmwareFact.set(fw ? [fw, ota].filter(Boolean).join(' · ') : missing);
    if (list) {
      const answering = list.filter((b) => b === 'ok').length;
      const absent = list.map((b, i) => (b === 'missing' ? `C${i + 1}` : null)).filter(Boolean);
      const why = off ? ` · ${RAIL_OFF}` : absent.length ? ` · ${absent.join(', ')} missing` : '';
      boardsFact.set(`${answering} of ${list.length} answering${why}`, parts.has('boards'));
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
    // The piano's Bluetooth lock (v1.27 — M63), while it has one, and guest play while it lasts: the tablet's words.
    const lock = m === 'midi' ? null : (st && st.piano && st.piano.lock) || null;
    const lockText = lockWords(lock);
    lockFact.node.hidden = !lockText;
    lockFact.set(lockText);
    const guest = lockText && finite(lock.guestSecondsLeft) > 0 ? lock.guestSecondsLeft : 0;
    guestFact.node.hidden = !guest;
    guestFact.set(guest ? guestLeft(guest) : null);
    const pedal = ready && typeof diag.pedalboard === 'string' ? capital(diag.pedalboard) : null;
    pedalFact.set(pedal || missing);
    const i2c = ready ? finite(diag.i2cfails) : null;
    i2cFact.set(i2c !== null ? Math.round(i2c).toLocaleString() : missing);
    const repeat = ready ? finite(diag.repeatms) : null;
    repeatFact.set(repeat !== null ? `${Math.round(repeat)} ms` : missing);
    const active = ready ? finite(diag.active) : null;
    activeFact.set(active !== null ? Math.round(active).toLocaleString() : missing, active !== null && parts.has('held'));
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
    paintPower();
  }

  /**
   * The Power plug row (v1.26 — M62), the tablet's words for it, hidden with no plug paired: amber while its relay may be
   * stuck on, that warning under it, and the tablet's words for a failure while it has any. Again every second, from the
   * last read, so its timer counts down as the tablet's does; the tablet's clock is the read's time plus what passed here.
   */
  function paintPower() {
    const sys = shared.sys;
    const power = sys && sys.power;
    const paired = !!power && power.paired === true;
    plugFacts.hidden = !paired;
    if (!paired) return;
    const at = finite(sys.at);
    const tabletNow = at === null ? Date.now() : at + (Date.now() - shared.at);
    const welded = power.welded === true;
    powerFact.set(plugFact(power, tabletNow), welded);
    plugWelded.hidden = !welded;
    const said = power.attention === true && typeof power.message === 'string' ? power.message : '';
    setText(plugMessage, said);
    plugMessage.hidden = !said;
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

  /** Listen and test, from the state's `listen` and `keytest` (v1.23 — M55). */
  function paintListen() {
    const listen = (st && st.listen) || {};
    const test = (st && st.keytest) || {};
    const allowed = listen.allowed === true;
    const until = finite(listen.until);
    const live = listen.on === true && until !== null;
    const running = test.running === true;
    // A hearing that ended (the five minutes, a Stop anywhere, the tablet's switch) lets this panel's ears go.
    if (seen.live && !live) hear.live = false;
    if (seen.running && !running) hear.test = false;
    seen.live = live;
    seen.running = running;
    const now = hear.live || hear.test;
    if (hearing && !now) ear.rest();
    hearing = now;
    level.hidden = !now;
    volumeBox.hidden = !now;
    listenOff.hidden = allowed || running;
    listenRow.hidden = !allowed && !live;
    testRow.hidden = !allowed && !running;
    label(listenButton, hear.live ? 'g-stop' : 'g-mic', hear.live ? 'Stop' : 'Listen');
    label(testButton, running ? 'g-stop' : 'g-keys', running ? 'Stop' : 'Test every key');
    if (!working.has(listenButton)) listenButton.disabled = !allowed && !hear.live;
    if (!working.has(testButton)) testButton.disabled = !allowed && !running;
    paintLeft();
    clearInterval(timers.left);
    timers.left = live && showing ? setInterval(paintLeft, 1000) : 0;

    const total = finite(test.total) || 84;
    const done = finite(test.done) || 0;
    const key = finite(test.key);
    setText(testLine, running ? (key !== null ? `Key ${done} of ${total}` : done === 0 ? 'Listening to the room…' : 'Checking the last keys…') : refused.test);
    testBar.hidden = !running;
    if (running) testFill.style.setProperty('--fill', `${Math.round((done / Math.max(1, total)) * 1000) / 10}%`);
    const results = Array.isArray(test.results) ? test.results.filter((r) => r && typeof r.key === 'number') : null;
    const words = running ? '' : results ? resultLine(results) : typeof test.stopped === 'string' ? test.stopped : 'Not tested yet';
    setText(result, words);
    result.hidden = running;
    result.classList.toggle('attention', !!results && results.some((r) => r.heard === false));
    keymap.set(test, running ? `Key ${done} of ${total}` : words);
  }

  /** "4:12 left" while a panel listens live, or why this panel's Listen was refused. */
  function paintLeft() {
    const listen = (st && st.listen) || {};
    const until = finite(listen.until);
    if (listen.on === true && until !== null) setText(listenLeft, leftWords(until - Date.now(), hear.live));
    else setText(listenLeft, refused.live);
  }

  /** Listen, or Stop (any signed-in panel may stop it): the room live in this browser for five minutes at most. */
  async function toggleListen() {
    if (working.has(listenButton)) return;
    if (hear.live) {
      hear.live = false;
      paintListen();
      await busy(listenButton, () => host.post(host.ROOT + '/api/listen/stop'));
      return;
    }
    if (!ear.wake()) {
      host.toast("This browser can't play the tablet's sound.");
      return;
    }
    refused.live = '';
    hear.live = true;   // at once: the state saying so may come before the answer does
    paintListen();
    await busy(listenButton, async () => {
      try {
        await host.post(host.ROOT + '/api/listen/start');
      } catch (e) {
        hear.live = false;
        refused.live = (e && e.status === 409 && e.message) || '';
        throw e;
      }
    });
    paintListen();
  }

  /** Test every key, or Stop: the piano strikes its 84 keys in turn while this browser hears it (if it can play sound). */
  async function toggleTest() {
    if (working.has(testButton)) return;
    const test = (st && st.keytest) || {};
    if (test.running === true) {
      await busy(testButton, () => host.post(host.ROOT + '/api/keytest/stop'));
      return;
    }
    refused.test = '';
    hear.test = ear.wake();   // at once, as Listen's; a browser that can't play sound still runs the test
    paintListen();
    await busy(testButton, async () => {
      try {
        await host.post(host.ROOT + '/api/keytest/start');
      } catch (e) {
        hear.test = false;
        refused.test = (e && e.status === 409 && e.message) || '';
        throw e;
      }
    });
    paintListen();
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

  /** The app's update from a distance (v1.23): the tablet checks, installs and starts again by itself. */
  function installUpdate(button) {
    const run = () => busy(button, async () => {
      await host.post(host.ROOT + '/api/system/tool', { name: 'update' });
      host.toast('The tablet is looking for the update. If there is one it installs it and starts again; this page reconnects by itself.');
      soon();
    });
    const title = 'Install the update?';
    const message = 'The tablet downloads the latest Steven Piano, installs it and starts again: the music stops for a minute. Running now shows how it goes.';
    if (typeof host.confirm === 'function') host.confirm({ title, message, action: 'Install', run });
    else if (window.confirm(`${title} ${message}`)) run();
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
    timers.tick = setInterval(() => {
      paintUpdated();
      paintPower();   // v1.26 — M62: the plug's timer, as the tablet counts it
    }, 1000);
  }

  function stop() {
    clearInterval(timers.system);
    clearInterval(timers.refresh);
    clearInterval(timers.history);
    clearInterval(timers.tick);
    clearTimeout(timers.soon);
    clearInterval(timers.left);
    timers.system = timers.refresh = timers.history = timers.tick = timers.soon = timers.left = 0;
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

/**
 * The seven power boards as seven octaves of keys, C1 to C7: each answering, or hollow and amber when missing; with the
 * power plug keeping the 24 V off (v1.26 — M62) a board that doesn't answer is unpowered, hollow in ink and never amber.
 */
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
    /** [list]: `ok` / `missing` per board, C1 first; null while not known. [off]: the 24 V is off (the power plug). */
    set(list, off) {
      const key = (list ? list.join(',') : '') + (off ? '|off' : '');
      if (key === shown) return;
      shown = key;
      const spoken = [];
      octaves.forEach((group, i) => {
        const b = list ? list[i] : undefined;
        const state = b === 'ok' ? 'ok' : b === 'missing' ? (off ? 'off' : 'missing') : 'unknown';
        group.setAttribute('class', `octave ${state}`);
        if (state === 'missing') labels[i].replaceChildren(`C${i + 1}`, h('span', { class: 'word', text: 'Missing' }));
        else labels[i].textContent = state === 'ok' ? `C${i + 1} · OK` : `C${i + 1}`;
        labels[i].className = state === 'missing' ? 'missing' : '';
        if (state !== 'unknown') spoken.push(`C${i + 1} ${state === 'ok' ? 'OK' : state === 'off' ? 'off' : 'missing'}`);
      });
      const head = off ? 'Power boards, the 24 V off' : 'Power boards';
      svgEl.setAttribute('aria-label', spoken.length ? `${head}: ${spoken.join(', ')}` : 'Power boards: not known');
    },
  };
}

// ---- Listen and test's parts (v1.23 — M55) ---------------------------------------------------------------------------

/** The card's words while listening is off on the tablet (the tablet's ListenCopy.OFF_HOW). */
const LISTEN_OFF = 'Listening is off on the tablet. To hear the room, turn on Listening from the panel on the tablet: Piano › System.';

/** How far ahead the sound is played (s): the network's unevenness never breaks it up; more through the relay. */
const LEAD_S = 0.25;
const LEAD_RELAYED_S = 0.5;

/** Past this much sound waiting to play (s), a frame is dropped: the room stays live, never minutes behind. */
const MOST_BEHIND_S = 1.2;

/** The volume in this browser, kept here (0–1). */
const VOLUME_KEY = 'steven-piano-listen-volume';

/** µ-law to linear (G.711, the tablet's Mulaw.decode), full scale 1: a value for each of the 256 bytes. */
const MULAW = (() => {
  const table = new Float32Array(256);
  for (let i = 0; i < 256; i++) {
    const bits = ~i & 0xff;
    const exponent = (bits >> 4) & 0x07;
    const mantissa = bits & 0x0f;
    const magnitude = (((mantissa << 3) + 0x84) << exponent) - 0x84;
    table[i] = ((bits & 0x80) !== 0 ? -magnitude : magnitude) / 32768;
  }
  return table;
})();

/**
 * The room in this browser: the socket's µ-law frames decoded and played with Web Audio, each just after the one before,
 * through one gain (the volume, kept in this browser). Sound may start only from a tap, so [wake] is called from one.
 */
function makeEar() {
  let context = null;
  let gain = null;
  let next = 0;
  let volume = keptVolume();
  return {
    /** From a tap: ready to play (the context made or woken); false when this browser can't play sound. */
    wake() {
      const Context = window.AudioContext || window.webkitAudioContext;
      if (!Context) return false;
      try {
        if (!context) {
          context = new Context();
          gain = context.createGain();
          gain.gain.value = volume;
          gain.connect(context.destination);
        }
        if (context.state === 'suspended') context.resume().catch(() => {});
      } catch (e) {
        return false;
      }
      next = 0;
      return true;
    },
    /** A frame ([message], `{seq, rate, codec, data}`) played after the last, [lead] seconds ahead at first; its level, 0–1. */
    play(message, lead) {
      if (!context || !message || typeof message.data !== 'string' || message.codec !== 'mulaw') return null;
      let bytes;
      try {
        bytes = atob(message.data);
      } catch (e) {
        return null;
      }
      const count = bytes.length;
      if (count === 0) return null;
      const samples = new Float32Array(count);
      let sum = 0;
      for (let i = 0; i < count; i++) {
        const v = MULAW[bytes.charCodeAt(i) & 0xff];
        samples[i] = v;
        sum += v * v;
      }
      const loud = levelOf(sum / count);
      if (context.state === 'suspended') context.resume().catch(() => {});   // the page has had its tap already
      const now = context.currentTime;
      if (next < now + 0.02) next = now + lead;   // the first frame, or after a gap
      if (next - now > lead + MOST_BEHIND_S) return loud;   // too far behind: this one is dropped
      const rate = finite(message.rate) || 16000;
      try {
        const buffer = context.createBuffer(1, count, rate);
        buffer.getChannelData(0).set(samples);
        const source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(gain);
        source.start(next);
        next += count / rate;
      } catch (e) {
        return null;
      }
      return loud;
    },
    volume: () => volume,
    setVolume(value) {
      volume = clamp(value, 0, 1);
      if (gain) gain.gain.value = volume;
      try {
        localStorage.setItem(VOLUME_KEY, String(volume));
      } catch (e) {
        // Private browsing: it holds for this page only.
      }
    },
    /** Nothing more to play: the context sleeps until the next tap. */
    rest() {
      next = 0;
      if (context && context.state === 'running') context.suspend().catch(() => {});
    },
  };
}

function keptVolume() {
  try {
    const value = Number(localStorage.getItem(VOLUME_KEY));
    return localStorage.getItem(VOLUME_KEY) !== null && Number.isFinite(value) ? clamp(value, 0, 1) : 0.8;
  } catch (e) {
    return 0.8;
  }
}

/** A frame's loudness for the meter: its mean square as decibels, −60 dB to 0, as 0–1. */
function levelOf(meanSquare) {
  if (!(meanSquare > 0)) return 0;
  const db = 10 * Math.log10(meanSquare);
  return clamp((db + 60) / 60, 0, 1);
}

/** The note names, C to B (the tablet's ListenCopy). */
const NOTE_NAMES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];

/** "C2", "F♯4": MIDI [key] (60 is C4). */
function keyName(key) {
  return NOTE_NAMES[((key % 12) + 12) % 12] + (Math.floor(key / 12) - 1);
}

/** "All 84 keys sounded", or "3 keys didn't sound: C2, F♯4, A6" ("1 key didn't sound: C2"), as the tablet says it. */
function resultLine(results) {
  const missing = results.filter((r) => r.heard === false).map((r) => r.key).sort((a, b) => a - b);
  if (missing.length === 0) return `All ${results.length} keys sounded`;
  const names = missing.map(keyName).join(', ');
  return missing.length === 1 ? `1 key didn't sound: ${names}` : `${missing.length} keys didn't sound: ${names}`;
}

/** "4:12 left" ([mine]: this panel's), or "Listening on another panel · 4:12 left". */
function leftWords(ms, mine) {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  const left = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} left`;
  return mine ? left : `Listening on another panel · ${left}`;
}

/** A button's glyph and words, changed only when they change. */
function label(button, glyphId, text) {
  const key = `${glyphId}|${text}`;
  if (buttonLabels.get(button) === key) return;
  buttonLabels.set(button, key);
  button.replaceChildren(glyphNode(glyphId), document.createTextNode(text));
}

/** What each button shows now (kept in the module, never as a data- attribute in the page). */
const buttonLabels = new WeakMap();

/** A glyph of the frame's sprite, as host.glyph draws it (for the parts made outside create()). */
function glyphNode(id) {
  const svgEl = svgNode('svg', { class: 'glyph', 'aria-hidden': 'true' });
  svgEl.append(svgNode('use', { href: `#${id}` }));
  return svgEl;
}

/** The naturals' places in an octave (C D E F G A B), and the sharps' (after C, D, F, G, A): [position, semitones above C]. */
const NATURAL_STEPS = [0, 2, 4, 5, 7, 9, 11];
const SHARP_STEPS = [[0, 1], [1, 3], [3, 6], [4, 8], [5, 10]];

/**
 * The piano's 84 keys as seven octaves, C1 to C7, in the board octaves' style: while a test runs the keys struck so far, the
 * one struck now in the content colour, those to come faint; then the keys heard as an answering board's, the keys not
 * heard in the attention amber (the line under it names them).
 */
function makeKeyMap(h) {
  const svgEl = svgNode('svg', { class: 'keymap', viewBox: '0 0 420 48', role: 'img', 'aria-label': 'Keyboard map', focusable: 'false' });
  const keys = new Map();
  for (let o = 0; o < 7; o++) {
    const x = o * 60;
    const first = 24 + o * 12;
    const group = svgNode('g');
    group.append(svgNode('rect', { class: 'case', x: x + 2, y: 2, width: 56, height: 44, rx: 6 }));
    NATURAL_STEPS.forEach((step, k) => {
      const rect = svgNode('rect', { class: 'nat pending', x: n2(x + 4 + k * 7.5), y: 5, width: 6.3, height: 38, rx: 1.5 });
      keys.set(first + step, { rect, kind: 'nat' });
      group.append(rect);
    });
    for (const [k, step] of SHARP_STEPS) {
      group.append(svgNode('rect', { class: 'gap', x: n2(x + 4 + k * 7.5 + 3.8), y: 5, width: 6, height: 23.8, rx: 1.6 }));
      const rect = svgNode('rect', { class: 'acc pending', x: n2(x + 4 + k * 7.5 + 4.6), y: 5, width: 4.4, height: 23, rx: 1.2 });
      keys.set(first + step, { rect, kind: 'acc' });
      group.append(rect);
    }
    svgEl.append(group);
  }
  const names = [];
  for (let o = 0; o < 7; o++) names.push(h('span', { text: `C${o + 1}` }));
  const node = h('div', { class: 'keymap-box' }, svgEl, h('div', { class: 'board-labels', 'aria-hidden': 'true' }, names));
  let shown = '';
  return {
    node,
    /** The key test's state ([test]); [spoken], what its line says, for screen readers. */
    set(test, spoken) {
      const running = test.running === true;
      const current = finite(test.key);
      const done = finite(test.done) || 0;
      const total = finite(test.total) || 84;
      const results = !running && Array.isArray(test.results) ? test.results : null;
      const heard = results ? new Map(results.map((r) => [r.key, r.heard === true])) : null;
      const key = `${running}|${current}|${done}|${results ? results.map((r) => (r.heard === true ? 1 : 0)).join('') : '-'}`;
      svgEl.setAttribute('aria-label', `Keyboard map: ${spoken}`);
      if (key === shown) return;
      shown = key;
      for (const [note, { rect, kind }] of keys) {
        let shade = 'pending';
        if (running) {
          if (note === current) shade = 'now';
          else if (current !== null ? note < current : done >= total) shade = 'struck';
        } else if (heard) {
          shade = heard.get(note) === false ? 'missing' : 'heard';
        }
        rect.setAttribute('class', `${kind} ${shade}`);
      }
      names.forEach((name, o) => {
        let missing = false;
        for (let i = 0; i < 12 && heard; i++) if (heard.get(24 + o * 12 + i) === false) missing = true;
        name.className = missing ? 'missing' : '';
      });
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
