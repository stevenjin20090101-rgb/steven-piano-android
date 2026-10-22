/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// Studio (DESIGN.md › v1.7 — M23, M24), a section of the panel's frame: the models, Transcribe (recordings sent to the
// tablet), Compose and the jobs, or why Studio can't run on the tablet. A module of its own since v1.27 (M63): the frame
// (app.js) makes it once, create(host) → { show(), render(), drop(files) }. It asks the tablet only through the host,
// from host.ROOT.

import { $, clock, debounce, holdToRepeat } from './ui.js';

export function create(host) {
  const { h, fill, glyph, chip, setEmpty, get, post, toast, failed, show, zoneFor, addUploads, renderUploads } = host;
  /** The panel's state, the frame's: read where it is used, so never an old one. */
  const state = () => host.state();

  // ---- Studio (v1.7 — M23) ---------------------------------------------------------------------------------

  const AUDIO_BYTES = 200 * 1024 * 1024;
  const AUDIO_EXTENSIONS = ['wav', 'wave', 'mp3', 'm4a', 'mp4', 'aac', 'flac', 'ogg', 'oga', 'opus', 'webm', '3gp', 'amr'];

  /** Studio's page: MODELS, TRANSCRIBE (the drop zone for recordings and what was sent), COMPOSE (v1.7 — M24), JOBS; or why Studio can't run on the tablet. */
  function renderStudio() {
    $('studio-body').replaceChildren(
      h('p', { class: 'empty', id: 'studio-unavailable', hidden: true }),
      h('div', { id: 'studio-parts' },
        h('h2', { class: 'section-head eyebrow', text: 'Models' }),
        h('ul', { class: 'rows card', id: 'studio-models' }),
        h('h2', { class: 'section-head eyebrow', text: 'Transcribe' }),
        zoneFor({
          accept: 'audio/*,' + AUDIO_EXTENSIONS.map((e) => '.' + e).join(','),
          multiple: true,
          title: 'Drop a piano recording here',
          meta: '.wav, .mp3, .m4a, .flac, .ogg and the like, up to 200 MB. About a minute per three minutes of audio.',
          button: 'Choose recordings',
          onFiles: addRecordings,
        }),
        h('ul', { class: 'rows card uploads', id: 'studio-upload-rows' }),
        h('h2', { class: 'section-head eyebrow', text: 'Compose' }),
        h('div', { id: 'studio-compose' }),
        h('h2', { class: 'section-head eyebrow', id: 'studio-jobs-head', text: 'Jobs', hidden: true }),
        h('ul', { class: 'rows card', id: 'studio-jobs' })));
    renderUploads();
    renderCompose();
    renderStudioState();
  }

  /** Recordings sent one at a time, as the tablet takes them, each checked here first as the tablet will. */
  function addRecordings(files) {
    const added = [];
    for (const file of Array.from(files)) {
      const name = file.name;
      const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
      const upload = { file, name, size: file.size, status: 'Waiting', progress: 0, route: host.ROOT + '/api/studio/audio', list: 'studio-upload-rows', sent: 'Sent to the tablet · transcribing there', refused: 'Not sent' };
      if (!AUDIO_EXTENSIONS.includes(extension)) upload.status = 'Not sent: only recordings (.wav, .mp3, .m4a, .flac, .ogg…)';
      else if (file.size > AUDIO_BYTES) upload.status = 'Not sent: a recording can be 200 MB at most';
      else if (file.size === 0) upload.status = 'Not sent: the file is empty';
      else upload.pending = true;
      added.push(upload);
    }
    if (host.section() !== 'studio') show('studio');
    addUploads(added);
  }

  /** The models and the jobs, as the tablet reports them in its state; their rows kept in place, so a bar eases. */
  function renderStudioState() {
    const body = $('studio-parts');
    if (!body || !state() || !state().studio) return;
    const studio = state().studio;
    const unavailable = $('studio-unavailable');
    unavailable.hidden = studio.available;
    if (!studio.available) setEmpty(unavailable, 'g-studio', studio.reason || '');
    body.hidden = !studio.available;
    keyedRows($('studio-models'), studio.models, (model) => model.name, studioRow, (row, model) => {
      setStudioRow(row, model.title, model.line, model.progress === null || model.progress === undefined ? undefined : model.progress);
    });
    if (!composing) renderCompose();   // the note follows the model; an open form is left as it is
    $('studio-jobs-head').hidden = studio.jobs.length === 0;
    keyedRows($('studio-jobs'), studio.jobs, (job) => job.id, studioRow, (row, job) => {
      setStudioRow(row, job.state === 'done' && job.title ? job.title : job.name, job.line, job.state === 'running' ? job.progress : undefined);
      const cancellable = job.state === 'queued' || job.state === 'running';
      if (cancellable && !row.cancel) {
        row.cancel = h('button', { class: 'outlined', type: 'button', 'aria-label': `Cancel ${job.name}`, onclick: () => cancelJob(job.id) }, 'Cancel');
        row.node.append(row.cancel);
      } else if (!cancellable && row.cancel) {
        row.cancel.remove();
        row.cancel = null;
      }
    });
  }

  /**
   * [list]'s rows for [items], one per [keyOf], each made once by [make] (→ {node, …}) and brought up to date by
   * [update]: a row that stays keeps its place in the page, so its bar eases to each new value (v1.20 — M53).
   */
  const keyedShown = new WeakMap();

  function keyedRows(list, items, keyOf, make, update) {
    const known = keyedShown.get(list) || new Map();
    const next = new Map();
    const nodes = items.map((item) => {
      const key = keyOf(item);
      const row = known.get(key) || make(item);
      update(row, item);
      next.set(key, row);
      return row.node;
    });
    keyedShown.set(list, next);
    if (nodes.length !== list.children.length || nodes.some((node, i) => list.children[i] !== node)) list.replaceChildren(...nodes);
  }

  /** A Studio row: its title, its line, and a 2 px bar under them. */
  function studioRow() {
    const title = h('p', { class: 'title' });
    const line = h('p', { class: 'meta' });
    const fill = h('span');
    const bar = h('div', { class: 'progress' }, fill);
    return { node: h('li', { class: 'row' }, h('div', { class: 'text' }, title, line, bar)), title, line, bar, fill, cancel: null };
  }

  /** [progress]: undefined, no bar; null, under way with no measure yet (the sweep); else 0–1. */
  function setStudioRow(row, title, line, progress) {
    if (row.title.textContent !== title) row.title.textContent = title;
    if (row.line.textContent !== (line || '')) row.line.textContent = line || '';
    row.bar.hidden = progress === undefined;
    row.bar.classList.toggle('waiting', progress === null);
    row.fill.style.setProperty('--fill', `${Math.round((Number(progress) || 0) * 100)}%`);
  }

  async function cancelJob(id) {
    try {
      await post(host.ROOT + `/api/studio/jobs/${id}/cancel`);
    } catch (e) {
      failed(e);
    }
  }

  // ---- Composing (v1.7 — M24) ------------------------------------------------------------------------------

  const MOODS = [['calm', 'Calm'], ['bright', 'Bright'], ['wild', 'Wild'], ['melancholy', 'Melancholy']];
  const MAJOR_NAMES = ['C', 'D♭', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
  const MINOR_NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'B♭', 'B'];
  const COMPOSE_NOTE = 'Runs on this tablet. About a minute for a two-minute piece.';
  const TEMPO = { min: 40, max: 200 };
  const LENGTH = { min: 1, max: 5 };

  /** The compose form: null while closed; else its seed (as the tablet sent it), the choices made here, the search. */
  let composing = null;

  const keyName = (key) => `${(key.minor ? MINOR_NAMES : MAJOR_NAMES)[key.tonic]} ${key.minor ? 'minor' : 'major'}`;
  const spokenKey = (key) => keyName(key).replace('♯', ' sharp').replace('♭', ' flat');
  const manner = (seed) => (seed.composer ? `${seed.title} (${seed.composer})` : seed.title);

  /** The key the form holds: the one chosen here, else the seed's (for Melancholy its minor, as on the tablet). */
  function composeKey(c) {
    if (c.key) return c.key;
    if (!c.seed) return null;
    const own = c.seed.key;
    return c.mood === 'melancholy' && !own.minor ? { tonic: (own.tonic + 9) % 12, minor: true } : { tonic: own.tonic, minor: own.minor };
  }

  const composeBpm = (c) => (c.bpm !== null ? c.bpm : c.seed ? c.seed.bpm : null);

  /** Under the button and the form: what it takes, and that the model downloads first while it isn't on the tablet. */
  function composeNote() {
    const model = state() && state().studio ? state().studio.models.find((m) => m.name === 'composer') : null;
    return model && !model.installed ? `${COMPOSE_NOTE} Downloads the composing model (${Math.round(model.sizeBytes / 1e6)} MB) first.` : COMPOSE_NOTE;
  }

  /** COMPOSE: the button and its note, or the form. */
  function renderCompose() {
    const holder = $('studio-compose');
    if (!holder) return;
    if (!composing) {
      holder.replaceChildren(h('div', { class: 'actions compose-start' },
        h('button', { class: 'outlined', type: 'button', onclick: () => openCompose(null) }, 'Compose a piece…'),
        h('p', { class: 'meta', text: composeNote() })));
      return;
    }
    holder.replaceChildren(composeEditor());
  }

  function openCompose(pieceId) {
    composing = { seed: null, reading: true, mood: 'calm', key: null, bpm: null, minutes: 2, choosing: false, query: '', error: null, sending: false };
    renderCompose();
    loadSeed(pieceId);
  }

  /** The seed from the tablet: the piece chosen, else the one played last; its key and tempo become the form's until changed. */
  async function loadSeed(pieceId) {
    const c = composing;
    c.reading = true;
    try {
      const seed = await get(pieceId ? host.ROOT + `/api/studio/seed?piece=${pieceId}` : host.ROOT + '/api/studio/seed');
      if (composing !== c) return;
      c.seed = seed;
      c.key = null;
      c.bpm = null;
      c.error = null;
    } catch (e) {
      if (composing !== c) return;
      if (e.status === 404 && !c.seed) c.error = 'A composition starts from a piece in the library. Add one first.';
      else if (e.status !== 401) c.error = e.message;
    }
    c.reading = false;
    renderCompose();
  }

  /** The tablet's sheet laid flat: MOOD, KEY, TEMPO, LENGTH, IN THE MANNER OF, the note, Cancel and Compose. */
  function composeEditor() {
    const c = composing;
    const again = () => {
      c.error = null;
      renderCompose();
    };
    const key = composeKey(c);
    const keyChips = Array.from({ length: 12 }, (_, tonic) => {
      const option = { tonic, minor: key ? key.minor : false };
      const node = chip((option.minor ? MINOR_NAMES : MAJOR_NAMES)[tonic], !!key && key.tonic === tonic, () => { c.key = option; again(); }, !key);
      node.setAttribute('aria-label', spokenKey(option));
      return node;
    });
    const modeChips = [false, true].map((minor) => chip(minor ? 'Minor' : 'Major', !!key && key.minor === minor, () => { c.key = { tonic: key.tonic, minor }; again(); }, !key));
    const ready = !!c.seed && !c.reading && !!key && composeBpm(c) !== null;
    return h('div', { class: 'compose-editor' },
      h('p', { class: 'eyebrow inset', text: c.seed ? `In the manner of ${manner(c.seed)}` : 'Studio' }),
      h('h2', { class: 'editor-title', text: 'Compose a piece' }),
      h('h3', { class: 'section-head eyebrow', text: 'Mood' }),
      h('div', { class: 'actions' }, h('div', { class: 'chips', role: 'group', 'aria-label': 'Mood' },
        MOODS.map(([name, label]) => chip(label, c.mood === name, () => { c.mood = name; again(); })))),
      h('h3', { class: 'section-head eyebrow', text: 'Key' }),
      h('div', { class: 'actions' },
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Key' }, keyChips),
        h('div', { class: 'chips', role: 'group', 'aria-label': 'Major or minor' }, modeChips)),
      h('h3', { class: 'section-head eyebrow', text: 'Tempo' }),
      stepperField('Tempo', 'BPM', TEMPO, () => composeBpm(c), (v) => { c.bpm = v; }, () => (c.seed ? (composeBpm(c) === c.seed.bpm ? "The piece's own tempo" : `The piece's own: ${c.seed.bpm} bpm`) : null), (v) => `${v} beats a minute`),
      h('h3', { class: 'section-head eyebrow', text: 'Length' }),
      stepperField('Length', 'MIN', LENGTH, () => c.minutes, (v) => { c.minutes = v; }, () => null, (v) => (v === 1 ? '1 minute' : `${v} minutes`)),
      h('h3', { class: 'section-head eyebrow', text: 'In the manner of' }),
      seedPart(c),
      c.error ? h('p', { class: 'note inset', role: 'status', text: c.error }) : null,
      h('p', { class: 'note inset', text: composeNote() }),
      h('div', { class: 'actions editor-actions' },
        h('button', { class: 'text-button', type: 'button', onclick: () => { composing = null; renderCompose(); } }, 'Cancel'),
        h('button', { class: 'outlined', type: 'button', disabled: !ready || c.sending, onclick: sendCompose }, 'Compose')));
  }

  /**
   * A number between [range]'s ends with − and +, which repeat while held: the value and its note change in
   * place, so a held button keeps its hold. [read] gives it, [write] keeps it, [note] and [spoken] say it.
   */
  function stepperField(label, unit, range, read, write, note, spoken) {
    const value = h('span', { class: 'value' });
    const noteLine = h('span', { class: 'meta' });
    const down = h('button', { class: 'icon-button', type: 'button' }, glyph('i-remove'));
    const up = h('button', { class: 'icon-button', type: 'button' }, glyph('i-add'));
    const refresh = () => {
      const v = read();
      value.textContent = v === null ? '—' : String(v);
      down.disabled = v === null || v <= range.min;
      up.disabled = v === null || v >= range.max;
      down.setAttribute('aria-label', `Less, ${v === null ? '' : spoken(v)}`);
      up.setAttribute('aria-label', `More, ${v === null ? '' : spoken(v)}`);
      const n = note();
      noteLine.textContent = n || '';
      noteLine.hidden = !n;
    };
    const step = (delta) => () => {
      const v = read();
      if (v === null) return;
      write(Math.min(range.max, Math.max(range.min, v + delta)));
      refresh();
    };
    holdToRepeat(down, step(-1));
    holdToRepeat(up, step(1));
    refresh();
    return h('div', { class: 'setting' },
      h('div', { class: 'label' }, label, h('span', { class: 'eyebrow', text: unit }), noteLine),
      h('div', { class: 'stepper' }, down, value, up));
  }

  /** The seed: its title, then its composer, key and tempo, and Change; or the search over the library. */
  function seedPart(c) {
    if (c.reading && !c.seed) return h('ul', { class: 'rows' }, h('li', { class: 'row' }, h('p', { class: 'meta', text: 'Reading the piece…' })));
    const rows = [];
    if (c.seed) {
      rows.push(h('li', { class: 'row' },
        h('div', { class: 'text' },
          h('p', { class: 'title', text: c.seed.title }),
          h('p', { class: 'meta', text: [c.seed.composer, c.seed.key.label, `${c.seed.bpm} bpm`].filter(Boolean).join(' · ') })),
        h('button', { class: 'outlined', type: 'button', 'aria-label': c.choosing ? 'Close the search' : 'Choose another piece', onclick: () => { c.choosing = !c.choosing; renderCompose(); } }, c.choosing ? 'Close' : 'Change')));
    }
    const list = h('ul', { class: 'rows' }, rows);
    if (!c.choosing) return list;
    const choices = h('ul', { class: 'rows', role: 'radiogroup', 'aria-label': 'In the manner of' });
    const search = h('input', { class: 'field', type: 'search', placeholder: 'Search titles and composers', 'aria-label': 'Search pieces', autocomplete: 'off', maxlength: '200' });
    search.value = c.query;
    const choose = (id) => {
      c.choosing = false;
      c.query = '';
      loadSeed(id);
    };
    const show = (pieces) => {
      const items = pieces.map((p) => {
        const chosen = c.seed && p.id === c.seed.pieceId;
        const node = h('li', { class: 'row clickable choice', role: 'radio', tabindex: '0', 'aria-checked': chosen ? 'true' : 'false' },
          h('div', { class: 'text' }, h('p', { class: 'title', text: p.title }), h('p', { class: 'meta', text: [p.composerShort || 'Unknown composer', clock(p.durationMs)].join(' · ') })),
          chosen ? glyph('i-check') : null);
        node.addEventListener('click', () => choose(p.id));
        node.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            choose(p.id);
          }
        });
        return node;
      });
      fill(choices, items.length ? items : h('li', { class: 'row' }, h('p', { class: 'meta', text: c.query ? 'Nothing matches that search.' : 'No pieces yet.' })));
    };
    const load = () => {
      const params = new URLSearchParams({ limit: '30' });
      if (c.query) params.set('q', c.query);
      else params.set('category', 'recent');
      get(host.ROOT + `/api/library?${params}`).then((page) => { if (composing === c) show(page.pieces); }).catch(failed);
    };
    search.addEventListener('input', debounce(() => {
      c.query = search.value.trim();
      load();
    }, 250));
    load();
    return h('div', { class: 'choices' }, list, h('div', { class: 'inset' }, search), choices);
  }

  /** Sends the form's choices; the job shows under JOBS as the tablet takes it. The tablet's refusal comes back in its own words. */
  async function sendCompose() {
    const c = composing;
    const key = composeKey(c);
    if (!c || !c.seed || !key) return;
    c.sending = true;
    renderCompose();
    try {
      await post(host.ROOT + '/api/studio/compose', { pieceId: c.seed.pieceId, mood: c.mood, key: { tonic: key.tonic, minor: key.minor }, bpm: composeBpm(c), minutes: c.minutes });
      if (composing === c) composing = null;
      renderCompose();
      toast('Composing on the tablet. It shows under Jobs.');
    } catch (e) {
      c.sending = false;
      if (e.status && e.status !== 401) {
        c.error = e.message;
        renderCompose();
      } else {
        failed(e);
      }
    }
  }

  return { show: renderStudio, render: renderStudioState, drop: addRecordings };
}
