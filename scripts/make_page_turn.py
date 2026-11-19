"""Synthesises the page-turn sound (app/src/main/res/raw/page_turn.wav): a short
paper swish made from filtered noise, so the repo needs no third-party audio."""
import sys
import wave

import numpy as np

RATE = 44_100
rng = np.random.default_rng(7)


def swish(duration, centre, width, peak_at):
    n = int(duration * RATE)
    noise = rng.standard_normal(n)
    # Band-pass by FFT: keep a gaussian band around `centre` Hz.
    spectrum = np.fft.rfft(noise)
    freqs = np.fft.rfftfreq(n, 1 / RATE)
    spectrum *= np.exp(-((freqs - centre) / width) ** 2)
    band = np.fft.irfft(spectrum, n)
    t = np.linspace(0, 1, n)
    envelope = np.where(t < peak_at, t / peak_at, (1 - t) / (1 - peak_at)) ** 2
    # Paper crackle: random amplitude flutter.
    flutter = 0.6 + 0.4 * np.abs(np.convolve(rng.standard_normal(n), np.ones(220) / 220, "same")) * 8
    return band * envelope * np.clip(flutter, 0, 1.5)


sound = np.zeros(int(0.55 * RATE))
a = swish(0.35, 3500, 2500, 0.7)
b = swish(0.25, 6000, 3000, 0.2)
sound[: len(a)] += a
start = int(0.28 * RATE)
sound[start : start + len(b)] += 0.7 * b
sound /= np.max(np.abs(sound))
pcm = (sound * 0.8 * 32767).astype("<i2")

with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(pcm.tobytes())
