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
  person (see `v1.2 — M11 › Network policy`).
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
  .pem`, `LICENSE`, `AUTHORS`, `.gitignore`, `gradlew`, `gradle.properties`,
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
