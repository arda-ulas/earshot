#!/usr/bin/env bash
# Records the author's own voice for the test set: one clip per utterance in labels-recorded.jsonl.
#
# For each line still tagged "pending-recording", it shows the text, records 16 kHz mono 16-bit WAV to
# testset/<suite>/audio/<audio> (audio/recorded/<id>.wav), plays it back, and on "keep" removes the
# "pending-recording" tag from that line. The harness skips lines that still carry the tag and reports
# how many it skipped; it refuses a labels file in which every line is still pending.
#
# Recording needs one of these command-line recorders:
#   sox  (`rec`)       brew install sox
#   ffmpeg             brew install ffmpeg   (records from the default input device via avfoundation)
# macOS has no built-in command-line recorder. Without either tool, record each phrase with Voice
# Memos or QuickTime Player and import the file; afconvert (built into macOS) converts it:
#   scripts/record-clips.sh --import u01 ~/Desktop/u01.m4a
#
# The recordings are a person's voice: they stay on this machine and are gitignored (testset/*/audio/).
# Say each phrase naturally, as you would to the car. Keep the room quiet: recorded clips are used clean,
# and no noise is mixed into them yet (scripts/make-testset.sh only mixes synthetic clips).
# Harness results from these files are evidence "clip" with source "recorded": a person's voice played
# from a file, not a live-microphone ("mic") test of the app.
#
# Usage:
#   scripts/record-clips.sh [--suite testset/car] [--seconds 4] [--only u07] [--redo]
#   scripts/record-clips.sh [--suite testset/car] --import <utterance, e.g. u01> <audio file>
#   scripts/record-clips.sh [--suite testset/car] --status
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
suite="testset/car"
seconds=4
only=""
redo=0
import_id=""
import_file=""
status=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) suite="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --only) only="$2"; shift 2 ;;
    --redo) redo=1; shift ;;
    --import) import_id="$2"; import_file="$3"; shift 3 ;;
    --status) status=1; shift ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

dir="$root/$suite"
labels="$dir/labels-recorded.jsonl"
[[ -f "$labels" ]] || { echo "no labels-recorded.jsonl in $dir" >&2; exit 1; }

# Prints "<id>\t<audio>\t<pending 0|1>\t<transcript>" for each line.
list_labels() {
  python3 -c '
import json, sys
for l in open(sys.argv[1]):
    if l.strip():
        o = json.loads(l)
        pending = "1" if "pending-recording" in o.get("tags", []) else "0"
        print("\t".join([o["id"], o["audio"], pending, o["transcript"]]))' "$labels"
}

# Removes the pending tag from one line, keeping every other line byte for byte.
mark_recorded() {
  python3 - "$labels" "$1" <<'PY'
import json, os, sys
path, cid = sys.argv[1], sys.argv[2]
lines = open(path).read().splitlines()
out, found = [], False
for l in lines:
    if l.strip():
        o = json.loads(l)
        if o["id"] == cid:
            o["tags"] = [t for t in o.get("tags", []) if t != "pending-recording"]
            l = json.dumps(o, ensure_ascii=False, separators=(", ", ": "))
            found = True
    out.append(l)
if not found:
    sys.exit(f"{cid}: not in {path}")
tmp = path + ".tmp"
with open(tmp, "w") as f:
    f.write("\n".join(out) + "\n")
os.replace(tmp, path)
PY
}

check_wav() {
  python3 - "$1" <<'PY'
import sys, wave
with wave.open(sys.argv[1], "rb") as w:
    fmt = (w.getframerate(), w.getnchannels(), w.getsampwidth())
    secs = w.getnframes() / w.getframerate()
if fmt != (16000, 1, 2):
    sys.exit(f"{sys.argv[1]}: {fmt[0]} Hz, {fmt[1]} ch, {8 * fmt[2]} bit; expected 16000 Hz mono 16-bit")
print(f"  {secs:.1f} s, 16 kHz mono 16-bit")
PY
}

if [[ $status -eq 1 ]]; then
  total=0; pending=0
  while IFS=$'\t' read -r _ _ p _; do total=$((total + 1)); [[ "$p" == 1 ]] && pending=$((pending + 1)); done < <(list_labels)
  echo "recorded: $((total - pending)) of $total; pending: $pending"
  exit 0
fi

if [[ -n "$import_id" ]]; then
  [[ -f "$import_file" ]] || { echo "no such file: $import_file" >&2; exit 1; }
  line="$(list_labels | awk -F'\t' -v u="$import_id" '$1 == u || $1 == u"-author"')"
  [[ -n "$line" ]] || { echo "no label '$import_id' in $labels" >&2; exit 1; }
  IFS=$'\t' read -r id audio _ text <<<"$line"
  out="$dir/audio/$audio"
  mkdir -p "$(dirname "$out")"
  afconvert -f WAVE -d LEI16@16000 -c 1 "$import_file" "$out"
  check_wav "$out"
  mark_recorded "$id"
  echo "$id (\"$text\") imported to ${out#"$root"/}"
  exit 0
fi

if command -v rec >/dev/null; then
  recorder=sox
elif command -v ffmpeg >/dev/null; then
  recorder=ffmpeg
else
  cat >&2 <<'EOF'
No command-line recorder found (sox's `rec` or ffmpeg); macOS has none built in.
Either install one (brew install sox), or record each phrase with Voice Memos or QuickTime Player
and import it, which converts it to 16 kHz mono 16-bit WAV with afconvert:
  scripts/record-clips.sh --import u01 ~/Desktop/u01.m4a
Phrases still to record:
EOF
  list_labels | awk -F'\t' '$3 == 1 { printf "  %s  %s\n", $1, $4 }' >&2
  exit 1
fi

record() { # out
  case "$recorder" in
    # Neither recorder may read standard input: the loop below feeds the label list through it, and
    # ffmpeg reads stdin for its interactive keys by default, which would swallow the remaining lines.
    sox) rec -q -r 16000 -c 1 -b 16 -e signed-integer "$1" trim 0 "$seconds" </dev/null ;;
    ffmpeg) ffmpeg -nostdin -loglevel error -y -f avfoundation -i ":default" -t "$seconds" -ac 1 -ar 16000 -sample_fmt s16 "$1" </dev/null ;;
  esac
}

echo "Recording with $recorder, $seconds s per phrase. The first recording may trigger the macOS microphone permission prompt."
while IFS=$'\t' read -r id audio pending text; do
  [[ -n "$only" && "$id" != "$only" && "$id" != "$only-author" ]] && continue
  [[ "$pending" == 0 && $redo -eq 0 ]] && continue
  out="$dir/audio/$audio"
  mkdir -p "$(dirname "$out")"
  while true; do
    printf '\n%s\n  Say: "%s"\n  Press Enter to start (s = skip, q = quit): ' "$id" "$text"
    read -r answer </dev/tty
    case "$answer" in s) continue 2 ;; q) exit 0 ;; esac
    echo "  recording for $seconds s..."
    record "$out"
    check_wav "$out"
    afplay "$out" </dev/null 2>/dev/null || true
    printf '  Keep it? [y = keep, r = redo, s = skip] '
    read -r answer </dev/tty
    case "$answer" in
      y|Y|"") mark_recorded "$id"; break ;;
      s) rm -f "$out"; continue 2 ;;
      *) ;;
    esac
  done
done < <(list_labels)
"$0" --suite "$suite" --status
