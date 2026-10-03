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
  three on a phone under 360 dp.)*
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
  keep their v1.1 names. **Superseded in v1.12** by the divider and the View menu: see
  v1.12 — the split and the View menu.)*
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
  see v1.5 — M17; v1.12 moves NOTES to Now playing's View menu).
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
- *(v1.18 — M47: the panel is dark by default, and Appearance (Dark · Light · Follow system) is on its Settings page,
  not in the frame.)*
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
exists: "Set a PIN on the tablet first: Piano › Remote control." A session lasts a year unused,
across restarts of the app, until the PIN changes or Web control turns off.

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
hash built into the app. Everything above still holds. Released in 1.7, with M24's composing.

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
  (the composer's, until M24: "For composing, which comes in the next update."; in 1.7 it is v1.7 —
  M24's line), and **Download** (outlined).
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
  library. One job at a time; others wait their turn. Cancel stops a job between two windows; once
  its piece is being saved a Cancel changes nothing, and the piece waits for Keep or Discard (audit
  delta 2).
- **What it refuses**, in its line: "A recording can be 200 MB at most.", "A recording can be 20
  minutes long at most.", "This file isn't a recording the tablet can read.", "The recording is
  empty.", "No piano was heard in this recording.", "More notes were heard in this recording than a
  piece can hold." (past 200,000 notes: audit delta 2), and the memory lines above.
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
  While Studio has eight jobs waiting or running, the tablet refuses more before a byte is sent
  (audit delta 2): the row reads "Not sent: Studio has 8 jobs to do already. Try again when one has
  finished.", and the compose form says the same.
- **JOBS**, as on the tablet, with **Cancel** on those waiting or running; the lines are the
  tablet's own. Keep and Discard stay on the tablet, where the piece is heard.
- Where Studio can't run, the page is the reason alone.

# v1.7 — M24: Studio, part 2 (composing)

Studio's second job composes a new piece **in the manner of** one from the library: the composing
model (the Anticipatory Music Transformer, Apache 2.0) continues the first fifteen seconds of that
piece into music of its own, steered by a mood, a key, a tempo and a length. Like a transcription it
runs on the tablet alone, and its piece waits for Keep or Discard. Everything in v1.7 — M23 still
holds; the composing model's row on the Studio page now reads **"173 MB · Apache 2.0 · Writes a new
piano piece in the manner of one in the library."** This is 1.7 (with M23 and the security audit's
second delta).

## Where composing is

- **Piano › Studio**: a **COMPOSE** section after TRANSCRIBE: **Compose a piece…** (outlined) and
  the note "Runs on this tablet. About a minute for a two-minute piece." (with "Downloads the
  composing model (173 MB) first." while it isn't there). When the tablet's memory refused the last
  composition, that line follows as the section's note ("Close other apps and try again.", "The
  tablet ran short of memory, so composing stopped. Close other apps and try again.").
- **Library › + › Compose a piece…**, below Transcribe a recording…, under the same hairline and
  with the same note. It opens the same sheet over the Library.
- **The hub's value** while it runs: **"Composing 42%"** (as "Transcribing 42%").
- **The web panel's Studio page**: a COMPOSE section between TRANSCRIBE and JOBS (below).
- **In kiosk mode** the Studio page is locked as every settings page, and the Library's + waits for
  the kiosk PIN, so composing is a settings action there.

## The sheet

A sheet with its drag handle, on the elevated tone, laid out as the schedule editor:

- The eyebrow names the seed: **IN THE MANNER OF CLAIR DE LUNE (CLAUDE DEBUSSY)**; the title
  **Compose a piece**.
- **MOOD**: four chips, **Calm** · **Bright** · **Wild** · **Melancholy**, Calm chosen. Calm plays
  softest and most smoothly, Wild most freely; Melancholy turns the key to the seed's minor (its
  relative minor) while no key has been chosen.
- **KEY**: a chip for each of the twelve tonics, spelled as the mode spells them (D♭ in major, C♯
  in minor), then **Major** · **Minor**. The seed's own key is chosen at first: read from the file's
  key signature where it has one (its major or its relative minor, as the notes lean), else from
  its notes. TalkBack reads "D flat major".
- **TEMPO**: "Tempo", "BPM" beneath, and the − 68 + stepper (40–200, repeating while held), the
  seed's own tempo at first, with the note "The piece's own tempo" (or "The piece's own: 68 bpm"
  once changed).
- **LENGTH**: "Length", "MIN", − 2 + (1–5 minutes).
- **IN THE MANNER OF**: the seed's title over "Claude Debussy · D♭ major · 68 bpm", and **Change**
  (outlined), which opens the library's search below it (the pieces played or added last before a
  search; the chosen one checked); a piece chosen closes it and brings its own key and tempo. The
  seed at first is the piece played last that Studio didn't make, else the library's first by
  title. An empty library says "A composition starts from a piece in the library. Add one first."
- The note, then **Cancel** and **Compose**, which queues the job and closes the sheet.

## A composition

- **The steps**: when the composing model isn't there, its download is queued first. Then the seed
  is read from the library and the model writes, token by token, until the length is reached or
  its budget (45 tokens a second of music, 9,000 at most) is spent; then the piece is written
  and added. One job at a time; Cancel stops it between two tokens.
- **The job** is named for its seed: **"In the manner of Clair de lune"**, "Composing · 42%" over
  the hairline, "Adding it to the library…", then as a transcription's: "Ready: listen, then keep
  it or discard it", "Kept as Composition · Sep 28, 2026 2:05 PM", "Discarded", "Cancelled", or its
  failure's sentence ("That piece has no notes to start from. Choose another.", "That piece is no
  longer in the library. Choose another.", "The model didn't write any notes this time. Try
  again.", the memory lines, "The composition didn't finish.").
- **The notification** (channel Studio): "Composing in the manner of Clair de lune", its line, its
  progress and **Cancel**; then "Composition · Sep 28, 2026 2:05 PM is in the library" with
  "Listen, then keep it or discard it." The Library's line: "Composing in the manner of Clair de
  lune · 42%".
- **The piece**: **Composition · Sep 28, 2026 2:05 PM** (the date and time in the tablet's own
  style) by **Made in Studio**, with its roll card; its sheet reads **"Made in Studio · in the
  manner of Clair de lune (Claude Debussy)"** (the title alone when the composer isn't known) and has
  no "From Wikipedia" line. It holds only the new music, never the seed, written at the tempo
  chosen so its bars follow its beats.
- **Keep or Discard**, as a transcription's, after 15 seconds of it: "Keep this piece?",
  **"Composed in Studio in the manner of Clair de lune (Claude Debussy). Discard deletes it."**,
  Keep and Discard.

## What the music is

- New music only, continuing the seed's manner; the model was trained on the Lakh MIDI collection,
  so it knows more than the piano, and the app keeps it to the piano: piano notes only, keys 24–107
  (folded by octaves), no pedal.
- Lightly on a sixteenth-note grid at the chosen tempo (each onset half-way to its line), starting
  on a beat.
- Velocities by mood, never under 20 or over 110: the top of each chord sings, the bass and inner
  voices step back, a four-bar swell, a little unevenness; Calm around 46, Melancholy 52, Bright 66,
  Wild 78. The last two bars fade to 45 %.
- Playable by the piano as written: a key is struck again no sooner than 120 ms after itself, and
  no more than ten notes start at one instant; rests (a second without a new note) never follow
  one another, so the piano never falls silent for long.

## The web panel's Studio page

- **COMPOSE**: **Compose a piece…** and the note; it opens the form above JOBS, the tablet's sheet
  laid flat: the eyebrow, **Compose a piece**, MOOD and KEY chips, TEMPO and LENGTH steppers (− and
  + repeat while held), IN THE MANNER OF with **Change** and the library's search, the note, the
  tablet's refusals in its own words, **Cancel** and **Compose**. Then "Composing on the tablet.
  It shows under Jobs." and the job under JOBS, with Cancel, as the tablet lists it.

## Memory and time

- A composition starts only with **700 MiB** free above Android's low mark and Android not short of
  memory; it stops if the free memory falls under 128 MiB while it runs. The app peaks at about
  **0.6 GB** while it composes (measured 0.55–0.62 GiB on the emulator).
- About **a minute for a two-minute piece** on a recent tablet; on the emulator a two-minute piece
  took 5–32 s and a five-minute one 13–25 s, depending on how dense the music is and how busy the
  computer running it was.

## The app on every device again

M23 made the whole app arm64-only; now only ONNX Runtime's library is, and the app installs
wherever 1.6.2 did. Where the runtime isn't there, Studio hides as on any device it can't run on.

---

# v1.7.1 — the resting screen

Steven asked (2026-09-30) for the resting screen to show the piece's album art and a few lines about
it instead of the roll and the keys, and to come and go slowly. During the run he placed the byline
at the top right, on two lines, and let the window's shape decide where the art stands. Everything
above still holds except where this section says otherwise; the paper roll stays, as a choice. This
is release 1.7.1.

## Standby shows

- **Piano › Display › STANDBY › Standby shows**, after Standby canvas: chips **Art and notes** (the
  default) · **Paper roll**. Display mode's note reads **"The piece's art and title fill the screen for
  passers-by"**, true of either.
- **Paper roll** is v1.5 — M17's display as it was (the portrait faint behind the title, the roll
  across the whole width over its keyboard, "● Sent to piano" at the foot), but for the byline, which
  moves to the top right with every resting screen's (below).

## Art and notes

On the chosen canvas (true black, or the app's own ink or paper), and nothing else on it:
*(v1.15 — M41 retires "nothing else on it" and "never a faded backdrop": the album's colours now drift behind Art and notes.)*

- **The art**, large and sharp, never a faded backdrop: the composer's portrait, else the piece's roll
  card, mounted as every art surface is (the elevated surface inside a hairline, the card corners); in
  black and white when Artwork in black and white is on.
- **The title** in **Display Large** (45 sp) on wide frames (a tablet, a phone on its side) and
  **Display** (34 sp) on phones, three lines at most; under it **the composer** in the eyebrow (16 sp
  beside Display Large, as display mode had it), with " · CALM · CHANNEL" while a channel plays.
- **The description**, in Body and the secondary ink, at most **six lines on wide frames and four on
  phones**, cut with an ellipsis, and set as one paragraph: the piece's own notes when it has any (its
  Wikipedia extract, kept once its sheet has fetched it; from v1.8 the resting screen asks for them
  itself, once and behind everything else, when they were never looked up and *Fetch artwork
  automatically* is on, and they take the composer's place when they come), else the composer's
  blurb, else nothing at all.
  The screen never says that nothing was found. A piece made in Studio shows its own line, "Made in
  Studio · in the manner of Clair de lune (Claude Debussy)". Where the room runs short (a large font, a
  phone on its side) the description gives way first.
- **Its credit** (v1.8): under Wikipedia's text, one line in the eyebrow, sentence case, **"From
  Wikipedia · CC BY-SA 4.0"** (the piece's own extract or the composer's blurb alike; there is no link
  to follow on a screen any touch dismisses). The app's own line (a piece made in Studio) has none.
- **Where they stand: the window's shape decides**, whatever the device. In a window wider than it is
  tall (a tablet on the piano, a phone on its side) the art stands at the left, a square 55 % of the
  window's height, vertically centred, and the words to its right at reading width (720 dp at most),
  the two centred as one across the screen with a tenth of the art between them (32–64 dp). Only a
  window taller than it is wide stacks them: the art centred on top, 45 % of the window's width, and
  the words centred under it. The art never takes the words' room; the piece stands in the middle,
  clear of the byline above and the dot below.
- **The live dot alone** at the foot on the left, without its words; TalkBack still reads "Sent to
  piano" or "Not connected", and the filled dot and the hollow ring still differ by shape as well as
  colour. No roll, no keyboard strip, no controls.

## The byline at rest

On every resting screen (Art and notes, Paper roll, and kiosk mode's rest with nothing loaded, its
request code or not): at the **top right**, on **two lines**, right-aligned, in the eyebrow:
**PLAYER PIANO** over **MADE BY STEVEN JIN** ("made by", where the tabs' byline says "by"), inside the
margins the title keeps at the top left: 16 dp from the top and the title's 24 dp from the side. It
fades with the screen and takes part in its shift. The tabs keep their one-line byline under the title.

## Burn-in

Every resting screen, whatever it shows, steps 4 dp round a small square once a minute, never
animated, as only kiosk mode's rest did before; the byline, the art, the words and the dot step
together.
*(v1.15 — M41: the screen is no longer still; behind Art and notes the album's colours drift while a piece plays, without the step, and hold still while it comes or goes.)*

## Motion

| Moment | Motion | Duration / easing |
|---|---|---|
| Coming to rest | The resting screen cross-fades in over the app. | 1,500 ms, standard easing |
| A touch | It fades away. The touch does nothing else, as before, but the app beneath is live at once: a second touch, Back and TalkBack reach it while it fades. | 600 ms, standard easing |
| A new piece while resting (Art and notes) | The art, the title, the composer and the description cross-fade together, old and new at once. | 1,200 ms, standard easing |

Nothing on the screen moves while it comes or goes: it keeps clear of the display's cutout alone (the
system bars step aside while it rests and come back as it leaves), and holds its piece and its margins
once it starts to go. **Reduced motion**: all three are cuts. The idle timer (a minute without a touch,
the same touches) and the screen staying on are as before.

---

# v1.8 — M25: piano sound on the tablet

Steven asked (2026-09-30): "add piano noise when playing, with volume control; don't make it sound so
synthesised." The tablet gets a piano voice of its own: **recordings of a real upright**, note by note,
never a synthesiser, playing what the app plays, when the piano isn't there to (or, if Steven chooses,
beside it). Everything above still holds except where this section says otherwise: v1.1's "No sound from
the phone" on the Keys screen gives way to the choice below.

## The sound

- A Kawai upright recorded in a living room, key by key at two strengths: FreePats' **Upright Piano KW**
  (2022-02-21), published under CC0. It is the app's one sound, a 57 MB download (the models' way: from
  the app's own GitHub release, its SHA-256 checked against the one built into the app), kept until
  Remove. Nothing about it is generated: each note is the recording of that key (or its neighbour, moved
  by a semitone or two), fading as the real string fades, damped when the key is let go (a little over
  half a second), held by the sustain pedal as the piano holds it.
- The tablet plays exactly what the piano is sent, at the same moments: pieces, channels, schedules, the
  Keys tab and Studio's Listen, after the pause before each piece, folded into C1–B7, at the Velocity the
  person set; pause, stop and seek silence it as they silence the piano.
- **Loudness.** Soft pieces are soft and loud ones loud, as on the piano; **Volume** 60 % at first sits a
  quiet piece (Clair de lune) well below a loud one, and 100 % brings the quiet ones up to about the level
  of other media. At the top the loudest chords are turned down smoothly rather than clipped. The tablet's
  own media volume governs it too, as any music app.

## When it sounds

**Piano sound on the tablet**, three chips:

- **Off**: never.
- **When the piano isn't connected** (at first): the tablet plays while the piano's link is anything but
  connected, so it never doubles the real piano out of step. The piano connecting mid-piece silences the
  tablet at once (within a note; measured on the emulator: 3 ms after the connection, before the piece's
  next note), and the piece goes on on the piano.
- **Always**: the tablet plays beside the piano too (Steven's choice). The page says what that costs:
  "It may sound slightly early or late compared with the piano." (the tablet's own delay, some 20–40 ms,
  and the piano's are not the same).

Another app taking the sound (a call, a video) pauses what plays, as the piano's drop does; Play resumes
it. A notification lowers the tablet for its moment, as Android lowers any music.

## Piano › Playback › TABLET SOUND

After the page's rows, a section under the eyebrow **TABLET SOUND** (the page's first section keeps its
hairline alone):

- **Piano sound on the tablet**, the three chips as the app's choice rows draw them (the chosen one with its
  check), and under them, in the eyebrow's size, sentence case, secondary, what the choice does: "The tablet
  stays silent. Pieces play on the piano alone." · "The tablet plays pieces and the Keys tab itself while the
  piano isn't connected." · "The tablet plays along with the piano. It may sound slightly early or late
  compared with the piano."
- **Volume**, the app's hairline slider with "%" beneath the label and the value beside it, "60%".
- **The SoundFont's row**, as Studio's model rows: **Upright piano** in Body, then in the eyebrow's size,
  sentence case: "57 MB · CC0 · FreePats · A Kawai upright, recorded note by note." with **Download**;
  "Downloading · 12 of 57 MB" over the progress hairline with **Cancel**; "Installed · 57 MB · CC0" with
  **Remove**; or why the download failed, in words ("Downloading the piano sound needs an internet
  connection.", "Couldn't reach the download server.", "The download didn't match the piano sound; try
  again.", "There isn't enough free space for the piano sound.", "The piano sound isn't offered right
  now.") with Download again.

The hub's Playback row keeps its value ("2 s pause · 100%").

## The speaker (Now playing, and the tablet's now-playing panel)

- At the end of Now playing's tempo row, after the stepper (in the panel, at the start of its foot row,
  across from "● Sent to piano"): a **speaker** (a cone and two arcs, 48 dp target) in the content colour
  while the tablet sounds, and in the tertiary grey while it doesn't (off, the piano connected, or no
  SoundFont yet), as Shuffle and Repeat show off and on. TalkBack: "Tablet sound, volume 60%" / "Tablet
  sound, off here, volume 60%".
- A tap opens a **popover**, the app's standard one (the elevated tone, with a hairline edge so it holds
  its shape over the score's panel in the dark; not glass; from v1.9 the menus' glass, **GlassPopover**,
  below the speaker with its end at the speaker's end; from v1.10, in the panel, with its start at the
  speaker's start, so it opens within the panel instead of across the divider over the list): the eyebrow
  **TABLET SOUND**, what the sound is doing in Body, secondary ("Playing on this tablet while the piano isn't connected.", "Playing on this
  tablet with the piano.", "Silent while the piano is connected.", "The piano sound isn't on this tablet
  yet.", "Off. Piano › Playback turns it on."), then **Volume** with its value and the hairline slider, which
  is heard as it moves. While the sound waits for its download, the download's line and **Download** (or
  the progress hairline) follow. The mode is not here: it is a setting.
- **Now playing's note**: while the mode wants the tablet to sound and the SoundFont isn't there, one line
  under the tempo row, in Body, secondary: "Hear it on this tablet: the piano sound is a 57 MB download."
  with **Download**; then "Downloading the piano sound · 12 of 57 MB" with **Cancel** over the progress
  hairline, or the failure's line with Download again. It goes once the sound is installed, or the mode no
  longer wants it (the piano connected, Off).

## The Keys tab

Keys sound on the tablet while it plays the piano sound (the piano not connected, or Always), and the
connection line says so: "Not connected. The tablet plays these keys." (otherwise, as before, "Not
connected. The piano won't play these keys."). The same key rules as the piano's (a key held is not struck
again, the 100 ms guard); every note sounds at least 50 ms, as a key tapped briefly still does.

## The web panel

Now playing gains, under the channel's volume, **Piano sound on the tablet** with "%" and the slider and
its value (the same volume: moving either moves the other), and under it the tablet's line ("Playing on the
tablet while the piano isn't connected.", "Silent while the piano is connected.", "The piano sound isn't on
the tablet yet: download it there, in Piano › Playback."). Hidden while the mode is Off. The mode itself,
and the download, stay on the tablet.

## Kiosk mode

The volume is free, like everything on Now playing: the speaker's popover never asks for the kiosk PIN. The
mode and the SoundFont are settings: the Playback page is locked as every settings page is.

## About

A line after Studio's models: "Piano sound: Upright Piano KW, FreePats (CC0)".

---

# v1.9 — Liquid Glass across the functional layer

Steven asked (2026-09-30) for Apple's Liquid Glass across the whole app, designed with the
`apple-design` skill (`references/hig/liquid-glass.md`, `materials.md`, `scroll-views.md`), and
reminded us that the app is made for the school tablet first. v1.5 — M16 put glass on the floating
controls only; this pass extends it to every floating functional surface, adds the scroll-edge
effect, and brings the primary action back to a filled circle. Everything above still holds except
where this section says otherwise; **M16's "Where." list in *The glass* is superseded by the list
below**, and its lens is gone.

## Two layers

- **Content** is never glass: rows, cards, tiles, the connection card, channel cards (their band is
  a static scrim), the roll, the score, the art, the keyboard, the Keys mini-map, the tempo row, the
  resting screen and display mode's controls. A control in the content layer never takes glass.
- **The functional layer** is glass: the headers, the tab bar and the rail, the mini player, the
  transport, sheets, menus, popovers and dialogs, the Keys pills. Nothing else.

## The material

Regular glass everywhere (text-heavy surfaces), monochrome: the surface colour over a 24 dp blur of
whatever lies beneath, no tint, no noise, no colour but the live red dot; a 1 dp edge in the hairline
token with the 1 dp specular line inside it, along the edge that faces the content (a bar's top, a
header's bottom, the rail's end, round a floating shape with the line along its top).

| Fill | Where |
|---|---|
| **0.72** (bars) | the headers, the tab bar and the mini player, the rail, the transport, the Keys pills |
| **0.86** (sheets) | every bottom sheet (Up next, the piece sheet, Add, the PIN sheets, a channel's volume, the schedule editor, Compose, the QR code, a share), menus (a row's, a tile's, a playlist's, a channel's, a schedule's), popovers, dialogs (Add to playlist, Rename, the confirmations, the time picker) |

- **What sits on it.** On a bar, text is the content colour (7.1:1 and 8.3:1 over the worst backdrop,
  pure white under the dark glass, pure black under the light); glyphs may be the secondary grey
  (3.1:1); the tertiary grey never. On a sheet the content colour reads 11.8:1 and 11.9:1 and the
  secondary grey 5.3:1 and 4.6:1, so sheets keep their secondary lines; their tertiary eyebrows take
  the secondary grey (3.8:1 and 3.5:1 would be too little).
- **Why 0.86 and not 0.84.** At 0.84 the secondary grey on the paper reads 4.3:1 over the worst
  backdrop (a black portrait under a sheet); 0.86 is the least fill, in hundredths, at which it reads
  4.5:1 on both appearances. The difference does not show.
- **Only where something passes beneath** (M16's rule, kept): a surface blurs only while content can
  pass beneath it. The rail, the Keys pills, the tab bar over Now playing and Keys, and a header with
  nothing scrolled beneath it draw the glass's look without a blur: the surface itself, their edge,
  the text rule.

## The headers

Every tab's header is a glass navigation bar the content scrolls under, reaching up under the status
bar: the title, the byline and the header's action (the Library's +, Now playing's queue glyph); the
Piano tab's pages have theirs (the back glyph on phones, the title beside the hub). On the tablet's
splits each pane has its own: the Library's list and the now-playing panel (whose NOW PLAYING row is
its header, as tall as the list's at every text size), the Piano hub and its page.

A header has the two appearances of a navigation bar (UIKit's scroll-edge and standard appearances):

- **At rest**, with nothing scrolled beneath it, it is the surface itself, with no edge and no band,
  its byline in the tertiary grey: every tab looks as it always has. (The Library's hairline under its
  header becomes this edge.)
- **With content beneath it**, the glass shows: the rows blurred under the bar's fill, the edge and
  the specular line along its bottom, the scroll-edge band, and its text in the content colour (the
  byline, the Library's progress lines). The change fades over 120 ms.

What stays in the content: the Library's search field and chips (they scroll away), the crash and
requests banners (the list's first lines). What joins the header: the Library's import, artwork and
Studio progress lines, which stay in sight. Now playing's column scrolls beneath its header only where
the screen is short; Keys never scrolls, and its header is always at rest.

## The scroll-edge effect

Where scrolling content meets a bar it fades into the glass instead of colliding with it:

- **The content's band**: over the last **24 dp** before the bar's edge the content fades, from clear
  to the surface at a sheet's opacity at the edge. Under the header only while content is scrolled
  beneath it (**at the resting scroll position there is no band**); above the tab bar and the mini
  player on phones while there is more beneath them; beside the rail over the content's own **16 dp**
  margin, where the list, the hub or a page meets it (a 24 dp band there would wash the first letters
  of every row, inset 16 dp).
- **The glass's band**: inside a blurring bar's edge the frost thickens over the last 24 dp, from the
  bar's fill to a sheet's (0.72 to 0.86), so the eye reads a denser band just where content goes under.
- Each pane has its own (the list and the panel, the hub and the page), level with each other.

## The primary action

The play control is the app's filled monochrome circle again wherever it stands: 72 dp, the content
colour filled, the surface-coloured glyph (17.2:1 and 16.3:1), sitting on the transport's glass band;
never glass on glass. A playlist's floating Play is the same circle at 56 dp. Shuffle, Repeat,
Previous and Next stay glyphs on the band.

## Sheets, menus and dialogs

One material for all of them (0.86), their scrim or dim behind as before. Sheets carry their grabber
inside the glass (32 × 4 dp, the secondary grey), with Material's sheet actions for TalkBack (close,
expand, collapse); dialogs keep the 24 dp corners, menus theirs and their shadow. The blur runs only
while one is open. A small popover (for a control's setting in place, such as a volume) is the same
glass, anchored below its control, 16 dp inside, its end at the control's end (from v1.10, for a
control at the start of a pane, such as the panel's speaker, its start at the control's start, so it
opens within the pane).

## Keys

The latching Sustain and, where the keys scroll, the ‹ › octave buttons are 48 dp glass pills floating
just above the keyboard's top edge: ‹ and Sustain at the start, › at the end. Nothing moves beneath
them, so they are glass without a blur: the surface, their ring in the action outline's grey (the
content colour while Sustain is on, reading *Sustain on*) and the specular line along their top. They
never cover the keys, whose tops are every key's soft end: the keyboard is never under glass. The
mini-map stays at the top, now across the whole width; VELOCITY moves under the keys beside the
connection line.

## Tablet first

- **The rail** is the main bar: glass without a blur (nothing passes beneath it), its end edge, the
  selected pill and the labels as before; the list, the hub and the pages fade into its inner edge
  over their margin.
- **The two-pane Library**: the list's header is glass with its band; the now-playing panel's NOW
  PLAYING row is its own header; the panel's transport keeps its glass band with the filled circle; the
  roll strip is content.
- **The Piano split**: the hub and the page each have a glass header; the connection card is content;
  sheets opened from either pane are glass over the whole window.
- **Now playing**: the score and the roll with the transport's band; the header is glass; the tempo row
  is content. The resting screen and kiosk mode's rest are untouched.

## Accessibility

- **Reduce Transparency** (Android's *High contrast text*, the nearest the platform has): every glass
  surface is the solid surface with its hairline, as before; no bands; controls that float on glass
  go back to their solid places (the transport under the roll). Sheets, menus and dialogs take today's
  elevated tone.
- **Increase Contrast** (the same switch, `ACCESSIBILITY_HIGH_TEXT_CONTRAST`): every hairline is the
  content colour at 0.4 instead of the hairline token (3.5:1 on the ink, 2.5:1 on the paper, against
  1.3:1).
- **Reduced motion**: the bands and the header's glass appear and go as a cut; sheets, menus and
  popovers appear without their motion (the platform's animation scale), and nothing morphs.
- Targets stay 48 dp or more.

## The web panel

The same two layers. Narrow, the tab strip is the bar: the surface at 72 % over a 24 px backdrop blur,
the hairline and the specular line along its bottom; once the page is scrolled beneath it the content
fades into it over 24 px, its frost thickens and its text takes the content colour. Wide, the section
list is the rail: glass without a blur, its end edge, the page fading into it over its margin. *(v1.18 — M47: wide,
the rail is a glass card 12 px from the window's edges, its sections in four groups; see v1.18 — M47.)* Row
menus and the toast are the sheets' glass (86 %); the PIN gate's card and the schedule and compose
editors are sheets laid flat. The play control is the filled circle. *prefers-reduced-transparency*
and *prefers-contrast: more* give solid surfaces (and more contrast, hairlines in the content colour
at 40 %); *prefers-reduced-motion* drops the bands' fade.

## The transport yields to a scrolling list

A blur is live while what lies beneath it moves: it is drawn again on every frame. Held upright, the
tablet had two live blurs on every frame of a scroll: the Library list's header, and beside it the
now-playing panel's transport, over the roll strip playing beneath it. So the transport yields
(decided at review, 2026-09-30):

- **While the list scrolls**, the panel's transport band stops blurring. For that while it is glass
  without a blur (the surface itself, as the rail and a resting header are, with its edge, the filled
  circle and the glyphs; the fill tokens unchanged), and the list's header keeps its blur. When the
  list comes to rest the band's blur comes back, fading in over 120 ms (a cut with reduced motion). A
  pause changes nothing but the blur: what sits on the glass keeps its colours.
- **The tablet on its side**: nothing changes. The transport stands under the strip there, so the
  list's header is already the only glass over a scroll.
- **Phones keep both**: the header and the tab bar blur a scrolling list together. Pausing the header
  through a fling was built and measured, made no measurable difference, and was dropped at review.

## Performance

One source per glass surface: the navigation content for the tab bar, the mini player and every sheet,
menu and dialog; each pane's own content for its header (recorded in a layer of its own, so a roll
playing in the other pane, or a list scrolling beside it, never re-blurs a header it is not under);
the note panel for the transport. A header blurs only its own bounds, and only while content is
scrolled beneath it. The wide surfaces (headers, sheets, menus, dialogs) blur a copy at a fifth of the
resolution; the bars keep M16's third. And beside a scrolling list the transport yields its blur
(above).

---

# v1.10 — M26: Steven Piano Cloud, the tablet's side

Steven asked (2026-09-30) for the web panel from anywhere, over HTTPS, without Tailscale, on his own
Cloudflare account, for many pianos, with a console of his own. The relay and the console are `cloud/`
(R1); this section is the tablet's side: it keeps **one outbound connection** to the relay, answers the
browsers the relay carries through **the same web panel** it serves on its own networks (the same routes,
the same PIN, the same pages), and gains a **CLOUD** section on the Remote page. Nothing above changes:
Web control on the tablet's own addresses works as before, with or without the cloud.

## What a browser sees

- The panel at **`https://<relay>/p/<piano>/`** (a short random id per piano): the gate, the PIN, then the
  panel exactly as on the tablet's address, its live updates included. The relay adds only the lock of
  HTTPS. Guests' request page (`…/request`) exists there only while **Guests can request** is on.
- **When the tablet isn't there** (off, asleep without a network, the app stopped): a page opened then is
  the relay's own "The piano is offline" page, which looks again every 30 seconds; a panel already open
  keeps what it showed and says, at the head of the window, "The piano is offline. The panel comes back
  when its tablet does.", its connection line "Offline"; it comes back by itself when the tablet does
  (to the gate, if the app restarted meanwhile: sessions live in the app's memory). A panel whose page
  loads but whose piano has gone shows an offline card in place of the gate, and looks again every 10 s.
- Every address the panel builds starts from where the page lives (its path), so the same files work on
  the tablet's own address and under the relay's `/p/<piano>/`; its socket is `wss:` when the page is
  `https:`.

## Piano › Remote control › CLOUD

After GUESTS, under the eyebrow **CLOUD**:

- **Remote access over the internet**, a switch, off at first. Disabled, with the reason under it in the
  eyebrow's size, until it can work: "Set a PIN first" (the panel's PIN guards the cloud too), then
  "Enrol this tablet first".
- While it is on, a line saying how the connection stands, read out as it changes: "Connected",
  "Connecting…", "Waiting for a network · retrying in 30 s" or "The relay can't be reached · retrying in
  2 min" (counted down), "Another tablet connected with this enrolment · retrying in 1 min", "Revoked in
  the console. Enrol again.", "Removed from the console. Enrol again." (the console forgot this piano),
  "This tablet's key is gone. Enrol again.".
- Under it, while the link can lead somewhere (not once revoked, removed or keyless), the panel's public
  link in Body, tabular, "The panel from anywhere, behind the same PIN" under it, and its QR code at
  96 dp on its paper card beside it; a tap shows it large on the sheet ("Scan to open the panel from
  anywhere"), as the tablet's own address is shown.
- **Cloud address**: the relay's host as typed ("steven-piano-relay.you.workers.dev"), or "Not set".
- **Enrol with code**: an outlined button; its note "Get a code from the console", or "A new code enrols
  this tablet again" once enrolled.
- Once enrolled, **Forget this cloud**: its note "Remote access stops and this tablet's key is deleted;
  its row in the console stays until removed there", then a dialog on the dialogs' glass: "Forget this
  cloud?" · "The tablet stops using <host> and deletes its key. To come back, enrol again with a new code
  from the console." · Cancel · Forget. The typed address stays for the next enrolment.

The hub's row reads "On · 100.101.2.3 · Cloud" with Web control on as well, "Cloud" with the cloud alone,
"Off" with neither. In kiosk mode the page, and so the section, waits behind the kiosk's PIN, as every
settings page does.

## Enrol with code

A sheet on the sheets' glass with its grabber, as the PIN sheet: the eyebrow CLOUD, the title "Enrol with
code", a line ("Get a code from the console", then what the relay answered), and two fields in the app's
outlined style: **Relay address** (a URL keyboard; remembered from the last time; "https://" and a
trailing slash are forgiven) and **Code** (capitals, tabular; any case, a dash or spaces or none:
`ABCD-EFGH`). **Enrol** is enabled while both read as what they must be; while it asks the relay it reads
"Enrolling…". On success the sheet closes and the switch can be turned on (the tablet never goes online by
itself). The relay's refusals, in plain words: "That code isn't right, or it has expired. Make a new one
in the console.", "Too many tries. Try again in a minute.", "The relay can't be reached. Check its address
and the network.", "That isn't a code. Codes look like ABCD-EFGH.".

## The notification

While the relay is connected, the web service's notification adds "· Cloud" to what it says ("Web control
on" · "http://100.101.2.3:8737 · Cloud"). With the cloud alone it reads "Remote access on" and the relay's
state ("Cloud · steven-piano-relay.you.workers.dev", "Cloud · connecting", "Cloud · Waiting for a network").

## What the relay sees, and what it doesn't

Off by default. When on, the relay (Steven's own Cloudflare account) carries what the panel shows and is
sent, as any HTTPS site's server does: the pages, the library's lists, a piece being played. The tablet
reports every 30 s and soon after a change: the app's version, the piano's firmware version, whether the
piano is connected, what plays (its title, composer, position and length, the channel), whether guests may
request, whether Web control is on, the library's size and Steven's library's version, the channels' names.
Never a device identifier: no Bluetooth address, no piano name, no serial, no Android id; and (audit delta 3)
never the panel's own address on the tablet's networks, which went with every report at first and which the
console never used. The enrolment sends the code alone. The PIN is checked on the tablet, as always; the
relay never holds it. The tablet's key to the relay is sealed by Android's keystore and never shown.

**About** says it too (audit delta 3: it said nothing of the cloud), a last eyebrow line after Wikipedia's:
"Remote access over the internet is off unless you turn it on. Then your own relay carries the panel's pages
and requests, and every 30 s the app's and the piano's versions, whether the piano is connected, what plays,
the guests' switches, whether Web control is on, the library's size and the channels' names. Never a device
identifier." (`CloudCopy.ABOUT`).

---

# v1.10 — M27: Steven's library from GitHub

Steven asked (2026-09-30) that a new tablet can load the song library from GitHub, and that tablets
which have it can update it. The library he built (`Player Piano/midi/`, 1,727 files in its INDEX.csv)
becomes a **versioned pack**, `library-v<n>.zip`, on this repository's GitHub release `library`, named by
`releases/library.json` on `main`. The app downloads it on demand only; it is never bundled in the APK.
Everything above still holds; Library › + keeps its three ways in (files, a folder, a zip).

## The pack

- **What is in it**: the MIDI files the library's INDEX.csv lists, each once (INDEX.csv lists 1,727
  files; one, piano-midi.de's `bor_ps1_format5.mid`, is `bor_ps1_format4.mid` byte for byte, so version 1
  holds **1,726 pieces**), their INDEX.csv with a new last column `sha256`, the library's README (its
  collections and their credit lines) and MAESTRO's licence. Nothing else of the folder: not
  `pop-shopping-list/`, not `ALL SONGS/` or `ALL-SONGS.zip`. Version 1 is **61 MB** (92.9 MB of MIDI).
- **The three collections**, credited as their licences ask: **MAESTRO v3.0.0** (Google Magenta;
  Curtis Hawthorne et al., "Enabling Factorized Piano Music Modeling and Generation with the MAESTRO
  Dataset", ICLR 2019; CC BY-NC-SA 4.0: **non-commercial use only**), **piano-midi.de** (Bernd Krueger,
  CC BY-SA 3.0 Germany), **the Mutopia Project** (public domain).
- **Versions only rise.** A tablet offers a pack whose version is above the one it loaded. An update
  brings **only the pieces no earlier pack offered this tablet**, and **never deletes one**: a piece the
  person deleted stays deleted, and a piece the pack no longer holds stays in the library.

## Load Steven's library

- **The empty Library**: "No pieces yet." then "Load Steven's library, or add MIDI files of your own.",
  and side by side the two outlined actions **Load Steven's library** and **＋ Add MIDI files**; under
  them, in the eyebrow's size, sentence case, secondary: "1,726 pieces · 61 MB · MAESTRO, piano-midi.de,
  Mutopia · for non-commercial use" (before the pack is known: "MAESTRO, piano-midi.de, Mutopia · for
  non-commercial use"). Load is dimmed while a load runs. Here Load brings every piece of the pack, even
  ones an earlier pack offered: a library emptied by hand can be filled again.
- **Library › +**: first, above a hairline, **Load Steven's library** with the same line, until a pack
  has been loaded on this tablet. After that the row shows only while a newer pack is on offer (or
  loading): **Update the library · 2 new pieces** ("Update the library" when the count is not known),
  with the pack's line. While a load runs the row says what it is doing and does nothing when tapped.
- **The licence sheet**, before the first load (from either place): the sheet's glass, **Steven's
  library** in the title's size, "1,726 piano pieces from three open collections, a 61 MB download from
  Steven Piano's releases on GitHub.", the eyebrow **CREDITS**, each collection's name in Body with its
  credit under it in the eyebrow's size ("Curtis Hawthorne et al., “Enabling Factorized Piano Music
  Modeling and Generation with the MAESTRO Dataset”, ICLR 2019. CC BY-NC-SA 4.0.", "Bernd Krueger,
  www.piano-midi.de. CC BY-SA 3.0 Germany.", "www.mutopiaproject.org. Public domain."), then "For
  non-commercial use: MAESTRO's performances may not be sold or used for profit. The credits stay with
  the pieces, in the pack's README.", then **Not now** and the filled **Load · 61 MB**. Updates don't
  show it again; a first load that did not finish shows it again next time.
- **While it loads**: under the Library's header, where imports show, "Loading Steven's library · 23 of
  61 MB" over the progress hairline; then the import's own line, "Imported 1,204 of 1,726", and at the
  end "Imported 1,726 pieces." with Dismiss, as any import. Composers' artwork follows, as after any
  import. The notification: **Loading Steven's library**, "23 of 61 MB", then "Imported 204 of 1,726",
  with the progress bar and **Cancel**; it goes on with the screen off or the app left.
- **When it stops**, one line in words where the progress was, with Dismiss, and Load available again:
  "Loading Steven's library needs an internet connection.", "Couldn't reach the download server.",
  "The library's list couldn't be read.", "The download didn't match the library; try again.", "The
  download stopped; try again.", "There isn't enough free space for the library.", "The library's
  pieces couldn't be read.". Cancel, or a stop part-way, keeps what arrived (the next load brings the
  rest) and leaves no file behind.
- **Kiosk mode**: loading and updating the library change it, so they wait for the kiosk PIN, as adding
  music does (the + is locked already).
- **The console** (Steven Piano Cloud, M26): **Load Steven's library** on a piano's page starts the same
  load on the tablet (an update when one is loaded; pieces a teacher deleted stay deleted). Never the first
  load (audit delta 3): that one is the tablet's, after its licence sheet, and the console's button answers
  "Steven's library loads the first time on the tablet, where its licence is shown. After that, the console
  can bring its updates." With the app
  in the background the load keeps its notification: the web service, holding the relay's connection,
  keeps the app allowed to start it (seen at the merge); were Android to refuse, it would run without it.

## When the app asks GitHub

The pack's list (about 600 bytes, `releases/library.json` from raw.githubusercontent.com) is asked for:
once a day while the app is open and online with **Check for updates** on (the app's own update check's
switch); and when the empty Library or the + sheet shows, at most once every 10 minutes, since the
person is looking at the offer. The 61 MB zip only on Load or Update. Nothing about the person or the
tablet is sent.

---

# v1.10.1 — uploads become playlists

On 2026-10-01 Steven dropped a zip of his own on the school tablet's web panel: 266 MIDI files as
`MIDI/<Artist>/<Title>.mid` (107 artist folders) and five at the root named `Title - Artist`. 1.10 read a
composer only from a `Composer - Title` file name, so they arrived as titles without artists, in no playlist,
with no photographs, lost among the rest. He asked: "place them into a playlist, make sure every one of them
will have artwork, make it appear first." Designed with the `apple-design` lenses, tablet first; nothing above
changes except where this section says so.

## A piece's artist (D1)

Without an INDEX.csv row, a piece's artist comes from, in this order:

1. **A `Composer - Title` file name**, read as always, but **the other way round when only its right side is
   known**: known = a canonical composer of the app's list, or an artist folder of the same import. A trailing
   parenthetical on that side goes to the title: "Cornfield Chase - Hans Zimmer (version 2)" is *Cornfield Chase
   (version 2)* by Hans Zimmer. Both sides known, or neither ("Stay - Interstellar"), it reads as it always has:
   the left side is the composer.
2. **The artist folder**: the folder that holds the file, when it lies below the import's root. `MIDI/Coldplay/
   Sparks.mid` is *Sparks* by Coldplay; a file in the root itself has none.
3. Otherwise no composer, as before.

INDEX.csv rows win, as they always have. What a Mac adds beside the music, anything in `__MACOSX/` and the `._`
files, is skipped before anything is counted: never a piece, never a failure.

## A zip or a folder is a playlist (D2)

- **Its name**: the root, the one top-level folder every file of the zip or folder shares ("MIDI"), else the zip
  or the folder itself without its extension ("Spring Recital.zip" is *Spring Recital*). Names are cut as every
  playlist's are; a name a built-in list holds moves the built-in aside, as before ("Popular · built in").
- **What goes in**: every piece of the upload that no INDEX.csv row places, in path order (folder by folder, then
  by title, numbers by value), **pieces already in the library included**: such a piece is linked too, and its blank
  composer filled from the upload. A playlist of that name already there is filled further, at its end.
- **A loose MIDI file sent through the web panel** goes into the standing playlist **Uploads**, made when first
  needed. It is a playlist of the person's like any other: renamed or deleted, the next loose upload makes it again.
- **Files picked one by one** on the tablet (Library › + › Add files, Open with, Share) go into no playlist, as
  before; a folder or a zip picked on the tablet follows the rules above. Steven's library keeps its three
  collections and makes no playlist of its own.

## Artists' names (D3)

The app's canonical composers stay as they are: "Claude Debussy" and "Erik Satie" from a folder join Debussy and
Satie. Anyone else, read from a folder or from the known side of a reversed name, keeps **the whole name** as
written: grouped by the whole name folded (*Ed Sheeran*, *Louis Armstrong* apart from *Craig Armstrong*,
*Coldplay*, *C418*), and rows show the whole name ("Ed Sheeran · 3:54", as "Made in Studio · 3:05" does). No surname
logic for artists: *Twenty One Pilots*, *The Weeknd* and *Lady Gaga & Bradley Cooper* stay whole. So that one artist
never gets two groups, a composer read from a `Composer - Title` name, or typed in Rename, joins an artist the
library (or the same upload) already has by that whole name. Nothing already in the library is re-keyed otherwise:
the classical library's composers and their portraits stay exactly where they were.

## What was already imported (D4)

The first start of 1.10.1 repairs, once, what earlier uploads left loose: the pieces with no collection, in no
playlist, that came from a folder in a zip or a folder, none of Studio's. Grouped by the first folder of their path
(the zip's root), a group of two or more is read as an upload now would be: blank artists filled from their folders,
a reversed name that 1.10 read the wrong way round corrected (its title too), and all of them put into the playlist
named after the root, in path order. On the school tablet that is the playlist **MIDI**, 265 pieces. Then the
built-in lists refresh and the new artists' photographs are fetched. Nothing in a playlist or a collection is
touched, and it never runs again.

## Artwork for every piece (D5)

A piece shows its artist's photograph, else its roll card (its first 20 seconds as perforations). **No frame stands
empty**: where a roll card can't be drawn (a file gone or unreadable), the piece's title's monogram stands in, in the
now-playing panel, the mini player, the resting screen and the piece sheet, and in the web panel's rows, Now playing
and channel cards.

An artist is looked up on Wikipedia by their name; past a disambiguation page or a page about something else, as
"*name* (band)", "(singer)", "(musician)", then "(composer)"; the first page about a band or a performer wins (its
description, else its first sentence, names one: band, duo, group, singer, songwriter, musician, rapper, DJ, record
producer, composer, pianist…). *Queen* is the band, *Passenger* the singer. A joint name ("A & B", "A and B", "A feat.
B", "A, B") whose own page finds nothing is looked up as A: *Lady Gaga & Bradley Cooper* shows Lady Gaga. Five
lookups an artist at most; a company or anything else not about music (*Nintendo*) keeps its roll cards. Only
Wikipedia's free photographs: no album covers. "Fetch artwork automatically" still decides.

## The Playlists' order (D6)

- **Newest first** (the default): the person's playlists by when they were made, newest first, so an upload's
  playlist appears first; the built-in lists after them in their fixed order (Popular, Recognisable, Epic on piano).
  **Name**: the order before 1.10.1 (the built-in lists first, then by name).
- **The pop-up button**, at the end of the Playlists' own header row, below the channels: the eyebrow **PLAYLISTS** at
  its start, the order chosen ("Newest first") in the label style and the content colour, a chevron down in the
  secondary grey, 48 dp tall; TalkBack reads "Sort playlists, Newest first". A tap opens the two orders on the menus'
  glass, the current one checked, the menu's end at the button's end, so on the tablet it opens within the list's
  pane and never across the divider. At font scale 2.0 the button stays on one line; the eyebrow may wrap beside it.
  `pop-up-buttons.md › Best practices`: *"Use a pop-up button to present a flat list of mutually exclusive options or
  states"*, *"Provide a useful default selection"*; `settings.md › Task-specific options`: ordering a collection belongs
  on the screen it orders, not in settings.
- The choice is remembered, and the web panel lists the playlists in the same order.

## Where an upload went (D7)

- **The panel's Add tab**, when an import has finished: its tally ("Imported 265 pieces · 1 already there"), and under
  it **In the playlist MIDI** with the outlined button **Open the playlist** (the Library section, that playlist open).
  A loose file reads "In the playlist Uploads". Sentence case, no exclamation marks.
- **The tablet's import bar**: "Imported 265 pieces · in the playlist MIDI."; pieces that were all there already, "Those
  pieces are already in the library · in the playlist MIDI.".

# v1.11 — instruments, live playing, recording

On 2026-10-01 Steven chose all four: connect any Bluetooth MIDI piano or keyboard, and USB keyboards by cable;
play the school piano live from it; record what is played and keep it as a piece; play the app's library on any
MIDI piano, not only Steven Piano. Designed by Fable with the `apple-design` lenses, tablet first, in the app's ink
and paper; glass only through the existing wrappers (the picker and the recording sheet are glass sheets). The
school piano is a permanent install: **fire safety decides every doubt**, so keys and pedal are let go whenever
anything about an input is uncertain. Nothing above changes except where this section says so.

## The rules

- **Steven Piano keeps its own Bluetooth link.** Every other device goes through Android's MIDI service. Steven
  Piano is never opened, listed, paired with or chosen as a MIDI device: not by its name, not at the remembered
  piano's address, not as a nameless device that turns out to be it. A keyboard or a MIDI piano chosen is never
  connected to by the piano's link either.
- **Live only on the Keys tab.** The keyboard plays the instrument only while the Keys tab is on screen, the app in
  front, Live switched on, the keyboard connected and something to play on (the instrument, or the tablet's own
  piano sound). The screen stays on while Live is on or a take runs. Live is never offered to the web panel or the
  cloud: they only show it.
- **Let go, every time.** Every key and pedal the keyboard holds is let go when the Keys tab is left, the app goes
  to the background or the screen goes off, Live is switched off, the keyboard is unplugged, lost or forgotten,
  another keyboard or instrument is chosen, the keyboard falls silent (its Active Sensing stops for a second, or
  keys or a pedal stay down with no byte for a minute), it sends All Notes Off or a System Reset, the instrument's
  link drops, a firmware update locks the player, or the app crashes.
- **Fresh presses only.** A key or pedal already down when Live opens waits for a new press; a Note Off goes to the
  instrument only for a Note On that went.
- **The flood breaker.** More than 200 Note Ons in a second, 32 keys held at once, or more than 64 malformed bytes in
  a second from a keyboard: everything is let go, Live switches itself off, and a line says why ("Live turned off: the
  keyboard sent more than 200 notes in a second."). Only the person turns it on again.
- **Never looped.** A device that is both the instrument and the keyboard never plays through: Live stays off with a
  line saying why ("Live stays off: this keyboard is the instrument too, so it already plays its own keys."); the
  keys still light and a take still records.
- **Steven Piano's keys.** A0–B0 (21–23) and C8 (108) from a keyboard follow *Fold notes outside C1–B7*: an octave in,
  or dropped. On a MIDI piano they play as they are.

| | Steven Piano | Any other MIDI piano |
|---|---|---|
| Keys | 24–107 (folded or dropped as Fold says) | 21–108 |
| A key struck again sooner than the re-strike time (100 ms before v1.16) | a piece's repeats are spaced first to keep the rhythm (v1.16 — M44); a live strike that soon is dropped, and the piano defers one a little early | struck again: Note Off, then Note On |
| A key another source holds | shared | struck again |
| Pedals | sustain (CC64), paced | sustain, soft and sostenuto (CC64, 66, 67), at once |
| Stopping | sustain up, then All Notes Off | every held key's Note Off, the three pedals up, All Notes Off |
| Leaving the instrument | as before | the same, then All Sound Off |

## The Piano tab: INSTRUMENTS

The full reorganisation of the Piano tab is a later milestone; this adds only:

- **INSTRUMENTS**, under the connection card, two navigation rows: **Instrument** ("Steven Piano", or the chosen
  instrument's name) and **Keyboard** ("None", "<name>", or "<name> · not connected").
- **The connection card** names the selected instrument and says its state in its own words ("Looking for the
  piano…" is Steven Piano's; a MIDI piano is "Connecting…"). A MIDI piano on a cable asks for no Bluetooth
  permission.
- **The Instrument page.** THIS INSTRUMENT: its name, its kind ("The school piano" or "Standard MIDI piano") with its
  state, and Connect or Disconnect. CHOOSE: "Steven Piano" and "Another MIDI piano…", a check on the one playing; the
  second opens the picker. While a MIDI piano plays, the note "Feel, Lighting, Pedal and Firmware belong to Steven
  Piano and are hidden while <name> plays.", and the hub hides the PIANO group and the piano's status line (a piano
  page left open falls back to Playback). ACTIONS: **All keys off** (it also stays on Firmware and status).
- **The Keyboard page.** The state line; "Choose a keyboard…" (the picker); once chosen: its name, "Bluetooth" or
  "USB", its state, and **Forget**. Notes: "Play it from the Keys tab." and "A cable is steadier than Bluetooth: plug
  the keyboard into the tablet when you can." When pairing is needed: "This keyboard asks to pair. Accept the
  request, or pair it in Bluetooth settings, then choose it again." with **Open Bluetooth settings**.
- **The picker**, one glass sheet for both: eyebrow MIDI, title "Choose an instrument" or "Choose a keyboard"; USB
  devices first, then Bluetooth devices as they are found ("Looking for MIDI devices…", 12 s, on the scan budget
  the piano's link shares; **Look again** after; a line saying how long to wait when Android's budget is spent);
  each row its name and "USB" or "Bluetooth"; never Steven Piano, never the remembered piano's address; **Cancel**.
  Other apps' MIDI ports are offered in debug builds only.

A MIDI piano that is unplugged reads "<name> isn't connected. Plug it into the tablet or switch it on nearby, then
tap Retry." and connects again by itself when it is plugged in; one another app holds, "Another app is using
<name>. Close it, then tap Retry."; one that asks to pair, "<name> asks to pair. Accept the request, or pair it in
Bluetooth settings, then tap Retry."

## The Keys tab: the live monitor

- **The eyebrow** under the header, when a keyboard is set: "KEYBOARD · <NAME>", and its state in words when it is
  not connected.
- **The pills, in order**: **Live** (latching like Sustain: "Live" / "Live on"; shown only while a keyboard is
  connected; remembered) · Octave down · Sustain · Octave up · **Record** (a filled circle and "Record"; while
  recording a square and the elapsed time in tabular digits, "0:42"). The Record control is in the content colour,
  never red: red means live.
- **The keys light** for presses on the keyboard, Live on or off. The drawing stays 84 keys: a key outside C1–B7
  lights an octave in.
- **The connection line**: "Sent to piano" (the live dot) while Live is on and an instrument is connected; "Keyboard
  connected. Live is off." with a hollow dot; the lines of before otherwise. While Live plays Steven Piano, one quiet
  note under the pills: "The piano lets a held key go after 2 seconds to keep its coils cool." The same place says
  why Live is off when it switched itself off.
- **Accessibility**: every pill has a label and a state ("Live, on"); the elapsed time is said on Stop, not every
  second; 48 dp targets; at font scale 2.0 the pills stay on one row that scrolls.

## Recording

- **What was played**, captured before anything is folded or paced: every key with its velocity and every pedal value
  (half-pedalling kept), from the keyboard (Live on or off) and the on-screen keys. A piece playing is never
  captured.
- **After Stop**, a glass sheet: the eyebrow RECORDING, "Keep this recording?", the take's line ("0:42 · 318 notes"),
  a **Title** field prefilled with "Recording · <medium date> <short time>", and **Discard** · **Listen** · **Keep**.
  The take is in the library already, waiting: Keep keeps it under the title typed, Discard deletes it, Listen plays
  it (Now playing's banner then asks, "Recorded here. Discard deletes it."), and dismissing the sheet leaves it
  waiting. A take with no note: "Nothing was played." for a moment, and nothing saved.
- **The piece**: composer "Recorded live" (its row reads "Recorded live · 0:42"), in the built-in playlist
  **Recordings**, newest first, which the Library shows among the playlists once it holds a piece. Recordings is
  never in the guests' catalogue and never refilled by the built-in lists.
- **Limits**: an hour or 200,000 events; five minutes with nothing played ends the take. A take is written to a file
  before it is imported, so a crash loses none: the next start saves it.

## Kiosk mode

Free without the PIN: **Live** and **Record**. A take then waits undecided ("Saved to Recordings. Someone with the PIN
keeps or discards it." with **Listen** and **Done**); at most 30 wait, the oldest discarded. The PIN, as Disconnect
asks for it: choosing or forgetting an instrument or a keyboard (their pages are settings), and keeping or
discarding a recording.

## The web panel and the cloud

The panel shows two read-only lines under "Sent to piano" and on its Piano page: "Instrument: <name>" and "Keyboard:
<name>" ("None", or "· not connected"), followed by "· Live" and "· Recording" when they are on. While a MIDI piano
plays, the panel's Piano page shows those lines and the same note as the tablet; Steven Piano's pages and actions
are hidden. The relay's status carries the same without any name: the instrument's kind and state, the keyboard's
transport and state, Live and Recording.

# v1.12 — Studio as a tab

*(The conversation, the shelf and the aura's numbers below gave way in v1.13.1 to Studio's stage, at the end.)*

Designed by Fable (apple-design lenses), built in M30. Studio leaves the Piano hub and becomes the fourth of five
tabs: Library · Now playing · Keys · **Studio** · Piano (a compact bar's labels may scale to 0.85; none is
shortened). The rules:

- **A conversation.** Turns at reading width, newest at the bottom; the prompt bar (glass, 28 dp, the aura's ring)
  docked above the bottom inset and the keyboard: attach · "Describe a piece…" (200 characters, a counter past
  160) · Options · Send (off while empty or while three ideas wait). Over it, 250 ms after the last key, the line
  of what is understood and "Not used: …". Models live in a glass sheet from the header; on wide frames the shelf
  MADE IN STUDIO stands beside the conversation (a sheet from a second glyph on phones).
- **Keywords, honestly.** There is no text model; an idea is read by keywords (moods, tempo, keys, lengths, forms,
  the library's titles and names, the catalogue). What was not used is said. An idea without a seed of its own
  refines the last turn ("slower", "longer", "in D minor", "another", "different"), and the card says what changed.
- **Titles never contain typed text**: "<Mood>, after <seed title>", "<Mood>, after <composer or form>", "<Mood>
  piece". Typed text lives only in Studio's history on the tablet: never in a title, a file name, a log line or
  the diagnostics.
- **A card says where it is**: the steps as words (the current one in the content colour), a determinate hairline,
  "42% · 0:50 of 2:00 · about 40 s left" in tabular digits, the notes appearing as they are written, Cancel; then
  Listen · Keep · Discard · Another like it · Adjust… and the credits. A budget stop is said plainly ("3:41 written
  of 5:00: the music was dense, so it ends here"). Progress is announced at steps, not every tick.
- **Every Studio piece has a drawn cover** (from its key, mood and notes; it reads in grey too) and joins the
  built-in **Made in Studio**, which no channel draws from and no guest sees. A piece's own cover comes first
  wherever the piece is shown.
- **The aura is colour's one new meaning: the tablet is writing music.** Blue, violet, rose, amber, in
  `ui/theme/Aura.kt` alone, drawn by one component: the prompt bar's ring (rest 30 %, focused 60 %, working 100 %),
  the running card's top line, a 6 dp dot on the Studio tab while a job runs. Red stays live, yellow stays sounding.
  It turns only while the screen is resumed and in sight, is still under reduced motion, has no glow with high
  contrast or reduced transparency, never stands behind text without the bar's surface, and is never the only sign
  of status.
- **Kiosk mode: typing ideas is free** (Steven). Send, Options, Another like it, Listen and Cancel need no PIN;
  Attach, Models, Keep, Discard and removing a turn do. At most three ideas wait, "Not used" is a count, the
  history hides typed words until the PIN opens the settings, and at most 30 Studio pieces wait (the oldest goes).

# v1.12 — the split and the View menu

Designed by Fable with the `apple-design` lenses (`split-views.md`, `settings.md › Task-specific options`,
`pop-up-buttons.md`), tablet first. The v1.1 **Wide layout** preference is gone; nothing else changes.

- **The divider.** On wide frames a divider sits in the 8 dp gap between the score and the notes: a 1 dp hairline
  (`LocalHairline`) with a 36 × 4 dp grabber (`onSurfaceVariant`), a 48 dp touch target centred on the gap. It is
  content: no colour, no glass. Drag it: it rests at a third, a half and two thirds (within 12 dp); a pane keeps its
  minimum (stacked: the score 200 dp, the notes 165 dp; side by side: 240 dp each), and dragged 56 dp past it the pane
  hides, the divider waiting at that edge to bring it back. A light tick on resting on a stop and on hiding. Double-tap
  resets to the arrangement's default: a third for the score stacked, a half side by side.
- **Remembered per arrangement**: stacked and side by side each keep their own share (`notesSplitStacked`,
  `notesSplitSide`); 0 shows the notes alone, 1 the score alone. An older build's *Notes only* reads as 0 and *Score
  only* as 1, for both.
- **Bars per system follow the score page's width**: under 480 dp two, under 560 dp three, else four. A re-layout
  waits for the 150 ms settle; while a finger moves, the old layout stays drawn, top-start, clipped, never scaled.
  The transport floats or stands as the committed share says, so it does not jump during a drag.
- **The View menu**: a glyph in Now playing's header opening the menus' glass, its end at the glyph's end so it
  never crosses the divider. On wide frames SHOW (*Score and notes* · *Notes only* · *Score only*: the same shares
  without dragging); NOTES (*Paper roll* · *Falling notes*, and *Score* on a phone); *Fingering* · *Chord names* ·
  *Hand colours* (with its note). Piano › Display keeps Appearance, Artwork and Standby. "Falling notes" is the one
  name for that view.
- **Kiosk**: the divider and the View menu are free, no PIN. **TalkBack**: "Sheet music and notes divider, sheet
  music 33 percent", adjusted as a slider, with the actions Reset, Show sheet music only, Show notes only. **Keys**:
  the arrows along the axis move it 2 %, Page keys jump between the stops, Home and End hide a pane. Short screens
  that scroll keep their fixed heights and no divider.

# v1.13 — the Piano tab reorganised, with search (M31b)

Steven chose the hub: INSTRUMENTS · THE PIANO · PLAYING · SHARING · THIS TABLET, under "Search settings" and the card.

- **Every hub row opens a page**; switches and actions sit on the page they belong to, and the row's value says what it holds.
- **One disclosure per page at most** (Fine tuning, Strip set-up): closed at first, its value names what it holds.
- **One name per thing**, app and web panel alike: Web panel; Piano, Tablet, Channel and Schedule volume; Resting
  screen; Falling notes. Copy that names a place names it as the hub does.
- **Notes**: one plain line, sentence case, no full stop, under 60 characters, all in `ui/SettingNotes.kt`.
- **Search** is content, not glass: results give the label and its path in the eyebrow; a result opens its page at
  the row, opens its fold and fills the row with the elevated surface for about a second; what moved off the tab
  opens its new screen. In kiosk searching is free and a locked page still asks for the PIN.
- **No action twice**: beside the Instrument page the card shows the name and state only.

# v1.13 — motion (planned as 1.14)

Steven chose "smooth and responsive"; designed by Fable (`motion.md`: purposeful, optional, brief, cancellable). It
replaces › Motion's 240 ms tabs, "system ripple only" and "one haptic"; everything else there stands.

- **Tokens** (`ui/theme/Motion.kt`): 120 quick · 200 standard · 320 emphasised · 480 slow, the most anything takes
  (popovers 160); the press spring (damping 0.8, firm) and the settle spring (0.9, soft); entering decelerates,
  leaving accelerates. Every transition goes through one helper: under reduced motion it is a cut.
- **Nothing loops by itself** but the aura; status moves only while it lasts (the live dot, an indeterminate hairline).
  One status, one sign: looking for the piano keeps its hairline alone. No bounce, no parallax, nothing in the way of a touch, everything interruptible.
- **Touch**: glass controls, tiles, chips and filled buttons scale to 0.97 while pressed; rows keep the ripple. A light
  tick on Send and on Record, as on play and pause.
- **Places**: tabs fade through (out 120, in 200 from 0.92); Piano pages beside the hub cross-fade rising 8 dp, on
  phones they push; popovers grow from their anchor's corner (0.92, 160 ms; gone in 120). Sheets and Material's menus
  keep the platform's motion.
- **Content**: a listing's first ten rows fade in rising 8 dp, 12 ms apart, once a visit (never on scroll, never after
  a sheet); rows added, removed or moved settle into place; the now-playing panel's art of a piece started from a row
  or a tile grows in from 0.92 (320 ms; a phone's Now playing has no art); a new Studio turn rises 12 dp (320 ms) and a
  finished card's actions fade in. *(v1.16 — M43 retires "a phone's Now playing has no art": Now playing now has the
  small art beside its title, which does not grow in.)*
- **Figures**: the tempo, the Record time and Studio's figures roll digit by digit (120 ms, tabular); determinate
  hairlines ease to each value (200 ms); play/pause cross-fades with a small scale (200 ms), shuffle and repeat cross-fade.
- **Left alone**: the score's page turn and the roll, the live red and the sounding yellow, the aura, the resting screen.

# v1.13 — the panel's notes and score

Designed by Fable (`plans/plan-web-split.md` §§ 2, 4); built in M32. The web panel's Now playing shows what the tablet's
shows, in step with it.

- **The views**: the score and the moving notes (paper roll or falling notes, with the keyboard strip), drawn from what
  the tablet laid out: the same engraving, the same bars a system for a page as wide, the cursor and the sounding yellow,
  the left hand outlined, fingering, chord names and Hand colours as the tablet's settings say. Content, never glass: each
  view is a 12 px card on the elevated surface, in the panel's own ink and paper tokens (`--sounding`, `--hand-left`,
  `--hand-right` are the app's colours).
- **From 900 px** both views show above the title, stacked (the score over the notes) or side by side once the column is
  840 px wide, split by the tablet's divider (a third and a half by default, the same stops, minimums and hiding);
  the share is remembered in this browser only, per arrangement: a remote viewer never rearranges the tablet.
- **Below 900 px** one view at a time: **Art · Notes · Score**, Art first, remembered in the browser; a phone that never
  opens a view loads nothing for it. Every page still fits 390 px without sideways scrolling.
- **The View control** (beside the views) shows and changes the tablet's own settings: Paper roll · Falling notes,
  Fingering, Chord names, Hand colours. The score's pages turn by themselves; ‹ and › (or the arrow keys) look ahead or
  back, **Follow** comes back, and a tap on a bar plays from there. Nothing on the panel moves the tablet's own split.
- **Motion**: frames run only while Now playing shows, the page is visible and the piece plays (and 400 ms after a
  change); with reduced motion the roll still scrolls and everything else cuts.

# v1.13.1 — Studio's stage

Steven, on 1.12's Studio: the create box in the middle of the screen, the aura moving and glowing, the generated
pieces somewhere else. Designed by Fable (apple-design `generative-ai.md`), built in M36.

- **The stage.** One column at reading width, centred on the whole width (no second pane). Idle: "What should the
  piano play?" in the display style, the idea box (glass on its solid surface, at least 64 dp: attach · "Describe a
  piece…" · Options · Send), the understood line on its own small glass, the suggestions, centred in the space above
  the bar or the keyboard; with nothing made yet, one quiet line at the foot.
- **One card.** While a turn waits or runs the column eases upward (320 ms; a cut under reduced motion) so the box
  sits in the upper third, the card under it as in 1.12, then its result (Listen · Keep · Discard · Another like it ·
  Adjust…, the credits). It stays until the next idea is sent or the tab has been left for a minute (one that came
  while away waits); a cancelled turn leaves the stage idle.
- **History.** A second glyph beside Models: a glass sheet of every turn, newest first, its cover, title, the idea's
  words (hidden in kiosk mode until the PIN) and its state as an eyebrow (KEPT · UNDECIDED · DISCARDED · FAILED ·
  CANCELLED); a tap opens Listen · Another like it · Adjust… · Keep · Discard · Remove under the same PIN rules. The
  Library's Made in Studio is unchanged.
- **The aura's three states.** Ring 2.5 dp. Rest 65 %, a turn in 8 s, glow 28 dp; focused 85 %, a turn in 6 s; working
  100 %, a turn in 2.5 s, the glow breathing 28 ↔ 40 dp every 1.8 s. It moves only while Studio is resumed and in
  sight; still with its glow under reduced motion; the ring alone with high contrast or reduced transparency; stacked
  fading strokes below API 31.
- **Never behind words.** The glow lies behind the bar's solid surface; the understood line has its own, the card's
  covers it, and the headline and suggestions stand clear of it (40 and 32 dp); the words and the hairline still carry
  the status.

# v1.14 — M37: Classical and Modern, and the recordings

Steven asked (2026-10-02) for the two defects in the keyboard flow fixed and the Library split into Classical and
Modern. Designed by Fable (apple-design lenses; the plan's decisions by multiple choice), built in three runs.

## The recordings

- **Quiet saves.** What the tablet makes itself (a recording, a take recovered after a crash, a Studio composition or
  transcription) goes into the library without the import bar, the panel's import line or a notification. Imports the
  person starts (files, folders, zips, the pack, panel uploads) are unchanged.
- **"Kept in Recordings."** After Keep, one quiet line under the Keys pills for 4 s, where "Nothing was played." shows;
  TalkBack hears it once. Discard, Listen, Done and the kiosk copy are unchanged.
- **A cover for every recording**, drawn by Studio's generator from its own notes (key and mood read from them; the
  same take always gives the same cover): new takes, recovered ones, and at the next start every recording already there.
- **Playlists show their music.** A playlist's picture: the person's photo; when its first piece has a cover of its
  own, the covers among its first four pieces (one fills the frame); the first composer's portrait; the monogram.
- **Composers too.** A composer mosaic's cell shows a piece's own cover before its roll card, so Made in Studio and
  Recorded live show covers in the grid; "Recorded live" is never looked up on Wikipedia.

## The data

- **The column.** `pieces.genre`: 0 none (Made in Studio, Recorded live), 1 Classical, 2 Modern.
- **The rule** (`data/Genres.kt`, one for imports and the upgrade): made here, none; an artist (never the blank name)
  the library already sorts, the genre most of their pieces have; a classical composer the app knows, Classical (the
  47 canonical names, the channels' Field, Couperin, Telemann, Smetana, Falla and C. P. E. Bach, MacDowell, the pack's
  other 19; a canonical surname followed only by 1–3 initials too, so "Bach CPE" is and "Adam Levine" is not); a piece
  of the pack's three collections, Classical; anything else, Modern. Within one import a name a strong rule made
  Classical teaches the import's later pieces; a duplicate that fills a blank name is sorted again; Rename never
  changes a genre.
- **The upgrade** (schema 4 → 5) adds the column, then sorts every piece with the same lists in two passes: the rule
  without artists, then a Modern piece whose artist has a Classical piece becomes Classical. Every row stays.
- **Moves.** A piece, or every piece by one name, goes to Classical or Modern; made-here pieces and the blank name stay
  out. **Playlists** show under the genre most of their pieces have, under both on a tie or when empty; Recordings and
  Made in Studio always.
- **Channels.** **Classical** and **Modern** come first, every piece of their genre; the other channels are listed under
  Classical, Everything under both. **Studio** reads "classical", "classic", "modern", "pop" and "contemporary" as those
  channels: "… in the manner of Modern pieces".

## The Library

- **The switch.** A segmented control, new to the app: **All · Classical · Modern**, three equal segments in a capsule
  track (the content colour at 8 %, a hairline), the chosen one a lighter thumb (paper's elevated surface; on ink the
  content colour at 18 %) with a hairline, its label in the content colour and medium weight, the others secondary.
  Solid, never glass; 36 dp inside a 48 dp target, segments at least 88 dp. All at first, then the last one chosen.
- **Where it sits.** In the pinned header's title row, before the padlock and `+`, when the header is 560 dp or wider and
  the text at most 1.3×; otherwise a full-width row of its own under the title, still pinned. Not there until the
  library has pieces, nor while it can't be read.
- **What follows it.** Pieces, Favorites, Recent and search: that genre's pieces (playing one queues the list shown).
  Composers or Artists: the names with a piece of it, counted within it; a name's page and Play all, that genre's.
  Playlists: those that show under it (Recordings and Made in Studio always); a playlist opens whole. The channels row
  and See all: the genre's channels (Everything only under All). Changing it closes an open page; chip and search stay.
- **The words.** The chip "All" is **Pieces**; under Modern "Composers" is **Artists**, with "Unknown artist" and "Back
  to artists", and Rename's second field is "Artist" for a Modern piece. The search field names its scope ("Search
  Classical titles and composers", "Search Modern titles and artists"; in a playlist, which is whole, the plain one).
  Empty: "No Modern pieces yet." / "Songs you add that are not classical appear here."; "No Classical pieces yet." /
  "Steven's library and classical composers appear here."; "No Modern favorites yet."; "No Classical playlists yet.";
  "No artists yet." / "No composers yet." with "Pieces by them appear here."
- **Move.** A piece's menu, after Rename: "Move to Modern" or "Move to Classical", never for a piece made here. A name's
  tile, after Play all · Shuffle and a hairline: the same for every piece by them, by the genre most of their pieces
  have (not for the blank name, a made-here one or a tie). No confirmation: moving back undoes it; the row leaves a list
  of the other genre.
- **Kiosk.** The switch is free, like changing the view; Move asks for the PIN, as Rename does.
- **Accessibility.** TalkBack reads "Genre", then each segment as a radio button ("Classical, selected", 2 of 3); with
  high contrast text the thumb's edge is the content colour at 1.5 dp; a move is heard: "Moved to Modern."
- **Motion.** The thumb slides on the settle spring (about 200 ms); the list fades out from the touch and the genre's
  fades in once read (160 ms in all); both cuts under reduced motion. No haptic, and no rows ease in again.

## The web panel and guests

- **The panel's switch.** The Library page has the tablet's switch, **All · Classical · Modern**, above its search: a
  segmented control drawn as the tablet's (a capsule track, the content colour at 8 % with a hairline; the chosen
  segment a lighter thumb with a hairline, its label primary and medium, the others secondary; 36 px tall, equal
  segments; the thumb's change fades in 160 ms, a cut under reduced motion; a 1.5 px primary edge with more contrast).
  The browser remembers it, as it remembers Appearance; All the first time. Pieces, Favorites, Recent, search,
  Playlists (the same majority rule), Composers and a composer's page follow it; a playlist opens whole; Channels is a
  page of its own and lists every channel. Changing it closes an open playlist or composer and keeps the chip and the
  search. No Move in the panel.
- **Words.** The chip "All" is **Pieces**; under Modern "Composers" reads **Artists**, and a row with no name "Unknown
  artist"; the search names its scope: "Search titles and composers", "Search Classical titles and composers",
  "Search Modern titles and artists".
- **Guests.** The request page's lists name their genre: Popular, Recognisable and Epic on piano are Classical, and a
  new list, **Modern**, holds every Modern piece by title, at most 2,000, titles and artists only (Recordings and
  Studio's pieces have no genre and stay off every list). The same switch shows above the lists when there are both; a
  search box ("Search pieces") shows when there are more than 20 pieces and filters the rows already on the phone; a
  list shows 200 rows, then **Show more**. Request, the five-minute rule and approval are unchanged. The Modern list
  is worked out when the library changes, without pictures, and kept: anyone with the QR code can ask for it.

# v1.15 — M40: album covers

Steven chose (2026-10-02, multiple choice) real album covers for the pieces, and a cover chosen by hand. Designed by
Fable, built by Opus in one lean run. It reverses v1.10.1's "no album covers" (D5) on purpose, with a credit and a switch.

- **The source.** Apple's iTunes Search API, one search a piece, no key: every Classical and Modern piece, never one made
  here (Studio's and the recordings' keep their drawn covers). Searches 3.5 s apart, images 1 s apart; Apple's 403 or 429
  stops the lookups for an hour.
- **The match.** The first result with artwork whose artist and track clearly fit: every word of the artist (an artist's
  whole name, a composer's surname, which the album or the track may name instead), and the title's core (folded, without
  trailing brackets or a " - …" tail) equal to the track's, one holding the other, or 70 % of its longer words in it.
  Otherwise the composer's portrait or the roll card stays, as before.
- **Where it shows.** The cover is the piece's own: its row (every row now shows the piece's own art), the piece sheet,
  Now playing, the mini player, the resting screen, playlist tiles and composer mosaics, and the web panel.
- **The switch.** Piano › Library and artwork › **Album covers** (on), under Fetch artwork automatically and off with it:
  "Looks each piece up by its title and artist in Apple's catalogue. Composers' portraits and notes still come from
  Wikipedia." Off, nothing is looked up; covers already found stay.
- **By hand.** A piece's menu, after Move to …: **Change cover** (the photo picker, the kiosk PIN first). A cover chosen
  by hand, or drawn, is never replaced by a lookup.
- **The credit.** About: "Album covers from Apple's iTunes Search API." The piece sheet, under the notes' credit: "Cover:
  album · artist", linking to the track on Apple Music, or "Cover chosen on this tablet".

# v1.15 — M41: the album-colour backdrop

Steven chose (2026-10-02, after a mock) Apple Music's Now Playing backdrop: the playing piece's art colours drifting
behind the player, full strength and moving while a piece plays, with a switch to turn the colour off and on. Designed
by Fable, built by Opus in one lean run. It overrides v1.7.1's "nothing else on the canvas", "never a faded backdrop"
and "never animated" for Art and notes, retired in place above.

- **The look.** Four soft discs of the art's colours, each fading from its colour to nothing over a radius of 60 % of
  the pane's shorter side, drifting on slow paths (a turn in 20, 22.5, 24 and 26 s, each its own), centred in the lower
  three quarters. No blur: the gradient is the soft shape. The colours are the art's own (the piece's cover, else its
  composer's portrait): four hues kept apart, their saturation lifted a third; the lightness is the appearance's (45 %
  on paper, 32 % on ink), so the words read whatever the art. Grey art gives none: an engraving, a black-and-white
  photograph, a roll card, a monogram. Most composer portraits are grey, so the backdrop shows mostly for pieces with a
  colour cover.
- **Where.** Behind **Now playing** (the phone's, and the tablet's Now playing tab), under its header and the tab bar
  too; behind the **tablet's now-playing panel**; and on the **resting screen** behind Art and notes (Paper roll keeps
  its own faded portrait). The **web panel**'s Now playing has it as well, from a sample of the art it shows.
  *(v1.18 — M47 retires the four discs on the web panel: its backdrop is the cover itself, blurred and turning, under the
  dimming the cover needs; see v1.18 — M47.)*
- **The veil and the words.** A veil of the surface over the discs: 55 % on paper, 62 % on ink; on the resting
  screen's black, black at 35 % over the words' side alone, so the art's surroundings keep the full colours. Over the
  backdrop every word is in the **primary colour**, the eyebrows, the times and "Sent to piano" included: through the
  veil only the primary keeps 4.5:1 over every hue (worst 5.7:1 on paper, 8.6:1 on ink, 5.1:1 on black). The score and
  the roll keep their opaque cards, and the art its frame; the colours show around them, under the title, the gaps,
  the transport and the tempo row.
- **Motion.** It moves only while a piece **plays**, the screen is in sight and the app not resting beneath the resting
  screen; still when paused and under reduced motion. A new piece's colours cross-fade (480 ms; 1,200 ms at rest, the
  resting screen's own pace; a cut under reduced motion). On the resting screen it holds still while the screen fades,
  and it takes no burn-in step (soft shapes have no edge); the words and the art keep theirs. Nothing loops by itself but
  the aura and, while a piece plays, the backdrop.
- **When it is gone.** High contrast text or reduced transparency, Artwork in black and white, or the switch off: no
  backdrop at all, and every screen looks as before.
- **The bars.** No blur on every frame: over the moving colours the header and the tab bar are the surface at the bar's
  72 % fill, so the colours show through faintly, and the header's words take the content colour. A short screen
  scrolled beneath its header still blurs, as before.
- **The switch.** Piano › Display › **Album colours behind the player** (on), after Artwork in black and white: "The
  album's colours drift behind the player while playing". The same switch is **Album colours** at the foot of Now
  playing's View menu, one tap from the player. Search finds it by backdrop, colours, album and Apple Music.

# v1.16 — M44: how a piece is played

Steven asked (2026-10-03): "Some songs where a key is constantly repeated, you can't hear the sound." He chose, by
multiple choice, repeats that keep the rhythm, expression on by default (Light, dynamics and timing), and a dynamic
range with a floor for the quietest note. Designed by Fable, built by Opus in one lean run. **A change to any of the four
settings shapes the next piece, never the one playing.**

- **One pass as a piece loads.** What a piece sounds is shaped once, after its hands are known: expression, then the
  dynamic range and the floor, then the repeats. The roll, the score, the hands, the fingering and the chords stay the
  file's; the pedal keeps its times and the piece its length. Only a piece's notes: the Keys screen and keyboards play as
  before. A drum-channel note is left as it is.
- **Repeats that keep the rhythm** (always on for Steven Piano). The re-strike time T is **Re-strike time**: Auto, the
  piano's own repeat period (`!repeatms`, `minstrike + gap + 20 ms`: 110 ms at its defaults, 40 ms with Snappy; 100 ms
  with no piano or none reported), or 60–250 ms. Each key lifts before its next strike by the release gap, the longer of
  60 ms and T less 40 ms, so every strike lands (never shorter than 30 ms for it). Repeats faster than T keep the first
  and every n-th, each a touch louder (6 for every note it stands for, 18 at most): the pulse stays and nothing piles up
  at the piano. The router's guard follows the same T, 10 ms of timing allowed, the last line of defence for the Keys
  screen, a keyboard and a faster tempo. A MIDI piano's repeats stay as the file has them.
- **Dynamic range and the quietest note.** Each velocity spreads from the piece's mean, × 0.7 Narrow, × 1 Natural (at
  first), × 1.3 Wide; then a softer one is raised to the **Quietest note** (20 at first, 1–60), so it still strikes.
- **Expression** (Off · Light at first · Full, which doubles every deviation and every millisecond), in six rules:
  1. In each cluster (onsets within 30 ms) the melody, its highest right-hand note, × 1.08; the bass × 0.97; inner notes × 0.92.
  2. The melody's phrases (split at a rest longer than a beat and 600 ms) of three notes or more swell, × (1 + 0.12 sin πt), and follow their contour, 0.03 an octave, ± 0.06.
  3. On the grid alone, a downbeat × 1.06, another beat × 1.02, off the beat × 0.98; of identical chords in a row, the second × 0.96.
  4. A file that already carries dynamics is shaped lightly: the full effect under a velocity deviation of 6, 30 % of it from 20.
  5. Timing never accumulates: the melody leads its chord by 8 ms; a chord of three or more within 10 ms rolls up over 12 ms, its top note on the beat; a phrase after a rest starts 15 ms late; its last note holds up to 20 % longer.
  6. Nothing added or taken away, no onset more than 25 ms from the file's, velocities 1–127, the same every time.
- **The Full power line.** Full power, on by default, makes the piano ignore velocity, so none of this is heard until it
  is off. Under Velocity, one line in the secondary colour: with the piano connected and Full power on, "Full power is
  on: every note strikes at full strength. Turn it off on Sound and touch to hear dynamics and expression."; otherwise
  "Dynamics are heard with Full power off."
- **Where the rows are.** Piano › Playback, after Velocity: **Dynamic range** (Narrow · Natural · Wide, "How far soft and
  loud notes spread apart"), **Quietest note** (a stepper in fives, "Softer notes are raised to this, so they still
  strike"), **Expression** (Off · Light · Full, "Shapes loudness and timing as a pianist would") and **Re-strike time**
  ("Auto · 110 ms from the piano" or "Auto · 100 ms", then 60–250 ms in tens, "The least time between two strikes of one
  key"). Sound and touch › Fine tuning › TIMING shows the piano's **Repeat period** ("110 ms", read-only), read again
  after the shortest strike or the repeat gap changes. Search finds the four by repeat, re-strike, trill, dynamics,
  expression, humanize and soft notes; the web panel's settings take them too.

# v1.17 — M45: pictures through the relay

Steven saw (2026-10-03) the web panel's art stand empty or show letters over the internet link: Now playing and every
Up next row. Against a stand-in for the relay's limits (120 requests a minute from one address; 8 in flight and 32
waiting a piano, the rest refused at once), opening Now playing with an 86-piece queue sent **265** picture requests and
**39** were answered (75 refused as busy, 151 over the minute's limit, the page's own files caught too); with a state
message every 0.6 s, **2,800 in 8 s**. On the tablet's own address the same page asked 83 times, every one answered.
Designed by Fable, built by Opus in one lean run. The panel's pictures now follow six rules:

- **Never empty.** A frame shows its title's letter at once; the picture is laid over it when it arrives (a 160 ms fade,
  none under reduced motion).
- **One request a picture.** The piece's cover, its composer's portrait (at a row's size or a tile's) or its roll card,
  at an address that names its version, so the browser keeps it for good. A picture that fails leaves the letter.
- **Only what is in sight.** A frame asks once it comes within 200 px of the window; Now playing's asks at once, first.
- **A few at a time.** Four at once through the relay (six on the tablet's own address), and through the relay 40 at
  most at once, then one a second, so the page's own requests always have room in the relay's 120 a minute.
- **Failures wait.** A picture that failed is asked again after 4 s, 20 s and 60 s, only while in sight; three failures
  in a row pause every picture for 20 s (the relay's refusal lasts up to a minute).
- **Pictures stay.** A state message that changes nothing Up next shows leaves its rows alone; one that does keeps the
  pictures already there.

Covers match a little wider too. Measured on Steven's 266 uploaded songs against Apple's search, 255 were found under
M40's rule; looking at 25 results (was 10), reading "S.T.A.Y." as "Stay", setting a classical title's movement words
aside, and, when nothing fits, taking the album the artist names ("Stay" by "Interstellar") or the artist's own album of
the work ("Skyrim Theme" by Jeremy Soule) finds four of the other eleven and four of five classical misses on the demo
tablet, and changes no cover found today except for an earlier result. A lookup that found nothing before this version
is tried once more.

# v1.18 — M47: the panel's new look

Steven chose (2026-10-03, from mocks) a new look for the web panel: dark by default, a glass side rail, Now playing with
the album's cover large and its colours behind the whole screen, "like Apple Music, perfectly matched to the album art,
more punchy", a grid of covers in the Library, white dials with one amber colour for "needs attention". Designed by
Fable, built by Opus in one lean run. Everything the panel did, it still does; the guests' page and the poster are as
they were.

- **Dark by default.** The panel starts in the camera body's tokens ("Ink", never pure black); Appearance (Dark · Light ·
  Follow system, kept in the browser) moves to the Settings page. One new token pair: **attention**, amber
  (`AttentionInk` #E6A23C, 8.8:1 on the surface; `AttentionPaper` #9A5B00, 4.8:1), meaning "needs attention" and
  nothing else, always beside a word that says it. Red still means live and only the dot uses it.
- **The frame.** From 900 px a glass rail, 236 px wide with 22 px corners, 12 px from the window's edges: the piano's
  name and the connection line, then the sections in four groups under engraved headings, each a glyph and a label:
  **Play** (Now playing, Library, Channels), **Plan** (Schedule, Requests with the number waiting), **Make** (Add,
  Studio), **Piano** (Settings; System, once its page is there). The chosen one sits on the content colour at 12 %, its
  label in the primary colour; 40 px rows, 44 px on a touch screen. At its foot, the system's vitals. Up next is a
  section of its own only below 1100 px; from there it stands beside Now playing. Below 900 px today's glass strip of
  tabs, 44 px tall, without headings or glyphs. Page heads are 28 px, weight 600, with their tools as glass capsules at
  the right; lists and settings sit on 18 px cards; buttons are capsules, the one primary action filled.
- **Now playing, Art.** A centred block as wide as the cover (54 % of the window's height, at most 560 px, and on a
  window under about 850 px tall no more than leaves the rest in sight): the cover with 22 px corners and a deep soft
  shadow, the title (34 px, bold, two lines at most), the composer with the channel after it, the scrubber (a 6 px
  track), the transport (a 76 px play circle in the content colour), then one row of glass capsules: Tempo, the
  volumes there are (the channel's, the tablet's, each opening its slider in a small popover) and the views' switch,
  **Art · Notes · Score**; the instrument's lines beneath, small. "Sent to piano" is a capsule in the head. Nothing
  loaded: "Choose a piece from the library." centred.
- **Now playing, Notes and Score.** A strip at the top (the cover at 132 px, the title 38 px, the transport at the
  right), then the views filling the window: Notes, the roll alone; Score, the score over or beside the notes with the
  divider (below 900 px the score alone).
- **The backdrop.** Behind the whole panel while Now playing shows a piece with a picture: the cover itself three times,
  each blurred and saturated, turning slowly at its own pace (a turn in 60 s; in 84 s the other way, half again as large
  at 60 %; in 48 s, smaller at 38 % and off centre), only while the piece plays, still when paused, never with reduced
  motion. Over it a black dimming from top to foot (dim − 0.06 to dim + 0.10) where **dim** keeps light words at 4.5:1
  over the cover's brightest part: the cover read at 16 × 16, its brightest 4 × 4 block's luminance L,
  dim = max(0.18, 1 − (0.14 / L)^(1/2.2)). None for a roll card or a monogram, with Album colours off, in black and
  white, with reduced transparency or more contrast.
- **Immersive.** While the backdrop shows, the words are the ink's primary colour in both appearances; nothing
  secondary or tertiary stands directly on it below 18 px; glass (the rail, the capsules, Up next, the switch) is a
  black tint at 26 % over a 30 px blur with a light edge; the live dot wears a 1.5 px light ring so red reads on a red
  cover. The roll has no card: its notes are drawn light straight over the cover (the right hand at 92 %, the left at
  50 %; with Hand colours the ink's two), a light line where they land, the keys as keys with a sounding one yellow.
  The score keeps its opaque sheet in its own colours.
- **Up next, beside it.** A glass card: rows 60 px with a 44 px cover and the length at the right, the playing row
  tinted with a small three-bar mark (still); a row's move and remove buttons show on hover or focus, always on a touch
  screen.
- **The Library.** One row of tools: the genre switch, the search field with its glyph, and **Covers · List** (kept in
  the browser; covers from 900 px, rows below). Covers: a grid of tiles (168 px at least), each the cover with 14 px
  corners, the title and the composer; a tap plays as a row's does; More is a small glass circle at the cover's top
  right, on hover or focus and always on a touch screen. Playlists and composers stay rows.
- **Phones (below 600 px).** The name and the connection over the page; the sections in a glass bar at the foot, 56 px
  tall, each a glyph over its label: Now playing, Up next, Library, More, which opens a sheet with the others. The art
  view's cover is as wide as the page less its margins, at most 360 px.
- **Settings and System.** Their pages are modules loaded the first time they show (M47b); until they are there, the
  built-in Settings page shows as before, on cards, with Appearance at its foot, and System stays hidden.

