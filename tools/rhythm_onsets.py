#!/usr/bin/env python3
"""量一首歌的拍子，写出节奏草坪谱面用的那张鼓点强度表。

`RhythmCharts` 里的 `ONSETS` 常量**不是手编的**：它由这份脚本从音频文件里量出来。谱面能不能
跟着音乐，全看这张表准不准，所以量的过程必须可复现、可复核——这就是这个脚本存在的理由。

用法（仓库根）：

    python3 tools/rhythm_onsets.py                      # 量内置曲目，打印表与 Java 字面量
    python3 tools/rhythm_onsets.py --check              # 量一遍并与 RhythmCharts.java 里的表比对
    python3 tools/rhythm_onsets.py <别的.ogg> --bpm 128 # 换一首歌量

它做什么：

1. **解码**：ffmpeg 把 ogg 解成 22050 Hz 单声道浮点；
2. **起始包络**：1024 点 STFT（hop 256 ≈ 11.6 ms），取 log 幅度谱的正向差分之和——即 spectral
   flux，音符一敲就有一个峰；
3. **定速与定相**：把包络与各种 BPM 的脉冲串做互相关（含 2/3/4 次谐波加权）选速度，再在
   0.25 ms 的粒度上扫相位，取"每个脉冲附近 ±35 ms 内包络之和"最大的那个偏移。整首歌一起算，
   不是只看开头，所以 109 与 110 这种差 1 BPM 的候选会在中段分道扬镳并被正确地淘汰；
4. **采样**：以 1/4 拍为格、σ=25 ms 的高斯核对包络采样，归一化到 0..15，写成每格一个十六进制
   数字。0 = 这一格没有东西落下去，F = 最响的那一下；
5. **收尾**：末尾静音段（最后一个有声音的小节之后）的格子直接截掉，谱面不许用。

依赖：`ffmpeg` 可执行文件、`numpy`。两者都只是**这个脚本**的依赖，游戏本身不做音频分析。
"""

from __future__ import annotations

import argparse
import array
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TRACK = REPO_ROOT / "pvzce-game/src/main/resources/assets/pvzce/sounds/music/ancient_egypt_ultimate_battle.ogg"
CHARTS = REPO_ROOT / "pvzce-game/src/main/java/com/pvzce/common/level/RhythmCharts.java"

SAMPLE_RATE = 22050
SLOTS_PER_BEAT = 4
GAUSS_SIGMA = 0.025
FFT_SIZE = 1024
HOP = 256


def decode(path: Path) -> "list[float]":
    """The track as mono float samples, through ffmpeg - the one decoder this repo already uses."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SAMPLE_RATE),
         "-f", "f32le", "-"],
        capture_output=True, check=True).stdout
    samples = array.array("f", raw)
    return samples


def onset_envelope(samples) -> "tuple[list[float], float]":
    """Spectral flux and the seconds between frames."""
    import numpy as np

    x = np.frombuffer(samples.tobytes(), dtype=np.float32)
    window = np.hanning(FFT_SIZE).astype(np.float32)
    frames = 1 + (len(x) - FFT_SIZE) // HOP
    index = np.arange(FFT_SIZE)[None, :] + HOP * np.arange(frames)[:, None]
    spectrum = np.abs(np.fft.rfft(x[index] * window, axis=1))
    # Log compression rather than raw magnitude: a loud bass note would otherwise drown out every
    # hit that is not a kick, and the chart needs the hi-hats as much as the downbeats.
    spectrum = np.log1p(10.0 * spectrum)
    flux = np.maximum(np.diff(spectrum, axis=0), 0.0).sum(axis=1)
    return flux - flux.mean(), HOP / SAMPLE_RATE


def fit_tempo(flux, dt: float, low: float = 60.0, high: float = 200.0) -> float:
    """The tempo whose pulse train best matches the whole track, harmonics included."""
    import numpy as np

    correlation = np.correlate(flux, flux, "full")[len(flux) - 1:]
    best, best_score = low, -1e30
    for bpm in np.arange(low, high, 0.01):
        lag = 60.0 / bpm
        score = 0.0
        for harmonic, weight in ((1, 1.0), (2, 0.5), (3, 0.33), (4, 0.25)):
            at = harmonic * lag / dt
            i = int(at)
            if i + 1 >= len(correlation):
                break
            frac = at - i
            score += weight * (correlation[i] * (1 - frac) + correlation[i + 1] * frac) / harmonic
        if score > best_score:
            best, best_score = bpm, score
    return float(best)


def phase_score(flux, dt: float, period: float, offset: float) -> float:
    """How much onset energy sits on a pulse train of this period and offset."""
    import numpy as np

    t = np.arange(len(flux)) * dt
    distance = np.abs(t - offset - np.round((t - offset) / period) * period)
    return float((flux * np.exp(-((distance / 0.035) ** 2))).sum())


def fit_tempo_and_offset(flux, dt: float) -> "tuple[float, float]":
    """The (tempo, first beat) that puts the most of the track's energy on the beat grid.

    <p>The autocorrelation above is only a first guess, and a good one: for a track with a strong
    eighth-note pulse it lands a fraction of a BPM away, which over three minutes is most of a beat.
    What decides is the phase score - energy within 35 ms of a pulse - so the guess is refined by
    measuring it: a hundredth of a BPM at a time over a two-BPM window, each with its own best
    offset. 109 and 110 do not survive that, and neither does anything else that drifts.
    """
    import numpy as np

    guess = fit_tempo(flux, dt)
    t = np.arange(len(flux)) * dt
    best_bpm, best_offset, best_score = guess, 0.0, -1e30
    for bpm in np.arange(guess - 2.0, guess + 2.0, 0.01):
        period = 60.0 / bpm
        for offset in np.arange(0.0, period, 0.002):
            score = phase_score(flux, dt, period, offset)
            if score > best_score:
                best_bpm, best_offset, best_score = float(bpm), float(offset), score
    period = 60.0 / best_bpm
    for offset in np.arange(max(0.0, best_offset - 0.002), best_offset + 0.002, 0.00025):
        score = phase_score(flux, dt, period, offset)
        if score > best_score:
            best_offset, best_score = float(offset), score
    return best_bpm, best_offset


def fit_offset(flux, dt: float, bpm: float) -> float:
    """The first beat's time in the file, searched at 0.25 ms over one beat."""
    import numpy as np

    period = 60.0 / bpm
    best, best_score = 0.0, -1e30
    for offset in np.arange(0.0, period, 0.00025):
        score = phase_score(flux, dt, period, offset)
        if score > best_score:
            best, best_score = offset, score
    return float(best)


def strengths(flux, dt: float, bpm: float, offset: float, slots: int) -> "list[float]":
    """The envelope sampled at every quarter beat with a narrow gaussian."""
    import numpy as np

    period = 60.0 / bpm
    t = np.arange(len(flux)) * dt
    out = []
    for slot in range(slots):
        centre = offset + slot * period / SLOTS_PER_BEAT
        out.append(max(0.0, float((flux * np.exp(-((t - centre) / GAUSS_SIGMA) ** 2)).sum())))
    return out


def table(values, end_slot: int) -> str:
    """0..15 per quarter beat, as the hex string RhythmCharts stores."""
    top = max(values) or 1.0
    return "".join("%X" % max(0, min(15, round(v / top * 15))) for v in values[:end_slot])


def shipped_table() -> str:
    source = CHARTS.read_text(encoding="utf-8")
    body = re.search(r"private static final String ONSETS =(.*?);", source, re.S).group(1)
    return "".join(re.findall(r'"([0-9A-F]*)"', body))


def java_literals(hex_table: str, width: int = 68) -> str:
    return "\n".join('            + "%s"' % hex_table[i:i + width]
                     for i in range(0, len(hex_table), width))


def main(argv) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("track", nargs="?", default=str(DEFAULT_TRACK))
    parser.add_argument("--bpm", type=float, default=None,
                        help="skip the tempo search and use this tempo")
    parser.add_argument("--end-beat", type=float, default=None,
                        help="cut the table here (default: the last beat with any sound)")
    parser.add_argument("--check", action="store_true",
                        help="compare the measurement against RhythmCharts.ONSETS")
    args = parser.parse_args(argv)

    if not Path(args.track).exists():
        print("no such track: %s" % args.track, file=sys.stderr)
        return 2
    print("track   %s" % args.track)
    flux, dt = onset_envelope(decode(Path(args.track)))
    length = len(flux) * dt
    if args.bpm:
        bpm, offset = args.bpm, fit_offset(flux, dt, args.bpm)
    else:
        bpm, offset = fit_tempo_and_offset(flux, dt)
    print("length  %.2f s" % length)
    print("bpm     %.3f%s" % (bpm, " (given)" if args.bpm else " (fitted against the whole track)"))
    print("beat 0  %.4f s = %d ticks" % (offset, round(offset * 60)))
    slots = int((length - offset) / (60.0 / bpm) * SLOTS_PER_BEAT)

    values = strengths(flux, dt, bpm, offset, slots)
    end_slot = slots
    if args.end_beat is None:
        # The file's own run-out: everything after the last quarter beat that carries any sound,
        # rounded up to the end of the bar it falls in. A chart ends on a bar, never mid-beat.
        last = max((i for i, v in enumerate(values) if v > 0.02 * max(values)), default=slots - 1)
        end_slot = min(slots, -(-(last + 1) // (SLOTS_PER_BEAT * 4)) * SLOTS_PER_BEAT * 4)
        print("end     beat %.2f (%.2f s), table cut there" %
              (end_slot / SLOTS_PER_BEAT, offset + end_slot / SLOTS_PER_BEAT * (60.0 / bpm)))
    else:
        end_slot = int(round(args.end_beat * SLOTS_PER_BEAT))
        print("end     beat %.2f (given)" % args.end_beat)

    measured = table(values, end_slot)
    print("slots   %d" % len(measured))
    if args.check:
        shipped = shipped_table()
        if shipped == measured:
            print("check   OK: RhythmCharts.ONSETS is this measurement")
            return 0
        where = next((i for i, (a, b) in enumerate(zip(shipped, measured)) if a != b),
                     min(len(shipped), len(measured)))
        print("check   DIFFERENT: shipped %d chars, measured %d, first difference at %d"
              % (len(shipped), len(measured), where), file=sys.stderr)
        return 1
    print("java literals for RhythmCharts.ONSETS:")
    print(java_literals(measured))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
