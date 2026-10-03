/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

/**
 * The relay protocol between a tablet and its PianoRoom (`steven-piano-relay-1`), as the tablet
 * (M26, `RelayProtocol.kt`) speaks it:
 *
 * - `wss://<relay>/tablet`, `Authorization: Bearer <pianoId>.<secret>`, subprotocol
 *   [SUBPROTOCOL]. Text frames are JSON of at most [MAX_TEXT_FRAME] bytes; binary frames are
 *   `id: u32 big-endian | kind: u8 | payload` of at most [CHUNK] bytes ([Kind]).
 * - The room says `hello` on accept; the tablet sends `status` every 30 s and after changes.
 * - A browser's request is `req` (+ `req.chunk`s under a credit window, then `req.end`); the answer
 *   is `res` + `res.chunk`s + `res.end`. `req.credit` grants more body; `req.abort` gives up.
 * - A browser's socket is bridged: `ws.open` → `ws.accept` | `ws.refuse`, `ws.text`, `ws.close`.
 * - The console: `cmd` → `cmd.result`. Rotation: `secret` → `secret.ack`.
 */

export const SUBPROTOCOL = 'steven-piano-relay-1';

/** A text frame's JSON, at most. */
export const MAX_TEXT_FRAME = 64 * 1024;

/** A binary frame's payload, at most; the room cuts request bodies to it. */
export const CHUNK = 64 * 1024;

/** The request body the room may send before the tablet grants more (`req.credit`). */
export const WINDOW = 1024 * 1024;

/** The Free plan's request body limit (`vars.MAX_BODY_BYTES`). */
export const DEFAULT_MAX_BODY = 104_857_600;

/** A request body larger than a JSON body may be (the tablet's 64 KB) is an upload: one at a time. */
export const UPLOAD_OVER = 64 * 1024;

/**
 * Pictures (v1.18 — M47b): a panel's GETs under `/api/art/` a minute from one address, on a limit of their own
 * (wrangler.relay.jsonc › ART_LIMIT, the same number), so a page of covers never spends the panel's 120. Every answer
 * the room relays says it in [ART_LIMIT_HEADER], and the page widens its picture budget to it.
 */
export const ART_LIMIT_PER_MINUTE = 600;
export const ART_LIMIT_HEADER = 'X-Relay-Art-Limit';

/** Binary frame kinds. */
export const Kind = {
  ReqChunk: 1,
  ReqEnd: 2,
  ResChunk: 3,
  ResEnd: 4,
} as const;
export type Kind = (typeof Kind)[keyof typeof Kind];

/** Close codes the room sends the tablet. */
export const Close = {
  /** Revoked from the console: stop retrying. */
  Revoked: 4401,
  /** Forgotten (disabled) from the console: stop retrying. */
  Disabled: 4403,
  /** A newer connection with the same id took over: retry after 60 s. */
  Replaced: 4409,
} as const;

/** Browser sockets: the tablet refused this one. */
export const CLOSE_POLICY = 1008;

/** The headers of a browser's request the tablet is shown, and no others. */
export const FORWARDED_HEADERS = ['host', 'cookie', 'origin', 'content-type', 'content-length', 'x-steven-piano', 'accept'] as const;

/** The headers of a browser's socket the tablet is shown. */
export const SOCKET_HEADERS = ['cookie', 'origin', 'host'] as const;

// ---- Binary frames ----------------------------------------------------------------------------

/** `id: u32 BE | kind: u8 | payload`. */
export function encodeFrame(id: number, kind: Kind, payload?: Uint8Array): Uint8Array {
  const length = payload ? payload.byteLength : 0;
  if (length > CHUNK) throw new RangeError(`A frame's payload is ${CHUNK} bytes at most.`);
  const frame = new Uint8Array(5 + length);
  new DataView(frame.buffer).setUint32(0, id >>> 0, false);
  frame[4] = kind;
  if (payload && length > 0) frame.set(payload, 5);
  return frame;
}

export interface Frame {
  id: number;
  kind: number;
  payload: Uint8Array;
}

/** A frame, or null when it is shorter than its head or its payload is over [CHUNK]. */
export function decodeFrame(data: ArrayBuffer | ArrayBufferView): Frame | null {
  const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (bytes.byteLength < 5 || bytes.byteLength > 5 + CHUNK) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { id: view.getUint32(0, false), kind: bytes[4]!, payload: bytes.subarray(5) };
}

// ---- Messages ---------------------------------------------------------------------------------

export interface Hello {
  t: 'hello';
  pianoId: string;
  host: string;
  prefix: string;
  caps: { maxBody: number; chunk: number; window: number };
  at: number;
}

export interface ReqMessage {
  t: 'req';
  id: number;
  method: string;
  /** The path after the prefix, still percent-encoded. */
  path: string;
  /** The query without its `?`, still percent-encoded ("" when none). */
  query: string;
  headers: Record<string, string>;
  /** The browser's address (`CF-Connecting-IP`). */
  address: string;
  prefix: string;
  body: boolean;
}

export interface ResMessage {
  t: 'res';
  id: number;
  status: number;
  headers: Record<string, unknown>;
  /** The body's length in bytes; absent or -1 when it isn't known. */
  length?: number;
}

/** The console's commands and their arguments (checked in [checkCommand]). */
export const COMMANDS = ['transport', 'play', 'playChannel', 'stopChannel', 'guests', 'library.load', 'status'] as const;
export type CommandName = (typeof COMMANDS)[number];

const TRANSPORT_ACTIONS = ['toggle', 'pause', 'resume', 'next', 'previous', 'stop'];

export type CommandCheck = { ok: true; name: CommandName; args: Record<string, unknown> } | { ok: false; message: string };

/** A console command, if it is one of [COMMANDS] with exactly its arguments; nothing else reaches a tablet. */
export function checkCommand(name: unknown, rawArgs: unknown): CommandCheck {
  if (typeof name !== 'string' || !(COMMANDS as readonly string[]).includes(name)) return { ok: false, message: "That isn't a command the console can send." };
  const args = rawArgs === undefined || rawArgs === null ? {} : rawArgs;
  if (typeof args !== 'object' || Array.isArray(args)) return { ok: false, message: 'The arguments must be an object.' };
  const given = args as Record<string, unknown>;
  const only = (keys: string[]): string | null => Object.keys(given).find((k) => !keys.includes(k)) ?? null;
  const unknownKey = (keys: string[]) => {
    const extra = only(keys);
    return extra === null ? null : `${name} doesn't take "${extra.slice(0, 40)}".`;
  };
  switch (name as CommandName) {
    case 'transport': {
      const bad = unknownKey(['action']);
      if (bad) return { ok: false, message: bad };
      if (typeof given.action !== 'string' || !TRANSPORT_ACTIONS.includes(given.action)) {
        return { ok: false, message: `action must be one of ${TRANSPORT_ACTIONS.join(', ')}.` };
      }
      return { ok: true, name: 'transport', args: { action: given.action } };
    }
    case 'play': {
      const bad = unknownKey(['pieceId']);
      if (bad) return { ok: false, message: bad };
      const id = given.pieceId;
      if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) return { ok: false, message: 'pieceId must be a whole number above 0.' };
      return { ok: true, name: 'play', args: { pieceId: id } };
    }
    case 'playChannel': {
      const bad = unknownKey(['key']);
      if (bad) return { ok: false, message: bad };
      const key = given.key;
      if (typeof key !== 'string' || key.length === 0 || key.length > 64 || hasControl(key)) return { ok: false, message: 'key must name a channel.' };
      return { ok: true, name: 'playChannel', args: { key } };
    }
    case 'guests': {
      const bad = unknownKey(['open', 'approveFirst']);
      if (bad) return { ok: false, message: bad };
      const out: Record<string, unknown> = {};
      for (const k of ['open', 'approveFirst']) {
        if (given[k] === undefined) continue;
        if (typeof given[k] !== 'boolean') return { ok: false, message: `${k} must be true or false.` };
        out[k] = given[k];
      }
      if (Object.keys(out).length === 0) return { ok: false, message: 'guests needs open or approveFirst.' };
      return { ok: true, name: 'guests', args: out };
    }
    case 'stopChannel':
    case 'library.load':
    case 'status': {
      const bad = unknownKey([]);
      if (bad) return { ok: false, message: bad };
      return { ok: true, name: name as CommandName, args: {} };
    }
  }
}

// ---- The tablet's status ------------------------------------------------------------------------

/** What the relay keeps of a tablet's `status`: known fields only, strings cut, numbers bounded; no device ids, no address. */
export interface TabletStatus {
  app: { version: string | null; code: number | null } | null;
  firmware: string | null;
  link: { state: string | null; name: string | null } | null;
  /**
   * What plays and what is played from (v1.11 — M29): the instrument's kind ("steven" or "midi") and state, the
   * keyboard's transport and state, whether Live and a take are on. Never a device's name: a name may say whose it is.
   */
  instruments: {
    instrument: { kind: string | null; state: string | null } | null;
    keyboard: { transport: string | null; state: string | null } | null;
    live: boolean;
    recording: boolean;
  } | null;
  player: {
    status: string | null;
    title: string | null;
    composer: string | null;
    positionMs: number | null;
    durationMs: number | null;
    channel: { key: string | null; name: string | null } | null;
  } | null;
  guests: { open: boolean; approveFirst: boolean } | null;
  /** Whether the tablet's own Web control is on. Never its address there (audit delta 3: the relay has no use for it). */
  panel: { web: boolean } | null;
  library: { pieces: number | null; pack: number | null } | null;
  /** The tablet's channels, when it names them (the console's Channels list). */
  channels: Array<{ key: string; name: string }> | null;
  at: number | null;
}

const TEXT = 200;

export function sanitizeStatus(raw: unknown): TabletStatus | null {
  if (!isObject(raw)) return null;
  const app = isObject(raw.app) ? { version: text(raw.app.version, 40), code: whole(raw.app.code) } : null;
  const link = typeof raw.link === 'string'
    ? { state: text(raw.link, 40), name: null }
    : isObject(raw.link) ? { state: text(raw.link.state, 40), name: text(raw.link.name, 80) } : null;
  let player: TabletStatus['player'] = null;
  if (isObject(raw.player)) {
    const p = raw.player;
    const channel = typeof p.channel === 'string'
      ? { key: text(p.channel, 64), name: null }
      : isObject(p.channel) ? { key: text(p.channel.key, 64), name: text(p.channel.name, 80) } : null;
    player = {
      status: text(p.status, 24),
      title: text(p.title, TEXT),
      composer: text(p.composer, TEXT),
      positionMs: bounded(p.positionMs),
      durationMs: bounded(p.durationMs),
      channel,
    };
  }
  let instruments: TabletStatus['instruments'] = null;
  if (isObject(raw.instruments)) {
    const i = raw.instruments;
    instruments = {
      instrument: isObject(i.instrument) ? { kind: text(i.instrument.kind, 16), state: text(i.instrument.state, 24) } : null,
      keyboard: isObject(i.keyboard) ? { transport: text(i.keyboard.transport, 16), state: text(i.keyboard.state, 24) } : null,
      live: i.live === true,
      recording: i.recording === true,
    };
  }
  const guests = isObject(raw.guests) ? { open: raw.guests.open === true, approveFirst: raw.guests.approveFirst === true } : null;
  const panel = isObject(raw.panel) ? { web: raw.panel.web === true } : null;
  const library = isObject(raw.library) ? { pieces: whole(raw.library.pieces), pack: whole(raw.library.pack) } : null;
  let channels: TabletStatus['channels'] = null;
  if (Array.isArray(raw.channels)) {
    channels = [];
    for (const c of raw.channels.slice(0, 32)) {
      if (!isObject(c)) continue;
      const key = text(c.key, 64);
      if (!key) continue;
      channels.push({ key, name: text(c.name, 80) ?? key });
    }
  }
  return { app, firmware: text(raw.firmware, 40), link, instruments, player, guests, panel, library, channels, at: whole(raw.at) };
}

// ---- Small checks -------------------------------------------------------------------------------

export function isObject(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Control characters (C0, DEL, C1). */
export function hasControl(value: string): boolean {
  return /[\u0000-\u001f\u007f-\u009f]/.test(value);
}

/** A string without control characters, cut to [max] characters; null when it isn't a string or is empty. */
export function text(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').trim();
  if (clean.length === 0) return null;
  return clean.length > max ? clean.slice(0, max) : clean;
}

function whole(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : null;
}

function bounded(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) < 1e12 ? Math.round(value) : null;
}
