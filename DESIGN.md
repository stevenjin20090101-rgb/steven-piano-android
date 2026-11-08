<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano — design system

Android app that plays a MIDI library on the self-playing piano over Bluetooth MIDI.
Made by Steven Jin. App name **Steven Piano** (the piano's own Bluetooth name). This
document is the design of record; `BUILD_SPEC.md` is the engineering contract. Where
they disagree, this file wins on anything visual.

## Thesis

**Restraint as luxury.** A Leica Monochrom is not "black and white"; it is a matte body,
precise engraved labels, and one red dot. The app is the same: a calm monochrome
instrument whose only colour is a status light.

The thing it will be remembered by: **the paper roll.** A pianola plays from a
perforated paper roll that unwinds from the top spool *downward* past a tracker bar
onto the take-up spool. The Now Playing screen shows exactly that — notes as
perforations on a monochrome roll travelling downward through a horizontal
tracker-bar line. Notes are heard the instant they cross it. This is the actual
mechanism of the instrument, which is why it is ours and not a template. A second,
Synthesia-style *falling notes* style is available as a setting (Steven asked for
both); it shares every token, lane and colour, and differs only in where the hit line
sits and how the bars are drawn.

Two disciplines make the palette a choice rather than a default:
1. **Red means one thing: live.** The red dot appears when the piano is connected
   and breathes gently while it is playing. It is never a button fill, never text,
   never a highlight. If red appears anywhere else, the design has been broken.
2. **No other colour, anywhere.** No tinted nav indicator, no coloured icons, no
   error red. Errors are copy plus an outlined banner. *(Two named exceptions since, both
   Steven's: the hand colours on the waterfall (v1.3, a switch, off by default) and the
   sounding yellow on the score (v1.5 — M16). Neither is red, and neither carries meaning alone.)*

## Principles applied (Apple HIG, adapted for Android)

| Principle | How it shows up |
|---|---|
| Purpose | One job: pick a piece, send it to the piano, watch it play. Every screen serves that. |
| Familiarity | Material 3 navigation and controls; identity lives in colour, type and the roll. |
| Simplicity | Four tabs (three in v1.0; Keys joined in v1.1). Text-only library rows. One accent, one signature moment. |
| Craft | Tabular figures on every timer, hairline rules, 48 dp targets, contrast computed not guessed. |
| Delight | The roll starting to turn. That is the whole emotional budget, spent once. |

`branding.md › Best practices`: *"Ensure branding always defers to content"* and
*"Resist the temptation to display your logo throughout your app."* — no wordmark
inside the app; the name appears in the launcher and once, as plain text, in the About
row at the bottom of the Piano tab.

## Colour

Dark is the default ("the camera body"). Light follows the system setting ("the
paper roll"). No in-app appearance switch — `dark-mode.md › Best practices`: *"Avoid
offering an app-specific appearance setting."* (Overridden at Steven's request in
v1.5 — M17: Piano › Display › Appearance, following the system unless changed.)

Contrast ratios computed from the hex values against their surface (relative
luminance, WCAG formula). Target is `dark-mode.md › Dark Mode colors`: *"at a minimum
… no lower than 4.5:1. For custom foreground and background colors, strive for 7:1."*

### Dark — camera body

| Token | Hex | Role | Contrast on surface |
|---|---|---|---|
| `surface` | `#0E0E0E` | app background | — |
| `surfaceElevated` | `#1A1A1A` | cards, sheets, nav bar | — |
| `hairline` | `#2A2A2A` | dividers, outlines | non-text |
| `disabledGlyph` | `#6B6B6B` | disabled icons only, **never text** | 3.5:1 |
| `contentPrimary` | `#F2F2F2` | titles, body | **16.9:1** |
| `contentSecondary` | `#A3A3A3` | meta, captions | **7.4:1** |
| `contentTertiary` | `#8A8A8A` | eyebrows, timestamps | **5.4:1** |
| `live` | `#D9232E` | the dot. status only | 3.2:1 (non-text; always paired with a word) |

Whites are softened (`#F2F2F2`, not `#FFFFFF`) — `dark-mode.md › Dark Mode colors`:
*"Soften the color of white backgrounds."* The surface is near-black rather than pure
black so elevated layers can read as elevated.

### Light — paper roll

| Token | Hex | Role | Contrast on surface |
|---|---|---|---|
| `surface` | `#F4F1EA` | warm paper | — |
| `surfaceElevated` | `#FBF9F4` | cards, sheets | — |
| `hairline` | `#D8D3C8` | dividers | non-text |
| `disabledGlyph` | `#B8B2A6` | disabled icons only | non-text |
| `contentPrimary` | `#141414` | titles, body | **15.6:1** |
| `contentSecondary` | `#5C5851` | meta | **6.1:1** |
| `contentTertiary` | `#6E6A62` | eyebrows | **4.7:1** |
| `live` | `#C81E28` | the dot | 5.8:1 |

Material 3 mapping (see `ui/theme/Theme.kt`): `primary` = contentPrimary, so every
system button is monochrome. `error` = contentPrimary with an outlined container —
deliberate, so red keeps its single meaning.

## Type

One typeface: the platform's Roboto. `typography.md › Conveying hierarchy`: *"Minimize
the number of typefaces you use, even in a highly customized interface."* Personality
comes from typesetting, not a second font.

No Light or Thin weights anywhere — `typography.md › Best practices`: *"avoid
Ultralight, Thin, and Light font weights, which can be difficult to see."*

| Style | Size / weight | Use |
|---|---|---|
| Display | 34 sp Medium, tracking −0.5 | piece title on Now Playing |
| Title | 22 sp Medium | screen titles, sheet titles |
| Body | 17 sp Regular, line 24 | library rows, settings, copy (HIG default 17) |
| Label | 15 sp Medium | buttons, chips |
| Eyebrow | 12 sp Medium, **UPPERCASE, tracking +1.4 sp** | section labels, meta ("COMPOSER", "2:14 / 3:42") |

The eyebrow is the engraved-camera-label move: small caps-feel labels with wide
tracking, always in `contentTertiary`. Minimum 12 sp clears the 11 sp floor.

**Every numeric readout uses tabular figures** (`fontFeatureSettings = "tnum"`) so
timers and counters do not jitter as digits change. This is the single cheapest
"feels precise" detail in the app.

All sizes in `sp`. Layouts must survive system font scale 2.0× with hierarchy intact
— the roll shrinks before text truncates.

## Structure

Bottom `NavigationBar`, four tabs since v1.1 (Library · Now playing · Keys · Piano; the
sketch below is v1.0's three), short labels, tabs navigate and never act.
Indicator pill is `surfaceElevated`, not a tint. *(v1.5 — M16: the bar is glass, with the
mini player above it on phones; see that section.)*

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ Library              [+] [⌕] │   │ Now playing                  │
│ ───────────────────────────  │   │  Nocturne in E-flat          │
│ ALL  COLLECTIONS  COMPOSERS  │   │  CHOPIN · OP. 9 NO. 2        │
│ FAVORITES  RECENT            │   │ ┌──────────────────────────┐ │
│                              │   │ │  ▌  ▌▌   ▌     ▌▌  ▌     │ │  paper roll
│ Nocturne in E-flat           │   │ │    ▌   ▌▌  ▌  ▌    ▌▌    │ │  travels ↓
│ Chopin · 4:31                │   │ │ ═══════════════════════  │ │  tracker bar
│ Clair de lune                │   │ │ ▐▌▐▌▐▌▐▌▐▌▐▌▐▌▐▌▐▌▐▌▐▌  │ │  84-key strip
│ Debussy · 5:02               │   │ └──────────────────────────┘ │
│ Time                         │   │  1:24 ───────●──────── 4:31  │
│ Zimmer · 3:48                │   │      ⏮      (  ▶  )      ⏭   │
│                              │   │  TEMPO 100%   ● Sent to piano│
├──────────────────────────────┤   ├──────────────────────────────┤
│  Library   Now playing  Piano│   │  Library   Now playing  Piano│
└──────────────────────────────┘   └──────────────────────────────┘
```

### Library
- Search field at the top of the list (Android convention), filters by title and composer.
- A chip row of categories, modelled on Synthesia's groupings: **All · Collections ·
  Composers · Favorites · Recent**. Collections are user-made folders plus the
  collections an imported INDEX.csv names; Composers is derived automatically, grouped
  by surname so "chopin" and "Frédéric Chopin" are one composer (see BUILD_SPEC);
  Recent is by last played, then by import date.
- Rows are text only: title (Body, contentPrimary) over `Surname · m:ss`
  (Eyebrow style but sentence case, contentSecondary); the full composer name lives in
  Rename and in the Composers group header. No thumbnails — removed on
  purpose; they added nothing. 56 dp row height, hairline dividers inset to text.
- Tap plays and switches to Now playing. Long-press → context menu: *Add to
  collection*, *Favorite* / *Unfavorite*, *Rename*, *Delete* (Delete asks; it is
  irreversible).
- `+` in the top bar opens a small bottom sheet: *Add files* (multi-select picker),
  *Add folder* (a whole folder, recursively; an INDEX.csv inside it fills collection and
  composer), *Add zip*. The app also registers as an "Open with" target for `.mid` /
  `.midi` so any file manager imports. Imports run in the background with a hairline
  progress line and "Imported 1,204 of 1,727" copy; duplicates are skipped silently.
- Empty state: *"No pieces yet."* / *"Add a MIDI file to begin."* with the `+` action
  repeated inline.

### Now playing — the signature screen
- Title in Display, composer line in Eyebrow beneath it. *(v1.16 — M43: the small art beside the
  title, 72 dp (56 dp on phones), opens the piece sheet as the title does; the title keeps two lines,
  three on a phone under 360 dp.)* *(v1.18 — M49 retires the small art: the cover is large, 400 dp
  beside the roll, 84 dp in the score's strip, as wide as a phone; the title 40 sp bold, the composer
  19 sp under it. See v1.18 — M49.)*
- **Note canvas**: fills the middle. Background is `surfaceElevated`. 84 lanes for
  C1–B7 (the piano's real range), black-key lanes narrower and slightly darker.
  Upcoming notes are drawn in `contentSecondary`; the moment a note crosses the line it
  brightens to `contentPrimary` for its duration. Notes travel downward at tempo in both
  styles. Nothing here is ever red.
  - *Paper roll* (default): each note is a perforation — a rounded bar 1 dp inset in its
    lane. The **tracker bar** is a 2 dp `contentPrimary` line one third up from the
    bottom, with a 1 dp hairline 6 dp above it (the bar's brass edge, rendered in grey).
    The two thirds above it preview what is coming; the third below shows what just
    played, as the paper does on its way to the take-up spool.
  - *Falling notes*: block-styled bars (square ends), the hit line is the top edge of
    the keyboard strip, no history below it.
  - Setting: Piano tab › *Note display* (v1.5: Piano › Display). Switching while playing is seamless.
- **Keyboard strip** under the roll: 84 keys, monochrome; an active key inverts
  (white key → contentPrimary fill).
- Scrubber: hairline track, 12 dp round thumb, times in Eyebrow with tabular figures.
  Dragging scrubs; release seeks.
- Transport: previous · **play/pause (72 dp circle, contentPrimary fill, surface
  glyph)** · next. Play sits mid-screen-low for one-handed reach. *(v1.5 — M16: the
  scrubber and transport float on glass over the roll's history, and the play control is
  a frosted lens there; the filled circle stays wherever the transport is solid.)*
- Below transport: **TEMPO** with a compact `−  100%  +` stepper (25–200 %), and the
  connection state as *"● Sent to piano"* (dot live) or *"○ Not connected"* (hollow,
  tap to open the Piano tab).
- Empty: *"Choose a piece from the library."*

### Piano
- Connection card: device name (`Steven Piano`), status line with the dot, and one
  button that reads *Connect* or *Disconnect*. While scanning: *"Looking for the
  piano…"* with an indeterminate hairline progress line, never a spinner.
- Preferences (few, infrequent — this is the settings surface):
  *Auto-connect on launch* (switch) · *Note display* (Paper roll / Falling notes) ·
  *Default tempo* · *Transpose* (−12…+12) · *Velocity* (50–150 %) · *Fold notes outside
  C1–B7* (switch, on) · *Skip drum channel* (switch, on). *(Superseded by v1.5: these now
  sit on the hub's APP group and on the Playback and Display pages.)*
- About row at the very bottom, Eyebrow style: "Steven Piano · Made by Steven Jin ·
  v1.1 · eab16a502f679465", and one quiet line acknowledging the library sources
  (MAESTRO, piano-midi.de, Mutopia).
- Playback keeps going with the screen off, from a monochrome media notification with
  play/pause; the piano is silenced on pause, stop, seek, a dropped link, or the app
  being swiped away.
- Errors are in-place, no alerts: *"Can't reach Steven Piano. Make sure it's powered
  on and within range."* with a *Retry* button.

## Motion

Governing rule, `motion.md › Best practices`: *"Add motion purposefully, supporting
the experience without overshadowing it."* and *"Make motion optional."*

| Moment | Motion | Duration / easing |
|---|---|---|
| **Press play** (the one orchestrated moment) | The roll eases from still to moving; the play glyph crossfades to pause; the live dot fades in on the status line. | 320 ms, ease-out. Cancellable — pause at any time. |
| Note crosses tracker bar | Bar brightens secondary → primary | ≤120 ms |
| Piano is playing | Live dot breathes 100 % → 55 % opacity | 2000 ms, sine, loops. Static when merely connected. |
| Tab change | Material fade-through | 240 ms |
| Sheets | Standard bottom-sheet slide | system |
| Frequent taps (rows, chips, steppers) | System ripple only. No custom motion. | — |

`motion.md › Providing feedback`: *"In apps, generally avoid adding motion to UI
interactions that occur frequently."* — hence nothing custom on rows and chips.

**Reduced motion** (Android "Remove animations" / animator scale 0): the roll still
moves — it is content and progress, not decoration — but the dot does not breathe,
the play transition is a cut, and fade-through becomes a cut. Progress is always also
shown numerically, so motion is never the only carrier of meaning.

**Haptics**: one light tick on play and on pause. Nothing else.

## Accessibility floor

- 48 dp minimum touch targets (Material); play control 72 dp.
- Body 17 sp, nothing below 12 sp, no Light weights.
- Every text-on-surface pair ≥ 4.5:1; primary/secondary text ≥ 7:1 in dark (table above).
- Nothing conveyed by colour alone: connected = dot **and** the word; playing = dot
  **and** the pause glyph **and** the moving roll **and** the timer.
- Every icon-only control has a `contentDescription`. TalkBack order follows visual
  order. Text uses `sp` and layouts are tested at 2.0× font scale.

## Copy

Sentence case throughout (Material). Labels say what happens: *Add MIDI file*,
*Send to piano*, *Connect*, *Delete piece*. An action keeps its name across the flow.
No exclamation marks, no apologies in errors, no jargon ("MIDI file", not "SMF").

## Self-critique (what was removed)

- Library thumbnails — removed; text rows are calmer and faster to scan.
- A red *Play* button — rejected; it would give red a second meaning.
- A second display typeface — rejected; tracking and weight do the job.
- Coloured Synthesia-style falling notes — rejected. A *monochrome* falling-notes
  style was added at Steven's request as a switchable second style; the paper roll
  stays the default because it is truer to the instrument and to the Leica brief.

Would this plan appear for a different product? The palette discipline could; the
paper roll, the tracker bar, and the 84-lane C1–B7 range could not. That is the test.

---

# What later versions changed

The rules above are v1.0's. These replaced parts of them; the sections named are in `docs/archive/DESIGN-history.md`, and a
citation in the code such as `DESIGN.md › v1.10.1, D2` is a heading there. Search it, don't read it whole.

- **Tablet first.** Design and check at tablet size, both orientations, then phone: a thin rail with the name under the mark
  (*v1.25 — M59*), the Library like Apple Music (*v1.37 — M73*), the Piano tab in groups with search (*v1.13 — the Piano tab*).
- **Glass.** Liquid Glass on the functional layer only (bars, rail, sheets, menus, transport), never on content (*v1.9*).
- **Surfaces.** Warm ink and paper everywhere; true black only on the resting screen (*v1.7.1*). The web panel mirrors the
  app, dark ink by default (*v1.18 — M47*, *v1.20 — M53*).
- **Colour means one thing each.** Red: notes going to the piano. Yellow: a sounding note. Amber: needs attention. The colour
  aura: the AI at work, and nothing else (*v1.13 — motion*).
- **Motion.** Smooth and responsive, on transitions, presses and progress. Nothing loops by itself but the aura, and every
  animation has a reduced-motion cut (*v1.13 — motion*).
- **Studio** is a tab of its own, chat-like (*v1.12 — Studio as a tab*, *v1.13.1*). **Power and safety wording**: *v1.26*, *v1.36 — M72*, *v1.38 — M75*.

# v1.40 — M78: the key sweep only after a fault, and a 25-minute cool-down

Steven, 2026-11-01: "test only after a power fault shut off where keys are stuck, and add a cool down period for it as
well of 25 min, but in plug settings that could be bypassed"; then an 80 W leeway on every power trip. **The sweep**
(every key with the microphone) runs only in a safety check a fault CUT called for: a power trip, `!i2cfails` rising,
`!active` after a piece, a coil on. Every other check (Run the safety check with no fault, the off-by-default 6th piece)
is quick and silent: the stop, `!active`, the boards, then `!i2cfails` and the idle draw on two fresh adverts; System
says "quick check, no keys struck". Missed keys still only warn; Test every key is unchanged. **The cool-down**: a fault
CUT begins 25 minutes in which nothing switches the plug on (Play, Turn the piano on, Keys, the On hours, the panel, the
safety check, the firmware update; an "on" advert is CUT), kept across restarts. "Cooling down after a fault · 24 min
left" is on Play's refusal (the card, for a moment), Piano › Power plug, the Piano tab, System and the panel; then "Run
the safety check to play again" until the sweep check passes. **Skip the cool-down** (Piano › Power plug, only while
cooling, behind the kiosk PIN as the page is) asks "The coils may still be hot. Skip the 25-minute cool-down?", is
logged, and leaves the check required. The On hours ending, the grace, Turn the piano off and the piano not starting
never cool down. **The 80 W leeway**: the draw cuts the 24 V only at 80 W or more above what is explained (idle, plus the
notes and the pedal while playing): playing for 5 s, idle on two readings, after the piece on three fresh adverts, the
usual idle at rail-up; the rail-up's absolute 100 W cap stays, and the 10 W / 30 s coil rule only logs. Brief
`~/.claude/plans/m78-fault-cooldown-brief.md`.

# v1.41 — a new version of a piece takes the old one's place

Steven, 2026-11-01: the library's band arrangements (about 360) are becoming piano versions and thousands of new songs
are coming, but an import knew a file only by its bytes, so each new version would have arrived beside its old one, and
pieces are deleted one at a time. Of three ways out (wiping the app's data, which loses favorites, plays, covers and
settings; deleting the old versions by hand; replacing in place), Steven chose the last. **INDEX.csv's `replaces`** (an
optional column after `path`): the SHA-256s of earlier versions of the row's file, apart by spaces; anything that is not
64 hex digits is ignored, and an index without the column reads as before. **On import** a file the library already has
is a duplicate, whatever its row names. Otherwise, when the row names an earlier version the library holds, the first
added of them takes the new file in place: the file, its size, length and notes are the new version's (so are the
length on its row, the Calm channel's notes a second and its roll card); the piece keeps its id, and with it its
favorite, its plays and place in Recent, a rename, a move to Classical or Modern, its cover and its playlists, and it
joins its row's playlist as a new piece would. The other earlier versions stay; a row naming none the library holds
brings a new piece. Every import does this: Add zip, Add folder, the web panel, Steven's library. **The words**: the
import bar "Imported 1,204 pieces, replaced 360 · in the playlist Pop." or "Replaced 360 pieces."; the web panel's
tally "Imported 1,204 pieces · 360 replaced · 6 already there".

# M79: each school's name

Steven, 2026-11-02: a second, identical system goes into Fessenden School (its own piano, controller, plug and tablet),
enrolled in the same Steven Piano Cloud as a second piano, and each school's panel should say whose it is. **The name
is the console's**: the piano's name there (`pianos.name`, "Steven Jin's Piano · Fessenden School"; Brooks keeps
"Brooks Arts Center Piano") comes to the tablet in the relay's hello, and a rename in the console reaches a connected
tablet at once; the tablet keeps it with its enrolment and Forget this cloud clears it. **The panel** shows it as the
page's title, the rail's name and the eyebrow of the PIN and offline cards; before the state arrives, and with no name
(a tablet not enrolled, or a piano still "New piano" in the console), it says "Steven Piano", so a PIN page learns the
name only once signed in. The relay's own offline page carries the name too when its room knows it. On the tablet,
Piano › Web panel › OVER THE INTERNET says it in one quiet line, "The panel shows this piano as …, its name in the
console". The app is still Steven Piano everywhere else. **A school's short address**: a tiny Worker,
`playerpiano-stevenjin.sjengineering.workers.dev`, sends every request on to that piano's panel on the relay (`/` the
panel, `/request` the guest page), and answers "This piano isn't set up yet." until its piano's id is set, once
Fessenden's tablet is enrolled.


# v1.43 — album covers from the song zip

Steven, 2026-11-02: the song search on the Mac looks up each modern song's album art (Apple's catalogue, as the
tablet's own lookup does), and he wants it on the tablet too, without the tablet asking Apple again for two thousand
songs one search every 3.5 s. **INDEX.csv's `cover` and `cover_album`** (optional, found by their header names): a
JPEG or PNG beside the music, its path relative to the index as `path` is, and its credit, "album · artist" as a found
cover's; a cover that is not .jpg, .jpeg or .png, or whose path climbs out with "..", is ignored, and an index without
them reads as before. **On import** of a zip (Add zip, the web panel's upload, Steven's library), once the pieces are
saved, each piece whose row names a cover, new, replaced or already there, is offered it, once: the cover becomes the
piece's own as an album cover the lookup found does. It never goes over a cover the piece has (drawn by Studio or a
recording, found by the lookup, chosen by hand), nor where one was taken away in the panel; it is recorded as found,
so Apple is never asked for that piece's cover, and the covers' rule treats it as a found one: a K-pop piece keeps its
plain cover (M73), a House & Dance piece shows it. A cover over 1 MB, missing from the zip or unreadable is skipped;
it never fails the import. A folder's or files picked one by one bring no covers. The piece sheet's credit reads
"Cover: 25 · Adele" (or "Cover: from the song zip" with none). **The words**: the import bar adds "1,966 covers
added."; the web panel's tally "1,966 covers added".

# v1.45 — the cool-down: 10 minutes, and Skip on the card that refuses Play

Steven (2026-11-07, multiple choice): "Shorter, plus Skip". The cool-down after a fault CUT is **10 minutes** (25 since
v1.40). The **Cooling down** card that refuses Play gains **Skip the cool-down**, a text button before OK (the safe answer stays
the last and plainest): the kiosk PIN while the kiosk keeps the settings locked, then Piano › Power plug's own confirm ("The
coils may still be hot. Skip the 10-minute cool-down?", Skip and Cancel). The card stays up while they're asked, and closes
once answered; skipped, the next Play runs the safety check the fault called for, every key, as before. The tablet only:
the panel never skips a cool-down, as nobody there can see the piano.

# v1.45 — M80: the LED strip in the album art's colour, or one picked at the tablet

Steven (2026-11-07, multiple choice): exact cover colours, "all of the above with options" for the looks, and "pick a custom
colour right at the tablet". Piano firmware 2.1.2 (the Player Piano chat) gives the strip `ledcolor` (kept), `ledtint` (live,
never kept), `ledfade` and `ledambient`; nothing new shows until a piano says `!ledcolorproto=1`.

- **Piano › Lights and screen** gains **Colour** (Album art · Custom · Palette; Palette only in Reactive, as the palettes
  only colour notes), **Custom colour** (twelve named swatches, then Hue and Saturation, the chosen one ringed and shown at
  the row's end), and in Reactive with Album art or Custom **Background glow** (Off · Low · Medium · High). Under Strip
  set-up, MOTION gains **Colour change** (Instant · 0.5 s · 1.5 s · 3 s). Rows that don't apply grow out of the page, a cut
  under reduced motion.
- **The cover's colour** comes from the backdrop's own histogram: the most vivid colour with a fair share, raised to at
  least 0.55 saturation and full value, as LEDs show grey and dark badly. A grey cover, or none, shows the custom colour.
- **LED colours are content:** they're the piano's, so the one-meaning-per-colour rule of the app's chrome doesn't bind
  them; the swatches have no glass, and the page's own chrome stays monochrome.
- A palette chosen elsewhere (the web panel, the piano's screen) moves the tablet's Colour to Palette rather than forcing
  Solid back. The web panel shows none of this.

# v1.46 — M81: the 24 V between pieces, the faster key check, the strip at rest

Steven (2026-11-08, multiple choice): "Day, fixed after faults", "About 25 s", "Smooth fade".

- **The 24 V between pieces.** Within the On hours it stays on between pieces, as since 1.39, and now also after a fault:
  through the cool-down nothing comes on, and the next piece, once its safety check passed and the piano came up with every
  board answering, puts the hours back. Overnight nothing changes: the 24 V is off unless a person at the tablet wakes it, so a
  tablet that froze at night can't leave the plug on unwatched (the SwitchBot has no timer of its own).
- **The key check** strikes one key every 0.3 s, low to high, once (about 26 s; 39 s before). It still runs only in a check a
  fault called for. Keys not heard still only warn.
