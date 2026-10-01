<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano Cloud

The piano's web panel from anywhere, over HTTPS, without Tailscale; and one console for every
piano. Two Workers on your own Cloudflare account (the Free plan is enough):

- **The relay** (`steven-piano-relay`, public): each tablet keeps one WebSocket open to it, and a
  browser reaches a piano's panel at `https://steven-piano-relay.<you>.workers.dev/p/<piano>/`.
  The relay carries the browser's requests to the tablet and its answers back; the tablet still
  asks for its PIN and serves the panel itself. One Durable Object per piano (`PianoRoom`).
- **The console** (`steven-piano-console`, behind Cloudflare Access, for you alone): every piano
  with its live dot and what it plays; enrol a tablet; play, pause, next, stop, channels, the guest
  switches; open a piano's panel; load Steven's library; rotate a tablet's secret, revoke it, or
  forget the piano; the audit log.

They share one D1 database (`steven-piano`). The tablet's side is the app's CLOUD section (Piano ›
Remote control), which is off until it is enrolled.

## Deploy it (once)

You need the Cloudflare account and Node 22 or later on the Mac (`node -v`). Every command runs in
this folder.

1. **Install the tools.**
   ```sh
   cd "Player Piano/android/cloud"
   npm i
   ```
   npm may warn that some packages' install scripts were not run: they are not needed.
2. **Sign in to Cloudflare** (a browser opens; allow Wrangler):
   ```sh
   npx wrangler login
   ```
3. **Make the database:**
   ```sh
   npx wrangler d1 create steven-piano
   ```
   If it asks to add the database to a configuration file, answer **No**. It prints a
   `"database_id": "…"`: paste that id into **both** `wrangler.relay.jsonc` and
   `wrangler.console.jsonc`, in place of `00000000-0000-0000-0000-000000000000`.
4. **Make its tables** (answer yes when asked):
   ```sh
   npm run migrate
   ```
5. **Deploy the relay:**
   ```sh
   npm run deploy:relay
   ```
   The first deploy may ask you to choose your `workers.dev` subdomain. The last lines print the
   relay's address, `https://steven-piano-relay.<you>.workers.dev`: **the relay's address**. (It
   is also on the dashboard: Workers & Pages › steven-piano-relay.)
6. **Tell the console where the relay is:** in `wrangler.console.jsonc`, set
   `"RELAY_URL": "https://steven-piano-relay.<you>.workers.dev"`.
7. **Deploy the console:**
   ```sh
   npm run deploy:console
   ```
   It prints `https://steven-piano-console.<you>.workers.dev`: **the console's address**. Until
   Access is on (next step), it answers every request "Sign in through Cloudflare Access" (401).
8. **Put Cloudflare Access in front of the console** (one click): on the dashboard, **Workers &
   Pages › steven-piano-console › Settings › Domains & Routes**, and for `workers.dev` click
   **Enable Cloudflare Access**. (Newer dashboards have an **Access** tab on the Worker instead:
   **Protect this Worker behind Access › All traffic**, then **Apply Access**.) Then **Manage
   Cloudflare Access** (Zero Trust › Access › Applications › the console's application): its
   policy **Allow** with **Emails** = your own address, and **One-time PIN** as the login method.
   The Enable Access window shows two values; keep both:
   - `POLICY_AUD`, the application's **audience tag** (later: Zero Trust › Access › Applications ›
     the application › *Application Audience (AUD) Tag*);
   - `TEAM_DOMAIN`, `https://<team>.cloudflareaccess.com` (later: Zero Trust › Settings).

   Leave the **relay** without Access, and don't make all Workers private by default: tablets and
   browsers must reach the relay without signing in (the tablet's PIN guards the panel).
9. **Give the console the audience tag** (it checks every request's Access token itself too):
   ```sh
   npx wrangler secret put ACCESS_AUD -c wrangler.console.jsonc
   ```
   and paste the `POLICY_AUD` value when it asks.
10. **Give it the team**: in `wrangler.console.jsonc`, set `"ACCESS_TEAM_DOMAIN": "<team>"` (the
    `<team>` of `https://<team>.cloudflareaccess.com`; the whole address works too), then deploy it
    again:
    ```sh
    npm run deploy:console
    ```
11. **Open the console's address.** Access asks for your email and sends a code to it; then the
    console shows **Pianos**, empty.
12. **Enrol a tablet:** **Enrol a tablet** shows a code (it works once, for 15 minutes) and the
    relay's address. On the tablet: **Piano › Remote control › CLOUD › Enrol with code**, type the
    relay's address (`steven-piano-relay.<you>.workers.dev`) and the code. The console's sheet
    says "Enrolled: …", and the piano's row gets its red dot once the tablet is connected.
13. **Open the panel:** the piano's page in the console › **Open the panel**, which is
    `https://steven-piano-relay.<you>.workers.dev/p/<piano>/` (the tablet's CLOUD section shows the
    same link with its QR code). The tablet asks for its PIN, as over Tailscale.

### Where each address and value comes from

| What | Looks like | Where it appears |
|---|---|---|
| The relay | `https://steven-piano-relay.<you>.workers.dev` | `npm run deploy:relay` (step 5); Workers & Pages › steven-piano-relay |
| The console | `https://steven-piano-console.<you>.workers.dev` | `npm run deploy:console` (step 7); Workers & Pages › steven-piano-console |
| A piano's panel | `https://steven-piano-relay.<you>.workers.dev/p/<piano>/` | the console › the piano › Open the panel; the tablet's CLOUD section |
| The database id | `8f1c…-…` | `npx wrangler d1 create` (step 3); Storage & Databases › D1 |
| The audience tag (`ACCESS_AUD`) | 64 hex digits | the Enable Access window (step 8); Zero Trust › Access › Applications |
| The team (`ACCESS_TEAM_DOMAIN`) | `https://<team>.cloudflareaccess.com` | the Enable Access window (step 8); Zero Trust › Settings |

### Later

- **After an update** (`git pull`): `npm i`, then `npm run migrate` (only new migrations run),
  `npm run deploy:relay`, `npm run deploy:console`. Deploying the relay restarts every piano's
  room: the tablets reconnect by themselves within seconds, and open panels reconnect too.
- **A lost or replaced tablet:** **Revoke** it in the console (it is disconnected at once and can't
  come back), then enrol the new one; **Forget** removes a piano you no longer have.
- **A new secret:** **Rotate secret** sends the connected tablet a new secret; once it has kept it,
  the old one stops working. Nothing to do on the tablet.

## Local development (no Cloudflare account)

```sh
npm i
npm run dev
```

The relay answers at `http://localhost:8787` and the console at `http://localhost:8788`, on this
Mac only, with one local database (`.wrangler/state`). The console skips Access here (the
`DEV_BYPASS` variable, which it honours only for a request to localhost; it is never in a
configuration file). Then:

- **A stand-in tablet**, which speaks the protocol with a canned state:
  ```sh
  node tools/fake-tablet.mjs --new
  curl http://localhost:8787/p/<piano>/api/state
  ```
  `--new` asks the local console for a code and enrols; the next time, `node tools/fake-tablet.mjs`
  connects again as the same piano. Open `http://localhost:8787/p/<piano>/` in a browser: its
  page shows the panel's socket messages (the state, then the time once a second). `--help` says
  the rest.
- **The Android emulator**: it reaches the relay at `10.0.2.2:8787` (the debug build lets its
  CLOUD section use `ws://10.0.2.2:8787`). Make a code in the console at `http://localhost:8788`,
  enrol with the relay address `10.0.2.2:8787`, and open the panel on the Mac at
  `http://localhost:8787/p/<piano>/` (`npm run dev` tells the tablet that browsers use
  `localhost:8787`).
- **The daily cron**, by hand: `curl "http://localhost:8787/cdn-cgi/local/scheduled"`.
- **Tests and types:** `npm test` (the Workers runtime itself, through Miniflare) and
  `npm run typecheck`. The runtime notes "read end of pipe was aborted" once: that is the test
  whose body the relay refuses (411) without reading it.

## How it works

The protocol (`steven-piano-relay-1`) is in `src/shared/protocol.ts`, and the app's
`RelayProtocol.kt` speaks the same:

- The tablet connects to `wss://<relay>/tablet` with `Authorization: Bearer <piano>.<secret>`; the
  room checks the secret's SHA-256 against the database and says `hello`. The tablet sends its
  `status` every 30 seconds and after a change.
- A browser's request to `/p/<piano>/…` becomes a `req` (the prefix taken off; only `Host`,
  `Cookie`, `Origin`, `Content-Type`, `Content-Length`, `X-Steven-Piano` and `Accept` passed on, and
  the browser's address from `CF-Connecting-IP`), its body in 64 KB binary chunks never more than
  the tablet's credit ahead (1 MB at first); the tablet's `res` and chunks are streamed back.
  The panel's socket (`/p/<piano>/ws`) is bridged the same way.
- The console's commands (`transport`, `play`, `playChannel`, `stopChannel`, `guests`,
  `library.load`, `status`) go down the tablet's own socket, checked against that list first.

| Limit | Value |
|---|---|
| A request's body | its length said (411 without, or chunked), at most 100 MB (413) |
| Requests per piano | 8 at once, 32 waiting, then 503 |
| Uploads (bodies over 64 KB) | one at a time per piano (409) |
| The tablet's answer | 15 s (504); a body or an answer may sit still 30 s; an upload 10 minutes |
| The panel's sockets | 4 per piano (503) |
| Per client address, a minute | 120 requests to panels, 10 PIN tries, 5 enrolments |
| The status in D1 | at most once a minute per piano (the room keeps the latest) |

**Security.** A tablet's secret is 32 random bytes, shown to it once, kept only as its SHA-256.
Rotation is two-phase: the new secret's hash is accepted beside the old for 10 minutes, until the
tablet confirms (`secret.ack`) or connects with it. Revoke closes the tablet's socket with 4401 and
accepts no secret; Forget closes it with 4403 and removes the piano; a connection still being checked
when either comes is refused; a second connection for the same piano takes over (4409), and only the
newest stays. Enrolment codes are 8 letters from 32 (no I, O, 0 or 1), used once within 15 minutes,
only for the new piano they were made with (a code never re-keys or un-revokes one), 5 tries a minute
per address, and a wrong, used or expired code costs the same work and gets the same answer. The
console checks every request's Access token itself (RS256 against the team's keys, the audience, the
issuer, the dates), wants `X-Steven-Piano: 1` and its own origin on its API, and never answers with a
CORS header; neither does the relay. Every answer carries HSTS and the panel's security headers; of a
tablet's answer the relay passes on only the headers the panel sends (its type, caching, cookies, the
security headers), and a tablet's cookie must stay under its own `/p/<piano>/`: every piano's panel
shares the relay's address, so nothing one tablet answers may reach beyond its piano's path. The
tablet checks the PIN with a gate of its own for tries through the relay (ten wrong in a row from
anywhere, then a minute, doubling to an hour). The audit log (console actions, enrolments, rotations)
keeps 90 days and holds the owner's email as the actor, never a visitor's address.

**On the Free plan.** Durable Objects with SQLite storage and WebSocket hibernation, the Rate
Limiting binding and D1 are all on the Free plan; its request body limit is the relay's 100 MB. The
rate limits are counted per Cloudflare location and are a brake, not an exact count.

**What the relay sees.** The app's and firmware's versions, whether the piano is connected, what it
is playing, the guest switches, whether the tablet's Web control is on, the library's size and the
version of Steven's library loaded, and the channels' names (the console shows them; never a device
identifier or the tablet's address on its own networks), and every request and answer between a
browser and the panel as they pass (kept in memory while they pass, never stored). Nothing reaches
it until a tablet is enrolled.

## Files

- `wrangler.relay.jsonc`, `wrangler.console.jsonc`: the two Workers; `migrations/0001_init.sql`: the
  database.
- `src/shared/`: the protocol, ids and codes, hashing, the database's shapes, HTTP answers.
- `src/relay/`: the relay Worker (`index.ts`), enrolment (`enrol.ts`), the room (`room.ts`), the
  offline page (`offline.ts`).
- `src/console/`: the console Worker (`index.ts`, `routes.ts`) and its Access check (`access.ts`);
  `console/static/`: its page.
- `test/`: the tests; `tools/dev.mjs` (`npm run dev`) and `tools/fake-tablet.mjs`.
