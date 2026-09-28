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
  *Staff* as a third option. *(Since v1.2 the staff is the score, and the choices read
  **Score and notes** · **Notes only** · **Score only**, and **Score**; the saved values
  keep their v1.1 names.)*
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
  as sections with Eyebrow headers: **LIGHTING · FEEL · PEDAL · DIAGNOSTICS**. *(Superseded
  by v1.5: the piano's settings are four pages opened from the hub's PIANO group — Feel,
  Lighting, Pedal, Firmware and status — in the sections that section lists.)* All of
  it is disabled, greyed with `disabledGlyph` handles, until the piano is connected and
  has answered the read; a one-line note explains: "Connect to the piano to adjust its
  settings."
- Controls follow the value's shape: switches for on/off, steppers with tabular
  figures for numbers with few steps (count, offset, gap in ms), a hairline slider
  with the number beside it for continuous ones (brightness, volume, curve), and a
  row of chips for modes, palettes and presets (the chosen chip carries a check). Every
  control shows its unit (%, ms, LEDs) in the eyebrow and its live value.
- **Feel presets** are a row of chips; choosing one applies the preset on the piano and
  every dependent control updates to what the piano reports back, so the user sees what
  a preset actually did.
- **Lighting preview**: no on-screen strip; the piano is the preview. A *Test LED*
  action lights one key's LED (the firmware's `ledtest`) so offset and scale can be
  aligned from the app while standing at the piano.
- **Strike test** (under the floors and the ceiling): a key chosen with a stepper, shown
  by name (C4), and *Floor* / *Ceiling* strike it once as softly as its floor allows or as
  hard as the ceiling (the firmware's `testmin` / `testmax`), so the floors can be set by
  ear.
- **Diagnostics** shows the piano's status text as it reports it (boards found,
  temperature, uptime) in Body on `surfaceElevated`, with *All keys off* as an outlined
  button. No red anywhere; a fault reads in words. *(As built, and in v1.5's Firmware and
  status page: the version; the seven power boards as OK / MISSING words, I²C errors, the
  pedal board and uptime as read-only rows; two read-only key-force rows, "White-key
  force ×1.00" and "Black-key force ×1.00", with "Key force is set at the piano's USB
  console."; then Read status, whose report appears on `surfaceElevated` under it, and All
  keys off and Save now side by side, all three outlined buttons.)*
- A refusal ("ledbright out of range") shows as an outlined banner, "The piano said: …"
  with *Dismiss*, directly under the control it concerns.
- Writes are rate-limited (150 ms after the last change) so dragging a slider does not
  flood the piano; the app never sends a value it has not shown.
- If the piano runs older firmware without the channel, the whole section shows one
  line: "This piano's firmware doesn't offer settings over Bluetooth yet." and nothing
  else, so the app keeps working exactly as v1.0. *(v1.5: the rule holds per page; the
  line also stands under the hub's connection card.)*

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
  button side by side, then the rows. *(v1.5 — M16: Play floats as a glass circle at the
  list's bottom end; Shuffle stays in the head.)*
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
  with the same 120 ms flip as the roll. *(v1.5 — M16: they turn the sounding yellow.)* Pages turn so the cursor is always visible: with
  two pages the left page turns to the page after next while the right page is being
  finished, and vice versa.
- Agency: swipe left or right to look at other pages; a small outlined **Follow** chip
  appears while detached, and tapping it (or the next automatic turn) resumes following.
  Tapping a bar seeks to it (the piano is silenced first, as with any seek). One eyebrow
  line-height is reserved above each system so bar numbers never touch the staff at
  large text sizes.
- **Note values** when the file quantizes cleanly (sequenced files): hollow whole and half
  heads, filled quarters and shorter, stems, eighth and sixteenth flags, dots. Performed
  files (MAESTRO) keep filled heads with duration bars. Key signatures are drawn and
  accidentals spelled in the key (pulled forward from v1.3); no beams, rests or ties
  yet — said plainly in the README.
- The grand staff keeps 6 dp line spacing; the gap between systems is 32 dp so the
  lowest and highest ledger lines fit.

## Rotation

Rotating while playing live never cuts a note: Android cancels every touch when the
display turns, so while any key is held the Keys screen holds its orientation and the
requested rotation happens the moment the last finger lifts. The keyboard keeps its first
visible key (clamped to the new width), the pedal stays where it was, and nothing on any
tab resets on rotation.

---

# v1.3 — score fidelity and the waterfall format

Steven's reference: a transcription app showing engraved notation above a waterfall,
with chord symbols, finger numbers on notes and bars, the two hands in two colours, and
the sounding keys lit on a keyboard. v1.2 already has the stacked score-over-waterfall
layout, the lit keyboard strip, the playhead and the paged score with bar lines and note
values. v1.3 adds the rest, in the app's own language.

## Score fidelity (M13)

- **Key signatures** at every system, from the file's key signature; accidentals are
  spelled in the key (an E♭ piece reads in flats), with naturals where needed and one
  accidental per pitch per bar, as engraving does. No key signature in the file: sharps,
  as before.
- **Beams** join flagged notes within one beat on one staff (single beam for eighths,
  double for sixteenths); stems of a beamed group share a side. **Rests** fill gaps of a
  sixteenth or longer on each staff. **Ties** carry a note across a bar line and join
  durations that no single value can write (a quarter tied to a sixteenth). All Bravura.
- **Tempo mark** (♩ = 80) at the first system from the tempo map; a new mark where the
  tempo changes by more than 10 %. **Dynamics** (pp · p · mp · mf · f · ff) under the
  treble staff where the bar's average velocity moves into a new band, in Bravura's
  dynamics glyphs. Performed files show dynamics only when the change is clear.
- Everything here is monochrome and follows the existing colours: staff `contentTertiary`,
  glyphs `contentSecondary`, sounding notes `contentPrimary`, playhead `contentPrimary`.
  *(v1.5 — M16: sounding notes are the sounding yellow; the playhead stays `contentPrimary`.)*
- Honest limits, in the README: no voices within a hand, no tuplets, no grace notes, no
  pedal markings; performed files (MAESTRO) keep heads and duration bars.

## The waterfall format (M14)

- **Hands.** Files with two tracks named for the hands (or two tracks at all, as
  piano-midi.de's) split by track; otherwise by a moving pitch split. The right hand's
  notes are **filled** bars, the left hand's are **outlined** bars (1 dp hairline, the
  elevated surface inside), on the roll, the falling notes and the keyboard strip
  (outlined keys for the left hand). On the score, hands map to staves: the right hand on
  the treble staff even below middle C.
- **Hand colours** (a Piano-tab switch, off by default): tints the left hand green and the
  right hand blue, muted for the dark and light surfaces, on the waterfall bars and the
  keyboard highlights only. This is the one place colour may enter the interface besides
  artwork; red keeps its single meaning. Tokens: `handLeft`, `handRight` in `Color.kt`,
  each with a light and dark variant at ≥ 3:1 against its surface.
- **Suggested fingering.** Computed per hand from the notes (a cost model over stretch,
  crossing, thumb on black keys and repeated notes), shown as small tabular numerals
  inside the waterfall bar at its leading edge, and above right-hand heads / below
  left-hand heads on the score. A switch **Fingering** (on). Chords of five notes or more
  get thumb-to-little assignment by spread. It is a suggestion, and the README says so.
- **Chord names.** Detected per beat from the sounding pitch classes weighted by duration
  (major, minor, dim, aug, sus2, sus4, 6, 7, maj7, m7, add9, maj9; slash bass when the
  lowest note is not the root), spelled in the key. Shown above the score at each change,
  and at the waterfall's left edge as an Eyebrow label at the time the chord begins,
  scrolling with the notes, exactly where the reference puts them. A switch **Chord
  names** (on).
- Layout is unchanged: score above, waterfall below on medium and expanded widths; the
  keyboard strip under the waterfall; on phones, whichever Note display is chosen.

## Audit (before every release)

A code audit runs before each release and its fixes ship in the same release: file
imports (zip paths, content links), the MIDI parser on malformed files, the network
layer (HTTPS only, redirects, size caps, headers), exported components and intents,
foreground services and notifications, storage and backups, permissions, dependency
vulnerabilities, and release signing with a real release key kept outside the repo.

---

# v1.4 — updates that come to the piano, and bug reports that come back

Steven asked for the app to update itself and for a way to get fixes out quickly. Three
pieces, all quiet: an updater, silent installs on the school tablet, and a diagnostics
share. No accounts, no analytics, nothing leaves the device unless Steven shares it.

## Updates

- The app checks for a newer release on launch and once a day: one small manifest fetched
  over HTTPS from the app's GitHub repository (the only new host, plus GitHub's download
  hosts for the file itself). Wi-Fi or data, a few hundred bytes.
- When a newer version exists, the Piano tab shows a row above Preferences, Eyebrow header
  **UPDATE**, then "Steven Piano 1.4 is available" in Body with the release notes beneath
  in `contentSecondary`, and one filled button **Update**. Nothing red; no badge on the tab.
  *(Superseded by v1.5 for its place: on the hub, between the connection card and the
  groups.)*
- Update: a hairline progress row ("Downloading 1.4 · 1.2 of 2.3 MB"), the file's hash is
  checked against the manifest, then Android's installer opens; one confirmation tap and
  the app relaunches on the new version. Android itself refuses any file not signed with
  the release key, so a wrong or tampered file can never install.
- On a tablet set up as the app's **device owner** (the school tablet, a one-time cable
  setup), the same flow installs with no tap and the row reads "Updated to 1.4; restart
  to use it" with a **Restart** button.
- Failures are one line under the row, in words: "Couldn't reach the update server." /
  "The download didn't match the release; try again." Retry is the same Update button.
- A switch in App preferences: **Check for updates automatically** (on). The row can also
  be triggered by hand: "Check now" as a text button under the switch. *(v1.5: both are
  rows of the hub's APP group; Check now is an outlined button in an action row, with what
  the last check found under it.)*

## Diagnostics

- The app keeps its own last crash reports (the last five) and the last 500 lines of the
  connection log on the device. Nothing is sent anywhere by itself.
- Piano tab, under Diagnostics: **Share diagnostics** (outlined button; *v1.5: the last row
  of the hub's APP group, an action row*) builds one small
  zip — app version and build, device model and Android version, the crash reports, the
  connection log, the app preferences (no library contents, no photos, no Wikipedia
  text) — and opens the system share sheet, so Steven can send it by any means. The row
  explains it in one Eyebrow line: "A small file with the app's logs. Nothing personal."
- After a crash, the next launch shows a one-line banner "The app crashed last time. Share
  diagnostics?" with the button; dismissable.

## Publishing (Steven's side, one command)

A script in the repo builds the release, signs it with the key outside the repo, writes the
manifest with the version, notes and hash, commits it, and attaches the APK to a GitHub
release. The repository must be public for phones to read the manifest without a login.

---

# v1.5 — the Piano tab as groups

Steven asked (2026-09-27) for the settings to be laid out "so it makes the most sense". The
Piano tab had become one long page: the connection card, about forty-five of the piano's own
controls in four sections, the update row, fifteen app preferences in a flat list, and About;
and the v1.5 batch adds some twenty rows more. It becomes a **hub** whose rows open **pages**,
iPad Settings style. Everything above still holds except where this section says it replaces
it: the v1.1 placement of the piano's settings (under the card, above the preferences), the
v1.0 and v1.4 preference lists, and the v1.4 places of the UPDATE row, Check now and Share
diagnostics.

## The hub

The tab keeps its name, title and byline. Under them, in this order:

- The **connection card**, unchanged, and under it the piano's status line while its
  settings can't be changed: "Connect to the piano to adjust its settings." · "Reading the
  piano's settings…" over an indeterminate hairline · "This piano's firmware doesn't offer
  settings over Bluetooth yet." Nothing once the piano has answered.
- **UPDATE**, only while a newer release is known or has just been installed. It is
  transient and wants attention, so it stays on the hub, between the card and the groups,
  and never hides in a page.
- The **groups**, each an eyebrow over a full-width hairline, then its rows:
  - **PIANO** — Feel · Lighting · Pedal · Firmware and status
  - **PLAYING** — Playback · Display (Schedule joins in M19)
  - **CONTROL** — Remote control (M18) · Kiosk (M20) · Studio (M23). A group with no rows
    is not shown at all, not even its eyebrow: until M18 the hub has three groups.
  - **APP** — Auto-connect on launch (switch) · Check for updates automatically (switch) ·
    Check now · Share diagnostics
- **About**, at the very bottom, as before.

Each page row carries a one-line value, so the hub reads as a summary of the instrument:
Feel "Full power", or "Volume 70%"; Lighting "Off", or the mode and the brightness as the
piano rounds it, "Reactive · 62%"; Pedal "On" or "Off"; Firmware and status the version the
piano reports; Playback the default tempo, "100%" (from M16, "2 s pause · 100%"); Display the
note display's name (on wide screens the roll's style, as the page offers it there). The
values come from what the app already holds and never cost a read. Until the piano has
answered, its four rows read "—" and still open their pages.

## Rows

One set of rows for the whole tab, the piano's settings and the app's alike: at least 56 dp,
the label in Body at the start 16 dp in, the value or control at the end, a hairline under
it inset 16 dp to the text; the system ripple and nothing else.

- **Page row**: the label in `contentPrimary`, the value in Body `contentSecondary` with
  tabular figures, and a chevron in `contentTertiary`. When the two don't fit on one line
  (large text, a long version string) the value goes under the label rather than squeezing
  it. The chevron points the other way in right-to-left layouts.
- **Action row** (Check now, Share diagnostics, and Read status, All keys off and Save now on
  the Firmware page): the app's action control, the outlined button of the connection card's
  Disconnect and the test rows (a hairline border in `contentTertiary`, the label in
  `contentPrimary`, 40 dp tall in a 48 dp target), left-aligned at the 16 dp inset in the 56 dp
  row; several actions share a row side by side (All keys off · Save now, as v1.4 had them).
  Under the button, when there is one, a line in the eyebrow style, sentence case,
  `contentSecondary`: what the action does ("A small file with the app's logs. Nothing
  personal.") or what it found ("Steven Piano is up to date."). With large text the button
  keeps clear of the row's hairlines. So the tab reads at a glance: a chevron opens a page, an
  outlined button acts, a row with neither only reads (STATUS).
- Switches, steppers, sliders and chip rows as in v1.1, unchanged in size, type, rules and
  colours. The Test LED and Strike test rows keep their outlined buttons.

## Pages

- **Header**: on phones a 48 dp back glyph (the app's arrow), then the page's title in
  Title (22 sp); no byline, the tab's header carries it. Beside the hub, the title alone,
  level with the hub's own title at every text size.
- **Body**: a column at the reading width (720 dp) that scrolls from anywhere across the
  pane, in sections under eyebrows. A page with a single section begins with the hairline
  alone.
- **Feel**: PRESETS (the chips) · LOUDNESS (full power, volume) · TOUCH (velocity curve and
  multiplier, the two floors, the ceiling, the strike test) · TIMING (scatter, bursts, strike
  lengths, gaps, hold, re-strike) · RELEASE · DRIVE.
- **Lighting**: STRIP (on or off, mode, brightness, reactive palette) · LAYOUT (length,
  offset, scale, the unlit end, direction, glow, Test LED) · MOTION (brightness following
  velocity, fade and rainbow speeds) · PIANO'S SCREEN (when it dims, and how far).
- **Pedal**: one section: the sustain pedal, half-pedalling, the up and down positions.
- **Firmware and status**: FIRMWARE (the piano's firmware version, "Unknown" until it has
  said; M21 adds updating it here) · STATUS (the seven power boards, I²C errors, the pedal
  board, uptime, the two key-force rows and "Key force is set at the piano's USB console.") ·
  ACTIONS (Read status with the piano's report under it, then All keys off and Save now side
  by side).
- **Playback**: one section: Default tempo, Transpose, Velocity, Fold notes outside C1–B7,
  Skip drum channel (M16 puts Pause before each piece first).
- **Display**: NOTES (Note display as chips, with Wide layout under it on wide screens;
  Fingering, Chord names, Hand colours) · ARTWORK (Artwork in black and white, Fetch artwork
  automatically with its line) (M17 adds STANDBY).
- The piano's pages carry the status line at their top; until the piano has answered, their
  controls are there, disabled, under it. Firmware without the Bluetooth console: each piano
  page shows that one line and nothing else (the v1.1 rule, now per page). A refusal shows
  as the outlined banner "The piano said: …" with Dismiss, directly under its control.

## Phones and wide screens

- **Phones** (compact widths): the hub alone; a row pushes its page over it, with the tab
  bar still there. The page slides in from the end edge in 240 ms while the hub gives way by
  a quarter of its width, and back (the glyph or the gesture) reverses it; with animations
  removed it is a cut. Choosing Piano in the bar while a page is open goes back to the hub;
  another tab and back finds the page as it was left; a notification or "Not connected" on
  Now playing or Keys opens the hub.
- **Wide screens** (anything wider than a phone held upright, a phone on its side
  included): the hub in a 360 dp column, a vertical hairline, and the open page beside it.
  The open page's row is filled with `surfaceElevated` from edge to edge; rows there only
  change which page is open (Feel at first).
- Turning a phone keeps the open page and where it was scrolled: a page open on the phone
  moves beside the hub, and a page chosen beside the hub comes back over it, without the
  slide, when the phone is upright again.

## Saving

The piano saves its settings when the tab itself stops (another tab, the app in the
background), as before; closing a page never does.

## Later features add a page and a row here

Every later feature with settings adds its page to the tab and one row to its group, with a
value of a few words (Schedule "Next Wed 12:30", Remote control "On · 100.101.2.3", Kiosk
"On", Studio "2 models"). Nothing else goes on the hub.

---

# v1.5 — M16: glass, yellow, the pause before each piece, the mini player, two panes

Steven asked (2026-09-27) for the sounding notes on the score in yellow, an Apple Music-style
now-playing panel that stays in sight while browsing, two seconds of silence before every piece,
and a Liquid Glass look for the floating controls. He decided: yellow on the **score only**;
glass on the **floating controls only**, monochrome. Everything above still holds except where
this section, or a note above pointing here, says otherwise.

## The glass

- **The material.** The surface colour at 72 % over a 24 dp blur of whatever lies beneath; no
  tint, no noise. A 1 dp edge in the hairline token and, inside it, a 1 dp specular line, white
  at 10 % on the camera body and 70 % on the paper: along the top of a bar, the inner edge of the
  rail, round the upper half of a circle. The play control on glass is a **lens**: clearer (60 %)
  than the glass around it, inside a hairline ring, its glyph `contentPrimary`.
- **Where.** The tab bar (and the mini player in it), the rail, the transport on Now playing and
  in the now-playing panel (the scrubber, Shuffle, Previous, the play lens, Next, Repeat), and a
  playlist's Play. **Never** on content: cards, rows, tiles, the roll, the score, the keyboard,
  sheets, dialogs, banners. M17's channel cards carry a static scrim, not glass.
- **What sits on glass.** Text is `contentPrimary`: over the worst backdrop the blur can bring
  (pure white under the dark bar, pure black under the light one) it reads 7.1:1 and 8.3:1.
  Glyphs may be `contentSecondary` (3.2:1, above the 3:1 graphics floor). Nothing tertiary sits on
  glass: the tab labels are all primary (the pill and the brighter glyph tell the chosen tab), the
  scrubber's times are primary, Shuffle and Repeat when off are secondary. The play lens's glyph
  reads 4.5:1 and 5.8:1 at worst.
- **Solid when it must be.** Below Android 12 (no blur), and whenever the person has turned on
  *High contrast text* (Android's nearest to Reduce Transparency), the glass is today's solid
  surface with its hairline, and controls that float on glass go back to their solid places (the
  transport under the roll). The switch is followed as it changes.
- **Only where something passes beneath.** A glass surface re-blurs whenever the screen beneath
  it changes, which is every frame while a piece plays. Where nothing can pass beneath it (the
  rail, which every screen keeps clear of; the tab bar over the fixed layouts of Now playing and
  Keys) it draws the glass's look without blurring, the same pixels over the bare background, and
  costs nothing per frame.

## The floating padding

The content draws beneath the bar and the rail, and each screen keeps clear of them itself:
lists take the bar's height as padding under their last row, so they scroll beneath the glass and
their last row still rises above it (the Library, the Piano tab's hub and pages); fixed layouts
stop above the bar (Now playing, Keys). Everything keeps clear of the rail. **The Keys keyboard
is never under glass.** With the keyboard open, a list ends at the keyboard.

## Yellow on the score

- A sounding note's head, stem, flags, ledger lines and accidental turn a warm yellow as the
  cursor reaches it (the 120 ms flip; a cut when motion is reduced), hold it for the note's length,
  and settle back to `contentSecondary`. A note's tied heads light as the cursor reaches each.
- The token `noteSounding`: `#F2C94C` on the camera body (11.0:1 on the score panel, 12.2:1 on the
  surface) and `#9C7A00` on the paper (3.8:1 on the panel, 3.6:1 on the surface): a yellow (hue
  about 45°), never read as the live red.
- The score's cursor stays `contentPrimary`; beams, ties, rests, marks and the staff stay grey;
  the roll, the falling notes and the keyboard strip keep `contentPrimary` or the hand colours.
  Nothing else in the app is yellow.

## The pause before each piece

- Every piece begins after a pause of silence: a tap in the Library, a playlist's Play, the end
  of the piece before, Repeat one (later: schedules and channels). **Piano › Playback › Pause
  before each piece**, the page's first row: Off, then half seconds to 5 s; 2 s at first. The
  hub's Playback row reads "2 s pause · 100%", or "No pause · 100%".
- During the pause the play glyph already reads pause, the timer reads 0:00 with the thumb at the
  start, the roll shows the first notes travelling down to the tracker bar and meeting it as the
  piano plays them, the score shows no cursor yet, and an eyebrow **STARTING** in
  `contentTertiary` stands under the composer (on Now playing and in the panel), fading in and out
  over 120 ms (a cut when motion is reduced). Its line is always kept, so nothing moves.
- Resuming after Pause never waits (a pause inside the pause holds the piece's start: Play then
  begins it at once). A seek plays from its bar at once. Between two pieces the gap is the longer
  of the pause and 1.5 s: 2 s with the default, not 3.5.

## The mini player (phones)

- Whenever a piece is loaded (or loading), a 64 dp row sits above the tab bar, inside the bar's
  glass, a hairline between them (the moving hairline while a piece loads): the piece's art at
  48 dp (the composer's portrait, else the piece's own roll card), its title in Body over its
  composer as an eyebrow, one line each, then play/pause and next, 48 dp each.
- The whole row opens Now playing ("Open Now playing"); it has no swipes. It is not shown on Now
  playing itself, which is the full player, nor on wide screens, which have the panel.
- It grows in from the bar and gives way into it over 240 ms (a cut when motion is reduced); the
  lists' padding follows it. At large text it grows rather than clipping; the composer ellipsizes.

## The Library in two panes (wide screens)

- Anything wider than a phone held upright shows the list (55 %, keeping its 720 dp reading
  column) and, beside it past a hairline, the **now-playing panel** (45 %): a NOW PLAYING eyebrow
  with the Up next glyph at its end; the piece's art, at most 320 dp, centred (smaller on a short
  pane); the title in Title (it opens the piece sheet); the composer eyebrow (M17's channel joins
  it); STARTING during the pause; a live **roll strip** (the paper roll, slower, at 48 dp a second,
  over its keyboard strip; no hands, fingering or chord names: a glance, not a study), 120 dp at
  least and taking the pane's height; then the scrubber and the transport, floating on glass over
  the strip's history where it can hold them, solid beneath it where it cannot. With nothing
  loaded: "Choose a piece from the library."
- Playing from the list stays on the Library: the panel shows the piece. The Now playing tab stays
  for the full score; the notification still opens it. Turning the device switches between one
  pane and two without losing the list's place or the piece.

## The transport on glass

On Now playing the scrubber and the transport float on glass over the paper roll's **history**,
the third below the tracker bar, with their bottom on the keyboard strip's top edge: never over
the tracker bar, never over the keys. The notes just played pass beneath them, blurred; the lens
shows them a little clearer. Falling notes and the score alone have no history, and a short
screen (a phone on its side, large text) has too little: there the transport stands under the
views, solid, as before.

## A playlist's Play

Play floats as a 56 dp glass circle at the bottom end of the playlist's column, above the mini
player and the bar, where a thumb finds it wherever the list is scrolled; the last row can rise
clear of it. Shuffle stays in the head, outlined. It gives the play tick.

## Motion

Two small additions, both cut when motion is reduced: STARTING fades over 120 ms, and the mini
player grows and gives way over 240 ms. Pressing play is still the one orchestrated moment; the
roll's ease now begins at the pause's start, so the first notes reach the bar at 0.

## Sixty frames

On displays faster than 60 Hz the app asks for 60 frames a second: enough for the roll and the
score, and half the glass's work. The piano's timing never depends on the display.

