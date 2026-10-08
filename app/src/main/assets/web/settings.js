/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The panel's Settings page (DESIGN.md › v1.18 — M47b): an ES module the frame (app.js) imports the first time the
// section `settings` shows, create(host, body, tools) → { show(), hide(), render(state), open(page) }. A list of pages
// (System, Playback, the piano's three, Guests, Panel; v1.20 — M53 added System, system.js's page made here through
// host.system(), and Guests, the guests' two switches and their address) with the chosen page's cards beside it from
// 900 px; below that the list, and a page over it with a back row (system.css › Settings). Every element is built with
// DOM calls, what the tablet says goes in as text, sizes go through the CSSOM, and every request is built from host.ROOT.

/** The app's own words (ui/SettingNotes.kt, ui/PlaybackCopy.kt), so the panel says what the tablet says. */
const NOTES = {
  defaultTempoPct: 'How fast pieces start; Now playing can change it',
  velocityPct: 'Plays every piece louder or softer',
  dynamicRange: 'How far soft and loud notes spread apart',
  velocityFloor: 'Softer notes are raised to this, so they still strike',
  expression: 'Shapes loudness and timing as a pianist would',
  restrikeMs: 'The least time between two strikes of one key',
  foldOutOfRange: 'Moves notes the piano lacks into its range',
  skipDrumChannel: 'Skips the drum part of a file',
  albumBackdrop: "The album's colours drift behind the player while playing",
  // The tablet's Guests page (ui/SettingNotes.kt).
  webGuests: "Anyone with the poster's code can ask for a piece",
  webApproveFirst: 'A request waits for you before it plays',
};
const FULL_POWER_ON =
  'Full power is on: every note strikes at full strength. Turn it off on Sound and touch to hear dynamics and expression.';
const FULL_POWER_OFF = 'Dynamics are heard with Full power off.';

/** Dynamic range's and Expression's choices on the wire and as the tablet's chips name them. */
const RANGES = [['narrow', 'Narrow'], ['natural', 'Natural'], ['wide', 'Wide']];
const EXPRESSIONS = [['off', 'Off'], ['light', 'Light'], ['full', 'Full']];

/** Re-strike time: 0 is Auto (Performance.AUTO), which takes the piano's own repeat period, else 100 ms. */
const AUTO = 0;
const DEFAULT_RESTRIKE_MS = 100;

/** The feel presets' one line each. */
const PRESET_LINES = {
  soft: 'Quiet, gentle strikes',
  cinematic: 'Wide and unhurried',
  expressive: 'More contrast',
  snappy: 'Fast repeats',
};

/** The piano's pages' glyphs, by the table's keys. */
const PIANO_GLYPHS = { feel: 'g-keys', lighting: 'g-studio', pedal: 'g-piano' };

/** The piano's pages until /api/piano names them (PianoPage's titles). */
const PIANO_PAGES = [
  { key: 'feel', title: 'Sound and touch' },
  { key: 'lighting', title: 'Lights and screen' },
  { key: 'pedal', title: 'Pedal' },
];

/** The panel's appearance, as host.appearance names it, and its words. */
const APPEARANCES = [['dark', 'Dark'], ['light', 'Light'], ['system', 'Follow system']];

/** A change waits this long for the next before it is sent (today's debounce), and a sent one this long to come back. */
const SEND_MS = 150;
const SETTLE_MS = 3000;

/** The page chosen, remembered in this browser only. */
const STORED_PAGE = 'steven-piano-settings-page';

/** Two panes from here (system.css › .split). */
const WIDE = '(min-width: 900px)';

export function create(host, body, tools) {
  const { h, fill } = host;
  const glyph = (id) => host.glyph(id);

  /** The last state, the piano's table (/api/piano) and the app's playback settings (/api/settings). */
  let st = host.state() || null;
  let table = null;
  let tableFailed = false;
  let prefs = null;
  let prefsFailed = false;
  let showing = false;

  /** The page chosen (System, the first, unless this browser chose another), and (below 900 px) whether it is open over the list. */
  let chosen = stored() || 'system';
  let opened = false;

  /** A piano setting being changed: its value until the piano's own comes back, or [SETTLE_MS] after it was sent. */
  const drafts = new Map();
  /** A playback setting just changed, read in place of the state's for [SETTLE_MS] (Album colours lives in the state). */
  const pending = new Map();
  const timers = new Map();
  /** The slider under a finger: it keeps its place while the state moves on. */
  let dragging = null;

  /** The pane's controls, each brought up to date in place: built again only when the pane's shape changes. */
  let controls = [];
  let paneKey = null;

  // ---- The frame: the list, the pane beside it (or over it, narrow), the head's tools ----------------------------

  const list = h('nav', { class: 'card pages', 'aria-label': 'Settings pages' });
  /** The list's buttons by page key, with their line and page. */
  const items = new Map();
  const back = h('button', { class: 'back-row', type: 'button' }, glyph('i-back'), h('span', { text: 'Settings' }));
  const paneTitle = h('h2', { class: 'pane-title' });
  const paneBody = h('div', { class: 'stack' });
  const pane = h('div', { class: 'page-pane' }, back, paneTitle, paneBody);
  const split = h('div', { class: 'split' }, list, pane);
  const root = h('div', { class: 'settings-page' }, split);
  fill(body, root);

  back.addEventListener('click', () => {
    opened = false;
    update();
    const item = items.get(chosen);
    if (item) item.button.focus();
  });

  const linkName = h('span');
  const linkCapsule = h('span', { class: 'capsule glass wide', hidden: true }, h('span', { class: 'dot live' }), linkName);
  const save = h('button', { class: 'outlined filled', type: 'button', hidden: true }, glyph('g-save'), h('span', { text: 'Save to the piano' }));
  /** System's page (v1.20 — M53): its head's capsules in [system.tools], its cards in [system.holder], made once. */
  const system = { state: 'idle', page: null, showing: false, holder: h('div', { class: 'system-holder' }), tools: h('span', { class: 'system-tools', hidden: true }) };
  save.addEventListener('click', async () => {
    try {
      await host.post(host.ROOT + '/api/piano/action', { name: 'save' });
      host.toast('Saved on the piano.');
    } catch (e) {
      host.failed(e);
    }
  });
  if (tools) fill(tools, system.tools, linkCapsule, save);

  // ---- What the state says ------------------------------------------------------------------------------------------

  const link = () => (st && st.link) || { state: 'disconnected' };
  const piano = () => (st && st.piano) || { state: 'unknown', values: {}, facts: {} };
  const connected = () => link().state === 'connected';
  const pianoReady = () => connected() && piano().state === 'ready';
  const values = () => piano().values || {};
  const facts = () => piano().facts || {};

  /** Another MIDI piano plays (v1.11 — M29): the piano's pages are Steven Piano's, hidden meanwhile. Its name, or null. */
  function otherInstrument() {
    const instrument = st && st.instruments && st.instruments.instrument;
    return instrument && instrument.kind === 'midi' ? instrument.name || 'another piano' : null;
  }

  /** Why the piano's controls can't be used now, as today's page says it; null when they can. */
  function pianoStatus() {
    if (!connected()) return 'Connect to the piano to adjust its settings.';
    if (piano().state === 'unsupported') return "This piano's firmware doesn't offer settings over Bluetooth yet.";
    if (piano().state !== 'ready') return "Reading the piano's settings…";
    return null;
  }

  /** The piano's repeat period as it reports it (PianoFacts: 1–2000 ms), else null. */
  function repeatMs() {
    if (st) {
      const ms = pianoReady() ? whole(facts().repeatms) : null;
      return ms !== null && ms >= 1 && ms <= 2000 ? ms : null;
    }
    return prefs && prefs.piano && Number.isFinite(prefs.piano.repeatMs) ? prefs.piano.repeatMs : null;
  }

  /** Full power on (PlaybackCopy.fullPower: the piano ready and its value not 0). */
  function fullPower() {
    if (st) {
      const wire = values().fullpower;
      return pianoReady() && typeof wire === 'string' && wire.trim() !== '0';
    }
    return !!(prefs && prefs.piano && prefs.piano.fullPower === true);
  }

  // ---- Loading ------------------------------------------------------------------------------------------------------

  async function loadTable() {
    try {
      const answer = await host.get(host.ROOT + '/api/piano');
      if (answer && Array.isArray(answer.pages)) {
        table = answer;
        tableFailed = false;
      }
    } catch (e) {
      tableFailed = table === null;
      host.failed(e);
    }
    update();
  }

  async function loadPrefs() {
    try {
      const answer = await host.get(host.ROOT + '/api/settings');
      if (answer && answer.values) {
        prefs = { values: Object.assign({}, answer.values), limits: answer.limits || {}, piano: answer.piano || {} };
        prefsFailed = false;
      }
    } catch (e) {
      prefsFailed = prefs === null;
      host.failed(e);
    }
    update();
  }

  // ---- Sending ------------------------------------------------------------------------------------------------------

  /** A piano setting, as today's page sends it: once a change has rested 150 ms, each setting on its own. */
  function sendPiano(name, value, slack) {
    drafts.set(name, { value, sentAt: 0, slack: slack || 1e-6 });
    clearTimeout(timers.get(name));
    timers.set(name, setTimeout(async () => {
      timers.delete(name);
      const draft = drafts.get(name);
      if (!draft) return;
      try {
        await host.put(host.ROOT + `/api/piano/${encodeURIComponent(name)}`, { value: draft.value });
        draft.sentAt = Date.now();
        setTimeout(() => {
          if (drafts.get(name) === draft) drafts.delete(name);
          update();
        }, SETTLE_MS);
      } catch (e) {
        if (drafts.get(name) === draft) drafts.delete(name);
        host.failed(e);
        update();
      }
    }, SEND_MS));
  }

  /** One of the app's own settings, kept here at once and sent once it has rested; a refusal reads them again. */
  function sendPref(name, value) {
    if (prefs) prefs.values[name] = value;
    pending.set(name, { value, at: Date.now() });
    const key = 'pref:' + name;
    clearTimeout(timers.get(key));
    timers.set(key, setTimeout(async () => {
      timers.delete(key);
      try {
        await host.put(host.ROOT + '/api/settings', { [name]: value });
      } catch (e) {
        pending.delete(name);
        host.failed(e);
        loadPrefs();
      }
    }, SEND_MS));
    update();
  }

  /** A piano setting's draft is let go once the piano's own value matches it. */
  function settle() {
    for (const [name, draft] of drafts) {
      if (!draft.sentAt) continue;
      const wire = values()[name];
      if (typeof wire !== 'string') continue;
      const arrived = typeof draft.value === 'boolean'
        ? (wire.trim() !== '0') === draft.value
        : Math.abs(Number(wire) - draft.value) <= draft.slack;
      if (arrived) drafts.delete(name);
    }
    const now = Date.now();
    for (const [name, change] of pending) if (now - change.at > SETTLE_MS) pending.delete(name);
  }

  /** A piano setting as shown: the draft while one stands, else the piano's wire value (a string), else undefined. */
  function pianoValue(name) {
    const draft = drafts.get(name);
    if (draft) return draft.value;
    const wire = values()[name];
    return typeof wire === 'string' ? wire : undefined;
  }

  // ---- Controls -----------------------------------------------------------------------------------------------------

  /** A row: the label (a unit beside it, a note under it) and the control; dimmed while it can't be used. */
  function row(label, lines, control, enabled, stacked) {
    const node = h('div', { class: stacked ? 'setting stacked' : 'setting' }, h('div', { class: 'label' }, label, lines), control.node);
    controls.push({
      update() {
        control.update();
        node.classList.toggle('disabled', !enabled());
      },
    });
    return node;
  }

  /** An on-off switch. [read] gives true, false or null (not known). */
  function switchControl(label, read, write, enabled) {
    const button = h('button', { class: 'switch', type: 'button', role: 'switch', 'aria-label': label, 'aria-checked': 'false' });
    button.addEventListener('click', () => {
      const on = button.getAttribute('aria-checked') === 'true';
      button.setAttribute('aria-checked', on ? 'false' : 'true');
      write(!on);
    });
    return {
      node: button,
      update() {
        const on = read();
        button.setAttribute('aria-checked', on === true ? 'true' : 'false');
        button.disabled = !enabled() || on === null;
      },
    };
  }

  /** A choice of a few, as a segmented control; [read] gives the chosen one's index (or -1). */
  function segmentedControl(label, options, read, write, enabled) {
    const buttons = options.map((option, index) => {
      const button = h('button', { type: 'button', 'aria-pressed': 'false', text: option });
      button.addEventListener('click', () => {
        if (button.getAttribute('aria-pressed') === 'true') return;
        buttons.forEach((b, i) => b.setAttribute('aria-pressed', i === index ? 'true' : 'false'));
        write(index);
      });
      return button;
    });
    return {
      node: h('div', { class: 'segmented', role: 'group', 'aria-label': label }, buttons),
      update() {
        const index = read();
        const on = enabled() && index >= 0;
        buttons.forEach((b, i) => {
          b.setAttribute('aria-pressed', i === index ? 'true' : 'false');
          b.disabled = !on;
        });
      },
    };
  }

  /** A choice of many (the reactive palette's eight), as chips that wrap: the chosen one carries a check. */
  function chipsControl(label, options, read, write, enabled) {
    const buttons = options.map((option, index) => {
      const button = h('button', { class: 'chip', type: 'button', 'aria-pressed': 'false' }, h('span', { text: option }));
      button.addEventListener('click', () => {
        if (button.getAttribute('aria-pressed') === 'true') return;
        write(index);
        mark(index);
      });
      return button;
    });
    function mark(chosenIndex) {
      buttons.forEach((b, i) => {
        const on = i === chosenIndex;
        b.setAttribute('aria-pressed', on ? 'true' : 'false');
        const check = b.querySelector('.glyph');
        if (on && !check) b.prepend(glyph('i-check'));
        if (!on && check) check.remove();
      });
    }
    return {
      node: h('div', { class: 'chips', role: 'group', 'aria-label': label }, buttons),
      update() {
        const index = read();
        mark(index);
        for (const b of buttons) b.disabled = !enabled() || index < 0;
      },
    };
  }

  /**
   * − value +: a press steps once and, held, again every 70 ms after 400 ms (Compose's stepper's hold). [read] gives the
   * value (null: not known), [next](value, up) the value a press leads to (the same value at an end), [show] its text.
   */
  function stepperControl(names, read, next, show, write, enabled) {
    const value = h('span', { class: 'value' });
    const down = h('button', { class: 'icon-button', type: 'button', 'aria-label': names.less }, glyph('g-minus'));
    const up = h('button', { class: 'icon-button', type: 'button', 'aria-label': names.more }, glyph('g-plus'));
    const press = (upward) => () => {
      const v = read();
      if (v === null || !enabled()) return;
      const n = next(v, upward);
      if (n === v) return;
      write(n);
      refresh();
    };
    holdToRepeat(down, press(false));
    holdToRepeat(up, press(true));
    function refresh() {
      const v = read();
      const on = enabled() && v !== null;
      value.textContent = v === null ? '—' : show(v);
      down.disabled = !on || next(v, false) === v;
      up.disabled = !on || next(v, true) === v;
    }
    return { node: h('div', { class: 'stepper' }, down, value, up), update: refresh };
  }

  /** A native slider with its value beside it; while a finger holds it, the state never moves it. */
  function sliderControl(name, label, range, read, show, spoken, write, enabled) {
    const input = h('input', { class: 'range', type: 'range', min: range.min, max: range.max, step: range.step, 'aria-label': label });
    const value = h('span', { class: 'value' });
    const paint = (v) => {
      const share = range.max > range.min ? ((v - range.min) / (range.max - range.min)) * 100 : 0;
      input.style.setProperty('--fill', `${Math.max(0, Math.min(100, share))}%`);
    };
    const release = () => {
      window.removeEventListener('pointerup', release);
      window.removeEventListener('pointercancel', release);
      if (dragging === name) {
        dragging = null;
        update();
      }
    };
    input.addEventListener('pointerdown', () => {
      dragging = name;
      window.addEventListener('pointerup', release);
      window.addEventListener('pointercancel', release);
    });
    input.addEventListener('change', release);
    input.addEventListener('input', () => {
      const v = Number(input.value);
      paint(v);
      value.textContent = show(v);
      input.setAttribute('aria-valuetext', spoken(v));
      write(v);
    });
    return {
      node: h('div', { class: 'with-value' }, input, value),
      update() {
        const v = read();
        input.disabled = !enabled() || v === null;
        if (dragging === name) return;
        const at = v === null ? range.min : v;
        if (Number(input.value) !== at) input.value = String(at);
        paint(v === null ? range.min : v);
        value.textContent = v === null ? '—' : show(v);
        if (v === null) input.removeAttribute('aria-valuetext');
        else input.setAttribute('aria-valuetext', spoken(v));
      },
    };
  }

  // ---- The piano's pages (Sound and touch, Lights and screen, Pedal), from /api/piano's table -----------------------

  /** How a setting's value reads, as the app's table shows it (today's shown()). */
  function shown(setting, wire) {
    if (wire === undefined || wire === null) return '—';
    const kind = setting.kind || {};
    const text = String(wire);
    const number = Number(text);
    if (kind.type === 'switch') return text.trim() !== '0' ? 'On' : 'Off';
    if (kind.type === 'choice') return Array.isArray(kind.options) && kind.options[number] !== undefined ? kind.options[number] : text;
    if (Number.isNaN(number)) return text;
    if (number === 0 && setting.zero) return setting.zero;
    if (kind.type === 'slider') {
      if (setting.percentOf255) return String(Math.floor((Math.round(number) * 100) / 255));
      if (kind.decimals > 0) return number.toFixed(kind.decimals);
    }
    return host.signed(Math.round(number));
  }

  /** A percentage's value carries its sign ("70%"); every other unit stands beside the setting's name. */
  function withUnit(setting, text) {
    return setting.unit === '%' && /\d$/.test(text) ? text + '%' : text;
  }

  function spokenUnit(setting, text) {
    if (!setting.unit || !/\d$/.test(text)) return text;
    const units = { '%': 'percent', ms: 'milliseconds', s: 'seconds', Hz: 'hertz' };
    return `${text} ${units[setting.unit] || setting.unit}`;
  }

  /** One of the piano's settings as a row, in the control its kind takes; null for a kind this page doesn't know. */
  function pianoRow(setting) {
    const kind = setting.kind || {};
    const name = setting.name;
    const label = setting.label || name;
    // The unit beside the name (a percentage rides on its value instead), the note under it.
    const lines = [
      setting.unit && setting.unit !== '%' ? h('span', { class: 'eyebrow unit', text: setting.unit }) : null,
      setting.note ? h('span', { class: 'meta', text: setting.note }) : null,
    ];
    const enabled = () => pianoReady() && pianoValue(name) !== undefined;
    const number = () => {
      const v = pianoValue(name);
      const n = v === undefined ? NaN : Number(v);
      return Number.isFinite(n) ? n : null;
    };
    if (kind.type === 'switch') {
      const read = () => {
        const v = pianoValue(name);
        if (typeof v === 'boolean') return v;
        return v === undefined ? null : v.trim() !== '0';
      };
      return row(label, lines, switchControl(label, read, (on) => sendPiano(name, on), enabled), enabled);
    }
    if (kind.type === 'choice') {
      const options = Array.isArray(kind.options) ? kind.options.map(String) : [];
      const read = () => {
        const n = number();
        return n === null ? -1 : Math.round(n);
      };
      const write = (index) => sendPiano(name, index);
      // A few choices side by side; the reactive palette's eight as chips that wrap. Under the label, as the tablet's.
      const control = options.length <= 4 ? segmentedControl(label, options, read, write, enabled) : chipsControl(label, options, read, write, enabled);
      return row(label, lines, control, enabled, true);
    }
    if (kind.type === 'stepper') {
      const min = Number(kind.min);
      const max = Number(kind.max);
      const step = Number(kind.step) || 1;
      const lowestOn = Number(kind.lowestOn) || 0;
      // As SettingKind.Stepper.next: in range, and off the gap below the lowest value that is on.
      const next = (v, upward) => {
        let n = Math.min(max, Math.max(min, v + (upward ? step : -step)));
        if (lowestOn && n > 0 && n < lowestOn) n = upward || v > lowestOn ? lowestOn : 0;
        return n;
      };
      const show = (v) => withUnit(setting, shown(setting, String(v)));
      const control = stepperControl({ less: `Less ${label}`, more: `More ${label}` }, number, next, show, (v) => sendPiano(name, v), enabled);
      return row(label, lines, control, enabled);
    }
    if (kind.type === 'slider') {
      const range = { min: Number(kind.min), max: Number(kind.max), step: Number(kind.step) || 1 };
      const show = (v) => withUnit(setting, shown(setting, String(v)));
      const spoken = (v) => spokenUnit(setting, shown(setting, String(v)));
      const control = sliderControl(name, label, range, number, show, spoken, (v) => sendPiano(name, v, range.step / 2), enabled);
      return row(label, lines, control, enabled, true);
    }
    return null;
  }

  /** Feel's four presets, each with its line; each saves itself on the piano. */
  function presetsCard() {
    const presets = Array.isArray(table.presets) ? table.presets : [];
    if (presets.length === 0) return null;
    const buttons = presets.map((preset) => {
      const button = h('button', { class: 'preset', type: 'button' },
        h('b', { text: preset.label }),
        PRESET_LINES[preset.command] ? h('span', { class: 'meta', text: PRESET_LINES[preset.command] }) : null);
      button.addEventListener('click', async () => {
        try {
          await host.post(host.ROOT + '/api/piano/preset', { name: preset.command });
          host.toast(`${preset.label} applied on the piano.`);
        } catch (e) {
          host.failed(e);
        }
      });
      return button;
    });
    controls.push({
      update() {
        for (const b of buttons) b.disabled = !pianoReady();
      },
    });
    return h('section', { class: 'card', 'aria-label': 'Feel' },
      h('div', { class: 'card-head' }, h('h2', { text: 'Feel' }), h('span', { class: 'meta', text: 'Each one saves itself on the piano' })),
      h('div', { class: 'presets' }, buttons));
  }

  /** The lines over the piano's cards: what stops them being used, and the piano's last refusal. */
  function pianoNotes() {
    const holder = h('div', { class: 'pane-notes' });
    controls.push({
      update() {
        const status = pianoStatus();
        const error = connected() && piano().lastError ? piano().lastError : null;
        fill(holder,
          status ? h('p', { class: 'note', text: status }) : null,
          error ? h('p', { class: 'notice', role: 'status' }, glyph('g-alert'), h('span', { text: `The piano said: ${error}` })) : null);
        holder.hidden = !status && !error;
      },
    });
    return holder;
  }

  function buildPiano(key) {
    const name = otherInstrument();
    if (name) {
      return [h('p', { class: 'note', text: `Sound and touch, Lights and screen, Pedal and Firmware belong to Steven Piano and are hidden while ${name} plays.` })];
    }
    const nodes = [pianoNotes()];
    if (!table) {
      nodes.push(h('p', { class: 'note', text: tableFailed ? "The piano's settings couldn't be read. Open the page again to try once more." : "Reading the piano's settings…" }));
      return nodes;
    }
    if (piano().state === 'unsupported') return nodes;
    const page = table.pages.find((p) => p && p.key === key);
    if (!page) return nodes;
    if (key === 'feel') nodes.push(presetsCard());
    for (const part of Array.isArray(page.sections) ? page.sections : []) {
      const settings = Array.isArray(part.settings) ? part.settings : [];
      const rows = settings.map(pianoRow).filter(Boolean);
      if (rows.length === 0) continue;
      // The repeat period beside the timing it comes from (minstrike + gap + 20 ms), as the tablet's TIMING shows it.
      const timing = settings.some((s) => s.name === 'minstrike' || s.name === 'gap');
      const meta = timing ? h('span', { class: 'meta' }) : null;
      if (meta) {
        controls.push({
          update() {
            const ms = repeatMs();
            meta.textContent = ms === null ? '' : `Repeat period · ${ms} ms`;
          },
        });
      }
      const head = part.title ? h('div', { class: 'card-head' }, h('h2', { text: part.title }), meta) : null;
      nodes.push(h('section', { class: 'card', 'aria-label': part.title || page.title }, head, rows));
    }
    return nodes;
  }

  // ---- Playback (the app's own, from /api/settings) ------------------------------------------------------------------

  const prefValue = (name) => (prefs && prefs.values ? prefs.values[name] : undefined);
  const prefNumber = (name) => {
    const v = prefValue(name);
    return typeof v === 'number' && Number.isFinite(v) ? v : null;
  };
  const prefsReady = () => prefs !== null;

  /** A stepper over one of the app's numbers, in its limits from /api/settings. */
  function prefStepper(name, label, names, show, landOn) {
    const limits = (prefs && prefs.limits && prefs.limits[name]) || null;
    const enabled = () => prefsReady() && limits !== null;
    const next = (v, upward) => {
      if (!limits) return v;
      const n = Math.min(limits.max, Math.max(limits.min, v + (upward ? limits.step : -limits.step)));
      return landOn ? landOn(v, n) : n;
    };
    const control = stepperControl(names, () => prefNumber(name), next, show, (v) => sendPref(name, v), enabled);
    return row(label, [NOTES[name] ? h('span', { class: 'meta', text: NOTES[name] }) : null], control, enabled);
  }

  function prefChoice(name, label, choices) {
    const read = () => choices.findIndex(([wire]) => wire === prefValue(name));
    const control = segmentedControl(label, choices.map(([, word]) => word), read, (index) => sendPref(name, choices[index][0]), prefsReady);
    return row(label, [h('span', { class: 'meta', text: NOTES[name] })], control, prefsReady, true);
  }

  function prefSwitch(name, label) {
    const read = () => (typeof prefValue(name) === 'boolean' ? prefValue(name) : null);
    return row(label, [h('span', { class: 'meta', text: NOTES[name] })], switchControl(label, read, (on) => sendPref(name, on), prefsReady), prefsReady);
  }

  function buildPlayback() {
    const nodes = [h('p', { class: 'note pane-line', text: 'Changes apply from the next piece.' })];
    if (!prefs) {
      nodes.push(h('p', { class: 'note', text: prefsFailed ? "The tablet's settings couldn't be read. Open the page again to try once more." : 'Reading the settings…' }));
      return nodes;
    }
    const percent = (v) => `${v}%`;
    const semitones = (v) => (v > 0 ? `+${v}` : host.signed(v));
    // Quietest note lands on 1 or a multiple of five; between Auto and 60 ms there is no re-strike time (PlaybackPage).
    const floorStep = (from, to) => (to <= 1 ? 1 : Math.floor((to + 2) / 5) * 5);
    const restrikeLimits = (prefs.limits && prefs.limits.restrikeMs) || null;
    const restrikeNext = (v, upward) => {
      if (!restrikeLimits) return v;
      const to = Math.min(restrikeLimits.max, Math.max(AUTO, v + (upward ? restrikeLimits.step : -restrikeLimits.step)));
      if (to <= AUTO) return AUTO;
      if (to < restrikeLimits.min) return to > v ? restrikeLimits.min : AUTO;
      return to;
    };
    const restrike = (ms) => {
      if (ms > AUTO) return `${ms} ms`;
      const piano = repeatMs();
      return piano !== null ? `Auto · ${piano} ms from the piano` : `Auto · ${DEFAULT_RESTRIKE_MS} ms`;
    };
    const pause = (ms) => {
      if (ms <= 0) return 'Off';
      const tenths = Math.floor((ms + 50) / 100);
      return tenths % 10 === 0 ? `${tenths / 10} s` : `${Math.floor(tenths / 10)}.${tenths % 10} s`;
    };
    const fullLine = h('span', { class: 'meta' });
    controls.push({
      update() {
        fullLine.textContent = fullPower() ? FULL_POWER_ON : FULL_POWER_OFF;
      },
    });
    const restrikeEnabled = () => prefsReady() && restrikeLimits !== null;
    const restrikeRow = row('Re-strike time', [h('span', { class: 'meta', text: NOTES.restrikeMs })],
      stepperControl({ less: 'Shorter re-strike time', more: 'Longer re-strike time' }, () => prefNumber('restrikeMs'), restrikeNext, restrike,
        (v) => sendPref('restrikeMs', v), restrikeEnabled),
      restrikeEnabled);
    const velocityLimits = (prefs.limits && prefs.limits.velocityPct) || null;
    const velocityEnabled = () => prefsReady() && velocityLimits !== null;
    const velocityRow = row('Velocity', [h('span', { class: 'meta', text: NOTES.velocityPct }), fullLine],
      stepperControl({ less: 'Play softer', more: 'Play louder' }, () => prefNumber('velocityPct'),
        (v, upward) => (velocityLimits ? Math.min(velocityLimits.max, Math.max(velocityLimits.min, v + (upward ? velocityLimits.step : -velocityLimits.step))) : v),
        percent, (v) => sendPref('velocityPct', v), velocityEnabled),
      velocityEnabled);
    nodes.push(h('section', { class: 'card', 'aria-label': 'Playback' },
      prefStepper('defaultTempoPct', 'Default tempo', { less: 'Slower default tempo', more: 'Faster default tempo' }, percent),
      prefStepper('transpose', 'Transpose', { less: 'Transpose down a semitone', more: 'Transpose up a semitone' }, semitones),
      velocityRow,
      prefChoice('dynamicRange', 'Dynamic range', RANGES),
      prefStepper('velocityFloor', 'Quietest note', { less: 'Lower the quietest note', more: 'Raise the quietest note' }, String, floorStep),
      prefChoice('expression', 'Expression', EXPRESSIONS),
      restrikeRow,
      prefStepper('preRollMs', 'Pause before each piece', { less: 'Shorter pause', more: 'Longer pause' }, pause),
      prefSwitch('foldOutOfRange', 'Fold notes outside C1–B7'),
      prefSwitch('skipDrumChannel', 'Skip drum channel')));
    return nodes;
  }

  // ---- System (v1.20 — M53: system.js's page, the first of Settings) -----------------------------------------------------

  /** system.js through the frame, once: its page is made in [system.holder] the first time System shows. */
  function loadSystemPage() {
    if (system.state !== 'idle') return;
    if (typeof host.system !== 'function') {
      system.state = 'failed';
      return;
    }
    system.state = 'loading';
    Promise.resolve(host.system()).then((module) => {
      const page = module && typeof module.create === 'function' ? guarded(() => module.create(host, system.holder, system.tools)) : null;
      system.page = page && typeof page.show === 'function' ? page : null;
      system.state = system.page ? 'ready' : 'failed';
      update();
    }, () => {
      system.state = 'failed';
      update();
    });
  }

  /** System's page runs (its reads, every 5 s) only while it shows: chosen, beside the list or open over it, and Settings showing. */
  function systemShows(page) {
    const on = showing && page.key === 'system' && (opened || window.matchMedia(WIDE).matches) && !!system.page;
    if (on && !system.showing) {
      system.showing = true;
      guarded(() => system.page.show());
    } else if (!on && system.showing) {
      system.showing = false;
      guarded(() => system.page.hide());
    }
    if (system.showing && st) guarded(() => system.page.render(st));
  }

  function buildSystem() {
    if (system.state === 'failed') return [h('p', { class: 'note', text: "The System page couldn't be loaded. Reload the page to try once more." })];
    return [system.holder];
  }

  // ---- Guests (v1.20 — M53: the guests' switches and their address, from the Requests page of 1.19) -------------------

  /** One of the guests' two settings: a change just made here, else the state's (it lives there, live). */
  function guestSetting(name, field) {
    const change = pending.get(name);
    if (change) return change.value;
    const requests = st && st.requests;
    return requests && typeof requests[field] === 'boolean' ? requests[field] : null;
  }

  const guestsOpen = () => guestSetting('webGuests', 'guests');
  const approveFirst = () => guestSetting('webApproveFirst', 'approveFirst');

  function buildGuests() {
    const openEnabled = () => guestsOpen() !== null;
    const approveEnabled = () => guestsOpen() === true && approveFirst() !== null;
    const address = h('p', { class: 'note pane-line' });
    controls.push({
      update() {
        const guest = st && st.web && st.web.guestAddress;
        const text = guest ? `Guests ask at ${guest}, or with the poster's code.` : '';
        if (address.textContent !== text) address.textContent = text;
        address.hidden = !text;
      },
    });
    return [
      h('section', { class: 'card', 'aria-label': 'Guests' },
        row('Guests can request', [h('span', { class: 'meta', text: NOTES.webGuests })],
          switchControl('Guests can request', guestsOpen, (on) => sendPref('webGuests', on), openEnabled), openEnabled),
        row('Approve requests first', [h('span', { class: 'meta', text: NOTES.webApproveFirst })],
          switchControl('Approve requests first', approveFirst, (on) => sendPref('webApproveFirst', on), approveEnabled), approveEnabled)),
      address,
    ];
  }

  // ---- Panel (this browser's appearance, and Album colours behind the player) -----------------------------------------

  const appearanceNow = () => {
    try {
      const value = host.appearance && typeof host.appearance.get === 'function' ? host.appearance.get() : 'dark';
      return APPEARANCES.some(([v]) => v === value) ? value : 'dark';
    } catch (e) {
      return 'dark';
    }
  };

  /** Album colours: a change just made here, else the state's (it lives there, live), else /api/settings'. */
  function albumBackdrop() {
    const change = pending.get('albumBackdrop');
    if (change) return change.value;
    if (st && typeof st.albumBackdrop === 'boolean') return st.albumBackdrop;
    const v = prefValue('albumBackdrop');
    return typeof v === 'boolean' ? v : null;
  }

  function buildPanel() {
    const choose = (index) => {
      if (host.appearance && typeof host.appearance.set === 'function') host.appearance.set(APPEARANCES[index][0]);
      update();
    };
    const appearance = segmentedControl('Appearance', APPEARANCES.map(([, word]) => word),
      () => APPEARANCES.findIndex(([v]) => v === appearanceNow()), choose, () => true);
    const backdropEnabled = () => albumBackdrop() !== null;
    const backdrop = switchControl('Album colours behind the player', albumBackdrop, (on) => sendPref('albumBackdrop', on), backdropEnabled);
    return [h('section', { class: 'card', 'aria-label': 'Panel' },
      row('Appearance', [h('span', { class: 'meta', text: 'In this browser only' })], appearance, () => true, true),
      row('Album colours behind the player', [h('span', { class: 'meta', text: NOTES.albumBackdrop })], backdrop, backdropEnabled))];
  }

  // ---- The list: each page with its glyph, its name and one line of what is set --------------------------------------

  function pages() {
    const piano = table && Array.isArray(table.pages) && table.pages.length > 0
      ? table.pages.filter((p) => p && typeof p.key === 'string').map((p) => ({ key: p.key, title: p.title || p.key }))
      : PIANO_PAGES;
    return [
      ...(system.state === 'failed' ? [] : [{ key: 'system', title: 'System', glyph: 'g-system' }]),
      { key: 'playback', title: 'Playback', glyph: 'g-now' },
      ...piano.map((p) => ({ key: p.key, title: p.title, glyph: PIANO_GLYPHS[p.key] || 'g-piano', piano: true })),
      { key: 'guests', title: 'Guests', glyph: 'g-guests' },
      { key: 'panel', title: 'Panel', glyph: 'g-grid' },
    ];
  }

  /** The line under a page's name: what is set there now. */
  function summary(page) {
    if (page.key === 'system') return 'The tablet, the controller, the piano';
    if (page.key === 'guests') {
      // As the tablet's hub says it: Off, On, On · approve first.
      if (guestsOpen() === null) return '';
      return guestsOpen() ? (approveFirst() ? 'On · approve first' : 'On') : 'Off';
    }
    if (page.key === 'playback') {
      if (!prefs) return 'Tempo, velocity, expression';
      const expression = EXPRESSIONS.find(([wire]) => wire === prefValue('expression'));
      const tempo = prefNumber('defaultTempoPct');
      return [tempo !== null ? `Tempo ${tempo}%` : null, expression ? `Expression ${expression[1]}` : null].filter(Boolean).join(' · ');
    }
    if (page.key === 'panel') {
      const word = { dark: 'Dark', light: 'Light', system: 'Follows the system' }[appearanceNow()];
      const backdrop = albumBackdrop();
      return backdrop === null ? word : `${word} · album colours ${backdrop ? 'on' : 'off'}`;
    }
    if (otherInstrument()) return 'Steven Piano only';
    if (!connected()) return 'Not connected';
    if (piano().state === 'unsupported') return 'Not offered by this firmware';
    if (piano().state !== 'ready') return 'Reading…';
    const v = values();
    const on = (name) => typeof v[name] === 'string' && v[name].trim() !== '0';
    /** A setting's value as its row shows it, through the table (null before the table or without the value). */
    const read = (name) => {
      const setting = tableSetting(name);
      return setting && typeof v[name] === 'string' ? withUnit(setting, shown(setting, v[name])) : null;
    };
    if (page.key === 'feel') {
      const volume = read('volume');
      return [`Full power ${on('fullpower') ? 'on' : 'off'}`, volume !== null ? `Volume ${volume}` : null].filter(Boolean).join(' · ');
    }
    if (page.key === 'lighting') {
      if (typeof v.leds === 'string' && !on('leds')) return 'Strip off';
      return [read('ledmode'), read('ledbright')].filter(Boolean).join(' · ');
    }
    if (page.key === 'pedal') {
      if (typeof v.pedalon !== 'string') return '';
      return on('pedalon') ? (on('pedalhalf') ? 'On · half-pedalling' : 'On') : 'Off';
    }
    return '';
  }

  /** A setting of the piano's table by name, or null. */
  function tableSetting(name) {
    if (!table) return null;
    for (const page of table.pages) {
      for (const part of (page && Array.isArray(page.sections) ? page.sections : [])) {
        const found = (Array.isArray(part.settings) ? part.settings : []).find((s) => s && s.name === name);
        if (found) return found;
      }
    }
    return null;
  }

  let listKey = null;

  function renderList() {
    const all = pages();
    const key = all.map((p) => `${p.key}:${p.title}`).join('|');
    if (key !== listKey) {
      listKey = key;
      items.clear();
      fill(list, all.map((page) => {
        const line = h('span', { class: 'meta' });
        // No data attributes: the frame looks the page over for its own (data-page, data-section…).
        const button = h('button', { class: 'nav-item', type: 'button' },
          glyph(page.glyph),
          h('span', { class: 'text' }, h('span', { class: 't', text: page.title }), line),
          glyph('g-chevron'));
        button.addEventListener('click', () => choose(page.key));
        items.set(page.key, { button, line, page });
        return button;
      }));
    }
    // The page shown is marked: beside the list from 900 px, or open over it below.
    const showsPage = opened || window.matchMedia(WIDE).matches;
    for (const [pageKey, item] of items) {
      if (pageKey === chosen && showsPage) item.button.setAttribute('aria-current', 'page');
      else item.button.removeAttribute('aria-current');
      const text = summary(item.page);
      if (item.line.textContent !== text) item.line.textContent = text;
    }
  }

  function choose(key) {
    const wasOpen = opened;
    chosen = key;
    opened = true;
    remember(key);
    update();
    // Narrow, the page opens over the list: its back row takes the focus. Wide, the focus stays on the list.
    if (!wasOpen && !window.matchMedia(WIDE).matches) back.focus();
  }

  // ---- The pane -----------------------------------------------------------------------------------------------------

  function renderPane() {
    const all = pages();
    const page = all.find((p) => p.key === chosen) || all.find((p) => p.key === 'feel') || all[0];
    if (page.key !== chosen) chosen = page.key;
    const midi = page.piano && otherInstrument() !== null;
    const shape = page.key === 'playback' ? (prefs ? 'prefs' : prefsFailed ? 'failed' : 'loading')
      : page.piano ? `${midi ? 'midi' : piano().state === 'unsupported' ? 'unsupported' : 'cards'}:${table ? 'table' : tableFailed ? 'failed' : 'none'}:${listKey}`
        : page.key === 'system' ? system.state
          : page.key;
    const key = `${page.key}|${shape}`;
    if (key !== paneKey) {
      paneKey = key;
      controls = [];
      paneTitle.textContent = page.title;
      const build = { playback: buildPlayback, panel: buildPanel, guests: buildGuests, system: buildSystem }[page.key];
      fill(paneBody, build ? build() : buildPiano(page.key));
    }
    for (const control of controls) control.update();
    return page;
  }

  function renderTools(page) {
    // System's own capsules (what needs attention, how old the figures are) while its page shows; the piano's link else.
    const onSystem = page.key === 'system' && system.showing;
    system.tools.hidden = !onSystem;
    const showLink = connected() && !onSystem;
    linkCapsule.hidden = !showLink;
    if (showLink) {
      const name = link().name || otherInstrument() || 'the piano';
      const fw = typeof facts().fw === 'string' ? facts().fw.split('+')[0].trim() : '';
      const text = fw && !otherInstrument() ? `${name} · firmware ${fw}` : name;
      if (linkName.textContent !== text) linkName.textContent = text;
    }
    // Save to the piano: on the piano's pages only, while one shows.
    const onPianoPage = page.piano && (opened || window.matchMedia(WIDE).matches) && !otherInstrument();
    save.hidden = !onPianoPage;
    save.disabled = !pianoReady();
  }

  function update() {
    if (!showing) {
      if (system.showing) systemShows({ key: '' });
      return;
    }
    settle();
    split.classList.toggle('opened', opened);
    loadSystemPage();   // once: without system.js, Settings has no System page
    renderList();
    const page = renderPane();
    systemShows(page);
    renderTools(page);
  }

  // The layout changes at 900 px: the Save button follows whether a page shows.
  const wide = window.matchMedia(WIDE);
  const relayout = () => update();
  if (typeof wide.addEventListener === 'function') wide.addEventListener('change', relayout);

  return {
    show() {
      showing = true;
      st = host.state() || st;
      opened = false;
      update();
      loadTable();
      loadPrefs();
    },
    hide() {
      showing = false;
      dragging = null;
      update();   // System's page stops its reads
    },
    render(state) {
      if (state) st = state;
      update();
    },
    /** [key]'s page, chosen and open (an address that names it: #system). */
    open(key) {
      if (typeof key !== 'string' || !key) return;
      chosen = key;
      opened = true;
      remember(key);
      update();
    },
  };
}

// ---- Small helpers ------------------------------------------------------------------------------------------------

/** A whole number from the piano's wire text ("70", " 110 "), or null. */
function whole(text) {
  if (typeof text !== 'string' && typeof text !== 'number') return null;
  const n = Number(String(text).trim());
  return Number.isFinite(n) ? Math.round(n) : null;
}

/**
 * A button that acts once when pressed and, held, again every 70 ms after 400 ms. A click no pointer made (Enter or
 * Space, a screen reader's activation: its `detail` is 0) acts once; a pointer's own click was its press.
 */
function holdToRepeat(button, act) {
  let delay = 0;
  let every = 0;
  const stop = () => {
    clearTimeout(delay);
    clearInterval(every);
    delay = 0;
    every = 0;
  };
  button.addEventListener('pointerdown', (event) => {
    if (button.disabled || event.button !== 0) return;
    stop();
    act();
    delay = setTimeout(() => {
      every = setInterval(() => (button.disabled ? stop() : act()), 70);
    }, 400);
  });
  for (const type of ['pointerup', 'pointerleave', 'pointercancel', 'blur']) button.addEventListener(type, stop);
  button.addEventListener('click', (event) => {
    if (event.detail === 0) act();
  });
  button.addEventListener('contextmenu', (event) => event.preventDefault());
}

function stored() {
  try {
    return localStorage.getItem(STORED_PAGE);
  } catch (e) {
    return null;
  }
}

/** Runs [fn] for System's page; what it throws is kept from Settings. */
function guarded(fn) {
  try {
    return fn();
  } catch (e) {
    if (window.console) console.error(e);
    return undefined;
  }
}

function remember(key) {
  try {
    localStorage.setItem(STORED_PAGE, key);
  } catch (e) {
    // Private browsing: this page only.
  }
}
