#!/usr/bin/env bash
# Builds the audio for a test set (default: testset/car) from its labels.jsonl. macOS only (uses `say`).
#
#   1. Renders every synthetic label with macOS `say` into audio/<audio>, where <audio> is the label's
#      audio field (clean/<id>.wav for the synthetic labels), as 16 kHz mono 16-bit WAV.
#      Clips tagged "unclear" are spoken fast (rate 330) and quietly, as a mumble.
#   2. Generates the noise profiles named in suite.yaml (`noise:`; this script knows pink, brown and
#      fan) in pure Python with the seeds given there, into noise/. No downloads, no third-party audio.
#   3. Mixes every clean clip with every noise profile at every SNR in suite.yaml (`snr_db:`) into the
#      suite's `noisy_audio` path (audio/snr<snr>/<noise>/<audio>, e.g.
#      audio/snr10/fan/clean/u01-samantha.wav). SNRs and noise names are read from suite.yaml, never
#      hard-coded, so the harness never finds a condition missing because the two disagree.
#
# Every file is written to a temporary name and renamed into place, so a crashed run leaves no
# truncated file behind. Without --force, existing files are kept, except that a noisy mix is made again
# when its clean clip or noise file is newer than it, or the clean clip's SHA-256 differs from the one
# recorded in audio/manifest.tsv.
#
# Everything written here is synthetic speech and is gitignored. Results from it are labelled "clip",
# never "mic". The same macOS version and voices give the same audio; other versions may differ, so
# audio/manifest.tsv records the macOS version and, per noisy file, the voice, the clean clip's SHA-256
# and the mixing parameters.
#
# Usage: scripts/make-testset.sh [--suite testset/car] [--force] [--jobs N (default 2)]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
suite="testset/car"
force=0
jobs=2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) suite="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --jobs) jobs="$2"; shift 2 ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

dir="$root/$suite"
[[ -f "$dir/labels.jsonl" ]] || { echo "no labels.jsonl in $dir" >&2; exit 1; }
[[ -f "$dir/suite.yaml" ]] || { echo "no suite.yaml in $dir" >&2; exit 1; }
command -v say >/dev/null || { echo "macOS 'say' not found; this script only runs on macOS" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

# Check every voice the labels use is installed before rendering anything.
voices="$(python3 -c '
import json, sys
vs = {json.loads(l)["voice"] for l in open(sys.argv[1]) if l.strip() and json.loads(l).get("source") == "synthetic"}
print("\n".join(sorted(vs)))' "$dir/labels.jsonl")"
installed="$(say -v '?' | awk '{print $1}')"
for v in $voices; do
  grep -qx "$v" <<<"$installed" || { echo "voice '$v' is not installed (System Settings > Accessibility > Spoken Content)" >&2; exit 1; }
done

mkdir -p "$dir/noise"

# 1. Render clean clips.
rendered=0
skipped=0
while IFS=$'\t' read -r audio voice unclear text; do
  out="$dir/audio/$audio"
  mkdir -p "$(dirname "$out")"
  if [[ $force -eq 0 && -s "$out" ]]; then skipped=$((skipped + 1)); continue; fi
  tmp="${out%.wav}.partial.wav"  # say picks the container from the extension
  if [[ "$unclear" == 1 ]]; then
    say -v "$voice" -r 330 --data-format=LEI16@16000 -o "$tmp" "[[volm 0.3]] $text"
  else
    say -v "$voice" --data-format=LEI16@16000 -o "$tmp" "$text"
  fi
  mv -f "$tmp" "$out"
  rendered=$((rendered + 1))
done < <(python3 -c '
import json, sys
for l in open(sys.argv[1]):
    if not l.strip():
        continue
    o = json.loads(l)
    if o.get("source") != "synthetic" or not o.get("audio"):
        continue
    unclear = "1" if "unclear" in o.get("tags", []) else "0"
    print("\t".join([o["audio"], o["voice"], unclear, o["transcript"].replace("\t", " ")]))' "$dir/labels.jsonl")
echo "clean clips: $rendered rendered, $skipped already present"

# 2 and 3. Noise generation and mixing.
python3 - "$dir" "$force" "$jobs" <<'PY'
import array
import hashlib
import json
import math
import re
import os
import platform
import random
import subprocess
import sys
import wave
import multiprocessing

suite_dir, force, jobs = sys.argv[1], sys.argv[2] == "1", int(sys.argv[3])
RATE = 16000
NOISE_SECONDS = 30
NOISE_RMS_DBFS = -26.0
audio_dir = os.path.join(suite_dir, "audio")
noise_dir = os.path.join(suite_dir, "noise")


def suite_config(path):
    """snr_db, noise names and seeds, and noisy_audio from suite.yaml (the few keys used; no YAML library)."""
    snrs, pattern, noises, current, section = None, "snr{snr}/{noise}/{audio}", [], None, None
    for raw in open(path):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith((" ", "-")):
            key, _, value = line.partition(":")
            section = key.strip()
            value = value.strip()
            if section == "snr_db":
                m = re.fullmatch(r"\[(.*)\]", value)
                if not m:
                    raise SystemExit("suite.yaml: snr_db must be a list on one line, e.g. [20, 10, 5]")
                snrs = [int(v) for v in m.group(1).split(",") if v.strip()]
            elif section == "noisy_audio":
                pattern = value.strip("\"'")
            continue
        if section != "noise":
            continue
        m = re.match(r"\s*-\s*(?:name:\s*)?([A-Za-z0-9_]+)\s*$", line)
        if m:
            current = {"name": m.group(1)}
            noises.append(current)
            continue
        m = re.match(r"\s+seed:\s*(\d+)\s*$", line)
        if m and current is not None:
            current["seed"] = int(m.group(1))
    if not snrs:
        raise SystemExit("suite.yaml: no snr_db")
    if not noises:
        raise SystemExit("suite.yaml: no noise profiles")
    for n in noises:
        if n["name"] not in GENERATORS:
            raise SystemExit(f"suite.yaml: noise '{n['name']}' has no generator here (known: {sorted(GENERATORS)})")
        if "seed" not in n:
            raise SystemExit(f"suite.yaml: noise '{n['name']}' has no seed")
    if "{audio}" not in pattern:
        raise SystemExit("suite.yaml: noisy_audio must contain {audio}")
    return snrs, {n["name"]: n["seed"] for n in noises}, pattern


def read_wav(path):
    with wave.open(path, "rb") as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
            raise SystemExit(f"{path}: expected 16 kHz mono 16-bit, got "
                             f"{w.getframerate()} Hz, {w.getnchannels()} ch, {8 * w.getsampwidth()} bit")
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        data.byteswap()
    return [float(x) for x in data]


def write_wav(path, samples):
    data = array.array("h", (max(-32768, min(32767, int(round(x)))) for x in samples))
    if sys.byteorder == "big":
        data.byteswap()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".partial"
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())
    os.replace(tmp, path)


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def normalise(x, dbfs):
    rms = math.sqrt(sum(v * v for v in x) / len(x))
    target = 32768.0 * 10 ** (dbfs / 20)
    return [v * target / rms for v in x]


def pink(n, rng):
    # Paul Kellet's refined pinking filter over Gaussian white noise.
    b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
    out = []
    for _ in range(n):
        w = rng.gauss(0.0, 1.0)
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        out.append(b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362)
        b6 = w * 0.115926
    return out


def brown(n, rng):
    # Leaky integrator over white noise, then a one-pole DC blocker (about 5 Hz).
    acc = 0.0
    prev_in = prev_out = 0.0
    r = 1.0 - 2 * math.pi * 5.0 / RATE
    out = []
    for _ in range(n):
        acc = 0.998 * acc + rng.gauss(0.0, 1.0)
        y = acc - prev_in + r * prev_out
        prev_in, prev_out = acc, y
        out.append(y)
    return out


def fan(n, rng):
    # Blade-pass hum at 97 Hz with harmonics 2..6, slow pitch wobble, low-passed broadband air noise,
    # and a slow 0.3 Hz amplitude modulation.
    f0 = 97.0
    phases = [rng.uniform(0, 2 * math.pi) for _ in range(6)]
    amps = [1.0 / (k ** 1.2) for k in range(1, 7)]
    alpha = 1.0 - math.exp(-2 * math.pi * 800.0 / RATE)
    lp = 0.0
    phase = 0.0
    tonal, air = [], []
    for i in range(n):
        t = i / RATE
        f = f0 * (1.0 + 0.004 * math.sin(2 * math.pi * 0.17 * t))
        phase += 2 * math.pi * f / RATE
        tonal.append(sum(a * math.sin(k * phase + p) for k, (a, p) in enumerate(zip(amps, phases), start=1)))
        lp += alpha * (rng.gauss(0.0, 1.0) - lp)
        air.append(lp)
    tonal = normalise(tonal, -3.0)
    air = normalise(air, -6.0)
    return [(tv + av) * (1.0 + 0.1 * math.sin(2 * math.pi * 0.3 * i / RATE)) for i, (tv, av) in enumerate(zip(tonal, air))]


GENERATORS = {"pink": pink, "brown": brown, "fan": fan}
SNRS, SEEDS, PATTERN = suite_config(os.path.join(suite_dir, "suite.yaml"))


def make_noise(name):
    path = os.path.join(noise_dir, f"{name}.wav")
    if not force and os.path.exists(path):
        return name, "present"
    x = GENERATORS[name](RATE * NOISE_SECONDS, random.Random(SEEDS[name]))
    write_wav(path, normalise(x, NOISE_RMS_DBFS))
    return name, "generated"


def previous_hashes():
    """audio -> clean_sha256 from an earlier manifest.tsv, if it has that column."""
    path = os.path.join(audio_dir, "manifest.tsv")
    if not os.path.exists(path):
        return {}
    rows = [l.rstrip("\n").split("\t") for l in open(path) if not l.startswith("#")]
    if not rows or "clean_sha256" not in rows[0]:
        return {}
    col = rows[0].index("clean_sha256")
    return {r[0]: r[col] for r in rows[1:] if len(r) > col}


def active_power(x):
    frame = RATE // 50  # 20 ms
    powers = [sum(v * v for v in x[i:i + frame]) / frame for i in range(0, len(x) - frame + 1, frame)] or [0.0]
    loudest = max(powers)
    if loudest <= 0:
        return 0.0
    active = [p for p in powers if p >= loudest * 10 ** (-30 / 10)]
    return sum(active) / len(active)


_noise = {}
_old_hashes = {}


def init_worker(old_hashes):
    for name in SEEDS:
        _noise[name] = read_wav(os.path.join(noise_dir, f"{name}.wav"))
    _old_hashes.update(old_hashes)


def stale(out, clean_path, noise_path, clean_hash, old_hash):
    if force or not os.path.exists(out):
        return True
    if old_hash is not None and old_hash != clean_hash:
        return True
    mtime = os.path.getmtime(out)
    return os.path.getmtime(clean_path) > mtime or os.path.getmtime(noise_path) > mtime


def mix_clip(item):
    audio, voice = item
    clean_path = os.path.join(audio_dir, audio)
    clean_hash = sha256(clean_path)
    speech = read_wav(clean_path)
    ps = active_power(speech)
    rows = []
    remixed = 0
    clip_id = os.path.splitext(os.path.basename(audio))[0]
    for snr in SNRS:
        for name, noise in _noise.items():
            rel = PATTERN.replace("{snr}", str(snr)).replace("{noise}", name).replace("{audio}", audio)
            out = os.path.join(audio_dir, rel)
            rng = random.Random(f"{clip_id}/{name}/{snr}")
            offset = rng.randrange(len(noise))
            seg = [noise[(offset + i) % len(noise)] for i in range(len(speech))]
            pn = sum(v * v for v in seg) / len(seg)
            gain = math.sqrt(ps / (pn * 10 ** (snr / 10))) if pn > 0 and ps > 0 else 0.0
            mixed = [s + gain * v for s, v in zip(speech, seg)]
            peak = max(abs(v) for v in mixed) or 1.0
            scale = min(1.0, 32000.0 / peak)  # scale speech and noise together, so the SNR is kept
            if stale(out, clean_path, os.path.join(noise_dir, f"{name}.wav"), clean_hash, _old_hashes.get(audio)):
                write_wav(out, [v * scale for v in mixed])
                remixed += 1
            rows.append(f"{audio}\t{voice}\t{clean_hash}\t{name}@{snr}dB\t{name}\t{snr}\t{offset}\t{gain:.5f}\t{scale:.5f}")
    return rows, remixed


if __name__ == "__main__":
    os.makedirs(noise_dir, exist_ok=True)
    print(f"from suite.yaml: SNR {SNRS} dB, noise {SEEDS}, noisy_audio {PATTERN}", flush=True)
    for name in SEEDS:
        print("noise", *make_noise(name), flush=True)
    with open(os.path.join(suite_dir, "labels.jsonl")) as f:
        labels = [json.loads(line) for line in f if line.strip()]
    clean = sorted({(o["audio"], o.get("voice", "")) for o in labels if o.get("source") == "synthetic" and o.get("audio")})
    old = previous_hashes()
    # fork, not spawn: this code is read from stdin, which spawned workers cannot re-import.
    with multiprocessing.get_context("fork").Pool(jobs, initializer=init_worker, initargs=(old,)) as pool:
        results = pool.map(mix_clip, clean, chunksize=4)
    try:
        macos = subprocess.run(["sw_vers", "-productVersion"], capture_output=True, text=True).stdout.strip()
    except OSError:
        macos = platform.platform()
    manifest = os.path.join(audio_dir, "manifest.tsv")
    with open(manifest + ".partial", "w") as f:
        f.write(f"# generated by scripts/make-testset.sh on macOS {macos}; synthetic speech (say) + generated noise\n")
        f.write("audio\tvoice\tclean_sha256\tcondition\tnoise\tsnr_db\tnoise_offset\tnoise_gain\tpeak_scale\n")
        for rows, _ in results:
            for r in rows:
                f.write(r + "\n")
    os.replace(manifest + ".partial", manifest)
    mixed = sum(len(r) for r, _ in results)
    remixed = sum(n for _, n in results)
    print(f"clean clips: {len(clean)}; noisy clips: {mixed} ({len(clean)} x {len(SEEDS)} noise x {len(SNRS)} SNR), {remixed} (re)written")
PY
