/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// The panel's Quiet times page (DESIGN.md › v1.20 — M54): an ES module the frame (app.js) imports the first time the
// section `quiet` shows, create(host, body, tools) → { show(), hide(), render(state) }, as settings.js and system.js
// are. The page is the tablet's: the quiet now with Play anyway, the week at a glance (Monday to Sunday, 6:00 to 22:00,
// the blocks hatched), a card a section (its name, its days, its blocks as chips, Edit) and Add section; the editor in
// the editors' sheet. Every element is built with DOM calls, what the tablet says goes in as text, positions go through
// the CSSOM, every request is built from host.ROOT, and the tablet checks every save again by the same rules. The page's
// own styles are quiet.css, which the module links once.

/** The days as the tablet numbers them on the wire (Monday 1 … Sunday 7), short and in full. */
const DAYS = [
  [1, 'Mon', 'Monday'],
  [2, 'Tue', 'Tuesday'],
  [3, 'Wed', 'Wednesday'],
  [4, 'Thu', 'Thursday'],
  [5, 'Fri', 'Friday'],
  [6, 'Sat', 'Saturday'],
  [7, 'Sun', 'Sunday'],
];
/** JavaScript's getDay() order (Sunday 0) as short names. */
const SHORT_BY_JS_DAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const WEEKDAYS = [1, 2, 3, 4, 5];
const WEEKENDS = [6, 7];
const EVERY_DAY = [1, 2, 3, 4, 5, 6, 7];

/** The tablet's limits (schedule/QuietTimes.kt). */
const MAX_SECTIONS = 12;
const MAX_BLOCKS = 16;
const MAX_NAME = 40;
const GAP_MINUTES = 10;
const DEFAULT_LENGTH = 50;
const DAY_MINUTES = 1440;

/** The week strip's window, 6:00 to 22:00, and the hours ruled across it. */
const WEEK_FROM = 6 * 60;
const WEEK_TO = 22 * 60;
const RULED = [6, 12, 18, 22];

/** What quiet times do, under the sections (QuietCopy.NOTE). */
const NOTE = 'During a quiet time the piano stays silent: anything playing stops as a block begins, and nothing starts until it ends. Play anyway lifts it until the block ends.';
const NO_SECTIONS = 'No quiet times yet: the piano plays whenever someone asks it to.';
const PLAY_ANYWAY = 'Play anyway';

/** The tablet's words when sections can't be kept (QuietTimes.validate). */
const WORDS = {
  tooManySections: 'There can be 12 sections at most.',
  noName: 'Give the section a name.',
  longName: "A section's name can be 40 characters at most.",
  sameName: "Two sections can't have the same name.",
  noDay: 'Choose at least one day.',
  noBlock: 'Add at least one block.',
  tooManyBlocks: 'A section can have 16 blocks at most.',
  notATime: "A block's times must be times of day.",
  sameTimes: "A block's end must differ from its start.",
};

export function create(host, body, tools) {
  const { h, fill } = host;
  linkStyles(h);

  /** The last state (its quiet: {now, until, overridden, next}), the tablet's answer to GET /api/quiet, whether the page shows. */
  let st = host.state() || null;
  let data = null;
  let showing = false;
  let quietKey = '';

  // ---- The head's tool, and the page ------------------------------------------------------------------------------

  const addSection = h('button', { class: 'outlined', type: 'button' }, host.glyph('i-add'), h('span', { text: 'Add section' }));
  addSection.addEventListener('click', () => openEditor(null));
  if (tools) fill(tools, addSection);

  const statusLine = h('p', { class: 'quiet-line', text: 'Reading the quiet times…' });
  const anyway = h('button', { class: 'outlined filled', type: 'button', hidden: true, text: PLAY_ANYWAY });
  anyway.addEventListener('click', playAnyway);
  const status = h('div', { class: 'card quiet-status', role: 'status', 'aria-live': 'polite' }, host.glyph('g-moon'), statusLine, anyway);
  const grid = h('div', { class: 'quiet-grid' });
  const week = h('div', { class: 'card quiet-week', role: 'img', 'aria-label': "The week's quiet times, Monday to Sunday, 6:00 to 22:00" }, grid);
  const sectionsNode = h('div', { class: 'stack quiet-sections' });
  const root = h('div', { class: 'quiet-page stack' }, status, week, sectionsNode, h('p', { class: 'note inset', text: NOTE }));
  fill(body, root);

  // ---- Reading and drawing ------------------------------------------------------------------------------------------

  async function load() {
    try {
      data = await host.get(host.ROOT + '/api/quiet');
      renderAll();
    } catch (e) {
      host.failed(e);
    }
  }

  /** The quiet now: the state's (it follows the tablet), else the page's last read. */
  function quietNow() {
    return (st && st.quiet) || (data && data.now) || null;
  }

  function renderAll() {
    renderStatus();
    renderWeek();
    renderSections();
  }

  function renderStatus() {
    const q = quietNow();
    const now = Date.now();
    let text;
    let holds = false;
    if (q && q.now && q.until) {
      holds = !q.overridden;
      text = q.overridden ? `Lifted until ${untilText(q.until, now)}` : `Quiet now · until ${untilText(q.until, now)}`;
    } else if (q && q.next) {
      text = `Next quiet time ${nextText(q.next, now)}`;
    } else {
      text = data || q ? 'No quiet times set' : 'Reading the quiet times…';
    }
    statusLine.textContent = text;
    anyway.hidden = !holds;
  }

  /** Monday to Sunday across, 6:00 to 22:00 down: the hours ruled, every block hatched (quiet.css), placed through the CSSOM. */
  function renderWeek() {
    const sections = data ? data.sections.map(inMinutes) : [];
    const columns = DAYS.map(() => h('div', { class: 'quiet-col' }));
    for (const column of columns) {
      for (const hour of RULED) column.append(placed(h('span', { class: 'quiet-rule' }), hour * 60));
    }
    for (const piece of weekPieces(sections)) {
      const node = placed(h('span', { class: 'quiet-block' }), piece.from);
      node.style.height = `${((piece.to - piece.from) / (WEEK_TO - WEEK_FROM)) * 100}%`;
      columns[piece.day].append(node);
    }
    const hours = h('div', { class: 'quiet-hours' }, RULED.map((hour) => placed(h('span', { text: clock(hour * 60) }), hour * 60)));
    fill(grid, h('span', { class: 'quiet-corner' }), DAYS.map(([, short]) => h('span', { class: 'quiet-day', text: short })), hours, columns);
  }

  function renderSections() {
    const sections = data ? data.sections : [];
    addSection.disabled = sections.length >= MAX_SECTIONS;
    if (!data) return;
    if (sections.length === 0) {
      fill(sectionsNode, h('p', { class: 'empty', text: NO_SECTIONS }));
      return;
    }
    fill(sectionsNode, sections.map((section, index) => {
      const edit = h('button', { class: 'outlined', type: 'button', 'aria-label': `Edit ${section.name}`, text: 'Edit' });
      edit.addEventListener('click', () => openEditor(index));
      return h('section', { class: 'card quiet-section', 'aria-label': section.name },
        h('div', { class: 'card-head' },
          h('h2', { text: section.name }),
          h('span', { class: 'meta', text: daysText(section.days) }),
          edit),
        h('div', { class: 'chips quiet-chips' },
          section.blocks.map((b) => h('span', { class: 'chip static', text: range(inMinutesBlock(b)) }))));
    }));
  }

  /** Play anyway: the quiet lifts until its block ends; the tablet's state says so. */
  async function playAnyway() {
    anyway.disabled = true;
    try {
      await host.post(host.ROOT + '/api/quiet/override');
      const q = quietNow();
      host.toast(q && q.until ? `Lifted until ${untilText(q.until, Date.now())}.` : 'Lifted.');
      load();
    } catch (e) {
      host.failed(e);
    } finally {
      anyway.disabled = false;
    }
  }

  // ---- The editor, in the editors' sheet ---------------------------------------------------------------------------

  /** The section being edited: its place (null for a new one), its fields in minutes, what keeps it from saving. */
  let draft = null;
  let sheet = null;
  let editor = null;

  function openEditor(index) {
    const sections = data ? data.sections : [];
    if (index === null && sections.length >= MAX_SECTIONS) {
      host.toast(WORDS.tooManySections);
      return;
    }
    const base = index === null ? null : sections[index];
    draft = base
      ? { index, name: base.name, days: new Set(base.days), blocks: base.blocks.map(inMinutesBlock), problem: null, saving: false }
      : { index: null, name: '', days: new Set(WEEKDAYS), blocks: [proposed([], nextHour())], problem: null, saving: false };
    if (!sheet) {
      sheet = h('dialog', { class: 'sheet quiet-sheet', 'aria-labelledby': 'quiet-editor-title' });
      sheet.addEventListener('click', (event) => {
        if (event.target === sheet) sheet.close();   // the scrim
      });
      document.body.append(sheet);
      buildEditor();
    }
    editor.title.textContent = index === null ? 'Add section' : 'Edit section';
    editor.name.value = draft.name;
    editor.remove.hidden = index === null;
    renderDays();
    renderBlocks();
    renderProblem();
    if (!sheet.open) sheet.showModal();
    editor.name.focus();
  }

  function buildEditor() {
    const title = h('h2', { class: 'editor-title', id: 'quiet-editor-title' });
    const name = h('input', { type: 'text', maxlength: String(MAX_NAME), placeholder: 'School days', 'aria-label': 'Name', autocomplete: 'off' });
    name.addEventListener('input', () => {
      draft.name = name.value;
      changed();
    });
    const days = h('div', { class: 'chips', role: 'group', 'aria-label': 'Days' });
    const presets = h('div', { class: 'chips' });
    const blocks = h('div', { class: 'quiet-blocks' });
    const addBlock = h('button', { class: 'outlined', type: 'button' }, host.glyph('i-add'), h('span', { text: 'Add block' }));
    addBlock.addEventListener('click', () => {
      if (draft.blocks.length >= MAX_BLOCKS) return;
      draft.blocks.push(proposed(draft.blocks, nextHour()));
      renderBlocks();
      changed();
    });
    const problem = h('p', { class: 'note inset quiet-problem', role: 'status', 'aria-live': 'polite' });
    const remove = h('button', { class: 'text-button', type: 'button', text: 'Delete section' });
    remove.addEventListener('click', confirmDelete);
    const cancel = h('button', { class: 'text-button', type: 'button', text: 'Cancel' });
    cancel.addEventListener('click', () => sheet.close());
    const save = h('button', { class: 'outlined filled', type: 'button', text: 'Save' });
    save.addEventListener('click', saveDraft);
    fill(sheet, h('div', { class: 'quiet-editor' },
      h('p', { class: 'eyebrow inset', text: 'Quiet times' }),
      title,
      h('div', { class: 'inset' }, h('label', { class: 'field' }, name)),
      h('h3', { class: 'section-head eyebrow', text: 'Days' }),
      h('div', { class: 'actions quiet-days' }, days, presets),
      h('h3', { class: 'section-head eyebrow', text: 'Blocks' }),
      blocks,
      h('div', { class: 'actions' }, addBlock),
      problem,
      h('div', { class: 'actions editor-actions' }, remove, h('span', { class: 'spacer' }), cancel, save)));
    editor = { title, name, days, presets, blocks, addBlock, problem, remove, save };
  }

  function renderDays() {
    fill(editor.days, DAYS.map(([day, short, full]) => {
      const node = host.chip(short, draft.days.has(day), () => {
        if (draft.days.has(day)) draft.days.delete(day);
        else draft.days.add(day);
        renderDays();
        changed();
      });
      node.setAttribute('aria-label', full);
      return node;
    }));
    const chosen = sortedDays(draft.days);
    fill(editor.presets,
      host.chip('Weekdays', same(chosen, WEEKDAYS), () => setDays(WEEKDAYS)),
      host.chip('Every day', same(chosen, EVERY_DAY), () => setDays(EVERY_DAY)));
  }

  function setDays(days) {
    draft.days = new Set(days);
    renderDays();
    changed();
  }

  /** Each block: from and to as the browser's time fields, "The next day" when it crosses midnight, and its remove. */
  function renderBlocks() {
    fill(editor.blocks, draft.blocks.map((block, i) => {
      const next = h('span', { class: 'meta', text: 'The next day', hidden: block.end < block.start ? null : true });
      const remove = h('button', { class: 'icon-button small', type: 'button', 'aria-label': `Remove ${range(block)}` }, host.glyph('i-close'));
      const update = () => {
        next.hidden = !(block.end < block.start);
        remove.setAttribute('aria-label', `Remove ${range(block)}`);
        changed();
      };
      const from = timeField(block.start, `Block ${i + 1}, from`, (m) => {
        block.start = m;
        update();
      });
      const to = timeField(block.end, `Block ${i + 1}, to`, (m) => {
        block.end = m;
        update();
      });
      remove.addEventListener('click', () => {
        draft.blocks.splice(i, 1);
        renderBlocks();
        changed();
      });
      return h('div', { class: 'quiet-block-row' }, h('span', { text: 'From' }), from, h('span', { text: 'to' }), to, next, remove);
    }));
    editor.addBlock.disabled = draft.blocks.length >= MAX_BLOCKS;
  }

  /** A time of day as the browser's own field; what it holds goes to [onChange] in minutes (NaN when it holds none). */
  function timeField(minute, label, onChange) {
    const input = h('input', { class: 'field quiet-time', type: 'time', step: '60', required: true, 'aria-label': label });
    input.value = Number.isFinite(minute) ? wire(minute) : '';
    input.addEventListener('change', () => onChange(minutes(input.value)));
    return input;
  }

  function changed() {
    draft.problem = null;
    renderProblem();
  }

  function renderProblem() {
    const problem = draft.problem || validate(all(fromDraft()));
    editor.problem.textContent = problem || '';
    editor.problem.hidden = !problem;
    editor.save.disabled = !!problem || draft.saving;
  }

  /** The section as it stands in the editor: its name trimmed, its days in order. */
  function fromDraft() {
    return { name: draft.name.trim(), days: sortedDays(draft.days), blocks: draft.blocks.map((b) => ({ start: b.start, end: b.end })) };
  }

  /** Every section as a save would keep them: this one at its place, or added; without it ([section] null) for Delete. */
  function all(section) {
    const list = (data ? data.sections : []).map(inMinutes);
    if (draft.index === null) {
      if (section) list.push(section);
    } else if (section) {
      list[draft.index] = section;
    } else {
      list.splice(draft.index, 1);
    }
    return list;
  }

  async function saveDraft() {
    const kept = all(fromDraft());
    const problem = validate(kept);
    if (problem) {
      draft.problem = problem;
      renderProblem();
      return;
    }
    await send(kept, 'Saved.');
  }

  function confirmDelete() {
    if (draft.index === null) return;
    const name = draft.name.trim() || 'This section';
    host.confirm({
      title: 'Delete this section?',
      message: `${name}: its blocks go, and the piano may play at those times again.`,
      action: 'Delete section',
      run: () => send(all(null), 'Section deleted.'),
    });
  }

  /** Every section to the tablet at once; its refusal comes back in its own words, shown in the editor. */
  async function send(kept, said) {
    draft.saving = true;
    renderProblem();
    try {
      await host.put(host.ROOT + '/api/quiet', { sections: kept.map(toWire) });
      draft.saving = false;
      if (sheet.open) sheet.close();
      host.toast(said);
      load();
    } catch (e) {
      draft.saving = false;
      if (e && e.status && e.status !== 401) {
        draft.problem = e.message;
        renderProblem();
      } else {
        host.failed(e);
      }
    }
  }

  return {
    show() {
      showing = true;
      st = host.state() || st;
      renderStatus();
      load();
    },
    hide() {
      showing = false;
    },
    render(state) {
      if (state) st = state;
      const key = JSON.stringify((st && st.quiet) || null);
      if (key === quietKey) return;
      quietKey = key;
      renderStatus();
      if (showing) load();   // a block began or ended, Play anyway, or the tablet saved its sections
    },
  };
}

// ---- Small helpers --------------------------------------------------------------------------------------------------

/** quiet.css, linked once, after the frame's styles. */
function linkStyles(h) {
  if (document.querySelector('link[data-quiet-styles]')) return;
  document.head.append(h('link', { rel: 'stylesheet', href: 'quiet.css', 'data-quiet-styles': true }));
}

/** [node] placed at [minute] of the week strip's window, through the CSSOM. */
function placed(node, minute) {
  node.style.top = `${((minute - WEEK_FROM) / (WEEK_TO - WEEK_FROM)) * 100}%`;
  return node;
}

/** Every block as the strip draws it: a piece each day it runs, one crossing midnight on the next day too, cut to 6:00–22:00. */
function weekPieces(sections) {
  const pieces = [];
  const add = (day, from, to) => {
    const a = Math.max(from, WEEK_FROM);
    const b = Math.min(to, WEEK_TO);
    if (b > a) pieces.push({ day, from: a, to: b });
  };
  for (const section of sections) {
    for (const block of section.blocks) {
      if (!Number.isFinite(block.start) || !Number.isFinite(block.end) || block.start === block.end) continue;
      for (const day of section.days) {
        const at = day - 1;
        if (block.end > block.start) {
          add(at, block.start, block.end);
        } else {
          add(at, block.start, DAY_MINUTES);
          add((at + 1) % 7, 0, block.end);
        }
      }
    }
  }
  return pieces;
}

/** What keeps [sections] from being kept, in the tablet's words (QuietTimes.validate); null when nothing does. */
function validate(sections) {
  if (sections.length > MAX_SECTIONS) return WORDS.tooManySections;
  const names = new Set();
  for (const section of sections) {
    const name = section.name.trim();
    if (!name) return WORDS.noName;
    if (name.length > MAX_NAME) return WORDS.longName;
    const key = name.toLowerCase();
    if (names.has(key)) return WORDS.sameName;
    names.add(key);
    if (section.days.length === 0) return WORDS.noDay;
    if (section.blocks.length === 0) return WORDS.noBlock;
    if (section.blocks.length > MAX_BLOCKS) return WORDS.tooManyBlocks;
    for (const block of section.blocks) {
      if (!isMinute(block.start) || !isMinute(block.end)) return WORDS.notATime;
      if (block.start === block.end) return WORDS.sameTimes;
    }
    const clash = overlap(section);
    if (clash) return clash;
  }
  return null;
}

/** Two blocks of [section] that meet on a day of its week ("8:40–9:30 and 9:00–9:50 overlap."), as the tablet finds them. */
function overlap(section) {
  const week = DAY_MINUTES * 7;
  const pieces = [];
  section.blocks.forEach((block, index) => {
    for (const day of section.days) {
      const from = (day - 1) * DAY_MINUTES + block.start;
      const to = from + length(block);
      if (to <= week) {
        pieces.push({ from, to, index });
      } else {
        pieces.push({ from, to: week, index });
        pieces.push({ from: 0, to: to - week, index });
      }
    }
  });
  pieces.sort((a, b) => a.from - b.from);
  for (let i = 0; i < pieces.length; i++) {
    for (let j = i + 1; j < pieces.length; j++) {
      if (pieces[j].from >= pieces[i].to) break;
      if (pieces[j].index !== pieces[i].index) {
        const pair = [section.blocks[pieces[i].index], section.blocks[pieces[j].index]].sort((a, b) => a.start - b.start || a.end - b.end);
        return `${range(pair[0])} and ${range(pair[1])} overlap.`;
      }
    }
  }
  return null;
}

/** Add block's proposal: ten minutes after the last block's end, as long as it; with none, [start] for 50 minutes. */
function proposed(blocks, start) {
  const last = blocks[blocks.length - 1];
  if (!last || !isMinute(last.start) || !isMinute(last.end)) return { start, end: (start + DEFAULT_LENGTH) % DAY_MINUTES };
  const from = (last.start + length(last) + GAP_MINUTES) % DAY_MINUTES;
  return { start: from, end: (from + (length(last) || DEFAULT_LENGTH)) % DAY_MINUTES };
}

/** A block's length in minutes; one ending before its start runs into the next day. */
function length(block) {
  return (((block.end - block.start) % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES;
}

const isMinute = (m) => Number.isInteger(m) && m >= 0 && m < DAY_MINUTES;

/** The next whole hour on this browser's clock, in minutes. */
function nextHour() {
  return ((new Date().getHours() + 1) % 24) * 60;
}

/** "08:40" (the wire's, the time field's) as minutes; NaN for anything else. */
function minutes(text) {
  const match = /^([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d(?:\.\d+)?)?$/.exec(text || '');
  return match ? Number(match[1]) * 60 + Number(match[2]) : NaN;
}

/** Minutes as the wire has them, "08:40". */
function wire(minute) {
  return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
}

/** Minutes as the tablet says them, "8:40". */
function clock(minute) {
  return `${Math.floor(minute / 60)}:${String(minute % 60).padStart(2, '0')}`;
}

/** A block as the tablet says it, "8:40–9:30". */
function range(block) {
  return isMinute(block.start) && isMinute(block.end) ? `${clock(block.start)}–${clock(block.end)}` : 'this block';
}

const inMinutesBlock = (b) => ({ start: minutes(b.start), end: minutes(b.end) });
const inMinutes = (s) => ({ name: s.name, days: s.days.slice(), blocks: s.blocks.map(inMinutesBlock) });
const toWire = (s) => ({ name: s.name, days: s.days, blocks: s.blocks.map((b) => ({ start: wire(b.start), end: wire(b.end) })) });

const sortedDays = (days) => [...days].sort((a, b) => a - b);
const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

/** The days as the tablet says them: "Every day", "Weekdays", "Weekends", "Mondays", "Mon, Wed, Fri". */
function daysText(days) {
  const chosen = sortedDays(new Set(days));
  if (same(chosen, EVERY_DAY)) return 'Every day';
  if (same(chosen, WEEKDAYS)) return 'Weekdays';
  if (same(chosen, WEEKENDS)) return 'Weekends';
  if (chosen.length === 0) return 'No days';
  if (chosen.length === 1) return `${DAYS[chosen[0] - 1][2]}s`;
  return chosen.map((d) => DAYS[d - 1][1]).join(', ');
}

/** "9:30" on this browser's clock; a weekday before it when it is a day or more away. */
function untilText(ms, now) {
  const at = new Date(ms);
  return ms - now >= 24 * 3600 * 1000 ? `${SHORT_BY_JS_DAY[at.getDay()]} ${hm(at)}` : hm(at);
}

/** "9:40" today, else with its weekday, "Mon 8:40". */
function nextText(ms, now) {
  const at = new Date(ms);
  return at.toDateString() === new Date(now).toDateString() ? hm(at) : `${SHORT_BY_JS_DAY[at.getDay()]} ${hm(at)}`;
}

function hm(date) {
  return `${date.getHours()}:${String(date.getMinutes()).padStart(2, '0')}`;
}
