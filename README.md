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
piano over Bluetooth LE MIDI. Pick a piece in the **Library** or a playlist,
watch it on **Now playing** as a paper roll, falling notes or pages of score
while the piano plays it, play the piano yourself on **Keys**, and on the
**Piano** tab connect, adjust the piano's own lighting and feel, and tune
playback. Phones and tablets alike. Sideloaded as an APK; no accounts, no
analytics, and the network only for composers' portraits and short notes from
Wikipedia (see *Artwork and notes* below), for the app's own updates from its
GitHub repository (see *Updates*), for the piano's firmware releases from the
firmware's (see *Updating the piano's firmware*) and for Studio's models and the
piano sound when you download them (see *Studio*, *Piano sound on the tablet*). With
**Web panel** on, it also
serves its own control panel to your phone or laptop over Tailscale, and a request
page to guests on the tablet's Wi-Fi (see *Web panel*). It never starts a piece by itself, and
keeps silent at the hours you set (see *Quiet times*), on the school tablet it can be locked to the app as a kiosk
(see *Kiosk*), **Studio**, a tab of its own, composes a piece from an idea you type, or turns a
piano recording into a piece, on the tablet itself (see *Studio*), and the tablet can play
the pieces itself with recordings of a real piano when the piano isn't there (see *Piano sound
on the tablet*). Made by Steven Jin. Version 1.19.

## What it does

- **Library**: search; **Playlists** and **Composers** as grids of tiles with
  art; favorites and recent pieces. Import single files, a whole folder (with
  its `INDEX.csv`, whose sets arrive as playlists) or a zip, or load **Steven's
  library** from GitHub in one tap, and its updates later. A zip or a folder
  arrives as a playlist of its own, and its artist folders name the artists
  (see *Importing*). A piece's menu plays it next, adds it to the queue or to a
  playlist, favorites, renames or deletes it, and opens *About this piece*.
- **Classical and Modern** (from 1.14): a switch at the top of the Library,
  **All · Classical · Modern**, and everything under it follows: pieces, search,
  playlists, composers (**Artists** under Modern), favorites, recent and the
  channels. It is remembered. Your uploads are Modern, Steven's library and the
  classical composers Classical; a piece's menu moves it (*Move to Modern*, *Move
  to Classical*), and a composer's or artist's tile moves every piece by them. In
  kiosk mode the switch is free and a move asks for the PIN.
- **Playlists**: a playlist is a page with its cover (your photo, else its first
  composer's portrait), **Shuffle**, and its pieces in the order you give them:
  drag a row by its handle, or use *Move up* and *Move down* in its menu. **Play**
  floats as a filled circle at the bottom of the list, wherever it is scrolled.
  Rename, change the photo or delete it from its menu. The playlists are listed
  **Newest first** (so an upload's playlist is on top) or by **Name**: the
  pop-up at the end of the PLAYLISTS row chooses, and the web panel follows.
- **Built-in playlists**: **Popular**, **Recognisable** and **Epic on piano** fill
  themselves from the library: the pieces everyone knows (Für Elise, the
  Moonlight, Clair de lune…), the concert warhorses (the Hungarian Rhapsody No. 2,
  the Heroic Polonaise, La Campanella…), and the 45 pieces of the Epic on piano
  set, however they came in. They follow your own playlists (sorted by Name,
  they come first) with a *BUILT IN* eyebrow, fill again after every import,
  hold only what the library has (each piece in at most four recordings), and
  can't be renamed, reordered or deleted; Change photo still works. A list that
  finds nothing is not shown. From Steven's `midi` folder they hold 17, 29 and 49 pieces.
- **Channels**: a row of wide cards above the playlists (**See all** shows them
  all as a grid): **Classical** and **Modern** (every piece of that genre; from 1.14), Calm, Epic,
  Recognisable, Popular, Baroque, Romantic, Impressionist, Nocturnes, Études and Everything, each
  faced with its four most frequent composers. A tap
  plays the channel without end: 25 of its pieces shuffled into Up next, ten
  more whenever fewer than five are left, and nothing again until the whole pool
  has played (the next round leaves out the last 20). Now playing and the panel read
  "CLAUDE DEBUSSY · CALM · CHANNEL", and the card "● PLAYING". A long press sets
  the channel's **volume** (70 % at first): the piano's own volume while the
  channel plays, when its firmware offers it (else the app's velocity), put back
  as it was when the channel ends. Playing
  anything else, or Stop, ends the channel. A channel of fewer than three pieces
  reads "Add more pieces" and does not play.
- **Up next, shuffle and repeat**: the queue glyph on Now playing opens *Up
  next*, to reorder, remove, clear, or skip to a piece. **Shuffle** and
  **Repeat** (off, all, one) sit at the two ends of the transport and are
  remembered; shuffle keeps the current piece playing, and turning it off brings
  the order back. The lock screen and the system media controls show the queue
  and both modes.
- **Artwork and notes**: composers' portraits and two-sentence blurbs, and each
  piece's notes (tap the title on Now playing), from Wikipedia (`en.wikipedia.org`,
  `upload.wikimedia.org`); each piece's album cover from Apple's catalogue
  (`itunes.apple.com`, `*.mzstatic.com`), or one you choose (see below). Pieces
  without a cover or a portrait get a card drawn from their own first seconds.
- **Now playing**: the pianola paper roll (the default), Synthesia-style falling
  notes, or the **score**, with tempo, scrubbing, previous and next. Over the
  paper roll the scrubber and the transport float on glass above the notes just
  played, never over the tracker bar or the keys.
- **Album colours** (1.15): behind Now playing, the tablet's now-playing panel,
  the resting screen and the web panel's Now playing, the playing piece's art
  colours drift slowly as soft shapes, as Apple Music does, while it plays (still
  when paused or with animations off), under a veil that keeps every word
  readable. Grey art (most composers' portraits) gives none, so it shows mostly
  for pieces with a colour cover. **Piano › Display › Album colours behind the
  player** or **Album colours** in Now playing's View menu turns it off; it is
  never shown in black and white or with *High contrast text*.
- **A pause before each piece**: two seconds of silence before every piece starts
  (**Piano › Playback › Pause before each piece**: off, or half seconds up to
  5 s). Meanwhile the play button already reads pause, the time stays at 0:00,
  *STARTING* shows under the composer, and the first notes travel down the roll to
  meet the tracker bar as the piano plays them. Resuming after a pause never
  waits; a seek plays at once; two pieces are about 2 s apart.
- **How a piece is played** (1.16): each piece is shaped as it loads, as a
  pianist would play it (**Piano › Playback › Expression**: Off, Light at
  first, or Full): the melody sings over its chord, phrases swell, chords roll,
  downbeats lean, never more than 25 ms from the file. **Dynamic range** spreads
  soft and loud apart; **Quietest note** lifts a note too soft to strike.
  Repeated keys keep the rhythm: each key lifts in time to strike again, and
  repeats faster than the piano can play keep their pulse, every second (or
  third) note played a touch louder (**Re-strike time**, Auto: the piano's own
  repeat period). With Full power on (Sound and touch) every note strikes at
  full strength: turn it off to hear dynamics. A change counts from the next
  piece.
- **The mini player**: on a phone, whatever is playing sits above the tab bar
  with its portrait, title and composer, play/pause and next; tap it for Now
  playing.
- **Resting screen** (**Piano › Display › Resting screen after a minute**, off at
  first): after a minute without a touch while a piece is loaded, the screen
  fades slowly into a display for passers-by: the piece's art large (the
  composer's portrait, else its roll card), its title, the composer and the
  channel, and a few lines about it (the piece's own Wikipedia notes, else the
  composer's), the art beside the words when the screen is wider than tall and
  above them when it is taller; the live dot at the foot and the byline, "Player
  piano" over "Made by Steven Jin", at the top right; on true black (or on the
  app's own ink or paper: **Background**), the album's colours drifting behind
  while the piece plays (1.15). **What it shows › Paper roll**
  keeps the earlier display instead: the portrait faint behind the title and the
  paper roll over its keyboard. A new piece cross-fades in. No controls: any
  touch, or Back, fades it away and brings the app back as it was, and does
  nothing else. The screen stays on and the system bars step aside while it
  shows.
- **Glass**: Apple's Liquid Glass across the app's functional layer, monochrome:
  the headers, the tab bar and the rail, the mini player, the transport, sheets,
  menus and dialogs, and the Keys pills are frosted surfaces the content scrolls
  under, with a soft fade where content meets a bar; the content itself (rows,
  cards, the roll, the score, the art) never is. At rest a header looks as it
  always has; the glass shows once the list is scrolled beneath it. The play
  control is a solid circle. With *High contrast text* on (Android's
  accessibility setting), or on Android 11 and older, the surfaces are solid and
  their hairlines stronger. The web panel's tab strip, section list, menus and
  PIN card follow it.
- **The score**: the piece as sheet music, in systems of bars on pages (two
  bars a system on a phone, three on a small tablet, four on a tablet on its
  side, and two pages side by side when the score has a tablet's width to
  itself). Every system opens with its clefs and the file's key signature; notes
  are spelled in the key (an E-flat piece reads in flats), with one accidental
  per pitch per bar and naturals where they are needed, and time signatures
  show at the start and wherever the metre changes. A cursor moves through the
  bar being played, sounding notes turn yellow, and pages turn by themselves so
  the cursor is always in sight. Swipe to look at other pages (**Follow** brings
  the score back to the music), and tap a bar to play from there. Files written
  in a sequencer (most of piano-midi.de and Mutopia) are engraved: whole, half,
  quarter, eighth and sixteenth notes with stems and dots; eighths and
  sixteenths beamed within each beat (in threes in 6/8, 9/8 and 12/8), with a
  partial beam for a lone sixteenth; rests wherever a staff falls silent for a
  sixteenth or more, and a whole rest for an empty bar; and ties where a note
  crosses a bar line or lasts a length no single note can write (a quarter tied
  to a sixteenth). A chord rolled a few ticks apart reads as one chord. Every
  piece shows its tempo at the start (♩ = 74, or ♩. = 67 in a compound metre)
  and again where a system starts more than a tenth faster or slower, and
  dynamics (pp to ff) under the treble staff where the loudness of a bar, read
  from the file's velocities, moves into a new band. Each hand has its staff (the
  right hand on the treble staff even below middle C; a note more than four ledger
  lines off its hand's staff goes to the other one), and the score carries the
  suggested fingering and the chord names described under *The waterfall format*.
  Honest limits: no voices within a hand, no tuplets, no grace notes, no pedal
  markings; values shorter than a sixteenth read as sixteenths; notes sit where they sound in
  time, so dense bars are tight on a phone; piano-midi.de writes its rubato as
  tempo changes, so its pieces carry several tempo marks. Recorded performances
  (MAESTRO) keep plain note heads with a line for each note's length, in bars
  counted at the file's own tempo, with their tempo and only the clear changes
  of dynamics (two bands or more). A file without a key signature is written in
  sharps. A piece of more than 100,000 notes is shown as performed (plain heads
  with a line for each note's length; no note values, beams, rests or ties),
  a score writes at most twice as many rests as its notes (never fewer than
  4,096) and ties at most 100,000 heads, and a score too large for the memory
  left says so in its panel instead of showing.
- **The waterfall format**: who plays what, with which finger, over which
  chord, on the roll, the falling notes, the keyboard strip and the score.
  - **Hands.** The right hand's notes are filled bars and the left hand's
    outlined ones (a hairline around a hollow bar); the keyboard strip
    outlines a key only the left hand is playing; on the score each hand has
    its staff. The hands come from the file: tracks named for them ("Piano
    right", "Piano left", "upper", "RH"…) decide; two unnamed tracks split by
    their pitch (the higher one is the right hand); a single track (every
    MAESTRO performance) is split by pitch as it goes, the notes sounding within
    a second divided where they fall into a lower and a higher group, and a
    beamed run kept in one hand.
  - **Suggested fingering**: a finger, 1 (thumb) to 5, for every note, as small
    figures inside the waterfall's bars at the end that reaches the line first
    (on bars tall and wide enough to hold them) and above the right hand's heads
    and below the left hand's on the score. It is a suggestion computed from
    the notes, not an editor's fingering: a cost model after Parncutt et al.
    (1997) weighs each finger pair's stretch, crossings (only the thumb passes
    under), the thumb on black keys, the same finger on a new key and moves of
    the hand, and picks the cheapest fingering for each hand's whole line. A C
    major scale comes out 1-2-3-1-2-3-4-5 in the right hand and 5-4-3-2-1-3-2-1
    in the left; transposing works it out again for the keys played.
  - **Chord names** where the harmony changes, above the score and at the
    waterfall's left edge where the chord begins (major, minor, dim, aug, sus2,
    sus4, 6, 7, maj7, m7, add9, maj9, with the bass after a slash when it is not
    the root: B♭/D), spelled in the key (a file without a key signature in the
    key its notes suggest). A beat is named only when more than one line sounds
    and the notes point to one chord; otherwise the name before holds.
  - **Now playing › View › Fingering** and **Chord names** (on) show or hide them; **Hand
    colours** (off) tints the left hand green and the right hand blue in the
    notes and on the keyboard strip only: the one place colour enters the app
    besides artwork, and red still means only that the piano is live.
  - Honest limits: the hands of a single-track file are a guess (with
    piano-midi.de's files that name both hands merged into one track, the split
    gives nine notes in ten the hand their tracks name); a hand that crosses
    over the other, or plays alone across a wide range, can be split wrongly.
    Fingering sees the notes, not the music: no phrasing, no finger substitution
    on held notes, ornaments and fast runs fingered as written notes, and chords
    of six notes or more leave some keys without a figure; in dense passages of
    a performance the score's figures crowd. Chord names come from beats of the
    file's own metre (half a second a beat for performances): a melody moving
    over a held chord can have its passing note named (Cadd9 for a D over C), a
    fast run can read as a ninth chord, a diminished seventh shows as one of its
    diminished triads, and a single line names nothing.
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
  piece plays. Turning the phone or tablet while keys are held never cuts them:
  the screen turns once the last finger lifts. A **MIDI keyboard** (Bluetooth, or
  USB by cable) lights the keys as it is played, and with **Live** on it plays the
  piano; **Record** keeps what was played as a piece (see *Keys: a keyboard and
  Live* and *Recording*).
- **Piano settings**: the piano's own settings, from the Piano tab, over the
  same Bluetooth connection (on firmware with its Bluetooth console; older
  firmware just says it doesn't offer them yet). **Sound and touch**: the Soft,
  Cinematic, Expressive and Snappy presets, *Full power* and *Piano volume*; under
  **Fine tuning** (folded until you open it) the velocity curve, the strike floors
  and ceiling (with a strike test), the key-force readings, timing, release and
  drive; then *Save to the piano now*. **Lights and screen**: the strip on or off,
  mode, brightness and palette; under **Strip set-up** its length, offset, scale,
  glow, fade and a *Test LED* that lights one key's LED to line the strip up; then
  the piano's own screen. **Pedal**: on, half-pedalling, up and down positions.
  **Firmware and status**: the piano's firmware version, the power boards, I²C
  errors, uptime and the piano's own status report (*Read status*). Most rows
  carry one line saying what they do. The app reads every value when it connects,
  sends a change as you make it and shows what the piano reports back; the piano
  saves your changes when you leave the tab. Bench commands (firing solenoids,
  resets, per-key force) stay at the piano's USB console.
- **Piano**: one page of groups under **Search settings**. Type a few letters of
  any setting ("tab vol", "dark", "wifi") and the list gives way to what matches,
  each with where it lives ("THE PIANO › SOUND AND TOUCH › FINE TUNING"); choose
  one and its page opens at that row, lit for a moment (a folded section opens
  itself); settings that moved elsewhere say where ("Fingering — Now playing ›
  View") and take you there. Then the connection card, then **INSTRUMENTS**
  (**Instrument**: Steven Piano or another MIDI piano, Connect or Disconnect,
  *Auto-connect on launch*, *All keys off*; **Keyboard**: the MIDI keyboard; see
  *Instruments*), **THE PIANO** (Sound and touch · Lights and screen · Pedal ·
  Firmware and status, the piano's settings above), **PLAYING** (**Playback**: the
  pause before each piece, default tempo, transpose, velocity, dynamic range, the
  quietest note, expression, the re-strike time, folding, drum
  channel; **Tablet sound**: the tablet's own piano sound and *Tablet volume*;
  **Quiet times**: the hours the piano stays silent, see *Quiet times*), **SHARING** (**Web panel**: the
  panel, its PIN, *Also on Wi-Fi* and the panel over the internet; **Guests**: the
  guests' switches and the poster) and **THIS TABLET** (**System**: the tablet's battery,
  temperature, memory and storage, the piano's controller and power boards, what the app is
  running, the day's chart and a few tools, read live while it is open; **Display**: appearance,
  artwork in black and white, album colours behind the player and the **resting
  screen**: after a minute, its background and what it shows; **Kiosk**;
  **Updates**: checking by itself, *Check
  for app updates* and a release on offer; **Library and artwork**: fetching
  artwork, for every composer too, and Steven's library; **Help and about**: Share
  diagnostics and the credits). Each row says in a few words what its page holds
  ("Piano volume 70%", "Reactive · 62%"); on a phone it opens its page over the list
  (back returns), on a tablet or a phone on its side the page opens beside it.
- **Web panel**: the piano from any browser on your phone or laptop, over
  Tailscale and behind a six-digit PIN: Now playing, Up next, the library,
  channels, requests, adding MIDI files and zips, and the piano's settings, all
  live (see *Web panel*).
- **Guests' requests**: a printed poster's QR code opens a request page on the
  tablet's Wi-Fi, where anyone can ask the piano for a piece from Popular,
  Recognisable or Epic on piano, or (from 1.14) the Modern list, one every five
  minutes; it joins Up next, or waits for your Approve, and never starts the piano by
  itself: it waits in Up next for someone's Play.
- **Updates**: the app looks for a newer release when it opens and once a day,
  and the Piano tab offers it under **UPDATE**: one tap downloads it, checks it
  and hands it to Android's installer. On the school tablet it installs without
  a tap (see *Updates* and *School tablet*).
- **Quiet times**: sections of days with blocks of time ("School days, Mon to Fri: 8:40–9:30,
  9:40–10:30 …") when the piano stays silent; staff can **Play anyway** (see *Quiet times*).
  Nothing starts a piece by itself: timed plays were removed in 1.20.
- **Studio** (its own tab since 1.12): type an idea ("calm and slow", "a bright waltz,
  2 minutes", "stormy, in D minor", a composer or a piece's name) and the tablet composes a
  piece in that manner from the library, showing what it understood, its progress and the
  notes as they are written; it also turns a piano recording (m4a, mp3, wav, flac, ogg…) into
  a piece, with how hard each note was played and the pedal. Every piece gets a cover drawn
  from its music and joins the playlist **Made in Studio**. Listen, then keep it or discard
  it. Each model (125 MB, 173 MB) downloads once, when you ask; nothing you record or type
  leaves the tablet (see *Studio*).
- **Piano sound on the tablet**: recordings of a real upright piano play what the
  app plays, on the tablet itself: while the piano isn't connected (the default), or
  always, with a volume on Now playing's speaker and under Piano › Tablet sound; the
  Keys tab sounds too. The sound (57 MB, CC0) downloads once (see *Piano sound on the
  tablet*).
- **The piano's firmware**: Piano › Firmware and status shows the version the
  piano runs, looks for a new signed release and sends it over Bluetooth; the
  piano checks the signature, restarts on it, and rolls back by itself if it
  fails to start (see *Updating the piano's firmware*). Needs firmware 2.0.0 on
  the piano, flashed over USB once.
- **Kiosk**: on the school tablet, with the app as device owner, the app becomes
  the home screen and nothing else can be reached; it wakes and restarts into
  the app, rests in display mode, and settings, disconnecting, imports and
  deletions ask for a PIN while playing, queueing and browsing stay free. A
  three-second hold on the byline and the PIN are the way out (see *Kiosk*).
- **Diagnostics**: **Piano › Help and about › Share diagnostics** sends a small zip
  of the app's own logs by any app you choose; after a crash, the Library offers
  it (see *Diagnostics*).
- **Tablets and phones on their side**: a navigation rail on the left instead of
  the bottom bar. Now playing shows the score and the notes together: stacked on
  a small tablet or a phone on its side, side by side on a large tablet on its
  side. **Drag the divider** between them to share the screen as you like: it rests
  at a third, a half and two thirds, drag a view well past its smallest size to hide
  it (the divider waits at the edge to bring it back), double-tap it to go back to
  the start (a third for the score stacked, half each side by side); each
  arrangement remembers its own split. A system holds two bars on a page under 480 dp
  wide, three under 560, else four. The **View** menu (the split-frame glyph in the
  header) offers the same without dragging (*Score and notes* · *Notes only* ·
  *Score only*; *Score only* on a tablet on its side opens two pages), the notes'
  style (*Paper roll* · *Falling notes*, and *Score* on a phone), and *Fingering*,
  *Chord names* and *Hand colours*. In kiosk mode the divider and the menu stay
  free. TalkBack reads the divider as "Sheet music and notes divider" and adjusts
  it like a slider; with a keyboard the arrows move it, Page keys jump between the
  stops, Home and End hide a view. The Library and the Piano tab keep a comfortable
  720 dp reading column in the middle of the screen. The Library is two panes: the
  list, and beside it a now-playing panel (the portrait, the title, a small live
  roll, the scrubber and the transport, Up next, and where the piece goes), so playing a piece keeps you in
  the Library; the Now playing tab is still there for the full score.

The phone does all the timing: the piano plays each note the moment it arrives.
The app folds notes outside the piano's range (C1–B7) by octaves, never sends a
key faster than the solenoids can strike it, and silences the piano (pedal up,
then all notes off) whenever playback pauses, stops, seeks, loses the link, or
the app is swiped away.

## Artwork and notes

Composers get their Wikipedia portrait and a two-sentence blurb; a piece's sheet
(*About this piece*) shows its Wikipedia notes when it has a page, otherwise its
composer's. Artists who are not among the app's classical composers (Coldplay, Ed
Sheeran, Hans Zimmer…) are looked up by their name, then as "*name* (band)",
"(singer)", "(musician)" and "(composer)": only a page about a band or a performer
is taken, so a namesake's photograph never appears. A joint name ("Lady Gaga &
Bradley Cooper") whose own page finds nothing is looked up by its first name; five
lookups an artist at most. An artist without a free photograph, or a company, keeps the
roll cards below. For all this the app talks to **two hosts**: `en.wikipedia.org` (page
summaries and search) and `upload.wikimedia.org` (the portraits), and, for album covers,
Apple's two (below); a redirect anywhere else is refused.
(The only other network use is the app's own updates and the piano's firmware
releases, below, Studio's models when you download them, *Studio*, and Steven's library, *Steven's library*.) What it sends is a page title or a search made from the library's own
composer names and piece titles ("Claude Debussy", "Clair de lune Claude Debussy"),
with the app's User-Agent. **Nothing about you is sent**: no account, no identifier, no
location, nothing about what you play.

- Composers are fetched after an import, when the app opens with composers not yet
  looked up, and from **Library › + › Fetch artwork and notes for every composer**
  (a notification shows the progress); a piece's notes when its sheet opens.
  **Piano › Library and artwork › Fetch artwork automatically** (on) turns the automatic fetching off; then a
  piece's sheet asks Wikipedia only when you tap **Fetch notes**.
- One request at a time, at most four a second. Offline nothing is fetched and nothing
  is recorded; a failed fetch is retried a day later. Without a portrait a composer
  shows a mosaic of their pieces' first seconds drawn as a paper roll, and a piece its
  own; a piece whose file can't be drawn shows its title's first letter.
- **Piano › Display › Artwork in black and white** shows the portraits in black and white.

**Album covers** (1.15): each Classical and Modern piece is looked up once in Apple's
catalogue (the iTunes Search API, `itunes.apple.com`, its images on `*.mzstatic.com`) by
its title and its artist, and a cover is kept only when both clearly match (every word of
the artist in the result's artist, or, for a composer, in its album or track; the title
equal, one inside the other, or 70 % of its longer words), else the portrait or the roll
card stays. What goes to Apple is the title, the artist's name and the device's two-letter
country; nothing about you. Searches go 3.5 s apart and images 1 s apart, after the
composers (Modern first, newest first; the piece playing first of all); Apple saying "too
many" stops them for an hour. The cover is the piece's own everywhere (rows, the sheet, Now
playing, the resting screen, playlists, the web panel), credited on the sheet ("Cover: album
· artist", linking to Apple Music) and in About. **Piano › Library and artwork › Album
covers** (on; off with *Fetch artwork automatically*) stops the lookups, and covers found
stay. A piece's menu › **Change cover** picks a photo instead (the kiosk PIN first): no
lookup replaces it, nor a cover Studio or a recording drew. On the web panel (1.18), a piece's
menu › **Find a cover…** searches Apple's catalogue by hand for the words you type (whatever the
switches, one search in 4 s) and makes the cover you pick the piece's own, or takes a wrong one
away; no lookup changes either.

## Updates

The app looks for a newer release when it opens (after its first screen is drawn) and
once a day while it stays open, when **Piano › Updates › Check for updates automatically** is on
(the default) and the tablet is online. **Check for app updates** (Piano › Updates), under the switch, asks at once
whatever the switch says, and says what it found ("Steven Piano is up to date.").

A check reads one small file from this repository on GitHub,
`https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/latest.json`:
the newest release's versionCode, name, notes, file address, SHA-256 and size. Nothing
about you or the tablet goes with it beyond what every HTTPS request carries (the app's
User-Agent and the tablet's IP address).

When the release is newer than the app, the Piano tab shows **UPDATE** under the
connection card, above its groups: "Steven Piano 1.5 is available", its notes, and **Update**. Update
downloads the file from the GitHub release (`github.com`, which hands it over from
`objects.githubusercontent.com` or `release-assets.githubusercontent.com`) with a
notification you can cancel ("Downloading Steven Piano 1.5"), then checks its size and
SHA-256 against the manifest. A file that doesn't match is deleted before anything else
happens to it ("The download didn't match the release; try again."). One that matches
goes to Android's installer: "Do you want to update this app?", **Update**, then
**Open**. The first time, Android asks you to allow Steven Piano to install apps; the
row says so ("Allow this app to install updates") with **Open settings**.

Why a wrong file cannot install:

- the manifest comes from this repository over HTTPS, and may name only a release asset
  of this repository (`https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/…`);
  anything else is refused before it is fetched, and so is every redirect off those four
  GitHub hosts;
- the file must match the manifest's SHA-256 (and size, at most 50 MB) before it is
  kept, and again just before it goes to the installer;
- Android installs an update only when it is signed with the same key as the app
  already installed, Steven Piano's release key, which never leaves this Mac. A file
  that matched a tampered manifest still could not replace the app.

The repository must be **public** for tablets to read the manifest without a login.
While it is private GitHub answers 404, and Check for app updates says "Couldn't reach the update
server."; nothing else happens. Failures are one line under the row, in words; Update
is the retry.

## School tablet: updates without a tap

When Steven Piano is the tablet's **device owner**, Update installs with no tap: the
download, then the app closes and opens again on the new version about a second later,
on the Piano tab, reading "Updated to 1.5" (Android adds its own notice, "Updated by
your admin"). The piano is silenced first, as for any stop. Device owner is used for
this one thing: the app asks for no policies, locks nothing and hides nothing, and the
tablet works as before, until kiosk mode is turned on (see *Kiosk*).

One-time setup, with a computer and a USB cable:

1. Start from a factory-fresh tablet (or reset it: Settings › System › Reset options ›
   Erase all data, which clears everything on it). Go through Android's setup **without
   adding a Google account**: Android refuses a device owner once any account is on
   the device. Accounts can be added afterwards.
2. Turn on USB debugging (see *Sideload*), connect the tablet and install the release:
   `adb install ../apk/steven-piano-1.19.apk`.
3. Make the app the device owner:

   ```bash
   adb shell dpm set-device-owner dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin
   # Success: Device owner set to package dev.stevenjin.stevenpiano
   ```

4. Import the music and connect the piano as usual. USB debugging can go off again.

What changes: updates install without Android's confirmation, and Android lists Steven
Piano under the device admin apps. While it is the owner Android **will not uninstall
it**, and `adb shell dpm remove-active-admin dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin`
is refused ("Attempt to remove non-test admin": Android allows that command only for
test builds). To give the role back, over adb:

```bash
adb shell setprop debug.stevenpiano.releaseowner yes
adb shell am force-stop dev.stevenjin.stevenpiano
adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity   # the app gives up the role as it starts
adb shell dpm list-owners                                        # "no owners"
adb shell "setprop debug.stevenpiano.releaseowner ''"            # quoted whole: adb drops a bare ""
```

Only adb can set that property; no app on the tablet can. Afterwards updates ask
again and the app can be uninstalled. A factory reset also removes the device owner,
with everything else.

## Build

Needs JDK 17 and the Android SDK (the first build downloads Gradle, the
libraries and Android platform 36, a few hundred MB).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd "Player Piano/android"
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # the JVM tests
./gradlew assembleRelease      # minified, release key: app/build/outputs/apk/release/app-release.apk
```

If Gradle can't find the SDK, create `local.properties` with
`sdk.dir=/opt/homebrew/share/android-commandlinetools` (it is not committed).
`./gradlew testDebugUnitTest -Pcorpus` also parses every file under `../midi/`.
The settings table's test reads `../firmware/docs/BLE_SETTINGS.md` and is
skipped when the firmware folder isn't beside this one.

On an emulator, debug builds reach an emulated piano instead of Bluetooth, with
its console, so the Piano tab's settings work there (`adb logcat -s PianoLink`
shows every line both ways). `adb shell setprop debug.stevenpiano.console none`
before connecting stands in for firmware without the console. `adb shell setprop
debug.stevenpiano.idlesecs 6` shortens display mode's minute to 6 s in a debug build
(read when the app starts: force-stop it after setting it).

The updater can be tried on an emulator without GitHub: serve a manifest and an APK
from this Mac, `adb shell setprop debug.stevenpiano.updateurl
http://10.0.2.2:8765/latest.json`, and start the app. Debug builds on an emulator only
honour it; that address is then the only one the updater reaches (plain HTTP allowed,
and only to 10.0.2.2). A copy that reads as newer than the one installed:
`./gradlew assembleDebug -PversionCodeOverride=30`. A debug-only crash for the crash
banner: `adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity --ez
dev.stevenjin.stevenpiano.EMULATOR_CRASH true`.

The debug build is signed with this Mac's debug key; the release build with
Steven Piano's own release key, which lives outside this repository (see
*Security*). Without `~/steven-piano-keystore.properties` the release build stops
with a message; it never falls back to the debug key. Android installs an update
only over a copy signed with the same key: to go from a debug build to the release
build (or to a build from another computer), uninstall first, which clears the
library. The school tablet runs the release build.

## Sideload

The school tablet and any piano that stays gets the release build,
`app-release.apk`; debug builds are debuggable and belong on test phones only.

- **From the phone:** copy `app-release.apk` to it (USB, Drive, mail), open it,
  and when Android asks, allow **Install unknown apps** for the app that opened
  it (Files or Chrome). Then **Install**.
- **With adb:** turn on USB debugging (Settings › About phone › tap *Build
  number* seven times, then Settings › System › Developer options), connect the
  phone and run `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Publishing a release

One command on this Mac, after the version is set and committed:

1. In `app/build.gradle.kts` raise `versionCode` by one and set `versionName` (and
   `Provenance.text`, the README and BUILD_SPEC); draft the release's entry at the end
   of `releases/history.json` (`"draft": true`, its version and notes: a test checks
   it); commit, then re-sign provenance
   (`~/.platformio/penv/bin/python3 provenance/sign.py && ~/.platformio/penv/bin/python3 provenance/verify.py`)
   and commit `provenance/`.
2. Run:

   ```bash
   tools/publish-release.sh 1.5 "Fixes for the school tablet."
   ```

It checks the tree (clean, on `main`, not behind GitHub, no release of that name yet)
and the authorship signature; builds the release APK with the key in
`~/steven-piano-keystore.properties`; checks that its versionName is the one given and
that it is signed with Steven Piano's key; copies it to `../apk/steven-piano-1.5.apk`;
pushes `main` and creates the GitHub release `v1.5` with the APK attached (`gh release
create`); and only then writes `releases/latest.json` (versionCode read from the APK,
the notes, the asset's address, SHA-256, size, minSdk), appends it to
`releases/history.json` in place of the drafted entry, commits and pushes. A tablet
never sees a manifest whose file is not there yet. Installed copies offer the release
within a day, or at once with Check for app updates. It needs the GitHub CLI signed in (`brew install gh`, `gh auth login`) and a
public repository.

## Bring in the music

The quickest way, from 1.10: **Load Steven's library** on the empty Library (or Library › +)
brings the whole library from GitHub, no copying needed; see *Steven's library* below.

Or copy the library to the phone first, for example
`adb push "Player Piano/midi/ALL-SONGS.zip" /sdcard/Download/`, or copy the
whole `midi` folder over USB.

- **Library › + › Add folder**, then choose the `midi` folder: every MIDI file
  inside, subfolders included. Its `INDEX.csv` puts them in playlists
  (MAESTRO, piano-midi.de, Mutopia) and names the composers, so the 1,727 pieces
  arrive grouped. Copies of the same file are skipped.
- **Library › + › Add zip**, then `ALL-SONGS.zip`: the same 1,727 pieces, named
  from their file names, in one playlist of their own, **ALL SONGS** (the zip's
  folder).
- **Library › + › Add files** for a few pieces, or send `.mid` files to Steven
  Piano from any file manager (*Open with* or *Share*); the app asks "Add 3 files to
  the library?" before it copies anything.

Imports continue with the screen off and show their progress in the Library and
in a notification. A full import takes about a minute.

### Importing: artists and playlists

From 1.10.1 an upload of your own music arrives with its artists and in a playlist:

- **Folders are artists.** A file in a folder inside the zip's or the folder's top
  folder takes that folder as its artist: `MIDI/Coldplay/Sparks.mid` is *Sparks* by
  Coldplay. A file named `Composer - Title` reads as before, but the other way round,
  `Title - Artist`, when only its right side is a known name (a classical composer
  the app knows, or an artist folder of the same upload): `Cornfield Chase - Hans
  Zimmer (version 2).mid` is *Cornfield Chase (version 2)* by Hans Zimmer. An
  `INDEX.csv` row still wins. What a Mac adds (`__MACOSX`, `._` files) is skipped.
- **Artists keep their whole name** (*Ed Sheeran*, *Twenty One Pilots*; *Louis
  Armstrong* apart from *Craig Armstrong*), and the app's classical composers keep
  theirs: a *Claude Debussy* folder joins Debussy.
- **A zip or a folder is a playlist**, named after its one top folder (`MIDI`), else
  after the zip or the folder itself (`Spring Recital.zip` is *Spring Recital*):
  every piece in it that `INDEX.csv` doesn't place, folder by folder and by title,
  pieces already in the library included (a blank artist is filled in). Newest
  first, it is on top of the playlists.
- **Uploads**: a `.mid` or `.midi` file sent on its own through the web panel goes
  into the playlist **Uploads**, made when first needed; rename or delete it as you
  like, and the next one makes it again. Files picked one by one on the tablet (**Add
  files**, *Open with*, *Share*) go into no playlist, as before.
- **What came in before 1.10.1**: the first start of 1.10.1, once, puts the pieces an
  earlier zip or folder left loose (in folders, in no playlist, with no `INDEX.csv`
  set) into a playlist named after the top folder they came in, filling in their
  artists and turning `Title - Artist` names the right way round. On the school
  tablet that is **MIDI**, 265 pieces.

## Steven's library

A new tablet can load Steven's library straight from GitHub (from 1.10):

1. **Load Steven's library**, beside **Add MIDI files** on the empty Library (or first in
   **Library › +**). Its line says what it is: "1,726 pieces · 61 MB · MAESTRO,
   piano-midi.de, Mutopia · for non-commercial use".
2. The first time, a sheet shows the three collections' credits and "For non-commercial
   use". **Load · 61 MB** starts it; **Not now** leaves it.
3. It downloads from this repository's GitHub release `library` (never bundled in the
   app), is checked against its SHA-256 before a byte of it is read, then imports as
   *Add folder* does: playlists by collection, composers named. The Library shows
   "Loading Steven's library · 23 of 61 MB", then "Imported 1,204 of 1,726"; a
   notification with **Cancel** keeps it going with the screen off.

- **Updates.** When Steven publishes a newer version, **Library › +** offers **Update the
  library · 2 new pieces**. An update brings only pieces no earlier version brought and
  never deletes anything: a piece you deleted stays deleted, and your own pieces are
  untouched. On an emptied Library, **Load Steven's library** brings the whole library
  back.
- **When the app asks GitHub**: one small file, `releases/library.json`, once a day while
  the app is open with **Check for updates** on, and when the empty Library or the + sheet
  shows (at most every 10 minutes). The 61 MB only when you tap Load or Update. Offline,
  it says so ("Loading Steven's library needs an internet connection.").
- **Kiosk mode**: loading and updating wait for the kiosk PIN, as adding music does.
- **From the console** (Steven Piano Cloud): **Load Steven's library** on a piano's page
  brings a newer pack's new pieces to that tablet, as the + sheet's Update does. The first
  load is always the tablet's own, after its licence sheet: on a tablet that has never loaded
  the pack the console's button says so ("Steven's library loads the first time on the tablet,
  where its licence is shown…") and starts nothing.
- **The licences.** **MAESTRO v3.0.0** (Google Magenta; Curtis Hawthorne et al., "Enabling
  Factorized Piano Music Modeling and Generation with the MAESTRO Dataset", ICLR 2019):
  CC BY-NC-SA 4.0, **non-commercial use only**. **piano-midi.de** (Bernd Krueger,
  www.piano-midi.de): CC BY-SA 3.0 Germany. **The Mutopia Project**: public domain. The pack
  carries the library's README with these credit lines, and MAESTRO's licence.

**Publishing a new version** (from the Mac, `gh` signed in): put the files in
`Player Piano/midi/` and their rows in its `INDEX.csv`, then
`python3 tools/publish_library.py --upload` (the next version; `--version N` to choose). It
builds `library-v<n>.zip` from the files the index lists (each file once, the index with a
`sha256` column added, the README and MAESTRO's licence beside them), checks it against the
app's limits, uploads it to the release `library` (never "Latest") and writes
`releases/library.json`. Commit and push that file: tablets offer the new version once it is
on `main`. Versions only go up.

**On the emulator**: `adb shell setprop debug.stevenpiano.libraryurl
http://10.0.2.2:8767/library.json`, before the app starts, points the pack's list at a server
on the Mac (`python3 -m http.server 8767` in a folder holding a `library.json`); the zip it
names may be on that server or the published one. `python3 tools/publish_library.py --midi
<dir> --version 2 --work <dir> --manifest <dir>/library.json --url-base
http://10.0.2.2:8767/` builds such a test pack without publishing it.

## Connect to the piano

1. Power the piano. While nothing is connected to it, it advertises as
   **Steven Piano** and its screen reads *BLE MIDI: advertising...*.
2. On the **Piano** tab, tap **Connect** and allow **Nearby devices** (Android
   12 and newer). On Android 11 and older, allow **Location** and keep Location
   turned on: those versions only find Bluetooth devices with it on. The app
   never reads your location.
3. The dot turns red and the status reads **Connected**; the piano's screen
   reads *BLE MIDI: CONNECTED*. With *Auto-connect on launch* on (the default),
   the app reconnects by itself next time, and after a dropped link it keeps
   trying in the background.
4. The app remembers this piano and connects only to it. If another device calls
   itself Steven Piano and the known one is not around, the Piano tab says so and
   offers **Connect to it**; the app never switches by itself.

Don't pair the piano in the tablet's Bluetooth settings: the app connects
without pairing, and a pairing stops it (below). The app itself never pairs.

### When it won't connect

The Piano tab says what went wrong, with the fix under the message.

- **"Can't find Steven Piano…"** The piano talks to one device at a time and
  stops advertising while one is connected, so nothing else can find it. An iPad
  or phone app, or a Mac's Audio MIDI Setup, that connected to it once
  reconnects by itself whenever it can, and hides the piano. If the piano's
  screen reads *BLE MIDI: CONNECTED*, disconnect that device (or turn its
  Bluetooth off) and tap **Retry**. Another app on this tablet holding the piano
  is no problem: Steven Piano connects alongside it.
- **"…also needs Location turned on."** Some tablets find nothing over Bluetooth
  while Location is off, even on Android 12 and newer. Tap **Open Location
  settings**, turn it on and come back: the app looks again.
- **"This device is paired with Steven Piano…"** The tablet was paired with the
  piano in its Bluetooth settings. The piano refuses pairing, so the leftover
  pairing stops every connection. Tap **Open Bluetooth settings**, open Steven
  Piano there (the gear beside it), tap **Forget** (or *Unpair*), then come back
  and tap **Retry**.
- **"Found Steven Piano but the connection failed (code N)."** Tap **Retry**; if
  it keeps failing, restart the piano (switch it off and on).
- **"Bluetooth scanning isn't available right now (code N)."** Turn Bluetooth
  off and on in the tablet's quick settings, then tap **Retry**.
- If Steven Piano never shows in the tablet's own Bluetooth settings among the
  devices available to pair (look only; don't tap it) while nothing else is
  connected to it, the piano's controller isn't advertising: restart the piano.

The first time, the piano's name can go missing on the way (some tablets drop
the part of its advertisement that carries it). The app then connects to the
nameless MIDI device it found and asks it its name, and keeps it only if it
answers Steven Piano.

### Send a log

The app writes each step of connecting to the tablet's log, and release builds
keep it. To see why a connection fails, connect the tablet by USB with USB
debugging on (see *Sideload*), then:

```bash
adb logcat -c    # start from an empty log
adb logcat -s PianoLink:W BluetoothGatt:V BluetoothLeScanner:V | tee piano-log.txt
```

Tap **Connect** (or **Retry**), wait for the Piano tab's message, stop with
Ctrl-C and send `piano-log.txt`. The `PianoLink` lines say what each scan saw
(every MIDI device's address, name, signal strength and whether it advertised
MIDI, once a scan), each step of the connection with Bluetooth's status codes,
and why it stopped. They hold Bluetooth addresses and device names only, nothing
from the library. A good first connection reads like this (addresses and
numbers vary):

```
Connect: no piano remembered yet
Scan started (filter: MIDI service 03B80E5A-EDE8-4B33-A751-6CE34EC4C700, mode: low latency), looking for any Steven Piano
Seen C8:2E:18:00:11:22 "Steven Piano", RSSI -58 dBm, MIDI service yes: connecting
connectGatt C8:2E:18:00:11:22, autoConnect=false
GATT connected: C8:2E:18:00:11:22, status 0 (success)
MTU 247 (asked for 255)
Services discovered: MIDI yes, console yes
Connected to Steven Piano (C8:2E:18:00:11:22), MTU 247, with its console
```

Without a computer, **Share diagnostics** (below) carries the same lines: the app
keeps the last 500 of them.

## Diagnostics

**Piano › Help and about › Share diagnostics** builds one small zip and opens Android's
share sheet, so it can go by mail, Drive, Bluetooth or anything else you choose. It
holds:

- `about.txt`: the app's version and build, the tablet's model and Android version,
  whether the app is the device owner, the updater's state, the piano link's state, the
  instrument and the keyboard (with Live's state, and why it is off when it switched itself
  off);
- `settings.txt`: the app's preferences (the remembered piano's Bluetooth address
  among them, and the keyboard's and MIDI piano's address or USB identity and name);
- `link.log`: the last 500 lines of the piano link's log, with their times (Bluetooth
  addresses, device names and status codes, as in *Send a log*), for each piece played how
  many notes went out and how late the latest was ("Timing: 3059 events, the latest 6 ms after
  its time, at 1:15.5"), and for each Studio transcription or composition how long it took and
  the memory it used ("Studio: transcribed 180.0 s of audio in 66.1 s …", "Studio: composed
  121.1 s of music in 5.2 s …"), no titles or file names;
- the app's last five crash reports, if any: the time, version, device, thread and
  stack trace, with any file address or web address in a message taken out.

Nothing from the library (no titles, playlists or files), no photos, no Wikipedia text.
Nothing is sent by itself: the zip leaves the tablet only when you share it. The crash
reports stay in the app's private storage, and after a crash the next launch shows
"The app crashed last time. Share diagnostics?" on the Library, with **Share
diagnostics** and **Dismiss**.

## Keep playing with the screen off

Playback runs in a foreground service with a media notification, which most
phones leave alone. Some (Samsung, Xiaomi, OnePlus, Huawei among them) still stop
background apps: set **Settings › Apps › Steven Piano › Battery** to
**Unrestricted** (or *Don't optimise*). Allow notifications when the app asks on
the first play, so the lock screen shows play and pause.

## Web panel

Run the piano from a phone or a laptop, and let guests ask it for a piece. The
tablet on the piano serves a small web panel itself; nothing goes through the
internet or any server of ours.

**Once, to set it up:**

1. Install **Tailscale** (tailscale.com; free for personal use) on the tablet and on
   your phone or laptop, and sign in to the same account on both. The tablet gets an
   address such as `100.101.2.3` that only your own devices can reach, from anywhere,
   and everything between them is encrypted. Keep Tailscale connected on the tablet
   (Android's *Always-on VPN* for Tailscale is the steadiest).
2. On the tablet: **Piano › Web panel › Set a PIN**: six digits, twice.
3. Turn on **Web panel**. The page shows the panel's address,
   `http://100.101.2.3:8737`, with its QR code beside it (tap it to show it large).
   Scan it with your phone, or type the address into a browser on a device with
   Tailscale, and enter the PIN.

**The panel** has what the app has, in five places (1.20): **Now playing** (the time
running, the transport, tempo, a channel's volume; **Up next** beside it, or as a sheet
from its head on a narrower window, to reorder, remove or clear, with the guests'
requests at its top to approve or decline), the **Library** (**Pieces · Playlists ·
Composers · Channels**, the **All · Classical · Modern** switch, which the browser
remembers, search, artists under Modern; Play, Play next, Add to queue; **Add music**:
drop `.mid`/`.midi` files or a `.zip` on it or on the page, up to 8 MB and 64 MB: they
upload one at a time and the tablet imports them, with the tally; a zip becomes a
playlist and loose files go into **Uploads**, and under the tally "In the playlist MIDI"
with **Open the playlist**), **Quiet times** (see *Quiet times*), **Studio** and **Settings** (**System**
first: the tablet, the controller and the piano, with Read status, All keys off and the
other tools, and, read-only, the instrument and the keyboard; Playback; the piano's Sound
and touch, Lights and screen and Pedal settings with Save to the piano, or while another
MIDI piano plays only a note that they are Steven Piano's; **Guests**; the panel's
**Appearance**). Live and recording are the tablet's alone. It updates as things change on
the tablet, and the browser's tab shows the app's icon.

From 1.13 the panel's **Now playing** shows the music as the tablet does, in step with it:
the **sheet music** (its cursor, the notes sounding turning yellow, fingering and chord
names) and the **notes** moving down the paper roll or falling onto a strip of keys. On a
computer both show, one above the other (side by side on a wide window); drag the bar
between them to give either more room, and the browser remembers it. On a phone choose
**Art**, **Notes** or **Score** at the top. **View** changes the tablet's own Paper roll or
Falling notes, Fingering, Chord names and Hand colours. The score turns its pages by itself;
‹ and › look ahead or back, **Follow** catches up, and a tap on a bar plays from there. It
works through Steven Piano Cloud too; the first time, the score's font (about 0.9 MB) comes
from the tablet, then the browser keeps it.

From 1.15 a browser that has entered the PIN **stays signed in** for a year, across
restarts of the app and of the browser; a new phone or computer still needs the PIN. A
new PIN or turning the web panel off signs every browser out: do that if a phone that
had the panel is lost. Five wrong PINs close the gate for 30 seconds, then longer each
time, up to ten minutes.

From 1.18 the tablet also answers the panel's System routes (behind the PIN like the rest): its battery, temperature,
memory and network, what the app is doing, the piano's own facts and Share diagnostics' zip, for the System page a
later version shows.

While the web panel is on, a quiet notification reads "Web panel on" with the address.
The panel runs as a foreground service, as playback does: set the app's battery use to
*Unrestricted* (see *Keep playing with the screen off*) so Android leaves it running
while the screen is off. After the tablet restarts, it comes back when the app is
opened.

**Guests** (Piano › Guests):

- **Guests can request** (off at first) opens the request page, served on the tablet's
  Wi-Fi address, such as `http://192.168.1.20:8737/request`: a phone on the same Wi-Fi,
  with no Tailscale and no PIN, sees Popular, Recognisable and Epic on piano and, from
  1.14, **Modern** (every Modern piece by title and artist, up to 2,000), with the
  **All · Classical · Modern** switch and, for long lists, a search box that searches on
  the phone itself; a tap on **Request** asks. One request a phone every five minutes;
  nothing is sent but the piece, nothing but those lists. While it is off the page says
  requests are closed.
- **Approve requests first** (on at first): a request waits for **Approve** or
  **Dismiss**, on the panel's Requests page or on the tablet, where the Library shows
  "1 request waiting". Off, it goes straight into Up next (and plays at once if nothing
  is playing).
- **Print the request poster** opens Android's print dialog for an A4 poster: "Ask the
  piano", the request page's QR code and its address. If the tablet's Wi-Fi address
  changes, print it again (a reservation for the tablet on the Wi-Fi's router keeps the
  address).

The Wi-Fi address serves only the request page and the poster. **Panel on Wi-Fi too**
serves the whole panel there as well, for a Wi-Fi without Tailscale, but read its note:
**over Wi-Fi the PIN travels unencrypted**, as does everything the panel shows, so
anyone on that network who records its traffic can read the PIN and use the panel.
Leave it off on any Wi-Fi that is not your own; Tailscale encrypts everything. Some
school networks keep devices from reaching each other (client isolation): guests'
phones then cannot reach the request page, and only Tailscale works.

## Cloud

From version 1.10 the panel can also be reached from anywhere, over HTTPS, without Tailscale, through
**Steven Piano Cloud**: a small relay and a console that run on **your own Cloudflare account** (free
plan). The tablet keeps one connection out to the relay; a browser opens
`https://<your relay>/p/<piano>/` and gets the same panel, behind the same PIN. Nothing is set up or sent
anywhere until you do it; it is off by default.

**Once, to set it up:** deploy the relay and the console with the steps in
[`cloud/README.md`](cloud/README.md) (about fifteen minutes; Cloudflare Access keeps the console to your
own email). Then, for each tablet:

1. In the console, **Enrol a tablet**: it shows a code such as `QUUT-9Q3H` (it works once, for
   15 minutes) and the relay's address.
2. On the tablet: set the panel's PIN first (Piano › Web panel › **Set a PIN**), then
   **CLOUD › Enrol with code**: type the relay's address (for example
   `steven-piano-relay.you.workers.dev`; the tablet remembers it) and the code.
3. Turn on **Remote access over the internet**. The line under it says "Connected", and the panel's
   public link appears with its QR code (tap it to show it large). Open it on any phone or laptop and
   enter the PIN.

The hub's Web panel row reads "Internet" (or "On · 100.101.2.3 · Internet" with the panel on the tablet's networks too), and the notification
adds "· Cloud" while connected. If the tablet loses its network, the line says so and counts down to the
next try; it reconnects by itself. A panel already open says "The piano is offline" at the top until the
tablet is back; a page opened meanwhile is the relay's own offline page.

**From the console** you can see each piano (online, what plays, versions, Steven's library's version), pause
or skip, play a piece or a channel, open or close guests' requests, **Load Steven's library** (see *Steven's
library*), and **Rotate secret** (the tablet takes its new key by itself),
**Revoke** (the tablet shows "Revoked in the console. Enrol again." and stops trying) or **Forget** a
piano ("Removed from the console. Enrol again."). On the tablet, **Forget this cloud** turns remote access
off and deletes the tablet's key; remove its row in the console too.

**Guests** can use the relay too: with **Guests can request** on, `https://<relay>/p/<piano>/request` is
the request page (the Wi-Fi poster still prints the tablet's own address). While it is off, the relay's
request page doesn't exist.

**What the relay sees:** it is your own Cloudflare Worker, and it carries what the panel shows and is sent,
as any HTTPS site's server does: pages, lists, the piece playing, requests. The tablet reports every
30 seconds: the app's and the piano's versions, whether the piano is connected, what plays, whether guests
may request (and wait for approval), whether the web panel is on, the library's size and the version of
Steven's library loaded, and the channels' names. Never a device identifier (no Bluetooth address, no
serial, no Android id) and never the tablet's address on its own networks; enrolling sends the code alone.
Piano › About says the same. The PIN is checked on the tablet, never by the relay. The tablet's key to the
relay is sealed by Android's keystore and never shown or included in Share diagnostics; nor is the piano's
id.

**PIN tries from the internet** have a gate of their own, stricter than the tailnet's: after ten wrong PINs
in a row, from anywhere, the panel's sign-in through the relay waits a minute, then longer each time, up to
an hour (some 24 tries a day, so a six-digit PIN can't be ground through). Someone who keeps sending wrong
PINs can keep that sign-in shut; a browser already signed in stays signed in, and the tablet and the
tailnet are not affected.

## Updating the piano's firmware

From version 1.6 the app can update the piano's own firmware (the ESP32 program that strikes the
keys, runs the lights and the pedal) over the same Bluetooth link it plays through. Nothing unsigned
ever runs: the app, and then the piano itself, check that each release was signed with Steven's
authorship key before anything is installed.

**Once, over USB: firmware 2.0.0.** The piano's current firmware can't receive updates, so the first
one that can, 2.0.0, goes on from the Mac over the USB-C port (the firmware's README: `pio run -t
upload` with the board's BOOT and RST dance). After that flash, **Piano › Firmware and status** shows
"Piano firmware 2.0.0 · a1b2c3d". Before it, the page says "Unknown — this firmware has no version.
Flash 2.0.0 over USB once." The same 2.0.0 also brings the piano's settings over Bluetooth: Sound and touch,
Lights and screen and Pedal come alive on the tablet.

**From then on, from the tablet.** The app looks for a new release once a day while the piano is
connected (with *Check for updates automatically* on), and whenever the Firmware and status page
opens; **Check for piano updates** asks at once. When there is one, the hub's row reads **Update
available** and the page shows the release's notes and **Update the piano to 2.1.0**. Tap it with the
tablet near the piano and at least 20 % charged (or plugged in):

1. The app downloads the release from GitHub (about 1 MB) and checks its fingerprint and signature.
2. It stops whatever is playing, and the piano goes quiet: its keys and pedal are released and
   checked off, its LED strip goes dark, and its screen reads "Updating • 38 %". Nothing plays from
   the tablet, the web panel or the Keys screen meanwhile; Now playing says "Updating the piano".
3. The new firmware goes over in about two minutes ("Sending · 38% · about 1 min left", also in a
   notification with Cancel, so the screen can go off). Cancel stops it cleanly until the very end.
4. The piano checks the whole image and its signature, restarts on it (Bluetooth drops for a few
   seconds; the app waits for it), and the page reads "Updated to 2.1.0 · confirming…", then
   "Updated to 2.1.0" once the piano has confirmed itself, about 30 seconds after it starts.

**If something goes wrong,** the piano keeps its old firmware: an update that doesn't finish (the
tablet walks out of range, the piano is switched off, Cancel) leaves the running firmware untouched,
and the page says "The update didn't finish. The piano kept its old firmware." with **Retry**. A new
firmware that fails to start properly is rolled back by the piano itself at its next restart: the
page then says "The piano restarted but reports 2.0.0 — it rolled back." Your settings stay on the
piano either way. A release that changes the flash layout reads "This update needs a USB flash" and
is never sent over Bluetooth; one that needs a newer Steven Piano reads "Needs a newer app".

**Publishing a firmware release** is the firmware repository's `tools/publish-firmware.sh "<notes>"`
(see its header and `firmware/docs/BLE_OTA.md` › 10): it builds, signs with the authorship key,
creates the GitHub release `fw-v<version>` on `stevenjin20090101-rgb/Steven-Jin-Player-Piano` and
writes the manifest the app reads. The repository must stay public.

**Needs the real piano** (the emulator runs every step against a stand-in; see BUILD_SPEC.md ›
v1.6 — M21):

- [ ] Flash firmware 2.0.0 over USB once; Firmware and status reads "Piano firmware 2.0.0 · …" and
      Check for piano updates says the firmware is up to date.
- [ ] With 2.0.1 (or later) published: Update the piano, keep the tablet at the piano, watch the
      piano's screen count up and the page follow; the piano restarts and the page reads "Updated to
      2.0.1", then (about 30 s later) without "confirming…". Play a piece: it plays.
- [ ] Cancel halfway: the piano plays again at once, on its old version.
- [ ] Walk the tablet out of range halfway: "The update didn't finish…"; back in range, Retry
      finishes it.
- [ ] While updating, tap a piece, a channel and the Keys screen: nothing sounds.

## Kiosk

Kiosk mode (v1.6.1) locks the school tablet to the app: no Home, no Recents, no
notifications, no other apps. The app is the tablet's home screen, so a restart lands
back in it; the lock screen is off, so the power button wakes straight into it; and the
screen stays on while the tablet is plugged in. With nobody touching it for a minute the
tablet rests in display mode: the piece playing (its art and a few lines about it), or
with nothing loaded the byline at the top right and, while the web panel and **Guests can
request** are on, the request page's QR code.

**Turning it on**

1. Make the app the tablet's device owner, once, with a computer and a USB cable (see
   *School tablet* above for the fresh-tablet steps):

   ```bash
   adb shell dpm set-device-owner dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin
   # Success: Device owner set to package dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin
   ```

2. On the tablet: **Piano › Kiosk › Set a PIN** (six digits, twice), then turn on
   **Kiosk mode**. The screen locks to the app at once.

If the tablet has its own screen lock (a PIN, pattern or password in Android's settings),
Android keeps it: after a restart the tablet waits at the lock screen. Remove it to start
straight into the piano; the Kiosk page says when this is the case.

**Getting out**

- Press and hold the line under any tab's title, *PLAYER PIANO · BY STEVEN JIN*, for three
  seconds. Nothing on screen hints at it; a thin line grows under the words while you hold.
- Enter the kiosk PIN, then **Unlock for now** (Home and the other apps come back until the
  app is opened again, or the tablet is left alone for a minute) or **Turn kiosk off**
  (everything goes back as it was; the PIN is kept for next time). On the Kiosk page,
  **Unlock for now**, turning the switch off and **Change PIN** ask for the PIN too.
- Wrong PINs: three are free, then each one makes the next wait 5 s, 10 s, 20 s… up to
  five minutes. Restarting the app or the tablet doesn't reset the count.

**Forgotten PIN**: give the device-owner role back over adb. Kiosk mode ends first (the
tablet's own home screen and lock screen come back), then the role goes, and with it
silent updates:

```bash
adb shell setprop debug.stevenpiano.releaseowner yes
adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity   # the app ends kiosk mode, then gives up the role
adb shell dpm list-owners                                        # "no owners"
adb shell "setprop debug.stevenpiano.releaseowner ''"            # quoted whole: adb drops a bare ""
```

(`am force-stop`, in *School tablet*'s sequence, is ignored by Android 14 for a device
owner's own app; the `am start` is what reaches it.) Set the device owner again, as above,
to use kiosk mode again. A factory reset also ends everything.

**Settings are locked in kiosk mode.** Anyone can play, queue, browse and use the Keys tab
(**Live** and **Record** included: a recording then waits, at most 30, for someone with the
PIN to keep or discard it), but anything that changes the piano or the library asks for the
kiosk PIN first: every page of the Piano tab (Instrument, Keyboard, Sound and touch, Lights and screen, Pedal,
Firmware and status, Playback, Tablet sound, Quiet times, Web panel, Guests, Display, Kiosk, Updates,
Library and artwork, Help and about; so choosing or forgetting an instrument or a keyboard), keeping or
discarding a recording, Disconnect; searching the settings stays free, and in the Library the
**+** (adding music), deleting, renaming, playlists' edits, Change photo, and a channel's volume;
and **Play anyway** during a quiet time. A
small padlock marks them. The right PIN opens them for five minutes, or until the tablet
rests in display mode, whichever comes first; while unlocked for now they are open too.

Things to know: an update from the app's own updater reopens the app, which locks again; one
installed over adb leaves Android's launcher up until Home is pressed. An update of the piano's
firmware carries on if the tablet rests meanwhile, and Firmware and status keeps it in view
while the settings are locked: how far it has got, and how it ended; only its Cancel asks for
the PIN (and while it runs, its row opens without it).

## Quiet times

Since 1.20 the piano never starts a piece by itself: someone presses Play, on the tablet or the web panel, and then Up
next follows on, four pieces in a row at most. A guest's request joins Up next and waits for someone's Play. The timed
plays of earlier versions (Schedules) were removed; any you saved stay on the tablet, unshown, and never run.

**Quiet times** keep the piano silent at set hours, for lessons or the night. **Piano › Quiet times** (the hub's row
reads "Quiet until 9:30" or "Next Mon 8:40"):

1. **Add section**: a name ("School days"), its days (Weekdays and Every day are one tap), and its blocks, each from a
   time to a time ("8:40–9:30"). **Add block** proposes the next one ten minutes after the last, as long as it. A block
   that ends before it starts runs past midnight ("The next day"): 21:00–7:00 keeps the night quiet.
2. **Save.** The page shows the week at a glance (Monday to Sunday, 6:00 to 22:00, the blocks hatched) and a card for
   each section; **Edit** changes one, **Delete section** removes it. Up to 12 sections of 16 blocks; the blocks of one
   section may not overlap (two sections may).

During a block the piano is silent: whatever plays stops as the block begins, and nothing starts until it ends; nothing
resumes by itself afterwards. Now playing and the resting screen say **Quiet until 9:30** with **Play anyway**, which
lifts the quiet until that block ends (the next block is quiet again); pressing Play asks the same. In kiosk mode Play
anyway asks for the kiosk PIN. The Keys tab and a MIDI keyboard still play: someone is at the piano. The web panel's
**Quiet times** page shows and edits the same sections and has Play anyway; its Play, while quiet, answers "Quiet until
9:30. Use Play anyway." The guests' page says "The piano is resting until 9:30. Your request will wait until then.", and
requests still go in.

**The tablet keeps the times**: leave it on, with the app's battery use *Unrestricted* (see *Keep playing with the screen
off*). An exact alarm wakes it as each block begins. Android 13 and newer allow that by themselves; on Android 12 the
person may take it away (Settings › Apps › Special app access › Alarms & reminders): the page then shows **Allow exact
alarms**, which opens that setting, and until then the app stops the piano only while it is awake.

**On the piano:**

- [ ] A schedule two minutes ahead with the tablet's screen off and the piano not connected: at
      the minute the tablet connects and plays at the schedule's volume; at the end time the
      piano stops and its volume comes back.
- [ ] The piano switched off: 20 seconds after the minute the page reads "Missed: … (piano not
      connected)".
- [ ] Restart the tablet with a schedule ahead and don't open the app: it still plays.

## Studio

Studio makes pieces on the tablet itself: no service, no account, nothing sent anywhere. It
**turns a piano recording into a piece**: the tablet listens to the recording with a
transcription model and writes down what it hears, every note with how hard it was played, and
the pedal, as a MIDI file in the library that the piano then plays. And it **composes a new
piece in the manner of one from the library**: a composing model continues the first fifteen
seconds of that piece into music of its own. (Studio came with 1.7.)

**Transcribing a recording**

1. **Studio › Models › Transcription › Download**, once: 125 MB from this repository's GitHub
   release `models`, checked against the SHA-256 the app carries before it is used. **Remove**
   frees the space again.
2. **Library › + › Transcribe a recording…** (or the paper clip on the Studio tab) and pick a
   recording: m4a, mp3, wav, flac, ogg, opus, whatever the tablet plays, up to 20 minutes and
   200 MB. From a computer or a phone, drop recordings on the web panel's **Studio** page instead;
   they go to the tablet one at a time, and while Studio has eight jobs waiting or running the page
   says to try again when one has finished. Without the model, the job downloads it first.
3. It runs in the background, with its progress in the notification (and Cancel), on its card in
   the Studio tab and in the Library: "Transcribing Clair de lune.m4a · 42%". About **a minute per three
   minutes of audio**; the piano can go on playing meanwhile, and keeps its time. A Cancel that comes
   once the piece is being saved changes nothing: the piece is kept, to keep or discard.
4. The piece appears in the library titled after the file, by **Made in Studio** (a roll card,
   and "Made in Studio · Sep 28, 2026" on its sheet). Play it: once it has played 15 seconds (or
   to its end), Now playing asks **Keep this piece?** **Keep** keeps it; **Discard** deletes it
   (in kiosk mode, behind the PIN). The job's **Listen** plays it straight away.

**The Studio tab (1.12; the stage since 1.13.1)**

Studio is the fourth tab: Library · Now playing · Keys · **Studio** · Piano. In the middle of the
screen, under "What should the piano play?", type an idea in the box ("Describe a piece…") and
**Send**, or tap a suggestion: a mood (calm, bright, stormy, sad…), a tempo (slow, andante, fast,
96 bpm), a key (in D minor, F♯ major), a length (2 minutes, 3:30, short), a form (nocturne, waltz,
lullaby, étude…), a composer, a performer or a piece's name from the library, or a channel or list
(Baroque, Popular). There is no text model: words are understood by keywords, and the line under
the box says what was understood ("Calm · D minor · slow · 2 min · in the manner of Clair de lune
(Debussy)") and which words were not used. The ring round the box is the aura: it turns slowly at
rest, brighter while you type, and fast and glowing while the tablet writes. Once sent, the box
moves up and one card under it shows the steps (Reading the piece · Composing · Shaping · Saving),
the percentage, the music written of the length asked for, the time left, and the notes appearing
as they are written; then **Listen**, **Keep**, **Discard**, **Another like it** and **Adjust…**
(the options sheet, as before). The card stays until the next idea, or until you have been away
from the tab for a minute. After a piece, **slower**, **faster**, **longer**, **shorter**,
**sadder**, **in D minor**, **another** or **different** change the last one. Everything Studio
made is in **History** (top right, beside **Models**), newest first: tap one for Listen, Another
like it, Adjust…, Keep, Discard or Remove. The **Options** button opens the options sheet from what
you typed; the paper clip transcribes a recording; **Models** downloads or removes the two models.
Titles are made from what was understood, never from what was typed: "Calm, after Clair de lune",
"Wild, after Chopin", "Calm piece". In kiosk mode typing ideas is free (at most three wait; History
hides the words until the PIN opens the settings); Models, recordings, Keep, Discard and removing a
turn ask for the PIN. The notification opens the Studio tab.

**Composing a piece**

1. **Studio › Models › Composing › Download**, once: 173 MB, checked the same way.
2. Type an idea and **Send** (above), or **Options** on the Studio tab (also **Library › + ›
   Compose in Studio…**) for the sheet:
   **Mood** (Calm, Bright, Wild, Melancholy); **Key** (C to B, major or minor) and **Tempo** (40–200
   bpm), both the chosen piece's own until you change them (Melancholy turns the key to its minor);
   **Length** (1–5 minutes); and **In the manner of**: the piece played last, or **Change** to search
   the library for another. From a computer or a phone, the web panel's **Studio** page has the same
   form.
3. **Compose**. It runs in the background like a transcription: "Composing · 42% · 0:50 of 2:00 ·
   about 40 s left" on its card, in the notification (with Cancel) and in the Library. About **a
   minute for a two-minute piece**, often less.
4. The piece appears titled from what was asked ("Calm, after Clair de lune") by **Made in Studio**,
   with a cover drawn from its music, in the playlist **Made in Studio**, its sheet saying "Made in
   Studio · in the manner of Clair de lune (Claude Debussy)", written at the tempo chosen. Keep it or
   discard it after a first listen, as a transcription.

**What it writes**: new music only. The chosen piece's first fifteen seconds are the model's
starting point and are never part of the result; the model (trained on the Lakh MIDI collection,
not on piano alone) carries on in their manner, not note for note. Calm plays softest, Wild the
most freely; every piece ends with a two-bar fade and no pedal. Every note is one the piano can
play: keys 24–107, a key struck again no sooner than 120 ms after itself, at most ten notes
starting at once. A very dense piece can come out shorter than asked (the model is stopped at 45
tokens a second of music, 13,500 for five minutes since 1.12); its card then says so: "3:41 written
of 5:00: the music was dense, so it ends here".

**What it hears well**: a clear recording of a solo piano. The model was trained on real pianos
(the MAESTRO recordings; its authors measured a 96.8 % note F1 there); audio from a synthesizer gets
extra notes (held notes struck again), and voices or other instruments turn into notes of their
own. A MIDI file picked by mistake is refused: "That's a MIDI file already. Add it with Add
files." So is a recording that would make more than 200,000 notes (a piano recording of twenty
minutes makes a few tens of thousands).

**The models**, downloaded only when asked, never bundled, each keeping its own licence (AUTHORS,
`third_party/`; About credits them):

| Model | Size | Licence | From |
|---|---|---|---|
| Transcription | 125 MB | CC BY 4.0 | ByteDance's high-resolution piano transcription (Kong et al., Zenodo 4034264), converted to ONNX |
| Composing | 173 MB | Apache 2.0 | The Anticipatory Music Transformer, music-small-800k (Thickstun et al., Stanford CRFM), converted to ONNX |

**Memory and devices.** Studio needs an arm64 tablet or phone with at least 2.5 GiB of memory
(most sold with 3 GB or more); elsewhere the Studio tab says only "Studio isn't available on this
device" or "This tablet doesn't have enough memory for Studio", and the + sheet has no Studio
row. A transcription starts only with about 900 MiB free, a composition with about 700 MiB, else
"Close other apps and try again."; the app uses about 0.75 GB while it transcribes and 0.6 GB
while it composes, and gives it back after.

**Size.** ONNX Runtime, which runs the models, makes the app a bigger download: the release APK
is 13.5 MB (1.6.2's was 2.8 MB). Its library comes for 64-bit ARM only; the app itself installs
wherever 1.6.2 did, and Studio hides on the other devices. The models are separate downloads, kept
in the app's own storage.

**Privacy.** Recordings never leave the tablet: they are decoded and transcribed there, and a
recording sent from the panel is deleted once its job ends. A composition starts only from a
piece already in the library, and nothing anyone types reaches the model: an idea is read for
keywords, kept (at most 200 characters) only in Studio's history on the tablet, and never put in a
title, a file name, the log or Share diagnostics. The only traffic is the
models' download, when you ask for it.

**On the piano:**

- [ ] Download the transcription model on the school tablet; transcribe a three-minute recording
      while a piece plays, then Stop and Share diagnostics: `link.log` has the transcription's
      time and memory ("Studio: transcribed 180.0 s of audio in … s …; peak VmHWM … kB"; about a
      minute and under 1 GB is right) and the piece's "Timing: … the latest N ms after its time"
      (a few ms is right). The piece should keep its time throughout.
- [ ] Listen to the new piece; Keep; play it again; Discard another.
- [ ] Download the composing model; compose a two-minute Calm piece in the manner of Clair de lune
      and a one-minute Wild one in the manner of Für Elise. `link.log` has each one's figures
      ("Studio: composed … s of music in … s (… ms a token …); peak VmHWM … kB": about a minute
      and under 1 GB is right). Play one and listen: every note should sound (none dropped for
      coming too soon).

## Instruments

**Piano › Instrument** chooses what plays the library, the Keys tab and schedules (from
1.11):

- **Steven Piano**, the school piano, over its own Bluetooth link, as always.
- **Another MIDI piano…**: any digital piano or sound module with MIDI, by USB cable or
  Bluetooth MIDI. The picker lists USB devices first, then Bluetooth devices as a 12 s
  search finds them (**Look again** after); it never lists Steven Piano. Choosing one
  pauses and silences the instrument before, and connects the new one; it is remembered,
  and connects at launch when *Auto-connect on launch* is on.

While another MIDI piano plays, the piano's own pages (Sound and touch, Lights and screen, Pedal, Firmware and
status) and its status line are hidden: they belong to Steven Piano. What it is sent
differs from Steven Piano's: all 88 keys (21–108) as they are, a key struck again while it
sounds is struck again, the sustain, soft and sostenuto pedals at once, and stopping sends
every held key's Note Off, the three pedals up and All Notes Off (many digital pianos
ignore All Notes Off alone). The Instrument page's **All keys off** sends that at once.

A Bluetooth MIDI piano that asks to pair: accept Android's request (or pair it in Bluetooth
settings), then **Retry**. Unplugged, a USB one reads "… isn't connected" and connects again
by itself when it is plugged back in. The connection card names the instrument with its
state.

## Keys: a keyboard and Live

**Piano › Keyboard › Choose a keyboard…** picks a MIDI keyboard: USB by cable (steadier),
or Bluetooth MIDI. Once chosen, the Keys tab names it ("KEYBOARD · <NAME>") and lights the
keys it plays. **Forget** lets it go.

**Live** (a pill on the Keys tab, shown while the keyboard is connected; remembered) plays
the instrument from the keyboard, **only while the Keys tab is on screen**: leaving the
tab, Home, the screen going off, Live off, the keyboard unplugged or gone quiet, or the
piano's link dropping lets go of every key and the pedal at once. The connection line reads
"Sent to piano" while Live plays, and "Keyboard connected. Live is off." otherwise. On
Steven Piano, A0–B0 and C8 follow *Fold notes outside C1–B7*, and the piano lets a held key
go after 2 seconds to keep its coils cool (the tab says so). A keyboard that sends a flood
(more than 200 notes a second, 32 keys held, or garbled data) switches Live off with a line
saying why; turn it on again by hand. A keyboard that is also the instrument never plays
through.

The web panel shows the instrument and the keyboard in two lines (and Live and Recording
while they are on), never a switch: Live is the tablet's alone.

**On the emulator** (debug builds): "Emulated keyboard" and "Emulated MIDI piano" are listed
beside a test device that goes through Android's own MIDI service. Play bytes from the
keyboard with `adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity --es
dev.stevenjin.stevenpiano.EMULATOR_MIDI "'90 3C 50 80 3C 00'"` (`EMULATOR_MIDI_SERVICE` for
the test device; `--ez dev.stevenjin.stevenpiano.EMULATOR_MIDI_PLUG false` unplugs it), and
read what the emulated piano was sent with `adb logcat -s MidiPiano`.

## Recording

**Record** (the last pill on the Keys tab: a filled circle, then a square and the time)
keeps what was played, from the keyboard (Live on or off) and the screen's keys, with every
pedal value: after **Stop**, *Keep this recording?* with its length and notes, a title
("Recording · <date> <time>"), **Discard**, **Listen** and **Keep**. It goes into the playlist
**Recordings**, by "Recorded live". A take ends by itself after an hour, 200,000 events or
five minutes of silence; one with no note is not kept; a crash loses none (the next start
saves it). After **Keep** the Keys tab says "Kept in Recordings." for a moment. Every
recording has a cover of its own, drawn from its notes as Studio's pieces' are, and the
Recordings playlist shows them (from 1.14). In kiosk mode a take waits for someone with
the PIN to keep or discard it ("Saved to Recordings. Someone with the PIN keeps or discards
it."), at most 30 at a time.

## Piano sound on the tablet

The tablet can play the pieces itself, with **recordings of a real piano**: FreePats'
**Upright Piano KW**, a Kawai upright recorded key by key at two strengths, played back
note by note by the app's own sampler (nothing is synthesised). It plays exactly what
the piano would be sent: pieces, channels, schedules, the **Keys** tab, and Studio's
Listen. (From 1.8.)

1. **Piano › Tablet sound › Upright piano › Download**, once: 57 MB from this
   repository's GitHub release `models`, checked against the SHA-256 the app carries
   before it is used (the same way as Studio's models). **Remove** frees the space. While
   the tablet would play but has no sound yet, Now playing offers the same download in a
   line under the tempo.
2. **Piano sound on the tablet** chooses when it plays:
   - **Off**: never.
   - **When the piano isn't connected** (the default): only while the piano's Bluetooth
     link isn't connected, so it never plays alongside the real piano. If the piano
     connects in the middle of a piece, the tablet goes quiet at once and the piano
     carries on.
   - **Always**: alongside the piano too. It may sound slightly early or late compared
     with the piano: the tablet's own delay (some 20–40 ms) and the piano's are not the
     same.
3. **Volume**: the speaker at the end of Now playing's tempo row (and at the foot of the
   now-playing panel on a tablet) opens it; so does the Playback page, and the web
   panel's Now playing has the same slider while the sound is on. The speaker is bright
   while the tablet plays and grey while it doesn't. The tablet's media volume applies
   on top, as for any music. In kiosk mode the volume stays free; the choice of when it
   plays is a setting, behind the PIN.

On the **Keys** tab the keys sound on the tablet while it plays the piano sound (the
line under the keys says "Not connected. The tablet plays these keys.").

When another app takes the sound (a call, a video), the piece pauses; **Play** resumes
it. A notification only lowers the tablet for its moment.

**The licence.** The Upright Piano KW is dedicated to the public domain under **CC0 1.0**
by the FreePats project (https://freepats.zenvoid.org, version 2022-02-21); the app
downloads it unmodified. It is credited all the same, in About, in `AUTHORS` and in
`third_party/upright-piano-kw/`.

**On the emulator and the Mac.** Point the download at a server on this Mac as for
Studio: `adb shell setprop debug.stevenpiano.modelsurl
http://10.0.2.2:8766/models.json` (a `models.json` with the sound's entry under
`"sounds"` and its `url` on that server), then start the app. The emulator has no
speaker to record, so the sound can be heard as a file instead:
`./gradlew testDebugUnitTest --tests '*TabletSoundRenderTest'
-PpianoSound=<upright-piano-kw-v1.sf2> -PpianoRender=<out.wav>` plays Clair de lune
(`../midi/piano-midi.de/debussy/deb_clai.mid`, or `-PpianoRenderMidi=<file.mid>`)
through the app's own engine and sampler into a 48 kHz WAV.

**Publishing the sound** (done once, for 1.8): `python tools/studio/publish_models.py
--work <dir> --models upright-piano-kw --upload` fetches the archive from FreePats,
checks its hash and the SoundFont's, writes the entry into `releases/models.json`
(under `"sounds"`, which 1.7 never reads, so older tablets keep working) and uploads the
file to the release `models`.

## Test it on the piano

- [ ] Sideload `app-release.apk` (uninstall a debug build first). The app launches
      instantly and asks for Bluetooth permission only from the Piano tab, with a
      one-line reason.
- [ ] Connect: the dot goes live and the status reads Connected within a few
      seconds of the piano advertising.
- [ ] With an iPad (or a Mac's Audio MIDI Setup) connected to the piano, tap
      Connect: after 12 s the card says it can't find the piano, that it hides
      while another device is connected, and how the piano's screen shows it.
      Disconnect the iPad and tap Retry: Connected. `adb logcat -s PianoLink:W`
      shows each step (see *Send a log*).
- [ ] Play a quiet piano-midi.de piece: the roll scrolls, notes brighten crossing
      the bar, the keyboard strip inverts, the timers count with tabular figures,
      the piano plays in time. Tempo 50 % halves the rate live. Switch Note
      display to Falling notes and back while playing.
- [ ] The pause before each piece: tap a piece and the piano stays silent for two
      seconds while *STARTING* shows, then plays its first notes as they reach the
      tracker bar; at the end of a piece the next one starts about 2 s later. Pause
      during the pause and press Play: the piece starts at once.
- [ ] Pause: the piano is silent within a second, no key left down, pedal up.
      Seek while playing: the same. Lock the phone: playback continues, and the
      notification plays and pauses.
- [ ] Swipe the app away mid-piece: the piano is silent (the service sent the
      stop sequence).
- [ ] Power-cycle the piano while connected: the app shows Not connected, then
      reconnects by itself within about 15 s of the piano advertising again, and
      the roll resumes from pause when Play is pressed.
- [ ] Import the whole `midi` folder (or the zip): 1,727 pieces appear in
      playlists and by composer; search finds "Clair de lune"; a MAESTRO
      performance plays with its recorded dynamics (piano in variable-force mode).
- [ ] Keys: a tap plays the key; three fingers play a chord; sliding plays a
      glissando with each key released before the next; a touch near the top of a
      key is soft and near the bottom loud (VELOCITY confirms it). Sustain on holds
      the pedal, Sustain off lifts it. With a key and the sustain held, switch tab,
      press Home, or power the piano off: every key and the pedal come up.
- [ ] Keys while a piece plays: pressing a key the piece is holding does not
      re-strike it, and leaving Keys leaves the piece's notes sounding.
- [ ] On a tablet: the rail replaces the bottom bar; Now playing shows the score
      over the roll upright and beside it on its side, in step with each other;
      dragging the divider, hiding a view and bringing it back, and View › Score
      only and Notes only work while playing.
- [ ] Shuffle a playlist and skip around while connected: every piece starts
      cleanly, no key is left down between pieces, Previous and Next follow the
      shuffled order, and turning Shuffle off keeps the current piece playing.
- [ ] Keys: hold a chord and turn the phone (or the tablet): the chord keeps
      sounding, and the screen turns once the fingers lift.
- [ ] Clair de lune with the score showing, at 100 % and at 50 % tempo: the
      cursor keeps pace with the piano, a page turns before the cursor needs it
      (on a tablet on its side with Score only, the left page turns while the
      right one is being finished), and tapping a bar plays from there with no
      key left sounding. The first page reads ♩. = 67 over the clef, an eighth
      rest, eighths beamed in threes, ties over the bar lines, whole rests in
      the empty bass and p under bar 1; a tied note lights again as the cursor
      reaches its tied head.
- [ ] The waterfall format, on a tablet upright with Falling notes: Bach's C
      major prelude (piano-midi.de) shows its left hand as outlined bars and
      outlined keys and its right hand filled; fingering figures sit in the long
      left-hand bars and over and under the score's heads; the chord names read
      C, Dm7/C, G7/B, C, Am/C over the first bars and arrive at the waterfall's
      left edge as each bar begins. Now playing › View › Hand colours colours the two hands
      green and blue, in light and dark; Fingering and Chord names off take each
      away. A MAESTRO performance splits its hands by pitch. The piano plays as
      before whatever is shown.
- [ ] Open a piece's sheet on Wi-Fi (its notes and the composer's portrait
      appear), then again in airplane mode (the art shown before is kept, and the
      sheet says notes need an internet connection when it has none).
- [ ] Piano settings (firmware with the Bluetooth console): on connect the Piano
      tab's PIANO rows fill in ("Full power", "Reactive · 62%", the firmware
      version) and their pages come alive. On Lights and screen, set Brightness to 15 % and
      confirm the piano's serial `status` shows it (`bright=40/255`); the hub's row
      then reads "… · 15%". On Sound and touch, choose Cinematic: the dependent settings
      change to what the piano reports. Going back to the hub saves nothing yet;
      leave the tab, power the piano off and on: the change is still there. *Test
      LED* lights the key's LED; Firmware and status › Read status shows the
      piano's report. With older firmware the hub and each piano page say it
      doesn't offer settings over Bluetooth yet, and playback works as before.
- [ ] "Open with" from a file manager: the app asks "Add 1 file to the library?";
      Cancel adds nothing, Add imports it. From one that gives no access: after Add
      the Library says it couldn't read the file (no crash); *Add files* imports it.
- [ ] Updates (once the repository is public and a newer release exists): Piano ›
      Updates › Check for app updates shows UPDATE with the release's notes; Update downloads it with the
      progress row and a notification, then Android asks "Do you want to update this
      app?"; after Update and Open the About line shows the new version and Check for app updates
      says it is up to date. On the school tablet (device owner) Update installs with
      no tap and the app comes back on the Piano tab reading "Updated to …". While
      the repository is private, Check for app updates says "Couldn't reach the update server."
- [ ] Share diagnostics opens the share sheet with `steven-piano-diagnostics-….zip`;
      send it to yourself and check it holds about.txt, settings.txt, link.log and no
      titles.
- [ ] Channels, with the piano's firmware offering its settings: note the Sound and touch row
      ("Full power" or "Piano volume …"), then tap the Calm card: the piano plays at 70 %
      and Sound and touch reads "Piano volume 70%"; pieces follow one another without end. Long-press
      Calm › Set volume and move the slider: the piano follows. Tap a piece in the
      library: the channel ends and Sound and touch reads as it did before; power-cycle the
      piano: its own volume never changed (nothing was saved). With older firmware
      the channel plays at the app's velocity instead, and the Playback page's
      Velocity comes back when it ends.
- [ ] The resting screen on the school tablet: Piano › Display › Resting screen after a
      minute on, play a channel and leave the tablet: a minute later the black
      display fades in (the art, the title and a few lines about the piece; the
      roll with What it shows › Paper roll), the next piece cross-fades in, and the
      tablet does not sleep; a touch fades it away and brings the app back without
      pressing what was under the finger.

Still open after 1.11 (the emulator can't tell; on the school tablet and the piano):

- [ ] A **USB keyboard** by cable: chosen in Piano › Keyboard, the Keys tab lights its keys;
      Live on, it plays the piano with no lag you can hear; pull the cable mid-chord: every
      key lets go at once and the pedal comes up.
- [ ] A **Bluetooth MIDI keyboard**: Android's pairing request when it asks, then the same;
      with Steven Piano's link up too, play a fast passage and listen for unevenness (two
      Bluetooth links share the radio). Switch the keyboard off: it lets go within a second
      or two.
- [ ] Live: hold a key: the piano lets it go after 2 s (its coils), as the tab says; the
      sustain pedal on the keyboard works, half-pedalling too; leave the Keys tab, press
      Home, turn the screen off, each with keys and the pedal held: everything lets go.
- [ ] The flood breaker: mash many keys at once or play a glissando with the palm: 32 keys
      held switches Live off with its line; if it trips in normal playing, tell the
      developer (200 notes a second and 32 keys are first guesses).
- [ ] Record a piece from the keyboard, Keep it, and play it back from Recordings on the
      piano: the timing and the pedal as played.
- [ ] **Another MIDI piano** (any digital piano, USB or Bluetooth): Piano › Instrument ›
      Another MIDI piano…, play a library piece and Pause: no note hangs and the pedal comes
      up; play Live to it; choose Steven Piano again: the digital piano falls silent.
- [ ] Kiosk mode: Live and Record work without the PIN; a recording waits for the PIN;
      Piano › Keyboard and Instrument ask for it.

## Security

The full audit, every finding and what was done about it, is in
[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md).

- **What leaves the device:** only HTTPS requests to `en.wikipedia.org` and
  `upload.wikimedia.org`, carrying page titles and searches made from the library's
  own names; with *Album covers* on, or from a signed-in panel's *Find a cover…*, to
  `itunes.apple.com` (a search: a piece's title and artist, or the words typed there, and the
  device's two-letter country) and Apple's image hosts `*.mzstatic.com`;
  and, for updates of the app and of the piano's firmware and for Studio's
  models, the piano sound and Steven's library (only when you download one), to `raw.githubusercontent.com` (the four
  lists: the app's `latest.json`, the firmware's, `models.json` and `library.json`), `github.com` (the
  release downloads of this repository, its releases `models` and `library` among them, and of the
  firmware's, `stevenjin20090101-rgb/Steven-Jin-Player-Piano`, only) and
  `objects.githubusercontent.com` / `release-assets.githubusercontent.com` (where
  GitHub hands the file over), carrying nothing but the app's User-Agent and, as with
  any connection, the device's IP address. With *Remote access over the internet* on (off
  at first; see *Cloud*), one connection more, over HTTPS to your own relay: what the panel
  shows and is sent, and every 30 seconds the tablet's status (versions, whether the piano
  is connected, what plays, the guests' switches, whether the web panel is on, the library's
  size and pack, the channels' names), never a device identifier or the tablet's own network
  address (*What the relay sees*). Studio's runtime (ONNX Runtime 1.28.0) sends
  nothing: during a transcription and a composition the app's UID sent no packet at all
  (the audit's count, `docs/SECURITY_AUDIT.md`). No analytics, no accounts, and no crash
  report goes anywhere by itself: diagnostics leave only when you share them. Nothing
  is backed up to the cloud or carried to a new device by Android's transfer (the
  library stays where it was imported). Release builds log no file names or URLs; the
  Bluetooth link logs its steps, with Bluetooth addresses and device names only (*Send
  a log*).
- **What comes in (the web panel, off at first):** the app listens on port 8737 only on
  the tablet's Tailscale address (the whole panel, behind the PIN) and its Wi-Fi address
  (the request page and the poster only, unless *Panel on Wi-Fi too*), never on every
  address. The panel's PIN is kept only as a salted PBKDF2 hash; every change needs the
  session and a header no other site can send; no other site's page can use the panel;
  uploads and requests are capped before they are read. The panel is plain HTTP: over
  Tailscale that is encrypted by Tailscale, over Wi-Fi it is not. With *Remote access over
  the internet* on, the same panel also comes in through your relay, over HTTPS, behind the
  same PIN, which the tablet checks, never the relay, with a stricter gate for PIN tries from
  the internet. The details, and what is left, are in the audit's 1.5.1 section and its 1.10
  audit (delta 3).
- **Updates** install only a file whose SHA-256 matches the manifest and that Android
  accepts as signed with the release key (see *Updates*); on the school tablet the
  device owner role is used for silent updates and, once it is turned on, kiosk mode,
  and nothing else (see *School tablet* and *Kiosk*).
- **Kiosk mode** keeps a passer-by in the app, behind a PIN of its own, kept only as
  a salted hash, with waits that grow after three wrong tries and outlast a restart;
  a forgotten PIN needs a computer and adb. It is not a locked enclosure: someone with
  the tablet's buttons can still reach Android's safe mode or recovery. The details
  are in the audit's 1.6.1 section.
- **The piano's firmware** goes to the piano only when its release is signed with
  Steven's authorship key: the app checks the Ed25519 signature over the SHA-256 of
  the very bytes it downloaded, and the piano checks both again before it restarts on
  them; a firmware that fails its self-test is rolled back by the piano. Release
  builds trust that one key alone (see *Updating the piano's firmware*).
- **What a file may cost:** a MIDI file is read up to 8 MB and at most about two
  million events and a day of music; its text up to 256 bytes a name, and 4,096 time
  and 4,096 key signatures; titles and names
  are stored cut to 200 and 120 characters. A zip is refused over 512 MB or 20,000
  entries, a folder is read 16 levels deep and at most 20,000 files, an `INDEX.csv`
  up to 2 MB. Steven's library's list is read up to 4 KB and its zip up to 200 MiB,
  checked against its SHA-256 before it is opened; then a zip's limits apply. Files from
  other apps wait for **Add**. A file past a limit is skipped
  with a plain reason; it never takes the app down.
- **The release key** lives outside this repository, in the home folder:
  `~/steven-piano-release.jks` and `~/steven-piano-keystore.properties` (its
  passwords), both readable by Steven only and never committed. **Back them up**
  somewhere safe and offline. Losing them means no future release can update an
  installed copy: every device would need an uninstall and a fresh import.

## Acknowledgements

- The score's clefs, key and time signatures, accidentals, note heads, flags,
  dots, rests, dynamics and the tempo mark's note are drawn with **Bravura**,
  the SMuFL music font by Steinberg Media
  Technologies GmbH, bundled unmodified under the SIL Open Font License 1.1
  (notice in `AUTHORS`, licence in `third_party/bravura/OFL.txt`).
- Composers' blurbs and pieces' notes are text from Wikipedia (CC BY-SA 4.0), each
  linked back to its article with *From Wikipedia*; portraits come from Wikimedia
  Commons.
- Suggested fingering follows the ergonomic cost model of R. Parncutt, J. A.
  Sloboda, E. F. Clarke, M. Raekallio and P. Desain, "An ergonomic model of
  keyboard fingering for melodic fragments" (Music Perception, 1997); a file
  without a key signature is spelled in the key found with C. L. Krumhansl and
  E. J. Kessler's key profiles (1982). Both are implemented from the published
  descriptions; no code or data is copied.
- The glass is drawn with **Haze** by Chris Banes and the Haze contributors
  (Apache License 2.0), linked unmodified from Maven Central (notice in `AUTHORS`).
- The web panel runs on **NanoHTTPD** (BSD 3-Clause), and its QR codes are encoded
  with **qrcode-kotlin** by Rafael Lins (MIT), both linked unmodified from Maven
  Central (notices in `AUTHORS`, licences in `third_party/`).
- The firmware releases' signatures are checked with **EdDSA-Java** by str4d and
  its contributors (CC0 1.0), linked unmodified from Maven Central (notice in
  `AUTHORS`, legal code in `third_party/eddsa/`).
- Studio transcribes with ByteDance's high-resolution piano transcription model
  by Qiuqiang Kong, Bochen Li, Xuchen Song, Yuan Wan and Yuxuan Wang (CC BY 4.0,
  Zenodo 4034264), and its composing model is the Anticipatory Music Transformer
  by John Thickstun, David Hall, Chris Donahue and Percy Liang (Stanford CRFM,
  Apache License 2.0); both converted to ONNX, downloaded on demand and never
  bundled (notices in `AUTHORS`, what changed in `third_party/`). They run on
  **ONNX Runtime** by Microsoft (MIT), linked unmodified from Maven Central
  (licence and its third-party notices in `third_party/onnxruntime/`).
- The tablet's piano sound is FreePats' **Upright Piano KW** (2022-02-21), dedicated
  to the public domain under CC0 1.0 by the FreePats project, downloaded on demand and
  unmodified (credited in About, `AUTHORS` and `third_party/upright-piano-kw/`).
- The music library draws on MAESTRO (Google Magenta, CC BY-NC-SA 4.0),
  piano-midi.de (Bernd Krüger, CC BY-SA) and the Mutopia Project (public
  domain); those files are not part of this repository's source. Steven's library,
  built from them, is an asset of its GitHub release `library`, downloaded on demand
  and never bundled (credit lines in *Steven's library* and `AUTHORS`).

## Authorship

MIT licensed with attribution preserved: see `LICENSE` and `AUTHORS`. Every
source file is covered by an Ed25519-signed manifest; `python3
provenance/verify.py` checks it (see `PROVENANCE.md`).
