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
- From v1.5 (M16): Haze 1.7.2 (`dev.chrisbanes.haze:haze`, Apache-2.0), the blur behind the glass of
  the floating controls; only `ui/components/Glass.kt` names it (see `v1.5 — M16`).
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
  Red (`LocalLive.current`) is read by `LiveDot` only. The sounding yellow
  (`LocalNoteSounding.current`, v1.5 — M16) is read by `ScorePainter.overlay` only: a grep for it
  outside `ui/theme` finds `ScorePages.kt` alone.
- The floating controls are glass (v1.5 — M16): `GlassSurface` in `ui/components/Glass.kt`, the only
  file that names Haze; the glass sources are the navigation content (`NavHost.kt`) and the note
  panel (`nowplaying/NotePanel.kt`), no others. The content draws beneath the bar and the rail, and
  every screen keeps clear of them through `LocalFloatingPadding`. No `Modifier.blur` anywhere.
- Screens and behaviour exactly as `DESIGN.md`. Bottom `NavigationBar`, four tabs since
  v1.1: Library, Now playing, Keys, Piano (v1.0 had the three without Keys). Single
  activity, Navigation-Compose, fade-through 240 ms between tabs (a cut under reduced
  motion); inside the Piano tab, pages push over its hub (v1.5 — M15).
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
   `LiveDot.kt`; `LocalNoteSounding` only in `ScorePages.kt` outside `ui/theme`
   (`grep -rn "LocalNoteSounding" app/src/main` finds `Theme.kt` and `ScorePages.kt`);
   `grep -rn "hazeSource\|hazeBlur\|hazeChild\|HazeState" app/src/main` finds `Glass.kt`,
   `NavHost.kt` and `NotePanel.kt` only, and no `Modifier.blur`; provenance verifies; the
   provenance string is in the release DEX.

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
  successful `set` since the last save (v1.5: when the Piano tab's graph entry stops, never
  when one of its pages closes).
- On disconnect: `Unknown`. Values are never cached across connections (the piano is
  the source of truth).

### The table — `piano/PianoSettings.kt` (rewritten in v1.5 — M15)

A static list of `PianoSetting(name, label, section, kind, unit)` where `kind` is
`Switch`, `Stepper(min, max, step)`, `Slider(min, max, step, decimals)` or
`Choice(options)`; `section` is a `PianoSection`, and `page` (a `PianoPage`) is the section's.
Names are the firmware command names. Pages (`PianoPage`, the hub's order) and their sections
(`PianoSection(page, title)`), in order, with their members:

- **Feel**: **PRESETS** chip row Soft · Cinematic · Expressive · Snappy (no setting) ·
  **LOUDNESS** `fullpower` Switch "Full power (no dynamics)" · `volume` Slider 0–100 % ·
  **TOUCH** `velcurve` Slider 0.4–3.0 step 0.05 · `velmult` Slider 0.1–5.0 step 0.1 · `min`
  Slider 0–4095 "White-key floor" · `minblack` Slider 0–4095 "Black-key floor (0 = same as
  white)" · `max` Slider 0–4095 "Ceiling" · **Strike test**: a Stepper for a key (24–107,
  default 60, shown as note name) and *Floor* / *Ceiling* actions (`testmin` / `testmax`) ·
  **TIMING** `humanvel` Stepper 0–30 · `humantime` Stepper 0–40 ms · `burstgap` Stepper
  0–600 ms step 10 · `burstboost` Slider 0–100 % · `minstrike` Stepper 0–500 ms step 5 ·
  `isostrike` Stepper 0–500 ms step 5 · `isogap` Stepper 0–2000 ms step 10 · `gap` Stepper
  0–300 ms · `hold` Stepper 50–4000 ms step 50 · `restrike` Stepper 0 or 40–1000 ms step 10 ·
  **RELEASE** `softrelease` Switch · `releasepwm` Slider 0–4095 · `releasems` Stepper 0–200
  ms · **DRIVE** `freq` Stepper 24–1526 Hz step 10.
- **Lighting**: **STRIP** `leds` Switch "Strip" · `ledmode` Choice Off/Static/Rainbow/
  Reactive · `ledbright` Slider 0–255 shown as % · `reactcolor` Choice Rainbow/Solid/
  Velocity/Fire/Ocean/Forest/Lava/Party · **LAYOUT** `ledcount` Stepper 1–300 "LEDs" ·
  `ledoffset` Stepper −300–300 · `ledscale` Stepper 10–400 % · `ledtail` Stepper 0–255 ·
  `ledreverse` Switch · `ledglow` Stepper 0–10 · **Test LED**: a Stepper for a key (24–107,
  default 60, shown as note name) and a *Light it* action (`ledtest`) · **MOTION**
  `velbright` Switch "Brightness follows velocity" · `decay` Stepper 1–40 · `rainspeed`
  Stepper 1–40 · **PIANO'S SCREEN** `dimsecs` Stepper 0–3600 s step 30 · `dimfloor` Slider
  0–255.
- **Pedal** (one section, no eyebrow): `pedalon` Switch · `pedalhalf` Switch · `pedalup`
  Stepper 80–600 · `pedaldown` Stepper 80–600. (`pedaltest` is refused over Bluetooth; not
  shown.)
- **Firmware and status**: **FIRMWARE** the fact `!fw` as a read-only row "Piano firmware"
  ("Unknown" until reported) · **STATUS** facts as read-only rows (`!boards` rendered as
  seven OK / MISSING words, `!i2cfails`, `!pedalboard`, `!uptime` as h:mm), then
  `keyforce_white` / `keyforce_black` as two read-only rows ("White-key force ×1.00",
  "Black-key force ×1.00"; setting them is refused over Bluetooth) and the line "Key force
  is set at the piano's USB console." · **ACTIONS** *Read status* (its `statusText` in Body
  on `surfaceElevated` under the row), *All keys off* (`off`) and *Save now* (`save`) as
  outlined buttons in action rows, All keys off and Save now side by side as in v1.4.

`PianoSettings.rows(section)` gives each section's rows in that order (`PianoRow`: `Control`,
`Reading`, `Presets`, `StrikeTest`, `TestLed`, `KeyForceNote`, `Actions`); the pages render
nothing but that list.

### Screen — `ui/screens/piano/PianoSettingRows.kt` and `pages/` (rewritten in v1.5 — M15)

- Each piano page (`FeelPage`, `LightingPage`, `PedalPage`, `FirmwarePage`) is
  `PianoPageContent(page, report, actions)`: the status line, then its sections, each under a
  `SectionEyebrow` (a page's only section under a plain `SectionRule`). Every control reads
  its live value from `values`, shows the unit in its eyebrow, and calls `set` on change;
  sliders call `set` on value change (the repository debounces). Choices and presets are
  chip rows (`FilterChip` with a check on the chosen one; `SuggestionChip` for presets).
  Disabled with `LocalDisabledGlyph` handles while `Unknown`, under the line "Connect to the
  piano to adjust its settings." ("Reading the piano's settings…" over a hairline once
  connected); while `Unsupported` each piano page shows the single line "This piano's
  firmware doesn't offer settings over Bluetooth yet." and nothing else.
- `lastError` shows as an `OutlinedBanner` ("The piano said: …", Dismiss) directly under
  the control it concerns: the setting it names, the Test LED or strike-test row for their
  commands, PRESETS for a preset, and the end of ACTIONS for anything else.
- Every control has a `contentDescription` including its label and value; steppers'
  ± buttons are 48 dp. The rows themselves are `ui/components/SettingsRows.kt`'s.

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
"Artwork in black and white" and "Fetch artwork automatically" (v1.5: Piano › Display › ARTWORK)
with the line "Uses Wikipedia. Nothing about you is sent." beneath; the About area adds that sentence and "Text from Wikipedia,
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
  (v1.5: Piano › Display › NOTES, in the same order.)

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
  firmware offers no settings. *(v1.5 — M15: `UpdateRow` sits on the hub between the card and
  the groups; Check now and Share diagnostics are outlined buttons in action rows of the hub's
  APP group; `CheckNowRow` and `appDiagnostics` are gone.)*
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

# v1.5 — M15: the Piano tab as groups

Read `DESIGN.md › v1.5 — the Piano tab as groups` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M15. Not a release: `versionCode` 8, `versionName` "1.4" and `Provenance.text` stay.

## Files

- `ui/components/SettingsRows.kt` (new): every row of the tab. `SectionEyebrow(text)` (Eyebrow,
  padding 16/24/8, heading, full-width hairline; it replaces the three copies), `SectionRule()` (a
  page's only section), `SwitchRow`, `StepperRow(label, unit, enabled, description, note, below,
  control)` with `StepperButtons` (the piano's 48 dp repeating − value +; the app's rows keep
  `StepperControl`), `SliderRow` (around the unchanged `HairlineSlider`), `ChoiceRow` (chips),
  `NavRow(label, value, onClick, selected)`, `ActionRow(note, below, buttons)` with
  `ActionButton(label, onClick, enabled, description)` (the app's outlined button: hairline
  border in `LocalTertiary`, `LocalHairline` when disabled; the test rows use it too),
  `NoteLine`, `ReadingRow`, and `Modifier.mirrored(rtl)`. `ActionRow` sets its buttons in a
  `FlowRow` at the 16 dp inset with 4 dp above and below (a 56 dp row around the 48 dp target),
  and 4 dp more each side once a button outgrows 48 dp (large text), so a pill never meets a
  hairline. `NavRow` and `ReadingRow`
  lay label and value out in a `FlowRow` whose arrangement spreads two items to the ends at least
  16 dp apart, so a value that doesn't fit goes under its label. The two old row systems (the
  private rows of `PianoScreen.kt` and `PianoSettingsSections.kt`) are gone.
- `ui/components/PageHeader.kt` (new): `PageHeader(title, onBack)`; `onBack` null beside the hub
  (the title with an empty byline line under it, so it sits level with `ScreenHeader`'s title).
- `ui/screens/piano/PianoScreen.kt`: `PianoScreen(tab, onOpenPage, onReopenPage)` (the hub; on
  `AppFrame.twoPane` the `Row` of hub 360 dp, `VerticalDivider`, page) and
  `PianoPageScreen(tab, page, onBack)`.
- `ui/screens/piano/PianoSettingRows.kt` (was `PianoSettingsSections.kt`): `PianoSettingsActions`,
  `PianoStatusLine`, `PianoPageContent(page, report, actions)`, `PianoReport`, the Presets, Test
  LED, Strike test, fact, board and action rows, the refusal banner and its placing.
- `ui/screens/piano/pages/`: `FeelPage`, `LightingPage`, `PedalPage`, `FirmwarePage` (each
  `PianoPageContent` for its `PianoPage`), `PlaybackPage`, `DisplayPage`.
- `ui/screens/piano/GroupSummaries.kt`, `HubGroups.kt` (new, pure).
- `ui/screens/piano/PianoViewModel.kt`: a `SavedStateHandle`; `selectedPage`, `open`, `pick`,
  `keepOpen`, `takeOpened`, `scrollOf`.
- `ui/screens/piano/UpdateRow.kt`: its header is a `SectionEyebrow`; `CheckNowRow` is gone.
- `ui/components/DiagnosticsShare.kt`: `ShareDiagnosticsRow` is an `ActionRow`.
- `piano/PianoSettings.kt`: `PianoPage`, `PianoSection(page, title)`, `PianoRow`, `rows(section)`,
  `sections(page)`; `Fact` has a section; `all` is in page order.
- `ui/Routes.kt`, `ui/NavHost.kt`, `ui/AdaptiveFrame.kt` (`twoPane`).

## The table's new columns

`PianoSetting.section` is now one of fourteen `PianoSection`s, each on one `PianoPage` (Feel,
Lighting, Pedal, Firmware) with its eyebrow title (null for Pedal's only section);
`PianoSetting.page` is the section's. `Fact.section`: `fw` (label "Piano firmware") under
FIRMWARE, the rest under STATUS. `rows(section)` puts the rows that are not settings in their
places: PRESETS is the chips alone, the strike test closes TOUCH, the Test LED closes LAYOUT, the
key-force note closes STATUS, ACTIONS is the actions. Order and members: `v1.1 › The table`.

## Routes

- `Route.Piano`'s path `piano` is a nested graph: start `piano/hub`, and `piano/{page}?cut={cut}`
  for the six keys `feel`, `lighting`, `pedal`, `firmware`, `playback`, `display`
  (`SettingsPage`, with its title and its `PianoPage`). `Route.of` maps any `piano/…` to the tab.
- The page argument is typed (`PianoRoutes.PageType`, a `NavType<SettingsPage>` that parses only
  the six keys). Navigation 2.9 finds a graph's start destination, and a route to pop to, by the
  first node whose pattern matches (`NavGraph.findNode(route)`, nodes in id order); with a string
  argument `piano/{page}` matched `piano/hub` and the tab opened on a page called "hub" (Feel
  by the fallback). A key that fails to parse makes the pattern not match.
- `openTab(route)` (a notification's `EXTRA_TAB`, "Not connected" on Now playing and Keys, a piece
  starting): for Piano, the tab and then `popBackStack("piano/hub")`, so it always lands on the hub.
  `selectTab(route)` (the bar, the rail): a tab comes back as it was left (`restoreState`); Piano
  chosen while showing pops to its hub.
- A row pushes `piano/<key>` only while the hub is the top entry, and back pops only while the page
  is (`isTop`), so a double tap never pushes two pages or pops the hub.
- Transitions: between tabs, fade-through as before; inside the tab on phones, `PagePush`: the page
  `slideIntoContainer(Start)` over 240 ms (`Motion.StandardMs`, `Motion.Standard`), the hub
  `slideOutOfContainer(Start)` by a quarter of its width, the reverse on pop (NavHost draws the
  popped page above); no transition at all (`EnterTransition.None`, `ExitTransition.None`) under
  reduced motion, on wide frames, and for a page pushed with `cut=true`. The hub and pages draw the background colour themselves, so
  the sliding page covers the hub.

## One view model for the tab, and when the piano saves

Both destinations take `nav.getBackStackEntry("piano")` (the graph's entry) and
`viewModel(tab) { PianoViewModel(graph, createSavedStateHandle()) }`, so the hub and its pages
share one instance, kept while the tab's stack is saved. `leave()` runs from
`LifecycleEventEffect(ON_STOP, lifecycleOwner = tab)`: the graph entry stops when the tab is left
(popped with `saveState`, through STOPPED) or the app goes to the background, and stays RESUMED
while a page is pushed or popped inside it (NavController resumes the parents of the top
destination). The old `DisposableEffect { onDispose { leave() } }` is gone: it would have sent
`save` on every page pop.

## The split and rotation

- `AppFrame.twoPane` = `widthClass != Compact` (a phone on its side included). The hub
  destination then shows the hub (360 dp) and `vm.selectedPage` beside it (Feel at first);
  its rows call `pick`.
- The selection and whether a page is open live in the view model's `SavedStateHandle`
  (`selectedPage`, `pageOpened`), not in a `rememberSaveable` of one destination, because the
  pushed page and the split are different destinations. Each page's `ScrollState` lives in the
  view model too (a row that opens or picks a page starts it at the top).
- A phone turned on its side with a page open: the page destination sees `twoPane`, calls
  `keepOpen(page)` and pops itself (no transition on wide frames); the split shows that page,
  scrolled where it was. Turned upright again: the hub's `LaunchedEffect(twoPane)` asks
  `takeOpened()` and pushes the page with `cut=true` (no slide). A page chosen beside the hub
  comes back over it the same way; the default Feel never does.

## `GroupSummaries` (pure, `ui/screens/piano/GroupSummaries.kt`)

`from(piano, settings, wide)`, from state already held (never a read). Not `Ready`: the four piano
rows read "—". Feel: "Full power" while `fullpower` is on, else "Volume N%" (`Format.percent`);
Lighting: "Off" while `leds` is off or `ledmode` is Off, else the mode's name and the brightness
as the table shows it (the firmware's `(v × 100) / 255`), "Reactive · 62%"; Pedal: `pedalon` "On"
or "Off"; Firmware and status: `!fw` trimmed; Playback: the default tempo, "100%"; Display: the
note display's label, `rollStyle`'s on wide frames. A value the piano didn't report reads "—".
The hub computes it with `remember(piano, settings, frame.wide)`.

## Hub groups (`HubGroups`)

`all` = PIANO (the four piano pages), PLAYING (Playback, Display), CONTROL (empty until M18), APP
(`AutoConnect`, `CheckForUpdates`, `CheckNow`, `ShareDiagnostics`); `shown` drops a group without
rows. A later run adds its `SettingsPage`, its page file and one `HubRow` here.

## Measured (September 2026, `steven_piano`, debug build, the emulated piano)

- Tests: 681, none failing (5 skipped without `-Pcorpus`, as before). `lint`: 0 errors; its 19
  warnings are all in files this run did not touch.
- Action rows: 151 px from Read status to All keys off, a 56 dp row and its hairline (the
  steppers' pitch is 150 px); at font scale 2.0 the pills keep 8 dp from the hairlines, and All
  keys off and Save now still fit side by side on the phone.
- Reading width: at 2560 × 1600 px, 240 dpi (1707 dp wide) the page pane beside the hub is about
  1264 dp and the page's column runs from 714.7 to 1434.7 dp: 720 dp, centred (`readingWidth()`
  wraps the page's header and its column, as the hub's).
- Rows: the Feel page against v1.4's FEEL section, the same emulator and values (1080 × 2400 px,
  420 dpi), measured from the UI tree top to top: all 17 pairs of neighbouring rows within one
  section are as far apart as in v1.4 (150 px between steppers, 244 px between sliders, 254 px
  from Full power to Volume); each of the four new section breaks (TOUCH, TIMING, RELEASE, DRIVE)
  adds 124 px, its eyebrow and hairline. Type, hairlines and switch colours match in the
  screenshots.
- Save: toggling Full power sent `fullpower 0` and `get fullpower`; back to the hub sent nothing;
  choosing Library then sent `save` (logcat `PianoLink`).
- Navigation: `EXTRA_TAB=piano` with the Pedal page open landed on the hub; Library and back to
  Piano restored the Lighting page; Piano chosen again popped to the hub.
- Rotation: the Feel page scrolled to TIMING (its eyebrow 408 px from the top) moved beside the
  hub with TIMING at 408 px, and came back over the hub at 408 px; Lighting picked in the split
  came back over the hub when the phone was upright again, and with nothing picked the hub
  came back.
- Transitions, animations slowed 10 ×: the page slid in over the hub while the hub moved a
  quarter left, and back reversed it; with animations removed, the frames around a tap showed
  the hub, then the settled page.
- States: not connected (the status line, "—" on the four rows, the pages' controls disabled);
  Unsupported (`debug.stevenpiano.console none`: the one line on the hub and on each piano page);
  a refusal (`EMULATOR_SET "ledbright 300"`: "The piano said: ledbright out of range (0..255)"
  directly under Brightness); UPDATE on the hub from a manifest served on 10.0.2.2; font scale
  2.0 (values move under their labels, nothing clipped); light and dark; the split on the phone
  on its side and at 1600 × 2560 and 2560 × 1600 px, 320 dpi (tablet upright and on its side).

## Deviations from the plan, and why

- **Percentages read "70%"**, not "70 %": `Format.percent`, as the tempo, the steppers and every
  other percentage in the app.
- **Lighting reads "Off" when the mode is Off** as well as when the strip is: "Off · 62%" would
  describe a dark strip as lit.
- **The hub keeps the piano's status line** under the card (the brief's "status line"): it says
  why the four rows read "—".
- **Note display and Wide layout are chip rows**, as every choice (the shared `ChoiceRow`); in
  v1.4 they were radio rows. PRESETS lost its inner "Presets" label, which repeated its eyebrow.
- **Actions are outlined buttons in rows** (the design review's fix: the first build drew them as
  plain rows, which read like the STATUS rows above them): Check now, a text button in v1.4, is
  outlined like Share diagnostics and the Firmware page's actions; the Test LED and Strike test
  keep their buttons, now the shared `ActionButton`.
- **The hub route is `piano/hub`** (the graph takes the tab's `piano`), and the page argument is
  typed (above). The selection lives in the view model's `SavedStateHandle` instead of a
  `rememberSaveable` (above); `cut=true` is a query argument of the page route.
- **The firmware version row reads "Unknown"** until the piano reports it; the other facts keep "—".
- The table lives in `piano/PianoSettings.kt` (not `settings/`), as since v1.1.

## Tests added in M15

`RoutesTest` (6: the tabs' paths; `piano/…` belongs to the Piano tab; a route per page, with
`cut`; the graph's start and the page pattern; `PageType` refuses "hub" and anything not a page;
the pages' titles and piano pages), `GroupSummariesTest` (7: Feel; Lighting with the firmware's
rounding and a mode that is Off; Pedal and Firmware; dashes until the piano answers while the app's
rows still read; Playback; Display on phones and wide screens; each page's row takes its own
value), `PianoPagesTest` (6: every setting on one page and section in the design's order; each
page's sections and eyebrows; the rows that are not settings in their places; every setting and
fact exactly once; the hub's groups and rows, CONTROL hidden while empty; every page opened from
exactly one row), `AdaptiveFrameTest` (+1: `twoPane`), and `PianoSettingsTableTest`'s order test
now checks pages and sections. 661 tests before, 681 after.

---

# v1.5 — M16: glass, yellow, the pause before each piece, the mini player, two panes

Read `DESIGN.md › v1.5 — M16` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M16. Not a release: `versionCode` 8, `versionName` "1.4" and `Provenance.text` stay (M17 releases
1.5).

## Files

Added:

- `ui/theme/Glass.kt`: `GlassTokens` (`ContainerAlpha` 0.72, `LensAlpha` 0.60, `LensVeilAlpha` =
  1 − (1 − 0.72) / (1 − 0.60) = 0.30, `Blur` 24 dp, `Edge` 1 dp); `GlassEdgeDark` `#1AFFFFFF` and
  `GlassEdgeLight` `#B3FFFFFF` (the only new literals), `LocalGlassEdge` (provided by `PianoTheme`);
  `rememberReducedTransparency()` (`Settings.Secure` `high_text_contrast_enabled == 1`, followed by a
  `ContentObserver`, or in debug builds `debug.stevenpiano.noblur` set, read once per process);
  `GlassCanBlur` (API 31 and up).
- `ui/components/Glass.kt`, the only file that names Haze: `typealias HazeState`,
  `rememberHazeState()` (blurring on where `GlassCanBlur`), `Modifier.hazeSource(state)`,
  `LocalHazeState` (the navigation content's, provided by the nav host), `LocalReducedTransparency`
  (provided once by the nav host), `LocalOnGlass` (true inside a surface that has the glass's look),
  `glassAvailable()`, `enum class GlassEdge { Top, End, Outline }`,
  `GlassSurface(modifier, shape = RectangleShape, source = LocalHazeState.current, edge,
  containerAlpha = 0.72, blur = true, lens = false, content: BoxScope.() -> Unit)` and
  `GlassLens(modifier, content)`.
- `ui/components/MiniPlayer.kt`: `MiniPlayer(state, onOpen, onPlayPause, onNext, modifier)`,
  `PlayerState.miniPlayerShown`.
- `ui/components/FloatingPlay.kt`: `FloatingPlaySlot`, `LocalFloatingPlaySlot`,
  `FloatingPlayRequest(visible, anchor, onPlay)`, `FloatingPlayLayer(slot, shown)`,
  `FloatingPlayButton(onPlay, modifier)`, `FloatingPlaySize` 56 dp, `FloatingPlayMargin` 16 dp,
  `FloatingPlayClearance` 88 dp.
- `ui/components/RollStrip.kt`: `RollStrip(notes, transpose, fold, frameNanos, clock, activeLow,
  activeHigh, modifier)`, `ROLL_STRIP_DP_PER_SECOND` 48, `RollStripCanvasHeight` 120 dp,
  `RollStripHeight` (with the hairline and the keyboard strip).
- `ui/screens/nowplaying/NotePanel.kt`: `Panel` (the note views' card, moved from
  `NowPlayingScreen.kt`), `GlassTransportPanel(modifier, stripHeight, panel, controls)`,
  `transportFloats(plan, height)`, `TransportHeight` (128 dp), `PANEL_GAP`, and
  `ColumnScope.TransportControls(piece, state, frame, roll, player, playback, onSeek, onMoved)`
  (the scrubber over the transport, shared by Now playing and the panel).
- `ui/screens/nowplaying/NowPlayingPanel.kt`: `NowPlayingPanel(playback, modifier)`.
- `ui/screens/nowplaying/FrameClock.kt`: `internal fun rememberFrameNanos(playing, pieceId, settle,
  roll)`, moved from `NowPlayingScreen.kt` unchanged.
- Tests: `ui/components/GlassTokensTest.kt`, `ui/screens/nowplaying/TransportFloatsTest.kt`, and the
  helper `ui/theme/Wcag.kt` (the WCAG formula and source-over blends, shared with `ColorTokensTest`).

Changed: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Haze); `ui/theme/Color.kt`, `Theme.kt`;
`ui/components/ScorePages.kt`, `NoteCanvas.kt` (`dpPerSecond`, `TRACKER_FROM_BOTTOM` internal),
`Scrubber.kt`, `TransportBar.kt`, `LiveDot.kt`, `Artwork.kt` (`PieceArt`), `ReadingWidth.kt`
(`readingPadding(available, bottom)`); `player/PlaybackEngine.kt`, `Player.kt`, `PlayerState.kt`;
`settings/Settings.kt`; `AppGraph.kt`; `diag/DiagnosticsExporter.kt`; `data/LibraryRepository.kt`;
`service/MediaSessionHolder.kt`; `MainActivity.kt`; `ui/NavHost.kt`, `AdaptiveFrame.kt`
(`LocalFloatingPadding`), `Format.kt` (`seconds`); `ui/screens/library/LibraryScreen.kt`,
`PlaylistHeader.kt`; `ui/screens/nowplaying/NowPlayingScreen.kt`, `RollClock.kt` (its note);
`ui/screens/keys/KeysScreen.kt`; `ui/screens/piano/PianoScreen.kt`, `PianoViewModel.kt`
(`setPreRoll`), `GroupSummaries.kt`, `pages/PlaybackPage.kt`.

## Haze

Haze **1.7.2**, not the plan's 2.0.0 (see *Deviations*). In 1.x the blur is part of the `haze`
artifact. Used: `HazeState(initialBlurEnabled = GlassCanBlur)`, `Modifier.hazeSource(state)`, and
`Modifier.hazeEffect(state, HazeStyle(backgroundColor = surface, tints = [HazeTint(surface at the
container's alpha)], blurRadius = 24 dp, noiseFactor = 0)) { inputScale = HazeInputScale.Auto;
expandLayerBounds = false }` (`@OptIn(ExperimentalHazeApi::class)`). No tint beyond the surface, no
noise, no progressive blur, no mask. Where the glass cannot blur, `GlassSurface` draws the solid
surface itself; Haze's own scrim fallback is never reached.

## The glass (`GlassSurface`)

- Modifier chain: `clip(shape)`, the edge (`glassEdge`: over the content, a 1 dp hairline and inside
  it the 1 dp specular line; along the top for `Top`, the end for `End`, the outline for `Outline`
  with the line fading out over the upper half), then `hazeEffect` or `background(surface)`.
- `blurring` = `glassAvailable()` (`GlassCanBlur` and not `LocalReducedTransparency`) and `blur` and
  the source has an area; `glass` (the look: the specular line and `LocalOnGlass`) = available and
  (`!blur` or the source has an area). `blur = false` draws the look over the surface colour: what
  the blur of the bare background under the tint comes to, pixel for pixel.
- `lens = true`: the surface is blurred under `LensAlpha` and a `LensVeil` (the surface colour at
  `LensVeilAlpha` everywhere but the circle a `GlassLens` inside it reports through
  `LocalGlassLens`) brings the rest to `ContainerAlpha`; the lens needs no blur of its own.
- Contrast (`GlassTokensTest`, WCAG over the worst backdrop: white under the dark container, black
  under the light one): `contentPrimary` on the container 7.1:1 and 8.3:1; the play glyph on the
  lens 4.5:1 and 5.8:1; `contentSecondary` glyphs 3.2:1 (≥ 3:1); the tertiary grey under 3:1, so it
  never sits on glass.

## Where the glass is, and the floating padding (`ui/NavHost.kt`)

- `RailFrame(rail, modifier, content: (railWidth: Dp) -> Unit)`: a `SubcomposeLayout` that measures
  the rail first, lays the content out at full size and places the rail over its start edge; the
  rail is a sibling of the content, never inside its source.
- The `Scaffold`'s `contentWindowInsets` are the system bars and the cutout; the `NavHost` takes
  only `padding(top)`, `consumeWindowInsets(top)` and `hazeSource(content)`. `LocalFloatingPadding`
  (`AdaptiveFrame.kt`) = `PaddingValues(start = max(inset start, rail width), end = inset end,
  bottom = the Scaffold's bottom padding)`: the bar column's measured height on phones (it follows
  the mini player), the navigation bar alone on wide frames.
- Screens: the Library pads its sides and gives its list the bottom less the keyboard (≥ 0) as
  content padding (`readingPadding(maxWidth, bottom)`); the Piano hub and pages pad their sides and
  end each scroll column with a spacer; Keys and Now playing take all of it as padding.
- `BottomBar` = `GlassSurface(blur = current is Library or Piano) { Column { the mini player and a
  hairline (the moving hairline while loading) in an AnimatedVisibility; TabBar } }`; `TabBar` is a
  `NavigationBar(containerColor = Transparent)`; `TabRail` = `GlassSurface(fillMaxHeight, edge = End,
  blur = false) { NavigationRail(containerColor = Transparent) }`. Labels on glass are all
  `onSurface`; on the solid surface the unselected ones stay `onSurfaceVariant`. The pills are as
  before.
- `FloatingPlayLayer(slot, shown = current == Library)` beside the `NavHost` in the Scaffold's
  content `Box`.

## The pause before each piece

- `PlaybackEngine.play(nowNanos, preRollNanos = 0L)`: `anchorNanos = now + preRoll`, status Playing,
  the pedal restored; `positionMicros` runs below zero until the anchor, `advance` sends nothing and
  wakes at the anchor, so the first event leaves at exactly `now + preRoll`. `startMicros`: where
  this run began (below zero during a pause). `pause` holds `max(position, 0)` (a pause inside the
  pause resumes from 0 at once); `seek` is unchanged but for `startMicros` (it ends the pause);
  `setTempo` re-anchors (it scales what is left of the pause); `resync` inside the pause only
  silences.
- `Player.setPreRoll(ms)` (0–5000), fed by `AppGraph.start`'s settings collector; `startCurrent` and
  `restartCurrent` pass it, `resume` never; `autoAdvance` waits `max(1500 − preRoll, 0)` ms, so the
  gap between pieces is `max(1.5 s, preRoll)`. `PositionClock.at`: while running,
  `max(min(position, duration), min(startMicros, duration))`; held, `position` within 0..duration.
- Readers that need zero or more: `Scrubber` (the thumb's fraction and the elapsed seconds);
  `MediaSessionHolder` (position `max(0, position)`, speed 0 while the pause runs, published again
  when it ends); `ScorePainter.overlay` (draws nothing while `now < 0`).
- `PianoSettings.preRollMs` (key "preRollMs", default 2000, `PlaybackLimits.PreRollMs` 0..5000 on
  read and write), `SettingsRepository.setPreRoll`, listed in `settings.txt` (20 lines).
- UI: the Playback page's first row, `StepperRow("Pause before each piece")` over a
  `StepperControl` from 0 to 5000 in steps of 500 showing "Off" or `Format.seconds` ("0.5 s", "1 s",
  "2 s", "2.5 s"); `GroupSummaries.playback` = "2 s pause · 100%" or "No pause · 100%". Now playing
  and the panel: `StartingLine(starting)` under the composer, `starting = derivedStateOf { playing &&
  roll.positionAt(frame) < 0 }`, `AnimatedVisibility` with a 120 ms fade (none under reduced
  motion), its line always reserved.
- `RollClock` needs no change: its ease starts from the position at the first frame, now the
  pause's start, and `cut()` runs only while not playing (the pause is playing).

## Yellow

`Color.kt` `NoteSoundingDark` `#F2C94C`, `NoteSoundingLight` `#9C7A00`; `Theme.kt`
`LocalNoteSounding`, provided by `PianoTheme` beside `LocalLive`; `ScorePages`'s `ScoreColors` gains
`cursor` (`onSurface`) and takes `sounding` from `LocalNoteSounding`, which feeds only the overlay's
`colorRamp(upcoming, sounding)`; the playhead uses `cursor`.

## The mini player, the transport, the floating Play, the panel

- `MiniPlayer`: `heightIn(min = 64 dp)`, `clickable(onClickLabel = "Open Now playing")`,
  `PieceArt(pieceId, composerKey, ArtSize.Row, 48 dp)` (`Artwork.kt`: the composer's portrait, else
  the piece's roll card), the title in `bodyLarge` over the composer `Eyebrow` (`onSurface` on
  glass), 48 dp `GlyphButton`s play/pause and next; "Opening the piece…" while a first piece loads.
  Shown when `!frame.twoPane && (piece != null || loading) && current != NowPlaying`, entering with
  `expandVertically + fadeIn` and leaving with `shrinkVertically + fadeOut` over 240 ms (none under
  reduced motion). `NowPlaying.composerKey` comes from `PieceEntity.composerKey` through
  `LibraryRepository.load` (`PlayablePiece.composerKey`).
- Now playing: when the screen does not scroll, the views sit in a `BoxWithConstraints(weight 1)`;
  `glassAvailable() && transportFloats(plan, maxHeight)` puts the controls in the roll's card
  (`GlassTransportPanel`: the card is the source; the glass, a sibling across its width with its
  bottom on the strip's top edge, holds the `TransportControls` with `lens = true`), else they follow
  the views, solid. `transportFloats`: the paper roll only; the roll's card is the whole height
  (roll alone, side by side) or two thirds of it less the gap (stacked); its canvas, less the
  hairline and the 44 dp strip, times one third must hold `TransportHeight` + 12 dp.
- `TransportBar` on glass: `PlayPauseButton` is a `GlassLens` (72 dp) with the glyph in `onSurface`;
  `ModeToggle` off is `onSurfaceVariant`. The scrubber's times are `onSurface` on glass, and its
  track draws in its own `graphicsLayer`.
- A playlist's Play: `LibraryItems` asks `FloatingPlayRequest(playlist open && pieces shown, anchor,
  play.all(shown))`, where `anchor` is the list box's `boundsInRoot` narrowed to the reading column
  and raised by the list's bottom padding; the list adds `FloatingPlayClearance` to its bottom
  padding meanwhile. The nav host's layer places `FloatingPlayButton` 16 dp in from the anchor's end
  (its start in right-to-left) and above its bottom. `PlaylistHeader` loses `onPlay` and its circle.
- The two-pane Library (`AppFrame.twoPane`): `Row { library(weight 0.55); VerticalDivider;
  NowPlayingPanel(weight 0.45, padding(bottom = floating bottom)) }`, else `library()`; the list's
  state lives above the split. `LibraryPlay` calls `onPlaying` only when `!frame.twoPane`.
- `NowPlayingPanel`: a 64 dp row with the NOW PLAYING eyebrow and the Up next glyph
  (`UpNextSheet`); the loading hairline and the problem banner; then `PieceArt(ArtSize.Full)` at
  `min(320 dp, width − 32 dp, height − 469 dp)` (469 dp: the header row, the words, the strip at its smallest, the solid controls), at least 96 dp, the title (`titleLarge`, the piece
  sheet), the composer eyebrow (the channel's slot), `StartingLine`; then the `RollStrip`
  (`weight(1)`, at least `RollStripHeight`) with the controls on glass over its history when
  `glassAvailable() && transportFloats(roll plan, height)`, else the strip and the controls solid
  under it. Under 560 dp the panel scrolls with the strip at `RollStripHeight`. It uses
  `RollClock`, `rememberFrameNanos`, `TransportControls` and the `PlaybackStarter`; its sheets'
  state is positional (`rememberSaveable` without keys).

## Performance

- `MainActivity.capRefreshRate()`: `window.attributes.preferredRefreshRate = 60f` when
  `display.supportedModes` offer a rate above 61 Hz; the scheduler thread is untouched.
- Two sources only (the navigation content, the note panel). Each blur is worked out inside its
  surface's bounds (`expandLayerBounds = false`) from a third-resolution copy
  (`HazeInputScale.Auto`), drawn clipped to its shape. Surfaces with nothing beneath (`blur = false`)
  and the lens (the veil) cost no blur.
- Per-frame drawing keeps to its own layers so it does not redraw the glass: the scrubber's track,
  the live dot's breath (its layer's alpha), the roll and the strip (the note panel's card), the
  score's overlay.

## Greps (v1.5 — M16)

- `grep -rn "Color(0x" app/src/main --include=*.kt | grep -v ui/theme`: nothing.
- `grep -rn "LocalNoteSounding" app/src/main`: `Theme.kt`, `ScorePages.kt`.
- `grep -rn "hazeSource\|hazeBlur\|hazeChild\|HazeState" app/src/main`: `Glass.kt`, `NavHost.kt`,
  `NotePanel.kt`.
- `grep -rln "dev.chrisbanes" app/src/main`: `Glass.kt`.
- `grep -rn "Modifier.blur" app/src/main`: nothing.

## Measured (September 2026, `steven_piano`, debug build)

The emulator runs headless and draws with SwiftShader through ANGLE (`gles_mode_selected:swangle`):
the GPU's work runs on the CPU, so the glass costs far more here than on a tablet's GPU.

- Tests: 701, none failing (5 skipped without `-Pcorpus`). `lint`: 0 errors; its 28 warnings are in
  files this run did not write, but for the version catalog's "newer version" notices (Haze 2.0.0
  among them, see *Deviations*).
- Frames, `dumpsys gfxinfo`, 20 s of Clair de lune: a tablet held upright (1600 × 2560 px, 320 dpi:
  the score stacked over the roll, the transport on glass) 5 janky frames of 1,197 (0.42 %) dark
  and 5 of 1,199 (0.42 %) light, 50th / 90th / 99th percentiles 26 / 31 / 42 ms (dark); the phone
  (1080 × 2400 px, 420 dpi: the roll, the transport on glass) 1 of 1,201 (0.08 %). The blur off
  (`debug.stevenpiano.noblur`): 1 of 1,203 (0.08 %) on the tablet frame. On the way there: the blur
  at full resolution 25.1 % on the phone; at a third, 0.75 % on the phone but 21.6 % on the tablet
  frame, where the rail (2,560 px tall) was re-blurred every frame the roll drew (with the rail
  solid, 0.17 %) and the lens was a second blur (about 2 points).
- The pause: the engine's virtual-clock tests; on the emulator, STARTING about a second after a tap
  with the timer at 0:00 and the first notes descending, then the notes meeting the tracker bar as
  the pause ends; the media session at position 0, speed 0 during it and running after it.
- The glass: the tab bar over a scrolled Library (rows blurred beneath it), the mini player with a
  piece loaded (and at font scale 2.0: the row grows, the composer ellipsizes), the transport over
  the roll's history with the lens, the playlist's floating Play (the last row clear of it at the
  list's end), Keys with the keyboard above the mini player and the bar, the two panes at
  2560 × 1600 px, 240 dpi (the panel's roll and glass transport), High contrast text on (the bars
  and the mini player solid, the transport back under the roll), light and dark each; the search
  field with the keyboard open (the list ends at the keyboard); the rail on a phone on its side.

## Deviations from the plan, and why

- **Haze 1.7.2, not 2.0.0.** 2.0.0 (and 1.7.3) depend on Compose UI 1.12.0, whose AAR requires
  `compileSdk` 37 and AGP 9.1.0; this app builds with `compileSdk` 36, AGP 9.0.1, Kotlin 2.3.20 and
  Compose 1.10.6. 1.7.2 resolves onto them unchanged (only `androidx.tracing` moves 1.2.0 → 1.3.0).
  Its API is `hazeSource` / `hazeEffect` (`hazeChild` is its deprecated alias); the blur is in the
  one artifact.
- **The floating Play is drawn by the nav host,** not as a sibling of the `LazyColumn`: the list is
  inside the navigation content, the glass's source, and a glass surface inside its own source
  finds nothing to draw (Haze excludes it); a second source for the list would break the one-source
  rule and the grep. Drawn beside the content, the glass blurs the rows beneath it.
- **The scrubber rides in the transport's glass,** not above it on the bare roll: its times are text.
- **Glass without a blur where nothing passes beneath** (the rail; the tab bar over Now playing and
  Keys), and **the lens as a veil over one blur** instead of a second `GlassSurface`: the same
  pixels, and what brought the tablet frame from 21.6 % janky to 0.42 % (above). `HazeInputScale.Auto`
  and `expandLayerBounds = false` likewise.
- **`LiveDot` breathes through its layer's alpha:** drawn in its canvas, the breath redrew the whole
  screen every frame while playing, and with it the glass.
- **Without glass the transport does not float** (below API 31, High contrast text): a solid band over
  the roll would hide its history; it stands under the roll as before.
- **`PositionClock` holds to where the run began** rather than `−preRoll`: the same during the pause,
  and a frame never reads a position before a seek's target.
- **The media session stands at 0:00 with speed 0 through the pause** and is published again when it
  ends, rather than only clamping: the system's clock then never shows a negative time.
- **In the panel the roll strip takes the pane's height** (at least 120 dp): at 120 dp its history
  (40 dp) could never hold the 128 dp transport and the plan's own fallback would always apply; on a
  tall pane it now carries the glass transport, on a short one the transport stands under it.
- **`PieceArt`** (the composer's portrait, else the piece's own roll card, as the piece sheet) for the
  mini player and the panel, rather than `ComposerArt`, whose fallback is a mosaic of other pieces
  (for an unknown composer, of unrelated ones).
- **On glass the tab labels are all `onSurface`:** the unselected ones in `onSurfaceVariant` would read
  3.2:1 over the worst backdrop, under 4.5:1 for text; the pill and the glyph tell the chosen tab.
- **The mini player is not shown on Now playing** (the full player is there), reads "Opening the
  piece…" while a first piece loads, and the hairline between it and the tabs moves meanwhile.
- **The panel's Up next glyph sits in a NOW PLAYING eyebrow row** at its top (the plan named the glyph,
  not its place). The panel scrolls under 560 dp instead of squeezing.
- **`rememberSaveable` without keys:** its `key` parameter is deprecated in Compose 1.10; the panel's
  sheets are remembered where it is composed, apart from Now playing's.
- **`Format.seconds` reads "0 s" at 0;** the stepper shows "Off" there, and the hub "No pause".

## Tests added in M16

`ColorTokensTest` (+2: the sounding yellow clears 3:1 on both surfaces of its appearance, with the
recorded figures; a yellow, hue 40–55°, and not a red), `PlaybackEngineTest` (+5, virtual clock: the
first event exactly 2 s after play and nothing before; the position below zero and a tempo change
inside the pause; a pause inside it resuming from 0 at once; a seek inside it playing at once; the
wake time at the anchor), `PlayerTest` (+2: the pause before each piece and the gap between two;
resume without a pause; the hands test also checks `composerKey`), `SettingsRepositoryTest` (+1:
2 s at first, remembered, held to 0–5 s), `FormatTest` (+1: `seconds`), `GlassTokensTest` (6: the
tokens; text on the container; the play glyph on the lens; secondary glyphs and why nothing tertiary;
the specular whites; the veiled lens equals the container), `TransportFloatsTest` (3); `GroupSummariesTest`
(the Playback value) and `DiagnosticsExporterTest` (20 lines) changed; `Wcag` shared. 681 tests
before, 701 after.

---

# v1.5 — M17: schema v3, built-in playlists, channels and tiles, display mode; release 1.5 (versionCode 9)

Read `DESIGN.md › v1.5 — M17` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M17. This run releases 1.5 (M15, M16 and M17): `versionCode` 9, `versionName` "1.5",
`Provenance.text` "Made by Steven Jin · v1.5 · eab16a502f679465", the entry drafted at the end of
`releases/history.json` (`"draft": true`), which `tools/publish-release.sh` replaces when it
publishes; `latest.json` still names 1.4 until then.

## Files

Added:

- `data/db/ScheduleEntity.kt` (`enum class ScheduleKind { PLAYLIST, CHANNEL, PIECE }`,
  `ScheduleEntity(id, days, startMinute, kind, target, endMinute?, volumePct?, enabled = true,
  createdAt)`), `data/db/ScheduleDao.kt` (`observeAll()` by start then id, `@Upsert upsert`,
  `delete(id)`, `setEnabled(id, enabled)`): made now, used from M19.
- `data/builtin/BuiltInCatalogue.kt` (`ASSET` "builtin_playlists.json", `load(context)`,
  `parse(json)`), `data/builtin/BuiltInPlaylists.kt` (`Matcher`, `BuiltInList.matches(pieces)`,
  `interface BuiltInStore`, `BuiltInPlaylists.refresh(store)`, `builtInName`, `linkChanges` →
  `LinkChanges`).
- `channels/Channels.kt` (`PoolMatcher` = `All` | `BuiltIn(list)` | `Match(composers, titles,
  maxNotesPerSecond)`, `Channel(key, name, pool)`, `CardComposer`, `ChannelSummary(key, name, ids,
  composers)` with `size`, `playable` (`MIN_POOL` 3), `object Channels` (`ASSET` "channels.json",
  `CARD_COMPOSERS` 4, `load`, `parse`, `summaries`, `topComposers`), `ChannelPools`),
  `channels/ChannelPlayer.kt` (`interface ChannelDeck`, `PianoLoudness(volume, fullPower)`,
  `interface PianoVolume`, `ChannelPlayer`).
- `ui/ChannelCopy.kt` (the channel's words: `eyebrow(composer, channel)` "CLAUDE DEBUSSY · CALM ·
  CHANNEL" before the Eyebrow's capitals, `cardMeta(summary, playing)`, "Add more pieces",
  "Coming in the next update"; `rememberChannelName(key)`), `ui/IdleWatch.kt` (`IdleTimer`,
  `IdleState`, `Modifier.watchTouches`, `rememberIdle`, `DisplayModeTimeout`),
  `ui/screens/display/DisplayScreen.kt`, `ui/screens/library/ChannelCard.kt`, `ChannelRow.kt`,
  `ChannelsGrid.kt` (`ChannelsHeader`, `LazyListScope.channelsGrid`), `ChannelVolumeSheet.kt`.
- Assets `builtin_playlists.json`, `channels.json`; `app/schemas/…PianoDatabase/3.json`.
- Tests: `data/db/SchemaV3Test.kt`, `data/db/ExportedSchema.kt` (the exported schemas' reader,
  shared with `SchemaV2Test`), `data/builtin/BuiltInPlaylistsTest.kt`, `LibraryFixture.kt`,
  `LibraryFixtureTest.kt`, `channels/ChannelsTest.kt`, `ChannelPlayerTest.kt`, `ui/IdleWatchTest.kt`;
  resources `library_titles.csv` (the `midi` folder as the importer names it: collection, composer,
  title, notes, duration; 1,727 rows), `library_titles_all_songs.csv` (ALL-SONGS.zip, 1,726) and
  `epic_on_piano_index.csv` (the Epic zip's INDEX, 45).

Changed: `data/db/Migrations.kt`, `PianoDatabase.kt`, `PlaylistEntity.kt`, `PlaylistDao.kt`,
`PieceDao.kt` (`list()`, `existing(ids)`); `data/LibraryRepository.kt`; `AppGraph.kt`;
`MainActivity.kt`; `service/ImportService.kt`, `PlaybackService.kt`; `player/Player.kt`,
`PlayerState.kt`; `piano/PianoSettingsRepository.kt`; `settings/Settings.kt`;
`diag/DiagnosticsExporter.kt`; `ui/theme/Color.kt`, `Theme.kt`; `ui/components/Artwork.kt`,
`NoteCanvas.kt`; `ui/NavHost.kt`, `AdaptiveFrame.kt`, `PlaybackStarter.kt`;
`ui/screens/library/LibraryScreen.kt`, `LibraryViewModel.kt`, `LibraryRows.kt`,
`LibraryDialogs.kt`, `PlaylistHeader.kt`; `ui/screens/nowplaying/NowPlayingScreen.kt`,
`NowPlayingPanel.kt`; `ui/screens/piano/PianoViewModel.kt`, `pages/DisplayPage.kt`;
`Provenance.kt`; `app/build.gradle.kts`, `gradle/libs.versions.toml` (sqlite-jdbc, tests only);
`releases/history.json`; `tools/publish-release.sh`; tests `SchemaV2Test`, `PlayerTest`,
`PianoSettingsRepositoryTest`, `SettingsRepositoryTest`, `DiagnosticsExporterTest`,
`LibraryStatesTest`, `GroupSummariesTest`, `ColorTokensTest`, `ReleaseManifestTest`; README.

## Schema v3

`PianoDatabase` version 3, `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. `MIGRATION_2_3` runs
`SchemaV3.DDL` in order, each statement pinned to `3.json` by `SchemaV3Test`:

```sql
ALTER TABLE `collections` ADD COLUMN `builtIn` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `collections` ADD COLUMN `builtInKey` TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_builtInKey` ON `collections` (`builtInKey`);
CREATE TABLE IF NOT EXISTS `schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `days` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `kind` TEXT NOT NULL,
  `target` TEXT NOT NULL, `endMinute` INTEGER, `volumePct` INTEGER,
  `enabled` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL);
```

`PlaylistEntity` gains `@ColumnInfo(defaultValue = "0") builtIn: Boolean = false` and `builtInKey:
String?` (unique index; SQLite lets any number of rows hold NULL in it); `PlaylistSummary` carries
both. `PlaylistDao.byId`, `byBuiltInKey`. Nothing is dropped or rewritten: a 1.4 database opens
with every row (`SchemaV3Test` migrates one on a real SQLite, then checks it against `3.json`).

## Built-in playlists

- **The catalogue** (`assets/builtin_playlists.json`): `{"about": …, "lists": [{"key", "name",
  "pieces": [{"composer", "title", "collection"?}, …]}, …]}`, the lists in their order (popular 28
  matchers, recognisable 25, epic 45) and each list's matchers in playing order. `composer` is
  the library's `composerKey` (the folded surname `ComposerNames` gives), a string or a list (a
  piece known by its arranger too: `["schubert", "liszt"]`; `""` is an unknown composer); `title` a
  regular expression, case-insensitive, found (`containsMatchIn`) in `PieceEntity.titleKey` =
  `TextKeys.fold(title)`, so written without accents; `collection` the INDEX.csv collection,
  exactly. Popular and Recognisable start every pattern with `^(?!.*\[\d)`: ALL-SONGS.zip's
  numbered repeats ("… [2]") are other takes of a MAESTRO piece already counted. Epic's patterns are
  anchored on the titles the set's own files carry under the three names one file can have (the
  Epic zip's INDEX, the `midi` folder's INDEX, ALL-SONGS.zip's file name), so the list is Steven's
  set wherever it came from. The catalogue was tuned with the corpus dumps (below); written from
  scratch for Canon in D and Rhapsody in Blue it would find nothing (the corpus has neither), so
  they are not in it.
- **Matching** (`BuiltInList.matches`, pure): pieces grouped by `composerKey`; for each matcher its
  composers' pieces it accepts (composer, collection when given, title), sorted by
  (`titleKey`, `id`), the first `MAX_HITS` = 4; a `LinkedHashSet` keeps each piece at its first
  place. The same library always gives the same list.
- **Refresh** (`BuiltInPlaylists.refresh(store)`, one at a time under a `Mutex`): reads every piece
  once (`PieceDao.list()`); for each list, when it matches nothing and has no row yet it is
  skipped, else `ensureBuiltIn(key, name)` and `setPlaylistPieces(id, ids)`. `AppGraph`:
  `refreshBuiltIns()` on IO, logging and swallowing failures; at `start()`; after an import with
  `imported > 0` (`ImportService`, inside the import's coroutine, before `ArtworkService.start`); and
  `library.namesChanged` (a `SharedFlow` that `rename`, `renamePlaylist` and `deletePlaylist` emit)
  debounced by `BUILT_INS_SETTLE_MS` = 2,000.
- **`LibraryRepository`** implements `BuiltInStore`. `ensureBuiltIn(key, name)` (one transaction):
  the row with that `builtInKey`, made if needed, named `builtInName(name, taken)`: the name, or
  when another playlist holds it (`byName`, case aside) "name · built in", then "name · built in
  2"…; an existing row is renamed when its wanted name changed (it takes its own back once free).
  `setPlaylistPieces(id, orderedIds)` (one transaction): the ids still in the library
  (`PieceDao.existing`, in chunks), then `linkChanges(current, ordered)`: remove, move (a new
  `position`) and add only what differs. `renamePlaylist`, `deletePlaylist`, `addToPlaylist`,
  `removeFromPlaylist`, `reorderPlaylist`, `movePiece` return without writing for a built-in.
  `playlistId(name)` (a new playlist, an INDEX set) and `renamePlaylist` first move a built-in
  that holds the name aside (`moveBuiltInAside`), so the person's playlist takes it.
- **UI.** `LibraryViewModel.playlists` (Add to playlist's choices, the rename dialog's names) leaves
  the built-ins out. `PlaylistShelf.shown(all)`: the built-ins first, by id (the order made), those
  with no pieces left out, then the rest as before. Tiles: the eyebrow `BUILT_IN` · "14 PIECES"; the
  menu Change photo only. The playlist page: eyebrow "Built in · …", menu Change photo only, no
  drag handles, no Remove or Move in the rows' menus, the Play and Shuffle as any playlist; empty:
  "This playlist is empty." / "It fills itself from the library's pieces." `LibraryDialogs`: for a
  built-in, Rename and Delete become `NeverFor(onClose)` (nothing shown).

## Channels

- **The catalogue** (`assets/channels.json`): `{"about": …, "channels": [{"key", "name", and
  "all": true | "builtIn": "<list key>" | "composers": [...], "titles": [...],
  "maxNotesPerSecond"?}]}`, in their order on screen: calm (composers satie, debussy, field; titles
  nocturne, berceuse, gymnop, gnossienne, clair de lune, adagio, lullaby, reverie, traumerei,
  consolation, arabesque, andante; 6 notes a second), epic (`builtIn` "epic"), baroque (bach,
  handel, scarlatti, couperin, rameau, purcell, telemann, "bach cpe"), romantic (chopin, schumann,
  liszt, brahms, mendelssohn, schubert, grieg, tchaikovsky, rachmaninoff, dvorak, smetana,
  mussorgsky), impressionist (debussy, ravel, satie, faure, albeniz, granados, falla, scriabin),
  nocturnes (nocturne, notturno, nachtst), etudes "Études" (etude, etued, `\bstud(?:y|ies|ie)\b`),
  everything (`all`).
- **Pools** (`PoolMatcher.pool(pieces)`, pure, in the library's order; Epic in the list's):
  `Match` accepts a piece whose `composerKey` is one of `composers` or whose `titleKey` matches
  one of `titles` (joined into one case-insensitive regex), and, with `maxNotesPerSecond`, whose
  `noteCount × 1000 / durationMs` is at most that (a piece of no length is left out). `ChannelPools`:
  `library.all()` debounced 500 ms, `Channels.summaries` on `Dispatchers.Default`,
  `distinctUntilChanged` (a play count changes the library, not a card), failures logged,
  `stateIn(Eagerly, null)`; `summary(key)`. A card's composers: the pool's pieces with a known
  composer grouped by key, most pieces first, then by name, four.
- **`ChannelPlayer(deck, pools, volumeOf, piano, scope, random)`**, on the main thread after
  `start()` (which collects `deck.state`):
  - `play(key)`: the pool from `pools(key)` (the card's ids; null until worked out), distinct;
    under `MIN_POOL` → false and nothing changes. A `Session(key, pool)` shuffles the pool into
    `remaining`; with `starting` set, `applyVolume(volumeOf(key))`, the session, then
    `deck.playAll(deal(FIRST = 25), shuffle = false, channel = key)`; then `onState` once.
  - `onState(state)`: ignored while `starting`; when `state.channel != session.key` the session ends
    (and when `state.channel == null`, the volume is restored; a channel that took over keeps the
    first one's restore); else when `state.queue.upNextIds.size < TOP_UP_BELOW = 5`,
    `deck.addToQueue(deal(TOP_UP = 10), channel = key)`.
  - `deal(n)`: from `remaining`, reshuffling when it runs out: the pool without the last
    `min(RECENT = 20, pool.size / 2)` dealt. `recent` keeps the last 20.
  - Volume: `applyVolume(pct)` (0–100): when `piano.current()` (`PianoState.Ready` with "volume")
    is known, the first time `Restore.Piano(loudness)` and `piano.hold(pct)`; else the first time
    `Restore.Velocity(the player's velocity, applied)` and `deck.setVelocity(velocityFor(pct))`,
    `velocityFor(v) = 50 + v / 2` (in `PlaybackLimits.VelocityPct`). `restoreVolume()`:
    `piano.release(loudness)`, or `setVelocity(previous)` only while the velocity is still the one
    the channel set. `volumeChanged(key, pct)` applies at once to the channel playing. `stop()` =
    `deck.stop()`; `playing` = the session's key.
- **`AppGraph.pianoVolume`**: `current()` = `PianoLoudness(values["volume"] rounded,
  values["fullpower"] == "1")`; `hold(pct)` = `pianoSettings.holdTemporarily("volume", pct)`;
  `release(previous)` = `releaseTemporary("volume", previous.volume)`, and when it was on,
  `releaseTemporary("fullpower", 1)` (the firmware turns Full power off below 100).
- **`PianoSettingsRepository.holdTemporarily(name, value)` / `releaseTemporary(name, value)`**: a
  held value is sent like any other but its write has `persist = false` (never counted for a
  save); while anything is held, a save that `leave()` asks for waits (`saveWhenReleased`) and goes
  after the last release. A release writes the value back unless the person set that setting on
  this connection (`setByPerson`, whose value stands and is saved as usual); a hold from before a
  reconnection is put back too.
- **`Player`** (`ChannelDeck`): `PlayerState.channel: String?`. `playAll(ids, shuffle, channel)`
  sets the queue and the channel in one state update (`setQueue(queue, channel)`), and remembers
  the channel's queue uids (`channelUids`); `addToQueue(ids, channel)` adds the new uids when the
  channel matches; `play` (a piece from a list), `playAll` without a channel, `stop`,
  `stopAndFlush` and `leaveChannel()` (the notification's dismiss, `PlaybackService`) clear it;
  `skipToQueueEntry` keeps it only for one of the channel's entries; `next`, `previous`,
  `addToQueue`, `playNext` keep it. Every queue change publishes queue and channel together, so a
  watcher running at once on the main thread never sees the channel's queue without its channel.
- **`AppGraph.start`'s settings collector** applies each playback setting only when it changed
  (`applied`), so a channel's velocity stands until the person changes Velocity.
- **UI.** `PlaybackStarter.playChannel(key)`: `channelPlayer.play`, then the playback service as
  for any start; `LibraryPlay.channel(key)` then opens Now playing on phones, as a piece does. The
  eyebrow: `ChannelCopy.eyebrow(composer, rememberChannelName(
  state.channel))` on Now playing, in `NowPlayingPanel`'s title and in display mode.
  `ChannelVolumeSheet`: `ModalBottomSheet` (`surfaceVariant`), Eyebrow "Channel", the name in
  Title, `SliderRow("Volume", 0..100, step 1, Format.percent)` whose change saves
  `setChannelVolume` and calls `channelPlayer.volumeChanged`, and its note.
- **Settings**: `channelVolumes: Map<String, Int>` (key "channelVolumes", one JSON object,
  `{"calm":60}`, sorted; unreadable → none; values held to 0–100), `channelVolume(key)` (default
  `DEFAULT_CHANNEL_VOLUME` 70), `setChannelVolume(key, pct)`.

## Tiles

`ChannelRow` (at the top of `Listing.Playlists` when the search is empty): `SectionEyebrow`-like
row "Channels" with a "See all" `TextButton`, then a `LazyRow(contentPadding 16 dp, spacedBy
12 dp)` keyed by channel. `ChannelCard(summary, playing, connected, onPlay, onSetVolume)`: 280 dp
wide (`ChannelCardWidth`), `ArtFrame(aspect = CHANNEL_CARD_ASPECT 1.4f)` (`Artwork.kt` gains
`aspect`, and `framed` on the art so a mosaic's cells carry no frames of their own), a public
`Mosaic(count, modifier, cell)` of `ComposerArt(key, name, ArtSize.Tile, framed = false)`, or
`MonogramTile(name)` with no composer; the band: `heightIn(min = 56 dp)`, `surface.copy(alpha =
GlassTokens.ContainerAlpha)`, a 1 dp `LocalHairline` top, the name `titleLarge` and the Eyebrow in
`onSurface` (with `LiveDot` while playing). `combinedClickable` (no haptic) with
`clearAndSetSemantics` ("Calm channel, playing", Button); the long-press `DropdownMenu`: "Set
volume", and "Schedule" disabled with `disabledTextColor = onSurfaceVariant`. `ChannelsGrid`: a
Library page (`Group.Channels`, `Listing.Channels`) with `ChannelsHeader(count, onBack)` and
`channelsGrid(...)` in `TileRow`s of `columns` (2/3/4). The flows: `ChannelPools` (debounced, off
the main thread, distinct), so a refresh never re-lays the row.

## Display mode

- `IdleTimer(now, timeoutMs)` (pure): `touch(now)` remembers a touch at most once a second
  (`WRITE_EVERY_MS`), always when idle; `isIdle`, `remaining`, `retimed`. `IdleState` (`touch()`,
  `idle`, `touches`); `Modifier.watchTouches(onTouch)` = `pointerInput(Unit) {
  awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); onTouch() }
  } }`, consuming nothing; `rememberIdle(enabled, timeoutMs)`: a `LaunchedEffect(state, enabled,
  timeoutMs, state.touches)` that waits `remaining` and sets `idle`. `DisplayModeTimeout.ms`: 60 s;
  in debug builds `debug.stevenpiano.idlesecs` (read once per process with `getprop`).
- `NavHost`: `rememberIdle(settings.displayModeAfterMinute, DisplayModeTimeout.ms)`; the outer
  `Box(fillMaxSize().watchTouches(onTouch))` holds `RailFrame { Scaffold … }` and, last,
  `DisplayOverlay(idle, onLeave = onTouch)`, which composes `DisplayScreen` when `idle` and the
  player holds a piece. Inside the nav host's `CompositionLocalProvider` (the monochrome switch
  reaches it).
- `DisplayScreen(onLeave)`: `DisplayTheme(black = standbyCanvas == BLACK, darkTheme =
  appearance.dark(isSystemInDarkTheme()))` (`Theme.kt`: black → `PianoTheme(true)` with
  `DarkScheme.copy(surface = DisplayBlack, background = DisplayBlack)`; otherwise the app's
  `PianoTheme(darkTheme)`); `BackHandler(onLeave)`; `DisposableEffect`: `keepScreenOn` and the
  system bars hidden (`WindowInsetsControllerCompat`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`),
  both undone on dispose. A `Box(fillMaxSize, background(canvas))` whose `pointerInput` consumes
  every change of each gesture and calls `onLeave` (semantics: "Display mode: <title>", click
  "Leave display mode"); `PieceArt(pieceId, composerKey, ArtSize.Full, fillMaxSize, framed = false)`
  at alpha 0.25; a vertical gradient from transparent to the canvas at 0.72; then, inside the
  system bars' and cutout's insets and 24/16 dp: the title (`displayMedium`, two lines; since the
  1.5 review `displayLarge`, 45 sp, on `frame.twoPane`), the Eyebrow (composer and channel; on
  `frame.twoPane` `EyebrowLarge`, 16 sp, 8 dp under the title), `NoteCanvas(PAPER_ROLL, blackKeyLanes = false)` with `RollClock` and
  `rememberFrameNanos`, a hairline, `KeyboardStrip`, and a `FlowRow` of `LiveDot` with "Sent to
  piano" / "Not connected" and `Eyebrow(Provenance.byline)`. `NoteCanvas` gains `blackKeyLanes:
  Boolean = true`.
- `Color.kt` `DisplayBlack = Color(0xFF000000)`, used by `Theme.kt`'s `DisplayTheme` only.
- Settings: `displayModeAfterMinute` (false), `standbyCanvas: StandbyCanvas { BLACK, INK }`
  (BLACK), both on the Display page (STANDBY: `SwitchRow` "Display mode after a minute" with its
  note, `ChoiceRow` "Standby canvas" "Black" · "Same as the app"); unknown stored names read as
  the default.

## Appearance

`Appearance { SYSTEM, LIGHT, DARK }` (`dark(systemDark)`), key "appearance", default SYSTEM,
unknown → SYSTEM; `AppGraph.appearance: StateFlow<Appearance?>` (null until the settings are read).
`MainActivity.setContent`: nothing is composed until it is known (so the first frame is never the
wrong appearance); `dark = appearance.dark(isSystemInDarkTheme())`; `PianoTheme(darkTheme = dark)`;
`LaunchedEffect(dark)` → `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }`
for both bars), so the bars' icons follow the app. The Display page's first section, APPEARANCE:
`ChoiceRow("Appearance")` "Follow system" · "Light" · "Dark" (`Appearance.label`). The hub's
Display value is unchanged (`GroupSummariesTest`). `settings.txt` in Share diagnostics lists
`channelVolumes`, `appearance`, `displayModeAfterMinute`, `standbyCanvas` (24 lines).

## Greps (v1.5 — M17)

- `grep -rn "Color(0x" app/src/main --include=*.kt | grep -v ui/theme`: nothing.
- `grep -rn "DisplayBlack" app/src/main`: `ui/theme/Color.kt`, `ui/theme/Theme.kt`.
- `grep -rn "LocalNoteSounding" app/src/main`: `Theme.kt`, `ScorePages.kt`.
- `grep -rln "dev.chrisbanes" app/src/main`: `Glass.kt`; `hazeSource`/`HazeState`: `Glass.kt`,
  `NavHost.kt`, `NotePanel.kt`.
- `grep -rn "Modifier.blur\|0\.0\.0\.0" app/src/main`: nothing.

## Measured (September 2026)

- Tests: 763, none failing (7 skipped without `-Pcorpus`: the two `LibraryFixtureTest`s beside
  M16's five). `lint`: 0 errors, 29 warnings (M16's 28 and the version catalog's notice that
  sqlite-jdbc has a newer version). `assembleDebug`, `assembleRelease` clean.
- The built-in lists (`BuiltInPlaylistsTest`, `LibraryFixtureTest -Pcorpus`), Popular ·
  Recognisable · Epic on piano: the `midi` folder 17 · 29 · 49; ALL-SONGS.zip 17 · 30 · 50; the
  Epic zip alone 7 · 13 · 45 (all 45, in the zip's order); no matcher over 4. Matchers that find
  nothing in Steven's library (their pieces are not in it; they fill when such a file comes):
  Popular 13 of 28 (Gymnopédie No. 1, Arabesque No. 1, Rêverie, the Minute Waltz, Waltz Op. 69
  No. 2, Gnossienne No. 1, Consolation No. 3, Solfeggietto, the Maple Leaf Rag, Spring Song,
  Nocturne Op. 9 No. 1, Ave Maria, To a Wild Rose), Recognisable 9 of 25 from the folder, 8 from
  ALL-SONGS.zip (Tristesse, the Military Polonaise, the Wedding March, the Swan, the Toccata and
  Fugue, the Mountain King, the Sugar Plum Fairy, Ode to Joy; the Appassionata's finale only as
  ALL-SONGS.zip names it: piano-midi.de calls all three movements alike).
- Channel pools, the folder / ALL-SONGS.zip: Calm 30 / 31, Epic 49 / 50, Baroque 197 / 284,
  Romantic 912 / 912, Impressionist 120 / 121, Nocturnes 23 / 24, Études 186 / 186, Everything
  1,727 / 1,726.
- On `steven_piano` (debug build, the emulated piano; phone 1080 × 2400 px at 420 dpi, and
  2560 × 1600 px at 240 dpi for the tablet frame): M16's library (1,726 pieces) upgraded in place
  to v3 with its playlist kept and the three built-ins made (14 · 30 · 47 there: 27 of its pieces
  came from an older test set whose short names, "Moonlight Sonata I", shadow the ALL-SONGS copies
  of the same files); the channel row and grid, Calm playing with its badge and eyebrow on Now
  playing and the tablet panel; the emulated console (`adb logcat -s PianoLink`) received volume
  70 when Calm started, volume 100 and fullpower 1 when a piece chosen from the list ended the
  channel, and no save; 21 Nexts took the queue from 25 to 35 pieces; the Set volume sheet sent
  volume 49 at once while Calm played, and kept it for Calm; display mode after the shortened wait on
  the phone and the tablet frame, dark and light, black at every edge (pixels `#000000`), and on
  "Same as the app" ink (`#0E0E0E`) and paper (`#F4F1EA`); leaving it on a touch with the app as it
  was; Appearance Light with the system dark (and Dark with it light).
- The 1.4 → 1.5 migration with the release builds (the same release key): 1.4 from `../apk/`, the
  Epic on piano folder imported (45 pieces, and the INDEX's playlist "Epic on piano") and a
  playlist "Popular" made with two pieces; 1.5 installed over it (`adb install -r`): both
  playlists as they were, and before them the built-ins "Popular · built in" (7 pieces),
  "Recognisable" (13) and "Epic on piano · built in" (45); the channel row with Epic at 45 and
  Calm reading "Add more pieces" (fewer than three pieces of the set are calm enough). Renaming
  the person's "Popular" to "Evening" gave the built-in its name back a few seconds later;
  renaming it "Popular" again moved the built-in beside it once more.

## Deviations from the plan, and why

- **The channel volume is held, not set.** `pianoSettings.set("volume", pct)` counts the change for
  the save the Piano tab sends when it stops (M15), so a channel's volume would have been stored
  on the piano. `holdTemporarily` / `releaseTemporary` send it the same way but never count it, and
  hold back a save meanwhile. The firmware turns Full power off below 100 %, so the release puts
  back the pair, volume and Full power.
- **Queue and channel in one update, and `starting`.** With `playAll` setting the queue and then
  the channel, `ChannelPlayer`'s watcher (on `Dispatchers.Main.immediate`) saw the new queue
  without the channel and ended the channel at once (the emulated console showed the volume put
  back right after the tap). `Player` now publishes both together, and `ChannelPlayer` ignores what
  the player says while it hands a channel over; `ChannelPlayerTest` holds the case on the real
  `Player`.
- **Pools come from the cards** (`ChannelPools`, worked out off the main thread and cached) rather
  than from the library at `play`: the tap needs no query, and a pool not yet worked out (the first
  half second after start) does not play.
- **Calm's "chopin-by-title"** is the titles for any composer: a Field or Chopin nocturne, a
  Beethoven adagio and a Schumann Träumerei all qualify, and the density cap keeps the busy ones
  out. Études also takes `etued` (a misspelling in the corpus) and the German `Studie`.
- **Epic's matchers are anchored titles** under the three names a file can have, rather than loose
  composer-and-title patterns: loose ones pulled other pieces of the same name into Steven's set;
  anchored, the zip's 45 come back in order, and the folder and ALL-SONGS.zip find the same files
  (a few pieces have more than one recording there, hence 49 and 50).
- **`composer` may be a list, or "" for an unknown composer**, beyond the plan's single key: a
  piece is known under its arranger too (Ave Maria as Schubert's or Liszt's, the Flight of the
  Bumblebee as Rimsky-Korsakov's or Rachmaninoff's), and Mutopia's Entertainer in the `midi` folder
  carries no composer.
- **A playlist renamed to a built-in's name takes it**, the built-in moving beside it, as a new
  playlist or an INDEX set does (the plan named only the first case). The rename dialog lists only
  the person's playlists, so it no longer refuses such a name with a database error.
- **Test-only dependency `org.xerial:sqlite-jdbc` 3.41.2.2** (Apache-2.0), so `SchemaV3Test`
  migrates a real 1.4 database with its rows on the JVM (the project has no instrumented tests).
- **`releases/history.json` holds a drafted entry** (`"draft": true`, versionCode 9, "1.5", the
  notes, the APK's address, minSdk; no hash or size until the script builds it).
  `ReleaseManifestTest` compares `latest.json` with the last published entry and checks the draft
  (this source's version, the last entry, notes ≤ 1,000 characters), and `tools/publish-release.sh`
  drops a draft with its tag before appending the published entry. Nothing else in the script
  changed; it was not run.
- **Steven's additions during the run**: the Appearance setting (overriding v1.0's "no in-app
  appearance switch") and the Standby canvas, with `DisplayBlack` still only in `DisplayTheme`.
- **Display mode hides the system bars** while it shows (a tablet's taskbar left a light strip
  under the black), and the roll there has no black-key lanes (a barcode over the portrait).
- **The tablet panel's connection row** (Fable's review item) takes the place of the 8 dp spacer
  that closed the panel's column: with the row added on top, the roll strip on a 2560 × 1600 px
  tablet was 2 dp short of holding the glass transport.
- **The M16 library on the emulator** carried an older 27-file test set, so its built-ins read
  14 · 30 · 47, not the 17 · 30 · 50 of a clean ALL-SONGS.zip import; replaying the catalogue over
  that database's own rows gives the same pieces in the same order.

## Tests added in M17

`SchemaV3Test` (7: the two columns as `3.json` declares them; the index and the schedules table
with its statements; everything else as v2; a 1.4 database migrated on a real SQLite keeps every
row; the result is what Room expects at v3; a built-in key names one playlist while NULLs are
many; a schedule is enabled by default), `BuiltInPlaylistsTest` (13: the catalogue's order;
patterns written for folded titles; folding; at most four, the first by title; order kept and each
piece once; collections and several or unknown composers; refresh makes a list when there is
something, then follows the library; the name beside the person's; link changes; in Steven's
library ≥ 15 · ≥ 15 · ≥ 30 both ways; no matcher over four in four libraries; the Epic zip's 45 in
order; Popular and Recognisable find only their own pieces in the zip), `LibraryFixtureTest` (2,
`-Pcorpus`: the two CSVs are what the importer makes of the corpus and of ALL-SONGS.zip),
`ChannelsTest` (8), `ChannelPlayerTest` (12, among them the real `Player` with a watcher that runs
at once, and Full power put back with the volume), `IdleWatchTest` (6), `PianoSettingsRepositoryTest`
(+4: a held value never saved; a save waits for the release; the person's value stands; a release
after a reconnection), `PlayerTest` (+1: the channel's life), `SettingsRepositoryTest` (+4: channel
volumes; unreadable volumes; appearance, display mode and canvas remembered; unknown names read as
the defaults), `LibraryStatesTest` (+1: the shelf), `GroupSummariesTest` (+1: Display unchanged by
appearance and standby), `ColorTokensTest` (+2: the display's black and the camera body's text on
it, 18.8 · 8.3 · 6.1:1; the card's band keeps the glass's contrast), `ReleaseManifestTest` (+1: the
drafted release); `DiagnosticsExporterTest` (24 lines) and `SchemaV2Test` (the shared reader)
changed. 701 tests before, 763 after.

---

# v1.5.1 — M18: the web panel, guests' requests, the poster; release 1.5.1 (versionCode 10)

Read `DESIGN.md › v1.5.1 — M18` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M18 (binding), whose fifteen-point audit checklist `docs/SECURITY_AUDIT.md › 1.5.1 — web panel
(pre-audit notes)` answers point by point. This run releases 1.5.1: `versionCode` 10, `versionName`
"1.5.1", `Provenance.text` "Made by Steven Jin · v1.5.1 · eab16a502f679465", the entry drafted at the
end of `releases/history.json` (`"draft": true`); `latest.json` still names 1.5 until
`tools/publish-release.sh` runs, after the security audit and Fable's review. The 1.5 review's two
fixes came first, as their own commit (`4dacf52`: display mode's title in Display Large with a
16 sp eyebrow on wide frames; the standby note without "black").

## Dependencies

Pinned in `gradle/libs.versions.toml`, from Maven Central:

- `org.nanohttpd:nanohttpd:2.3.1` and `org.nanohttpd:nanohttpd-websocket:2.3.1` (BSD-3-Clause; the
  notice is in AUTHORS and `third_party/nanohttpd/LICENSE.txt`). Small, readable, and it runs as a
  real server in JVM tests. Its last release is from 2016: the server works around six of its
  behaviours (*The server*, below).
- `io.github.g0dkar:qrcode-kotlin:4.5.0` (MIT; AUTHORS and `third_party/qrcode-kotlin/LICENSE.txt`).
  It is a Kotlin Multiplatform library: Gradle resolves the coordinate to its Android variant,
  `qrcode-kotlin-android` 4.5.0. Only its encoder is used (`QRCodeProcessor(text,
  ErrorCorrectionLevel.MEDIUM).encode()` → `QrMatrix`); the drawing is the app's own (SVG for the
  poster, a Compose `Canvas` on the tablet).
- `com.google.zxing:core:3.5.4` (Apache-2.0), **tests only**: `PosterTest` decodes the code back.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`):

- `M/web/WebBackend.kt` (the panel's one view of the app, and its data: `WebPiece`, `WebState`,
  `WebPlayer`, `WebQueueItem`, `WebChannel`, `WebPiano`, `CatalogueList`, `GuestSettings`,
  `SettingsChange`, `QueueCommand`, `Transport`, `ChannelStart`, `WebLimits`), `M/web/AppWebBackend.kt`
  (its Android side over `AppGraph`), `M/web/WebAuth.kt` (`PinHash`, `ConstantTime`, `Sessions`,
  `LoginGuard`), `M/web/WebApi.kt` (JSON in and out), `M/web/WebServer.kt` (the routes, `WebCookies`,
  `securityHeaders`, the thread pool, the client handler), `M/web/WebAssets.kt` (the allow-list,
  `AssetSource`, `AndroidAssets`), `M/web/WebAddress.kt` (`NetInterface`, `WebChoice`, `ListenerPlan`,
  `choose`, `plan`), `M/web/GuestRequests.kt`, `M/web/WebSocketHub.kt` (+ `FrameGuard`),
  `M/web/Poster.kt` (`QrMatrix`, `Poster.qr/svg/page/escape`), `M/web/PosterPrint.kt`,
  `M/web/WebPanel.kt` (`WebStatus`; the process's one set of sessions, guard, requests and backend).
- `M/service/WebService.kt`.
- `M/ui/screens/piano/pages/RemotePage.kt`, `M/ui/components/PinSheet.kt`, `M/ui/components/QrCode.kt`
  (`QrTile`, `QrSheet`), `M/ui/screens/library/RequestsBanner.kt`.
- `app/src/main/assets/web/`: `index.html`, `app.js`, `style.css`, `request.html`, `request.js`,
  `poster.html` (each with the 8-line notice as a comment; no build step, no framework).
- `third_party/nanohttpd/LICENSE.txt`, `third_party/qrcode-kotlin/LICENSE.txt`.
- Tests: `web/WebAuthTest.kt`, `WebServerTest.kt`, `WebApiTest.kt`, `WebAddressTest.kt`,
  `GuestRequestsTest.kt`, `WebSocketHubTest.kt`, `PosterTest.kt`, `WebAssetsTest.kt`; helpers
  `FakeWebBackend.kt`, `RawHttp.kt` (raw sockets, for what `HttpURLConnection` hides).

Changed: `AndroidManifest.xml` (the service; `FOREGROUND_SERVICE_SPECIAL_USE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`); `App.kt` (the notification channel); `AppGraph.kt` (`web`,
`setWebEnabled`, `startWebIfOn`, `reportFailedImport`); `MainActivity.kt` (starts the service on
`onStart` when Web control is on); `settings/Settings.kt` (the web keys, `StoredPin`, `webPin`,
`setWebPin`); `diag/DiagnosticsExporter.kt`; `data/imports/Importer.kt` (`importOpened`,
`runOpened`), `ImportLimits.kt` (`WEB_DIR`, swept at start); `data/db/PieceDao.kt` (`byIds`);
`data/LibraryRepository.kt` (`pieces(ids)`); `data/art/ArtworkRepository.kt`
(`portraitComposers`); `ui/Routes.kt` (`SettingsPage.Remote`); `ui/screens/piano/HubGroups.kt`,
`GroupSummaries.kt`, `PianoScreen.kt`, `PianoViewModel.kt`; `ui/screens/library/LibraryScreen.kt`;
the review fixes' `ui/theme/Type.kt`, `ui/components/Eyebrow.kt`, `ui/screens/display/DisplayScreen.kt`,
`ui/screens/piano/pages/DisplayPage.kt`; `Provenance.kt`; `app/build.gradle.kts`,
`gradle/libs.versions.toml`; `releases/history.json`; `provenance/sign.py` (the web pages
signed too); AUTHORS, README, DESIGN.md, this file, `docs/SECURITY_AUDIT.md`; tests `ImporterTest`,
`ImportLimitsTest`, `SettingsRepositoryTest`, `DiagnosticsExporterTest`, `GroupSummariesTest`,
`PianoPagesTest`, `RoutesTest`.

## Settings

DataStore keys `webEnabled` (false), `webGuests` (false), `webApproveFirst` (true), `webOnWifi`
(false), `webHostName` (none), `webPinSalt`, `webPinHash`. `PianoSettings` carries `webPinSet`, never
the salt or hash: those are read on their own (`SettingsRepository.webPin()` → `StoredPin`, whose
`toString` prints neither), so nothing that passes the settings around holds them. Web control
cannot turn on without a PIN (`AppGraph.setWebEnabled`), and the service stops if the PIN goes.
`settings.txt` in Share diagnostics lists the five switches and names, and `webPinSet`.

## The server (`web/WebServer.kt`)

One `WebServer` (a `NanoWSD`) per address the service listens on, port **8737**, never the
any-address. Checks, in order, for every request:

1. **Host** must be this listener's `address:8737`, or `webHostName:8737` (or `localhost:8737` on
   the emulator's loopback listener), lower-cased; otherwise **403** before anything else (DNS
   rebinding).
2. A **guest-only** listener (the Wi-Fi's, without Panel on Wi-Fi too) knows only the public routes
   and files; everything else is **404**, the socket included.
3. The route's access (table below): **401** without a valid session; **403** without
   `X-Steven-Piano: 1` or with an `Origin` other than `http://<Host>`; login needs the header but no
   session; a public POST that carries an `Origin` must carry its own.
4. Bodies: `WebApi.readObject` (below); the handler then runs under a 10 s timeout (**503**
   "The app is busy" past it; uploads are not timed).

What NanoHTTPD 2.3.1 does by itself, and what the server does about it:

- **It never skips a body the handler did not read**, so a kept-alive connection would read the
  rest of a refused upload as the next request. Every response closes its connection
  (`closeConnection(true)`), but a socket's handshake; before closing, the client handler sends the
  answer's end, then reads and drops up to 256 KB, for up to 500 ms **in all** (audit W3: it was
  500 ms per read, so a byte-a-second peer kept the thread draining without end), so a browser sees a
  413 as a 413 and not as "connection reset".
- **It bounds a request's arrival only by a per-read timeout** (10 s), so a peer sending a header byte
  every few seconds held a request thread until its head reached 8 KB — hours — and four held a
  listener (audit W3). The client handler now reads the head itself first, under a whole-request
  deadline (`DeadlineInput`, `REQUEST_DEADLINE_MS` 10 s) on top of the per-read timeout, and checks it
  (`RequestHead`, below); an upload's body and a socket's life `lift()` the deadline.
- **It answers a request it cannot parse itself, before `serve()`** — a malformed request line, an
  unknown verb, a broken `%zz` escape, an over-8 KB head, another HTTP version — without the security
  headers below, keeping the connection alive, and echoing the method into a `text/plain` body (audit
  W3). `RequestHead.check` now refuses each first (400/501/505/431/408, a second `Host` 400), written
  with the panel's headers, JSON, `Connection: close`, nothing of the request echoed; a head that
  passes goes to NanoHTTPD with every byte already read.
- **It asks DNS for every peer's name** (`InetAddress.getHostName`), seconds on a school network,
  per request: `createClientHandler` gives the session an address named by its own digits.
- **NanoWSD upgrades any path** that asks for a WebSocket: `serve` answers the upgrade only for
  `GET /ws` on a panel listener with a valid session, an `Origin` of `http://<Host>` and room for a
  socket (**404**, **401**, **403**, **503**), before NanoWSD's own handshake.
- **NanoWSD allocates whatever length a frame announces** (up to 2 GB) and joins fragments without
  end: every frame passes `FrameGuard` first (masked, payload ≤ 4 KB, ≤ 16 fragments; otherwise the
  socket closes before the payload is read).
- **Its accept loop retries at once after any failure, until its socket is closed.** Android
  destroys the sockets bound to an address that goes away (the Wi-Fi dropping), and the loop then
  spun a core at 100 % (seen on the emulator). `SteadyServerSocket`, the server's socket factory,
  pauses 20 ms more after each failure in a row (at most 500 ms), starts the count again after a
  success, and closes itself after 20 in a row, which ends the loop; the service's next look
  starts the listener again.
- **One value per header name**: no response sets two cookies.

Threads: `BoundedRunner`, 4 threads and a queue of 32 (a connection past that is closed at once);
each socket holds one thread while open, hence two sockets at most. A socket read waits at most
10 s, and a whole request at most 10 s (`DeadlineInput`; an upload's body and a socket exempt once
checked). NanoHTTPD's temporary files (none: no route reads a multipart form) go to `cacheDir/web`.

**Every response** carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'self'; img-src 'self' data:;
connect-src 'self' ws://<Host>; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` and
`Cross-Origin-Resource-Policy: same-origin`; JSON answers add `Cache-Control: no-store`, the pages
`no-cache`, the art `private, max-age=3600`. **No CORS header, ever**: a preflight is answered 405
with `Allow`, so a browser never sends another site's request with the custom header.

## Routes

Access: **public** (no session; every listener), **login** (the header, no session), **read** (the
`sp_session` cookie), **write** (the cookie and the header). Every route also passes the Host and
`Origin` checks above. Errors are `{"error": code, "message": text}`, with `retryAfter` (seconds)
on a 401 from login and on every 429.

| Method | Path | Access | Body | Answer |
|---|---|---|---|---|
| GET | `/`, `/app.js` | panel listeners | — | the panel's page and script |
| GET | `/style.css`, `/request`, `/request.js` | public | — | `/request` sets `sp_guest` when missing |
| GET | `/poster` | public | — | the poster, its QR and the guests' address filled in (the Wi-Fi's, else the tailnet's); 503 without either |
| GET | `/ws` | read, own Origin | — | 101; 401 · 403 · 404 · 503 (two open) |
| GET | `/api/state` | read | — | the state (below) |
| GET | `/api/library?q&category&offset&limit` | read | — | `{total, offset, pieces}`; `category` all · favorites · recent; `limit` ≤ 200 (50) |
| GET | `/api/playlists`, `/api/playlists/{id}` | read | — | `{playlists}`; `{playlist, pieces}` or 404 |
| GET | `/api/composers`, `/api/composers/{key}` | read | — | `{composers}`; `{composer, pieces}` or 404 |
| GET | `/api/art/composer/{key}?size=row\|tile`, `/api/art/piece/{id}` | read | — | PNG, JPEG, WebP or GIF only; 404 |
| GET | `/api/channels` | read | — | `{playing, channels: [{key, name, size, playable, playing, volume, composers}]}` |
| GET | `/api/requests` | read | — | `{guests, approveFirst, pending: [{id, pieceId, title, composer, at}]}` |
| GET | `/api/piano` | read | — | the piano's state, `statusText`, `presets`, and the Feel · Lighting · Pedal table |
| POST | `/api/play` | write | `{pieceId, queue?}` | 204; 404 (queue: the library's pieces only) |
| POST | `/api/play-all` | write | `{ids \| playlistId, shuffle?}` | 204; 404 |
| POST | `/api/transport` | write | `{action}`: toggle · pause · resume · next · previous · stop | 204 |
| POST | `/api/seek` | write | `{ms}` ≥ 0 | 204 |
| POST | `/api/tempo` | write | `{pct}` in the app's tempo range | 204 |
| POST | `/api/shuffle` | write | `{on}` | 204 |
| POST | `/api/repeat` | write | `{mode}`: off · all · one | 204 |
| POST | `/api/queue` | write | `{action, ids?, uid?, toIndex?}`: playNext · add · remove · move · clear · skip | 204; 404 |
| POST | `/api/channels/{key}/play` | write | — | 204; 409 too small; 404 |
| POST | `/api/channels/stop` | write | — | 204 |
| PUT | `/api/channels/{key}/volume` | write | `{pct}` 0–100 | 204; 404 |
| POST | `/api/requests/{id}/approve`, `/api/requests/{id}/dismiss` | write | — | 204; 404 |
| PUT | `/api/piano/{name}` | write | `{value}` | 204; 404 (not in the table); 403 (read-only: `keyforce_*`); 400 (out of range, never clamped) |
| POST | `/api/piano/preset` | write | `{name}` (a preset's command) | 204; 400 |
| POST | `/api/piano/action` | write | `{name}`: off · save · status | 204; 400 |
| PUT | `/api/settings` | write | any of `preRollMs`, `defaultTempoPct`, `transpose`, `velocityPct`, `foldOutOfRange`, `skipDrumChannel`, `webGuests`, `webApproveFirst`, `webHostName` | 204; 400 |
| PUT | `/api/upload?name=` | write | the file | 202 `{name}`; 400 · 409 · 411 · 413 · 415 · 507 |
| POST | `/api/logout` | write | — | 204, the cookie expired |
| POST | `/api/login` | login | `{pin}` | 204 + `Set-Cookie`; 401 `{retryAfter}`; 429 + `Retry-After`; 403 no PIN yet |
| GET | `/api/public/catalogue` | public | — | `{open, lists: [{key, name, pieces: [{id, title, composer}]}]}` |
| POST | `/api/public/request` | public | `{pieceId}` | 202 `{status: queued \| pending}`; 400 not on the list; 403 closed; 429; 503 full |

Anything else is 404, and the route table's own methods answer others 405 with `Allow`. The
schedules' routes come with M19; until then the panel's Schedule page reads the 404 as "Coming in
the next update".

## JSON

- **In** (`WebApi.readObject`): a `Content-Length` (411 without, or chunked), at most 64 KB (413,
  answered before reading), `application/json` with no charset or UTF-8 (415), strict UTF-8 (400),
  one object nested at most four deep, counted outside strings before parsing (400), known fields
  only (400 names the first unknown one). Ids are whole numbers above 0 that fit a Long (1.5, "1",
  2⁶³, 0, -3, true and null are 400); lists of ids at most 5,000; strings cut as the library cuts
  them (`TextLimits`); ranges checked, never clamped silently (`pct` 300 is 400).
- **The state** (`GET /api/state`, and the socket's `{"type": "state", …}`): `{player: {status,
  loading, piece: {id, title, composer, composerShort, composerKey, durationMs, favorite, art:
  "portrait" | "roll"} | null, positionMs (below zero during the pause before a piece), tempoPct,
  transpose, velocityPct, preRollMs, channel: {key, name, volume} | null, queue: {ids, uids, index,
  shuffle, repeat, items: [piece + uid + requested] (the current piece and up to 100 after it)},
  problem}, link: {state, name}, piano: {state, values, facts, lastError, errorAbout}, import:
  {running, done, total, imported, duplicates, failed, current}, artwork: {running, done, total},
  requests: {pending, guests, approveFirst}, web: {address, guestAddress, guests}, monochrome}`.
- **Progress** (the socket, once a second while playing): `{"type": "progress", positionMs, at}`;
  the page runs its clock on from there at `tempoPct`.

## Sign-in (`web/WebAuth.kt`)

- `PinHash`: six ASCII digits only; PBKDF2WithHmacSHA256, 100,000 iterations, a 16-byte
  `SecureRandom` salt, a 32-byte key; compared with `ConstantTime.equals` (every byte, whatever the
  first difference); the PIN's chars cleared after use; `toString` blank. A malformed PIN costs no
  derivation but counts as a wrong one.
- `Sessions`: 32 random bytes as URL-safe base64 (43 characters); only their SHA-256 is kept; at
  most 10 (the least recently used goes); forgotten after 24 h unused; in memory only, so a
  restart of the app signs everyone out, as do a new PIN and turning Web control off.
- `LoginGuard`: two schedules (split in audit delta 1, W1, so the global one is not a
  denial-of-service lever). Per client address: 5 wrong PINs in a row → 30 s, doubling to 10 min.
  For everyone together (a ceiling on a brute force spread over rotating addresses): a far gentler
  gate that trips only after 20 wrong in a row and waits 5 s → 60 s, so no single reachable device
  can shut the panel for anyone but itself. Tries during a wait are refused (429) uncounted; a right
  PIN clears its address and the global count; at most 256 addresses remembered.
- Cookies: `sp_session=…; HttpOnly; SameSite=Strict; Path=/` (a browser-session cookie, no
  `Secure`: the panel is plain HTTP); `sp_guest=<16 random bytes>; HttpOnly; SameSite=Strict;
  Path=/; Max-Age=31536000`. At most 32 cookies read from a header, the first of a name winning.

## The socket (`web/WebSocketHub.kt`)

At most 2 open. The whole state as it opens, then on every change of the player, the link, the
piano's settings, the import, the artwork, the settings, the requests or the panel's addresses,
coalesced to one message every 100 ms at most; progress once a second while playing; a ping every
4 s, at which a socket whose session has ended is closed. What the page sends is read through
`FrameGuard` and dropped.

## Uploads

`PUT /api/upload?name=<file>` with the file as the body (no multipart). The name's last path
part, control characters removed, cut to a display name's length; `.mid`/`.midi` or `.zip` (415);
a `Content-Length` and no `Transfer-Encoding` (411); a MIDI file at most 8 MB, a zip at most 64 MB
(413); an empty file 400; one upload at a time (409 while another is read). All of that before a
byte is read (measured: answered in 4–5 ms with nothing sent). A MIDI file is read through
`ImportLimits.readCapped` (the importer's own cap); a zip is streamed to `cacheDir/web/upload-….zip`
with the cache's free-space margin kept (507 without it) and deleted if the body ends short, then
opened as `ZipSource(deleteWhenClosed = true)` (its entry cap and the importer's caps unchanged).
Then `Importer.importOpened` in the app's scope under the importer's lock, the built-in playlists
refreshed and composers' artwork requested as after the app's own imports, and 202; the progress
and the tally come over the socket. `cacheDir/web` is swept at start with the other stale import
files.

## Guests (`web/GuestRequests.kt`)

In memory. `POST /api/public/request {pieceId}`: 403 while Guests can request is off; the id must
be in the catalogue (Popular, Recognisable, Epic on piano as they stand, each piece once), else
400; each of the guest's keys (`sp_guest` and the client address) may ask once every 5 minutes
(429 with `Retry-After`); at most 50 wait for approval, or 50 guests' pieces sit in Up next (503
"full"). With Approve requests first the request waits for Approve or Dismiss (the panel's
Requests page, or the Library's banner on the tablet); without it the piece joins Up next at once
(starting it when nothing plays). Queue entries guests asked for are remembered while they stay in
Up next, so the panel tags them **Requested**. Nothing else comes in: no names, no text.

## Binding (`web/WebAddress.kt`) and the service (`service/WebService.kt`)

- **Where**: the tailnet address is the first IPv4 in 100.64.0.0/10 on a VPN interface that is up
  (`tun…`, as Android names Tailscale's, or `tailscale…`), and serves the whole panel. The Wi-Fi
  address is the first RFC 1918 IPv4 on an interface named `wlan…` that is up, and serves guests
  only unless Panel on Wi-Fi too is on. A 100.64/10 address on Wi-Fi or a mobile network (carriers
  use the block too) is never taken for the tailnet. Never the any-address, IPv6, loopback,
  link-local or a mobile network. Debug builds on an emulator (`goldfish`/`ranchu` or a `generic`
  fingerprint) add `127.0.0.1` answering to `localhost`, for `adb forward tcp:8737 tcp:8737`;
  release builds never listen there.
- **The service**: foreground, type `specialUse` (subtype "Local web control panel for the piano,
  on the person's own network") with `connectedDevice` as the fallback; not exported;
  `START_STICKY`; a low-importance silent notification "Web control on" + the address. It starts
  with the switch (and with the app when the switch is on), follows the settings, a network
  callback that sees VPNs, and a look every 30 s, and starts the listeners again whenever the
  addresses change (one that failed to bind, or whose socket closed itself, is started again at
  the next look). It stops, closing
  the listeners, the sockets and every session, when the switch turns off, the PIN goes, or Android
  times it out. While a piece plays it holds a partial wake lock (10 minutes a hold, renewed every
  30 s while the playing goes on).

## The page (`assets/web/`)

No framework and no build step. The CSP allows no inline script, style or handler, and none is
there (`WebAssetsTest` reads every file): the pages are built with DOM calls (`textContent`, never
`innerHTML`), dynamic values go through the CSSOM (`style.setProperty`), and the roll cards are CSS
masks over the app's alpha maps. The tokens are `Color.kt`'s, checked value for value. The page
talks to the server with `fetch` (`credentials: 'same-origin'`, the custom header on every change)
and one WebSocket; on a 401 it shows the gate again. Uploads use `XMLHttpRequest` for its upload
progress, one file at a time.

## Greps (v1.5.1 — M18)

- `grep -rn "Color(0x" app/src/main --include='*.kt' | grep -v ui/theme`: nothing.
- `grep -rnF -e "0.0.0.0" -e "Access-Control" app/src/main`: nothing. (Unescaped, the brief's
  `"0.0.0.0\|Access-Control"` also matches numbers such as `1_000_000` in files M18 did not touch;
  no file M18 touched matches either form.)
- `grep -rn "DisplayBlack" app/src/main`: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, as before.
- `LocalNoteSounding`, `dev.chrisbanes`, `hazeSource`/`HazeState`, `Modifier.blur`: as in M17.
- In `assets/web`: no `#000`, `#fff`, `black` or `white` but the stylesheet's comment saying so.

## Measured (September 2026, `steven_piano`, debug build, the emulated piano)

- Tests: 832, none failing (7 skipped without `-Pcorpus`, as in M17). `lint`: 0 errors, 29
  warnings, M17's 29 (ZXing moved to 3.5.4 so the catalog raises no new notice). `assembleDebug`,
  `assembleRelease` clean, no compiler warnings. The release APK is 2,675,444 bytes (1.5:
  2,548,714): NanoHTTPD, qrcode-kotlin and the pages (97 KB before compression).
- A Tailscale and a Wi-Fi address stood in by dummy interfaces (`tun9` 100.101.2.3, `wlan9`
  192.168.77.20; the emulator has neither): `ss -ltn` shows 8737 on those two and `127.0.0.1`,
  nothing else. On `wlan9` `/` and `/api/state` are 404, `/request`, `/poster` and the catalogue
  200; on `tun9` `/` is 200 and `/api/state` 401.
- Through `adb forward`: login 0.28–0.35 s (100,000 PBKDF2 rounds on the emulator), `/api/state`
  1.5 KB in 8 ms, a library page of 50 10 KB in 46 ms, `/api/piano` 8 KB in 13 ms, `/` 11 KB in
  4 ms. The 401/403 matrix over all nineteen changing routes, the JSON and upload caps, Host,
  CORS and headers: `api-checks.txt` (in the run's evidence).
- Headless Chrome 153 at 1280 × 800 and 390 × 844, light and dark: the gate; a wrong PIN, then
  "That PIN isn't right. Try again in 30 s." counting down; Now playing's clock moving between
  two screenshots a second apart; a reorder in Up next; a `.mid` and a 546 KB zip uploaded at a
  throttled 60 KB/s ("Sending 12%" … "90%", then "Imported 3 pieces") and the new pieces in the
  Library; the Piano page's three groups; the request page, "Thanks — it's in the queue.", and
  the 429 line at the foot of the screen after a Request tapped fifteen rows down (it used to sit
  under the list, out of sight); the Library's banner "1 request waiting" on the tablet, Approve,
  and the piece tagged Requested on the panel. The poster at 1280, 390 and A4 print; its QR, the
  Remote page's and Android's print preview all decode (ZXing) to the addresses shown.
- Android's print dialog: one A4 page (it was two before the print rule was fixed: a sheet exactly
  297 mm tall spilled a blank page); two quick taps give two dialogs, each closing cleanly.
- The Wi-Fi listener's address removed and put back (`ip addr del/add` on `wlan9`): its socket
  destroyed with it, the app at 0 % CPU (100 % before `SteadyServerSocket`), the listener back
  21 s after the address. Web control off: nothing listens on 8737 and the notification is gone;
  on again: the three listeners and the notification.

## Deviations from the plan, and why

- **Paths.** The Remote page is `ui/screens/piano/pages/RemotePage.kt` (the brief's name, M15's
  structure), not `RemoteSection.kt`; diagnostics changed in `DiagnosticsExporter.kt`.
- **The state carries more** than the plan's shape: Up next's rows with titles and the Requested
  mark, `composerShort`/`favorite`/`art` on a piece, `player.loading`, the channel as an object,
  `web.guestAddress`, `monochrome`. The page needs each; none is secret.
- **Two sockets at most** (the plan set no number): each holds one of the four threads while open.
- **Origin is checked** on changing routes, login and public POSTs as well as the custom header;
  the CSP adds `base-uri 'none'` and `form-action 'none'`, and every answer
  `Cross-Origin-Resource-Policy: same-origin`.
- **Every response closes its connection**, and refused uploads linger briefly (NanoHTTPD never
  skips an unread body); a custom client handler skips NanoHTTPD's reverse DNS; `FrameGuard` bounds
  NanoWSD's frames; `SteadyServerSocket` keeps a destroyed socket from spinning. None of these was
  in the plan; each answers a behaviour of NanoHTTPD 2.3.1.
- **Uploads**: 409 while another is read (the plan's "one at a time"), 507 without the room, 400
  for an empty file.
- **Defaults**: guests off and Approve requests first on (the plan set none; the safer pair).
- **The tailnet address comes from a VPN interface only.** The first version took any 100.64/10
  address, which a carrier's network or a Wi-Fi addressed from that block would have given the
  whole panel; fixed before the audit (`5cbe271`).
- **A debug-only loopback listener** on emulators, for the evidence through `adb forward`.
- **`webHostName`** (the plan's Host allow-list entry) has no control in 1.5.1: `PUT /api/settings`
  sets it. The panel's own Appearance chips are kept in the browser, as the brief said.
- **The printed poster** leaves off the paper tint and is centred a little short of the page:
  Android's WebView prints backgrounds, and a sheet of exactly 297 mm spilled a blank second page.
- **Test-only ZXing** to read the codes back.

## Tests added in M18

`WebAuthTest` (10: six digits only; PBKDF2 with 100,000 rounds over a 16-byte salt; a salt each
time; a kept hash restored only whole; constant-time comparison; sessions random, ten at most, a
day's life; logout ends one and closeAll every one; five wrong PINs → 30 s doubling to ten minutes;
many addresses lock everyone and a right PIN clears; a bounded memory), `WebServerTest` (20:
nineteen against a real server on 127.0.0.1 with an ephemeral port and `FakeWebBackend`, for the
401/403 matrix over every changing route with nothing reaching the backend; reads needing a
session, stale or forged ones refused; login, its guard and its cookie; the headers on every
answer and no CORS; Host; JSON caps; upload caps before a byte is read; one upload at a time; the
allow-list and paths that never resolve; the public routes; the guests' limit; approval; the
poster; the guest-only listener; the socket's checks; the piano's table and ranges; the settings
subset; the reads; the commands; and one for `SteadyServerSocket` alone, pausing a little longer
after each failure, then closing itself), `WebApiTest`
(7), `WebAddressTest` (5, among them the carrier block never taken for the tailnet),
`GuestRequestsTest` (5), `WebSocketHubTest` (7: the state as a socket opens and coalesced after;
progress; two sockets; a session's end at the next ping; a frame too large ends the socket before
its payload is read; stopping; the guard's rules), `PosterTest` (5: the code scans back with ZXing;
the smallest version with its finder squares; the SVG's modules and quiet zone; the page's address
escaped; the template), `WebAssetsTest` (4: the palettes as `Color.kt`'s; no pure black or white and
red for the dot alone; every file exists with its notice and no inline script, style or handler;
the pages say what the design says); `ImporterTest` (+3: an opened source imports under the lock
and caps and is closed; a listing that throws still finishes; a saved zip imports and is deleted),
`SettingsRepositoryTest` (+2: the web switches' defaults and memory; the PIN kept apart),
`GroupSummariesTest` (+1: Remote control's value); `ImportLimitsTest` (`cacheDir/web` swept),
`DiagnosticsExporterTest` (30 lines), `PianoPagesTest` and `RoutesTest` changed. 763 tests
before, 832 after (7 skipped, as before: the corpus tests, `-Pcorpus`).

## Audit (delta 1) — 2026-09-28

`docs/SECURITY_AUDIT.md › 1.5.1 — web panel: audit (delta 1)` records it in full; three
denial-of-service / hardening findings at the network edge, all fixed, none a break of the PIN or the
piano boundary:

- **W1** (`62672f8`): the global login lock shared the per-address schedule, so any reachable device
  could keep the login shut for everyone up to 10 min. `LoginGuard`'s global gate now trips only after
  20 wrong in a row and waits 5 s → 60 s; per-address is unchanged.
- **W2** (`8453092`): `login()` read the wait, derived the PIN, then counted, so tries sent at once all
  passed the wait before any was counted (eight of ten weighed for a threshold of five, several PBKDF2
  at once). `LoginGuard.attempt` now checks, weighs and counts as one step, one at a time.
- **W3** (`635d43b`): a byte-a-time request head held a thread until 8 KB (four held a listener); a
  post-answer trickle held one draining; and NanoHTTPD's own answers to a request it could not parse
  went out without the security headers, kept alive, echoing the method. The client handler now reads
  the head itself under a whole-request deadline (`DeadlineInput`) and refuses what NanoHTTPD would
  mishandle with the panel's headers (`RequestHead`); `linger` drains for 500 ms in all.

`WebAuthTest` 10 → 12, `WebServerTest` 20 → 23, `WebSocketHubTest` 7 → 8; 832 tests before the audit,
**840 after** (7 skipped, as before). Provenance re-signed each step. `WebAddress.choose`,
`WebSocketHub`/`FrameGuard`, `SteadyServerSocket` and the `Host`/`Origin`/no-CORS stack were
re-checked and hold.

---

# v1.6 — M21: firmware updates from the app; release 1.6 (versionCode 11)

Read `DESIGN.md › v1.6 — M21` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M21 with its amendments (binding); the contract is `firmware/docs/BLE_OTA.md` (frames, error codes,
the safety order, the app's obligations in § 11, feature detection § 12, the manifest in § 10, the
decisions in § 15). Built on its own branch (`m21-firmware`) beside M19 and M20. Not a release in this
run: `versionCode`, `versionName` and `Provenance.text` stay as they are and nothing is staged or
signed; the merge makes it 1.6 (`versionCode` 11, firmware 2.0.0's `minAppVersionCode`; see *The
merge* at the end of this section).

## Dependencies

- `net.i2p.crypto:eddsa:0.3.0` (EdDSA-Java, CC0 1.0; the notice in AUTHORS, the legal code in
  `third_party/eddsa/LICENSE.txt`), pinned in `gradle/libs.versions.toml`, resolved from Maven Central.
  Only `firmware/Ed25519.kt` names it. It references the JDK's `sun.security.x509.X509Key` in a branch
  the app never takes: `proguard-rules.pro` has `-dontwarn` for that class alone (R8 stopped the
  release build without it). No reflection in the library. The fallback the brief named,
  `org.bouncycastle:bcprov-jdk18on`, was not needed.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`):

- `M/firmware/Ed25519.kt` (the wrapper: the platform's provider asked first from API 33, EdDSA-Java
  otherwise; S below the group order on both), `FirmwareKeys.kt` (the author's key as 32 raw bytes and
  RFC 8032 TEST 1's; `fingerprint`), `FirmwareVersion.kt` ("2.0.0+a1b2c3d" as release and build,
  semver order), `FirmwareManifest.kt` (§ 10's fields, `verify`, `check`), `OtaExample.kt` (§ 5's image,
  digest and signature, § 10's manifest), `FirmwareState.kt` (the states, `FirmwarePiano`,
  `PowerState`, `FirmwarePlayer`, `FirmwareFailures`), `FirmwareUpdater.kt`, `FakeOta.kt` (the emulator's
  scenarios and `FakeFirmwareServer`), `Battery.kt`.
- `M/ble/Ota.kt` (`PianoOta`, `OtaBegin`, `OtaEvent`, `OtaFrames`, `OtaChannel`), `OtaPiano.kt` (the
  piano's side as a model), `EmulatedOta.kt` (the emulated piano's update service).
- `M/service/FirmwareService.kt`, `M/ui/FirmwareCopy.kt`.
- `third_party/eddsa/LICENSE.txt`.
- Tests: `update/UpdateSourceTest.kt`, `firmware/Ed25519Test.kt`, `FirmwareManifestTest.kt`,
  `FirmwareVersionTest.kt`, `PinnedKeyTest.kt`, `FirmwareUpdaterTest.kt`, `FakeOtaTest.kt`,
  `ble/GattOtaTest.kt`, `OtaFramingTest.kt`, `OtaPianoTest.kt`, `ui/FirmwareCopyTest.kt`.

Changed: `update/UpdateSource.kt` (the firmware's allow-list); `ble/PianoLink.kt` (`firmwareVersion`,
`ota`, `expectRestart`, with defaults), `BleRadio.kt` (`GattConnection` and `GattEvents` for Device
Information and the update service, with defaults), `AndroidBleRadio.kt`, `GattPianoLink.kt`,
`LoggingPianoLink.kt`, `EmulatedConsole.kt` (`setFact`); `player/Player.kt` (`lock`, `unlock`,
`locked`, `stopQuietly`); `piano/PianoSettingsRepository.kt` (`readFact`);
`ui/screens/piano/pages/FirmwarePage.kt`, `PianoSettingRows.kt` (`PianoPageContent`'s
`firmwareSection`), `GroupSummaries.kt`, `PianoViewModel.kt`, `PianoScreen.kt`; `AppGraph.kt`
(`firmwareUpdater`, the schedule), `App.kt` (the channel), `AndroidManifest.xml` (the service);
`app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`; AUTHORS, README,
DESIGN.md, this file. Tests changed: `ble/FakePianoLink.kt` (the firmware, `FakeOtaChannel`),
`FakeRadio.kt` (`FakeGatt`'s update service), `PlayerTest`, `PianoSettingsRepositoryTest`,
`GroupSummariesTest`.

## The allow-list (`update/UpdateSource.kt`)

The firmware repository is `stevenjin20090101-rgb/Steven-Jin-Player-Piano` (its name since the
rename; § 15): `allowsFirmwareManifest(url)` is
`https://raw.githubusercontent.com/stevenjin20090101-rgb/Steven-Jin-Player-Piano/main/releases/latest.json`
exactly (HTTPS on 443, the host's case aside, no query or fragment, no user info, no backslash);
`allowsFirmwareBinary(url)`, every hop of a download, a release asset of that repository on
`github.com` (`/…/releases/download/<tag>/<file>`, plain names) or one of GitHub's two asset hosts;
`allowsFirmwareFile(url)`, what a manifest may name, a `.bin` release asset on `github.com` only.
`UpdateSource.firmware` is the HTTP client's source (it names no APK); the app's own source never
reaches the firmware's addresses. `MAX_FIRMWARE_BYTES` = 4 MB, apart from the APK's 50 MB.

## The manifest (`firmware/FirmwareManifest.kt`)

§ 10's JSON, read with `org.json`: `version` `MAJOR.MINOR.PATCH` (a `-pre` allowed), at most 16
bytes; `build` 7–40 lower-case hex digits; `binUrl` `allowsFirmwareFile`; `sizeBytes` 1..4 MB;
`sha256` 64 hex digits (kept lower-case); `sig` 88 base64 characters of 64 bytes;
`minAppVersionCode` 1..2,100,000,000; `notes` optional, plain text, at most 1,000; `usbOnly` a JSON
boolean, required. Unknown fields are ignored; anything else throws `InvalidFirmwareManifest(field)`.
`verify(publicKey)` is Ed25519 over the 32 raw bytes of `sha256`. The key (`FirmwareKeys.author`) is
the 32 bytes § 8 prints; `PinnedKeyTest` checks its fingerprint (`eab16a502f679465`, `Provenance`'s),
that it is `provenance/author_ed25519_public.pem`'s, and, once the firmware carries
`include/ota_pubkey.h`, the piano's.

**Ed25519 on Android.** The plan expected the platform's Ed25519 from API 33. Measured on the emulator
(API 34) with a probe dex: Conscrypt offers X25519 (XDH) only, no Ed25519 `KeyFactory` or `Signature`.
`Ed25519.check` asks the platform first from API 33 and falls through to EdDSA-Java only when the
platform can't check Ed25519 at all (never a second opinion on a refused signature); every Android the
app supports today therefore uses EdDSA-Java, and the log says which answered ("signature checked with
EdDSA-Java"). EdDSA-Java does not refuse a non-canonical S: the wrapper does, as RFC 8032 › 5.1.7 asks.

## The link (`ble/`)

- **Discovery.** On firmware 2.0.0 and later, Device Information's Firmware Revision String
  (`0x180A`/`0x2A26`) is read after discovery and before Connected (3 s at most; NULs and control
  characters dropped, 64 characters kept): `PianoLink.firmwareVersion`. The update service
  `7D0A0001-…` with Control (`…0002`, and its CCCD) and Data (`…0003`) is `PianoLink.ota`. Older
  firmware has neither, and the link connects as before; both go with the connection.
- **A session** (`OtaChannel.begin(header): Flow<OtaEvent>`): switches Control's notifications on
  (once a connection), re-asks high connection priority, writes BEGIN with response, and gives the
  piano's notifications parsed (`OtaFrames.parse`: READY, ACK, VERIFYING, OK, ABORTED, ERR; a known
  opcode of the wrong length is `Malformed`, an unknown one ignored) until OK, ABORTED, an ERR, or
  `Lost` (the connection ended, a Control write failed, or the stack refused the frames for about
  2 s). `write(seq, payload)` queues a Data frame (1..`maxChunk` bytes; 64 at most waiting),
  `end()` END, `abort()` ABORT ahead of the frames still waiting (which are dropped; nothing after
  END). Leaving a session before END sends ABORT. One session at a time.
- **The queue.** Every frame goes through the link's one-operation queue: MIDI first, then the
  update's frames in order, then console lines. A Data frame is never dropped for a busy stack (a gap
  would cost the whole transfer): it is retried as a MIDI release is, and a stack that keeps refusing
  ends the session. Frames of a session that has ended never go out.
- **MTU.** `maxChunk` = min(MTU − 5, 250) from the MTU the connection negotiated (never assumed); a
  frame never carries more than READY's figure or the link's. The link asks for MTU 255, as it always
  has (NimBLE's own preference; Android 14 asks 517 whatever the app says).
- **Priority.** The link holds `CONNECTION_PRIORITY_HIGH` for MIDI from the moment it connects
  (BUILD_SPEC › Bluetooth); a session asks for it again at BEGIN, and nothing asks for BALANCED after
  (see *Deviations*).
- **The restart.** `expectRestart(withinMs)` (60 s after OK): a drop in that window is reconnected
  whatever Auto-connect says, in the background at once and with the first scan after 3 s instead of
  20 s; the piano back, the link's rules are as before. With Auto-connect off, a piano that doesn't
  come back within the window is let go.

## The updater (`firmware/FirmwareUpdater.kt`)

- **States** (`FirmwareState`): `Idle`, `Checking`, `UpToDate(version)`, `Available(manifest)`,
  `UsbOnly(manifest)`, `NeedsNewerApp(manifest)`, `Downloading(bytes, total)`, `Verifying`,
  `Sending(bytes, total, ratePerSec)`, `PianoVerifying`, `Restarting`, `Done(version, confirming)`,
  `Failed(message, retryable, manifest?, check, hint, rolledBack)`. `busy` from Downloading to
  Restarting; `cancellable` until END; `offered` while a newer release is known and not installed.
- **Checks**: the manifest from `UpdateSource.firmware` (64 KB at most), refused unless its
  signature checks out against the embedded key ("The piano update isn't signed by Steven Jin, so it
  isn't offered."), then its version against the piano's: newer is Available (UsbOnly when `usbOnly`,
  NeedsNewerApp when `minAppVersionCode` is above `BuildConfig.VERSION_CODE`), the same or older
  UpToDate. A check failing keeps a release already on offer. Checks run once a day while a piano that
  can be updated is connected, the device is online and *Check for updates automatically* is on (with
  the app's own, from `MainActivity` while it is started); when the Firmware page opens (at most every
  ten minutes); and on its button. A due check that can't ask waits a minute rather than asking again
  at once. A piano connecting with another version has the release weighed again.
- **An update** (`update()`, the page's Update or Retry): preconditions first (connected, the update
  service there, the tablet at 20 % or charging), then the download (4 MB and the manifest's size at
  most; "Downloading · 0.9 MB"), then its size, SHA-256 and the signature over the digest of those very
  bytes, off the main thread; the preconditions again; then the player is locked ("Updating the
  piano"), stopped, and the updater waits until the stop sequence (CC64 = 0, CC123) has been written
  and 600 ms more have passed (the piano's 500 ms, § 7 and 11, with 100 ms for the air), and sends:
  BEGIN (window 16), READY (its window is used; frames of its chunk at most), then the image a window
  at a time, each window's ACK awaited (15 s) and checked against the bytes sent, then END, VERIFYING
  and OK (30 s each). The verified image is kept for Retry.
- **Cancel** (the page, the notification): before END, ABORT once and the piano's ABORTED (3 s at
  most); the release is back on offer. After END nothing stops it.
- **After OK**: `expectRestart(60 s)`, then the piano must be back within the minute (a new
  connection: a drop seen, or a new epoch). Its version decides: the release's is `Done`, with
  `confirming` while the new dump's `!ota` says `pending`, read again (`get !ota`) every 30 s for five
  minutes until it says otherwise; any other version is a rollback, also when the piano resets during
  the confirmation and comes back old. Not back within the minute: "The piano hasn't come back…", and
  its next connection (ten minutes) still says how it went. A session lost after END is judged the
  same way, by what the piano runs once back ("didn't finish" if the old one).
- **Failures**: any ERR, a lost link, a silence or an ACK that doesn't add up is "The update didn't
  finish. The piano kept its old firmware."; ERR 2, 6 and 8 are not retryable (6 and 8 with "The piano
  refused this release."), ERR 10 says to switch the piano off and on. Nothing is retried by itself.
  Every outcome is a line of the link's log (`LinkLog.warn`): the check and which Ed25519 answered,
  the sending, each ERR by name, cancels, the restart, the version after it, `!ota`, rollbacks.
- **The player** (`FirmwarePlayer`, `Player`'s `lock`, `unlock`, `stopQuietly`): while locked,
  `play`, `playAll` (channels), `resume`, `seek`, `next`, `previous`, `skipToQueueEntry`, starting from
  an empty queue, Repeat one and the Keys screen's note-ons and sustain are turned away; releases
  still go. The reason is `PlayerState.problem`, which Now playing, the panel and the web panel show.
  Schedules (M19, another branch) play through the same player and are turned away the same way.

## The service (`service/FirmwareService.kt`)

Foreground type `connectedDevice` (declared since M18; the manifest adds the service, not exported),
started by the page's Update or Retry while the app is in the foreground. It follows the updater's
state: a low-importance, silent notification (channel "firmware", "Piano firmware") "Updating the
piano" with the page's line, a progress bar (indeterminate while checking and restarting) and Cancel
while `cancellable`; at most a few updates a second. A partial wake lock of at most 10 minutes. When
the state leaves `busy` it stops, leaving one auto-cancelling line of how it ended. `onTimeout` (both
forms) cancels the update (ABORT before END). A refused start leaves the transfer running in the
app's process, without the notification.

## The page and the hub

`FirmwarePage` draws FIRMWARE itself (`PianoPageContent`'s `firmwareSection` replaces the table's
FIRMWARE section, under the status line, even without the console): the version row, the check's
action row with `FirmwareCopy.checkLine` under it, and the update block by state, as DESIGN says.
`GroupSummaries.firmware(piano, update, reported)`: "Update available" while `offered`, "Updating…"
while `busy`, else the reported release ("2.0.0", Device Information's first, the dump's `!fw`
otherwise), else the one-argument form's value. `PianoViewModel` implements `FirmwareActions`.

## The emulator (`firmware/FakeOta.kt`, `ble/EmulatedOta.kt`)

Debug builds on an emulator only (`LoggingPianoLink.isWanted()`): `adb shell setprop
debug.stevenpiano.fakeota <scenario>`, then start the app. Scenarios: `happy`, `err1` (ERR 1 at
BEGIN), `disconnect` (the link drops after 40 windows, back 3 s later on the old firmware),
`rollback` (back on 2.0.0), `hash` (ERR 5 after END), `signature` (the piano holds the author's key:
ERR 6), `usbonly`, `newerapp`, `uptodate`, `nocomeback`, `old` (no version, no update service). The
emulated piano reports `2.0.0+a1b2c3d` and has the update service; `EmulatedOta` answers through
`OtaPiano` with a real piano's pauses (READY 1.2 s, an ACK 300 ms after each window: about 75 s for
the worked example; OK 2.8 s after END); after OK it drops, is back 4 s later pending and confirms
25 s after that. `FakeFirmwareServer` serves the scenario's release (§ 10's example, for any app but
`newerapp`) and § 5's image at 160 KB/s; the updater then trusts RFC 8032's test key. Release builds
have none of it, and trust the author's key alone.

## Greps (v1.6 — M21)

- `grep -rn "esp32-player-piano" app/src`: nothing (the renamed repository only).
- `grep -rn "Color(0x" app/src/main --include='*.kt' | grep -v ui/theme`: nothing.
- `grep -rnF -e "0.0.0.0" -e "Access-Control" app/src/main`: nothing.
- `LocalLive`, `LocalNoteSounding`, `hazeSource`/`HazeState`, `Modifier.blur`, `DisplayBlack`: as in M18.

## Measured (September 2026, `steven_piano_m21`, API 34, debug build, the emulated piano)

- Tests: 920, none failing (8 skipped: the corpus tests without `-Pcorpus`, and `PinnedKeyTest`'s
  check against the firmware's `include/ota_pubkey.h`, which firmware 2.0.0 adds). `lint`: 0 errors,
  29 warnings, M18's. `assembleDebug`, `assembleRelease` clean. The release APK is 2,724,616 bytes
  (1.5.1: 2,675,444): EdDSA-Java and this run's code.
- `happy` end to end: the hub's row "Update available"; Update; "Downloading · 0.4 MB"; "Sending ·
  38% · less than a minute left" with Cancel, and the same in the notification; Now playing's banner
  "Updating the piano"; the hub's row "Updating…"; "The piano is checking the update…"; "Restarting
  the piano…"; "Updated to 2.1.0 · confirming…" 5.6 s after OK; "Updated to 2.1.0" 30 s later; the
  hub's row "2.1.0". The link's log: "sending 2.1.0, 991232 bytes", 79 s later "the piano verified
  2.1.0 and restarts", "the piano runs 2.1.0+a1b2c3d, !ota pending", "2.1.0 confirmed (!ota
  confirmed)".
- Cancel on the page 7 s into sending, and from the notification's Cancel at 79 %: "cancelled while it
  was sent" in the link's log each time, the release back on offer, the hub back to "Update available".
- `err1`: the failure 0.2 s after sending began, with Retry. `disconnect`: after 164,000 bytes. `hash`:
  after END. `signature`: "The piano refused this release.", no Retry. `rollback`: "The piano restarted
  but reports 2.0.0 — it rolled back." `nocomeback`: "The piano hasn't come back after restarting…" a
  minute after OK. `usbonly`, `newerapp`, `uptodate` and no property at all (firmware without a
  version: "Unknown — this firmware has no version. Flash 2.0.0 over USB once.", Check greyed), each as
  DESIGN says. Light and dark. "Checking the download…" lasts a few milliseconds on the emulator and
  was never caught on screen; `FirmwareUpdaterTest` sees it published.
- The platform's Ed25519 on API 34: none (above).

## Deviations from the plan, and why

- **Ed25519 below and above API 33 alike.** Android 14 has no Ed25519 in Conscrypt (measured), so the
  plan's "the platform's from API 33" holds nowhere the app runs today: the platform is asked first
  and EdDSA-Java answers. Kept the order the brief asked for, so a later Android that has Ed25519 uses
  its own.
- **No BALANCED after a transfer.** The brief asked for HIGH during a transfer and balanced after.
  The link has held HIGH since it connects (the piano plays MIDI on arrival, so the short interval is
  its timing); switching to BALANCED after a failed or cancelled update would slow every piece played
  afterwards. The session asks for HIGH again at BEGIN and leaves it there.
- **MTU 255 asked, not 517.** § 11 says the app requests 517; it has always asked 255, NimBLE's own
  preference, which yields the same 255 (and Android 14 asks 517 whatever the app says). The chunk
  comes from the negotiated value either way.
- **600 ms of quiet**, not 500: the 500 ms rule counts from the piano's last energize, and the stop
  sequence still has to cross the air after the write is confirmed.
- **States the plan didn't list**: `UsbOnly` and `NeedsNewerApp` (the brief's two lines), `Done`'s
  `confirming` (the amendment's "confirming…"), `Failed`'s `check`, `hint` and `rolledBack`. The hub
  also reads "Updating…" during a transfer ("Update available" there would be wrong).
- **The version on the hub** is the release alone ("2.0.0"); the page shows the build too.
- **The daily check** shares *Check for updates automatically* and the app's schedule (no new
  setting); it runs while the app is in the foreground, as the app's own does.
- **A lost link after END** waits for the piano and reads its version, rather than failing: from END
  the piano finishes and restarts without the app (§ 6).
- **The service follows, the app's scope transfers**: the transfer lives in `FirmwareUpdater` (in
  `AppGraph.appScope`), so a refused service start leaves it running; the service holds the
  foreground, the wake lock and the notification.
- **The fake scenarios' release takes any app**: § 10's example names `minAppVersionCode` 11, 1.6's
  build, which the branch's build (10) was below; `newerapp` keeps a higher one.
- **Emulator AVD** `steven_piano_m21` (this run's, removed at the end), not `steven_piano`, as the
  run's instructions said.

## Tests added in M21

`UpdateSourceTest` (6: the manifest at one address; binaries and asset hosts; what a manifest may
name; the firmware source's hops; the app's source never reaching them; the cap), `Ed25519Test` (5:
RFC 8032's vectors through both engines; a bit, a message or a key changed; S + L refused;
lengths; which engine answers), `FirmwareManifestTest` (9: § 10's example; the test key and never
the author's; a generated key pair with a tampered sha256 and signature refused; `usbOnly` and
`minAppVersionCode`; unknown fields; each missing field; wrong shapes; binaries elsewhere; notes),
`FirmwareVersionTest` (3), `PinnedKeyTest` (4: the fingerprint; the PEM; the firmware's header, skipped
until 2.0.0; copies), `OtaFramingTest` (7: § 5's BEGIN byte for byte, and against BLE_OTA.md's own dump;
END, ABORT, Data 0, 1 and 3964; 3,965 frames; every answer; malformed and unknown; BEGIN's checks),
`OtaPianoTest` (3), `GattOtaTest` (14: the version before Connected; older firmware; the version's
timeout and the MTU's chunk; a session's frames in order; MIDI first and console last; abort; leaving;
a drop; one session at a time; sizes; a busy stack waited out, then the session ended; the restart
reconnected whatever Auto-connect says, and let go when it doesn't come back), `FirmwareUpdaterTest`
(19: offered; unsigned; up to date; the happy path with windows, ACKs, the ≥ 500 ms rule, the lock,
the restart, pending then confirmed; ERR 1 with Retry and no retry loop; a disconnect mid-transfer;
Cancel; a rollback; a reset while confirming; USB only and newer app never sent; a tampered download;
preconditions; ERR 6; ERR 5 after END; a link lost after END; not back within the minute; the daily
schedule; the page's check; offline), `FakeOtaTest` (3), `FirmwareCopyTest` (4), and cases in
`PlayerTest` (+1: locked, nothing reaches the piano), `PianoSettingsRepositoryTest` (+1: `readFact`),
`GroupSummariesTest` (+1: "Update available", "Updating…", the release). 840 tests before, 920 after.

## The merge: release 1.6 (versionCode 11)

Merged into `main` after 1.5.1 (`56da814`), ahead of M19 and M20 (`18a21ba`), and released as
**1.6**: `versionCode` 11, `versionName` "1.6", `Provenance.text` "Made by Steven Jin · v1.6 ·
eab16a502f679465", the entry drafted at the end of `releases/history.json` (`"draft": true`, its
notes; no hash or size until `tools/publish-release.sh` builds it); `latest.json` still names 1.5.1.

- **One conflict**, in this file: M18's *Audit (delta 1)* and this section met at its end; both kept,
  the audit first.
- **`minAppVersionCode` 11** (`966388d`): BLE_OTA.md › 15 was amended when the runs landed in a
  different order (firmware 2.0.0 names 11, the build of 1.6, and § 10's example says 11 too), so
  `OtaExample`, `FirmwareManifest`'s KDoc and `FakeOta`'s note follow it, `FirmwareUpdaterTest`'s rig
  is this build, and `FirmwareManifestTest` checks the boundary (11 takes the example, 10 is asked
  for a newer app). Nothing a person sees changes.
- **No compiler warnings** (`1968805`): `EmulatedOta` read `SendChannel.isClosedForSend`, a delicate
  API in kotlinx.coroutines 1.10, five times (five warnings where 1.5.1's main sources had none); it
  opts in, with a line on why that is harmless there, and the tests' `FakeOtaChannel` does the same.
- **1.6, not 1.6.1**: this section's title and DESIGN's, README's section, and every comment that
  pointed at them.
- **AUTHORS and README**: EdDSA-Java's notice joins the other third-party notices; README's
  *Updating the piano's firmware* stands after *Web control*, and the introduction, *What it does*,
  *Security* (what leaves the device now includes the firmware's manifest and release downloads; the
  firmware's signature) and *Acknowledgements* say what M21 added.
- Tests: 920 before the merge's fixes and after (8 skipped: the corpus tests without `-Pcorpus`, and
  `PinnedKeyTest`'s check against the firmware's `include/ota_pubkey.h`, which isn't there yet).
  `lint`: 0 errors, 29 warnings. The greps above: as stated. The release APK is 2,724,616 bytes
  (versionCode 11, "1.6", signed `CN=Steven Piano, O=Steven Jin, C=US`), the debug APK 16,490,995;
  staged as `../apk/steven-piano-1.6.apk` and `-debug.apk`.

---

# v1.6.1 — M20: kiosk mode; release 1.6.1 (versionCode 12)

Read `DESIGN.md › v1.6.1 — M20` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M20 (binding) and feature item 11. Built in its own worktree (`m20-kiosk`, from `635d43b`: 1.5.1
with the audit's W1–W3) beside other runs, so this run leaves the version alone: `versionCode` 10,
`versionName` "1.5.1" and `Provenance.text` stay, and the version bump to 1.6.1 (build 12), the
provenance signature and the staged APKs come when the branch is merged.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/admin/Kiosk.kt`: `KioskDevice` (the seam over `DevicePolicyManager` as `PianoDeviceAdmin`,
  `Settings.Global`, `PackageManager` and `ActivityManager`), `KioskResult` (`On(stayOnBefore,
  keyguardOff)` · `NotOwner` · `Refused(reason)`), `KioskController(device, packageName, log)` with
  `KioskController.of(context)` over `AndroidKioskDevice`.
- `M/admin/KioskMode.kt`: `KioskStatus`, `OwnerRelease` (the adb way back as a seam), `KioskMode`
  (`AppGraph.kiosk`).
- `M/admin/KioskPin.kt`: `KioskPinGuard` (the kiosk's schedule on the web panel's `LoginGuard`, kept
  across restarts).
- `M/ui/KioskExit.kt`: `LocalBylineHold`, `KioskCopy`, `KioskExit` (Unlock · TurnOff · ChangePin,
  `offered(unlocked)`), `KIOSK_HOLD_MS`, `HeldByline`, `KioskPinSheet`, `KioskExitSheet`,
  `KioskMode.leave`.
- `M/ui/screens/piano/pages/KioskPage.kt` (`KioskPageCopy`, `KioskPage`).
- `M/ui/KioskLock.kt` (settings locked in kiosk: `KioskLockCopy`, `KioskGate`, `rememberKioskGate`,
  `KioskGateSheet`, `LockGlyph`, `LockedPage`) and `res/drawable/ic_lock.xml` (an outlined padlock).
- Tests: `T/admin/FakeKioskDevice.kt`, `KioskControllerTest.kt`, `KioskModeTest.kt`,
  `PinGuardTest.kt`; `T/ui/KioskExitTest.kt`.

Changed: `AndroidManifest.xml` (the `KioskHome` alias; the device admin's comment);
`AppGraph.kt` (`kiosk`, `releaseOwnerIfAsked`, the kiosk's start first); `MainActivity.kt`
(`followKiosk`, `onStart`/`onStop`/`onNewIntent`); `admin/DeviceOwnerRelease.kt` (`asked`,
`release`); `admin/PianoDeviceAdmin.kt` (its KDoc); `settings/Settings.kt` (below);
`diag/DiagnosticsExporter.kt` (two lines); `ui/components/PinSheet.kt` (`PinCheckSheet`, `PinWait`,
the shared `PinSheetFrame` and `PinField`; `PinSheet` itself unchanged); `ui/components/ScreenHeader.kt`
(the byline through `LocalBylineHold`); `ui/NavHost.kt`; `ui/IdleWatch.kt` (`DisplayRule`,
`LocalIdleState`); `ui/Routes.kt` (`SettingsPage.Kiosk`); `ui/screens/piano/HubGroups.kt`,
`GroupSummaries.kt`, `PianoScreen.kt` (and the settings lock); `ui/screens/display/DisplayScreen.kt`
(`DisplayRest`); `ui/components/SettingsRows.kt` (`NavRow`/`SwitchRow` `locked`);
`ui/screens/library/LibraryScreen.kt` (the settings lock);
tests `SettingsRepositoryTest`, `DiagnosticsExporterTest`, `IdleWatchTest`, `RoutesTest`,
`PianoPagesTest`, `GroupSummariesTest`; DESIGN.md, this file, README.

## Settings

DataStore keys: `kioskEnabled` (false) and `kioskPinSalt`/`kioskPinHash` (a `PinHash`, as the web
panel's: PBKDF2-HMAC-SHA256, 100,000 rounds, a 16-byte salt), read on their own
(`SettingsRepository.kioskPin()` → `StoredPin`); `PianoSettings` carries `kioskEnabled` and
`kioskPinSet` only. Housekeeping, read on their own too: `kioskPinStrikes` and
`kioskPinLockedUntil` (the wrong tries in a row and when their wait ends, epoch ms; a new PIN
removes both), `kioskStayOnBefore` (the "stay on while plugged in" mask kiosk mode replaced).
`settings.txt` in Share diagnostics gains `kioskEnabled` and `kioskPinSet` (32 lines); no hash,
salt or count ever travels.

## The controller (`admin/Kiosk.kt`)

- `enable()`, as device owner only (else `NotOwner`, nothing touched): `setLockTaskPackages([package])`
  first (a `startLockTask` before it shows Android's screen-pinning prompt instead),
  `setLockTaskFeatures(LOCK_TASK_FEATURE_NONE)` (API 28+; before it, lock task already hides the
  status bar and the power menu), `setKeyguardDisabled(true)` (false when the tablet has a secure lock
  screen: reported, not fatal), `setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN, "7")` (AC | USB |
  WIRELESS) after reading the old value, the `KioskHome` alias enabled
  (`COMPONENT_ENABLED_STATE_ENABLED`, `DONT_KILL_APP`), `clearPackagePersistentPreferredActivities`
  then `addPersistentPreferredActivity(MAIN + HOME + DEFAULT → .KioskHome)`. A refused step undoes
  the rest (`Refused`). Safe to repeat.
- `disable(stayOnBefore)` reverses it, each step on its own (one refusal never stops the others,
  logged): the preferred home, the alias (back to the manifest's default, off), stay-on as it was,
  the lock screen, the lock task features back to Android's default (`GLOBAL_ACTIONS`), and **the
  lock task list last**: Android clears a locked task whose package leaves the list (the app would
  close), so the caller lets go of the screen first.
- `tidyWithoutOwner()`: without the device owner a home alias left on goes off (an enabled home
  alias the app does not own would leave it a launcher nobody could take away); nothing else is
  touched.

## Kiosk mode in the app (`admin/KioskMode.kt`)

- `status: StateFlow<KioskStatus(checked, owner, unlockedForNow, keyguardKept, problem)>` and
  `lockWanted = kioskEnabled && checked && owner && !unlockedForNow`, which `MainActivity` follows in
  `repeatOnLifecycle(RESUMED)`: `startLockTask()` when wanted, the lock task mode NONE and
  `isLockTaskPermitted`; `stopLockTask()` when not wanted and the mode is LOCKED (never a screen the
  person pinned themselves, PINNED).
- `start()` (from `AppGraph.start`, first, on IO): the kept strikes back into the guard; the adb way
  back (`releaseIfAsked`); without the device owner `tidyWithoutOwner()` and `kioskEnabled` off; with
  it, kiosk mode on and the lock task list lost, `enable()` again. `checked` is set in a `finally`:
  nothing locks before it.
- `turnOn()`: a PIN is needed; `enable()` on the policy dispatcher (IO); the old stay-on kept once;
  `kioskEnabled` on. `turnOff()`: `unlockedForNow` (the activity lets go), `kioskEnabled` off, then it
  waits for `isLocked()` to clear (`LET_GO_MS` 2 s at most, polled every 50 ms), then `disable()` with
  the kept stay-on.
- Unlock for now: `unlockForNow()`; it ends at `relock()` (Lock again, display mode coming) or when
  the activity starts again after stopping (`appLeft` from `onStop` outside configuration changes,
  `appOpened` from `onStart`), or when the process restarts (the flag is in memory).
- `check(pin)`: the stored `PinHash` weighed by `KioskPinGuard.attempt` on `Dispatchers.Default`;
  after every weighed try the strikes are kept. `setPin(pin)`: `PinHash.create` off the main thread,
  the strikes cleared.
- `releaseIfAsked()` (one at a time, a `Mutex`): when `OwnerRelease.asked()` (the device owner and
  `debug.stevenpiano.releaseowner` yes), the screen is let go and waited for as in `turnOff`, then
  `disable`, `kioskEnabled` off, then `OwnerRelease.release()` (`clearDeviceOwnerApp`). Asked at start
  and whenever `MainActivity` starts or is sent an intent (`AppGraph.releaseOwnerIfAsked`, IO).

## The guard (`admin/KioskPin.kt`)

`KioskPinGuard` = `LoginGuard(threshold = 4, firstLockMs = 5 s, maxLockMs = 5 min, globalThreshold =
Int.MAX_VALUE, maxKeys = 1)` on one key, "screen": the waits after each wrong try in a row are 0, 0,
0, 5 s, 10 s, 20 s, 40 s, 80 s, 160 s, 300 s, 300 s…; a try during a wait is refused uncounted
(`Attempt.Wait`); a right PIN or a new PIN clears it. `Strikes(count, lockedUntil)` is kept after
each weighed try; a restart replays `count` failures into a fresh guard at the moment that makes the
last wait end at the kept `lockedUntil` (the waits come from a scratch copy of the guard, so the
schedule is stated once); a count past 64 is held to it, a nonsense one reads as none. `WebAuth.kt`
is untouched.

## The way out (`ui/KioskExit.kt`, `ui/components/PinSheet.kt`)

- `NavHost` provides `LocalBylineHold` (non-null only while `kioskEnabled`) and composes
  `KioskExitSheet` over every tab; `ScreenHeader` draws its byline through `HeldByline` when a hold
  is provided: `awaitEachGesture { awaitFirstDown(requireUnconsumed = false); … waitForUpOrCancellation() }`,
  a coroutine that grows `fill` from 0 to 1 over `KIOSK_HOLD_MS` = 3 s with `withFrameMillis` (or
  sets it to 1 at once under reduced motion) and calls the hold at the end; the fill is a 1 dp rect
  in `LocalTertiary` along the text's foot (`drawBehind`, no layout change, mirrored in RTL). A
  `CustomAccessibilityAction("Kiosk PIN")` does the same for TalkBack.
- `PinCheckSheet(eyebrow, title, hint, actions, waitMs, check, onRight, onDismiss)`: the web PIN
  sheet's frame and field (`PinSheetFrame`, `PinField`, shared, not copied); one `ActionButton` per
  action in a `FlowRow` (end-aligned, wraps at large text); the countdown re-reads `waitMs()` every
  250 ms; the field is enabled only while no wait runs and no try is being weighed, and takes the
  focus in an effect keyed on that (a disabled field cannot be focused). `PinWait.text(ms)`: "5 s"
  under a minute, then "2 min" rounded up.
- `PinSheetFrame` watches its own touches for display mode (`LocalIdleState`, which `NavHost`
  provides) and dismisses itself when the app goes idle.
- `KioskPage`: `LifecycleResumeEffect` → `kiosk.refresh()` (the owner may have been set over adb);
  the switch off, Unlock for now and Change PIN (while on) open `KioskPinSheet` with the one action;
  Lock again calls `relock()`.

## Display mode (`ui/IdleWatch.kt`, `ui/NavHost.kt`, `ui/screens/display/DisplayScreen.kt`)

`DisplayRule.watched(afterMinute, kiosk) = afterMinute || kiosk` (the idle clock) and
`shows(pieceLoaded, kiosk) = pieceLoaded || kiosk`. `DisplayOverlay` calls `kiosk.relock()` as it
shows in kiosk mode. `DisplayScreen` with no piece composes `DisplayRest`: the canvas, the byline
(`Alignment.End`, at the foot), and while `webEnabled && webGuests` and the panel has a guests'
address, `RequestCode(url, wide)`: "Ask the piano" (`displayLarge` on `twoPane`, else
`displayMedium`), the line, `QrTile(url, side)` with `side = min(70 % of the width, 45 % of the
height, 280 dp or 360 dp on wide frames)`, the address. `rememberDrift()` steps the whole column
round an eight-point square of 4 dp once a minute (`offset`, never animated). `keepScreenOn` only
while a piece is loaded; the bars' hiding moved to its own effect.

## Settings locked in kiosk (`ui/KioskLock.kt`)

- `KioskMode.settingsLocked = kioskEnabled && !unlockedForNow && !settingsOpen` (`StateFlow`,
  distinct). `unlockSettings()` opens it and starts a timer of `SETTINGS_UNLOCK_MS` = 5 min in the
  app's scope (a second right PIN starts it again); `relock()` (display mode coming, Lock again, the
  app opened again after Unlock for now) closes it early; `turnOn`/`turnOff` start with it closed.
- `KioskGate` (`rememberKioskGate()`, over `settingsLocked` collected with the lifecycle):
  `run(action)` runs at once, or, while locked, keeps the action and `KioskGateSheet(gate)` shows
  `PinCheckSheet(KIOSK, "Settings are locked in kiosk", "The kiosk PIN opens them for five minutes.",
  [Unlock])`; the right PIN calls `unlockSettings()`, then the action. The guard and the waits are
  the kiosk's (`KioskMode.check`).
- `PianoScreen`/`PianoPageScreen`: page rows through the gate (`vm.open` + navigate on phones,
  `vm.pick` beside the hub), the APP group's two switches and Check now through it, Disconnect through
  it (Connect, Cancel and connecting to another piano stay free); `NavRow(locked)` draws `LockGlyph`
  (18 dp, `LocalTertiary`, described "Locked") in the chevron's 24 dp, `SwitchRow(locked)` before the
  switch; Check now's button reads "Check now, locked" with a padlock beside it. `SettingsPageView`
  shows `LockedPage(gate)` in place of any page while locked.
- `LibraryScreen`: the header's + ("Add MIDI files, locked", a padlock before it) and the empty
  library's button, every `LibraryDialog` (`openDialog`: Add to playlist, Rename, Delete, Rename
  playlist, Delete playlist), `removeFromPlaylist` and `move`, Change photo, and a channel's Set
  volume go through the gate; `reorderable` is false while locked (no drag handles, no Move items).
  `setFavorite`, playing, Play next, Add to queue, About this piece and the requests banner do not.

## Manifest

`<activity-alias android:name=".KioskHome" android:targetActivity=".MainActivity"
android:enabled="false" android:exported="true">` with MAIN + HOME + DEFAULT. Nothing else is
exported; the device admin still declares no policies (lock task, the keyguard, the preferred home
and `STAY_ON_WHILE_PLUGGED_IN` are a device owner's own powers).

## Greps (v1.6.1 — M20)

`Color(0x` outside `ui/theme`: none. `DisplayBlack`: `Color.kt`, `Theme.kt` (the resting state takes
the canvas from `DisplayTheme`). `0.0.0.0`, `Access-Control`, `Modifier.blur`: none. `LocalLive`:
`LiveDot.kt`, `Theme.kt`. `startLockTask`/`stopLockTask`: `MainActivity.followKiosk` only.

## Measured (September 2026, `steven_piano_m20`, API 34, Pixel 7 profile, debug build)

Settings locked in kiosk, on the AVD made again for it (`m20/kiosk-lock-evidence.log`): with kiosk
mode on, the hub read "Locked" on its eight page rows, both APP switches and Check now ("Check now,
locked"); Feel's row and the Library's + (read "Add MIDI files, locked", a padlock beside it) each
opened "Settings are locked in kiosk" and cancelling left the screen as it was; the right PIN from
Feel's row opened Feel, and back on the hub the padlocks were gone; with Feel open and the tablet
left alone, display mode came at the (shortened) idle time, and the first touch found Feel reading
"Settings are locked in kiosk." with Unlock, the hub's padlocks back; on a 2560 × 1600 px, 240 dpi
frame the page beside the hub showed the same locked page.

A fresh AVD of this run's own (never `steven_piano` or `steven_piano_tablet`), addressed with
`adb -s emulator-5558`; the evidence log is `m20/kiosk-evidence.log` in the run's scratchpad.

- `adb shell dpm set-device-owner dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin` on the fresh
  AVD: "Success". The Kiosk page read "Set a PIN first", then its explanation; Kiosk mode on:
  `mLockTaskModeState=LOCKED`, HOME resolving to `.KioskHome`, `stay_on_while_plugged_in` 1 → 7.
- Home and Recents dead: `KEYCODE_HOME`, `KEYCODE_APP_SWITCH`, the home gesture (a swipe up from the
  bottom edge) and a swipe down from the top edge each left the screen pixel for pixel as it was
  and the app on top, locked; Back twice at the Library (the root) left it there, locked.
- The byline held: the hairline half grown at 1.7 s, the sheet at 3 s over the Library; with
  animations off, the hairline whole at 0.9 s. Wrong PINs: three with no wait, then "Try again in
  5 s", "… 10 s", and after reinstalling the app (a new process) the next wrong try "… 20 s": the
  count outlasted the restart. When a wait ended the field took the focus and the keyboard came
  back.
- Unlock for now: LOCKED → NONE, the shade and Recents opened, Home came back to the app (still the
  home screen); Lock again on the Kiosk page: LOCKED.
- Turn kiosk off: NONE with the app still open (not closed by Android), HOME resolving to the Pixel
  launcher again, the alias off, stay-on back to 1; Home opened the launcher.
- Kiosk on again, `adb reboot`: booted into the app, LOCKED; the power button off and on: straight
  into the app, no lock screen.
- The way back: `setprop debug.stevenpiano.releaseowner yes`; `am force-stop` was ignored ("Ignoring
  request to force stop protected package"); `am start -n …/.MainActivity` delivered to the running
  app ended kiosk mode and gave the role back ("no owners"), NONE with the app still open, HOME the
  launcher, stay-on 1.
- Display mode at rest (`debug.stevenpiano.idlesecs 15`, nothing loaded): black with the byline;
  with Web control and guests on, "Ask the piano", the code (ZXing reads
  `http://10.0.2.17:8737/request`) and the address; on a 2560 × 1600 px, 240 dpi frame the code
  at its 360 dp cap under Display Large. A PIN sheet touched every 5 s stayed open past the idle
  time; left alone it closed and the tablet rested.
- Dark and font scale 2.0: the Kiosk page and both sheets wrap without clipping (the exit sheet's
  buttons go to two lines).
- `./gradlew lint`: 0 errors and 29 warnings (28 on an earlier run: the newer-version notices vary
  with what the check finds online), none in code this run wrote (the manifest's
  `DataExtractionRules` warning predates it). `assembleRelease` builds (2,708,324 bytes, the alias
  disabled and exported in its manifest, the provenance string in `classes.dex`); not staged.

## Deviations from the plan, and why

- **The way back is asked on every activity start and new intent, not only at process start.**
  Android 14 ignores `am force-stop` for a device owner's own package ("protected"; `am stop-app`
  too), so the documented setprop, force-stop, start never restarted the process and the release
  never ran; `am start` now reaches the running app. The README's sequence works unchanged.
- **The way back lets go of the screen first**, as Turn kiosk off does: emptying the lock task list
  under a locked task makes Android clear the task, closing the app.
- **The wrong tries are kept across restarts** (`kioskPinStrikes`, `kioskPinLockedUntil`), as the
  2026-09-27 design asked, so a restart of the tablet gives nobody the three free tries back. The
  schedule is still `LoginGuard`'s: `KioskPinGuard` wraps it and replays kept strikes, and
  `WebAuth.kt` is untouched (its audit runs beside this run).
- **One sheet shape for the PIN before an action**: `PinCheckSheet` (one entry, then the actions),
  sharing `PinSheet`'s frame and field rather than a second sheet; the byline's sheet offers both
  actions at once instead of a second step after the PIN.
- **Lock again** (while unlocked for now) and the rule that Unlock for now also ends when the app is
  opened again or the tablet rests in display mode: "until the next launch" read as a school
  tablet needs it, since an unlocked kiosk left alone would stay open indefinitely otherwise.
- **The PIN is asked for before Change PIN while kiosk mode is on**, and before the switch turns off,
  besides the byline: the Kiosk page is reachable in kiosk mode.
- **The stay-on value is put back**, not reset to 0 (`kioskStayOnBefore`).
- **`KioskController` takes a `KioskDevice`**, not `(dpm, admin, context)`: the seam the test fakes;
  `KioskController.of(context)` builds the Android one.
- **Display mode at rest drifts 4 dp a minute** (burn-in, hours on end) and keeps the screen on only
  through "stay on while plugged in"; a PIN sheet counts its own touches and closes at rest.
- **Not built here, for the merge**: the version bump to 1.6.1 (build 12), `Provenance.text`, the
  staged APKs and the provenance signature.
- **Settings locked in kiosk, a little beyond the list it was given**: besides the + sheet, Delete
  playlist, Remove from playlist, Delete piece and Change photo, the library's other changes ask too
  (Rename a piece or a playlist, Add to playlist, Move up and Move down) and drag reordering is not
  offered while locked, following the rule "anything that changes the piano or the library asks";
  a channel's Set volume asks as a change to the piano. Share diagnostics, the UPDATE row (Update,
  Restart) and Connect stay free. A page shown while locked gives way to a locked page rather than
  showing its controls: on a tablet the hub always shows a page beside it.
- **The Kiosk page's own actions still ask each time**, even with the settings open: they are the way
  out, as before.

## Residuals

- Settings locked in kiosk gate the app's own screens; the web panel (behind its own PIN, on the
  tailnet) is not gated by the kiosk.
- An update installed over adb while kiosk mode is on leaves the launcher up until Home is pressed
  (the app then locks again: measured). The in-app updater reopens the app itself as device owner
  (`UpdateResultReceiver.reopen`), which then locks again: from the code, not run here.
- "Unlock for now" lives in memory: a restart of the app ends it (it locks again).
- A tablet with its own secure lock screen keeps it (Android refuses `setKeyguardDisabled`); the page
  says so.

## Tests added in M20

`KioskControllerTest` (8: the order as device owner, the lock task list first; repeating it never
stacks two preferred homes; nothing touched without the owner; a screen lock Android keeps; off in
reverse with the list last; a refused step undoes the rest; one refused step while turning off
never stops the others; the tidy-up without the owner touches only the alias), `KioskModeTest`
(10: on only with a PIN and the owner; nothing locks before the checks; unlock for now until
opened again, and relock; off waits for the screen to let go; an activity that never lets go does
not keep kiosk mode on; the adb way back ends kiosk mode before the owner goes; the way back while
locked lets go first; without the owner the alias goes off and kiosk mode reads off; a lost lock
task list put back at start; the PIN weighed as the panel's, its tries outlasting a restart),
`PinGuardTest` (6: 0, 0, 0, 5 s, 10 s… to 5 min; a try during a wait is refused uncounted; a right
or new PIN starts again; a restart gives nobody the free tries back; a wait that ended while away;
the cap whatever is kept), `KioskExitTest` (3: three seconds and the offers; the wait's words; the
switch's note and the page's explanation), `SettingsRepositoryTest` (+1: kiosk mode off at first,
its PIN kept apart, its housekeeping), `IdleWatchTest` (+1: display mode always on in kiosk and at
rest with nothing loaded), `GroupSummariesTest` (+1: Kiosk reads on or off); `RoutesTest`,
`PianoPagesTest` and `DiagnosticsExporterTest` (32 lines) changed; then, with settings locked in
kiosk, `KioskModeTest` (+3: open for five minutes (its time shortened) and a second PIN starts it
again, then locked; coming to rest locks them early; unlock for now counts as open, and kiosk mode
off leaves nothing locked, on again nothing left over). 840 tests before, 873 after (7
skipped, as before: the corpus tests, `-Pcorpus`).

## The merge: release 1.6.1 (versionCode 12)

Merged into `main` after 1.6 (`ea8b2cb`, M21) as `f743943`, and released as **1.6.1**: `versionCode`
12, `versionName` "1.6.1", `Provenance.text` "Made by Steven Jin · v1.6.1 · eab16a502f679465", the
entry drafted at the end of `releases/history.json` (`"draft": true`, its notes; no hash or size until
`tools/publish-release.sh` builds it); `latest.json` still names 1.6.

- **Conflicts**: `PianoScreen.kt`'s imports (1.6's `FirmwareReport` beside this run's `KioskPage`: both
  kept; the page switch merged by itself, so the Firmware and status page sits behind the kiosk gate
  as the others do); DESIGN.md and this file, where 1.6's section and this one met at the end: both
  kept, in release order, M21's first. `HubGroups` (CONTROL: Remote control · Kiosk), `GroupSummaries`
  (the Firmware row's update and Kiosk's value), `Routes`, `AppGraph` (the kiosk's start first,
  `firmwareUpdater.start()` beside the web's), the manifest and `GroupSummariesTest` merged by
  themselves.
- **ActionButton** (`53b82eb`, Fable's design fix): Material 3 1.4's outlined button draws its label in
  `onSurfaceVariant`, so every action button read in the secondary ink, the same grey as when it was
  unavailable. The label is `onSurface` now, `onSurfaceVariant` when unavailable, the hairline border
  as before: Check now, Read status, All keys off, Save now, Test LED's Light it, Strike test's Floor
  and Ceiling, Share diagnostics, the Firmware, Remote and Kiosk pages' buttons, the PIN sheets'
  actions and the locked page's Unlock. The connection card's Disconnect and Cancel, the playlist's
  Shuffle and the empty library's Add MIDI files are plain `OutlinedButton`s with the same default,
  left as they are.
- **`docs/SECURITY_AUDIT.md › 1.6.1 — kiosk`** (`621c992`): a note from the merge, since this run
  could not edit the audit: the device owner's wider use, what the PIN protects, the five-minute
  unlock, the adb escape hatch and the residuals.
- **1.6.1, not 1.6**: this section's title and DESIGN's, README's *Kiosk*, and the comments that
  pointed at them. README: *Kiosk* stands after *Updating the piano's firmware* (it followed
  Authorship); the introduction, *What it does* (CONTROL's Kiosk, and a Kiosk entry), *School tablet*
  and *Security* (the device owner's use; kiosk mode's PIN and its limits) say what M20 added.
- **Clearing `debug.stevenpiano.releaseowner`**: the documented `adb shell setprop
  debug.stevenpiano.releaseowner ""` answers "usage: setprop NAME VALUE" (adb drops an empty
  argument) and leaves the property at yes, so setting the device owner again, as *Kiosk* says to,
  was given back the moment the app started (measured on `steven_piano_int`). README (both
  sequences) and `DeviceOwnerRelease`'s KDoc quote the whole command now:
  `adb shell "setprop debug.stevenpiano.releaseowner ''"`, which clears it.
- **With 1.6's firmware updates**: Firmware and status is locked like every page. An update started
  while the settings were open carries on when they lock again (the tablet rests after a minute
  without a touch, or the five minutes run out): the updater runs in the app's scope and its service
  holds the wake lock; the page then shows the locked page until the PIN, and Now playing still says
  "Updating the piano". README › Kiosk says so.
- Tests: 953 (1.6's 920 and this run's 33), 8 skipped (the corpus tests without `-Pcorpus`, and
  `PinnedKeyTest`'s check against the firmware's `include/ota_pubkey.h`). `lint`: 0 errors, 29
  warnings. No compiler warnings in the app's sources. The greps above and M21's: as stated. The
  release APK is 2,758,096 bytes (versionCode 12, "1.6.1", signed `CN=Steven Piano, O=Steven
  Jin, C=US`), the debug APK 16,132,483; staged as `../apk/steven-piano-1.6.1.apk` and
  `-debug.apk`.

---

# v1.5.2 — M19: schedules

Read `DESIGN.md › v1.5.2 — M19` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M19 (binding). Built on its own branch (`m19-schedules`) beside M20 and M21: the version stays
`versionCode` 10, `versionName` "1.5.1" and `Provenance.text` as they were; the bump to 1.5.2
(`versionCode` 11), the provenance manifest and the APKs are made when the branch is merged. The
M18 review's Up next fix came first, as its own commit (`4edb505`).

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`):

- `M/schedule/Occurrences.kt`: `Edge` (START, END), `Occurrence(scheduleId, edge, at)` (`atMillis`),
  `object Occurrences` (`dayBit`, `next`, `nextStart`, `dueAt`, `startAfter`, `endOfRun`, `endAt`;
  `ALL_DAYS` 127, `WEEKDAYS` 31, `WEEKENDS` 96).
- `M/schedule/ScheduleDraft.kt`: `ScheduleRules` (days 1–127, minutes 0–1439, an end that differs
  from the start, a channel's key `[a-z0-9_-]{1,40}` or an id `[1-9][0-9]{0,17}`, volume 0–100 or
  none, `MAX_SCHEDULES` 50; each problem a sentence), `ScheduleDraft` (`problem`, `toggle`,
  `toEntity`, `of`, `fresh`).
- `M/schedule/ScheduleCopy.kt`: the lines, shared by the tablet and the panel (`days`, `clock`,
  `whenLine`, `whatLine`, `next`, `hub`, `missed`, `played`, `recent` and `LAST_SHOWN_MS`).
- `M/schedule/ScheduleRepository.kt` (+ `SaveResult`: `Saved`, `TooMany`, `Gone`).
- `M/schedule/AlarmScheduler.kt`, `AndroidAlarmScheduler.kt`, `SchedulePlanner.kt`,
  `ScheduleReceiver.kt`, `ScheduleRunner.kt` (+ `ScheduleDeck`, `ScheduleOutcomes`),
  `StoredOutcomes.kt`, `Schedules.kt` (+ `ScheduleRow`, `NextSchedule`; `AppGraph.schedules`).
- `M/channels/LoudnessHold.kt` (the channel's volume logic taken out of `ChannelPlayer`, with an
  owner).
- `M/ui/screens/piano/pages/SchedulePage.kt`; `M/ui/screens/schedule/ScheduleEditorSheet.kt`,
  `NextScheduleLine.kt` (+ `ScheduleDraftSaver`).
- Tests: `schedule/OccurrencesTest.kt`, `ScheduleCopyTest.kt`, `ScheduleDraftTest.kt`,
  `SchedulePlannerTest.kt`, `ScheduleRunnerTest.kt`, `FakeScheduleDao.kt`;
  `channels/LoudnessHoldTest.kt`.

Changed: `AndroidManifest.xml` (`USE_EXACT_ALARM`; `SCHEDULE_EXACT_ALARM` with `maxSdkVersion` 32;
`RECEIVE_BOOT_COMPLETED`; the receiver); `AppGraph.kt` (`schedules`, started with the app);
`data/db/ScheduleDao.kt` (`list`, `byId`, `count`), `ScheduleEntity.kt` (its note);
`channels/ChannelPlayer.kt` (`loudness`, `play(key, volumePct)`); `service/PlaybackService.kt`
(stays in the foreground while a schedule starts), `WebService.kt` (pushes a state when the
schedules change); `ble/LoggingPianoLink.kt` (`debug.stevenpiano.piano away`); `web/WebBackend.kt`,
`WebApi.kt`, `WebServer.kt`, `AppWebBackend.kt`; `ui/Routes.kt` (`SettingsPage.Schedule`),
`ui/ChannelCopy.kt` (`SCHEDULE_LATER` gone); `ui/components/SettingsRows.kt` (`switchColors`
internal); `ui/screens/piano/GroupSummaries.kt`, `HubGroups.kt`, `PianoScreen.kt`,
`PianoViewModel.kt`; `ui/screens/library/ChannelCard.kt`, `ChannelRow.kt`, `ChannelsGrid.kt`,
`LibraryScreen.kt`; `ui/screens/nowplaying/NowPlayingScreen.kt`, `NowPlayingPanel.kt`;
`assets/web/app.js`, `index.html`, `style.css`; tests `FakeWebBackend`, `WebServerTest`,
`WebApiTest`, `WebAssetsTest`, `GroupSummariesTest`, `RoutesTest`, `PianoPagesTest`.

## When (`Occurrences`, pure)

- A schedule starts at `startMinute` on each day whose bit is set (Monday 1 … Sunday 64), in the
  zone of the `ZonedDateTime` given. `startAfter(entry, now)` looks eight days ahead from today
  and returns the first start strictly after `now`. `endOfRun(entry, now)`: the run that started
  today or yesterday (a run lasts under a day) and ends after `now`; the end is on the start's
  day, or the next day when `endMinute <= startMinute` (past midnight); no `endMinute`, no end.
- `next(entries, now)`: the earliest of every enabled, well-formed schedule's next start and the
  end of its run going on; at one instant an END before a START, then by id. `nextStart`: starts
  only (the "Next:" line). `dueAt(entries, at)`: everything at exactly that instant, the ends
  first, then the starts by id.
- Local times go through `ZonedDateTime.of(date, time, zone)`: a start in the hour spring skips
  moves forward by the gap (02:30 plays at 03:30), one in the hour autumn repeats takes the
  earlier offset and plays once; a run across either is an hour shorter or longer in real time.

## The alarm (`AlarmScheduler`, `SchedulePlanner`, `ScheduleReceiver`)

- One alarm: `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, atMillis, PendingIntent.getBroadcast(
  context, 0, explicit Intent(ScheduleReceiver) ACTION_FIRE + at, schedule, edge, FLAG_IMMUTABLE |
  FLAG_UPDATE_CURRENT))`; request code 0, so each alarm replaces the last; `cancel()` looks it up
  with `FLAG_NO_CREATE`. `canScheduleExact()`: below API 31 true, else `canScheduleExactAlarms()`;
  in debug builds `debug.stevenpiano.noexact` (any value, read with getprop each time) answers no.
- `SchedulePlanner.replan(afterMillis?)` (one at a time, under a `Mutex`): asks
  `canScheduleExact()` (→ `exactAllowed`), works out `Occurrences.next(entries, after)` in the
  system zone, and sets the alarm for it, or cancels it when nothing is ahead **or exact alarms
  are refused** (then nothing is planned). `recheckExact()` plans again when the answer changed.
- It runs when the table changes (`Schedules.start` collects `repository.all`: every edit, from
  the page or the panel, and the app's start), at every alarm (from the alarm's own minute), and
  from `ScheduleReceiver` for `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`,
  `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, each under
  `goAsync()`. The receiver is not exported: only the system and the app's own alarm reach it.

## What an alarm does (`Schedules.onAlarm`, `ScheduleRunner`)

- On a START alarm, before anything is read: a partial wake lock (`StevenPiano:schedule`, 45 s at
  most) and `runner.prepare()`, which sets `starting` and starts the playback service while the
  exact alarm's grace allows a foreground-service start from the background (Android attaches a
  10 s temporary allow list of type "foreground service allowed" to an exact allow-while-idle
  alarm when the app may schedule exact alarms). `PlaybackService` keeps itself in the foreground
  and the tablet awake while `starting` holds.
- Then: the table is read, `dueAt(at)` found, the alarm planned again from `at`, the broadcast
  answered; every END goes to `runner.end`, the first START to `runner.fire`, any other START at
  that minute to `runner.skipped` (a LinkLog line).
- `fire(occurrence, entry)`: when the link is not `Connected`, `link.connect(lastDeviceAddress)`
  and wait `CONNECT_WAIT_MS` 20 s for `Connected`; after that `missed()`: LinkLog "Missed:
  Wednesday 12:30 (piano not connected)" (through `LinkLog.warn`, tag PianoLink) and the stored
  last line. Connected: wait up to `SETTINGS_WAIT_MS` 3 s for the piano's settings (`PianoState`
  not Unknown, read on every connection), so the volume reaches the piano's own; then play:
  `ChannelPlayer.play(key, volumePct)` (waiting up to 5 s for the channels' pools on a cold
  start), `player.playAll(playlist's ids, false)`, or `player.play(id)`; a playlist's or a piece's
  volume through `LoudnessHold.hold(run, pct)`. What it can't play is missed with the reason ("the
  playlist has no pieces", "the piece was deleted", "the channel needs more pieces"). LinkLog
  "Schedule started: Wednesday 12:30 (channel calm, schedule 4)": a key or an id, never a title.
- **The run** (what a schedule started): a channel's, in charge while `PlayerState.channel` is its
  key; a queue's (its entries' uids at the start), in charge while one of them is current, no
  channel has taken over and it has not failed to load. It ends when it is no longer in charge, or
  when it has stood stopped, not loading, for `IDLE_GRACE_MS` 8 s after it has played (its list
  ran out, or someone stopped it; the gap between pieces is 1.5 s at most). Its end releases its
  hold. `end(occurrence)`: for the run of that schedule only, `player.stop()` if it is still in
  charge, then the release; a later schedule's start takes the run over (and, holding a volume,
  the loudness).
- **`LoudnessHold`** (one, `ChannelPlayer.loudness`, shared): `hold(owner, pct)` remembers what was
  there the first time (`PianoLoudness` from the piano's state, or the player's velocity) and
  holds the new level (`PianoSettingsRepository.holdTemporarily`, never saved; else `setVelocity(50
  + pct / 2)`); a later hold by another owner takes over and keeps that memory; `release(owner)`
  puts it back only for the owner holding it now (the piano's volume with its Full power; the
  velocity only if unchanged). `ChannelPlayer` uses it exactly as before (ChannelPlayerTest's
  twelve pass unchanged), its session as the owner.
- **The last line** (`StoredOutcomes`, its own DataStore "schedules": `last`, `lastAt`): "Last:
  Wednesday 12:30, Calm channel" or the missed line, shown for six days (`ScheduleCopy.recent`);
  not in Share diagnostics' settings (the link log carries the missed starts).

## UI

- `SettingsPage.Schedule` ("schedule", "Schedule") between Display and Remote control (the pages
  are in the hub's order, `PianoPagesTest`); `HubGroups` PLAYING = Playback, Display, Schedule;
  `GroupSummaries.schedule` = `ScheduleCopy.hub(next start)` ("Next Wed 12:30" / "None"), from
  `PianoViewModel.nextSchedule` (`Schedules.next`: the table, the channels and a minute tick,
  `WhileSubscribed`).
- `SchedulePage()`: `NextScheduleLine`, the last line (`Schedules.last`, only with rows), the
  exact-alarm `ActionRow` while `exactAllowed` is false (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with
  the package, else `ACTION_APPLICATION_DETAILS_SETTINGS`; `LifecycleResumeEffect` →
  `recheckExact()`), `SectionRule`, a row a `ScheduleRow` (`combinedClickable`: tap edits, long
  press `DropdownMenu` Edit/Delete; `Switch` with `switchColors()`, described by the row's lines),
  the delete `AlertDialog`, and the Add schedule `ActionRow`. Writes run in the app's scope.
- `ScheduleEditorSheet(initial, onDismiss)`: `ModalBottomSheet` (`surfaceVariant`,
  `skipPartiallyExpanded`, `imePadding`, scrolling); the draft in `rememberSaveable(stateSaver =
  ScheduleDraftSaver)`; chips `FilterChip` with `selectedContainerColor = surface` and a
  tertiary 1 dp selected border; the times on `ActionButton`s opening `TimeDialog`
  (`BasicAlertDialog` + `Surface(surfaceVariant, tonalElevation 0)` + `TimePicker(is24Hour = true,
  TimePickerDefaults.colors(...))`, every colour an ink or paper token); the choices from
  `channelPools.summaries`, `library.playlists()`, `library.search`/`recent` (30); Save through
  `Schedules.save` in the app's scope (TooMany / Gone shown in the sheet).
- `ChannelCard(…, onSchedule)`: the menu's Schedule is live; `LibraryScreen` opens
  `ScheduleEditorSheet(ScheduleDraft.fresh(now, CHANNEL, key, the channel's volume))`.
- `NextScheduleLine(modifier, centred)`: `Schedules.next`'s line in `Eyebrow` (two lines at most),
  over the empty line of `NowPlayingScreen` and `NowPlayingPanel` (a centred `Column`).

## Web (`/api/schedules`)

| Method | Path | Access | Body | Answer |
|---|---|---|---|---|
| GET | `/api/schedules` | read | — | `{schedules: [{id, days, startMinute, kind, target, endMinute, volumePct, enabled, name, when, what}], next, last, exactAlarms}` |
| POST | `/api/schedules` | write | `{days, startMinute, kind, target, endMinute?, volumePct?, enabled?}` | 201 `{schedule}`; 400; 409 `too-many` |
| PUT | `/api/schedules/{id}` (id from 1) | write | as POST | 204; 400; 404 (gone) |
| DELETE | `/api/schedules/{id}` | write | — | 204; 404 |

- `kind` is playlist · channel · piece; `target` a channel's key or the id as text; `endMinute`
  null (or absent) plays until the end; `volumePct` null (or absent) sets none; `enabled` true
  unless false. Fields strictly (`onlyKeys`, whole numbers); the numbers then judged by
  `ScheduleRules`, so a refusal reads as the editor's ("Choose at least one day."); the target
  must be in the library (`WebBackend.scheduleTarget`, 400 "That channel, playlist or piece isn't
  in the library."). `name`, `when`, `what` are read-only (400 if sent).
- The write routes need the cookie, `X-Steven-Piano: 1`, the panel's Host and Origin, as every
  other: the WebServerTest matrix is 22 routes now. `WebBackend` gains `schedules`,
  `scheduleTarget`, `saveSchedule`, `deleteSchedule`; the state gains `schedule: {next, revision}`
  (the next start's line; a hash of the table), and the web service pushes a state when the
  table changes, so an open Schedule page reads it again. A panel DELETE sends no body.
- The panel (`app.js`): `scheduleLoad`/`renderSchedule`, rows with `showMenu` (the pieces' menu
  builder, shared), the delete asked in the row, the editor (`scheduling.editing`) with the times
  as two `<select>`s each (00–23, 00–59), choices from `/api/channels`, `/api/playlists`,
  `/api/library`; Now playing's eyebrow shows `state.schedule.next` with nothing loaded.

## Greps (v1.5.2 — M19)

As in M18: no `Color(0x` outside `ui/theme`; no `0.0.0.0` or `Access-Control` in `app/src/main`;
`DisplayBlack` in `Color.kt` and `Theme.kt`; `LocalNoteSounding` in `Theme.kt` and `ScorePages.kt`;
Haze in `Glass.kt` (sources `Glass.kt`, `NavHost.kt`, `NotePanel.kt`); no `Modifier.blur`; no pure
black or white in `assets/web` (only `white-space`).

## Measured (September 2026, `steven_piano`, API 34, debug build, the emulated piano)

- Tests: 879, none failing (7 skipped without `-Pcorpus`, as before). `lint`: 0 errors, 29
  warnings, M18's 29 (none in this run's code; three in touched files predate it).
  `assembleDebug`, `assembleRelease` clean, no compiler warnings; the release APK 2,771,540 bytes
  (1.5.1: 2,675,444).
- The alarm, `dumpsys alarm`: `RTC_WAKEUP … tag=*walarm*:dev.stevenjin.stevenpiano.action.SCHEDULE_FIRE
  … exactAllowReason=policy_permission`, its delivery carrying `temporaryAppAllowlistDuration=10000,
  temporaryAppAllowlistType=0` (foreground-service starts allowed).
- **Screen off** (`input keyevent KEYCODE_SLEEP`, `mWakefulness=Asleep` throughout; a schedule made
  through the API two minutes ahead, Calm at 60 % until two minutes later, the link disconnected):
  the alarm at 10:15:00.044 (the end planned at .061); the piano connected at 01.572 (the emulated
  scan's 1.5 s); its settings read by 01.626; "Schedule started" at 01.647; `volume 60` held at
  01.786 (the firmware turned Full power off); the first notes at 03.745, after the 2 s pause;
  `PlaybackService` in the foreground (mediaPlayback) with the screen asleep. The end at
  10:17:00.022: `B0 40 00`, `B0 7B 00` at .044, "Schedule ended", `volume 100` and `fullpower 1`
  back; the service gone. (`schedule-fire.txt`)
- **Deep Doze** (`dumpsys battery unplug; dumpsys deviceidle force-idle deep`, IDLE throughout): a
  playlist at 45 % started at 10:19:00.034, the playback service started from the background
  (ActivityManager's own line), 858 notes in its minute, the end at 10:20:00.009 with the volume
  back. (`schedule-fire-doze.txt`)
- **Missed** (`debug.stevenpiano.piano away`): the alarm at 10:32:00.060, "Missed: Monday 10:32
  (piano not connected)" at 10:32:20.101 in the log and then on the page. (`schedule-missed.txt`)
- **Restart** (`adb reboot`): the 12:30 alarm before; after the boot, the app's process started for
  `BOOT_COMPLETED` about 24 s in, "Planning again after android.intent.action.BOOT_COMPLETED", the
  12:30 alarm set again, the app never opened. (`reboot-alarm.txt`)
- **Time zone** (auto zone off; `IAlarmManager.setTimeZone` by `service call`): to Europe/London the
  alarm moved to 20:00 BST (five hours earlier in absolute time), back to America/New_York 20:00
  EDT. A force-stop and a launch set the alarm again at the start. (`timezone-replan.txt`)
- **Exact alarms refused** (`debug.stevenpiano.noexact 1`): "Exact alarms not allowed: no alarm",
  nothing pending for the app's uid, the row shown; its button opened Android's Alarms & reminders
  page for the app, whose switch is greyed on API 34 (the app holds `USE_EXACT_ALARM`); the
  property cleared, back on the page: the row gone and the alarm set again.
- The page (empty, with rows, the editor, the piece search, the time picker, the channel card's
  Schedule with Calm at its 49 %, the missed line), Now playing's empty state and the tablet frame's
  panel (2560 × 1600 px, 240 dpi) with "NEXT: MONDAY 12:30, CALM", light and dark. The time
  picker's dialog measures `#FBF9F4` with the dial `#F4F1EA` (Material's TimePickerDialog gave
  `#DCDAD3`, the paper under the ink's elevation tint).
- The panel (headless Chrome, 1280 × 800 and 390 × 844, light and dark): the list, the editor,
  a save (201, "Weekdays 17:00 · Calm channel · until 17:45 · 70%"), a POST without days (400
  "Choose at least one day."), a delete asked in its row; Now playing's empty state with "Next:
  Monday 12:30, Calm"; nothing scrolls sideways at 390 px. (`web-evidence.txt`)

## Deviations from the plan, and why

- **The end stops only what the schedule started**: a piece the person played meanwhile plays on
  (the plan: "the end alarm → `player.stop()`"). Stopping someone's own choice at a minute they
  did not set would be a surprise; the run's rule above decides.
- **`LoudnessHold`**, taken out of `ChannelPlayer` with an owner, rather than a second copy of the
  channel's volume logic: a schedule, a channel and a schedule that plays a channel then share one
  "what comes back", in whatever order the player tells them.
- **No alarm without exact alarms** (no inexact fallback): an inexact alarm may come hours late,
  and a piano starting at a random time is worse than one that says why it won't.
- **`USE_EXACT_ALARM` makes the Allow row Android 12's only**; on API 33+ the person cannot take
  exact alarms away (Android's page shows the switch greyed), so the emulator shows the row
  through `debug.stevenpiano.noexact` (debug builds only).
- **`AlarmScheduler.schedule(atMillis, occurrence)`** takes the occurrence (the plan's
  `occurrenceId`); the alarm carries its minute, the schedule and the edge, and what is due is read
  again from the table at that minute (`dueAt`), so an edit made since counts.
- **Planned from the alarm's minute**, not the clock, after an alarm: a start sharing the minute
  was due with it and is not planned twice; two starts at one minute: the first plays, the others
  are logged as skipped.
- **The playback service comes up at the alarm** (`prepare`), before the 20 s wait, while the
  exact alarm's grace lets it start from the background, and stays in the foreground meanwhile;
  plus a 45 s wake lock. Started after a 20 s wait it could be refused.
- **Up to 3 s for the piano's settings** after connecting (not in the plan): without them the
  volume would fall back to the app's velocity on a piano that has a volume of its own.
- **Also planned again** on `MY_PACKAGE_REPLACED` and whenever the app starts (the table's first
  read), beyond boot, time, zone and the permission.
- **The last line in a DataStore of its own** ("schedules"), shown six days and only beside
  schedules: a weekday without a date is only clear within the week; kept apart from
  `Settings.kt`, which M20 edits too.
- **The time picker in a `BasicAlertDialog` of the app's own**, not Material's `TimePickerDialog`,
  whose surface is the paper tinted by the ink's elevation overlay (`#DCDAD3`, no token). The times
  sit on outlined buttons (an outlined button acts).
- **"Set the volume"** (the schema's `volumePct` null) beside the slider the plan named.
- **Percentages "70%"**, not "70 %": `Format.percent`, as every percentage since M15.
- **The web API's shapes**: GET adds read-only `name`, `when`, `what` and `{next, last,
  exactAlarms}`; POST answers 201 with the schedule, PUT 204; ids from 1 in the paths (a PUT
  never makes one); 409 past fifty; the state carries `schedule {next, revision}`. The panel's
  times are selects, as a browser's time field follows its own 12-hour clock (seen: "05:00 PM").
- **The panel's Now playing shows the Next line too** (the plan: "also on Now playing's empty state
  and the panel").
- **`debug.stevenpiano.piano away`** (debug builds on an emulator): the stand-in piano never found,
  for the missed start's evidence.
- **A schedule turned off or deleted while it plays** leaves what it started playing: its end
  alarm follows the enabled schedules only.
- **`SettingsPage.Schedule` sits after Display** in the enum, not at its end: the pages are in the
  hub's order (`PianoPagesTest`).
- **No version bump** in this run (above).

## Tests added in M19

`OccurrencesTest` (11: the day bits and a start on its days only; a week round; a run's end, then
the next start; past midnight, across a day the schedule isn't on; until the end; spring's skipped
hour and a run across it; autumn's repeated hour, once; the zone; disabled and malformed schedules;
an end before a start at one instant, and `nextStart`; what is due at a minute), `ScheduleCopyTest`
(5: the days; a row's lines; the next start, the hub, the missed and played lines; six days for
the last line; the 24-hour clock), `ScheduleDraftTest` (3: the rules' sentences; a new draft; the
repository's made-when, the fifty-first and a schedule gone), `SchedulePlannerTest` (6, a
`FakeAlarmScheduler`: the next start; a run's end then the next start; changes and no alarm with
nothing ahead; a start sharing an alarm's minute not planned twice; exact alarms refused and
allowed again; a new time zone), `ScheduleRunnerTest` (10, virtual time: the piano connecting after
2 s; never, missed at 20 s; the settings waited for; a channel and its end; the person taking over;
the end stopping a playlist; the gap between pieces is not an end, a list run out is; a second
schedule taking the loudness over; what can't play; no volume), `LoudnessHoldTest` (2),
`GroupSummariesTest` (+1: None, Next Wed 12:30), `WebServerTest` (+1: list, make, change, delete,
fifteen refusals in the app's words, the fifty-first, exact alarms off and the last line; the
write matrix at 22 routes); `WebApiTest` (the state's schedule), `WebAssetsTest` (the page's
copy), `RoutesTest` and `PianoPagesTest` (the new page) changed. 840 tests before, 879 after.
