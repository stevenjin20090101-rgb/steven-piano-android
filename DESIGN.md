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
  automatically with its line) (M17 puts APPEARANCE first and adds STANDBY last:
  see v1.5 — M17).
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

---

# v1.5 — M17: built-in playlists, channels, display mode

Steven asked (2026-09-27) for playlists that fill themselves with the pieces everyone knows,
channels that play the library without end like a radio, shown as image tiles, and a display
for the tablet on the piano when nobody is touching it. After seeing the mockups he asked to keep
the ink and the paper everywhere: the only pure black in the app is display mode's canvas, and
nothing else leans darker than the surface tokens. Then he wanted the choice in Settings: the
app's appearance, and the canvas of display mode. Everything above still holds except where this
section says otherwise. This is release 1.5, with M15 and M16.

## Built-in playlists

- **Popular** (the pieces everyone knows: Für Elise, the Moonlight's first movement, Clair de
  lune, the E-flat Nocturne, the first Gymnopédie, the Turkish March, the C major Prelude…),
  **Recognisable** (the concert warhorses: the second Hungarian Rhapsody, the Heroic Polonaise,
  Rachmaninoff's C-sharp minor Prelude, the Revolutionary Étude, Pictures at an Exhibition…) and
  **Epic on piano** (the 45 pieces of Steven's Epic on piano set). They fill themselves from the
  library through a catalogue that ships with the app: for each piece its composer and a pattern
  for its title, so a piece is found however its file names it (the `midi` folder's INDEX,
  ALL-SONGS.zip's file names, the Epic zip). They hold only what the library has, each piece in
  at most four recordings, in the catalogue's order, which is the playing order. Nothing is added
  that the library does not already hold: public domain and openly licensed, as the library is.
- **Where.** First among the Playlists' tiles, in the order they were made (Popular,
  Recognisable, Epic on piano), then the others as before. The tile's eyebrow reads **BUILT IN ·
  14 PIECES**. A list with nothing in it is not shown at all: no empty tiles.
- **What can be done with them.** Play, Shuffle, play one piece, and Change photo: the tile's
  menu and the page's offer nothing else. No rename, no delete (no dialog ever offers it), no
  reordering (no drag handles) and no Remove; the person's own playlists are untouched. The page's
  eyebrow reads "BUILT IN · …"; a list that has emptied while open says "It fills itself from
  the library's pieces."
- **Names.** A built-in list is known by its key, never its name. When a playlist of the person's
  (or an INDEX set) is already called "Popular", the built-in reads **Popular · built in** (then
  "Popular · built in 2"); a name the person chooses later wins the same way.
- **When they fill.** When the app starts, after an import that brought pieces in, and two
  seconds after renaming stops (a renamed piece can join or leave a list).
- In Steven's library: from the `midi` folder they hold 17, 29 and 49 pieces; from ALL-SONGS.zip
  17, 30 and 50; from the Epic zip alone 7, 13 and all 45, in the set's order.

## Channels

- Eight, in this order: **Calm** (Satie, Debussy, Field, or any piece called a nocturne,
  berceuse, gymnopédie, gnossienne, Clair de lune, adagio, lullaby, rêverie, Träumerei,
  consolation, arabesque or andante; and only pieces that play at most six notes a second on
  average), **Epic** (the built-in list's pieces, in its order), **Baroque** (Bach, Handel,
  Scarlatti, Couperin, Rameau, Purcell, Telemann, C. P. E. Bach), **Romantic** (Chopin,
  Schumann, Liszt, Brahms, Mendelssohn, Schubert, Grieg, Tchaikovsky, Rachmaninoff, Dvořák,
  Smetana, Mussorgsky), **Impressionist** (Debussy, Ravel, Satie, Fauré, Albéniz, Granados,
  Falla, Scriabin), **Nocturnes** (nocturne, notturno, Nachtstück), **Études** (étude, study),
  **Everything** (the whole library). A channel's pool is worked out from the library whenever it
  changes and never stored. From Steven's `midi` folder: 30, 49, 197, 912, 120, 23, 186 and 1,727
  pieces.
- **Playing.** A tap plays the channel without end: 25 of its pieces, shuffled, become Up next;
  whenever fewer than five are left, ten more join; nothing comes twice until the whole pool has
  played, and the next round leaves out the last 20 heard (at most half the pool, so a small one
  still shuffles). Up next, Next, Previous, Add to queue and Play next work as ever and keep the
  channel. Playing a piece or a playlist, Stop, or dismissing the notification ends it. A channel
  of fewer than three pieces reads "Add more pieces" and does not play.
- **The eyebrow.** While a channel plays, the composer line reads **CLAUDE DEBUSSY · CALM ·
  CHANNEL** on Now playing, in the tablet's panel and in display mode.
- **Volume.** Each channel has its own, 70 % at first. While the channel plays it is the piano's
  own volume, when the piano's firmware offers it; otherwise the app's velocity (50 % and half the
  volume: 70 % plays at 85 %). When the channel ends, what it replaced comes back: the piano's
  volume together with its Full power, and the velocity unless the person has changed it
  meanwhile. Nothing is saved on the piano. A channel chosen while another plays keeps the first
  one's "what comes back".

## Channel tiles

- **The row.** At the top of the Playlists listing (not while searching): the eyebrow CHANNELS
  with **See all** at its end, then the cards in a row that scrolls sideways, 280 × 200 dp, 12 dp
  apart, 16 dp in from the edges. See all opens them as a page of the Library: the back glyph,
  the title Channels over "8 CHANNELS", and the cards in a grid, two, three or four across as the
  playlist tiles.
- **The card.** Its art is a two-by-two mosaic of the four composers the pool holds most pieces
  by (fewer cells when it has fewer): their portraits, else their roll cards; a channel with no
  known composer shows its monogram. Along its foot a **band** at least 56 dp tall: the surface at
  the glass's opacity (72 %) with a hairline along its top, a static scrim and not glass, since
  nothing moves behind it; on it the name in Title and an eyebrow, "49 PIECES", "ADD MORE
  PIECES", or the live dot and **PLAYING** while it plays. Everything on the band is
  `contentPrimary`, as on glass, and reads as text on glass does over the worst portrait. The
  band is today's surface: ink on the camera body, paper on the paper roll.
- **Touch.** A tap plays. A long press opens a menu: **Set volume** (a sheet: CHANNEL, the name,
  the Volume slider from 0 to 100 % with its value, and the line "The piano's own volume while
  this channel plays, or how hard its keys are struck where the piano has none. What was there
  comes back when it ends.") and **Schedule**, greyed, with "Coming in the next update" under it
  (M19). A move of the slider is heard at once when that channel is playing.

## Display mode

- **When.** Piano › Display › STANDBY › **Display mode after a minute** (off at first; "The
  portrait, the title and the roll fill the screen for passers-by": no "black", since the canvas
  is a choice). With it on, a minute without a touch
  anywhere, while a piece is loaded, brings the display over everything: the tab bar, the rail
  and the mini player included. Any touch, or Back, leaves it, and that touch does nothing else;
  the app is as it was, its tab, page and scroll.
- **What it shows.** The composer's portrait fills the screen at a quarter of its strength (in
  black and white when Artwork in black and white is on), fading into the canvas towards the foot;
  over it the title in Display, the composer and the channel in Eyebrow (on wide screens, a tablet
  on the piano read from a step away, the title in **Display Large**, 45 sp Medium, tracking −0.5,
  and the eyebrow a third larger to match, 16 sp); the paper roll across the
  whole width over its keyboard strip, without the black-key lanes (over a portrait they read as a
  barcode); and at the foot the live dot with "Sent to piano" (or "Not connected") and the byline.
  No controls. The screen stays on, and the status and navigation bars step aside while it shows
  (a swipe from the edge brings them back for a moment).
- **The black rule.** Its canvas is **true black**, `DisplayBlack` `#000000`, in both appearances:
  the one pure black in the app, with the camera body's tokens on it (`contentPrimary` 18.8:1,
  `contentSecondary` 8.3:1, `contentTertiary` 6.1:1). Every other surface keeps its ink or paper,
  nothing else leans darker than the surface tokens, and the display's black never reaches the
  screens beneath it: it is one full-window layer, nothing more.
- **Standby canvas.** Piano › Display › STANDBY › **Standby canvas**: **Black** (the default) or
  **Same as the app**, which lays the display on the app's own surface, ink or paper as the
  appearance resolves, with that appearance's tokens for the portrait's fade, the roll and the
  words.

## Appearance

The Display page opens with **APPEARANCE**: **Appearance**, chips **Follow system** (the default)
· **Light** · **Dark**. The choice applies at once and everywhere: every screen, the glass bars,
the system bars' icons, display mode on "Same as the app", and the web panel when it comes
(M18). The hub's Display row still reads the note display. The Display page is now APPEARANCE ·
NOTES · ARTWORK · STANDBY.

---

# v1.5.1 — M18: the web panel, guests' requests, the poster

Steven asked for the piano to be run from a phone or a laptop as well as from the tablet on it,
and for passers-by to be able to ask it for a piece. The app itself serves a small web panel: on
the tablet's Tailscale address the whole panel, behind a six-digit PIN; on its Wi-Fi address only
the request page and the poster, unless the person chooses otherwise. Everything above still
holds; the panel speaks the app's own language. This is release 1.5.1.

## Remote control (Piano tab)

- **The row.** The hub's **CONTROL** group appears now that it has a row: **Remote control**,
  reading "On · 100.101.2.3" (the panel's address; "On" while it has none) or "Off". Kiosk (M20)
  and Studio (M23) join it later.
- **PANEL.** **Web control**, a switch, off at first and greyed with "Set a PIN first" until a
  PIN exists. While it is on, the panel's address in Body with tabular figures,
  "http://100.101.2.3:8737", over "Scan it with your phone, or tap it to show it large" (an
  eyebrow's size and tracking, not its capitals), with its QR code at 96 dp beside it: always the
  paper's ink on the paper's elevated tone, whatever the appearance (cameras read dark on light
  best), in a tile with a hairline. A tap shows the code large in a sheet (at most 480 dp), the
  address under it in Title and "Scan to open the panel". Without an address it says why in a
  note: "Starting…", "Waiting for a network", Android's refusal, or, with Wi-Fi but no Tailscale,
  "No Tailscale address yet: the panel waits for one (or for Panel on Wi-Fi too). Guests can use
  http://…/request". Then **Set a
  PIN** (or **Change PIN**) with "Six digits, asked for when the panel opens in a browser", and
  **Panel on Wi-Fi too** with what it costs: "Over Wi-Fi the PIN travels unencrypted".
- **The PIN sheet.** The WEB CONTROL eyebrow, the title (Set a PIN / Change PIN), one field of six
  digits, masked, entered twice: "Enter six digits." → Next → "Enter them again." → Save; a
  mismatch starts again with "The two didn't match. Enter six digits." Cancel leaves the old PIN.
  A new PIN signs every browser out.
- **GUESTS.** **Guests can request** (off at first), **Approve requests first** (on at first,
  greyed while guests can't request), and **Print the request poster**, which opens Android's own
  print dialog on the poster (A4), with "The poster's code opens http://192.168.1.20:8737/request"
  under it, or "Turn on Web control to print the poster".
- **The notification.** While Web control is on, a silent, low-importance notification: "Web
  control on" over the panel's address (or "Guests: …" when only guests are served, or "Waiting
  for a network"); a tap opens the Piano tab. Turning the switch off ends it with every session.
- **Requests on the tablet.** With Approve requests first on, the Library shows an outlined
  banner while any wait: "1 request waiting" (or "2 requests waiting"), the oldest's title and
  composer, **Approve** and **Dismiss**, as the panel's Requests page does.

## The panel: its language

- **The app's tokens, value for value.** `style.css` holds `ui/theme/Color.kt`'s two palettes as
  CSS variables (a test compares them): the paper roll when the system is light, the camera body
  when it is dark. No surface is pure black or pure white; the only pure black in the product is
  still display mode's canvas. Red is the live dot's alone.
- **Appearance.** The panel's own control at the foot of the section list (at the foot of the page
  on a phone): APPEARANCE, chips **Follow system** (the default) · **Light** · **Dark**, as the
  app's Display page; the choice is kept in the browser.
- **Type.** The system's sans-serif, tabular figures everywhere; titles 22 px, the display line 28
  px (Medium, tracking −0.3), Body 17/24, notes 15/21, meta 13/18, and the eyebrow: 11 px Medium,
  letter-spaced 1.3 px, in capitals, in the tertiary ink.
- **Shapes.** Hairline rules, 56 px rows, 16 px insets, 8 px corners on controls and 12 px on
  cards; outlined buttons as the app's; switches, chips, steppers and sliders drawn in the ink,
  never in colour. The app's own glyphs (play, pause, next, previous, shuffle, repeat, repeat
  one, the queue's arrows…) are inlined as SVG symbols in the content colour.
- **Art.** Portraits and roll cards come from the app (`/api/art/…`), 40 px on rows with an 8 px
  corner and a hairline, 160 px on Now playing; in black and white when the app's Artwork in black
  and white is on.

## The panel: its layout

- **Wide (900 px and more).** A 200 px section list at the left: the piano's name, the live dot
  with "Connected · 100.101.2.3:8737" (a hollow dot and "Reconnecting…" while the socket is
  away), then the eight sections, **Now playing · Up next · Library · Channels · Schedule ·
  Requests · Add · Piano**, the chosen one on the elevated surface, Requests with the number
  waiting in a small outlined pill; the Appearance chips and the byline "Player piano · by Steven
  Jin" at its foot. The page at the right, in a reading column of 720 px.
- **Narrower.** The name and the connection line over the sections as a strip of tabs that
  scrolls sideways, the chosen one underlined; the Appearance chips and the byline at the foot of
  each page. Every page works at 390 px without scrolling sideways.
- **Now playing.** The art at 160 px, the title in the display line, the eyebrow "CLAUDE DEBUSSY
  · CALM · CHANNEL" (the composer alone without a channel), STARTING during the pause before a
  piece, the scrubber with its two times (drag to seek), the transport (shuffle, previous, the
  play/pause **lens**: a 56 px circle on the elevated surface inside a hairline ring, next,
  repeat), the tempo stepper, the channel's volume while a channel plays, and the piano's link
  ("● Sent to piano" or "Not connected"). The time runs in the browser at the piece's tempo
  between the tablet's once-a-second reports. From 1100 px Up next sits beside it at 360 px.
- **Up next.** "UP NEXT · 12 PIECES" and **Clear**; the playing row "Playing · Debussy", then the
  rows, each with its handle and ▲ ▼ ✕ (and dragged where the browser drags), a guest's piece
  tagged **Requested**. Beside Now playing the list is compact: no handles.
- **Library.** A search field ("Search titles and composers"), chips **All · Playlists ·
  Composers · Favorites · Recent**, rows with their art and a ⋮ menu (**Play**, **Play next**,
  **Add to queue**); a playlist or a composer opens as a list under a crumb (back, its name,
  **Play**, **Shuffle**); 50 rows at a time with **Show more**.
- **Channels.** The app's tiles: the two-by-two mosaic of the pool's composers, the band along the
  foot (the surface at 72 % with a hairline: a static scrim) with the name and "49 PIECES", "ADD
  MORE PIECES" or "● PLAYING". A tap plays; **Stop the channel** in the page's head while one plays.
- **Schedule.** Reads `/api/schedules`; until M19 serves it, SCHEDULE and "Coming in the next
  update."
- **Requests.** The requests waiting (title; composer and the time it came) with **Approve** and
  **Dismiss**, or "No requests waiting."; then GUESTS: the two switches, as on the tablet, and the
  guests' address.
- **Add.** A drop zone, dashed at the tertiary ink and solid while a file is over it: "Drop MIDI
  files or a zip here", ".mid and .midi up to 8 MB, .zip up to 64 MB", **Choose files**. One row a
  file: its name, "545.9 KB · Sending 45%" over a 2 px bar, then "Sent to the tablet", or why it
  was not added ("Not added: a MIDI file can be 8 MB at most"); files go one at a time. Under them
  the tablet's import: "Importing 2 of 3 · Etude 2", then "Imported 3 pieces · 1 already there".
- **Piano.** The link ("● Connected to Steven Piano" or "Not connected"), then the piano's settings
  as three disclosures, **Feel** (open at first) · **Lighting** · **Pedal**, with the app's rows and
  controls: switches, steppers, sliders with their values and units, chips, the presets; then
  ACTIONS: **Read status**, **All keys off**, **Save now**, and the piano's status report. Firmware
  and the bench commands stay on the tablet and the USB console.
- **Toasts.** A short line at the foot of the window for what just happened ("Playing on the
  piano.", "Added to Up next.") or what went wrong, on the elevated surface, outlined in the ink.

## The PIN gate

Centred on the surface: STEVEN PIANO, **Enter the PIN** in the display line, "The six digits set
on the tablet, in Piano › Remote control.", one 240 × 56 px field of six masked digits (spaced
wide) and **Open**. A wrong PIN reads "That PIN isn't right."; after five, "That PIN isn't right.
Try again in 30 s.", counting down, the field and Open disabled until it ends. Before a PIN
exists: "Set a PIN on the tablet first: Piano › Remote control." A session lasts until a day
unused, the PIN changes, or Web control turns off; then the gate comes back by itself.

## The request page

For a phone in a passer-by's hand, 640 px wide at most: STEVEN PIANO, **Ask the piano**, "Pick a
piece. It joins the queue.", ONE REQUEST EVERY FIVE MINUTES; then Popular, Recognisable and Epic
on piano (the built-in lists as they stand, each piece once) as eyebrowed lists of rows, title
over composer, each with an outlined **Request**. A request turns the page into "Thanks — it's in
the queue." (or "Thanks — it joins the queue once it's approved."), the piece and composer under
it. A second within five minutes: "One request every five minutes. Try again in 4 min.", in a
line at the foot of the screen (seen wherever the list is scrolled; it goes after eight seconds). Guests
off: "Requests are closed right now." Nothing to type, nothing but the list's pieces to ask for.
It follows the system's light or dark.

## The poster

An A4 sheet, always on the paper palette whatever the screen's appearance: STEVEN PIANO, **Ask the
piano** (56 px Medium, tracking −0.5), "Scan to pick a piece for the piano", the request page's
QR code 120 mm wide in the ink with a quiet zone of four modules, the address in plain text
under it, and the byline. Printed, it is one page with the paper left as it is (no tint), centred
on the sheet; Android's print dialog prints it from the tablet itself (a WebView of the app's own
page: no browser, so it works in kiosk mode too).

---

# v1.6 — M21: updating the piano's firmware from the app

Steven asked (2026-09-27) for the app to flash the piano's ESP32 itself, so that after the flash the
Feel, Lighting and Pedal pages work on the real piano. It happens over the Bluetooth link the app
already holds; the one flash that makes this possible, firmware 2.0.0, goes on over USB once. The
firmware's side is `firmware/docs/BLE_OTA.md`. Everything above still holds.

## Firmware and status › FIRMWARE

- **The version.** "Piano firmware" and, as the piano's Device Information reports it, "2.0.0 ·
  a1b2c3d" (the release, then the build it came from). "Unknown" while the piano isn't connected.
  Connected to firmware older than 2.0.0, which has no version to report and can't take an update
  over Bluetooth: "Unknown — this firmware has no version. Flash 2.0.0 over USB once." (the value
  goes under its label, as long values do). The section comes from the link, not the console, so it
  stands under the page's status line even where the piano offers no settings.
- **Check for piano updates.** An outlined button in an action row, with what the last check found
  under it: "Checking for piano updates…", "The piano's firmware is up to date.", "Version 2.1.0 is
  available.", or why it couldn't ask ("Checking for piano updates needs an internet connection.",
  "Couldn't reach the update server."). Greyed while no piano that can be updated is connected. The page
  also checks as it opens (at most every ten minutes), and the app once a day while the piano is
  connected and **Check for updates automatically** is on (the same switch as the app's own
  updates).
- **A release on offer.** "Piano firmware 2.1.0 is available" in Body, its notes in
  `contentSecondary`, the filled **Update the piano to 2.1.0**, and under it "The piano goes quiet for
  about two minutes. Keep the tablet near it." (or "Connect to the piano to update it."). Nothing red,
  no badge: the hub's row says it.
- **While it goes.** One line in `contentSecondary` with tabular figures over the progress hairline,
  then an outlined **Cancel** for as long as Cancel still stops it: "Downloading · 0.9 MB",
  "Checking the download…", "Sending · 38% · about 1 min left" ("less than a minute left" at the end;
  no time until the first window has gone). From END on nothing stops it, so no Cancel: "The piano is
  checking the update…", "Restarting the piano…", each over an indeterminate hairline.
- **How it ended.** In Body: "Updated to 2.1.0", or "Updated to 2.1.0 · confirming…" while the new
  firmware hasn't passed its self-test yet (the piano confirms it about 30 s after it starts; the app
  reads again every 30 s). A failure is one line, "The update didn't finish. The piano kept its old
  firmware.", with the filled **Retry** where trying again can mend it; where it can't (the piano
  refused the release's signature or image), no Retry and a second line, "The piano refused this
  release." (after its crash-loop safe mode: "The piano is in its safe mode. Switch it off and on,
  then try again."). The piano back on its old firmware after the restart: "The piano restarted but
  reports 2.0.0 — it rolled back.", with Retry. Not back within a minute: "The piano hasn't come back
  after restarting. Check that it's on; the app will look for it again." (and the page says how it
  went when it does).
- **Releases the app can't send.** A release that changes the partition table or the bootloader:
  the heading and notes, then "This update needs a USB flash", and no button. One that asks for a
  newer Steven Piano: "Needs a newer app".
- The rest of the page, STATUS and ACTIONS, is as in v1.5.

## The hub's row

**Firmware and status** reads **Update available** while a newer release is known (also one this
app can't send, and after a failure Retry can mend), **Updating…** while one is being sent, else the
release the piano reports, "2.0.0" (the build stays on the page), else "—" as before.

## While the piano updates

- **Nothing plays.** From the moment the transfer starts until it ends, whatever the outcome, the
  player is locked: pieces, channels, the web panel's commands and the Keys screen's keys are turned
  away, and Now playing (and the panel) shows the outlined banner **Updating the piano**, where it
  shows playback's problems. The app stops the piano first and waits half a second of quiet before
  it sends anything, as the piano asks.
- **The notification.** While it runs, a silent, low-importance notification (channel "Piano
  firmware"): **Updating the piano**, the same line as the page, its progress bar, and **Cancel**
  until END. A tap opens the Piano tab. When it's over, one line says how it ended ("Updated to
  2.1.0", "The update didn't finish. The piano kept its old firmware.") and goes when tapped. The
  transfer carries on with the screen off or the app in the background.
- **The piano's own screen** shows "Updating • 38 %", then "Updated to 2.1.0" for a minute after it
  confirms itself (BLE_OTA.md › 7, 9): the tablet and the piano say the same thing.

---

# v1.6.1 — M20: kiosk mode

Steven asked (2026-09-27) for the school tablet to be locked to the app, with a way out that
doesn't mean rebuilding anything. Android gives a device owner a proper kiosk ("lock task"), and
the school tablet is the app's device owner already (v1.4, for silent updates). Everything above
still holds; this section adds the Kiosk page, the hidden way out, and display mode's resting
state. It is release 1.6.1.

## What kiosk mode does

- The screen shows the app and nothing else: no Home, no Recents, no notifications or status bar,
  no power menu (Android's lock task with none of its features). Back works inside the app and
  stops at its root.
- The app is the tablet's home screen: a restart, or anything that sends the tablet home, lands in
  the app, locked. The lock screen is off, so the power button wakes straight into the app, and the
  screen stays on while the tablet is plugged in (a charger, USB or a wireless pad).
- Display mode is always on, and is the resting state (below).
- It needs the app to be the tablet's device owner (README › School tablet) and a kiosk PIN.

## The Kiosk page (Piano › CONTROL › Kiosk)

- **The row.** CONTROL gains **Kiosk** under Remote control, reading "On" or "Off" ("On" while
  unlocked for now too: it locks again by itself).
- **Kiosk mode**, a switch. While the app is not the device owner it is greyed with "Make the app
  the device owner first: README › Kiosk"; without a PIN, "Set a PIN first", as Web control is.
  Before it comes on, a line in Body under it says what it does and where the way out is: "The
  tablet shows only this app, wakes into it and comes back to it after a restart. To leave, hold the
  byline under any tab's title for three seconds." Turning it on locks the screen at once; turning
  it off asks for the PIN.
- While it is on, **Unlock for now** (outlined, "Home and the other apps come back until the app is
  opened again"), which asks for the PIN; while unlocked, **Lock again** in its place ("Unlocked
  until the app is opened again, or left alone"), which asks for nothing.
- **Set a PIN** / **Change PIN** (outlined, "Six digits, asked for to leave kiosk mode"): the web
  panel's sheet, six digits twice. While kiosk mode is on, the old PIN first.
- The page ends with the note **Display mode is always on in kiosk**.
- Should Android keep the tablet's own screen lock (a PIN, pattern or password set on the tablet),
  the line under the switch says so: "The tablet has a screen lock, so after a restart it waits at
  the lock screen. Remove the lock in Android's settings to start straight into the piano."

## The way out

- **Nothing on screen hints at it.** While kiosk mode is on, a three-second hold on the byline under
  any tab's title (PLAYER PIANO · BY STEVEN JIN) opens the kiosk's PIN sheet. While the finger is
  down, a hairline in the byline's own grey grows along its foot over the three seconds, and goes
  the moment the finger lifts or strays; with animations removed it stands whole at once (a cut).
  Nothing else moves and the header keeps its size. TalkBack offers the same as an action on the
  byline, "Kiosk PIN". With kiosk mode off the byline is plain text with no gesture at all.
- **The PIN sheet**: the KIOSK eyebrow, **Enter the PIN** in Title, "The six digits set in Piano ›
  Kiosk.", the six-digit field of the web PIN's sheet (masked, a number pad, tabular figures spaced
  wide), then Cancel and two outlined buttons side by side, **Unlock for now** and **Turn kiosk
  off** (Turn kiosk off alone while already unlocked). A button weighs the PIN; nothing is decided
  before one is pressed.
- **Wrong**: "That PIN isn't right." The first three wrong tries cost nothing; after that each one
  makes the next wait 5 s, 10 s, 20 s…, doubling to five minutes, and the line counts it down,
  "That PIN isn't right. Try again in 5 s." (seconds under a minute, then whole minutes), the field
  and the buttons greyed until it ends. The count outlasts a restart of the app or of the tablet; the
  right PIN, or a new one, starts it again.
- **Unlock for now**: the screen lets go. Home, Recents, the notifications and the other apps are
  back until the app is next opened (after another app, or the screen turning off) or the tablet
  rests in display mode; then it locks again by itself. The app stays the home screen meanwhile, so
  Home comes back to it.
- **Turn kiosk off**: everything kiosk mode changed goes back as it was: the screen lets go, the
  tablet's own launcher is home again, the lock screen returns, and "stay on while plugged in" is
  what it was before. The PIN is kept for next time.
- **A forgotten PIN**: the adb way back (README › Kiosk). The app gives up the device owner, ending
  kiosk mode first; only someone with a computer and a cable can do it.
- A PIN sheet counts its own touches for display mode; left alone until display mode comes all the
  same, it closes, so the tablet never rests with a PIN sheet over it.

## Display mode at rest

- In kiosk mode display mode is always on, whatever Piano › Display › STANDBY says, and it comes
  after the same minute without a touch with nothing loaded too: the resting state. Coming to rest
  ends an "Unlock for now".
- **A piece loaded**: display mode as v1.5 — M17 draws it.
- **Nothing loaded**: the canvas (true black, or the app's own surface on "Same as the app") with
  the byline at the foot where display mode has it, and nothing else, unless guests may ask. While
  Web control and Guests can request are both on, the request page's code stands in the middle as
  the poster has it: **Ask the piano** in Display (Display Large on wide screens), "Scan to pick a
  piece for the piano" in Body, the code on its paper card (the paper's ink on its elevated paper
  inside a hairline, as on the Remote page: cameras read dark on light), as large as the screen
  allows up to 280 dp on phones and 360 dp on tablets, and the address under it in tabular figures.
- Nothing moves at rest but the whole: once a minute it steps 4 dp round a small square, never
  animated, so hours of the same words burn nothing into the screen. At rest the screen stays on only
  as "stay on while plugged in" says (kiosk mode sets it); with a piece loaded it stays on as before.
- Any touch leaves, as before, and goes no further.

## Settings locked in kiosk

The tablet stands in a public space (Fable, 2026-09-28): in kiosk mode, playing, queueing,
browsing and the Keys tab stay free, and anything that changes the piano or the library asks for
the kiosk PIN first.

- **The Piano tab.** The hub keeps its groups and their values, but every page row (Feel,
  Lighting, Pedal, Firmware and status, Playback, Display, Remote control, Kiosk) carries a small
  padlock in its chevron's place, in the tertiary grey, and opens only after the PIN. The APP
  group's two switches carry the padlock just before the switch and Check now beside its button;
  each asks before it acts. Share diagnostics stays free. The connection card's **Disconnect** asks;
  **Connect** never does.
- **The Library.** A padlock stands beside the **+**, which asks before its sheet (adding music)
  opens, as the empty library's Add MIDI files does. Delete (a piece or a playlist), Remove from
  playlist, Rename (a piece or a playlist), Add to playlist, Move up and Move down, Change photo, and
  a channel's Set volume ask too; while locked a playlist shows no drag handles, since a drag cannot
  wait for a PIN. Playing, Shuffle, Play next, Add to queue, Favorite, About this piece, the guests'
  banner and everything on Now playing and Keys never ask.
- **The sheet**: the kiosk's PIN sheet with the title **Settings are locked in kiosk** and the line
  "The kiosk PIN opens them for five minutes.", then Cancel and **Unlock**. The right PIN opens the
  settings and the action goes on (the page opens, the switch turns, the + sheet rises); a wrong one
  counts against the same waits as the way out.
- **How long.** The right PIN opens the settings for five minutes, or until the tablet rests in
  display mode, whichever comes first; while unlocked for now they are open too. While open the
  padlocks give way to the chevrons, and nothing asks.
- **A page shown while locked** (beside the hub on a tablet, or a page left open when the five
  minutes ran out or the tablet rested) gives its controls up for the line "Settings are locked in
  kiosk." and an outlined **Unlock** ("The kiosk PIN opens them for five minutes."), which asks for
  the PIN; the page's title stays.
- **A firmware update stays in view** (v1.6.2). While one runs, Firmware and status keeps its
  FIRMWARE block over the locked page's line: "Sending · 38% · about 1 min left" over the progress
  hairline, and **Cancel** while Cancel still stops it, which asks for the PIN; then how it ended
  ("Updated to 2.1.0", or the failure's line, without Retry). Its row opens without the PIN while
  the update runs (a chevron, not the padlock), and the tablet coming to rest locks the settings,
  never the update's progress. The release on offer, Check for piano updates and Retry wait for the
  PIN like every setting.
- The Kiosk page's own Unlock for now, Turn kiosk off and Change PIN still ask for the PIN each time,
  whether the settings are open or not: they are the way out.

---

# v1.6.2 — M19: schedules

Steven asked for timed play, Disklavier's Timer Play in the app's language: a playlist, a channel
or a piece on chosen days at a start time, until an end time or its end, at a volume. The tablet
plays them itself, from one exact alarm, with its screen off and dozing; the web panel lists and
edits them too. Everything above still holds. This is 1.6.2.

## The Schedule page (Piano tab)

- **The row.** PLAYING › **Schedule**, after Display, reading when the next one starts, **"Next Wed
  12:30"** (the day short, the 24-hour clock), or **"None"**.
- **At the top**, under the page's header: **NEXT: WEDNESDAY 12:30, CALM** in the eyebrow style,
  then what the last one did, in the eyebrow's size, sentence case, `contentSecondary`: **"Last:
  Wednesday 12:30, Calm channel"** or **"Missed: Wednesday 12:30 (piano not connected)"**. The
  last line is kept on the device for six days (so its weekday is always the last such day) and
  shows only while there are schedules; neither line shows with nothing to say.
- **Allow exact alarms**, only while Android refuses them: an action row, the outlined button
  and under it "Schedules start at an exact time, which Android asks you to allow. Until then,
  none will start." The button opens Android's Alarms & reminders page for the app; the page
  asks again when it comes back into view. (From Android 13 the app holds `USE_EXACT_ALARM`,
  which cannot be taken away, so the row appears on Android 12 only.)
- **The rows**, after a full-width hairline: **"Weekdays 12:30"** in Body over **"Calm channel ·
  until 13:15 · 70%"** in the eyebrow's size, sentence case, secondary, and the switch at the
  end (off: the first line turns secondary too). A tap edits; a long press offers **Edit** and
  **Delete**, which asks: "Delete this schedule?", the row's two lines and "It won't play again.",
  **Delete schedule** / **Cancel**. None yet: "No schedules yet. The piano can play by itself at
  set times: a channel, a playlist or a piece."
- **Add schedule**, the app's outlined button, with "The tablet starts them: keep it on, charged
  and near the piano."
- **The words.** Days: "Every day", "Weekdays", "Weekends", "Wednesdays" for one day, else the
  days in week order, Monday first, "Mon, Wed, Fri". Times on the 24-hour clock with two-digit
  hours, "07:45". "until 13:15", or "until the end". The volume as every percentage in the app,
  "70%", and nothing when the schedule sets none. What plays: "Calm channel", a playlist's name,
  a piece's title; "A deleted playlist" / "A deleted piece" once it is gone.

## The editor

A sheet on the elevated tone with its drag handle: SCHEDULE over **Add schedule** or **Edit
schedule** in Title, then:

- **DAYS**: a chip a day, Mon to Sun (TalkBack reads "Monday"), then **Weekdays** and **Every
  day**, chosen when the days are exactly those. A chosen chip carries its check and, on the
  sheet (the elevated tone the theme also gives chosen chips), takes the surface's tone inside a
  tertiary hairline, as the panel's chips do.
- **TIME**: **Starts**, the time on the app's outlined button, which opens the time picker; then
  **Until the end** (a switch: "A playlist or a piece plays to its end; a channel plays until
  someone stops it"); off, **Ends**, with **"The next day"** under it when the end comes before
  the start. A new schedule starts at the next whole hour, ends an hour later, on weekdays.
- **The time picker**: Material's clock dial on the 24-hour clock, in the ink only: the chosen
  hour or minutes on `contentPrimary` with the surface's tone for its figures, the other on the
  surface's tone; the selector `contentPrimary`; the dial on the surface; the dialog itself on
  the elevated tone, as the app's other dialogs (not Material's own dialog, which tints the paper
  grey), the field's name as an eyebrow (STARTS), **Cancel** and **Done**.
- **PLAYS**: chips **Channels · Playlists · Pieces**, then the choices as rows, the name in Body
  over what it is ("32 pieces", "Built in · 14 pieces", "Debussy · 4:08"), a check at the end of
  the chosen one. The channels in their order (one too small reads "Add more pieces" and can't be
  chosen); the playlists, the built-in ones first; the pieces through the Library's search field,
  and before a search the ones played or added last (PLAYED OR ADDED LAST).
- **VOLUME**: **Set the volume** (on at first, "Off: the piano plays as it is set, and a channel
  at its own volume"), then the **Volume** slider from 0 to 100% with its value and the channel
  sheet's line ("The piano's own volume while it plays, or how hard its keys are struck where the
  piano has none. What was there comes back when it ends.").
- Above **Cancel** and **Save**, what keeps it from saving, in Body, secondary: "Choose at least
  one day.", "Choose what to play.", "The end must differ from the start." (Save is greyed
  meanwhile), or "There are 50 schedules already. Delete one first."
- **From a channel's card**: its long press's **Schedule** (no longer greyed "Coming in the next
  update") opens the editor with that channel chosen, at the channel's own volume.

## "Next", where nothing plays

With nothing loaded, Now playing and the tablet's now-playing panel show **NEXT: WEDNESDAY 12:30,
CALM** in the eyebrow style, centred 8 dp above "Choose a piece from the library."; nothing when
no schedule is ahead. It moves on as the minutes pass.

## When a schedule plays

- At its minute the tablet wakes (asleep or dozing), its playback notification comes up, and if
  the piano is not connected the tablet reaches for the last one and waits up to 20 seconds. It
  waits a moment more for the piano's settings, so the volume goes to the piano's own. Then it
  plays, in place of whatever played: a channel (at the schedule's volume, else the channel's
  own), a playlist from its top, or a piece, each after the usual pause before a piece.
- **The volume** is held as a channel's is: never saved on the piano, and what was there comes
  back when the schedule's play ends: at its end time, by a stop, when its list runs out, or when
  someone plays something else. A schedule or a channel that follows another keeps the first
  one's "what comes back".
- **At the end time** the tablet stops what the schedule started, if it still plays; something
  the person chose meanwhile plays on. "Until the end": a playlist or a piece plays out, a channel
  until someone stops it.
- **Missed**: no piano within 20 seconds, and nothing plays; the page's last line and the
  connection log (Share diagnostics) say "Missed: Wednesday 12:30 (piano not connected)". While
  the piano's firmware is being updated (v1.6), a start is missed the same way, "Missed: Wednesday
  12:30 (the piano was updating)", and the piano is not asked for. Two schedules at the same
  minute: the first on the page plays, the other is skipped (in the log).
- A schedule turned off or deleted while it plays leaves what it started playing.
- The tablet must be on, with Bluetooth on: a tablet switched off misses what falls while it is
  off; after a restart the next schedule is set again by itself. The piano's own safety layers
  (its hold ceiling, its silence on a dropped link) apply as to any playing.

## The web panel's Schedule page

- The page's head: **Schedule**, and **Add schedule** (outlined) at its end. Then NEXT: …, the
  last line in the notes' size, a banner while exact alarms are off on the tablet ("Exact alarms
  are off on the tablet, so no schedule will start. Allow them there: Piano › Schedule › Allow
  exact alarms."), a hairline, the rows as on the tablet (the two lines, the switch, a ⋮ menu with
  **Edit** and **Delete**; Delete asks in the row itself: "Delete this schedule?", **Cancel**,
  **Delete schedule**), "No schedules yet. …" and the tablet's line.
- **The editor** opens above the list, the tablet's sheet laid flat: DAYS, TIME, PLAYS, VOLUME,
  the same chips, switches and rows; the times as two selects each, the hour 00–23 and the
  minutes, so every browser shows the tablet's 24-hour clock; Cancel and Save, and the tablet's
  own words when it refuses one. The page reads the list again whenever the tablet's schedules
  change.
- Now playing with nothing loaded: "Choose a piece from the library." with NEXT: … under it, in
  the eyebrow.

## The panel's Up next column (the M18 review's fix)

From 1280 px the Up next column beside Now playing widens with the window (three fifths of Now
playing's column, never under 360 px; 1100–1279 px keeps the fixed 360); its titles take a second
line before any ellipsis, there and on the Up next page, while the composer · length line stays
single.

# v1.7 — M23: Studio, part 1 (transcription)

Steven wants pieces made on the tablet itself: no service, no account, nothing sent anywhere.
**Studio** is where that happens. Its first job turns a piano recording into a piece: the tablet
listens to the audio with a transcription model (ByteDance's piano transcription, CC BY 4.0) and
writes what it hears, notes, velocities and the pedal, as a MIDI file in the library. Composing
(M24) follows; its model is already listed. The models are large and optional: the app downloads
them only when someone asks, from the app's own GitHub release, and checks each one against a
hash built into the app. Everything above still holds. Not released yet: 1.7 comes after M24.

## Where Studio is

- **Piano › CONTROL › Studio**, after Kiosk. Its value: **"No models"**, **"1 model"**, **"2
  models"**; while a job runs, **"Downloading 34%"** or **"Transcribing 42%"** ("Transcribing"
  while the recording is read, **"Waiting"** while a job waits its turn).
- **Library › + › Transcribe a recording…**, below a hairline after Fetch artwork, with "Any
  piano recording. About a minute per three minutes of audio." (and "Downloads the transcription
  model (125 MB) first." while it isn't there). It opens the same picker as the page. The sheet
  now opens all the way up, so its last row is never cut off on a phone.
- **The web panel's Studio page**, after Add (below).
- **Where Studio can't run**, the hub's row keeps its place without a chevron, the reason under
  its name in the eyebrow's size: **"Studio isn't available on this device"** (ONNX Runtime's
  library doesn't load on it) or **"This tablet doesn't have enough memory for Studio"** (under
  2.5 GiB); the + sheet has no Studio row, and the panel's page says the same
  line. Whether Studio runs is asked once per process, the first time one of these is shown,
  never at start.

## The Studio page

- **MODELS**, a row a model: its name in Body (**Transcription**, **Composing**), then in the
  eyebrow's size, sentence case: **"125 MB · CC BY 4.0 · Turns a piano recording into a piece."**
  (the composer's: "For composing, which comes in the next update."), and **Download** (outlined).
  Downloading: **"Downloading · 42 of 125 MB"** over the hairline progress line, and **Cancel**.
  Installed: **"Installed · 125 MB · CC BY 4.0"** and **Remove**. A download that failed says why
  in its line ("Downloading a model needs an internet connection.", "Couldn't reach the download
  server.", "The download didn't match the model; try again.", "There isn't enough free space for
  the model.").
- **TRANSCRIBE**: **Transcribe a recording…** (outlined) and the note under it. When the tablet's
  memory refused the last try, that line follows as the page's note (Body, secondary, after a
  hairline): **"Close other apps and try again."**, "The tablet ran short of memory, so the
  transcription stopped. Close other apps and try again." or "This tablet doesn't have enough
  memory for Studio.".
- **JOBS**, newest first, while there are any (the last twenty that ended, and those waiting or
  running): the recording's file name (the piece's title once made), its line, a hairline while it
  runs, **Cancel** while it waits or runs, **Listen** while its piece waits for Keep or Discard.
  The lines: "Waiting", "Reading the recording…", "Transcribing · 42%", "Adding it to the
  library…", **"Ready: listen, then keep it or discard it"**, **"Kept as Clair de lune"**,
  "Discarded", "Cancelled", or the failure's own sentence.
- On a device Studio can't run on, the page is STUDIO and the reason, nothing else.
- In kiosk mode the page is locked as the other settings pages are.

## A transcription

- **The picker** is Android's document picker for audio (and Ogg, which some apps label as an
  application): m4a, mp3, wav, flac, ogg, opus and whatever else the tablet decodes. MIDI files
  appear there too (Android counts them as audio); picking one ends the job at once with
  **"That's a MIDI file already. Add it with Add files."**
- **The steps**: when the transcription model isn't there yet, its download is queued first and
  the transcription waits for it. Then the recording is read (decoded, made mono and 16 kHz), then
  transcribed ten seconds at a time (the percentage), then written as a piece and added to the
  library. One job at a time; others wait their turn. Cancel stops a job between two windows.
- **What it refuses**, in its line: "A recording can be 200 MB at most.", "A recording can be 20
  minutes long at most.", "This file isn't a recording the tablet can read.", "The recording is
  empty.", "No piano was heard in this recording.", and the memory lines above.
- **The piece**: the recording's file name without its extension ("Recording · Sep 28, 2026" when
  it has none) by **Made in Studio**, a composer that is no one: no portrait and no Wikipedia text
  is looked for, and the Library draws its roll card. Its sheet reads **"Made in Studio · Sep 28,
  2026"** (the date in the tablet's own style) and has no "From Wikipedia" line. It plays, queues
  and joins playlists like any other piece.
- **Keep or Discard.** A new piece is in the library at once, but undecided. Once it has been
  heard, fifteen seconds of it or all of it, Now playing (and the tablet's now-playing panel) shows
  an outlined banner where playback's problems show: **"Keep this piece?"**, "Made in Studio from a
  recording. Discard deletes it.", **Keep** and **Discard**. Keep ends the question for good.
  Discard silences the piano, lets the player go of the piece (it leaves Up next too) and deletes
  it from the library; in kiosk mode it asks for the kiosk PIN first. An undecided piece stays
  undecided across restarts until one of the two is chosen (or it is deleted from its menu); the
  job's **Listen** plays it and opens Now playing.
- **While it runs**, the Library shows it under its import bar, the way imports and artwork do:
  **"Transcribing Prelude in C.m4a · 42%"** over the hairline progress line ("Reading …", "Adding …
  to the library…", "Downloading the transcription model · 42 of 125 MB").
- **The notification** (channel **Studio**, low importance, silent): "Downloading the
  transcription model" or "Transcribing Prelude in C.m4a", its line, its progress and **Cancel**.
  When the last job ends it leaves one line: **"Prelude in C is in the library"** with "Listen,
  then keep it or discard it." (a tap opens the Library), "The transcription model is installed",
  "The transcription didn't finish" with the reason, or "… didn't download".
- **The piano plays on.** Studio works on its own thread at background priority and never touches
  the player's; the piece playing keeps its time (measured on the emulator: no lateness beyond the
  emulator's own jitter; BUILD_SPEC › v1.7 — M23 › Measured).

## Memory and time

- Studio is offered on devices with at least **2.5 GiB** of memory (Android's `totalMem`; a tablet
  sold with 3 GB passes). A transcription starts only with **900 MiB** free above Android's low
  mark and Android not short of memory; between windows it stops if the free memory falls under
  128 MiB (something else is taking it). The app peaks at about **0.75 GB** while it transcribes.
- About **a minute per three minutes of audio** on a recent tablet (the spike measured 0.32 × real
  time; the emulator on the Mac takes 65–69 s for three minutes while a piece plays).
- The app itself grows by ONNX Runtime's library (about 11 MB to download); the models are separate
  downloads, 125 MB and 173 MB, kept in the app's own storage until Remove.

## The web panel's Studio page

- **MODELS**: each model's name and line as on the tablet ("Installed · 125 MB · CC BY 4.0",
  "Downloading · 42 of 125 MB" over a 2 px bar). Models are downloaded on the tablet.
- **TRANSCRIBE**: a drop zone, "Drop a piano recording here", ".wav, .mp3, .m4a, .flac, .ogg and
  the like, up to 200 MB. About a minute per three minutes of audio." and **Choose recordings**.
  Recordings go one at a time, each checked in the page first as the tablet will ("Not sent: only
  recordings (.wav, .mp3, .m4a, .flac, .ogg…)", "Not sent: a recording can be 200 MB at most"),
  their rows reading "Sending 52%" over the bar, then **"Sent to the tablet · transcribing there"**.
- **JOBS**, as on the tablet, with **Cancel** on those waiting or running; the lines are the
  tablet's own. Keep and Discard stay on the tablet, where the piece is heard.
- Where Studio can't run, the page is the reason alone.
