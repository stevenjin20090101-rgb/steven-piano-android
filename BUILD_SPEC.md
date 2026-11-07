<!-- =====================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano — engineering spec

Start with `docs/CURRENT.md`. This file keeps the piano's MIDI contract and the latest releases; each release adds its
section at the end. Everything older is in `docs/archive/BUILD_SPEC-history.md`, word for word: a citation in the code such
as `BUILD_SPEC › v1.7 — M23` is a heading there. Search it, don't read it whole. `DESIGN.md` wins on anything visual.

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

# v1.40 — M78: the sweep only after a fault, the cool-down, the 80 W leeway

Brief `~/.claude/plans/m78-fault-cooldown-brief.md` plus the coordinator's 80 W leeway; worktree `android-wt-m78`, branch
`m78-cooldown`, from `fb97731`. No version bump, no signing, no emulator, no firmware change. **`SafetyRecord.fault`** (key
`fault`; absent: `pending` or the coil lock, so a 1.39 trip's check keeps its sweep), set by `require()` and the `CoilOn`
outcome, cleared by a pass unless a fault came while it ran (`faultDuring`); `SafetyCheck.start(quick = !fault)` →
`CheckState.quick` (no SWEEP line); after a quick pass `SafetyHost.powerLook()` (`RailKeeper.quickLook`: `!i2cfails`
against this rail-up's, two fresh adverts 80 W above `BoardWatch.idle`; a trip is `cutAtOnce`); `LastCheck.quick`
(`lastQuick`). **The cool-down**: `RailKeeper(cool: CoolStore?, wall)`, `PlugStore` key `coolUntil` (wall ms, kept by
`pair`/`forget`), `COOL_MS` 25 min begun by `faulted()` (every former `checkDue` site) and by `cutNow`; while cooling `put`
refuses every want (logged once a reason), `wantedNow`/`holding` are false (an "on" advert is CUT) and `arm()` refuses;
wants made before the store is read wait for it, and are ended if it is cooling; `skipCooling()`. `SafetyRunner(cooling)`:
`admit` refuses (`SafetyState.cooling`, the card's `Cooling` moment), `refusal()`, `runNow` → `Deferral.COOLING` (the host's
deferral too, so a check under way stops). `AppGraph.coolWords` (15 s tick) feeds `pianoAsleep` and Piano › Power plug (the
words, **Skip the cool-down** in a `GlassAlertDialog`, Turn the piano on disabled); `setPianoOnFromAfar` and `wakeForUpdate`
refuse. **80 W**: `PowerWatch.LEEWAY_WATTS` = `UNEXPLAINED_WATTS` = `BOARD_IDLE_WATTS` = `USUAL_ABOVE_WATTS`; `CAP_WATTS` 100
stays; the idle `CoilOn` only logs (once per 30 s run). **Tests:** `RailKeeperTest` (a fault's cool-down end to end, the
coil-on cut with a new keeper and Skip, the non-fault CUTs, the quick look; the leeway numbers), `SafetyRunnerTest` (quick
by hand, cooling then the sweep, a fault during a quick check; the sweep tests now start from a fault), `SafetyStoreTest`
(the fault's round-trip and migration), `PowerWatchTest` (80 W after the piece and at the usual idle).

## The release: 1.40 (versionCode 51)

M78 merged: the key sweep only after a fault CUT (other checks quick and silent), a 25-minute cool-down after a fault (persisted; Skip in Power plug behind the PIN), and an 80 W leeway on every power trip (the 100 W rail-up cap kept).

# v1.41 — a new version in place: INDEX.csv's `replaces`

From `2a6e7a1` (1.40), in the main checkout; the library side (writing the column) is another session's. No schema change
(Room v7 stays). **`IndexCsv.Row.replaces: Set<String>`**: the column found by its header name `replaces` only (none
without a header), its values apart by whitespace, each kept when it is 64 hex digits (lower-cased, in the field's
order), else left out; an index without it reads as before. **`Importer.prepare`**: a SHA-256 seen in the batch or found
(`findBySha`) is a duplicate as before, `replaces` unread; else the parse and `PieceFiles.write` (`<sha256>.mid`), then
`replace(row, file)`: `ImportStore.oldestBySha(replaces)` (`PieceSql.OLDEST_BY_SHA`: the lowest id; 500 hashes a query)
and `ImportStore.replaceFile(earlier.copy(sha256, fileName, sourceName, sizeBytes, durationMs, noteCount), collection)`:
`PieceSql.REPLACE_FILE`, an `UPDATE OR IGNORE` of those six columns by id (0 rows: another piece holds the SHA-256, or
the piece is gone), then the row's collection linked as `insertAll` links (`playlistId(name, imported = true)`;
`addPiece` IGNORE keeps a piece already in it where it is), in one transaction. Names, `collection`, `addedAt`,
`favorite`, `playCount`, `lastPlayedAt`, `genre`, `appleGenre`, artwork rows and links are not touched. On true the old
file is deleted, `Importer(replaced = …)` hears the id (`AppGraph`: `ArtworkRepository.forgetRollCard`, the card's file
and its bitmap, `BitmapCache.remove`) and the outcome is `Replaced` (its SHA-256 goes to the batch's playlist as any
piece's does, D2); with no earlier version held, or false, the file is a new piece as before. Both store methods have
defaults (none found, nothing written), as `artistGenres` has. `ImportProgress.replaced` (and in `piecesChanged`); the
log's ", N replaced" only when some were; `WebApi.import`'s `replaced` and the panel's "N replaced" (`library.js`);
`ImportCopy.summary` "Imported N pieces, replaced M" / "Replaced M pieces"; `LibraryPack`'s NOT_READ counts replaced
pieces as read. **Tests:** `IndexCsvTest` (+4: the column, an index without it, bad values, no header), `ImporterTest`
(+8: a replace keeps the id, favorite, plays, rename, genre and `addedAt` and swaps the SHA-256, the file, its size,
length and notes, the old file deleted, the row's playlist, the roll card's hook, the log; a hash the library lacks
brings a new piece; no column, a new piece beside the old; a known SHA-256 a duplicate whatever `replaces` names; the
first added of several; a new version that is no MIDI file fails and leaves the piece; a refused replace brings a new
piece; a zip's replaced piece in the zip's playlist), `PieceSqlTest` (new: both statements on Room's v7 export through
sqlite-jdbc: only the six columns written, the unique index, the first added), `ImportCopyTest` (+1), `WebApiTest` (the
`replaced` key).

## The release: 1.41 (versionCode 52)

A new version in place (INDEX.csv's `replaces`). 1.40 (versionCode 51, M78) was already out when this began, so it is 1.41.

## Fixed after the release: the corpus check

`midi/INDEX.csv` now lists the modern songs too (2,392 rows: the three classical collections' 1,727, then 665 under
`modern/`), so `LibraryFixtureTest -Pcorpus` failed on its count (2,392 rows made, 1,727 kept). `library_titles.csv`
stays the classical library, as the pack has it (`ChannelsTest`: 1,727 Classical, 0 Modern; `BuiltInPlaylistsTest`:
1,727): its test, now `library_titles_csv is what the importer makes of the classical collections`, makes only the rows
whose path is outside `modern/`, and those 1,727 match the fixture as it was, row by row. The modern songs are fan-made,
not for the pack, and change as the library does; `CorpusTest` still parses and lays out every file, theirs too. Tests
only (`LibraryFixtureTest.kt`, `LibraryFixture.kt`'s note), no new version. `./gradlew test`: 1,883 unit tests (12
skipped, the corpus's 7 among them), none failing; with `-Pcorpus` the same 1,883, 5 skipped (the sound render and
Studio's models), none failing: `CorpusTest` read 4,119 files (both copies of the classical library and the 665 modern
songs), and only the damaged Borodin's two copies warn.

## Fixed after the release: the library pack

`tools/publish_library.py` packed every INDEX.csv row, so its next `--upload` would have put the 665 modern songs,
fan-made transcriptions of copyrighted songs, into the public pack, which `midi/README.md` and `modern/README.md` keep
them out of and whose `licences` names only the three classical collections. `build()` now packs only the rows under
those three (`COLLECTIONS`, beside `LICENCES`) and counts the rest on the way ("left out the 665 rows under modern/").
These are the 1,727 rows `LibraryFixtureTest` makes (outside `modern/`), but named rather than excepted, so that a
folder added later stays out of a public upload until it is named with its licence. The docstring says so, README
(*Publishing a new version*) and DESIGN (*What is in it*) in a phrase each. A dry build (`--version 2`, a scratch
`--work` and `--manifest`, no `--upload`), three times, one hash: 1,726 pieces, 1,729 entries, 92,865,812 bytes of
MIDI, the Borodin copy left out, as version 1; no `modern/` path in the zip or its INDEX.csv; the pieces' SHA-256s
exactly the 1,726 of the classical files, none changed since August, so version 1's (the script as it was: 2,391
pieces, the modern songs among them). Nothing uploaded: `releases/library.json` stays version 1, and a version 2 now
would bring no new piece. Tools and docs only, no new version. `./gradlew test` (its unit tests run again, `--rerun`,
not from the cache): 1,883 unit tests (12 skipped, the corpus's 7 among them), none failing.

# M79: each school's name

From `7229090` (main: 1.40 and the unpublished 1.41), in the worktree `android-wt-m79`, branch `m79-school-name`; no
firmware change, no schema change (Room v7, D1's `0001_init.sql` stay). **The relay** (`cloud/`): `Hello.name`
(`shared/protocol.ts`; `NameMessage {t:'name', name}`, `MAX_PIANO_NAME` 60) from `db.ts › panelName` (`text(…, 60)`,
null for `DEFAULT_NAME` "New piano"); `PianoRoom.connectTablet` reads `name` with the secret (`SELECT secret_hash,
pending_secret_hash, pending_until, name`: the revoke test's hook keys on the prefix) and says it in the hello; the new
RPC `PianoRoom.renamed(pianoId, name)` sends `{t:'name'}` to a connected tablet, and the console's `PATCH
/api/pianos/<id>` calls it after D1's write, best effort (a relay that doesn't answer leaves the rename standing; the
next hello carries it). The room keeps the name (`keepName`: memory and storage `name`, written only on a change;
`forget` clears it) for `offline(api, method, name)`: `offlinePage(name)`, HTML-escaped, in the `<title>` ("… ·
offline") and the eyebrow; the style and its hash are unchanged, and `OFFLINE_PAGE` is the page without a name. Older
tablets ignore `hello.name` and drop `name` (an unknown `t`, one log line). **The short address**:
`cloud/src/short/index.ts` (only a default export) and `cloud/wrangler.short.jsonc` (`playerpiano-stevenjin`,
`workers_dev`, no bindings, vars `RELAY_URL` and `PIANO_ID` "", `ShortEnv`): every method and path → `302`
`${RELAY_URL origin}/p/${PIANO_ID}${pathname}${search}`, `Cache-Control: no-store`, `withSecurity` (HSTS, nosniff,
DENY, no-referrer, CORP); a `PIANO_ID` that isn't 12 base32 letters, or a `RELAY_URL` not https (http only for localhost): `503`
`text/plain` "This piano isn't set up yet." with `API_CSP`. `npm run deploy:short`; not deployed. **The app**:
`RelayProtocol.pianoName` (a string, trimmed, 1–60, no control characters; anything else null, leniently),
`RelayMessage.Hello.name` (default null; encoded only when set) and `RelayMessage.Name`; `RelayClient(named)` hands the
name from every hello and every `name` after the hello to one keeper per connection (a conflated channel, in order);
`WebService` keeps it with `SettingsRepository.setCloudPianoName(pianoId, name)`, written only while that id is the
enrolment; `setCloudEnrolment` and `forgetCloud` remove it; `PianoSettings.cloudPianoName` reads it only with a piano
id. `WebAddresses.name` (from `WebPanel`) is the state's `web.name` (null without). `CloudSection`: a `NoteLine`
(`CloudCopy.named`) under the link while enrolled with a name. **The panel**: `[data-piano-name]` on the rail's
`.piano-name` and the gate's and offline card's eyebrows; `app.js › applyName(state.web.name)` in `onState` sets them and
`document.title`, "Steven Piano" without one; `.piano-name` wraps (`overflow-wrap: anywhere`). `request.html` and the
poster are unchanged. **Tests:** cloud `name.test.ts` (new: the hello's name, null for New piano; a console rename
reaches the tablet at once, then the offline page's escaped title and eyebrow), `short.test.ts` (new: `/` and
`/request?x=1`, the headers; 503 for an empty, missing or malformed id and a bad relay; the config),
`forward.test.ts` (the hello's `name`), `entrypoints.test.ts` (the short Worker exports only its handler):
`npm test` 90 tests in 21 files (85 before), `npm run typecheck` clean; the app's `RelayProtocolTest` (+1, and the
round trip), `RelayClientTest` (+1: the hello's name, a rename, none, a name before the hello dropped), `SettingsRepositoryTest` (kept for its enrolment only, cleared by a new
enrolment and by Forget, a late hello after Forget keeps nothing), `WebApiTest` (`web.name`, null or the name):
`./gradlew testDebugUnitTest lintDebug assembleDebug`: 1,885 unit tests (12 skipped), none failing; lint's two
warnings are main's (a newer AGP and sqlite-jdbc).

## The release: 1.42 (versionCode 53)

M79 (each school's name on its panel, and a school's short address) on top of 1.41 (versionCode 52, a new version in
place), which was committed but never published, so 1.42 carries both. The cloud's relay and console need deploying
with it for the name to reach the tablets; the short Worker `playerpiano-stevenjin` once Fessenden's tablet is enrolled.

# v1.43 — album covers from the song zip: INDEX.csv's `cover`

From `67c5f77` (1.42), in the main checkout; the library side (the covers and the columns in the song zips) is the song
search's, `midi-work/scripts/covers.py` and `build_all_zip.py`. No schema change (Room v7 stays). **`IndexCsv.Row.cover:
String?`, `coverAlbum: String`**: the columns found by their header names `cover` and `cover_album` only (none without a
header); a cover is kept when it ends in .jpg, .jpeg or .png (any case), backslashes made slashes, and no segment is
"..", else null; the credit trimmed. **`OpenedSource.openFile`** (a parameter, `{ null }` by default) and
**`openCover(row)`**: `indexBase + row.cover` opened through it; `ZipSource.open(path)` finds an entry by
`IndexCsv.normalizePath` (either slash, any case; hidden entries are not listed), null when none; the three zip sources
pass it (`openSource`'s Zip, `openLocalZip`, `AppWebBackend`'s upload); a tree's and picked files' don't. **`Importer`**:
`run` keeps, by SHA-256, the first row naming a cover of each outcome with a SHA-256 (ready, duplicate, replaced); after
`flush()` and `link()`, `keepCovers`: per SHA-256, `findBySha` (none: skipped), `openCover` read through
`ImportLimits.readCapped(MAX_COVER_BYTES = 1 MB)` (null: logged "…: missing, or larger than 1 MB", the cover named in
debug builds only), then `covers(pieceId, image, coverAlbum)`, a new constructor hook (`{ false }` by default),
counted when true; an `IOException` or a `RuntimeException` is logged and the next goes on, cancellation is checked
between covers. `ImportProgress.covers` (not in `piecesChanged`: the covers come through the artwork rows); the log's
", N covers" only when some were; `WebApi.import`'s `covers` and the panel's "N covers added" (`library.js`);
`ImportCopy.summary`'s "N covers added." after what came in. **`AppGraph`** passes `covers = { id, image, album ->
artwork.keepZipCover(id, image, album) }`; **`ArtworkRepository.keepZipCover`** is `coverStore.keep(pieceId, image,
sourceUrl = null, sourceTitle = album.ifBlank { ArtworkEntity.FROM_SONG_ZIP })` == `Fetched.Saved`, the album-cover
lookup's own path: `BitmapCache.isImage`, then `keepCover(…, unlessCovered = true)` (nothing over a piece's
`imagePath`, nor where `ArtworkPolicy.chosenByHand`), the `cover:<id>` row written OK with that credit, so
`ArtworkPolicy` never looks the piece up again and `CoverRule` treats it as found (`coversChosenByHand` doesn't list it).
**Tests:** `ImporterTest` (+6: a new piece's cover with its credit and the log; a piece already there offered its cover
once whatever copies the zip holds, a refused one not counted; a replaced piece under its own id; a missing, a 1 MB + 1
and a .gif cover skipped with the pieces as before; no columns or no `openFile`, none offered; the column's parsing:
.jpg/.jpeg/.png only, no "..", the credit trimmed, none without a header), `ZipSourceTest` (+1: `open` by path in
either slash and any case, null when missing, and `openCover` from beside the index), `ImportCopyTest` (+2),
`WebApiTest` (the `covers` key).

`./gradlew test`: 1,892 unit tests (12 skipped), none failing; `assembleRelease` passes and the provenance verifies.

## The release: 1.43 (versionCode 54)

Album covers from the song zip (INDEX.csv's `cover`).

# v1.44 — the audit: less background traffic, and code a run can read

Nothing a person sees changes, but for a notification that no longer clashes.

- **The relay's retry.** `WebService`'s 30-second look no longer nudges `RelayClient`, whose 1 s × 2ⁿ wait never got past about
  30 s because of it. Only a real network change ends the wait now, so a relay that's down gets a try every 5 minutes at most.
- **The status.** `RelayClient.STATUS_EVERY_MS` goes from 30 s to 5 minutes; a change still goes within `STATUS_SETTLE_MS`. The
  status is what refreshes a piano's row, so the relay's `FRESH_MS` goes from 3 to 11 minutes. **Deploy the relay and the
  console before publishing**, or the console shows idle tablets as offline. A socket that closes still reads offline at once;
  one that vanishes silently now takes up to 11 minutes.
- **The panel's loops.** `WebSocketHub`'s progress and ping loops wait for a client instead of ticking for nobody. The keyboard's
  polls (`NavHost`, `KeysScreen`) stay: `publishHeld` runs on the port's thread, under the keyboard's lock, before notes fan out,
  and every way to main takes a monitor there. They only run with a MIDI keyboard chosen.
- **`AppGraph` by area.** 1,961 lines become 832 and six `*Wiring.kt` files, moved line for line. `start()` is unchanged, and
  forwarders keep every `graph.x` where it was. Each wiring class is built ahead of the graph's other fields, so it may hold only
  what is lazy or plain state: an eager field there must not read the graph.
- **`ForegroundJobService`.** The six job services share their scope, foreground start and stop, and channel set-up.
- **A clash.** `LibraryService`'s notification ID and open request were Studio's (9 and 11): one notification could replace the
  other, and a tap open the other's tab. They're 13 now.
- **The docs.** `docs/CURRENT.md` is the starting page; older sections moved word for word to `docs/archive/`. `tools/release.sh`
  sets the version everywhere, runs the gate, commits and re-signs.

Seen on the emulator (`steven_piano`, the fake plug): the app starts with no error from its process; a two-part send of 1,203
songs and 997 covers through the panel; the plug paired, the piano on, a piece played from the panel; `trunk dead` gave `CHECK: 0
of 7 boards` and `TrunkFault(answered=0, total=7, cut=true)` with the 24 V off.

## The release: 1.44 (versionCode 55)

Uses less of the shared cloud allowance, and waits properly before trying again when the cloud is down. The library's download notification no longer clashes with Studio's. Tidying inside the app, with nothing else changed for you.

# v1.45 — the cool-down: 10 minutes, Skip on the card

`RailKeeper.COOL_MS` is 10 minutes (Steven; 25 since v1.40); `PowerCopy.SKIP_COOL_TEXT` reads the minutes from it. The
safety card's `Cooling` (Play refused while cooling) has **Skip the cool-down**: `SafetyRunner.holdCooling()` calls off the
card's 8-second moment, then the kiosk gate, then the confirm; Skip is `graph.skipCooling()` (as Piano › Power plug's), and
any answer closes the card (`dismiss`). The check due stays due, and runs at the next Play.
