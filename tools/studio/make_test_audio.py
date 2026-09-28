#!/usr/bin/env python3
# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

"""Make the spike's test audio (all written to <work>/audio, never to the repository).

1. bach_bwv846.mid / .wav — the arpeggio figure and harmonies of J. S. Bach's Prelude in C major,
   BWV 846 (public domain), bars 1–24 and a final C major chord, written note by note by this
   script (quarter = 70, velocities shaped by bar with seeded humanisation), rendered with
   TinySoundFont through FreePats' "Upright Piano KW" SoundFont (CC0 1.0). Licence-clean: the
   fixtures come from this clip. A copy of the MIDI is kept in fixtures/ as the composer's seed.
2. gymnopedie1.wav — Erik Satie, Gymnopédie No. 1, the Wikimedia Commons recording
   "Gymnopedie No. 1..ogg" by Teknopazzo (CC0 1.0), Ogg-FLAC demuxed and decoded (first 200 s).
3. bench_3min.wav — the first 180 s of gymnopedie1.wav: the emulator bench's input.
4. cut_liszt.wav — the example bundled with piano_transcription_inference (resources/cut_liszt.mp3,
   Lang Lang playing Liszt's Liebestraum; a commercial recording, so it is used to verify only and
   never enters a fixture).
All outputs: 16 kHz mono PCM-16 WAV (what the app will decode to).
"""

import argparse
import json
import os
import random
import subprocess

import mido
import numpy as np
import soundfile as sf

import studio_common as sc

SF2_URL = "https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SF2-20220221.7z"
SF2_7Z_SHA256 = "17c084c6e4205233dc49b34e4bc44a9b2d7c7a2c02b04729ecda77079b07c826"
SF2_FILE = "UprightPianoKW-SF2-20220221/UprightPianoKW-20220221.sf2"
GYMNO_URL = "https://upload.wikimedia.org/wikipedia/commons/b/b7/Gymnopedie_No._1..ogg"
GYMNO_SHA256 = "13e4e03797169392166b9f11d9bf9c421c022b48ad87d9b10bd441ce068502da"
LISZT_URL = ("https://raw.githubusercontent.com/qiuqiangkong/piano_transcription_inference/"
             "master/resources/cut_liszt.mp3")
LISZT_SHA256 = "0a551787adbc61776e3b0691b7727a2276f2a8bf47fa4aad0fb4e8f89ef83603"

# BWV 846, bars 1–24: five notes per bar (a b c d e), each half bar played a b c d e c d e.
N = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def pitch(name):
    base, octave = name[:-1], int(name[-1])
    semis = N[base[0]] + base[1:].count("#") - base[1:].count("b")
    return 12 * (octave + 1) + semis


BARS = [
    "C4 E4 G4 C5 E5", "C4 D4 A4 D5 F5", "B3 D4 G4 D5 F5", "C4 E4 G4 C5 E5",
    "C4 E4 A4 E5 A5", "C4 D4 F#4 A4 D5", "B3 D4 G4 D5 G5", "B3 C4 E4 G4 C5",
    "A3 C4 E4 G4 C5", "D3 A3 D4 F#4 C5", "G3 B3 D4 G4 B4", "G3 Bb3 E4 G4 C#5",
    "F3 A3 D4 A4 D5", "F3 Ab3 D4 F4 B4", "E3 G3 C4 G4 C5", "E3 F3 A3 C4 F4",
    "D3 F3 A3 C4 F4", "G2 D3 G3 B3 F4", "C3 E3 G3 C4 E4", "C3 G3 Bb3 C4 E4",
    "F2 F3 A3 C4 E4", "F#2 C3 A3 C4 Eb4", "Ab2 F3 B3 C4 D4", "G2 F3 G3 B3 D4",
]
FINAL = "C2 C3 E4 G4 C5"


def bach_notes(tempo_qpm=70, seed=846):
    """[(onset_s, offset_s, pitch, velocity)] for bars 1–24 and the final chord."""
    rng = random.Random(seed)
    six = 60.0 / tempo_qpm / 4.0
    notes = []
    for b, chord in enumerate(BARS):
        a, bb, c, d, e = [pitch(x) for x in chord.split()]
        # a gentle arc: p (bar 1) to mf (bar 12) and back, then a swell into bar 24
        level = 48 + 30 * np.sin(np.pi * b / (len(BARS) - 1)) ** 1.5
        for half in range(2):
            t0 = (b * 16 + half * 8) * six
            figure = [(0, a, 8), (1, bb, 7), (2, c, 3), (3, d, 3), (4, e, 3), (5, c, 3), (6, d, 2), (7, e, 1)]
            for pos, p, held in figure:
                jitter = rng.uniform(-0.008, 0.008) if pos else 0.0
                on = max(0.0, t0 + pos * six + jitter)
                off = t0 + (pos + held) * six - 0.01
                vel = level + (8 if pos == 0 else 0) + (4 if pos in (2, 5) else 0) + rng.uniform(-6, 6)
                notes.append((on, off, p, int(np.clip(round(vel), 20, 110))))
    t_end = len(BARS) * 16 * six
    for i, x in enumerate(FINAL.split()):
        notes.append((t_end + 0.012 * i, t_end + 3.2, pitch(x), 70))
    return sorted(notes)


def write_midi(notes, path):
    mid = mido.MidiFile(ticks_per_beat=480)
    tr = mido.MidiTrack()
    mid.tracks.append(tr)
    tr.append(mido.MetaMessage("set_tempo", tempo=500000, time=0))  # 120 qpm: 1 beat = 0.5 s
    tr.append(mido.Message("program_change", program=0, channel=0, time=0))
    ev = []
    for on, off, p, v in notes:
        ev.append((on, 1, p, v))   # note-on after note-off at the same instant
        ev.append((off, 0, p, 0))
    ev.sort()
    last = 0
    for t, kind, p, v in ev:
        tick = int(round(t * 960))  # 480 ticks per 0.5 s beat
        dt = tick - last
        last = tick
        if kind:
            tr.append(mido.Message("note_on", note=p, velocity=v, channel=0, time=dt))
        else:
            tr.append(mido.Message("note_off", note=p, velocity=0, channel=0, time=dt))
    tr.append(mido.MetaMessage("end_of_track", time=0))
    mid.save(path)


def render(notes, sf2, sr=44100, tail=2.0):
    import tinysoundfont
    synth = tinysoundfont.Synth(samplerate=sr)
    sfid = synth.sfload(sf2)
    synth.program_select(0, sfid, 0, 0)
    ev = []
    for on, off, p, v in notes:
        ev.append((off, 0, p, 0))
        ev.append((on, 1, p, v))
    ev.sort()
    out = []
    done = 0
    for t, kind, p, v in ev:
        target = int(round(t * sr))
        if target > done:
            out.append(np.frombuffer(synth.generate(target - done), dtype=np.float32).copy())
            done = target
        if kind:
            synth.noteon(0, p, v)
        else:
            synth.noteoff(0, p)
    out.append(np.frombuffer(synth.generate(int(tail * sr)), dtype=np.float32).copy())
    stereo = np.concatenate(out).reshape(-1, 2)
    return stereo.mean(axis=1), sr


def ogg_flac_to_flac(src, dst):
    """Demux an Ogg-FLAC stream into native FLAC (libsndfile cannot open this Ogg-FLAC file)."""
    data = open(src, "rb").read()
    pos, packets, cur = 0, [], b""
    while pos < len(data):
        assert data[pos:pos + 4] == b"OggS"
        nseg = data[pos + 26]
        segs = data[pos + 27:pos + 27 + nseg]
        p = pos + 27 + nseg
        for s in segs:
            cur += data[p:p + s]
            p += s
            if s < 255:
                packets.append(cur)
                cur = b""
        pos = p
    first = packets[0]
    assert first[:5] == b"\x7fFLAC" and first[9:13] == b"fLaC"
    nhdr = int.from_bytes(first[7:9], "big")
    blocks = [first[13:]] + packets[1:1 + nhdr]
    out = bytearray(b"fLaC")
    for i, blk in enumerate(blocks):
        blk = bytearray(blk)
        blk[0] = (blk[0] | 0x80) if i == len(blocks) - 1 else (blk[0] & 0x7F)
        out += blk
    for f in packets[1 + nhdr:]:
        out += f
    open(dst, "wb").write(out)


def read_until_error(path):
    """Read a FLAC whose STREAMINFO has no length: libsndfile stops with a seek error at the end."""
    chunks = []
    with sf.SoundFile(path) as f:
        sr = f.samplerate
        while True:
            try:
                b = f.read(sr * 5, dtype="float32", always_2d=True)
            except Exception:
                break
            if len(b) == 0:
                break
            chunks.append(b)
    return np.concatenate(chunks).mean(axis=1), sr


def to16k_wav(y, sr, path):
    import librosa
    if sr != sc.SAMPLE_RATE:
        y = librosa.resample(y.astype(np.float32), orig_sr=sr, target_sr=sc.SAMPLE_RATE)
    peak = float(np.abs(y).max())
    if peak > 0.99:
        y = y * (0.99 / peak)
    sf.write(path, y, sc.SAMPLE_RATE, subtype="PCM_16")
    return len(y) / sc.SAMPLE_RATE


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work")
    args = ap.parse_args()
    work = sc.work_dir(args.work)
    dl, audio = os.path.join(work, "downloads"), os.path.join(work, "audio")
    sources = {}

    # 1. Bach, rendered with a CC0 SoundFont
    sf7z = sc.download(SF2_URL, os.path.join(dl, os.path.basename(SF2_URL)), SF2_7Z_SHA256)
    sfdir = os.path.join(dl, "sf2")
    sf2 = os.path.join(sfdir, SF2_FILE)
    if not os.path.exists(sf2):
        os.makedirs(sfdir, exist_ok=True)
        subprocess.check_call(["tar", "-xf", sf7z, "-C", sfdir])  # bsdtar reads 7z
    notes = bach_notes()
    mid = os.path.join(audio, "bach_bwv846.mid")
    write_midi(notes, mid)
    y, sr = render(notes, sf2)
    secs = to16k_wav(y, sr, os.path.join(audio, "bach_bwv846.wav"))
    with open(os.path.join(audio, "bach_bwv846.notes.json"), "w") as f:
        json.dump([[round(a, 4), round(b, 4), p, v] for a, b, p, v in notes], f)
    fx_mid = os.path.join(sc.FIXTURES, "bach_bwv846.mid")
    write_midi(notes, fx_mid)
    sources["bach_bwv846.wav"] = {"seconds": round(secs, 2), "notes": len(notes),
                                  "licence": "CC0-1.0 (own MIDI of a public-domain work; CC0 SoundFont)",
                                  "soundfont": SF2_URL, "soundfont_sha256": SF2_7Z_SHA256}

    # 2./3. Satie, CC0 recording from Wikimedia Commons
    ogg = sc.download(GYMNO_URL, os.path.join(dl, "Gymnopedie_No._1.ogg"), GYMNO_SHA256)
    flac = os.path.join(dl, "Gymnopedie_No._1.flac")
    ogg_flac_to_flac(ogg, flac)
    y, sr = read_until_error(flac)
    secs = to16k_wav(y, sr, os.path.join(audio, "gymnopedie1.wav"))
    y16, _ = sf.read(os.path.join(audio, "gymnopedie1.wav"), dtype="int16")
    sf.write(os.path.join(audio, "bench_3min.wav"), y16[:180 * sc.SAMPLE_RATE], sc.SAMPLE_RATE, subtype="PCM_16")
    sources["gymnopedie1.wav"] = {"seconds": round(secs, 2), "source": GYMNO_URL, "sha256": GYMNO_SHA256,
                                  "licence": "CC0-1.0 (Wikimedia Commons, Teknopazzo)"}
    sources["bench_3min.wav"] = {"seconds": 180.0, "from": "gymnopedie1.wav[0:180 s]"}

    # 4. the package's example (verification only)
    mp3 = sc.download(LISZT_URL, os.path.join(dl, "cut_liszt.mp3"), LISZT_SHA256)
    y, sr = sf.read(mp3, dtype="float32", always_2d=True)
    secs = to16k_wav(y.mean(axis=1), sr, os.path.join(audio, "cut_liszt.wav"))
    sources["cut_liszt.wav"] = {"seconds": round(secs, 2), "source": LISZT_URL, "sha256": LISZT_SHA256,
                                "licence": "commercial recording: verification only, never in a fixture"}

    for name in list(sources):
        sources[name]["wav_sha256"] = sc.sha256_file(os.path.join(audio, name))
    with open(os.path.join(audio, "sources.json"), "w") as f:
        json.dump(sources, f, indent=2)
    print(json.dumps(sources, indent=2))


if __name__ == "__main__":
    main()
