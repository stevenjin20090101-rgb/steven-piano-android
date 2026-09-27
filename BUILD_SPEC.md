<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano — engineering spec

Android app: plays a library of Standard MIDI Files on the "Steven Piano" self-playing
piano over **Bluetooth LE MIDI**. Sideloaded as an APK. Read `DESIGN.md` first — it is
the design of record and wins on anything visual. The approved implementation plan
(package layout, milestones, pitfalls) supersedes this file where they differ.

Made by Steven Jin. Package `dev.stevenjin.stevenpiano`. App name **Steven Piano**.
Folder: `Player Piano/android/` (its own git repo; never pushed without Steven's say-so).

## Stack

- Kotlin, Jetpack Compose, Material 3. Single module `app/`. Scaffolded with
  `android create empty-activity` (AGP 9, Gradle wrapper checked in, built-in Kotlin —
  do **not** apply `org.jetbrains.kotlin.android`).
- `minSdk 26`, `targetSdk 34`, `compileSdk 36`. Gradle Kotlin DSL + version catalog.
- Room (KSP), DataStore Preferences, coroutines/Flow, Navigation-Compose,
  androidx.media (`MediaSessionCompat`, MediaStyle notification).
- No analytics. No accounts. No Accompanist (archived) — permissions via
  `rememberLauncherForActivityResult`. Network (from v1.2, M11): exactly two hosts,
  `en.wikipedia.org` and `upload.wikimedia.org`, for composers' portraits and notes; what is
  sent is page titles and search terms made from the library's own names, nothing about the
  person (see `v1.2 — M11 › Network policy`). From v1.4, for the app's own updates only,
  `raw.githubusercontent.com`, `github.com` (this repository's release downloads) and GitHub's
  download hosts `objects.githubusercontent.com` and `release-assets.githubusercontent.com`;
  nothing is sent but the request itself (see `v1.4 › Network policy`).
- Toolchain on this Mac: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`,
  `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`, `local.properties`
  `sdk.dir` pointing there. The `android` CLI needs `--sdk=$ANDROID_HOME`.

## The piano's MIDI contract (firmware facts — the app must match them)

- BLE-MIDI service `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`, characteristic
  `7772E5DB-3868-4112-A1A9-F2669D106BF3`, name "Steven Piano" **in the scan response**
  (scan by service UUID), no bonding, no encryption, one central at a time.
- **Timestamps are discarded** by the piano: events play on arrival. The phone owns
  timing and pacing. Packet framing: header byte, then a timestamp byte before **every**
  status byte; running status only within a packet.
- Throughput ≈ 1000 messages/s; a 64-byte receive queue (~21 messages) blocks the
  piano's Bluetooth task when full → **token bucket: burst 20, refill 1 message/ms**.
- Honoured: Note On/Off, Note On vel 0, CC64 (≥64 down), CC120/121/123 (all = every key
  off + pedal up). Everything else ignored. Channel numbers discarded (OMNI).
- Notes outside MIDI **24–107 are dropped** → the app folds by octave (default) or drops.
- No per-key reference counting in the piano → the app reference-counts sent keys.
- Retrigger: Note Off must precede a re-strike; 30 ms min gap; 60–95 ms min strike →
  **same-key onsets ≥ 100 ms apart** (thin faster repeats); **off before on** at equal
  times.
- Hold ceiling 2 s (firmware cuts long notes). Velocity: **send raw** file velocities.
- Stop sequence: **CC64=0 then CC123** (in that order, one packet, one channel).
  CC120/121/123 from files are never forwarded (CC121 is common at bar 1).

## MIDI file parsing — `midi/SmfParser.kt`

- Format 0 and 1 (2 and SMPTE division rejected with plain-English `SmfException`s).
- `MThd` with extra header bytes skipped; unknown chunks skipped; a track length past
  EOF parsed as far as it goes with a warning (`piano-midi.de/borodin/bor_ps5.mid`).
- Running status (meta cancels it), variable-length quantities, SysEx skipped, meta:
  0x51 tempo, 0x03 track name (Track 0's = `sequenceName`), 0x01/0x02 text, 0x02
  copyright. Text decoded as UTF-8 if valid, else ISO-8859-1.
- Tracks merged stably by (tick, rank: tempo 0, CC 1, note-off/vel-0 2, note-on 3).
- Tempo map → exact `Long` microseconds per segment (default 120 BPM).
- Output `MidiPiece(sequenceName, texts, copyright, format, ppq, durationMicros,
  events: List<TimedEvent>, notes: NoteList, noteCount, warnings)`; `TimedEvent(atMicros,
  status, data1, data2)`; `NoteList` = parallel arrays sorted by start with
  `maxDurationMicros` (for the canvas). (Since the v1.2 audit `events` is an `EventList`:
  parallel primitive arrays read by index, still a `List<TimedEvent>`; see the last section.)

## Note routing — `midi/KeyMap.kt`, `midi/NoteRouter.kt`

- `KeyMap.map(note, transpose, fold)`: transpose, then fold by octaves into 24–107
  (`while (n < 24) n += 12; while (n > 107) n -= 12`) or `-1` when folding is off.
- `NoteRouter` state: `sentKeyForSource[16×128]`, `refCount[128]`, `lastOnsetMicros[128]`.
  - Note On: implicit off if that source is already sounding; drum channel 10 skipped
    when the setting is on; map; thin if the key had an onset < 100 ms ago and is not
    held; send `0x90 key clamp(1..127, vel × pct/100)` only when the count goes 0→1.
  - Note Off: release the **sent** key at 1→0 (a transpose change mid-note still
    releases the right key).
  - CC: only CC64 passes. Program change, pitch bend, aftertouch: dropped.
  - `silence()`: `B0 40 00`, `B0 7B 00`, state cleared.

## Playback — `player/`

- `PlaybackEngine` is pure (clock injected): `songMicros = anchorSong + (now − anchorNanos)
  × tempoPct / 100`; play and tempo changes re-anchor at the current position (no jump);
  `advance(nowNanos)` emits everything due through the router into one batch and returns
  the next wake time; pause / seek / stop **silence first**; on seek or resume, re-send
  the CC64 value in effect at the target.
- `Scheduler`: one dedicated thread ("steven-piano-scheduler", urgent-audio priority),
  commands via a blocking queue with a timed poll. No coroutine `delay` in the hot path.
- `Player` (process singleton in `AppGraph`): `StateFlow<PlayerState>` (status,
  piece, tempo, transpose, velocity, fold, queue position), allocation-free
  `positionMicrosNow()` for the canvas, active keys as two `AtomicLong` bitsets,
  `play(pieceId, queue)`, `togglePlayPause`, `pause`, `stop`, `seek`, `next`,
  `previous` (restart when > 3 s in), setters, `stopAndFlush(timeoutMs)`.
- End of piece: silence, then auto-advance to the next queued piece after 1.5 s.
- Tempo 25–200 %, transpose −12…+12, velocity 50–150 %.

## Bluetooth — `ble/`

- Own `BluetoothGatt` client (`GattPianoLink`), behind the `PianoLink` interface
  (`state: StateFlow<LinkState>`, `connect(address?)`, `disconnect()`, `send(batch)`,
  `flush(timeoutMs)`). `LinkState`: Disconnected / Scanning / Connecting / Connected(name,
  mtu) / Reconnecting(attempt) / Error(message).
- Sequence: filtered scan (service UUID, low latency, 12 s timeout; stop before
  connecting) → `connectGatt(ctx, false, cb, TRANSPORT_LE)` → `requestMtu(255)` and use
  `onMtuChanged`'s value → `discoverServices` (retry once) → characteristic
  `WRITE_TYPE_NO_RESPONSE`, `requestConnectionPriority(HIGH)` → Connected; persist the
  address and name.
- One GATT operation in flight, always; next packet only after `onCharacteristicWrite`
  (API 33+ `writeCharacteristic(ch, bytes, type)` returns 201 BUSY otherwise). Copy byte
  arrays. Callbacks arrive on a Binder thread: hand off, never block.
- `BleMidiFramer`: header + timestamp before every status; ≤ 20 messages per packet;
  payload ≤ MTU − 3. `PacedWriter`: token bucket 20 / 1 per ms.
- Drop: `gatt.close()`, `Reconnecting(n)`, `connectGatt(autoConnect = true)` on the
  cached device plus a filtered scan with backoff 1/2/4…15 s after 20 s (when
  auto-connect is on). User disconnect pauses the player first. Adapter off/on via a
  receiver. `LoggingPianoLink` (debug builds only) for the emulator.
- Permissions: API 31+ `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`;
  API 26–30 `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (`maxSdkVersion 30`)
  and Location Services on. Asked from the Piano tab with a one-line reason.

## Background playback — `service/`

- `PlaybackService`: foreground service `mediaPlayback`, started from the play button,
  `startForeground` within 5 s, MediaStyle notification (play/pause, title; monochrome
  small icon), `MediaSessionCompat` mirroring `PlayerState`, partial wake lock only while
  playing, `stopForeground(DETACH)` on pause, `stopSelf` on stop. `onTaskRemoved` and
  `onDestroy` → `player.stopAndFlush(300)`. `POST_NOTIFICATIONS` requested in context on
  first play (API 33+).
- Playback **continues with the screen off**. The piano is silenced on pause, stop,
  seek, link drop, task removal and service destroy — not on every app background.
- `ImportService`: foreground service `dataSync` running the importer with a progress
  notification; SAF grants are per process, so imports run here, in-app.

## Library — `data/`

- Room entities: `PieceEntity(id, title, composer, composerKey, collection?, sha256
  unique, fileName, sourceName, sizeBytes, durationMs, noteCount, addedAt, favorite,
  playCount, lastPlayedAt?, searchText)`, `CollectionEntity(id, name, createdAt,
  imported)`, `CollectionPieceEntity(collectionId, pieceId)` with cascade.
- Files at `filesDir/pieces/<sha256>.mid`.
- Categories: **All** (title, ASCII-folded sort) · **Collections** (user folders and
  imported INDEX.csv collections) · **Composers** (group by `composerKey`, show
  `MIN(composer)`) · **Favorites** · **Recent** (`COALESCE(lastPlayedAt, addedAt) DESC
  LIMIT 100`). Search: case-insensitive contains on `searchText` (title + composer).
- Import sources: multi-select file picker; **folder** (`ACTION_OPEN_DOCUMENT_TREE`,
  recursive via `DocumentsContract` child queries — not `DocumentFile`); **zip**
  (`java.util.zip.ZipFile`, UTF-8, copied to cache first); "Open with" (`ACTION_VIEW`,
  `ACTION_SEND`, `ACTION_SEND_MULTIPLE`; MIME `audio/midi`, `audio/mid`, `audio/x-midi`,
  `application/x-midi`, plus `application/octet-stream` and `*/*` with `.mid`/`.midi`
  path patterns).
- Pipeline per file: bytes (8 MB cap) → SHA-256 → duplicate check (skip; fill a blank
  composer if the new import knows one) → parse (failure counted, logged) → metadata:
  `INDEX.csv` row if present (columns `collection,composer,title,size_kb,path`, RFC-4180
  quoting, path relative to the root) → else `^(.+?) - (.+)$` filename split → else
  title = file name; a stub title (`^[a-z0-9_\-\.]+$`) is replaced by Track 0's name when
  it contains a space and is not a generic name (control track, upper, lower, piano,
  track, untitled…) → `ComposerNames`: display name, surname, ASCII-folded lowercase
  `composerKey` ("" when unknown), override table for the 26 lowercase piano-midi.de
  folder names (`rachmaninow → Sergei Rachmaninoff`, `burgmueller → Friedrich Burgmüller`,
  `balakirew → Mily Balakirev`, `xmas → Traditional`, …); `ALL SONGS` names are
  double-encoded UTF-8 and are repaired
  (`String(name.toByteArray(ISO_8859_1), UTF_8)` when the name contains `Ã`/`â`) → write
  file → insert (transactions of 25) → CSV `collection` → get-or-create an imported
  `CollectionEntity` and link.
- `ImportProgress(done, total, imported, duplicates, failed, current, finished)` as a
  `StateFlow` in `AppGraph`.

## Settings — `settings/`

DataStore keys: `autoConnect: Boolean (true)`, `lastDeviceAddress: String?`,
`lastDeviceName: String?`, `noteDisplay: PAPER_ROLL | FALLING (PAPER_ROLL)`,
`defaultTempoPct: Int (100)`, `transpose: Int (0)`, `velocityPct: Int (100)`,
`foldOutOfRange: Boolean (true)`, `skipDrumChannel: Boolean (true)`.

## UI contract

- Use the provided `ui/theme` files **unchanged** (`PianoTheme`, `PianoTypography`,
  `PianoShapes`, `Tabular`, `Motion`, `rememberReducedMotion`, `LocalLive`,
  `LocalHairline`, `LocalDisabledGlyph`, `LocalTertiary`). Colour roles: contentPrimary
  = `colorScheme.onSurface`, contentSecondary = `colorScheme.onSurfaceVariant`,
  contentTertiary = `LocalTertiary.current`, hairline = `LocalHairline.current`,
  surfaceElevated = `colorScheme.surfaceVariant`. No colour literal outside `ui/theme`.
  Red (`LocalLive.current`) is read by `LiveDot` only.
- Screens and behaviour exactly as `DESIGN.md`. Bottom `NavigationBar`, three tabs:
  Library, Now playing, Piano. Single activity, Navigation-Compose, fade-through 240 ms
  between tabs (a cut under reduced motion).
- `NoteCanvas` (one `Canvas`, two styles from the `noteDisplay` setting): 84 lanes for
  MIDI 24–107 via a shared `KeyLayout`; notes travel **downward** in both styles.
  *Paper roll*: perforation-styled rounded bars, tracker bar (2 dp `onSurface`) one third
  up from the bottom with a 1 dp hairline 6 dp above it, history visible below.
  *Falling notes*: block-styled bars, hit line at the top of the keyboard strip, no
  history. Upcoming notes `onSurfaceVariant`; a note flips to `onSurface` over 120 ms as
  it crosses the line (cut under reduced motion) and stays so for its duration. Notes in
  start-sorted parallel arrays, binary search for the visible window, `withFrameNanos`
  writes one `Long` state read only inside `drawBehind`, `drawRoundRect` only, no
  per-note allocation, 60 fps with a 5,000-note piece. 320 ms ease-out from still to
  moving on play. Reduced motion: the canvas still moves (it is content), decorations cut.
- `KeyboardStrip`: 84 keys on a `Canvas`, active keys inverted, reads the two
  `AtomicLong` bitsets per frame.
- Timers use `Tabular`; eyebrow labels are `uppercase()` at the call site.
- Every icon-only control has a `contentDescription`. Layouts survive font scale 2.0.
- About row on the Piano tab shows `Provenance.text` ("Steven Piano · Made by Steven Jin
  · v1.1 · eab16a502f679465") and a one-line acknowledgement of the library sources.

## Authorship

- Every `.kt`, `.kts`, `.toml`, `.xml`, `.md`, `.py` starts with the adapted banner
  (copyright Steven Jin, attribution must be preserved, Ed25519 fingerprint
  `eab16a502f679465`, see `firmware/src/main.cpp:1-8`).
- `Provenance.kt`: `@Keep object` with the compiled-in string (R8 `-keep`), shown by the
  About row and duplicated as a manifest `<meta-data>`.
- `LICENSE` (MIT + attribution clause), `AUTHORS`, `PROVENANCE.md` adapted from
  `firmware/`; `provenance/sign.py` + `verify.py` variants over this tree (exclude
  `.git .gradle .kotlin .idea build .claude`; include `.kt .kts .toml .xml .md .py .pro
  .pem` (and `.sh` from v1.4), `LICENSE`, `AUTHORS`, `.gitignore`, `gradlew`, `gradle.properties`,
  `gradle-wrapper.properties`); public key copied from `firmware/provenance/`; the
  private key stays at `~/piano-authorship-PRIVATE-DO-NOT-SHARE.pem`, never in the repo
  (`.gitignore`: `*.pem`, `*PRIVATE*`, `!provenance/author_ed25519_public.pem`).

## Build & sideload

- `./gradlew assembleDebug assembleRelease testDebugUnitTest` must succeed from a clean
  checkout with JDK 17 and the Android SDK present (AGP fetches platform 36);
  `assembleRelease` also needs the release key (below).
- `app/build/outputs/apk/debug/app-debug.apk` for test phones; release is minified
  (`-keep` for `Provenance`) and, since the v1.2 audit, signed with Steven Piano's release
  key (see the last section), never the debug key.
- `README.md`: what it is, build, sideload (Install unknown apps / `adb install`),
  importing the `midi/` folder or `ALL-SONGS.zip`, connecting (forget any stale
  "Steven Piano" bond first), battery-optimisation exemption, the device checklist.

## Acceptance

1. Import three `.mid` files via the picker; sensible titles; search finds them; a
   collection can be created and populated. Import the `midi/` folder: 1,727 pieces
   grouped by collection and composer.
2. Connect to "Steven Piano" from the Piano tab; the dot goes live; status Connected.
3. Play: the canvas moves, notes brighten crossing the line, the keyboard strip inverts,
   timers count with tabular figures, the piano plays in time. Tempo 50 % halves the
   rate live. Switching Note display while playing is seamless. Pause silences within
   ~50 ms (CC64=0 then CC123). Seek silences then resumes.
4. Lock the phone: playback continues from the notification. Swipe the app away: the
   piano is silent. Power-cycle the piano: the app reconnects within ~15 s.
5. Rotate, font scale 2.0, TalkBack: nothing overlaps, everything is labelled.
6. Unit tests green; no colour literal outside `ui/theme`; `LocalLive` only in
   `LiveDot.kt`; provenance verifies; the provenance string is in the release DEX.

---

# v1.1 — engineering additions

Read `DESIGN.md › v1.1` first. Everything in v1.0 stays; these are additions. The
engine, data and BLE layers keep their v1.0 contracts except where stated.

## Adaptive frame

- Add `androidx.compose.material3:material3-window-size-class` (or compute the class
  from `LocalConfiguration` if the artifact does not resolve). `WindowWidthSizeClass`
  drives the frame: Compact → bottom `NavigationBar`; Medium and Expanded →
  `NavigationRail` (labels shown, same four destinations, same glyphs). One or the
  other, never both.
- Four destinations: `library`, `nowplaying`, `keys`, `piano`. Keys takes the keyboard
  glyph; Piano gets a new sliders glyph.
- Now playing layout by class: Compact = one canvas whose style comes from
  `noteDisplay` (now `PAPER_ROLL | FALLING | STAFF`); Medium = `Column`(staff ⅓, notes
  ⅔); Expanded = `Row`(staff, notes) with the keyboard strip and transport spanning
  beneath. The wide-screen choice comes from a new setting `wideLayout: STAFF_AND_NOTES
  | NOTES_ONLY | STAFF_ONLY` (default `STAFF_AND_NOTES`).
- Reading surfaces cap content at 720 dp centred (`Modifier.widthIn(max = 720.dp)`).
- Keys visible range by class: Compact ≈ 2 octaves, Medium ≈ 4, Expanded all 84.

## Keys screen — `ui/screens/keys/`

- `KeysScreen`, `KeysViewModel`, `PlayableKeyboard` (a `Canvas` with
  `pointerInput` handling), `KeyMiniMap`, `SustainButton`.
- Geometry from the existing `KeyLayout` (84 lanes, C1–B7) scaled to a chosen visible
  range with horizontal offset; white keys full height, black keys 60 % height drawn
  after whites. Hit-testing prefers black keys within their rectangle.
- Keyboard height at most `min(320 dp, 45 % of the screen's height)` (M9 review: 790 dp
  keys on an upright tablet read as a barcode). The keyboard and the Sustain / VELOCITY /
  connection row sit at the bottom; the mini-map stays at the top; the space between is
  left empty.
- Multi-touch: track each pointer id → current key; on down send Note On; when a
  pointer crosses into another key send Note Off for the old key and Note On for the
  new (glissando); on up/cancel send Note Off. Velocity from the touch's y within the
  key's rectangle: `24 + (y / keyHeight) × (127 − 24)`, clamped.
- Sending goes through a new `LiveInput` in `player/` (or on `Player`): `noteOn(key,
  vel)`, `noteOff(key)`, `sustain(down)` that feed the same `NoteRouter` instance the
  player uses (so reference counting, the 100 ms guard and the silence sequence stay
  correct when a piece plays at the same time) and `PianoLink.send`. Live input
  bypasses transpose/fold (keys are already 24–107) but respects `velocityPct`.
- `silenceLive()` on `ON_STOP` of the screen, on app background, and on link drop:
  Note Off for every held live key and CC64 = 0 through the router.
- Mini-map: the 84-key strip in miniature with a draggable viewport; ‹ › buttons shift
  by one octave. State in the view model; persisted `keysViewportStart` (default C3).
- VELOCITY readout: Eyebrow + Tabular, shown for 1 s after each tap (no animation
  other than appear/disappear cuts).
- No haptics. `contentDescription` on the keyboard describes it as a playable piano
  keyboard; TalkBack users get the octave buttons and sustain as focusable controls.

## Staff view — `ui/components/StaffCanvas.kt` (replaced in v1.2 by the score: see M12)

- Same inputs as `NoteCanvas` (the `NoteList`, `positionMicrosAt(frameNanos)`, active
  keys); same frame loop and draw-phase-only state reads; same pixels-per-second so the
  two views stay in step.
- Layout: treble staff and bass staff, line spacing 6 dp, 40 dp between the staves,
  centred vertically in the available height; clefs at the left edge; playhead at ⅓
  width. Horizontal axis is time: x = playheadX + (noteStart − now) × pxPerSecond.
- Pitch → staff position: use sharps only. Diatonic step index from MIDI number
  (C=0, D=1 … B=6 per octave; black keys take the step of the natural below and a
  sharp). Notes ≥ 60 on the treble staff (E4 = bottom line), < 60 on the bass staff
  (G2 = bottom line); middle C on a ledger line below the treble staff. Ledger lines
  every second step outside the five lines, drawn per note for the head's width + 4 dp.
- Note head: filled ellipse 7 × 5 dp rotated −20°; sharp glyph 6 dp to the left of the
  head; duration bar: 1 dp hairline from the head's right edge for `duration ×
  pxPerSecond`. Heads a second apart in one chord offset by one head width. Cull to the
  visible time window with the same binary search as the roll.
- Glyphs: bundle Bravura (SIL OFL) as `res/font/bravura.otf` and draw clefs (U+E050
  G clef, U+E062 F clef), sharps (U+E262) and note heads (U+E0A4) with `drawText`
  via `TextMeasurer`; if the font cannot be obtained offline, hand-drawn vector paths
  for the two clefs and a plain ellipse head are acceptable. Add the OFL text to
  `AUTHORS`/`README` acknowledgements.
- Colours: staff lines `LocalTertiary` (M9 review: the hairline token all but vanished in
  dark mode), ledger lines the note's colour, clefs `onSurfaceVariant`, upcoming heads
  `onSurfaceVariant`, active heads `onSurface` with the 120 ms flip (cut under reduced
  motion), playhead `onSurface`.

## Settings (new keys)

`noteDisplay` gains `STAFF`; `wideLayout` (see above); `keysViewportStart: Int (48)`.

## Piano settings over Bluetooth

Specified in `firmware/docs/BLE_SETTINGS.md` (protocol) and the "Piano settings"
subsection below (app side), written once the firmware's command catalogue is
confirmed.

## Piano settings over Bluetooth — app side (M9)

Protocol: `firmware/docs/BLE_SETTINGS.md` (Nordic UART Service next to BLE-MIDI; console
commands in, replies out; `dump` / `get` for machine-readable state; allow-list). Design:
`DESIGN.md › v1.1 › Piano settings`.

### Link: a console channel on the same connection — `ble/`

- After service discovery, `GattPianoLink` looks for NUS `6E400001-…`. If present:
  enable notifications on TX `6E400003-…` (write CCCD `0x2902` = `0x0001`, one GATT op
  in the existing serialized queue) and remember RX `6E400002-…`.
- `PianoLink` gains `val console: ConsoleChannel?` (null when the piano has no NUS):
  `fun sendLine(text: String)` (appends `\n`, ≤ 79 characters, `WRITE_TYPE_NO_RESPONSE`
  to RX, ≤ MTU−3 bytes per write) and `val lines: SharedFlow<String>` (notifications
  reassembled on `\n`, `> ` echo lines dropped).
- Console writes share the single-operation GATT queue with MIDI. MIDI packets go
  first: a console write is dequeued only when the paced MIDI writer has nothing
  pending. Never let a console write delay a due MIDI packet by more than one op.
- `FakePianoLink` gets a scripted console (canned replies per command) for tests;
  `LoggingPianoLink` answers `dump` with a fixed plausible dump so the settings screen
  can be exercised on the emulator.

### Repository — `piano/PianoSettingsRepository.kt`

- `state: StateFlow<PianoState>` where `PianoState` = `Unknown` (not connected or not
  yet read) · `Unsupported` (no NUS, or `dump` not answered with `!proto=1 … end`
  within 2 s) · `Ready(values: Map<String, String>, facts: Map<String, String>,
  lastError: String?)`.
- On `LinkState.Connected` with a console: send `dump`; parse `name=value` lines into
  `values`, `!name=value` into `facts`, until `end`.
- `set(name, value)`: debounce 150 ms per name → `sendLine("$name $value")` → then
  `sendLine("get $name")`; the reply updates `values[name]`. Reply lines containing
  `out of range`, `usage:`, `unknown`, or `refused` become `lastError` (cleared on the
  next successful `get`). Booleans as `0`/`1`, floats with two decimals.
- `preset(name)`: `sendLine(name)` then `dump` (presets change several values).
- `action(name)`: `off`, `save`, `ledtest <midi>`, `testmin <midi>`, `testmax <midi>`,
  `status` (its text lines are collected into `statusText` until 300 ms of silence).
- `save()` is sent automatically when the Piano screen leaves the foreground after any
  successful `set` since the last save.
- On disconnect: `Unknown`. Values are never cached across connections (the piano is
  the source of truth).

### The table — `piano/PianoSettings.kt`

A static list of `PianoSetting(name, label, section, kind, unit)` where `kind` is
`Switch`, `Stepper(min, max, step)`, `Slider(min, max, step, decimals)` or
`Choice(options)`. Names are the firmware command names. Sections and members, in order:

- **LIGHTING**: `leds` Switch "Strip" · `ledmode` Choice Off/Static/Rainbow/Reactive ·
  `ledbright` Slider 0–255 shown as % · `reactcolor` Choice Rainbow/Solid/Velocity/
  Fire/Ocean/Forest/Lava/Party · `ledcount` Stepper 1–300 "LEDs" · `ledoffset` Stepper
  −300–300 · `ledscale` Stepper 10–400 % · `ledtail` Stepper 0–255 · `ledreverse` Switch
  · `ledglow` Stepper 0–10 · `velbright` Switch "Brightness follows velocity" · `decay`
  Stepper 1–40 · `rainspeed` Stepper 1–40 · **Test LED**: a Stepper for a key (24–107,
  default 60, shown as note name) and a *Light it* action (`ledtest`) · `dimsecs`
  Stepper 0–3600 s step 30 · `dimfloor` Slider 0–255.
- **FEEL**: presets chip row Soft · Cinematic · Expressive · Snappy · `fullpower` Switch
  "Full power (no dynamics)" · `volume` Slider 0–100 % · `velcurve` Slider 0.4–3.0 step
  0.05 · `velmult` Slider 0.1–5.0 step 0.1 · `min` Slider 0–4095 "White-key floor" ·
  `minblack` Slider 0–4095 "Black-key floor (0 = same as white)" · `max` Slider 0–4095
  "Ceiling" · `humanvel` Stepper 0–30 · `humantime` Stepper 0–40 ms · `burstgap`
  Stepper 0–600 ms step 10 · `burstboost` Slider 0–100 % · `minstrike` Stepper 0–500 ms
  step 5 · `isostrike` Stepper 0–500 ms step 5 · `isogap` Stepper 0–2000 ms step 10 ·
  `gap` Stepper 0–300 ms · `hold` Stepper 50–4000 ms step 50 · `restrike` Stepper 0 or
  40–1000 ms step 10 · `softrelease` Switch · `releasepwm` Slider 0–4095 · `releasems`
  Stepper 0–200 ms · `freq` Stepper 24–1526 Hz step 10.
- **PEDAL**: `pedalon` Switch · `pedalhalf` Switch · `pedalup` Stepper 80–600 ·
  `pedaldown` Stepper 80–600. (`pedaltest` is refused over Bluetooth; not shown.)
- **DIAGNOSTICS**: facts as read-only rows (`!fw`, `!boards` rendered as seven OK /
  MISSING words, `!i2cfails`, `!pedalboard`, `!uptime` as h:mm) · *Read status* action
  showing `statusText` in Body on `surfaceElevated` · *All keys off* (`off`) and *Save
  now* (`save`) outlined buttons. `keyforce_white` / `keyforce_black` are shown
  read-only ("Key force ×1.00") since setting them is refused over Bluetooth.

### Screen — `ui/screens/piano/PianoSettingsSections.kt`

- Rendered on the Piano tab between the connection card and the app preferences, per
  DESIGN. Every control reads its live value from `values`, shows the unit in its
  eyebrow, and calls `set` on change; sliders call `set` on value change (the
  repository debounces). Disabled with `LocalDisabledGlyph` handles while `Unknown`,
  with the line "Connect to the piano to adjust its settings."; the single line "This
  piano's firmware doesn't offer settings over Bluetooth yet." while `Unsupported`.
- `lastError` shows as an `OutlinedBanner` under the control's section.
- Every control has a `contentDescription` including its label and value; steppers'
  ± buttons are 48 dp.

### Tests

Parser (dump/get/`!` facts/end/timeout → Unsupported), debounce and write-then-get
ordering, error detection, preset → dump, `save` on leave, MIDI-before-console queue
priority in the link (with the fake), and the settings table's ranges matching
`BLE_SETTINGS.md` (a test asserts every name in the table is in the spec's list).

---

# v1.2 — M10: schema v2, playlists, the queue, the byline, rotation

Read `DESIGN.md › v1.2` first. Everything above stays except where this section says
otherwise. M11 (artwork) and M12 (score pages) add their own sections.

## Collections are playlists

One concept, called Playlists everywhere the person can see it (a grep for "collection" in
`ui/` is empty). The data layer keeps v1's table and column names so v1.1 libraries carry
over: `PlaylistEntity` is table `collections`, `PlaylistPieceEntity` is `collection_pieces`
(its playlist column is still `collectionId`), and `PieceEntity.collection` remains the
INDEX.csv `collection` column's value. `PlaylistDao` replaces `CollectionDao`.

## Room schema v2 — `data/db/`

- `collection_pieces` gains `position INTEGER NOT NULL DEFAULT 0` and an index on
  `(collectionId, position)`. A playlist plays in `position` order, title for ties.
- New table `artwork` (`ArtworkEntity(key PK, imagePath?, description?, sourceUrl?,
  sourceTitle?, fetchedAt, status: OK | NOT_FOUND | FAILED)`, keys `composer:<composerKey>`,
  `piece:<id>`, `playlist:<id>`) and `ArtworkDao` (`observe`, `get`, `upsert`, `delete`),
  created now so v1.2 has one migration; the UI reads it from M11.
- `MIGRATION_1_2` (`Migrations.kt`) runs `SchemaV2.DDL`: the position column added with the
  exact definition in the exported `2.json` (the way Room's own auto-migrations add a column),
  then 2.json's `createSql` for the new index and the artwork table. It then numbers every
  playlist 0, 1, 2… ordered by `addedAt`, then title (then id), in Kotlin (API 26's SQLite has
  no window functions). No destructive fallback. `SchemaV2Test` holds the statements equal to
  `app/schemas/…/2.json` (and v2's link table equal to v1's plus that column); `1.json` is kept.
- Note for owners of 1.1 libraries: 1.1 listed a collection by title; 1.2 plays a migrated
  playlist in the order its pieces were added. Imported INDEX sets were added in import order.

## Library — `data/LibraryRepository.kt`

`playlists()` (summaries with `pieceCount` and total `durationMs`), `inPlaylist(id)`,
`playlistIdsOf(pieceId)`, `createPlaylist`, `renamePlaylist`, `deletePlaylist`,
`addToPlaylist(id, pieceId)` (one transaction: `nextPosition`, then insert at the end),
`removeFromPlaylist`, `reorderPlaylist(id, orderedIds)` (a drag; the ids may be a search's
subset, see `PlaylistOrder.reordered`), `movePiece(id, pieceId, delta)` (Move up / down), and
`summaries(ids)` (`PieceSummary(id, title, composerShort, durationMs, composerKey)` by id,
asked 500 ids at a time). Reorders write only the positions that change. The importer's
INDEX.csv links go to the end of their playlist.

## The queue — `player/Queue.kt` (replaces `Playlist.kt`)

- `QueueEntry(uid, pieceId)`: every entry has its own uid (numbering carries across queues),
  so a piece can be queued twice. `Queue(entries, index, original?, repeat, nextUid)` is
  immutable: `next / previous / previousRestarts / afterEnd / playNext / addToQueue /
  remove(uid) / move(uid, toUpNextIndex) / clearUpNext / skipTo / withShuffle(on, random) /
  withRepeat`, factories `startingAt(pieceId, ids, previous, random)` and `all(ids, shuffle,
  previous, random)`.
- Shuffle: the current piece stays first and plays on; the rest follow in random order;
  `original` keeps the order they had. Off: that order comes back. Play next goes after the
  current piece in both orders; Add to queue while shuffled stays out of `original`, so
  restoring puts it right after the current piece; moves in Up next change only the shown
  order; remove and clear take entries out of both. The current entry is never removed.
- Repeat (`RepeatMode.OFF | ALL | ONE`, the button cycles in that order): `afterEnd()` is the
  next entry, the top again (all), the same entry (one), or null. Next wraps with repeat all.
- `PlayerState.queue: QueueSnapshot(ids, uids, index, shuffle, repeat)` with `currentUid`,
  `upNextIds`, `upNextUids`, `hasNext`, `advancesAtEnd` and `window()` (at most 50 entries
  for the media session, starting five before the current one). `queueIndex` and `queueSize`
  remain as computed properties.

## Player — `player/Player.kt`

- New: `playAll(ids, shuffle)` (a playlist's Play or Shuffle; sets the shuffle mode),
  `playNext(ids)` and `addToQueue(ids)` (with nothing queued they start playing and return
  true), `removeFromQueue(uid)`, `moveInQueue(uid, toIndex)`, `clearUpNext()`,
  `skipToQueueEntry(uid)`, `setShuffle(on)`, `setRepeat(mode)`, and a `Random` constructor
  parameter for tests. `play(id, queue)` keeps the current modes.
- The end of a piece: 1.5 s later the player asks the queue again (Up next and the modes may
  have changed); the same entry again restarts the loaded piece with `engine.seek(0)` then
  `engine.play()`, without reading the file again (it still counts as a play).
- Settings gain `shuffle: Boolean (false)` and `repeat: RepeatMode (OFF)`; `AppGraph.start()`
  seeds the player with them and writes every change back.
- `PlaybackStarter.playAll / playNext / addToQueue` start the playback service when playback
  starts. `MediaSessionHolder` publishes the queue window (`setQueue`, uids as queue ids, names
  from `library.summaries`), the shuffle and repeat modes, the active queue item, and handles
  `onSkipToQueueItem`, `onSetShuffleMode`, `onSetRepeatMode`. `PlaybackService` keeps the
  foreground through the pause between pieces whenever `queue.advancesAtEnd`.

## UI

- `ScreenHeader(title, modifier, byline = Provenance.byline, actions)`: the byline "Player
  piano · by Steven Jin" in the eyebrow style under every tab title, no animation. The About
  row's `Provenance.text` drops the app's name: "Made by Steven Jin · v1.2 · eab16a502f679465".
- `TransportBar(playing, hasNext, shuffle, repeat, onShuffle, onRepeat, onPrevious,
  onPlayPause, onNext)`: 48 dp Shuffle and Repeat at the ends (tertiary off; primary with a
  4 dp dot on; descriptions "Shuffle on/off", "Repeat off/all/one"; Shuffle has the switch
  role); gaps shrink to fit 360 dp.
- Now playing: the queue glyph opens `UpNextSheet` (`UpNextViewModel`): a modal bottom sheet
  with the current piece, then up next with "Remove from queue" and a "Reorder" handle, Clear,
  tap to play, "Nothing up next." when empty. Its order is the queue's only.
- Library: `Category.Playlists`, `Group.Playlist`, `Listing.Playlists`; grids of tiles for
  Playlists and Composers (`AppFrame.tileColumns` 2 / 3 / 4, chunked rows inside the one
  `LazyColumn`), art is `MonogramTile` (`ui/components/Artwork.kt`) until M11. Tiles
  long-press: Rename · Delete (playlists; Change photo comes with M11), Play all · Shuffle
  (composers). `PlaylistHeader(summary, cover, onBack, onPlay, onShuffle, onRename, onDelete)`.
  Row menus through `PieceActions` (`PieceMenu`, three groups, destructive last); inside a
  playlist, and only while no search narrows it, rows carry a drag handle and Move up / Move
  down.
- `ui/components/DragReorder.kt`: `rememberDragReorderState(listState, canMoveTo, onMove,
  onDrop)`, `Modifier.dragHandle(state, key)` (consumes the drag, so a sheet stays put),
  `Modifier.reorderable(state, key, itemScope)` (lift with `surfaceVariant`, hairline and
  shadow; `animateItem()` for the others, a cut under reduced motion), `DragHandle` (48 dp,
  "Reorder", Move up / Move down accessibility actions), pure `targetIndex`, `moved`,
  `reorderedBy`. Keys are stable (`p<id>`, queue uids), comparisons are layoutInfo offsets only,
  the list keeps its scroll when the first visible row moves, and it auto-scrolls near the edges.
- New glyphs: `ic_shuffle`, `ic_repeat`, `ic_repeat_one`, `ic_queue`, `ic_drag_handle`, `ic_more`.

## Rotation

`MainActivity` handles `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`
itself, so rotating never recreates it: nothing is silenced, the latched Sustain stays down (so
struck strings keep ringing), and the window size class is recomputed from the new
configuration. `onStop` silences live keys only when not `isChangingConfigurations`.
`KeysViewModel` keeps the first white key as chosen and clamps it when read (21 stays 21 at 15
visible keys, shows 20 at 29, 0 at 49). Nothing may rely on recreation to refresh.

Android itself cancels every touch in progress when the display rotates (each window gets
`ACTION_CANCEL`; measured on the emulator with a device-level touch in M10), and a cancel lets go
of the key like a lift. So while any key is held (`KeyTouches.anyHeld`) the Keys screen asks for
`SCREEN_ORIENTATION_LOCKED` and gives the orientation back when the last key is let go or the
screen goes; a rotation asked for meanwhile happens then. Held keys and the pedal therefore
never see a rotation, and a key is never kept down without a finger the app can see.

## Version

1.2, `versionCode 3`. Provenance is re-signed at the end of v1.2 (M12), not per run.

## Tests added in M10

`QueueTest` (v1.1's three `PlaylistTest` cases kept), `PlayerTest` (repeat one restarts
without reloading, repeat all wraps, play-next order, enqueue with nothing queued),
`SchemaV2Test`, `PlaylistOrderTest`, `SettingsRepositoryTest` (shuffle and repeat),
`DragReorderTest`, `KeyboardGeometryTest` (rotation), `FormatTest` (playlist totals,
monograms), `AdaptiveFrameTest` (tile columns).

---

# v1.2 — M11: artwork and notes, tiles with art, the piece sheet

Read `DESIGN.md › v1.2 › Artwork and notes` first. Everything above stays except where this
section says otherwise; M12 (score pages) adds its own section. Schema unchanged (v2).

## Network policy

- `INTERNET` and `ACCESS_NETWORK_STATE`. Two hosts, no others: `en.wikipedia.org` (REST
  `api/rest_v1/page/summary/{title}`; `w/api.php?action=query&list=search&srsearch=…&srlimit=3&
  srnamespace=0&format=json&formatversion=2`) and `upload.wikimedia.org` (portraits). HTTPS only:
  `WikipediaUrls.allowed` refuses any other host, and redirects are followed by hand (at most 5)
  through the same check. The summary API hands images out on `thumb.wikimedia.org` with `utm_*`
  queries; they are rewritten to `upload.wikimedia.org` (the same files) without the query.
- What is sent: page titles and search terms built from the library's composer names and piece
  titles; nothing about the person. Every request, image downloads included, carries
  `User-Agent: StevenPiano/1.2 (https://github.com/stevenjin20090101-rgb/steven-piano-android)`
  and `Accept: application/json`.
- `net/WikipediaClient.kt`: `WikiApi { summary(title): WikiSummary?; search(query): List<String>;
  download(url, maxBytes): ByteArray? }` over `HttpURLConnection`: connect 10 s, read 15 s; 404/410
  → null; 429/503 → `WikiBusyException(code, retryAfterMs)`; other non-2xx → `IOException`; bodies
  capped before parsing (JSON 256 KB, past it an `IOException`; files at the caller's cap, 6 MB for
  images, past it null). Titles: spaces → `_`, then UTF-8 percent-encoding. `WikiJson` (org.json,
  kept thin: a stub on the JVM). Each request is logged at debug level (tag `Wikipedia`) with its
  wall-clock time.
- Image rule (`WikipediaUrls.image`): the original when ≤ 1024 px wide and jpg/png/gif/webp;
  otherwise the thumbnail rewritten to **960 px**. Wikimedia renders thumbnails only at its
  standard widths (… 330, 500, 960, 1280 …); `1024px-` answers HTTP 400 "Use thumbnail sizes
  listed on https://w.wiki/GHai" (measured September 2026), so 960 is the widest step within the
  1024 px limit. A narrower scan (TIFF) takes the widest step within its own width; drawings (SVG)
  always 960.
- `net/NetworkMonitor.kt`: `online: StateFlow<Boolean>` (default-network callback) for the UI,
  `isOnline()` (INTERNET capability on the active network) for the worker.

## Art keys, fetching and policy — `data/art/`

- `ArtKey.Composer(composerKey, display)` → `composer:<composerKey>`; `ArtKey.Piece(id, title,
  composer)` → `piece:<id>`; `playlist:<id>` rows are the person's photos, never fetched.
  `ArtworkDao` gains `observeAll()`; `PieceDao` gains `idsByComposer(key, limit)` and
  `firstComposerKey(playlistId)` (queries only).
- `ArtworkFetcher` (pure over `WikiApi`): a composer's page name is `ComposerNames.canonical(key)`
  (now public) or the display name; blank, "Traditional", "Anonymous", "Unknown", "Various" →
  NOT_FOUND with no request; `type == "disambiguation"` → one retry as "{name} (composer)"; a
  composer outside the canonical list is taken only when the page's description or extract is
  about music (so a namesake's photograph never shows); the portrait is downloaded. A piece:
  search "title composer" (the composer's full name); the first hit that is not the composer's
  page nor a disambiguation and whose extract names the surname (accents folded) gives the
  extract, title and URL; text only. `IOException` → FAILED; `WikiBusyException` → wait as asked
  once (5 s when unsaid, never past 60 s) and retry; asked again → `Fetched.Busy`.
- `ArtworkPolicy.shouldFetch(existing, now, force)`: nothing recorded → yes; OK → never; NOT_FOUND
  → only when forced; FAILED → 24 h later, or when forced, or when the clock went backwards.

## Repository, worker, files, bitmaps, roll cards

- `ArtworkRepository` (`AppGraph.artwork`): `artwork(key): Flow<ArtworkEntity?>` (one shared map
  of every row: one query per change to the table, however many rows and tiles watch), `peek(key)`,
  `request(key, priority, force)`, `requestComposers(force): Int`, `composersDue()`,
  `cancelBackground()`, `progress: StateFlow<ArtworkProgress(done, total, current, idle)>`
  (background work only; a sheet's own request is not counted), `online`, `bitmap(row | key,
  size)`, `cached(row, size)`, `setPlaylistPhoto(id, uri)` (copied at once through the picker's
  transient grant, turned upright from EXIF, JPEG ≤ 1024 px on the longer side), `rollCard(pieceId)`,
  `cachedRollCard(pieceId)`, `mosaicPieces(composerKey)` (first four by title, cached until the
  pieces change), `firstComposerKey(playlistId)`, `forget(key)` (row and file, when a playlist or
  piece is deleted).
- `ArtworkWorker` (pure; confined to the app scope's main thread): one queue, strictly sequential;
  `request` appends, or puts first when `priority`; duplicates merge (and move up); `requestAll`
  queues only what is due; offline → skipped, nothing recorded (a failure seen while offline is
  not recorded either); `Busy` → pause as asked (≥ 1 s), or past a minute drop the rest of the
  background run for the next start. `RequestPacer` + `PacedWikiApi`: ≥ 250 ms between request
  starts (measured on the emulator: 250–254 ms).
- `ArtFiles`: `filesDir/art/<readable key>-<crc32>.<jpg|png|gif|webp|img>`, written atomically;
  paths stored relative to `filesDir`. Downloads are kept as they came, after a decodability check.
- `BitmapCache`: bounds pass, then the smallest power-of-two `inSampleSize` that brings the shorter
  side to ≤ `ArtSize.Row` 128 / `Tile` 512 / `Full` 1024 px; `LruCache` sized by
  `allocationByteCount` (⅛ of the heap, ≤ 48 MB); `OutOfMemoryError` → no picture.
- `RollCard.render(notes)`: a 256 × 256 alpha map of the 20 s after the first note, 84 lanes (C1–B7,
  folded by octave), time running up from the bottom row, alpha 120–255 by velocity, each lane's
  last column left as paper, notes at least two rows tall. Drawn as an `ALPHA_8` bitmap tinted with
  `onSurfaceVariant`, so no colour lives in it. A composer with no portrait shows a 2 × 2 mosaic of
  their first four pieces' cards (two pieces as a checkerboard; a single piece's card whole).

## Service — `service/ArtworkService.kt`

dataSync foreground service, channel "artwork", notification "Fetching artwork and notes" with
"Claude Debussy · 12 of 61" and a progress bar. `start(context, force)` queues
`requestComposers(force)`, follows `progress` and stops itself when the worker is idle (unless
another start is still queueing). Started by `ImportService` after an import with `imported > 0`,
while that service still holds the foreground (Android 12+ refuses most background starts), when
`fetchArtworkAutomatically`; by `MainActivity.onStart` → `AppGraph.fetchArtworkIfDue()` when
automatic, online and a composer is due (a 1.1 library, an import made offline, a day-old failure);
and by the `+` sheet's "Fetch artwork and notes for every composer" with `force = true`. If Android
refuses the start, the same work runs in the app's process without the notification.
`onTimeout(startId)` and `onTimeout(startId, fgsType)` drop the background queue and stop; nothing
is recorded for the rest, so the next start fetches it.

## Settings

`artworkMonochrome: Boolean (false)`, `fetchArtworkAutomatically: Boolean (true)`. Piano tab rows
"Artwork in black and white" and "Fetch artwork automatically" with the line "Uses Wikipedia.
Nothing about you is sent." beneath; the About area adds that sentence and "Text from Wikipedia,
CC BY-SA 4.0 · portraits from Wikimedia Commons".

## UI

- `ui/components/Artwork.kt`: `ArtFrame` (square, `surfaceVariant`, a 1 dp `LocalHairline`
  outline drawn over the picture, `shapes.medium`, no semantics), `MonogramTile`, `Monochrome`
  (saturation 0) through `LocalArtworkMonochrome` (provided by the nav host), `rememberArtworkRow`,
  `rememberArtwork(key, size)`, `ArtworkImage(key, size, modifier, fallback)`, `RollCardImage`,
  `MosaicTile`, `ComposerArt` (portrait → mosaic → monogram), `PlaylistCover` (photo → first
  piece's composer portrait → monogram). Portraits crop a little above centre.
- Library: tiles show art (`AppFrame.tileColumns`); playlist tiles long-press Rename · Change photo
  | Delete; `PieceRow` leads with a 40 dp `ComposerArt` (divider inset to the text, 72 dp);
  `ComposerHeader(portrait, name, meta, blurb = Sentences.firstTwo(description), sourceUrl, onBack)`
  with a "From Wikipedia" link (the blurb is Wikipedia's text); `PlaylistHeader(…, onChangePhoto,
  …)`; `PickVisualMedia(ImageOnly)`; `ArtworkBar` ("Fetching artwork 12 of 61") shares the import
  bar's hairline progress row; the `+` sheet gains the fetch action under a hairline.
- `ui/screens/piece/PieceDetailSheet(pieceId, onDismiss)`: `ModalBottomSheet` with its drag handle;
  art = the composer's portrait (`Full`), else the piece's roll card; title `titleLarge`; composer
  Eyebrow; then `PieceNotesChoice.of(piece, composer, online, waiting)`: the piece's extract, else
  the composer's (once the piece's own fetch is done, or offline), with "From Wikipedia" and the
  attribution Eyebrow; "No notes found for this piece."; "Notes need an internet connection." when
  offline with nothing kept; a hairline while the piece's fetch runs (at most 12 s). Opening it
  asks for the piece (and a never-looked-up composer) first in line. Opened from the Now playing
  title (`onClickLabel = "About this piece"`) and from "About this piece" in the row menu
  (`PieceActions.about`, first group, after Add to queue). Material 1.4's sheet keeps the height
  it opened at when its content grows, so the sheet calls `expand()` again when its notes change.

## Tests added in M11

`WikipediaUrlsTest`, `ArtworkFetcherTest` (against `FakeWikipedia`), `ArtworkPolicyTest`,
`ArtworkWorkerTest` (virtual time: strictly sequential, 250 ms spacing, offline skip and no record,
priority to the front, FAILED backoff, busy waits and long waits, cancellation, progress),
`RollCardTest` (identical bytes for the same notes; lane 0 marks the expected pixels), `ArtFilesTest`
(names, signatures, atomic writes, sample sizes), `SentencesTest` ("J. S. Bach"), `ArtworkCopyTest`,
`ComposerNamesTest` (+ `canonical`), `SettingsRepositoryTest` (+ the two keys).

---

# v1.2 — M12: the score in pages, the parser's tempo map and signatures

Read `DESIGN.md › v1.2 › Score` first; key signatures come forward from `DESIGN.md › v1.3 ›
Score fidelity` (its first bullet only). Everything above stays except where this section says
otherwise. This run closes v1.2: README, `AUTHORS` and the provenance manifest are updated with it.

## Parser — `midi/`

- `TempoMap` (`TempoMap.kt`): the tempo segments as ticks, microseconds and tempo. The merge builds
  it as it goes (`TempoMap.Builder`: `micros(tick)`, `change(tick, tempo)`; a second change at one
  tick replaces the first), with the parser's own arithmetic (`segmentMicros + (tick - segmentTick)
  * tempo / ppq`, rounded down), so `tickToMicros` is exactly how events are timed and
  `microsToTicks` (to the nearest tick) gives any event's tick back. `microsToBeats` and `ticksAt`
  are fractional; `tempoAt(tick)`; `constant(ppq)`. All 3,454 corpus files' events, notes,
  durations and warnings are byte-identical to the parser before M12 (digests compared).
- Meta `0x58` (time signature: numerator, power-of-two denominator) and `0x59` (key signature:
  signed sharps, mode) go to lists of their own beside the packed words, whose 2-bit rank is full.
  `SignatureLists` (`Signatures.kt`) puts them in time order (stable across tracks), keeps the last
  at any tick, drops unusable ones (time: numerator 1–255, denominator 1–64; key: -7..7 and mode
  0/1) and removes any that repeats the signature in force. `TimeSignature(tick, atMicros,
  numerator, denominator)` with `valid`, `sameAs`, `ticksIn(bars, ppq)`, `Common` (4/4 at 0);
  `KeySignature(tick, atMicros, sharps, minor)` with `sameAs` and `transposed(semitones)` (seven
  sharps a semitone, folded to -5..6; unchanged at 0).
- `Bars` (`Bars.kt`): `startTicks(signatures, ppq, durationTicks)` and `starts(tempo, signatures,
  durationMicros)`: bar 1 at 0, a new bar at every change of metre even mid-bar (a pickup, a
  cadenza), bars of each metre's length measured from where it took over (rounded down per bar
  count, so lines never drift), unusable and repeated signatures ignored, bars until the one
  holding the piece's end, at most `MAX_BARS` = 100,000.
- `MidiPiece` gains `tempoMap`, `timeSignatures` (never empty: 4/4 until the file says otherwise),
  `keySignatures` (empty when the file has none) and `barStartsMicros`. `SmfBuilder.Track` gains
  `timeSignature(tick, n, d)` and `keySignature(tick, sharps, minor)` for tests.

## The score engine — `score/` (no Android, no Compose)

- `ScoreWidth` (`COMPACT` 2, `MEDIUM` 3, `EXPANDED` 4 bars a system; `AppFrame.scoreWidth` maps the
  window's width class). `ScoreMetrics.forPanel(width, panelWidthPx, panelHeightPx, density,
  headWidth, clefWidth, numberHeight)`: two pages side by side (16 dp apart) when the panel is
  840 dp wide or more; 6 dp staff space, 40 dp between the staves (an 88 dp grand staff), 32 dp
  between systems, one bar-number line (`numberHeight`, the eyebrow's line height, measured, so it
  grows with the font scale) above each system, 8 dp above the first system and 16 dp below the
  last; systems a page = as many as fit (at least one), the room left over spread evenly above,
  between and below them; 12 dp page margins. `staffTop(row)`, `slotLeft(slot)`.
- `Quantize.onGrid(starts, tempo, tolerance = 0.12, share = 0.80)`: a file is sequenced when at
  least 80 % of its onsets fall within 12 % of a sixteenth (ppq / 4 ticks), measured in ticks
  through the tempo map, never in microseconds. `Quantize.value(durationTicks, ppq)`: dotted when
  within 12 % of 1.5 times a value, otherwise the nearest of sixteenth … whole on a log2 scale
  (a 32nd reads as a sixteenth, longer than a whole note as a whole note). `NoteValue(power,
  dotted)` with `whole`, `hollow`, `flags`.
- `Spelling` (pure, table-driven, allocation-free): `keyAlteration(letter, sharps)` (sharps added
  F C G D A E B, flats B E A D G C F), `letter`, `alteration` (+1, -1, 0), `step` (diatonic, C4 = 35,
  as `StaffPitch.diatonic`) and `name` ("B♭4", "B♮4") of a key in a key signature: the key's own
  spelling when the pitch is in it, else the natural of a letter the key alters, else a flat in a
  flat key and a sharp otherwise (so no key signature reads in sharps, as the staff did).
  `BarAccidentals` shows an accidental only where the bar does not already read the note so, per
  staff and octave, reset at every bar line and key change: one accidental per pitch per bar, a
  natural on a return to the key's note. `StaffPitch.position(step, treble)` places a letter step;
  `StaffPitch.position(key)` (sharps) stays for callers without a key.
- `ScoreLayoutEngine.layout(notes, keys, tempo, bars, keySignatures, metrics, timeSignatures)` →
  `ScoreLayout`. Systems of `barsPerSystem` bars, every bar of a system as wide as the others; each
  system opens with its clefs and the key signature in force (standard positions: treble sharps
  F5 C5 G5 D5 A4 E5 B4, flats B4 E5 A4 D5 G4 C5 F4, the bass a third lower), the time signature at
  the first bar and at every change of metre (at a change mid-system, after its bar line; a key
  change mid-system draws the new key there, naturals cancelling a change to no accidentals). A
  note sits at its place in beats within its bar (`ScoreBars.xAt`; its head's left edge where the
  cursor is as it sounds); treble from middle C up, bass below; spelled in the key; ledger lines
  counted (`StaffPitch.ledgerLines`). Chords (the same tick when sequenced, within 30 ms and one bar
  when performed) move heads a second apart one head right, stack accidentals leftwards in columns
  (top down, six steps clear), and, when sequenced, give each value (head, flags, dot) one stem,
  3.5 spaces from the head nearest its end and reaching the middle line from far notes: a lone
  value points away from its farthest head (up when that is below the middle line); two values
  struck together stem apart, the higher up. Flags on the stem's owner only; dots in the space
  (a line's dot moves up). Performances keep black heads, no stems, and a hairline to where each
  note ends (held to its system). Arrays in the note list's order: `system` (-1: unplayable),
  `x`, `y`, `head`, `treble`, `ledgers`, `accidental` + `accidentalX`, `stemX`/`stemFrom`/`stemTo`/
  `stemUp`, `flags`, `dotted` + `dotX`/`dotY`, `moved`, `durationEnd`. `ScoreSystem(index, page,
  slot, trebleTop, bassTop, left, right, firstBar, barCount, firstNote, noteEnd, bandTop,
  bandBottom, signKind/signX/signY, final)` with `xAt(micros)` and `barAtX(x)`; drawing keeps to
  the band (the neighbouring staves or the page's edges), which clips the most extreme ledger lines.
  `ScoreLayout.systemAt(micros)` and `ScoreBars.barAt(micros)` by binary search; `systemsOn(page)`,
  `pageCount`, `quantized`.
- `PageTurn.pagesShown(cursorSystem, systemCount, systemsPerPage, pages)` (and `visibleSystems`):
  one page shows the cursor's; two show even pages left, odd right, the cursor's page in its slot
  and the other slot the next page once the cursor is on its page's last system (or when there is
  no page before), else the previous one (at the end, the previous). `PageTurn.browsing(first,
  pageCount, pages)`: a page and the one after, held to even pages with two slots.

## `ui/components/ScorePages.kt` (replaces `StaffCanvas.kt`)

- Bravura at four spaces to the em, in dp (the score keeps its size at any font scale); glyphs:
  heads U+E0A2/E0A3/E0A4, flags U+E240/E241/E242/E243, dot U+E1E7, sharp U+E262, flat U+E260,
  natural U+E261, clefs U+E050/E062, time-signature digits U+E080–E089. All measured once with
  `rememberTextMeasurer` on the main thread (`overflow = Visible`), drawn with their SMuFL origin on
  the baseline. Colours: staff and bar lines `LocalTertiary`, clefs, signatures and upcoming notes
  `onSurfaceVariant`, sounding notes through `colorRamp`/`rampLevel` to `onSurface` (the 120 ms
  flip, a cut under reduced motion), the 2 dp cursor `onSurface`, bar numbers the tertiary
  eyebrow, the spine between two pages `LocalHairline`. Final bar line thin-thick.
- The layout is computed in `produceState` on `Dispatchers.Default`, keyed on the notes, tempo map,
  bars, signatures, transpose, folding and metrics (a new piece clears the old pages first). Each
  visible page is a `Spacer` in its own offscreen `graphicsLayer` drawn from `drawWithCache` keyed
  on the layout and page (bar numbers measured there, on the main thread) and never reading the
  frame clock; the overlay (its own layer) reads `frameNanos` in the draw phase only, draws the
  cursor in the sounding system and re-draws each sounding note in its ramp colour over the page,
  without allocating. The cursor's system comes from `derivedStateOf`, so composition (and the
  page turn) happens only when it changes.
- Bar numbers sit on a baseline 1.4 spaces + 1 dp above the treble's top line, clear of the G clef's
  top (which rises 1.39 spaces), inside the reserved line and a few dp into the gap above.
- Agency: a horizontal drag of 40 dp or more shows the next (left swipe) or previous spread of
  pages and an outlined `SuggestionChip` "Follow" (surfaceVariant container, tertiary border) at
  the top right; tapping it, or the next change of the followed spread, follows again. A tap picks
  the slot, the nearest system and `barAtX`, and calls `onSeek(bar start)`. Semantics:
  "Score", the pages shown as its state, custom actions Next page, Previous page, Follow the music.

## Wiring

- `NowPlaying` gains `tempoMap`, `barStartsMicros`, `keySignatures`, `timeSignatures` (defaults for
  tests), filled from the `MidiPiece` in `Player.startCurrent`; its other fields are unchanged.
- `NotesLayout.STAFF` is `SCORE`; `NoteViews` shows `ScorePages` in every plan (with the keyboard
  strip when it is alone); a bar tap seeks as the scrubber does (`player.seek`, then the paused
  picture's settle frames). Short screens give the score 200 dp (one system), plus the strip alone.
  The title's piece sheet and the Up next sheet are as M10–M11 left them.
- Labels: `NoteDisplay.label` ("Paper roll", "Falling notes", "Score") and `WideLayout.label`
  ("Score and notes", "Notes only", "Score only") in `AdaptiveFrame.kt`; the saved names (`STAFF`,
  `STAFF_AND_NOTES`, `STAFF_ONLY`) are unchanged, so settings carry over.

## Measured (September 2026)

- Corpus (`CorpusTest -Pcorpus`): all 3,454 files lay out on a 411 × 600 dp phone panel and a
  1280 × 700 dp two-page tablet panel in 5 s; 20.9 % quantise (piano-midi.de 259 of 340, Mutopia
  102 of 111, MAESTRO 0 of 1,276).
- Frames on the emulator, 20 s of playback with the score visible (`dumpsys gfxinfo`): phone, score
  alone, 1 janky frame of 1,203 (0.08 %); tablet upright, score over the roll, 0 of 1,203; tablet on
  its side, two pages, 1 of 1,203 (0.08 %).

## Deviations from the plan, and why

- Key signatures and spelling in the key are in (the plan's "sharps only" is superseded, as the run
  asked); a file without FF 59 spells as C major, sharps, with the one-accidental-per-bar rule, so a
  C after a C♯ in a bar shows its natural.
- `ScoreLayoutEngine.layout` also takes `timeSignatures` (bar lengths alone cannot tell 6/8 from
  3/4), and `ScoreMetrics.forPanel` takes `ScoreWidth` (a pure enum, not the Compose width class)
  and the measured `numberHeight`.
- `ScoreLayout` holds more than the plan's arrays (accidental and dot positions, the chord's stem
  ends, duration ends, `treble`) so drawing does no layout work; `PageTurn.pagesShown` and
  `browsing` sit beside `visibleSystems`.
- Bar numbers are lifted over the G clef (the reserved eyebrow line alone let the clef's top touch
  a two-digit number).

## Tests added in M12

`TempoMapTest`, `BarsTest`, `SmfParserTest` (signatures, `FD 01` as three flats minor, collapsing,
bars through tempo changes), `QuantizeTest`, `SpellingTest`, `ScoreMetricsTest`, `PageTurnTest`,
`ScoreLayoutEngineTest`, `AdaptiveFrameTest` (the Score names, bars a system by width), and
`CorpusTest` laying out every corpus file on both panels.

---

# v1.2 — security and robustness (the audit)

`docs/SECURITY_AUDIT.md` lists the audit's eighteen findings and what became of each. These are the
contracts the fixes added; everything above stays except where this section says otherwise.

## Limits on what comes in

- `data/TextLimits`: text is cut on code-point boundaries before it is stored: title 200,
  composer 120, collection and playlist names 120, source path 512, display names 255, `INDEX.csv`
  fields 1,024; `PieceEntity.named` derives `searchText` (400) and `titleKey` (200) from what is
  kept. `data/db/TextRepair` cuts older rows with `substr` once, from Room's `onOpen`, guarded by the
  DataStore flag `libraryTextRepairDone`.
- `SmfParser`: at most `MAX_EVENTS` = 2,097,152 events ("This file has too many events."),
  `min(declared, MAX_TRACKS = 1024)` tracks (one reused per-track table), text metas read to 256
  bytes (cut back to a UTF-8 boundary) and 16 of a kind, 20 warnings then "...and N more.", and at
  most a day of music. `MidiPiece.events` is an `EventList` (`atMicros(i)`, `status(i)`,
  `data1(i)`, `data2(i)`, `firstAtOrAfter`, `lastMicros`), a `List<TimedEvent>` when read as one.
  The corpus digest in `CorpusTest` holds the output byte-identical.
- `data/imports/ImportLimits`: `INDEX.csv` 2 MB (larger: ignored); a zip at most 512 MB and 20,000
  entries, copied only with a 64 MB free-space margin; `TreeWalk` (pure, under `TreeWalker`) visits
  each folder id once, 16 levels deep, at most 20,000 MIDI files, 5,000 folders and 100,000
  documents, cancellable per folder; stale `import-*.zip` and `*.part` files swept at start.
- `OutOfMemoryError` is caught where files are parsed (importer: "File too large to read";
  `LibraryRepository.load`: "This piece is too large to play."; roll cards). Roll cards are drawn
  one at a time and kept gzipped in the cache (`RollCardFiles`).
- Intents: content URIs only, at most 500 (`SharedFiles.accepted`); no `file` scheme in the
  manifest; files from other apps wait for the person's Add in `ShareSheet`.

## Failing safe

- `Scheduler` catches any `Throwable` from a step: the engine stops (the stop sequence), then the
  failure is reported; the thread carries on.
- `CrashSilencer` (installed first in `App.onCreate`) calls `PianoLink.emergencySilence(200)` when a
  link exists and is connected, then hands the crash to Android's handler. `GattPianoLink` writes
  CC64 = 0 and CC123 straight onto the ready connection, retrying a busy stack for at most 200 ms;
  the fakes record the call.
- The Library's state goes to `unreadable` ("The library couldn't be read.") when a read fails.

## The link and pacing

- `LinkState.Connected(name, mtu, epoch)`: a new epoch for every connection and for every packet
  given up on; `Player` re-syncs a playing piece when it sees one. `LinkState.Error` carries
  `otherAddress` for `LinkError.OtherPiano`: the remembered address is pinned, another "Steven
  Piano" is offered after 3 s ("Connect to it"), never taken, and ignored while reconnecting.
- Packets carrying a Note Off, CC64 or CC120–123 (`BleMidiFramer.mustArrive`) are never given up
  while connected; a Note-On-only packet given up on is followed by the stop sequence.
- `PacedWriter` keeps at most 2,000 waiting messages, dropping waiting Note Ons only.
  `NoteRouter` passes a file's CC64 changes three at once, then one per 50 ms (latest wins,
  `flushPedal`, `pedalDueMicros`); the stop sequence and live sustain are never held back.

## Network and privacy

- `WikipediaUrls.pageLink` keeps a "From Wikipedia" URL only for `https://en.wikipedia.org/wiki/…`
  (no user info, no port); `WikipediaLink` opens it as a browsable VIEW or says "No browser is
  available to open this link." `allowed`/`hostOf` read URLs with `java.net.URI` (no user info,
  backslash, or port but 443); `Retry-After` at most a day; a too-deep JSON body is an `IOException`.
- R8 strips `Log.v/d/i`; URLs and file paths are logged in debug builds only. The piece sheet asks
  Wikipedia only with "Fetch artwork automatically" on, otherwise it shows "Fetch notes". At most 200
  automatic lookups a run for composers outside the canonical list.

## Drawing

The roll and the score's overlay look back at most 30 s (`NoteList.scanStart`) and draw at most
4,000 notes a frame; decoded pictures are at most 4 megapixels.

## Build

- Release signing: `~/steven-piano-keystore.properties` (storeFile, storePassword, keyAlias,
  keyPassword; path from `System.getProperty("user.home")`) feeds `signingConfigs.release`, APK
  Signature Scheme v2 and v3; without it `checkReleaseSigning` (before `preReleaseBuild`) stops the
  release build. The keystore and properties never enter the repository (`.gitignore`).
- `versionCode` 4, `versionName` 1.2; `dataExtractionRules` exclude everything from cloud backup and
  device transfer; StrictMode logs in debug builds; `ImportService.onTimeout` stops cleanly.

## Tests added

`SmfLimitsTest`, `TextLimitsTest`, `TextRepairTest`, `LibraryStatesTest`, `RollCardFilesTest`,
`SchedulerTest`, `CrashSilencerTest`, `TreeWalkTest`, `ImportLimitsTest`, `CanvasBudgetTest`,
`PieceNotesChoiceTest`, and additions to `ImporterTest`, `CsvReaderTest`, `PlaybackEngineTest`,
`PlayerTest`, `GattPianoLinkTest`, `PacedWriterTest`, `NoteRouterTest`, `WikipediaUrlsTest`,
`TitleHeuristicsTest`, `ArtFilesTest`, `ArtworkWorkerTest`, `SharedFilesTest`, `ImportCopyTest` and
`CorpusTest` (the corpus digest): 396 tests before, 472 after.

---

# v1.3 — M13: score fidelity (beams, rests, ties, tempo marks, dynamics)

Read `DESIGN.md › v1.3 › Score fidelity` first (its key-signature bullet shipped in M12). Everything
above stays except where this section says otherwise. The version stays 1.2 (`versionCode` 4) until
M14 completes v1.3; the provenance manifest is re-signed at the end of this run.

## The engine — `score/` (pure passes after M12's note placement)

- **Heads, not notes.** `ScoreLayout`'s arrays are per head: heads 0 until `noteCount` are each
  note's own head with M12's meaning; a sequenced note's further written pieces follow as tied heads
  (`headCount`; `tiedNote`, `tiedStartMicros`, `tiedHeadCount(note)`, `tiedHead(note, k)`), sorted by
  onset, with `ScoreSystem.firstTied`/`tiedEnd`. Drawing and the overlay treat every head alike.
- **Written notes** (sequenced files only; `WrittenNotes` in the engine): a note's onset and end are
  put on the sixteenth grid (`ppq / 4` ticks), it lasts a sixteenth at least, and it belongs to the bar
  its written onset is in (a note a hair early is the next bar's downbeat; M12 used the exact tick).
  A chord rolled or spread by a few ticks (each note less than 0.2 of a sixteenth after the one
  before, all within a sixteenth of the first) is one chord, struck at its first note's tick: in the
  quantised corpus the gaps under 0.2 sixteenth (2,667) are rolls and humanising, while 64ths (0.25)
  and triplet 32nds (0.33) stay apart.
- **Ties** (`Ties.kt`): a written note is split at every bar line it crosses and, within a bar, into
  the largest single value (plain or dotted) that fits plus the remainder (5 sixteenths = 4 + 1,
  7 = 6 + 1); in a compound metre (6/8, 9/8, 12/8 …) the plain half and whole are left out, so a full
  bar of 9/8 is 12 + 6. Each piece's value is `Quantize.value` of its length. Caps: 64 heads a note,
  tied heads at most `max(4,096, 2 × notes)` a piece; a piece under half a sixteenth is dropped and
  nothing is written past the last bar. The first piece keeps the note's accidental; tied heads have
  none, never touch `BarAccidentals`, and keep the note's spelling across a key change. Arcs
  (`ScoreTies`: ends, `above`, `from`/`to` heads, sorted by system): from the right edge of a head (after
  its dot) to the left edge of the next, 0.35 space off the heads away from the stem (a whole note by
  its side of the middle line); across a system break two halves, the first to the system's end (at
  least a space) with `to = -1`, the second from 1.5 spaces before its head with `from = -1`.
- **Onsets and chords**: notes' heads and tied heads are merged by tick; chords are M12's (same bar and
  tick when sequenced, 30 ms when performed), so a tied chord shares one stem and a tied eighth can be
  beamed. `stems()` records each stem owner's value group (lowest and highest heads, column, position
  sum) and whether the chord is one flagged value.
- **Rests** (`Rests.kt`): per bar and staff, every silence of a sixteenth or more (from the bar's start,
  where all written values so far have ended, and to the bar's end) is tiled with the largest plain
  values on the beat grid: under a beat, inside one beat at a multiple of itself (in a compound metre a
  quarter or eighth rest on any eighth of the beat); a beat or longer, on a multiple of itself from the
  bar's start, never in a compound metre. A silent bar is one whole rest centred in it (any metre).
  `ScoreRests`: glyph value in sixteenths, `wholeBar`, staff, bar; a whole rest hangs from the fourth
  line, the others sit on or centre on the middle line. At most 64 rests a silence. The onset after a
  rest is marked, and ends a beam.
- **Beams** (`Beams.kt`): beat groups of a quarter (x/4 and any other metre), a dotted quarter (6/8,
  9/8, 12/8) or a half (2/2); consecutive onsets of one bar and beat that are each one flagged value
  form a group, broken by a rest, a quarter or two values struck together (those keep M12's stems and
  flags); a group of one keeps its flag. Stems up when the heads' average position is below the middle
  line; one straight beam whose slope follows the first and last far heads, held to one space over the
  group and to a quarter of its width, 3.5 spaces clear of the nearest far head, reaching the middle line
  at every stem. `ScoreBeams`: one entry per segment (outer edge x1,y1 to x2,y2, `up`, `level` 1 or 2,
  `stub`, `group`), sorted by system; primary 0.5 space thick, secondary 0.75 space in (centre to
  centre); a lone sixteenth's stub is a head wide, into the group (inside it, back toward the note it
  completes an eighth with when its onset is off the eighth grid). Beamed notes have no flags.
- **Tempo marks** (`TempoMarks.kt`, every file): the first system gets `TempoMap.tempoAt(0)` as
  `round(60,000,000 / µs a quarter)` (a dotted quarter's in a compound metre: U+E1D5 with U+E1E7), and any
  later system whose starting tempo is more than 10 % from the last mark's gets its own;
  `TempoMark(system, x, bpm (1..9,999), dotted)` with `text` "= N"; `x` is the first bar's left.
- **Dynamics** (`Dynamics.kt`, every file): each bar's mean velocity of the notes struck in it (both
  staves) in bands pp < 32, p < 48, mp < 64, mf < 80, f < 96, ff; the first bar with notes is marked,
  then a sequenced file marks where the mean has moved 3 velocity units past a band edge (a Schmitt
  trigger: the band of the mean less 3 going up, plus 3 going down) and a performance where the band is
  two or more from the last mark; silent bars neither mark nor reset. `DynamicMark(system, bar, x, y,
  band)`: x the bar's first onset (a chord's leftmost head), y the baseline with the p's and m's tops
  1.5 spaces under the treble staff, or half a space under the lowest treble head, accidental or
  down-stem under the mark when one reaches further, never into the bass staff (`Dynamics.width` gives
  the glyphs' width). `ScoreLayout.tempoMarkIn(s)` and `dynamicsIn(s)` find a system's by binary search.
- `BySystem` (a stable counting sort) orders beams, rests and ties by system; `runOf` gives a system's run.

## The painter — `ui/components/ScorePages.kt`

- Glyphs, measured once: rests U+E4E3–E4E7, dynamics p/m/f U+E520–E522 set as words ("mp" = m then p),
  and the tempo mark's note U+E1D5 (+ dot U+E1E7) in Bravura at 1.5 × the eyebrow's size in sp, so it
  grows with the text beside it (the rest of the score stays in dp).
- Each page's cached layer builds, per system: the beams as one filled `Path` (a parallelogram per
  segment, the thickness toward the heads), the ties as one stroked `Path` (quadratic arcs, 1 dp, rising
  0.15 of their length within 0.3–0.9 space), and the tempo mark measured and placed: after the bar
  number on the numbers' baseline (over the clef and key signature), its note's head on the baseline,
  then "= N" in the eyebrow style, lifted half a space clear of any stem, flag, beam, head, accidental or
  upward tie under it and never above its band. Then it draws rests, ties, beams, notes, tied heads and
  dynamics. Colours: rests, ties, beams and heads `onSurfaceVariant`; tempo marks and dynamics the glyph
  colour (`onSurfaceVariant`); nothing new is ever red.
- The overlay (per frame, no allocation): sounding notes as before, and each note's tied heads lit from
  their written onset (`tiedStartMicros`) to the note's end, wherever a shown page holds them (a note
  whose first head is on a page not shown still lights its tied heads). Beams, ties, rests and marks stay
  in the static colour.

## Measured (September 2026)

- Corpus (`CorpusTest -Pcorpus`, which now also checks every tied head, beam, rest, tie, tempo mark and
  dynamic of every file on both panels): 3,454 files lay out on both panels in 5.3–7.3 s (7.1 s in the
  final run; 5.5 s before M13); on the phone panel 34,268 tied heads, 44,840 tie arcs, 178,172 beamed
  groups, 205,132 rests, 10,316 tempo marks and 74,350 dynamics. The parse digest is unchanged.
- On the phone panel: Clair de lune (9/8) 188 tied heads, 167 beamed groups, 115 rests, 13 tempo marks
  (piano-midi.de writes its rubato as 733 tempo changes), 8 dynamics; Bach's C major prelude 255 beamed
  groups, 4 tempo marks, 1 dynamic (mp); Chopin's op. 27 no. 2 (6/8, quantised at 80.3 %) 229 beamed
  groups, 380 rests, 10 tempo marks, 15 dynamics; MAESTRO's op. 9 no. 2 one tempo mark (♩ = 120) and
  three dynamics (mp, f at bar 92, mp at 96).
- Frames on the phone emulator, 20 s of Clair de lune with the score alone (`dumpsys gfxinfo`, debug
  build): 1 janky frame of 1,202 (0.08 %).

## Deviations from the run's rules, and why

- Compound metres leave the plain half and whole out of the tie values (the rule said the largest value
  plus the remainder): a full 9/8 bar written as a whole note tied to an eighth hides the dotted-quarter
  beat; it is a dotted half tied to a dotted quarter.
- Sequenced files mark a dynamic only 3 velocity units past a band edge (the rule said at every change of
  band): piano-midi.de shapes each phrase's velocities, and Clair de lune's first twelve bars (means
  28.6–38.3 around p's floor of 32) flipped p/pp every bar: 22 marks became 8, Bach's prelude 5 became 1.
  Performances keep the two-band rule as written.
- The tempo mark starts after the bar number, over the clef, and is lifted when notes reach into its line
  ("at the first bar's left"): set where the first bar's notes begin, stems of the upper voice ran through
  it. Dynamics move down under low treble notes (middle C's ledger line touched Bach's mp).
- Beam slope is also held to a quarter of the group's width: two sixteenths a sixteenth apart on a phone
  otherwise got a flag-steep beam. Ties start after a dotted head's dot, not across it.
- Rolled chords are one chord (not in the rules): six notes 20 ticks apart made six stems and six tie
  chains.
- A chord of two values struck together is not beamed (its values keep their own stems, as M12's voices
  rule gives them); rests are plain values only, never dotted (the rules' own example).
- ScoreLayoutEngineTest's note-values test now expects its beamed eighth and sixteenths without flags.

## Tests added in M13

`BeamsTest` (14), `RestsTest` (10), `TiesTest` (13), `TempoMarksTest` (6), `DynamicsTest` (8),
`ScoreFixtures` (the pieces they share), and the engraving checks and counts in `CorpusTest`: 472 tests
before, 523 after.

---

# v1.3 — M14: the waterfall format (hands, suggested fingering, chord names) and release 1.3

Read `DESIGN.md › v1.3 › The waterfall format` first. Everything above stays except where this
section says otherwise. This run completes v1.3: `versionCode` 5, `versionName` "1.3",
`Provenance.text` "Made by Steven Jin · v1.3 · eab16a502f679465" (the About row), the Wikipedia
User-Agent `StevenPiano/1.3 (…)`; the provenance manifest is signed again last.

## Parser — `midi/`

- Every note keeps the track its Note On came from: `RawEvents.add` takes the track (0-based, in file
  order; a `Short`, as at most `MAX_TRACKS`, 1,024, are read), `merge` carries it beside the packed
  words, and `pairNotes` stores it per note: `NoteList.track(i)` (a `ShortArray`; a list built by hand
  has every note on track 0).
- `MidiPiece.trackNames`: one entry per track read, its first track-name meta (FF 03, decoded to
  `MAX_TEXT_BYTES`, 256), or "" without one. `sequenceNames` (Track 0's names) is unchanged.
- Timing and pitch are untouched: the corpus digest is still `7192757e…870cc5`.

## Hands — `score/Hands.kt` (pure)

`Hands.assign(notes, trackNames, tempo = TempoMap.constant(480), timeSignatures = [4/4]): ByteArray`,
`RIGHT` 0 and `LEFT` 1, by the first rule that applies:

1. **Names** (`classify`): "right", "rh", "r.h", "treble", "upper" against "left", "lh", "l.h",
   "bass", "lower" (and "righthand", "lefthand"), as whole words in any case. A name is split at
   anything but letters and dots and where lower case turns to upper ("PianoRH"); a word of four
   letters or more may carry one letter more (LilyPond's "uppera", "lowerb"). A name that says both
   hands says neither. With exactly two tracks carrying notes and one of them named, the other is the
   other hand; notes of any other unnamed track are split by pitch (rule 3, measured against every
   note). Names that put every note in one hand decide nothing.
2. **Two tracks** carrying notes, unnamed: the higher median pitch (then mean) is the right hand.
3. **Pitch split**: each note against Otsu's threshold of the notes sounding within `WINDOW_MICROS`
   (1 s) either side of its start: the cut between a lower and a higher group that maximises
   n0 · n1 · (mean0 − mean1)², from a sliding pitch histogram (notes leave by a heap on their ends;
   occupancy bits, so a split visits only the pitches present). A window spanning an octave or less
   (`ONE_HAND_SPAN`) is one hand, by middle C, as is a note on the threshold. Then melodic runs keep
   one hand (`smoothRuns`): consecutive onsets (notes within 30 ms are one onset) each holding exactly
   one note shorter than the beat group, in one beat group (`Beams.beatSixteenths`: a quarter, a
   dotted quarter in compound metres, a half in 2/2), each starting at most an eighth of the beat after
   the last one ends and within an octave of it, at most 64 notes; a run of two or more takes the hand
   most of its notes have (its first note's on a tie).

## Staves by hand — `ScoreLayoutEngine`

`layout(…, hands: ByteArray? = null, fingers: ByteArray? = null)`. With hands, a note goes on its
hand's staff (a right-hand B3 on the treble staff); one more than `MAX_HAND_LEDGERS` (4) ledger lines
off it goes to the other staff when it needs fewer there (the right hand below C3, the left above C5).
Tied heads copy their note's staff; accidentals, chords, stems, rests, beams, ties and dynamics
already worked per staff. Without hands, M12's split at middle C.

## Suggested fingering — `score/Fingering.kt` (pure)

`Fingering.assign(notes, hands, transpose = 0, fold = true): ByteArray`, 1–5 or `NONE` (0), each hand
on its own, on the keys the piano plays (`KeyMap` with transpose and folding; a note it can't play
gets none). A piece's first `MAX_NOTES` (250,000) notes are fingered (the corpus's largest file has
25,076).

- **Events**: the hand's notes starting within `CHORD_MICROS` (30 ms) of the first; their distinct
  keys in rising order, the left hand mirrored (keys negated) so both hands read as a right hand.
- **States**: every rising finger order for one to four keys (5, 10, 10 and 5 states); five keys go
  1–5; more are spread (the lowest the thumb, the highest the little finger, each between the finger
  its place in the chord's span gives, or the next free one; past the fourth finger, none).
- **Costs**, summed along the line; a Viterbi over the events finds the cheapest:
  - stretch between fingers a < b over d semitones (b's key less a's), from each pair's spans
    (smallest comfortable; relaxed from–to; largest comfortable): 1–2: −3; 1–5; 7 · 1–3: −2; 3–7; 10 ·
    1–4: −1; 5–9; 12 · 1–5: 1; 7–10; 14 · 2–3, 3–4, 4–5: 1; 1–2; 3 · 2–4: 1; 3–4; 5 · 2–5: 2; 5–6; 8 ·
    3–5: 1; 3–4; 5. Free within the relaxed span; past it 1 a semitone for pairs with the thumb (2
    without) up to the largest comfortable span, then 2 a semitone; short of it 2 a semitone with the
    thumb (1 without), and 2 more a semitone below the smallest comfortable span (Parncutt et al.'s
    large-span, small-span and stretch rules);
  - crossings (d < 0): the thumb under 2, 3 or 4, or those over it, `CROSS_OK` 3; any other
    `CROSS_BAD` 12;
  - `THUMB_ON_BLACK` 1; `WEAK_FOURTH` 0.5 a use of finger 4; `SAME_FINGER` 4 for one finger on a new
    key (a repeated key keeps its finger for nothing); `HELD` 6 for a finger still holding a key
    `HELD_MICROS` (100 ms) into the next event;
  - the hand's move: the thumb's implied place (each finger over its own white key; keys placed in
    half white keys, a black key between its neighbours) shifting, `POSITION_STEP` 0.25 a white key,
    `POSITION_CHANGE` 1 more past two white keys and 1 more past seven;
  - chords: every pair's stretch; to or from a chord only its outer keys move (thumb side to thumb
    side, little-finger side to little-finger side), without the same-finger cost, as a shape moves;
    held fingers are checked key by key.
- Single-to-single and chord steps come from tables (per finger pair, ±48 semitones and ±64 half
  white keys; the functions past them), the chord before's states grouped by their outer fingers.

Figures on the score (`ScoreLayoutEngine.numerals`, after the beams, when every stem is final): per
onset and hand, a stack over the right hand's heads (the highest note's figure on top) and under the
left hand's (the lowest note's at the bottom), centred on the chord's column, `FINGER_CLEARANCE`
(0.4 space) clear of what that staff holds there, `FINGER_GAP` (0.2 of a figure's height) between
figures. What a staff holds comes from per-system `Profiles`, half a space a column: heads with their
ledger lines and accidentals, stems with flags, beams (`ScoreBeams.treble` now records each beam's
staff) and ties. Output: `ScoreFingers` (system, x, baseline, finger, above, note), sorted by system;
`ScoreMetrics.numeralHeight` and `numeralWidth` carry a figure's measured cap height and advance
(8.6 and 6.8 dp at font scale 1).

## Chord names — `score/Chords.kt` (pure)

`Chords.detect(notes, tempo, bars, timeSignatures, keySignatures): ChordTrack`: for each chord its
start, its root and bass pitch classes (bass −1 when it is the root), its quality (`MAJOR` … `MAJ9`)
and the key it is spelled in, in the file's pitches; `ChordTrack.name(i, transpose)` spells it
transposed ("B♭/D", "F♯m7").

- **Windows** over the bars: a beat (a quarter in x/4, an eighth in x/8), a dotted quarter in compound
  metres (6/8, 9/8, 12/8), half a bar in x/2, the whole bar in 3/8; at least an eighth, at most 16 a
  bar, at most `MAX_WINDOWS` (20,000) a piece. A performance timed at 120 BPM (MAESTRO) gets half a
  second a window.
- **Weights** per pitch class: each note's share of the window, doubled when it is struck in the
  window; a note running on from before counts only when it fills `TAIL` (a fifth) of the window.
  Drums (channel 10) are left out; at most 512 notes a window. **Bass**: the lowest key struck in the
  window's first half, or held from before past the tail.
- **Scores** for the 144 chords: the weight of its tones − `OUTSIDE` 0.6 × the weight of the rest −
  `MISSING` 0.2 a tone absent + `DOUBLED` 0.1 an extra key on its root (two at most) + `ON_BASS` 0.15
  when its root is the bass − `EXTENSION` 0.4 a tone past the triad (6, 7, maj7, m7, add9: one; maj9:
  two).
- **Evidence**: a window counts only with two pitch classes or more and more than one line sounding
  (its notes' shares add up past `MIN_VOICES`, 1.25).
- **Decoding**: a Viterbi over the windows with the scores as evidence (a window without evidence
  changes nothing) and `CHANGE` (1.2) for each change of chord. A stretch of one chord is named when
  its scores summed over the stretch beat every chord of other notes by `MARGIN` (0.1): C6 and Am7,
  the same notes, are told apart by the bass; a bare fifth is neither major nor minor. Its name starts
  at the stretch's first window with evidence, at the first of its tones struck there. Otherwise, and
  in silence, the name before holds; a name comes only when the chord changes, one a window at most.
- **Spelling**: the key signature in force, or for a file without one the key its notes suggest
  (pitch classes weighted by duration, at most 4 s a note, against Krumhansl and Kessler's profiles).
  A root in the key takes the key's spelling (`Spelling`), as does the natural of a letter the key
  alters; any other root is a sharp for a diminished chord (a leading tone: F♯dim in C) and a flat for
  the rest (B♭ in C, E♭ in G). A slash bass that is a chord tone is spelled from the root (B♭/D,
  Caug/G♯); any other as a root in the key.

## The score — `ScorePages`

- `ScorePages(…, hands, fingers, chords)`. `ScoreMetrics.forPanel(…, numeralHeight, numeralWidth,
  chordHeight)`: with chord names, a chord line above each system's bar-number line (the measured
  `bodyMedium` line, plus what the bar number rises over its own line, plus 2 dp); 0 without them.
- The static page layer draws the fingering figures (`labelSmall`, the eyebrow's size, with `Tabular`,
  untracked, `onSurfaceVariant`; they grow with the font scale only to 1.3×, `NUMERAL_MAX_SCALE`) and
  the chord names (`bodyMedium`, `onSurfaceVariant`) at each chord's x: a name's box sits 2 dp above
  the bar number's, lifted clear of notes, figures and the tempo mark (whose box `SystemMarks` now
  records) but never above the system's band, and moved left to stay on the page; a name that would
  come within 6 dp of the one before is left out on the score (the waterfall still shows it). The
  tempo mark's lift now sees the figures too.

## The waterfall — `NoteCanvas`, `KeyboardStrip`

- `NoteCanvas(…, hands, fingers, chords)`: right-hand bars filled as before; left-hand bars outlined,
  `surfaceVariant` inside a 1 dp line in the ramp's colour drawn just within the bar (rounded on the
  paper roll).
- A bar at least `NUMERALS_TALL` (3) figures tall and three quarters of one wide carries its finger at
  its leading edge (the bottom, which meets the line first in both styles), 2 dp in and clear of a
  perforation's round end: in `surfaceVariant` inside a filled bar, `onSurfaceVariant` inside an
  outlined one.
- Chord names: a `labelSmall` label in the chord's own case, `onSurfaceVariant`, on a `surfaceVariant`
  backing 4 dp larger than the text, 4 dp from the left edge, the backing's bottom on the line where
  the chord begins, travelling with the notes; each distinct name measured once per piece and
  transpose; nothing allocated per frame.
- `KeyboardStrip(…, hands: KeyHands?, clock: SongClock?)`: a key only the left hand is playing is
  outlined (1.5 dp) instead of filled. `KeyHands` finds the notes sounding at the frame's song
  position (±30 ms, so a key just struck or not yet let go still finds its note) by hand, as four
  64-bit words, without allocating.
- **Hand colours**: `Color.kt` `HandLeftDark` #6AA080, `HandRightDark` #7A97B8, `HandLeftLight`
  #3D6C50, `HandRightLight` #3E6189 (WCAG on surface / elevated surface: dark 6.4 / 5.8 both; light
  left 5.4 / 5.8, right 5.7 / 6.1; the floor is 3:1). `Theme.kt`: `HandTones`, `LocalHandTones`
  (provided by `PianoTheme`, dark or light) and `LocalHandColours` (provided by the nav host from the
  settings). Only `NoteCanvas` and `KeyboardStrip` read the tones, and only while the switch is on and
  the piece has hands: each hand's ramp runs from its colour to that colour mixed 45 %
  (`HAND_SOUNDING_MIX`) toward `onSurface` as it sounds, and a lit key takes the mixed colour, filled
  or outlined. Off, all stays monochrome.

## Wiring

- `NowPlaying` gains `hands` (`handsOrNull`), `fingers` with the `fingersTranspose` and `fingersFold`
  they were worked out for (`fingersFor(transpose, fold)` gives them only when those match) and
  `chords`.
- `Player` (new `compute` dispatcher, `Dispatchers.Default`) works them out as a piece loads, before it
  is shown and played: the chords concurrently with the hands and then the fingering. A failure (an
  exception or `OutOfMemoryError`) leaves that part empty and the piece plays. A change of transpose
  or folding works the fingering out again (`refinger`); none shows meanwhile.
- `NowPlayingScreen` gives the views the hands always, and fingering and chords only while their
  switches are on.
- Settings: `fingering` (true), `chordNames` (true), `handColours` (false), keys "fingering",
  "chordNames", "handColours". Piano › App preferences, after Note display (and Wide layout on wide
  windows): "Fingering", "Chord names", "Hand colours" with the note "Colours the two hands on the
  waterfall and the keyboard strip" (the v1.3 delta audit's copy fix; it read "waterfall only").

## Measured (September 2026)

- Tests: 560, none failing or skipped. `CorpusTest -Pcorpus` took 11.6 and 14.2 s for its five tests
  over the last two runs; its layout test, which now also works out every file's hands, fingering and
  chords and lays the file out with them, 5.7 and 6.5 s of that, on half the processors. The parse
  digest is unchanged.
- Analysis over the corpus (3,454 files, 15,869,424 notes), one thread, warm: hands 1.8 s, fingering
  2.6 s, chords 3.8 s; the slowest file, Beethoven's op. 106 (23,469 notes), 12.9 ms for all three.
  55.6 % of notes in the right hand, 99.9 % fingered, 15,860,444 figures on the phone panel's score,
  803,556 chord names (one every 2.0 s of music).
- Hands: with the 324 piano-midi.de files that name both hands merged into one track, the pitch split
  gives 90.5 % of notes the hand their track names (the plan's median rule: 86.3 %).
- Chords: Bach's C major prelude (piano-midi.de) reads, bar by bar from 1 to 22: C, Dm7/C, G7/B, C,
  Am/C, D7/C, G/B, Cmaj7/B, Am7, D7, G, Gdim, Dm/F, Fdim, C/E, Fmaj7/E, Dm7, G7, C, C7, Fmaj7,
  F♯dim. piano-midi.de: 52,097 names in 340 files (one every 1.6 s), 6.7 % of them maj9; MAESTRO:
  341,931 in 1,276 performances (one every 2.1 s), 5.5 % maj9.
- Frames: the phone emulator as a tablet held upright (1600 × 2560 px at 320 dpi, 800 × 1280 dp: the
  score over falling notes), fingering and chord names on, 20 s of the Bach prelude, debug build: 4
  janky frames of 1,201 (0.33 %); 50th, 90th and 99th percentiles 22, 25 and 29 ms.
- Release: `apksigner` verifies v2 and v3, signer "CN=Steven Piano, O=Steven Jin, C=US"; `aapt2`
  reads versionCode 5, versionName 1.3; the provenance string is in `classes.dex`.

## Deviations from the run's rules, and why

- Hands, names: whole words, not any name containing one ("Bassoon", "Copyright" and "Flower"
  contain "bass", "right" and "lower").
- Hands, rule 3: Otsu's threshold instead of the window's median (90.5 % against 86.3 %, above). A
  window within an octave is one hand, by middle C (any split halves a melody alone). Runs are smoothed
  only where the pitch split decided. `assign` takes the tempo map and time signatures as optional
  arguments, for the beat groups.
- The engine moves a note more than four ledger lines off its hand's staff to the other one (a
  pitch-split outlier would otherwise hang eight ledger lines under the treble staff).
- Fingering: the plan's spans (1–2: 5–7 and so on, +2 a semitone beyond) are read as the largest
  relaxed and the largest comfortable span, with Parncutt's smaller spans under them and his graded
  costs between. The hand's move is counted in white keys, not semitones (in semitones the Alberti
  bass's E was as much a 2 as a 3). Finger 4 costs 0.5 (Parncutt's weak-finger rule: it settles C–E–G
  as 1-3-5 over 1-2-4), and a finger still holding a key costs 6 to reuse. Chords of six keys or more
  leave some keys without a figure.
- The fingering is worked out again when transpose or folding changes, not only once a piece: which
  keys are black decides where the thumb goes.
- Score figures are placed against per-system profiles: the tempo mark's check (above) and the
  dynamics' (below) made per staff, half a space a column, and built once a system, where a scan of
  the system's notes for each of the corpus's 15.9 million figures would not do. They grow with the
  font scale only to 1.3× (at 2× a sixteenth's figure ran into the next one's).
- On the waterfall the figure inside a filled bar is in `surfaceVariant`, not `onSurface`: a sounding
  bar is itself `onSurface` (1:1), and on an upcoming one, `onSurfaceVariant`, `onSurface` reads at
  2.3:1 (dark) and 2.6:1 (light); the knocked-out figure reads at 5.8:1 or more. The leading edge is
  the bottom in both styles: the plan's "bottom of a rising roll bar" assumed a rising roll, and here
  both styles travel downward. A bar narrower than three quarters of a figure (a black key's lane on a
  phone) carries none.
- Chords, decoding: a Viterbi over the windows with a cost for each change, not a label decided window
  by window: beat by beat, an arpeggio whose third arrives on its second beat was named a beat late and
  a passing note flipped the name back and forth. `CHANGE` is 1.2: at 0.6 the prelude flips between
  diminished triads in bars 12, 14, 22 and 23, and piano-midi.de gets 62,329 names instead of 52,097;
  at 2.0 real one-bar chords were lost (Für Elise's).
- Chords, evidence: a window needs more than one line sounding (`MIN_VOICES`: a fugue's subject alone
  read Dsus2, Fsus2, Esus4), and a note merely ringing into a window counts only past a fifth of it
  (`TAIL`), so the chord before does not colour the next beat.
- Chords, scores: `EXTENSION` (0.4 a tone past the triad) is not in the plan: without it maj9 is
  18.7 % of piano-midi.de's names and 22.8 % of MAESTRO's, and the prelude reads C6 in bar 4 and Cmaj9
  in bar 34. The plan's root-doubling bonus is `DOUBLED`, 0.1 a key (two at most), and `ON_BASS` (0.15
  for the root in the bass) is added: enough to tell C6 from Am7, never enough to outweigh a missing
  tone. The margin is measured over the whole stretch against chords of other notes (a chord of the
  same notes is no alternative: the bass decides that).
- Chords, bass: the lowest key struck in the window's first half or held into it (the plan: the lowest
  sounding at the window's start), so a bass played just after the beat, as in every performance,
  still counts.
- Chords, spelling: outside the key, a diminished chord's root is a sharp and any other root a flat,
  in sharp keys too (E♭ in G major is written so; the plan said sharps in sharp keys). A file without
  a key signature is spelled in the key its notes suggest, while the score still writes it in sharps
  (M12).
- Chord labels on the waterfall have the eyebrow's size and tracking but the chord's own case ("Am",
  not "AM"). On the score a name is moved left to stay on the page, and one that would run into the
  name before it is left out.
- The player works hands, fingering and chords out as a piece loads rather than beside the score's
  layout: the waterfall needs them without the score, and both take them from `NowPlaying`.
- The hand tones reach the views through `LocalHandTones` beside the plan's `LocalHandColours`, so
  the colours stay out of the Material scheme, as the live red does.
- The keyboard strip outlines a left-hand key 1.5 dp thick (keys are narrow: a 1 dp line barely
  shows).
- `ColorTokensTest` checks the WCAG formula against WCAG's own figures (21:1; #767676 on white 4.54:1).
  `DESIGN.md › Colour` gives #F2F2F2 on #0E0E0E as 16.9:1, which the formula puts at 17.2:1; the
  table is left as it is.
- `CorpusTest` runs its files in parallel (half the processors); each file's digest is combined in
  file order, so the digest is the same.
- Emulator evidence for the stacked layout was taken with the phone emulator at 1600 × 2560 (a tablet
  held upright, medium width, where the score stands over the falling notes); at 2560 × 1600 (on its
  side, expanded) the app puts the score and the roll side by side.

## Tests added in M14

`HandsTest` (10: names read as whole words; names over pitches; one named track of two; a third,
unnamed track split by pitch; the two-track median rule; a name for one hand alone; the bass against
the tune; an octave's window by middle C; a beamed run kept in one hand where the split would cut it;
runs stopped by a rest, a leap and the beat), `FingeringTest` (9: the C major scale, right hand
1-2-3-1-2-3-4-5 and left 5-4-3-2-1-3-2-1; octaves 1–5; a thumb kept off a black key; repeated notes;
a five-note chord spread 1–5; a triad 1-3-5 and the Alberti bass 5-1-3-1; each hand on its own and
unplayable notes left bare; stretch and crossing costs), `ChordsTest` (9: C, Am, C7, Ddim; G/B and
B♭/D; spelling in E♭ major, in a key found from the notes, borrowed and leading-tone chords in C, and
transposed; Gsus4, Fmaj9 and Csus2; names only at changes; an ambiguous stretch and silence keeping the
name before; a single line naming nothing; an arpeggio named from its first beat; slash basses spelled
from the chord), `ColorTokensTest` (3), `SmfParserTest` (+1: tracks and their names),
`ScoreLayoutEngineTest` (+2: hands on their staves, with the four-ledger rule and tied heads; figures
above and below, stacked, clear of an up stem), `ScoreMetricsTest` (+1: the chord line),
`SettingsRepositoryTest` (+1: the three switches' defaults, remembered), `PlayerTest` (+1: hands and
fingering arrive with the piece, and transposing fingers it again); `CorpusTest`'s layout test now
analyses every file and lays it out with its hands and fingering. 523 tests before, 560 after.

# v1.3 — the delta audit's fixes (versionCode 6)

The v1.3 delta audit (`docs/SECURITY_AUDIT.md` › v1.3 delta) bounds what a crafted file may cost the
score and the waterfall, and versionName stays 1.3 (versionCode 6). `ScoreLayoutEngine` writes at most
`restBudget(n) = max(4,096, 2n)` rests and `tiedBudget(n) = min(max(4,096, 2n), 100,000)` tied heads,
lays a piece of more than `MAX_ENGRAVED_NOTES` (100,000) notes out as performed, allocates no
engraving arrays for a performed piece, finds the metre by binary search, keeps each system's
`ScoreSkyline` for the painter, and calls a `checkpoint` between its passes (as `Hands`, `Fingering`
and `Chords` do every few thousand notes or windows; it throws to stop the work); `Ties.segments`
cuts a note after `MAX_EMPTY_BARS` (64) empty bars; `Chords` keeps a forward index into the key
signatures; `SmfParser` keeps 4,096 time and 4,096 key signatures and warns once, "Too many signature
changes; some were ignored." `ScorePages` lays out through `layOut` (`ScoreLaid.kt`): an
`OutOfMemoryError` or `RuntimeException` shows "This score is too large to show." (Body,
`onSurfaceVariant`, centred), cancellation passes through, a layout is drawn only with the notes it
was made for (`madeFor`), a re-layout of the piece shown waits `RELAYOUT_SETTLE_MS` (150 ms), and a
page draws at most `MAX_NOTE_DRAWS` rests, numerals, beam and tie segments a system, reading the
skyline for its tempo mark and chord names; the player's piece start and refingering wait 150 ms
(`SETTLE_MS`) when they replace one still running; `NoteCanvas` draws only chord names clear of the
one before (chosen once per scale) and at most `MAX_CHORD_DRAWS` (64) a frame. Tests: 583, adding
`ScoreLayoutBudgetTest`, `ScoreLaidTest`, `AnalysisCancelTest`, `ScoreSkylineTest` and cases in
`ChordsTest`, `SmfParserTest`, `TiesTest`, `CanvasBudgetTest` and `PlayerTest`.

# v1.3.1 — the link explains itself (versionCode 7)

The owner's first connection on the piano failed without a reason. `versionCode` 7, `versionName`
"1.3.1", `Provenance.text` "Made by Steven Jin · v1.3.1 · eab16a502f679465". `GattPianoLink` logs its
milestones with `Log.w` under `PianoLink` (R8 keeps `Log.w`): the connect request; each scan with its
filter and mode; each device a scan sees, once per address per scan (address, name or "(no name)",
RSSI, whether it advertised the MIDI service, and the decision); each connectGatt with autoConnect;
connections made and lost with the GATT status (`BleCodes` names the common ones and makes an
advertised name safe for one line); the MTU; the services (MIDI, console); the retry; each failure
with its reason and the status or "timeout". `LinkError` is a sealed type: `NotFound(locationOff)`
only from a scan's timeout (the Location sentence added when Location Services are off);
`ConnectFailed(status)` after the one retry (null: the 15 s timeout; −1: connectGatt gave no
connection); `ScanFailed(code)` from onScanFailed (−1, no scanner, reads as `BluetoothOff`); `Paired`
when `BleRadio.isBonded`; `BluetoothOff`, `PermissionMissing`, `LocationOff`, `Unsupported`, `Failed`
(no MIDI characteristic) and `OtherPiano` as before. Before each scan `BleRadio.connectedDevices()`
(GATT and GATT_SERVER) is asked for the remembered address, or with none remembered a device named
Steven Piano, and a piano another app holds is connected to directly. With no piano remembered, a
nameless result that advertised the MIDI service is a candidate: connected to after
`NAMELESS_GRACE_MS` (1 s) without a named Steven Piano, or at the scan's end; after discovery its GAP
Device Name (0x1800/0x2A00) is read (3 s at most): "Steven Piano" makes it the piano, anything else
disconnects it and the scan goes on without it until the next Connect. `guard` catches
`IllegalStateException` as well; after `BluetoothOff`, the adapter coming back on clears the error
and, with auto-connect on, connects. The card adds Open Bluetooth settings beside Retry for `Paired`,
and for `NotFound` the tip "if the piano's screen reads “BLE MIDI: CONNECTED”, another device is
connected to it" (the firmware's status label) with Retry and, when Location was off, Open Location
settings. Tests: 605, adding `LinkErrorCopyTest` and 17 cases in `GattPianoLinkTest`.

# v1.4 — updates, silent installs, diagnostics, publishing (versionCode 8)

Read `DESIGN.md › v1.4` first. Everything above stays except where this section says otherwise.
`versionCode` 8, `versionName` "1.4", `Provenance.text` "Made by Steven Jin · v1.4 · eab16a502f679465"
(the About row); the User-Agent now takes its version from `BuildConfig` ("StevenPiano/1.4 (…)").

## Network policy (adds to M11's)

- Four hosts more, for updates and nothing else: `raw.githubusercontent.com` (the manifest, only
  under `/stevenjin20090101-rgb/steven-piano-android/`), `github.com` (only
  `/stevenjin20090101-rgb/steven-piano-android/releases/download/<tag>/<file>`, two plain segments),
  and GitHub's redirect targets `objects.githubusercontent.com` and
  `release-assets.githubusercontent.com` (any path: signed, expiring addresses).
  `UpdateSource.allowsHop` passes every hop, redirects included; `allowsApk` passes a manifest's
  file (a release asset of this repository ending `.apk`, no query or fragment, no `.`/`..`
  segment). HTTPS only, port 443, no user info, no backslash, hosts compared exactly, as
  `java.net.URI` reads the address.
- `net/HttpFetch.kt` is the Wikipedia client's path made general: `HttpFetch(allowed, accept,
  connectTimeoutMs = 10 s, readTimeoutMs, transport, userAgent, log)`; `exchange(url) { answer -> }`
  follows at most 5 redirects by hand (`RefusedRequestException` before anything is sent to a
  refused hop), gives the caller the final answer and closes it; `readCapped`. `HttpTransport`
  (`UrlConnectionTransport`: no caches, no automatic redirects) is a fake in tests.
  `WikipediaClient` runs on it with its behaviour unchanged (10 s / 15 s, 256 KB JSON, 404/410
  null, 429/503 busy, debug-only request log).
- Updater requests (`HttpUpdateServer`): 10 s to connect, 30 s between reads, the app's
  User-Agent; the manifest capped at 64 KB before it is decoded, the file at the manifest's size
  and 50 MB; any answer but 2xx is a failure (a private repository answers 404).
- Debug builds on an emulator only: `debug.stevenpiano.updateurl` (read with getprop, like the
  console hook) makes `UpdateSource.local(url)`: one origin (scheme, host, port) for the manifest
  and its file, plain HTTP allowed; `app/src/debug` adds a network security config that allows
  cleartext to 10.0.2.2 and nowhere else. Release builds have neither.

## The manifest — `update/UpdateManifest.kt`

`releases/latest.json`: `{"versionCode", "versionName", "notes", "apkUrl", "sha256", "sizeBytes",
"minSdk"}`, read with `org.json` (on the JVM `org.json:json` joins the unit-test classpath only).
versionCode a JSON integer 1..2,100,000,000; versionName `[0-9A-Za-z][0-9A-Za-z._-]*`, at most 32
(it names the downloaded file); notes optional, plain text (control characters but line breaks
dropped), at most 1,000 characters; apkUrl `allowsApk`; sha256 64 hex digits (kept lower-case);
sizeBytes 1..50 MB; minSdk optional, 1..1,000. Unknown fields are ignored. A manifest that fails
throws `InvalidManifest(field)`.

## State and checks — `update/UpdateState.kt`, `update/UpdateChecker.kt`

- `UpdateState`: Idle · Checking · UpToDate · Available(manifest) · Downloading(manifest, bytes,
  total) · ReadyToInstall(manifest, file) · Installing(manifest) · Installed(version, restartNeeded)
  · Failed(message, manifest?). `busy` (Downloading, ReadyToInstall, Installing, Installed waiting
  for Restart): no check replaces it.
- `UpdateChecker(BuildConfig.VERSION_CODE, SDK_INT, source, server, NetworkMonitor.online, clock)`:
  a higher versionCode is Available (Failed "Steven Piano 1.5 needs a newer version of Android."
  when minSdk is above the device's), the same or lower UpToDate; an IOException "Couldn't reach
  the update server.", a bad manifest "The update information couldn't be read."; a failure keeps
  a release already on offer. Checks run one at a time (a Mutex), and one that started before a
  download began never overwrites it.
- `runSchedule(enabled)`, run by `MainActivity` through `AppGraph.runUpdateSchedule` inside
  `repeatOnLifecycle(STARTED)`, after the first frame (a Choreographer callback): while the switch
  is on (read from DataStore, never the default) and the device is online, a check when due (none
  yet in this process, or 24 h after the last); switched off or offline, nothing is asked, and the
  wait starts again from the last check when both are back. `checkNow()` ignores the switch;
  offline it says "Checking for updates needs an internet connection." without asking.
- Settings: `checkForUpdates: Boolean (true)`, key "checkForUpdates"; `crashNoticeSeenAt` (a Long,
  housekeeping, outside `PianoSettings`).

## Download — `update/UpdateDownloader.kt`, `update/Updater.kt`, `service/UpdateService.kt`

- `cacheDir/updates/<versionName>.apk.part`, SHA-256 computed on the way and bytes counted: past
  the manifest's size or 50 MB the part is deleted and "The download didn't match the release; try
  again."; at the end size and hash are compared before the rename to `<versionName>.apk`, and a
  mismatch deletes it with the same line. A drop after the first bytes is "The download stopped;
  try again.", before them unreachable; less free space than the file plus 16 MB is "There isn't
  enough free space for the update." Older downloads are removed first; `verified(file)` hashes
  the file again just before it is installed; at process start, downloads older than the process
  are swept.
- `UpdateService`: a dataSync foreground service, channel "updates" (low importance), notification
  "Downloading Steven Piano 1.4" with "1.2 of 2.3 MB", a progress bar and Cancel (an explicit,
  immutable PendingIntent to the service), which puts the release back on offer; `onTimeout` in
  both forms stops it; a refused start downloads in the app's process. `Updater.downloadAndInstall`
  publishes the progress in at most 200 steps, then installs.

## Install — `update/UpdateInstaller.kt`, `update/UpdateResultReceiver.kt`, `admin/`

- Device owner (`DevicePolicyManager.isDeviceOwnerApp`): Installing; the piano is silenced (live
  keys, `pauseAndFlush(300)`); a `PackageInstaller` session, `MODE_FULL_INSTALL`,
  `setAppPackageName` (this package and no other), `USER_ACTION_NOT_REQUIRED` on API 31+, the file
  streamed in and committed with an explicit broadcast PendingIntent to the non-exported
  `UpdateResultReceiver` (`FLAG_MUTABLE` on API 31+, because the installer adds EXTRA_STATUS and
  its message when it sends it).
- Otherwise `ACTION_VIEW` of `application/vnd.android.package-archive` on
  `AppFileProvider.uriFor(file)` with `FLAG_GRANT_READ_URI_PERMISSION`: Android's own confirmation.
  The state stays ReadyToInstall, so Update opens it again if the person backs out. With
  `canRequestPackageInstalls()` false the row shows "Allow this app to install updates" and Open
  settings (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the package), read again on resume.
- `UpdateResultReceiver`: success where the installed versionCode equals `BuildConfig`'s (Android
  stopped the old process for the install and started the new version for this broadcast) clears
  the download, `markChecked`s, publishes Installed(version, restartNeeded = false) and reopens the
  app on the Piano tab (the device owner may start an activity from the background); success heard
  by old code publishes Installed(restartNeeded = true), whose Restart (`AppGraph.restartForUpdate`:
  live keys off, `stopAndFlush(300)`, the launcher intent with NEW_TASK | CLEAR_TASK, then the
  process exits) runs the new code; pending user action shows Android's confirmation; anything else
  is "The update couldn't be installed." with the release still on offer. The class name never
  changes: the old version's PendingIntent names the new version's receiver.
- `admin/PianoDeviceAdmin` (a `DeviceAdminReceiver`, no policies: `res/xml/device_admin.xml`),
  exported with `BIND_DEVICE_ADMIN` and the DEVICE_ADMIN_ENABLED filter. `DeviceOwnerRelease`: as
  the app starts, while it is the device owner and `debug.stevenpiano.releaseowner` (settable only
  over adb) is `yes`, `clearDeviceOwnerApp`.
- `AppFileProvider`: authority `<package>.files`; `res/xml/file_paths.xml` names `cache-path
  updates/` and `cache-path diagnostics/` and nothing else. Manifest: `REQUEST_INSTALL_PACKAGES`,
  the service, both receivers, the provider.

## Diagnostics — `diag/`

- `LinkLog`: the last 500 lines, stamped `yyyy-MM-dd HH:mm:ss.SSS` in local time, one line each, at
  most 400 characters; `LinkLog.warn` is `GattPianoLink`'s log (`Log.w` under PianoLink, then the
  buffer), and the emulated link adds its connections.
- `CrashReports`: `filesDir/diagnostics/crash-<epoch ms>.txt` (a second crash in one millisecond
  gets `-1`): time, app and build, device, Android, thread, stack trace (64 KB at most; content and
  file URIs, shared-storage paths and web addresses' paths scrubbed from messages), the link's last
  50 lines; the newest 5 kept; it never throws. `CrashSilencer(link, previous, report)`: the stop
  sequence first, then the report, then Android's handler.
- `DiagnosticsExporter`: `cacheDir/diagnostics/steven-piano-diagnostics-<yyyy-MM-dd-HHmmss>.zip`
  (the one before removed) with `about.txt` (`DiagnosticsText.about`), `settings.txt` (the 19
  preferences, one a line), `link.log` and the crash reports (128 KB each at most); it is given
  nothing from the library. `Diagnostics.share`: `ACTION_SEND` of `application/zip` with
  EXTRA_STREAM and ClipData, the read grant, through the chooser "Share diagnostics".
- The crash banner: `AppGraph.crashNotice` is true while the newest report is newer than
  `crashNoticeSeenAt`; Share diagnostics (once the share sheet opens) or Dismiss moves it on.
- Debug builds on an emulator: the intent extra `EMULATOR_CRASH` crashes the main thread outside
  `route`'s guard.

## UI

- Piano tab: `UpdateRow` (`ui/screens/piano/UpdateRow.kt`) between the piano's sections and App
  preferences, only while the state carries a manifest or is Installed; `CheckNowRow` under the
  last preference, "Check for updates automatically"; `ShareDiagnosticsRow` closes DIAGNOSTICS
  (`PianoSettingsSections(…, appDiagnostics)`), under a DIAGNOSTICS header of its own when the
  firmware offers no settings.
- Library: `CrashBanner` (an `OutlinedBanner` with Share diagnostics and Dismiss) under the import
  and artwork bars.
- `UpdateCopy` holds every line; megabytes are decimal, one decimal place, in the locale's form.
  Nothing new is red, and nothing outside `ui/theme` names a colour.

## Publishing — `tools/publish-release.sh`, `releases/`

`tools/publish-release.sh <versionName> "<notes>"`: checks (the name, a clean tree on `main` not
behind origin, no tag or release of that name, the keystore properties, `gh auth`, provenance
verifies), `./gradlew assembleRelease`, checks the APK (its versionName is the one given, `apksigner`
shows `CN=Steven Piano`, at most 50 MB), copies it to `../apk/steven-piano-<v>.apk`, takes its
SHA-256 and size, pushes `main`, creates the release (`gh release create v<v> ../apk/steven-piano-<v>.apk
--repo … --target <commit> --title "Steven Piano <v>" --notes "<notes>"`), then writes
`releases/latest.json` (versionCode and minSdk read from the APK with `aapt2`), appends it with its
tag and date to `releases/history.json`, commits (trailer "Co-Authored-By: Claude Fable 5.1") and
pushes. `releases/latest.json` for 1.4 is committed with this release.

## Measured (September 2026)

- Tests: 661, none failing (5 skipped without `-Pcorpus`, as before).
- Release: `app-release.apk` 2,434,545 bytes, SHA-256 `1426f3cc9b92999b5a3aa315432620c978408951d5d7bf75ce68365f7294d88c`; `apksigner` verifies v2 and v3,
  signer `CN=Steven Piano, O=Steven Jin, C=US`; `aapt2` reads versionCode 8, versionName 1.4; the
  provenance string is in `classes.dex`.
- Emulator (`steven_piano`, API 34, debug builds, a server on the Mac through
  `debug.stevenpiano.updateurl`, test releases with versionCode 9 to 12): the first check ran once
  the first frame was up; a 15.1 MB file downloaded with the row counting "Downloading 1.4.1 · 4.0
  of 15.1 MB"; Android's installer asked first for "Install unknown apps" and then "Do you want to
  update this app?", and the update installed; a wrong hash left the line "The download didn't
  match the release; try again." and no file; a missing file (404) left "Couldn't reach the update
  server."; Cancel in the notification stopped a download, removed its part file and put the
  release back on offer; as device owner, the session committed with no tap and the new version
  reopened on the Piano tab ("Updated to 1.5") 0.65 s after the hand-over (logcat: the old
  process killed "due to installPackageLI", the receiver's process started, the relaunch allowed
  as BAL_ALLOW_ALLOWLISTED_COMPONENT); Restart sent B0 40 00, B0 7B 00, then started a fresh
  process; the crash hook wrote `crash-<epoch>.txt` after "Emergency silence" and the next launch
  showed the banner; the diagnostics zip held about.txt, settings.txt, link.log and the crash
  report; `dpm remove-active-admin` was refused ("Attempt to remove non-test admin") and
  `debug.stevenpiano.releaseowner` gave the role back; the Piano tab kept its layout at font scale
  2.0 and in dark mode.

## Deviations from the run's rules, and why

- **Restart after a silent install is rarely reached.** Android 14 stops an app while it replaces
  it (logcat "Killing … due to installPackageLI"), so the success is heard by the new version, not
  by the old code that DESIGN's "Updated to 1.4; restart to use it" assumed. The new version's
  receiver reopens the app on the Piano tab reading "Updated to 1.4" (no button: it is already the
  new code), and the next automatic check waits a day. Installed(restartNeeded = true) with Restart
  stays for an install the old process outlives; on the emulator it was reached once, when a test
  manifest named versionCode 12 for a file that was 11 and the receiver compared the manifest's
  number with its own. It now reads the installed package's versionCode instead, and that case
  reopens too.
- **Giving back the device owner.** `adb shell dpm remove-active-admin` refuses an admin that is not
  a test-only build, and Android will not uninstall a device owner, so without help only a factory
  reset undoes the setup. `DeviceOwnerRelease` gives the role back when `debug.stevenpiano.releaseowner`
  is set over adb (no app can set a `debug.` property) and the app starts. It replaced the
  debug-only intent hook this run first used on the emulator.
- **Publishing order.** The script pushes `main` and creates the release (with `--repo` and
  `--target <commit>`, so the tag names the commit that built the APK) before it commits and
  pushes `releases/latest.json`: a tablet never reads a manifest whose file is not there yet. The
  versionCode written is read from the built APK, so it cannot disagree with the file.
- **Provenance and banners.** `releases/*.json` carry no banner (JSON has no comments, and a field
  would join the format) and stay out of the signed manifest, since every publish changes them
  without re-signing. `tools/*.sh` joins it: `provenance/sign.py` now includes `.sh`.
- **The row says more than DESIGN lists, all in its words.** "Steven Piano 1.4 is ready to install"
  for a verified file waiting on Android's installer (the Update button keeps its name); "Allow
  this app to install updates" with Open settings (the run's rule); "Installing Steven Piano 1.4…"
  over an indeterminate hairline while the device owner's session runs. Check now reports the last
  check in one eyebrow line under it ("Steven Piano is up to date.", "Version 1.5 is available.",
  or a failure's line), and offline says so without asking.
- **States carry their release.** Downloading, ReadyToInstall, Installing and Failed hold the
  manifest (the row needs its name and notes); Installed carries `restartNeeded`.
- **The crash banner is on the Library**, the start tab, under the import and artwork bars, not
  across the frame, so every tab keeps its title and byline where they are.
- **Crash reports** also carry the link's last 50 lines (the in-memory log goes with the process),
  and their messages are scrubbed of content and file URIs, storage paths and web paths, so a file
  or piece name a message quotes does not travel.
- **Share diagnostics** is always enabled (the piano's own diagnostics wait for a connection).
- `notes` and `minSdk` are optional in a manifest; `org.json:json` joins the unit-test classpath
  (android.jar's is a stub there); `UpdateService` lives with the other services in `service/`.

## Tests added in v1.4

`HttpFetchTest` (7: GitHub's redirect followed with the User-Agent on every hop; redirects off the
list refused before anything is sent, http, another port, another repository and a backslash among
them; a refused start; relative redirects; five redirects and not six; a redirect without Location;
capped reads), `UpdateManifestTest` (10: a valid manifest; optional and unknown fields; each required
field missing or null; wrong shapes; other hosts, look-alikes and user info; other repositories,
paths, dot segments, queries and types; http and other ports; notes; the production and local
sources), `UpdateCheckerTest` (9: newer, equal and older versionCodes; minSdk; failures keeping the
release on offer; offline; the 24 h schedule on a virtual clock; a network lost part-way; the switch
off with Check now still asking; busy states never replaced; after a silent update),
`UpdateDownloaderTest` (6: a match kept under its name with progress; a hash mismatch deleted; the
manifest's size and the cap; a short file; a dropped and a failed connection; older downloads
cleared and a changed file no longer verifying), `HttpUpdateServerTest` (5), `CrashReportsTest` (5:
contents, the last five, two in one millisecond, scrubbing, a folder that can't be written),
`LinkLogTest` (4), `DiagnosticsExporterTest` (3: exactly the four kinds of entry; nothing from the
library even inside a crash's message; each export replacing the one before), `UpdateCopyTest` (2),
`ReleaseManifestTest` (1: the committed `releases/latest.json` passes the app's own checks, names no
version above the source's, and ends `history.json`), and cases in `CrashSilencerTest` (+2: the
report after the silence; failures still handed on) and `SettingsRepositoryTest` (+2: the crash
banner's answer; checkForUpdates). 605 tests before, 661 after.
