/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { SELF } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { sha256Hex } from '../src/shared/hash';
import { newPianoId, newSecret } from '../src/shared/ids';
import { SUBPROTOCOL, decodeFrame, encodeFrame, type Frame, type Kind } from '../src/shared/protocol';

/** The relay as the tests call it (SELF is the relay Worker, in this very runtime). */
export const RELAY = 'https://relay.test';

/** A client address of this test's own, so one test's rate limits never touch another's. */
export function newAddress(): string {
  const b = crypto.getRandomValues(new Uint8Array(3));
  return `10.${b[0]}.${b[1]}.${b[2]}`;
}

/** A piano in the database with a known secret, as if enrolled. */
export async function seedPiano(options: { name?: string; enrolled?: boolean } = {}): Promise<{ pianoId: string; secret: string }> {
  const pianoId = newPianoId();
  const secret = newSecret();
  const now = Date.now();
  await env.DB.prepare('INSERT INTO pianos (id, name, secret_hash, created_at, enrolled_at) VALUES (?, ?, ?, ?, ?)')
    .bind(pianoId, options.name ?? 'Test piano', options.enrolled === false ? null : await sha256Hex(secret), now, options.enrolled === false ? null : now)
    .run();
  return { pianoId, secret };
}

export function room(pianoId: string) {
  return env.ROOMS.getByName(pianoId);
}

/** A tablet's socket request to the relay, as OkHttp sends it. */
export function tabletRequest(pianoId: string, secret: string, init: { protocol?: string | null; address?: string } = {}): Promise<Response> {
  const headers: Record<string, string> = {
    Upgrade: 'websocket',
    Authorization: `Bearer ${pianoId}.${secret}`,
    'CF-Connecting-IP': init.address ?? newAddress(),
  };
  if (init.protocol !== null) headers['Sec-WebSocket-Protocol'] = init.protocol ?? SUBPROTOCOL;
  return SELF.fetch(`${RELAY}/tablet`, { headers });
}

type Message = Record<string, any>;

/** Something that receives a socket's messages in order and hands them out by what is wanted. */
class Inbox {
  readonly texts: Message[] = [];
  readonly raw: string[] = [];
  readonly frames: Frame[] = [];
  closedWith: { code: number; reason: string } | null = null;
  private wakers: Array<() => void> = [];

  constructor(readonly ws: WebSocket) {
    ws.binaryType = 'arraybuffer';
    ws.addEventListener('message', (event) => {
      if (typeof event.data === 'string') {
        this.raw.push(event.data);
        try {
          this.texts.push(JSON.parse(event.data));
        } catch {
          // Not JSON ("pong").
        }
      } else {
        const frame = decodeFrame(event.data as ArrayBuffer);
        if (frame) this.frames.push({ ...frame, payload: frame.payload.slice() });
      }
      this.wake();
    });
    ws.addEventListener('close', (event) => {
      this.closedWith = { code: event.code, reason: event.reason };
      this.wake();
    });
  }

  private wake(): void {
    const wakers = this.wakers;
    this.wakers = [];
    for (const w of wakers) w();
  }

  /** Waits for [ms] at most for [check] to find something. */
  async waitFor<T>(check: () => T | undefined, what: string, ms = 5000): Promise<T> {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = check();
      if (found !== undefined) return found;
      const left = deadline - Date.now();
      if (left <= 0) throw new Error(`Timed out waiting for ${what}`);
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, Math.min(left, 250));
        this.wakers.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }

  /** The first text message of type [t] (and matching [where]) not taken yet. */
  next(t: string, where: (m: Message) => boolean = () => true, ms?: number): Promise<Message> {
    return this.waitFor(() => {
      const i = this.texts.findIndex((m) => m.t === t && where(m));
      return i < 0 ? undefined : this.texts.splice(i, 1)[0];
    }, `"${t}"`, ms);
  }

  /** The first binary frame for [id] not taken yet. */
  nextFrame(id: number, ms?: number): Promise<Frame> {
    return this.waitFor(() => {
      const i = this.frames.findIndex((f) => f.id === id);
      return i < 0 ? undefined : this.frames.splice(i, 1)[0];
    }, `a frame for ${id}`, ms);
  }

  /** The socket's close. */
  closed(ms?: number): Promise<{ code: number; reason: string }> {
    return this.waitFor(() => this.closedWith ?? undefined, 'the close', ms);
  }

  /** Nothing of type [t] arrives within [ms]. */
  async nothing(t: string, ms = 300): Promise<void> {
    await new Promise((r) => setTimeout(r, ms));
    const found = this.texts.find((m) => m.t === t);
    if (found) throw new Error(`Unexpected "${t}": ${JSON.stringify(found)}`);
  }
}

/** A tablet on the relay: the test plays its part of the protocol. */
export class FakeTablet extends Inbox {
  static async connect(pianoId: string, secret: string, init: { address?: string } = {}): Promise<FakeTablet> {
    const response = await tabletRequest(pianoId, secret, init);
    if (response.status !== 101 || !response.webSocket) {
      throw new Error(`The relay refused the tablet: ${response.status} ${await response.text()}`);
    }
    const ws = response.webSocket;
    ws.accept();
    const tablet = new FakeTablet(ws, response);
    return tablet;
  }

  constructor(ws: WebSocket, readonly response: Response) {
    super(ws);
  }

  send(message: unknown): void {
    this.ws.send(JSON.stringify(message));
  }

  sendFrame(id: number, kind: Kind, payload?: Uint8Array): void {
    this.ws.send(encodeFrame(id, kind, payload));
  }

  close(code = 1000, reason = 'bye'): void {
    try {
      this.ws.close(code, reason);
    } catch {
      // Closed already.
    }
  }
}

/** A browser's socket on a piano's panel. */
export class FakeBrowser extends Inbox {
  static async open(pianoId: string, headers: Record<string, string> = {}): Promise<FakeBrowser | Response> {
    const response = await SELF.fetch(`${RELAY}/p/${pianoId}/ws`, {
      headers: { Upgrade: 'websocket', 'CF-Connecting-IP': newAddress(), ...headers },
    });
    if (response.status !== 101 || !response.webSocket) return response;
    response.webSocket.accept();
    return new FakeBrowser(response.webSocket);
  }

  send(data: string): void {
    this.ws.send(data);
  }

  close(code = 1000, reason = 'bye'): void {
    try {
      this.ws.close(code, reason);
    } catch {
      // Closed already.
    }
  }
}

/** A browser's request for a piano's panel. */
export function panel(pianoId: string, path: string, init: RequestInit & { address?: string } = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (!headers.has('CF-Connecting-IP')) headers.set('CF-Connecting-IP', init.address ?? newAddress());
  return SELF.fetch(`${RELAY}/p/${pianoId}${path}`, { ...init, headers });
}

/** The tablet's answer to [req]: JSON, in two chunks. */
export function answerJson(tablet: FakeTablet, id: number, status: number, body: unknown, headers: Record<string, string> = {}): void {
  const bytes = new TextEncoder().encode(JSON.stringify(body));
  tablet.send({ t: 'res', id, status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers }, length: bytes.byteLength });
  const half = Math.floor(bytes.byteLength / 2);
  tablet.sendFrame(id, 3, bytes.subarray(0, half));
  tablet.sendFrame(id, 3, bytes.subarray(half));
  tablet.sendFrame(id, 4);
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function pianoRow(pianoId: string): Promise<Record<string, any> | null> {
  return env.DB.prepare('SELECT * FROM pianos WHERE id = ?').bind(pianoId).first();
}
