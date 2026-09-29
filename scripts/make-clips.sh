#!/usr/bin/env bash
# Generates synthetic test clips with macOS `say` as 16 kHz mono 16-bit WAV in clips/ (not committed).
# These are for exercising the pipeline on the emulator without a microphone. They are synthetic
# speech, not recordings of a person, and results from them are labelled "clip" in traces and in
# docs/manual-test-plan.md, never as live-voice testing.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/clips"
mkdir -p "$out"

voices=("Samantha" "Daniel")

clip() { # id text
  for v in "${voices[@]}"; do
    say -v "$v" --data-format=LEI16@16000 -o "$out/$1-$(echo "$v" | tr "A-Z" "a-z").wav" "$2"
  done
}

clip u1-set-temp-21        "Set the temperature to 21"
clip u2-front-defrost-on   "Turn on the front defrost"
clip u3-how-fast           "How fast am I going?"
clip u4-show-climate       "Show me my climate settings"
clip u5-warmer             "Make it warmer"
clip u6-pizza              "Order me a pizza"
clip u8-never-mind         "Never mind"
clip u9-defrost-off        "Turn off the defrost"
clip u10-freezing          "I'm freezing"
clip yes                   "Yes"
clip no                    "No"
clip fan-3                 "Set the fan to 3"
clip ac-on                 "Turn on the AC"
clip what-gear             "What gear am I in?"
clip temp-35               "Set the temperature to 35"
clip help                  "What can you do?"

# U7: unclear audio. Near-silence (below the audio gate) and a fast whispered mumble.
python3 - "$out/u7-near-silence.wav" <<'PY'
import random, struct, sys, wave
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
random.seed(7)
w.writeframes(b"".join(struct.pack("<h", int(random.gauss(0, 20))) for _ in range(16000 * 2)))
w.close()
PY
say -v Whisper -r 320 --data-format=LEI16@16000 -o "$out/u7-mumble-whisper.wav" "mm the uh fan hmm to the uh"

ls "$out" | wc -l | xargs echo "clips written:"
