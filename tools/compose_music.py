"""
Original ambient pieces for Shuffle, synthesised from scratch (no samples, no licences).
Each piece is built to loop seamlessly: the reverb tail past the end is folded back onto the start.
"""
import numpy as np
from scipy.signal import fftconvolve, butter, sosfilt
from scipy.io import wavfile
import subprocess, os, sys

SR = 44100
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
TAIL = 7.0  # seconds of ring-out folded back to the start


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


NOTE = {n: i for i, n in enumerate("C C# D D# E F F# G G# A A# B".split())}


def m(name):  # "C4" -> midi
    p, o = name[:-1], int(name[-1])
    return 12 * (o + 1) + NOTE[p]


class Track:
    def __init__(self, seconds, seed):
        self.L = int(seconds * SR)
        self.n = self.L + int(TAIL * SR)
        self.buses = {k: np.zeros((2, self.n)) for k in ("pad", "pluck", "bass", "noise")}
        self.rng = np.random.default_rng(seed)

    def add(self, bus, start, sig, pan=0.0):
        i = int(start * SR)
        if i >= self.n:
            return
        sig = sig[: self.n - i]
        l = np.cos((pan + 1) * np.pi / 4)
        r = np.sin((pan + 1) * np.pi / 4)
        self.buses[bus][0, i:i + len(sig)] += sig * l
        self.buses[bus][1, i:i + len(sig)] += sig * r


def env(n, a, r, sus=1.0):
    e = np.full(n, sus)
    na, nr = max(1, int(a * SR)), max(1, int(r * SR))
    e[:na] = np.linspace(0, sus, na) ** 1.6
    e[-nr:] *= np.linspace(1, 0, nr) ** 2
    return e


def pad_voice(rng, f, dur, amp, attack=2.5, release=3.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.zeros(n)
    for cents in (-7, 0, 6):
        ff = f * 2 ** (cents / 1200)
        for k in range(1, 6):
            s += np.sin(2 * np.pi * ff * k * t + rng.uniform(0, 6.28)) / (k ** 2.2)
    lfo = 1 + 0.12 * np.sin(2 * np.pi * rng.uniform(0.07, 0.15) * t + rng.uniform(0, 6.28))
    return s * lfo * env(n, attack, release) * amp / 3


def pluck(f, dur, amp, bright=1.0, B=0.0003):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.zeros(n)
    for k in range(1, 11):
        fk = k * f * np.sqrt(1 + B * k * k)
        if fk > 12000:
            break
        decay = (1.1 + 0.55 * k) / bright
        s += np.sin(2 * np.pi * fk * t) * np.exp(-decay * t) / (k ** 1.25)
    att = np.minimum(1, t / 0.004)
    return s * att * amp


def bell(f, dur, amp):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.zeros(n)
    for ratio, a, d in ((1, 1, 0.9), (2.0, 0.5, 1.4), (2.76, 0.35, 2.2), (5.4, 0.18, 3.5), (8.93, 0.08, 5)):
        s += a * np.sin(2 * np.pi * f * ratio * t) * np.exp(-d * t)
    return s * np.minimum(1, t / 0.002) * amp


def bass(f, dur, amp):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) + 0.18 * np.sin(4 * np.pi * f * t)
    return s * env(n, 0.6, 1.5) * amp


def lowpass(x, cutoff, order=2):
    sos = butter(order, cutoff, btype="low", fs=SR, output="sos")
    return sosfilt(sos, x, axis=-1)


def reverb_ir(seconds, rng, damp=6000):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal((2, n)) * np.exp(-6.9 * t / seconds)  # ~RT60 = seconds
    ir = lowpass(ir, damp)
    ir[:, : int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))
    return ir / np.sqrt(np.sum(ir ** 2, axis=1, keepdims=True))


def finish(tr, name, wet=0.32, rt=3.4, pad_cut=2400):
    mix = (lowpass(tr.buses["pad"], pad_cut) * 0.9 + tr.buses["pluck"] + tr.buses["bass"] * 0.8
           + tr.buses["noise"])
    ir = reverb_ir(rt, tr.rng)
    wetsig = np.stack([fftconvolve(mix[c], ir[c])[: tr.n] for c in range(2)])
    out = mix * (1 - wet) + wetsig * wet * 1.6
    # gentle high-pass to clean rumble
    sos = butter(2, 35, btype="high", fs=SR, output="sos")
    out = sosfilt(sos, out, axis=-1)
    # fold tail onto start -> seamless loop
    tail = tr.n - tr.L
    out[:, :tail] += out[:, tr.L:]
    out = out[:, : tr.L]
    # level: target RMS, then soft-limit
    rms = np.sqrt(np.mean(out ** 2))
    out *= 0.11 / rms
    out = np.tanh(out * 1.1) / 1.1
    peak = np.max(np.abs(out))
    if peak > 0.95:
        out *= 0.95 / peak
    wav = os.path.join(OUT, name + ".wav")
    wavfile.write(wav, SR, (out.T * 32767).astype(np.int16))
    ogg = os.path.join(OUT, name + ".ogg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", ogg], check=True)
    os.remove(wav)
    print(f"{name}: {tr.L / SR:.0f}s, peak {np.max(np.abs(out)):.2f}, {os.path.getsize(ogg) / 1e6:.2f} MB")


def chord_pad(tr, start, dur, notes, amp, attack=2.5, release=3.5):
    for i, nn in enumerate(notes):
        pan = (i / max(1, len(notes) - 1) - 0.5) * 0.8
        tr.add("pad", start, pad_voice(tr.rng, hz(m(nn)), dur + release * 0.6, amp, attack, release), pan)


# ---------------------------------------------------------------- 1. Drift
def drift():
    chord_len = 8.0
    prog = [
        (["C3", "G3", "B3", "D4", "E4"], "C2"),
        (["A2", "E3", "G3", "B3", "C4"], "A1"),
        (["F2", "C3", "E3", "G3", "A3"], "F1"),
        (["G2", "D3", "E3", "A3", "B3"], "G1"),
    ]
    reps = 4
    tr = Track(chord_len * len(prog) * reps, seed=11)
    scale = ["C5", "D5", "E5", "G5", "A5", "C6", "D6"]
    for r in range(reps):
        for c, (notes, root) in enumerate(prog):
            t0 = (r * len(prog) + c) * chord_len
            chord_pad(tr, t0, chord_len, notes, 0.16)
            tr.add("bass", t0, bass(hz(m(root)), chord_len + 1.5, 0.22))
            if r > 0:  # sparse melody after the first pass
                k = tr.rng.integers(2, 4)
                for tt in sorted(tr.rng.uniform(0.5, chord_len - 1, k)):
                    note = scale[tr.rng.integers(0, len(scale))]
                    tr.add("pluck", t0 + tt, pluck(hz(m(note)), 5, 0.09, bright=0.8), tr.rng.uniform(-0.5, 0.5))
    finish(tr, "drift", wet=0.38, rt=4.0)


# ---------------------------------------------------------------- 2. Lantern
def lantern():
    bpm = 84
    beat = 60 / bpm
    bar = 4 * beat
    prog = [
        (["D3", "A3", "F#4"], [m("D4"), m("F#4"), m("A4"), m("D5")], "D2"),
        (["B2", "F#3", "D4"], [m("B3"), m("D4"), m("F#4"), m("B4")], "B1"),
        (["G2", "D3", "B3"], [m("G3"), m("B3"), m("D4"), m("G4")], "G1"),
        (["A2", "E3", "C#4"], [m("A3"), m("C#4"), m("E4"), m("A4")], "A1"),
    ]
    patterns = [
        [0, 1, 2, 3, 2, 1, 2, 3],
        [0, 2, 1, 3, 0, 2, 3, 2],
        [3, 2, 1, 0, 1, 2, 3, 2],
    ]
    melody = [m(x) for x in ("A5", "F#5", "E5", "D5", "B4", "D5", "E5", "F#5")]
    reps = 6
    tr = Track(bar * 2 * len(prog) * reps, seed=23)
    for r in range(reps):
        pat = patterns[r % len(patterns)]
        for c, (padn, arp, root) in enumerate(prog):
            t0 = (r * len(prog) + c) * 2 * bar
            chord_pad(tr, t0, 2 * bar, padn, 0.10, attack=1.2, release=2.5)
            tr.add("bass", t0, bass(hz(m(root)), 2 * bar + 1, 0.22))
            for b in range(2):
                for i, step in enumerate(pat):
                    t = t0 + b * bar + i * beat / 2
                    vel = 0.075 if i % 2 == 0 else 0.055
                    note = arp[step] + (12 if r >= 2 else 0)
                    tr.add("pluck", t + tr.rng.uniform(0, 0.012), pluck(hz(note), 2.8, vel, bright=1.1),
                           -0.35 + 0.7 * step / 3)
            if r in (3, 4):  # gentle melody in the middle
                note = melody[(c * 2 + r) % len(melody)]
                tr.add("pluck", t0 + beat * 0.02, pluck(hz(note), 4, 0.07, bright=0.7), 0.1)
                tr.add("pluck", t0 + bar + beat * 2, pluck(hz(melody[(c * 2 + r + 1) % len(melody)]), 4, 0.06, 0.7), 0.1)
    finish(tr, "lantern", wet=0.3, rt=2.6)


# ---------------------------------------------------------------- 3. Tide
def tide():
    chord_len = 10.0
    prog = [
        (["D3", "A3", "C4", "E4", "F4"], "D2"),
        (["A#2", "F3", "A3", "D4"], "A#1"),
        (["G2", "D3", "F3", "A#3", "A3"], "G1"),
        (["A2", "E3", "G3", "C#4"], "A1"),
    ]
    bells = [m(x) for x in ("D5", "F5", "A5", "C6", "E5", "G5")]
    reps = 3
    tr = Track(chord_len * len(prog) * reps, seed=37)
    for r in range(reps):
        for c, (notes, root) in enumerate(prog):
            t0 = (r * len(prog) + c) * chord_len
            chord_pad(tr, t0, chord_len, notes, 0.15, attack=3.5, release=4)
            tr.add("bass", t0, bass(hz(m(root)), chord_len + 2, 0.2))
            for tt in sorted(tr.rng.uniform(1, chord_len - 2, 2)):
                tr.add("pluck", t0 + tt, bell(hz(bells[tr.rng.integers(0, len(bells))]), 6, 0.05),
                       tr.rng.uniform(-0.6, 0.6))
            # wave swell: filtered noise, rising and falling over the chord
            n = int(chord_len * SR)
            noise = lowpass(tr.rng.standard_normal(n), 700, order=2)
            swell = np.sin(np.linspace(0, np.pi, n)) ** 2
            tr.add("noise", t0, noise * swell * 0.035, tr.rng.uniform(-0.4, 0.4))
    finish(tr, "tide", wet=0.42, rt=4.5, pad_cut=1800)


# ---------------------------------------------------------------- 4. Morning
def morning():
    bpm = 92
    beat = 60 / bpm
    bar = 4 * beat
    prog = [
        (["F3", "A3", "C4", "F4"], "F2"),
        (["E3", "G3", "C4", "E4"], "C2"),
        (["D3", "F3", "A3", "C4"], "D2"),
        (["A#2", "D3", "F3", "A3"], "A#1"),
    ]
    strum_times = [0, 1.5, 2, 3, 3.5]  # beats within a bar
    tune = [m(x) for x in ("C5", "A4", "G4", "A4", "F4", "G4", "A4", "C5")]
    reps = 6
    tr = Track(bar * 2 * len(prog) * reps, seed=51)
    for r in range(reps):
        for c, (notes, root) in enumerate(prog):
            t0 = (r * len(prog) + c) * 2 * bar
            chord_pad(tr, t0, 2 * bar, notes, 0.06, attack=1.0, release=2.0)
            tr.add("bass", t0, bass(hz(m(root)), bar * 1.1, 0.24))
            tr.add("bass", t0 + bar, bass(hz(m(root)), bar * 1.1, 0.2))
            for b in range(2):
                for j, sb in enumerate(strum_times):
                    t = t0 + b * bar + sb * beat
                    vel = 0.05 if j == 0 else 0.032
                    order = notes if j % 2 == 0 else notes[::-1]
                    for k, nn in enumerate(order):
                        tr.add("pluck", t + k * 0.014, pluck(hz(m(nn) + 12), 1.6, vel, bright=1.4, B=0.0001),
                               -0.3 + 0.2 * k)
                # soft shaker on the off-beats
                for e in range(8):
                    if e % 2 == 1 and r > 0:
                        n = int(0.06 * SR)
                        hit = tr.rng.standard_normal(n) * np.exp(-np.arange(n) / SR * 60)
                        sos = butter(2, 6000, btype="high", fs=SR, output="sos")
                        tr.add("noise", t0 + b * bar + e * beat / 2, sosfilt(sos, hit) * 0.02, 0.3)
            if r >= 2:
                for i in range(2):
                    note = tune[(c * 2 + i + r) % len(tune)]
                    tr.add("pluck", t0 + i * bar + beat * 0.5, pluck(hz(note), 3, 0.06, bright=0.8), -0.1)
    finish(tr, "morning", wet=0.24, rt=2.2)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    drift(); lantern(); tide(); morning()
