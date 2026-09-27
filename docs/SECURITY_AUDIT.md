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
