<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano

An Android app that plays Standard MIDI Files on Steven's self-playing acoustic
piano over Bluetooth LE MIDI. Pick a piece in the **Library**, watch it on
**Now playing** as a paper roll, falling notes or a staff while the piano plays
it, play the piano yourself on **Keys**, and on the **Piano** tab connect, adjust
the piano's own lighting and feel, and tune playback. Phones and tablets alike.
Sideloaded as an APK; no accounts, no analytics, and the network only for
composers' portraits and short notes from Wikipedia (see *Artwork and notes* below).
Made by Steven Jin. Version 1.1.

## What it does

- **Library**: search, collections, composers, favorites and recent pieces;
  import single files, a whole folder (with its `INDEX.csv`) or a zip.
- **Now playing**: the pianola paper roll (the default), Synthesia-style falling
  notes, or a **staff**: the notes on a grand staff (treble and bass, sharps
  only, a line trailing each head for its length), scrolling through a playhead
  in step with the roll. Not engraved sheet music: no beams, rests or ties.
  Tempo, scrubbing, previous and next.
- **Keys**: a playable keyboard over the piano's 84 keys, C1–B7, never taller
  than a real keyboard needs, along the bottom of the screen. Every touch is a
  Note On to the piano; chords with several fingers, a glissando by sliding.
  Where you touch a key sets how hard it plays: near the top softly (velocity
  24), near the bottom loudly (127); the last value shows as VELOCITY for a
  second. A latching **Sustain** holds the pedal. A phone shows two octaves at a
  time (drag the mini-map, or use the ‹ › octave buttons), a small tablet about
  four, a large tablet in landscape all 84 keys. Leaving the screen, putting the
  app in the background or losing the link lets go of every key and the pedal.
  Keys shares the piano's safety rules with playback (never re-strike a held
  key, no same-key strikes closer than 100 ms), so it can be played while a
  piece plays.
- **Piano settings**: the piano's own settings, from the Piano tab, over the
  same Bluetooth connection (on firmware with its Bluetooth console; older
  firmware just says it doesn't offer them yet). **Lighting**: the strip on or
  off, mode, brightness, palette, length, offset, scale, glow, fade and a *Test
  LED* that lights one key's LED to line the strip up. **Feel**: the Soft,
  Cinematic, Expressive and Snappy presets, full power, volume, velocity curve,
  the strike floors and ceiling (with a strike test), timing and release.
  **Pedal**: on, half-pedalling, up and down positions. **Diagnostics**: the
  power boards, I²C errors, uptime, the piano's own status report, *All keys
  off* and *Save now*. The app reads every value when it connects, sends a
  change as you make it and shows what the piano reports back; the piano saves
  your changes when you leave the tab. Bench commands (firing solenoids, resets,
  per-key force) stay at the piano's USB console.
- **Piano**: the connection, the piano settings above, the app's preferences
  (auto-connect, note display, wide layout, default tempo, transpose, velocity,
  folding, drum channel) and the About line.
- **Tablets and phones on their side**: a navigation rail on the left instead of
  the bottom bar. Now playing shows the staff and the notes together: stacked on
  a small tablet or a phone on its side, side by side on a large tablet on its
  side; **Piano › Wide layout** can show either alone. The Library and the Piano
  tab keep a comfortable 720 dp reading column in the middle of the screen.

The phone does all the timing: the piano plays each note the moment it arrives.
The app folds notes outside the piano's range (C1–B7) by octaves, never sends a
key faster than the solenoids can strike it, and silences the piano (pedal up,
then all notes off) whenever playback pauses, stops, seeks, loses the link, or
the app is swiped away.

## Artwork and notes: the app's only network use

Composers get their Wikipedia portrait and a two-sentence blurb; a piece's sheet
(*About this piece*) shows its Wikipedia notes when it has a page, otherwise its
composer's. The app talks to **two hosts and no others**: `en.wikipedia.org` (page
summaries and search) and `upload.wikimedia.org` (the portraits); a redirect anywhere
else is refused. What it sends is a page title or a search made from the library's own
composer names and piece titles ("Claude Debussy", "Clair de lune Claude Debussy"),
with the app's User-Agent. **Nothing about you is sent**: no account, no identifier, no
location, nothing about what you play.

- Composers are fetched after an import, when the app opens with composers not yet
  looked up, and from **Library › + › Fetch artwork and notes for every composer**
  (a notification shows the progress); a piece's notes when its sheet opens.
  **Piano › Fetch artwork automatically** (on) turns the automatic fetching off.
- One request at a time, at most four a second. Offline nothing is fetched and nothing
  is recorded; a failed fetch is retried a day later. Without a portrait a composer
  shows a mosaic of their pieces' first seconds drawn as a paper roll.
- **Piano › Artwork in black and white** shows the portraits in black and white.

## Build

Needs JDK 17 and the Android SDK (the first build downloads Gradle, the
libraries and Android platform 36, a few hundred MB).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd "Player Piano/android"
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # the JVM tests
./gradlew assembleRelease      # minified: app/build/outputs/apk/release/app-release.apk
```

If Gradle can't find the SDK, create `local.properties` with
`sdk.dir=/opt/homebrew/share/android-commandlinetools` (it is not committed).
`./gradlew testDebugUnitTest -Pcorpus` also parses every file under `../midi/`.
The settings table's test reads `../firmware/docs/BLE_SETTINGS.md` and is
skipped when the firmware folder isn't beside this one.

On an emulator, debug builds reach an emulated piano instead of Bluetooth, with
its console, so the Piano tab's settings work there (`adb logcat -s PianoLink`
shows every line both ways). `adb shell setprop debug.stevenpiano.console none`
before connecting stands in for firmware without the console.

Both builds are signed with this Mac's debug key, so either installs over the
other and keeps the library. A build from another computer has a different key:
uninstall first (which clears the library).

## Sideload

- **From the phone:** copy `app-debug.apk` to it (USB, Drive, mail), open it,
  and when Android asks, allow **Install unknown apps** for the app that opened
  it (Files or Chrome). Then **Install**.
- **With adb:** turn on USB debugging (Settings › About phone › tap *Build
  number* seven times, then Settings › System › Developer options), connect the
  phone and run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Bring in the music

Copy the library to the phone first, for example
`adb push "Player Piano/midi/ALL-SONGS.zip" /sdcard/Download/`, or copy the
whole `midi` folder over USB.

- **Library › + › Add folder**, then choose the `midi` folder: every MIDI file
  inside, subfolders included. Its `INDEX.csv` names the collections
  (MAESTRO, piano-midi.de, Mutopia) and the composers, so the 1,727 pieces arrive
  grouped. Copies of the same file are skipped.
- **Library › + › Add zip**, then `ALL-SONGS.zip`: the same 1,727 pieces, named
  from their file names (no collections).
- **Library › + › Add files** for a few pieces, or send `.mid` files to Steven
  Piano from any file manager (*Open with* or *Share*).

Imports continue with the screen off and show their progress in the Library and
in a notification. A full import takes about a minute.

## Connect to the piano

1. Power the piano. It advertises as **Steven Piano**.
2. If the phone was ever paired with "Steven Piano" in its Bluetooth settings,
   **Forget** that device first. The piano refuses encryption, so a stale bond
   stops the connection. The app itself never pairs.
3. On the **Piano** tab, tap **Connect** and allow **Nearby devices** (Android
   12 and newer). On Android 11 and older, allow **Location** and keep Location
   turned on: those versions only find Bluetooth devices with it on. The app
   never reads your location.
4. The dot turns red and the status reads **Connected**. With *Auto-connect on
   launch* on (the default), the app reconnects by itself next time, and after
   a dropped link it keeps trying in the background.

## Keep playing with the screen off

Playback runs in a foreground service with a media notification, which most
phones leave alone. Some (Samsung, Xiaomi, OnePlus, Huawei among them) still stop
background apps: set **Settings › Apps › Steven Piano › Battery** to
**Unrestricted** (or *Don't optimise*). Allow notifications when the app asks on
the first play, so the lock screen shows play and pause.

## Test it on the piano

- [ ] Sideload `app-debug.apk`. The app launches instantly and asks for Bluetooth
      permission only from the Piano tab, with a one-line reason.
- [ ] Connect: the dot goes live and the status reads Connected within a few
      seconds of the piano advertising.
- [ ] Play a quiet piano-midi.de piece: the roll scrolls, notes brighten crossing
      the bar, the keyboard strip inverts, the timers count with tabular figures,
      the piano plays in time. Tempo 50 % halves the rate live. Switch Note
      display to Falling notes and back while playing.
- [ ] Pause: the piano is silent within a second, no key left down, pedal up.
      Seek while playing: the same. Lock the phone: playback continues, and the
      notification plays and pauses.
- [ ] Swipe the app away mid-piece: the piano is silent (the service sent the
      stop sequence).
- [ ] Power-cycle the piano while connected: the app shows Not connected, then
      reconnects by itself within about 15 s of the piano advertising again, and
      the roll resumes from pause when Play is pressed.
- [ ] Import the whole `midi` folder (or the zip): 1,727 pieces appear grouped by
      collection and composer; search finds "Clair de lune"; a MAESTRO
      performance plays with its recorded dynamics (piano in variable-force mode).
- [ ] Keys: a tap plays the key; three fingers play a chord; sliding plays a
      glissando with each key released before the next; a touch near the top of a
      key is soft and near the bottom loud (VELOCITY confirms it). Sustain on holds
      the pedal, Sustain off lifts it. With a key and the sustain held, switch tab,
      press Home, or power the piano off: every key and the pedal come up.
- [ ] Keys while a piece plays: pressing a key the piece is holding does not
      re-strike it, and leaving Keys leaves the piece's notes sounding.
- [ ] On a tablet: the rail replaces the bottom bar; Now playing shows the staff
      over the roll upright and beside it on its side, in step with each other;
      Wide layout › Staff only and Notes only work while playing.
- [ ] Piano settings (firmware with the Bluetooth console): on connect the Piano
      tab fills in LIGHTING, FEEL, PEDAL and DIAGNOSTICS. Adjust a setting from
      the Piano tab and confirm the piano's serial `status` shows it (Brightness
      to 15 % prints `bright=40/255`). Choose Cinematic: the dependent settings
      change to what the piano reports. Leave the tab, power the piano off and
      on: the change is still there. *Test LED* lights the key's LED; Read
      status shows the piano's report. With older firmware the tab says it
      doesn't offer settings over Bluetooth yet, and playback works as before.
- [ ] "Open with" from a file manager that gives no access: the Library says it
      couldn't read the file (no crash); *Add files* imports it.

## Acknowledgements

- The staff view's clefs, sharps and note heads are drawn with **Bravura**, the
  SMuFL music font by Steinberg Media Technologies GmbH, bundled unmodified under
  the SIL Open Font License 1.1 (notice in `AUTHORS`, licence in
  `third_party/bravura/OFL.txt`).
- Composers' blurbs and pieces' notes are text from Wikipedia (CC BY-SA 4.0), each
  linked back to its article with *From Wikipedia*; portraits come from Wikimedia
  Commons.
- The music library draws on MAESTRO (Google Magenta, CC BY-NC-SA 4.0),
  piano-midi.de (Bernd Krüger, CC BY-SA) and the Mutopia Project (public
  domain); those files are not part of this repository.

## Authorship

MIT licensed with attribution preserved: see `LICENSE` and `AUTHORS`. Every
source file is covered by an Ed25519-signed manifest; `python3
provenance/verify.py` checks it (see `PROVENANCE.md`).
