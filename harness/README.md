# Regression harness

Runs a labelled test set through the app's decision path (rules, the language-model fallback, the
policy) on a development machine, scores it, and compares the result with a baseline.

It calls core's `RuleInterpreter`, `LmInterpreter`, `LmWireFormat`, `Policy` and `TurnEngine`. One
part is copied rather than called: the routing between the stages (which result goes to the language
model, which to the policy) is repeated in `Pipeline.interpret`, so each stage can be scored and timed
on its own. That copy is checked on every decision: the same transcript also goes through core's
`TurnEngine` with a simulated vehicle, and any difference in command, command source, verdict or
vehicle writes is counted and gated (see Gates). A core change that would let the two drift apart
fails the harness instead of passing unnoticed. A core function that exposes `TurnEngine`'s
interpretation step on its own would remove the copy; that is a core change and has not been made.

What it does not do: capture audio, drive a real vehicle or the car property API, speak, or run on a
phone. Each clip is one fresh exchange. Latency comes from the development host and says nothing about
a phone or an in-car computer.

## Commands

Run from the repository root (paths are relative to it):

```bash
# Text only: the labels' own transcripts at confidence 1.0. No models; this is what CI can run.
./gradlew :harness:run --args="run --suite testset/car --engine reference --lm none --out reports/ref-none"

# Speech-to-text and the language model through the host binaries (scripts/build-host-engines.sh).
./gradlew :harness:run --args="run --suite testset/car --engine whisper --lm qwen3 --snr all --out reports/whisper-qwen3"

# Gates against the baseline, for one or more reports together; exit code 1 when a gate fails.
./gradlew :harness:run --args="compare --baseline harness/baseline.json --report reports/ref-none/report.json,reports/whisper-qwen3/report.json"

# Keep one or more reports' gated numbers as the new baseline.
./gradlew :harness:run --args="baseline --report reports/ref-none/report.json,reports/whisper-qwen3/report.json --out harness/baseline.json"

# Load and check a test set without running it.
./gradlew :harness:run --args="validate --suite testset/car"
```

Options for `run`: `--labels FILE` (default `labels.jsonl`), `--snr all|clean|20|10|5` or a comma list
such as `clean,5` (default `all`: the clean clips and every SNR in `suite.yaml`; a number must be one
of the suite's SNRs; a text source ignores it), `--threads N` (default 2, like the app), `--models DIR`
(default `models`), `--whisper-bin`, `--whisper-model`, `--lm-bin`, `--lm-model`, `--lm-timeout-ms`
(default 10000, like the app).

Options for `compare`: `--allow-partial` lists baseline groups the reports lack, and conditions a run
skipped, as notes instead of failing; `--allow-new-groups` does the same for report groups that have no
baseline group. Without them every gap fails. CI's text-only job uses `--allow-partial`.

Before a model is used, its size and SHA-256 are checked against `models/manifest.json` with core's
`ModelVerifier`. The report records each model's hash and what each host binary printed for `--info`.

## Labels

A labels file is loaded and checked as a whole: every problem is reported with its line number. Lines
tagged `pending-recording` (the author's voice, not recorded yet) are checked, then left out; the count
is printed and written to the report header. A file in which every line is pending is refused.

## Pipeline per clip

1. A transcript source gives text and a confidence: `reference` (the label's transcript, 1.0) or
   `whisper` (`tools/host/build/whisperhost/whisperhost` on the clip's WAV file).
2. `RuleInterpreter` runs on the text. A rule rejection (`RuleResult.Rejected`: negated, a question
   that is not a supported query, an unsupported target, more than one action, an unsupported unit,
   words outside the command vocabulary) goes to the policy as out of domain, as in `TurnEngine`; the
   language model is never asked.
3. On a rule miss, and only if the confidence is at or above the threshold, `LmInterpreter` runs
   (with `--lm qwen3`, through `lmhost`; the spec and grammar are written from `LmWireFormat` on every
   run).
4. An out-of-range value with a confident transcript stops here, as in the app: the verdict is
   recorded as `OutOfRange`. With an unclear transcript it goes on as out of domain.
5. `Policy.decide` runs once per driving state (`PARKED`, `MOVING`, `UNKNOWN`) with the engine's
   confidence, `repromptsSoFar = 0`, no confirmation pending, and the front defrost state from
   `suite.yaml` (`context.front_defrost`: `on`, `off` or `unknown`; not stated means `unknown`, which
   the policy handles as on, the same fail-safe as the app when the property cannot be read).
6. The same transcript goes through core's `TurnEngine` in each driving state: a fresh engine (no
   pending confirmation), a frozen clock (so the 5 s action budget never runs out), core's
   `SimulatedVehicleGateway` at its default climate with every write counted, the front defrost read
   as the suite's context (unreadable for `unknown`), and the language model's answer from step 3
   replayed rather than asked again.

A text source runs once (condition `text`). An audio source runs on the clean clips and then on every
noise profile at every SNR whose files exist. A noisy condition with no files is listed as skipped
(and fails `compare`); a half-generated one is an error. Noisy files are found with `noisy_audio` from
`suite.yaml` (default `snr{snr}/{noise}/{audio}`, where `{audio}` is the label's path, e.g.
`clean/u01.wav`).

## Metrics

- **WER**: word-level edit distance over reference words, summed over the clips, after the harness's
  own normaliser `harness-wer-1` (`WerNormaliser`): lower case, apostrophes dropped, other
  punctuation and hyphens separate words (a decimal point between digits and a minus sign before a
  number are kept), number words from zero to ninety-nine as digits. It deliberately does not use
  core's `TextNormalizer`, whose synonym folding ("defogger" to "defrost", "temp" to "temperature")
  serves the rules and may change with them. The normaliser's id is stored in the report and the
  baseline; `compare` fails when they differ.
- **Intent / slot accuracy**: the command class is right / the whole command with its values is right.
  A `reject` clip is right when no command comes out; its slots are right when it is the rejection the
  label's `reason` names: `OutOfRange` for `out_of_range`, a rule rejection for `rejected`, otherwise
  out of domain. An `lm` clip is right when the rules miss it, and, when the model ran and the label
  names commands (`lm_intent`), when the model produced one of them.
- **Policy correctness**: every decision (clip and driving state) is scored, on one of three bases,
  and the report gives the count for each:
  - `label`: the transcript was confident and the command exactly the labelled one. The verdict must
    be in the label's set for that state. `Refuse` in a label matches any refusal;
    `Refuse(OUT_OF_DOMAIN)` only that one. When the rules missed a misheard command and the language
    model produced the right one, the label's verdicts are raised to at least `Confirm` with core's
    `Verdict.atLeast`, because the policy confirms every model command (SG-7). For an `lm` clip the
    label's verdicts are used exactly when the model produced one of its commands; with `--lm none`
    (a rule miss is out of domain) the only correct verdict is `Refuse(OUT_OF_DOMAIN)`.
  - `unclear`: the transcript was below the confidence threshold. Whatever the label says, the
    verdict must be `Reprompt` (`Stop` for a cancel), as core's policy precedence requires with
    `repromptsSoFar = 0` (SG-1).
  - `table`: the transcript was confident but the command was not the labelled one, so the label
    cannot speak for it. The verdict must be what `PolicyTable`, a table written out in the harness
    from `docs/safety.md` and the precedence on core's `Policy`, gives for the command actually
    produced. It is an oracle, not a call to `Policy`, so a change in core's policy that disagrees
    with it fails the gate. A test checks that it agrees with every car label.
- **False actions**: decisions where the pipeline reached a vehicle write that the label does not
  expect in that state: an `Allow` or `AllowVoiceOnly` verdict on a temperature, fan, defrost or AC
  command, or a write that core's `TurnEngine` actually made to the simulated vehicle, when the label
  does not expect that same command with an allowing verdict. Any write at all on an unconfident
  transcript is a false action, whatever the label says. Only real actions count: a confirmation
  question, a refusal, a re-prompt, a stop, an out-of-range answer, a query, the screen and
  conversation commands never do. A spoken yes acts only on a pending confirmation, which the harness
  never has; multi-turn flows are tested in core's `TurnEngine` tests, not here.
- **Wrong confirmations**: `Confirm` verdicts on a write command the label does not expect (not its
  command, not one of its acceptable model commands). Nothing is written, but one spoken "yes" would
  act. The subset that goes the opposite way to the label (warmer where the label wants cooler or the
  AC on, or the reverse) is counted as wrong direction. Reported for every group, not gated.
- **TurnEngine mismatches**: decisions where core's `TurnEngine` produced a different command, command
  source, verdict, or number of writes than the harness pipeline.
- **Errors**: transcript errors from the speech-to-text source, and language-model calls that timed
  out or failed (an invalid answer is the model's own output and is not an error).
- **Latency**: p50 and p95 per stage. `stt` and `lm` are what the host binaries report; `rules` and
  `policy` are measured in the JVM. Always shown with the host: OS, CPU, logical cores, engine threads.

Every report also records the labels file and its SHA-256, the clip count, the WER normaliser, the
thresholds, and the code: the git commit, whether the working tree differed from it, and the git tree
hashes of `core/src/main`, `harness/src/main` and the suite folder as they were when the run started.
A commit contains exactly the code that produced a report when `git rev-parse <commit>:<dir>` gives the
same hash.

## Gates (`compare`)

Fixed, for every group (transcript source, language-model setting, condition): every decision scored
and all of them correct, no false actions, no engine or language-model errors, no TurnEngine
mismatches.

Against the baseline: the reports must be for the same suite, clip count, labels file and labels
SHA-256, and the same WER normaliser; the baseline's thresholds are applied, and a report whose
thresholds are looser (or whose confidence threshold differs) fails. Every baseline group must appear
in the reports, every report group must have a baseline group, and no condition may have been skipped,
unless `--allow-partial` or `--allow-new-groups` says otherwise. For each group, intent accuracy may
not drop by more than `max_intent_drop_points` (2) and WER may not rise by more than
`max_wer_rise_points` (3), in percentage points. Latency and wrong confirmations are reported, not
gated. `policy_correctness` and `false_action_rate` in `suite.yaml` can only restate the fixed values;
anything looser is a load error.

## What the harness has found so far

The recorded runs are in [docs/harness/report.md](../docs/harness/report.md), with their gated numbers
in `harness/baseline.json`. In short: no false actions and no TurnEngine mismatches in any group; the
language model still asks some wrong confirmation questions, a few of them in the wrong direction (for
example "It's really stuffy in here" misheard as "It's really stuff in here." becomes `warmer`). With
core at `75b4a37`, which strips trailing punctuation before the model, the correctly heard sentence
no longer does. See the report for the numbers and what they do not show.

## Evidence labels

Every report says `"evidence": "clip"`: a WAV file or a label's text is fed in, never a live
microphone. Clip counts by `source` are in the report header. `synthetic` clips are macOS `say`
speech. `recorded` clips are the author's voice played from a file; they are still evidence `clip`
with source `recorded`, not `mic`, and they do not test the app's audio capture. No harness result is
`mic`.

## Tests

`./gradlew :harness:check` runs ktlint and the unit tests: WER and its normaliser, percentiles, the
false-action and wrong-confirmation definitions, policy scoring on all three bases (including a policy
stub that acts on an unconfident transcript and must fail the gates), the policy table against every
car label, `TurnEngine` agreement on the car suite with and without a language model, acceptable-verdict
sets, the gates and their opt-outs, the loaders (including pending recordings and the car suite's own
labels, and a reference run of the car suite that must meet every label), the host-process protocol
(with shell-script stand-ins for the binaries) and the CLI on the tiny suite in
`src/test/resources/fixture`.
