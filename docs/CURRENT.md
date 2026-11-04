<!-- ============================================================================
     Steven Piano - Android player for the self-playing acoustic piano
     Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
     Original author & creator: Steven Jin.
     Licensed under the MIT License (see LICENSE). This copyright and attribution
     notice MUST be preserved in all copies or substantial portions of the work.
     Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
     ============================================================================ -->

# Steven Piano: start here

The app that plays a self-playing acoustic piano in a school: a tablet in kiosk mode, talking Bluetooth to a controller
that drives 84 solenoids from a 24 V supply on a smart plug. It runs unattended in a building full of children, so **fire
safety decides every design call**. There are two identical systems, each with its own tablet, piano, plug and panel.

Read this page, then only what your task needs.

## Where things are written

| File | What it is | How to use it |
|---|---|---|
| `README.md` | What every feature does, for the person running it | Read the feature's section; its headings are a good map |
| `DESIGN.md` | The look: the rules, what later versions changed, the latest sections | Read before changing a screen |
| `BUILD_SPEC.md` | The piano's MIDI contract and the latest releases | Read the contract before touching notes or Bluetooth |
| `docs/archive/*-history.md` | Every older section, word for word (1.1 MB) | **Search it, don't read it.** A citation in the code such as `BUILD_SPEC › v1.7 — M23` is a heading there |
| `docs/SECURITY_AUDIT.md` | The security audits and each finding's status | Read its summary, and the finding you're near |
| `cloud/README.md` | The relay, the console and the short address | Read before touching `cloud/` |
| `../firmware/` | The controller's code and its docs: another project's | Read only. Never edit it from here |

## The code

Under `app/src/main/java/dev/stevenjin/stevenpiano/`:

| Package | What it holds |
|---|---|
| `AppGraph.kt`, `*Wiring.kt` | The wiring, by area (library, studio, update, web, power, safety); `AppGraph.start()` sets the order |
| `power/` | The 24 V rail on its plug: `RailKeeper` (the watchdog), `BoardWatch`, `PowerWatch`, `PlugAdverts` |
| `safety/` | The safety check that must pass before and between pieces |
| `ble/`, `piano/` | The Bluetooth link, the piano's settings and its lock |
| `player/`, `midi/`, `score/` | Playback and pacing, MIDI parsing and routing, the score |
| `data/`, `library/`, `channels/` | The library, imports (`data/imports/`), the library pack, channels |
| `web/`, `web/relay/` | The panel's server and routes; the outbound connection to the relay |
| `service/` | The foreground services: power watch, panel, playback, and the job services |
| `admin/`, `quiet/`, `update/`, `firmware/` | Kiosk, quiet times, app updates, the piano's firmware updates |
| `studio/`, `audio/`, `instruments/`, `record/` | Studio's AI, the tablet's own piano sound, MIDI instruments, recording |
| `ui/` | The screens. Words live in the `*Copy.kt` objects |

The panel's pages are in `app/src/main/assets/web/`. The relay, console and short address are in `cloud/` (Cloudflare Workers).

## What runs all the time on a tablet

The rail's watch and the plug's adverts, the panel's service with its outbound relay connection (a ping every 30 s, a
status every 5 minutes and within seconds of a change), and light clock checks for On hours and quiet times. At rest the
screen shifts once a minute and draws nothing else. Anything new that runs in the background must justify itself here.

## Rules that never bend

- **The fire-safety layers stay.** Don't weaken, bypass or "simplify" the rail, the watches, the safety check or the
  cool-down, and don't touch them in a refactor unless the task is about them.
- Never type, log or commit a PIN, a password or the Wi-Fi password. The signing key and the authorship key stay out of the repo.
- The repository is public. The library pack is openly licensed music only; no fan-made pop transcriptions, here or in it.
- Keep tests lean, but keep them for data migrations, piano safety and internet-facing routes.
- Every source file keeps its copyright header.

## Building and releasing

The gate, from this folder (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`,
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`):

```bash
./gradlew -q testDebugUnitTest --rerun lintDebug assembleDebug
```

`cloud/` has its own: `npm test`. For a release, `tools/release.sh <version> "<notes>"` sets the version everywhere, runs
the gate, commits and re-signs; nothing leaves the Mac until `tools/publish-release.sh` runs, and that only on Steven's say-so.
A coding run never pushes, publishes, deploys, bumps the version or signs: it commits on its branch and reports back.
Change the relay or console and it needs deploying by Steven; when an app change depends on it, deploy before publishing.

On a debug build in an emulator the piano is emulated and the whole panel answers on loopback: `adb forward tcp:8737
tcp:8737`, then `http://localhost:8737`. Use the `steven_piano` emulator; `steven_piano_tablet` is for Steven's own demos.
