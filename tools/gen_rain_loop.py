#!/usr/bin/env python3
"""Make the rain loopable, and long enough that nobody hears where it loops.

The shipped `sounds/sfx/ambient/rain.ogg` is the rip's rain sample: 4.34 seconds of noise that
starts and ends wherever the rip happened to cut it. Played once that is fine; looped under a
level for three minutes it is not, and the player reported exactly that - "雷雨音效会出现断片
的感觉". Two things are wrong with it, and they need different fixes:

* **The seam.** Two samples that were never meant to meet are now adjacent every four seconds.
  This is noise, so the mismatch is not a click - the fix is not "find the phase", it is a
  crossfade: the file's tail is mixed into its own head over `FADE_SECONDS`, so the join is a
  blend of two parts of the same noise instead of a cut. (The levels are also matched first, so
  the fade cannot make a dip where a loud stretch meets a quiet one.)
* **The period.** Four seconds of stationary noise is a *texture* a player recognises within a
  minute, crossfaded or not. So the loop is built out of several permutations of the crossfaded
  base, each played at a slightly different speed - word-level resampling, so the spectrum is
  preserved and only the noise's own "shape" moves - and crossfaded into each other. The result
  is `LOOP_SECONDS` of rain that does not repeat within any stretch a player will hear.

**Why not do it in the engine.** OpenAL loops a buffer, and a buffer is what it is; a runtime
crossfade would mean keeping the rain on two sources and mixing them by hand for a sound whose
only job is to be background. Baking it costs one tool run and 100 KB.

Run from the repository root (needs `ffmpeg` on PATH - it is the only Vorbis encoder here):

    python3 tools/gen_rain_loop.py
"""

from __future__ import annotations

import argparse
import array
import math
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE = (REPO_ROOT / "pvzce-game" / "src" / "main" / "resources" / "assets" / "pvzce"
          / "sounds" / "sfx" / "ambient" / "rain.ogg")

#: The sample rate the untouched rip is decoded at, and the rate written back. One rate all the
#: way through: resampling is what would put a colour on the noise.
SAMPLE_RATE = 22050

#: How much of the loop's tail is mixed into its head. Long enough that neither side of the join
#: is recognisable in the other; short enough to leave most of the sample intact.
FADE_SECONDS = 0.5

#: How long the finished bed is. Twelve seconds is four source lengths - enough permutations that
#: the ear never lands on the same stretch twice in a level.
LOOP_SECONDS = 12.0

#: The speeds the base is played at, in the order they are joined. All near 1: rain has no pitch
#: to be wrong, but a bed whose parts were stretched far would have an audible "swell" where they
#: meet - what is wanted is a different *arrangement* of the same noise, not a different noise.
SPEEDS = (1.0, 0.94, 1.06, 0.97, 1.03, 0.91, 1.09, 0.99)

#: The crossfade between two permutations, in seconds. Shorter than `FADE_SECONDS`: these two are
#: the same noise at slightly different speeds, so they already agree about almost everything.
JOIN_SECONDS = 0.35

#: Word length for the word-level resampler, in samples. About 4.5 ms - long enough that the
#: spectrum is untouched, short enough that the repeats are inaudible in noise.
WORD = 100


def ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe is None:
        sys.exit("ffmpeg is required (it is the only Vorbis encoder this repo has); install it "
                 "and run this tool again")
    return exe


def decode(path: Path) -> array.array:
    raw = subprocess.run(
        [ffmpeg(), "-v", "error", "-i", str(path), "-f", "s16le", "-ac", "1",
         "-ar", str(SAMPLE_RATE), "-"],
        check=True, capture_output=True).stdout
    samples = array.array("h")
    samples.frombytes(raw)
    return samples


def encode(samples: array.array, path: Path) -> None:
    raw = samples.tobytes()
    subprocess.run(
        [ffmpeg(), "-v", "error", "-y", "-f", "s16le", "-ac", "1", "-ar", str(SAMPLE_RATE),
         "-i", "-", "-c:a", "libvorbis", "-q:a", "4", str(path)],
        input=raw, check=True)


def rms(samples: array.array, start: int, length: int) -> float:
    end = min(len(samples), start + length)
    if end <= start:
        return 0.0
    total = sum(float(samples[i]) ** 2 for i in range(start, end))
    return math.sqrt(total / (end - start))


def crossfade(head: array.array, tail: array.array) -> array.array:
    """Mix `tail` into `head` over its length, equal power, and return the blend."""
    length = min(len(head), len(tail))
    out = array.array("h", [0] * length)
    for i in range(length):
        t = (i + 1) / length
        # Equal power rather than linear: two uncorrelated noises mixed linearly dip by 3 dB in
        # the middle of the fade, which is exactly the "breath" the ear picks out as a loop.
        gain_head = math.cos(t * math.pi / 2)
        gain_tail = math.sin(t * math.pi / 2)
        out[i] = clamp(tail[i] * gain_tail + head[i] * gain_head)
    return out


def clamp(value: float) -> int:
    return int(max(-32768, min(32767, round(value))))


def reseed(samples: array.array, speed: float) -> array.array:
    """`samples` played `speed` times as fast, by whole words - no interpolation, so no colour."""
    if abs(speed - 1.0) < 1e-6:
        return array.array("h", samples)
    out = array.array("h")
    position = 0.0
    while True:
        word = int(position)
        if word * WORD + WORD > len(samples):
            break
        take = WORD if speed <= 1.0 else max(1, int(round(WORD / speed)))
        if speed <= 1.0:
            # Slower: the word is heard for longer, so it is repeated.
            out.extend(samples[word * WORD:(word + 1) * WORD])
            extra = max(0, take - WORD)
            if extra:
                out.extend(samples[word * WORD:word * WORD + extra])
        else:
            # Faster: the word is heard for less, so only its first part is kept.
            out.extend(samples[word * WORD:word * WORD + take])
        position += speed
    return out


def join(parts: list[array.array], fade: int) -> array.array:
    """Concatenate `parts`, crossfading each into the next over `fade` samples."""
    out = array.array("h", parts[0])
    for part in parts[1:]:
        head = array.array("h", part[:fade])
        body = part[fade:]
        out[-fade:] = crossfade(head, array.array("h", out[-fade:]))
        out.extend(body)
    return out


def base_loop(source: array.array) -> array.array:
    """The source as something that can be looped: its tail blended into its own head."""
    fade = int(FADE_SECONDS * SAMPLE_RATE)
    if len(source) <= fade * 2:
        sys.exit(f"the source is only {len(source)} samples; it cannot hold a {FADE_SECONDS}s fade")
    head, tail, middle = source[:fade], source[-fade:], source[fade:-fade]
    # Match the two stretches' levels before blending, or a loud tail meeting a quiet head makes
    # a dip in the middle of the fade - audible as a "breath" every time round.
    head_level, tail_level = rms(head, 0, len(head)), rms(tail, 0, len(tail))
    if head_level > 1.0 and tail_level > 1.0:
        gain = head_level / tail_level
        tail = array.array("h", [clamp(sample * gain) for sample in tail])
    loop = array.array("h", middle)
    loop.extend(crossfade(head, tail))
    return loop


def build(source: array.array) -> array.array:
    base = base_loop(source)
    parts: list[array.array] = []
    wanted = int(LOOP_SECONDS * SAMPLE_RATE)
    for speed in SPEEDS:
        parts.append(reseed(base, speed))
        if sum(len(part) for part in parts) >= wanted:
            break
    return join(parts, int(JOIN_SECONDS * SAMPLE_RATE))


def seam(samples: array.array) -> float:
    """The mean absolute step across the loop point, against the sample's own typical step."""
    step = abs(samples[0] - samples[-1])
    typical = sum(abs(samples[i + 1] - samples[i])
                  for i in range(0, len(samples) - 1, 7)) / max(1, len(samples) // 7)
    return step / typical if typical else 0.0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE,
                        help="the rain to loop (default: the shipped one)")
    parser.add_argument("--out", type=Path, default=SOURCE,
                        help="where to write it (default: in place)")
    args = parser.parse_args()

    before = decode(args.source)
    after = build(before)
    encode(after, args.out)
    written = args.out.stat().st_size
    shown = args.out.relative_to(REPO_ROOT) if args.out.is_relative_to(REPO_ROOT) else args.out
    print(f"wrote {shown}: {len(before) / SAMPLE_RATE:.2f}s -> "
          f"{len(after) / SAMPLE_RATE:.2f}s, {written // 1024} KB")
    print(f"loop seam step / typical step: before {seam(before):.2f}, after {seam(after):.2f}")


if __name__ == "__main__":
    main()
