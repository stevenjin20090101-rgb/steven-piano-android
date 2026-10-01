<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano v1.2: security and robustness audit

2026-09-25. The audit read the tree at `63738b8` (M11); the fixes below were made on top of the
finished v1.2 (`ee53a24`, after M12), in five commits (`7ddb560`, `a7b1a45`, `93405e8`, `08e5940`,
`644f4f1`), before the release build.

## Summary

**Overall risk before the fixes: medium.** There was no remote code execution, no privilege
escalation through the exported activity, and the network client was already well hardened. The
real problems were crafted-file denial of service and crashes that skipped the app's own stop
sequence. The firmware limits the physical impact of any app failure: it releases every key when
the Bluetooth link drops and caps any single energize at 4 s (`firmware/docs/SAFETY.md`, layers 3
and 7).

The three most important findings were:

1. **(High, F1)** One crafted file could make the Library crash on every launch: imported text had
   no length limit, and a row over Android's 2 MB cursor window breaks every query that returns it.
2. **(High, F2)** A crafted 8 MB MIDI file needed 330–380 MB of heap to parse, and the
   `OutOfMemoryError` was not caught when importing, playing or drawing roll cards.
3. **(Medium, F5)** The release APK was signed with the well-known debug key.

All eighteen findings are fixed or mitigated, except the parts listed as deferred with their
reasons. Tests went from 396 to 472; the corpus of 3,454 files still parses byte for byte as
before (a combined digest in `CorpusTest`).

## Findings and their status

| # | Severity | Finding | Status |
|---|---|---|---|
| F1 | High | Imported text had no length cap: permanent crash loop | Fixed |
| F2 | High | Crafted-MIDI memory bomb; OOM uncaught where files are parsed | Fixed |
| F3 | Medium | Crash paths that bypass the app's stop sequence | Fixed |
| F4 | Medium | Zip and folder imports had no resource caps | Fixed |
| F5 | Medium | Release signed with the debug key | Fixed (tablet migration: owner) |
| F6 | Medium | "From Wikipedia" link opened without validation or error handling | Fixed |
| F7 | Low | Timing overflow spins the scheduler; no duration cap | Fixed |
| F8 | Low | A MIDI packet dropped after 40 busy retries | Fixed |
| F9 | Low | Unbounded send backlog; pedal not rate-limited | Fixed |
| F10 | Low | Exported `MainActivity` surface | Fixed |
| F11 | Low | Photo import and bitmap cache | Fixed |
| F12 | Low | Wikipedia client edge cases | Fixed |
| F13 | Low | Per-frame canvas cost | Fixed |
| F14 | Low | Title regex is O(n²) | Fixed |
| F15 | Low | Artwork queue | Fixed |
| F16 | Low | BLE trust | Fixed in the app; firmware authentication deferred |
| F17 | Info | Privacy and logging | Fixed |
| F18 | Info | Build and release hygiene | Mostly fixed; two items deferred |

### F1: imported text had no length cap (High): fixed

A file named like a stub (`a.mid`) whose Track 0 held a 1 MB name became a 3 MB row (title plus
its folded copies), past the 2 MB cursor window: every Library query threw
`SQLiteBlobTooBigException`, and the Library is the start tab. `INDEX.csv` and a sender's display
name led to the same place.

- Text is cut on code-point boundaries before it is stored (`data/TextLimits.kt`): title 200,
  composer 120, collection and playlist names 120, source path 512, display names 255.
  `searchText` (400) and `titleKey` (200) are derived from what is kept (`PieceEntity.named`), so
  imports, Rename and duplicates filling in a composer all obey the caps.
- The parser reads at most 256 bytes of a text meta, moving a cut inside a UTF-8 character back to
  its start, and keeps 16 names and 16 texts. `INDEX.csv` fields keep 1 KB.
- Rows already imported are repaired once as the database opens, before the first query
  (`data/db/TextRepair.kt`, from Room's `onOpen`, guarded by a DataStore flag): `substr` on every
  capped column, only for rows over a cap. It was run on SQLite with 2.json's schema: a 4 MB row cut
  to the caps, astral characters kept whole, NULLs kept.
- The Library's state pipeline catches a failed read and shows "The library couldn't be read." in
  an `OutlinedBanner` (another category reads again). The dialogs, a composer's Play all, Up next,
  the piece sheet and artwork's shared rows fail quietly too.

### F2: memory bomb; OOM uncaught (High): fixed

- At most 2,097,152 events (`SmfException`: "This file has too many events."). Events are stored
  as two primitive arrays (`EventList`, 12 bytes each; still a `List<TimedEvent>` for tests), and
  the merge reuses its sort keys' array for the times. A 1 MB file of running-status re-strikes
  (about 700,000 events) parses and its piece holds well under 64 MB (tested); the 8 MB version is
  refused.
- At most `min(declared tracks, 1024)` tracks are read, with one per-track table reused; 20
  warnings, then "...and N more."
- `OutOfMemoryError` is caught where files are parsed: an import counts the file as failed ("File
  too large to read"), playing says "This piece is too large to play.", a roll card falls back.
- Roll cards are drawn one at a time (a one-permit semaphore) and kept gzipped in the cache
  (`RollCardFiles`), so scrolling a grid never parses many files at once.

### F3: crash paths (Medium): fixed

- The scheduler catches any `Throwable` from a step and calls `fail()`, which stops the engine
  first (the stop sequence goes out), then reports; the thread carries on.
- A crash nothing else catches still silences the piano: `CrashSilencer`, installed first in
  `App.onCreate`, writes CC64 = 0 then CC123 straight onto the GATT connection
  (`PianoLink.emergencySilence(200)`, past the paced queue and the link's own thread, at most
  200 ms), only if a link exists and is connected, then hands the crash to Android's handler.
- A file whose sender or database throws fails alone; the import finishes; `ImportService` catches
  anything left. `MainActivity.route()` drops an intent it cannot read, and extras are read
  defensively (`BadParcelableException` means no files).
- Residual: a native crash or a kill by the system skips any handler. The firmware's release on
  disconnect and its hold watchdog remain the backstop.

### F4: import resource caps (Medium): fixed

- `INDEX.csv` is read through the capped reader, 2 MB at most (a larger one is ignored).
- A zip is refused over 512 MB (declared, or found while copying), when the cache would keep less
  than a 64 MB margin, or with more than 20,000 entries (counted before any is listed).
- The folder walk (`TreeWalk`) visits each folder id once, reads 16 levels deep, at most 20,000
  MIDI files, 5,000 folders and 100,000 documents, and checks for cancellation before every folder.
- Stale `import-*.zip` copies and `*.part` files older than the process are swept at start.
- Residual, mitigated: opening a crafted zip whose central directory is huge still allocates it
  inside `ZipFile` before the entry count is known; that allocation fails fast and is caught as an
  `OutOfMemoryError`, so the import fails instead of the app.

### F5: release signing (Medium): fixed

The release APK is signed with Steven Piano's own key (RSA 4096, `CN=Steven Piano, O=Steven Jin,
C=US`, APK Signature Scheme v2 and v3). The keystore and its passwords live in the home folder
(`~/steven-piano-release.jks`, `~/steven-piano-keystore.properties`, both `chmod 600`), never in the
repository; `.gitignore` covers `keystore.properties`, `*.jks` and `*.keystore`. Without the
properties file the release build stops with a message instead of falling back to the debug key;
debug builds keep the debug key. **Deferred:** key rotation with an `apksigner rotate` lineage from
the debug key, because a lineage would keep vouching for a key anyone has; each device with a
debug-signed build is migrated by a planned reinstall instead (see *What the owner must do*).

### F6: the "From Wikipedia" link (Medium): fixed

A summary's page URL is kept only when `java.net.URI` reads scheme `https`, host exactly
`en.wikipedia.org`, no user info, no port and a `/wiki/` path (`WikipediaUrls.pageLink`), and it is
checked again when tapped. It opens as `ACTION_VIEW` with `CATEGORY_BROWSABLE` inside `runCatching`;
without a browser an `OutlinedBanner` says "No browser is available to open this link."

### F7: timing overflow (Low): fixed

Files lasting more than a day are refused. `PlaybackEngine.wakeTime` saturates at `Long.MAX_VALUE`
instead of wrapping negative. M12's `TempoMap` arithmetic was re-checked: ticks are at most 2^37 and
tempos 2^24, so every product fits in a `Long`.

### F8: dropped packets (Low): fixed

A packet carrying a Note Off (or a velocity-0 Note On), CC64 or CC120–123 is never given up while
connected; after 40 quick retries it is retried every 20 ms. A Note-On-only packet given up on is
followed by the stop sequence (replacing whatever waits) and a new link epoch, which makes the
player re-sync (silence, then the pedal).

### F9: backlog and pedal pace (Low): fixed

`PacedWriter` keeps at most 2,000 waiting messages: past that the waiting Note Ons are dropped, Note
Offs and controllers kept in order. The router passes a file's pedal changes three at once, then one
per 50 ms (at most 20 a second), a waiting change replaced by a newer one; the engine wakes to send
it. The stop sequence and the Keys screen's sustain are never held back.

### F10: the exported activity (Low): fixed

The `file` scheme is gone from the VIEW filters, and only `content://` URIs are taken from any
intent (the manifest cannot filter a share's `EXTRA_STREAM` or an explicit intent), at most 500.
Nothing is imported until the person answers "Add 3 files to the library?" (Add / Cancel). Display
names are cut to 255 characters.

### F11: photo import and bitmaps (Low): fixed

`PhotoImport` catches `RuntimeException` from decoding and from the framework `ExifInterface`
(no new dependency); decoding is bounded to 4 megapixels in `PhotoImport` and `BitmapCache`
whatever the picture's shape; a full disk while saving a cover is not a crash.

### F12: Wikipedia client (Low): fixed

`WikipediaUrls.allowed`/`hostOf` parse with `java.net.URI`, refuse user info, backslashes and any
port but 443, and compare hosts exactly (so `https://evil.com\@en.wikipedia.org/` is refused).
`Retry-After` is clamped to a day. A JSON body nested deeply enough to overflow `org.json` is an
`IOException`.

### F13: per-frame canvas cost (Low): fixed

The roll and the score's overlay look back at most 30 s before the visible window
(`NoteList.scanStart`) and draw at most 4,000 notes a frame; a system's page drawing is capped alike.

### F14: title regex (Low): fixed

Names are cut to 255 characters and "Composer - Title" is split with `indexOf(" - ")`, matching what
the regex matched (line breaks included). All 5,181 names in the corpus and its zip came out
identical.

### F15: artwork queue (Low): fixed

Queued keys are found through a `HashMap`. An automatic run asks about at most 200 composers outside
the library's canonical list; "Fetch artwork and notes for every composer" is never capped.

### F16: BLE trust (Low): fixed in the app

The remembered address is pinned: another advertiser named "Steven Piano" is never connected to by
itself. A scan that finds only such a one offers it after 3 s ("Another piano called Steven Piano is
nearby…", **Connect to it**), and reconnecting ignores it. Every connection carries a new epoch, so a
drop and reconnection too quick to see still re-syncs the piano. **Deferred (firmware):** the link is
unauthenticated (`BLE_SETTINGS.md`: "Security: none"); in a public space anyone can connect while
the app is not connected. Noted for the firmware owner.

### F17: privacy and logging (Info): fixed

R8 removes `Log.v/d/i` from release builds; request URLs and file paths are logged in debug builds
only. The piece sheet asks Wikipedia only when "Fetch artwork automatically" is on, otherwise it
shows **Fetch notes**. The User-Agent keeps the GitHub URL, now published.

### F18: build and release hygiene (Info): mostly fixed

- Fixed: `dataExtractionRules` exclude every domain from cloud backup and from Android 12+
  device-to-device transfer; StrictMode logs in debug builds; `versionCode` 4 (every sideloaded
  build bumps it); `ImportService.onTimeout` stops cleanly, ready for `targetSdk` 35.
- **Deferred:** Gradle dependency verification metadata and an OSV scan. Both need network access
  and a review of every checksum; worth a run of their own.
- **Deferred (owner):** a passphrase on the authorship key (`sign.py` loads it with
  `password=None`); the owner chooses and keeps the passphrase.
- **Owner practice:** keep debug builds (debuggable) off the school tablet; install the release APK.
- **Question for the firmware owner:** the app limits same-key onsets to one per 100 ms but not the
  duty cycle; the hold watchdog re-arms after each verified off, so a crafted file can keep a key on
  most of the time. Is there a per-channel duty budget?

## Confirmed correct (left alone)

- The 8 MB cap is enforced while streaming: never more than 8 MB + 64 KB is buffered.
- Parser reads are bounds-checked, VLQs are at most 4 bytes, chunk arithmetic is in `Long`,
  truncated chunks are clamped, there is no recursion, and ticks up to 2^37 keep the microsecond
  math overflow-free.
- Zip-slip does not apply: entry names are only labels, and files are stored as
  `pieces/<sha256>.mid`. Per-entry decompression is capped at 8 MB. `CsvReader` is a single linear
  pass. Room queries are parameterised and LIKE input is escaped.
- All storage is internal: no external storage, no world-readable modes, `allowBackup=false`; art
  file names are sanitised to `[a-z0-9-]` plus a CRC32.
- Network: HTTPS and the two-host allow-list on every hop, manual redirects (at most 5), platform
  TLS, cleartext blocked; bodies capped before decoding (256 KB JSON, 6 MB images); titles and
  queries URL-encoded; image URLs rebuilt on `upload.wikimedia.org`; requests serial and paced;
  Wikipedia text rendered as plain text, never HTML.
- Images decode in two passes with OOM caught; the bitmap cache is bounded (an eighth of the heap,
  at most 48 MB); the photo picker needs no storage permission.
- Only `MainActivity` is exported; PendingIntents are explicit and immutable; the Bluetooth
  receiver is not exported; `EXTRA_TAB` only selects a fixed route.
- The emulator hooks need a debug build on an emulator and only reach the fake link.
- Console lines are built from the settings table's names and clamped values, never from a title or
  file name; `encode` rejects line breaks and anything over 79 bytes.
- Silence: pause, stop, seek, load and eject silence first; a link drop pauses and releases the Keys
  screen; swipe-away and `onDestroy` stop and flush; live keys are released on cancel and stop.
- No secrets in the tree or its history; only the public authorship key is committed. The release
  build is not debuggable; minify and shrinkResources are on; no reflection R8 could break.

## What the owner must do

- **Back up the release key**: `~/steven-piano-release.jks` and `~/steven-piano-keystore.properties`
  (it holds the password). Keep a copy somewhere safe and offline. Without them no future release can
  update an installed copy: every device would need an uninstall (which clears its library).
- **Migrate each device** that has a debug-signed build (the school tablet included): uninstall it,
  then install the release APK. Android refuses an update signed by a different key; uninstalling
  clears the library, so plan to re-import the music.
- Keep debug builds off the school tablet from now on.

## v1.3 delta — 2026-09-25

A delta audit read v1.3 at `a7dadc1` (M13's engraving and M14's hands, fingering and chord names on
top of the fixes above). It found one High, one Medium, two correctness and performance findings
(P1, P2) and four Low ones, all in what v1.3 added: crafted files could make the score's layout, the
chord names or the drawing cost without bound. All eight are fixed, in three commits (`51c6eeb`,
`8a7d891`, `044d039`) and this one (docs, versionCode 6, provenance). Tests went from 560 to 583; the
corpus of 3,454 files parses byte for byte as before (digest `7192757e…`) and engraves exactly as
before (34,268 tied heads, 44,840 ties, 203,920 beamed groups, 184,884 rests on the phone panel).

| # | Severity | Finding | Status |
|---|---|---|---|
| H1 | High | The score's layout could run the heap out on a crafted file, and the error killed the app | Fixed |
| M1 | Medium | Chord names looked the key up from the start for every name; signatures were uncapped | Fixed |
| P1 | Performance | The layout found each bar's metre with a scan from the end | Fixed |
| P2 | Correctness | For a frame after a change of piece, the old layout was drawn with the new notes | Fixed |
| L1 | Low | Analyses and layouts could not be stopped and piled up under rapid taps | Fixed |
| L2 | Low | A page's build on the main thread scanned and drew without a cap | Fixed |
| L3 | Low | The waterfall drew every visible chord name each frame | Fixed |
| L4 | Low | Ties walked every bar a note crossed, even bars too short to hold any of it | Fixed |
| — | Copy | The Hand colours note said "waterfall only" | Fixed |

### H1: the layout's memory (High): fixed

The engine allocated about 123 bytes of working arrays per head, budgeted tied heads at
`max(4096, 2n)` (three heads a note) and capped rests per silence but not per piece, and
`ScorePages` ran it in `produceState` with nothing to catch an `OutOfMemoryError`.

- Rests stop at `restBudget(n) = max(4,096, 2n)`: past it silences are still found (and still end
  beams) but no longer written. Tied heads stop at `tiedBudget(n) = min(max(4,096, 2n), 100,000)`.
  A piece of more than 100,000 notes (`MAX_ENGRAVED_NOTES`) is laid out as performed: heads and
  duration lines, no values, beams, rests or ties. A performed piece allocates none of engraving's
  working arrays: 72 bytes a head.
- Measured here: the audit's 0.72 MB file (10,000 bars of 255/1 at 4 ticks a quarter, eight one-tick
  notes a bar) made 2.8 million rests and held 58 MB after the layout; it now makes 160,000 rests and
  holds 9 MB. 300,000 held notes made 900,000 heads (95 MB); now 300,000, performed (26 MB).
- `ScorePages` lays out through `layOut` (`ui/components/ScoreLaid.kt`): an `OutOfMemoryError` or a
  `RuntimeException` leaves the panel saying "This score is too large to show." (Body,
  `onSurfaceVariant`, centred). Cancellation passes through, and so does a failure that ends a layout
  already replaced: `withContext` hands a failure back as it is, cancelled or not, so the result is
  kept only while the coroutine is still active (the test for it found the race).

### M1: chord names and signatures (Medium): fixed

`Chords.detect` makes its names in time order, so the key in force is now a forward index
(`KeysInForce`, which starts again if asked out of order) instead of a scan from the first signature
for each name: 20,000 names under 100,000 key signatures took 1.20 s here and take 0.08 s. The parser
keeps at most 4,096 time signatures and 4,096 key signatures (`SmfParser.MAX_SIGNATURES`, the first
read) and drops the rest with one warning: "Too many signature changes; some were ignored."

### P1: the metre in force: fixed

`ScoreLayoutEngine.timeAt` is a binary search over the signatures' ticks (the same signature for every
tick as before): 20,001 bars under 200,000 time signatures took 1.85 s to lay out and take 0.06 s.

### P2: one piece's layout: fixed

`ScorePages` draws a layout only with the notes it was made for (`laid.madeFor(notes)`, an identity
check) and hands `ScoreView` the layout's own notes, so the overlay never reads one piece's notes
through another's heads.

### L1: stopping superseded work: fixed

`Hands`, `Fingering`, `Chords` and `ScoreLayoutEngine` take a `checkpoint`, called every 4,096 notes
or events (1,024 windows or bars for the chords) and between the layout's passes; it throws to stop
the work. The player passes its coroutine's `ensureActive` and rethrows the cancellation where it used
to catch every exception. A piece start or a refingering that replaces one still running waits 150 ms
first (a burst of five Next taps reads pieces 1 and 5 only), and a layout of the piece already shown
waits 150 ms (`RELAYOUT_SETTLE_MS`); a new piece's first layout starts at once.

### L2: the page's build: fixed

The engine keeps each system's skyline (`ScoreSkyline`: how far above the treble staff's top line
anything reaches, per half-space column, numerals over the right hand included), built from the
profiles the numerals already use; the painter reads a few columns for the tempo mark and each chord
name instead of scanning the system. A system's rests, numerals, beam and tie segments are drawn to
`MAX_NOTE_DRAWS` (4,000) each, as its heads already were. The skyline is never below anything that
reaches up under a label and never above what lies within a column of it (tested against a
brute-force scan).

### L3: chord names on the waterfall: fixed

The names drawn are chosen once per canvas scale, each clear of the one before it (names travel
together, so the same ones show every frame), and at most 64 are drawn a frame.

### L4: bars too short for a tie: fixed

`Ties.segments` cuts a note after 64 bars in a row that hold none of it (`MAX_EMPTY_BARS`; no music
has one), instead of walking every such bar a note crosses.

### Copy

The Hand colours switch's note reads "Colours the two hands on the waterfall and the keyboard strip".

### Confirmed sound (re-checked while fixing)

- The parser's v1.2 caps are unchanged: 2,097,152 events, 1,024 tracks, 256 bytes and 16 texts a
  kind, a day of music, 20 warnings; bars stop at 100,000 (`Bars.MAX_BARS`).
- The engine's other bounds hold: at most 64 heads a written note, 64 rests a silence, 48 heads a
  chord on a staff; key signatures and bars are found by binary search; tempo marks show at most
  9,999 BPM.
- Chord names: at most 20,000 windows a piece, 16 a bar and 512 notes a window; the Viterbi keeps
  three 64-bit words a window.
- Fingering: at most 250,000 notes, at most ten finger orders an event. Hands: linear but for a heap,
  runs cut at 64 notes.
- The roll and the score's overlay draw at most 4,000 notes a frame and look back at most 30 s; a
  note's tied heads light as the cursor reaches them, 63 at most.
- A failure in the hands, fingering or chords (an exception or `OutOfMemoryError`) leaves that part
  empty and the piece plays; only cancellation passes through.
- The hand tones are read only by `NoteCanvas` and `KeyboardStrip`, and only while the switch is on;
  the live red is still read only by `LiveDot`.

### Residual

A performed piece of about a million notes (the most the event cap allows) still lays out at about
72 bytes a head, some 90 MB: on a small heap its panel says the score is too large and the app goes
on. An `OutOfMemoryError` is process-wide, so another thread allocating at that moment could still
fail; the budgets make that unlikely. A crafted file of 100,000 bars with notes in every system keeps
up to about 14 MB of skylines.

### What the owner must do

Install the release APK of versionCode 6 over 1.3 (same key, so it updates in place). Nothing else.

## 1.3.1 — 2026-09-26

The owner's first test on the piano could not connect, and the app could not say why. 1.3.1 makes
the Bluetooth link explain itself (README › Connect to the piano). What that changes for this audit;
nothing above is weakened:

- **Logging (F17 holds).** The link logs its milestones with `Log.w` under the tag `PianoLink`,
  which R8 keeps in release builds (it still strips `Log.v/d/i`). The lines hold Bluetooth addresses,
  RSSI, GATT status codes, the piano's fixed name and the advertised names of the other BLE-MIDI
  devices the filtered scan sees: no file names, titles, URLs, settings or location, no user data. A
  name from the air has its control characters replaced and is cut at 40 characters, so it cannot
  forge a log line; a device is logged once a scan, "the piano fell behind" once a connection.
- **Pinning (F16 holds).** With a piano remembered, only its address is connected to by itself,
  from a scan or when another app on the tablet already holds it; another "Steven Piano" is still
  only offered. With none remembered, a nameless BLE-MIDI device is connected to only when no named
  Steven Piano answered within a second, and becomes the piano only if its GAP Device Name reads
  "Steven Piano"; otherwise it is disconnected before any MIDI is sent and passed over until the
  next Connect.
- **Pairing.** A piano this device is bonded with is reported ("Forget it there, then tap Retry")
  instead of connected to; the app still never pairs.
- **No new permissions.** The bond state and the connected-device list use `BLUETOOTH_CONNECT`,
  already held; a `SecurityException` from either reads as "not paired" or "none", and every radio
  call also catches the `IllegalStateException` Android throws while Bluetooth turns off.

Tests went from 583 to 605.

**What the owner must do:** install the release APK of versionCode 7 over 1.3 (same key, so it
updates in place). To report a connection that fails, capture
`adb logcat -s PianoLink:W BluetoothGatt:V BluetoothLeScanner:V` (README › Send a log).

## 1.4 — 2026-09-26

1.4 lets the app update itself from its GitHub releases (with no tap on a tablet where it is the
device owner) and share its own diagnostics (README › Updates, School tablet, Diagnostics). What
that changes for this audit; nothing above is weakened:

- **New hosts (F12 holds).** Four, for updates only: `raw.githubusercontent.com` (the manifest,
  under this repository's path only), `github.com` (this repository's release downloads only),
  `objects.githubusercontent.com` and `release-assets.githubusercontent.com` (where GitHub
  redirects a release download). Every hop, each redirect included, passes
  `UpdateSource.allowsHop` before anything is sent to it: HTTPS, port 443, no user info, no
  backslash, the host compared exactly, the path's prefix. The Wikipedia client moved onto the
  same path (`net/HttpFetch.kt`) with its behaviour and its two hosts unchanged. Caps: the
  manifest 64 KB before it is decoded, the file at the manifest's size and 50 MB; 10 s to
  connect, 30 s between reads. What is sent is the request and the User-Agent: nothing about the
  person, nothing from the library.
- **The update's trust model.** The manifest is fetched from the repository over HTTPS, and may
  name only a release asset of this repository (`/stevenjin20090101-rgb/steven-piano-android/releases/download/<tag>/<file>.apk`).
  The file's size and SHA-256 must match the manifest's before the file is kept (it is streamed
  to a `.part` file and renamed only on a match), and it is hashed again just before it goes to
  the installer. Android's package manager then enforces the signature: an update must be signed
  with the key of the installed app, Steven Piano's release key, which never leaves the Mac. So a
  wrong or tampered file cannot install. Residual: someone able to change the repository could
  offer a file of their choosing (Android would refuse it), withhold updates, or make tablets
  download up to 50 MB; the checker offers only a higher versionCode, and Android refuses a
  downgrade anyway. While the repository is private the manifest answers 404 and the app says
  "Couldn't reach the update server."; nothing else happens.
- **`REQUEST_INSTALL_PACKAGES`.** It lets the app open Android's installer on its own verified
  download. Android still asks the person to allow "Install unknown apps" for Steven Piano, and to
  confirm every install. The app installs nothing but itself: the ACTION_VIEW path hands over only
  the file it downloaded and verified, and the device owner's session is locked to the app's own
  package (`setAppPackageName`).
- **Device owner: its scope.** Only on a tablet set up by hand over adb (`dpm set-device-owner`,
  possible only with no account on the device). `PianoDeviceAdmin` declares no policies, and the
  app uses the role for one thing: a `PackageInstaller` session for its own package, committed
  without a tap. No lock task, no restrictions, no hidden apps. Before the commit the piano is
  silenced (live keys released, playback paused and flushed), since Android stops the running app
  as it replaces it (measured on API 34); the firmware's release on a dropped link stays the
  backstop. The new version's receiver reopens the app, which the role exempts from Android's
  limits on starting activities from the background. While it is the owner the app cannot be
  uninstalled, and `dpm remove-active-admin` refuses an admin that is not a test build; the role
  is given back with `debug.stevenpiano.releaseowner` set over adb (a `debug.` property can be set
  by the shell, not by an app) and a restart of the app, or by a factory reset.
- **Exported components.** New and exported: `PianoDeviceAdmin`, protected by
  `BIND_DEVICE_ADMIN`, which only the system holds. New and not exported: `UpdateResultReceiver`,
  `UpdateService`, the FileProvider.
- **One mutable PendingIntent, on purpose.** The session's status callback is
  `PendingIntent.getBroadcast` with `FLAG_MUTABLE` (API 31+), because the package installer fills
  in EXTRA_STATUS, EXTRA_STATUS_MESSAGE and, when it wants the person, EXTRA_INTENT as it sends it.
  The intent is explicit (component and package set) to a receiver that is not exported, so what
  is filled in cannot redirect it, and it is handed only to the platform's `PackageInstaller`.
  Every other PendingIntent stays immutable (the download notification's content and Cancel among
  them).
- **FileProvider paths and grants.** One provider (`<package>.files`), not exported,
  `grantUriPermissions`, naming `cache-path updates/` (the verified download, with a one-off read
  grant on the ACTION_VIEW intent to Android's installer) and `cache-path diagnostics/` (the zip,
  with a one-off read grant to the app the person picks in the share sheet). Nothing else in the
  cache, and nothing in `files/`, the database or the library, can be named through it.
- **Diagnostics (F17 holds).** Nothing leaves by itself. The app keeps its last five crash reports
  in private storage (`filesDir/diagnostics`: time, version, device, thread, stack trace, the
  link's last 50 lines; content and file URIs, shared-storage paths and web addresses' paths are
  scrubbed from messages) and the link's last 500 lines in memory. Share diagnostics zips
  `about.txt` (version and build, device model, Android version, device owner yes or no, the
  updater's and the link's state), `settings.txt` (the preferences, the remembered piano's
  Bluetooth address and name among them), `link.log` (Bluetooth addresses, names from the air and
  status codes, as the 1.3.1 log) and the crash reports: no titles, playlists, files, photos or
  Wikipedia text. The data extraction rules still keep everything out of backups and transfers.
- **Debug-only hooks.** `debug.stevenpiano.updateurl` (one local origin for the updater, plain HTTP
  through a network security config that exists only in debug builds and allows cleartext to
  10.0.2.2 alone) and the `EMULATOR_CRASH` extra act only in debug builds on an emulator. Release
  builds keep cleartext refused everywhere and ignore both.
- **Build.** `org.json:json` is a unit-test dependency only (android.jar's `org.json` is a stub on
  the JVM); nothing new ships in the APK. `versionCode` 8.

Tests went from 605 to 661.

**What the owner must do:**
- Make the repository public (GitHub › Settings › General › Change repository visibility), or no
  tablet can read the manifest.
- Install 1.4 over 1.3.1 by hand once (same key, so it updates in place, library kept); from then on
  the Piano tab offers each release. Publish them with `tools/publish-release.sh` (README ›
  Publishing a release).
- For silent updates on the school tablet: the one-time device-owner setup (README › School
  tablet), which needs a factory-fresh tablet with no Google account on it.
- Back up the release key more carefully than ever: without it no update can reach any installed
  copy, and a device-owner tablet cannot even uninstall the app without first giving the role back.

## 1.5.1 — web panel (pre-audit notes)

2026-09-28, written by the coding run (M18) for the auditor, who verifies it; not an audit. 1.5.1
adds the app's first **incoming** network surface: an HTTP and WebSocket server (NanoHTTPD 2.3.1)
on port 8737 that serves a control panel behind a six-digit PIN on the tablet's Tailscale address,
and a public request page and poster on its Wi-Fi address (README › Web control; BUILD_SPEC.md ›
v1.5.1 — M18 has the route table and every cap). Off until a PIN is set and Web control is
switched on. Nothing above is weakened: no new outgoing host, no new permission beyond the
foreground service's two, the network security config unchanged. The plan's fifteen points, each
with where it is built and what shows it (tests are JVM unit tests; `api-checks.txt` is the curl
transcript against `steven_piano`, kept with the run's evidence):

1. **Every changing route: 401 without a session, 403 without the header or with a foreign
   Host.** `WebServer.dispatch`: `Access.WRITE` needs a valid `sp_session` (401) and
   `X-Steven-Piano: 1` (403), and an `Origin`, when sent, of `http://<Host>` (403); `serve` refuses
   a `Host` other than the listener's `address:8737` or the person's `webHostName` (403) before
   anything else, so DNS rebinding gets nowhere. All nineteen changing routes, upload and logout
   among them: `WebServerTest` › *every route that changes anything refuses…* (the fake backend
   records that nothing reached it), `api-checks.txt` § 1. Reads need the session too (§ 2).
2. **`/ws` 401 without a session.** `WebServer.socket`, before NanoWSD's handshake: only `GET /ws`
   (another path asking to upgrade is 404), only on a listener that serves the panel (404 on the
   guest-only one), a valid session (401), `Origin` exactly `http://<Host>` (403), at most two
   sockets (503). A socket whose session ends (logout, a new PIN, Web control off, a day unused) is
   closed at its next ping, within 4 s. `WebServerTest` › *the socket opens only for a session…*,
   `WebSocketHubTest` › *a socket whose session ends…*, `api-checks.txt` § 3.
3. **The PIN.** `PinHash`: PBKDF2WithHmacSHA256, 100,000 iterations, a 16-byte `SecureRandom`
   salt per PIN, a 32-byte key; `ConstantTime.equals` over every byte; the PIN's chars cleared
   after derivation; six ASCII digits only. Stored as `webPinSalt`/`webPinHash` (base64) in the
   app's private DataStore, read only by `SettingsRepository.webPin()`; `PianoSettings` carries only
   `webPinSet`, so Share diagnostics' `settings.txt` (and every screen) has the flag and never the
   salt or hash; `PinHash` and `StoredPin` print as "(kept)". No `Log` call in `web/` or
   `WebService` carries a PIN, a hash, a token, a cookie or a request's content (the listening
   address is logged in debug builds only). `LoginGuard`: per address and for everyone, five wrong
   PINs in a row → 30 s, doubling to 10 min; a try during a wait is refused 429 uncounted; a
   malformed PIN counts as wrong. At the ten-minute ceiling that is about 144 tries a day from all
   addresses together, so a random six-digit PIN takes years on average. `WebAuthTest` (10),
   `SettingsRepositoryTest` › *the PIN is kept apart…*, `DiagnosticsExporterTest`,
   `api-checks.txt` § 13. Login measured 0.28–0.35 s on the emulator.
4. **Uploads.** `PUT /api/upload?name=` with a raw body only (no multipart): the name's extension
   `.mid`/`.midi`/`.zip` (415), a `Content-Length` and no `Transfer-Encoding` (411), a MIDI file ≤ 8
   MB and a zip ≤ 64 MB (413), all before a byte is read (answered in 4–5 ms with nothing sent,
   § 6); an empty file 400; one upload at a time (409). A MIDI file goes through
   `ImportLimits.readCapped` into memory; a zip streams to `cacheDir/web/upload-*.zip` with the
   free-space margin (507) and is deleted when short or once read (`ZipSource(deleteWhenClosed)`).
   `ImportLimits` is unchanged (the zip's entry cap, the parser's caps and the text limits apply as
   to any import); `cacheDir/web` is swept at start. The name reaches the importer only as a display
   name (last path part, control characters removed, cut to length), never as a path.
   `WebServerTest` › *uploads need a length…*, *one upload at a time*, `ImporterTest` (+3),
   `ImportLimitsTest`.
5. **JSON limits.** `WebApi.readObject`: a declared length ≤ 64 KB (411/413 before reading),
   `application/json` with no charset or UTF-8 (415), strict UTF-8 (400), one object, nesting ≤ 4
   counted outside strings before the parser runs (and a `StackOverflowError` caught besides),
   known fields only, ids whole numbers above 0 that fit a Long, lists of ids ≤ 5,000, strings cut
   by `TextLimits`, every number range-checked (refused, not clamped). `WebApiTest` (7),
   `WebServerTest` › *JSON bodies are capped…*, § 5.
6. **No filesystem from URLs.** Static files come from `WebAssets`' two maps keyed by the exact
   request path (`/`, `/app.js`; `/style.css`, `/request`, `/request.js`) plus the poster's
   template, read from the APK's assets by name; the request path is never resolved, joined or
   decoded into a file name: no listing, no `..`, no `/index.html`, no percent tricks.
   `WebServerTest` › *files come from the allow-list by name…*, `WebAssetsTest`, § 7.
7. **No CORS.** No response carries any `Access-Control-*` header (`grep -rnF "Access-Control"
   app/src/main` is empty); a preflight gets 405 with `Allow`. So another site's page can neither
   send the custom header nor read an answer. `WebServerTest` › *every response carries the
   security headers…*, § 8.
8. **The security headers**, on every response the server builds (errors included):
   `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
   `Content-Security-Policy: default-src 'self'; img-src 'self' data:; connect-src 'self'
   ws://<Host>; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
   `Cross-Origin-Resource-Policy: same-origin`; `Cache-Control: no-store` on the API. The pages hold
   no inline script, style or handler, so the CSP needs no `unsafe-inline` (`WebAssetsTest`), and
   they build the DOM with `textContent`, never `innerHTML`. Same test, § 9.
9. **Listeners.** Only the addresses `WebAddress.choose` picks: the first 100.64/10 IPv4 on a VPN
   interface (`tun…`/`tailscale…`) for the whole panel, the first RFC 1918 IPv4 on `wlan…` for
   guests (the whole panel there only with Panel on Wi-Fi too); never the any-address
   (`grep -rnF "0.0.0.0" app/src/main` is empty), IPv6, loopback (but the debug build on an
   emulator), link-local or a mobile network, and never a 100.64/10 address on Wi-Fi or a mobile
   network (carriers use the block too: fixed during the run, `5cbe271`). Started again when the
   addresses change (a network callback that sees VPNs, and a look every 30 s); a listener whose
   socket Android destroyed with its address closes itself and is started again at the next look
   (fixed during the run, `99bf381`: it used to spin a core); everything closes when Web control
   turns off or the PIN goes. `WebAddressTest` (5), `WebServerTest` › *a Wi-Fi listener without
   Panel on Wi-Fi too serves guests only*, `api-checks.txt` §§ 10, 11, 14, 15 (`ss -ltn` inside
   the emulator).
10. **Guests.** `/api/public/catalogue` lists the three built-in lists' current pieces (id, title,
    composer: no art, no lengths, nothing else); `POST /api/public/request {pieceId}` accepts only
    an id on that list (400), and no other field (400: no names, no messages); one request per
    `sp_guest` cookie and per client address every five minutes (429 with `Retry-After`; a new
    cookie from the same address is still 429); at most 50 waiting for approval, or 50 guests'
    pieces in Up next (503). A public POST that carries a foreign `Origin` is 403, and a
    cross-site form cannot send `application/json`. Off by default (Guests can request), and
    Approve requests first on by default. `GuestRequestsTest` (5), `WebServerTest` › *a guest may
    ask once…*, § 12.
11. **The service.** `WebService`, `exported="false"`, `foregroundServiceType="specialUse|
    connectedDevice"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` "Local web control panel for the
    piano, on the person's own network": a local control panel fits none of Android's named types
    (`dataSync` would also end after six hours a day from Android 15); `connectedDevice` is the
    fallback should a device refuse `specialUse`. Permissions added: `FOREGROUND_SERVICE_SPECIAL_USE`
    and `FOREGROUND_SERVICE_CONNECTED_DEVICE` only. Started only by the app itself (the switch, or
    the app's start with the switch on); `dumpsys` shows it foreground with type `specialUse`.
12. **Network security config unchanged.** Release builds still refuse all outgoing cleartext;
    the debug-only config (10.0.2.2 for the updater's emulator test) is untouched. The panel is an
    incoming server, which the config does not govern; the print WebView loads only the app's own
    assets, with JavaScript, file and content access off and every navigation refused.
13. **The piano's routes.** `PUT /api/piano/{name}` accepts only a name in `PianoSettings`'
    table (404 otherwise), refuses a read-only setting (403: the `keyforce_*` pair stays at the USB
    console), and checks the value against the table's kind and range (400, never clamped) before
    sending the wire form the app's own rows send. Presets only by their command; actions only
    `off`, `save`, `status` (no strike tests, no LED test, no reset from afar). `WebServerTest` ›
    *the piano's routes write only the table's names…*, `WebApiTest` › *the piano's values go
    out…*.
14. **Residuals.**
    - **Plain HTTP.** Over Tailscale the traffic is WireGuard-encrypted end to end. With Panel on
      Wi-Fi too, the PIN, the session cookie and everything the panel shows cross the Wi-Fi in
      the clear: anyone on it who records traffic can take over the panel until the session ends.
      The switch says so ("Over Wi-Fi the PIN travels unencrypted") and is off by default. The
      guest pages on Wi-Fi carry nothing secret. The session cookie has no `Secure` flag (there is
      no HTTPS to hold it to).
    - **NanoHTTPD 2.3.1** (2016, no maintained successor) is pinned and was read for this run;
      six of its behaviours are worked around (BUILD_SPEC.md › The server). Its own answers to a
      request it cannot parse (a malformed request line) leave without our security headers;
      they carry no data of ours. Worth the auditor's own reading: `HTTPSession.execute` and
      `decodeHeader` (header parsing, an 8 KB header buffer), and NanoWSD's handshake.
    - **Sessions live in memory:** a restart of the app signs everyone out; nothing is written.
    - **The global login lock is a lever for denial of service:** anyone who can reach the panel
      (a device on the tailnet, or on the Wi-Fi with Panel on Wi-Fi too) can keep the gate shut for
      everyone, up to ten minutes at a time, by sending wrong PINs. Existing sessions keep working.
    - **Guests can fill the queue:** 50 requests need 50 phones (or addresses) within five
      minutes; Approve requests first (on by default) keeps them out of Up next.
    - **CSRF** rests on the custom header, `SameSite=Strict`, the `Origin` check and the absence
      of CORS, not on a token.
    - **`webHostName`**, when set (only through `PUT /api/settings`, with a session), is accepted
      as a `Host` on every listener; it should name the tablet (a MagicDNS name).
    - **The debug build on an emulator** also listens on `127.0.0.1` for `adb forward`; release
      builds never do (`BuildConfig.DEBUG`, removed by R8), and a debug build on a real device
      only on `goldfish`/`ranchu` hardware or a `generic` fingerprint.
    - **Two sockets at most**, and four request threads: a peer that opens connections and sends
      nothing holds a thread for up to 10 s (the socket read timeout); 32 more wait, the rest are
      closed. A crowd on the Wi-Fi can make the request page slow; it cannot reach the panel.
15. **Tests.** 763 before, 832 after: `WebAuthTest` 10, `WebServerTest` 20, `WebApiTest` 7,
    `WebAddressTest` 5, `GuestRequestsTest` 5, `WebSocketHubTest` 7, `PosterTest` 5,
    `WebAssetsTest` 4 (63 in `web/`), `ImporterTest` +3, `SettingsRepositoryTest` +2,
    `GroupSummariesTest` +1; `ImportLimitsTest`, `DiagnosticsExporterTest`, `PianoPagesTest`,
    `RoutesTest` changed. All green; `lint` 0 errors.

**Found and fixed during the run, before this note:** the tailnet address taken from any
interface (`5cbe271`, point 9); the accept loop spinning after Android destroyed its socket
(`99bf381`, point 9); `POST /api/play`'s queue now keeps only the library's pieces, as Play all
did (`76b2c8c`).

**What the owner must do:** install Tailscale on the tablet and the phone (same account), keep it
connected on the tablet (Always-on VPN), set the PIN, and leave Panel on Wi-Fi too off unless the
Wi-Fi is his own. Print the poster from the Remote page once guests are wanted, and test at the
school whether its Wi-Fi lets phones reach the tablet (client isolation would stop guests).


## 1.5.1 — web panel: audit (delta 1) — 2026-09-28

A delta audit read the web panel at `736f735` (M18: NanoHTTPD 2.3.1 + NanoWSD on 8737, a six-digit
PIN, a public guest request page and QR poster, uploads) against the fifteen-point checklist in the
pre-audit notes above, adversarially, on the `steven_piano` emulator and in JVM tests. Read closely:
`web/`, `service/WebService.kt`, `data/imports/Importer.kt`, `assets/web/`, the manifest, and
NanoHTTPD 2.3.1's own `HTTPSession.execute`, `decodeHeader` and NanoWSD handshake (sources kept with
the run).

**The panel is soundly built.** No unauthenticated route reaches the app's state or the piano; the
custom-header + `SameSite=Strict` + `Origin` + no-CORS stack blocks CSRF and cross-site WebSocket
hijacking; DNS rebinding is refused by the `Host` check; uploads and JSON are capped before a byte is
read; the piano's routes reach only the settings table's names within range (never `keyforce_*`,
never the bench commands — `off`/`save`/`status` are the only actions, so the panel can never fire a
solenoid or an LED test); the listeners bind only the two addresses `WebAddress` chose. The three
findings are all denial-of-service or hardening at the network edge, not a break of the
authentication or the piano boundary; all three are fixed. Tests went from 832 to 840. Fixes:
`62672f8` (W1), `8453092` (W2), `635d43b` (W3), and this one (docs, BUILD_SPEC, provenance).

| # | Severity | Finding | Status |
|---|---|---|---|
| W1 | Medium | The shared global login lock let any reachable device shut the login for everyone, up to 10 min at a time | Fixed |
| W2 | Medium | Login attempts sent at once were weighed before any was counted, slipping past the lock and running many PBKDF2 derivations at once | Fixed |
| W3 | Medium | At NanoHTTPD's edge: a byte-at-a-time request head held a thread indefinitely (4 held a listener); a post-answer trickle held one draining; and NanoHTTPD's own answers to a request it could not parse went out without the panel's headers, kept alive, echoing the method | Fixed |

### W1: the global login lock was a denial-of-service lever (Medium): fixed

`web/WebAuth.kt`, `LoginGuard`. Wrong PINs were counted per client address **and** globally, on the
*same* schedule (five in a row → 30 s, doubling to 10 min). The global count is there because a
per-address lock is escaped by rotating source addresses (at most 256 are remembered, then the
oldest — and its lock — is forgotten), so a distributed brute force needs a global ceiling. But
giving it the per-address schedule made that ceiling a weapon: any device that can reach the panel (on
the tailnet, or on the Wi-Fi with Panel on Wi-Fi too) could send five wrong PINs and lock the owner
out, then keep the gate shut for up to ten minutes at a time, indefinitely, by trickling wrong PINs.
Existing sessions kept working, but no one could sign in.

- **Fix** (`web/WebAuth.kt:184`): the per-address gate is unchanged (5 → 30 s → 10 min). The global
  gate now has its own, far gentler schedule — it trips only after `GLOBAL_THRESHOLD` = 20 wrong in a
  row, and its wait is short and shallow-capped (`GLOBAL_FIRST_LOCK_MS` 5 s → `GLOBAL_MAX_LOCK_MS`
  60 s). So one caller can no longer lock the panel for anyone but itself; the most a sustained flood
  can impose on the owner is a minute, ridden out, and a right PIN from anyone clears it at once.
- **Trade-off, chosen deliberately:** the global brute-force ceiling loosens from ~144 tries a day
  (the old ten-minute lock) to ~1,440 at the one-minute cap — a random six-digit PIN still takes on
  the order of a year to grind through, and the panel is behind Tailscale (WireGuard), not the open
  internet, with the per-address gate still stopping a single hammering address in five tries. The
  owner's ability to sign in is worth more than squeezing an already-slow space further.
- **Test:** `WebAuthTest` › *one address hammering wrong PINs no longer locks the gate for everyone
  (audit W1)* and › *the global gate still bounds a distributed brute force, but gently and capped at
  a minute* (the escalation 5 s → 10 → 20 → 40 → 60 → 60, and a right PIN clearing it); the
  per-address ceiling is still pinned by › *five wrong PINs lock the address for 30 s, then each
  doubles to ten minutes*. Transcript `…/audit1/guard-probe.txt` (two client addresses on the running
  build: A's five wrong PINs lock A; B still gets 401).

### W2: login attempts sent at once slipped past the lock (Medium): fixed

`web/WebServer.kt`, `login()`. The handler read the guard's wait, derived the PIN (PBKDF2, ~0.3 s),
then counted a wrong answer — three separate steps. Requests sent together all passed the wait check
before any of them was counted, so the lock never caught them: ten wrong PINs sent at once were
weighed eight times against a threshold of five, each running its own PBKDF2 derivation in parallel (a
CPU amplification on the tablet that drives the piano, and a straight multiplier on any brute force).

- **Fix** (`web/WebAuth.kt:199` `LoginGuard.attempt`, called from `web/WebServer.kt:412`): the wait
  check, the PIN derivation and the count are one step, taken **one at a time** across every listener
  and request thread, and the wait is re-read once the lock is held. So concurrent tries queue and are
  weighed in turn — at most the five before the lock — and at most one derivation runs at a time,
  whatever the number of connections. A locked key is refused *before* it queues, so a flood cannot
  even make legitimate callers wait behind derivations.
- **Test:** `WebAuthTest` › *tries sent at once are weighed one at a time, and only until the lock
  (audit W2)* (twelve threads: exactly five weighed, never two derivations at once); `WebServerTest` ›
  *wrong PINs sent at once are weighed one at a time…* (ten concurrent HTTP logins → five 401, five
  429; it failed with eight 401 before the fix).

### W3: the request edge NanoHTTPD 2.3.1 left open (Medium): fixed

`web/WebServer.kt`, the client handler. Three holes, all reachable without a PIN (the guest-only Wi-Fi
listener included), because NanoHTTPD parses and answers the request line and headers itself, before
`serve()`:

1. **Slowloris.** NanoHTTPD's only bound on time is a *per-read* socket timeout (10 s). A peer sending
   one header byte every few seconds never trips it, so it held a request thread until its head
   reached the 8 KB buffer — effectively forever. Four such peers held all four request threads: on
   `steven_piano`, a plain `GET /` then got no answer within 20 s (`…/audit1/slowloris-before.txt`).
2. **The drain.** `linger()` (which reads and discards an unread body so a pre-body refusal isn't seen
   as a reset) used a *per-read* 500 ms timeout too, so a peer trickling a byte every few hundred
   milliseconds after any answer kept a thread draining without end.
3. **Unheadered errors.** A request NanoHTTPD could not parse — a malformed request line, an unknown
   verb, a broken `%zz` escape, an over-8 KB head, another HTTP version — it answered (or dropped)
   itself, *without* the panel's security headers, `Connection: keep-alive`, and echoing the method
   into a `text/plain` body (`…/audit1/nano-error-probe.txt`). Not browser-exploitable (a browser
   cannot set an arbitrary method, and the answer is cross-site opaque), but a hardening gap.

- **Fix** (`web/WebServer.kt`: `LiteralClientHandler`, `DeadlineInput`, `RequestHead`): the handler
  reads the head itself first (at most 8 KB, NanoHTTPD's own buffer) through `DeadlineInput`, a
  deadline for the **whole request** (`REQUEST_DEADLINE_MS` 10 s) on top of the per-read timeout, and
  checks it (`RequestHead`): not `METHOD /target HTTP/1.x` → 400, an unknown method → 501, another
  version → 505, a broken escape → 400, over 8 KB → 431, a second `Host` → 400, too slow → 408 — each
  written with the panel's headers, JSON, `Connection: close`, and nothing of the request echoed. A
  head that passes is handed to NanoHTTPD with every byte already read (`SequenceInputStream`), one
  request per connection. An upload's body (checked and signed in) and a socket's life `lift()` the
  deadline — their reads keep the per-read timeout, so a 64 MB upload over slow Wi-Fi and a quiet
  socket are unaffected. `linger()` now drains for 500 ms **in all**, not per read.
- **After:** the head-trickle is cut at the deadline and the listener answers meanwhile
  (`…/audit1/slowloris-after.txt`); the malformed requests carry the headers and echo nothing
  (`…/audit1/nano-error-after.txt`).
- **Test:** `WebServerTest` › *a request NanoHTTPD could not parse is refused with the panel's own
  headers, echoing nothing (audit W3)* (ten malformed requests; a bare-LF head still passes), › *a
  head trickled a byte at a time is cut off at the request's deadline, and the listener answers
  meanwhile*, › *a peer that keeps trickling after its answer is let go within half a second*, › *a
  signed-in upload's body may take longer than a request's deadline*; `WebSocketHubTest` › *a socket
  outlives a request's deadline…*. The two `lift` tests fail with the lifts removed (checked).

### Confirmed correct (the checklist, verified adversarially)

Each point was checked; the coder's `…/m18-shots/api-checks.txt` transcript (M18 build) and this run's
`…/audit1/regression-probe.txt` (fixed build) back the network ones, JVM tests the rest.

1. **Every mutating route** answers 401 without a session, 403 without `X-Steven-Piano: 1` or with a
   foreign `Host` or `Origin`, and nothing reaches the backend (`WebServerTest` › *every route that
   changes anything refuses…* over all nineteen; api-checks §1). Reads need the session too (§2).
2. **`/ws`** rejects a missing/invalid cookie (401), a foreign origin (403), another path (404), a
   full house (503) and, on the guest-only listener, everything (404) — before NanoWSD's handshake
   (`WebServerTest`, `WebSocketHubTest`, §3).
3. **PIN:** PBKDF2WithHmacSHA256, 100 000 rounds, a 16-byte per-PIN salt, 32-byte key, constant-time
   compare, chars cleared; the hash/salt are never in `settings.txt`, any screen or any `Log` in
   `web/` (grep clean); a new PIN and Web-control-off end every session; sessions are ≤10, expire in
   24 h, tokens are 32 random bytes kept only as SHA-256; the cookie is `HttpOnly; SameSite=Strict;
   Path=/`, no `Domain` (`WebAuthTest`, `SettingsRepositoryTest`, `DiagnosticsExporterTest`). W1/W2
   strengthen the `LoginGuard`.
4. **Uploads:** `Content-Length` required and honoured, extension allow-list, 8 MB MIDI / 64 MB zip
   refused before a byte is read, one at a time, temp files only under `cacheDir/web` and swept at
   start; a zip bomb is caught by `ImportLimits`; a `.mid` that is a zip in disguise is routed to the
   MIDI path, fails to parse and is counted failed — never unzipped; a name with `..`, slashes, NUL or
   10 000 chars becomes a bare display name (`WebServerTest`, `ImporterTest`, `ImportLimitsTest`, §6).
5. **JSON:** ≤64 KB and length-declared before reading, depth ≤4 outside strings (plus a caught
   `StackOverflowError`), strict UTF-8, one object, unknown fields refused, numbers range-checked not
   clamped, ids whole > 0 that fit a Long, id lists ≤5 000 (`WebApiTest`, `WebServerTest`, §5).
6. **Paths:** static files come from the two allow-list maps by exact request path; the path is never
   resolved, joined or re-decoded into a file name; `..`, `%2e%2e`, `//`, `/web/…`, `.git` all 404
   (`WebServerTest`, `WebAssetsTest`, §7, regression-probe).
7. **No CORS, ever:** grep clean; a preflight is 405; no `Access-Control-*` on any answer
   (`WebServerTest`, §8, regression-probe).
8. **Security headers** (`nosniff`, `DENY`, `no-referrer`, the CSP with no `unsafe-inline`, CORP
   same-origin; `no-store` on APIs) on every response the server builds — and now, with W3, on
   NanoHTTPD's former unheadered error answers too (`WebServerTest`, `WebAssetsTest`, §9).
9. **Binding:** only `WebAddress.choose`'s picks — the first 100.64/10 on a `tun*`/`tailscale*`
   interface (a carrier's 100.64 on Wi-Fi/cell is excluded, verified `WebAddressTest`), the first RFC
   1918 on `wlan*`; never the any-address (grep clean), IPv6, loopback (but the debug emulator),
   link-local or mobile; a Wi-Fi listener serves guests only unless Panel on Wi-Fi too; a destroyed
   socket closes and is restarted, not spun (`WebAddressTest`, `WebServerTest`, §§10–11/14–15,
   regression-probe listener sections).
10. **Guests:** catalogue ids only, one request per `sp_guest` and per address every 5 min, no free
    text (a form post is 415, a foreign origin 403), pending and queued both bounded to 50; off by
    default, Approve-first on (`GuestRequestsTest`, `WebServerTest`, §12).
11. **The service:** `exported="false"`, `specialUse|connectedDevice` with the justifying property,
    started only by the app; its notification's PendingIntent is immutable and opens the Piano tab —
    it reaches nothing unauthenticated (manifest, `WebService.kt`).
12. **Denial of service:** the pool is 4 threads + a 32 queue (past that a connection is closed), a
    socket read waits ≤10 s and a whole request ≤10 s (W3), WebSocket frames are `FrameGuard`-bounded
    and ≤2 sockets exist, headers are bounded to 8 KB by NanoHTTPD's buffer. The player runs on its
    own `URGENT_AUDIO` thread and the BLE link on its own `HandlerThread`, both untouched by the web
    pool, so the piano keeps playing through a flood (W3 tests; slowloris transcripts).
13. **Network security config** unchanged: release builds still refuse all outgoing cleartext; the
    incoming server is not governed by it (documented residual); the poster's print WebView loads only
    the app's own assets with JavaScript, file and content access off and every navigation refused
    (`PosterPrint.kt`).
14. **Residuals stated** (below).
15. **Tests:** 832 → 840. New: `WebAuthTest` +2 (W1, W2), `WebServerTest` +3 (W2, W3×2 — one splits
    the pre-existing header test's intent), `WebSocketHubTest` +1 (W3). `web/` is 71 tests. All green;
    `lint` unaffected.

### Residuals (unchanged risk, stated honestly)

- **Plain HTTP.** Over Tailscale the traffic is WireGuard-encrypted end to end. With Panel on Wi-Fi
  too (off by default, warned), the PIN, cookie and everything the panel shows cross the Wi-Fi in the
  clear; the session cookie has no `Secure` flag (there is no HTTPS to hold it to). The guest pages
  carry nothing secret.
- **NanoHTTPD 2.3.1** (2016, no maintained successor) is pinned and was read for this run. W3 moved the
  request edge in front of it, so its own error answers no longer escape unheadered; the library still
  parses the head a second time after our check, which is belt-and-braces, not a risk. A future move to
  a maintained server (or Ktor) would retire the workarounds.
- **Sessions live in memory:** a restart of the app signs everyone out; nothing is written.
- **Unauthenticated work on the public routes.** `/api/public/catalogue` and `/poster` do a little
  library/DB work without a PIN; it is bounded by the 4-thread pool and the 10 s call timeout, and the
  player thread is separate, so the piano keeps playing. A crowd on the school Wi-Fi can make the guest
  page slow; it cannot reach the panel or the piano's settings.
- **Doze with the screen off is untested on hardware.** The web service holds a partial wake lock while
  playing and runs as a foreground service, but whether Android lets the BLE writes flow under Doze on
  the school tablet, screen off, was not verified on a device — only in the emulator. The firmware's
  release-on-disconnect and hold watchdog remain the physical backstop.
- **CSRF** rests on the custom header, `SameSite=Strict`, the `Origin` check and the absence of CORS,
  not on a token — sound for this threat model, but a token would be defence in depth.
- **`webHostName`**, when set (only via an authenticated `PUT /api/settings`, validated to a DNS name),
  is honoured as a `Host`; it should name the tablet (a MagicDNS name).

The M18 pre-audit residuals were re-checked and hold: the tailnet address comes only from a
`tun*`/`tailscale*` interface (`WebAddress.choose`); the reverse-DNS lookup is bypassed
(`LiteralClientHandler`); every response closes its connection (`secure`); frames are `FrameGuard`-bounded
with ≤2 sockets.

## 1.6 — firmware updates (notes)

2026-09-28, notes written at the 1.6.2 merge, not an audit (the firmware run could not edit this
file). 1.6 (M21) sends the piano's own firmware over the Bluetooth link (README › Updating the
piano's firmware; BUILD_SPEC.md › v1.6 — M21; the contract is `firmware/docs/BLE_OTA.md`). The app
can now change what runs on the machine that drives the solenoids, so what may reach it is held
at five places:

- **Where a release may come from** (`update/UpdateSource.kt`). The manifest from one address
  only, `https://raw.githubusercontent.com/stevenjin20090101-rgb/Steven-Jin-Player-Piano/main/releases/latest.json`
  (HTTPS on 443, the path exactly, no user info, query, fragment or backslash:
  `allowsFirmwareManifest`); what a manifest may name, a `.bin` release asset of that repository
  on `github.com` (`allowsFirmwareFile`); every hop of the download, redirects included, a release
  asset of that repository on `github.com` or one of GitHub's two asset hosts
  (`allowsFirmwareBinary`). The app's own releases and the firmware's never reach each other's
  addresses (`UpdateSourceTest`). Cleartext stays refused; the fake release server exists only in
  debug builds on an emulator.
- **Caps.** The manifest is read to 64 KB and every field checked before anything uses it
  (`FirmwareManifest.parse`: the version, the build, the file's address, `sizeBytes` 1 byte to
  4 MB, a 64-digit SHA-256, an 88-character signature, `minAppVersionCode`, notes to 1,000
  characters, `usbOnly`); the image is streamed to at most the manifest's size and 4 MB
  (`MAX_FIRMWARE_BYTES`), and a longer one is refused.
- **The signature, checked by the app.** The manifest's `sig` is Ed25519 over the 32 raw bytes of
  its SHA-256, checked against the author's key compiled into the app (`FirmwareKeys.author`); a
  manifest that fails is not offered ("The piano update isn't signed by Steven Jin, so it isn't
  offered."). After the download, the image's size and SHA-256 must equal the manifest's and the
  signature is checked again over the digest of those very bytes, before anything is sent. The
  platform's Ed25519 answers where Android has one (Android 14 has none, measured), else
  EdDSA-Java 0.3.0 (pinned, verification only), with S required below the group order (RFC 8032 ›
  5.1.7), which EdDSA-Java alone does not check (`Ed25519Test`). `PinnedKeyTest` pins the key: its
  fingerprint is `eab16a502f679465`, `Provenance`'s, and it is `provenance/author_ed25519_public.pem`'s
  32 bytes. Release builds trust that key alone; RFC 8032's test key is trusted only by the debug
  build's emulator scenarios.
- **The piano's own checks** (BLE_OTA.md › 6–9). The piano checks BEGIN's signature over the
  announced SHA-256 with its own copy of the key before it stops anything; waits for every coil to
  be verified off; refuses energizing commands for the whole session; hashes the image as it
  arrives; and after END checks the digest and the signature again before `Update.end` switches the
  boot slot (ERR 5, 6, 8). The new image boots unconfirmed and confirms itself only after a 30 s
  self-test; a reset before that boots the old one (rollback). With secure boot off, the signature
  is the only check of who built an image.
- **Nothing plays during a transfer.** From the transfer's start to its end, whatever the outcome,
  the player is locked: pieces, channels, the web panel's commands, the Keys screen's notes and,
  from 1.6.2, schedules (a start that falls in an update is missed and says so) are turned away;
  the stop sequence is written and 600 ms of quiet kept before BEGIN (the piano asks for 500 ms).
  An update starts only from the person's Update or Retry (in kiosk mode, behind the PIN), in the
  foreground, and is never retried by itself; Cancel sends ABORT until END.

Residuals:

- **Nothing is verified on hardware yet.** Every step ran against the emulator's stand-in piano
  (`FakeOta`'s scenarios); the real piano needs firmware 2.0.0 flashed over USB first, and README's
  "Needs the real piano" checklist has not been run.
- **The piano's copy of the key is not cross-checked yet.** `PinnedKeyTest`'s case comparing the
  app's key with the firmware's `include/ota_pubkey.h` is skipped until the firmware ships that
  header (with 2.0.0); until then only the fingerprint and the PEM are checked.
- **No anti-rollback.** An older genuinely signed release installs if it is sent (the app offers
  only newer ones; BLE_OTA.md › 8). The link has no bonding or encryption, as BLE-MIDI never had:
  anyone in range can connect, and a replayed BEGIN can stop the music once.
- **The signing key is the authorship key** (`~/piano-authorship-PRIVATE-DO-NOT-SHARE.pem`, outside
  the repository): whoever holds it can sign firmware the piano runs. Back it up offline and never
  share it. Whoever controls the firmware repository can withhold updates or re-offer an older
  signed release, but cannot make the piano run unsigned code.

## 1.6.1 — kiosk

2026-09-28, a note from the merge, not an audit (the kiosk run could not edit this file). 1.6.1 adds
kiosk mode for the school tablet (README › Kiosk; BUILD_SPEC.md › v1.6.1 — M20). It widens what the
device owner is used for, which the 1.4 section limited to silent updates ("no lock task"): with
kiosk mode on, the app also sets lock task with no features (no Home, Recents, status bar or power
menu), turns the keyguard off, sets "stay on while plugged in", and makes its disabled-at-install
`KioskHome` alias the persistent preferred home. `PianoDeviceAdmin` still declares no policies (these
are the device owner's own powers) and sets no user restrictions; turning kiosk off undoes each step
and puts stay-on back as it was.

- **What the PIN protects.** A six-digit kiosk PIN of its own, apart from the web panel's, kept as a
  salted PBKDF2-HMAC-SHA256 hash (100,000 rounds, 16-byte salt) and never in diagnostics
  (`settings.txt` says only whether one is set). It guards the way out (the byline's three-second
  hold, then Unlock for now or Turn kiosk off; on the Kiosk page Unlock for now, the switch and
  Change PIN), and while kiosk mode is on the settings: every page of the Piano tab, the APP group's
  two switches and Check now, Disconnect, and the library's changes (adding music, deleting,
  renaming, playlists' edits, Change photo, a channel's volume). Playing, queueing, browsing and Keys
  stay free by design. Wrong tries: three free, then 5 s doubling to 5 min, a try during a wait
  refused uncounted; the count and the wait are kept in DataStore, so restarting the app or the
  tablet gives nobody the free tries back. At five minutes a try, the million PINs take years.
- **The five-minute unlock.** The right PIN at a locked setting opens the settings for five minutes
  (`SETTINGS_UNLOCK_MS`; another right PIN starts it again), or until the tablet rests in display
  mode, whichever comes first. Unlock for now opens everything until the app is opened again or the
  tablet rests. Both live in memory: a restart of the app locks again.
- **The adb escape hatch.** A forgotten PIN is recovered only from a computer:
  `setprop debug.stevenpiano.releaseowner yes`, then `am start` of the app (Android 14 ignores
  `am force-stop` for a device owner's app). The app checks the property as it starts and on every
  activity start and new intent, and only while it is the device owner; it lets go of the screen,
  turns kiosk mode off, then gives up the device owner (and with it silent updates). A `debug.`
  property can be set by the adb shell, not by an app, so this needs USB debugging on and a computer
  adb accepts. It works in release builds by design: it is the way back.

Residuals: the kiosk keeps a passer-by in the app; it is not a tamper-proof enclosure. Someone with
the tablet's buttons can still reach Android's safe mode or recovery (no `DISALLOW_SAFE_BOOT` or
`DISALLOW_FACTORY_RESET` is set). USB debugging left on is the escape hatch for anyone with a
computer adb accepts; turning it off closes that too, and then a forgotten PIN leaves only a factory
reset. The web panel is not gated by the kiosk PIN (it has its own, on the tailnet). A tablet with
its own secure lock screen keeps it (Android refuses to disable a secure keyguard); the Kiosk page
says so.

## 1.7 — Studio (notes)

2026-09-28, notes written with M23, not an audit. Studio (BUILD_SPEC.md › v1.7 — M23; the spike's
contract is `docs/STUDIO_SPIKE.md`) brings two new kinds of input into the app: a model file that
native code (ONNX Runtime) loads and runs, and recordings from anyone who can pick a file on the
tablet or use the panel. What may reach each is held at these places:

- **Where a model may come from** (`update/UpdateSource.kt`, `Kind.Models`). The list from one
  address only, `https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/models.json`
  (HTTPS on 443, the path exactly, no query or fragment: `allowsModelManifest`); what the list may
  name, an `.onnx` asset of this repository's release tagged `models` on `github.com`
  (`allowsModelFile`: never a version tag, so the app's own releases and the models never reach each
  other's addresses); every hop of the download, redirects included, such an asset or one of
  GitHub's two asset hosts (`allowsModelBinary`). `UpdateSourceTest` covers the three. The emulator's
  local server (`debug.stevenpiano.modelsurl`, HTTP to 10.0.2.2) exists only in debug builds on an
  emulator, as the updater's does.
- **Pinned hashes.** Each model's name, version, file, size and SHA-256 are compiled into the app
  (`ModelCatalogue`); the list only says where a file is. A list whose entry for a known version
  gives another hash or size is refused whole (`ModelManifest.parse`, `ModelManifestTest`), and the
  download is checked against the app's own figures, never the list's: the file is hashed as it is
  written to `models/<file>.part`, must be exactly the pinned size and hash, and only then is moved
  into place (`VerifiedDownloader`). `ModelStore` hashes it again the first time a process opens it
  and deletes a file that no longer matches ("The transcription model was damaged and has been
  removed. Download it again."). A new model, or a new version of one, needs a new app.
- **Caps.** The list is read to 64 KB, at most 32 entries, each field checked (names, versions,
  64-digit hashes, sizes, attribution to 300 characters); a model is streamed to at most its pinned
  size and `MAX_MODEL_BYTES` (1 GiB), apart from the APK's 50 MB and the firmware's 4 MB; a
  download needs the model's size and 256 MB more free, so a model never fills the tablet.
- **ONNX Runtime pinned at 1.28.0** (`gradle/libs.versions.toml`). 1.29.0 and later add a startup
  provider (`ai.onnxruntime.TelemetryInitializer`) that the app would not control. The build's own
  check (`checkOnnxTelemetry`, run by `check`: `check<Variant>OnnxTelemetry` for each variant) reads
  the merged manifest and fails the build if any `<provider>` from `ai.onnxruntime`, or the
  initializer's name anywhere, is in it. The runtime runs on the CPU only (no NNAPI or other
  execution provider), from Maven Central, its native library for arm64 only (M24: the app's other
  native libraries keep their four ABIs); R8 keeps its Java API whole (its JNI looks classes up by
  name).
- **Recordings stay on the tablet.** A recording is decoded, transcribed and written as a MIDI file
  on the tablet; nothing about it is sent anywhere, and the models see only 16 kHz samples. Caps: a
  file over 200 MB is refused before it is read; one whose container says it lasts over 20 minutes
  before decoding, and one that decodes past 20 minutes as soon as it does (the decoded audio is at
  most 77 MB of floats). MIDI files are refused unread past their first bytes. The picker grants the
  app one document, read once by the job; the panel's copy is the app's own cache file, deleted
  when its job ends.
- **The panel's upload** (`PUT /api/studio/audio?name=`): under the panel's sign-in like every
  changing route (the session cookie, `X-Steven-Piano`, the Host and Origin checks; the write
  matrix in `WebServerTest` now holds 24 routes); the name's extension from a fixed list of audio
  types (415 otherwise); a declared length, no chunked body (411), at most 200 MB (413), not empty;
  refused before a byte is read when Studio can't run here (409); one upload at a time (409 "busy");
  streamed to `cacheDir/web/studio-….<ext>` with the cache's free-space margin kept (507), then
  queued. Guests (the request page) cannot reach it.
- **The service** (`StudioService`, `dataSync`) is not exported; the app starts it itself when a
  job is queued, and its Cancel is an explicit, immutable `PendingIntent` to it. Android's time
  limit for `dataSync` (`onTimeout`) cancels every job. Each job holds a partial wake lock of at
  most 30 minutes, released when it ends. The work runs on one thread at background priority,
  one job at a time, never on the player's thread.

Residuals:

- **The models are not signed**, only pinned: their integrity rests on the hashes in the app (and
  so on the app's own signature). ONNX Runtime parses the file in native code; only a file with the
  pinned hash is ever loaded.
- **Recordings are decoded by Android's own codecs** (`MediaExtractor`, `MediaCodec`) in the app's
  process and the media service; a malformed file is what those decoders are built to refuse. WAV
  files are read by the app's own reader, which checks every chunk against the caps above.
- **The GitHub repository is private for now**: until it is public the models' address answers
  404 and Download says it couldn't reach the server. Nothing else changes when it becomes public.

Composing (M24, BUILD_SPEC.md › v1.7 — M24) adds the third input, what a composition is asked for,
and one output, a piece the piano plays:

- **The seed only from the library.** A composition names a piece of the library by its id
  (`ComposeOrder`); the job reads it through the library and the app's own MIDI parser, as the
  player reads it (`LibrarySeeds`), and turns its first 15 s into tokens: integers for times,
  durations and pitches, nothing else. No file, recording or text from the panel or anyone else is
  ever a seed; a piece gone meanwhile ends the job in words ("That piece is no longer in the
  library. Choose another.").
- **No free text to the model.** The sheet and the panel send choices only: a piece id, one of four
  moods, a key (a tonic 0–11 and a minor flag), a tempo of 40–200 bpm, a length of 1–5 minutes.
  `POST /api/studio/compose` reads the body strictly (`WebApi.composeOrder`): any other field, a
  mood not among the four, a value out of range or of the wrong type is 400 before anything is
  queued (`WebApiTest` refuses 16 such bodies, one with a `prompt`); the model's inputs are token
  ids alone. `GET /api/studio/seed` reads a piece's key and tempo, nothing more.
- **The token budget cap.** A length of 1–5 minutes allows 1,800 tokens a minute (2,700 from the
  1.7 release), at most 9,000 a job (`PromptBuilder.budget`): the sampler stops there whatever the model writes, and at the
  length asked. The context is at most 1,024 positions (the window slides to the last 170 events);
  time tokens stay under 10,000 (it slides before 90 s from its origin). So a job's time and memory
  are bounded: on the emulator 5–17 ms a token, the process at 0.55–0.62 GiB at its peak.
- **The gates.** A composition starts only with 700 MiB free above Android's low mark and Android
  not short of memory (`MemoryGate.canStartComposing`); every 100 tokens and before a slide it
  needs 128 MiB (else "The tablet ran short of memory, so composing stopped. …"). One job at a
  time, on Studio's background thread, cancellable between any two tokens; the service's timeout
  cancels it.
- **What reaches the piano.** Every composition is bounded before it is written: keys 24–107
  (folded), velocities 20–110, no pedal, a key struck again no sooner than 120 ms after itself (the
  piano needs 100; the margin keeps the file's ticks and the player's timing from ever bringing
  two strikes under it), at most ten notes starting at one instant (the sampler's guard; measured
  runs peak at seven). The firmware's own limits stay the backstop. The piece is a MIDI file
  imported through the importer's own path and caps, and waits for Keep or Discard.
- **Where it can be asked for.** On the tablet, the Library's + (behind the kiosk PIN in kiosk
  mode, as adding music is) and the Studio page (a settings page, locked in kiosk mode); on the
  panel, behind its sign-in like every changing route (the write matrix in `WebServerTest`: 25
  routes; the seed route among the read routes, which need a session too).

Residuals (composing):

- **The panel is its own gate.** A signed-in panel can compose (as it can transcribe, play or
  change the piano's settings) whether or not the tablet is in kiosk mode: kiosk mode locks the
  tablet's screen, and the panel has its PIN and Tailscale.
- **Resemblance.** The model continues 15 s of a library piece in its manner; the seed itself is
  never written into the result, but nothing measures how close the new music comes to any
  existing piece. It is written for the school's own piano, not published.
- **The model's own behaviour.** Left alone it can fall into long runs of rests or restrike a few
  keys at one instant (measured, BUILD_SPEC.md › v1.7 — M24); the sampler's guards (one rest in a
  row, a key every 120 ms, ten notes at an instant) and the budget bound what that can cost.

## 1.7 — Studio: audit (delta 2) — 2026-09-28

A delta audit read Studio at `d2a66a6` (M23's transcription and M24's composing on top of 1.6.2)
against the ten points of its brief, adversarially: in JVM tests (the real models too, with
`-PstudioModels`), and on the `steven_piano` emulator (API 34, arm64, `-memory 4096`, the app's heap
limit 192 MB), with the models served from the Mac on 10.0.2.2 by a stand-in for the `models`
release and by a hostile one. Read closely: `studio/**` and `studio/compose/**`, `midi/SmfWriter.kt`,
`update/UpdateSource.kt`, `VerifiedDownloader.kt`, `UpdateServer.kt`, `net/HttpFetch.kt`,
`service/StudioService.kt`, the Studio routes in `web/`, `ui/screens/piano/pages/ComposeSheet.kt`,
the manifest, `app/build.gradle.kts`, and the tests. The coders' notes above were treated as claims
and checked one by one.

**Studio holds.** A model is trusted only by the hashes compiled into the app, and every way tried to
hand it another file failed; the runtime is 1.28.0 and sent nothing, by the kernel's own count; every
hostile recording tried failed in words without taking the app down; the panel's Studio routes sit
behind the same sign-in, header, `Host` and `Origin` stack as the rest; no free text reaches the
model and the token budget holds. The findings are one Medium, a memory amplification a crafted MIDI
file reached through the composer's seed, and seven Low, each a bound or a cleanup that was missing;
all eight are fixed. Tests went from 1,137 to 1,147 (8 skipped with the models, 12 without; see point
10). Commits: `8f32157` (S1), `e0baa5b` (S2), `8d5e4e4` (S3), `7202ecc` (S4), `2da9e3c` (S5),
`3d1ca73` (S6), `14340d9` (S7), `0c6db38` (S8), two tests (`37f0daa`, `241b8ae`), then this one
(docs, BUILD_SPEC, README, DESIGN) and the provenance.

| # | Severity | Finding | Status |
|---|---|---|---|
| S1 | Medium | A crafted MIDI file within the importer's caps, used as a composition's seed (by default the piece played last), was read whole: 151 MB for the compose sheet's key and tempo, 377 MB for a prompt, parsed on the main thread, with nothing in the sheet to catch an OutOfMemoryError | Fixed |
| S2 | Low | A cancel while a finished piece was being saved left it in the library, its job "Cancelled", never asked about | Fixed |
| S3 | Low | An Error other than OutOfMemoryError from a job escaped Studio into the app's scope: a crash, or a job "Running" for good | Fixed |
| S4 | Low | The panel could queue Studio jobs without bound, each recording held in the cache until its turn | Fixed |
| S5 | Low | A verified download that failed with an unexpected exception left its part file behind | Fixed |
| S6 | Low | A transcription's notes had no bound (the peak rule allows a note at every frame of every key, 8,800 a second) | Fixed |
| S7 | Low | The build's runtime check guarded ONNX Runtime's telemetry provider, not the version pin | Fixed |
| S8 | Low | A WAV of unknown length grew its 20-minute buffer by quarters: 158 MB of heap where 78 MB would do | Fixed |

### S1: a crafted seed read whole (Medium): fixed

`studio/compose/AmtTokenizer.kt:120` (`notes`, at `d2a66a6`) read every note starting within the
seed's window into three `ArrayList`s of boxed numbers, then a list of `SeedNote`s; nothing bounded
how many. The compose sheet reads its default seed, the piece played last, the moment it opens
(`ComposeSheet.kt:110`, `produceState` → `Studio.seedChoice`, `Studio.kt:167`), and so does the
panel's `GET /api/studio/seed`; a composition reads it again to build its prompt. A MIDI file of 6 MB
and 2,000,000 events (every key let go and struck again each tick, in running status: a million notes
in 12.4 s) passes the importer's 8 MB and the parser's 2,097,152-event caps. From it the key and tempo
allocated **151 MB** and the prompt **377 MB** (JVM, `…/audit2/seed-probe-before.txt`), and the prompt
held 104,246 events of which 340 are ever read. On `steven_piano` (`…/audit2/seed-bomb-baseline-emulator.txt`)
the file imported (piece 1756), played, and then Library › + › Compose a piece… drove the heap to
**187 MB of 192 MB** with blocking GCs on the main thread: `LibraryRepository.load` parses on its
caller's thread, and `produceState` runs on the main one. It did not run out in two tries, but nothing
in the sheet would have caught it if it had: the app is the piano's driver, so an OutOfMemoryError there
is a crash (CrashSilencer then silences the piano). `LibrarySeeds` (`AppStudioLibrary.kt:63`) also let
the parser's own refusal of a file damaged on disk (`SmfException`) escape to the sheet.

- **Fix** (`8f32157`): a seed is read to `AmtTokenizer.MAX_NOTES` = 4,096 notes (`AmtTokenizer.kt:191`;
  fifteen seconds of a dense étude hold some 300; the prompt keeps 340 events), into primitive arrays;
  the notes past it are left out as notes past the window are. `Studio.seedChoice` (`Studio.kt:170`) reads
  and parses on `Dispatchers.Default` and never throws but for cancellation (an exception or an
  OutOfMemoryError is no choice); `LibrarySeeds.seed` (`AppStudioLibrary.kt:72`) takes an `SmfException`
  for no seed. Ordinary seeds are untouched: the fixture's 214 tokens come out as before.
- **After:** facts and prompt allocate under 1 MB for the same file (`…/audit2/seed-probe-after.txt`);
  on the emulator the sheet opened on it with one background GC at 58 of 82 MB and nothing on the main
  thread (`…/audit2/seed-bomb-after-emulator.txt`).
- **Test:** `SeedLimitsTest` (2): the crafted file (built in the test, within 8 MB) reads to 4,096 notes,
  its facts allocate under 8 MB and its prompt under 16 MB (the thread's allocation counter); an ordinary
  seed is untouched and a limit keeps a piece's first notes. `StudioTest` › *a seed that can't be read is
  no choice, and the sheet's call never throws* (it threw the `SmfException` before, checked).

### S2: a cancel while saving orphaned the piece (Low): fixed

`Studio.kt:273` and `:317` (at `d2a66a6`) imported the finished piece, then marked it undecided; a cancel
arriving meanwhile (the notification's, the page's, the panel's) threw at the import's next suspension,
and `outcome` (`:342`) turned it into "Cancelled". The piece could already be in the library (the
importer's transaction done), with no Keep or Discard ever asked and sometimes no "Made in Studio" line.

- **Fix** (`e0baa5b`): saving (the import, the sheet's line, the review) runs as one step under
  `NonCancellable` once it begins (`Studio.save`, `Studio.kt:347`), and a cancel that lands after it
  changes nothing: the job is Done and the piece waits for Keep or Discard (Discard deletes it).
- **Test:** `StudioTest` › *a cancel while the piece is saved changes nothing* (cancelled while the fake
  library holds the import open: Done, the piece undecided, the recording given back; it ended
  "Cancelled" with the piece orphaned before, checked).

### S3: an Error escaped a job (Low): fixed

`outcome` (`Studio.kt:350` at `d2a66a6`) caught `Exception` and `OutOfMemoryError`. Any other `Error` from
a job (a `StackOverflowError`, a `LinkageError` from the native runtime) propagated out of the job's
coroutine into `appScope` (`AppGraph.kt:121`: a `SupervisorJob` on the main dispatcher, no handler): a
crash on the tablet, and short of that a job "Running" for good, its notification with it. The class
already promised that nothing a job does can throw out of it.

- **Fix** (`8d5e4e4`): every `Throwable` but cancellation ends the job as failed, in words
  (`Studio.kt:368`); `OutOfMemoryError` keeps its own line.
- **Test:** `StudioTest` › *an Error from the runtime fails the job in words, and Studio goes on* (a model
  that throws `StackOverflowError`: Failed, the recording given back, the next job Done; before, the job
  stayed Running and the test timed out, checked).

### S4: the panel's Studio queue had no bound (Low): fixed

Studio's queue is a `Channel.UNLIMITED` (`Studio.kt:117`) and the panel fed it: `PUT /api/studio/audio`
and `POST /api/studio/compose` queued a job each, as fast as a signed-in panel (or a page stuck
retrying) could send them. Each recording waited in `cacheDir/web` until its turn, bounded only by the
64 MB free-space margin; each composition added a piece. On the emulator twelve uploads sent behind a
five-minute composition were all taken: 13 jobs to do, 44 MB held (`…/audit2/queue-baseline.txt`).

- **Fix** (`7202ecc`): both routes answer 409 "full" ("Studio has 8 jobs to do already. Try again when
  one has finished.") while eight jobs wait or run (`STUDIO_JOBS_MAX`, `WebServer.kt:1193`;
  `studioFull`, `:618`), before a byte of a recording is read; the page shows it as any refusal. The
  tablet's own Studio page and + sheet, one person picking files, are not held to it.
- **Test:** `WebServerTest` › *Studio takes no more from the panel while it has eight jobs to do* (at
  eight: both refused, a declared 150 MB body unread, nothing kept or queued; at seven with twenty
  finished: both taken; fails with the check removed, checked).

### S5: a failed download's part file left behind (Low): fixed

`VerifiedDownloader` (`update/VerifiedDownloader.kt:107–119` at `d2a66a6`) deleted `<name>.part` for its
own failures, the server's refusal, a cancel and an `IOException`; anything else thrown half-way (a
`RuntimeException` from the platform's HTTP) left up to a whole model, or an APK, until the next start's
sweep. A part is never loaded, so this was space, not trust.

- **Fix** (`2da9e3c`): any throw deletes the part and is passed on (`VerifiedDownloader.kt:120`).
- **Test:** `VerifiedDownloaderTest` › *whatever else is thrown half-way, the part goes with it*.

### S6: a transcription's notes had no bound (Low): fixed

`NotePostProcessor` kept every note. The package's peak rule (neighbours need only not rise) makes a
level onset above 0.3, as a saturated output gives, a peak at every frame: a note can start at every
frame of each of 88 keys, 8,800 a second, some ten million in twenty minutes, held as objects and then
written as twice as many MIDI events, far past the heap before the importer's event cap would refuse the
file. The real model gave far less on everything tried (clicks, noise, a square wave, all 88
fundamentals, bursts, piano-like strikes on 40 keys every 100 ms: at most 78 notes a second, which is
94,000 in twenty minutes; JVM, `-PstudioModels`), so this bounds a crafted or pathological recording.

- **Fix** (`3d1ca73`): the notes are counted as they close; past `NotePostProcessor.MAX_NOTES` = 200,000
  (`NotePostProcessor.kt:275`, about 170 a second for twenty minutes) the job ends "More notes were heard
  in this recording than a piece can hold." (`StudioFailures.TOO_MANY_NOTES`).
- **Test:** `NotePostProcessorTest` › *a level onset above the threshold is a peak at every frame, and a
  recording's notes stop at 200,000* (87,736 notes from one window of it; refused past a cap).

### S7: the runtime check guarded the symptom, not the pin (Low): fixed

`checkOnnxTelemetry` read the merged manifest for `ai.onnxruntime.TelemetryInitializer`. On a scratch
worktree of main with `onnxruntime = "1.29.0"` the check failed, as it should; with the provider removed
by `tools:node="remove"` (the spike's own recipe for a later version) both variants' checks **passed**
while the build resolved `onnxruntime-android:1.29.0`, whose telemetry the spike stopped only with that
removal and `setTelemetry(false)` together, the latter never measured alone (`…/audit2/ort-bump-1.29.0.txt`).

- **Fix** (`14340d9`): each variant's check also reads every `com.microsoft.onnxruntime` module its runtime
  classpath resolves (from the variant's resolution result; configuration-cache safe) and fails unless it
  is at `onnxRuntimePinned` = "1.28.0" (`app/build.gradle.kts:139`). Bumping the version catalog alone now
  fails the build: a newer runtime needs the rule changed first, as BUILD_SPEC says. The reports name the
  runtime ("runtime onnxruntime-android:1.28.0").
- **Evidence:** the four cases on the scratch worktree (`…/audit2/ort-bump-1.29.0.txt`): 1.29.0 with its
  provider fails before and after; with the provider removed it passed before and fails now; main passes.

### S8: a WAV of unknown length grew its buffer (Low): fixed

A WAV whose `data` size was never written (0 or 0xFFFFFFFF, as a recorder cut off leaves it) is read to
the end of the file into `MonoTo16k`'s buffer, which `WavReader` sized for a minute (`WavReader.kt:202` at
`d2a66a6`) and `FloatBuilder` grew by a quarter at a time (`Resample.kt:75`), two copies alive at each
step. A 19.9-minute 16 kHz file of unknown length took the app's Java heap to **158 MB** of 192 while
being read, against **78 MB** for the same audio with its length written (`…/audit2/wav-buffer-heap.txt`).
The notes' "at most 77 MB of floats" held for the result, not on the way.

- **Fix** (`0c6db38`): `WavReader.decode` takes the file's length (`AudioDecoder` passes the document's or
  the upload's size, `AudioDecoder.kt:61`) and sizes the buffer from what is left after the header
  (`WavReader.kt:165`); a wrong length only sizes it, and without one a minute is still the guess. After:
  **90.5 MB** peak for the unknown-length file, 77.5 MB for the known one.
- **Test:** `AudioDecoderTest` › *a data chunk of unknown size is sized from the file's length, not grown
  from a minute* (one buffer sized once, the same samples; a length too long or short changes nothing read).

### The ten points, verified

Transcripts are kept with the run's evidence (`…/audit2/`); tests are JVM unit tests.

1. **Model provenance.** Size and SHA-256 of both models are compiled in (`ModelCatalogue.kt:48–49, 63–64`)
   and a download is checked against them, never against the list (`ModelInstaller.kt:72–81`). Against the
   hostile server on the emulator (`…/audit2/model-provenance-probe.txt`, `evil_server.py`): a list naming
   another SHA-256 for transcription v1 → "The list of models couldn't be read."; the same size with one
   byte flipped → "The download didn't match the model; try again."; a file cut off after 10 MB → "The
   download stopped; try again."; one 1 MB longer (no length declared) → the mismatch, at the pinned size;
   a 302 to another origin → "Couldn't reach the download server." with nothing requested there (the other
   server's log). Each time `files/models` stayed empty, no part left. The good path installed both
   (84 notes and one pedal from the fixture window). A byte changed on disk under the installed model was
   found by the first job of the next process ("The transcription model was damaged and has been removed.
   Download it again."), the file gone (`…/audit2/model-corrupt-on-disk.txt`). The allow-list admits only
   `raw.githubusercontent.com/<repo>/main/releases/models.json` exactly and `.onnx` assets of this
   repository's `models` release (a fork, a version tag, `http`, a port, a query, a sub-path, an asset host
   named directly: refused; `UpdateSourceTest`), and each redirect hop is checked before anything is sent
   (`HttpFetch.exchange`). 1 GB cap and the model's own size as the read limit (`VerifiedDownloader`), a
   256 MB margin (`VerifiedDownloaderTest`; a disk reporting 0 usable bytes is "unknown: tried", by design
   since 1.4, and fails at its first write). `ModelStore` verifies each model's hash before its first
   session in a process (`ModelStoreTest`).
2. **The runtime.** `onnxruntime-android` resolves at exactly 1.28.0 (`…/audit2/runtime-manifests.txt`);
   the merged debug and release manifests hold two providers (`FileProvider`, androidx.startup's), none of
   `ai.onnxruntime`, and no "telemetry" anywhere. With the models installed, iptables counters on the app's
   UID (every packet it sends anywhere but loopback; `adb root` on the userdebug image) read **0 packets**
   through a 3-minute m4a transcription (86.9 s) and a 2-minute composition (23.8 s), and 8 packets for the
   positive control, the app's own update check at its start (`…/audit2/runtime-network-iptables.txt`;
   `…/audit2/runtime-network.txt` has the socket watch). The check task on 1.29.0: S7.
3. **Audio input.** Nineteen hostile files through the panel, one at a time (`…/audit2/hostile-audio.txt`):
   a WAV whose header claims 3.4 hours, one claiming ten hours, a chunk to skip 4 GB, a `fmt ` chunk of
   4 GB, a block alignment of 0, 100 and 17 channels, a zip and a MIDI file named `.wav`, a header alone,
   an empty data chunk, random bytes as `.mp3`/`.flac`/`.ogg`/`.m4a`, an m4a claiming ten hours, a
   25-minute m4a, a 25-minute WAV of unknown length: each ended in its own words within 0.1–4.3 s
   ("A recording can be 20 minutes long at most.", "This file isn't a recording the tablet can read.",
   "That's a MIDI file already…", "The recording is empty."), the process the same, `cache/web` empty. An
   m4a cut to 30 % and to 97 % transcribed what was there; 384 kHz stereo (30 s) transcribed in 10.7 s;
   a 384 kHz FLAC is refused by Android's decoder in words; 768 kHz mono (60 s) read and transcribed in
   23.3 s. The 200 MB cap and the 20-minute cap are checked before decoding (`AudioDecoderTest`); the
   resampler keeps only the input its filter still reads (`Resampler.compact`), and after S8 the output
   buffer is sized once. Decoding runs on Studio's thread (`StudioTest`), and a cancel mid-decode released
   the app's `c2.android.aac.decoder` (`dumpsys media.resource_manager`, `…/audit2/cancel-codec.txt`).
4. **The generation loop.** `PromptBuilder.budget` holds any length to 1–5 minutes and 9,000 tokens
   (`PromptBuilderTest`); a five-minute Wild piece on the emulator stopped at 9,000 tokens ("stop
   budget", `…/audit2/foreground-job.txt`). SEPARATOR is masked unless `SamplingSettings.allowEnd`, which
   no mood, no sheet and no route sets. The panel's body is read strictly (`WebApi.composeOrder`: only
   `pieceId`, `mood`, `key`, `bpm`, `minutes`; `WebApiTest` refuses 16 bodies, the live matrix a `prompt`
   field), and the seed is a library piece by id, turned into integer tokens; after S1 it is bounded too.
   The KV cache cannot pass 1,024 positions (`OrtComposerModel.feed` requires it) and the history the
   budget; the cancel is asked before every token and the memory every hundred (`SamplerTest`,
   `StudioTest`). A job that throws adds nothing and leaves no notification (S2, S3).
5. **Foreground jobs.** One at a time on one background thread (`StudioTest`); `StudioService` is not
   exported, foreground with type `dataSync` (`types=00000001`); its Cancel is a `startService`
   PendingIntent to it and its content a `startActivity`, both immutable. With the task removed (`am stack
   remove`, as a swipe from Recents) the process and the five-minute composition carried on to the end;
   then the service stopped and `Wake Locks: size=0` (the lock's history: acquired and released around
   the job's 54 s) (`…/audit2/foreground-job.txt`). The wake lock is at most 30 minutes a job, not
   reference-counted. `onTimeout` (both overloads) cancels every job and stops the service: read, not run,
   since the app targets API 34 and Android applies the dataSync limit from a target of 35. In kiosk mode
   the Library's + waits for the PIN (`LibraryScreen`: `addMusic = gate.run`), the Studio page is a locked
   settings page (`LockedFirmwareTest`: no page but Firmware during an update opens unlocked), and the
   notification shade is out of reach under lock task.
6. **The panel's Studio routes.** The live matrix on the emulator (`…/audit2/studio-routes-matrix.txt`):
   401 without a session for all four and `/api/state`; 403 without `X-Steven-Piano`, with a foreign
   `Origin`, with a foreign `Host`; 415 as a cross-site form and for `.exe`; 400 for a `prompt` field and
   for six minutes; 413 for a declared 200 MB + 1 before a byte is read; 411 chunked; 400 empty; 405 for a
   wrong method; no `Access-Control-*` header; `cache/web` empty after. `WebServerTest`'s matrix walks all
   25 changing routes and every read route; the guests' listener now answers 404 to every route that
   isn't public, Studio's included (`241b8ae`). The Studio part of the state is the models (name, title,
   size, licence, installed, line, progress) and the jobs (id, kind, name, state, line, progress, title):
   no path, no address. The temp file goes when its job ends or is cancelled while waiting (twelve
   cancelled uploads left `cache/web` at 8 kB), and `cacheDir/web` is swept at start.
7. **The pieces Studio writes.** Compositions: keys 24–107, velocities 20–110, no pedal, a key struck
   again no sooner than 120 ms (100 ms or more in the file at 40 bpm), round-tripping through the app's
   parser (`PostprocessTest`); transcriptions: velocities held to 1–127 and every note at least a tick
   (`SmfWriterTest`). A transcription's file keeps what was heard (keys 21–23 and 108, a key struck again
   40 ms later), as any imported file keeps its author's; the player's router folds and thins it, so the
   piano gets 24–107 only and 100 ms between strikes of a key (`StudioPiecesTest` +1, `37f0daa`). The
   files go through the importer's own caps. The "Made in Studio" artwork row carries a description and no
   image, source URL or source title (`ArtworkRepository.describe`). Measured: a composition in the manner
   of the crafted seed was as dense as its seed allowed, 575 notes in 1.3 s before the budget ran out,
   within the sampler's ten-at-an-instant guard and the player's limits, as any file is.
8. **Memory gates.** The gates are the spike's measured costs plus half (900 MiB to transcribe, 700 MiB to
   compose, 128 MiB to go on; `MemoryGate`). With `debug.stevenpiano.studio=busy` a composition and a
   transcription both ended at once with "Close other apps and try again.": no session made (the process
   gained one thread, Studio's, and none of ONNX Runtime's), the service stopped, no wake lock
   (`…/audit2/gate-refusal.txt`). `lowMemory` mid-job stops a transcription between windows and a
   composition within a hundred tokens (`StudioTest`, both).
9. **Privacy promise.** About's line ("Studio models: ByteDance piano transcription (CC BY 4.0) ·
   Anticipatory Music Transformer (Apache-2.0)") and README's acknowledgements match AUTHORS and
   `third_party/`. README had gone stale in two places, corrected here: the Wikipedia section said the
   app's own updates were its only other network use, and *Security* counted "the two manifests" without
   Studio's list or its models. No host beyond GitHub's four serves the models (point 1). Share
   diagnostics after a transcription and a composition (`…/audit2/diagnostics-export.txt`): `link.log`
   holds the figures lines only ("Studio: composed 1.3 s of music … seed 2830993359307 …", the job's random
   seed, not the seed piece); no title, file name, model path, content URI or address in any file.
10. **Regression sweep.** The whole suite passes with the real models (`-PstudioModels`): 1,147 tests, 8
    skipped, the seven corpus tests and `PinnedKeyTest`'s firmware header, which is still the only skip
    beyond the corpus; without the models 12 (their four cases too). `WebServerTest`'s 401/403 matrix over
    the grown table, the upload and JSON caps, `UpdateSourceTest`'s firmware and model allow-lists and
    `PinGuardTest`'s kiosk PIN all pass. `check` passes: lint 0 errors, 28 warnings (as before), both
    variants' runtime checks.

### Residuals (stated honestly)

- **Nothing is measured on the tablet yet**: every figure here is the emulator's on a Mac, the heap limit
  its 192 MB. README's Studio checklist is still to run on the school tablet.
- **The models are pinned, not signed**: their trust is the app's own signature over the hashes it
  carries. ONNX Runtime parses the model in native code; only a file with the pinned hash reaches it.
- **Android's decoders parse what a recording claims to be**, in the app's process and the media
  service; every malformed file tried was refused in words. A codec that never ends its stream would keep
  its job reading until Cancel (the wake lock lets go after 30 minutes).
- **A document whose provider gives no size** is not held to the 200 MB cap before reading (the
  20-minute cap still bounds what is decoded); pickers of local files give one.
- **A compressed recording whose container gives no duration** still starts its buffer at a minute and
  grows it (S8 sized WAVs from their length); Android's extractors gave a duration for every format tried.
- **The panel is its own gate**: signed in, it can transcribe and compose whether or not the tablet is in
  kiosk mode, eight jobs at a time (S4); the tablet's own queue has no cap, as one person picks its files.
- **The dataSync time limit** is written for (`onTimeout`) but not exercised: the app targets API 34.
- **A composition can be as dense as its seed**, within the sampler's guards; what reaches the piano is
  bounded by the player and the firmware as for any file.
- **The app's updater would follow a redirect onto the models' release path** (its hops admit any release
  asset of this repository); what arrives must still be the APK's size and SHA-256.

### What the owner must do

Nothing new beyond 1.7's release: make the repository public for the models to download, run README's
Studio checklist on the school tablet, and keep the audit's two new lines in mind ("Studio has 8 jobs to
do already…" on the panel; "More notes were heard in this recording than a piece can hold.").


## 1.10 — the cloud (pre-audit notes)

2026-09-30, written by the coding run (M26, the tablet's side of Steven Piano Cloud) for the auditor
(R3, delta 3), who verifies it; not an audit. 1.10 adds the app's first **long-lived outbound connection
that carries requests in**: with *Remote access over the internet* on (off by default, and only with the
panel's PIN set and the tablet enrolled), the tablet keeps one WebSocket to the owner's relay (a
Cloudflare Worker on Steven's account, `cloud/`), and the relay carries browsers' requests and sockets
for `https://<relay>/p/<piano>/…` down it to the same web server the listeners use. BUILD_SPEC.md ›
v1.10 — M26 has the protocol and every cap; `relay-checks.txt` (the run's evidence) is the transcript
over the relay under `wrangler dev`. Nothing of 1.5.1's audit is weakened: the listeners, their
addresses, `allowedHosts`, the network security config and the permissions are unchanged (the app had
`INTERNET` already).

1. **One outbound host, TLS only.** The relay's WebSocket is `wss://<host>/tablet` for the host the person
   typed (`CloudAddress.host`: a DNS name or IPv4, an optional port, nothing else) and no other; OkHttp
   verifies the certificate and the hostname as the platform does (no pinning); no redirect is followed,
   OkHttp's own retries are off; the 101 must echo the subprotocol (else 1002); the hello must name this
   tablet's piano, a host of the relay's form, and `/p/<id>` (else 1008/dropped). Plain `ws://` exists only
   through `CloudOverride`, a debug-build, emulator-only property, and the debug network security config
   allows cleartext to 10.0.2.2 alone. `RelayClientTest`, `RelayProtocolTest`, `EnrolmentTest`.
2. **The bearer secret.** 32 random bytes from the relay, sent as `Authorization: Bearer <id>.<secret>` to
   that host only. Kept sealed: AES-256-GCM under an AndroidKeyStore key (`steven-piano-cloud`, never
   exported, randomized encryption), a fresh IV each seal, the tag checked; DataStore holds `v1:` + base64.
   Read on its own (`SettingsRepository.cloudSecret`), never in `PianoSettings`, `settings.txt`, a log line
   or a `toString` (`RelayConfig`, `EnrolResult.Enrolled`, `RelayMessage.Secret` print "kept"). A key that
   vanished, or a text that was changed, opens to nothing: "not enrolled". Rotation keeps the new secret
   only once it seals and opens again, then acknowledges (`secret.ack`); the relay accepts both hashes for
   10 minutes meanwhile. *Forget this cloud* deletes the text and the key. `allowBackup="false"` as before.
   `SealedSecretTest`, `SettingsRepositoryTest`, `DiagnosticsExporterTest`; on the emulator: enrol, rotate
   (committed), reconnect with the new secret.
3. **Enrolment.** One `POST https://<typed host>/api/enrol` of `{"code": "XXXX-XXXX"}` alone (no name, no
   model), after the code is read into the relay's 32-letter form; `HttpPost`'s allow-list: `https`, the host
   and port exactly as typed, the path `/api/enrol`, no user info, query, fragment or backslash; no redirect
   followed; at most 16 KB of answer; `pianoId` and `secret` must be of the relay's forms (depth ≤ 2). The
   id and the sealed secret are written in one DataStore edit. `EnrolmentTest`.
4. **Relayed requests meet the audited route table** (`WebServer.serveRelayed` → `answer`), with the relay's
   edge in place of the listener's: `Host` exactly the relay's host (the hello's), 403 otherwise; an
   `Origin` of `https://<host>` for every change and for the login (403 otherwise; the tablet's LAN origin
   is refused too); `X-Steven-Piano: 1` for every change (403); the session for every read (401); cookies
   `HttpOnly; SameSite=Strict; Path=/p/<id>/; Secure`, no `Domain`; the policy's `connect-src` names
   `wss://<host>`; a method NanoHTTPD doesn't know 501, an address that doesn't decode 400 (as
   `RequestHead` on a listener); a relayed upgrade request 404. `WebServerRelayTest` proves every route's
   relayed status and headers equal the listener's over `RawHttp` (but the socket's scheme and the cookie's
   path and `Secure`), and that no header a listener sends is lost on the relay. `relay-checks.txt` shows the
   401/403 matrix through the real relay.
5. **Only the forwarded headers reach the server**: `host`, `cookie`, `origin`, `content-type`,
   `content-length`, `x-steven-piano`, `accept` (the relay sends no others; `RelayedSession` keeps no others,
   so no `Upgrade`, no `Transfer-Encoding`, no `X-Forwarded-*` can be smuggled). The client address is the
   relay's `CF-Connecting-IP`, used only for the login guard's and the guests' per-address counts, and only
   when it looks like an address ("unknown" otherwise).
6. **Guests over the relay** exist only while *Guests can request* is on: `/request`, `/request.js`,
   `/poster` and `/api/public/*` answer 404 otherwise (on the listeners the page says requests are closed, as
   before). The per-cookie and per-address limits apply as on the Wi-Fi. `WebServerRelayTest`.
7. **Bodies.** The relay refuses a body without a length or chunked (411) and over 100 MB (413) at its
   edge; on the tablet the route's own caps apply unchanged before a byte is read (JSON 64 KB; a MIDI file
   8 MB, a zip 64 MB, a recording 200 MB, which the relay's 100 MB never lets through; one upload at a time).
   A relayed body is a `BodyPipe` of the credit window (1 MB): the relay may never have more outstanding,
   a chunk past it ends the body; credit goes back only as the server reads; a read waits at most 30 s;
   `req.abort` ends it. `BodyPipeTest`, `RelayClientTest` (1 MB under a 128 KB window).
8. **Concurrency.** At most 8 relayed requests at once (the relay's cap mirrored): past it 503 `busy` at
   once; they run on 4 threads of their own (the listeners' pool untouched). At most 4 relayed browser
   sockets (the relay's cap mirrored), apart from the listeners' 2. The OkHttp queue is kept under 1 MB
   between an answer's chunks (OkHttp closes a socket past 16 MB). `RelayClientTest`.
9. **Relayed sockets**: `ws.open` must name the relay's host, then `admitSocket` (a valid session, the
   origin `https://<host>` exactly: 401/403 → `ws.refuse`); the tablet reads nothing a browser sends (the
   relay drops it); a session that ends closes the socket at the hub's next ping (4000); the hub's messages
   are fitted to one 64 KB frame (Up next gives up rows) or dropped. `WebSocketHubTest`, `RelayClientTest`,
   and through the real relay: 1008 "Enter the PIN first." without a session.
10. **Console commands are trusted because they arrive on the tablet's own authenticated connection** (the
    console Worker, behind Cloudflare Access, is the only other holder of the room's binding). The tablet
    checks them again all the same: `transport`, `play`, `playChannel`, `stopChannel`, `guests`,
    `library.load`, `status` with exactly their arguments and types, anything else refused with its reason;
    they act through `WebBackend` as the panel's routes do (the same range checks); each is a trail line of
    its name and outcome, never a title. No command reaches the PIN, the kiosk, the firmware, Studio's
    models or the settings beyond the two guest switches. `RelayCommandsTest`.
11. **What the relay learns** (`RelayStatus`, every 30 s and ≤ 2 s after a change): the app's version and
    build, the piano's firmware version, the link's state, what plays (title, composer, position, length,
    channel), the guest switches, whether Web control is on and its address on the tablet's own networks,
    the library's size, the channels' names. No device identifier: no Bluetooth address or name, no serial,
    no Android id. `RelayStatusTest`. The relay also sees every relayed request's content, as an HTTPS
    site's server does; it is the owner's own Worker.
12. **The relay's messages are read strictly** (`RelayProtocol.decode`): 64 KB in UTF-8 bytes, six deep
    before `org.json` parses them, ids u32, known fields of their types and lengths, the rest dropped;
    frames of at most 64 KB. A relay that misbehaves can waste the tablet's four threads for 30 s at a time
    and send commands from the allow-list; nothing else.
13. **Logs**: the trail (`LinkLog`, kept in release builds) gets "Cloud: enrolled with <host>", "connected
    to <host>", the connection's ends and waits, "revoked", "removed from the console", "a new secret is
    kept", and the console's commands by name and outcome. Never a secret, a cookie, a PIN, a path or a body.
14. **The service** runs only while (Web control or remote access) is on and a PIN is set, as a
    foreground service with its notification ("· Cloud" while connected); its special-use subtype now reads
    "Web control panel for the piano: on the person's own network, and through the owner's own relay". No
    new permission.
15. **The dependency**: OkHttp 4.12.0 (Apache-2.0), pinned, named only under `web/relay/` (grep), with the
    Okio the app already had; `HttpFetch` and the enrolment stay on `HttpURLConnection`.
16. **Residuals.**
    - **The login guard is shared** by the listeners and the relay: the internet can now reach W1's lever
      (wrong PINs closing the gate for everyone for up to ten minutes), behind the relay's own 10 logins a
      minute per address and the guard's per-address lock. Existing sessions keep working.
    - **CSRF over HTTPS** still rests on the custom header, `SameSite=Strict`, the exact `Origin` and the
      absence of CORS (the relay adds none), not on a token.
    - **Sessions live in the app's memory**: a restart signs relayed browsers out too.
    - **No certificate pinning**: a CA the platform trusts could stand in for the relay's host.
    - **The relay is trusted with content**: it is the owner's own Cloudflare account; the PIN is never
      checked or held there, but a compromised account could serve a page that asks for it.
    - **A tablet asleep off power** keeps the connection only as Doze allows; the school tablet is expected
      on its charger.
    - **Not on real hardware or Cloudflare yet**: every figure is `wrangler dev` on the Mac with the
      emulator.
17. **Tests.** 1,219 before, 1,273 after: `RelayProtocolTest` 9, `BodyPipeTest` 6, `WebServerRelayTest` 8,
    `RelayClientTest` 10, `RelayCommandsTest` 4, `EnrolmentTest` 4, `SealedSecretTest` 3, `RelayStatusTest`
    2, `CloudCopyTest` 1, `WebSocketHubTest` +4, `WebAssetsTest` +1, `SettingsRepositoryTest` +1,
    `GroupSummariesTest` +1; `WebApiTest` and `DiagnosticsExporterTest` changed. `lint` 0 errors.

**What the owner must do:** deploy the relay and the console (`cloud/README.md`), put Cloudflare Access in
front of the console, then enrol each tablet with a code and turn on *Remote access over the internet*.
Keep the Cloudflare account's own sign-in strong (two-factor): whoever holds it holds the relay.

## 1.10 — Steven's library pack (notes, M27)

2026-09-30, notes written with M27, not an audit (BUILD_SPEC.md › v1.10 — M27). The app can now download a
61 MB zip of MIDI files from this repository's release `library` and import it: a new source of files that
arrives without the person picking them. What may reach the app, and what the pack can do once it has, are
held at these places:

- **Where the pack may come from** (`update/UpdateSource.kt`, `Kind.Library`). Its list from one address
  only, `https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/library.json`
  (HTTPS on 443, the path exactly, no query or fragment: `allowsLibraryManifest`); what the list may name, a
  `.zip` asset of this repository's release tagged `library` on `github.com`, a plain name (`allowsLibraryFile`:
  never a version tag or `models`); every hop of either request, redirects included, one of those or GitHub's
  two asset hosts (`allowsLibraryBinary`). The app's, the firmware's and the models' sources refuse the
  pack's addresses, and its source refuses theirs (`UpdateSourceTest`). The emulator's server
  (`debug.stevenpiano.libraryurl`, HTTP to 10.0.2.2) exists only in debug builds on an emulator, as the
  updater's does; that source may also reach the production addresses above (so a manifest from the Mac can
  name the published pack), nothing else.
- **The list** is read to 64 KB by the transport and refused past 4 KB by `LibraryManifest.parse`; every field
  checked (a JSON integer version 1..10,000; the file exactly `library-v<version>.zip` and the URL's last
  segment; the size 1 byte..200 MiB; 64 hex digits; pieces 1..20,000; notes and licence lines plain text,
  capped). It is not pinned: its trust is its address (this repository's `main`), as for the app's own updates.
- **The zip** is streamed to at most its declared size and `MAX_LIBRARY_BYTES` (200 MiB, its own cap), hashed
  as it is written to `cacheDir/library/<file>.part`, and renamed into place only at exactly the list's size
  and SHA-256 (`VerifiedDownloader`); any failure, a cancel, or an exception from below deletes the part. It
  starts only with room for itself and, beside it, twice its size and 64 MB more (its pieces once unpacked). A
  load that stopped leaves at most a file there, swept at the next start (`LibraryPack.sweep`).
- **The import's caps still apply.** The pack goes through the importer like any zip the person picks
  (`ImportSource.LocalZip` → `openLocalZip` → `ZipSource`): at most 20,000 entries (counted before any is
  listed), INDEX.csv read to 2 MiB, hidden and `__MACOSX` entries skipped, each MIDI file read to 8 MB and
  parsed with the parser's own bounds, text cut to the library's limits. It is read where it lies, never
  copied, and deleted when the import closes it. Entry names are lookup keys and display names only: a piece
  is saved as `filesDir/pieces/<sha256>.mid`, so no path in the zip ever becomes a path on disk.
- **The index's `sha256` column decides nothing about trust.** It is read only to leave out pieces an earlier
  pack offered (`skipShas`) and to record what this pack offered (`filesDir/library/offered-v<n>.txt`, app
  private, read back to 2 MB each and only as 64-hex lines). The importer hashes every file itself and dedups
  by that hash; a column that lied could at most make an update skip a piece or offer one again.
- **Nothing is deleted, and nothing runs.** An update adds pieces and never removes one; a pack is MIDI files,
  a CSV, a README and a licence text, and only MIDI files and the index are read.
- **Entry points.** `LibraryService` is not exported; its intent carries one Boolean. `ImportService.start`
  refuses a `LocalZip`. On the tablet, loading waits for the kiosk PIN in kiosk mode, as adding music does;
  the console's `library.load` (M26) is the owner's command and starts the same load.
- **What leaves the device**: the list's request (daily while the app is open and online with Check for updates
  on, and when the empty Library or the + sheet shows, at most every 10 minutes) and, on Load or Update, the
  zip's request; each with the app's User-Agent and nothing else, as the updater's.

### Residuals (stated honestly)

- **The pack is not signed**: a pack published by whoever controls the repository's `main` and its releases is
  loaded. Its reach is the importer's (pieces added to the library, bounded as above).
- **MAESTRO's licence is non-commercial** (CC BY-NC-SA 4.0): the licence sheet says so before the first load,
  and README and AUTHORS carry the credits; the school's use is non-commercial.
- **The console's command with the app in the background** starts the load's own foreground service: the web
  service, which holds the relay's connection, keeps the app's process in the foreground-service state, and
  Android allowed the start (seen at the merge, BUILD_SPEC.md › v1.10 — M27 › *The merge*); were it refused,
  the load would run in the app's process without the notification.
- Not run on the school tablet yet.

## 1.10 — the cloud and the library pack: audit (delta 3) — 2026-09-30

A delta audit read Steven Piano Cloud and Steven's library pack at `90f87fa` (R1's relay and console in
`cloud/`, M26's relay client, M27's pack, merged on `main`) against the twelve points of its brief,
adversarially: the relay and the console in the Workers runtime itself (vitest through Miniflare, a local
D1, the rate-limit bindings), the app in JVM tests, and end to end on the audit's own AVD
`steven_piano_audit` (API 34, `medium_tablet` 2560 × 1600, 4 GB, the debug build) enrolled with the relay
and the console under `wrangler dev` on the Mac (`CloudOverride` → `10.0.2.2:8787`; nothing deployed,
Steven's Cloudflare account untouched). Read closely: `cloud/src/**`, `cloud/test/**`, `cloud/console/**`,
the two Wrangler configs, `M/web/relay/**`, `M/web/WebServer.kt`, `WebAuth.kt`, `WebPanel.kt`,
`M/service/WebService.kt`, `LibraryService.kt`, `M/net/HttpPost.kt`, `M/library/**`, `M/update/UpdateSource.kt`,
`VerifiedDownloader.kt`, `M/diag/**`, `tools/publish_library.py`. The coders' notes above (the cloud's
pre-audit notes, M27's) were treated as claims and checked one by one.

**The cloud holds where it matters most:** a tablet gets in only with its secret, which the relay keeps only
as a SHA-256 and the tablet only sealed by its Keystore; the panel over the relay meets the same route table,
PIN, header, `Host` and `Origin` checks as over a listener, with `Secure` cookies under the piano's own path;
no CORS anywhere; bodies are capped and metered before they reach the tablet; the console is behind Access
and checks Access's token itself; D1 holds hashes, the console's actions and nothing of a visitor. The
findings are three Medium and four Low, all fixed: the relay put the PIN on the open internet behind a gate
made for the tailnet (C1); a revoke could be missed by a connection replacing another (C2); the relay passed
any header of a tablet's answer to a browser, on an origin every piano shares (C3); and four Low. Tests went
from 1,324 to **1,328** in the app (13 skipped, as before) and from 61 to **79** in `cloud/`. Commits:
`45edc49` (C1), `14cf525` (C2), `73026c6` (C3), `3ddfe5b` (C4), `91758d3` (C5), `dfb81ea` (C6),
`bf0e10b` (C7), `5c67e6f` (tests of the limits, of traffic that is not the protocol, of the console's codes),
then this one (docs) and the provenance. Transcripts are kept with the run's evidence (`…/audit3/`).

| # | Severity | Finding | Status |
|---|---|---|---|
| C1 | Medium | The relay put the six-digit PIN on the open internet behind the listeners' gate (W1's, gentle on purpose behind the tailnet): ~1,440 tries a day from rotating addresses, half the PINs in about a year; and the shared guard let the internet close the tailnet's sign-in | Fixed |
| C2 | Medium | A revoke or a forget landing while a replacing connection wrote its "replaced" note was missed: the revoked tablet's connection was accepted and stayed; two replacing connections could both stay | Fixed |
| C3 | Medium | The relay passed every header of a tablet's answer but a deny-list, on an origin every piano shares: `Service-Worker-Allowed` would let one piano's answer install a service worker over every piano's panel | Fixed |
| C4 | Low | The console's `library.load` made a tablet's first load without its licence sheet (MAESTRO's non-commercial licence) | Fixed |
| C5 | Low | Every status carried the tablet's own network address (kept in D1, never used); About said nothing of the relay; README's list was not exact | Fixed |
| C6 | Low | An enrolment code could re-key, and un-revoke, any piano it named (none does today: the claim held only by the console's construction) | Fixed |
| C7 | Low | After a 101 that failed the subprotocol check, the relay client still acted on what arrived before the close | Fixed |

### C1: the relay's PIN gate was the tailnet's (Medium): fixed

`web/WebAuth.kt:184` (`LoginGuard`) and `service/WebService.kt:232` at `90f87fa`: the relay's `WebServer` was
given the process's one `guard`. Its per-address gate (5 wrong → 30 s doubling to 10 min) is escaped by
rotating addresses, which on the internet are plentiful; its global gate is W1's (`GLOBAL_THRESHOLD` 20, then
5 s doubling to a 60 s cap), deliberately gentle because "the panel is behind Tailscale (WireGuard), not the
open internet" (this file, W1). Through the relay it is the open internet: one wrong PIN a minute from fresh
addresses, the relay's own 10 a minute per address never in the way, weighs some 1,440 PINs a day, half the
million in about a year and 4 % in a month. And since the guard was shared, wrong PINs from the internet shut
the tailnet's and the Wi-Fi's sign-in too, the very lever W1 had taken away, now reachable by anyone.

- **Fix** (`45edc49`): `LoginGuard.forRelay()`, `WebPanel.relayGuard`, given to the relay's server alone. The
  per-address gate is the listeners'; the global one trips after `RELAY_GLOBAL_THRESHOLD` = 10 wrong in a row
  and waits `RELAY_GLOBAL_FIRST_LOCK_MS` = 1 min, doubling to `RELAY_GLOBAL_MAX_LOCK_MS` = 1 h: some 40 tries the
  first day and 24 a day after, under 1 % of the PINs in a year (half in some 57 years). The listeners keep W1's
  gate, which the internet no longer reaches.
- **Trade-off, chosen deliberately:** someone who keeps sending wrong PINs through the relay can keep its sign-in
  shut, up to an hour at a time. Sessions already open keep working (24 h, renewed by use), and the tablet and
  the tailnet are untouched; README › Cloud says so.
- **Tests:** `WebAuthTest` › *the relay's gate lets a distributed brute force some 24 tries a day, the listeners'
  some 1,440* (a simulated day of rotating addresses: 1,400–1,500 on the listeners' gate, 30–45 on the relay's,
  and the relay's schedule 1, 2, 4 … 32 min, then 60, 60); `WebServerRelayTest` › *the relay's PIN tries have a
  stricter gate of their own, and never close the listeners'*.
- **Evidence** (`…/audit3/gate-check-audit3.txt`, the real wiring on `steven_piano_audit`): ten wrong PINs through
  the relay from ten addresses, the tenth answered `retryAfter 60`; the right PIN through the relay 429 "Try again
  in 60 s."; meanwhile the listener (the debug build's loopback, `adb forward`) weighs a wrong PIN (401) and signs
  the owner in (204); a minute on, the relay signs in (204).

### C2: a revoke during a replacement was missed (Medium): fixed

`cloud/src/relay/room.ts:323–336` at `90f87fa`. R1's fix re-reads the room's auth epoch after the secret's look in
D1, so a revoke landing during that read refuses the connection (`revoke.test.ts` › *refuses a connection whose
check was overtaken by a revoke*, which holds). But a connection that replaces an open one then awaits a second D1
call, the "replaced" audit note (`:327`), before it closes the older sockets it listed at `:325` and accepts
itself (`:336`). D1 is I/O, so the Durable Object takes other events meanwhile: a revoke landing there sent away
only the older socket, and the new connection was accepted (101) and stayed, its secret already gone from D1 and
the console reading "Revoked". The same for a forget. A stolen secret reconnecting in a loop (two a second under
the relay's limit) could now and then survive a revoke, unseen; revoke is the owner's one answer to a lost tablet. Two replacing connections in that window also both stayed open, each having closed only the sockets it
listed before its write.

- **Fix** (`14cf525`): the epoch is looked at again after the note, with no await from there to the accept, and
  every tablet socket open at that moment is closed, one accepted meanwhile included: the newest stays. A revoke
  that lands after the accept closes the new socket, as before. And the online flag: the connection's
  `online = 1` and a status's write now need the piano to hold a secret, so a write arriving after a revoke's
  `online = 0` no longer marks it online (`:352`, `:441`).
- **Tests** (`revoke.test.ts`, holding the "replaced" note in D1 through a proxy): a revoke and a forget landing
  there → 503, no socket, then 401 (both were 101); two replacing connections → one left open (both were); a late
  status of a piano without a secret leaves it offline. They failed before the fix (`…/audit3/cloud-race-before.txt`)
  and pass five runs in five. `tablet-auth.test.ts`: a wrong secret's 401 leaves the connected tablet connected.

### C3: the relay passed a tablet's headers by a deny-list (Medium): fixed

`cloud/src/relay/room.ts:986–1018` at `90f87fa` (`responseHeaders`). Every piano's panel lives on the relay's one
origin, `https://<relay>/p/<id>/…`; only the cookies' `Path` separates them. The relay dropped a tablet's
hop-by-hop, CORS, `x-relay-*` and `cf-*` headers and passed everything else. Among them `Service-Worker-Allowed`:
an answer under `/p/<id>/` carrying it could register a service worker for the whole origin, which would then see
every piano's panel in that browser (pages, sessions, every PIN typed there) for as long as it stayed registered;
`Clear-Site-Data` could clear the origin's cookies and storage; `Refresh`, `Link`, `Location`,
`Content-Disposition` and the reporting headers are no part of the panel. Only something holding a piano's secret
answers under its path, so this widened what one stolen secret or one compromised tablet could reach: from its
own piano to every piano.

- **Fix** (`73026c6`): `RESPONSE_HEADERS`, an allow-list of exactly what the app's `RelayedResponse` sends:
  `Content-Type`, `Cache-Control`, `Set-Cookie` (still only under the piano's prefix, no `Domain`), `Retry-After`,
  `Allow`, and the five security headers. `Content-Encoding` no longer passes (the app never sends it).
- **Test:** `forward.test.ts` › *passes on only the headers the app's panel sends* (fourteen others, all passed
  before, `…/audit3/cloud-headers-before.txt`). Through the real relay the panel's own answers are unchanged
  (`…/audit3/relay-checks-audit3.txt`). What stays is the shared origin itself (*Residuals*).

### C4: the console's library.load skipped the licence sheet (Low): fixed

`web/relay/RelayCommands.kt:143–154` at `90f87fa`: "on a tablet with none, the whole pack, without the licence
sheet: the owner's command". The tablet shows the licence sheet (MAESTRO's CC BY-NC-SA 4.0, the credits, "for
non-commercial use") before any load while no pack is loaded; the console's command reached the pack's load
directly, so 1,726 pieces under a non-commercial licence could arrive on a school tablet with no one there having
seen it.

- **Fix** (`3ddfe5b`): `RelayCommands.libraryLoad(online, loaded, state, load)`; with no pack loaded it starts
  nothing and answers "Steven's library loads the first time on the tablet, where its licence is shown. After that,
  the console can bring its updates." (a load under way is still said first). Updates from the console are as
  before.
- **Test:** `LibraryPackTest` › *the console's library load starts the + sheet's load, and says why when it can't*
  (rewritten: on a fresh tablet nothing starts; after the tablet's own first load, the console's update as before).
- **Evidence** (`…/audit3/console-checks-audit3.txt`, the local console): on the fresh tablet `{"ok":false,
  "message":"Steven's library loads the first time on the tablet, …"}`; after the tablet's own load behind its
  licence sheet (`…/audit3/shots/14-licence-sheet.png`), the console's command `{"ok":true,"message":"Loading
  Steven's library."}` and the status `pieces 3, pack 2`.

### C5: the tablet's address in every status; About silent (Low): fixed

`web/relay/RelayStatus.kt:71` at `90f87fa`: every report's `panel` carried `host`, the panel's Tailscale (or
Wi-Fi) address and port, which the relay kept in D1's `status_json` (`protocol.ts:242`) and the console never
showed or used. README's *What the relay sees* did not name it, and About, which should say what the relay sees
and that it is off by default, said nothing of the cloud.

- **Fix** (`91758d3`): `panel` is `{web}` alone in the app's report and in what the relay keeps
  (`sanitizeStatus`), so no tablet's address reaches D1 whatever a tablet sends. About ends with `CloudCopy.ABOUT`:
  "Remote access over the internet is off unless you turn it on. Then your own relay carries the panel's pages and
  requests, and every 30 s the app's and the piano's versions, whether the piano is connected, what plays, the
  guests' switches, whether Web control is on, the library's size and the channels' names. Never a device
  identifier." README › Cloud (*What the relay sees*), README › Security, `cloud/README.md` and DESIGN.md now list
  exactly that.
- **Tests:** `RelayStatusTest` (panel holds `web` alone), `CloudCopyTest` (About names what the status carries),
  `hygiene.test.ts` › *keeps nothing of a browser's visit in D1* (it failed on the tablet's address before).
- **Evidence:** the console's live status `"panel": {"web": true}`; About on the emulator
  (`…/audit3/shots/11-app-group.png`).

### C6: a code could re-key the piano it named (Low): fixed

`cloud/src/relay/enrol.ts:50–72` at `90f87fa`: the claim set a new secret on whatever piano a valid code named and
cleared its revoke. The console makes every code with a new piano, so today no code names an enrolled one; the
brief's "a code cannot re-enrol an existing piano" held only by that construction, not in the claim.

- **Fix** (`dfb81ea`): the first statement claims a piano never enrolled (`enrolled_at` and `secret_hash` still
  NULL); the audit note and the code's use follow that very write (the piano now holds this request's hash). The
  same three statements run for every outcome, so the 404's cost is unchanged (the timing test's shapes and
  medians).
- **Tests:** `enrol.test.ts` › *never re-keys a piano that was enrolled already, revoked or not* (200 and a new
  secret before); › *makes codes of 8 symbols from the 32 (40 bits), each symbol as likely as the others*.

### C7: the subprotocol check's close was not the end (Low): fixed

`web/relay/RelayClient.kt:279–303` at `90f87fa`: a 101 without `steven-piano-relay-1` was closed (1002), but OkHttp
reads on until the other side's close, and what came meanwhile was acted on: a hello (Connected), a request
(served), a command (run). The relay's host is the person's own, over TLS, so this mattered only for a host that
answers 101 and isn't the relay.

- **Fix** (`bf0e10b`): `Connection.refused` is set before the close; both `onMessage` overloads return while it is.
- **Test:** `RelayClientTest` › *a relay that doesn't answer in the subprotocol is closed, and nothing it sends
  meanwhile is read* (`FakeRelay.protocolAnswer`, `greeting`; the command was run before,
  `…/audit3/app-subprotocol-before.log`).

### The twelve points, verified

1. **Relay auth and takeover.** The bearer is read from `Authorization` alone (`parseBearer`, the exact
   `Bearer <12 base32>.<43 base64url>` form; 401 otherwise, `tablet-auth.test.ts`), sent by the tablet in that
   header alone (`RelayClientTest`). The room compares SHA-256 hex strings over every character
   (`constantTimeEqual`); the secret itself is never compared or stored. A second connection with the secret takes
   over (4409), and after C2 only the newest stays; one without it is 401 and leaves the connected tablet alone
   (new test). Revoke mid-handshake: R1's case holds; the replacement's window was C2, fixed. Limits:
   `limits.test.ts` (120 tablet connections a minute per address, 120 panel requests, 10 PIN tries), enrolment's
   5 a minute (`enrol.test.ts`). Through the real relay: `…/audit3/relay-checks-audit3.txt`.
2. **Enrolment.** 8 symbols of 32 from `crypto.getRandomValues`, each byte's low five bits (256 is a multiple of
   32): 40 bits, uniform (new test). 15 minutes, single use (the claim is one D1 batch), 5 a minute per address,
   the same 404 after the same three statements for an unknown, used, expired or malformed code (`enrol.test.ts`,
   with medians within 25 ms). A code never re-enrols an existing piano: C6. `POST /api/enrol-codes` needs Access's
   token for this application, the console's header and its origin; nothing is made otherwise (new test in
   `console-access.test.ts`).
3. **Secrets on the tablet.** AES-256-GCM under an AndroidKeyStore key that never leaves the Keystore
   (`KeystoreSealer`), a fresh IV each seal, the tag checked; DataStore holds `v1:` and the ciphertext; a text that
   doesn't open reads as not enrolled (`SealedSecretTest`, `SettingsRepositoryTest`). So a copied DataStore file
   opens nothing without that device's Keystore: verified by the design and the tests, not by an extraction on the
   emulator (*Residuals*). Never logged: the debug build's own log through enrolment, connection and two console
   commands holds no bearer, secret, cookie, PIN or piano id (`…/audit3/logcat-hygiene-audit3.txt`); release builds
   strip more. Never in diagnostics: the export after enrolment (`…/audit3/diagnostics-export-audit3.txt`): no id, no
   sealed or plain secret, no token, no PIN; `settings.txt` says `cloudEnabled`, `cloudHost`, `cloudEnrolled`. Never
   in the panel's state: it carries the public link (the id) and no secret. Rotation is two-phase (`rotate.test.ts`,
   `RelayClientTest`). Forget: on the emulator the switch off, "Enrol this tablet first", the room offline, the
   trail's line (`…/audit3/shots/19-forgotten.png`); the text and the key go (`SealedSecretTest`).
4. **CSRF over HTTPS.** Cookies `HttpOnly; SameSite=Strict; Path=/p/<id>/; Secure` over HTTPS (`WebServerRelayTest`;
   over the local relay's plain HTTP without `Secure`, by design); no `Domain`, and the relay drops a cookie that
   would leave the piano's path (`forward.test.ts`). Every change needs `X-Steven-Piano: 1` and, when sent, the
   relay's exact origin; the tablet's LAN origin is refused too. No CORS: none in the app's or the cloud's sources
   (grep), the relay and the console send none, a preflight is 405. The console's page sends the header on every
   call (`console.js › call`). Clients' `x-relay-*` never reach the room (the Worker builds the room's headers
   itself) and a tablet's are dropped (`forward.test.ts`; through the relay: `…/audit3/relay-checks-audit3.txt`).
   Another piano's page cannot read this piano's cookies (`HttpOnly`, the path): but it shares the origin, so a
   script running under one piano's path could use the owner's open session on another's: *Residuals*; C3 removed
   the ways an answer could reach past its own path.
5. **Uploads over the relay.** 100 MB at the Worker before a byte goes on (413), a body without a length or chunked
   411 (`body.test.ts`; 411 through the local relay); the tablet's 8 MB / 64 MB / 200 MB caps on the declared
   length before reading, the relayed request meeting the same routes (`WebServerRelayTest`'s 64 MB + 1 zip, and
   `WebServerTest`'s matrix of those routes). The Worker passes the stream; the room holds at most one 64 KB
   chunk of a request and never sends past the tablet's credit (`body.test.ts`: 64 MB under a 1 MB window, never
   overdrawn); the tablet's pipe holds at most the window (`BodyPipeTest`). An answer may sit unread in the room up
   to 8 MB per request (then 502). A stalled body: 408 at the relay, 30 s on the tablet. A second upload: 409 at the
   relay and on the tablet. Malformed traffic: `malformed.test.ts` (the room) and `RelayProtocolTest`,
   `RelayClientTest` (the tablet).
6. **Console command trust.** Commands go only down the tablet's own authenticated socket (`PianoRoom.command`); the
   relay Worker never calls the room's methods and only the console is given its binding besides; the console checks
   Access's RS256 token against the team's keys, the audience, the issuer and the dates (`console-access.test.ts`,
   eleven bad tokens); `DEV_BYPASS` is in neither deployed config and only in `wrangler dev`'s script, and even set it
   lets in only a request to localhost (`hygiene.test.ts`, `console-access.test.ts`). The tablet's allow-list
   refuses anything else (`RelayCommandsTest`; through the console: "reboot" refused). `library.load`: C4.
7. **D1 hygiene.** Every statement binds its values; the only text spliced into SQL is three constants (`valid`,
   `claimed`, `PUBLIC_COLUMNS`) (`…/audit3/greps-audit3.txt`). The audit log's actor is the Access email for the
   console's actions and "tablet" for the tablet's (`cmd.test.ts`, `revoke.test.ts`; the local console's
   `dev@localhost`); a guest's page and request, a PIN try and a panel's socket leave no row and no trace
   (`hygiene.test.ts`). 90 days (`prune.test.ts`). Only hashes of secrets, never returned by the console
   (`PUBLIC_COLUMNS`).
8. **The tablet's listeners.** `allowedHosts` unchanged: a listener refuses the relay's host and origin, the relay's
   edge refuses the LAN's (`WebServerRelayTest`); on the emulator the listeners bound the Wi-Fi address and the debug
   loopback only (`…/audit3/listeners-audit3.txt`). Guests over the relay exist only while Guests can request is on (404 otherwise,
   through the local relay too). The relay reaches the panel's routes and nothing more (the whole table compared in
   `WebServerRelayTest`); a kiosk setting through the panel's settings route is 400 "Unknown field kioskEnabled."
9. **Privacy statement.** C5: README › Cloud and Security, `cloud/README.md` and About now say exactly what the relay
   sees, and that it is off by default; the piano's id is kept out of every export (`DiagnosticsExporterTest`, and the
   export after enrolment).
10. **The library pack.** Manifest: 64 KB read, 4 KB parsed, every field checked (`LibraryManifestTest`), fetched
    from this repository's `main` over TLS (`UpdateSource.allowsLibraryManifest`, exact; the zip's address a `.zip`
    asset of the release `library` alone, `UpdateSourceTest`). Zip: the manifest's size and SHA-256 and 200 MiB,
    hashed as it arrives, renamed into place only on a match. On the emulator, from a stand-in for the release
    (`LibraryOverride`): a zip with one byte changed → "The download didn't match the library; try again.", one cut
    off after 1,000 bytes → "The download stopped; try again.", each leaving `cache/library` empty and nothing
    recorded, the licence sheet shown again before each retry; the good one → "Imported 2 pieces.",
    `offered-v1.txt` (shots 13–17). The importer's caps apply (`LocalZipTest`). Never deletes (`LibraryPackTest`).
    `LibraryService` holds no wake lock (so none can leak); every step is bounded by bytes and by 30 s a read; its
    `onTimeout` is written, not exercised at target 34 (*Residuals*). The licence sheet before the first load: C4.
11. **Regression sweep.** The whole app suite passes: `WebServerTest`'s 401/403/411/413/415 matrix over the panel's
    routes, `UpdateSourceTest`'s firmware, model and library allow-lists, `PinGuardTest`'s kiosk PIN, Studio's caps,
    `GlassContainersTest`. `check` passes: lint 0 errors, 30 warnings (as at the merge), both variants' ONNX Runtime
    checks ("runtime onnxruntime-android:1.28.0", no provider). Greps: no `0.0.0.0`, no `Access-Control` in the app,
    `okhttp3`/`okio` only in `web/relay/RelayClient.kt`, no `Color(0x` outside the theme, no `Modifier.blur`.
12. **Cloud tests and types.** `npm test` 79 passed (18 files), `npx tsc --noEmit` clean. `npm audit` reports 5 high
    advisories, all in development tools (`sharp` and `undici` under `@cloudflare/vitest-pool-workers`' own
    Miniflare and Wrangler); `npm audit --omit=dev` finds none: nothing of them is deployed (*Residuals*).

### Residuals (stated honestly)

- **Every piano shares the relay's origin.** Cookies are `HttpOnly` and under each piano's path, so no page reads
  another piano's cookies; but a script running under one piano's path is same-origin with every other piano's panel
  and could use the owner's session there while it is open in that browser. Only something holding a piano's secret
  can answer under its path (the owner's own tablets), and the panel's pages build their DOM as text under a policy
  with no inline script; C3 closed the ways an answer reached past its own path. Separate origins need a domain of
  the owner's with one name per piano (not possible on `workers.dev`). Revoke a lost tablet at once.
- **The relay's PIN gate can be held shut from the internet** (C1's trade-off), up to an hour at a time; open
  sessions, the tablet and the tailnet are unaffected. The guards' counts and the sessions live in the app's memory:
  a restart signs everyone out and gives the free tries back (ten before the relay's gate).
- **Plain HTTP on the LAN**, as before (1.5.1): over Wi-Fi with Panel on Wi-Fi too the PIN crosses in the clear.
- **No certificate pinning**: the platform's trust store and OkHttp's hostname check; a CA the platform trusts could
  stand in for the relay's host (Cloudflare rotates its certificates, so a pin would break the link).
- **A stolen secret is a stolen tablet**: whoever holds it can be the piano on the relay (and receive the PINs typed
  into its panel) until revoked; after C2 a revoke holds. That a copied DataStore file opens nothing rests on the
  Keystore's design and the tests; it was not demonstrated by copying one off a device.
- **Rotation's one edge**: a tablet that keeps a new secret and loses the connection before its acknowledgement
  reaches the relay, then stays offline past the ten minutes, is refused and asks to be enrolled again.
- **The console trusts Access's policy for who**: any identity the policy admits is the owner. Keep the policy to the
  owner's email (`cloud/README.md`, step 8).
- **The Free plan's daily quotas**: the relay is a public Worker; a flood of requests from anywhere can use up the
  day's requests (100,000) and stop the relay for everyone until the day turns; the rate limits count against it too.
  Workers Paid lifts it.
- **Uploads**: one at a time per edge (the relay's room and the relay's server, each listener its own), so an upload
  through the relay can run beside one over the tailnet, as two listeners' could. An answer can wait unread in the
  room up to 8 MB per request, 8 requests at once.
- **`LibraryService` has no overall deadline** and no wake lock: each step is bounded (bytes, 30 s a read; GitHub
  alone serves the pack), and Android 15's data-sync limit (`onTimeout`) is written for, not exercised at target 34;
  with the screen off and the tablet off its charger, Doze may slow a load.
- **Development advisories**: `npm audit`'s five high advisories are in the test and dev tools (`sharp`, `undici`
  under `@cloudflare/vitest-pool-workers`), never deployed; fixing them needs a breaking downgrade of the test pool.
- **Not on Cloudflare or on the school tablet yet**: everything here ran under `wrangler dev` and on the emulator;
  Cloudflare's edge (its `CF-Connecting-IP`, its body limit, Access itself) and Doze with the screen off are still to
  be seen on the real deployment.

### What the owner must do

Nothing new beyond the cloud's own steps (`cloud/README.md`): keep Cloudflare Access's policy to your own email, the
account's sign-in strong (two-factor), and revoke a lost tablet in the console at once. Load Steven's library on a new
tablet from the tablet itself (its licence sheet first); the console brings the updates after that. If someone keeps
the relay's sign-in shut with wrong PINs, use the tailnet or the tablet; a browser already signed in keeps working.
