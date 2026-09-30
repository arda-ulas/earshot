# Earshot

[![CI](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml/badge.svg)](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml)

Offline push-to-talk voice control for cabin climate, with a safety policy between the models and a
simulated vehicle.

Earshot is an Android app that turns short spoken requests ("set the temperature to 21", "I'm
freezing") into climate actions on a simulated vehicle. Speech-to-text (whisper.cpp) and a small
language model (llama.cpp) run on the device, and the app has no network permission. Neither model
writes to the vehicle. Every command goes through one policy function. That function uses the
driving state and the speech-to-text confidence to decide whether to act, ask for a spoken yes, or
refuse. It runs on the Android phone emulator, not in a car.

## Status

Version 0.2.x, Phase 1 of a larger plan. v0.1.0 added the voice loop with hand-written rules, v0.2.0
the on-device language-model fallback, and v0.2.1 the documentation plus a debug-only caption strip
for screen recordings. Release notes: [CHANGELOG.md](CHANGELOG.md).

- **Exists:** a push-to-talk voice loop running offline on the arm64 API 36 phone emulator;
  hand-written rules, a language-model fallback for indirect requests, and a policy that is the only
  path to the simulated vehicle; unit tests with requirement traceability checked in CI; a manual test
  plan run on synthetic clips.
- **Does not exist yet:** results from a person at the microphone (two checks pending); a regression
  harness (the 0.5 confidence threshold is not calibrated); the Android Automotive emulator, the car
  API, a real vehicle, or any users.

## What it does

English only, Celsius, one climate zone. Temperature 16 to 28 °C (set, or change by 1 to 4 degrees),
fan 0 to 5, front and rear defrost, AC, questions about speed, gear and cabin, "show my climate
settings", cancel, help, and yes or no.

| ID | Request | Driving state | What happens |
|---|---|---|---|
| U1 | "Set the temperature to 21" | Parked | Sets it; the reply is read back from the vehicle and shown on screen |
| U2 | "Turn on the front defrost" | Moving | Turns it on; voice only |
| U3 | "How fast am I going?" | Moving | Speaks the simulated speed; voice only |
| U4 | "Show me my climate settings" | Moving, then parked | Moving: refused with a short spoken summary. Parked: climate panel shown |
| U5 | "Make it warmer" | Any | Raises the temperature by 1 °C, read back |
| U6 | "Order me a pizza" | Any | Refused, no action |
| U7 | Near-silence or a mumble | Any | Asks once to repeat, then stops; no action |
| U8 | "Turn off the defrost", then "Never mind" | Moving | Question cancelled, no action |
| U9 | "Turn off the defrost", then "yes" or "no" | Moving | Asks first; yes turns it off (read back), no does nothing |
| U10 | "I'm freezing", then "yes" or "no" | Parked or moving | Rules miss; the language model picks `warmer`; asks "Raise the temperature by 2 degrees? Say yes or no." |

U1 to U10 pass in the manual test plan on `clip` input only; none has been checked with `mic` yet.

```text
hold the button, or F2 / the voice-assist key (stands in for a steering-wheel button)
  -> AudioRecord: 16 kHz mono, at most 8 s, in memory only
  -> AudioGate: too short or too quiet -> asks to repeat, nothing transcribed
  -> whisper.cpp tiny.en: transcript + confidence (mean token probability)
  -> RuleInterpreter (hand-written)
       '-> on a confident rule miss only: llama.cpp, Qwen3-0.6B, one intent label, strict parse
  -> Policy: pure function, the single decision point
  -> TurnEngine -> SimulatedVehicleGateway: one write, then read the value back
  -> Android text-to-speech: reply built from the read-back value; no capture while speaking
```

## What is simulated and what is not

| Part | In this phase | Not in this phase |
|---|---|---|
| Vehicle | `SimulatedVehicleGateway`, in the app process: climate values, speed and gear | No real vehicle, no car API, no car permissions |
| Driving | Scripted scenarios: Parked; City drive (park, then drive, up to 50 km/h); Signal lost (50 km/h, signals stop at 5 s) | No real driving-state signals |
| Platform | Android phone emulator, API 36, arm64 | Not the Android Automotive emulator yet |
| Voice input | The emulator's microphone (host audio input), or synthetic clips in debug builds | No live-microphone results yet |
| Models | whisper.cpp and llama.cpp run on the emulator, offline | No cloud service of any kind |
| People | None | No drivers, no users, no user study |

## Quick start

Requirements for building and unit tests: macOS or Linux (CI runs on Ubuntu); a JDK 17 installation
(the `:core` toolchain; no toolchain download is configured, and Gradle itself can run on a newer
JDK, CI uses 21); the Android SDK with NDK 28.2.13676358 and CMake 3.31.6 (pinned in
`gradle/libs.versions.toml`); Python 3, `curl` and `shasum` for the scripts.

Running the app needs an arm64 API 36 Android emulator, because the native libraries are built for
arm64-v8a only (armv8.2-a with dot product and fp16). It has only been run on an Apple silicon
Mac. The manual tests used a `google_apis` arm64-v8a image with 6 GB RAM, 4 vCPUs and host audio
input on.

```bash
scripts/fetch-models.sh        # download the models, check size and SHA-256 against models/manifest.json
./gradlew :app:installDebug
scripts/push-models.sh         # copy the models to the app's external files directory and restart the app
```

The models take about 500 MB: whisper tiny.en (78 MB) and Qwen3-0.6B Q4_0 (429 MB). `--all` also
fetches whisper base.en (148 MB), which the app loads only if tiny.en fails its check. The app checks
every model's size and SHA-256 again before loading it. Without a valid speech model the assistant is
disabled and says why on screen; without a valid language model only the fallback is off. The app
downloads nothing.

Then hold the button (or F2) and speak. On an emulator without a microphone, use synthetic clips
(macOS only, they use `say`):

```bash
scripts/make-clips.sh          # 16 kHz WAVs in clips/, voices Samantha and Daniel; not committed
scripts/push-models.sh --clips
```

The **developer view** below the button is a test tool, not part of the assistant's own interface.
It sets the driving scenario and the simulated vehicle connection, shows signals, driving state,
climate values and model status, and (debug builds) plays clips with a caption for screen
recordings. After each turn it shows the trace: input source, transcript and confidence, command and
source, verdict, reply and per-stage timings. Traces are also kept as JSONL in app-private storage
(7 daily files); on a debug build,
`adb shell run-as io.github.ardaulas.earshot cat files/traces/<date>.jsonl`.

Unit tests and traceability need no emulator and build no native code: `./gradlew :core:check` and
`python3 scripts/traceability.py --check`. Gradle still configures the Android modules for these
tasks. CI runs them on Ubuntu with the Android SDK installed; a run without the SDK has not been
tried.

## Evidence and how to read it

**Input labels.** Every trace and every manual test result says where the audio came from.
`clip` is a synthetic WAV from macOS `say` (plus one generated near-silence file). The debug-only
clip player reads it from a file and hands it to the turn engine in place of captured audio, so it
skips `AudioRecord` and the emulator's microphone. It tests everything after capture, not how well
the app hears a person. `mic` is a person speaking into the emulator's microphone. There are no
`mic` results yet.

**Manual test plan** ([docs/manual-test-plan.md](docs/manual-test-plan.md)). On the rules-only
build, M-1 to M-12 pass on clips, and M-14 (a damaged speech model disables the assistant) passes
and uses no audio. With the language model loaded, M-16 and M-17 pass on clips, and M-1 and M-9 were
re-run with unchanged results; the other cases were not re-run. M-13 (no capture while a reply is
spoken) and M-15 (the main cases by live voice) need a person at the microphone and are pending.

A caveat from M-7, on the rules-only build: a whispered mumble once came back with a high token
probability (0.89) and was refused as out of domain instead of re-prompted. With the fallback, such
a transcript now goes to the language model; any command the model picks still needs a spoken yes.
Token probability is a weak signal for unclear speech.

**Latency.** Measured on the arm64 API 36 emulator on an Apple silicon laptop, debug build, clip
input. These are not in-vehicle or on-phone figures.

| Stage | Measured |
|---|---|
| whisper tiny.en encoder, 1 to 3 s command | about 1.0 to 1.2 s |
| whisper decoder | about 0.25 s |
| Speech-to-text stage, 2 threads | 2.2 to 4.4 s per clip, typically 2.2 to 3.4 s |
| Speech-to-text of a short answer ("yes") | about 0.6 s |
| Language-model stage, per request | 0.5 to 2.0 s |
| Language-model prompt prefix (409 tokens), once after start-up, in the background | 8.2 s and 5.4 s (two runs) |

With the laptop heavily loaded (load average around 20), the encoder took up to 6 s and some turns
hit the 10 s speech-to-text timeout, which re-prompted as designed. whisper.cpp checks its abort flag
only after the encoder finishes and after each decoder step, so an aborted turn took up to about
20 s to return.

**Screen recording.** This release has none. With `adb shell screenrecord` running on the same
emulator, speech-to-text took 5.3 to 7.6 s for short commands, so the 5 s action budget discarded
the actions (see [docs/manual-test-plan.md](docs/manual-test-plan.md)). Debug builds keep a caption
strip for a later recording.

**Unit tests and traceability.** 160 JVM unit tests run at v0.2.1 (157 at v0.2.0; JUnit and Kotest property tests),
including fault injection and fake-clock timing. Tests carry `@Verifies("SR-n")` for the
requirements in [docs/requirements.md](docs/requirements.md);
[docs/traceability.md](docs/traceability.md) is generated from them and checked in CI. The check
confirms that each requirement has an annotated test, not that the test ran. The docs review found
two tests in `AbortableTest.kt` (one tagged SR-11) that JUnit never ran because their bodies returned
a value; they are fixed in v0.2.1 and now run. SR-19 is manual (M-13, pending); SR-20 is a build check on the merged manifest. CI
builds the app but does not run the emulator or the models.

## The language-model fallback

- It runs only when speech-to-text is confident and the hand-written rules do not match.
- It picks one of ten intent labels (`warmer`, `cooler`, `ac_on`, `ac_off`, `defrost_front_on`,
  `defrost_rear_on`, `query_speed`, `query_gear`, `query_cabin`, `out_of_domain`) as
  grammar-constrained JSON, parsed strictly in Kotlin. Kotlin maps each label to one fixed command;
  `warmer` and `cooler` change the temperature by 2 degrees.
- Every action or query it proposes goes through the policy and needs a spoken yes. `out_of_domain`
  is refused.
- It cannot produce cancel, help, yes/no, screen, fan or defrost-off commands, and its text never
  reaches text-to-speech. A timeout (10 s), an error or invalid output means "not understood": no
  action.

The shipped model is Qwen3-0.6B (Q4_0, Apache-2.0), 2 threads, 1024-token context. It was chosen from
three models of at most 1B parameters and two output formats. On a held-out set of 32 typed phrases,
written after prompt tuning, it scored 28/32. It labelled 14 of the 15 out-of-domain phrases
`out_of_domain` and gave no wrong-direction answers (warming a driver who says they are hot, or
cooling one who says they are cold). These results come from a host program that mirrors the app's
llama.cpp path (`docs/lm-eval/lmhost`). They are typed-text results, not `clip` or `mic`.

The untuned first setup (Qwen2.5-0.5B, first prompt, writing whole commands) scored 3/32 with 5
wrong-direction answers. After tuning, the closest alternatives were Qwen3-0.6B writing whole
commands (25/32) and Qwen2.5-0.5B writing whole commands (20/32), also with no wrong-direction
answers. The shipped model's four misses: "I can't feel my fingers" (out of domain), "it's muggy"
(cooler), "clear the rear window" (out of domain) and "good morning" (`query_gear`).

Limits: 32 phrases is a small set, and one phrase is about 3 points; treat differences of one or two
phrases as noise. The phrases are typed text, not speech. A separate AI agent audited each tuned
prompt for leakage and flagged minor leakage in every variant, mostly from seed examples shared by
all starting prompts; one seed ("I can't see out the windshield") is close to one test phrase. Five
of the six tuned prompts, but not the shipped one, contain one or two test phrases word for word as
examples (for example "turn on the headlights" in the Qwen3 command prompt), and those phrases scored
correct, so the alternatives' scores above include them. The result covers the fallback's
interpretation only, not the fallback together with speech-to-text, noise and the policy.

Method, per-model results and how to reproduce the Qwen3 and Granite runs:
[docs/lm-eval/README.md](docs/lm-eval/README.md). Decisions:
[ADR 0004](docs/adr/0004-language-model-gating.md) (gating),
[ADR 0005](docs/adr/0005-language-model-choice.md) (model choice, licence).

## Safety and security

Earshot has a hazard analysis and threat model informed by ISO 26262 / ISO 21448 / ISO/SAE 21434
concepts. All three standards are written for electrical and electronic systems in
series-production road vehicles:

- ISO 26262 (functional safety): the latest published edition is from 2018
  ([ISO](https://committee.iso.org/standard/68383.html)). A third edition was at the draft (DIS)
  stage when checked in September 2026 ([ISO](https://committee.iso.org/standard/90020.html)).
- ISO 21448:2022 (safety of the intended functionality, SOTIF)
  ([ISO open data, a large JSONL file](https://isopublicstorageprod.blob.core.windows.net/opendata/_latest/iso_deliverables_metadata/json/iso_deliverables_metadata.jsonl)).
- ISO/SAE 21434:2021 (cybersecurity engineering)
  ([ISO page, archived](https://web.archive.org/web/20260927121109/https://www.iso.org/standard/70918.html);
  [SAE summary, archived](https://web.archive.org/web/20221220203245/https://www.sae.org/standards/content/iso/sae21434/)).

Earshot borrows vocabulary and practice from them (hazards, safety goals, requirements traced to
tests) and uses the SOTIF idea only as a design lens. It is a prototype on a phone emulator, not a
vehicle system. No ASIL is assigned, because that needs vehicle-level context this project does not
have. It has not been assessed against any of these standards and claims no compliance with them.
What the code does:

- The policy is a pure function, and a vehicle write happens only on its verdict. Today there is one
  call to `VehicleGateway.write`, in `TurnEngine`; review keeps it that way, not an automated check.
  Only five climate properties can be written; nothing else can be expressed as a write.
- Unknown or stale driving state is handled as moving, and so is drive or reverse at a standstill.
- While moving or unknown: no assistant output on screen, replies of at most 12 words, and a spoken
  yes for visibility-reducing commands (defrost off; fan off while the front defrost is on or
  unknown). Status text, the developer view and the debug recording caption still show; a result
  shown while parked is removed when the car starts moving (see [docs/safety.md](docs/safety.md)).
- Confidence below 0.5 or unknown: ask once to repeat, then stop. One write per turn, 1 s timeout,
  no retry; the reply comes from reading the value back. An action that cannot start within 5 s of
  the end of the utterance is discarded.
- `RECORD_AUDIO` is the only system permission. AndroidX core also adds its own signature-level
  permission (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). There is no `INTERNET`, and only the
  launcher activity is exported; a build check on the merged manifest enforces these two. Audio
  stays in memory for one turn. Models and native sources are pinned by SHA-256. CI has no secrets.

Known residual risks include the following. Audio played while the button is held can issue comfort
commands and queries. While parked it can issue any allowlisted command the rules match, including
defrost off, with no confirmation, and a recorded "yes" counts as a confirmation. There is no
speaker verification. Traces contain transcripts and are readable with `run-as` on debug builds.

Details: [docs/safety.md](docs/safety.md) (hazards, safety goals, degradation modes as implemented),
[docs/threat-model.md](docs/threat-model.md), [docs/requirements.md](docs/requirements.md),
[docs/traceability.md](docs/traceability.md), [docs/permissions.md](docs/permissions.md).
Decisions ([index](docs/adr/README.md)):
[0001 speech-to-text](docs/adr/0001-speech-to-text-whisper-cpp.md),
[0002 native module split](docs/adr/0002-native-module-split.md),
[0003 fail-safe defaults](docs/adr/0003-fail-safe-defaults.md),
[0004 language-model gating](docs/adr/0004-language-model-gating.md),
[0005 language-model choice](docs/adr/0005-language-model-choice.md).

## Project layout

| Path | Contents |
|---|---|
| `core/` | Pure Kotlin/JVM, no Android imports: commands, rules, language-model wire format, policy, turn engine, simulated vehicle, traces, model checks, and all unit tests |
| `native/whisper/` | whisper.cpp v1.9.4 over JNI, in its own Android library and `.so` |
| `native/llama/` | llama.cpp v0.5.0 over JNI, in its own Android library and `.so` (both vendor ggml; see ADR 0002) |
| `app/` | Compose UI (single activity), audio capture, text-to-speech, developer view |
| `models/` | `manifest.json` only: pinned revision, size, SHA-256 and licence per model |
| `scripts/` | `fetch-models.sh`, `push-models.sh`, `make-clips.sh`, `traceability.py` |
| `docs/` | Safety, threat model, requirements, test plan, ADRs, language-model evaluation, AI log |

## Roadmap

Next: a regression harness measuring false actions and policy correctness.

Also planned: a captioned screen recording of the demo. The debug build already has a pinned caption
strip for it; an attempt in this phase failed because screen recording on the emulator slowed
speech-to-text past the 5 s action budget (see [docs/manual-test-plan.md](docs/manual-test-plan.md)).

The harness is also where the confidence threshold gets calibrated and where the language-model
fallback is measured together with speech-to-text and noise. Later:

- The Android Automotive emulator, reading speed and gear through the car API (values from the
  emulator's vehicle HAL, still emulated), with the car API in place of the simulated gateway.
- Climate writes through the car API need a signature|privileged permission
  ([VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds)), so
  they will likely need a privileged install. If that is not possible, writes stay simulated, and the
  docs will say so.
- Gradle dependency verification metadata and an SBOM, planned for a release phase.

## Licence

Earshot's own code is under the MIT licence ([LICENSE](LICENSE)). Native libraries and models,
checked 2026-09-29 from each project's licence file and repository metadata (not legal advice). The
Kotlin and AndroidX libraries the app also ships are not covered by this table.

| Component | How it is used | Licence |
|---|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE) v1.9.4 | Built from the source archive of the v1.9.4 tag, pinned by SHA-256 | MIT (the ggml authors) |
| [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE) v0.5.0 | Built from the source archive of the v0.5.0 tag, pinned by SHA-256; core library only, with the common, tools, server and example targets off | MIT (the ggml authors); vendored third-party code in its `vendor/` and `licenses/` directories has its own licences |
| [Whisper tiny.en and base.en, ggml format](https://huggingface.co/ggerganov/whisper.cpp) | Downloaded by script from a pinned revision; not in this repository | MIT (repository tag; [upstream Whisper](https://github.com/openai/whisper/blob/main/LICENSE) states its code and weights are MIT) |
| [Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B/blob/main/LICENSE), Q4_0 GGUF from [ggml-org/Qwen3-0.6B-GGUF](https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF) | Downloaded by script from a pinned revision; not in this repository | Apache-2.0 (licence text in the Qwen/Qwen3-0.6B repository; the GGUF repository carries the tag but no licence file) |

## AI assistance

This project is built with AI coding assistance, directed and reviewed by the author.
[docs/ai-log.md](docs/ai-log.md) records what was asked, what was produced, what was changed or
rejected, and how it was verified.
