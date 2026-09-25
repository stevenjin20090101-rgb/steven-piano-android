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
   error red. Errors are copy plus an outlined banner.

## Principles applied (Apple HIG, adapted for Android)

| Principle | How it shows up |
|---|---|
| Purpose | One job: pick a piece, send it to the piano, watch it play. Every screen serves that. |
| Familiarity | Material 3 navigation and controls; identity lives in colour, type and the roll. |
| Simplicity | Three tabs. Text-only library rows. One accent, one signature moment. |
| Craft | Tabular figures on every timer, hairline rules, 48 dp targets, contrast computed not guessed. |
| Delight | The roll starting to turn. That is the whole emotional budget, spent once. |

`branding.md › Best practices`: *"Ensure branding always defers to content"* and
*"Resist the temptation to display your logo throughout your app."* — no wordmark
inside the app; the name appears in the launcher and once, as plain text, in the About
row at the bottom of the Piano tab.

## Colour

Dark is the default ("the camera body"). Light follows the system setting ("the
paper roll"). No in-app appearance switch — `dark-mode.md › Best practices`: *"Avoid
offering an app-specific appearance setting."*

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

Bottom `NavigationBar`, three tabs, single-word labels, tabs navigate and never act.
Indicator pill is `surfaceElevated`, not a tint.

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
- Title in Display, composer line in Eyebrow beneath it.
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
  - Setting: Piano tab › *Note display*. Switching while playing is seamless.
- **Keyboard strip** under the roll: 84 keys, monochrome; an active key inverts
  (white key → contentPrimary fill).
- Scrubber: hairline track, 12 dp round thumb, times in Eyebrow with tabular figures.
  Dragging scrubs; release seeks.
- Transport: previous · **play/pause (72 dp circle, contentPrimary fill, surface
  glyph)** · next. Play sits mid-screen-low for one-handed reach.
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
  C1–B7* (switch, on) · *Skip drum channel* (switch, on).
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

# v1.1 — tablets, Keys, staff, piano settings

Additions Steven asked for after v1.0. Everything above still holds; this section only
adds. Four things: the app adapts to tablets, a playable keyboard, a staff view beside
the waterfall, and the piano's own lighting and feel settings adjustable from the app.

## Structure change: four destinations

**Library · Now playing · Keys · Piano.** Keys is new. The Piano tab keeps the
connection card and the app's preferences and gains the piano's own settings (below).
Glyphs: Library keeps the books, Now playing keeps the roll, **Keys takes the keyboard
glyph**, and **Piano moves to a sliders glyph** (three horizontal lines with knobs).

## Adaptive layout (tablets and phones in landscape)

Window size classes decide the frame; nothing else changes with size.

| Width class | Frame | Now playing | Keys |
|---|---|---|---|
| Compact (< 600 dp: phones portrait) | Bottom `NavigationBar`, as v1.0 | One canvas; *Note display* chooses Paper roll / Falling notes / **Staff** | Two octaves visible, scroll for more |
| Medium (600–840 dp: small tablets, phones landscape) | `NavigationRail` on the left, labels shown | **Stacked**: staff on top (⅓), notes below (⅔) | About four octaves visible |
| Expanded (≥ 840 dp: tablets landscape) | `NavigationRail` on the left | **Side by side**: staff left, notes right, equal widths; keyboard strip and transport span the full width beneath | All 84 keys visible, no scrolling |

- Never a rail and a bar at once. The rail carries the same four glyphs and labels.
- Reading surfaces (Library list, Piano settings) cap their content width at 720 dp and
  centre it; rows keep their 56 dp height and 17 sp body.
- A new preference on wide screens, **Wide layout**: *Staff and notes* (default) ·
  *Notes only* · *Staff only*. On compact widths the *Note display* preference gains
  *Staff* as a third option.
- Landscape phones use the Medium layout. Rotation keeps position and state.

## Keys — a playable keyboard

The piano, from the tablet. Every tap becomes a Note On over the same link the pieces
use; the piano's own LED strip reacts as it does to any note.

- **The keyboard** spans the screen's width at the bottom, never taller than 320 dp or
  45 % of the screen's height (taller keys read as a barcode): 84 keys, C1–B7, white
  keys `surfaceElevated` with hairline gaps, black keys `contentTertiary` at 60 % height
  overlapping the white ones, the octave letters (C1 … C7) in Eyebrow style at the
  bottom of each C. A pressed key inverts to `contentPrimary` for as long as the finger
  is down. Multi-touch: chords. Sliding across keys plays a glissando (off, then on, as
  the finger crosses a boundary).
- **Loudness by touch position**: the vertical position of the touch on the key sets
  velocity, top = soft (velocity 24), bottom = loud (127), linear in between; black keys
  the same across their own height. A small Eyebrow readout **VELOCITY 84** under the
  keyboard shows the last value for a second so the mapping can be learned.
- **Scrolling** on narrow screens: a mini-map above the keyboard, the full 84-key strip
  in miniature with a viewport rectangle in `contentPrimary`; drag it to move, or use
  the ‹ › octave buttons at either end of it.
- **Sustain**: one outlined toggle button, *Sustain*, latching: down sends CC64 = 127,
  up sends CC64 = 0. It reads *Sustain on* while down. Leaving the screen releases it.
- **Connection line** as on Now playing: "● Sent to piano" / "○ Not connected"; when
  not connected the keys still invert (so the screen is honest about what it can do)
  and the line explains.
- **Safety**: on leaving the Keys screen, on app background, and on link drop every
  held key gets its Note Off and the pedal is released, through the same silence path
  as playback. The same-key 100 ms guard applies to taps too.
- No haptics on keys (frequent interaction). No sound from the phone.

## Staff — the sheet-music view

Honest about what it is: pitch on a grand staff, in time, in sync with the roll. Not
engraved notation (no beams, rests, ties or voices).

- **Grand staff**: treble and bass, five 1 dp lines each in `contentTertiary` (the hairline
  grey all but vanishes on the dark surface), staff line spacing 6 dp,
  40 dp between the staves, clefs at the left edge in `contentSecondary`. Notes at or
  above middle C sit on the treble staff, below it on the bass staff; middle C gets its
  own ledger line. Ledger lines as needed (the piano's C1 needs several below the
  bass staff; they are drawn, not clipped).
- **Notes**: filled note heads (an ellipse 7 × 5 dp, tilted), a sharp `♯` before the
  head for black keys (no key signatures; sharps only, consistently), and a hairline
  **duration bar** trailing to the right of each head for the note's length. Chords
  stack; heads a second apart offset to the right, as engraving does.
- **Time** runs left to right at the same pixels-per-second as the roll; the
  **playhead** is a 2 dp `contentPrimary` vertical line one third from the left. Heads
  to the right are upcoming (`contentSecondary`), a head brightens to `contentPrimary`
  as it crosses the playhead and stays bright for its duration (the same 120 ms flip
  as the roll; a cut under reduced motion).
- **Glyphs**: clefs, sharps and note heads from the Bravura music font (SIL Open Font
  Licence, bundled) so they look like music; if the font cannot be bundled, simplified
  vector clefs drawn by hand are acceptable and must still read as G and F clefs.
- Reduced motion, font scaling and colours exactly as the roll.

## Piano settings — the instrument's own lighting and feel, from the app

The firmware exposes its console commands over a Bluetooth text channel (see
`firmware/docs/BLE_SETTINGS.md`). The app reads every value on connect and writes a
value the moment a control changes; the piano stores them itself.

- Lives on the **Piano tab**, under the connection card and above the app preferences,
  as sections with Eyebrow headers: **LIGHTING · FEEL · PEDAL · DIAGNOSTICS**. All of
  it is disabled, greyed with `disabledGlyph` handles, until the piano is connected and
  has answered the read; a one-line note explains: "Connect to the piano to adjust its
  settings."
- Controls follow the value's shape: switches for on/off, steppers with tabular
  figures for numbers with few steps (count, offset, gap in ms), a hairline slider
  with the number beside it for continuous ones (brightness, volume, curve), and a
  single-choice row for palettes and presets. Every control shows its unit
  (%, ms, LEDs) in the eyebrow and its live value.
- **Feel presets** are a row of chips; choosing one applies the preset on the piano and
  every dependent control updates to what the piano reports back, so the user sees what
  a preset actually did.
- **Lighting preview**: no on-screen strip; the piano is the preview. A *Test LED*
  action lights one key's LED (the firmware's `ledtest`) so offset and scale can be
  aligned from the app while standing at the piano.
- **Diagnostics** shows the piano's status text as it reports it (boards found,
  temperature, uptime) in Body on `surfaceElevated`, with *All keys off* as an outlined
  button. No red anywhere; a fault reads in words.
- Writes are rate-limited (150 ms after the last change) so dragging a slider does not
  flood the piano; the app never sends a value it has not shown.
- If the piano runs older firmware without the channel, the whole section shows one
  line: "This piano's firmware doesn't offer settings over Bluetooth yet." and nothing
  else, so the app keeps working exactly as v1.0.

---

# v1.2 — byline, playlists, queue, artwork, score pages

Steven's requests after using 1.1. Everything above still holds; this section adds and,
in two named places, overrides. The approved plan (`~/.claude/plans/humming-prancing-finch.md`)
holds the engineering detail; this section is the visual authority.

## Byline (overrides the v1.0 "no wordmark" rule, at the owner's request)

Under every tab title, in Eyebrow style and `contentTertiary`: **PLAYER PIANO · BY STEVEN JIN**.
Same position on all four tabs, no divider, no animation (a plain cut: the press-play
moment stays the app's only orchestrated motion). The About row drops the app's name so
the name appears exactly once per screen ("Made by Steven Jin · v1.2 · eab16a502f679465").
Design note: `branding.md › Best practices` warns against repeating a brand through an
app; the owner chose this line knowingly, and the design keeps its cost to one 16 sp row.

## Playlists (replaces Collections, one concept)

- The chip reads **Playlists**. Imported INDEX sets appear as playlists too.
- A playlist is a page: cover art (96 dp, `shapes.medium`), name in Title, an Eyebrow
  "12 pieces · 41:20", then a filled 56 dp **Play** circle and an outlined **Shuffle**
  button side by side, then the rows.
- Rows inside a playlist carry a trailing **drag handle** (48 dp) for reordering; the row
  menu also offers *Move up* and *Move down* so reordering works without dragging.
- Row menu everywhere, in three groups with hairline separators (context menus scan best
  with at most three groups, destructive items last): **Play next · Add to queue** |
  **Add to playlist · Favorite · Rename** (+ *Move up · Move down* inside a playlist) |
  **Remove from playlist** (inside a playlist) · **Delete**. Playlist menu, also on
  long-press of a playlist tile: *Rename · Change photo · Delete*. Composer tiles
  long-press to *Play all · Shuffle*. Menus appear wherever items appear, so people
  learn where to find them once.
- The dragged row lifts onto `surfaceElevated` and the others slide out of its way; the
  drag handle is described as "Reorder" to screen readers.
- The Playlists and Composers chips show **grids of tiles** (2 columns on phones, 3 on
  medium widths, 4 on tablets in landscape): square art, name in Body, count in Eyebrow.
  8 dp gutters, inside the 720 dp reading column.

## Up next, shuffle and repeat

- A queue glyph in the Now playing header opens the **Up next** sheet: a Material bottom
  sheet with its drag handle, swipe-away, the current piece first, then the coming pieces
  with drag handles ("Reorder") and a remove glyph ("Remove from queue"), and *Clear* at
  the top right. Empty: "Nothing up next." Reordering here is the queue's order; it does
  not touch any playlist. Shuffle and Repeat announce their state ("Shuffle on",
  "Repeat all").
- **Shuffle** and **Repeat** sit at the two ends of the transport row, outside previous
  and next: `contentTertiary` when off; `contentPrimary` with a 4 dp dot beneath when on.
  Repeat cycles off → all → one; "one" shows a small "1" glyph. Both remember their
  state. Shuffle keeps the current piece where it is and restores the original order when
  turned off. Repeat one restarts after the same 1.5 s pause as a normal advance.
- The system media controls show the queue and the two modes.

## Artwork and notes (the app now uses the internet, for two hosts only)

- Composers get their Wikipedia portrait and a two-sentence blurb in the composer
  header. Pieces get a **Piece sheet** (a bottom sheet with its drag handle; tap the title
  on Now playing, or *About this piece* in the row menu): art, title, composer eyebrow,
  the piece's Wikipedia extract if a page exists, otherwise the composer's, a *From
  Wikipedia* link, and the attribution line in Eyebrow: "Text from Wikipedia, CC BY-SA
  4.0 · portraits from Wikimedia Commons". With nothing found: "No notes found for this
  piece."; offline: "Notes need an internet connection."
- Every art surface (tile, cover, row portrait, sheet art) sits on `surfaceElevated`
  inside a 1 dp hairline outline, so photographs read as prints mounted on the
  instrument; the red dot stays the only interface colour.
- Piece rows show a **40 dp composer portrait** at the left (this reverses the v1.0
  "no thumbnails" call, at the owner's request); tiles show portraits; playlist covers
  are the owner's photo (photo picker), else the first piece's composer portrait, else a
  monogram tile (the initial in Display type on `surfaceElevated`). A composer with no
  portrait gets a 2×2 mosaic of their pieces' roll cards rather than a monogram, so
  Bach, Beethoven and Brahms never collapse into three "B" tiles.
- Pieces with no portrait get a **roll card**: their own first 20 seconds drawn as
  perforations, monochrome, generated on the device.
- Portraits show **in colour** by default. A Piano-tab switch **Artwork in black and
  white** applies a saturation-0 filter for the Leica Monochrom look. The rest of the
  interface stays monochrome either way; red still means live and nothing else.
- Fetching is quiet: composers are fetched automatically after an import (a foreground
  notification "Fetching artwork and notes", one request at a time); pieces are fetched
  when their sheet opens. A `+`-sheet action *Fetch artwork and notes for every composer*
  and a switch *Fetch artwork automatically* (on) with one line beneath it: "Uses
  Wikipedia. Nothing about you is sent." (repeated in the About area). While a fetch
  runs, the Library shows the same hairline progress row imports use: "Fetching artwork
  12 of 61". Offline or failing: the fallback art shows and nothing else is said.

## Score (replaces "Staff")

- The piece laid out as **systems of bars** stacked on **pages**: 4 bars per system on
  tablets in landscape, 3 at medium widths, 2 on phones. **Two pages side by side when
  the score panel itself is at least 840 dp wide** (Score only, tablet, landscape); one
  page otherwise. Bar lines, clefs at each system, bar numbers as Eyebrows at each
  system's start. Staff lines `contentTertiary`, clefs `contentSecondary`.
- A 2 dp `contentPrimary` cursor moves within the current system; sounding notes brighten
  with the same 120 ms flip as the roll. Pages turn so the cursor is always visible: with
  two pages the left page turns to the page after next while the right page is being
  finished, and vice versa.
- Agency: swipe left or right to look at other pages; a small outlined **Follow** chip
  appears while detached, and tapping it (or the next automatic turn) resumes following.
  Tapping a bar seeks to it (the piano is silenced first, as with any seek). One eyebrow
  line-height is reserved above each system so bar numbers never touch the staff at
  large text sizes.
- **Note values** when the file quantizes cleanly (sequenced files): hollow whole and half
  heads, filled quarters and shorter, stems, eighth and sixteenth flags, dots. Performed
  files (MAESTRO) keep filled heads with duration bars. Sharps only; no beams, rests, ties
  or key signatures — said plainly in the README.
- The grand staff keeps 6 dp line spacing; the gap between systems is 32 dp so the
  lowest and highest ledger lines fit.

## Rotation

Rotating while playing live keeps every held key sounding and keeps the keyboard's
first visible key (clamped to the new width). Nothing on any tab resets on rotation.
