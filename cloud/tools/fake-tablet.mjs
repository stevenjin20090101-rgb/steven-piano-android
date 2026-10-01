#!/usr/bin/env node
/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// A stand-in tablet for the relay: it speaks steven-piano-relay-1 as the app's RelayClient does
// (M26), with no app and no piano. Node 22 or later; nothing to install.
//
//   node tools/fake-tablet.mjs --new               a code from the local console (npm run dev), then enrol
//   node tools/fake-tablet.mjs --code ABCD-EFGH    enrol with a code from the console
//   node tools/fake-tablet.mjs                     connect again as the piano saved last time
//
//   --relay <url>     the relay (default http://localhost:8787; a deployed one is https://…)
//   --console <url>   the local console, for --new (default http://localhost:8788)
//   --state <file>    where the piano's id and secret are kept (default tools/.fake-tablet.json, git-ignored)
//   --quiet           less logging
//
// What it does: enrols (POST /api/enrol), connects (wss://…/tablet, Bearer <id>.<secret>), says its
// status every 30 s and after a change, and answers the panel's requests from a canned state:
// GET /api/state, POST /api/login (any six digits), POST /api/transport, PUT /api/upload (counted,
// under credits), GET / and /fake.js (a page that shows the socket's messages); anything else 404.
// A browser's socket (/p/<id>/ws) is accepted and gets the state, then progress once a second while
// "playing". Console commands are applied to the canned state; a rotation's secret is saved, then
// acknowledged. Close codes: 4401 revoked and 4403 forgotten stop it; 4409 retries after 60 s;
// anything else reconnects after 1 s, doubling to 5 minutes, with jitter.

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const SUBPROTOCOL = 'steven-piano-relay-1';
const KIND = { ReqChunk: 1, ReqEnd: 2, ResChunk: 3, ResEnd: 4 };
const CHUNK = 64 * 1024;

// ---- Options -------------------------------------------------------------------------------------

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const flag = (name) => args.includes(name);
if (flag('--help') || flag('-h')) {
  console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter((l) => l.startsWith('//')).map((l) => l.slice(3)).join('\n'));
  process.exit(0);
}
const stateFile = option('--state', fileURLToPath(new URL('./.fake-tablet.json', import.meta.url)));
const quiet = flag('--quiet');
const log = (...parts) => console.log(new Date().toISOString().slice(11, 19), ...parts);
const say = (...parts) => {
  if (!quiet) log(...parts);
};

function load() {
  try {
    return JSON.parse(readFileSync(stateFile, 'utf8'));
  } catch {
    return null;
  }
}

function save(saved) {
  writeFileSync(stateFile, JSON.stringify(saved, null, 2) + '\n', { mode: 0o600 });
}

// ---- Enrolment ---------------------------------------------------------------------------------------

async function newCode(consoleUrl) {
  const response = await fetch(`${consoleUrl}/api/enrol-codes`, { method: 'POST', headers: { 'X-Steven-Piano': '1' } });
  if (!response.ok) throw new Error(`The console (${consoleUrl}) answered ${response.status}: ${await response.text()}`);
  const { code } = await response.json();
  return code;
}

async function enrol(relay, code) {
  const body = JSON.stringify({ code, name: 'Fake tablet', model: 'fake-tablet.mjs' });
  const response = await fetch(`${relay}/api/enrol`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
  const answer = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`Enrolment refused (${response.status}): ${answer.message ?? ''}`);
  return answer;
}

// ---- The canned piano ---------------------------------------------------------------------------------

const PIECES = [
  { id: 1, title: 'Clair de lune', composer: 'Claude Debussy', composerShort: 'Debussy', composerKey: 'debussy', durationMs: 301000, favorite: true, art: 'roll' },
  { id: 2, title: 'Arabesque No. 1', composer: 'Claude Debussy', composerShort: 'Debussy', composerKey: 'debussy', durationMs: 262000, favorite: false, art: 'roll' },
  { id: 3, title: 'Nocturne in E-flat major, Op. 9 No. 2', composer: 'Frédéric Chopin', composerShort: 'Chopin', composerKey: 'chopin', durationMs: 274000, favorite: false, art: 'roll' },
];
const CHANNELS = [
  { key: 'debussy', name: 'Debussy' },
  { key: 'chopin', name: 'Chopin' },
];

const piano = {
  status: 'playing',
  index: 0,
  startedAt: Date.now(),
  pausedAt: 0,
  channel: null,
  guests: false,
  approveFirst: true,
  libraryPack: null,
  panelUrl: null,
};

function positionMs() {
  const piece = PIECES[piano.index];
  const elapsed = piano.status === 'playing' ? Date.now() - piano.startedAt : piano.pausedAt;
  return Math.min(Math.max(0, elapsed), piece.durationMs);
}

function playerState() {
  const piece = PIECES[piano.index];
  return {
    status: piano.status,
    loading: false,
    piece: piano.status === 'stopped' ? null : piece,
    positionMs: positionMs(),
    tempoPct: 100,
    transpose: 0,
    velocityPct: 100,
    preRollMs: 0,
    channel: piano.channel ? { ...piano.channel, volume: 100 } : null,
    tablet: { mode: 'off', volume: 50, active: false, installed: false },
    queue: {
      ids: PIECES.map((p) => p.id),
      uids: PIECES.map((p) => p.id * 10),
      index: piano.index,
      shuffle: false,
      repeat: 'off',
      items: PIECES.slice(piano.index).map((p) => ({ ...p, uid: p.id * 10, requested: false })),
    },
    problem: null,
  };
}

/** The panel's state, as WebApi.state builds it. */
function state(type) {
  return {
    ...(type ? { type } : {}),
    player: playerState(),
    link: { state: 'connected', name: 'Steven Piano (fake)' },
    piano: { state: 'ready', values: {}, facts: {}, lastError: null, errorAbout: null },
    import: { running: false, done: 0, total: 0, imported: 0, duplicates: 0, failed: 0, current: null },
    artwork: { running: false, done: 0, total: 0 },
    requests: { pending: 0, guests: piano.guests, approveFirst: piano.approveFirst },
    web: { address: piano.panelUrl, guestAddress: null, guests: piano.guests },
    monochrome: false,
    schedule: { next: null, revision: 0 },
    studio: { available: false, reason: 'Not on the fake tablet.', models: [], jobs: [] },
  };
}

/** The relay's status message. */
function status() {
  const piece = PIECES[piano.index];
  return {
    t: 'status',
    app: { version: '1.10-fake', code: 0 },
    firmware: '2.0.0',
    link: { state: 'connected', name: 'Steven Piano (fake)' },
    player: {
      status: piano.status,
      title: piano.status === 'stopped' ? null : piece.title,
      composer: piano.status === 'stopped' ? null : piece.composer,
      positionMs: positionMs(),
      durationMs: piece.durationMs,
      channel: piano.channel,
    },
    guests: { open: piano.guests, approveFirst: piano.approveFirst },
    panel: { web: false },
    library: { pieces: PIECES.length, pack: piano.libraryPack },
    channels: CHANNELS,
    at: Date.now(),
  };
}

function transport(action) {
  const now = Date.now();
  const pause = () => {
    if (piano.status === 'playing') {
      piano.pausedAt = now - piano.startedAt;
      piano.status = 'paused';
    }
  };
  const resume = () => {
    if (piano.status === 'stopped') piano.pausedAt = 0;
    if (piano.status !== 'playing') {
      piano.startedAt = now - piano.pausedAt;
      piano.status = 'playing';
    }
  };
  const go = (index) => {
    piano.index = (index + PIECES.length) % PIECES.length;
    piano.startedAt = now;
    piano.pausedAt = 0;
    piano.status = 'playing';
  };
  switch (action) {
    case 'toggle':
      piano.status === 'playing' ? pause() : resume();
      return piano.status === 'playing' ? 'Playing.' : 'Paused.';
    case 'pause':
      pause();
      return 'Paused.';
    case 'resume':
      resume();
      return 'Playing.';
    case 'next':
      go(piano.index + 1);
      return `Playing ${PIECES[piano.index].title}.`;
    case 'previous':
      go(piano.index - 1);
      return `Playing ${PIECES[piano.index].title}.`;
    case 'stop':
      piano.status = 'stopped';
      piano.pausedAt = 0;
      piano.channel = null;
      return 'Stopped.';
    default:
      return null;
  }
}

// ---- The connection -----------------------------------------------------------------------------------

let saved = load();
let relay = (option('--relay', saved?.relay ?? 'http://localhost:8787')).replace(/\/+$/, '');
let ws = null;
let backoff = 1000;
const requests = new Map(); // id → { method, path, query, headers, chunks: [], bytes }
const browsers = new Map(); // id → { accepted }
let statusTimer = null;
let progressTimer = null;
let pingTimer = null;

function send(message) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
}

function frame(id, kind, payload) {
  const out = new Uint8Array(5 + (payload ? payload.byteLength : 0));
  new DataView(out.buffer).setUint32(0, id >>> 0, false);
  out[4] = kind;
  if (payload) out.set(payload, 5);
  return out;
}

function respond(id, statusCode, headers, body) {
  const bytes = body === null || body === undefined ? new Uint8Array(0) : typeof body === 'string' ? new TextEncoder().encode(body) : body;
  send({ t: 'res', id, status: statusCode, headers, length: bytes.byteLength });
  for (let offset = 0; offset < bytes.byteLength; offset += CHUNK) ws.send(frame(id, KIND.ResChunk, bytes.subarray(offset, offset + CHUNK)));
  ws.send(frame(id, KIND.ResEnd));
}

const JSON_HEADERS = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
const json = (id, statusCode, value, extra = {}) => respond(id, statusCode, { ...JSON_HEADERS, ...extra }, JSON.stringify(value));
const noContent = (id, extra = {}) => respond(id, 204, { 'Cache-Control': 'no-store', ...extra }, null);

function pageHeaders(host) {
  return {
    'Cache-Control': 'no-cache',
    'Content-Security-Policy': `default-src 'self'; connect-src 'self' ws://${host} wss://${host}; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  };
}

const PAGE = `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Fake tablet</title></head>
<body>
<h1>Fake tablet</h1>
<p>The relay carried this page from tools/fake-tablet.mjs. The panel's socket, as it arrives:</p>
<pre id="log">Connecting…</pre>
<script src="fake.js"></script>
</body>
</html>
`;

const SCRIPT = `'use strict';
(function () {
  var log = document.getElementById('log');
  var root = location.pathname.replace(/[^/]*$/, '');
  var ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + root + 'ws');
  var lines = [];
  function show(line) { lines.unshift(line); lines.length = Math.min(lines.length, 20); log.textContent = lines.join('\\n'); }
  ws.onopen = function () { show('open'); };
  ws.onmessage = function (e) { var m = JSON.parse(e.data); show(m.type + (m.type === 'state' ? ' · ' + (m.player.piece ? m.player.piece.title : 'nothing') + ' · ' + m.player.status : ' · ' + m.positionMs + ' ms')); };
  ws.onclose = function (e) { show('closed ' + e.code + ' ' + e.reason); };
})();
`;

function handle(req, body) {
  const { id, method, path } = req;
  say(`req ${method} ${path}${req.query ? '?' + req.query : ''} from ${req.address}`);
  const route = `${method} ${path}`;
  switch (route) {
    case 'GET /':
      return respond(id, 200, { 'Content-Type': 'text/html; charset=utf-8', ...pageHeaders(req.headers.host) }, PAGE);
    case 'GET /fake.js':
      return respond(id, 200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-cache' }, SCRIPT);
    case 'GET /api/state':
      return json(id, 200, state());
    case 'POST /api/login': {
      let pin = '';
      try {
        pin = String(JSON.parse(new TextDecoder().decode(body)).pin ?? '');
      } catch {
        // Not JSON.
      }
      if (!/^\d{6}$/.test(pin)) return json(id, 401, { error: 'pin', message: "That PIN isn't right." });
      const token = crypto.randomUUID();
      return noContent(id, { 'Set-Cookie': `sp_session=${token}; HttpOnly; Secure; SameSite=Strict; Path=${req.prefix}/` });
    }
    case 'POST /api/transport': {
      let action = null;
      try {
        action = JSON.parse(new TextDecoder().decode(body)).action;
      } catch {
        // Not JSON.
      }
      if (transport(action) === null) return json(id, 400, { error: 'field', message: 'action must be toggle, pause, resume, next, previous or stop.' });
      changed();
      return noContent(id);
    }
    case 'PUT /api/upload': {
      const name = new URLSearchParams(req.query).get('name') ?? 'upload';
      say(`upload ${name}: ${body.byteLength ?? body} bytes`);
      return json(id, 202, { name });
    }
    default:
      return json(id, 404, { error: 'not-found', message: 'Not here.' });
  }
}

function changed() {
  send(status());
  for (const [id, b] of browsers) if (b.accepted) send({ t: 'ws.text', id, data: JSON.stringify(state('state')) });
}

function command(message) {
  const { id, name, args } = message;
  let ok = true;
  let text = 'Done.';
  switch (name) {
    case 'transport':
      text = transport(args?.action) ?? 'No such action.';
      ok = text !== 'No such action.';
      break;
    case 'play': {
      const index = PIECES.findIndex((p) => p.id === args?.pieceId);
      if (index < 0) {
        ok = false;
        text = 'No such piece.';
      } else {
        piano.index = index;
        piano.startedAt = Date.now();
        piano.pausedAt = 0;
        piano.status = 'playing';
        text = `Playing ${PIECES[index].title}.`;
      }
      break;
    }
    case 'playChannel': {
      const channel = CHANNELS.find((c) => c.key === args?.key);
      if (!channel) {
        ok = false;
        text = 'No such channel.';
      } else {
        piano.channel = channel;
        piano.status = 'playing';
        text = `Playing the ${channel.name} channel.`;
      }
      break;
    }
    case 'stopChannel':
      piano.channel = null;
      text = 'The channel stopped.';
      break;
    case 'guests':
      if (typeof args?.open === 'boolean') piano.guests = args.open;
      if (typeof args?.approveFirst === 'boolean') piano.approveFirst = args.approveFirst;
      text = piano.guests ? 'Guests can request.' : 'Guests cannot request.';
      break;
    case 'library.load':
      piano.libraryPack = 1;
      text = 'Loading Steven’s library (not really: this is the fake tablet).';
      break;
    case 'status':
      text = 'Status sent.';
      break;
    default:
      ok = false;
      text = 'Unknown command.';
  }
  log(`cmd ${name} ${JSON.stringify(args ?? {})} → ${text}`);
  send({ t: 'cmd.result', id, ok, message: text });
  changed();
}

function onText(text) {
  if (text === 'pong') return;
  let m;
  try {
    m = JSON.parse(text);
  } catch {
    return;
  }
  switch (m.t) {
    case 'hello':
      backoff = 1000;
      piano.panelUrl = `${relay.startsWith('https:') ? 'https' : 'http'}://${m.host}${m.prefix}/`;
      log(`connected as ${m.pianoId}. The panel: ${piano.panelUrl}`);
      log(`try: curl ${piano.panelUrl}api/state`);
      send(status());
      return;
    case 'req': {
      if (!m.body) return handle(m, new Uint8Array(0));
      requests.set(m.id, { req: m, chunks: [], bytes: 0 });
      return;
    }
    case 'req.abort':
      if (requests.delete(m.id)) say(`req ${m.id} aborted`);
      return;
    case 'ws.open':
      browsers.set(m.id, { accepted: true });
      say(`browser socket ${m.id} from ${m.address}`);
      send({ t: 'ws.accept', id: m.id });
      send({ t: 'ws.text', id: m.id, data: JSON.stringify(state('state')) });
      return;
    case 'ws.close':
      browsers.delete(m.id);
      say(`browser socket ${m.id} closed (${m.code})`);
      return;
    case 'cmd':
      return command(m);
    case 'secret':
      saved = { ...saved, secret: m.secret };
      save(saved);
      log('a new secret from the console: saved, acknowledged');
      send({ t: 'secret.ack' });
      return;
  }
}

function onBinary(data) {
  const bytes = new Uint8Array(data);
  if (bytes.byteLength < 5) return;
  const id = new DataView(bytes.buffer, bytes.byteOffset).getUint32(0, false);
  const kind = bytes[4];
  const pending = requests.get(id);
  if (!pending) return;
  if (kind === KIND.ReqChunk) {
    const payload = bytes.subarray(5);
    pending.bytes += payload.byteLength;
    // Uploads are counted, not kept; small bodies are kept for the handler.
    if (pending.bytes <= 64 * 1024) pending.chunks.push(payload.slice());
    send({ t: 'req.credit', id, bytes: payload.byteLength });
  } else if (kind === KIND.ReqEnd) {
    requests.delete(id);
    const small = pending.bytes <= 64 * 1024;
    const body = small ? Buffer.concat(pending.chunks) : { byteLength: pending.bytes };
    handle(pending.req, body);
  }
}

function connect() {
  const url = `${relay.replace(/^http/, 'ws')}/tablet`;
  say(`connecting to ${url}`);
  const socket = new WebSocket(url, { protocols: [SUBPROTOCOL], headers: { Authorization: `Bearer ${saved.pianoId}.${saved.secret}` } });
  socket.binaryType = 'arraybuffer';
  ws = socket;
  socket.addEventListener('open', () => {
    statusTimer = setInterval(() => send(status()), 30_000);
    progressTimer = setInterval(() => {
      if (piano.status !== 'playing') return;
      for (const [id, b] of browsers) if (b.accepted) send({ t: 'ws.text', id, data: JSON.stringify({ type: 'progress', positionMs: positionMs(), at: Date.now() }) });
    }, 1000);
    pingTimer = setInterval(() => socket.readyState === WebSocket.OPEN && socket.send('ping'), 25_000);
  });
  socket.addEventListener('message', (event) => (typeof event.data === 'string' ? onText(event.data) : onBinary(event.data)));
  socket.addEventListener('error', () => undefined);
  socket.addEventListener('close', (event) => {
    clearInterval(statusTimer);
    clearInterval(progressTimer);
    clearInterval(pingTimer);
    requests.clear();
    browsers.clear();
    if (ws !== socket) return;
    ws = null;
    if (event.code === 4401 || event.code === 4403) {
      log(`closed ${event.code} (${event.reason || (event.code === 4401 ? 'revoked' : 'forgotten')}): enrol again to come back.`);
      process.exit(1);
    }
    const wait = event.code === 4409 ? 60_000 : Math.round(backoff * (0.8 + Math.random() * 0.4));
    if (event.code !== 4409) backoff = Math.min(backoff * 2, 300_000);
    log(`closed ${event.code}${event.reason ? ` (${event.reason})` : ''}; again in ${Math.round(wait / 1000)} s`);
    setTimeout(connect, wait);
  });
}

async function main() {
  const code = option('--code', null);
  if (flag('--new') || code) {
    const theCode = code ?? (await newCode(option('--console', 'http://localhost:8788').replace(/\/+$/, '')));
    log(`enrolling with ${theCode} at ${relay}`);
    const answer = await enrol(relay, theCode);
    saved = { relay, pianoId: answer.pianoId, secret: answer.secret };
    save(saved);
    log(`enrolled: piano ${answer.pianoId}; its panel at ${answer.panelUrl} (credentials in ${stateFile})`);
  } else if (!saved?.pianoId || !saved?.secret) {
    console.error('No piano yet: run with --new (the local console makes a code) or --code XXXX-XXXX.');
    process.exit(2);
  }
  connect();
}

process.on('SIGINT', () => {
  if (ws) ws.close(1000, 'The fake tablet stopped.');
  setTimeout(() => process.exit(0), 200).unref();
});

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
