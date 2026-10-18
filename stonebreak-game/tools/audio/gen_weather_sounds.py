#!/usr/bin/env python3
"""Generates the weather ambience in stonebreak-game/src/main/resources/sounds/weather/.

rain_loop.wav: a seamless 24 s loop of steady rain, built from
  - a soft wash: white noise band-passed to about 150 Hz - 2.5 kHz, so it hisses
    without the shrill top end of raw white noise;
  - patter: hundreds of tiny drop ticks a second (short decaying tones,
    1.2-3.5 kHz), with a few closer, lower drops on top;
  - slow swells whose periods divide the loop length, so they wrap cleanly.
The tail is crossfaded into the head (equal power), so the loop has no seam.

Also fades in the thunder clips' first 5 ms, which start mid-waveform and click.

Stdlib only, deterministic (fixed seed). Run from anywhere:
    python3 stonebreak-game/tools/audio/gen_weather_sounds.py
"""

import math
import random
import struct
import wave
from pathlib import Path

RATE = 22050
LOOP_SECONDS = 24.0
FADE_SECONDS = 2.0
PEAK = 0.7
OUT = Path(__file__).resolve().parents[2] / "src/main/resources/sounds/weather"


def one_pole(signal, cutoff_hz, highpass=False):
    a = math.exp(-2.0 * math.pi * cutoff_hz / RATE)
    out, y = [], 0.0
    for x in signal:
        y = (1.0 - a) * x + a * y
        out.append(x - y if highpass else y)
    return out


def wash(rng, n):
    white = [rng.uniform(-1.0, 1.0) for _ in range(n)]
    low = one_pole(one_pole(white, 2500.0), 2500.0)  # 12 dB/oct above 2.5 kHz
    return one_pole(low, 150.0, highpass=True)


def patter(rng, n):
    out = [0.0] * n
    seconds = n / RATE

    def drops(per_second, f_lo, f_hi, decay_lo, decay_hi, amp):
        for _ in range(int(per_second * seconds)):
            start = rng.randrange(n)
            freq = rng.uniform(f_lo, f_hi)
            decay = rng.uniform(decay_lo, decay_hi)
            a = amp * rng.paretovariate(3.0) / 1.5  # mostly faint, a few louder
            w = 2.0 * math.pi * freq / RATE
            phase = rng.uniform(0.0, 2.0 * math.pi)
            length = int(decay * 5 * RATE)
            for k in range(min(length, n - start)):
                out[start + k] += a * math.exp(-k / (decay * RATE)) * math.sin(phase + w * k)

    drops(320, 1200.0, 3500.0, 0.002, 0.006, 0.05)  # distant patter
    drops(6, 500.0, 1400.0, 0.008, 0.02, 0.18)       # near drops
    return out


def rain_loop():
    rng = random.Random(0x5241494E)
    n_loop = int(LOOP_SECONDS * RATE)
    n_fade = int(FADE_SECONDS * RATE)
    n = n_loop + n_fade
    base = wash(rng, n)
    drops = one_pole(patter(rng, n), 60.0, highpass=True)
    mix = []
    for i in range(n):
        t = (i % n_loop) / RATE
        swell = 0.88 + 0.07 * math.sin(2 * math.pi * t / 8.0) + 0.05 * math.sin(2 * math.pi * t / 12.0 + 1.3)
        mix.append(swell * (0.8 * base[i] + drops[i]))
    # Crossfade the extra tail into the head: equal power for uncorrelated noise.
    loop = mix[:n_loop]
    for i in range(n_fade):
        g = i / n_fade
        loop[i] = mix[i] * math.sin(0.5 * math.pi * g) + mix[n_loop + i] * math.cos(0.5 * math.pi * g)
    peak = max(abs(x) for x in loop)
    return [x * PEAK / peak for x in loop]


def write(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack("<%dh" % len(samples),
                                  *(int(max(-1.0, min(1.0, x)) * 32767) for x in samples)))


def fade_in_thunder(path, seconds=0.005):
    with wave.open(str(path), "rb") as w:
        params = w.getparams()
        frames = w.readframes(w.getnframes())
    data = list(struct.unpack("<%dh" % (len(frames) // 2), frames))
    n = int(seconds * params.framerate)
    for i in range(min(n, len(data))):
        data[i] = int(data[i] * i / n)
    with wave.open(str(path), "wb") as w:
        w.setparams(params)
        w.writeframes(struct.pack("<%dh" % len(data), *data))


if __name__ == "__main__":
    write(OUT / "rain_loop.wav", rain_loop())
    for name in ("thunder_1.wav", "thunder_2.wav"):
        fade_in_thunder(OUT / name)
    print("wrote", OUT)
