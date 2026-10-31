"""
Procedurally synthesised ambient loops for Glance Kit's focus scenes (all original, no samples).
Outputs mono Ogg Vorbis files: seamless loops + one-shot thunder claps.
"""
import subprocess, sys, os
import numpy as np
from scipy import signal

SR = 32000
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(42)

# ---------------- helpers ----------------
def white(n): return rng.standard_normal(n)

def pink(n):
    # Paul Kellet's refined filter approximation via IIR
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    return signal.lfilter(b, a, white(n)) * 8

def brown(n):
    x = np.cumsum(white(n))
    x = signal.lfilter([1, -1], [1, -0.9995], x)  # leak to stop drift
    return x / (np.std(x) + 1e-9)

def bp(x, lo, hi, order=2): return signal.sosfilt(signal.butter(order, [lo, hi], "band", fs=SR, output="sos"), x)
def lp(x, f, order=2): return signal.sosfilt(signal.butter(order, f, "low", fs=SR, output="sos"), x)
def hp(x, f, order=2): return signal.sosfilt(signal.butter(order, f, "high", fs=SR, output="sos"), x)

def lfo(n, freqs_amps, phase=None):
    t = np.arange(n) / SR
    out = np.zeros(n)
    for f, a in freqs_amps:
        out += a * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
    return out

def reverb(x, seconds=1.2, mix=0.25, damp=3000):
    n = int(seconds * SR)
    ir = white(n) * np.exp(-np.linspace(0, 6, n))
    ir = lp(ir, damp)
    ir /= np.sqrt(np.sum(ir ** 2))
    wet = signal.fftconvolve(x, ir)[: len(x)]
    return (1 - mix) * x + mix * wet

def place(buf, ev, at):
    end = min(len(buf), at + len(ev))
    if at < len(buf): buf[at:end] += ev[: end - at]

def make_loop(gen, seconds, xfade=3.0):
    """Render seconds+xfade, then fold the tail onto the head with an equal-power crossfade."""
    L = int(seconds * SR); X = int(xfade * SR)
    x = gen(L + X)
    t = np.linspace(0, np.pi / 2, X)
    head = x[:X] * np.sin(t) + x[L:L + X] * np.cos(t)
    y = x[:L].copy(); y[:X] = head
    return y

def normalize(x, peak_db=-3.0, rms_db=None):
    x = x - np.mean(x)
    if rms_db is not None:
        x *= 10 ** (rms_db / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-12)
    p = np.max(np.abs(x))
    lim = 10 ** (peak_db / 20)
    if p > lim:  # soft limit instead of hard scaling, keeps loudness
        x = np.tanh(x / lim) * lim
    return x

def lufs(x):
    raw = os.path.join(OUT, "_m.f32"); x.astype(np.float32).tofile(raw)
    r = subprocess.run(["ffmpeg", "-hide_banner", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", raw,
                        "-af", "ebur128", "-f", "null", "-"], capture_output=True, text=True).stderr
    os.remove(raw)
    import re
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", r)[-1])

def to_lufs(x, target, peak_db):
    for _ in range(3):
        x = x * 10 ** ((target - lufs(x)) / 20)
        lim = 10 ** (peak_db / 20)
        x = np.tanh(x / lim) * lim
    return x

def save(name, x, q=3):
    raw = os.path.join(OUT, name + ".f32")
    (x.astype(np.float32)).tofile(raw)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", raw,
                    "-c:a", "libvorbis", "-q:a", str(q), os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(raw)

# ---------------- rain ----------------
def drop(kind):
    if kind == "tick":  # small hard tick on a surface
        n = int(rng.uniform(0.004, 0.012) * SR)
        e = np.exp(-np.linspace(0, rng.uniform(6, 10), n))
        f = rng.uniform(1800, 5200)
        return (np.sin(2 * np.pi * f * np.arange(n) / SR) * 0.6 + white(n) * 0.5) * e
    if kind == "plop":  # bigger drop into water: rising-pitch bubble
        n = int(rng.uniform(0.03, 0.07) * SR)
        t = np.arange(n) / SR
        f0 = rng.uniform(500, 1100); f = f0 * (1 + 3.5 * t / t[-1])
        ph = 2 * np.pi * np.cumsum(f) / SR
        return np.sin(ph) * np.exp(-t * rng.uniform(60, 110))
    # leaf patter: soft filtered click
    n = int(0.02 * SR)
    return bp(white(n), 900, 3000) * np.exp(-np.linspace(0, 8, n))

def rain(n):
    gust = 1 + lfo(n, [(0.031, 0.18), (0.067, 0.12), (0.013, 0.1)])
    body = lp(bp(pink(n), 400, 7000), 5500) * 0.55 * gust
    wash = bp(pink(n), 250, 1600) * 0.35 * gust       # the distant roar of rain on everything
    hiss = bp(white(n), 5000, 9000) * 0.02 * gust
    low = lp(brown(n), 260) * 0.35 * (1 + 0.5 * (gust - 1))
    drops = np.zeros(n)
    t = 0
    while t < n:  # dense ticks
        t += int(rng.exponential(SR / 140))
        place(drops, drop("tick") * rng.lognormal(-1.6, 0.6), t)
    t = 0
    while t < n:  # leaf patter
        t += int(rng.exponential(SR / 25))
        place(drops, drop("leaf") * rng.lognormal(-1.2, 0.5), t)
    t = 0
    while t < n:  # occasional bubbles in puddles
        t += int(rng.exponential(SR * 0.9))
        place(drops, drop("plop") * rng.lognormal(-1.5, 0.4), t)
    drops = reverb(drops, 0.6, 0.3, 5000)
    return body + wash + hiss + low + lp(drops, 7000) * 0.8

# ---------------- thunder ----------------
def thunder(near):
    dur = rng.uniform(6.5, 9.0) if near else rng.uniform(7.5, 10.0)
    n = int(dur * SR); t = np.arange(n) / SR
    rumble = lp(brown(n), 150 if near else 95, 4)
    # rolling: a few overlapping swells with long tails
    env = np.zeros(n)
    k = rng.integers(3, 6)
    starts = np.sort(rng.uniform(0.05, dur * 0.45, k)); starts[0] = 0.05 if near else 0.4
    for s in starts:
        a = rng.uniform(0.5, 1.0)
        rise = rng.uniform(0.08, 0.35)
        e = np.where(t < s, 0, np.where(t < s + rise, (t - s) / rise, np.exp(-(t - s - rise) / rng.uniform(0.8, 2.0))))
        env += a * e
    env *= 1 + 0.35 * lfo(n, [(1.7, 1), (3.1, 0.6), (0.6, 0.8)]) / 2.4
    x = rumble * env
    if near:  # the crack: sharp broadband burst with a few snaps
        cn = int(0.35 * SR)
        crack = hp(white(cn), 700) * np.exp(-np.linspace(0, 9, cn))
        for _ in range(6):
            p = rng.integers(0, cn // 2)
            crack[p:p + 60] += white(60) * rng.uniform(1, 3)
        x[: cn] += crack * 0.9
        x += bp(white(n), 200, 900) * env * 0.12
    x = reverb(x, 2.5, 0.45, 1200)
    fade = int(0.8 * SR); x[-fade:] *= np.linspace(1, 0, fade)
    return x

# ---------------- leaves / autumn wind ----------------
def chirp_bird():
    notes = rng.integers(3, 7); out = []
    base = rng.uniform(2600, 4200)
    for i in range(notes):
        d = rng.uniform(0.05, 0.11); n = int(d * SR); tt = np.arange(n) / SR
        f = base * (1 + rng.uniform(-0.15, 0.25)) * (1 + rng.uniform(0.2, 0.6) * np.sin(np.pi * tt / d))
        ph = 2 * np.pi * np.cumsum(f) / SR
        out.append(np.sin(ph) * np.sin(np.pi * tt / d) ** 2)
        out.append(np.zeros(int(rng.uniform(0.03, 0.09) * SR)))
    return np.concatenate(out)

def leaves(n):
    g = 1 + lfo(n, [(0.045, 0.35), (0.11, 0.15), (0.021, 0.2)])
    g = np.clip(g, 0.3, None)
    wind = (bp(pink(n), 180, 900) * 0.6 + bp(pink(n), 600, 2200) * 0.25 * (g - 0.3)) * g
    rustle = np.zeros(n); t = 0
    while t < n:
        t += int(rng.uniform(1.5, 4.5) * SR)
        d = int(rng.uniform(0.3, 1.4) * SR)
        grain = (rng.random(d) < 0.08) * white(d)
        r = hp(grain + white(d) * 0.15, 2500) * np.sin(np.linspace(0, np.pi, d)) ** 1.5
        place(rustle, r * rng.uniform(0.15, 0.4) * g[min(t, n - 1)], t)
    birds = np.zeros(n); t = int(2 * SR)
    while t < n:
        place(birds, chirp_bird() * rng.uniform(0.04, 0.09), t)
        t += int(rng.uniform(6, 14) * SR)
    birds = reverb(birds, 1.6, 0.45, 6000)
    return wind + rustle + birds

# ---------------- snow / hushed winter ----------------
def chime():
    f = rng.choice([523.25, 587.33, 659.25, 783.99, 880.0, 1046.5])
    d = 4.0; n = int(d * SR); tt = np.arange(n) / SR
    x = (np.sin(2 * np.pi * f * tt) + 0.35 * np.sin(2 * np.pi * f * 2.76 * tt) * np.exp(-tt * 2) +
         0.2 * np.sin(2 * np.pi * f * 5.4 * tt) * np.exp(-tt * 4))
    return x * np.exp(-tt * 1.1) * np.minimum(1, tt * 400)

def snow(n):
    g = np.clip(1 + lfo(n, [(0.025, 0.4), (0.06, 0.2)]), 0.2, None)
    wind = lp(brown(n), 380) * 0.6 * g + bp(pink(n), 300, 1200) * 0.12 * g ** 2
    hush = hp(white(n), 4000) * 0.015
    ch = np.zeros(n); t = int(3 * SR)
    while t < n:
        place(ch, chime() * rng.uniform(0.03, 0.06), t)
        t += int(rng.uniform(7, 15) * SR)
    ch = reverb(ch, 3.0, 0.5, 4000)
    return wind + hush + ch

# ---------------- fireflies / summer night ----------------
def cricket(n, rate, freq, level):
    out = np.zeros(n); t = int(rng.uniform(0, rate) * SR)
    pulse_n = int(0.012 * SR); tt = np.arange(pulse_n) / SR
    pulse = np.sin(2 * np.pi * freq * tt) * np.sin(np.pi * tt / tt[-1])
    while t < n:
        for k in range(3):  # three pulses per chirp
            place(out, pulse * level, t + int(k * 0.028 * SR))
        t += int(rate * SR * rng.uniform(0.95, 1.05))
    return out

def frog():
    d = rng.uniform(0.18, 0.3); n = int(d * SR); tt = np.arange(n) / SR
    f = rng.uniform(220, 340)
    return np.sin(2 * np.pi * f * tt) * (0.5 + 0.5 * np.sin(2 * np.pi * 26 * tt)) * np.sin(np.pi * tt / d)

def night(n):
    amb = bp(pink(n), 150, 1500) * 0.05 + lp(brown(n), 200) * 0.08
    crk = sum(cricket(n, rng.uniform(0.55, 1.1), rng.uniform(4100, 4900), rng.uniform(0.05, 0.12)) for _ in range(4))
    frogs = np.zeros(n); t = int(4 * SR)
    while t < n:
        for k in range(rng.integers(1, 4)):
            place(frogs, frog() * rng.uniform(0.05, 0.1), t + int(k * 0.42 * SR))
        t += int(rng.uniform(5, 11) * SR)
    return amb + reverb(crk, 0.8, 0.35, 7000) + reverb(frogs, 1.5, 0.4, 2000)

# ---------------- stars / drone pad ----------------
def stars(n):
    t = np.arange(n) / SR
    chord = [110.0, 164.81, 220.0, 277.18, 329.63, 493.88]
    x = np.zeros(n)
    for f in chord:
        a = 1 / (1 + f / 220)
        for det in (-0.6, 0.6):
            x += a * np.sin(2 * np.pi * (f + det) * t + rng.uniform(0, 6.28)) * (0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(0.02, 0.06) * t + rng.uniform(0, 6.28)))
    shimmer = bp(pink(n), 3000, 7000) * 0.02 * (1 + np.sin(2 * np.pi * 0.05 * t))
    return lp(x, 2500) * 0.25 + shimmer

LOOP = 48
# Target loudness (LUFS): all loops sit at the same calm level; thunder a little above the rain.
loops = {"amb_rain": rain, "amb_leaves": leaves, "amb_snow": snow, "amb_fireflies": night, "amb_stars": stars}
for name, g in loops.items():
    y = to_lufs(make_loop(g, LOOP), -24, -3)
    save(name, y)
    print(name, round(lufs(y), 1))
for i, near in enumerate([True, False, True]):
    y = to_lufs(thunder(near), -19 if near else -22, -1)
    save(f"thunder_{i + 1}", y, q=4)
    print("thunder", i + 1, round(lufs(y), 1))
