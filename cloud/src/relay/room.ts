/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { DurableObject } from 'cloudflare:workers';
import type { RelayEnv } from '../env';
import { auditStatement } from '../shared/db';
import { constantTimeEqual, sha256Hex } from '../shared/hash';
import { PIANO_ID, PENDING_LIFE_MS, newSecret, randomU32 } from '../shared/ids';
import { API_CSP, error, withSecurity } from '../shared/http';
import {
  ART_LIMIT_HEADER,
  ART_LIMIT_PER_MINUTE,
  CHUNK,
  CLOSE_POLICY,
  Close,
  DEFAULT_MAX_BODY,
  FORWARDED_HEADERS,
  Kind,
  MAX_TEXT_FRAME,
  SOCKET_HEADERS,
  SUBPROTOCOL,
  UPLOAD_OVER,
  WINDOW,
  checkCommand,
  decodeFrame,
  encodeFrame,
  isObject,
  sanitizeStatus,
  text,
  type Hello,
  type ReqMessage,
  type TabletStatus,
} from '../shared/protocol';
import { offline } from './offline';

/** Requests the tablet works on at once; more wait, up to [MAX_QUEUED], then 503 busy. */
export const MAX_IN_FLIGHT = 8;
export const MAX_QUEUED = 32;
/** Browser sockets per piano (the panel's live updates). */
export const MAX_BROWSERS = 4;
/** From the request (or the end of its body) to the tablet's `res`. */
export const RES_TIMEOUT_MS = 15_000;
/** The most a body may sit still: the browser not sending, or the tablet not granting credit; and between a response's chunks. */
export const IDLE_MS = 30_000;
/** An upload's whole body. */
export const UPLOAD_MS = 10 * 60_000;
/** How long a request may wait for a free place. */
export const QUEUE_WAIT_MS = 15_000;
/** How long a browser's socket waits for the tablet's `ws.accept`, and a command for its result. */
export const ANSWER_MS = 15_000;
/** The status reaches D1 at most this often. */
export const D1_EVERY_MS = 60_000;
/** A response's bytes the browser hasn't taken yet, at most. */
const MAX_UNFLUSHED = 8 * 1024 * 1024;
/** A browser's frame (which is dropped), at most; a larger one closes its socket. */
const BROWSER_FRAME_MAX = 4096;
const OPEN = 1;

interface TabletAttachment {
  role: 'tablet';
  pianoId: string;
  /** One per connection: requests and browser sockets belong to the connection they began on. */
  session: number;
  connectedAt: number;
}

interface BrowserAttachment {
  role: 'browser';
  wsId: number;
  session: number;
  accepted: boolean;
  /** Closed by the room itself: the tablet is not told. */
  dropped: boolean;
  openedAt: number;
}

type Attachment = TabletAttachment | BrowserAttachment;

/** What survives an eviction besides the sockets: the latest status and when D1 last had it. */
interface Stored {
  status: TabletStatus | null;
  statusAt: number | null;
  lastD1Write: number;
}

interface Pending {
  id: number;
  session: number;
  tablet: WebSocket;
  method: string;
  api: boolean;
  upload: boolean;
  finished: boolean;
  /** The answer the browser is waiting for, until it is given. */
  answer: ((response: Response) => void) | null;
  writer: WritableStreamDefaultWriter<Uint8Array> | null;
  discard: boolean;
  unflushed: number;
  reader: ReadableStreamDefaultReader<Uint8Array> | null;
  credit: number;
  creditWaiter: (() => void) | null;
  timer: ReturnType<typeof setTimeout> | null;
}

export interface LiveStatus {
  online: boolean;
  connectedAt: number | null;
  status: TabletStatus | null;
  statusAt: number | null;
  browsers: number;
  requests: number;
}

export interface CommandResult {
  ok: boolean;
  message: string;
}

export interface RotateResult extends CommandResult {
  committed: boolean;
}

/**
 * One piano's room (a Durable Object per piano, named by its id): its tablet's socket, the browsers'
 * requests and sockets it carries to the tablet, and the console's commands.
 *
 * Hibernation-safe: the sockets are accepted with `ctx.acceptWebSocket` (tags `tablet`, `browser`)
 * and carry their role and ids as attachments; the latest status lives in storage. Requests in
 * flight live in memory only (a request holds the room awake; a restart answers them 502).
 */
export class PianoRoom extends DurableObject<RelayEnv> {
  /** Now, in milliseconds (a test may replace it). */
  clock: () => number = () => Date.now();
  /** Status writes to D1 since this instance began (the throttle's test reads it). */
  d1StatusWrites = 0;
  /** The room's deadlines (a test may shorten them). */
  timing = { res: RES_TIMEOUT_MS, idle: IDLE_MS, upload: UPLOAD_MS, queue: QUEUE_WAIT_MS, answer: ANSWER_MS };

  private stored: Stored = { status: null, statusAt: null, lastD1Write: 0 };
  private storedPianoId: string | null = null;
  private nextId = randomU32();
  private readonly pending = new Map<number, Pending>();
  /** Requests over lately: their late frames are dropped without a `req.abort`. */
  private readonly recent: number[] = [];
  private readonly commands = new Map<number, { session: number; resolve: (r: CommandResult) => void }>();
  private rotationWaiter: ((committed: boolean) => void) | null = null;
  private inFlight = 0;
  private readonly waiting: Array<{ grant: () => void }> = [];
  private uploading = false;
  private readonly acceptTimers = new Map<number, ReturnType<typeof setTimeout>>();
  /** Moves on at every revoke and forget: a connection checked before one is refused, not accepted. */
  private authEpoch = 0;

  constructor(ctx: DurableObjectState, env: RelayEnv) {
    super(ctx, env);
    // A text "ping" from anyone is answered "pong" without waking the room.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    void ctx.blockConcurrencyWhile(async () => {
      const stored = await ctx.storage.get<Stored>('state');
      if (stored) this.stored = stored;
      this.storedPianoId = (await ctx.storage.get<string>('pianoId')) ?? null;
    });
  }

  // ---- Entry points ------------------------------------------------------------------------------

  /** From the relay Worker only: `/tablet`, `/ws` (a browser's socket) and `/http` (a browser's request). */
  override async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === '/tablet') return this.connectTablet(request);
    if (path === '/ws') return this.connectBrowser(request);
    if (path === '/http') return this.forward(request);
    return error(404, 'not-found', 'Not here.');
  }

  /** The console's view of the piano, live. */
  async status(pianoId: string): Promise<LiveStatus> {
    this.pianoId(pianoId);
    const tablet = this.tablet();
    return {
      online: tablet !== null,
      connectedAt: tablet ? tabletAttachment(tablet).connectedAt : null,
      status: this.stored.status,
      statusAt: this.stored.statusAt,
      browsers: this.browsers().length,
      requests: this.inFlight,
    };
  }

  /** A console command, if it is on the allow-list; the tablet's answer, or why there is none. */
  async command(pianoId: string, name: unknown, args: unknown, actor: string): Promise<CommandResult> {
    const id = this.pianoId(pianoId);
    const check = checkCommand(name, args);
    if (!check.ok) return { ok: false, message: check.message };
    const tablet = this.tablet();
    let result: CommandResult;
    if (!tablet) {
      result = { ok: false, message: 'The piano is offline.' };
    } else {
      const cmdId = this.newId();
      const session = tabletAttachment(tablet).session;
      result = await new Promise<CommandResult>((resolve) => {
        const timer = setTimeout(() => {
          this.commands.delete(cmdId);
          resolve({ ok: false, message: "The piano didn't answer." });
        }, this.timing.answer);
        const waiter = {
          session,
          resolve: (r: CommandResult) => {
            clearTimeout(timer);
            resolve(r);
          },
        };
        this.commands.set(cmdId, waiter);
        try {
          tablet.send(JSON.stringify({ t: 'cmd', id: cmdId, name: check.name, args: check.args }));
        } catch {
          this.commands.delete(cmdId);
          waiter.resolve({ ok: false, message: 'The piano went offline.' });
        }
      });
    }
    await auditStatement(this.env.DB, { at: this.now(), actor, pianoId: id, action: 'command', detail: { name: check.name, args: check.args, ok: result.ok, message: result.message } }).run();
    return result;
  }

  /**
   * Two-phase rotation: the new secret's hash is kept as pending (accepted for 10 minutes beside the
   * old one), the secret goes to the tablet, and its `secret.ack` makes it the only one.
   */
  async rotate(pianoId: string, actor: string): Promise<RotateResult> {
    const id = this.pianoId(pianoId);
    const tablet = this.tablet();
    if (!tablet) return { ok: false, committed: false, message: 'The piano is offline: a new secret can only go to a connected tablet.' };
    const now = this.now();
    const row = await this.env.DB.prepare('SELECT secret_hash, pending_until FROM pianos WHERE id = ?').bind(id).first<{ secret_hash: string | null; pending_until: number | null }>();
    if (!row || !row.secret_hash) return { ok: false, committed: false, message: 'This piano has no secret to rotate.' };
    if (row.pending_until !== null && row.pending_until > now) {
      return { ok: false, committed: false, message: 'A rotation is already waiting for the tablet. Try again in a few minutes.' };
    }
    const secret = newSecret();
    const hash = await sha256Hex(secret);
    await this.env.DB.batch([
      this.env.DB.prepare('UPDATE pianos SET pending_secret_hash = ?, pending_until = ? WHERE id = ?').bind(hash, now + PENDING_LIFE_MS, id),
      auditStatement(this.env.DB, { at: now, actor, pianoId: id, action: 'rotate' }),
    ]);
    const committed = await new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => settle(false), this.timing.answer);
      const settle = (value: boolean) => {
        clearTimeout(timer);
        if (this.rotationWaiter === settle) this.rotationWaiter = null;
        resolve(value);
      };
      this.rotationWaiter = settle;
      try {
        tablet.send(JSON.stringify({ t: 'secret', secret }));
      } catch {
        settle(false);
      }
    });
    return committed
      ? { ok: true, committed: true, message: 'The tablet has its new secret; the old one no longer works.' }
      : { ok: true, committed: false, message: "Sent. The tablet hasn't confirmed yet; until it does, for 10 minutes, its old secret works too." };
  }

  /** Revoke: no secret is accepted any more, and the tablet is sent away (4401). */
  async revoke(pianoId: string, actor: string): Promise<CommandResult> {
    const id = this.pianoId(pianoId);
    const now = this.now();
    this.authEpoch++;
    await this.env.DB.batch([
      this.env.DB.prepare('UPDATE pianos SET secret_hash = NULL, pending_secret_hash = NULL, pending_until = NULL, revoked_at = ?, online = 0 WHERE id = ?').bind(now, id),
      auditStatement(this.env.DB, { at: now, actor, pianoId: id, action: 'revoke' }),
    ]);
    this.sendTabletsAway(Close.Revoked, 'Revoked from the console.', "The piano's access was revoked.");
    return { ok: true, message: 'Revoked. The tablet was disconnected and needs a new enrolment to come back.' };
  }

  /** Forget: the tablet is sent away (4403), the piano's rows, codes and this room's storage go. */
  async forget(pianoId: string, actor: string): Promise<CommandResult> {
    const id = this.pianoId(pianoId);
    const now = this.now();
    this.authEpoch++;
    this.sendTabletsAway(Close.Disabled, 'Removed from the console.', 'The piano was removed.');
    await this.env.DB.batch([
      this.env.DB.prepare('DELETE FROM enrol_codes WHERE piano_id = ?').bind(id),
      this.env.DB.prepare('DELETE FROM pianos WHERE id = ?').bind(id),
      auditStatement(this.env.DB, { at: now, actor, pianoId: id, action: 'forget' }),
    ]);
    await this.ctx.storage.deleteAll();
    this.stored = { status: null, statusAt: null, lastD1Write: 0 };
    this.storedPianoId = null;
    return { ok: true, message: 'Forgotten.' };
  }

  // ---- The tablet ----------------------------------------------------------------------------------

  private async connectTablet(request: Request): Promise<Response> {
    const h = request.headers;
    if (h.get('upgrade')?.toLowerCase() !== 'websocket') return error(426, 'upgrade', 'This address takes a WebSocket.');
    const given = h.get('x-relay-piano');
    if (!given || !PIANO_ID.test(given)) return error(400, 'piano', 'No piano named.');
    const pianoId = this.pianoId(given);
    const hash = h.get('x-relay-secret-hash') ?? '';
    const now = this.now();
    const epoch = this.authEpoch;
    const row = await this.env.DB.prepare('SELECT secret_hash, pending_secret_hash, pending_until FROM pianos WHERE id = ?')
      .bind(pianoId)
      .first<{ secret_hash: string | null; pending_secret_hash: string | null; pending_until: number | null }>();
    const current = !!row?.secret_hash && constantTimeEqual(hash, row.secret_hash);
    const pending = !current && !!row?.pending_secret_hash && (row.pending_until ?? 0) > now && constantTimeEqual(hash, row.pending_secret_hash);
    if (!row || (!current && !pending)) return error(401, 'auth', "This tablet isn't enrolled here, or its access was revoked.");
    // A tablet that comes back with the rotation's new secret has it: the rotation is done.
    if (pending) {
      await this.commitRotation(pianoId, row.pending_secret_hash!, now, 'tablet');
      this.rotationWaiter?.(true);
    }
    // Revoked or forgotten while this was checked: the tablet asks again, and is refused then.
    if (epoch !== this.authEpoch) return error(503, 'busy', 'Try again.');

    if (this.ctx.getWebSockets('tablet').some((ws) => ws.readyState === OPEN)) {
      await auditStatement(this.env.DB, { at: now, actor: 'tablet', pianoId, action: 'replaced', detail: { address: h.get('x-relay-address') } }).run();
      // Audit delta 3: a revoke or a forget may have come while the note was written (D1 is I/O: the room
      // takes other events meanwhile). Looked at again here, and no await from here to the accept.
      if (epoch !== this.authEpoch) return error(503, 'busy', 'Try again.');
    }
    // Every older connection gives way, one accepted while this one waited included: the newest stays.
    for (const ws of this.ctx.getWebSockets('tablet')) {
      this.dropSession(tabletAttachment(ws).session, 1012, 'The piano reconnected.');
      closeQuietly(ws, Close.Replaced, 'Replaced by a newer connection.');
    }

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(server, ['tablet']);
    const attachment: TabletAttachment = { role: 'tablet', pianoId, session: randomU32(), connectedAt: now };
    server.serializeAttachment(attachment);
    if (this.storedPianoId !== pianoId) {
      this.storedPianoId = pianoId;
      await this.ctx.storage.put('pianoId', pianoId);
    }
    const hello: Hello = {
      t: 'hello',
      pianoId,
      host: this.env.PUBLIC_HOST || h.get('x-relay-host') || '',
      prefix: `/p/${pianoId}`,
      caps: { maxBody: this.maxBody(), chunk: CHUNK, window: WINDOW },
      at: now,
    };
    server.send(JSON.stringify(hello));
    // Only a piano that still has a secret is marked online: a revoke landing from here on closes this socket
    // (it is accepted now), and its "online = 0" is not undone by this write arriving after it (audit delta 3).
    await this.env.DB.prepare('UPDATE pianos SET online = 1, last_seen = ? WHERE id = ? AND secret_hash IS NOT NULL').bind(now, pianoId).run();
    return new Response(null, { status: 101, webSocket: client, headers: { 'Sec-WebSocket-Protocol': SUBPROTOCOL } });
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      await this.onMessage(ws, message);
    } catch {
      // The database failed us (a status, a confirmation): the next one tries again.
    }
  }

  private async onMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    if (!attachment) return;
    if (attachment.role === 'browser') return this.fromBrowser(ws, message);
    if (typeof message !== 'string') return this.fromTabletFrame(attachment, message);
    if (message.length > MAX_TEXT_FRAME) return;
    let msg: unknown;
    try {
      msg = JSON.parse(message);
    } catch {
      return;
    }
    if (!isObject(msg) || typeof msg.t !== 'string') return;
    switch (msg.t) {
      case 'status':
        return this.onStatus(attachment, msg);
      case 'res':
        return this.onRes(attachment, msg);
      case 'req.credit':
        return this.onCredit(attachment, msg);
      case 'ws.accept':
      case 'ws.refuse':
      case 'ws.text':
      case 'ws.close':
        return this.onBridge(attachment, msg);
      case 'cmd.result': {
        const waiter = typeof msg.id === 'number' ? this.commands.get(msg.id) : undefined;
        if (!waiter || waiter.session !== attachment.session) return;
        this.commands.delete(msg.id as number);
        waiter.resolve({ ok: msg.ok === true, message: text(msg.message, 200) ?? (msg.ok === true ? 'Done.' : 'The piano said no.') });
        return;
      }
      case 'secret.ack':
        return this.onSecretAck(attachment);
    }
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    closeQuietly(ws, code === 1005 || code === 1006 || code === 1015 ? 1000 : code, reason);
    await this.socketGone(ws, code, reason).catch(() => undefined);
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    await this.socketGone(ws, 1011, 'Error').catch(() => undefined);
  }

  private async socketGone(ws: WebSocket, code: number, reason: string): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    if (!attachment) return;
    if (attachment.role === 'browser') {
      this.clearAcceptTimer(attachment.wsId);
      if (attachment.dropped) return;
      const tablet = this.tablet();
      if (tablet && tabletAttachment(tablet).session === attachment.session) {
        tablet.send(JSON.stringify({ t: 'ws.close', id: attachment.wsId, code, reason: text(reason, 120) ?? '' }));
      }
      return;
    }
    this.dropSession(attachment.session, 1001, 'The piano went offline.');
    if (this.tablet() === null) {
      await this.env.DB.prepare('UPDATE pianos SET online = 0, last_seen = ? WHERE id = ?').bind(this.now(), attachment.pianoId).run();
    }
  }

  private async onStatus(attachment: TabletAttachment, msg: Record<string, unknown>): Promise<void> {
    const status = sanitizeStatus(msg);
    if (!status) return;
    const now = this.now();
    const changed = statusKey(status) !== statusKey(this.stored.status);
    const due = now - this.stored.lastD1Write >= D1_EVERY_MS;
    this.stored.statusAt = now;
    if (!changed && !due) return;
    this.stored.status = status;
    if (due) {
      this.stored.lastD1Write = now; // before the write: a status arriving meanwhile is not due
      this.d1StatusWrites++;
      await this.env.DB.prepare(
        'UPDATE pianos SET last_seen = ?, online = 1, app_version = ?, app_code = ?, firmware = ?, guests = ?, approve_first = ?, status_json = ? WHERE id = ? AND secret_hash IS NOT NULL',
      )
        .bind(
          now,
          status.app?.version ?? null,
          status.app?.code ?? null,
          status.firmware,
          status.guests ? (status.guests.open ? 1 : 0) : null,
          status.guests ? (status.guests.approveFirst ? 1 : 0) : null,
          JSON.stringify(status),
          attachment.pianoId,
        )
        .run();
    }
    await this.ctx.storage.put('state', this.stored);
  }

  private async onSecretAck(attachment: TabletAttachment): Promise<void> {
    const now = this.now();
    const row = await this.env.DB.prepare('SELECT pending_secret_hash, pending_until FROM pianos WHERE id = ?')
      .bind(attachment.pianoId)
      .first<{ pending_secret_hash: string | null; pending_until: number | null }>();
    if (!row?.pending_secret_hash || (row.pending_until ?? 0) <= now) {
      this.rotationWaiter?.(false);
      return;
    }
    await this.commitRotation(attachment.pianoId, row.pending_secret_hash, now, 'tablet');
    this.rotationWaiter?.(true);
  }

  private async commitRotation(pianoId: string, pendingHash: string, now: number, actor: string): Promise<void> {
    await this.env.DB.batch([
      this.env.DB.prepare('UPDATE pianos SET secret_hash = pending_secret_hash, pending_secret_hash = NULL, pending_until = NULL WHERE id = ? AND pending_secret_hash = ?').bind(pianoId, pendingHash),
      auditStatement(this.env.DB, { at: now, actor, pianoId, action: 'rotate-commit' }),
    ]);
  }

  /** Closes every tablet socket with [code] and everything that went through it. */
  private sendTabletsAway(code: number, reason: string, browserReason: string): void {
    for (const ws of this.ctx.getWebSockets('tablet')) {
      this.dropSession(tabletAttachment(ws).session, CLOSE_POLICY, browserReason);
      closeQuietly(ws, code, reason);
    }
  }

  /** A tablet connection is over: its requests fail (502), its commands too, its browsers' sockets close. */
  private dropSession(session: number, browserCode: number, browserReason: string): void {
    for (const p of [...this.pending.values()]) {
      if (p.session === session) this.fail(p, 502, 'offline', 'The piano went offline.', false);
    }
    for (const [id, waiter] of this.commands) {
      if (waiter.session !== session) continue;
      this.commands.delete(id);
      waiter.resolve({ ok: false, message: 'The piano went offline.' });
    }
    this.rotationWaiter?.(false);
    for (const ws of this.ctx.getWebSockets('browser')) {
      const attachment = ws.deserializeAttachment() as BrowserAttachment;
      if (attachment.session !== session) continue;
      this.dropBrowser(ws, attachment, browserCode, browserReason);
    }
  }

  // ---- Browsers' requests ---------------------------------------------------------------------------

  private async forward(request: Request): Promise<Response> {
    const h = request.headers;
    const pianoId = this.pianoId(h.get('x-relay-piano'));
    const path = h.get('x-relay-path') ?? '/';
    const api = path === '/api' || path.startsWith('/api/');
    const tablet = this.tablet();
    if (!tablet) return offline(api, request.method);
    const length = Number(h.get('x-relay-length') ?? '0');
    const hasBody = request.body !== null && length > 0;
    const upload = length > UPLOAD_OVER;
    if (upload) {
      if (this.uploading) return error(409, 'busy', 'Another file is being added. Try again in a moment.');
      this.uploading = true;
    }
    let handedOver = false;
    try {
      if (!(await this.acquire())) return error(503, 'busy', 'The piano is busy. Try again.');
      const current = this.tablet();
      if (!current) {
        this.release();
        return offline(api, request.method);
      }
      const headers: Record<string, string> = {};
      for (const name of FORWARDED_HEADERS) {
        const value = name === 'host' ? h.get('x-relay-host') : name === 'content-length' ? h.get('x-relay-length') : h.get(name);
        if (value !== null) headers[name] = value;
      }
      const id = this.newId();
      const req: ReqMessage = {
        t: 'req',
        id,
        method: request.method,
        path,
        query: h.get('x-relay-query') ?? '',
        headers,
        address: h.get('x-relay-address') ?? 'unknown',
        prefix: `/p/${pianoId}`,
        body: hasBody,
      };
      const frame = JSON.stringify(req);
      if (frame.length > MAX_TEXT_FRAME) {
        this.release();
        return error(431, 'too-large', "The request's head is too large.");
      }
      const p: Pending = {
        id,
        session: tabletAttachment(current).session,
        tablet: current,
        method: request.method,
        api,
        upload,
        finished: false,
        answer: null,
        writer: null,
        discard: false,
        unflushed: 0,
        reader: null,
        credit: WINDOW,
        creditWaiter: null,
        timer: null,
      };
      const answer = new Promise<Response>((resolve) => {
        p.answer = resolve;
      });
      this.pending.set(id, p);
      handedOver = true;
      try {
        current.send(frame);
      } catch {
        // The socket closed just now: its close will be seen, this request is over.
        this.fail(p, 502, 'offline', 'The piano went offline.', false);
        return await answer;
      }
      if (hasBody) void this.pump(p, request.body!, length);
      else this.arm(p, this.timing.res, () => this.fail(p, 504, 'timeout', "The piano didn't answer in time."));
      return await answer;
    } finally {
      if (!handedOver && upload) this.uploading = false;
    }
  }

  /**
   * Sends a request's body as `req.chunk`s of up to 64 KB (what the browser sends in smaller pieces
   * is gathered first), never more than the tablet's credit ahead; then `req.end`. The body must be
   * exactly its declared length. It may sit still (no bytes from the browser, no credit from the
   * tablet) for [timing.idle] at most, and take [timing.upload] in all.
   */
  private async pump(p: Pending, body: ReadableStream<Uint8Array>, length: number): Promise<void> {
    const reader = body.getReader();
    p.reader = reader;
    const started = this.now();
    const stalled = () => this.fail(p, 408, 'timeout', 'The upload stalled.');
    const over = () => p.finished || p.writer !== null || p.discard; // failed, or the tablet has answered
    const buffer = new Uint8Array(CHUNK);
    let piece: Uint8Array | null = null;
    let used = 0;
    let sent = 0;
    try {
      this.arm(p, this.timing.idle, stalled);
      while (sent < length) {
        while (p.credit <= 0) {
          await new Promise<void>((resolve) => {
            p.creditWaiter = resolve;
          });
          if (over()) return;
        }
        const target = Math.min(CHUNK, p.credit, length - sent);
        let filled = 0;
        while (filled < target) {
          if (piece === null || used >= piece.byteLength) {
            const { done, value } = await reader.read();
            if (over()) return;
            if (done) break;
            piece = value;
            used = 0;
            this.arm(p, this.timing.idle, stalled);
          }
          const take = Math.min(target - filled, piece.byteLength - used);
          buffer.set(piece.subarray(used, used + take), filled);
          filled += take;
          used += take;
        }
        if (filled === 0) break; // the browser's body ended early
        if (this.now() - started > this.timing.upload) {
          this.fail(p, 408, 'timeout', 'An upload can take 10 minutes at most.');
          return;
        }
        p.tablet.send(encodeFrame(p.id, Kind.ReqChunk, buffer.subarray(0, filled)));
        p.credit -= filled;
        sent += filled;
      }
      // Exactly its length: nothing short, nothing more.
      const extra = piece !== null && used < piece.byteLength;
      const last = extra ? { done: false } : await reader.read();
      if (over()) return;
      if (sent !== length || !last.done) {
        this.fail(p, 400, 'short', sent < length ? 'The upload ended early.' : 'The upload was longer than it said.');
        return;
      }
      p.reader = null;
      p.tablet.send(encodeFrame(p.id, Kind.ReqEnd));
      this.arm(p, this.timing.res, () => this.fail(p, 504, 'timeout', "The piano didn't answer in time."));
    } catch {
      // The browser went away mid-body (or the socket to the tablet did); not when the tablet has answered.
      if (!over()) this.fail(p, 400, 'short', 'The upload ended early.');
    }
  }

  private onCredit(attachment: TabletAttachment, msg: Record<string, unknown>): void {
    const p = typeof msg.id === 'number' ? this.pending.get(msg.id) : undefined;
    if (!p || p.session !== attachment.session) return;
    const bytes = msg.bytes;
    if (typeof bytes !== 'number' || !Number.isSafeInteger(bytes) || bytes <= 0) return;
    p.credit = Math.min(p.credit + bytes, WINDOW * 16);
    const waiter = p.creditWaiter;
    p.creditWaiter = null;
    waiter?.();
  }

  private onRes(attachment: TabletAttachment, msg: Record<string, unknown>): void {
    const id = msg.id;
    if (typeof id !== 'number') return;
    const p = this.pending.get(id);
    if (!p || p.session !== attachment.session) {
      if (!this.recent.includes(id)) this.sendTo(attachment.session, { t: 'req.abort', id });
      return;
    }
    if (!p.answer) return; // answered already
    const status = msg.status;
    if (typeof status !== 'number' || !Number.isInteger(status) || status < 200 || status > 599) {
      this.fail(p, 502, 'relay', 'The piano gave an answer the relay could not read.');
      return;
    }
    const headers = responseHeaders(msg.headers, attachment.pianoId);
    withSecurity(headers, headers.get('content-type')?.includes('json') ? API_CSP : PAGE_CSP);
    // The relay's own word, never the tablet's (RESPONSE_HEADERS doesn't take it): the pictures' limit, for the page.
    headers.set(ART_LIMIT_HEADER, String(ART_LIMIT_PER_MINUTE));
    const noBody = p.method === 'HEAD' || status === 204 || status === 205 || status === 304;
    // The tablet has decided: the rest of a body not yet sent is not wanted.
    this.stopBody(p);
    if (noBody) {
      p.discard = true;
      this.give(p, new Response(null, { status, headers }));
    } else {
      const length = msg.length;
      const known = typeof length === 'number' && Number.isSafeInteger(length) && length >= 0;
      const stream = known ? new FixedLengthStream(length) : new TransformStream<Uint8Array, Uint8Array>();
      if (known) headers.set('Content-Length', String(length));
      const writer = stream.writable.getWriter();
      p.writer = writer;
      writer.closed.catch(() => {
        // The browser went away (or the stream was ended short): tell the tablet.
        if (!p.finished) this.fail(p, 502, 'relay', 'The answer was cut short.');
      });
      this.give(p, new Response(stream.readable, { status, headers }));
    }
    this.arm(p, this.timing.idle, () => this.fail(p, 504, 'timeout', 'The piano stopped answering.'));
  }

  private fromTabletFrame(attachment: TabletAttachment, data: ArrayBuffer): void {
    const frame = decodeFrame(data);
    if (!frame) return;
    const p = this.pending.get(frame.id);
    if (!p || p.session !== attachment.session || (!p.writer && !p.discard)) {
      if (!p && !this.recent.includes(frame.id)) this.sendTo(attachment.session, { t: 'req.abort', id: frame.id });
      return;
    }
    if (frame.kind === Kind.ResChunk) {
      this.arm(p, this.timing.idle, () => this.fail(p, 504, 'timeout', 'The piano stopped answering.'));
      if (p.discard || !p.writer || frame.payload.byteLength === 0) return;
      const n = frame.payload.byteLength;
      p.unflushed += n;
      if (p.unflushed > MAX_UNFLUSHED) {
        this.fail(p, 502, 'relay', 'The browser is not taking the answer.');
        return;
      }
      p.writer.write(frame.payload).then(
        () => {
          p.unflushed -= n;
        },
        () => undefined,
      );
    } else if (frame.kind === Kind.ResEnd) {
      this.complete(p);
    }
  }

  /** The response has ended well. */
  private complete(p: Pending): void {
    if (p.finished) return;
    p.finished = true;
    this.cleanup(p);
    p.writer?.close().catch(() => undefined);
  }

  /**
   * The request is over badly: the tablet is told (`req.abort`) unless its socket is gone, and the
   * browser gets [status] if it has no answer yet, or a cut-off stream if it has.
   */
  private fail(p: Pending, status: number, code: string, message: string, tellTablet = true): void {
    if (p.finished) return;
    p.finished = true;
    this.cleanup(p);
    if (tellTablet) this.sendTo(p.session, { t: 'req.abort', id: p.id });
    this.stopBody(p);
    if (p.answer) this.give(p, error(status, code, message));
    p.writer?.abort(new Error(message)).catch(() => undefined);
  }

  private give(p: Pending, response: Response): void {
    const answer = p.answer;
    p.answer = null;
    answer?.(response);
  }

  private stopBody(p: Pending): void {
    const reader = p.reader;
    p.reader = null;
    reader?.cancel().catch(() => undefined);
    const waiter = p.creditWaiter;
    p.creditWaiter = null;
    waiter?.();
  }

  private cleanup(p: Pending): void {
    if (p.timer) clearTimeout(p.timer);
    p.timer = null;
    if (this.pending.get(p.id) === p) {
      this.pending.delete(p.id);
      this.recent.push(p.id);
      if (this.recent.length > 64) this.recent.shift();
      if (p.upload) this.uploading = false;
      this.release();
    }
  }

  /** One timer per request: the next deadline replaces the last. */
  private arm(p: Pending, ms: number, onTimeout: () => void): void {
    if (p.finished) return;
    if (p.timer) clearTimeout(p.timer);
    p.timer = setTimeout(onTimeout, ms);
  }

  private async acquire(): Promise<boolean> {
    if (this.inFlight < MAX_IN_FLIGHT) {
      this.inFlight++;
      return true;
    }
    if (this.waiting.length >= MAX_QUEUED) return false;
    return new Promise<boolean>((resolve) => {
      const waiter = {
        grant: () => {
          clearTimeout(timer);
          resolve(true);
        },
      };
      const timer = setTimeout(() => {
        const i = this.waiting.indexOf(waiter);
        if (i >= 0) this.waiting.splice(i, 1);
        resolve(false);
      }, this.timing.queue);
      this.waiting.push(waiter);
    });
  }

  /** A place is free: the next waiting request takes it, or the count goes down. */
  private release(): void {
    const next = this.waiting.shift();
    if (next) next.grant();
    else this.inFlight = Math.max(0, this.inFlight - 1);
  }

  // ---- Browsers' sockets ------------------------------------------------------------------------------

  private async connectBrowser(request: Request): Promise<Response> {
    const h = request.headers;
    if (h.get('upgrade')?.toLowerCase() !== 'websocket') return error(426, 'upgrade', 'This address takes a WebSocket.');
    const tablet = this.tablet();
    if (!tablet) return error(503, 'offline', 'The piano is offline.');
    const open = this.browsers();
    if (open.length >= MAX_BROWSERS) return error(503, 'sockets', 'Too many panels are open.');
    const used = new Set(this.ctx.getWebSockets('browser').map((ws) => (ws.deserializeAttachment() as BrowserAttachment).wsId));
    let wsId = randomU32();
    while (wsId === 0 || used.has(wsId)) wsId = randomU32();
    const session = tabletAttachment(tablet).session;
    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(server, ['browser']);
    const attachment: BrowserAttachment = { role: 'browser', wsId, session, accepted: false, dropped: false, openedAt: this.now() };
    server.serializeAttachment(attachment);
    const headers: Record<string, string> = {};
    for (const name of SOCKET_HEADERS) {
      const value = name === 'host' ? h.get('x-relay-host') : h.get(name);
      if (value !== null) headers[name] = value;
    }
    tablet.send(JSON.stringify({ t: 'ws.open', id: wsId, headers, address: h.get('x-relay-address') ?? 'unknown' }));
    this.acceptTimers.set(
      wsId,
      setTimeout(() => {
        this.acceptTimers.delete(wsId);
        const ws = this.browser(wsId);
        if (!ws) return;
        const current = ws.deserializeAttachment() as BrowserAttachment;
        if (current.accepted) return;
        this.sendTo(current.session, { t: 'ws.close', id: wsId, code: 1011, reason: 'No answer.' });
        this.dropBrowser(ws, current, 1011, "The piano didn't answer.");
      }, this.timing.answer),
    );
    return new Response(null, { status: 101, webSocket: client });
  }

  /** What a browser sends is dropped ("ping" never gets here: the room answers it "pong"); a large frame closes it. */
  private fromBrowser(ws: WebSocket, message: string | ArrayBuffer): void {
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (size > BROWSER_FRAME_MAX) {
      const attachment = ws.deserializeAttachment() as BrowserAttachment;
      this.sendTo(attachment.session, { t: 'ws.close', id: attachment.wsId, code: 1009, reason: 'Too large.' });
      this.dropBrowser(ws, attachment, 1009, 'Too large.');
    }
  }

  private onBridge(attachment: TabletAttachment, msg: Record<string, unknown>): void {
    if (typeof msg.id !== 'number') return;
    const ws = this.browser(msg.id);
    if (!ws) return;
    const browser = ws.deserializeAttachment() as BrowserAttachment;
    if (browser.session !== attachment.session || browser.dropped) return;
    switch (msg.t) {
      case 'ws.accept':
        if (!browser.accepted) {
          browser.accepted = true;
          ws.serializeAttachment(browser);
          this.clearAcceptTimer(browser.wsId);
        }
        return;
      case 'ws.refuse': {
        const status = msg.status === 401 || msg.status === 403 || msg.status === 503 ? msg.status : 403;
        const reason = status === 401 ? 'Enter the PIN first.' : status === 503 ? 'Too many panels are open.' : 'Refused.';
        this.dropBrowser(ws, browser, CLOSE_POLICY, reason);
        return;
      }
      case 'ws.text':
        if (browser.accepted && typeof msg.data === 'string') ws.send(msg.data);
        return;
      case 'ws.close': {
        const code = typeof msg.code === 'number' && (msg.code === 1000 || (msg.code >= 3000 && msg.code <= 4999)) ? msg.code : 1000;
        this.dropBrowser(ws, browser, code, text(msg.reason, 120) ?? '');
        return;
      }
    }
  }

  private dropBrowser(ws: WebSocket, attachment: BrowserAttachment, code: number, reason: string): void {
    this.clearAcceptTimer(attachment.wsId);
    if (!attachment.dropped) {
      attachment.dropped = true;
      try {
        ws.serializeAttachment(attachment);
      } catch {
        // Already closed.
      }
    }
    closeQuietly(ws, code, reason);
  }

  private clearAcceptTimer(wsId: number): void {
    const timer = this.acceptTimers.get(wsId);
    if (timer) clearTimeout(timer);
    this.acceptTimers.delete(wsId);
  }

  // ---- Small things ------------------------------------------------------------------------------------

  /** The connected tablet's socket, if one is open. */
  private tablet(): WebSocket | null {
    return this.ctx.getWebSockets('tablet').find((ws) => ws.readyState === OPEN) ?? null;
  }

  private browsers(): WebSocket[] {
    return this.ctx.getWebSockets('browser').filter((ws) => ws.readyState === OPEN && !(ws.deserializeAttachment() as BrowserAttachment).dropped);
  }

  private browser(wsId: number): WebSocket | null {
    return this.ctx.getWebSockets('browser').find((ws) => (ws.deserializeAttachment() as BrowserAttachment).wsId === wsId) ?? null;
  }

  /** Sends [message] to the tablet of [session], if it is still the one connected. */
  private sendTo(session: number, message: unknown): void {
    const tablet = this.tablet();
    if (tablet && tabletAttachment(tablet).session === session) {
      try {
        tablet.send(JSON.stringify(message));
      } catch {
        // Closing.
      }
    }
  }

  private newId(): number {
    this.nextId = (this.nextId + 1) >>> 0;
    if (this.nextId === 0) this.nextId = 1;
    return this.nextId;
  }

  private now(): number {
    return this.clock();
  }

  private maxBody(): number {
    const value = Number(this.env.MAX_BODY_BYTES);
    return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_MAX_BODY;
  }

  /** This room's piano: its name (the room is named by the piano's id), checked against what the caller says. */
  private pianoId(given?: string | null): string {
    const id = this.ctx.id.name ?? given ?? this.storedPianoId;
    if (!id || !PIANO_ID.test(id) || (given && given !== id)) throw new Error('This room is not that piano.');
    return id;
  }
}

function tabletAttachment(ws: WebSocket): TabletAttachment {
  return ws.deserializeAttachment() as TabletAttachment;
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason.slice(0, 120));
  } catch {
    // Closed already.
  }
}

/** A status's content without its time, to see whether anything changed. */
function statusKey(status: TabletStatus | null): string {
  return status ? JSON.stringify({ ...status, at: null }) : '';
}

/** For an answer the tablet sent without a policy of its own (it sends its own for its pages). */
const PAGE_CSP = "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

/**
 * The tablet's response headers a browser may be given: exactly those the app's panel sends
 * (`RelayedResponse.HEADERS` and its Content-Type; the relay sets Content-Length itself). Audit delta 3:
 * this was a deny-list, and every piano's panel shares the relay's origin, so a header under one
 * piano's path could reach the others': `Service-Worker-Allowed` let an answer under /p/<id>/ register a
 * service worker for the whole origin (every piano's panel, every PIN typed there, from then on);
 * `Clear-Site-Data`, `Refresh`, `Link`, `Location` and the reporting headers are no part of the panel either.
 */
export const RESPONSE_HEADERS: ReadonlySet<string> = new Set([
  'content-type',
  'cache-control',
  'set-cookie',
  'retry-after',
  'allow',
  'x-content-type-options',
  'x-frame-options',
  'referrer-policy',
  'content-security-policy',
  'cross-origin-resource-policy',
]);

/**
 * The tablet's response headers the browser may see: only [RESPONSE_HEADERS] (so never CORS, never
 * hop-by-hop, never the relay's own), values without line breaks, and a cookie only when it stays
 * under this piano's prefix (no Domain, and a Path, when given, of /p/<id>/…). At most 64 values and
 * 16 KB.
 */
export function responseHeaders(raw: unknown, pianoId: string): Headers {
  const out = new Headers();
  if (!isObject(raw)) return out;
  let count = 0;
  let size = 0;
  for (const [rawName, rawValue] of Object.entries(raw)) {
    const name = rawName.toLowerCase();
    if (!RESPONSE_HEADERS.has(name)) continue;
    const values = Array.isArray(rawValue) ? rawValue : [rawValue];
    for (const v of values) {
      if (typeof v !== 'string' && typeof v !== 'number') continue;
      const value = String(v);
      if (/[\r\n\u0000]/.test(value) || value.length > 8192) continue;
      if (name === 'set-cookie' && !cookieStaysHome(value, pianoId)) continue;
      count += 1;
      size += name.length + value.length;
      if (count > 64 || size > 16384) return out;
      out.append(name, value);
    }
  }
  return out;
}

/** A Set-Cookie with no Domain, and a Path (when it has one) inside /p/<id>/. */
export function cookieStaysHome(setCookie: string, pianoId: string): boolean {
  const home = `/p/${pianoId}/`;
  for (const part of setCookie.split(';').slice(1)) {
    const eq = part.indexOf('=');
    const key = (eq < 0 ? part : part.slice(0, eq)).trim().toLowerCase();
    const value = eq < 0 ? '' : part.slice(eq + 1).trim();
    if (key === 'domain') return false;
    if (key === 'path' && !(value === home || value === home.slice(0, -1) || value.startsWith(home))) return false;
  }
  return true;
}
