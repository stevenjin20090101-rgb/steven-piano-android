<!-- =====================================================================
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
  person (see `v1.2 — M11 › Network policy`). From v1.15 (M40), with Album covers on,
  `itunes.apple.com` and Apple's image hosts `*.mzstatic.com` for pieces' album covers (see
  `v1.15 — M40`). From v1.4, for the app's own updates only,
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

# v1.6.2 — M19: schedules; release 1.6.2 (versionCode 13)

Read `DESIGN.md › v1.6.2 — M19` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› M19 (binding). Built on its own branch (`m19-schedules`) beside M20 and M21: the version stays
`versionCode` 10, `versionName` "1.5.1" and `Provenance.text` as they were; the bump (it became
1.6.2, `versionCode` 13, after M21's 1.6 and M20's 1.6.1: see *The merge* at the end of this
section), the provenance manifest and the APKs are made when the branch is merged. The
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

## Greps (v1.6.2 — M19)

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

## The merge: release 1.6.2 (versionCode 13)

Merged into `main` after 1.6.1 (`7a1197d`: M21's 1.6 and M20's 1.6.1 before it) as `dd7e953`, and
released as **1.6.2**: `versionCode` 13, `versionName` "1.6.2", `Provenance.text` "Made by Steven
Jin · v1.6.2 · eab16a502f679465", the entry drafted at the end of `releases/history.json`
(`"draft": true`, its notes; no hash or size until `tools/publish-release.sh` builds it);
`latest.json` still names 1.6.1.

- **Conflicts**: `SettingsPage` (the enum merged by itself in the hub's order, Schedule after Display
  and Kiosk last; its note), `HubGroups` (PLAYING Playback · Display · Schedule, CONTROL Remote
  control · Kiosk), `GroupSummaries` (kiosk and schedule, `from`'s firmware and next-schedule
  parameters), `PianoViewModel`, `PianoScreen` (the summaries, the page switch), `LibraryScreen`
  (the kiosk gate and the channel card's Schedule: scheduling a channel asks for the kiosk PIN, as
  its volume does), `AppGraph` (kiosk and schedules; `firmwareUpdater.start()` and
  `schedules.start()`), the manifest (`FirmwareService` and `ScheduleReceiver`), `RoutesTest`,
  `GroupSummariesTest`, `PianoPagesTest`, and DESIGN.md and this file (main's text with this
  section after M20's; git had interleaved the two "Files" lists). `NowPlayingPanel`,
  `LoggingPianoLink`, `SettingsRows` and README merged by themselves; `WebServerTest`'s matrix
  stays at 22 changing routes (M20 and M21 added none).
- **A schedule during a firmware update** (`adb9471`): this run was built before the player had
  M21's lock, and `Player.play`/`playAll` refuse quietly while locked, so a start in an update would
  have recorded "Last: …", held a volume on the piano and asked the link to connect while the
  updater waited for the piano's restart (BLE_OTA.md › 11: no schedule runs during a transfer).
  `ScheduleDeck.locked` (the app's deck reads `Player.locked`); `fire` turns the start away before
  asking for the piano and again after waiting for it: "Missed: Wednesday 12:30 (the piano was
  updating)" (`ScheduleCopy.UPDATING`) in the link's trail and on the page, nothing played, no
  volume held. `ScheduleRunnerTest` +2.
- **Outlined buttons** (`e4f181d`): the connection card's Disconnect and Cancel, the playlist's
  Shuffle and the empty library's Add MIDI files take `ActionButton`'s label colours
  (`ActionLabels`/`actionButtonColors()` in `SettingsRows`: `onSurface`, `onSurfaceVariant`
  unavailable); the theme's schemes are `internal` for `ActionLabelsTest` (2).
- **Kiosk and a firmware update** (`6ddc3c0`): with the settings locked, Firmware and status keeps
  the update's block in view (its line, percentage and hairline, Cancel through the kiosk gate, how
  it ended; never the offer or Retry) above the locked page's line, and opens from the hub without
  the PIN while an update runs (its chevron back); the idle relock locks the settings, never the
  progress. `LockedFirmware` in `ui/KioskLock.kt`, `LockedFirmwareUpdate` in `FirmwarePage`;
  `LockedFirmwareTest` (2). DESIGN.md › v1.6.1 — M20 › Settings locked in kiosk says so. Under the
  block the locked page leaves its own top rule out (`e37eea5`, seen on the emulator: two rules).
- **`docs/SECURITY_AUDIT.md › 1.6 — firmware updates (notes)`** (`26210bf`), before the 1.6.1 kiosk
  note: the allow-list, the caps, the app's and the piano's Ed25519 checks, `PinnedKeyTest`, the
  player lock, and the residuals.
- **1.6.2, not 1.5.2**: this section's title and DESIGN's, and every comment, stylesheet note and
  test message that pointed at them. README: *Schedules* stands after *Kiosk* (it followed
  Authorship), its checklist no longer says "add to"; the introduction, *What it does* (the
  channels' Schedule, PLAYING's Schedule, a Schedules entry) and *Kiosk* (the Schedule page and a
  channel's Schedule behind the PIN; the firmware update kept in view) say what M19 and the fixes
  added.
- **Measured at the merge** (`steven_piano_int`, API 34, Pixel 7 profile, debug build, the emulated
  piano; the run's own AVD, removed after): four MIDI files added through Add files; the hub's
  Schedule row "None", then "Next Tue 11:53"; the editor (days, the app's time picker, Everything,
  70 %); a Weekdays 11:53–11:55 schedule of the Everything channel two minutes ahead, the piano
  disconnected, the screen off and the emulator forced into deep Doze on battery: the exact alarm
  (`RTC_WAKEUP`, `exactAllowReason=policy_permission`) fired at 11:53:00.04, the playback service
  started from the background (`ALARM_MANAGER_WHILE_IDLE`), the piano connected 1.6 s later,
  "Schedule started: Monday 11:53 (channel everything, schedule 1)", `volume 70` on the console,
  the first notes 2 s after; at 11:55:00 the stop sequence, "Schedule ended: Monday 11:55
  (schedule 1)", `volume 100` and `fullpower 1` back, the next start planned for Tuesday; the page
  read "Last: Monday 11:53, Everything channel". With `debug.stevenpiano.fakeota happy`, a 12:00
  schedule falling in a transfer (sending from 11:59:12): "Missed: Monday 12:00 (the piano was
  updating)" in the link's log and on the page; the update went on to 2.1.0. Kiosk mode on
  (`dpm set-device-owner`, a test PIN): Firmware and status opened with the PIN, Update, then
  display mode after the (shortened) idle time relocked the settings; a touch back found the page
  "Sending · 58%" with its hairline and Cancel over "Settings are locked in kiosk."; Cancel opened
  the PIN sheet (dismissed; the update went on); the hub's row read "Updating…" with its chevron
  and opened the page without the PIN; at the end "Updated to 2.1.0 · confirming…" over the lock's
  line. The device owner given back (`no owners`, lock task NONE, stay-on 1). No crash.
- Tests: 998 (1.6.1's 953, this run's 39, the merge's 6), 8 skipped (the corpus tests without
  `-Pcorpus`, and `PinnedKeyTest`'s check against the firmware's `include/ota_pubkey.h`). `lint`: 0
  errors, 29 warnings. No compiler warnings in the app's sources. The greps above, M20's and
  M21's: as stated. The release APK is 2,837,804 bytes (versionCode 13, "1.6.2", signed
  `CN=Steven Piano, O=Steven Jin, C=US`), the debug APK 16,537,905; staged as
  `../apk/steven-piano-1.6.2.apk` and `-debug.apk`.

# v1.7 — M23: Studio, part 1 — transcription on the tablet; release 1.7 (versionCode 14, with M24)

Read `DESIGN.md › v1.7 — M23` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› item 13 and "M23 — Studio transcription" (binding, with the spike's numbers overriding its
guesses: sizes, memory gates, the runtime's version); the contract is `docs/STUDIO_SPIKE.md` › The
contract for M23 and M24. Built on `main` after the spike's merge (`1470549`), a commit a step
(`67c0df2` runtime and gates, `e26435a` models, `72b4329` audio, `6eaf350` transcription, `25d9527`
jobs and the service, `07f8f77` the UI and the panel, `eafbd84` and `0a52b90` what the emulator
showed, then these notes). **No version bump in this run** (`versionCode` 13, `versionName` "1.6.2" and
`Provenance.text` stayed): 1.7 was released after M24 (composing) and the security audit's second delta
(v1.7 — M24 › The release: 1.7).

## The rule: ONNX Runtime 1.28.0, and no provider of its own

- `com.microsoft.onnxruntime:onnxruntime-android` is **1.28.0** (`gradle/libs.versions.toml`,
  `onnxruntime`), and never 1.29.0 or newer: from 1.29 the AAR merges a startup provider,
  `ai.onnxruntime.TelemetryInitializer`, into the app's manifest. The JVM build of the same version
  (`com.microsoft.onnxruntime:onnxruntime`) is a test dependency only.
- **The merged manifest of every variant holds no `<provider>` whose `android:name` starts with
  `ai.onnxruntime`, and no `TelemetryInitializer` anywhere.** `checkOnnxTelemetry` (in
  `app/build.gradle.kts`, `OnnxTelemetryCheck`) enforces it: `check<Variant>OnnxTelemetry` for each
  variant reads `SingleArtifact.MERGED_MANIFEST`, writes its report under `build/reports`, and fails
  the build naming what it found; `check` depends on the aggregate. Verified failing with 1.30.0 and
  passing with 1.28.0. A newer runtime needs this rule changed first, by a decision, never by
  removing the provider quietly.
- `defaultConfig.ndk.abiFilters += "arm64-v8a"` (the only ABI the app ships, for every native
  library; **replaced in M24** by excluding ONNX Runtime's other ABIs alone: v1.7 — M24 › The
  build); `packaging.jniLibs.useLegacyPackaging = true` (the 28.6 MB `libonnxruntime.so` travels
  deflated, 10.6 MB, and is extracted at install); R8 `-keep class ai.onnxruntime.** { *; }` (the
  JNI calls the Java API by name). `app/lint.xml` (new): `NewerVersionAvailable` ignored for
  `com.microsoft.onnxruntime` (the pin is deliberate), `ChromeOsAbiSupport` ignored (arm64 only is
  a decision).
- `-PstudioModels=<dir>` hands `TranscriberTest` a folder holding `transcription-v1.onnx`
  (system property `stevenpiano.studio.models`); without it the real-model case is skipped.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/studio/StudioAvailability.kt`: `StudioSupport` (Checking, Available, NoRuntime,
  TooLittleMemory), `MemorySnapshot`, `object MemoryGate` (`OFFER_TOTAL_BYTES` 2.5 GiB,
  `TRANSCRIPTION_FREE_BYTES` 900 MiB, `RUNNING_FREE_BYTES` 128 MiB; `offered`, `canStart`,
  `canContinue`, `support`; the overrides `noruntime`, `lowmem`, `busy`), `StudioAvailability`
  (asked once, off the main thread; `memory()`; `PROPERTY` `debug.stevenpiano.studio`).
- `M/studio/ModelCatalogue.kt` (`ModelEntry`; `transcription`, `composer`, `all`, `named`),
  `ModelManifest.kt` (+ `InvalidModelManifest`), `ModelStore.kt`, `ModelInstaller.kt`
  (+ `StudioFailure`, `object StudioFailures`: every refusal's sentence).
- `M/update/VerifiedDownloader.kt` (`Target`, `DownloadProblem`, `DownloadFailure`, `sha256Of`).
- `M/studio/Resample.kt` (`Resample`, `Resampler`, `FloatSink`, `FloatBuilder`), `WavReader.kt`
  (`AudioLimits`, `AudioFailure`, `DecodedAudio`, `MonoTo16k`, `WavReader`), `AudioDecoder.kt`
  (`AudioSource`: `Document`, `Local`).
- `M/studio/NotePostProcessor.kt` (`TranscribedNote`, `PedalEvent`, `Transcription`),
  `Transcriber.kt` (`WindowOutputs`, `WindowModel`, `OrtWindowModel`), `M/midi/SmfWriter.kt`.
- `M/studio/StudioPieces.kt` (`StudioLibrary`, `StudioPiece`), `AppStudioLibrary.kt`,
  `StudioReview.kt` (`ReviewStore`, `ReviewPlayer`, `StoredReview`: DataStore "studio").
- `M/studio/StudioJobs.kt` (`JobKind`, `JobState`, `JobStep`, `StudioJob`, `StudioJobs`),
  `Studio.kt` (+ `RecordingReader`), `M/service/StudioService.kt`.
- `M/ui/StudioCopy.kt`; `M/ui/screens/piano/pages/StudioPage.kt` (+ `rememberRecordingPicker`);
  `M/ui/screens/nowplaying/StudioReviewBanner.kt`.
- `app/lint.xml`; `third_party/onnxruntime/` (`LICENSE.txt`, `ThirdPartyNotices.txt` from the
  1.28.0 artifact), `third_party/piano-transcription/NOTICE.txt`,
  `third_party/anticipatory-music-transformer/` (`LICENSE.txt`, `NOTICE.txt`).
- Tests: `T/studio/MemoryGateTest`, `ModelManifestTest`, `ModelStoreTest`, `ModelInstallerTest`,
  `ResampleTest`, `AudioDecoderTest`, `StudioFixtures` (the spike's fixtures, WAV writer),
  `NotePostProcessorTest`, `TranscriberTest`, `StudioPiecesTest`, `StudioReviewTest`, `StudioTest`;
  `T/update/VerifiedDownloaderTest`; `T/midi/SmfWriterTest`; `T/ui/StudioCopyTest`.

Changed: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`,
`AndroidManifest.xml` (`StudioService`, not exported, `dataSync`); `App.kt` (the "studio" channel),
`AppGraph.kt` (`studio`, its thread, wake lock and log; the player's trail); `update/UpdateSource.kt`
(`Kind.Models`, `allowsModel*`, `localModels`, `MAX_MODEL_BYTES`), `UpdateDownloader.kt` (runs on
`VerifiedDownloader`, its behaviour and lines unchanged), `UpdateServer.kt` (the file's Accept),
`UpdateOverride.kt` (`ModelsOverride`: `debug.stevenpiano.modelsurl`), `UpdateManifest.kt` (a note);
`player/PlaybackEngine.kt` (`PlaybackTiming`), `Player.kt` (`trail`, `forget`, `timingLine`);
`diag/LinkLog.kt` (a note); `data/art/ArtworkRepository.kt` (`describe`), `ArtworkFetcher.kt`
("made in studio" is no one to look up), `data/imports/ComposerNames.kt` (`STUDIO`);
`service/WebService.kt`; `web/WebBackend.kt`, `WebApi.kt`, `WebServer.kt`, `AppWebBackend.kt`;
`ui/Routes.kt` (`SettingsPage.Studio`), `ui/NavHost.kt`; `ui/screens/piano/HubGroups.kt`,
`GroupSummaries.kt`, `PianoScreen.kt`, `PianoViewModel.kt`, `AboutRow.kt`;
`ui/screens/library/AddSheet.kt`, `ImportBar.kt` (`StudioBar`), `LibraryScreen.kt`;
`ui/screens/nowplaying/NowPlayingScreen.kt`, `NowPlayingPanel.kt`;
`ui/screens/piece/PieceDetailSheet.kt`; `assets/web/index.html`, `app.js`, `style.css`; tests
`UpdateSourceTest`, `PlayerTest`, `PlaybackEngineTest`, `ComposerNamesTest`, `PieceNotesChoiceTest`,
`RoutesTest`, `PianoPagesTest`, `GroupSummariesTest`, `FakeWebBackend`, `WebServerTest`,
`WebApiTest`, `WebAssetsTest`; `AUTHORS`, `README.md`, `DESIGN.md`, `docs/SECURITY_AUDIT.md`.

## Availability and the memory gates (`studio/StudioAvailability.kt`)

- Asked once per process, the first time something shows Studio (the hub, the + sheet, the page,
  the panel's upload), on `Dispatchers.Default`: `ActivityManager.MemoryInfo.totalMem` against
  2.5 GiB, then `OrtEnvironment.getEnvironment()` in a `try` catching `Throwable` (an
  `UnsatisfiedLinkError` is a no). Loading maps the 28 MB library, so it is never done at start.
- A transcription starts only when `!lowMemory && availMem − threshold ≥ 900 MiB`
  (`StudioFailures.BUSY`, "Close other apps and try again."); before every window it needs 128 MiB
  (`canContinue`; else `RAN_OUT`). Numbers from the spike: the job costs at most 576 MiB above the
  app's own resident set; the process peaks at 0.68–0.74 GiB.
- `debug.stevenpiano.studio` (debug builds on an emulator only, read once per process with
  `getprop`): `noruntime`, `lowmem` play those devices; `busy` makes `memory()` report nothing free.

## Models (`studio/ModelCatalogue.kt`, `ModelManifest.kt`, `ModelStore.kt`, `ModelInstaller.kt`)

- Pinned in source: `transcription` v1 `transcription-v1.onnx`, 124,511,036 bytes,
  `f5db051a0af4a3601c18b3ecf679be3150912d8d535e3a03554c9662c8525383`, CC-BY-4.0; `composer` v1
  `composer-v1.onnx`, 173,193,820 bytes,
  `86ddb19c7afce2bab6be13706cb0a0f44cd7a4271c021706d02394c10cbda7b1`, Apache-2.0; each with its
  address on the release `models`, its attribution, its title, licence label and use.
- `ModelManifest.parse(text, source)`: `releases/models.json` as the spike's `publish_models.py`
  writes it, at most 64 KB and 32 models, no name and version twice; names `[a-z][a-z0-9-]{0,31}`,
  versions 1–1,000, the file exactly `<name>-v<version>.onnx` and the address's last segment, sizes
  1 byte to `MAX_MODEL_BYTES`, 64-digit hashes, attribution to 300 characters, each address through
  the source's model rules. A pinned model's version with another hash or size refuses the whole
  list.
- `ModelStore(filesDir/models)`: installed = the file at its pinned size; `open()` hashes it once per
  process and deletes a mismatch (`MODEL_DAMAGED`); `remove`; `sweep` drops `.part` files at start.
- `ModelInstaller.install(model)`: the list (`UNREACHABLE`, `UNREADABLE`, `NOT_OFFERED`), then
  `VerifiedDownloader` with the catalogue's size and hash (never the list's), `MAX_MODEL_BYTES`
  (1 GiB), a 256 MB free margin, progress every 256 KB.
- `VerifiedDownloader` (the updater's way made general): `<dir>/<name>.part`, hashed as it is
  written, at most `min(cap, size)` bytes whatever the server says, then renamed only on the exact
  size and hash; `Mismatch`, `Stopped`, `Unreachable`, `NoRoom`. `UpdateDownloader` runs on it.
- `UpdateSource.models`: the list at `https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/models.json`
  exactly; files `https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/models/<file>.onnx`;
  hops there or on GitHub's two asset hosts. `ModelsOverride`: `debug.stevenpiano.modelsurl` names a
  local list whose files are on its own origin (HTTP allowed; debug builds on an emulator only).

## Audio (`studio/AudioDecoder.kt`, `WavReader.kt`, `Resample.kt`)

- `AudioDecoder.decode(source, cancelled)`: over 200 MB refused (`TOO_LARGE`); the first 12 bytes
  read: a MIDI file (`MThd`, or RIFF `RMID`) refused (`AudioFailure.MIDI`); a WAV file through
  `WavReader`; anything else through `MediaExtractor` (the first `audio/` track; a container that
  says it lasts over 20 minutes refused before decoding) and `MediaCodec` (16-bit or float PCM),
  drained into `MonoTo16k`.
- `WavReader`: PCM 8/16/24/32-bit, float 32/64, `WAVE_FORMAT_EXTENSIBLE`; 1–768 kHz, 1–16
  channels; a data chunk of unknown size runs to the end; the announced length checked before
  decoding. `MonoTo16k` averages the channels and resamples as frames arrive, 20 minutes of the
  source at most (`TOO_LONG`), into one array sized from the estimate.
- `Resampler`: windowed sinc, Kaiser β 9, 24 zero crossings, cutoff 0.94 of the lower Nyquist, a
  512-point-per-sample table with linear interpolation, each output at its exact input position;
  streaming (the same output whatever the pieces). `Resample.to16k` for whole arrays.

## Transcription (`studio/Transcriber.kt`, `NotePostProcessor.kt`; `midi/SmfWriter.kt`)

- `OrtWindowModel`: one `OrtSession` per job, CPU provider, `intraOpNumThreads` 4,
  `interOpNumThreads` 1, `SEQUENTIAL`, `ALL_OPT`, memory patterns off; input `audio` `[1,160000]`;
  the seven outputs copied into arrays kept for the job.
- `Transcriber.transcribe(audio, model, progress)`: the audio zero-padded to whole 160,000-sample
  windows, a window every 80,000 samples (`windowsFor`: 2 × padded / 160,000 − 1), one at a time;
  frames stitched as the package's `deframe` (`rowsOf`: frame 1000 dropped; 0–749 of the first,
  250–749 of the middle, 250–999 of the last; all 1,001 of a single window), every frame of the
  padded audio kept, as the package keeps them; progress per window; before each window the
  cancel and the memory checks.
- `NotePostProcessor`: the port of `RegressionPostProcessor` with `piano_vad`'s note and pedal
  detection (thresholds 0.3 / 0.3 / 0.1 / 0.2, pedal frame 0.5; float32 shifts, times via float64
  kept as float32, velocity `int(v × 128)` to 127, Python's truth of frame 0), streaming: a frame is
  decided once the four after it are in, so the outputs of a long recording are never all held.
- `SmfWriter.write(notes, pedals, title, text)`: format 0, PPQ 480, tempo 500,000 µs (120 bpm), 4/4;
  the title as the track name (FF 03, UTF-8), a text event (FF 01; Studio's is ASCII); CC64 and the
  notes at one tick in the parser's order (controls, offs, ons), every note at least a tick long,
  velocities 1–127; round-trips through `SmfParser`.

## The piece, and Keep or Discard (`studio/StudioPieces.kt`, `AppStudioLibrary.kt`, `StudioReview.kt`)

- `StudioPieces.add(transcription, recordingName, at)`: no notes → `NO_NOTES`; the title the
  recording's name without its extension (cleaned and cut as imports' titles), or "Recording ·
  <date>"; the MIDI with the title and "Made in Studio, yyyy-MM-dd HH:mm:ss" (so no two files are
  the same); `library.add(<title>.mid, bytes, title, "Made in Studio")`, then `describe(id, "Made in
  Studio · <date>")` (the date `FormatStyle.MEDIUM` in the device's locale).
- `AppStudioLibrary.add`: an `OpenedSource` of one file with an `INDEX.csv` row naming title and
  composer, through `Importer.importOpened` (the importer's caps, dedup and progress); the id found
  by the file's SHA-256. `discard`: `Player.forget(id)` on the main thread (its queue entries go;
  if loaded, the piano is silenced and the player empties), then the library's delete (the file too)
  and the artwork row's.
- `ComposerNames.STUDIO` keeps "Made in Studio" whole; `ArtworkFetcher` never looks it up; the
  artwork row made by `describe` has no source, so the piece sheet shows the line without "From
  Wikipedia" or Wikipedia's credit (`PieceDetailSheet`: `fromWikipedia` only with a source).
- `StudioReview`: the undecided pieces (DataStore "studio", key "undecided"; read back at start less
  those no longer in the library, deleted from its menu meanwhile: `StudioLibrary.exists`); a piece counts as heard
  when, loaded and playing, its position reaches 15 s (or its end less 50 ms), read every 250 ms and
  only after a position under that has been read (a new piece's state arrives before its clock:
  found on the emulator, the last piece's position counted); `asking` = the loaded piece when
  undecided and heard; `keep`, `discard`.

## Jobs and the service (`studio/Studio.kt`, `StudioJobs.kt`; `service/StudioService.kt`)

- `StudioJobs`: a `StateFlow` of the jobs waiting and running and the last 20 that ended.
- `Studio`: `download(model)`, `transcribe(source, name)` (queues the model's download first when
  it isn't installed), `cancel(id)`, `remove(model)`; one job at a time in the order asked, through
  a channel, on `studioThread` (a single thread at `THREAD_PRIORITY_BACKGROUND`: ORT's pool, made
  from it, takes its priority); a partial wake lock ("StevenPiano:studio", at most 30 min) around
  each; a job's end published after its recording is given back (`AudioSource.Local` deleted, a
  document's persisted grant released). Steps: Waiting, Downloading, Reading, Transcribing, Saving.
  The log line (tag Studio), also on the link's trail (`trail = LinkLog.shared::add`, so Share
  diagnostics' `link.log` carries the tablet's figures; no name in it): "Studio: transcribed 180.0 s
  of audio in 69.3 s (read 12.6 s, 35 windows, model 56.7 s), 1231 notes, 146 pedal; peak VmHWM
  750112 kB".
- `StudioService` (`dataSync`, not exported): started (`startForegroundService`) whenever a job is
  queued; follows `jobs`: the job running (else waiting) as a low-importance, silent notification on
  channel "studio" ("Transcribing <name>", its line, a determinate bar or an indeterminate one while
  reading, **Cancel** → `ACTION_CANCEL` to the service), at most every 400 ms unless the job or step
  changes; when none is left, the foreground notification goes and one line stays ("<title> is in
  the library" / "Listen, then keep it or discard it." opening the Library; "The transcription model
  is installed"; "… didn't finish" with the reason); `onTimeout` (both overloads) cancels every
  job. A refused start (`ForegroundServiceStartNotAllowedException`) leaves the jobs running in the
  process without the notification.

## Playback timing (`player/PlaybackEngine.kt`, `Player.kt`)

`PlaybackTiming`, owned by the scheduler thread: per batch, the lateness of its earliest event
against its time, in real microseconds (`(position − its time) × 100 / tempo`), the latest and the
events counted; a run ends at the piece's end, a stop or another load. `Player.publish` writes the
run to the link's trail (`LinkLog.warn`: logcat tag PianoLink and `link.log`) after the state:
"Timing: 3059 events, the latest 6 ms after its time, at 1:15.5" (where in the piece the latest
was). No title.

## UI

- `SettingsPage.Studio` ("studio", "Studio"), CONTROL after Kiosk (`HubGroups`); its value
  `StudioCopy.hub` in `GroupSummaries.studio`; where Studio can't run the row is `UnsupportedRow`
  (the reason, no chevron, nothing to open). `StudioPage`: MODELS (`ModelRow`), TRANSCRIBE
  (`ActionRow` with the picker, the memory line as a `NoteLine`), JOBS (`JobRow`, newest first,
  `Listen` → `NavHost`'s `listen`: play the piece alone and open Now playing). Locked in kiosk mode
  as every page (`LockedPage`).
- The picker: `OpenDocument` with `audio/*` and `application/ogg`; the grant taken persistable for
  the job and released after.
- `AddSheet(..., transcribe: TranscribeEntry?)`: the row below a hairline, only when `Available`;
  the sheet opens fully expanded (`skipPartiallyExpanded`).
- `StudioBar` under the Library's import bar (`StudioCopy.libraryLine`, the running job's
  measured progress). `StudioReviewBanner` (an `OutlinedBanner`, Keep / Discard as text buttons,
  Discard through the kiosk gate) on Now playing and `NowPlayingPanel`, where playback's problems
  show. About: `StudioCopy.MODELS_CREDIT`.

## Web (`/api/studio/*`)

- The state's `studio`: `{available, reason, models: [{name, title, sizeBytes, licence, installed,
  line, progress}], jobs: [{id, kind, name, state, line, progress, title}]}`; reading it asks the
  device (once per process), `available` true while that runs (the upload waits for the answer and
  refuses then). `WebService` pushes a state when the jobs, the models, the undecided set or the
  support change.
- `PUT /api/studio/audio?name=<file>` (write, untimed): the extension in `AUDIO_EXTENSIONS` (wav,
  wave, mp3, m4a, mp4, aac, flac, ogg, oga, opus, webm, 3gp, amr; else 415), `Content-Length`
  required and no chunked body (411), at most `AUDIO_BYTES` 200 MiB (413), not empty (400), Studio
  able to run (409 "unavailable" before a byte is read), one upload at a time (the uploads' lock,
  409 "busy"), streamed to `cacheDir/web/studio-<random>.<ext>` with the free-space margin (507);
  then `transcribeUpload` queues the job: 202 `{name, job}`. `POST /api/studio/jobs/{id}/cancel`
  (write): 204, or 404 for a job unknown or ended. The write matrix in `WebServerTest`: 24 routes.
- The page (`app.js`): the ninth section, `#studio`; `zoneFor` and `uploadRow` shared with Add, each
  upload carrying its route, its list and its words; the client checks the extension, size and
  emptiness first; a 409 "busy" waits and retries, a 409 "unavailable" is refused in the tablet's
  words.

## Greps (v1.7 — M23)

As in M19: no `Color(0x` outside `ui/theme`; no `0.0.0.0` or `Access-Control` in `app/src/main`;
`DisplayBlack` in `Color.kt` and `Theme.kt`; `LocalNoteSounding` in `Theme.kt` and `ScorePages.kt`;
Haze in `Glass.kt` (sources `Glass.kt`, `NavHost.kt`, `NotePanel.kt`); no `Modifier.blur`; no pure
black or white in `assets/web` (only `white-space`). New: `ai.onnxruntime` only in `studio/` (and
the R8 rule and the build's check); no `TelemetryInitializer` in any merged manifest.

## Measured (September 2026, `steven_piano`, API 34, arm64, 4 GB, debug build, the emulated piano)

The AVD booted headless with the emulator binary (`-memory 4096 -no-snapshot-save`, port 5556);
the models served from the Mac (a local HTTP server throttled to 6 MB/s, its `models.json` naming
`http://10.0.2.2:8766/<file>`; `debug.stevenpiano.modelsurl` pointing at it), since the GitHub
repository is still private. Nearby devices granted with `pm grant` so the emulated piano
connects (revoked after).

- **Tests**: 1,085, none failing (998 before; 9 skipped without `-PstudioModels`: the corpus tests,
  `PinnedKeyTest`'s firmware header, `TranscriberTest`'s real model). With
  `-PstudioModels=<the spike's exports>` `TranscriberTest`'s real-model case runs and passes (ORT
  1.28.0 for the JVM, the fixture window's outputs within 1e-2, the fixture's notes). `lint`: 0
  errors, 28 warnings (29 before). `check`, `checkDebugOnnxTelemetry` and
  `checkReleaseOnnxTelemetry` pass; `assembleDebug`, `assembleRelease` clean, no compiler warnings
  in the app's sources. **The release APK is 13,417,624 bytes** (1.6.2: 2,837,804), the debug APK
  28,511,948 (16,537,905); `lib/` holds `arm64-v8a` only (`libonnxruntime.so` 28,637,280 bytes and
  its JNI 111,648, deflated; androidx's two small libraries, which shipped for four ABIs before).
- **Download**: Piano › Studio › Transcription › Download: the list, then 124,511,036 bytes in
  21.3 s, "Studio: transcription-v1.onnx downloaded and verified"; "Downloading · 23 of 125 MB" on
  the page and in the job, the notification's bar, then "Installed · 125 MB · CC BY 4.0" and the
  line "The transcription model is installed".
- **The fixture** (`tools/studio/fixtures/transcription_window.wav`) through Library › + ›
  Transcribe a recording…: 10.0 s of audio in 2.3 s (read 0.1 s, one window, the model 2.2 s with
  its session), **84 notes and 1 pedal event: the fixture's**.
- **Three minutes of `bach_bwv846.mid`** (the fixture MIDI twice through, 1.5 s apart, then silence
  to 180.0 s; TinySoundFont with FreePats' Upright Piano KW; 44.1 kHz stereo 16-bit WAV, 31.75 MB,
  and AAC in m4a by `afconvert`, 128 kb/s, 2.58 MB; 778 written notes) **while the Chopin études
  played** (651, the emulated piano connected):
  - The m4a sent through the panel's API eight times, nothing touching the screen: **65.2–69.3 s**
    each (reading and decoding 11.1–12.7 s, the model 54.0–57.5 s for 35 windows: the spike's
    57.4–59.0 s), 1,231 notes and 146 pedal events every time. The process's resident set
    242–274 MB before each run, **744–774 MB at its peak** (VmRSS every 3 s; VmHWM 750,112–776,220
    kB), about 300 MB after; no growth from run to run.
  - The WAV through the + sheet while screenshots were taken: 67.2 s (read 1.2 s, the model
    66.0 s), 1,285 notes, 128 pedal events; `dumpsys meminfo` every 5 s: **TOTAL RSS 764,356 kB,
    TOTAL PSS 639,306 kB at the peak** (262–275 MB and 138–151 MB before).
  - **Against the Mac** (the INT8 model through onnxruntime there, librosa's resampler, the
    package's own post-processor): the reference finds 1,255 notes in the WAV, all 778 written ones
    (recall 100 %, precision 62 %: SoundFont audio gets ghost re-attacks, as the spike found). The
    app's WAV transcription agrees with it at F1 98.1 % (the resamplers differ), the m4a's at
    96.1 % with onsets 0.0 ms apart on average (AAC's priming handled by the extractor); both find
    all 778 written notes.
- **Playback while transcribing** (the Timing lines, each a piece played through the API for
  75–95 s, then Stop; nothing touching the screen):

  | Runs | Latest lateness per run (ms) | Median |
  |---|---|---|
  | 8 with the m4a transcribing | 9, **101**, 6, 5, 17, 7, 6, 5 | 6.5 |
  | 8 without Studio | 5, 9, 6, 6, 22, 12, 42, **298** | 10.5 |

  About 3,000 events a run. No systematic lateness from Studio; the emulator itself stalls now and
  then (a 298 ms batch with nothing else running, at 0:29.8 of the piece, beside a Wi-Fi beacon
  loss in the log), and the one long batch during a transcription (101 ms) came before the "at"
  was added, so where it fell is unknown. The tablet's own figures are the real test (README ›
  Studio › On the piano).
- **Other recordings**: the panel's page sent Gymnopédie No. 1 (Wikimedia Commons, CC0) as Ogg
  Vorbis (44.1 kHz stereo, 204.8 s) and as FLAC (22.05 kHz stereo, no length in its header) one
  after the other, a `.mid` refused in the page: 184.8 s and 125.2 s (headless Chrome and a second
  emulator busy on the Mac); against the Mac's reference F1 97.4 % (652 / 641 notes) and 98.4 %
  (612 / 601). A MIDI file picked on the tablet: "That's a MIDI file already. Add it with Add
  files."
- **Keep or Discard**: Listen played the piece and opened Now playing; the banner came 15 s in;
  Keep ended it. Before `eafbd84` an undecided piece loaded after 20 s of another was asked about
  at once; after it, 3 s in no banner, 19 s in the banner. Discard (the fixture's 10 s piece)
  silenced the piano, emptied Now playing ("Choose a piece from the library.") and took the piece
  and its file out of the library.
- **Cancel** from the page's job and from the notification's action: "Cancelled", no result line.
- **The gates** (`debug.stevenpiano.studio`): `noruntime` — the hub's row "Studio isn't available on
  this device" without a chevron, the + sheet without its row, the panel's page the line alone;
  `lowmem` — "This tablet doesn't have enough memory for Studio"; `busy` — a transcription sent
  from the panel ended at once with "Close other apps and try again.", under TRANSCRIBE and in the
  job.
- **Screens** (`m23-shots/`): the hub's row (No models, 1 model, Transcribing 5%, the two reasons),
  the page (no models, downloading, installed, a job transcribing with Cancel, Cancelled and Ready
  with Listen, MIDI refused, the memory line), the + sheet with and without the row, the picker,
  the Library's line while transcribing, the notifications (downloading; transcribing, expanded
  with Cancel; the result), Now playing with the banner, the pieces by Made in Studio with their
  roll cards, the piece sheet, About's credit; the panel's page at 1280 and 390 px, light and dark,
  sending, transcribing, done, and unavailable.

## Deviations from the plan, and why

- **arm64 only is the whole app's ABI.** `abiFilters` (binding) applies to every native library,
  so androidx's two small libraries, shipped for four ABIs until 1.6.2, now ship for arm64 only,
  and the APK no longer installs on 32-bit ARM or x86 devices without an ARM translation layer.
  The plan's "on x86_64 Studio hides" holds only on such devices (as on x86_64 emulator images,
  which translate). Keeping the app on every ABI while shipping ONNX Runtime for arm64 alone would
  be `packaging.jniLibs.excludes` for ORT's other ABIs instead of `abiFilters`; not done, as the
  brief binds the filter. Steven's tablet is arm64. (M24 made that change: v1.7 — M24 › The build.)
- **One service**, `StudioService`, for downloads and transcriptions (the plan offered a
  `ModelDownloadService`; the brief preferred one).
- **Timing lines** (`PlaybackTiming`): the link's trail had no measure of lateness, so the brief's
  measurement needed one; it stays, one line a piece played, readable in Share diagnostics.
- **Asked lazily**: whether Studio runs is asked the first time something shows it, never at start
  (the check maps ORT's 28 MB library).
- **A second memory gate** between windows (128 MiB; the brief's "peak-memory guard"), with its own
  line.
- **The hub's values** add "1 model", "Downloading 34%" and "Waiting" to the plan's three.
- **The + sheet** opens all the way up (it opened half up on a phone, cutting Studio's row), and
  the Library shows the job running under its import bar: a transcription started there showed
  nothing for a minute. Neither was in the plan; both seen on the emulator.
- **MIDI files** are refused with their own line: Android's picker offers them as audio, and
  `MediaExtractor` would render one with its synthesizer and transcribe that.
- **Keep or Discard** "after the first listen" is 15 s of the piece or all of it; the undecided
  pieces persist across restarts; Discard goes through the kiosk PIN in kiosk mode (it deletes).
- **The MIDI file's text event is ASCII** ("Made in Studio, <time>"): other programs read text
  events as Latin-1; the title stays UTF-8, as the app's parser reads it.
- **The web panel shows the models, never downloads them**: 125 MB and 173 MB go over the tablet's
  own connection, started on the tablet.

## Residuals

- **Nothing measured on the tablet yet**: time, memory and the player's timing are the emulator's
  (on a Mac running a second emulator). README's Studio checklist covers the tablet.
- **The repository is private**: the models' addresses answer 404 until it is public, so Download
  says "Couldn't reach the download server." there; downloads were tested only against the local
  server. Nothing changes when the repository becomes public.
- **Synthesized piano gets ghost notes** (the model hears held notes struck again); real
  recordings did not show this in the spike.
- **Jobs live in memory**: a job cut off with the process (Android ending it, the app updated) is
  not resumed; a picked document's grant then stays with Android until it is next released. A piece
  deleted from the Library's menu while its job reads "Ready" keeps that job's Listen until the app
  restarts.
- **Titles repeat**: the same recording transcribed twice makes two pieces of the same title, as
  two imports of same-titled files do.
- **Without the notification permission** (Android 13+, refused) jobs run with no notification;
  the page and the Library still show them.

## Tests added in M23

`MemoryGateTest` (4), `UpdateSourceTest` (+4: the models' list, files and hops; no crossing with
the app's or the firmware's), `ModelManifestTest` (7), `ModelStoreTest` (5), `VerifiedDownloaderTest`
(6, the updater's fake server), `ModelInstallerTest` (4), `ResampleTest` (7: a 1 kHz tone kept;
9–15 kHz gone rather than folded, below −60 dB, from 22.05, 44.1 and 48 kHz; 6 kHz passes; 8 kHz up
with no image; 16 kHz untouched; any pieces, the same output; silence and DC), `AudioDecoderTest`
(9: the fixture window exactly; 44.1 kHz stereo to 16 kHz mono; eight sample formats; unknown data
sizes and a file cut short; the 20-minute cap before and during decoding; refusals in words;
cancelling; a trickling stream; MIDI known by its first bytes), `NotePostProcessorTest` (6: the
fixture's 84 notes and pedal exactly, in order; the same answer in any pieces; a note's end; the
600-frame cut; peaks and their shifts; the pedal), `TranscriberTest` (4: windows; the deframe;
cancel and memory between windows; the real model on the JVM when `-PstudioModels` is given),
`SmfWriterTest` (4: notes, pedal, title and text round-trip through `SmfParser`; a tick at least,
velocities 1–127, a key struck again as it ends; long silences and an empty piece; the bytes),
`StudioPiecesTest` (3), `StudioReviewTest` (6: heard at 15 s, Keep; Discard; a short piece at its
end, a paused one not listened to; read back after a restart; the last piece's position not
counting for the next; pieces deleted meanwhile no longer waiting), `StudioTest` (7: the model downloaded first, then the piece; one at a time,
in order; the gates, the recording and a silent result refused in words, the recording given back;
no runtime; memory running short between windows; cancel waiting and running; a download needs the
network, and a model in use can't be removed; the figures on the trail), `StudioCopyTest` (5),
`PlayerTest` (+1: `forget`), `PlaybackEngineTest` (+1: timing), `ComposerNamesTest` (+1),
`PieceNotesChoiceTest` (+1), `GroupSummariesTest` (+1), `WebServerTest` (+1: the upload's refusals
and its 202, cancel; the write matrix at 24 routes); `WebApiTest`, `WebAssetsTest`, `RoutesTest`,
`PianoPagesTest` changed. 998 tests before, 1,085 after.

# v1.7 — M24: Studio, part 2 — composing on the tablet; release 1.7 (versionCode 14)

Read `DESIGN.md › v1.7 — M24` first. Plan: `~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› item 13 and "M24 — Studio composing"; the contract is `docs/STUDIO_SPIKE.md` › The contract for M23
and M24. The engine was built on the branch `m24-composer` and merged at `3591813`; the rest on `main`,
a commit a step. **No version bump in this run**: 1.7 was released after the security audit's second
delta (The release: 1.7, at the end of this section).

## The build: ONNX Runtime alone is arm64

M23's `defaultConfig.ndk.abiFilters += "arm64-v8a"` made arm64 the whole app's only ABI, so
androidx's two small native libraries, shipped for four ABIs until 1.6.2, went with it and the APK no
longer installed on 32-bit ARM or x86 devices. Now the filter is gone and `packaging.jniLibs` leaves
out ONNX Runtime's libraries for the other three ABIs alone (`**/armeabi-v7a/libonnxruntime*.so`,
`**/x86/libonnxruntime*.so`, `**/x86_64/libonnxruntime*.so`: `libonnxruntime.so` and its JNI,
`libonnxruntime4j_jni.so`); `useLegacyPackaging` stays (the libraries travel deflated). Where the
runtime isn't there, `OrtEnvironment` fails to load and Studio hides itself, as on any device it
can't run on (`StudioAvailability`). `app/lint.xml` no longer ignores `ChromeOsAbiSupport` (nothing
reports it now). `unzip -l` of the release APK (13,441,796 bytes; 13,417,624 with arm64 alone):

| Entry | Bytes |
|---|---|
| `lib/arm64-v8a/libonnxruntime.so` | 28,637,280 |
| `lib/arm64-v8a/libonnxruntime4j_jni.so` | 111,648 |
| `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libandroidx.graphics.path.so` | 10,096 · 7,252 · 9,284 · 10,760 |
| `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libdatastore_shared_counter.so` | 10,360 · 8,432 · 7,976 · 9,424 |

No `libonnxruntime*` outside `arm64-v8a`. `lint`: 0 errors, 28 warnings; `checkOnnxTelemetry` passes.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- The engine (branch `m24-composer`, merged at `3591813`): `M/studio/compose/AmtTokenizer.kt` (`Amt`,
  `AmtEvent`, `SeedNote`, `AmtTokenizer`), `PromptBuilder.kt` (`SamplingSettings`, `Mood`, `MusicKey`,
  `SeedPiece`, `SeedFacts`, `ComposeRequest`, `Prompt`, `PromptBuilder`), `ComposerModel.kt`
  (`ComposerModel`, `OrtComposerModel`), `Sampler.kt` (`Stop`, `Generation`, `Sampler`),
  `Postprocess.kt` (`Composition`, `Postprocess`), `ComposeFailures.kt`.
- `M/ui/screens/piano/pages/ComposeSheet.kt`; `M/ui/components/SheetChip.kt` and `PieceSearch.kt`
  (`PieceSearch`, `SheetChoiceRow`, `SheetNote`, `pieceMeta`: the schedule editor's, now shared).
- Tests: `T/studio/compose/AmtTokenizerTest`, `PromptBuilderTest`, `SamplerTest`, `PostprocessTest`,
  `ComposerTest` (the real model when `-PstudioModels` names the spike's exports, else skipped),
  `ComposerFixtures`.

Changed: `app/build.gradle.kts`, `app/lint.xml`; `M/midi/SmfWriter.kt` (a tempo); `M/studio/Studio.kt`
(`SeedSource`, `ComposeOrder`, `SeedChoice`; `compose`, `seedChoice`), `StudioJobs.kt`
(`JobKind.Compose`, `JobStep.Composing`), `StudioAvailability.kt` (`MemoryGate.COMPOSING_FREE_BYTES`,
`canStartComposing`), `ModelCatalogue.kt` (the composer's use), `ModelInstaller.kt` (`NO_COMPOSER`,
`COMPOSER_DAMAGED`), `StudioPieces.kt` (`addComposition`, `compositionTitle`,
`compositionDescription`, `mannerOf`), `AppStudioLibrary.kt` (`LibrarySeeds`); `M/AppGraph.kt`;
`M/data/db/PieceDao.kt` (`seedPiece`), `M/data/LibraryRepository.kt` (`seedPiece`);
`M/service/StudioService.kt`; `M/ui/StudioCopy.kt`; `M/ui/screens/piano/pages/StudioPage.kt`;
`M/ui/screens/library/AddSheet.kt` (`StudioEntry` for `TranscribeEntry`), `LibraryScreen.kt`;
`M/ui/screens/nowplaying/StudioReviewBanner.kt`; `M/ui/screens/schedule/ScheduleEditorSheet.kt` (the
shared chip and search); `M/web/WebBackend.kt` (`StudioCompose`), `WebApi.kt`, `WebServer.kt`,
`AppWebBackend.kt`; `assets/web/app.js`, `style.css`; tests `SmfWriterTest`, `StudioTest`,
`StudioCopyTest`, `MemoryGateTest`, `StudioPiecesTest`, `FakeWebBackend`, `WebServerTest`,
`WebApiTest`, `WebAssetsTest`; `DESIGN.md`, `README.md`, `docs/SECURITY_AUDIT.md`.

## The engine (`studio/compose/`)

- `AmtTokenizer`: the vocabulary of `anticipation` at af37397 (time 0–9,999 and duration 0–999 in
  10 ms ticks, note 11,000 + 128 × instrument + pitch, REST 27,512, controls 27,513–55,024 and the
  specials 55,025–55,027 never produced) and a port of `midi_to_events`, `clip`, `pad`, `unpad`,
  `translate`, `min_time`/`max_time`, tokens and `decode`. Times as mido makes them: each gap's
  seconds summed in floating point over the parsed piece's events and rounded half to even (the Bach
  fixture's key 62 at exactly 707.5 ticks is 707, as the package wrote it); a gap whose sum departs
  from the parser's microseconds by over 2 µs held a tempo change and takes the parser's time.
  Channel 10 is left out and every note is the piano's. The fixture's 214-token seed comes out
  token for token.
- `PromptBuilder`: the seed is the piece's first 15 s from its first note, time-scaled to the chosen
  tempo (the seed's own tempo, rounded, leaves it untouched), transposed to the chosen key by
  signature (up or down: fewer notes off 24–107, then the smaller move, then toward the middle),
  folded into 24–107, padded with rests, after AUTOREGRESS. `facts`: the key from the file's key
  signature in force at the seed's start (its major or relative minor, by the notes' Krumhansl–
  Kessler profile), else from the notes over all 24 keys; the tempo as the tempo map's beats over
  the 15 s. Moods: Calm 0.8 / 0.9, Bright 1.0 / 0.95, Wild 1.15 / 0.98, Melancholy 0.85 / 0.9
  (temperature / top-p), with velocities 46, 66, 78, 52 and spreads 8, 12, 18, 10. Budget: 2,700
  tokens a minute (`TOKENS_PER_SECOND` 45; 1,800 until the 1.7 release), at most 9,000; the end time
  the seed's 15 s plus the length.
- `OrtComposerModel`: `composer-v1.onnx` with transcription's session options; `input_ids`,
  `attention_mask`, `position_ids` and the 24 `past_key_values`, `present.*` fed back as the next
  past, the logits into one pinned buffer; at most 1,024 positions.
- `Sampler`: the package's masks (no controls or specials; a time from the current time on, a
  duration, and a piano note or a rest), NaN and infinities masked; temperature, then top-p
  (Hugging Face's rule); greedy = argmax. Guards: no more than 4 identical notes in a row and
  `MAX_RESTS` 1 rest in a row; no key struck again within `SAME_KEY_TICKS` 12 (120 ms); at most
  `MAX_CHORD` 10 notes at one instant (then the time must move on); a rest where no key is left. The
  window slides before an event that wouldn't fit, or 90 s past its origin: the last 170 events
  (none over 50 s back), re-based, prefilled after AUTOREGRESS. The cancel before every token, the
  memory every 100 tokens and before a slide, progress (tokens, and the larger of the budget spent
  and the music written toward the end time) every 100 tokens and at the end. Stops at the budget,
  the end time, or SEPARATOR when `allowEnd` opens it (never, as the moods have it).
- `Postprocess`: rests out, notes at least 60 ms; each onset half-way to its sixteenth at the chosen
  tempo; the piece from its first beat; keys folded into 24–107; a key's strike under
  `SAME_KEY_MICROS` 120,000 after its last joins it, a note held into its key's next strike ends
  there; velocities by mood (the top of a chord up, the bottom and inner voices down, a register
  tilt, a four-bar swell, a touch of the job's Random) with the last two bars faded to 45 %, held
  to 20–110; no pedal.

## The job (`studio/Studio.kt`, `StudioPieces.kt`, `AppStudioLibrary.kt`)

- `Studio.compose(ComposeOrder(pieceId, ComposeRequest), name)`: a `Compose` job, after the composing
  model's download when it isn't installed. On the job thread: Studio's support; the model opened
  (its hash once per process; `NO_COMPOSER`, `COMPOSER_DAMAGED`); `MemoryGate.canStartComposing`
  (700 MiB above the threshold, not `lowMemory`; else `BUSY`); the seed from `SeedSource` (the piece
  named, else `defaultPieceId`: `PieceDao.seedPiece`, the piece played last whose composer isn't
  "Made in Studio", else the first by title; `EMPTY_LIBRARY`, `SEED_GONE`, `NO_SEED`); the prompt;
  `Sampler(model, Random(seed), cancelled, memoryHolds = canContinue)` with the job's progress
  (`JobStep.Composing`); `Postprocess` of the generated events only; `addComposition`; `review.made`.
  A failure's line by kind (`ComposeFailures.RAN_OUT`, `FAILED`). The composer can't be removed while
  a composition waits or runs. The figures (log, and the link's trail for Share diagnostics; no
  title): "Studio: composed 121.1 s of music in 5.2 s (912 tokens, 5.7 ms a token, 2 slides, stop
  endtime), 235 notes at 68 bpm, calm, 2 min; seed 1836982538541; peak VmHWM 577108 kB".
- `seedChoice(pieceId)`: the seed and its facts, for the sheet and the panel, off the main thread.
- `StudioPieces.addComposition`: title "Composition · Sep 28, 2026 2:05 PM" (`FormatStyle.MEDIUM`
  date and `SHORT` time in the device's locale), composer "Made in Studio", the MIDI written at the
  composition's tempo (`SmfWriter.tempoOf`) with the text "Made in Studio, yyyy-MM-dd HH:mm:ss",
  imported through `importOpened`, then described "Made in Studio · in the manner of <title>
  (<composer>)". `LibrarySeeds` reads a seed through `LibraryRepository.load` (the player's parser).
- `SmfWriter.write(..., tempoMicros)`: the one tempo and every tick at it; 120 bpm by default (M23's
  files byte for byte as before).

## UI

- `ComposeSheet` (from the Studio page's COMPOSE and the Library's + sheet): MOOD, KEY (twelve
  `SheetChip`s in the mode's spelling, Major · Minor), TEMPO and LENGTH (`StepperRow` +
  `StepperButtons`, repeating while held), IN THE MANNER OF (`PieceSearch` behind Change); the key
  and tempo follow the seed until changed (and the key the mood's suggestion); Compose passes the
  seed's id and title.
- `StudioPage`: COMPOSE after TRANSCRIBE (`StudioCopy.COMPOSE`, `COMPOSE_NOTE`, `withDownload`), its
  memory line; job titles by `StudioCopy.jobTitle` ("In the manner of Clair de lune" until the piece
  exists). `AddSheet(..., studio: List<StudioEntry>)`: Transcribe and Compose under one hairline.
  `StudioReviewBanner`: `StudioCopy.reviewLine` from the piece's artwork row (a composition's line
  says what it is in the manner of). `StudioCopy.hub`: "Composing 42%".

## Web (`/api/studio/compose`, `/api/studio/seed`)

- `POST /api/studio/compose` (write): `WebApi.composeOrder`: only `pieceId` (an id, or absent/null:
  the default seed), `mood` (calm, bright, wild, melancholy), `key` (`{tonic: 0–11, minor}` or null),
  `bpm` (40–200 or null), `minutes` (1–5); anything else 400. Studio unable to run: 409
  "unavailable"; the piece not in the library: 404; else 202 `{job}`. The write matrix: 25 routes.
- `GET /api/studio/seed[?piece=<id>]` (read): `{pieceId, title, composer, key: {tonic, minor,
  label}, bpm}`; 404 without a piece, 400 for a malformed id.
- The page (`app.js`): `renderCompose`, `composeEditor`, `stepperField` + `holdToRepeat` (the value
  changes in place so a held button keeps its hold), `seedPart` (the search as the schedule editor's),
  `sendCompose`; the styles shared with the schedule editor (`.compose-editor`, `.compose-start`).

## Greps (v1.7 — M24)

M23's hold: no `Color(0x` outside `ui/theme`; no `0.0.0.0` or `Access-Control` in `app/src/main`;
`DisplayBlack` in `Color.kt` and `Theme.kt`; `LocalNoteSounding` in `Theme.kt` and `ScorePages.kt`;
Haze imported in `Glass.kt` only; no `Modifier.blur`; no pure black or white in `assets/web`;
`ai.onnxruntime` only under `studio/` (the compose package included), the R8 rule and the build's
check; no `TelemetryInitializer` in any merged manifest.

## Measured (September 2026, `steven_piano`, API 34, arm64, 4 GB, debug build, the emulated piano)

Booted headless as M23 (`-memory 4096 -no-snapshot-save`, port 5556), the models from the Mac's
local server (6 MB/s, `debug.stevenpiano.modelsurl`), Nearby devices granted for the emulated
piano (revoked after). The Mac was busy throughout (load average 8–13; a second emulator running),
which the per-token times show.

- **Tests**: 1,137, none failing (1,123 before; 12 skipped without `-PstudioModels`, 8 with it: then
  `ComposerTest`'s three and `TranscriberTest`'s real-model case run and pass, the INT8 fixture's 64
  greedy tokens exactly). `lint`: 0 errors, 28 warnings. `check` passes (`checkDebugOnnxTelemetry`,
  `checkReleaseOnnxTelemetry`); no compiler warnings in the app's sources. **The release APK is
  13,471,172 bytes**, the debug APK 28,777,600; `lib/` as in *The build* above.
- **Download**: Piano › Studio › Composing › Download: "Downloading · 36 of 173 MB", then
  "Studio: composer-v1.onnx downloaded and verified", "Installed · 173 MB · Apache 2.0".
- **The seeds**: Clair de lune (Suite bergamasque's MIDI, 733 tempo changes) D♭ major, 68 bpm (F
  minor before `cf3bff3`); Für Elise A minor, 69 bpm (its file says C major).
- **Two minutes of Calm in the manner of Clair de lune**, from the sheet (the seed the default, the
  piece played last): 121.1 s of music in **5.2 s** (912 tokens, 5.7 ms a token, 2 slides), 235
  notes at 68 bpm, velocities 20–55; the process 226,788 kB before, **577,108 kB at its peak**
  (VmHWM; `dumpsys meminfo` TOTAL RSS 531,212, PSS 412,854 kB mid-way), about 245 MB after. On the
  final build, a fresh process with the Mac at load 10: 119.7 s of music in 32.1 s (1,920 tokens,
  16.7 ms a token), 583 notes, 256,724 → **630,932 kB**.
- **One minute of Wild in the manner of Für Elise**, from the web panel's form (Für Elise found
  through its search): 61.2 s of music in **9.5 s** (861 tokens, 11.0 ms a token, 1 slide), 280
  notes at 69 bpm; 224,876 → **634,524 kB** (TOTAL RSS 634,128, PSS 517,035). On the final build,
  load 13: the 1,800-token budget ran out at 42.3 s of music (570 notes, 13.5 a second) in 30.9 s,
  212,840 → 630,908 kB.
- **Five minutes** (Wild, Wild, Melancholy): 301.1 s in 24.6 s (3,861 tokens, 6 slides, 1,268
  notes), 301.8 s in 12.8 s (2,499 tokens), 300.4 s in 25.4 s (4,596 tokens, 8 slides, 1,532 notes);
  peak 634,624–638,208 kB.
- **The model's ruts** (found here, measured on the Mac with the real model and the real Sampler,
  Clair de lune's seed, Calm, two minutes, ten random seeds): the first build's piece had 88 notes
  and 83 one-second rests in 116 s. Before the guards, 3 of 10 runs were rest-bound (0.59–1.45 notes
  a second) and some restruck a few keys at one instant (1,174–1,195 notes in 13–59 s of music, the
  100 ms rule keeping 42–555 of them, the budget spent: a "two-minute" piece of 13–64 s). With the
  guards, thirty runs (Clair de lune Calm and Wild two minutes, Für Elise Wild one minute) all stop
  at their end time with the length asked (117–130 s, 60–61 s), 2.4–7.3 notes a second in Calm,
  1.6–6.9 in Wild, 3.1–7.2 for Für Elise, at most 2,610 of 3,600 tokens.
- **Same-key onsets**: the guards build's five files had 0 under 100 ms but one of 99 ms (a 100 ms
  gap rounded to ticks at 68 bpm), hence the 120 ms margin (`883443d`). Played through the emulated
  piano (every message in logcat, tag PianoLink): the Calm piece **235 Note Ons for its 235 notes,
  the closest two strikes of a key 658 ms apart, none under 100 ms**, "Timing: 470 events, the
  latest 3 ms after its time"; the final build's dense Wild piece **570 Note Ons for 570 notes,
  closest 169 ms, none under 100 ms**, "Timing: 1140 events, the latest 15 ms after its time"
  (nothing thinned by the player's guard). The largest chord in any file: 7 notes.
- **Keep or Discard**: Listen, then 15 s in "Keep this piece? Composed in Studio in the manner of
  Clair de lune (Claude Debussy). Discard deletes it."; Discard emptied Now playing and took the
  piece out of the library; Keep ended the question. The piece sheet: the roll card and "Made in
  Studio · in the manner of Clair de lune (Claude Debussy)", no Wikipedia line.
- **Progress**: the Library "Composing in the manner of Clair de lune · 3%", the hub "Composing
  33%", the notification "Composing in the manner of Clair de lune" with its bar, the page
  "Composing · 19%", then the result "Composition · … is in the library" / "Listen, then keep it
  or discard it."
- **Cancel** mid-way (19%) from the job's row: "Cancelled", no figures, nothing added.
- **The panel**: the form at 1280 px (light and dark) and 390 px (no horizontal scroll), the default
  seed, the search, Wild and one minute, then "Composing on the tablet. It shows under Jobs." and
  the job composing and ready. Its console: the 401 before signing in and the favicon's 404, as in
  M23.
- **Screens** (`m24-shots/`): the hub (No models, Composing 33%), the Studio page (COMPOSE without
  the model, downloading, installed, composing, ready, cancelled), the notifications (downloading,
  composing, the result), the sheet (top, the search, the end, Wild and 5 min), the + sheet, the
  Library's line and its compositions with roll cards and the mini player, Now playing, Keep or
  Discard (both), after Discard, the piece sheet; the panel's page (`web01`–`web07`).

## Deviations from the plan and the brief, and why

- **The sampler's guards** (one rest in a row, a key every 120 ms, ten notes at an instant) are
  not in the brief: the emulator's first piece showed the model's ruts, measured above; they steer
  the model, not the result, and leave the fixture's greedy tokens alone.
- **120 ms, not 100, between two strikes of a key** in a composition: the file's ticks and the
  player's clock can shorten a gap; the piano's 100 ms stays the player's own rule.
- **The seed's key from the file's key signature** when it has one (the notes choose the mode):
  Clair de lune read F minor from its notes alone.
- **The default seed skips Studio's own pieces**: after listening to a composition, the piece
  played last would otherwise be that composition.
- **The budget is 45 tokens a second, not the brief's 30** (raised at the 1.7 release): at 30 a dense
  piece could end short of its length (42 s of a one-minute Wild piece once, measured above); 45 lets it
  reach it, and 9,000 still caps a job, from three minutes on (2,700, 5,400, 8,100, then 9,000 and
  9,000 for one to five minutes). A job's time and memory stay bounded by the same 9,000.
- **Progress** reports the larger of the budget spent and the music written, so a piece that ends at
  its length reads 100 %, not the share of an unspent budget.
- **`GET /api/studio/seed`**: the panel's form needs the seed's key and tempo, as the sheet has them.
- **The sheet's parts from the schedule editor** (`SheetChip`, `PieceSearch`) moved to
  `ui/components`, shared rather than copied; the + sheet's `TranscribeEntry` became `StudioEntry`.
- **The Keep or Discard line** for a composition is read from its sheet's own line.
- **Cancel from the notification** was not seen on the emulator (a five-minute piece took 13–25 s,
  over before the shade opened); Cancel from the job's row was, and both reach `Studio.cancel`.

## Residuals

- **Nothing measured on the tablet**: time, memory and timing are the emulator's on a busy Mac.
- **The repository is private**: the models answer 404 until it is public (as M23).
- **A very dense piece can still be shorter than asked** at four or five minutes, where 9,000 tokens
  cap it; a sparse one can be thin (the guards keep it at 1.6 notes a second or more in the runs
  measured).
- **Jobs live in memory** (as M23); the panel composes without the kiosk PIN (its own PIN).

## Tests added in M24

The engine (branch): `AmtTokenizerTest` (10), `PromptBuilderTest` (8, now 9), `SamplerTest` (9, now
10), `PostprocessTest` (8), `ComposerTest` (3). On `main`: `SmfWriterTest` +1 (a tempo round-trips),
`StudioTest` +5, `StudioCopyTest` +2, `MemoryGateTest` +1, `StudioPiecesTest` +1, `PromptBuilderTest`
+1 (the key signature), `SamplerTest` +1 (the guards), `WebServerTest` +1 (compose and the seed; the
matrix at 25), `WebApiTest` +1 (16 bodies refused); `PostprocessTest`, `SamplerTest`,
`WebAssetsTest` changed. 1,084 before the branch, 1,122 after it; 1,123 on `main` before this part,
1,137 after.

## Audit (delta 2) — 2026-09-28

`docs/SECURITY_AUDIT.md › 1.7 — Studio: audit (delta 2)` records it in full: one Medium and seven Low
findings, all fixed; no model could be swapped, the runtime sent nothing, no hostile recording took the
app down. What changed, and where the build now differs from the notes above:

- **S1** (`8f32157`): a seed is read to `AmtTokenizer.MAX_NOTES` = 4,096 notes, into primitive arrays
  (`notes(piece, seconds, limit)`); a crafted 6 MB file of a million notes had the compose sheet allocate
  151 MB and a prompt 377 MB. `Studio.seedChoice` reads and parses on `Dispatchers.Default` and returns null
  rather than throw (an OutOfMemoryError included); `LibrarySeeds` takes an `SmfException` for no seed.
- **S2** (`e0baa5b`): saving a finished piece (import, line, review) is one `NonCancellable` step
  (`Studio.save`); a cancel from then on leaves the job **Done** and the piece undecided, where the notes
  above had "Cancelled" (which could orphan the piece in the library).
- **S3** (`8d5e4e4`): `outcome` ends a job failed on any `Throwable` but cancellation, not only
  `Exception` and `OutOfMemoryError`.
- **S4** (`7202ecc`): the panel's `PUT /api/studio/audio` and `POST /api/studio/compose` answer 409 "full"
  while `STUDIO_JOBS_MAX` = 8 jobs wait or run, before a byte of a recording is read. The tablet's own
  entries are not capped (a decision: one person picks its files).
- **S5** (`2da9e3c`): `VerifiedDownloader` deletes its part on any throw.
- **S6** (`3d1ca73`): `NotePostProcessor(maxNotes = MAX_NOTES)`, 200,000 notes, past which
  `StudioFailures.TOO_MANY_NOTES` ("More notes were heard in this recording than a piece can hold.").
- **S7** (`14340d9`): `OnnxTelemetryCheck` also fails unless every `com.microsoft.onnxruntime` module the
  variant's runtime classpath resolves is at `onnxRuntimePinned` = "1.28.0", set in `app/build.gradle.kts`
  (the version catalog's line alone no longer decides it); its report names the runtime.
- **S8** (`0c6db38`): `WavReader.decode(input, cancelled, fileBytes)` sizes a `data` chunk of unknown size
  from the file's length (`AudioDecoder` passes it): 90.5 MB of heap for a 20-minute file of unknown
  length, 158 MB before.
- Tests: `SeedLimitsTest` (2, new), `StudioTest` +3, `WebServerTest` +1 (and the guests' listener test
  walks every panel route, `241b8ae`), `VerifiedDownloaderTest` +1, `NotePostProcessorTest` +1,
  `AudioDecoderTest` +1, `StudioPiecesTest` +1 (`37f0daa`: what reaches the piano from a transcription).
  1,137 tests before the audit, **1,147 after**; with `-PstudioModels` 8 skipped (the corpus's seven and
  `PinnedKeyTest`'s firmware header), without 12. `check` passes; lint 0 errors, 28 warnings. At the 1.7
  release, after the budget's change (which changed two tests and added none), **1,147**.
- README (*Artwork and notes*, *Security*, *Studio*) and DESIGN.md (v1.7 — M23's refusals, the panel's
  Studio page) carry the new lines and the corrected network statements.

## The release: 1.7 (versionCode 14)

Released from `main` after the security audit's second delta (`025ab0c`) as **1.7**: `versionCode` 14,
`versionName` "1.7" (`-PversionCodeOverride`'s example now 15), `Provenance.text` "Made by Steven Jin ·
v1.7 · eab16a502f679465", the entry drafted at the end of `releases/history.json` (`"draft": true`, tag
`v1.7`, its notes; no hash or size until `tools/publish-release.sh` builds it, which was not run);
`latest.json` still names 1.6.2. The release is M23 (transcription), M24 (composing and the build that
keeps the app's other ABIs) and the audit's eight fixes, with one change of its own:

- **The composer's budget** (`7c331b2`): `PromptBuilder.TOKENS_PER_SECOND` 45, not the brief's 30, still
  at most `MAX_TOKENS` 9,000: 2,700, 5,400, 8,100, 9,000 and 9,000 tokens for one to five minutes (the
  deviation above says why). A piece that ends at its length is unchanged: six Wild one-minute runs from
  Für Elise with the real model came out identical at 30 and 45 (60.3–60.8 s, "stop endtime", 696–1,662
  tokens). `PromptBuilderTest` (the budgets, the constant, the Bach prompt's 5,400) and `ComposerTest`'s
  real-model minute (held to its prompt's 2,700) follow; README, DESIGN.md and the audit's M24 notes say 45.
- **Docs**: README "Version 1.7.", the device-owner setup's `adb install ../apk/steven-piano-1.7.apk`,
  "Studio came with 1.7", the release APK's 13.5 MB; DESIGN.md's v1.7 — M23 and M24 say they are 1.7.
- **Tests**: **1,147**, none failing; 12 skipped (8 with `-PstudioModels`, whose real-model cases pass).
  `check` passes: lint 0 errors, 28 warnings, and `checkDebugOnnxTelemetry` / `checkReleaseOnnxTelemetry`
  ("runtime onnxruntime-android:1.28.0"). No compiler warnings in the app's sources.
- **APKs**: the release APK is **13,471,112 bytes**, `versionCode` 14, `versionName` 1.7, signed with Steven
  Piano's release key (`CN=Steven Piano, O=Steven Jin, C=US`; APK Signature Scheme v2 and v3), as built
  here (`tools/publish-release.sh` builds its own and records its hash and size); the debug APK 27,964,579
  bytes. `lib/` holds ONNX Runtime for `arm64-v8a` only (`libonnxruntime.so` 28,637,280 bytes and its JNI
  111,648, deflated) and androidx's two small libraries for all four ABIs.
- **Provenance** re-signed after this commit.


---

# v1.7.1 — resting screen; release 1.7.1 (versionCode 15)

Read `DESIGN.md › v1.7.1 — the resting screen` first. Steven's request (2026-09-30), with his two
additions during the run: the byline at the top right on two lines, and the window's shape deciding
where the art stands. Built on `main` after 1.7 (`1768655`) and released as **1.7.1**: `versionCode`
15, `versionName` "1.7.1" (`-PversionCodeOverride`'s example now 16), `Provenance.text` "Made by
Steven Jin · v1.7.1 · eab16a502f679465", the entry drafted at the end of `releases/history.json`
(`"draft": true`, tag `v1.7.1`, its notes; no hash or size until `tools/publish-release.sh` builds it,
which was not run); `latest.json` still names 1.7.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/ui/screens/display/StandbyText.kt` (pure): `WIDE_LINES` 6, `PHONE_LINES` 4, `maxLines(wide)`;
  `description(piece, composer)`: the `piece:<id>` row's text when the row is `OK` and says something,
  else the `composer:<key>` row's, else null; `oneParagraph(text)` (runs of whitespace become one space).
- `M/ui/screens/display/RestingLayout.kt` (pure): `sideBySide(window)` = width ≥ height;
  `artSide(sideBySide, window, room)`: beside, min(55 % of the window's height, the room's height, 45 %
  of the room's width); on top, min(45 % of the window's width, the room's width, 40 % of the room's
  height); `sideGap(art)` a tenth of the art within 32–64 dp; `wordsWidth(sideBySide, roomWidth, art)`
  at most `ReadingWidth` (720 dp). The room is the window inside the cutout, the 24/16 dp margins and
  the byline's band above and below.
- `M/ui/screens/display/RestingMotion.kt`: `ENTER_MS` 1,500, `LEAVE_MS` 600, `PIECE_MS` 1,200;
  `enter(reduced)` / `leave(reduced)` (`fadeIn`/`fadeOut` with `tween(…, Motion.Standard)`, or
  `EnterTransition.None` / `ExitTransition.None`); `pieceChange(reduced)`, a `ContentTransform` of both
  fades at once, or of both `None`.
- Tests: `T/ui/screens/display/StandbyTextTest.kt`, `RestingLayoutTest.kt`, `RestingMotionTest.kt`.

Changed: `settings/Settings.kt` (`StandbyShows`); `ui/AdaptiveFrame.kt` (`StandbyShows.label`);
`ui/screens/piano/PianoViewModel.kt` (`setStandbyShows`); `ui/screens/piano/pages/DisplayPage.kt` (the
row, the note); `diag/DiagnosticsExporter.kt` (`standbyShows`); `ui/NavHost.kt` (`DisplayOverlay`);
`ui/screens/display/DisplayScreen.kt`; `Provenance.kt` (`restingByline`, the version);
`app/build.gradle.kts`; `releases/history.json`; tests `SettingsRepositoryTest`, `AdaptiveFrameTest`,
`DiagnosticsExporterTest`, `GroupSummariesTest`; DESIGN.md, this file, README.

## Settings

`enum class StandbyShows { ART_AND_NOTES, PAPER_ROLL }`, key "standbyShows", default `ART_AND_NOTES`; a
stored name this version doesn't know reads as the default. `SettingsRepository.setStandbyShows`. The
Display page's STANDBY: `ChoiceRow("Standby shows")` "Art and notes" · "Paper roll" after "Standby
canvas"; `DISPLAY_MODE_NOTE` "The piece's art and title fill the screen for passers-by". The hub's
Display value is unchanged. `settings.txt` in Share diagnostics lists `standbyShows` (33 lines).

## The overlay (`ui/NavHost.kt`)

`DisplayOverlay` derives `loaded` (`derivedStateOf { player.value.piece != null }` over the collected
player state, so it recomposes only when that flips) and wraps `DisplayScreen` in
`AnimatedVisibility(visible = idle.idle && DisplayRule.shows(loaded, kiosk), enter =
RestingMotion.enter(reduced), exit = RestingMotion.leave(reduced))`, passing `resting =
transition.targetState == EnterExitState.Visible`. Kiosk mode's `relock()` as before, as it enters.
`IdleTimer`, `rememberIdle` and `DisplayModeTimeout` are untouched.

## The screen (`ui/screens/display/DisplayScreen.kt`)

- `DisplayScreen(onLeave, resting = true)`. While `resting`: `leaveOnTouch` (the semantics, and the
  `pointerInput` that consumes each whole gesture and calls `onLeave`, as before), `BackHandler(enabled
  = resting)`, `keepScreenOn` while a piece is loaded, the system bars hidden. Once it fades away, none
  of them: the pointer node leaves the tree, so the next touch hits the app beneath (the gesture that
  left stays the overlay's, and nothing beneath receives its rest), the bars come back at once, and
  `heldWhile(!resting, …)` keeps the piece it showed and its padding.
- `restingInsets(resting)`: the display cutout's insets, read in composition as
  `PaddingValues.Absolute` and held once it goes. The system bars' are not followed: they are hidden
  while it rests, and following them moved its words as they slid away and back.
- `rememberDrift()` (4 dp round a square once a minute) for every variant; before, `DisplayRest` only.
- **Art and notes** (`ArtAndNotes`, also kiosk mode's rest with nothing loaded): `BoxWithConstraints`
  (the window) → the frame `Box` (cutout, 24/16 dp, the drift) → `AnimatedContent(targetState = piece,
  contentKey = { it?.pieceId }, transitionSpec = { RestingMotion.pieceChange(reduced) })`, padded above
  and below by the byline's band (two eyebrow lines and 16 dp) → `PieceAtRest`, or with nothing loaded
  `RequestAtRest` (the request code as M20 had it, or nothing); `RestingByline` at `TopEnd`; the
  `LiveDot` at `BottomStart` in an `AnimatedVisibility(piece != null)` with the piece's fades, described
  "Sent to piano" / "Not connected".
- `PieceAtRest`: `rememberArtworkRow` of `piece:<id>` and `composer:<key>` → `StandbyText`;
  `RestingLayout` → the `Row` (art, gap, words at their width, centred as one) or the `Column` (art,
  24 dp or 32 dp on wide frames, words); `PieceArt(…, ArtSize.Full, Modifier.size(art))`, framed.
  `Words`: the title (`displayLarge` on `frame.twoPane`, else `displayMedium`, 3 lines), the
  `ChannelCopy.eyebrow` (`EyebrowLarge` on `twoPane`), the description (`bodyLarge`,
  `onSurfaceVariant`, `maxLines = StandbyText.maxLines(twoPane)`, `weight(1f, fill = false)` so it gives
  way first and is ellipsized by the height left too); start-aligned beside the art, centred under it.
- **Paper roll** (`PaperRoll`): v1.5's `DisplayContent`, the title and its eyebrow in a `Row` with
  `RestingByline` (16 dp between), the foot's `FlowRow` now the dot and its words alone.
- `RestingByline`: a `Column(horizontalAlignment = End)` of `Eyebrow(line, maxLines = 1)` over
  `Provenance.restingByline` ("Player piano", "Made by Steven Jin"), merged for TalkBack.

## Greps (v1.7.1)

`Color(0x` outside `ui/theme`: none. `DisplayBlack`: `Color.kt`, `Theme.kt`. `LocalLive`: `LiveDot.kt`,
`Theme.kt`. `LocalNoteSounding`: `Theme.kt`, `ScorePages.kt`. `hazeSource`/`HazeState`: `Glass.kt`,
`NavHost.kt`, `NotePanel.kt`. `Modifier.blur`, `0.0.0.0`: none.

## Measured (September 2026, `steven_piano`, API 34, debug build, the emulated piano)

Phone 1080 × 2400 px at 420 dpi; tablet frame `wm size 2560x1600`, `wm density 240` (1,707 × 1,067 dp);
`debug.stevenpiano.idlesecs` 8, then 20; Clair de lune's own notes fetched by opening its sheet ("Suite
bergamasque is a piano suite by Claude Debussy…"). Screenshots in the run's scratchpad (`evidence/`).

- **Art and notes**: the tablet frame sets the art at the left, 880 px (587 dp, 55 % of 1,067) and the
  words at 720 dp, the two centred; the phone upright centres the art on top at 185 dp (45 % of 411) with
  four lines ending "The popularity of t…"; a phone on its side (`wm size 2400x1080`) sets the art beside
  the words. Black and "Same as the app" (paper) on both. A piece with no notes of its own (Chopin's
  Nocturne in C-sharp minor, Op. posth.) shows Chopin's blurb; Rachmaninoff's was cut at six lines with
  an ellipsis on the tablet. At font scale 2.0 the phone upright keeps four lines, and on its side the
  description gives way to three.
- **The byline** stands at the top right on both variants, 16 dp from the top and 24 dp from the side,
  opposite the Paper roll's title; the live dot alone at the foot on Art and notes, "● Sent to piano"
  on Paper roll.
- **Fading in** (the tablet frame, raw frames from `screencap`, opacity from the pixels where the
  app's paper, 244, meets the black canvas): a frame at opacity 0.152, i.e. 315 ms on the standard
  easing (0.134 at 300 ms), and one at 0.889, 927 ms (0.876 at 900 ms). Aimed by the time since the
  last touch, the captures landed within about ±250 ms of their aim (`screencap`'s own start varies),
  so several were taken and these two kept.
- **Leaving**: a tap on the spot of Now playing's Pause left the resting screen and did nothing else
  (still playing); a second tap there 150 ms later, while it faded, paused (the media session read
  PAUSED): the app beneath is live at once. Before `restingInsets`, the byline and the dot slid by the
  status bar's height as the bars came back during the fade (the 250 ms frame); after it, they hold.
- **A new piece while resting**: with Clair de lune queued after Rachmaninoff's Prelude Op. 32 No. 1,
  the prelude ended while resting; the frame about 0.25 s after the media session named Clair de lune
  shows both, the new at about 0.76 of its opacity (≈ 590 ms of 1,200). Media keys (`input keyevent
  KEYCODE_MEDIA_NEXT`, `cmd media_session dispatch next`) never reach the app's session on the emulator
  ("Media button session is null": nothing plays audio), so the piece was left to end.
- **Reduced motion** (animator, transition and window scales 0, the app restarted): the first frame
  after the rest began was the whole resting screen; 120 ms after a touch the app was back whole.
- The emulator was put back as it was: `wm size` and `wm density` reset, `debug.stevenpiano.idlesecs`
  cleared, the three animation scales 1, font scale 1.0, the app's `settings.preferences_pb` restored
  byte for byte from a copy taken first; then stopped. It keeps the 1.7.1 debug build (it had 1.6.2's).

## Deviations from the brief, and why

- **The window's shape decides the layout**, not `frame.twoPane` (Steven, during the run): wider than
  tall, the art beside the words on any device; only taller than wide stacks them (a square window
  sets them side by side). The title's size and the six or four lines still follow `twoPane`.
- **The byline at the top right on two lines** on every resting screen, the kiosk rest included
  (Steven, during the run), where the brief had kept it bottom-right. "The same 16 dp margins as the
  title": it sits inside the title's own margins, 16 dp from the top and, as the title, 24 dp from the
  side; the resting screen's margins were not changed to 16 dp all round.
- **The 4 dp shift on every resting screen**, Paper roll included (the byline takes part in it, as
  asked); only kiosk mode's rest had it before. Paper roll is otherwise v1.5's screen, with no
  cross-fade between pieces.
- **The resting screen keeps clear of the cutout alone, and holds its padding while it goes**: following
  the system bars' insets moved its words while it was visible, as they slid away and came back.
- **Display mode's note** no longer names the roll: "The piece's art and title fill the screen for
  passers-by", true of either choice.
- **The dot without its words** is described for TalkBack ("Sent to piano" / "Not connected").
- **The description is set as one paragraph** (a line break would spend one of four lines), and gives
  way first when the room runs short (large fonts, a phone on its side).
- **The resting screen asks for nothing**: a piece's own notes appear once its sheet has fetched them,
  as the fetching policy has it (DESIGN.md › v1.2); until then the composer's.

## Residuals

- **Kiosk mode's rest** (the byline at the top right over the request code) was not seen on the
  emulator: it needs the app as device owner. It is the same frame as Art and notes with nothing loaded.
- **Wikipedia's text on the resting screen carries no credit line** (CC BY-SA 4.0); the piece sheet and
  the About area carry it, as with the portraits display mode has always shown. For Fable to weigh.
- **Not measured on the school tablet.**

## Tests added in v1.7.1

`SettingsRepositoryTest` +1 (the default, remembered, an unknown name read as the default),
`AdaptiveFrameTest` +1 (the standby chips), `StandbyTextTest` (6: the piece's notes first; the
composer's when the piece has none, is not found, failed or blank; nothing, never a placeholder; a Studio
piece's line; one paragraph; the line caps), `RestingLayoutTest` (5: a tablet on its side; a phone
upright; a phone on its side; the window's shape decides, a square window beside; the art never takes
the words' room), `RestingMotionTest` (3: the durations; every one a cut under reduced motion; fades
otherwise); `DiagnosticsExporterTest` (33 lines) and `GroupSummariesTest` (Display unchanged by it)
changed. 1,147 before, **1,163** after.

## The release: 1.7.1 (versionCode 15)

- **Tests**: 1,163, none failing, 12 skipped (as 1.7: the corpus's seven, `PinnedKeyTest`'s firmware
  header, and the real-model cases without `-PstudioModels`). `check` passes: lint 0 errors, 28
  warnings (as 1.7), and both ONNX Runtime telemetry checks. No compiler warnings in the app's sources.
- **APKs**: the release APK is **13,478,096 bytes**, `versionCode` 15, `versionName` 1.7.1, signed with
  Steven Piano's release key (`CN=Steven Piano, O=Steven Jin, C=US`; v2 and v3), the provenance string in
  `classes.dex`; the debug APK 27,987,251 bytes. Not staged in `../apk/`.
- **Provenance** re-signed after this commit.

---

# v1.8 — M25: the tablet's piano sound; release 1.8 (versionCode 16)

Read `DESIGN.md › v1.8 — M25` first. Built on the branch `m25-sound` from the 1.7 release commit
(`1768655`), beside another run on `main`; commits a step each. **No version bump, no `Provenance.text`
change, no provenance signing, no APK here**: the integrator's, at the merge (1.8, `versionCode` 16: see
*The merge* at the end of this section). The SoundFont is published (below); `releases/models.json` on
`main` names it once this branch is merged and pushed.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- The engine, `M/audio/` (no Android but `AudioOut`): `Sf2Reader.kt` (`Sf2Reader`, `SoundFont`, `Region`,
  `Sf2Sample`, `Sf2Exception`), `Sampler.kt`, `Limiter.kt`, `PianoVoice.kt`, `AudioOut.kt`, `TabletSound.kt`
  (`TabletSoundMode`, `TabletSoundState`, `SoundDownload`, `TeeSink`, `TabletSound`).
- UI: `M/ui/TabletSoundCopy.kt`; `M/ui/components/TabletSoundControls.kt` (`TabletSoundButton` and its
  popover, `TabletSoundNote`, `SoundFontRow`); `M/ui/screens/nowplaying/TabletSoundSpeaker.kt`
  (`TabletSoundSpeaker`, `TabletSoundDownloadNote`); `res/drawable/ic_speaker.xml`.
- `third_party/upright-piano-kw/` (`NOTICE.txt`, `LICENSE.txt`: CC0 1.0's legal code from the archive).
- Tests: `T/audio/Sf2Fixture` (SoundFonts the tests write), `Sf2ReaderTest`, `SamplerTest`, `PianoVoiceTest`,
  `OfflineRender` and `OfflineRenderTest`, `TabletSoundRenderTest` (the real SoundFont, when
  `-PpianoSound` names it), `TabletSoundTest`; `T/ui/TabletSoundCopyTest`.

Changed (each addition small and marked v1.8 — M25): `app/build.gradle.kts` (`-PpianoSound`,
`-PpianoRender`, `-PpianoRenderMidi` for the render test); `M/AppGraph.kt` (`tabletSound`, its output, the
player's tee, the collectors in `start()`); `M/player/Player.kt` (a `tablet: MidiSink?` parameter: the engine's
sink is `TeeSink(link, tablet)`); `M/settings/Settings.kt` (`tabletSound`, `tabletVolume`);
`M/diag/DiagnosticsExporter.kt` (the two in `settings.txt`); `M/studio/ModelCatalogue.kt` (`ModelKind`,
`ModelEntry.kind`, `pianoSound`, `kept`, `pinned`), `ModelManifest.kt` (the `sounds` array),
`ModelInstaller.kt` (the cap by kind), `ModelStore.kt` (`alsoKept`); `M/update/UpdateSource.kt` (`.sf2`,
`MAX_SOUND_BYTES`); `M/ui/screens/keys/KeysViewModel.kt`, `KeysScreen.kt`; `M/ui/screens/nowplaying/
NowPlayingScreen.kt`, `NowPlayingPanel.kt`; `M/ui/screens/piano/pages/PlaybackPage.kt`, `PianoViewModel.kt`,
`AboutRow.kt`; `M/ui/components/SettingsRows.kt` (`ChoiceRow(note)`); `M/web/WebBackend.kt` (`WebTablet`,
`SettingsChange.tabletVolume`), `WebApi.kt`, `AppWebBackend.kt`; `M/service/WebService.kt`;
`assets/web/index.html`, `app.js`; `releases/models.json`; `tools/studio/publish_models.py`; tests
`ModelManifestTest`, `ModelStoreTest`, `UpdateSourceTest`, `SettingsRepositoryTest`,
`DiagnosticsExporterTest`, `WebApiTest`, `WebServerTest`, `WebAssetsTest`; `AUTHORS`, `DESIGN.md`,
`README.md`.

## The SoundFont and its download

- **What**: FreePats' Upright Piano KW, 2022-02-21 (`UprightPianoKW-20220221.sf2` in
  `https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SF2-20220221.7z`, archive SHA-256
  `17c084c6…07c826`), CC0 1.0 as FreePats' page and the archive's readme and `cc0.txt` state. One preset
  (bank 0, preset 0), one instrument of 132 zones (66 stereo pairs: key ranges of about a minor third, two
  velocity layers, 0–80 and 81–127), 132 samples at 44.1 kHz, 28,678,096 frames; bass zones loop; release
  −884 timecents (0.6 s); no modulators; three soft-layer zones set a low-pass (ignored, below). Published
  unmodified as `upright-piano-kw-v1.sf2`: **57,377,848 bytes, SHA-256
  `d9f5157720963671906727ca2e12b3293fd822c831bb0de477dd1c5f3ad37108`**, an asset of the release `models`.
- **Listed** in `releases/models.json` under a new top-level **`sounds`** array (the models stay in
  `models`, byte for byte): `name` `upright-piano-kw`, `version` 1, `file`, `url`, `sizeBytes`, `sha256`,
  `licence` `CC0-1.0`, `source`, `attribution`, and in place of inputs and outputs `format`, `soundfont`,
  `sampleRates`. 1.7's `ModelManifest` reads `models` alone and refuses the whole list if an entry there
  isn't `<name>-v<n>.onnx`: a sound beside the models never reaches it (checked with 1.7's own parser, compiled
  from `1768655`, on the new list: it reads its two models and nothing else).
- **Pinned** in `ModelCatalogue.pianoSound` (`kind` `ModelKind.Sound`: `.sf2`, list key `sounds`, cap
  `UpdateSource.MAX_SOUND_BYTES` = 200 MiB); `ModelCatalogue.all` is still Studio's two, so the Studio page,
  the hub's "2 models" and the panel's Studio page are untouched. `ModelManifest.parse` reads both arrays
  (at most 32 each, a name and version once across both), each entry checked for its kind's extension and
  cap, a pinned entry held to its pin; `entryFor` matches the kind too. `UpdateSource.allowsModel(url,
  extension)` / `allowsModelFile(url, extension)` take `.onnx` or `.sf2` and nothing else;
  `ModelInstaller` caps a download at its kind's `maxBytes`.
- **Downloaded** by `TabletSound.download()` through Studio's path (`ModelInstaller` → `ModelManifest` →
  `VerifiedDownloader`: the part file hashed as it arrives, kept only at the pinned size and SHA-256, 256 MB
  kept free beside it) into `filesDir/models/`, in the app's scope (not a Studio job; no foreground service),
  with its progress, Cancel and failures in the piano sound's words (`TabletSound.failureLine`). On the
  emulator in debug builds, `debug.stevenpiano.modelsurl` points it at a local server as it does Studio's.
  `ModelStore(alsoKept = ModelCatalogue.kept)`: Studio's sweep at start keeps the SoundFont, and the sound's
  own store (`listOf(pianoSound)`, never swept) Studio's models. Before its first use in a process the file's
  SHA-256 is checked (`ModelStore.open`: 81 ms on the emulator), then it is memory-mapped and its pages
  brought in (`Sf2Reader.read(file)`, `MappedByteBuffer.load()`; 35 ms): nothing of it on the Java heap.
- **Publishing**: `tools/studio/publish_models.py --work DIR --models upright-piano-kw --upload` fetches the
  archive from FreePats (its hash checked), extracts the `.sf2` (bsdtar), checks its hash, writes the
  `sounds` entry into both `models.json` files (entries not named are kept as `releases/models.json` has
  them, so one file publishes without the others), uploads the `.sf2` and `models.json` to the release
  (`--clobber`) and reads both back through `gh` to compare hashes. Run on 2026-09-30: "round trip OK" for
  both; the public URL answers 200 through `release-assets.githubusercontent.com` with the pinned bytes.

## The engine's SF2 (`Sf2Reader`)

RIFF `sfbk`: `LIST INFO` (`INAM`), `LIST sdta` (`smpl`, 16-bit; `sm24` ignored), `LIST pdta` (`phdr`,
`pbag`, `pgen`, `inst`, `ibag`, `igen`, `shdr`; `pmod`/`imod` read past). Every table's size a whole number
of records, every bag, generator, instrument and sample index checked against its table, every chunk
within its parent; anything else is an `Sf2Exception` in words. The preset is bank 0 preset 0, else the
first. Zones: an instrument's first zone without a `sampleID` (a preset's without an `instrument`) is its
global zone; a zone's own generators over its global zone's over SF2's defaults; the preset zone's (its
own over its global's) **added** for the generators the preset level may set, the key and velocity ranges
**intersected**. Kept per region: key and velocity range; the sample and its start, end and loop moved by
the four address offsets and their coarse forms (instrument level only), clamped to the data; sample modes
(0 and 2 once, 1 loop, 3 loop until release); root key (`overridingRootKey`, else the sample's pitch);
coarse and fine tune with the sample's correction; scale tuning; initial attenuation; the volume envelope
(delay, attack, hold, decay, sustain, release, key to hold, key to decay). Read and not used: pan.
Ignored: filters and their modulators, the LFOs, the modulation envelope, chorus and reverb sends,
exclusive class, keynum and velocity overrides, modulators (the sampler applies SF2's default velocity
curve). ROM samples are skipped. A left sample's region whose linked right sample's region matches it in
everything but pan becomes one region with a `partner`: the pair plays as one voice.

## The sampler (`Sampler`, `Limiter`)

- **Voices**: struct-of-arrays, allocation-free after construction; `POLYPHONY` 48 voices that count
  (a stereo pair is one), plus `FADE_SLOTS` 16 where stolen and silenced voices fade out over `FADE_MS` 12.
  A new note past 48 fades the oldest released voice, else the oldest held one. A key struck again releases
  what it still sounds (held or pedalled), as FluidSynth does, so pedalled repeats don't pile up. Note Off
  releases, or with the pedal down marks the voice sustained; pedal up releases those; `allOff` (the stop
  sequence's CC123) releases all and lifts the pedal; `silence` (a mode change, focus lost, CC120) fades all;
  `reset` frees all at once (the output closed). A note is at least `MIN_NOTE_MS` 50 long: a key let go
  sooner releases then (a Note On and Off in one block would otherwise never be heard).
- **Playback**: linear interpolation between 16-bit frames (a pair's two channels summed and halved);
  a step of 2^((key − root) × scaleTuning / 1200 + tune / 1200) × sampleRate / outputRate; loops wrap
  within `loopStart`–`loopEnd` (mode 3 plays on to the end once released); a voice ends at its sample's end
  (not looping), when its release reaches −100 dB, or when its fade ends.
- **Envelope**: worked out at the end of each block of at most `BLOCK` 64 frames and ramped across it.
  Delay (the sample waits), attack linear in amplitude (at least one frame), hold, then decay, sustain and
  release linear in decibels, a full change being **100 dB** (SF2 2.04 § 8.1.2: sustain `sustainCb` below
  full; decay and release the time of a 100 dB change); a release during the attack continues from the
  attack's amplitude in the decibel domain. Timecents: 2^(tc/1200) s; −12,000 and below instant; release
  at least `MIN_RELEASE_MS` 10.
- **Gain**: SF2's default note-on velocity to attenuation modulator (concave, negative, 960 cB):
  −40·log10(v/127) dB, an amplitude of **(v/127)²**; initial attenuation 10^(−cB/200); the master
  **(volume/100)² × `HEADROOM` 2** (+6 dB at 100 %, −2.9 dB at the default 60 %), ramped over a block when it
  changes; then the **`Limiter`**: unchanged while |x| × gain stays under the ceiling, 0.8913 (−1 dBFS); a
  peak over it sets the gain to ceiling / |x| at once (that sample leaves at the ceiling exactly), and the
  gain recovers exponentially with a 250 ms time constant. The headroom was chosen on the real SoundFont
  (below: *Measured*).
- `render(out, frames)` fills mono floats; `activeVoices`, `liveVoices`, `heldKeys()`,
  `takeLowestLimiterGain()` for tests and the log.

## The voice and the output (`PianoVoice`, `AudioOut`)

- `PianoVoice(outputRate)`: `noteOn`, `noteOff`, `sustain`, `allOff`, `silence`, `reset` queue packed events
  (a 4,096-entry ring under a lock held for one store; a full queue nobody drains starts again from
  `silence`); `volume` is a volatile read at each render; `load(font)` swaps the sampler, and `load(null)`
  queues `silence` and lets the sampler go on the audio thread once nothing sounds (a compare-and-set, so a
  font loaded meanwhile stays), playing nothing new meanwhile: turning the sound off never clicks. The audio thread's
  `drain()` and `render()` apply the queue in order, then mix. `onPost` hears each event (the output's
  unpark). `sink(active)` / `play(batch)` read the player's batches: Note On (velocity 0: Off), Note Off,
  CC64 (≥ 64 down), CC123 → `allOff`, CC120 → `silence`; the rest ignored, as the piano ignores it.
  `notesPosted` counts keys for the log.
- `AudioOut`: one thread, "steven-piano-sound", `THREAD_PRIORITY_URGENT_AUDIO`, parked while nothing
  sounds and no piece plays. Woken, it applies the queue; once a voice sounds, or `keepOpen` (the tablet
  active and the player playing: so a piece's first note after its pause, and its rests, find the output
  open), it requests `AUDIOFOCUS_GAIN_TRANSIENT` (usage media, content music; the listener on the main
  thread) and builds an `AudioTrack`: `ENCODING_PCM_FLOAT`, stereo (the mono mix on both sides), at the
  device's output rate (`PROPERTY_OUTPUT_SAMPLE_RATE`, 44.1 or 48 kHz, else 48), `MODE_STREAM`,
  `PERFORMANCE_MODE_LOW_LATENCY`, a buffer of 20 ms or two of the device's bursts
  (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`), whichever is more, room for twice that. It renders and writes
  (`WRITE_BLOCKING`) the device's burst or 5 ms, whichever is less; each new underrun
  (`getUnderrunCount`) grows the buffer by a burst up to its capacity. After `IDLE_MS` 3 s without a sound,
  a pending event or `keepOpen`, it pauses, flushes and releases the track and abandons the focus.
  `AUDIOFOCUS_LOSS` and `LOSS_TRANSIENT`: the output resets the voices and closes, `onFocusLost` pauses the
  player (Play resumes); until a new note, a piece still playing no longer keeps the output open.
  `LOSS_TRANSIENT_CAN_DUCK` is Android's to duck (API 26+). Refused focus is a loss. The media volume stream
  governs the track; the media session's volume stays the system's.

## Wiring (`TabletSound`, `Player`, `AppGraph`, Keys)

- `TabletSoundState(mode, volume, connected, installed, download)`: `wanted` = `mode.sounds(connected)`
  (OFF never, WHEN_NOT_CONNECTED while the link isn't `Connected`, ALWAYS always), `active` = wanted and
  installed, `needsDownload` = wanted and not installed.
- `TabletSound.follow(mode, volume, connected)`, from `AppGraph.start()` (`combine(settings, link.state)`,
  distinct), sets the voice's volume and settles: `active` (volatile, read by the sink on the scheduler
  thread) is the state's `active` and a loaded voice; it turning false queues `silence` (the fade), so the
  piano connecting mid-piece silences the tablet before the next note. The SoundFont is read whenever the mode
  isn't OFF and it is installed, and let go at OFF. `playing(on)` (the player's status, distinct) feeds
  `keepOpen`. Each change of `active` is logged with the notes played so far.
- `Player(…, tablet = tabletSound.sink)`: `PlaybackEngine(TeeSink(link, tablet))`: each batch goes to the
  link, then to the tablet, on the scheduler thread at the same moment; the tablet's sink only queues. So the
  tablet hears the router's output (folding, the velocity percentage, the pedal's pacing, the 100 ms guard,
  the reference counts, the stop sequence) for pieces, channels, schedules, Studio's Listen and the Keys tab.
- The Keys tab: `KeysViewModel.noteOn` sends to the player while the piano is connected **or the tablet is
  active**; its line reads "Not connected. The tablet plays these keys." while the tablet plays them.
- Settings: `tabletSound` (`TabletSoundMode`, `WHEN_NOT_CONNECTED`), `tabletVolume` (0–100, 60); both in
  `settings.txt`.

## UI

`PlaybackPage`: after its rows, `SectionEyebrow("Tablet sound")`, `ChoiceRow` with the new `note`,
`SliderRow` (the value shown at once, the setting written as it moves), `SoundFontRow`.
`NowPlayingScreen`: `TabletSoundSpeaker()` at the end of the tempo row and `TabletSoundDownloadNote` under
it; `NowPlayingPanel`: `TabletSoundSpeaker()` at the start of its foot row (from 1.10 with
`popoverAlignment = Alignment.Start`). `TabletSoundButton`: an
`IconButton` tinted `onSurface` when active and `LocalTertiary` otherwise; a `DropdownMenu` with a
`LocalHairline` border (from 1.9 `GlassPopover`, its content 268 dp inside the glass's 16 dp), 300 dp wide,
the eyebrow, the status, Volume and its value, `HairlineSlider` (full width), and the download while it
waits. `AboutRow`: `TabletSoundCopy.CREDIT`. Kiosk: nothing asks
on Now playing; the Playback page is a locked page as before.

## Web

`WebPlayer.tablet: WebTablet(mode "off" | "whenNotConnected" | "always", volume, active, installed)` →
`player.tablet` in `/api/state` and the socket's state; `PUT /api/settings` takes `tabletVolume` (0–100;
anything else 400, the mode among it); `WebService` pushes on `tabletSound.state`. `index.html`: the
`#tablet-volume` row under the channel's volume; `app.js`: `tabletLine`, the slider sent debounced
(150 ms) as the channel's is, hidden while the mode is off.

## Greps (v1.8 — M25)

M24's hold: no `Color(0x` outside `ui/theme`; no `0.0.0.0` or `Access-Control` in `app/src/main`;
`DisplayBlack` in `Color.kt` and `Theme.kt`; `LocalNoteSounding` in `Theme.kt` and `ScorePages.kt`;
`LocalLive` in `LiveDot.kt` and `Theme.kt`; Haze imported in `Glass.kt` only; no `Modifier.blur`; no pure
black or white in `assets/web` but the stylesheet's comment; `ai.onnxruntime` only under `studio/`. New:
`AudioTrack` and `AudioFocusRequest` in `audio/AudioOut.kt` only; nothing in `audio/` but `AudioOut.kt`
imports Android.

## Measured (2026-09-30)

- **Tests**: **1,195**, none failing (1,147 before); 13 skipped without `-PpianoSound`
  (`TabletSoundRenderTest` joins the 12). `check` passes: lint 0 errors, 28 warnings (as 1.7; none in the new
  code), `checkDebugOnnxTelemetry` and `checkReleaseOnnxTelemetry`.
- **Clair de lune offline** (`TabletSoundRenderTest`, piano-midi.de's `deb_clai.mid`, the real SoundFont,
  the app's engine and voice at 48 kHz, volume 60): 251.6 s of music rendered in 0.43–0.46 s on the Mac's
  JVM (about 550–580× real time), peak 0.412 (−7.7 dBFS), RMS 0.0261 (−31.7 dBFS), at most 26 voices at
  once, no clipping, no clicks (no sample-to-sample jump over 8× the local RMS), no DC; the SoundFont read
  in 36 ms. The WAV (48 kHz, 16-bit, stereo, 48,314,608 bytes) reads back through `WavReader` at its
  length.
- **The level, chosen on the real SoundFont** (the mix unlimited at gain 1, then the gain and the limiter;
  RMS / most limiting): Clair de lune −28.8 dBFS RMS at gain 1 (its velocities: median 36, 28–52 for four
  in five); Für Elise −27.0; Chopin's Op. 10 No. 12 −16.4 with peaks at +4.9 dBFS. `HEADROOM` 0.25 (the
  first build) left Clair de lune at −49.7 dBFS RMS at the default volume: far too quiet. At 2.0: Clair de
  lune −31.6 dBFS at 60 % and −22.9 at 100 % (2.2 dB of limiting at most, 0.3 % of the time), the étude
  −19.3 at 60 % (3.0 dB at most, 0.7 %) and −13.1 at 100 % (11.9 dB at most, 41 % of the time: 100 % on the
  loudest pieces is held down hard). 1.5 and 3.0 were measured too.
- **The emulator** (`steven_piano_m25`, API 34, Pixel 7 profile, arm64, 4 GB, `-no-audio`, which keeps the
  guest's audio running and sends nothing to the Mac; tablet shots at 2560 × 1600, 240 dpi; logs in
  `m25-shots/emulator-evidence.log`):
  - *The real path first*: Download fetched `main`'s `releases/models.json`, which has no `sounds` until
    this branch is pushed: "The piano sound isn't offered right now." Then the local server (5 MB/s): 57 MB
    in 15.6 s from Now playing's note ("Downloading the piano sound · 10 of 57 MB"), "downloaded and
    verified", read in 35 ms, sounding.
  - *Clair de lune with the piano not connected*: the output opened as the piece started, 48 kHz, 2,176
    frames of buffer (the emulator's burst is 1,088 frames, 22.7 ms), writing 240 at a time; `dumpsys
    media.audio_flinger`: the track active, PCM float, stereo, usage media, content music, the media
    stream's −33 dB applied, **0 underruns**; it closed 3.6 s after the piece ended, "0 underruns, at most
    19 voices" after 128 s. `top -H`: the sound thread (PR 1, NI −19) at **1–4 % of a core**; the UI's
    RenderThread 16–19 %. Not a fast track on the emulator (its normal mixer; the track asks for low latency).
  - *The Keys tab*: the output opened on the first key, closed 3 s after the last: 0 underruns, at most 10
    voices.
  - *The piano connecting mid-piece* (When the piano isn't connected): connected at 12:20:45.770, the tablet
    silent at 45.773 ("after 126 notes"), the piano's resync at 45.773 and the piece's next notes on the
    piano at 46.184: **silent within the note**.
  - *Always, the piano connected*: "sounding (always, the piano connected)", the output open beside it.
  - *Audio focus*: YouTube Music opening a WAV took transient focus; the tablet's output closed and gave the
    focus back within 22 ms, and the piece paused (the piano got the stop sequence); Play resumed it.
  - *The popover's volume* moved to 87 %: the Playback page read 87 % after (the setting kept).
- **Screens** (`m25-shots/`): the Playback page (light, tablet: Download; dark: Installed, and Always with
  its warning), Now playing with the download note, downloading and sounding, the popover (light at 60 and
  87 %, dark), the Keys tab sounding, and on the phone Now playing, the popover and the Playback page (light
  and dark).

## Deviations from the brief, and why

- **The list's `sounds` array**, not an entry in `models`: 1.7's parser refuses the whole list over one entry
  it can't read, which would have stopped every 1.7 tablet's Studio downloads the moment the list reached
  `main`. Checked with 1.7's own parser.
- **A stereo pair plays as one voice**, its two channels summed to mono (the brief's "pan ignored →
  mono-to-stereo", done per pair): the 48 voices are 48 notes, and each note reads the pair's two
  recordings, as the font was made.
- **100 dB for a full envelope change** (SF2 2.04), not FluidSynth's 96 dB; the default velocity curve as
  SF2 specifies it, (v/127)².
- **A peak limiter and +6 dB of headroom** in place of a fixed gain: the measured levels above (a pp piece
  at −50 dBFS with the first build's gain).
- **A note is at least 50 ms**, stealing prefers released voices and fades the stolen one, and a key struck
  again releases its old voice: none named in the brief, each measured or tested (`SamplerTest`,
  `PianoVoiceTest`), each so the tablet sounds like a piano and never clicks.
- **The output isn't open all the time**: only while something sounds or a piece plays, and 3 s after, so an
  idle tablet holds no audio focus and no audio thread awake; the first key after a silence waits for the
  track to open (tens of milliseconds, once).
- **Audio focus**: `GAIN_TRANSIENT` as asked; a transient loss (a call) pauses as a loss does; a duck is
  Android's. A pause pauses the player, so in Always the piano pauses with the tablet.
- **The download runs in the app's scope**, not as a Studio job: no foreground service or wake lock for
  57 MB; its progress is on the Playback page and Now playing.
- **The filter is ignored** (as the brief says): three soft-layer zones (keys 47–52 and 59–61, velocity
  0–80) set a 1.5–2.5 kHz low-pass, so those keys are brighter there than FreePats meant.
- **The popover has a hairline edge**: the app's standard popover (no Liquid Glass pass in this tree), whose
  shadow alone doesn't show over the score's panel in the dark. (At 1.9's merge it moved onto the glass
  pass's `GlassPopover`, whose outline and specular line mark its edge: v1.9 › *The merge*.)
- **The emulator's buffer is 45 ms**, two of its 1,088-frame bursts; about 20 ms wherever the device's burst
  is 10 ms or less.
- **The web panel's volume shows only while the mode isn't Off**, and the mode stays on the tablet.

## Residuals

- **Nothing measured on the tablet itself**: its latency (the brief's 20–40 ms), the fast path, the sound
  thread's CPU and how the Always mode lines up with the real piano.
- **The real download** needs `releases/models.json` with its `sounds` on `main` (this branch, pushed); the
  asset is published and verified.
- The SoundFont's `sm24` (24-bit) and every modulator but the default velocity curve are ignored; no reverb.
- With the output closed, the first note after a silence is late by the track's opening.

## Tests added in M25

`Sf2ReaderTest` (10), `SamplerTest` (13), `PianoVoiceTest` (6), `OfflineRenderTest` (2),
`TabletSoundRenderTest` (1, skipped without `-PpianoSound`), `TabletSoundTest` (7), `TabletSoundCopyTest`
(4), `ModelManifestTest` +2, `ModelStoreTest` +1, `UpdateSourceTest` +1, `SettingsRepositoryTest` +1;
`WebApiTest`, `WebServerTest`, `WebAssetsTest` and `DiagnosticsExporterTest` changed. 1,147 before,
**1,195** after.

## The merge: release 1.8 (versionCode 16)

Merged into `main` after 1.7.1 (`5ceb03c`) as `6f33800`, and released as **1.8**: `versionCode` 16,
`versionName` "1.8" (`-PversionCodeOverride`'s example now 17, the README's too, which 1.7.1 had left
at 15), `Provenance.text` "Made by Steven Jin · v1.8 · eab16a502f679465", the entry drafted at the end
of `releases/history.json` (`"draft": true`, tag `v1.8`, its notes; no hash or size until
`tools/publish-release.sh` builds it); `latest.json` still names 1.7.1. Pushing `main` publishes
`releases/models.json` with its `sounds`, and from then the real download works.

- **Conflicts**: `DiagnosticsExporterTest` (`settings.txt` has 1.7.1's `standbyShows` and this run's
  `tabletSound` and `tabletVolume`: 35 lines); DESIGN.md and this file (main's text with 1.7.1's section,
  then this one after a separator). `Settings.kt`, `PianoViewModel.kt`, `DiagnosticsExporter.kt`,
  `SettingsRepositoryTest`, the build file and README merged by themselves.
- **The resting screen's credit** (`6100734`, Fable's 1.7.1 review): Art and notes sets "From Wikipedia
  · CC BY-SA 4.0" (`StandbyText.WIKIPEDIA_CREDIT`) one line under the description, in the eyebrow and
  sentence case, when the row that gave the text has a source (its page's address or title, as the
  piece's sheet decides: a page whose address failed the link check keeps its title); a piece made in
  Studio (a row with no source) none. `StandbyText.notes(piece, composer)` gives the text with its
  credit. `StandbyTextTest` +1.
- **The piece's own notes, asked for from the resting screen** (`d5fba13`, the same review): a piece
  with no `piece:<id>` row at all, while Fetch artwork automatically is on
  (`StandbyText.asksForOwnNotes`), has `ArtworkRepository.request(ArtKey.Piece(id, title, composer),
  priority = false)` once per piece shown, judged on the table as read (the repository's flow, not the
  first frame's guess); the composer's notes show meanwhile. `StandbyTextTest` +1.
- **Measured at the merge** (`steven_piano_int`, API 34, Pixel 7 profile, 4 GB, `-no-audio`, the run's
  own AVD, removed after; `integrate-m25-shots/emulator-evidence.txt` in the session scratchpad):
  - Piano › Playback › TABLET SOUND: the three chips, "The tablet plays pieces and the Keys tab itself
    while the piano isn't connected.", the volume at 60 %, Upright piano "57 MB · CC0 · FreePats · A
    Kawai upright, recorded note by note." with Download.
  - The real path: Download read `main`'s `models.json` on GitHub (no `sounds` yet): "The piano sound
    isn't offered right now." From the Mac the public asset answered 200 with 57,377,848 bytes of the
    pinned SHA-256. Then a local server with the merged list (`debug.stevenpiano.modelsurl`): 57 MB in
    7.1 s, "downloaded and verified", checked, read in 33 ms, 132 regions; the row read "Installed · 57 MB
    · CC0" with Remove.
  - Clair de lune with the piano disconnected: the output opened as it started, 48 kHz, 2,176 frames of
    buffer (the burst 1,088), writing 240 at a time. `dumpsys audio`: an `android.media.AudioTrack` of
    the app, `state:started`, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, stereo, 48,000 Hz, and the app holding
    `GAIN_TRANSIENT`; `dumpsys media.audio_flinger`: the track active, PCM float, stereo, the music
    stream at −33 dB, **0 underruns**. It played on through the resting screen and closed 3 s after
    Pause: "closed (0 underruns, at most 23 voices)".
  - The speaker on Now playing's tempo row: "Tablet sound, volume 60%"; its popover: TABLET SOUND,
    "Playing on this tablet while the piano isn't connected.", Volume 60 % and its slider.
  - The resting screen (Display mode after a minute, the idle time shortened): Debussy's portrait, the
    title, CLAUDE DEBUSSY, and the piece's own notes, fetched because the screen asked ("piece:3: found"
    in the Artwork log 22 s after it came to rest), four lines, then "From Wikipedia · CC BY-SA 4.0".
  - Installed fresh: no 1.7.x debug APK is staged, and the release APKs are signed with the release key;
    1.8 adds two settings with defaults and no schema change.
- Tests: 1,213 (1.7.1's 1,163, this run's 48, the merge's 2), 13 skipped: the corpus and library
  fixture tests without `-Pcorpus` (7), Studio's `TranscriberTest` and `ComposerTest` without their
  models (4), `PinnedKeyTest`'s header case (1) and `TabletSoundRenderTest` without `-PpianoSound` (1). `check` passes: `lint` 0 errors, 28 warnings (as 1.7
  and 1.7.1), `checkDebugOnnxTelemetry` and `checkReleaseOnnxTelemetry`. No compiler warnings in the
  app's sources. The greps above and every earlier section's: as stated. The release APK is 13,503,088
  bytes (versionCode 16, "1.8", signed `CN=Steven Piano, O=Steven Jin, C=US`), the debug APK 28,066,776;
  staged as `../apk/steven-piano-1.8.apk` and `-debug.apk`.

---

# v1.9 — Liquid Glass across the functional layer; release 1.9 (versionCode 17)

Read `DESIGN.md › v1.9 — Liquid Glass across the functional layer` first. Steven's request
(2026-09-30), designed by Fable with the `apple-design` skill; tablet first. Built on branch
`glass-pass` from `main` at 1.7.1 (`5ceb03c`), concurrently with M25 in its own worktree. **Not a
release here**: `versionCode`, `versionName`, `Provenance.text` and the provenance manifest are
untouched; the integrator bumps and signs at the merge (1.9, `versionCode` 17: *The merge*, at the end
of this section).

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/ui/components/GlassHeader.kt`: `GlassHeaderPane(scroll, modifier, header, content)`, a
  `SubcomposeLayout` that measures `header` (on its `HeaderBar` glass, padded by the incoming
  `LocalFloatingPadding.top`, the status bar) and lays `content` out at full size under it with
  `LocalFloatingPadding` whose top is the header's height; with a `scroll`, the content is the header's
  own source (`graphicsLayer().hazeSource(source)`, its own layer). `scrolled` = `scroll.canScrollBackward`;
  `presence` animates 0 ↔ 1 over `Motion.FastMs` (`snap()` under reduced motion) and drives the edge,
  the band and the text: `LocalTertiary` and `LocalSecondaryText` lerp from today's greys to `onSurface`.
  The header blurs only while `scrolled`. `PaneSlots` caches both slot lambdas by what they read, so a
  header whose text changes (an import's count) measures the pane again without recomposing the list.
- `M/ui/components/ScrollEdge.kt`: `Modifier.scrollEdges(state: ScrollableState)` (composable factory,
  like the app's other glass readers): draws over its container, in the surface colour, the top band
  (`EdgeBand`, from `EdgeFadeAlpha` at the header's edge to clear, while `canScrollBackward`), the bottom
  band (phones only, above the tab bar's column, while `canScrollForward`) and the rail band (`RailBand`,
  where `onGloballyPositioned` finds the container's start at the rail's edge, right to left too); each
  top and bottom band fades in and out over `Motion.FastMs` (a cut under reduced motion); nothing while
  `!glassAvailable()`.
- `M/ui/components/GlassSheet.kt`: `GlassSheet(onDismissRequest, modifier, sheetState, content)`:
  `ModalBottomSheet` with `containerColor = Transparent`, `tonalElevation = 0`, `dragHandle = null`,
  `contentWindowInsets = { WindowInsets(0) }`; inside, `GlassSurface(shape =
  BottomSheetDefaults.ExpandedShape, fill = Sheet, solid = surfaceVariant)` holding a `Column` padded by
  `BottomSheetDefaults.windowInsets` (as Material pads its own), `SheetGrabber` (32 × 4 dp,
  `onSurfaceVariant`, 22 dp above and below; semantics "Drag handle" with dismiss, and expand or collapse
  as Material's) and the content.
- `M/ui/components/GlassMenu.kt`: `GlassMenuContainer(modifier, shape = shapes.large, content)`;
  `GlassDropdownMenu(expanded, onDismissRequest, modifier, offset, content)` (Material's `DropdownMenu`
  with the glass as its column's modifier, which Material lays inside its surface, `containerColor =
  Transparent`, its shadow kept); `GlassAlertDialog(onDismissRequest, confirmButton, modifier,
  dismissButton, title, text, properties)` (Material's alert-dialog layout on `BasicAlertDialog`: 24 dp in,
  the title in `titleLarge`/`onSurface` 16 dp above the text in `bodyLarge`/`onSurfaceVariant` with
  `weight(1f, fill = false)`, the buttons in a `FlowRow` at the end, 8 dp apart);
  `GlassDialogSurface(modifier, content)` (the dialog's corners); `GlassPopover(expanded,
  onDismissRequest, modifier, offset = (0, 4 dp), content)` (a focusable `Popup` placed by
  `PopoverPosition`: below the anchor, ends aligned, above it where there is no room, 8 dp inside the
  window; `GlassMenuContainer` with 16 dp inside; a 120 ms fade, a cut under reduced motion; from 1.10
  also `alignment = Alignment.End`, or `Alignment.Start`: v1.10 › *The merge*). M25's volume popover
  can adopt `GlassPopover` as it stands.
- `M/ui/screens/keys/KeysPills.kt`: `KeysPills(sustain, onSustain, octaves, modifier)`,
  `OctavePills(canGoDown, canGoUp, onShift)`, the 48 dp `OctavePill` (glass without a blur, ring in
  `LocalTertiary`, `LocalHairline` when disabled).

Changed:

- `M/ui/theme/Glass.kt`: `GlassTokens` gains `SheetAlpha` 0.86, `WideInputScale` 0.2, `SpecularReach`
  24 dp, `EdgeBand` 24 dp, `RailBand` 16 dp, `EdgeFadeAlpha` (= `SheetAlpha`), `BandAlpha` (= 1 − (1 −
  0.86)/(1 − 0.72) = 0.5), `ContrastHairlineAlpha` 0.4, and loses `LensAlpha` and `LensVeilAlpha`;
  `GlassAccessibility(reducedTransparency, increasedContrast)` and `rememberGlassAccessibility()` (the
  High contrast text observer, the debug `noblur` property for reduced transparency alone);
  `rememberReducedTransparency()` kept on top of it.
- `M/ui/components/Glass.kt` (still the only file naming Haze): `GlassEdge.Bottom`; `enum GlassFill(alpha)
  { Bar, Sheet }`; `GlassShown`/`GlassHidden`; `GlassSurface(modifier, shape, source, edge, fill, blur,
  paused, solid, outline, edgeAlpha, band, content)`; `rememberGlassLook(...)` → `GlassLook(modifier,
  glass)`, shared with the menu; `paused` (the transport yielding to a scrolling list): while true the
  look drops its Haze node and is the glass without a blur (`background(surface)`), a cut; when it
  turns false the node comes back under `glassVeil`, the surface drawn over the blur at an `Animatable`
  going 1 → 0 over `Motion.FastMs` (`snapTo` under reduced motion); `LocalYieldBlur: State<Boolean>`
  (false unless provided); `GlassText(glass, fill)` (`LocalOnGlass`; `LocalTertiary` → `onSurface` on a
  bar, `onSurfaceVariant` on a sheet; `LocalSecondaryText` → `onSurface` on a bar); `LocalSecondaryText`,
  `secondaryText()`; `glassBand` (inside a blurring `Top`/`Bottom` bar, `EdgeBand` of the surface from 0 to
  `BandAlpha`); `glassEdge` draws with `edgeAlpha`, the outline's specular line over `SpecularReach` or
  half the height. Every blur `expandLayerBounds = false`; headers and `Sheet` fills
  `HazeInputScale.Fixed(WideInputScale)`, bars `Auto`. `GlassLens`, `LensVeil`, `GlassLensSlot`,
  `LocalGlassLens` and the `lens` parameter are gone.
- `M/ui/NavHost.kt`: `PianoNavHost(frame, requestedTab, onTabShown, content = rememberHazeState(),
  onImport)`; the `NavHost` no longer pads the status bar (it still consumes it) and
  `LocalFloatingPadding` carries it as `top`; `LocalReducedTransparency` and, with increased contrast,
  `LocalHairline` = `onSurface` at 0.4 from `rememberGlassAccessibility()`; `BottomBar`'s glass has its
  band while it blurs. `M/MainActivity.kt` hoists the navigation content's `HazeState` so the share sheet,
  outside the nav host, blurs it. `M/ui/AdaptiveFrame.kt` (`LocalFloatingPadding`'s top, documented).
- `M/ui/components/ReadingWidth.kt` (`readingPadding(available, top, bottom)`), `ScreenHeader.kt`
  (`screenHeaderHeight()`: max(64 dp, 16 dp + the title's and the byline's line heights)),
  `TransportBar.kt` (`PlayPauseButton` is always the filled `Surface`; the lens branch and its import
  gone), `FloatingPlay.kt` (`FloatingPlayButton` a filled circle, no glass).
- `M/ui/screens/library/LibraryScreen.kt` (the pane: the header with the import, artwork and Studio lines;
  the banners as the list's first item; `readingPadding(maxWidth, top, bottom)`; `scrollEdges(listState)`;
  `LibraryItems(…, banners)`; on two panes `LocalYieldBlur` provides `derivedStateOf {
  listState.isScrollInProgress }` to `NowPlayingPanel`), `ImportBar.kt` (`secondaryText()`);
  `M/ui/screens/piano/PianoScreen.kt` (the hub and `SettingsPageView` in panes, a top spacer,
  `scrollEdges`);
  `M/ui/screens/nowplaying/NowPlayingScreen.kt` (the pane, padded clear of the rail; the header moved out
  of `NowPlayingContent`, whose `onUpNext` went with it; the short column scrolls beneath the header with a
  top spacer, the `short` rule measured without the status bar as before), `NowPlayingPanel.kt` (the NOW
  PLAYING row as the pane's header, `heightIn(min = screenHeaderHeight())`, `scroll = null`),
  `NotePanel.kt` (`GlassTransportPanel` without the lens; its glass `paused` while `LocalYieldBlur` reads
  true); `M/ui/screens/keys/KeysScreen.kt` (the pane, padded clear of the rail; the mini-map across the
  width; `KeysPills` over the keys' top edge, the keys `weight(1f, fill = false)` under them; VELOCITY in
  the bottom row), `SustainButton.kt` (the glass pill).
- Sheets on `GlassSheet`: `UpNextSheet`, `PieceDetailSheet`, `AddSheet`, `PinSheet` (both PIN sheets),
  `ChannelVolumeSheet`, `ScheduleEditorSheet` (its `TimeDialog` on `GlassDialogSurface`, the picker's
  containers clear), `ComposeSheet`, `QrCode` (`QrSheet`), `ShareSheet`. Menus on `GlassDropdownMenu`:
  `PieceActions` (`PieceMenu`), `LibraryRows` (`PlaylistTile`, `ComposerTile`), `PlaylistHeader`,
  `ChannelCard`, `SchedulePage` (a schedule's). Dialogs on `GlassAlertDialog`: `LibraryDialogs` (four),
  `SchedulePage` (delete).
- `app/src/main/assets/web/style.css` (the glass tokens, `.sections` as the bar and the rail, the bands,
  `.menu`, `.toast`, `.gate-card`, the editors, `.play-circle`, the accessibility queries), `index.html`
  (`.gate-card`, `.play-circle`), `app.js` (`.scrolled` from a passive, frame-throttled scroll listener).
- Tests: `T/ui/components/GlassTokensTest.kt` (rewritten), `T/web/WebAssetsTest.kt` (+1). Docs: DESIGN.md,
  this file, README.

Shared with M25's run: `TransportBar.kt` (the play control only; the tempo row is Now playing's),
`NowPlayingScreen.kt` (the screen function's body and `NowPlayingContent`'s first lines; the tempo row in
`PieceView` untouched), `NowPlayingPanel.kt` (the header row and the pane's opening and closing lines, the
body's lines kept as they were), `KeysScreen.kt` (the pane's lines, the mini-map and keys, the bottom row's
first item; the connection line untouched). `PlaybackPage.kt`, `Settings.kt` and `AppGraph.kt`: not touched.

## Greps (v1.9)

`dev.chrisbanes`: `Glass.kt` alone. `Color(0x` outside `ui/theme`: none. `hazeSource`/`HazeState`:
`Glass.kt`, `GlassHeader.kt`, `GlassMenu.kt`, `NavHost.kt`, `NotePanel.kt`, `MainActivity.kt`.
`Modifier.blur`: none. `LensAlpha`/`GlassLens`/`LensVeil`: none. `ModalBottomSheet(`, `DropdownMenu(`,
`AlertDialog(` outside the glass wrappers: none (the time picker's `BasicAlertDialog` holds
`GlassDialogSurface`).

## Measured (September 2026, `steven_piano_glass`, API 34, Pixel 7 profile, arm64, 4 GB, debug build)

The emulator runs headless and draws with SwiftShader (the GPU's work on the host's CPU), as M16's did.
The library: `ALL-SONGS.zip` (1,726 pieces) uploaded through the panel's API (the debug build's loopback
listener, the project's test-fixture PIN). Frames: `dumpsys gfxinfo` over 20 s of alternating flings of
the Library list, deep in it (the header blurring throughout), after an unmeasured warm-up of the same
flings, a fresh process each run, Clair de lune playing; runs interleaved with 1.7.1's debug build (the
branch point, same data, same window) to cancel the host's drift (a second emulator from another session
ran part of the time: every figure here is from interleaved pairs). The host is Steven's working
machine, 16 cores; a video call ran on it through some rounds (noted), and where a run's load is given it
is the 1-minute load average as the run began (the emulator alone keeps it near 10). **The emulator's CPU
renderer inflates every figure and moves with the host's load; the real tablet's GPU is the deciding
measurement**, not taken yet.

- **Tablet on its side** (`wm size 2560x1600`, `wm density 240`; the list beside the now-playing panel,
  its roll strip playing; the panel's transport stands under the strip there): **0 janky of 1,202 (0.00 %)
  twice**, 50th / 90th / 99th percentiles 22 / 25 / 30 ms; 1.7.1 in the same runs 0 of 1,201 (0.00 %),
  22 / 25 / 31 ms. Before the fixes below: 21 of 1,202 (1.75 %), 26 / 34 / 48 ms. With the transport
  yielding (nothing changes on this side; 16:13–16:19, the call on): 1 of 1,200 (0.08 %, load 7.2) and
  2 of 1,203 (0.17 %, load 14.8); 1.7.1 0 of 1,203 (load 10.3) and 0 of 1,199 (load 14.0).
- **Tablet upright** (`wm size 1600x2560`; the panel's strip is tall, and the transport floats on its
  glass over the strip's history). **With the transport yielding to the scrolling list** (DESIGN.md ›
  v1.9): **0.58–0.99 % janky with the host quiet** (round A: 28 of 3,626, 0.77 %; 1.7.1 0.08–0.17 %),
  under the 1.5 % target; with the video call on, 1.31–9.23 %, 1.7.1 0.33–5.53 % in the same rounds; the
  build before the rule 16.65 % in round C. Every run (the rule's frames' 50th percentile 28–36 ms against
  1.7.1's 23–34 ms, the GPU's 18–19 ms against 17–18 ms):

  | Round, host | Build | Janky | Load |
  |---|---|---|---|
  | 15:02–15:08, a video call | the rule | 77 of 1,142 (6.74 %) | not logged (17.4 at the end) |
  | | 1.7.1 | 9 of 1,145 (0.79 %) | not logged |
  | | the rule | 76 of 1,142 (6.65 %) | not logged |
  | | 1.7.1 | 64 of 1,158 (5.53 %) | not logged |
  | A, 15:44–15:53, no call | 1.7.1 | 1 of 1,201 (0.08 %) | 6.2 |
  | | the rule | 12 of 1,209 (0.99 %) | 13.0 |
  | | 1.7.1 | 2 of 1,202 (0.17 %) | 12.1 |
  | | the rule | 7 of 1,207 (0.58 %) | 11.8 |
  | | 1.7.1 | 1 of 1,201 (0.08 %) | 14.5 |
  | | the rule | 9 of 1,210 (0.74 %) | 13.3 |
  | B, 16:18–16:24, a video call | the rule | 15 of 1,142 (1.31 %) | 9.5 |
  | | 1.7.1 | 4 of 1,207 (0.33 %) | 12.4 |
  | | the rule | 31 of 1,186 (2.61 %) | 12.1 |
  | | 1.7.1 | 8 of 1,203 (0.67 %) | 14.4 |
  | C, 16:40–16:45, the call, busiest | before the rule (`10b2760`) | 175 of 1,051 (16.65 %) | 14.9 |
  | | the rule | 98 of 1,062 (9.23 %) | 19.2 |
  | | 1.7.1 | 53 of 1,170 (4.53 %) | 18.2 |

  The rule's builds carried the phone's fling pause as well (dropped at review, below), which acts only on
  compact windows and so did nothing here; B and C also skip a no-op fade at a surface's first
  composition, as committed. In the first session, before the rule: **8.45–11.1 % janky** (95–117 of
  1,046–1,124 over four runs, loads not logged), against 1.85 % for 1.7.1, the frames late on the UI
  thread's side (90–117 "slow UI thread"): the one layout where two blurs were live on every frame of a
  scroll, the list's header and the panel's transport. Either alone was within budget: with the
  transport's glass not blurring 1.00 % and 1.25 %; with the header's not blurring 1.68 %; with nothing
  playing 0.59 % (1.7.1-like 0.34 % without the header's blur).
- **Phone** (1080 × 2400 px, 420 dpi; the list under the header and under the tab bar with the mini
  player): the header and the tab bar both blur a scrolling list, as they did before the transport's
  rule. Pausing the header through a fling faster than 500 dp a second was built, measured and dropped at
  review. In one round (16:31–16:40, the call on), without the pause 4.50 % (50 of 1,110, load 8.4) and
  4.80 % (53 of 1,104, load 14.2), with it 5.09 % (58 of 1,140, load 12.0) and 4.73 % (54 of 1,142, load
  12.6), 1.7.1 3.04 % (34 of 1,120, load 14.2) and 4.48 % (50 of 1,115, load 14.6). Its other runs, each
  beside 1.7.1: 4.93 % and 4.72 % (14:50–14:56, loads not logged; 1.7.1 4.26 % and 4.73 %); 4.99 %,
  5.00 % and 6.26 % (loads 11.8, 12.4, 15.8; 1.7.1 4.55 %, 5.95 %, 4.19 % at 9.3, 15.3, 13.4); 4.93 % and
  8.43 % (loads 15.0, 11.6, the call on; 1.7.1 4.54 % and 4.09 % at 14.7 and 20.0). The GPU's 50th
  percentile 17–18 ms in every run with and without the fling pause; 1.7.1's 10–11 ms (17 ms in two of
  its nine). In the first session (loads not logged): 5.49–6.44 % janky against 3.02–4.61 % for 1.7.1
  (whose tab bar already blurs every frame of a scroll); with the header's blur off 3.25–3.69 %; with the
  header's on and the tab bar's off 4.26–4.53 %.
- **The glass**: the Library, the Piano hub and a page, Now playing, the Up next sheet, a row menu, a
  dialog, the PIN sheet and Keys, light and dark, the tablet on its side and upright, then the phone; High
  contrast text (solid surfaces, hairlines at `onSurface` 0.4, no bands, the panel's transport under its
  strip); font scale 2.0 on the tablet's headers and rail (the list's and the panel's headers end level;
  the rail's labels cap at 1.5×) and the phone's header. Screenshots: the run's scratchpad, `glass-shots/`
  (`T-land-*`, `T-port-*`, `P-*`, `w*`).
- **The web panel** (headless Chrome over the DevTools protocol, the same fixture PIN): the gate's card,
  the Library at 1280 px (the section list's glass, the page fading into it) and at 390 px (the strip's
  glass and band once scrolled, no horizontal scroll), a row's menu, Now playing's filled circle, the
  schedule editor's sheet, light and dark; `prefers-reduced-transparency: reduce` (solid) and
  `prefers-contrast: more` (solid, `--hairline` at the content colour's 40 %).
- Tests: 1,163 before (12 skipped), **1,167** after (12 skipped), none failing. `check`: lint 0 errors,
  28 warnings (as 1.7.1, none in this run's files), the ONNX Runtime checks passing. The debug APK
  28,664,812 bytes (the release APK is built at the merge).

## Performance, what was changed on the way

- **Each pane's content is a layer of its own** under its header (`graphicsLayer()` before
  `hazeSource`), so a scrolling list re-records only itself.
- **The wide surfaces blur a copy at a fifth of the resolution** (`WideInputScale`): a header is as wide
  as its pane and blurs on every frame a list scrolls beneath it. With both, the tablet on its side went
  from 1.75 % to 0.00 %.
- **A header blurs only while content is scrolled beneath it**; at rest it is the surface, no Haze node at
  all. The panel's header and Keys' never blur (nothing passes beneath them); Now playing's only on a
  short screen, scrolled.
- **The transport yields to a scrolling list** (decided at review; DESIGN.md › v1.9): on two panes the
  Library provides `LocalYieldBlur` (its list's `isScrollInProgress`) and the panel's transport glass is
  `paused` while it reads true: its look drops the Haze node for that while (one surface recomposed at
  each end of a scroll) and takes it back under the fading veil. Upright, 16.65 % before it and 9.23 %
  with it in the same round at the host's busiest, 0.58–0.99 % with the host quiet.
- **Tried and not kept.** Pausing a compact window's header through a fling faster than 500 dp a second
  (a `NestedScrollConnection` on the pane's body, the tab bar keeping its blur): no measurable gain on
  the phone (above), dropped at review with its code. A pause that kept the Haze node and switched
  Haze's `blurEnabled` off inside its block (no recomposition, the veil over Haze's unblurred scrim): its
  phone runs spread 3.0–11.1 % with heavy tails. In a bisection on the phone, builds with the fling watch
  but no header blur at all held the GPU's 50th percentile at 15–17 ms (seven runs) where builds with
  neither sat at 10 ms (four runs of five): there, the pause's switching cost about what it saved.

## Deviations from the brief, and why

- **Sheets, menus and dialogs at 0.86, not 0.84**: at 0.84 the secondary grey on the paper reads 4.3:1
  over the worst backdrop (a black portrait under a sheet); 0.86 is the least fill, in hundredths, at
  which it reads 4.5:1 on both appearances (`GlassTokensTest`). The tertiary eyebrows on sheets take the
  secondary grey. Kept at review: the contrast rule wins.
- **A header's two appearances**: at rest no edge, no band and today's tertiary byline (the tabs look as
  they always have; UIKit's scroll-edge appearance); the glass (blur, edge, band, text in the content
  colour) shows once content is scrolled beneath it. The Library's fixed hairline under its header
  becomes that edge. The brief asked only that the resting header show no band. Confirmed at review.
- **"A slightly stronger blur band inside the glass edge" is a denser band**: the fill thickens from 0.72
  to 0.86 over the last 24 dp, in the same blur pass. A second blur for the band, or Haze's progressive
  blur (a variable-radius shader), would each cost a bar a second pass on every frame.
- **The rail's band is 16 dp**, the content's own margin, not 24 dp: 24 dp would wash the first letters of
  every row, inset 16 dp. The rail still never blurs: the panes keep clear of it.
- **The Keys pills sit just above the keys' top edge**, never over it (every key's top is its soft end),
  and their ring is the action outline's grey (a pill over the bare background needs a visible edge; the
  hairline token reads 1.3:1). The mini-map spans the width; VELOCITY moved under the keys.
- **The Library's banners scroll with the list** (its first lines) and **its progress lines joined the
  header**, so the list can pass beneath the glass from its top.
- **The now-playing panel's header never blurs**: its own column scrolls inside itself when short, so
  nothing passes beneath the header (the panel's body kept as it was, for the merge with M25).
- **Dialogs are laid out by the app on `BasicAlertDialog`** (Material's `AlertDialog` puts a modifier
  outside its surface); **sheets draw their grabber inside the glass** with Material's accessibility
  actions and keep Material's insets inside it.
- **The web panel's schedule editor** is a sheet laid flat too, as the compose editor the brief named.
- **The share sheet** blurs the navigation content too: `MainActivity` hoists its `HazeState`.
- **No version, provenance or APK staging** (the run's instructions): the integrator's.

## Residuals

- **The emulator decides nothing final.** Its CPU renderer inflates every figure and moves with the host:
  1.7.1's own upright runs span 0.08–5.53 % across these rounds. With the host quiet the tablet upright
  holds the 1.5 % target (0.58–0.99 %), still above 1.7.1's 0.08–0.17 % in the same runs: the list's
  header is the one live blur of a scroll there, where 1.7.1's was the transport's. The phone keeps two
  live blurs on a scroll (the header and the tab bar): 4.50 % and 4.80 % against 1.7.1's 3.04 % and 4.48 %
  in one round. On its side, the brief's window, 0.00–0.17 %.
- At font scale 2.0 the hub's byline wraps to three lines in its 360 dp column, so a page's header beside
  it ends higher (the titles stay level, as M15 designed); it shows only when both are scrolled. Accepted
  at review.
- Not measured on the school tablet: its GPU is the deciding measurement.

## Tests added in v1.9

`GlassTokensTest` rewritten (9, was 6: the two fills and the wide input scale; text on a bar; secondary
glyphs and why nothing secondary or tertiary is text on a bar; text on a sheet, the secondary grey clearing
4.5:1 and the tertiary not; 0.86 as the least such fill; the filled circle's glyph pair 17.2:1 and 16.3:1
and the circle against the band; the band thickening a bar to a sheet over any backdrop, the 24 and 16 dp
bands; the contrast hairline 3.5:1 and 2.5:1, stronger than the token; the specular whites. The lens's
two tests went with it). `WebAssetsTest` +1 (the panel's glass tokens are the app's, value for value; the
reduced-transparency and reduced-motion rules; the filled play circle, no `.lens`). 1,163 before,
**1,167** after.

## The merge: release 1.9 (versionCode 17)

Merged into `main` after 1.8 (`342870a`: M25's tablet sound and the resting screen's credit) as
`fd7bb42`, and released as **1.9**: `versionCode` 17, `versionName` "1.9" (`-PversionCodeOverride`'s
example now 18), `Provenance.text` "Made by Steven Jin · v1.9 · eab16a502f679465", the entry drafted
at the end of `releases/history.json` (`"draft": true`, tag `v1.9`, its notes; no hash or size until
`tools/publish-release.sh` builds it); `latest.json` still names 1.8.

- **One conflict**: DESIGN.md and this file (main's text with 1.7.1's and M25's sections, then this one
  after a separator). `NowPlayingScreen`, `NowPlayingPanel`, `KeysScreen`, the web panel's `app.js` and
  `index.html` and `WebAssetsTest` merged by themselves: M25's speaker stays at the end of Now playing's
  tempo row and at the start of the panel's foot row (now below the pane's glass header), the Keys line
  still says "The tablet plays these keys." while it does, and the panel's tablet volume row sits under
  the channel's volume, its classes the glass pass left as they were.
- **M25's volume popover on glass** (`e08a537`, the merge's first rule): `TabletSoundButton` opened
  Material's `DropdownMenu` with a hand-drawn hairline, the one solid menu left; it now opens
  `GlassPopover` (the menus' glass, below the speaker with the ends aligned, above it where there is no
  room, a 120 ms fade), its content 268 dp inside the glass's 16 dp so the popover keeps its 300 dp. The
  speaker glyph stays where M25 put it (the second rule): the tempo row and the panel's foot row carry no
  glass. `GlassContainersTest` (2): Material's sheets, menus, dialogs and popups are called only inside
  the glass wrappers (the time picker's hand-laid dialog, on `GlassDialogSurface`, the one exception),
  and the speaker's popover is `GlassPopover` with no hand-drawn edge; the section's grep, made a test.
- **Measured at the merge** (`steven_piano_int`, API 34, Pixel 7 profile, 4 GB, `wm size 2560x1600`,
  `wm density 240` as the run measured, the run's own AVD, removed after): 1.9's debug build installed
  over 1.8's; piano-midi.de's 340 pieces added through Add folder (339 imported); a Beethoven sonata
  movement playing, the Library scrolled beneath its header (the glass with its edge and band, the list
  fading under it, the header's text in the content colour) beside the now-playing panel (the filled
  play circle over its transport, the speaker at the foot's start); the Up next sheet on the sheets'
  glass, its grabber inside (Back first takes an expanded sheet to half, as Material's sheets do, then
  closes it); the speaker's popover on glass in light and dark, opening above the speaker (no room below
  it at the panel's foot) with its end at the speaker's end, so on the panel it reaches left over the
  list's pane: "TABLET SOUND", "Silent while the piano is connected.", Volume 60 % and its slider. No
  crash. Frames were not measured again (the run's figures stand; the tablet's GPU decides).
- Tests: 1,219 (1.8's 1,213, this run's 4, the merge's 2), 13 skipped (as 1.8). `check` passes: `lint`
  0 errors, 28 warnings (as 1.8), the ONNX Runtime checks. No compiler warnings in the app's sources.
  The greps above, M25's and every earlier section's: as stated. The release APK is 13,514,380 bytes
  (versionCode 17, "1.9", signed `CN=Steven Piano, O=Steven Jin, C=US`), the debug APK 28,122,664; staged
  as `../apk/steven-piano-1.9.apk` and `-debug.apk`.

---

# v1.10 — M26: Steven Piano Cloud, the relay client

Read `DESIGN.md › v1.10 — M26` first, and the plan's "Steven Piano Cloud" section (the decisions, the
protocol). Built on the branch `m26-relay` from the 1.9 release commit (`ec7dedc`), beside R1 (the relay
and the console, `cloud/`, branch `cloud-r1`), a commit a step. **No version bump, no `Provenance.text`
change, no provenance signing, no APK here**: the integrator's.

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/web/relay/`: `RelayProtocol.kt` (`RelayProtocol`, `Caps`, `RelayMessage` and its fifteen messages,
  `Frame`), `BodyPipe.kt`, `RelayedSession.kt` (`RelayedSession`, `RelayedResponse`), `RelayClient.kt`
  (`CloudStatus`, `RelayConfig`, `StatusSource`, `RelayClient`), `RelayCommands.kt` (`CommandResult`,
  `CommandHandler`, `RelayCommands`), `SealedSecret.kt` (`SecretSealer`, `KeystoreSealer`, `PlainSealer`,
  `CloudSecrets`), `Enrolment.kt` (`EnrolResult`, `CloudAddress`, `Enrolment`), `RelayStatus.kt`.
- `M/net/HttpPost.kt` (`PostAnswer`, `PostTransport`, `UrlConnectionPost`, `HttpPost`), beside `HttpFetch`.
- `M/ui/screens/piano/pages/CloudSection.kt` (`CloudSection`, `EnrolSheet`, `CloudCopy`).
- `third_party/okhttp/LICENSE.txt` (OkHttp's own, at `parent-4.12.0`).
- Tests: `T/web/relay/` `RelayProtocolTest`, `BodyPipeTest`, `WebServerRelayTest`, `FakeRelay` (the relay's
  side over `ws://`, NanoWSD), `RelayClientTest`, `RelayCommandsTest`, `EnrolmentTest`, `SealedSecretTest`,
  `RelayStatusTest`; `T/ui/screens/piano/pages/CloudCopyTest`.

Changed (each addition small and marked v1.10 — M26): `gradle/libs.versions.toml`, `app/build.gradle.kts`
(OkHttp); `AUTHORS`; `M/web/WebServer.kt` (the seams below), `WebSocketHub.kt` (relayed members),
`WebBackend.kt` (`WebAddresses.cloud`), `WebApi.kt` (`web.cloud`), `WebPanel.kt` (`cloud`, `enrolments`,
`cloudSecrets`, `cloudOverride`, `cloudLink`); `M/service/WebService.kt`; `M/settings/Settings.kt`;
`M/diag/DiagnosticsExporter.kt`; `M/update/UpdateOverride.kt` (`CloudOverride`); `M/AppGraph.kt`
(`setCloudEnabled`, `enrol`, `forgetCloud`, `startWebIfOn`); `M/ui/screens/piano/GroupSummaries.kt`,
`PianoViewModel.kt`, `pages/RemotePage.kt` (one line: the section); `AndroidManifest.xml` (the web
service's comment and its special-use subtype, which now names the relay); `assets/web/app.js`,
`request.js`, `index.html` (the offline card), `style.css` (one rule); tests `WebSocketHubTest`,
`WebAssetsTest`, `WebApiTest`, `SettingsRepositoryTest`, `GroupSummariesTest`, `DiagnosticsExporterTest`;
`DESIGN.md`, `README.md`, `docs/SECURITY_AUDIT.md`.

## The dependency

OkHttp 4.12.0 (Apache-2.0), named only under `M/web/relay/` (`grep -rln "okhttp3\|import okio"
app/src/main/java` lists only `web/relay/`); its consumer R8 rules come inside its jar. Okio was already
in the app through AndroidX DataStore: OkHttp's 3.6.0 resolves to that 3.9.1. `HttpFetch` (GitHub,
Wikipedia) stays `HttpURLConnection`, and so is the enrolment's `HttpPost`. The debug APK grows by
491,180 bytes (28,122,664 → 28,613,844); the release APK is the integrator's to measure. `lint`: 0
errors, 29 warnings (1.9's 28, and "a newer OkHttp than 4.12.0 is available: 5.5.0", the pin the plan
chose).

## The seams: one route table for both edges (`WebServer`)

The transport-agnostic seam is a synthetic `IHTTPSession` fed to the audited server:

- `serve(session)` is now `answer(session, "http", allowedHosts(), cookiePath = "/")`, byte for byte what
  it was (WebServerTest's 29 unchanged and green).
- `serveRelayed(session, host, prefix, scheme = "https")`: a method NanoHTTPD doesn't know → 501, an
  address that doesn't decode → 400 (what `RequestHead` refuses on a listener), with the security headers;
  else `answer(session, scheme, setOf(host), "$prefix/", relayed = true)`.
- `answer(...)` carries an `Edge(scheme, cookiePath, relayed)` into the dispatch and every `Call`: the
  `Host` must be one of the allowed (the relay's host exactly, lower-cased, no port added); an `Origin`, when
  sent, must be `<scheme>://<host>`; the cookies (`WebCookies.session`, `endSession`, `guest`, each now with
  `path` and `secure`, their listener output unchanged) are `Secure` over HTTPS and live under
  `<prefix>/`; the policy's socket is `wss://<host>` over HTTPS (`securityHeaders(host, scheme)`); a relayed
  request that asks to upgrade is 404 (the relay bridges sockets itself); the guests' pages and API
  (`/request`, `/request.js`, `/poster`, `/api/public/*`) are 404 while Guests can request is off.
  `/style.css` stays: the panel's own page needs it.
- `admitSocket(cookies, origin, expectedOrigin): Int`: 401 without a valid session, 403 unless the origin
  is exactly the expected one, else `SOCKET_ADMITTED` (101). `/ws` on a listener asks it, and so do the
  relay's bridged sockets.
- `RelayedSession(method, rawPath, query, headers, address, body)`: the path and the query decoded exactly
  as NanoHTTPD 2.3.1's `decodeHeader`/`decodeParms` do (`URLDecoder`, a name without `=` taking ""); a path
  not starting `/`, anything outside printable ASCII, or a bad escape reads as no `uri`; only the forwarded
  headers kept, their names lower-cased; the cookies from `cookie` (NanoHTTPD's own `CookieHandler`, made by
  a never-started server); the address the relay's `CF-Connecting-IP` when it looks like one, else
  "unknown"; `execute` and `parseBody` unsupported.
- `RelayedResponse.write(response)`: the status, `Content-Type` from the answer, the headers the server
  ever sets (the five security headers, `Cache-Control`, `Set-Cookie`, `Retry-After`, `Allow`) by their
  values, `Content-Length` from the body read whole (every route answers at a fixed length; at most 16 MB).
  `WebServerRelayTest` proves every route of the table, the pages, the refusals and the login lock answer
  the relay with the listener's status and headers (read over `RawHttp`), but for the policy's socket and
  the cookie's path and `Secure`, and that every header a listener sends but `Date` and `Connection` is one
  the relay is given.

## The protocol, as the tablet speaks it (`RelayProtocol`)

The relay's `cloud/src/shared/protocol.ts` at `72592f4`, matched field for field and byte for byte on the
frames:

- **Frames**: `id: u32 big-endian | kind: u8 | payload ≤ 64 KB`; kinds 1 `req.chunk`, 2 `req.end`,
  3 `res.chunk`, 4 `res.end`. `Frame.decode` refuses less than the 5-byte head or more than a chunk.
- **Text**: JSON of at most 64 KB counted in UTF-8 bytes (stricter than the relay's UTF-16 count), at most
  six deep (`WebApi.depthOf`, before `org.json` parses it), every id a whole number from 0 to 2³² − 1, every
  field of its type and length; anything else is no message and is dropped. A `hello` must name a piano's id
  of the relay's form, a host of the relay's form, and the prefix `/p/<id>`; its caps are taken within
  bounds (a chunk of 1 B–64 KB, a window from a chunk to 16 MB) or the defaults (64 KB, 1 MB, 100 MB).
- **The tablet's messages**: `status`, `req.credit {id, bytes}`, `res {id, status, headers, length}`,
  `ws.accept`, `ws.refuse {status}`, `ws.text {data}`, `ws.close {code, reason}`, `cmd.result {ok, message}`,
  `secret.ack`.
- **The status** (`RelayStatus`): `app {version, code}`, `firmware` (the piano's report's `fw`), `link` (the
  state's name), `player {status, title, composer, positionMs, durationMs, channel {key, name}}`, `guests
  {open, approveFirst}`, `panel {web, host}`, `library {pieces, pack}` (`pack` null until M27; from its merge
  the version loaded, 0 for none), `channels`
  (at most 32 `{key, name}`: the console lists them), `at`; texts cut to 200 characters. On hello, every
  30 s, and at most 2 s after a change (the changes conflated, then 2 s for more to join them).

## The client (`RelayClient`)

- **The connection**: OkHttp's WebSocket to `wss://<host>/tablet` (`CloudAddress.socketUrl`),
  `Authorization: Bearer <pianoId>.<secret>`, `Sec-WebSocket-Protocol: steven-piano-relay-1` (the 101 must
  echo it, else the tablet closes 1002), the app's user agent; pings every 30 s, 15 s to connect, no
  redirects followed, no retries of OkHttp's own; a hello within 15 s or it closes (1002); a hello for
  another piano's id closes it (1008). `config()` is read before every try (the sealed secret opened
  then), so a rotated secret takes effect at the next connection; null: not enrolled.
- **Requests**: at most 8 at once (the relay's cap mirrored): past it, `res 503 {"error":"busy"}` at once,
  as is a pool that refuses. Each runs `serveRelayed` on a pool of 4 threads (4 more waiting); its body is a
  `BodyPipe` of the hello's window: the relay's chunks queue there (never more than the window: a chunk past
  it is the relay's breach and ends the request's body), the server reads it as any body, and what it reads
  goes back as `req.credit` in 64 KB steps and whatever is owed before a read waits (so the relay is never
  starved); a read waits at most 30 s for the next chunk. The answer: `res` (the known headers, `length`),
  the body in chunks of the hello's size, `res.end` (also after a 204); before each chunk the socket's queue
  must be under 1 MB (OkHttp closes a socket past 16 MB). `req.abort`: the pipe fails at its next read and
  nothing is answered. A request whose answer throws is answered 500 by the client itself
  (`RelayedResponse.refusal`, the security headers included).
- **Browsers' sockets**: `ws.open`'s `host` must be the hello's; then `admitSocket(cookies, origin,
  "<scheme>://<host>")` (401, 403: `ws.refuse`), at most 4 (`ws.refuse 503`), then `ws.accept` and
  `WebSocketHub.attach`: the hub's state, each change and the progress go out as `ws.text`
  (`RelayProtocol.wsText`: a state message too long for one frame gives up rows from the end of Up next,
  the queue's `ids` and `index` whole, then its unused `uids`; one that still doesn't fit is dropped, the
  next state following); `ws.close` from the relay detaches it; a session that ends closes it (4000) at the
  hub's next ping; a hub that stops closes it (1001). Relayed members are never pinged (the relay answers the
  browsers' "ping" itself).
- **Commands**: `cmd` → `RelayCommands` → `cmd.result`; after `status`, a status report at once.
- **Rotation**: `secret` → `CloudSecrets.keep` (sealed, and only once what was sealed opens again) →
  `secret.ack`; nothing is acknowledged that wasn't kept.
- **Tries**: 4401, or a handshake answered 401 (a revoked or refused secret), stops as **Revoked**; 4403 as
  **Disabled** (the console forgot the piano); 4409 waits 60 s; anything else waits 1 s × 2ⁿ (at most
  300 s, ±20 %, the count starting again after a connection that had its hello). `nudge()` (the web
  service's network callback and its 30 s look) ends a wait at once while the device is online. The wait's
  reason: "Waiting for a network" when offline, "Another tablet connected with this enrolment" after 4409,
  else "The relay can't be reached".
- Nothing it logs holds a secret, a cookie or a request's content: the trail (`LinkLog`) gets "Cloud:
  connected to <host>", the ends and waits, the console's commands by name and outcome.

## Secrets, enrolment, commands

- `KeystoreSealer`: an AES-256-GCM key in the AndroidKeyStore, alias `steven-piano-cloud`, made at the first
  seal, purpose encrypt/decrypt, randomized encryption required; each seal a fresh 12-byte IV the Keystore
  picks; `v1:` + base64(IV ‖ ciphertext ‖ tag) in DataStore's `cloudSecret`. A key that has vanished, or a
  text that isn't one it sealed or was changed, opens to nothing: the tablet reads as not enrolled.
  `forget()` deletes the key. No `security-crypto`. `PlainSealer` stands in on the JVM.
- `Enrolment.enrol(host, code)`: `CloudAddress.host` (lower case; an `https://` and a trailing slash
  forgiven; the relay's host form; a port 1–65535) and `CloudAddress.code` (`XXXX-XXXX` from the relay's
  32-letter alphabet, any case, spaces or dashes); one `POST https://<host>/api/enrol` of `{"code": …}`
  alone (no name, no model: nothing that names the device) through `HttpPost` with its allow-list: `https`,
  the typed host and port exactly, the path `/api/enrol`, no user info, query or fragment, no backslash;
  never a redirect followed; 16 KB of answer at most. `{pianoId, secret}` must be of the relay's forms. The
  refusals in plain words (404 the code, 429 a minute, 400/411/413/415 not a code, a failed connection).
  `AppGraph.enrol` remembers the typed address first, then seals the secret (`CloudSecrets.seal`) and writes
  the address, the piano's id and the sealed secret in **one** DataStore edit (`setCloudEnrolment`), so the
  client never sees a new id with an old secret; `WebPanel.enrolled()` makes the web service start the
  client again for it.
- `RelayCommands`: the allow-list `transport {action}`, `play {pieceId}`, `playChannel {key}`,
  `stopChannel`, `guests {open?, approveFirst?}`, `library.load` (through a `libraryLoad` the library pack
  will pass in M27; until then "Loading Steven's library isn't available on this tablet yet."; from M27's
  merge `RelayCommands.libraryLoad`, v1.10 — M27 › *The merge*), `status` (a
  line of what plays); exactly those arguments, of their types, refused otherwise with the reason (the relay
  checks them first too); acted on through `WebBackend` as the panel's own routes act; a trail line for
  each, its name and outcome only.

## Settings, the service, the UI, the pages

- `PianoSettings`: `cloudEnabled` (off), `cloudHost` (typed, remembered after Forget), `cloudPianoId` (read
  only when of the relay's form), `cloudSecretSet`; `cloudEnrolled` = all three. The sealed secret is read
  on its own (`cloudSecret()`), like the PIN's hash, never in `settings`; `settings.txt` in diagnostics says
  `cloudEnabled`, `cloudHost`, `cloudEnrolled`, never the id or the secret. `forgetCloud()` drops the id and
  the secret and turns the switch off in one edit; `AppGraph.forgetCloud` then deletes the key.
- `WebService` runs while `(webEnabled || cloudEnabled) && webPinSet`. With Web control off it listens
  nowhere (and reports so); it keeps the hub for the relay's members. It makes the relay's `WebServer`
  (host "relay", port 0, never started; the process's sessions, guard, requests, backend, assets, hub and
  poster) and the `RelayClient` once the tablet is enrolled and the switch is on, again for a new
  enrolment (the key: the host, the id and `enrolments`), and stops it otherwise; the client's state goes to
  `WebPanel.cloud`; `nudge()` from the network callback and the 30 s look; "· Cloud" in the notification
  while connected, "Remote access on" with the cloud alone. `stopNow()` and `onDestroy()` stop it.
- `WebPanel.cloudLink()`: `<scheme>://<host>/p/<id>/`, the hello's host once connected, the typed one
  before, while on and enrolled; in the state as `web.cloud`. `GroupSummaries.remote`: "On · host · Cloud",
  "Cloud", "Off".
- `CloudSection` on the Remote page after GUESTS (DESIGN.md › v1.10 — M26): its rows, the `EnrolSheet` on
  `GlassSheet`, the QR as `QrTile`/`QrSheet`, Forget's `GlassAlertDialog`; `CloudCopy` words the status line.
- `app.js`: `const ROOT = location.pathname.replace(/\/(index\.html)?$/, '')` and every request built as
  `ROOT + '/api/…'` (48 of them, one inside a CSS `url()` as `${ROOT}/api/…`; `WebAssetsTest` greps that every `'/api/` is `ROOT + '/api/` and that no
  template starts a bare `/api/`); the socket `wss:` when the page is `https:`; `{"error":"offline"}` (503)
  shows the offline card in place of the gate before the panel has its state (a look again every 10 s),
  after it a line at the head of the window (`.toast.offline`), until any answer comes. `request.js`: its
  `ROOT` (the page's path without `/request`), and the relay's offline answer in its words.
- `CloudOverride` (debug builds on an emulator only, beside `UpdateOverride`): `adb shell setprop
  debug.stevenpiano.cloudurl http://10.0.2.2:8787` before the app starts makes that origin the relay
  (enrolment to its `/api/enrol`, the client to its `ws://…/tablet`, the panel's scheme `http`); the debug
  network security config already allows plain HTTP to 10.0.2.2 and nowhere else. The relay's own
  `PUBLIC_HOST` (`npm run dev` sets `localhost:8787`) names the host browsers use in its hello, so the
  tablet needs no host of its own for it.

## Measured (2026-09-30, `wrangler dev` of `cloud/` at `72592f4`, the run's AVD `steven_piano_m26`, API 34, `wm size 2560x1600`, `wm density 240`, the debug build)

`cloud/` from R1's commit, extracted to the run's scratch and started with `npm run dev` (the relay on
:8787, the console on :8788); the tablet enrolled with a code from the console's `POST /api/enrol-codes`.

- **The CLOUD section**, every state it has: no PIN ("Set a PIN first"), a PIN and no enrolment ("Enrol this
  tablet first"), the enrol sheet empty, filled, and with the relay's 404 ("That code isn't right, or it has
  expired…"), enrolled and off, on and **Connected** within a second with the link and its QR ("Remote
  control · Cloud" on the hub), the large QR sheet, **"The relay can't be reached · retrying in 7 s"** with
  the relay stopped, **"Waiting for a network · retrying in 2 min"** with the emulator's Wi-Fi and data off,
  back to Connected on the 30 s look once the relay was up again, **"Revoked in the console. Enrol again."**
  after the console's revoke (4401; after an app restart one try, 401, no more), enrolled again with a new
  code (Connected at once), **"Removed from the console. Enrol again."** after the console forgot the piano
  (4403), Forget's dialog, and forgotten (the address kept). Web control on too: "On · Cloud" on the hub
  and the notification "Guests: http://10.0.2.16:8737/request · Cloud"; the connected section in dark.
  "Connecting…" lasts under a second against a local relay (the words are `CloudCopyTest`'s).
- **The console** saw the tablet online with its status (1.9, build 17, the link, the player, 28 pieces,
  the eight channels); its commands answered: status "Idle", transport pause "Paused.", play "Playing.",
  playChannel nocturnes "Playing the channel.", stopChannel, guests open "Guests can request; each waits
  for approval." (the Remote page's switches followed), library.load "…isn't available on this tablet
  yet.", a transport "eject" refused by the relay itself; **rotate** "committed": the tablet kept the new
  secret, acknowledged it, and came back with it after the relay's restart.
- **The panel in headless Chrome** at `http://localhost:8787/p/<id>/`: the gate; the PIN; the panel
  "Connected · localhost:8787" over the bridged socket; a zip of 28 pieces (872,183 bytes) sent through Add
  with the upload held to ~120 KB/s: "Sending 36%" with its bar, then "Sent to the tablet", "Imported 28
  pieces"; the Library through the relay with the composers' portraits; a Chopin nocturne played from it,
  Now playing following live (0:03, six seconds later 0:09); `adb shell am force-stop`: the line "The piano
  is offline. The panel comes back when its tablet does.", the connection "Offline"; a fresh page then: the
  relay's offline page; the app started again: the panel came back by itself, to the gate (the sessions went
  with the process). A guest's request page through the relay at 390 px, Guests on: "Thanks — it joins the
  queue once it's approved."
- **`relay-checks.txt`** (raw sockets through the relay): 401 without a session (a read, a change); 403
  without the panel's header, from another site's origin, from the tablet's LAN origin; 411 for a chunked
  body (the relay's edge); 413 over 100 MB (the relay Worker; `wrangler dev` reads the body before the
  answer, where Cloudflare's edge refuses on the length alone), over the tablet's 64 MB for a zip (no byte
  read), a JSON body over 64 KB; 415 for a `.txt`; 404 for the guests' page while guests were off; 204 for
  the panel's own change; 200 with `Strict-Transport-Security` (the relay's), the tablet's policy naming the
  relay's socket, `Set-Cookie` with `Path=/p/<id>/`; the panel's socket without a session: open, then
  closed 1008 "Enter the PIN first." (the tablet's `ws.refuse 401`); offline: 503 `{"error":"offline"}` for
  the API and the relay's page for a page.

## Deviations from the brief, and why

- **`serveRelayed(session, host, prefix, scheme = "https")`**: a fourth parameter, so the debug build's
  local relay (plain `http` at `localhost:8787`) works end to end; release builds only ever reach
  `wss://`/`https://` at the typed host. **`admitSocket`** answers `SOCKET_ADMITTED` (101) when it admits.
- **`CloudStatus.Waiting(reason, retryInMs, since)`**: `since` added, for the countdown.
- **`RelayClient`'s parameters**: `commands` is a `CommandHandler`, `status` a `StatusSource` (the report and
  its changes), `secrets` `suspend (String) -> Boolean`; `online`, `random`, `sleep` (the waits: the tests
  record them), `statusSettleMs` and `userAgent` injected besides the brief's clock and log. The brief's
  "backoff with an injected clock" is the injected waits and jitter (the clock stamps the states).
- **The relay's contract as R1 fixed it** (the coordinator's list): `link` as a string, `player.channel` as
  `{key, name}`, `channels` in the status; a state message fitted to one `ws.text` frame; cookies under
  `/p/<id>/` with no `Domain`; 4403 read as "removed from the console".
- **No public-host override**: the relay's hello names the host browsers use (`PUBLIC_HOST`), so
  `CloudOverride` stands in for the relay's origin only.
- **The enrolment sends the code alone** (the relay would take a name and a model): nothing that names the
  device leaves the tablet.
- **The switch never turns itself on** after an enrolment; enrolling and going online are two decisions.
  **Forget keeps the typed address** for the next enrolment.
- **The public link hides** once revoked, removed or keyless (seen on the emulator: it led nowhere).
- **The offline card** is static markup in `index.html` (the page's CSP forbids building it from a string)
  and one CSS rule places the offline line at the head of the window; a page opened while the piano is away
  is the relay's own offline page.
- **Relayed browsers' sockets close with 4000** when their session ends (a private code; the relay passes
  1000 and 3000–4999 through).
- **No `tools/relay-stub/`**: R1's relay was ready (`72592f4`), so the evidence ran against it; the JVM tests
  keep `FakeRelay`.
- **The web service's special-use subtype** now names the relay as well as the person's own network.

## Residuals

- **Sessions live in the app's memory**: an app restart signs relayed browsers out, as it does the
  listeners' (seen: the panel came back to the gate).
- **The login guard is shared** by the listeners and the relay: wrong PINs from the internet count against
  the global gentle lock (W1) too; the relay's own 10 a minute per address and the guard's per-address lock
  come first.
- **TLS is the platform's**, with OkHttp's hostname check; no certificate pinning (Cloudflare rotates its
  certificates).
- **Doze**: the tablet on its charger stays reachable; a tablet asleep off power drops the connection until
  Android's maintenance windows or the app coming back (the wait's "nudge" on the network's return).
- **A state message too long even with Up next's rows given up** (a queue of thousands of pieces) is dropped
  for relayed browsers; the next one that fits follows.
- **The Keystore sealer** is exercised on the emulator (enrol, rotate, reconnect), not on the JVM.
- **Not yet on the real tablet or on Cloudflare**: Steven's deploy (`cloud/README.md`), then the school
  tablet, a phone on mobile data, a guest's request. The release build (R8 with OkHttp's rules) is the
  integrator's.
- `web.cloud` is in the panel's state but the panel doesn't show it yet.

## Tests added in M26

`RelayProtocolTest` (9), `BodyPipeTest` (6), `WebServerRelayTest` (8), `RelayClientTest` (10),
`RelayCommandsTest` (4), `EnrolmentTest` (4), `SealedSecretTest` (3), `RelayStatusTest` (2), `CloudCopyTest`
(1), `WebSocketHubTest` +4, `WebAssetsTest` +1, `SettingsRepositoryTest` +1, `GroupSummariesTest` +1;
`WebApiTest` and `DiagnosticsExporterTest` changed. 1,219 before, **1,273** after (13 skipped, as before).

## The merge: into `main`, before 1.10 (still 1.9, versionCode 17)

Merged into `main` after 1.9 and the cloud (`5640288`: 1.9, R1's `cloud/` folder, the signer's change) as
`ebdc4aa`. Not a release: 1.10 is cut after M27 and the audit, so the version stays 1.9 (versionCode 17),
no entry is drafted in `releases/history.json` and no APK is staged.

- **One conflict**: the README, where main's one line at the end of Web control pointing at
  `cloud/README.md` (added with the cloud's merge) met this run's Cloud section in the same place: both
  kept, the line now leading the Cloud section. Everything else merged by itself: `WebServer`,
  `WebService`, `Settings`, `DiagnosticsExporter` and its test, `GroupSummaries`, `PianoViewModel`,
  `AppGraph`, the web panel's assets, the manifest. `CloudSection` is on the glass (`GlassSheet`,
  `GlassAlertDialog`), as `GlassContainersTest` requires.
- **The panel's speaker opens its popover within the panel** (`a54e506`, the 1.9 review): `GlassPopover` gains
  `alignment`: `Alignment.End` as before, or `Alignment.Start`, the popover's start at the anchor's start
  (each mirrored in right to left). Its placement is now `popoverPosition`, a pure function that
  `PopoverPosition` calls with the offset and the margin in pixels. `TabletSoundButton` and
  `TabletSoundSpeaker` pass `popoverAlignment` through; the panel's foot row passes `Alignment.Start`, so
  the popover opens above the speaker and to its end, inside the panel, where ends aligned it reached back
  over the divider onto the list's pane; Now playing's tempo row keeps the ends. `GlassPopoverTest` (8):
  ends and starts aligned, right to left, the offset, above where there is no room below, the window's
  margin, the panel's case against its divider, and the two call sites.
- **Measured at the merge** (`steven_piano_int`, API 34, Pixel 7 profile, 4 GB, `wm size 2560x1600`,
  `wm density 240`, the integrator's own AVD, removed after; the shots in the session's
  `integrate-m26-shots/`): the merged debug build installed over 1.9's; three piano-midi.de pieces through
  Add folder; Für Elise playing beside the Library. The panel's speaker (1487–1559 px, the pane from 1463)
  opened its popover above it with its start at the speaker's start: inside the panel, over the
  transport's start. On a second boot, 1.9's build and then the merged one in the same session: 1.9's
  popover at 1109–1559 × 1217–1438, across the divider over the list; the merged build's at 1487–1937 ×
  1217–1438, 4 dp above the speaker as before. (On the first boot, whose top inset was 49 px taller, it sat
  31 px higher: `popoverPosition` keeps it 8 dp inside the window's visible height, which that inset
  shortened; 1.9's placement had the same bound.)
  Piano › Remote control without a PIN: CLOUD's switch "Set a PIN first", "Cloud address · Not set",
  **Enrol with code** "Get a code from the console"; with a PIN (generated, not recorded) the switch reads
  "Enrol this tablet first", not enrolled; the enrol sheet on the sheets' glass (the relay's address as its
  placeholder, Enrol waiting for a code), cancelled. Web control on: "No Tailscale address yet: the panel
  waits for one (or for Panel on Wi-Fi too)…"; with Panel on Wi-Fi too, `http://10.0.2.18:8737` with its QR
  and the hub's row **"On · 10.0.2.18"**, the notification "Web control on"; listening on 10.0.2.18 and
  127.0.0.1 only; through `adb forward`, a foreign Host refused ("This address isn't the panel's."), the
  gate 200 with the listener's own, `/api/state` 401 without a session. No crash (the crash buffer empty).
- Tests: **1,281** (1.9's 1,219, M26's 54, the merge's 8), 13 skipped (as 1.9). `check` passes: `lint` 0
  errors, 29 warnings (1.9's 28 and OkHttp's newer version, 5.5.0; M26 keeps 4.12.0 on purpose), the ONNX
  Runtime checks. No compiler warnings in the app's sources (the release compile ran whole). The greps
  above and every earlier section's: as stated; `okhttp3` and `okio` only in `web/relay/RelayClient.kt`.
- **The release build**, R8 with OkHttp's own rules: no R8 warnings; 13,692,055 bytes (1.9's 13,514,380:
  +177,675), versionCode 17, "1.9", signed `CN=Steven Piano, O=Steven Jin, C=US`; OkHttp's client, its
  WebSocket and the relay's classes kept, renamed. Not staged in `../apk/`: 1.10 is cut after M27 and the
  audit. The debug APK is 29,321,129 bytes.
- The provenance manifest re-signed last, the signer's sources now with `cloud/`'s.

---

# v1.10 — M27: Steven's library from GitHub

Read `DESIGN.md › v1.10 — M27` first. The plan's R2b (`~/.claude/plans/if-wer-are-doing-adaptive-stonebraker.md`
› *Steven Piano Cloud — relay, console, library pack (1.10)*), built on the branch `m27-library` from `main`
at `5640288` (1.9 and the merged `cloud/`), beside M26 (the relay client) in its own worktree; commits a
step each. **No version bump, no `Provenance.text` change, no provenance signing, no APK staging**: the
integrator's, at 1.10. The pack itself **is published** (below); `releases/library.json` on `main` names
it once this branch is merged and pushed, and until then tablets are offered nothing (the manifest's
address answers 404: a check fails quietly, a Load says "Couldn't reach the download server.").

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `tools/publish_library.py` (builds, checks, publishes); `releases/library.json` (version 1).
- `M/library/LibraryManifest.kt` (`LibraryManifest`, `InvalidLibraryManifest`), `M/library/LibraryPack.kt`
  (`PackState`, `LibraryFailures`, `OfferedPacks`, `LibraryPack`), `M/library/LibraryOverride.kt`.
- `M/service/LibraryService.kt`.
- `M/ui/LibraryCopy.kt`; `M/ui/screens/library/LibraryLicenceSheet.kt`.
- Tests: `T/library/LibraryManifestTest` (6), `T/library/LibraryPackTest` (17), `T/data/imports/LocalZipTest`
  (5), `T/ui/LibraryCopyTest` (4).

Changed (each addition small and marked v1.10 — M27): `M/update/UpdateSource.kt` (`Kind.Library` and its
addresses); `M/data/imports/ImportSource.kt` (`ImportSource.LocalZip`, `openLocalZip`); `M/data/imports/
IndexCsv.kt` (`Row.sha256`, `sha256s()`); `M/service/ImportService.kt` (`start` refuses a `LocalZip`: one
line, see *Deviations*); `M/settings/Settings.kt` (`libraryPackVersion`, `setLibraryPackVersion`);
`M/AppGraph.kt` (`libraryPack`, `importLibraryPack`, the schedule beside the updater's, the sweep at start);
`app/src/main/AndroidManifest.xml` (`LibraryService`, dataSync); `M/ui/screens/library/LibraryScreen.kt`
(`EmptyLibrary`'s Load, the + sheet's row, the licence sheet, `LibraryBar` in the header), `AddSheet.kt`
(`LibraryEntry`, `library` parameter, `SheetOption(enabled)`), `ImportBar.kt` (`LibraryBar`); tests
`CsvReaderTest.kt`'s `IndexCsvTest` (+4), `UpdateSourceTest` (+4), `SettingsRepositoryTest` (+1); `AUTHORS`,
`DESIGN.md`, `README.md`, `docs/SECURITY_AUDIT.md`.

## The pack and its publishing (`tools/publish_library.py`)

- **Built from** `--midi` (default `../midi`): the rows of its INDEX.csv (read as CSV, UTF-8, header
  `collection,composer,title,size_kb,path`; a `sha256` column already there is recomputed), each path plain
  and relative (no leading slash, backslash, `.`/`..` or hidden segment), a `.mid`/`.midi` file that exists,
  at most 8 MiB (the importer's cap). Each file once: a file whose SHA-256 an earlier row's file has is left
  out and named. Beside them `INDEX.csv` (the kept rows, their columns as they were plus `sha256`),
  `README.md` and `_maestro-metadata/LICENSE` (MAESTRO's CC BY-NC-SA 4.0). Nothing else of the folder.
- **Deterministic**: the index's order, `ZipInfo` date 1980-01-01, mode 0644, deflate level 9, UTF-8 names
  (the flag set for non-ASCII): the same folder builds the same bytes (checked: two builds, one hash).
- **Checked before anything is written**: the zip at most 200 MiB (`UpdateSource.MAX_LIBRARY_BYTES`), at most
  20,000 entries (`ImportLimits.ZIP_ENTRIES`), INDEX.csv at most 2 MiB (`ImportLimits.INDEX_BYTES`); then the
  written zip is read back (no name twice, every name plain, every row's `sha256` its file's own) and the
  manifest held to 4 KB. A version below the manifest's refused ("a pack's version only rises").
- **The manifest** `{version, file, url, sizeBytes, sha256, pieces, notes, licences}` is written to
  `--manifest` (default `releases/library.json`) and beside the zip (`--work`, default `build/library/`,
  ignored by git). `--url-base` builds a test pack served elsewhere (the emulator's).
- **`--upload`**: `gh release upload library --clobber` when the release exists, else `gh release create
  library --title "Steven's library" --target main --latest=false` with a body naming what it is; both assets
  (the zip, `library.json`) read back through `gh release download` and compared; the public URL fetched as a
  tablet would (200, the pack's bytes, where GitHub redirected).
- **Run on 2026-09-30** (`python3 tools/publish_library.py --version 1 --upload`): release **`library`**
  created (22:14:55Z), not Latest (v1.9 stays Latest), assets `library-v1.zip` (**61,237,277 bytes, SHA-256
  `358cc000dd00edaf6c683517e3f4f0cfc935bdf98f2b7880126a9e30607c7e2f`**, **1,726 pieces**, 1,729 entries,
  92,865,812 bytes of MIDI, INDEX.csv 378,843 bytes; left out: `piano-midi.de/borodin/bor_ps1_format5.mid`,
  the same bytes as `bor_ps1_format4.mid`) and `library.json` (570 bytes); "round trip OK" for both; the
  public URL answered with the pack's bytes via `release-assets.githubusercontent.com`. Log:
  `…/scratchpad/m27/publish-library-v1.log`.

## Where the app may reach (`UpdateSource`, `Kind.Library`)

- `LIBRARY_MANIFEST_URL` = `https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/library.json`,
  exactly (`allowsLibraryManifest`: HTTPS on 443, that path, no query or fragment).
- What the manifest may name (`allowsLibrary`, `allowsLibraryFile`): a `.zip` asset of this repository's
  release tagged **`library`** on `github.com` (`LIBRARY_DOWNLOAD_PREFIX` + a plain name), no query or fragment;
  never a version tag or `models`, and an asset host is never named directly.
- Every hop, redirects included (`allowsHop` → `allowsLibraryManifest || allowsLibraryBinary`): the manifest,
  such an asset, or GitHub's two asset hosts. The app's, the firmware's and the models' sources refuse the
  pack's addresses, and the library's refuses theirs (`UpdateSourceTest`).
- `MAX_LIBRARY_BYTES` = 200 MiB, its own cap (the models' 1 GiB, the sound's 200 MiB, the APK's 50 MB).
- `localLibrary(url)` (debug builds on an emulator only, `LibraryOverride`: `debug.stevenpiano.libraryurl`):
  its origin (HTTP allowed; the debug network config allows cleartext to 10.0.2.2 alone) **and** everything
  `library` reaches, so a manifest served from the Mac can name the published pack (how the first load's
  evidence reached the real GitHub URL before `library.json` is on `main`).

## The manifest (`LibraryManifest.parse`)

`org.json`, the text at most 4 KB (UTF-8 bytes); `version` a JSON integer 1..10,000; `file` exactly
`library-v<version>.zip`; `url` allowed by the source and ending in `file`; `sizeBytes` 1..200 MiB; `sha256`
64 hex digits (kept lower-case); `pieces` 1..20,000; `notes` optional plain text (control characters but line
breaks dropped, cut to 1,000 on a code point); `licences` optional, at most 8 lines of plain text of at most 120.
Unknown fields ignored. No pin: the manifest's trust is its address (this repository's `main`), as for the
app's own updates; the zip's is the manifest's size and hash. `LibraryManifestTest` also reads the committed
`releases/library.json` as the app does.

## The pack in the app (`LibraryPack`)

- **`state: StateFlow<PackState>`**: `Idle` (nothing newer known) · `Checking` (a load asking for the
  manifest) · `Offered(version, pieces, sizeBytes, newPieces)` · `Downloading(done, total)` · `Importing` ·
  `Done(added)` · `Failed(line)`. `busy` = Checking, Downloading or Importing: a check never replaces it, a
  second load is refused. **`offer: StateFlow<Offered?>`**: the pack on offer as last found (the rows'
  numbers whatever the load is doing); null when nothing newer is known. **`newerAvailable:
  StateFlow<Boolean>`**: the newest manifest read names a version above the one loaded (on a fresh tablet,
  any pack once asked).
- **`check(maxAgeMs = 0)`**: unless a load runs, the device is offline, or the last ask is younger than
  `maxAgeMs`: the manifest, then `Offered` when newer than `settings.libraryPackVersion`, else `Idle`. A failure
  is logged and changes nothing on screen. Asked by **`runSchedule(switch)`** (launched in
  `AppGraph.runUpdateSchedule` beside the app's and the firmware's: at once when the activity starts, then every
  24 h, while Check for updates is on and the device online, an on-demand ask counting as one (its check's age
  is the interval: one request at launch, measured); a skipped check retries after a minute) and on demand (the
  empty Library shown, the + sheet opened: 10 minutes' age).
- **`newPieces`** = the pack's `pieces` less the distinct SHA-256s every earlier pack offered this tablet
  (`OfferedPacks.all()`), at least 0: exact while packs only grow (a pack is each file once, so its `pieces` are
  distinct hashes); a pack that replaced files would undercount, and the row then drops the count.
- **`load(everything = false): Boolean`**: moves `state` to `Checking` (atomically; false when a load is under
  way) and calls `start(everything)` = `LibraryService.start(app, everything)`; a start that throws ends in
  `Failed("The library couldn't start loading; try again.")` and false.
- **`run(everything)`** (the service's work, one at a time): offline → `Failed(OFFLINE)`; the manifest (a 404,
  a dead network → `UNREACHABLE`; a manifest the parser refuses → `UNREADABLE`); not newer than the one loaded
  and not `everything` → `Idle` (up to date, nothing downloaded); the zip through `VerifiedDownloader` into
  `cacheDir/library/library-v<n>.zip` (at most the cap and the manifest's size, the part hashed as it arrives,
  `2 × size + 64 MB` kept free beside it for the pieces unpacked, progress every 256 KB; its problems →
  `MISMATCH`/`STOPPED`/`UNREACHABLE`/`NO_ROOM`); `Importing`: the pack's hashes read from its INDEX.csv
  (`ZipSource.readIndex().sha256s()`), then the importer with `ImportSource.LocalZip(file, skip)`, `skip` =
  every hash an earlier pack offered (none with `everything`); an import that read no piece (every file failed,
  or the zip would not open) → `Failed(NOT_READ)`, nothing recorded; else `offered-v<n>.txt` written (the
  pack's hashes, sorted, one per line; a part file renamed over), `settings.libraryPackVersion` = n, `Done(added)`.
  The zip is deleted by the import's close and again in a `finally` (a stop before the import opened it). A
  cancel puts `state` back to the offer, records nothing, leaves no file.
- **`OfferedPacks(filesDir/library)`**: `offered-v<n>.txt` files, each read only up to 2 MB and only lines
  of 64 hex digits; `all()` their union. **`LibraryPack.sweep(cacheDir/library, before)`** at start removes what
  a stopped load left (a zip, a part).
- The import (`AppGraph.importLibraryPack`): `importer.import(app, source)`, then, when pieces arrived, the
  built-in playlists refreshed and composers' artwork started (`ArtworkService.start`, which falls back to the
  app's process when refused), as `ImportService` does after its imports.

## The service (`LibraryService`)

A dataSync foreground service (manifest: not exported) holding the **whole** load: it calls `run(everything)`
(the `EXTRA_EVERYTHING` extra) and shows one notification on the **imports** channel (id 9): title "Loading
Steven's library", text "Asking for the library…" / "23 of 61 MB" / "Imported 204 of 1,726", a determinate bar
(bytes, then files), **Cancel** (cancels the job), at most every 400 ms; the Library's tab on tap. `onTimeout`
(Android 15's six hours) cancels. `start()` falls back to `appScope.launch { run(everything) }` when Android
refuses a foreground service (`ForegroundServiceStartNotAllowedException`, the console's command with the app
in the background), and so does `onStartCommand` when `startForeground` itself is refused.

## `ImportSource.LocalZip` and INDEX.csv's `sha256`

- `LocalZip(file, skipShas)`: a zip the app saved in its own storage; `openLocalZip` reads it where it lies
  (never copied) with every zip's caps (`ZipSource`: 20,000 entries, the index to 2 MiB, hidden paths and
  `__MACOSX` skipped), its MIDI files less those whose row's `sha256` is in `skipShas` (a file with no row or
  no hash is always read; the importer's own hash keeps one copy of each piece), and deletes the zip when the
  import closes it. A file that is not a zip is refused and left to the caller. `ImportService.start` refuses a
  `LocalZip` (`IllegalArgumentException`): the library's own service imports it.
- `IndexCsv.Row.sha256: String?`: the column found by its header name `sha256` (the sixth without a header),
  kept when it is 64 hex digits, lower-cased; otherwise null. An index without it reads as before.
  `IndexCsv.sha256s()`: every row's.

## The UI (`LibraryScreen`, `AddSheet`, `ImportBar`, `LibraryLicenceSheet`, `LibraryCopy`)

As DESIGN.md describes. `LibraryScreen` collects `libraryPack.state`, `offer`, `newerAvailable` and
`settings.libraryPackVersion`; `loadLibrary(everything)` runs through the kiosk gate and opens the licence sheet
while the version loaded is 0, else calls `load(everything)`. The empty Library's Load passes `everything = true`,
the + sheet's row `false`. The + sheet's row: `LibraryEntry(LOAD …)` while the version is 0; `LibraryEntry(
updateLabel(newPieces) …)` while `newerAvailable` or a load is busy; none otherwise. `LibraryBar` sits under
`ImportBar` in the glass header (`LibraryCopy.bar`: Checking and Downloading over `ProgressRow`; Failed with
Dismiss → `LibraryPack.dismiss()`).

## For M26: the console's `library.load`

The stable surface, for `RelayCommands` and the status report:

```kotlin
graph.libraryPack.load(everything = false): Boolean   // starts a load; false while one runs (or it could not start)
graph.libraryPack.state: StateFlow<PackState>          // Idle · Checking · Offered(version, pieces, sizeBytes, newPieces)
                                                       // · Downloading(done, total) · Importing · Done(added) · Failed(line)
graph.libraryPack.newerAvailable: StateFlow<Boolean>   // a pack newer than the one loaded is known
graph.libraryPack.offer: StateFlow<PackState.Offered?> // the newest pack's numbers, when newer than the one loaded
graph.settings.value.libraryPackVersion               // the version loaded, 0: none
```

`library.load` should call `load()` (not `everything`): on a tablet with a pack loaded it brings a newer one's
new pieces only, and pieces a teacher deleted stay deleted; on a fresh tablet it is the first load (no licence
sheet: the owner's command). Any thread. From the background it runs without the notification. The
command can answer from the Boolean ("Loading Steven's library" / "A load is under way already"); the outcome
follows in `state` (`Idle` after a load that found nothing newer). The status's "pack version" is
`settings.libraryPackVersion`.

## Greps (v1.10 — M27)

`Color(0x` outside `ui/theme`: none. `Modifier.blur`: none. `0.0.0.0`: none. `LocalLive`/`LocalNoteSounding`:
their painters and the theme, as before. None in this run's files.

## Measured (2026-09-30, `steven_piano_m27`: API 34, `medium_tablet` 2560 × 1600 at 320 dpi, 3 GB, debug build)

Screenshots in the session scratchpad, `m27/shots/`.

- **A fresh install, version 1 from GitHub.** The manifest from a local copy of `releases/library.json`
  (`debug.stevenpiano.libraryurl http://10.0.2.2:8767/library.json`: the zip's URL the real release's).
  The empty Library with both buttons and "1,726 pieces · 61 MB · …" (`00`); **Load Steven's library** → the
  licence sheet (`01`) → **Load · 61 MB** → "Loading Steven's library · 0 of 61 MB" (`02`), `LibraryService` in
  the foreground (id 9, channel imports) → the 61 MB from `github.com` via `release-assets.githubusercontent.com`
  in a few seconds → "Imported 1,280 of 1,726" in the notification with Cancel (`03`) → **"Imported 1,726
  pieces."** (`04`), the log "Import: 1726 new, 0 already there, 0 failed" (one file's trailing bytes skipped,
  `bor_ps5.mid`, as before) and "Library: version 1 loaded, 1726 pieces added"; artwork fetching after it;
  about 15 s from the tap to the tally (1,068 pieces in within about 8 s). `cache/library` empty;
  `files/library/offered-v1.txt` 112,190 bytes (1,726 × 65). The + sheet then has no library row (`05`).
- **The update, version 2 from the Mac.** `publish_library.py --midi <a copy with two generated pieces>
  --version 2 --work … --manifest <the served library.json> --url-base http://10.0.2.2:8767/` (61,237,906 bytes,
  1,728 pieces; never published), served at 5 MB/s. "12 Etudes, Op. 25 [2]" deleted first (`06`, `07`). After
  a restart (the start-up check): **"Update the library · 2 new pieces"** with "1,728 pieces · 61 MB · …"
  (`08`) → "Loading Steven's library · 14 of 61 MB" (`09`) and the notification "20 of 61 MB" with Cancel
  (`10`) → **"Imported 2 pieces."** (`11`): "Import: 2 new, 0 already there, 0 failed" (the import read the two
  files alone), "version 2 loaded, 2 pieces added"; both evidence pieces in the Library (`12`) and the deleted
  étude still gone (`13`); `offered-v2.txt` 112,320 bytes (1,728 × 65), `offered-v1.txt` kept, `cache/library`
  empty.
- **Dark, and offline.** A fresh state in dark: the empty Library (`14`), the + sheet's Load row (`15`), the
  licence sheet (`16`); in airplane mode, Load → "Loading Steven's library needs an internet connection." with
  Dismiss, Load available again (`17`).
- **Tests**: 1,219 before, **1,260** after (13 skipped, as before), none failing. `lintDebug`: 0 errors, 29
  warnings (1.9's 28 and `LibraryService`'s `InlinedApi` on `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, the line every
  data-sync service carries). No compiler warnings in the app's sources. The debug APK 28,459,243 bytes.

## Deviations from the brief, and why

- **The import runs in `LibraryService`, not handed to `ImportService.start(LocalZip)`.** Android 12+ refuses
  a foreground service started from the background, and a 61 MB download often ends with the app left or the
  screen off; one service holding the foreground from the tap to the last piece avoids that hand-over, and the
  pack's bookkeeping (`offered-v<n>.txt`, the version) follows the import's own result in one place. The import
  is still the importer's (`importer.import(app, LocalZip)`), with `ImportService`'s after-steps (built-ins,
  artwork). `ImportService.start` refuses a `LocalZip` rather than half-support it (its intent cannot carry the
  skip list).
- **1,726 pieces, not 1,727**: the pack holds each file once; one INDEX row names a byte-for-byte copy.
- **`PackState.Offered` also carries `newPieces`; `offer`, `dismiss()` and `load(everything)` added**: the rows
  need the numbers while a load runs or after it failed; the empty Library's Load brings everything so a library
  emptied by hand can be filled again.
- **The daily check follows Check for updates' switch**; the on-demand ones (the + sheet, the empty Library) ask
  whatever the switch says, at most every 10 minutes, since the person is looking at the offer.
- **`LibraryOverride` lives in `M/library/`** (not `UpdateOverride.kt`), and the emulator's source also reaches
  the published pack, so the first load's evidence used the real GitHub URL before the manifest is on `main`.
- **The licence sheet shows while `libraryPackVersion` is 0**: a first load that did not finish shows it again.
- **The notification uses the imports channel** (no new channel); **`libraryPackVersion` is not in Share
  diagnostics' `settings.txt`** (`DiagnosticsExporter` untouched, to keep the merge with M26's settings simple).

## Residuals

- **Nothing is offered until `releases/library.json` is on `main`** (merge and push); the release and its zip are
  already public.
- **The console's `library.load` path from the background** (the in-process fallback) is written and unit-level
  only; M26's integration exercises it. (At the merge Android allowed the service from the background, the web
  service holding the foreground, so the fallback itself stayed unexercised: *The merge*.)
- **`newPieces` undercounts a pack that replaced files** (the row then says "Update the library"); the import
  itself is exact (by hash).
- **The pack is trusted as the app's updates are**: the manifest's address and the zip's hash, no signature. A
  pack is only MIDI files through the importer's caps (8 MB each, 20,000 entries, the parser), so the worst a
  bad pack could do is add unwanted pieces.
- **MAESTRO's performances are for non-commercial use only**: the school's use is; the sheet and README say so.
- Not run on the school tablet.

## Tests added in M27

`LibraryManifestTest` (6: the committed manifest; a good one field by field; each field's check; not a manifest,
the 4 KB edge; notes and licences as plain text; the emulator's manifest). `LibraryPackTest` (17: the offer and
the version compare; online and the check's age; a failed check; the first load recorded; an update that skips
every hash offered before and never brings back a deleted piece; everything; up to date; a failed download
(stopped, mismatch, no room, 404) leaving no part and recording nothing, the offer back on Dismiss; the manifest's
failures in words; an import that reads nothing; a cancel; one load at a time and a refused start; a check never
replacing a load; the schedule's due time; the schedule on its switch and the network, counting an on-demand ask;
`OfferedPacks`; the sweep). `LocalZipTest` (5: read in place and
deleted; the skip list; an index without the column; not a zip; the importer bringing only the new pieces).
`LibraryCopyTest` (4). `IndexCsvTest` +4 (in `CsvReaderTest.kt`), `UpdateSourceTest` +4, `SettingsRepositoryTest`
+1. 1,219 before, **1,260** after.

## The merge: into `main`, before 1.10 (still 1.9, versionCode 17)

Merged into `main` after M26's merge (`c234895`) as `d4e60c6`. Not a release: the audit runs next, then 1.10
is cut; the version stays 1.9 (versionCode 17), no entry is drafted in `releases/history.json` and no APK is
staged.

- **Conflicts**: `Settings.kt` (the last fields of `PianoSettings`, their reading and the keys: M26's cloud
  settings, then `libraryPackVersion`, its key with the others before the relay's piano-id pattern);
  `SettingsRepositoryTest` (both tests kept); the tails of DESIGN.md and this file (M26's section, this file's
  with its merge notes, then this one, each after a "---", M26's missing since its merge) and of
  `docs/SECURITY_AUDIT.md` (the cloud's notes, then the pack's). README, AUTHORS, `AppGraph`, the manifest,
  `UpdateSource` (M26 had not touched it), `ImportSource`, `IndexCsv`, `ImportService` and the Library's screens
  merged by themselves.
- **The two runs wired** (`5c5e1e4`):
  - The console's `library.load`: `WebService` gives `RelayCommands` its `libraryLoad` (M26 had left it null),
    `RelayCommands.libraryLoad(online, state, load)`: the pack's `load(everything = false)`, as *For M26* above
    asks, answered at once: "Loading Steven's library."; a load under way, "Steven's library is loading
    already."; the tablet offline, `LibraryFailures.OFFLINE` (nothing started, no failure left on the tablet's
    screen); a start that failed, its line.
  - The status: `RelayStatus.report(…, settings, …)`, the panel's switch and `library.pack` from the settings
    (`libraryPackVersion`, 0 for none, which the console leaves out; "pack 1" once loaded). A pack loaded counts
    as a change, so the console hears it within 2 s.
  - `settings.txt` in Share diagnostics gains `libraryPackVersion` (39 lines).
  - README: the Cloud section reads once (the pointer line kept at M26's merge, which repeated the section's
    first paragraph, goes); the console's list names **Load Steven's library** and the pack's version;
    Security names the relay (what leaves the device, what comes in). M27's own security lines were already
    inside the list's bullets.
  - Tests: `LibraryPackTest` +1 (the console's command against a real pack: offline, started with
    `everything = false`, busy, a refused start), `RelayStatusTest` +1 (the pack and the switch from the
    settings), `DiagnosticsExporterTest` (the line, 39 lines).
- **Measured at the merge** (`steven_piano_int`, API 34, Pixel 7 profile, 4 GB, `wm size 2560x1600`,
  `wm density 240`, the integrator's own AVD, removed after; shots in the session's `integrate-m27-shots/`):
  the merged debug build on a fresh install, the manifest from a local copy of `releases/library.json`
  (`LibraryOverride`, the zip's URL the release's), `CloudOverride` `http://10.0.2.2:8787`.
  - The empty Library: "No pieces yet.", "Load Steven's library, or add MIDI files of your own.", **Load
    Steven's library** and **Add MIDI files**, "1,726 pieces · 61 MB · MAESTRO, piano-midi.de, Mutopia · for
    non-commercial use". Load → the licence sheet ("1,726 piano pieces from three open collections, a 61 MB
    download from Steven Piano's releases on GitHub.", the three credits, the non-commercial note, Not now ·
    Load · 61 MB) → **Load · 61 MB** → "Loading Steven's library · 6 of 61 MB" a second later, 32 of 61 at 6 s
    (from `github.com` by `release-assets.githubusercontent.com`) → "Imported 124 of 1,726" at 10 s →
    **"Imported 1,726 pieces."** at 23 s (the log: 18.5 s from the manifest to "version 1 loaded, 1726 pieces
    added, 0 there already, 0 failed"); `cache/library` empty, `offered-v1.txt` 112,190 bytes.
  - The console's command: the app's data cleared; a PIN set (generated, not recorded); `cloud/` under
    `wrangler dev` (at `72592f4`, the relay on :8787, the console on :8788 with its local bypass); a code from
    the console's `POST /api/enrol-codes`; CLOUD › Enrol with code (`10.0.2.2:8787`) → Remote access over the
    internet → **Connected**; the console's status: pieces 0, pack 0. The app sent home (the launcher on top),
    `POST /api/pianos/<id>/command {"name":"library.load"}` → `{"ok":true,"message":"Loading Steven's
    library."}`; 1.5 s later the same → `{"ok":false,"message":"Steven's library is loading already."}`; the
    link's trail "Cloud: the console sent library.load: done", then "…: not done". Android allowed
    `LibraryService` from the background ("Background started FGS: Allowed", the process in the
    foreground-service state for the web service), so the notification showed ("Loading Steven's library",
    "Imported 786 of 1,726"); the same download from GitHub; "version 1 loaded, 1726 pieces added" 17.4 s after
    the command; the console's status then **pieces 1,726, pack 1**, its audit both commands with their
    answers; the Library on return, the pieces. No crash.
- Tests: **1,324** (M26's merge's 1,281, M27's 41, the wiring's 2), 13 skipped. `check` passes: `lint` 0 errors,
  30 warnings (1.9's 28, M26's OkHttp notice, M27's `InlinedApi` on `LibraryService`, the line every data-sync
  service carries; nothing from the merge), the ONNX Runtime checks. No compiler warnings in the app's sources.
  The greps above and every earlier section's: as stated; the pack's manifest address only in `UpdateSource`.
- **The release build**, R8: no warnings; 13,710,983 bytes (+18,928 on M26's merge), versionCode 17, "1.9",
  signed `CN=Steven Piano, O=Steven Jin, C=US`. Not staged. The debug APK is 29,607,042 bytes.
- Seen in the Library, the pack's data: 111 Mutopia rows of `INDEX.csv` carry no composer, and some a file's
  name as their title ("a-breeze-from-alabama"); for the next pack.
- The provenance manifest re-signed last.

## Audit (delta 3) — 2026-09-30

`docs/SECURITY_AUDIT.md › 1.10 — the cloud and the library pack: audit (delta 3)` records it in full: three
Medium and four Low findings, all fixed, on the app's side and the cloud's. Nothing was deployed: the cloud
ran under `wrangler dev` on the Mac, the app on the audit's own AVD `steven_piano_audit` (API 34,
`medium_tablet` 2560 × 1600, 4 GB, kept for the run, never `steven_piano_tablet`). What changed, and where the
build now differs from the notes above (M26's, M27's and R1's):

- **C1** (`45edc49`): the relay's requests are weighed by a login guard of their own, `WebPanel.relayGuard` =
  `LoginGuard.forRelay()` (the per-address gate as the listeners'; the global one `RELAY_GLOBAL_THRESHOLD` 10
  wrong in a row, then `RELAY_GLOBAL_FIRST_LOCK_MS` 1 min doubling to `RELAY_GLOBAL_MAX_LOCK_MS` 1 h), given to
  the relay's `WebServer` by `WebService`. M26's "the login guard is shared" no longer holds: the listeners keep
  W1's gate and the internet doesn't reach it.
- **C2** (`14cf525`): R1's room (`connectTablet`) looks at its auth epoch again after the "replaced" note, with
  no await from there to the accept, and closes every tablet socket open then (one accepted meanwhile included);
  the online flag is set only for a piano that holds a secret (`AND secret_hash IS NOT NULL` on the connection's
  and the status's writes).
- **C3** (`73026c6`): the room passes on a tablet's answer's headers by an allow-list, `RESPONSE_HEADERS`
  (`Content-Type`, `Cache-Control`, `Set-Cookie` under the piano's prefix, `Retry-After`, `Allow`, the five
  security headers), where R1 had a deny-list; `Content-Encoding` no longer passes and the Response's
  `encodeBody` switch goes.
- **C4** (`3ddfe5b`): `RelayCommands.libraryLoad(online, loaded, state, load)`: with no pack loaded the console's
  `library.load` starts nothing and answers `LIBRARY_FIRST_ON_TABLET` ("Steven's library loads the first time on
  the tablet, where its licence is shown. After that, the console can bring its updates."). *For M26: the
  console's `library.load`* above said "on a fresh tablet it is the first load (no licence sheet: the owner's
  command)": no longer; the first load is the tablet's own, after its licence sheet.
- **C5** (`91758d3`): the status's `panel` is `{web}` alone (M26's protocol had `panel {web, host}`, the tablet's
  Tailscale or Wi-Fi address); `RelayStatus.report` takes no `panelHost`, and `WebService` no longer reports when
  it changes. The relay's `sanitizeStatus` keeps `panel.web` alone (`TabletStatus.panel: {web}`), so no address
  reaches D1. About ends with `CloudCopy.ABOUT` (DESIGN.md › v1.10 — M26 › *What the relay sees*). The fake
  tablet sends `{web}`.
- **C6** (`dfb81ea`): the enrolment's claim takes a piano never enrolled (`enrolled_at` and `secret_hash` NULL);
  its audit note and the code's use follow the piano's new hash (`claimed`), the same three statements for every
  outcome.
- **C7** (`bf0e10b`): `RelayClient`'s connection keeps `refused` once the 101 fails the subprotocol check; both
  `onMessage` overloads drop everything from then on. `FakeRelay` gains `protocolAnswer` and `greeting`.
- Tests (`5c67e6f` and each fix's): app `WebAuthTest` +1, `WebServerRelayTest` +1, `RelayClientTest` +1,
  `CloudCopyTest` +1, `LibraryPackTest` and `RelayStatusTest` changed: 1,324 before, **1,328 after** (13 skipped,
  as before). Cloud `revoke.test.ts` +4, `tablet-auth.test.ts` +1, `forward.test.ts` +1, `enrol.test.ts` +2,
  `console-access.test.ts` +1, new `limits.test.ts` (3), `malformed.test.ts` (2), `hygiene.test.ts` (4; it reads
  the deployed configs with Vite's `?raw`, declared in `test/raw.d.ts`): 61 before, **79 after**; `tsc --noEmit`
  clean. `check` passes: lint 0 errors, 30 warnings (the merge's), both variants' ONNX Runtime checks.
- Measured on `steven_piano_audit` with `wrangler dev` (`…/audit3/`): enrolment and connection; the 401/403/411/405/
  404 matrix through the relay with the panel's headers unchanged; the relay's gate shut by ten wrong PINs from
  ten addresses while the listener signed the owner in; the console's status without the tablet's address; its
  `library.load` refused on the fresh tablet and an update after the tablet's own load; the debug build's log and
  the diagnostics export without the id, secret, cookie or PIN; the pack from a stand-in release: a tampered zip
  and a truncated one refused with nothing kept, the licence sheet before each try, the good one imported; Forget.
- README (*Steven's library*, *Cloud*, *Security*), `cloud/README.md` (*Security*, *What the relay sees*) and
  DESIGN.md carry the new lines.

## The release: 1.10 (versionCode 18)

Cut from `main` after the audit (`464c709`): `versionCode` 18, `versionName` "1.10" (`-PversionCodeOverride`'s
example now 19), `Provenance.text` "Made by Steven Jin · v1.10 · eab16a502f679465", README's version lines, and the
entry drafted at the end of `releases/history.json` (`"draft": true`, tag `v1.10`, its notes; no hash or size until
`tools/publish-release.sh` builds it). Nothing else changes: the audit's tests (1,328 app, 79 cloud) are the
release's. The push that publishes it also makes `releases/library.json` (M27, `d13f93a`) live on `main`, so a
tablet on 1.10 can load Steven's library the moment it has the build. Steven's side after this release:
`cloud/README.md` › *Deploy it (once)*, then enrol the school tablet and open its panel from a phone on mobile data.

---

# v1.10.1 — M28: uploads become playlists, with artists and artwork

Read `DESIGN.md › v1.10.1 — uploads become playlists` first. Fable's brief (the session scratchpad's
`m28-uploads-brief.md`), built by Opus on `main` from `5d4fc80` (1.10, versionCode 18), a commit a decision in the
brief's order (D3, D1, D2, D4, D5, D6, D7), then two fixes the emulator found. **No version bump, no
`Provenance.text` change, no provenance signing, no release build, no push**: the integrator's, at 1.10.1 (build
19). Steven's uploaded zip is his own and this repository is public: neither the zip nor any file or byte of it is
committed. The tests use synthetic MIDI bytes under its 266 path names (`m28_zip_paths.txt`, the names alone); the
emulator run used the zip itself, and its evidence stays in the session scratchpad (`m28/`).

## Files

Added (`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests):

- `M/data/imports/ImportFolders.kt` (`ImportFolders`: an import's root and artist folders; `PathOrder`).
- `M/data/db/UploadRepair.kt` (the one-time repair, beside `TextRepair`).
- `M/ui/screens/library/PlaylistsHeader.kt` (the Playlists header row and its pop-up button);
  `app/src/main/res/drawable/ic_chevron_down.xml` (black, tinted where it is used, as every icon).
- Tests: `T/data/db/UploadRepairTest` (7); resources `composer_keys_1_10.csv` (the 125 composers of Steven's
  library as 1.10 imported them, with the display, short name and key 1.10's code gave each, written at
  `5d4fc80`) and `m28_zip_paths.txt` (the zip's 266 MIDI paths as the zip spells them: names only).

Changed (each addition marked v1.10.1 — M28): `M/data/imports/ComposerNames.kt` (`artist`, `resolve`, `artistKey`,
`canonicalOf`; `normalize` untouched); `TitleHeuristics.kt` (`Source`, `Metadata.source`, `metadata(…, folder,
artistNamed)`, `baseName`, `trailingParentheticals`); `ImportSource.kt` (`ImportBatch`, `OpenedSource.batch`,
`isMacMetadata`, `isHiddenPath` also a nested `__MACOSX`, `treeName`); `Importer.kt` (`ImportStore.hasComposerKey`
and `linkToPlaylist`, the Mac's files skipped, `BatchNames`, the batch's playlist, `Outcome.Duplicate.filled`);
`ImportProgress.kt` (`filled`, `playlist`, `piecesChanged`, `ImportedPlaylist`); `M/data/LibraryRepository.kt`
(`UploadRepair.Store`; `rename` through `resolve`; `hasComposerKey`, `linkToPlaylist`, `loosePieces`,
`repairChunk`); `M/data/db/PieceDao.kt` (`hasComposerKey`, `loose`, `setNames`, `idsBySha`) and `PieceEntity.kt`
(`PieceSha`); `M/AppGraph.kt` (`repairUploadsOnce` at start; a library load's after-steps on `piecesChanged`);
`M/service/ImportService.kt` (`piecesChanged`); `M/web/AppWebBackend.kt` (the batches, `piecesChanged`, the
playlists' order); `M/web/WebApi.kt` (`import.playlist`); `M/settings/Settings.kt` (`playlistSort`,
`setPlaylistSort`, `uploadRepairDone`, `markUploadRepairDone`); `M/diag/DiagnosticsExporter.kt` (`playlistSort`);
`M/data/PlaylistOrder.kt` (`PlaylistSort`, `listing`); `M/data/art/ArtworkFetcher.kt` (artists);
`M/ui/components/Artwork.kt` (`RollCardImage`'s and `PieceArt`'s `title`) and its callers `NowPlayingPanel.kt`,
`DisplayScreen.kt`, `MiniPlayer.kt`, `PieceDetailSheet.kt`; `M/ui/screens/library/LibraryScreen.kt` and
`LibraryViewModel.kt` (the header row, the order); `M/ui/ImportCopy.kt`; `app/src/main/assets/web/app.js` and
`style.css`; the tests below; `DESIGN.md`, `README.md`, `docs/SECURITY_AUDIT.md`. No schema change (the database
stays at version 3): the repair and the links use the tables as they are.

## D3 — artists' names (`ComposerNames`)

- **`artist(raw)`** (an artist folder's name, or the known side of a reversed name): `cleanText` (mojibake
  repaired, NFC, spaces collapsed, trimmed); blank: no composer; "Made in Studio": Studio's; a canonical
  composer's name (`canonicalOf`): that composer as `normalize` gives them ("Claude Debussy": key `debussy`, "Erik
  Satie": `satie`); anyone else `Name(text, text, artistKey(text))`: shown as written, the short name the whole
  name, the key the whole name folded without punctuation, spaces collapsed ("ed sheeran", "louis armstrong",
  "craig armstrong", "lady gaga bradley cooper", "c418").
- **`canonicalOf(raw)`**: the canonical composer a name names, or null: the full name, a short form `normalize`
  already reads as theirs ("Debussy", "Chopin, F", "Bach JS"), or the surname (or a spelling of it) after given
  names of the composer's own or their initials ("Pyotr Tchaikovsky", "W. A. Mozart"). "Andrew Berg" and "Janis
  Joplin" are not Alban Berg and Scott Joplin, though `normalize` keys them by surname.
- **`resolve(raw, isKey)`** (a `Composer - Title` name's left side, and Rename): `normalize(raw)`, unless the name
  is no canonical composer's, its whole key differs from `normalize`'s, and `isKey(whole)` holds (an artist folder
  of the same import, else `PieceDao.hasComposerKey`, asked once a key an import): then `artist(raw)`. So "Ed
  Sheeran - Perfect.mid" joins an "Ed Sheeran" folder's pieces instead of starting `sheeran`.
- `normalize` is unchanged. `ComposerNamesTest` reads `composer_keys_1_10.csv` and finds each of a 1.10 library's
  125 composers with the display, short name and key it had: no key moves, no artwork row is orphaned. Nothing
  already in the library is re-keyed (the repair touches only the loose pieces, D4).

## D1 — where a piece's artist comes from (`TitleHeuristics.metadata`, `ImportFolders`, `Importer`)

- **The Mac's files**: `isMacMetadata(path)` (a `__MACOSX` segment anywhere, or a last segment starting `._`)
  drops them from `source.items` before anything is counted or read. 1.10 already left them out of zips and folder
  walks (`isHiddenPath`: a top-level `__MACOSX/`, any segment starting with a dot), so the zip's 115 were never
  counted; it missed a nested `__MACOSX/` folder's files and a loose `._name.mid` picked on the tablet or sent
  through the panel. `isHiddenPath` names a nested `__MACOSX` too now.
- **`ImportFolders(paths)`**: `root`, the one top-level folder every path shares (null when they share none, or a
  file lies at the top); `artistFolderOf(path)`, the folder that directly holds the file when it lies below the
  root (with no root, any folder that holds it), cleaned; `artistNamed(name)`, the import's artist folder whose
  `artistKey` is the name's, spelt as the folder is; `artistKeys`, the keys `artist` gives its folders.
- **`metadata(fileName, row, sequenceNames, folder, artistNamed)`**, in order: an INDEX.csv row (`INDEX`); a
  `Composer - Title` split whose right side, less its trailing parentheticals (`trailingParentheticals`), is known
  (`canonicalOf`, or `artistNamed`) and whose left side is not: `REVERSED`, the composer the right side as the
  folder spells it, the title the left side with the parentheticals after it; else the split as always,
  `FILE_NAME`; else the artist folder, `FOLDER`, the title the file's base name; else `NONE`. A stub title still
  gives way to Track 0's name.
- **The importer** (`BatchNames`) reads the composer by its source: `INDEX` and `NONE` through `normalize` (as 1.10),
  `REVERSED` and `FOLDER` through `artist`, `FILE_NAME` through `resolve` (D3); cut to `TextLimits.COMPOSER` first,
  as before.

## D2 — an import's playlist (`ImportBatch`, `Importer.link`, `LibraryRepository.linkToPlaylist`)

- **`OpenedSource.batch`**: `None` for files picked one by one (`ImportSource.Uris`), Steven's library (`LocalZip`)
  and Studio's pieces; `Named(name)` for a zip (the tablet's `ImportSource.Zip` and the panel's `importZip`:
  `ImportBatch.zip(name)`, the name less `.zip`) and a folder (`ImportSource.Tree`: `treeName`, the provider's
  display name of the folder chosen, else its document id's last part, cut to 255); `Uploads` for a loose
  `.mid`/`.midi` sent through the panel (`importMidi`).
- **What goes in** (`Importer.link`, after the last item): every item read whose INDEX.csv row names no collection,
  new or already there (`found`: its path and SHA-256), less any SHA-256 an INDEX row of the batch placed
  (`placed`), in `PathOrder` of the paths, each SHA-256 once. `Named`: the root, else the batch's name; `Uploads`:
  "Uploads". The name `cleanText`ed and cut to `TextLimits.COLLECTION` (120); blank, "Uploads".
- **`PathOrder`**: segment by segment, folded (case and accents ignored), the last without its MIDI extension, runs
  of digits by value ("No. 2" before "No. 10"), a name before the longer ones it begins ("Fix You" before "Fix You
  (Live)").
- **`linkToPlaylist(name, imported, shas)`**: chunks of 500 (`SQL_CHUNK`), a transaction each: the pieces among the
  chunk's SHA-256s (`idsBySha`) in the chunk's order; the playlist found by name (as names compare) or made
  (`playlistId`: a built-in of that name moves aside, "Popular · built in") in the first transaction that has a
  piece for it; each piece after the playlist's last (`addPiece` ignores a piece already in it, which keeps its
  place). A zip's or a folder's playlist is marked `imported`, as an INDEX.csv's; Uploads is not. A failure is
  logged and leaves the pieces in the library without their playlist.
- **Duplicates**: a duplicate whose library copy has a blank composer takes the upload's, as `fillComposer` always
  did, now from D1's sources (`Outcome.Duplicate(sha, filled = true)`); `ImportProgress.filled` counts them, and
  `piecesChanged` (`imported > 0 || filled > 0`) decides the after-steps (the built-ins, artwork) in
  `ImportService`, the panel's imports and Steven's library's load.
- **`ImportProgress.playlist`** (`ImportedPlaylist(id, name)`), set when the import finishes. The log: "Import:
  3 new, 0 already there, 0 failed, in a playlist called Spring Recital" (the name in debug builds only).

## D4 — the one-time repair (`UploadRepair`, `AppGraph.repairUploadsOnce`)

- **When**: at start, first in the launch that refreshes the built-ins, on `Dispatchers.IO`, unless
  `settingsRepository.uploadRepairDone()` (the key `libraryUploadRepairDone`, housekeeping like `textRepairDone`:
  not in `PianoSettings`, not in Share diagnostics). Marked done once a run finishes; a failure is logged and leaves
  it due (what it did stays, and a second run finds those pieces in their playlist). Then the built-ins refresh,
  and, when it named an artist and **Fetch artwork automatically** is on, `artwork.requestComposers(force = false)`.
- **Which pieces** (`PieceDao.loose`, checked again by `UploadRepair` over what it is given): no collection, in no
  playlist (no `collection_pieces` row), a `/` in `sourceName` after its first character, a composer that is not
  "Made in Studio".
- **`plan(pieces)`**: grouped by `sourceName`'s first segment, the roots in `PathOrder`; a root with two pieces or
  more and a name (`cleanText`, cut to 120) is a `Plan`: its pieces in `PathOrder`, and those whose names change.
  `ImportFolders` over the root's paths gives its artist folders.
- **`repaired(piece, folders)`**: `metadata` as an import now reads the file's name. `FOLDER`: a blank composer
  takes the folder (`artist`). `REVERSED`: when the composer is blank or still what 1.10 read from the left side
  (`normalize(left).display`), the reversed reading's title and artist. Anything else stays. Through
  `PieceEntity.named`, so `searchText`, `titleKey` and `composerShort` follow; `PieceDao.setNames` writes those six
  columns and nothing else.
- **`run(store, log, named)`**: a plan's pieces in chunks of 500 (`CHUNK`), a transaction each (`repairChunk`: the
  names, then the links after the playlist's last, the playlist found or made as D2's); then a line in the link's
  trail: "Library: 265 pieces put in the playlist MIDI, 264 artists filled" (release builds: "… put in the playlist,
  264 artists filled").

## D5 — artwork for every piece (`ArtworkFetcher`, `Artwork.kt`, the panel)

- **An artist** (a composer key `ComposerNames.canonical` doesn't know; the canonical path is unchanged):
  `artistPage(display)`. Its own summary, taken when it is no disambiguation page and `aboutPerformer`; else, for a
  single name, "(band)", "(singer)", "(musician)", "(composer)" in turn, the first such page winning. A joint name
  (`firstOfJoint`: "&", ",", "and", "feat", "feat.", "ft.", "featuring") tries its own page alone, then its first
  name the same way, suffixes included. At most `MAX_LOOKUPS` = 5 summaries an artist; a page that doesn't exist ends
  that name's tries. Then the page's image, as a composer's (`WikipediaClient.IMAGE_CAP`): the same client, the same
  two hosts, the same retry and wait.
- **`aboutPerformer`**: the description names a performer (`PERFORMERS`, whole words or phrases: band, duo, trio,
  quartet, group, girl group, boy band, singer, songwriter, singer-songwriter, musician, multi-instrumentalist,
  rapper, DJ, disc jockey, record producer, music producer, composer, pianist, guitarist, drummer, bassist,
  vocalist, violinist, cellist, organist, harpsichordist, conductor, orchestra, ensemble, choir, recording artist,
  musical artist); else a description naming a work or a company (`WORKS`: album, song, single, soundtrack, EP,
  film, game, company, label, series…) and no person's years (`PERSON`: "born", "1862–1918") is not; else the
  extract's first sentence decides (`firstSentence`, past initials and abbreviations: "Robert F. "Toby" Fox is an
  American … composer."). So a soundtrack's page whose extract names a singer never stands for the singer.
- **No empty frame**: `RollCardImage(…, title)` shows the title's `MonogramTile` where the roll card is `Missing`
  (the piece's file gone, unreadable, or too large for the memory left), and `PieceArt(…, title)` passes it on;
  the now-playing panel, the mini player, the resting screen's framed art (its backdrop stays a backdrop) and the
  piece sheet give it. Library rows and tiles (`ComposerArt`: the portrait, else the composer's mosaic, else the
  monogram) and playlist covers are as they were. The panel: `/api/art/piece/{id}` and `/api/art/composer/{key}`
  answer as before (the image, or 404); its `art()` keeps a piece's title (`data-title`), and a roll card that
  cannot come (no id, or the probe image's error) becomes the title's monogram, as does a channel tile's portrait
  that fails to load.
- Artwork is asked for after an import (`piecesChanged`) and after the repair (`requestComposers(false)`); Fetch
  artwork automatically still decides. No piece images (album covers are not free): unchanged.

## D6 — the Playlists' order (`PlaylistOrder`, `PlaylistsHeader`, the panel)

- **`PlaylistSort`**: `NEWEST` ("Newest first", the default) and `NAME` ("Name"); `PianoSettings.playlistSort`
  (`setPlaylistSort`, the key `playlistSort`; a value this version doesn't know reads as the default), in Share
  diagnostics' `settings.txt` (40 lines).
- **`PlaylistOrder.listing(all, sort, builtInOrder)`** over the library's list (by name, case ignored): `NEWEST`,
  the playlists not built in by id, descending (ids only grow: creation order), then the built-ins in the
  catalogue's key order (`graph.builtIns.keys`: Popular, Recognisable, Epic on piano; a key it doesn't name after
  them by id); `NAME`, exactly the order before 1.10.1: the built-ins by id, then the rest as the library lists
  them. `PlaylistShelf.shown` still hides an empty built-in; the Library's Playlists listing combines the
  playlists, the channels and the order off the main thread (`flowOn(Dispatchers.Default)`);
  `AppWebBackend.playlists()` gives the panel the same order.
- **`PlaylistsHeader(sort, onSort)`**, the list item `playlists-head` after the channels' row (a spacer, as before,
  when there are no playlists): a row at least 48 dp tall, 16 dp in at the start and 4 dp at the end; the
  `Eyebrow("Playlists")` with `weight(1f)` and heading semantics (it may wrap); the pop-up button: the order's label
  in `labelLarge`, `onSurface`, one line without soft wrap, then `ic_chevron_down` (20 dp, `onSurfaceVariant`); at
  least 48 dp tall, `clickable(role = DropdownList, onClickLabel = "Change")`, its semantics "Sort playlists, Newest
  first". The menu: `GlassPopover(alignment = Alignment.End)`, its column as wide as its widest row
  (`IntrinsicSize.Max`, at least 168 dp), a `selectableGroup`; each row 48 dp, `selectable(role = RadioButton)`, a
  24 dp place for the check (`ic_check`, `onSurface`), the label in `bodyLarge`. A choice closes the menu and is
  saved; it needs no kiosk PIN (it changes how the list reads, not the library).

## D7 — where an upload went (`ImportProgress.playlist`, the panel, `ImportCopy`)

- `WebApi.import`: `playlist` `{id, name}` once the import has finished and filled one, else `null` (a run under way
  shows none); in `/api/state` on both listeners and through the relay.
- The Add tab (`renderTally` → `renderImportedPlaylist`): under the tally, "In the playlist MIDI" and the outlined
  button **Open the playlist** (`openImported`: the Library section, its Playlists chip, that playlist open), a
  wrapping row (`.import-playlist`); nothing when no playlist was filled.
- `ImportCopy.summary`: " · in the playlist MIDI" before the full stop of "Imported 265 pieces" and of "Those
  pieces are already in the library"; failures alone with a playlist, "In the playlist MIDI."; the tablet's import
  bar reads it. `ImportCopy.inPlaylist(name)`.

## Greps (v1.10.1 — M28)

`Color(0x` outside `ui/theme`: none. `Modifier.blur`: none. `0.0.0.0`: none. `dev.chrisbanes`: `Glass.kt` alone.
`hazeSource`/`HazeState`: `Glass.kt`, `GlassHeader.kt`, `GlassMenu.kt`, `NavHost.kt`, `NotePanel.kt`,
`MainActivity.kt`. `LocalLive`: `LiveDot.kt` and the theme; `LocalNoteSounding`: `ScorePages.kt` and the theme.
`LensAlpha`/`GlassLens`/`LensVeil`: none. `ModalBottomSheet(`, `DropdownMenu(`, `AlertDialog(`, `Popup(` outside
the glass wrappers: none (the time picker's `BasicAlertDialog` holds `GlassDialogSurface`). M28's only glass is
`PlaylistsHeader`'s `GlassPopover`; no colour added (`m28/greps-final.txt`, at `123be97`).

## Measured (2026-10-01, `steven_piano_audit`: API 34, `medium_tablet` 2560 × 1600 at 320 dpi, 4 GB, debug builds)

The audit's AVD, tablet-sized as it is (no `wm` change), booted headless as `emulator-5560`, stopped at the end,
the AVD kept; Steven's demo (`steven_piano_tablet`) never touched. The panel forwarded to the host's 8738 (`adb
forward tcp:8738 tcp:8737`; 8737 is the demo's); its PIN generated and kept in a scratchpad file (mode 600),
never shown. The panel's API driven with curl (its own Host, Origin and `X-Steven-Piano`); its pages in the Claude
browser pane through a local proxy that gave the forwarded panel its own Host and Origin and the session's cookie
(scratchpad only, nothing of it in the repository; the panel's checks unchanged). Screenshots in the session
scratchpad, `m28/shots/` (`00`–`25`); a first round, before the two fixes, in `m28/shots-round1/`.

- **The school tablet's state, reproduced.** 1.10's debug build (`Player Piano/apk/steven-piano-1.10-debug.apk`) on
  a fresh install: a PIN, Web control on (`00`), `~/Downloads/MIDI.zip` (266 MIDI files and 115 `__MACOSX/._*`
  entries) through the loopback panel's upload: 266 of 266, **265 imported, 1 already there**, 0 failed; no
  playlist; composers blank × 260, "Cornfield Chase" × 2, "Day One (Interstellar)", "Time (Inception)", "Stay"
  (`01`, `m28/transcript-1.10.txt`), as on the school tablet. The one already there:
  `MIDI/Mitski/My Love Mine All Mine.mid` has the same bytes as `MIDI/Tom Odell/Another Love.mid`.
- **The repair.** This build installed over it (`adb install -r`, the data kept) and opened: about 2 s later
  "Library: 265 pieces put in the playlist MIDI, 264 artists filled" (`m28/import-log-lines.txt`): the 260 folder
  pieces, and the four Hans Zimmer names read the right way round ("Cornfield Chase", "Cornfield Chase (version
  2)", "Day One (Interstellar)", "Time (Inception)": Hans Zimmer's group now 11 pieces); "Stay - Interstellar"
  (neither side known) kept 1.10's reading. Library › All (`02`), the artwork arriving (`03`, `04`): 106 artists
  looked up in 59 s (Debussy and Satie by the canonical path), **102 with a photograph** (`m28/artwork-log.txt`,
  `m28/photograph-count.txt`).
- **Playlists**: MIDI first under Newest first, its cover Adele's (its first piece in path order), then Popular and
  Epic on piano (`05`), the panel the same (`m28/playlists-after.json`); the sort pop-up open, the menu within the
  list's pane with its end at the button's (`07`); Name puts the built-ins first, as before (`08`). MIDI's page: 265
  pieces in path order, the artists on the rows, their photographs (`09`, `10`); Now playing's panel on a Coldplay
  piece (`06`).
- **Composers**: 107 groups (the 106 artists with pieces, and "Stay"); Claude Debussy and Erik Satie in `debussy`
  and `satie`; Louis Armstrong and Craig Armstrong apart (`11`, `12`, `m28/composers-after.json`). A piece whose
  artist has no photograph shows its roll card (Bruno Major's "Nothing", `13`).
- **Dark** (`14`–`16`); **font scale 2.0** on the header row (landscape `17`: the button on one line beside the
  eyebrow; portrait `18`, the menu open within the pane `19`).
- **A new upload.** `Spring Recital.zip`, made in the scratchpad (three synthetic files in two artist folders,
  "Paper Roll Trio" and "Tracker Bar Duo", no root folder), through the panel: the Add tab "Imported 3 pieces", "In
  the playlist Spring Recital" and **Open the playlist** (`20`), which opens the Library section on it (`21`); the
  tablet's import bar "Imported 3 pieces · in the playlist Spring Recital." with the playlist first (`22`). A loose
  synthetic `Lullaby for the Hall.mid`: "In the playlist Uploads" (`23`), Uploads first in the panel's list and the
  tablet's (`24`, `25`). The log: "Import: 3 new, 0 already there, 0 failed, in a playlist called Spring Recital",
  then "…, in a playlist called Uploads".
- **No crash** in the run's log (`m28/logcat-final.txt`).
- **Tests**: 1,328 before, **1,372** after, none failing; 12 skipped (13 before: `PinnedKeyTest`'s header check runs
  now that `firmware/include/ota_pubkey.h` exists beside this repository, written there on 2026-10-01 outside this
  run). `lintDebug`: 0 errors, 30 warnings, the same 30 as at `5d4fc80` (`m28/lint-final.txt`). The debug APK
  29,465,278 bytes.

## Deviations from the brief, and why

- **The sort menu is `GlassPopover(alignment = Alignment.End)`, not Material's dropdown (`GlassDropdownMenu`).** The
  brief names a `GlassMenu`; both wear the menus' glass (`GlassMenuContainer`, `GlassMenu.kt`), but Material's
  dropdown places itself by the window, its start at the anchor's start wherever the window has room, so from a
  button at the end of the list's pane it would open across the divider over Now playing. The popover's alignment
  is the rule `GlassPopoverTest` holds (+1 test for this menu, and a check of its call). Its rows are radio-button
  selectables in a `selectableGroup`, the current one checked. As first built, its rows filled the window's width
  (a popup offers its content the whole window); its column now takes its widest row's width (`123be97`, seen on
  the emulator).
- **`resolve` also reads a composer typed in Rename** (the brief names the `Composer - Title` path): typing "Ed
  Sheeran" joins the Ed Sheeran folder's group rather than starting `sheeran`, D3's own reason.
- **A reversed name's artist is spelt as its folder is** (`artistNamed`): "Cornfield Chase - hans zimmer.mid" beside a
  "Hans Zimmer" folder is by Hans Zimmer, one group.
- **Release builds leave the playlist's name out of the log** ("Library: 265 pieces put in the playlist, 264
  artists filled"; "Import: …, in a playlist"): the link's trail and the importer's warnings travel in Share
  diagnostics and Send a log, and in release builds they never name the person's folders or files (the v1.2
  audit's F17). Debug builds name it, as the brief's line does.
- **An import that only filled artists counts** (`ImportProgress.filled`, `piecesChanged`): the built-ins refresh and
  artwork is asked for after it, as after one that added pieces.
- **`aboutPerformer` reads on past a person's description that names a work**: "American indie game developer (born
  1991)" (Toby Fox) was first refused as a game's; a description with a person's years now goes on to the extract's
  first sentence, which runs past initials ("Robert F. "Toby" Fox is …") (`366a7d8`; the emulator's first round, where
  Toby Fox had no photograph).
- **The repair asks for artwork only when it named an artist and Fetch artwork automatically is on**: the brief's
  "then `artwork.requestComposers(false)`" with D5's "Fetch artwork automatically still decides".

## Residuals

- **Not run on the school tablet.** The emulator reproduced its state from the same zip; there the repair runs at
  the first start of 1.10.1.
- **An older upload's zip name was never kept**: an upload from before 1.10.1 whose zip had no single top folder
  becomes a playlist per top folder (of two pieces or more), named after it, and its artists stay blank (its files
  lie in that folder itself). A folder picked on the tablet before 1.10.1 is the same, its paths starting inside
  it. Steven's zip has its `MIDI` folder, so it comes out whole.
- **A tablet that imported `ALL-SONGS.zip` itself** (not Steven's library, whose pieces have collections) gets a
  playlist **ALL SONGS** of those pieces at the first start, as importing the zip now does (one top folder, `ALL
  SONGS`, and no INDEX.csv).
- **One artist, two groups, across versions**: pieces already grouped by a surname key (`zimmer`, from a `Hans Zimmer
  - Time.mid` imported before 1.10.1) stay apart from a later "Hans Zimmer" folder's (`hans zimmer`), both shown as
  Hans Zimmer: nothing in the library is re-keyed (D3). Renaming the older pieces' composer joins them (`resolve`).
- **A canonical composer named in a form `canonicalOf` doesn't read** (a folder "Chopin, Frédéric") is an artist of
  its own, keyed by the whole name.
- **No photograph for three artists** whose Wikipedia pages have no free image (Bruno Major, Hiroyuki Sawano, Shoji
  Meguro), nor for a company (Nintendo): roll cards, by design. A joint name whose own page is not a performer's is
  looked up as its first name, which could find a namesake's band; five lookups bound it.
- **A composer's mosaic whose pieces' files are all gone** shows its frame with empty cells, as before 1.10.1 (a roll
  card is missing only when the file is gone, unreadable, or too large for the memory left).
- **"Stay - Interstellar"** (neither side known) reads as it always has: the composer "Stay". Rename fixes it.
- **Mitski's file** in the zip has Tom Odell's "Another Love"'s bytes: the library keeps one piece (Tom Odell's, the
  first in the zip), so Mitski has no group; one of the two files is probably the wrong song.

## Tests added in M28

`ComposerNamesTest` +6 (a canonical composer's folder joins the group; anyone else whole, as written; the two
Armstrongs apart; one word; a joint name whole and apart; a 1.10 library's 125 composers unchanged).
`TitleHeuristicsTest` +6 (the folder's artist; reversed when only the right side is known; the parenthetical;
neither or both sides known, as before; the root and the artist folders; the Mac's files). `ImporterTest` +9 (a
zip's artist folders, a reversed name, the Mac's extras uncounted; a file name's composer joining the library's
artist; a duplicate's blank composer filled; a zip with a root folder → its playlist in path order; `PathOrder`; a
zip without one → the zip's name, cut to a playlist's; pieces already there linked and filled; an INDEX row's piece
kept out of the zip's playlist; a loose panel file → Uploads, files picked one by one → none). `UploadRepairTest` 7
(the zip's 266 paths over synthetic pieces, imported the 1.10 way → one playlist MIDI of 265 in path order, the
artists, the five root files; a second run changes nothing; collections, playlists, Studio's and folderless pieces
untouched, a root of one left alone; a composer of the person's own and a reversed name corrected by hand kept;
names already read left to right kept; 500 a transaction; release builds' line). `ArtworkFetcherTest` +9 (Queen →
"Queen (band)", Passenger → "(singer)"; the description, else the first sentence; Toby Fox; Nintendo not found
within five lookups; a work's page never the singer's; Lady Gaga & Bradley Cooper → Lady Gaga; the five-lookup cap;
the canonical path unchanged). `PlaylistOrderTest` +2 and `LibraryStatesTest` +1 (newest first, the built-ins after
in their order, an empty one hidden; Name as before). `SettingsRepositoryTest` +2 (`playlistSort`; `uploadRepairDone`
housekeeping, not a preference). `ImportCopyTest` +1. `GlassPopoverTest` +1 (the sort menu within the list's pane,
its end at the button's; the call's alignment). Assertions added: `WebApiTest` (`import.playlist`), `WebServerTest`
(the panel's playlists in the backend's order), `WebServerRelayTest` (the playlist through the relay),
`DiagnosticsExporterTest` (40 lines, `playlistSort`, no repair flag), `LocalZipTest` (Steven's library makes no
playlist). `GlassContainersTest` and the rest unchanged and passing. **1,328 → 1,372.**

## The release: 1.10.1 (versionCode 19)

Cut from `main` after M28 (`d05fbc2`): `versionCode` 19, `versionName` "1.10.1" (`-PversionCodeOverride`'s example
now 20), `Provenance.text` "Made by Steven Jin · v1.10.1 · eab16a502f679465", README's version lines, and the entry
drafted at the end of `releases/history.json` (`"draft": true`, tag `v1.10.1`). The designer's review of the
tablet-size screens found the run as designed; one note for a later pass: at font scale 2.0 a playlist tile's meta
line is cut ("265 PIEC…") where it should wrap. The push that publishes it also carries the deployed Cloudflare
values of `cloud/wrangler.*.jsonc` (`ad10e07`).

# v1.11 — M29: keyboards, live playing, recording, any MIDI piano

Read `DESIGN.md › v1.11 — instruments, live playing, recording` first. Fable's brief (the session scratchpad's
`m29-keyboards-brief.md`) with the approved plan (`plans/PLAN.md`) and the implementation plan (`plans/plan-midi.md`,
sections a–i), built by Opus on `main` from `1bb6887` (1.10.1, versionCode 19) in six phases, a commit each: `ba01334`
(0, hardening), `1b5292b` (1, a keyboard in), `0e031e2` (2, Live), `222fc2b` (3, recording), `6a58c0f` (4, any MIDI
piano out) and phase 5's (status and these documents). **No version bump, no `Provenance.text` change, no provenance
signing, no release build, no push, no relay deploy**: the integrator's, at 1.11. The firmware repository was not
touched; `firmware/docs/BLE_LIVE.md` (written before this run) is what the firmware should change for live playing.

## Files

`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `T` = its tests. Added:

- Phase 1: `M/midi/MidiStreamParser.kt` (with `KeyboardHolds`, `KeyEvents`); `M/instruments/MidiPorts.kt` (the seam:
  `MidiPorts`, `MidiDeviceRef`, `MidiTransport`, `MidiNames`, `MidiChoice`), `AndroidMidiPorts.kt` (and
  `AndroidMidiBluetooth`, `CombinedMidiPorts`), `EmulatedMidiPorts.kt`, `MidiDebugHooks.kt`, `MidiDevices.kt`,
  `MidiKeyboard.kt`, `MidiPicker.kt`; `M/ui/InstrumentCopy.kt` (every word of M29); `M/ui/screens/piano/
  MidiPickerSheet.kt`, `pages/KeyboardPage.kt`; debug only: `app/src/debug/java/.../debugmidi/TestMidiDeviceService.kt`
  and `app/src/debug/res/xml/test_midi_device.xml`.
- Phase 2: `M/midi/InstrumentProfile.kt`; `M/instruments/LiveThru.kt`, `LiveTimingHold.kt`.
- Phase 3: `M/record/Recorder.kt`, `RecordingPieces.kt`, `RecordingSession.kt`; `M/ui/screens/keys/RecordingSheet.kt`.
- Phase 4: `M/instruments/MidiPortLink.kt`, `InstrumentSwitch.kt`; `M/ui/screens/piano/pages/InstrumentPage.kt`.
- Tests: `T/midi/MidiStreamParserTest`, `InstrumentProfileTest`; `T/instruments/FakeMidiPorts` (the fakes),
  `MidiKeyboardTest`, `MidiDevicesTest`, `LiveThruTest`, `LiveTimingHoldTest`, `MidiPortLinkTest`,
  `InstrumentSwitchTest`; `T/record/RecorderTest`, `RecordingPiecesTest` (with the session's tests);
  `T/ui/InstrumentCopyTest`, `T/ui/screens/keys/KeysNoteTest`; `cloud/test/status.test.ts`.

Changed (each addition marked v1.11 — M29): `M/ble/PacedWriter.kt` (the live lane, `nextMessages`),
`GattPianoLink.kt` (foreign addresses, bonded candidates, `sendLive`, audio priority), `PianoScanner.kt`
(`ScanThrottle` shared, `acquire`), `PianoLink.kt` (`sendLive`; the MIDI piano's `LinkError`s); `M/midi/MidiBatch.kt`
(`MidiSink.sendLive`), `NoteRouter.kt` (the profile, the keyboard's bank), `KeyMap.kt` (a range), `SmfWriter.kt`
(`Control`); `M/audio/TabletSound.kt` (`TeeSink.sendLive`); `M/player/PlaybackEngine.kt` and `Player.kt`
(`connectedAnew`, the idle pedal, `external`, `silenceExternal`, `setProfile`, `allKeysOff`); `M/AppGraph.kt` (the
devices, the keyboard, Live, the recorder, the MIDI link and the switch; `chooseInstrument`, `restoreInstrument`,
`allKeysOff`; diagnostics); `M/MainActivity.kt` (the emulator's MIDI extras; `onStop` closes Live and ends a take);
`M/settings/Settings.kt` (`keyboardId`, `keyboardName`, `liveToPiano`, `instrumentKind`, `midiOutId`, `midiOutName`);
`M/data/LibraryRepository.kt` (`addRecording`, `recordings`), `M/data/imports/ComposerNames.kt` (`RECORDED_LIVE`);
`M/ui/Routes.kt` (`SettingsPage.Instrument`, `Keyboard`), `NavHost.kt`, `StudioCopy.kt`, `components/
ConnectionLine.kt`; `M/ui/screens/keys/KeysScreen.kt`, `KeysViewModel.kt`, `KeysPills.kt`, `PlayableKeyboard.kt`,
`KeyMiniMap.kt`; `M/ui/screens/piano/PianoScreen.kt`, `PianoViewModel.kt`, `HubGroups.kt`, `GroupSummaries.kt`,
`ConnectionCard.kt`; `M/ui/screens/nowplaying/StudioReviewBanner.kt`; `M/web/WebBackend.kt` (`WebInstruments`),
`WebApi.kt`, `AppWebBackend.kt`, `relay/RelayStatus.kt`; `M/service/WebService.kt`; `M/diag/DiagnosticsExporter.kt`;
`app/src/main/assets/web/index.html` and `app.js`; `app/src/main/AndroidManifest.xml` and `app/src/debug/
AndroidManifest.xml`; `cloud/src/shared/protocol.ts` and `cloud/test/d1-throttle.test.ts`; the tests below; `DESIGN.md`,
`README.md`, `docs/SECURITY_AUDIT.md`. No schema change (the database stays at version 3).

## Phase 0 — hardening (`ba01334`)

- **The live lane** (`PacedWriter.enqueueLive`): the keyboard's and the screen's messages go ahead of a piece's
  backlog, under one guard: a live message whose key or controller still waits in the backlog goes right behind the
  last such message (`insertBehindLast`), so each key's messages reach the wire in the router's order and a live
  Note Off never overtakes a piece's Note On. The stop sequence clears both lanes; the backlog's drop never touches
  the lane. `MidiSink.sendLive` (default `send`), `TeeSink`, `GattPianoLink.sendLive`.
- **The idle pedal**: `PlaybackEngine.advance` sends a held-back pedal change with nothing playing.
- **A stop on every connection** (`PlaybackEngine.connectedAnew`): each new connection or epoch begins with the stop
  sequence, playing or not, so a pedal the firmware kept from an old connection never outlives it.
- **The piano's link and other devices**: an address the MIDI side uses is foreign (`isForeign`): never connected to,
  never taken from another app; a nameless device this tablet is paired with is never a candidate, and one paired by
  the time it is connected to is passed over. `ScanThrottle` is one, shared, thread-safe (`acquire`); the link's
  thread runs at `THREAD_PRIORITY_AUDIO`.

## Phase 1 — a keyboard in, the Keys tab its monitor (`1b5292b`)

- **The seam** (`MidiPorts`): `AndroidMidiPorts` lists byte-stream devices only (`getDevicesForTransport` on API 33+;
  a MIDI 2.0 device's UMP twin ignored), another app's virtual ports only in debug builds; `EmulatedMidiPorts` gives
  the emulator "Emulated keyboard" (fed by `adb … --es dev.stevenjin.stevenpiano.EMULATOR_MIDI "<hex>"`, unplugged
  with `EMULATOR_MIDI_PLUG false`) and "Emulated MIDI piano" (logs every byte: `adb logcat -s MidiPiano`).
- **`MidiDevices`**: what Android lists; the picker's Bluetooth search (12 s, on the shared budget, a wait line when
  it is spent); foreign addresses for the piano's link; a device opened once for all its holders, 15 s to open.
  Steven Piano is never listed (its name, the remembered address, a nameless device), closed at once if a device
  turns out to be it, never paired with.
- **`MidiKeyboard`**: every output port heard; USB back when Android lists the same identity again; Bluetooth reopened
  with the piano link's backoff (1, 2, 4, 8, 15 s, then every minute after ten minutes); pairing asked once; everything
  let go when the device goes, when Active Sensing stops for a second, when keys or a pedal stay down with no byte for
  60 s, on Forget or another choice; malformed bytes counted in the trail.
- **`MidiStreamParser`**: running status across buffers, velocity-0 offs, real-time bytes inside messages, SysEx and
  system common dropped, malformed bytes counted, nothing allocated from input, every value masked; CC120 and
  CC123–127 let go of that channel's keys, CC121 of its pedals, System Reset of everything.
- **The UI**: INSTRUMENTS with the Keyboard row and page, the picker (a `GlassSheet`), the Keys tab's eyebrow and keys
  lit by the keyboard. Manifest: `android.software.midi` and `android.hardware.usb.host`, not required; no new
  permission.

## Phase 2 — Live (`0e031e2`)

- **`InstrumentProfile`**: StevenPiano (24–107, the 100 ms rule, a held key shared, CC64 paced, stop: CC64 0 then
  CC123) and StandardPiano (21–108, re-strike, CC64/66/67 at once, stop: every held key's Note Off, the pedals up,
  CC123).
- **`NoteRouter`**: the keyboard's bank (`externalNoteOn`, `externalNoteOff`, `externalPedal`, `silenceExternal`): the
  note as played (no transpose; folded or dropped as Fold says), the velocity percentage applied; the keyboard's CC64
  at once when it crosses 64 and paced between; letting go hands the pedal back to the higher of the piece's and the
  screen's (a keyboard pedal never touched stays untouched).
- **`LiveThru`**: the gate (DESIGN's conditions), fresh presses only, a Note Off only for a Note On let through, every
  close lets go through `Player.silenceExternal`; the breaker (200 Note Ons a second, 32 held, 64 malformed bytes a
  second) switches Live off and says why. **`LiveTimingHold`** holds the piano's `humantime` at 0 while Live plays
  Steven Piano and puts it back after. `PlaybackEngine.external` sends one buffer as one batch on the live lane;
  `LiveTiming` (median and worst, arrival to the lane) goes to the trail.
- **The Keys tab**: the Live pill, the pills one scrollable row, the line under them, "Keyboard connected. Live is
  off." with a hollow dot, the screen kept on; `MainActivity.onStop` closes the gate. Keyboard activity counts as a
  touch for display mode.

## Phase 3 — recording (`222fc2b`)

- **`Recorder`**: before the router; keys 0–127 with their velocities and every CC64/66/67 value, from the keyboard
  (its own timestamps within 250 ms, never going backwards, else the arrival) and the screen; caps an hour, 200,000
  events, five minutes of silence; on stop in time order, a held key closed, the pedals lifted, everything from 0.
- **`RecordingPieces`**: the take written to `filesDir/recordings/pending-*.mid` first, then through the importer
  (`StudioLibrary.add`) as "Recording · <medium date> <short time>" by "Recorded live", its text meta stamped to the
  second (once more with " (2)" if the library holds the bytes), first in the built-in playlist `recordings`
  (`LibraryRepository.addRecording`: never a catalogue list, so never refreshed and never offered to guests), undecided
  in Studio's review (hoisted into `AppGraph`); in kiosk mode at most 30 wait, the oldest discarded; leftovers saved
  at start (`recoverPending`). **`RecordingSession`**: Record, the caps' watch, Stop, the save off the main thread.
- **The UI**: the Record control, the sheet after Stop, "Nothing was played.", Now playing's banner line for a
  recording; a recording's Keep waits for the PIN in kiosk mode as its Discard does.

## Phase 4 — any MIDI piano out (`6a58c0f`)

- **`MidiPortLink`** (a `PianoLink` over the seam): its own `PacedWriter` (burst 20, then one message a millisecond,
  the live lane first) drained on a thread at audio priority (`nextMessages`), whole three-byte messages to input
  port 0, never running status; the keys it sent tracked; `disconnect`, another choice and `emergencySilence` (the
  crash handler's, straight to the port) send every tracked key's Note Off, CC64/66/67 0, CC120 and CC123 before it
  closes. Unplugged by cable: "<name> isn't connected…" and connected again when Android lists it; lost over
  Bluetooth: Reconnecting with the piano's backoff; pairing asked once; an input another app holds is said so.
  `choose` refuses Steven Piano (its name or address): it is never opened, paired with or made foreign.
- **`InstrumentSwitch`**: the app's one `PianoLink` hands everything to the instrument chosen, so the player, Keys,
  the tablet's sound, the web panel and the crash handler follow it unchanged; console, firmware version and update
  service are Steven Piano's alone, and the piano's settings and the firmware updater read `AppGraph.stevenLink`.
- **`AppGraph.chooseInstrument`**: refused while the player is locked; pause and silence (`pauseAndFlush`), let go of
  the old link, choose, select, the player's profile, remember (`setInstrument`), connect. `restoreInstrument` at
  start (never a remembered MIDI piano that is Steven Piano); auto-connect connects either. A keyboard that is the
  MIDI piano too sets `LiveThru.setLooped`.
- **The UI**: the Instrument row and page, the hub without PIANO and without the piano's status line under a MIDI
  piano (a piano page open beside it falls back to Playback), the connection card's name and words, no Bluetooth
  permission asked for a cable, the 2 s coil note on Keys only for Steven Piano.

## Phase 5 — status and documents

- **The panel**: `WebState.instruments` (`WebInstruments.of`), `/api/state`'s `instruments` beside `link`
  (`{instrument: {kind, name, state}, keyboard: {name, transport, state} | null, live, recording}`; `live` is the gate
  open, not the switch); `app.js`'s two lines (`instrumentLines`) under "Sent to piano" and on the Piano page, which
  under a MIDI piano shows only its link line, the two lines and the hidden note. Read-only: no route takes them.
- **The relay**: `RelayStatus.report` adds `instruments` without names (`{instrument: {kind, state}, keyboard:
  {transport, state} | null, live, recording}`); `sanitizeStatus` keeps exactly that (texts cut to 16 and 24, the
  booleans strictly `true`). The status is sent again when the instrument, the keyboard, Live or a take changes
  (`WebService`), and the panel's socket on the same.
- **Diagnostics**: `about.txt` gains *Instrument* and *Keyboard* lines (`DiagnosticsText.instrumentLine`,
  `keyboardLine`, with Live's state and why it is off); the MTU only when above 0; `settings.txt` the six new
  preferences (46 lines).

## The release table, as built

| Case | What lets go |
|---|---|
| The app leaves the foreground, the screen goes off | `MainActivity.onStop`: `player.silenceLive()`, `liveThru.setOnScreen(false)`, a take ended and saved |
| The Keys tab left | `KeysScreen`'s lifecycle effect: `KeysViewModel.onScreen(false)` |
| Live switched off; the keyboard or the instrument changed | `LiveThru` closes: `Player.silenceExternal`; an instrument change first `pauseAndFlush`es the old link |
| The keyboard unplugged, lost, forgotten | `MidiKeyboard` lets go (`letGo`) and the gate closes |
| The keyboard silent | Active Sensing seen then absent for 1 s, or keys or a pedal down with no byte for 60 s |
| All Notes Off or All Sound Off (CC120, CC123–127), CC121, System Reset from it | the parser's release: that channel's keys, its pedals, everything |
| The flood breaker | `LiveThru`'s trip: let go, Live off, the line |
| The instrument's link drops | `Player.onLinkState` (`silenceLive`, pause) and the gate's target |
| A firmware update | `Player.external` refused while locked; `stopForUpdate`'s stop sequence; `chooseInstrument` refused |
| A crash | `App`'s handler: the switch's `emergencySilence` (a MIDI piano: offs, pedals, CC120, CC123 to the port) |

## Greps (v1.11 — M29)

`Color(0x` outside `ui/theme`: none. `Modifier.blur`: none. `0.0.0.0`: none. `dev.chrisbanes`: `Glass.kt` alone.
`hazeSource`/`HazeState`: `Glass.kt`, `GlassHeader.kt`, `GlassMenu.kt`, `NavHost.kt`, `NotePanel.kt`,
`MainActivity.kt`. `LocalLive`: `LiveDot.kt` and the theme; `LocalNoteSounding`: `ScorePages.kt` and the theme.
`LensAlpha`/`GlassLens`/`LensVeil`: none. `ModalBottomSheet(`, `DropdownMenu(`, `AlertDialog(`, `Popup(` outside the
glass wrappers: none (the time picker's `BasicAlertDialog` holds `GlassDialogSurface`). M29's sheets are
`GlassSheet`s (`MidiPickerSheet`, `RecordingSheet`); no colour added; red only through `LiveDot` (`m29/p5-greps.txt`).
The release manifest merged alone (`processReleaseMainManifest`): no `TestMidiDeviceService`, no
`BIND_MIDI_DEVICE_SERVICE`; the two features, not required.

## Measured (2026-10-01, `steven_piano_audit`: API 34, `medium_tablet` 2560 × 1600 at 320 dpi, debug builds)

The audit's AVD, booted headless as `emulator-5560`, stopped at the end, the AVD kept; `steven_piano_tablet` and
`steven_piano` never touched; host port 8737 never used. Screenshots in the session scratchpad, `m29/shots/`.

- **A keyboard in** (`p1-01`–`p1-08`): INSTRUMENTS under the connection card, the Keyboard page, the picker listing
  the emulated keyboard (USB) and the debug test device (Virtual); a chord lit C4, E4 and G4 with the eyebrow
  "KEYBOARD · EMULATED KEYBOARD"; unplugged it let go and read "· not connected", plugged in again it reconnected; the
  debug `MidiDeviceService` lit C3–E3–G3 through Android's own `MidiManager`.
- **Live** (`p2-01`–`p2-05`): a chord forwarded as one batch to `LoggingPianoLink`; leaving Keys, Home, Live off and
  the keyboard unplugged each sent the held key's Note Off and the pedal up; a 210-note flood and a malformed burst
  (after `F6`, which clears running status) tripped the breaker with its line, nothing of the flood reached the piano,
  Live re-armed only by hand; "Keyboard connected. Live is off." with the hollow dot.
- **Recording** (`p3-01`–`p3-07`): a scale, a pedal sweep and a chord recorded; the sheet; renamed and kept; the piece
  in Recordings ("Recorded live · 0:08"), played back with its half-pedal values.
- **Any MIDI piano** (`p4-01`–`p4-09`, `m29/p4-*.log`): Instrument › Another MIDI piano… › Emulated MIDI piano; the card
  "Emulated MIDI piano · Connected", the PIANO group and the status line gone, the note shown; on connecting
  `B0 40 00 B0 42 00 B0 43 00 B0 7B 00`. A library piece played to it: B0 (23) and D1 (26) sent as they are; Clair de
  lune's sustain `B0 40 7F` / `B0 40 00` at once beside its notes; a re-strike as `90 36 5F 80 36 00 90 36 5F`; Pause
  sent `80 38 00 80 3A 00 80 3D 00 80 41 00 B0 40 00 B0 42 00 B0 43 00 B0 7B 00` (explicit offs, the pedals, then
  CC123). Live to it: A0 and C8 (`90 15 50 90 6C 50`) and a half pedal (`B0 40 40`) as played, no coil note; leaving
  Keys `80 3C 00 80 40 00 B0 40 00`. Steven Piano chosen again: the MIDI piano got `B0 40 00 B0 42 00 B0 43 00 B0 78 00
  B0 7B 00` and closed; PIANO back.
- **Light and dark, font scale** (`p5-01`–`p5-07`): the Instrument and Keyboard pages and Keys in both; Keys at font
  scale 2.0 with the pills on one row and the coil note.
- **Kiosk** (`p5-08`–`p5-16`): the app made device owner with `dpm set-device-owner`, a test PIN (generated, kept in a
  scratchpad file, never shown), kiosk on (lock task `LOCKED`). On Keys, Live off and on and a take recorded without
  the PIN; the sheet "Saved to Recordings. Someone with the PIN keeps or discards it." with Listen and Done ("0:01 · 3
  notes"); the Keyboard row asked for the PIN; with it, the page and its picker. Kiosk off with the PIN, the device
  owner given back (`debug.stevenpiano.releaseowner`, "no owners", lock task `NONE`).
- **The panel** (`p5-17`–`p5-19`, `m29/p5-panel-state-*.txt`): through `adb forward tcp:8738 tcp:8737` and a scratchpad
  proxy (as M28's): "Instrument: Steven Piano / Keyboard: Emulated keyboard"; with the MIDI piano, Keys on screen and
  a take running, "Instrument: Emulated MIDI piano / Keyboard: Emulated keyboard · Live · Recording", and its Piano
  page with only those lines and the note.
- **Tests**: 1,372 before (12 skipped), **1,503** after (12 skipped), none failing; per phase 1,386, 1,432, 1,462,
  1,483, 1,500, 1,503. `lintDebug`: 0 errors, 30 warnings, the same 30 as at `1bb6887`. `cloud/`: 79 → **82** tests,
  `tsc --noEmit` clean. The debug APK 29,905,820 bytes.

## Deviations from the brief, and why

- **A MIDI piano on a cable that is unplugged is an error with its words, not "Reconnecting"** (plan-midi (d)): the app
  cannot plug a cable back in, so the card says what to do ("<name> isn't connected. Plug it into the tablet or switch
  it on nearby, then tap Retry.") and connects by itself the moment Android lists the same device again. The player
  pauses as on any drop.
- **Bluetooth devices are reopened by address with the backoff, without a scan** (plan-midi (d): "a direct open, then
  scans"): `openBluetoothDevice` connects by address; a scan would spend the five-in-30-seconds budget that Steven
  Piano's link and the picker share. Keyboards and MIDI pianos alike.
- **The Instrument page's All keys off** is the instrument's stop sequence through the player, and on Steven Piano also
  its own `off` over the console (as Firmware and status's): the page serves both kinds.
- **The panel's "Live" is the gate open** (keys going to the instrument now), not the remembered switch: a panel line
  saying Live while the Keys tab is closed would be wrong.
- **Under a MIDI piano the panel's Piano page keeps its link line, the two lines and the note**, and hides Steven
  Piano's pages and its actions (Read status, All keys off, Save now are the piano's console's).
- **The drawings stay 84 keys** (plan-midi's own note): A0–B0 and C8 from a keyboard light an octave in on Keys.

## Residuals — what only the hardware can tell

- **Real keyboards**: a Bluetooth MIDI keyboard and a USB one on the school tablet: pairing (Android's request, the
  "asks to pair" line), latency by ear, Active Sensing, unplugging mid-chord, two Bluetooth links at once (the piano's
  and the keyboard's) and their jitter. The emulator had only the emulated ports and the debug service.
- **The piano's own limits**: the 2 s hold, the 100 ms same-key gap, the timing scatter and `humantime 0` (held at 0
  while Live plays), the pedal board: they bound the live feel until the firmware does what `BLE_LIVE.md` asks.
- **A real MIDI piano as the instrument** (Roland, Yamaha, Kawai…): whether it honours CC123 (the explicit offs are
  there for those that don't), CC66/67, re-strikes, and its USB identity across replugs and reboots.
- **The flood breaker's numbers** (200 Note Ons a second, 32 held) are first guesses, to be tuned on the piano.
- **Android's Bluetooth MIDI service** on the school tablet's Android version: if it proves weak, the seam allows a
  fallback on the app's own GATT client later.
- **The relay must be redeployed** (`npm run deploy:relay`) for the console's status to keep `instruments`; until then
  the relay drops the unknown field and nothing else changes.

## Tests added in M29

`MidiStreamParserTest` 15 (running status across buffers, velocity 0, real-time inside messages, SysEx dropped,
malformed data, the holds). `MidiKeyboardTest` 15 (connect, ports, replug, Bluetooth backoff, pairing asked once and
never for the piano, Active Sensing, the 60 s hold, Forget, malformed counts). `MidiDevicesTest` 9 (listing, the
picker's search and budget, never the piano, foreign addresses, shared opens). `InstrumentProfileTest` 3,
`NoteRouterTest` +9 (the keyboard's bank, keys 21 and 108, the pedal crossing 64, the standard stop). `LiveThruTest` 8
(the gate, fresh presses only, the breaker, looped), `LiveTimingHoldTest` 3, `LiveInputTest` +6 (the idle pedal flush,
the keyboard through the engine), `PlayerTest` +2, `PacedWriterTest` +6 (the lane, the guard, the stop, the drop, and
300 random interleavings of file, screen and keyboard traffic against the real router: a key the router let go is
never left down on the wire), `GattPianoLinkTest` +5 (a foreign address, a bonded candidate, the shared budget).
`RecorderTest` 7 (caps, trim, pedal values), `RecordingPiecesTest` 10 (unique files, the playlist, the kiosk cap,
crash recovery, the session), `SmfWriterTest` +1 (controller values round-trip), `ComposerNamesTest` +1.
`MidiPortLinkTest` 11 (epochs, whole messages at the pace with the live lane first, the stop replacing what waits,
disconnect and the crash's stop, busy, unplugged and back, not plugged in, Bluetooth pairing and backoff, Steven
Piano never chosen and its address never foreign, another choice letting go), `InstrumentSwitchTest` 2.
`InstrumentCopyTest` 7, `KeysNoteTest` 2, `LinkErrorCopyTest` +1, `GroupSummariesTest` +1, `PianoPagesTest` +1 (PIANO
hidden under a MIDI piano), `SettingsRepositoryTest` +3, `WebApiTest` +1 and the state's key set, `RelayStatusTest` +1
(no name) and its key set, `DiagnosticsExporterTest` +1 (46 lines, the about lines, no MTU of 0), `WebAssetsTest`'s
words, `RoutesTest`'s pages. **1,372 → 1,503.** `cloud/test/status.test.ts` 3 (kinds, transports, states and the two
switches kept, names dropped; cut and refused; through the room) and `d1-throttle.test.ts`'s key set: **79 → 82**.

## The release: 1.11 (versionCode 20)

Cut from `main` after M29 (phase 5 is `8b38c7f`): `versionCode` 20, `versionName` "1.11" (`-PversionCodeOverride`'s
example now 21), `Provenance.text` "Made by Steven Jin · v1.11 · eab16a502f679465", README's version lines, and the
entry drafted at the end of `releases/history.json` (`"draft": true`, tag `v1.11`). The designer's review of the
tablet-size screens found the run as designed; for the Piano tab's reorganisation (M31b): with the Instrument page
open beside the hub, Disconnect shows twice (the hub's card and the page). The relay Worker is redeployed after
this release so the console's status carries the instrument and keyboard states.

# v1.12 — M30: the Studio tab (typed ideas, live progress, history, drawn covers, the aura)

Read `DESIGN.md › v1.12 — Studio as a tab` first. Fable's brief (`m30-studio-brief.md`) with `plans/PLAN.md` and
`plans/plan-studio.md` (findings 1–12; (a) StylePrompt, (b) progress, (c) persistence, (d) the tab, (e) the drawn
cover only), built by Opus on `main` from `7c24ff5` (1.11). Out of this run, as the brief says: no text model, no
picture model, no `/api/studio/prompt`. Mid-run the owner narrowed verification: core tests only, **no emulator
work** (the integrator's smoke pass covers it), short documents. **No version bump, no provenance signing, no
release build, no push**; ONNX Runtime stays 1.28.0, its telemetry check untouched.

## What was built

- **The idea parser** (`studio/style/`): `StyleVocabulary` (moods, tempo words, lengths, keys, 28 forms,
  comparatives, again and different, negators, stop words), `StyleLibrary` (the library as the parser reads it:
  Studio's pieces, recordings, pieces under 15 s or 16 notes left out; channels and built-in lists as the
  catalogue), `StylePrompt` (passes: patterns, titles, negators, names, forms, catalogue, moods; a refinement when no
  seed-bearing word; the understood line; at most 12 unused words; 200 code points, 40 words, no regular expression
  from input; `title()` builds titles from what was understood), `SeedPicker` (on the job thread: at most four
  candidates read; a mode match and the nearest tempo; a tempo word held to 0.67–1.5 × the seed's).
- **Progress**: `Sampler.generate(onEvent)` after the event is in the history; `PreviewRoll` (append-only, rests
  skipped, keys folded, `Studio.preview`, never in the job); `ProgressMeter` (at most every 500 ms; time left after 3 s
  or 5 %, the smaller of the token and music estimates; cold start from `StoredCalibration`, the median ms a token of
  the last five jobs); `StudioJob` gains `turnId, tokens, budget, musicMs, targetMs, etaMs, steps, notes, stop` and
  `JobStep.Shaping`. The budget follows the length: `PromptBuilder.MAX_TOKENS` 13,500.
- **Schema v4** (`GenerationEntity`, `GenerationDao`, `GenerationSql`, `MIGRATION_3_4`, `SchemaV4`): the table
  `studio_generations` as plan-studio (c) lists it, plus `spec` (the asked `StyleSpec`, encoded); the backfill gives
  every 1.7–1.11 Studio piece a "kept" turn. `Generations`/`RoomGenerations`; turns written as a job queues and
  filled as it runs; leftover "pending" turns read "interrupted" at start; the history trimmed to 200 (never a
  waiting piece's turn).
- **Keep or Discard on the history** (`RoomReview`, `StoredWaiting` in `studio/StudioStore.kt`): a Studio piece waits
  while its turn is "made"; the one-off import of the old DataStore set reopens the backfilled turns it names, once
  (a flag), retried while the set can't be read. In kiosk mode at most 30 Studio pieces wait (the oldest discarded).
- **Covers and the playlist**: `data/art/StudioCover.kt` (`CoverInput`, `CoverSpec`, `StudioCover.render`: 768 px,
  `StrictMath`, a paper or ink ground, an accent from the key on the circle of fifths and the mood, twelve
  pitch-class rays round a disc set by the register, a density band, every note a faint perforation) and `Png.kt`;
  `ArtworkRepository.setPieceCover` and `pieceCovers`, `describe` now merges; covers drawn at start for pieces made
  before. `data/builtin/StudioPlaylist.kt`: **Made in Studio** from the history, refreshed after each save and with
  the built-ins. `PieceArt` takes a piece's own cover first (rows of Studio pieces, the piece sheet, the mini
  player, the now-playing panel, the resting screen); the panel's `art: "cover"` with `artVersion`.
- **The tab** (`ui/screens/studio/`: `StudioScreen`, `StudioViewModel`, `StudioTurns`, `StudioAccess`, `ComposeSheet`
  moved here with `ComposeStart`): `Route.Studio` between Keys and Piano (`ic_tab_studio`), the compact bar's label
  floor 0.85; Studio out of the Piano hub (`SettingsPage.Studio`, `StudioPage.kt` and the hub's Studio row gone);
  the Library's + sheet "Compose in Studio…" opens the tab; both notifications open it (`OPEN_STUDIO_REQUEST` 11,
  updates at most once a second). **The aura**: `ui/theme/Aura.kt` (the four stops, light and dark, and
  `LocalAuraStops`), `ui/components/Aura.kt` (`AuraRing`, `AuraHairline`, `AuraDot`; a `SweepGradient` turned by a
  frame clock, a `BlurEffect` glow on API 31+, three fading strokes below).
- **Smaller fixes**: the Library bar's hairline follows the job's own measure (composing swept before); a Studio
  piece never joins a channel by its title (`Channels.Match`: "Calm, after Clair de lune" isn't Clair de lune).

## Deviations from the brief, and why

- **No emulator evidence** (the owner's call mid-run): no 1.11 → 1.12 upgrade on `steven_piano_audit`, no
  screenshots, no `gfxinfo` frame figure. The migration is proven on real SQLite only (`SchemaV4Test`); **the
  integrator's smoke pass should install the 1.11 debug build, compose a piece, then install this build over it** and
  check the piece is in Made in Studio with a cover and its turn.
- **Recordings keep their waiting set in the DataStore.** M29 (after the plan) put the tablet's recordings in the
  same review. They are not Studio's turns, so a Studio piece waits on its turn and a recording in the old set; a
  piece is either a turn's or not, so the two stores never speak of the same piece.
- **A `spec` column** holds the asked spec (encoded, no typed text but a title's folded words), so "Another like
  it", "different" and refinements work after a restart; the variant lives inside it.
- **The web panel**: the job JSON's new fields and covers only. The preview's notes route
  (`/api/studio/jobs/{id}/notes`, plan (b)) waits for M32, which builds the panel's cards.
- **Listen** plays the piece and opens Now playing, as the old Studio page did. The piece sheet has no credits
  line (the card carries them). No minutes cap from the calibration: there is no bench figure yet.
- **Kept simple, as the owner asked**: the shelf's long press is a plain glass menu; refinements are the words in
  `StyleVocabulary` (slower/faster with "a bit" and "much", longer/shorter, a mood or a comparative, a key, another,
  different).

## Greps (v1.12 — M30)

The aura's eight hexes: `ui/theme/Aura.kt` alone. `LocalAuraStops`: provided by `Theme.kt`, read by
`ui/components/Aura.kt` alone. `Color(0x` outside `ui/theme`: none. `BlurEffect`: the aura's glow alone;
`Modifier.blur`: none. Sheets and menus: `GlassSheet` and `GlassDropdownMenu` (GlassContainersTest still passes).

## Tests added in M30

`StylePromptTest` 12 (a composer with a form; a title with a tempo word; mood, tempo and key; the length cap; the
refinements; a negated word; unused words never in the line; accents and case; hostile input and 10,000
characters; a shuffled library; the spec's line; titles). `SchemaV4Test` 5 (4.json's statements; a 1.11 database
with Studio pieces migrates on real SQLite and is backfilled; the shape Room expects; foreign keys null on delete;
the history's own statements: the import, Keep and Discard, Made in Studio, interrupted, the trim). `RoomReviewTest`
3 (the one-off import once; an unreadable set retried; Studio pieces and recordings each in their store).
`StudioAccessTest` 4 (the kiosk rules). `SamplerTest` +1 (`onEvent` after the history, in order, never a partial
event). Updated: `PromptBuilderTest` (13,500), `StudioTest`, `StudioReviewTest` (the new store), `RoutesTest` (five
tabs, no Studio page), `PianoPagesTest`, `GroupSummariesTest`, `WebApiTest` (the job's keys). **1,503 → 1,527**
(12 skipped), none failing. `lintDebug`: 0 errors, 30 warnings, the same 30 as at `7c24ff5`. The debug APK
assembles (29.6 MB).

## Residuals

- The 13,500-token budget's time on the school tablet (five minutes of music may take several minutes there), the
  covers' look by eye, and the aura's frame cost over the glass: none measured in this run.
- The integrator's upgrade check above, and a first composition from a typed idea on the merged build.

# v1.12 — M31a: the split between the score and the notes, and the View menu

Read `DESIGN.md › v1.12 — the split and the View menu` first. Fable's brief (`m31a-split-brief.md`) with the plan's
section 3 (`plans/plan-web-split.md`), built by Opus in the worktree `android-wt-m31a` (branch `m31a-split`) from
`7c24ff5` (1.11), beside M30 on `main`. The owner then cut the run to the feature, the critical tests and short
documents: **no emulator pass** (the integrator smoke-tests the merged build), no version bump, no provenance signing.

## Files

`M` = `app/src/main/java/dev/stevenjin/stevenpiano`. Added: `M/ui/components/SplitPane.kt` (`SplitAxis`, the pure
`SplitRules`, the `SplitPane` layout), `M/ui/screens/nowplaying/ViewMenu.kt` (`ViewMenu`, `ViewShow`),
`res/drawable/ic_view.xml`. Changed: `M/score/ScoreMetrics.kt` (`ScoreWidth.forPage`, `ScoreMetrics.fitting`),
`M/ui/components/ScorePages.kt` (no `width`; `fitting`), `TransportBar.kt` (`TransportMinWidth`), `M/ui/AdaptiveFrame.kt`
(`NotesPlan.split`/`axis`, `notesPlan(display, stacked, side)`; `scoreWidth` and `WideLayout.label` gone),
`M/ui/screens/nowplaying/NowPlayingScreen.kt`, `NotePanel.kt` (`transportFloats`); shared with other runs:
`M/settings/Settings.kt`, `M/diag/DiagnosticsExporter.kt`, `M/ui/screens/piano/pages/DisplayPage.kt`, `GroupSummaries.kt`,
`PianoScreen.kt`, `PianoViewModel.kt`.

## What was built

- **Settings**: `notesSplitStacked` and `notesSplitSide` (`Float?`, null the arrangement's default), the first Float
  preferences; `setNotesSplit(stacked, share)` refuses a share that is not a number and holds the rest to 0–1. A stored
  `wideLayout` of `NOTES_ONLY` reads as 0 for both, `STAFF_ONLY` as 1; the first write stores both and removes the old
  key (an older build then reads its default). `settings.txt` prints the two shares (47 lines).
- **The plan**: wide frames get an axis (medium: stacked, expanded: side by side) and the committed share; 0 is `ROLL`,
  1 is `SCORE`, anything between `STACKED` or `SIDE_BY_SIDE`. Phones are unchanged.
- **`SplitPane`**: a custom `Layout` reading the share in layout, so a drag moves the panes without recomposing them;
  a hidden pane leaves composition and the divider waits at its edge. `SplitRules` holds the arithmetic: the stops
  (12 dp), the minimums, hiding 56 dp past them and coming back at the minimum, the 2 % nudge, the Page-key stops,
  the tick (`CLOCK_TICK`, as play/pause) on resting on a stop and on hiding, TalkBack's state text. Release, double-tap,
  keys and accessibility actions write the setting; the pane holds what it drew until the committed share arrives.
- **Now playing**: the divider only where both views fit unscrolled; short screens keep their fixed heights (side by
  side, their widths follow the share). The transport's float rule reads the committed share, and
  one call site keeps the split and the score in place when the transport moves between glass and solid.
- **Bars per system** from the page's width (`ScoreWidth.forPage`: under 480 dp 2, under 560 dp 3, else 4) through
  `ScoreMetrics.fitting`, which is `forPanel` with that width; the score's 150 ms settle and clipping are unchanged.
- **The View menu** and **Display**: as DESIGN says; `PianoViewModel` loses the five setters that moved; the hub's
  Display row reads the appearance.

## Deviations from the brief, and why

- **The plan's pure moves for the web panel wait for M32**: `score/ScoreStyle.kt`, `score/ScoreMarks.kt`,
  `ScoreMetrics.chordLine` and `ScoreBars.microsAt` (plan § 1.2) serve only the browser's painter. With the run cut to the
  essentials and the equivalence test dropped, moving the painter's tempo-mark and chord-name placement now would risk
  the engraving for nothing the tablet shows. Only `ScoreWidth.forPage` and `ScoreMetrics.fitting` landed.
- **The transport floats side by side only on a roll at least 344 dp wide** (`TransportMinWidth`: the bar's five
  controls with their smallest gaps and 16 dp each side): the plan's 240 dp minimum would squeeze the bar; below 344 dp
  the transport stands solid under the views.
- **No edge grabber where bringing a pane back would make the screen scroll**: on a medium frame 520–780 dp tall with a
  pane hidden, the single view shows without the grabber (the View menu brings the pane back), since the stacked views
  need 780 dp before the short layout takes over.
- **TalkBack's state at the ends** reads "sheet music hidden" and "notes hidden", not a percentage.
- **The 48 dp target overlaps 20 dp of each pane** (as designed): a tap there, such as the last 8 dp of a side-by-side
  system or the Follow chip's right edge, goes to the divider. A pane hidden mid-drag takes the floating transport with
  it until the release. Worth a look on the school tablet.

## Tests added in M31a

`SplitRulesTest` 6 (defaults and minimums, the stops, hiding and coming back, shares kept to the minimums and the tight
case, keys and adjustments, ticks and TalkBack's words). `ScorePageWidthTest` 2 (the thresholds, `fitting` equal to
`forPanel` otherwise, 840 dp as two pages of two bars). `SettingsRepositoryTest` +2 (the Float round trip, clamping and
refusal; the seed from `wideLayout` and the first write). `TransportFloatsTest` +2 (explicit shares stacked and side by
side, the width, a hidden roll). `AdaptiveFrameTest` (the new plan; 0 and 1 the single views; the window-class bars test
removed), `GroupSummariesTest` (Display reads the appearance), `DiagnosticsExporterTest` (47 lines). The existing score
tests are untouched. **1,503 → 1,513** (12 skipped). `lintDebug`: 0 errors, the same 30 warnings as M29. The UI-contract
greps as M29's (`m31a/greps.txt`): no colour added, the divider no glass, the View menu a `GlassPopover`.

# v1.13 — M31b: the Piano tab reorganised, with search

Branch `m31b-settings` from `7c24ff5` (1.11), built as if M30 (Studio's own tab) and M31a (the View menu) were
merged: no Studio row, no NOTES section; then `main` (`bba2d4d`, both landed) merged into it. No firmware setting was
added or removed; nothing about how settings are sent changed. No emulator in this run (lean run): unit tests and lint
only.

## The structure

`HubGroups`: INSTRUMENTS (Instrument · Keyboard) · THE PIANO (Sound and touch · Lights and screen · Pedal · Firmware
and status) · PLAYING (Playback · Tablet sound · Schedule) · SHARING (Web panel · Guests) · THIS TABLET (Display · Kiosk
· Updates · Library and artwork · Help and about). Every hub row is a page (`HubRow.Page` only). `SettingsPage` keeps
its older constant names where a page was renamed (`Feel`, `Lighting`, `Remote`, keys `feel`/`lighting`/`remote`) so
the other runs' code still meets them; new pages `TabletSound`, `Guests`, `Updates`, `Artwork`, `Help`.
`piano/PianoSettings.kt` gains `PianoPage.title`, `PianoFold` (Fine tuning, Strip set-up), `PianoSection.fold`, a
`Save` section and the rows `ReadStatus` and `SaveNow` (ACTIONS is gone); the key-force readings moved to TOUCH. The
web panel's piano groups take `PianoPage.title`; their keys are unchanged.

## What moved

- Feel → **Sound and touch**: PRESETS, LOUDNESS ("Full power", "Piano volume"), **Fine tuning** (TOUCH with the key-force
  readings, TIMING, RELEASE, DRIVE), then "Save to the piano now". Lighting → **Lights and screen**: STRIP, **Strip
  set-up** (LAYOUT · MOTION, Test LED), THE PIANO'S SCREEN. Firmware and status: FIRMWARE, STATUS with Read status.
- Playback's TABLET SOUND → **Tablet sound** page ("Tablet volume"). Remote control → **Web panel** (PANEL: "Web
  panel", address and QR, PIN, "Also on Wi-Fi"; OVER THE INTERNET, was CLOUD: "Web panel over the internet", "Relay
  address", Enrol, Forget) and **Guests** (two switches, the poster).
- Display: APPEARANCE (Appearance, black-and-white artwork) and RESTING SCREEN ("Resting screen after a minute",
  "Background", "What it shows"). "Fetch artwork automatically" → **Library and artwork**, with "Fetch artwork for every
  composer" and Steven's library (both still in the Library's + sheet).
- The hub's APP group and About: Auto-connect → Instrument; "Check for updates automatically", "Check for app updates"
  (was Check now) and the UPDATE block → **Updates**; Share diagnostics and the About lines → **Help and about**.
- Names: "Web panel" (notification, PIN sheet, panel copy), "Piano/Tablet/Channel/Schedule volume", "Resting screen"
  (Kiosk's note, the resting screen's TalkBack label). The panel's place names follow ("Piano › Web panel",
  "Piano › Tablet sound"), and its Save is "Save to the piano now".
- Notes: 48 new one-line notes, all in `ui/SettingNotes.kt`; `PianoSettings.all` takes the piano's (the panel shows them).

## Search

`ui/screens/piano/SettingsIndex.kt` is pure: entries for every page, every row of the piano's table (anchored by
setting or fact name; the key-force note is not a row), every row of `PageRows.kt` (the app pages' row table their
composables draw labels and anchors from), and nine entries for what moved (`Elsewhere`: Now playing › View for Show,
Note display, Fingering, Chord names and Hand colours; Studio for Models, Compose a piece and Transcribe a recording,
opening `Route.Studio`; Library › Channels for Channel volume). Matching: NFKD-folded, accents dropped, words split at punctuation plus each hyphenated word whole;
every typed word must prefix a word of the label or a synonym; label hits before synonym hits, hub order, at most 50.
A result goes through the kiosk gate, then `PianoViewModel.jumpTo` opens its fold and `JumpEffect` (in
`SettingAnchors.kt`) waits for the row's `Anchored` wrapper to be placed, scrolls it 24 dp under the header and lights
it with `surfaceVariant` for 1.1 s (snaps under reduced motion). Hidden with THE PIANO while a MIDI piano plays.

## Deviations

- **The UPDATE block left the hub** for the Updates page (the brief's structure); the hub's Updates row names a release
  on offer ("1.14 available"). In kiosk it is now behind the PIN like every page.
- **The card hides only its main button** beside the Instrument page; its Bluetooth fixes (Turn on Bluetooth, Retry…)
  stay, as the page has none. The card is otherwise as before ("compact" taken as no added content).
- **"Cloud address" is "Relay address"**, the enrol sheet's own word for it (one name per thing).
- Not done (no emulator): screenshots and on-device checks of the search, the disclosures and the highlight.

## The merge with `main` (M30, M31a)

Conflicts in `Routes.kt`, `HubGroups.kt`, `GroupSummaries.kt`, `PianoScreen.kt`, `DisplayPage.kt`, `NavHost.kt`, the
three structure tests and the three documents. `main`'s side there was removals only (Studio's page and row, the
NOTES rows, `wide`, `onListen`), all of which this branch had made too, so the new structure was kept, with `main`'s
five tabs and `Route.Studio`; `NavHost` keeps `main`'s calls plus `onOpenTab`; `GroupSummaries.from` is `main`'s
shape with `update` and `version` added. `PianoViewModel` merged by itself (`main` removed the Studio members and the
five setters; this branch added search, folds and jumps). The documents keep M30's and M31a's sections, then this
one. After the merge the index's Studio results open `Route.Studio`, "Wide layout" gave way to the View menu's
"Show", and a few code comments naming old places (Piano › Studio, Remote control, Playback › TABLET SOUND, Display ›
STANDBY) were brought up to date. `AboutRow` keeps the Studio models' credit on purpose.

## Tests

`SettingsIndexTest` (7: every page and row indexed once, label and synonym hits, folding and prefixes, ordering, what
moved, a MIDI piano, the notes' rules); `PianoPagesTest`, `RoutesTest`, `GroupSummariesTest` rewritten for the pages;
copy tests updated (`InstrumentCopyTest`, `TabletSoundCopyTest`, `CloudCopyTest`, `WebAssetsTest`,
`PianoSettingsTableTest`). 1,503 → 1,509 unit tests before the merge; 1,545 after it (`main`'s 1,537, plus the seven
of `SettingsIndexTest` and one more in `GroupSummariesTest`); lint: no errors, no warning in a touched file.

## The release: 1.12 (versionCode 21)

Cut from `main` at `75367b9`: M30 (the Studio tab), M31a (the split and the View menu) and M31b (the Piano tab
reorganised, with search) together. `versionCode` 21, `versionName` "1.12". From this release the runs are lean
at the owner's request: coders write only the critical tests and do no emulator work, and the integrator makes
one smoke pass on the merged build. That pass (tablet-size emulator, the 1.10 debug build's library upgraded in
place): the database upgraded with the library intact; Studio composed "Calm and slow" end to end (model
download, steps, preview, cover, the shelf) and "Another like it"; Now playing showed the divider dragged to a
third with the score re-laid at two bars, and the View menu; the Piano tab showed the new groups, the folded
Fine tuning, and the search finding the three volumes. No crash in the log.

# v1.13 — M33: motion, smooth and responsive (planned as 1.14)

Fable's design (DESIGN.md › v1.13 — motion), Opus coding, a lean run: the app only, no emulator, two tests. Nothing
about how notes or the pedal reach the piano changed, nor the Keys tab's live playing; the score's page turn, the
roll, the aura, the live red and the sounding yellow are as they were. No new colour; glass only through its wrappers.

## What was built

- **Tokens and the helper** (`ui/theme/Motion.kt`): `QuickMs` 120, `PopMs` 160, `StandardMs` 200 (was 240: the
  mini player, the phone's page push and the disclosures follow it), `EmphasisedMs` 320, `SlowMs` 480 (`timed` holds
  anything longer to it); `FastMs` and `RollStartMs` stay as names for 120 and 320. The `press` spring (0.8, stiffness
  3,000) and the `settle` spring (0.9, 400); `Enter` decelerates, `Leave` accelerates. `timed`, `sprung`, `enter`,
  `exit` and `change` return cuts when motion is reduced; `reducedMotion(resolver)` is the rule outside composition.
  The live dot's 2 s breath moved into `LiveDot.kt` as its own constant (status, not a transition), unchanged.
- **Press** (`ui/components/PressScale.kt`): `Modifier.pressScale(interaction)`, a modifier node following the
  control's own interaction source and drawing it at 0.97 on the press spring (its layer only; nothing is laid out
  again; nothing under reduced motion); `FilledButton` is Material's `Button` with it. On every `GlyphButton` (the
  glass headers' and the transport's glyphs among them), play/pause, Shuffle and Repeat, the floating Play, Send, the
  Keys pills Live, ‹ › and Record, the Library's tiles and channel cards, the chips (`SheetChip`, the Library's
  categories, `ChoiceRow`, the presets) and the filled buttons (Connect, Update, Restart, the firmware's Update and
  Retry, Load, Add, Listen). The play tick (`CLOCK_TICK`) on Send and on starting or stopping a take.
- **Tabs and pages**: `NavHost.FadeThrough` out 120, in 200 from 0.92; on wide frames `PianoScreen` sets the page
  beside the hub in an `AnimatedContent` (in: fade and an 8 dp rise over 200 ms; out: a 120 ms fade).
- **Art**: `PlaybackStarter.artEntrance` (`ArtEntrance`): the Library's rows, tiles and cards ask; the now-playing
  panel's art takes the ask for the next piece it shows within 3 s and grows in from 0.92 over 320 ms.
- **Lists** (`ui/components/ListEntrance.kt`): `ListEntrance` (pure: rows composed within 100 ms of the visit's
  first, index under 10, each key once), `rememberEntrance` with `Modifier.easedIn` (fade and 8 dp rise, 200 ms
  after index × 12 ms), and `Modifier.placement` (`animateItem`, the settle spring, placement only). On the Library's
  piece rows, playlist and composer tile rows and the channels grid, one entrance per listing (a category, a
  playlist, a composer, the channels) and per visit; Studio's turns take the placement too.
- **Play/pause**: the transport's glyph in an `AnimatedContent` (fade and scale from 0.85, 200 ms), the mini player's
  through `GlyphButton(crossfade = true)`; Shuffle and Repeat cross-fade (200 ms).
- **Rolling digits** (`ui/components/RollingText.kt`): each digit its own `AnimatedContent`, 120 ms up or down; a
  plain swap whenever anything but a digit changes; one semantics text. On the tempo (`StepperControl(rolling =
  true)`: Now playing and Playback › Default tempo), the Record pill's time and Studio's figures (a `RollingText` per
  " · " part in a `FlowRow`, so the line still wraps).
- **Progress**: `ProgressHairline` eases a determinate value over 200 ms, from the first value it shows, not from 0.
- **Popovers**: `GlassPopover` fades in over 160 ms growing 0.92 → 1 from the corner beside its anchor (its position
  provider records whether it went above), and leaves in 120 ms.
- **The connection card**: `rememberSearchTurn` and `Modifier.searchArc`: a 90° arc, 1 dp, the secondary grey, 3 dp
  round the dot, a turn every 1.4 s, composed only while looking; none under reduced motion.
- **Studio**: `rememberArrival`: a turn first shown at least 0.6 s into the visit and made in the last 5 s (or shown
  from its job alone) fades in rising 12 dp over 320 ms, remembered under its key and its job's so the job's card
  becoming its history row doesn't arrive again; the card's actions sit in an `AnimatedContent` on `finished` (in
  200, out 120).

## Skipped, and why

- **Material's dropdown menus** (`GlassDropdownMenu`) keep Material's motion, which already grows from the anchor's
  corner with a fade (120 ms in, 75 out): `DropdownMenu` takes no animation spec, and replacing it would mean
  re-implementing its placement. The 160 ms from 0.92 is `GlassPopover`'s alone.
- **The art on phones**: a phone's Now playing shows no art (the title and the notes), so the moment is the
  tablet panel's.
- **Not pressed**: rows (the ripple), the tab bar and the rail (Material's indicator answers already) and the Sustain
  button (the Keys tab's live playing is untouched).
- The resting screen's fades (1.5 s, 0.6 s, 1.2 s, DESIGN.md › v1.7.1) are its own and stay outside the tokens.
- Not done (no emulator in this run): on-device checks of any moment.

## Tests

`MotionTokensTest` (3: no duration over 480 ms and both springs at rest inside it; every helper a cut under reduced
motion; otherwise as asked) and `ListEntranceTest` (5: the first ten rows, 12 ms apart; each once; never a row that
comes later; a visit that opens scrolled down; a new visit). 1,545 → 1,553 unit tests (12 skipped), none failing.
`lintDebug`: 0 errors, the same 30 warnings as 1.12, none in a touched file.

# v1.13 — M32: the web panel's notes and score

Read `DESIGN.md › v1.13 — the panel's notes and score` first. Fable's brief (`m32-web-brief.md`) with the plan's decisions
and §§ 1, 2, 4, 5 (`plans/plan-web-split.md`), built by Opus in the worktree `android-wt-m32` (branch `m32-web`) from
`m31a-split` (`0380a10`), beside other runs. A lean run: critical tests, one browser smoke check, short documents; no
version bump, no provenance signing, nothing in `cloud/` (the relay needs no change).

## Files

`M` = `app/src/main/java/dev/stevenjin/stevenpiano`, `W` = `app/src/main/assets/web`. Added: `M/score/ScoreStyle.kt` (the
score's sizes, glyphs, tempo-mark proportions and caps, moved from `ScorePages.kt` and `NoteCanvas.kt`, which now read
them), `M/score/ScoreMarks.kt` (`ScoreText`, tempo-mark and chord-name placement, moved out of the painter, which calls
it), `M/score/ScoreDisplayList.kt` (the three formats, `WebText`, the browser's `metrics`), `M/web/NowViews.kt`,
`W/clock.js`, `W/wire.js`, `W/roll.js`, `W/score.js`, `W/views.js`. Changed: `ScoreLayout.kt` (`ScoreBars.microsAt`),
`Player.kt` (`positionJumps`), `WebSocketHub.kt` (a progress message at once on a jump), `WebService.kt` (`at` on the
monotonic clock, the jumps), `WebAssets.kt` (the modules, `FONT`, `FONT_VERSION`), `WebPanel.kt` (turning off clears the
layouts); shared with other runs: `WebApi.kt`, `WebBackend.kt`, `AppWebBackend.kt`, `WebServer.kt`, `W/app.js`,
`W/index.html`, `W/style.css`, `M/ui/components/ScorePages.kt`, `NoteCanvas.kt`.

## What was built

- **Routes** (all `Access.READ`, the piece playing only, 404 on the guests' listener): `GET /api/now/notes?rev=`,
  `GET /api/now/score?rev=&w=&h=` (202 `{status:"working", retryAfterMs}` while laid out), `GET
  /api/now/score/{id}/page/{n}` (never starts a layout), `GET /api/font/bravura.otf?v=cdf0f893` (`font/otf`, `private,
  max-age=31536000, immutable`, the `res/font` file's bytes as they are). Answers: `application/octet-stream`,
  `no-store`; 404 `no-piece`, 409 `stale`, 413 `too-large`, 429 with `Retry-After`, 400 for any input not a bounded
  whole number (`rev` and the layout id fit an Int, `w`/`h` 1–8,192, the page up to five digits).
- **`NowViews`**: a revision moves on when the notes, hands, fingering or chords (by reference), the transpose or
  folding change; notes encoded once per revision (≤ 200,000 notes); one low-priority thread lays out one size at a time,
  shared by every request for it; sizes floored to 16 px and held to 280–2,000 × 160–2,000; a request waits ≤ 1.5 s then
  202; a layout past 8 s stops and its size is too large; ≤ 6 fresh layouts per 30 s; ≤ 60,000 notes and 20,000 bars;
  two sizes kept with ≤ 1 MB of pages; a change of piece cancels at the engine's checkpoint and clears it all.
- **State and settings**: the player gains `at` (the tablet's monotonic ms, sampled with the position), `fold` and
  `views {rev, notes, hands, fingers, chords, score}`; the state gains `display {noteDisplay, rollStyle, fingering,
  chordNames, handColours}`; `PUT /api/settings` takes `noteDisplay` (`paperRoll` or `falling` only), `fingering`,
  `chordNames`, `handColours`. The progress message carries `playing` and `tempoPct`, and is sent at once whenever
  the player replaces its position clock (play, pause, seek, stop, tempo, load).
- **The panel**: `views.js` (imported the first time a view shows) mounts the score and the roll; on wide screens both
  with the divider, on phones Art · Notes · Score; the View control; frames only while needed. `clock.js` (offset at
  the least delay over 30 samples; a jump over 120 ms snaps, a smaller one fades over 250 ms). `roll.js` (the app's
  NoteCanvas and KeyboardStrip). `score.js` (pages painted once from the ops, an overlay for the cursor and the
  sounding heads, PageTurn, ‹ ›, Follow, tap to seek, Bravura through `FontFace`).

## The wire formats

Little-endian; every section 4-byte aligned; each opens with a u32 magic and a u16 version (1), then u16 flags.

| Format | Header | Then |
|---|---|---|
| Notes `SPNT` | 32 B: flags (1 hands, 2 fingers, 4 chords cut), rev, n, m, durationMs, nameBytes | u32 startMs[n], u32 endMs[n], u8 key[n] (transposed and folded, 255 unplayable), u8 hand[n]?, u8 finger[n]?, u32 chordStartMs[m], u32 nameEnd[m], UTF-8 names (transposed) |
| Index `SPSI` | 96 B: flags (1 engraved, 2 two pages), rev, layoutId, pages, systemsPerPage, systemCount, pageCount, barsPerSystem; f32 pageWidth, pageHeight, pageGap, slot 1's left, firstSystemTop, space, hairline, cursor width, numberHeight, tempoSpace, the number, chord and numeral font sizes | u32 systemStartMs[systemCount] |
| Page `SPSP` | 40 B: flags (1 truncated), layoutId, page, systems, bars, heads, opWords, strings, stringBytes | systems (10 words: index, first bar, bars, final; f32 left, right, trebleTop, bassBottom, bandTop, bandBottom); bars (20 words: startMs, f32 right, nine (ms, f32 x) cursor points); heads (5 words: note, tiedStartMs or −1, op from, op to, system row; sorted by note); ops; u32 stringEnd[]; UTF-8 strings |

Ops: word 0 is `op | role << 8 | style << 16 | align << 24`; RECT x y w h; GLYPH codepoint x baseline; TEXT string x
baseline maxWidth; QUAD four corners (beams); CURVE x1 y1 cx cy x2 y2 (ties, a hairline); CLIP top bottom … END per
system. Roles: line, glyph, number, note; styles: music, tempo music, number, chord, numeral. A page past 256 KB stops
adding notes and sets its flag; each system keeps the painter's 4,000-a-system caps. Clair de lune at 628 × 320 px:
notes 18 KB, index 168 B, pages 11–21 KB; its layout 67 ms on the emulator.

## Deviations from the brief and the plan, and why

- **The formats are simpler than the plan's**: f32 geometry, not i16 sixteenths; u32 name ends; 20-byte heads (with the
  system row); nine fixed cursor points a bar, not up to nine. Typed arrays read them directly; pages stay small.
- **app.js keeps its own 250 ms clock** for the scrubber and the time (it now honours the progress message's
  `playing`); `clock.js` runs the views. The views' module loads only when a view shows, and the text clock needs no
  smoothing.
- **Text on the score is placed from estimated widths** (`ScoreDisplayList.WebText`, per-character shares of the em,
  erring wide) and drawn within them (`fillText`'s `maxWidth`); the roll's chord names are untracked (canvas
  `letterSpacing` left off), at the eyebrow's size.
- **The page buttons** sit at the score pane's bottom right, over the page, translucent; Follow at its top right.
- **Not added**: a WebSocketHubTest case for the jump (the smoke check's tap-to-seek exercised it); no tick on the
  divider's stops (browsers have no haptics).

## Tests added in M32

`ScoreMarksTest` 1 (the moved placement equals the painter's arithmetic at `0380a10` over three densities, 20+ marks),
`ScoreDisplayListTest` 2 (an engraved page's op order and replayable heads; a performance's hairlines, the per-system
cap, a page truncated under a small cap), `NowWireTest` 3 (`NowWire`, wire.js's Kotlin twin: the three formats round
trip; cut, extended, re-versioned, re-magicked and miscounted bytes refused), `NowViewsTest` 4 (shared layouts and the
grid, 202 then ready, the deadline remembered, six fresh layouts a half minute, a change of piece), `WebServerTest` +1
(binary answers, every bound, the statuses, the font's headers, 401 and the guests' 404), `WebServerRelayTest` +1 (the
bytes and the font through `serveRelayed`), `WebAssetsTest` +2 (the font's pinned hash, size, version and licence
lines, its banner exemption; the modules' bans and the roll's constants pinned to the app's); `WebApiTest` (the state's
new keys, the four settings keys). The existing score tests are untouched. **1,513 → 1,527** (12 skipped). `lintDebug`:
0 errors, the same 30 warnings (an `@SuppressLint("ResourceType")` on reading the font from `res/font`).

## The smoke check

`steven_piano_audit` headless (`emulator-5590`), the debug build, the panel through `adb forward tcp:8738 tcp:8737` and a
scratchpad proxy setting the panel's Host and Origin (as M28 and M29), headless Chrome. Clair de lune on the emulated
piano; screenshots in `scratchpad/m32/shots/`: `m32-1280-now-a` and `-now-b` (3 s apart), `m32-1280-divider`,
`m32-1280-view-menu`, `m32-1280-falling`, `m32-390-notes`, `m32-390-score`, and `m32-1280-sync-browser` beside
`m32-tablet-sync` (the same moment: the same system, bar and sounding heads). Bravura loaded (`document.fonts`), yellow
heads under the cursor, no horizontal scroll at 390 px, no console error but the panel's old `favicon.ico` 404. Not run:
through the real relay, on a phone's browser, in dark mode.

## The merge with `main` (1.12)

`main` at `32a1fa0` (release 1.12: M30's Studio tab and covers, M31b's Piano tab and its wording, M29's instruments)
merged into `m32-web`. Only the documents conflicted (BUILD_SPEC, DESIGN: `main`'s M31b sections, then this one;
README: this run's Now playing paragraph with `main`'s "turning the web panel off"). The code merged cleanly and was
read side by side: `main`'s covers (`art: "cover"`, `artVersion`), Studio job fields, page titles and reworded strings
sit beside the views' routes, `at`, `fold`, `views`, `display` and the four settings keys; `WebApiTest` pins both sides'
keys, `WebAssetsTest` both sides' checks. **1,545 → 1,559** (12 skipped); `lintDebug` 0 errors, the same 30
warnings. Re-checked on `steven_piano_audit` with the merged debug build: `merged-1280-now`, `merged-1280-library`
(artist photos; no Studio cover on that emulator) and `merged-390-now` in `scratchpad/m32/shots/`.

## The release: 1.13 (versionCode 22)

Cut from `main` at `b77fa2b`: M32 (the web panel's notes and score) and M33 (motion) together; the release the
plan called 1.14 is folded into this one. The integrator's smoke pass on the tablet-size emulator: the merged
build installed over 1.12's, tabs switched, a piece started from the Library with the panel's art and roll; the
panel on the loopback answered the state with `views`, `display` and `instruments`, the notes (18 KB), the score
index, the font (889,228 bytes) and the five modules with a session, and 401 without one; the coder's browser
screenshots after its merge with 1.12 show the score and roll at 1280 px and 390 px. No crash in the log.
Designer's change to M33: the arc round the connection dot is removed (the hairline already says "looking").

# v1.13.1 — M36: Studio's stage

Fable's design (DESIGN.md › v1.13.1 — Studio's stage), Opus coding, a lean run: the app only, no emulator, one test
file. Nothing about how notes or the pedal are sent changed; no version bump, no signing. No new colour (the aura's
stops stay in `ui/theme/Aura.kt`); glass only through `GlassSurface` and `GlassSheet`.

## What was built

- **The stage's rules** (`ui/screens/studio/StudioStage.kt`, pure): `StudioStage.card` (the running turn, else the
  next waiting, else the latest turn if finished, made since the app started (its job still known), not cancelled and
  not set aside), `history` (newest first), `eyebrow`; `StageMemory`, the one-minute rule on an injected monotonic
  clock (`left(card, now)`, `returned(now)`; a turn is known by its key and its job's, so a job's card that becomes its
  row is still recognised), and `removed(turn, turns, card)`: a result removed in History is never replaced on the
  stage by an older one.
- **`StudioViewModel`**: `stageMemory`, `stageLeft()`, `stageShown()`, `stageCard(turns, memory, inSight)` (the rule
  applied at composition, so the first frame back is already right), `nothingYet` (the foot line waits for the
  history's first read); `clock` defaults to `SystemClock.elapsedRealtime`.
- **`StudioScreen.kt`**: `StageColumn`, a hand-laid column in the space above the bar or the keyboard (the box's
  middle at 0.46 of it idle and a third with a card, `lift` easing between; the headline 40 dp above the box, the
  suggestions or the card 32 dp under it or 16 dp under the understood line; taller than the space it scrolls, under
  the header's glass); `PromptBar` (64 dp, radius 32, `GlassSurface(blur = false)`: nothing passes beneath it);
  `UnderstoodLine` (its own glass); `StageCard` (1.12's card without the idea's words and Remove); `HistorySheet` and
  `HistoryRow`; `stageInSight` (STARTED and not under the resting screen). The shelf, its pane and `ic_grid` are
  gone; `ic_history` (a clock turned back) is new.
- **`StudioTurns.of`**: a finished job whose written turn has left the history (removed in History) no longer comes
  back as a turn of its own (a 1.12 slip History would have shown).
- **The aura** (`ui/components/Aura.kt`, tokens in `ui/theme/Aura.kt`): `AuraMotion` (the turn and the breath on one
  frame loop per drawing; after working the glow settles in 320 ms); `AuraRing` takes no layout room: its glow's layer
  is laid out at the bar's size and drawn 60 dp beyond it, a stroke half the glow's reach wide, blurred by 0.6 of it,
  at 80 % of the ring's alpha (below API 31, three strokes up to the reach at a fifth each).

## Simplified

- History's actions open under the tapped row (no menu); a waiting or running turn offers Cancel there.
- The glow's reach is its visible fall-off (stroke and blur in proportion), judged by arithmetic, not on a device.
- Not done (lean run): on-device checks; the web panel's Studio page is unchanged.

## Tests

`StudioStageTest` (3: the stage's card, else idle; History newest first with its eyebrows and a removed turn staying
out; the one-minute rule on an injected clock). 1,567 → 1,570 unit tests (12 skipped), none failing. `lintDebug`:
0 errors, the same 30 warnings, none in a touched file.

## The release: 1.13.1 (versionCode 23)

Cut from `main` at `ed51fb8`: Studio's stage (M36) and the Recognisable and Popular channels (`c8b6a6d`). The
integrator's smoke pass on the tablet-size emulator: the stage idle with the aura and its glow, "A bright waltz,
2 minutes" composed with the box in the upper third and the one card under it, and the History sheet listing
three turns. No crash in the log.

## The release: 1.14 (versionCode 24)

Cut from `main` after the merge of `m39-genre-web` (`14d12a8`, `0227e23`) onto `ffe3f58` (run 2) and `f774428`,
`31c9955` (run 1): M37 in three lean runs. 1,582 unit tests green, lint 0 errors. The integrator's smoke pass on the
tablet-size emulator, upgraded in place from the 1.13 debug build with 88 pieces (the Epic set, three Studio pieces, a
recording and four uploaded artist folders sent through the panel beforehand): the upgrade sorted 48 pieces Classical
and 36 Modern with the Debussy upload among the classical ones; the switch in the header in all three positions on
Pieces, Playlists and Composers / Artists; "Search Modern titles and artists" finding A Sky Full of Stars; a piece moved
to Classical and back from the long-press menu; the Modern channel playing (ED SHEERAN · MODERN · CHANNEL); a take
recorded, kept, "Kept in Recordings." under the pills and no import bar; the Recordings tile and page showing covers,
the one made before 1.14 drawn at start; Studio reading "something modern and bright" as "Bright · 2 min · in the
manner of Modern pieces"; the panel's reads with `genre=`, the 400 for a wrong value, and the guests' catalogue with its
Modern list, all through `adb forward`. The guests' page was not opened in a browser (the emulator's Chrome wants its
first-run terms accepted first). No crash in the log.

# v1.14 — M37: Classical and Modern, and the recordings

Fable's design (DESIGN.md › v1.14 — M37), Opus coding from Fable's briefs, in three runs: this one on `main` (the two
fixes and the genre data), then the tablet's Library on `main` beside the web panel in a worktree. A lean run: no
emulator, three new tests, no version bump, no signing. Nothing about how notes are sent changed; `score/` untouched.

## The recordings

- **Quiet saves**: `Importer.importOpened(source, quiet = false)`; a quiet import follows a local `MutableStateFlow`
  (`runOpened` and `run(source, sink = progress)` take the sink), under the same lock, caps and parse.
  `AppStudioLibrary.add` imports quietly: its only callers are Studio's two saves and the recordings' store.
- **Kept**: `RecordingState.Kept` and `RecordingSession.kept()` (as `empty()`, `KEPT_SHOWN_MS` 4 s, then Idle);
  `KeysViewModel.keep` calls it; `recordingNote` gives `InstrumentCopy.KEPT`.
- **Covers**: `RecordingPieces.finish` draws one (`StudioCover` → `Png` → `RecordingStore.setCover`, a default `false`;
  seed the take's epoch ms; a recovered take's notes parsed by `SmfParser`); a failed draw never fails the save.
  `RecordingSession.drawMissingCovers()` runs after `recoverPending` at start: `PieceDao.withoutCover(key)` (no
  picture in the `piece:<id>` row, newest first), notes from `library.load(id)`, seed id × 7,919; unreadable ones wait.
- **Where they show**: `PieceRow` uses `PieceArt` for `ComposerNames.RECORDED_LIVE_KEY` (now public);
  `PlaylistCover` reads `LibraryRepository.playlistHead(id, limit = 4)` (`PieceDao.head`) and each head piece's
  artwork row, so a cover drawn while the tile shows appears; `MosaicTile`'s cells show a piece's own cover before its
  roll card; `ArtworkFetcher.NOT_PEOPLE` has "recorded live". The unused `firstComposerKey` chain is gone.

## The data

- **The column and the rule**: `PieceEntity.genre` (last, `@ColumnInfo(defaultValue = "0")`); `data/Genres.kt`:
  `LibraryScope` and `Genres` (`PACK_COLLECTIONS`, `CLASSICAL_KEYS` (73), `madeHere`, `strong`, `majority`, `of`,
  `name`, `named`, `playlistShows`); `ComposerNames.CANONICAL_KEYS` exposes the 47 canonical surnames.
- **Room 4 → 5**: `MIGRATION_4_5` runs `SchemaV5.ADD_GENRE`, `SORT` (one `CASE`, its lists built from `Genres` and
  `ComposerNames`, quoted with `''`) and `INHERIT`; `PianoDatabase` version 5; `schemas/…/5.json` committed.
- **Imports**: `BatchGenres` per run beside `BatchNames`, from `ImportStore.artistGenres()` (default empty; the
  library's majorities), remembering the keys a strong rule made Classical; `prepare` sorts each new piece after
  `named`, and a duplicate that fills a blank name goes to `fillComposer` sorted again.
- **DAO and repository**: `PieceDao.allIn`, `searchIn`, `favoritesIn`, `recentIn`, `byComposerIn`, `composersIn`,
  `artistGenres`, `setGenre`, `setComposerGenre` (never a made-here piece), `modern`; `PlaylistDao.summaries` adds
  `classicalCount` and `modernCount`. `LibraryRepository.all`, `search`, `favorites`, `recent`, `byComposer`,
  `composers` and `playlists` take `scope: LibraryScope = All`; `setGenre` and `setComposerGenre` (1 or 2 only; a
  made-here piece or key and the blank key refused), `artistGenres()`, `modernPieces(limit)`, `recordingsWithoutCover()`.
- **Channels**: `PoolMatcher.Genre`; `Channel.genre` and `ChannelSummary.genre` (`Genres.NONE` by default);
  `channels.json`'s `genrePool` and `genre`; Classical and Modern first, the nine others tagged classical.
- **Studio**: `StyleVocabulary.channelAliases` (also in `allWords`) and their clause in `StylePrompt.catalogue()`.
- **Tests**: `GenresTest` (3: the rule on the upgrade's cases; every key of a 1.10 library Classical by key but the
  blank one and Anonymous; the playlists' majority), `SchemaV5Test` (3: the statements are 5.json's and nothing else
  changes; a 1.13 database keeps every row and ends sorted as `Genres.of` sorts it; the shape of a fresh v5), and in
  `ImporterTest` a quiet import leaving the shared progress at Idle; `ChannelsTest` and `KeysNoteTest` follow, and
  `LibraryFixture.piece` sets the genre. 1,570 → 1,577 unit tests (12 skipped), none failing. `lintDebug`: 0 errors,
  the same 30 warnings.

## The Library

Run 2, on `main` beside run 3's worktree: `ui/**`, `settings/Settings.kt` and one line of the diagnostics; no data change.

- **The control**: `ui/components/SegmentedControl.kt`, one `Layout` of `selectable(role = RadioButton)` segments (no
  ripple; the label `pressScale`d) under a `drawBehind` track and thumb; segment widths from a `TextMeasurer` in the
  chosen weight, so the width never moves with the choice; a fixed width (a phone's row) is shared equally.
- **Where it sits**: `LibraryScreen`'s header is a `BoxWithConstraints` in the reading width: the switch goes in
  `ScreenHeader`'s actions at `maxWidth >= 560.dp` and `fontScale <= 1.3`, else its own row (16 dp sides); shown when
  the list is (`listed`) and the remembered genre has been read. `Settings.libraryScope` (key `libraryScope`,
  `setLibraryScope`, All by default) holds it; the diagnostics list it after `playlistSort`.
- **What follows it**: `Selection.scope` and `LibraryState.scope`; the selection is null until `rememberedScope.first()`
  (the stored setting, not the settings' defaults) is read; `vm.scope` is the choice at once, for the thumb.
  `selectScope` saves through `saveScope`, closes a group, keeps the chip and the query. `listing` asks the repository's
  scoped lists (a playlist opens whole); `GenreListing.channels` filters the row and See all; `composerPieces` is scoped.
- **The words**: `Category.label(scope)` ("Pieces"; "Artists" under Modern), `searchPlaceholder` (a playlist's search is
  All's), `EmptyListing`, `composerName(composer, scope)`, `ComposerHeader(backLabel)`, the Rename field.
- **Move**: `PieceActions.setGenre` (kept by `forPlaylist`), `PieceMenu`'s item after Rename, `ComposerTile(scope,
  genre, onMove)` with `Listing.Composers.genreByKey` (`GenreListing.byKey`: each key's majority over `library.all()`,
  never the blank or a made-here key); `moveTarget`, `moveLabel`, `movedLine`; `vm.setGenre`, `vm.setComposerGenre`.
- **Kiosk**: Move runs through `gate.run` as Rename does; the switch never does.
- **Accessibility**: the control's `selectableGroup()` with `contentDescription` "Genre"; `LocalReducedTransparency`
  (high contrast text) gives the thumb its 1.5 dp edge; `View.announceMoved` calls `announceForAccessibility`
  (deprecated in API 36 and suppressed there: a move shows no text a live region could carry).
- **Motion**: the thumb on `Motion.settle` (`Motion.sprung`, a cut when reduced), read in the draw; `LibraryItems` keeps
  one `Animatable` alpha in a `graphicsLayer` on every listing item (rows, tiles, the channel row, group headers, the
  empty message), 0 while the listing shown is not the chosen genre's, `PopMs / 2` each way; a snap when reduced. The
  entrance is not keyed on the genre.
- **Tests**: `LibraryStatesTest` +1 (the chips' words, the listing asked for the genre and the state carrying it, the
  channels under each genre, each name's Move), `SettingsRepositoryTest` +1 (All until chosen, then remembered);
  `DiagnosticsExporterTest`'s line count 47 → 48. 1,577 → 1,579 unit tests (12 skipped), none failing. `lintDebug`:
  0 errors, the same 30 warnings.

## The web panel and guests

- **The API**: `genre=classical|modern` on `/api/library` (beside `q`, `category` and paging), `/api/playlists`,
  `/api/composers` and `/api/composers/{key}` (`WebServer.scope`, read with `Genres.named`: absent is every piece,
  anything else 400 "genre must be classical or modern."); `/api/playlists/{id}` stays whole. `WebBackend.library(…,
  scope)`, `playlists(scope)`, `composers(scope)` and `composer(key, scope)` (`LibraryScope.All` by default) reach the
  repository's variants. `WebPiece.genre` and `WebChannel.genre` (`Genres.name`) are written only when there is one, so a
  made-here piece and Everything carry none; the Channels page is not filtered.
- **Guests**: `CatalogueList.genre`; the built-in lists "classical", then `CatalogueList("modern", "Modern", …, "modern")`
  from `AppWebBackend.guestModern`, a `StateFlow` over `library.all(LibraryScope.Modern)` in the app scope (debounced
  500 ms as `ChannelPools` is, mapped on `Dispatchers.Default`, the first `WebLimits.GUEST_MODERN` = 2,000 by title,
  no art). `catalogue()` reads its value, and no longer looks up portraits or covers for the built-in lists either
  (guests never received them). The request gate asks `WebBackend.offered(pieceId)` (the cached Modern list, else the
  piece's playlists against the built-in lists' ids) instead of building the whole catalogue for each POST. A guest's
  piece is still `{id, title, composer}`; a list gains `genre`.
- **The panel** (`index.html`, `app.js`, `style.css`): `.segmented` (`div[role=group][aria-label=Genre]`, three
  `button[aria-pressed]`, built once and marked in place; `--thumb` per appearance: paper's elevated surface, ink's
  content colour at 18 %); `library.genre` read from `localStorage` key `libraryScope`; `scoped()` adds `?genre=` to
  the playlists, composers and composer reads and `libraryPage` to its parameters; `chooseGenre` stores it and
  reloads (the crumb closes). The words: `renderChips`, `SEARCH_WORDS`, `unknownName()`.
- **The guests' page** (`request.html`, `request.js`): `#guest-tools` holds `#genre` (shown when lists of both genres
  came) and `#search` (more than 20 pieces), hidden with the catalogue on Thanks and Closed; `fold()` lower-cases and
  sets accents aside; `renderLists()` filters the loaded rows; `section()` appends 200 rows per Show more.
- **What a guest can reach** (the focused look), once Guests can request is on, the relay included:
  - the catalogue: the titles and artists of every Modern piece, at most 2,000, besides the three built-in lists;
  - capped and cached: worked out off the main thread after the library changes, so a catalogue costs a serialisation;
  - the request gate unchanged: one request every five minutes per guest and per address, approval first when set;
  - no new server-side input: the search runs in the browser; `genre` is the panel's, behind its session, an enum;
  - Recordings and Studio's pieces carry no genre and are on no guest list; a guest's piece is still id, title, artist.
- **Tests**: `WebServerTest` (2: `genre` on the four reads, its 400s and a Modern-only page; the catalogue's genres
  and Modern list, the gate taking a Modern piece and refusing an unlisted one), `WebApiTest` (1: a piece with and
  without `genre`, a channel with and without); `FakeWebBackend` follows the interface (its pieces Classical, its
  Modern list from its Modern pieces). 1,577 → 1,580 unit tests (12 skipped), none failing. `lintDebug`: 0 errors,
  the same 30 warnings, none in `web/` or `assets/web/`.

## The release: 1.15 (versionCode 25)

Cut from `main` after run A (`61d6b1c`, `be41304`, `c665369`), run B (`7dbf563`, `8366324`, `baecf3a`) and the merge of
`m42-sessions` (`011fac0`, `32a1531`), plus the integrator's one fix: `artPalette` lets only colourful bins decide, after
the Afterglow cover (paint over an off-white ground) gave no backdrop. 1,605 unit tests green, lint 0 errors. The
integrator's smoke pass on the tablet-size emulator, upgraded in place from 1.14: covers arriving from Apple one every
3.5 s (Ed Sheeran's first, the classical ones after), every row with its own art; the backdrop behind the tablet's
now-playing pane and Now playing while Afterglow played, drifting between two shots 20 s apart, 0 % janky frames
(modern count; the emulator's own 22 ms frames throughout) over 1,236 frames; "Album colours" in the View menu off
(plain paper) and on; the resting screen with the Appassionata cover's blue glow on the black canvas; the panel's
session valid after the app was reinstalled and restarted (stay signed in). Not seen live: Change cover's picker, the
panel's CSS backdrop in a browser (the built-in browser cannot reach 127.0.0.1). No crash in the log.

# v1.15 — M40: album covers

Fable's design (DESIGN.md › v1.15 — M40), Opus coding, one lean run on `main`: no emulator, two new tests, no version
bump, no signing.

- **The API** (`net/AppleCatalog.kt`): `AppleCatalogApi { search(term, limit): List<CatalogTrack>; download(url,
  maxBytes): ByteArray? }`; `AppleCatalog` over `HttpFetch` as `WikipediaClient` is (the app has no OkHttp): exactly
  `https://itunes.apple.com/search?term=<URLEncoder, spaces "+">&media=music&entity=song&limit=10&country=<the device's
  two letters, else US>`; 404/410 → none; 403/429 → `AppleBusyException`; other non-2xx → `IOException`; JSON capped at
  256 KB (`AppleJson`, `org.json`), images at 6 MB. `CatalogTrack(trackName, artistName, collectionName, artworkUrl100,
  trackViewUrl, kind)`; `coverUrl` is `AppleUrls.cover`: HTTPS on a host ending `.mzstatic.com` (user info, backslash,
  other ports and lookalikes refused), the last segment's `100x100bb` → `600x600bb`. `AppleUrls.pageLink` keeps
  `trackViewUrl` only as HTTPS on `music.apple.com` or `itunes.apple.com`, checked again when opened (`CoverLink`).
- **The match** (`data/art/CoverMatch.kt`, pure): `core`, `words` (runs of letters and digits), `term`, `pick`,
  `trackFits`; "contains" at word boundaries; the 70 % counts title words of 3 or more characters found as substrings of
  the track's core. No artist, a name that is no person's (`ArtworkFetcher.pageName`) or an empty core: no request.
- **The keys**: `ArtKey.Cover(id, title, artist = composerShort, classical)` → `cover:<id>` (`ArtworkEntity.forCover`):
  the lookup's status, `fetchedAt` and credit (`sourceUrl` = `trackViewUrl`, `sourceTitle` = "album · artist", or
  `ArtworkEntity.CHOSEN_HERE`). The picture is the `piece:<id>` row's `imagePath`, merged in by
  `ArtworkRepository.keepCover` under the write lock (the notes, their Wikipedia source and their status kept);
  `setPieceCover` (Studio, recordings) and `setPieceCoverFromUri` ("Change cover", `PhotoImport`) use it too.
  `ArtworkPolicy.notesOf`: a piece row found with no text and no source holds only a cover, and its notes stay due (the
  worker through `recordOf`, `PieceNotesChoice.of`, `StandbyText.asksForOwnNotes`). The worker's writes merge (keep
  `imagePath`; never overwrite a row found while its fetch was under way) under the repository's lock; `forget` drops
  `cover:<id>` with its piece.
- **The pacer**: `PacedAppleCatalog`, `RequestPacer`s of its own, searches 3,500 ms and images 1,000 ms apart.
  `CoverFetcher`: `Skipped` while covers are off or the piece has a picture; `CoverStore.keep` gives `Saved`, or
  `Skipped` when a cover came meanwhile, or NOT_FOUND when the bytes are no image. A 403 or 429 records FAILED and blocks
  an hour (`Fetched.Busy`): the worker drops the background covers only (Wikimedia's long wait now drops only Wikipedia's
  keys); `due()` counts no covers meanwhile. Wikipedia's background keys are queued ahead of covers.
- **Queueing**: `requestDue(force)` (was `requestComposers`): the composers, then, with `fetchArtworkAutomatically &&
  albumCovers`, `PieceDao.coverCandidates()` (genre 1 or 2, no picture on `piece:<id>`, no `cover:<id>` row or a FAILED
  one; Modern first, newest first), never forced. `due()` (was `composersDue`) counts them. `requestCover(id)` (first in
  line) from `DisplayScreen.PieceAtRest`, `NowPlayingScreen` and `NowPlayingPanel`.
- **The switch and the screens**: `Settings.albumCovers` (key `albumCovers`, true; diagnostics after
  `fetchArtworkAutomatically`); `PageRows.ALBUM_COVERS` on Library and artwork (enabled with automatic fetching, checked
  only with both); `ArtworkCopy.ALBUM_COVERS`, `APPLE_CREDIT`, `COVER_CHOSEN`, `cover()`; `AboutRow`; the sheet's
  `CoverCredit`. `PieceRow` uses `PieceArt` for every piece; `PieceActions.changeCover` behind `gate.run`.
- **The hosts**: `en.wikipedia.org` and `upload.wikimedia.org`, and with Album covers on `itunes.apple.com` and
  `*.mzstatic.com` (the updates' GitHub hosts unchanged). What goes to Apple: a piece's title core and its artist's name,
  and a two-letter country; nothing about the person.
- **Tests**: `CoverMatchTest` (6), `AppleCatalogTest` (4); `DiagnosticsExporterTest`'s line count 48 → 49. 1,582 → 1,592
  unit tests (12 skipped), none failing. `lintDebug`: 0 errors, the same 30 warnings.

# v1.15 — M41: the album-colour backdrop

Fable's design (DESIGN.md › v1.15 — M41), Opus coding, one lean run on `main`: no emulator, two new test classes, no
version bump, no signing.

- **The palette** (`data/art/ArtPalette.kt`, pure): `artPalette(pixels, width, height): ArtPalette?`, `data class
  ArtPalette(hues, saturations)`, exactly four. A 4-bit RGB histogram (4,096 bins, each read as its pixels' average);
  pixels under alpha 128 or with HSL lightness outside 0.08–0.92 skipped; score = count × (0.3 + saturation), ties by
  bin; greedy picks at least 25° apart in hue or 0.25 in saturation; saturation × 1.35, at most 1; fewer than four
  padded by the first hue + 30°, − 30°, + 60°; null when the best bin's chroma is under 0.12. `ArtPaletteRules` holds
  the numbers. `ArtworkRepository.palette(row)` reads the `ArtSize.Row` (128 px) decode's pixels on
  `Dispatchers.Default` and keeps the result in a 32-entry `LruCache` by `imagePath@fetchedAt` (a grey picture's none
  too; a picture that can't be read is not kept); `cachedPalette(row)` for the first frame.
  `ui/components/ArtPaletteState.kt` `rememberArtPalette(pieceId, composerKey)`: the piece's own row with a picture,
  else the composer's (the order `PieceArt` draws), through `produceState` keyed by the row's path and version, so a
  new piece keeps the last colours until its own are read.
- **The values** (`ui/theme/Backdrop.kt`, the one place): `Backdrop.PaperLightness` 0.45, `InkLightness` 0.32,
  `PaperVeil` 0.55, `InkVeil` 0.62, `BlackWordsVeil` 0.35, `Radius` 0.6, `PeriodsMs` 20,000 / 22,500 / 24,000 / 26,000,
  `veil(dark)`; `discColour(hue, saturation, dark)`; `hsl()` moved here from `StudioCover` (which imports it, still
  `StrictMath`). The contrast proof needed no veil raised.
- **The node** (`ui/components/ArtBackdrop.kt`): `ArtBackdrop(palette, playing, modifier, veil, resting, fadeMs,
  veilArea)`, a `Spacer` with `clipToBounds()` (its own layer) and one `drawWithCache`: four radial-gradient brushes
  about the origin (colour → nothing in five stops), built once per size and palette; per frame `translate(x, y) {
  drawCircle(brush) }` for each disc on an ellipse about its place (x 0.30/0.70/0.45/0.60 of the width ± 0.25, y
  0.50/0.58/0.66/0.62 of the height ± 0.22), then `drawRect(surface.copy(alpha = veil))`, or over `veilArea` alone
  with 48 dp gradient feathers at its inner edges. The loop is `Aura.kt`'s shape: a `LaunchedEffect` of
  `withFrameNanos` advancing four `mutableFloatStateOf` phases read only in the draw phase, while something is drawn,
  `playing`, not reduced motion, lifecycle ≥ RESUMED and, unless `resting`, `LocalIdleState.current?.idle != true`. A
  new palette cross-fades (`BackdropFade`: the old at 1 − p, the new at p) over `Motion.timed(480)`, or 1,200 ms
  (`RestingMotion.PIECE_MS`) on the resting screen; a cut under reduced motion.
- **The gates** (`rememberBackdrop(pieceId, composerKey, on)`): `albumBackdrop`, not `LocalArtworkMonochrome`, neither
  `increasedContrast` nor `reducedTransparency` (`rememberGlassAccessibility()`), and a palette; non-null is the
  screens' `backdropShown`. `OnBackdrop(shown)` provides `LocalTertiary` and `LocalSecondaryText` as `onSurface` over it
  (one composition either way); `ConnectionLine`, `TabletSoundNote`, `StudioReviewBanner` and the resting screen's
  description read `secondaryText()` so they follow.
- **Where**: `NowPlayingScreen` (a `Box` in `GlassHeaderPane`'s content: the backdrop `matchParentSize()`, then the
  `BoxWithConstraints`), `NowPlayingPanel` (the same round its column), `DisplayScreen` (first in the canvas under Art
  and notes, `playing && resting`; on black `BlackWordsVeil` over `WordsPlace.side`, the words' column reported by
  `onGloballyPositioned` against the canvas's `onPlaced` coordinates: from the column to the edge beside the art, from
  its top down under it). `PieceView` keeps its frame clock.
- **The bars**: `GlassSurface`/`rememberGlassLook` `translucent` paints `surface.copy(alpha = fill.alpha)` in the
  non-blurring glass branch (the solid fallback stays opaque); `GlassHeaderPane(translucent)` passes it to `HeaderBar`
  (its text in the content colour) and keys the header slot on it; `BottomBar` passes it on Now playing.
- **The switch**: `Settings.albumBackdrop` (key `albumBackdrop`, true), `setAlbumBackdrop`, `PianoViewModel`,
  `PageRows.ALBUM_BACKDROP` (`display.backdrop`), `SettingNotes.ALBUM_BACKDROP`, `DisplayPage`, the View menu's
  `MenuSwitch("Album colours")`, diagnostics after `standbyShows`, the web state's `albumBackdrop`.
- **The web panel**: `index.html` `#now-backdrop` (four `div.bd` and `div.bd-veil`, first in `.now-main`); `app.js`
  `backdropFrom(box)` and `artPalette(img)` (the same rule over a 32 × 32 canvas sample), `--bd1..--bd4` through
  `style.setProperty`, `.has-backdrop` and `.playing` classes, `body.no-backdrop`; `style.css` `--bd-l` and `--bd-veil`
  per appearance, `bd-sway` (`translate`) and `bd-rise` (`transform`) keyframes, paused unless playing, `animation:
  none` under reduced motion, hidden under `body.mono`, `body.no-backdrop`, reduced transparency and more contrast.
- **Greps**: `Color(0x` outside `ui/theme`: none. `BlurEffect`: still the aura's glow alone; `Modifier.blur`: none.
  `dev.chrisbanes`: `Glass.kt` alone.
- **Tests**: `ArtPaletteTest` (5: two colours, grey art, spacing, the boost's clamp, determinism),
  `BackdropContrastTest` (3: every hue 0–359 at saturation 1 through each veil ≥ 4.5:1, worst 5.7 paper, 8.6 ink, 5.1
  on black; the values), the setting's round trip; `DiagnosticsExporterTest` 49 → 50 lines, `WebApiTest`'s key set.
  1,592 → 1,601 unit tests (12 skipped), none failing. `lintDebug`: 0 errors, the same 30 warnings.
# v1.15 — M42: sessions remembered

Fable's design (the plan's M42; DESIGN.md › v1.5.1 — M18 › The PIN gate), Opus coding, one lean run in a worktree beside
M41: `web/` only, no emulator, four new tests, no version bump, no signing. Steven chose Stay signed in: a device that
has entered the PIN stays signed in for a year, across restarts of the app; a new device still needs the PIN (the
panel's address is the one on the guests' poster).

- **The table** (`web/WebAuth.kt`): `Sessions(store: SessionStore = SessionStore.NONE, …)`, `MAX_SESSIONS` 16 (was 10),
  `IDLE_MS` 365 days (was one), the prune rules as before (the least recently used goes first; a time more than a year
  ahead, the clock set back, is dropped). It reads the store once when made (stale entries dropped, the rest ordered by
  last use, the newest 16 kept) and saves the whole table on `open`, on `close` of an open session, on `closeAll`, and
  when `isValid` has moved a session's time more than `SAVE_AFTER_MS` (a minute) from what the store holds, so a page's
  requests and the sockets' 4 s checks write at most once a minute per session.
- **The file**: `SessionStore` (`load()`, `save(table)`: a token's digest → last used, epoch ms; `NONE` keeps nothing).
  `FileSessionStore.under(filesDir)` is `files/web/sessions.json`, a JSON object of SHA-256 hex digests to whole
  numbers, written whole through `sessions.json.part` (synced, then renamed over); `WebPanel.sessions` uses it. A write
  that fails deletes the file (everyone enters the PIN again rather than an ended session coming back); a missing file,
  one over 64 KB, or any key not 64 lower-case hex digits or value not a whole number reads as no sessions.
- **The cookie**: `WebCookies.session` adds `Max-Age=31536000` (`SESSION_MAX_AGE_S`, `IDLE_MS` in seconds) after the
  path; `HttpOnly`, `SameSite=Strict` and, over the relay, `Secure` kept; `endSession` unchanged. It is set at sign-in
  and not renewed, so a browser keeps a session at most a year from its PIN; the tablet forgets one a year unused.
  `closeAll` still runs on a new PIN (`WebPanel.setPin`) and when the panel turns off (`WebPanel.turnedOff`, from
  `AppGraph.setWebEnabled(false)` and the service's `stopNow`); a restart of the app or the service ends nothing now.
- **The gate**: "The six digits set on the tablet, in Piano › Web panel. Enter it once: this device stays signed in."
  (`index.html`); nothing else in the panel changed. The relay, its gate and its limits are unchanged.
- **Tests**: `WebAuthTest` (4: a session outlives a restart and one a year unused does not, a use inside the minute is
  not written and one past it is; the seventeenth session takes the least recently used out of the file, and the order
  holds after a restart; logging out and closing all take sessions out of the file; a missing or corrupt file reads as
  none and is written over), its ten-and-a-day test now sixteen and a year; `WebServerTest`'s login sees
  `Max-Age=31536000` and a digest alone in a `FakeSessionStore`, gone at logout; `WebServerRelayTest`'s two cookie
  strings gain `Max-Age`. 1,592 → 1,596 unit tests (12 skipped), none failing. `lintDebug`: 0 errors, the same 30
  warnings, none in `web/`.

## The release: 1.16 (versionCode 26)

Cut from `main` after M44 (`c50aa52`, `6478957`, `6171537`) and the merge of `m43-now-playing-art` (`6114a95`): 1,615
unit tests green, lint 0 errors. The integrator's smoke pass on the tablet-size emulator, upgraded in place from 1.15:
Now playing with the small art beside "Appassionata, 3rd movement" and the backdrop behind it, the piece (fast
repeated chords) playing without a crash; Piano › Playback with Velocity's Full power line, Dynamic range, Quietest
note, Expression and Re-strike time. The shaping itself is unit-tested and was checked by the coder over the 3,454
files of the `midi` folder at fourteen settings (order, length, pedals, onsets within 25 ms, repeat spacing and the
release gap all held). Not heard on the real piano yet: that is Steven's.

# v1.16 — M44: how a piece is played

Fable's design (DESIGN.md › v1.16 — M44), Opus coding, one lean run on `main`: no emulator, three new test classes,
no version bump, no signing. A change to the four settings shapes the next piece; nothing is reshaped mid-piece.

## The pre-pass
- **`player/Performance.kt`**: `Performance.shape(piece, hands: ByteArray?, PerformanceSettings, PianoFacts,
  checkpoint)` returns the piece with its `events` alone shaped (`MidiPiece.withEvents`), or the piece itself when
  nothing changed. `NoteTable.read(events)`: note i is the i-th Note On, ended as `SmfParser.pairNotes` ends it (its
  Note Off, a strike of its channel and key again, else the last event), so `hands` (one per note of `MidiPiece.notes`)
  line up. `events()` writes back each kept note's On at its onset with its velocity and its Off at its end, the
  controllers at their own times, sorted by (time, controller < Off < On, file order); a note ended by a re-strike or
  never ended gets its own Off, an Off that ended nothing goes, and if every note now ends before the last event an Off
  of a key nothing holds marks it, so `durationMicros` and the last event's time hold. Drum-channel notes are untouched.
- **Order**: (a) `Expression.shape` → (b) `Dynamics.shape` → (c) `Repeats.shape` (skipped when
  `PianoFacts.freeRepeats`: a MIDI piano). `checkpoint` between the parts.
- **(a) Expression** (`Expression.kt`; Light, Full doubles): clusters within 30 ms of their first onset; melody (the
  highest right-hand note, or the highest with no hands) +0.08, bass (the lowest left-hand note, or the lowest of two
  or more) −0.03, inner −0.08; phrases of the melody split where the rest before a note exceeds max(a beat, 600 ms);
  three notes or more: × (1 + 0.12 sin πt) and × (1 + 0.03 an octave from the phrase's mean pitch, within ± 0.06, Full
  too); on the grid (`Quantize.onGrid`): a downbeat (`barStartsMicros`) +0.06, a beat (`microsToBeats`) +0.02, else
  −0.02, within 20 ms; identical chords (two pitches or more) in a row: every second −0.04; the product blended by the
  file's velocity deviation s: 1 below 6, 0.3 from 20, linear between. Timing: lead 8 ms (Full 15) from the cluster's
  first onset; a cluster of 3+ within 10 ms rolls bottom to top over 12 ms (25), the top note on its time, in place of
  the lead; a phrase's first note (all but the first phrase) +15 ms (25), at most half its length, its end kept; a
  phrase's last note held 20 % (40 %) longer, to its key's next onset less G and the last event at most. Every onset
  within ± 25 ms of the file's, never before the previous note of its channel and key lets go, never past its own end.
- **(b) Dynamics** (`Dynamics.kt`): m the mean of the shaped velocities; v' = m + (v − m) × 0.7 · 1 · 1.3, then
  max(v', floor), within 1–127.
- **(c) Repeats** (`Repeats.kt`): T = `Performance.restrikeMs` (the setting, else `PianoFacts.repeatMs`, else 100);
  G = max(60, T − 40) ms. Per key (the file's note number, any channel) in onset order: a note is kept if it starts at
  least T − 1 ms (`ROUNDING_MICROS`) after the last kept one, which for a steady run is the first and every
  ⌈T / period⌉-th; a kept note gains 6 per note dropped after it (18 at most, 127 at most) and lasts to the latest end
  among them; then each kept note ends at min(that end, max(next kept onset − G, onset + 30 ms), next kept onset).
- **The router** (`NoteRouter.restrikeMicros`, `RESTRIKE_SLACK_MICROS` 10 ms): Steven Piano thins a strike of an idle
  key sooner than `restrikeMicros − 10 ms` after its last, on a piece's and the screen's path (`strike`) and the
  keyboard's (`externalNoteOn`); a MIDI piano, none. The player sets it to the shorter of the T the loaded piece was shaped with and the settings' T now
  (`Player.restrike`), so a longer T never thins a piece shaped for a shorter one and a shorter one counts at once.
- **The player**: `startCurrent` runs `performed(...)` on `compute` after the hands and the fingering, beside the
  chords, the file's piece kept for `NowPlaying`; the engine loads the shaped one. `setPerformance` and `setPianoFacts`
  (`AppGraph`: the settings' `performance`, and `PianoFacts.read` of `!repeatms` from `pianoSettings.state`).
- **The fact**: `PianoSettings.facts` begins with `Fact("repeatms", "Repeat period", TIMING)` ("110 ms");
  `alsoRead` sends `get !repeatms` after `gap` and `minstrike`, only when the dump reported the fact.

## The settings and the page
- `Settings`: `dynamicRange` (`DynamicRange`, NATURAL), `velocityFloor` (20, `PlaybackLimits.VelocityFloor` 1–60),
  `expression` (`ExpressionLevel`, LIGHT), `restrikeMs` (0 Auto, else `PlaybackLimits.restrikeMs`: tens within
  60–250), keys as named; `PianoSettings.performance`; `PianoViewModel` setters; diagnostics' four lines after
  `skipDrumChannel`.
- `PlaybackPage`: after Velocity (whose row now carries the Full power line, `PlaybackCopy.fullPower`): `ChoiceRow`
  Dynamic range, `StepperRow` Quietest note (in fives: 1, 5 … 60), `ChoiceRow` Expression, `StepperRow` Re-strike time
  (0, then 60–250 in tens, `PlaybackCopy.restrike`); `PageRows.DYNAMIC_RANGE`, `QUIETEST_NOTE`, `EXPRESSION`,
  `RESTRIKE`; four `SettingNotes`; `StepperControl` wraps a value past 120 dp.
- Web: `SETTINGS_KEYS` takes `dynamicRange` (narrow · natural · wide), `velocityFloor` (1–60), `expression` (off ·
  light · full), `restrikeMs` (0, or 60–250 in tens); `SettingsChange`, `AppWebBackend.applySettings`.

## Simplified, and why
- The router allows 10 ms under T: a repeat spaced exactly T apart would otherwise lose every other note to the
  scheduler's few milliseconds; the piano defers so early a strike by its own tick at most.
- "Every n-th" is kept as "at least T after the last kept": the same for a steady run, never closer for an uneven one.
- Repeats group by the file's note number; two notes folded onto one key meet the router's guard instead.
- A rolled chord's top note keeps the beat in place of the melody lead; the breath moves the phrase's first note alone;
  the repeated-chord softening alternates (second, fourth …).
- `hands` is the `ByteArray` `Hands.assign` gives, not an `IntArray`.

## Tests
`RepeatsTest` (4: the release gap and the 30 ms floor; every n-th with the bonus held to 18 and 127; nothing else
changes, a MIDI piano's repeats stay; Auto takes the fact), `ExpressionTest` (4: Off is the identity; a flat file gains
variance and the melody rises above the inner notes; onsets within 25 ms, count, length, order and the pedal kept;
Full at least Light), `DynamicsTest` (2: the arithmetic; a piece's own mean). Updated: `NoteRouterTest`'s and
`PlaybackEngineTest`'s guards are T; `PlayerTest` plays pieces as written; the four keys' round trip;
`DiagnosticsExporterTest` 50 → 54 lines; `WebApiTest`'s four. Checked once, not kept: the pass over all 3,454 files of
`../midi` at fourteen settings (Light and Full; T from 20 to 250 ms; a MIDI piano): the order, the length, the pedal,
the onsets, the spacing and the release gap all hold, 5 ms at most a piece on the Mac.
1,605 → 1,615 unit tests (12 skipped), none failing. `lintDebug`: 0 errors, the same 30 warnings.

# v1.16 — M43: the small art beside Now playing's title

- `NowPlayingScreen`'s `PieceView`: a `Row` of `PieceArt(…, ArtSize.Tile)` at 72 dp (`frame.wide`) or 56 dp, 12 dp, then
  the title (`displayMedium`, 2 lines; 3 where the pane is under 360 dp) over the eyebrow, centred on each other; the
  art's tap opens the piece sheet (`clearAndSetSemantics` before `clickable`: screen readers meet the title alone), and
  `StartingLine` sits under the words. 1,605 unit tests (12 skipped), none failing; `lintDebug`: 0 errors, the same 30
  warnings.

# v1.17 — M45: pictures through the relay

Fable's design (DESIGN.md › v1.17 — M45), Opus coding, one lean run on `main`: no emulator, no version bump, no signing.

## The tablet (`web/`)
- `GET /api/art/piece/{id}` takes `kind` (`WebArtKind`: `cover`, the piece's own cover alone,
  `image(ArtworkEntity.forPiece(id), size)`, else 404, so a roll card never lands in an `<img>`; `roll`, the roll card
  alone; absent, the chain as before, for older pages) and `size` (`WebArtSize`: `row` → `ArtSize.Row`, `tile` →
  `ArtSize.Tile`; absent, tile), each refused with 400 `field` otherwise. `WebBackend.pieceArt(id, kind, size)`.
- Either art route asked for with `v` answers `Cache-Control: private, max-age=31536000, immutable`; without it,
  `private, max-age=3600` as before (`WebServer.image(image, versioned)`). `v` is only looked for, never read.
- `artVersion` for every kind (`WebApi.piece`): a cover's is its `piece:<id>` row's `fetchedAt`, a portrait's the
  `composer:<key>` row's (`ArtworkRepository.portraitComposers()` is now key → `fetchedAt`, as `pieceCovers()` is; the
  backend's `toWeb` and the piece playing take `covers[id] ?: portraits[composerKey]`), a roll card's
  `WebApi.ROLL_ART_VERSION`, 1. `WebComposer` and a channel card's `WebCardComposer` carry theirs (`WebApi.composer`,
  `WebApi.channels`).

## The page (`app.js` › Art, `style.css` › Art)
- `art(box, piece, size, options)`: the monogram at once; once loaded, `.picture` (the `<img>`, or the `.roll` span with
  its mask set through the CSSOM to the same address) is appended over it, one style read, then `.shown` (opacity over
  160 ms) and `.pictured` (the monogram hidden as the fade ends); no transitions under reduced motion. A box already
  showing its key (`kind:id-or-composerKey:version:size`) is left as it is, its letter brought up to date.
- Addresses: cover `ROOT + /api/art/piece/{id}?kind=cover&size={row|tile}&v={artVersion}`; portrait
  `ROOT + /api/art/composer/{key}?size={row|tile}&v={artVersion}`; roll `ROOT + /api/art/piece/{id}?kind=roll&v=1`;
  `row` for the 40 px boxes, `tile` for Now playing's and the channel mosaics'. Id 0 with art "roll": the monogram alone.
- One `IntersectionObserver` (`artSight`, rootMargin 200 px) for every box; a box is unobserved once shown or given up,
  and `artForget`/`artForgetIn` let go of replaced rows (never `disconnect()`). `options.priority` (Now playing) skips it
  and goes to the head of the queue.
- `artPump`: at most 4 loads through the relay (`ROOT !== ''`), else 6; through the relay a token bucket of 40, one
  more a second, which Now playing's draws on without waiting. Skipped: a box gone from the page, out of sight by then,
  or showing something else. A picture already loading takes the box too (`artLoads`, by address): one load each.
- `artFailures` (by address): a failed address waits 4 s, 20 s, then 60 s, asked again only while its box is in sight;
  after the fourth failure the monogram stays. Three failures in a row set `artPausedUntil` 20 s on.
- `renderQueue`: a JSON signature per container (compact; the current item's uid, id, title, composer, composerShort,
  art, artVersion; each row's uid, id, title, composerShort, durationMs, requested, art, artVersion; the total) returns
  early when unchanged; a rebuild moves the old boxes into the new rows by key and forgets the rest.
- `backdropFrom(img)` is Now playing's `onPicture`: the `<img>`, or null for a roll card, a monogram or a failure. The
  composers list (`portraitOf`) and the channel mosaics (each cell an `.art` box, `.mosaic .art` filling its quarter
  without a hairline of its own) use the loader; their `<img loading="lazy">` is gone.

## Covers, a little wider (`data/art/`)
- `CoverFetcher.RESULTS` 25 (was 10).
- `CoverMatch.trackFits(title, track, classical)`: also equal with the spaces removed when that is 4 characters or more;
  for a composer, `1st 2nd 3rd 4th 5th first second third fourth fifth movement mvt mov` are left out of the 70 %.
- `CoverMatch.pick`: the strict pass, then over the same results (a) the first whose `core(collectionName)` is the
  artist's words joined and whose track fits, then (b) the first whose `core(artistName)` holds every artist word and
  whose album core holds, as whole words, every title-core word not among `theme main title song opening ending ost
  soundtrack from the of a an and in to for`, one of them 4 characters or more. `core()` drops a trailing "(feat. …)".
- `SettingsRepository.coverRuleSince(now)` (key `coverRuleSince`, 0 unset, 0 when unreadable): `now` the first time it
  is asked, before this version's first lookup, then kept. `ArtworkPolicy.shouldFetch(…, coverRuleSince)`: a `cover:`
  row NOT_FOUND with `fetchedAt` before it is due. Passed in by `ArtworkWorker` (`requestAll`, `process`; a constructor
  lambda, 0 by default), `ArtworkRepository.due()` and `PieceDao.coverCandidates(since)`, which now takes those rows
  with the never-looked-up and the failed.

## Simplified, and why
- "Three tries in all" is three more after the first failure, at 4, 20 and 60 s; a pause can only delay them.
- A box that scrolled out of sight while queued is skipped, as one gone from the page is: it asks again when it is back.
- Channel cards' composers carry `artVersion` too, so their portraits share the composers list's versioned addresses.
- `PieceDao.coverCandidates` takes `since`: without it the old not-found lookups would reach only the piece playing.
- The second look tries (a) over every result before (b), the looser rule.

## Tests
`WebServerTest` (1: one kind at a time, 400 `field` for a bad `kind` or `size`, the year with `v` and the hour without);
`WebApiTest`'s pinned key sets take `artVersion` (a roll card's 1, a portrait's and a composer's their own);
`CoverMatchTest` (5: "S.T.A.Y.", the Pathétique, "Interstellar", "Skyrim Theme", a "feat." album and artist and the
tribute album refused); `ArtworkPolicyTest` (1: `coverRuleSince`). The loader was checked by hand against a stand-in for
the relay's limits (an 86-piece queue, a state message every 0.6 s): 8 picture requests on opening Now playing, none more
over the state messages, a rebuilt Up next keeping its pictures. 1,615 → 1,622 unit tests (12 skipped), none failing.
`lintDebug`: 0 errors, the same 30 warnings.

## The release: 1.17 (versionCode 27)

Cut from `main` after M45 (`9a8c016`, `b56e197`, `92126d2`, `fc61332`): 1,622 unit tests green, lint 0 errors. The
integrator measured the page through a stand-in for the relay's limits over HTTP/2 (120 requests a minute per address,
8 in flight and 32 waiting), on the tablet-size emulator with an 84-piece queue and a state message every 0.6 s.
1.16: 2,800 picture requests in 8 s, 39 answered, the page's own files refused. This build: 19 requests, all answered,
none refused, 35 requests in all, every row in sight showing its cover. A row's cover is 3,977 bytes where it was
45,580. Not seen on the school tablet's own link yet: that is Steven's.

# v1.18 — M46: the system routes

Fable's design, Opus coding, one lean run in a worktree (`m46-system`) beside the art work on `main`: the data and the
routes only (the System page that shows them is a later run); no emulator, no version bump, no signing. Steven asked the
panel for "diagnostics from the piano, the tablet and the ESP: temps, memory, what process is running and battery".

- **The readers** (`diag/SystemProbe.kt`): `SystemProbe.read(): SystemReading`, every field nullable; `AndroidSystemProbe`
  (thread-safe) guards each read (the API level, `SecurityException`, anything else: null, never a throw). Battery from
  the sticky `ACTION_BATTERY_CHANGED` (no receiver): percent (level/scale), charging (charging or full), plug `ac` `usb`
  `wireless` `dock` `none`, `tempC` (tenths → one decimal), `voltageMv`, health `good` `overheat` `cold` `dead`
  `overVoltage` `unknown`; `firmware/Battery.kt`'s `batteryState()` now calls it (one reader; full on the charger counts
  as charging). Thermal: `currentThermalStatus` (API 29+) as `none light moderate severe critical emergency shutdown`,
  `getThermalHeadroom(10)` (API 30+, NaN → null), CPU and skin °C, the hottest finite sensor of
  `HardwarePropertiesManager` (a device owner's reading: asked only when the app is one, else null). Memory
  (`ActivityManager.MemoryInfo`), storage (`StatFs` of `filesDir`: total, usable). CPU: cores; the tablet's load from
  `getCpuUsages()` between two reads (device owner); the app's `Process.getElapsedCpuTime()` over the wall time between two
  reads, a share of one core (`app.cpuPct`); the first read gives null for both. Network: the default network's
  INTERNET capability, its transport (`wifi`, `ethernet`, `cellular` before `vpn`, else `other`), `signalStrength` (API
  29+), `linkDownstreamBandwidthKbps`. Tablet: "Manufacturer Model", Android's release, `elapsedRealtime`,
  `isInteractive`. App: version and build, pid, `Threads:` of `/proc/self/status`, uptime since
  `getStartElapsedRealtime`, heap used and max, native heap, device owner, kiosk (`lockTaskModeState`: locked now).
- **The day** (`diag/SystemHistory.kt`, pure): a ring of 1,440 `SystemSample(at, batteryPct, batteryTenthsC,
  memAvailPct, thermal, pianoTenthsC)`, `snapshot()` oldest first. `AppGraph.startSystemSamples()` adds one at start and
  every 60 s on `Dispatchers.Default` in the app's scope for the life of the process; nothing on disk. The piano's
  `!temp` goes in only when its facts came in within two minutes (`PIANO_FRESH_MS`): they are read only while the page
  is open (`firmware/docs/BLE_DIAG.md`), so a stale value is never charted as now.
- **What is running** (`diag/RunningNow.kt`, pure): `RunningNow.of(Inputs)` gives twelve `Activity(key, title, state,
  detail, progress)`, always in this order: `player` (Playing, Paused or Stopped · title · composer · "Calm channel";
  position/duration), `link` (Connected to <name> · MTU n, Connecting…, Not connected; a MIDI piano's name; the keyboard
  and its state; Live; Recording), `web` (devices signed in, panels open, guests), `relay` (connected to the host with
  its answered and refused, reconnecting with the wait, off; revoked, removed or key gone as a problem), `covers` (the
  worker's "Fetching artwork 12 of 61 · <label>", else waiting "Apple asked to wait · covers again at 14:32" while its
  hour-long stop is on, else idle), `import`, `studio` (the running job's line and progress, else the queue), `pack`,
  `update`, `firmware` (Sending · 38% · …, bytes of total), `schedule` (the next start's line), `sound` (active, silent,
  off). States `running` `waiting` `idle` `off` `problem`; details in the app's own words (its copy objects), a
  sentence's full stop dropped; progress 0–1, three decimals.
- **The piano** (`piano/`): `PianoSettings.diagFacts`, BLE_DIAG.md's twenty names in the `diag` order.
  `PianoSettingsRepository.refreshFacts()` (beside `readStatus`) sends `get !<name>` for `liveFacts` and `diagFacts`, only
  names among the facts the piano gave (its dump's: 2.0.0 is never asked a fact it lacks), nothing before Ready nor while
  `updating()` (`AppGraph`: the firmware updater `busy`); true when it asked. `factsAt`: when a fact last came in on this
  connection (epoch ms), null once the link drops. New constructor parameters `updating` and `clock`, with defaults.
- **Covers** (`data/`, accessors only; no art logic changed): `PieceDao.coverCounts()`, the pieces that may have a cover
  (genre 1 or 2) grouped by their `cover:<id>` row's status (OK, NOT_FOUND, FAILED, none = never looked up; a piece with a
  cover of its own and no lookup is not counted); `PieceDao.coverRetries()`, `coverCandidates` plus NOT_FOUND;
  `ArtworkRepository.coversBlockedForMs()` (`CoverFetcher.blockedFor`) and `lookAgainForCovers()`
  (`ArtworkWorker.requestAll(…, force = true)` over the retries, album covers on).
- **Counts**: `RelayClient.answered` / `refused` since each connection's hello (an answer from the panel, any status / a
  refusal at the client: busy past the requests in flight, or the server failing); `WebPanel.hub` and `relay`, as the web
  service holds them (`reportHub`, `reportRelay`).
- **Routes** (each with its `sample`; READ needs the session, WRITE the session and `X-Steven-Piano: 1`, as every route):
  - `GET /api/system`: `{at, app{version, build, pid, threads, uptimeMs, heapUsed, heapMax, nativeHeap, cpuPct,
    deviceOwner, kiosk}, tablet{model, android, uptimeMs, screenOn, battery{percent, charging, plug, tempC, voltageMv,
    health}, thermal{status, headroom, cpuC, skinC}, memory{total, available, low}, storage{total, free}, cpu{cores,
    loadPct}, network{online, transport, signalDbm, downKbps}}, piano{link{state, name, mtu}, state, facts{…}, factsAt,
    diag{temp, heap, heapmin, heapblock, tasks, stack, looprate, loopms, rssi, active, trips, crashes, i2cfails, uptime,
    repeatms, reset, ota, fw, boards, pedalboard}}, running[{key, title, state, detail, progress}], web{sessions, sockets,
    guests, relay{state, answered, refused}}, covers{found, missing, failed, waiting, blockedUntil}}`. Every key always
    there, null when unknown. `facts`: every fact as it stands (null before the piano answered); `diag`: `temp` and
    `loopms` decimals, the rest numbers whole, `reset` `ota` `fw` `pedalboard` words, `boards` a list of `ok` / `missing`,
    each null when absent or unreadable. `relay.state`: `connected`, `reconnecting`, `off`, or `stopped` (revoked,
    removed, key gone). `sockets` counts the listeners' sockets and the relay's bridged browsers.
  - `GET /api/system/history`: `{everyMs: 60000, samples: [[at, batteryPct, batteryTempC, memAvailPct, thermal,
    pianoTempC], …]}`, oldest first, the temperatures in °C (one decimal).
  - `GET /api/system/diagnostics`: Share diagnostics' zip, built in memory (`DiagnosticsExporter.exportBytes()`, the same
    entries; the shared file is never touched), `application/zip`, `Content-Disposition: attachment;
    filename="steven-piano-diagnostics.zip"`, `no-store`; 503 when it can't be made. It holds about.txt, settings.txt,
    link.log and the crash reports; of the secrets settings.txt names only `webPinSet` and `kioskPinSet` (and the cloud's
    on, host, enrolled): no PIN, PIN hash, session digest, relay secret, piano id or enrolment code, so no line was taken
    out (`DiagnosticsExporterTest` pins it). `RelayedResponse.HEADERS` gains `Content-Disposition`; the relay's own
    allow-list (`cloud/src/relay/room.ts`, audit delta 3) still drops it, so through the cloud the page names the file
    itself (`<a download>`); the type passes as it is.
  - `POST /api/system/refresh` (the body is not read): `{refreshed: true|false}`; the piano's facts at most once in 10 s
    whichever listener or the relay asks (`RefreshFloor` in `AppWebBackend`, `elapsedRealtime`).
  - `POST /api/system/tool` `{name}`: `covers` (204; queued in the app's scope) or `reconnect` (204;
    `AppGraph.reconnectPiano()`: the player paused and flushed, the link dropped, connected again as `chooseInstrument`
    connects; 409 `busy` while the player is locked or the firmware updater busy); any other name 400 `field`.
- **Tests**: `SystemHistoryTest` (2: fills, wraps, oldest first; a sample's figures and the piano's temperature only when
  fresh), `RunningNowTest` (4: all idle in order; a channel playing; Apple's stop as waiting; a firmware update's
  progress), `WebApiTest` (the system object's keys, nulls, `diag` from facts and null without them),
  `WebServerTest` (each route refused without a session, the two actions without the header; the floor; the tools, a bad
  name 400, busy 409; the zip's type, disposition and `no-store`), `DiagnosticsExporterTest` (no secret in the zip);
  `WebServerTest`'s write count 25 → 27; `FakeWebBackend`'s floor moves its clock 10 s a look unless a test sets it, so
  `WebServerRelayTest`'s every-route check sees the same answer twice. 1,615 → 1,624 unit tests (12 skipped), none
  failing. `lintDebug`: 0 errors, the same 30 warnings, none in the new code.

# v1.18 — M49: Now playing over the cover

Fable's design (DESIGN.md › v1.18 — M49), Opus coding, one lean run in a worktree (`m49-immersive`): no emulator, no
version bump, no signing. *(The picture, the values, the node and Immersive below were taken back by v1.18 — M51; the
layouts stay.)*

## The picture (`data/art/`)
- `BackdropRules.kt` (pure, new): `SIDE` 48, `BLUR_RADIUS` 3, `BLUR_PASSES` 3, `SATURATION` 1.6, `BLOCK` 12,
  `TARGET_LUMINANCE` 0.14, `MIN_DIM` 0.18. `prepare(pixels, w, h)` in place: opaque (laid over black by alpha), three
  separable box-blur passes (running sums, edges clamped, rounded), then the saturation matrix Android's
  `ColorMatrix.setSaturation` builds (weights 0.213 / 0.715 / 0.072), clamped. `dimFor(pixels, w, h)`: the brightest of
  the 12 × 12 block means by WCAG relative luminance L (`brightestBlock`, `luminance`, `StrictMath`), then
  `max(0.18, 1 − (0.14 / L)^(1 / 2.2))`, 0.18 at L ≤ 0.14.
- `ArtworkRepository.backdrop(row): BackdropPicture?`: the `ArtSize.Row` decode's centre square drawn filtered into a
  48 × 48 ARGB bitmap (`createBitmap`, `Canvas.drawBitmap`), its pixels prepared and set back, with `dimFor`; made on
  `Dispatchers.Default`, kept in a 32-entry `LruCache` by `imagePath@fetchedAt` (`pictureKey`); a picture that can't be
  read is not kept. `cachedBackdrop(row)` for the first frame. `class BackdropPicture(bitmap, dim)`. `ArtPalette.kt`,
  `palette()`, `cachedPalette()` and `KnownPalette` are gone.
- `ui/components/ArtBackdropState.kt` (was `ArtPaletteState.kt`): `rememberBackdropPicture(pieceId, composerKey)`, the
  piece's own row with a picture, else the composer's, through `produceState` keyed by path and version.

## The values (`ui/theme/Backdrop.kt`, the one place)
`Backdrop.Scales` 1.05 / 1.5 / 0.7 of the node's diagonal, `Alphas` 1 / 0.6 / 0.38, `PeriodsMs` 60,000 / 84,000 /
48,000, `Directions` 1 / −1 / 1, `StartDegrees` 8 / 150 / −30, `OffsetsX` 0 / 0.12 / 0.27 and `OffsetsY` 0 / −0.06 /
−0.24 of the node, `TopLighter` 0.06, `FootDeeper` 0.10, `RestingDeeper` 0.10, `Shade` (black), `Words`
(`SilverPrimary`), `Glass` (black at `GlassAlpha` 0.26), `GlassEdge` (words at 0.14), `Pill` (0.18), `Hairline` (0.22),
`RightHandAlpha` 0.92, `LeftHandAlpha` 0.5, `Landing` (0.75), `KeyWhite` `SilverPrimary`, `KeyBlack` `CarbonPrimary`,
`KeyLine` `SilverTertiary`, `KeySounding` `NoteSoundingDark`, `KeyHands` (the paper's), `LiveRing` 1.5 dp at 0.9.
`ImmersiveScheme` = `DarkScheme` with `onSurfaceVariant`, `secondary` and `tertiary` the words. `discColour`, the
lightness and veil constants and `BlackWordsVeil` are gone; `hsl()` stays for Studio's covers. `ui/theme/Type.kt`:
`NowPlayingTitle` (40 sp bold, 44, −1), `NowPlayingStripTitle` (30 sp, 34, −0.6), `NowPlayingComposer` (19 sp semibold).

## The node (`ui/components/ArtBackdrop.kt`)
`ArtBackdrop(picture, playing, modifier, resting, fadeMs, deeper)`: a `Spacer` with `clipToBounds()` and one
`drawWithCache`. Per size and picture, `BackdropLayers`: the `ImageBitmap`, each layer's integer square and centre,
the black `Brush.verticalGradient` from `dim + deeper − 0.06` to `dim + deeper + 0.10`. Per frame: for each layer
`rotate(degrees, centre) { drawImage(…, alpha, filterQuality = FilterQuality.High) }`, then `drawRect(shade)`; no
allocation. The fade (`BackdropFade`, as M41's): the new picture whole over the old at p through one `saveLayer` with a
cached `Paint` (only while it fades), or alone at p / 1 − p; `Motion.timed(480)` or `RestingMotion.PIECE_MS`, a cut
under reduced motion. The loop (`BackdropMotion`, three `mutableFloatStateOf` turns read only in the draw phase) runs
as M41's did. `rememberBackdrop` keeps M41's gates and returns the picture; `OnBackdrop` and `backdropDark` are gone.

## Immersive (`ui/components/Immersive.kt`, new)
- `LocalImmersive`; `Immersive(shown)` provides, one composition either way, `ImmersiveScheme` through `MaterialTheme`,
  `LocalContentColor`, `LocalTertiary` and `LocalSecondaryText` as the words, `LocalHairline` `Backdrop.Hairline`, the
  ink's live red, sounding yellow, specular edge, disabled glyph, hand tones and aura, and remembers the appearance it
  replaced (`AppAppearanceTokens`, a data class). `AppAppearance` puts that appearance back (a no-op outside): the
  score's sheet (`ScoreSheet`), `GlassSheet`, `GlassDropdownMenu`, `GlassAlertDialog` and `GlassPopover`'s content.
- `GlassSurface(immersive = LocalImmersive.current)` replaces `translucent`: `background(Backdrop.Glass)`, the edge in
  `Backdrop.GlassEdge`, never blurring, on any API. `GlassHeaderPane(immersive)`: its edge always shown, its text the
  content colour. `NavHost`: `BottomBar` and `TabRail` (now given `albumBackdrop`, reading the player's piece) wrap their
  glass in `Immersive(Now playing && rememberBackdrop(…) != null)`; `TabTones.pill` is `Backdrop.Pill` there.
- `NoteCanvas` (immersive): no black-key lanes, ramps from the words at 0.92 / 0.5 (`leftRamp` filled, `Roll.outlineLeft`
  false) to the sounding yellow, or the hand tones as before; one 2 dp `Landing` line at the tracker bar or the foot.
  `KeyboardStrip`: the `Key*` colours, every sounding key filled yellow without Hand colours. `RollStrip` and Now
  playing's roll drop the hairline before the keys. `LiveDot`: its canvas 2 × 1.5 dp larger, the ring under the dot.
  `RollPanel`: the card's clip and fill only when not immersive.

## The screens
- `NowPlayingScreen`: a `Box` with `ArtBackdrop(matchParentSize)` under `Immersive { GlassHeaderPane(padding(sides)) }`.
  `scrolls` = a piece and (short as before, or a compact frame); over the backdrop the scroll is padded below the header
  and clipped. `PieceView` picks `InTheScroll` (cover ≤ 360 dp and 0.8 of the viewport, `NowPlayingStripTitle`, then
  the views at `scrollHeight`: the viewport less 64 dp, or the fixed heights stacked), `ArtOnly` (≤ 520 dp and 0.56 of
  the height, centred), `BesideTheRoll` (`COLUMN` 400 dp, or half the width less 28 dp, then the views) or
  `UnderTheStrip` (cover 84 dp with 14 dp corners, `ArtSize.Tile`; the transport 400 dp at the end from 720 dp wide,
  else under the words; no scrubber). `CoverStack` lays the cover, the words and the controls one width: `coverSide`
  takes the height less 22 + 4 + 2 + 14 dp, the composer's, STARTING's and the title's lines and `TransportHeight`,
  two title lines unless `rememberTextMeasurer` finds one fits. `Cover`: `PieceArt` in a `MaterialTheme` whose
  `shapes.medium` is the cover's (24 dp), `shadow(24 dp)`, the tap as M43's. `FootRow`: `FlowRow` of `Capsule`s
  (`GlassSurface(CircleShape, Outline, blur = false)`): Tempo's eyebrow and stepper with the speaker, and
  `ConnectionLine`. `NoteViews` lost its floating controls (`GlassTransportPanel` is the panel's alone);
  `TransportControls(scrubber, inset)`.
- `NowPlayingPanel`: `Box(modifier) { ArtBackdrop; Immersive { GlassHeaderPane } }`; the strip's controls float only
  when not immersive.
- `DisplayScreen`: Art and notes over `ArtBackdrop(deeper = Backdrop.RestingDeeper)` in `Immersive`; `WordsPlace`,
  `veilArea` and the black words veil are gone.
- The View menu: `ViewShow.ART` ("Art only"), `ViewShow.of(plan)`; Score and notes from Art only keeps a split already
  between. `PianoSettings.notesArtOnly` (key `notesArtOnly`, false), `setNotesArtOnly`; `setNotesSplit` clears it.
  `NotesPlan.artOnly`, `AppFrame.notesPlan(…, artOnly)` (wide frames only).

## Greps
`Color(0x` outside `ui/theme`: none. `BlurEffect`: still the aura's glow alone (`Aura.kt`); `Modifier.blur`: none.
`dev.chrisbanes`: `Glass.kt` alone.

## Simplified, and why
- The covering size is the node's diagonal (the square that covers it at any turn); the mock's was 1.5 × its longer side.
- The cover is as large as the height leaves it beside the roll and under Art only, and the words as wide as it, so the
  column stays one width on a tablet whose bars leave about 600 dp.
- Phones and short screens share one scroll layout; Art only is offered on wide frames, as Show is.
- The panel's controls stand under its strip over the backdrop: black glass without a blur would show the notes
  passing under the glyphs.
- With Hand colours on, a sounding note and key keep the hands' colours; without them every sounding key fills yellow
  (a yellow outline would not read on a light key).
- The speaker's popover keeps its ends aligned, as `GlassPopoverTest` pins.
- `notesArtOnly` is not among the diagnostics' lines (`diag/` belongs to another run).

## Tests
`BackdropRulesTest` (3: white, pure yellow, mid grey, saturated red, dark and half white, half black, prepared: the
ink's primary over the dimmed brightest block ≥ 4.5:1 and deeper at the foot; the dimming ≥ 0.18, 0.591 for white;
deterministic, the edge softened, a pure red kept). `ArtPaletteTest` and `BackdropContrastTest` removed;
`AdaptiveFrameTest` takes Art only. 1,622 → 1,617 unit tests (12 skipped), none failing. `lintDebug`: 0 errors, the
same 30 warnings.

# v1.18 — M51: the effect before

Fable's design (DESIGN.md › v1.18 — M49, its first three lines), Opus coding, one lean run in a worktree
(`m51-effect-before`): no emulator, no version bump, no signing. M49's backdrop made of the cover was tried; Steven saw
it on the tablet-size emulator (2026-10-03) and chose the effect before, keeping the big cover and the layouts.
v1.15 — M41's rules stand again; only the tablet's Compose code and `data/art/` changed.

- **Put back, as at `fc61332`**: `data/art/ArtPalette.kt` and `ArtPaletteTest`; `ArtworkRepository.palette()`,
  `cachedPalette()` and their cache (the file is `0b38748`'s, M46's `coversBlockedForMs` and `lookAgainForCovers` kept);
  `ArtBackdrop.kt` (`rememberBackdrop`, `OnBackdrop`, `backdropDark`, the discs, the veil, `veilArea`) and
  `ArtPaletteState.kt`; `ui/theme/Backdrop.kt` and `BackdropContrastTest`; `GlassSurface` and `GlassHeaderPane`
  `translucent`; `KeyboardStrip`, `NoteCanvas`, `RollStrip`, `LiveDot`, `ConnectionLine`, `TabletSoundControls`,
  `GlassMenu` and `GlassSheet` as 1.17; `MainActivity`'s bar icons as the appearance has them; `NowPlayingPanel` and
  `DisplayScreen` whole.
- **Removed**: `BackdropRules.kt` and `BackdropRulesTest`, `BackdropPicture`, `backdrop()` and its cache,
  `ArtBackdropState.kt`, `Immersive.kt` (`LocalImmersive`, `ImmersiveBars`, `AppAppearance`), `ImmersiveScheme`,
  `ScoreSheet`, `RollPanel`, `TabTones.pill` and every immersive branch.
- **Kept from M49**: `NowPlayingScreen`'s layouts and sizes (`BesideTheRoll`, `UnderTheStrip`, `ArtOnly`, `InTheScroll`,
  `CoverStack`, `Cover`), `Type.kt`'s three styles, `TransportControls(scrubber, inset)`, Art only (`ViewShow.ART`,
  `notesArtOnly`, `NotesPlan.artOnly`), and the backdrop at the screen's root, edge to edge. On M41's look now: the
  pane's body in `OnBackdrop`, the header `translucent`, the roll and the score on `Panel`, the foot's
  `Capsule(translucent)` (`PieceLayout.colours`), and `TabRail` (given `albumBackdrop` and reading the player's piece,
  as M49 had it) `translucent` while Now playing shows the colours.
- **Simplified, and why**: a screen that scrolls (phones, short screens) passes beneath the header again, which blurs it
  as M41's did; the backdrop lies outside the pane now (so it reaches under the rail), so that blur holds the content,
  not the colours' faint tint. The tablet's wide layouts scroll only where the screen is short (a large font).
- **Greps**: `Immersive|BackdropRules|BackdropPicture` in the Kotlin: none (the web panel's own `updateImmersive` is
  M47's, not this run's). `Color(0x` outside `ui/theme`: none. `BlurEffect`: the aura's alone.
- **Tests**: `ArtPaletteTest` (5) and `BackdropContrastTest` (3) back, `BackdropRulesTest` (4) gone. 1,628 → 1,632 unit
  tests (12 skipped), none failing. `lintDebug`: 0 errors, the same 30 warnings.

# v1.18 — M47: the panel's new look

Fable's design (DESIGN.md › v1.18 — M47, the mocks `panel.css`/`panel.html` and `now2.html`), Opus coding, two lean runs
in a worktree (the second after Steven's choice of the effect before and the integrator's look at the real panel): no
emulator, no version bump, no signing. Settings and System (M47b) are written apart, as modules.

## Tokens (`ui/theme/Color.kt`, `style.css`)
- `AttentionInk` #E6A23C and `AttentionPaper` #9A5B00; `--ink-attention`/`--paper-attention`, `--attention` in each
  appearance block (`WebAssetsTest`'s parity list). Every other palette token, the glass's (72 %, 86 %, 24 px), the
  scroll-edge bands and the specular lines as before; `--radius-card` 18 px, `--radius-control` 10 px, `--inset` 20 px.
- `--bd-l`/`--bd-veil` as v1.15 had them: 45 % / 55 % on paper, 32 % / 62 % on ink (the two dark blocks).
- Dark by default: `<html lang="en" data-theme="dark">`, `<meta name="color-scheme" content="dark light">`;
  `appearance()` gives `dark` with nothing stored, `light` or `system` when chosen (`system` removes the attribute).
  The guests' page and the poster keep theirs; `.guest-page` restores 1.17's insets, corners, field, buttons, genre
  switch, section heads and note, so the shared classes' new shapes never reach it.

## The frame (`index.html`, `style.css` › The frame, `app.js` › Sections)
- `<nav class="sections">` holds `.rail-card.glass`: `.identity`, `.section-list` of four `.nav-group` (`role="group"`,
  an `.eyebrow` heading) of `.nav-item` buttons (`data-section`, a `g-…` stroke glyph, a label), `#rail-vitals`
  (`.rail-foot`, hidden while empty). From 900 px the panel is a 236 px grid column for it (sticky, 100vh, 12 px
  padding); below 900 px the nav is today's sticky strip (`.sections::after`, `.panel.scrolled` as before), the groups
  in one scrolling row of 36 px pills without headings or glyphs; below 600 px `.sections` is static (the name and the
  connection) and `.tab-bar.glass` is fixed at the foot (4 × `.tab-item`, 56 px + the safe area), More opening
  `<dialog class="sheet more-sheet">` with `showModal()` (closed by a choice, Escape, the scrim, or crossing 600 px).
- `SECTIONS` adds `system`. `show()` sets `aria-current="page"` on every `[data-section]` (rail, bar, sheet) and on More
  for the sheet's sections; `queue` from 1100 px (`besideQuery`) and `system` without its module fall back to `now`; a
  change of section hides the other module page and scrolls to the top. `.nav-item.up-next-item` is hidden from
  1100 px. Requests' count is every `[data-count="requests"]`.
- The sprite adds the mock's `g-…` symbols (paths with `class="glyph-stroke"`: no fill, a 1.7 stroke, round caps and
  joins; filled parts without it); `.glyph` is 20 px, 24 px in icon buttons.
- Shared classes (the mock's names; the modules use them): `.card` `.card-head` `.capsule` `.glass` `.outlined`
  `.filled` `.segmented` `.switch` `.range` (native: a 4 px track filled to `--fill`, a 20 px thumb) `.stepper`
  `.setting` (`.stacked`, `.with-value`) `.tag` (`.attention`) `.dot` (`.on`, `.live`, `.attention`) `.eyebrow` `.meta`
  `.nav-item` (`[aria-current="page"]` or `[aria-selected="true"]`) `.stack` `.field` (a capsule; a holder of a glyph and
  an `<input>`, or the input), `.vital`. Controls are 36 px at least, 44 px under `(pointer: coarse)`.

## Now playing (`index.html`, `style.css` › Now playing, `app.js` › Now playing)
- One markup for both layouts: `.now-hero` (`#now-art`, `.now-words`, `.transport`), `#now-views`, `.quick` (the
  tempo capsule, `#channel-volume`/`#tablet-volume` capsules, `#now-switch` `.segmented.glass`, `#now-view`),
  `.now-lines`, and the volumes' `.popover.glass` (positioned through the CSSOM over the capsule, closed by the capsule,
  Escape, a tap outside, a scroll or a section change). `#section-now.views-on` (the switch on Notes or Score) gives the
  strip; `.unloaded` (no piece, none loading) the centred line.
- Art: `.now-main` is one column `min(max(240px, min(54vh, 100vh − 444px)), 560px, 100%)`, centred both ways: 444 px
  is the page's padding and head (90) with the words, the controls and the instrument's lines under the cover, a title
  on two lines or the capsules on two rows (at 1440 × 900 the cover is 456 px, the lines end at 852). `.now-hero` is
  one `minmax(0, 1fr)` column; the transport's icon buttons are `flex: 0 1 52px` down to 40 px, the play circle fixed.
  From 900 px `.now` is a grid `auto 1fr` of `min-height: calc(100vh − 28px)` with a −26 px bottom margin (the page's
  foot is 14 px there), the views' row `minmax(320px, 1fr)` and `.views-panes` under `contain: size` (the canvases'
  pixel sizes never feed back into the row). From 1100 px `.now-body` is `1fr 380px`: `.now-side.glass`, at most
  `calc(100vh − 90px)`, scrolling inside.
- The strip (`.now.views-on`): `.now-hero` a wrapping flex row (gap 12 px × 20 px): the 132 px cover, `.now-words`
  `flex: 1 1 280px`, `.transport` `flex: none` with 44 px buttons, a 60 px play circle and 4 px gaps (252 px), pushed
  right; where the words would have less than 280 px it wraps to its own line. From 1100 px the title is one line,
  `white-space: nowrap; text-overflow: ellipsis`. At 1440 × 900 the words get 332 px (the scrubber 232 px, was about
  160) and the views 566 px between the strip and the capsules; at 1280 × 720 the transport wraps and the words get 444.
- `art(#now-art, piece, 'full', {priority, onPicture: backdropFrom})`. `nowView` (`steven-piano-now-view`) drives the
  switch at every width; `views.js` loads when it is not Art.
- Up next rows: the cover, the title (two lines) with Requested, the composer, `.time` at the right, `.row-tools` (up,
  down, remove); `@media (hover: hover) and (pointer: fine)` shows the tools in the time's place on hover or focus
  (width 0 otherwise, so the keyboard still reaches them); elsewhere the time moves into the meta line (`.meta-time`).
  The playing row: `.current`, `.bars` (three still bars).

## The colours behind Now playing (`index.html` `#now-backdrop`, `style.css` › Now playing's album colours, `app.js`)
- v1.15 — M41's backdrop as `main` had it before M47 (Steven's choice on 2026-10-03, after the blurred cover was tried
  in the first run): `#now-backdrop` holds `.bd1` … `.bd4` and `.bd-veil`; each disc 120vmin round,
  `radial-gradient(closest-side, hsl(var(--bd) var(--bd-l)) …)` to nothing, `bd-sway`/`bd-rise` at its own pace
  (10/7 s, 11.25/7.9 s, 12/8.4 s, 13/9.1 s), `animation-play-state` running only with `.playing`; `.bd-veil` the
  surface at `--bd-veil`. New: `.backdrop` is `position: fixed; inset: 0; z-index: -1`, the first child of `.panel`
  (`isolation: isolate`), so it fills the window behind the rail, the page and Up next; the discs sit at the mock's
  places (22 %/34 %, 74 %/64 %, 48 %/88 %, 92 %/8 %). `body.mono`, `body.no-backdrop`, reduced transparency and more
  contrast hide it; reduced motion stills it.
- `backdropFrom(img)` (Now playing's `onPicture`) and `artPalette(img)` are v1.15's, unchanged (`artPalette` the same
  bytes); the palette goes in as `--bd1` … `--bd4` and `updateBackdrop()` (each state, through `renderNow()`, so each
  section change too) shows it while the section is `now`, a piece is loaded and its art has colours, sets `.playing`
  while it plays and `.has-backdrop` on `.panel`.
- The words on it (`body:not(.mono):not(.no-backdrop) .panel.has-backdrop :is(.now-words, .transport, .now-lines,
  .views-note)`, and below 900 px `.sections`) take `--secondary` and `--tertiary` from `--primary`, v1.15's rule. The
  glass is the standard glass (`--glass-bar`, `--glass-sheet`, `--glass-blur`): the rail card, Up next, the capsules,
  the phone's bar; the roll and the score are on their cards (`.pane`'s elevated surface).
- `views.js` keeps `host.view()` (Art · Notes · Score at every width) and `panes.dataset.mode` (`notes`, `score` below
  900 px, `split` for Score from 900 px, the divider only in `split`); the roll and the score look as on `main` before
  M47. `roll.js` is `main`'s but for `rgb()`, which also reads `rgb(r g b)` (the hand ramps' mixed end colour fell back
  to grey before).

## The Library (`index.html`, `app.js` › Library, `style.css` › Library)
- `.lib-tools`: `#lib-genre`, `label.field.search-field` (the `g-search` glyph, `#lib-search`), `#lib-view` (Covers ·
  List, `steven-piano-library-view`; with nothing stored, covers from 900 px); `#lib-chips` under it.
- `renderPieces` sets `#lib-rows` to `cover-grid` or `rows card`; `pieceTile`: `li.cover-tile` > `button.tile-play`
  (`span.art` at `tile`, the title, the composer; a click plays the list) and `button.icon-button.tile-more.glass`
  (the menu), shown on hover or focus, always under `(pointer: coarse)`. Playlists and composers call `rowsOnly()`.
- Every list goes through `libraryAsk(load, draw, list)`: a ticket each (`libraryAsked`); the answer is drawn whenever
  it arrives unless a newer ask has taken a ticket since (a page more, a playlist or a composer opened included), and
  then `library.loaded` holds. Before, `libraryLoad()` set `loaded` ahead of the answer, so a first list refused (429 or
  503 busy through the relay, offline, the gate) left the tools over an empty page until a chip or the search was
  touched. Now a list (`list`: the first page, a search, the playlists, the composers) that can't be read clears
  `loaded` and notes `failedAt`; `show('library')` asks again, and so does each state while the Library shows, at most
  every `LIBRARY_RETRY_MS` (5 s). A page more, a playlist or a composer that fails leaves what shows.

## The seam with M47b (`app.js` › The Settings and System pages' modules)
- `index.html` links `system.css` after `style.css` (an empty file here, its banner only; `WebAssets.PANEL` serves it);
  `#piano-tools`, `#piano-body`, `#section-system` with `#system-tools` and `#system-body`, `#rail-vitals`.
- `host = Object.freeze({ ROOT, RELAYED, h, fill, glyph, chip, get, put, post, toast, failed, state, clock, plural,
  signed, debounce, art, show, appearance: { get, set }, confirm })`. `confirm({title, message, action, run})`: a
  `<dialog class="confirm">` with `showModal()`, Cancel focused, the action `.outlined.filled`, removed on close; `run`
  goes through a promise so its failure becomes a toast.
- `loadModule(key)` imports `./settings.js` or `./system.js` once (the result cached; a rejection or a module without
  `create` marks it failed). Pages are made by `create(host, body, tools)` on first show, then `show()`/`hide()`
  paired on section changes and `render(state)` on each state while shown; every call is guarded. `startSystem()` after
  the first state: the System items appear and `vitals(host, #rail-vitals)` is called once. Without `settings.js` the
  built-in page (`pianoLoad`/`renderPiano`, wrapped in `.content.builtin-settings`, with `appearanceCard()`) shows.

## The relay's allowance and the full size
- `call()` reads `X-Relay-Art-Limit` on every answer; `artAllowance(limit)` switches `artLimits` between M45's numbers
  (4 at once, 40, 1 a second) and, through the relay from 300, `{parallel: 6, budget: 120, refillMs: 1000 / 6}`, the
  bucket filled at once; on the tablet's own address nothing changes.
- `WebArtSize.FULL("full")` → `ArtSize.Full` (1024 px) in both art routes (`AppWebBackend.artSize`); the routes' 400
  says "row, tile or full".

## Tests
`WebAssetsTest`: the attention pair in the parity list; Settings and System among the section names; a new test (dark
by default, `system.css` linked after `style.css` and served, the seam's ids, the two modules imported by `app.js` and
never by the page, the frozen host, the guests' page keeping its own appearance). `WebServerTest`: `size=full` answered.
1,631 → 1,632 unit tests (12 skipped), none failing, after the second run too (run with `--rerun`: the web files are not
the test task's inputs, so a change to them alone comes back from the build cache untested). `lintDebug`: 0 errors, the
same 30 warnings, none in this run's files.

# v1.18 — M47b: Settings and System on the panel

Fable's design (DESIGN.md › v1.18 — M47b), Opus coding, one lean run in a worktree (`m47b-settings-system`) beside the
frame's (M47, `m47a-panel-look`): no emulator, no version bump, no signing, no deploy.

## The modules' contract (the frame's side is M47's)
- `W/settings.js` and `W/system.js` are ES modules exporting `create(host, body, tools)` → `{ show(), hide(),
  render(state) }`; `system.js` also `vitals(host, node)` (once, the rail's foot) and `attention(sys, state)` (pure).
  `host` as the frame freezes it: `ROOT, RELAYED, h, fill, glyph, chip, get, put, post, toast, failed, state(), clock,
  plural, signed, debounce, art, show, appearance{get, set}, confirm`. The modules ask the tablet only through `host`,
  every path `host.ROOT + '/api/…'`; build every element with DOM calls; set sizes through the CSSOM (`--fill`, an arc's
  `stroke-dasharray`) or SVG attributes; keep their state in their own maps and put no `data-` attribute in the page
  (the frame hides every `[data-page]` that isn't its section). Glyphs are the frame's sprite's (`g-…`, `i-back`, `i-check`).
- `W/system.css` holds the pages' own classes under `.settings-page` and `.system-page` (the mock's names: `.split`,
  `.pages`, `.presets`, `.preset`, `.notice`, `.tools`, `.sys-grid`, `.sys-lower`, `.dials`, `.dial`, `.level`, `.meter`,
  `.facts`, `.fact`, `.task`, `.bar`, `.chart`, `.legend`), and `.vital.attention`; tokens only; the shared classes are
  `style.css`'s. `WebAssets.PANEL` serves `/settings.js`, `/system.js` (JS) and `/system.css` (CSS).

## Settings (`settings.js`)
- Pages: Playback, the table's pages (`feel`, `lighting`, `pedal`, titled from `/api/piano`), Panel. Two panes from
  900 px (the list `clamp(232px, 30%, 300px)`); below, `.split.opened` shows the page under a `.back-row` that takes the
  focus and gives it back to the page's item. The page chosen is kept in this browser (`steven-piano-settings-page`).
- The pane is built once per shape (page; another MIDI piano, firmware without settings, or cards; the table read) and
  brought up to date in place on every state, so a focused or held control keeps its place.
- The piano's settings: a switch `.switch`; a choice `.segmented` (four or fewer) or `.chips` (the palette's eight); a
  stepper `.stepper`, with `lowestOn` as `SettingKind.Stepper.next`; a slider `input.range`. A change waits 150 ms for
  the next, each setting on its own (today's one debounce for all could drop a change to a second setting), its draft
  standing until the piano's value matches it or 3 s after the write; a slider under a finger is never moved by a state.
  A held − or + steps again every 70 ms after 400 ms; a click no pointer made (Enter, Space, a screen reader: `detail` 0)
  steps once. Feel's presets `POST /api/piano/preset`; Save to the piano `POST /api/piano/action {name: "save"}`.
- Playback: `GET /api/settings` on opening, `PUT /api/settings {name: value}` 150 ms after a change, read again after a
  refusal. Steps as `PlaybackPage`'s: tempo and velocity 5, transpose 1, the quietest note in fives landing on 1, the
  re-strike time Auto then 60–250 in tens (Auto and 60 either side of the gap), the pause 500 ms ("Off", "0.5 s"). The
  Full power line and Auto's "110 ms from the piano" follow the state's piano.
- Panel: Appearance through `host.appearance`; Album colours `PUT {albumBackdrop}`, shown from the state (a settings
  change sends a new one).

## System (`system.js`)
- `GET /api/system` every 5 s while the page shows (10 s when `RELAYED`), one request at a time and shared with the
  rail's foot, which reads it once a minute otherwise; `POST /api/system/refresh` on opening and every 15 s while the
  piano is ready, `/api/system` again 1.5 s after a `refreshed: true`; `GET /api/system/history` on opening and every
  60 s. `hide()` and a hidden document stop them all; coming back starts them again (the day only once a minute old).
- `attention(sys, state)` → `[{part, text, short}]`, in order: the battery under 20 % and not charging, or its health
  `overheat`, `cold`, `dead` or `overVoltage`; heat (Android's `moderate` and above; Hot from `severe`, or the battery at
  42 °C); `memory.low`; storage under 1 GB free; with the piano connected, a board `missing`, `temp` ≥ 70 °C, `heapmin`
  under 30 KB; `web.relay.state` not `connected` while `state.web.cloud` is set; each running row in `problem`; `covers`
  `waiting`. The head's capsule shows the first (`+n` more), the dials and rows their own part, the rail its `short` word.
- Dials: SVG, r 52 on a 132 viewBox, ticks every 9° (major every 45°), the arc a circle's dash over 270° set through the
  CSSOM so `transition: stroke-dasharray 480ms` eases it (none under reduced motion); `role="meter"` with
  `aria-valuemin/max/now` and `aria-valuetext` ("82 percent, charging"). Waiting (no figure): the fill hidden, "—", the
  reason ("Needs newer firmware", "Not connected", "Not in use", "Reading…"). The controller's memory: the used share of
  `facts.heapsize` with "212 KB free", else the free figure alone, no arc.
- The piano's boards: seven octaves (an SVG, `role="img"`, its label spoken), each `ok`, `missing` (hollow, amber, its
  label "C3" over "Missing") or not known. The controller's and the piano's facts read "—" while not connected or
  reading, and a `.tag` "Needs newer firmware" for a fact the piano doesn't give (or a firmware with no settings); every
  fact of `piano.facts` with no row of its own, but the protocol's `proto` and `ble`, is a plain row under its name.
- Running now: `running[]` in its order; the dot live for `player` running while `state.player.status` is `playing` and
  the link connected, attention for `problem` and for `waiting` on `covers` and `relay`, on for `running`, hollow
  otherwise; the word by state and row (Playing, Connected, Online, Serving, Fetching…; Paused, Reconnecting, Waiting;
  Problem; Off; Idle, the player's Stopped). Today: drawn at the chart's own width (a `ResizeObserver` draws it again),
  from the first sample or a day ago, battery 0–100 on the left, temperatures 10–50 °C on the right, a run broken where a
  value is missing or the samples stop for three minutes; the sentence from the samples shown.
- Tools: Read status (`POST /api/piano/action {name: "status"}`, then `/api/piano` until `statusReading` is false, its
  `statusText` in a `<pre>`), `POST /api/system/tool {name: "covers"}`, `{name: "reconnect"}` after `host.confirm`, All
  keys off (`{name: "off"}`), Download diagnostics (`<a download="steven-piano-diagnostics.zip">`, the relay dropping
  `Content-Disposition`). A tool waits for its answer before it can be pressed again; each answers with a toast.

## The tablet (`M/web/`)
- `GET /api/settings` (READ): `WebApi.settings(WebSettings)`, `{values: {defaultTempoPct, transpose, velocityPct,
  dynamicRange, velocityFloor, expression, restrikeMs, preRollMs, foldOutOfRange, skipDrumChannel, albumBackdrop},
  limits: {defaultTempoPct, transpose, velocityPct, velocityFloor, restrikeMs, preRollMs: {min, max, step}}, piano:
  {fullPower, repeatMs}}`: the choices in the PUT's words, `restrikeMs` 0 for Auto with its limits the times set by
  hand, the steps `PlaybackPage`'s. `WebBackend.settings()`; `AppWebBackend.settings()` reads the app's settings, Full
  power (the piano ready and `fullpower` not 0; else null) and `PlaybackCopy.pianoRepeatMs`.
- `PUT /api/settings` takes `albumBackdrop` (`SettingsChange.albumBackdrop`, `SettingsRepository.setAlbumBackdrop`).

## The relay (`cloud/`)
- A `GET` under `/p/<id>/api/art/` is counted against `ART_LIMIT` (600 a minute per address, key `art:<address>`,
  namespace 7304) instead of `PANEL_LIMIT`; any other method there stays the panel's.
- The room sets `X-Relay-Art-Limit: 600` on every answer it carries from a tablet, after `responseHeaders` has sifted
  the tablet's own: it is the relay's word, so it is not on the tablet's allow-list (`RESPONSE_HEADERS`) and a tablet's
  own value never passes. `ART_LIMIT_PER_MINUTE` and `ART_LIMIT_HEADER` are in `src/shared/protocol.ts`. Not deployed.

## Simplified, and why
- The Playback rows keep the tablet's names (Pause before each piece, Fold notes outside C1–B7, Skip drum channel), as
  every place on the panel is named as the tablet names it.
- Album covers waiting out Apple's stop is the last item of attention: its row's dot is amber, and the approved picture
  of today's firmware shows it in the head. A battery whose health is `unknown` is a tablet that doesn't say, not a fault.
- The rail's words are short to fit beside their names ("15% low", "Hot", "Board missing"); the memory dial says "Ran
  low" (its figures spoken in full).
- Today spans from the first sample when the app has run less than a day ("Since 14:05"), so a fresh day isn't a
  sliver at the right of 24 hours.
- The Wi-Fi row is named Network on Ethernet, mobile data or a VPN.

## Tests
`WebApiTest` (1: the settings read's keys, its words and limits, the piano's two null without one; `albumBackdrop` in
the PUT's test), `WebServerTest` (1: the route's session, its answer, `albumBackdrop` through the PUT with the header,
405 for anything but GET and PUT), `WebAssetsTest` (1: the modules on the list, asking only through `host.ROOT`, no
fetch or socket of their own, `system.css` tokens only, the Playback page in the tablet's words). The relay: `limits`
(2: the pictures' own 600 apart from the panel's 120, a non-GET staying the panel's; the header on a relayed answer;
every limit test now waits for a fresh minute when too little of one is left, as the local limiter counts in windows
aligned to the clock), `forward` (a tablet's own `X-Relay-Art-Limit` never passes, the relay's does), `hygiene` (the
binding is 600 a minute, each limit its own namespace). The two modules were run in a browser against a stand-in for
the frame's host and the tablet's answers (made by `WebApi`): every page, the narrow layout, the light appearance,
firmware 2.0.0, a missing board and the other attention states, not connected, another MIDI piano. 1,631 → 1,634 unit
tests (12 skipped), none failing; `lintDebug` 0 errors, the same 30 warnings, none in the new code. The relay: 82 → 85
tests, the type check clean.

# v1.18 — M50: System on the tablet

Fable's design (DESIGN.md › v1.18 — M50), Opus coding, one lean run in a worktree (`m50-tablet-system`): no emulator, no
version bump, no signing.

- **Lifted, not copied**: `AppGraph.runningInputs(now)` is `/api/system`'s gathering for `RunningNow`, now the one both
  pages call (`AppWebBackend.system()` reads the panel's figures from its `web`, `relay`, `covers`; its private copy is
  gone). `AppGraph.factsFloor` (`RefreshFloor`, 10 s) is the one floor for the piano's live facts, the panel's and the tablet's.
- **Attention** (`diag/Attention.kt`, pure): `Attention.of(Inputs(reading, running, piano, cloudOn, cloud))` → `Item(part,
  text, key)` in the panel's order and words, `heatWord`; `PianoDiag(facts)` reads the facts as `WebApi.pianoDiag` does,
  `PianoDiag.of(kind, link, piano)` only for Steven Piano connected and ready.
- **Theme**: `LocalAttention` (`AttentionInk` / `AttentionPaper`, provided by `PianoTheme`); `DialFigure` in `Type.kt`.
- **UI**: `ui/components/Dial.kt` (`Dial(label, DialValue, low, high)`, the panel's geometry in a 132-unit square sized in
  sp, an `Animatable` from 0 through `Motion.timed(SlowMs)`); `ui/SystemCopy.kt` (`system.js`'s words, pure);
  `pages/SystemPage.kt` (`SystemNow`, `SystemDay`, `ReadWhileShown`: `repeatOnLifecycle(RESUMED)` unless the resting
  screen is over the app; the cards, the octaves and the day on Canvas). `SettingsPage.System` ("system") first in THIS
  TABLET, `GroupSummaries.system`, `PageRows.FIND_COVERS` / `RECONNECT`, the page's synonyms; `SettingsPageView` sets
  System's column and header at `systemPageWidth()` (1,120 dp at most) and gives pages `onOpenPage` (beside the hub
  `vm.pick`, on phones a push from `NavHost`). View model: `system`, `systemDay`, `readSystem()` (IO, one at a time),
  `readSystemDay()`, `refreshPianoFacts()`, `findMissingCovers()`, `reconnectPiano()`.
- **Simplified, and why**: two columns follow the window's Expanded class (the app's "840 dp and up", `AppFrame`, so a
  phone on its side stays one column) with room for two 360 dp cards (760 dp), not the page's own width: beside the hub
  on the 1,280 dp tablet the page is 839 dp. The hub's row reads the tablet as it shows and then once a minute (the 5 s
  reads are the page's alone); with something needing attention the row is that sentence alone. Album covers waiting
  stays the last item of attention, as the panel's rule has it. The page opens behind the kiosk PIN like every page;
  Reconnect and All keys off go through the gate as well.
- **Tests**: `AttentionTest` (8: each rule on and off, the order); `RoutesTest`, `PianoPagesTest`, `GroupSummariesTest`
  updated. 1,635 → 1,643 unit tests (12 skipped), none failing; `lintDebug` 0 errors, the same 30 warnings, none in the
  new code.

# v1.18 — M48: the cover picker

Fable's design (DESIGN.md › v1.18 — M48), Opus coding, one lean run in a worktree (`m48-cover-picker`) beside the
tablet's Compose work: no emulator, no version bump, no signing.

## The tablet (`data/art/`, `web/`)
- **Search** (`CoverPicker.search`, `data/art/CoverPicker.kt`; `ArtworkRepository.coverPicker`): `term` (trimmed, 2–80
  characters, no control character; else `BadText`); while Apple's hour-long stop stands (`CoverFetcher.blockedFor`),
  `Busy(ms)` and nothing asked; one search in `FLOOR_MS` (4,000 on `elapsedRealtime`) whichever panel or the relay asks,
  else `Wait(ms)`. Then one `AppleCatalogApi.search(term, 25)` through the lookup's own pacer: `ArtworkRepository.apple`
  is `OneSearchAtATime(PacedAppleCatalog(…))`, a mutex round the 3.5 s pacer, so the lookup's searches and the picker's
  take turns. Apple's 403 or 429 starts the stop for both (`CoverFetcher.stop()`, which the lookup now uses too).
- **The results** (`CoverPicker.albums`): those with `coverUrl` (`AppleUrls.cover`'s rule: HTTPS, `*.mzstatic.com`), the
  first of each `collectionName` + `artistName` (case aside), at most 12; each `artworkUrl100` downloaded through the same
  client (`AppleUrls.allowed` on every hop) but not the lookup's 1 s image pacer, four at a time, 64 KB each; one that
  fails, or whose first bytes are no JPEG's or PNG's, is left out and the rest indexed from 0. Remembered under a fresh
  id (12 random bytes, URL-safe base64) for 10 minutes, the last four searches, in memory only.
- **Choose** (`CoverPicker.choose`): the piece (`PickedCovers.madeHere`: genre 1 or 2, else `MadeHere`; none, `NotFound`),
  the search and the index (`NotFound`), then the result's 600 px `coverUrl` at `WikipediaClient.IMAGE_CAP` (403/429: the
  stop and `Busy`; else `Unreachable`), kept through `keepCover` as "Change cover" keeps one, its lookup row `cover:<id>`
  OK with `sourceTitle` "album · artist" (`CoverFetcher.credit`), `sourceUrl` `AppleUrls.pageLink(trackViewUrl)` and
  `description` `ArtworkEntity.CHOSEN_IN_PANEL` ("Chosen in the web panel"): the piece sheet's "Cover: …" line and link
  read as a found cover's. **Remove** (`dropCover`, under the write lock): the piece row's picture cleared (the row
  deleted when it held the cover alone), `cover:<id>` NOT_FOUND with `CHOSEN_IN_PANEL`, the file deleted. Both answer once
  the shared rows show the change (`ArtworkRepository.shown`, 2 s at most), so the panel's next read has the new
  `artVersion`.
- **Chosen by hand** (`ArtworkPolicy.chosenByHand`: `sourceTitle` CHOSEN_HERE or `description` CHOSEN_IN_PANEL):
  `recordOf` reads such a cover row as found, so the worker never queues, fetches (forced too: Look again for covers) or
  writes over it, and `keepCover(unlessCovered)` keeps nothing for it from a lookup already under way.
- **Routes** (WRITE: the session, `X-Steven-Piano: 1`, the panel's origin; bodies through `WebApi`, `onlyKeys`):
  `POST /api/covers/search {text}` → 200 `{searchId, results: [{index, album, artist, picture}]}`, `picture` a
  `data:image/jpeg;base64,…` (or png) address (`WebApi.coverResults`); 400 `field` for a text not 2–80 characters
  trimmed, before anything is asked; 429 `wait` `{retryAfter, retryAfterMs}` with `Retry-After`; 503 `busy`
  `{retryAfterMs, blockedUntil}` (epoch ms) with `Retry-After`; 503 `unreachable`. `POST /api/covers/choose {pieceId,
  searchId, index}` → 204; 404 `not-found` (an index outside 0–11 is not looked for); 409 `made-here`; 503 `busy` or
  `unreachable`; 500 `cover`. `POST /api/covers/remove {pieceId}` → 204, 404, 409. `WebBackend.coverSearch`,
  `coverChoose`, `coverRemove` (`AppWebBackend`: `graph.artwork.coverPicker`).

## The panel (`covers.js`, `app.js`)
- `covers.js` (on `WebAssets.PANEL`), `open(host, piece)` → a promise, when the sheet closes, of whether the cover
  changed: a `dialog.sheet.confirm` (the editors' glass, 400 px, 22 px corners) with "Find a cover", the title and the
  composer; a `.field` holding the text field (filled "title composerShort", 80 characters, Enter searches) and Search
  (off under 2 characters and while a request runs); the covers as a `.cover-grid` of the Library's `.cover-tile` and
  `.tile-play` at 96 px (fixed columns through the CSSOM), each picture an `<img>` of a `data:` JPEG or PNG address (any
  other is left out); Remove this piece's cover for a piece whose art is `cover`, after `host.confirm`; Cancel, Escape
  and the scrim close it. Words: "Searching…", "Fetching the cover…", "Nothing found. Try the album's name or the
  artist's.", "Apple asked to slow down. Try again in a minute." (429), "Apple asked to slow down. Try again after
  15:30." (503 `busy`, its `blockedUntil`), "Those results have expired. Search again." (404); toasts "Cover changed.",
  "Cover removed.".
- `app.js`: `openMenu` gains "Find a cover…" for a piece whose `genre` is classical or modern, `import('./covers.js')
  .then((m) => m.open(host, piece))`; when the sheet closes the focus goes back to the piece's More, and after a change
  `coverChanged` reads `/api/state` (Now playing and Up next take the new `artVersion` now) and the piece again
  (`/api/library?q=<title>&limit=200`), giving each of its rows or tiles in the list drawn its `art` and `artVersion`
  (`art()` loads the new address; the rest keep their pictures).

## Simplified, and why
- Beyond the files named: `ArtworkRepository` (the shared catalogue, the store, `shown`, the guard in `keepCover`),
  `CoverFetcher.stop`, `ArtworkPolicy`, `ArtworkEntity.CHOSEN_IN_PANEL` and `FakeWebBackend`: the picker keeps covers as
  "Change cover" does and shares the lookup's pacing and stop, which live there. No change to `AppGraph`: the picker is
  the repository's, beside its worker.
- Chosen by hand is a mark in the cover row's `description`, so `sourceTitle` keeps the album's credit for the sheet;
  `CHOSEN_HERE` is read as chosen by hand too.
- A removal recorded as not found would have been looked for again by M46's Look again for covers (forced): the hand's
  mark now settles a cover row for every lookup.
- The 503 `busy` line names the time the stop lifts rather than "in a minute"; the 4 s floor's 429 keeps the sentence.
- Remove shows only for a piece with a cover of its own; the field is plain text (a search field's first Escape clears
  the words filled in instead of closing the sheet); Search keeps its width at a phone's.
- A choice downloads its cover even while the stop stands (one picture from the image hosts, asked for by a person); a
  403 or 429 there starts the stop. The picker works whatever the Album covers switch says: it asks only when a person
  does.

## Tests
`CoverPickerTest` (5, a stand-in catalogue: one result an album with artwork, twelve at most, a picture that fails or is
no picture left out and the index of what is shown; a lookalike, a bare and a plain-HTTP picture host never asked for;
the 4 s floor, the text's limits, Apple's 429 starting the shared stop and nothing asked while it stands; ten minutes
and the last four; made here refused, and a cover taken away never looked for again, forced or not), `WebServerTest`
(1: the three routes refused without a session, the header or the origin, POST only, bad bodies 400, an index past
eleven 404, the search's JSON and `data:` picture, 429 and 503 with their times, 404 and 409; the write routes 27 → 30),
`WebAssetsTest` (1: `covers.js` on the list, asking only through `host.ROOT`, its words, the `data:` rule, the menu's
item for the library's pieces). The panel was run in a browser against the real server and files over a stand-in
backend: a search, a choice and a removal from a tile and a row (the new art showing in place), nothing found, the floor,
Apple's stop, unreachable, expired results, Escape, the scrim, a phone's width. 1,635 → 1,642 unit tests (12 skipped),
none failing, the web tests again with `--rerun`. `lintDebug`: 0 errors, the same 30 warnings, none in this run's code.
