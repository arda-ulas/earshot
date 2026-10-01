# Harness results

[report.md](report.md) holds the recorded results of the regression harness (`harness/`, described in
[harness/README.md](../../harness/README.md)) on the car test set (`testset/car`, format in
[docs/test-set-format.md](../test-set-format.md)). The gated numbers from the same runs are in
[harness/baseline.json](../../harness/baseline.json).

All audio in these results is synthetic speech from macOS `say`, mixed with generated noise. Every
result is evidence `clip`: none of it comes from a person speaking into a microphone, and none of it
was measured on a phone, an emulator or a vehicle. Latency is from the development host named in the
report.

When the author's recordings (`labels-recorded.jsonl`) are run, their results will also be evidence
`clip`, with source `recorded`: a person's voice played from a file, not a live microphone, and not a
test of the app's audio capture. No harness result is `mic`.

## What CI gates

The CI job `harness (text-level gate)` in `.github/workflows/ci.yml` runs on every pull request and
push to `main`:

1. `./gradlew :harness:check`: ktlint and the harness unit tests, including a reference run of the car
   suite that must meet every label.
2. A reference run: `run --suite testset/car --engine reference --lm none`. Each label's own transcript,
   at confidence 1.0, goes through core's `RuleInterpreter` and `Policy` in all three driving states,
   and through core's `TurnEngine` with a simulated vehicle.
3. `compare --allow-partial` against `harness/baseline.json`: the same labels (SHA-256), clip count and
   WER normaliser as the baseline; every decision scored and correct; no false actions; no engine
   errors; no difference between `TurnEngine` and the harness pipeline; and for the
   `reference/none/text` group intent accuracy may not drop by more than 2 points and WER may not rise
   by more than 3 points. `--allow-partial` is there because this job runs only the text group: the
   baseline's audio groups are listed as not compared instead of failing. The job fails on any failed
   gate.

The job downloads no models, uses no audio and builds no native code. It therefore checks the rules
and the policy against the labels, and catches a core change that would turn a labelled refusal into
an action. It does not check:

- speech-to-text (whisper) or the language model (Qwen3); the audio groups stored in the baseline
  are compared only when someone runs them locally;
- noise robustness, or any SNR;
- latency (never gated, only reported);
- multi-turn behaviour: pending confirmations, re-prompt streaks, timeouts. Each clip is one fresh
  exchange; those flows are covered by core's `TurnEngine` tests;
- the Android app, the audio front end, the vehicle gateway or a real vehicle;
- a real voice: `labels-recorded.jsonl` is still pending.

## Reproduce from a clean clone

The reference run needs only a JDK (the build uses the Java 17 toolchain):

```bash
./gradlew :harness:check
./gradlew :harness:run --args="run --suite testset/car --engine reference --lm none --out reports/ref-none"
./gradlew :harness:run --args="compare --baseline harness/baseline.json --report reports/ref-none/report.json"
```

The audio runs need macOS (for `say`), CMake and Ninja (or the Android SDK's CMake 3.31.6), about 0.5 GB
for the models and about 120 MB for the generated audio. Allow roughly 35 minutes on a 2-thread CPU
budget for all of them. Run one engine job at a time:

```bash
scripts/fetch-models.sh                 # whisper tiny.en and Qwen3-0.6B, checked against models/manifest.json
scripts/build-host-engines.sh           # tools/host/build/{whisperhost,lmhost}
scripts/make-testset.sh                 # testset/car/audio: clean, and the suite's noise profiles and SNRs (2 workers)

run() { ./gradlew -q :harness:run --args="run --suite testset/car --threads 2 $*"; }
run --engine reference --lm qwen3 --out reports/ref-qwen3
run --engine whisper --lm qwen3 --snr clean --out reports/whisper-qwen3-clean
run --engine whisper --lm qwen3 --snr 20 --out reports/whisper-qwen3-snr20
run --engine whisper --lm qwen3 --snr 10 --out reports/whisper-qwen3-snr10
run --engine whisper --lm qwen3 --snr 5 --out reports/whisper-qwen3-snr5
```

Then compare all six reports together with the baseline; without `--allow-partial`, a baseline group
that none of them contains fails:

```bash
./gradlew -q :harness:run --args="compare --baseline harness/baseline.json --report reports/ref-none/report.json,reports/ref-qwen3/report.json,reports/whisper-qwen3-clean/report.json,reports/whisper-qwen3-snr20/report.json,reports/whisper-qwen3-snr10/report.json,reports/whisper-qwen3-snr5/report.json"
```

To record a new baseline from a set of runs (they must share the suite, labels, clip count, WER
normaliser and thresholds):

```bash
./gradlew -q :harness:run --args="baseline --report reports/ref-none/report.json,reports/ref-qwen3/report.json,reports/whisper-qwen3-clean/report.json,reports/whisper-qwen3-snr20/report.json,reports/whisper-qwen3-snr10/report.json,reports/whisper-qwen3-snr5/report.json --out harness/baseline.json"
```

Each run writes `report.json` and `report.md` under its `--out` folder (`reports/` is gitignored).
Synthetic audio depends on the macOS version and its voices; `testset/car/audio/manifest.tsv` records
the macOS version, and per noisy file the voice and the clean clip's SHA-256, so two machines can
compare their speech. A different macOS may give slightly different audio and numbers. whisper.cpp and
llama.cpp results can also differ slightly across CPUs.
