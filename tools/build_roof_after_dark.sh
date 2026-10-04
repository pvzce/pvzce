#!/usr/bin/env bash
# Put the night roof's own theme (and its drum layer) into the shipped resource pack.
#
# `refer/ms2/` holds the two files as they were handed over; this copies them byte for byte
# rather than re-encoding, because they are the master: a second Vorbis pass would be a second
# generation of the only copy of an original track. What it does add is the check that the two
# are still the same length - the client plays them as two layers of one song, so a re-export of
# one of them that changed its length would silently take the drums out of time.
#
# The drum layer is the same performance without the music: same 44.1 kHz stereo, same
# 6 350 651 samples (144.000 s). See `docs/素材对照表.md` for why this is an original asset.
set -euo pipefail
cd "$(dirname "$0")/.."

dest=pvzce-game/src/main/resources/assets/pvzce/sounds/original
mkdir -p "$dest"

for name in roof_after_dark roof_after_dark_drums; do
  src="refer/ms2/$name.ogg"
  if [ ! -f "$src" ]; then
    echo "missing $src - the night roof's theme lives outside the repository" >&2
    exit 1
  fi
  # 0644: the hand-over copies arrive 0600, and a resource pack that only its owner can
  # read is not one anybody else can build with.
  install -m 0644 "$src" "$dest/$name.ogg"
  echo "$(sha256sum "$dest/$name.ogg" | cut -d' ' -f1)  $name.ogg"
done

python3 - <<'PY'
import struct, sys

def last_granule(path):
    data = open(path, 'rb').read()
    i = 0
    granule = 0
    while i < len(data) - 27:
        if data[i:i + 4] != b'OggS':
            i += 1
            continue
        granule = struct.unpack('<q', data[i + 6:i + 14])[0]
        segments = data[i + 26]
        body = i + 27 + segments
        i = body + sum(data[i + 27:i + 27 + segments])
    return granule

base = 'pvzce-game/src/main/resources/assets/pvzce/sounds/original/'
song = last_granule(base + 'roof_after_dark.ogg')
drums = last_granule(base + 'roof_after_dark_drums.ogg')
print(f'song {song} samples, drums {drums} samples')
if song != drums:
    sys.exit('the drum layer is not the same length as the song; the two would drift')
PY
