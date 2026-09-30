# Earshot

[![CI](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml/badge.svg)](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml)

Offline push-to-talk voice control for cabin climate, with a safety policy between the models and a
simulated vehicle.

Earshot is an Android app that turns short spoken requests ("set the temperature to 21", "I'm
freezing") into climate actions on a simulated vehicle. Speech-to-text (whisper.cpp) and a small
language model (llama.cpp) run on the device, and the app has no network permission. Neither model can
act on the vehicle: every command goes through one policy function that decides, from the driving state
and the speech-to-text confidence, whether to act, ask for a spoken yes, or refuse. It runs on the
Android phone emulator, not in a car.

## Status

Version 0.2.x, Phase 1 of a larger plan. v0.1.0 added the voice loop with hand-written rules, v0.2.0
the on-device language-model fallback, and v0.2.1 this documentation ([CHANGELOG.md](CHANGELOG.md)).

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

Requirements: macOS or Linux; JDK 17 or newer; Python 3 and `curl` for the scripts; the Android SDK
with NDK 28.2.13676358 and CMake 3.31.6 (pinned in `gradle/libs.versions.toml`); an arm64 API 36
Android emulator, since the native libraries are built for arm64-v8a only. The manual tests used a
`google_apis` arm64-v8a image with 6 GB RAM, 4 vCPUs and host audio input on.

```bash
scripts/fetch-models.sh        # download the models, check size and SHA-256 against models/manifest.json
./gradlew :app:installDebug
scripts/push-models.sh         # copy the models to the app's files directory and restart the app
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

The **developer view** below the button is a test tool, not part of the in-car interface. It sets the
driving scenario and the simulated vehicle connection, shows signals, driving state, climate values
and model status, and (debug builds) plays clips with a caption for screen recordings. After each turn
it shows the trace: input source, transcript and confidence, command and source, verdict, reply and
per-stage timings. Traces are also kept as JSONL in app-private storage (7 daily files); on a debug
build, `adb shell run-as io.github.ardaulas.earshot cat files/traces/<date>.jsonl`.

Unit tests and traceability need no emulator or NDK: `./gradlew :core:check` and
`python3 scripts/traceability.py --check`.

## Evidence and how to read it

**Input labels.** Every trace and every manual test result says where the audio came from.
`clip` is a synthetic WAV from macOS `say`, played through the same pipeline by the debug-only clip
player; it tests the pipeline, not how well the app hears a person. `mic` is a person speaking into
the emulator's microphone. There are no `mic` results yet.

**Manual test plan** ([docs/manual-test-plan.md](docs/manual-test-plan.md)). M-1 to M-12, M-16 and
M-17 pass on clips; M-14 (a damaged speech model disables the assistant) passes and uses no audio.
M-13 (no capture while a reply is spoken) and M-15 (the main cases by live voice) need a person at the
microphone and are pending. A caveat from M-7: a whispered mumble once came back with a high token
probability and was refused as out of domain instead of re-prompted. Token probability is a weak
signal for unclear speech.

**Latency.** Measured on the arm64 API 36 emulator on an Apple silicon laptop, debug build, clip
input. These are not in-vehicle or on-phone figures.

| Stage | Measured |
|---|---|
| whisper tiny.en encoder, 1 to 3 s command | about 1.0 to 1.2 s |
| whisper decoder | about 0.25 s |
| Speech-to-text stage, 2 threads | 2.2 to 4.4 s per clip, typically 2.2 to 3.4 s |
| Speech-to-text of a short answer ("yes") | about 0.6 s |
| Language-model stage, per request | 0.5 to 2.0 s |
| Language-model prompt prefix (409 tokens), once after start-up, in the background | 8.2 s and 5.4 s |

With the laptop heavily loaded (load average around 20), the encoder took up to 6 s and some turns hit
the 10 s speech-to-text timeout, which re-prompted as designed. Whisper checks its abort flag between
encoder passes, so an aborted turn took up to about 20 s to return.

**Unit tests and traceability.** 157 JVM unit tests at v0.2.0 (JUnit and Kotest property tests),
including fault injection and fake-clock timing. Tests carry `@Verifies("SR-n")` for the requirements
in [docs/requirements.md](docs/requirements.md); [docs/traceability.md](docs/traceability.md) is
generated from them and checked in CI. SR-19 is manual (M-13, pending); SR-20 is a build check on the
merged manifest. CI builds the app but does not run the emulator or the models.

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

The shipped model is Qwen3-0.6B (Q4_0, Apache-2.0), 2 threads, 1024-token context, chosen from three
models of at most 1B parameters and two output formats. On a held-out set of 32 typed phrases, written
after prompt tuning, it scored **28/32**, with 14/15 out-of-domain phrases labelled `out_of_domain` and
**0 wrong-direction** answers (warming a driver who is hot, or cooling one who is cold). The first
setup, Qwen2.5-0.5B writing whole commands, scored 3/32 with 5 wrong-direction answers. The four misses:
"I can't feel my fingers" (out of domain), "it's muggy" (cooler), "clear the rear window" (out of
domain) and "good morning" (`query_gear`).

Limits: 32 phrases is a small set, and one phrase is about 3 points. The phrases are typed text, not
speech. Auditors found minor leakage from the seed examples; one seed ("I can't see out the
windshield") is close to one test phrase. The result covers the fallback's interpretation only, not
the fallback together with speech-to-text, noise and the policy.

Method, per-model results and how to reproduce: [docs/lm-eval/README.md](docs/lm-eval/README.md). Decisions:
[ADR 0004](docs/adr/0004-language-model-gating.md) (gating), [ADR 0005](docs/adr/0005-language-model-choice.md) (model choice, licence).

## Safety and security

Earshot has a hazard analysis and threat model informed by ISO 26262 / ISO 21448 / ISO/SAE 21434
concepts. Those standards are written for electrical and electronic systems in series-production road
vehicles ([ISO 26262](https://committee.iso.org/standard/68383.html), latest published edition 2018,
a third edition at [draft stage](https://committee.iso.org/standard/90020.html);
[ISO 21448:2022](https://isopublicstorageprod.blob.core.windows.net/opendata/_latest/iso_deliverables_metadata/json/iso_deliverables_metadata.jsonl);
[ISO/SAE 21434:2021](https://web.archive.org/web/20221220203245/https://www.sae.org/standards/content/iso/sae21434/)).
Earshot is a prototype on a phone emulator, not a vehicle system. No ASIL is assigned, because that
needs vehicle-level context this project does not have. It has not been assessed against any of these
standards and claims no compliance with them. What the code does:

- The policy is a pure function and the only path to a vehicle write. Only five climate properties can
  be written; nothing else can be expressed as a write.
- Unknown or stale driving state is handled as moving, and so is drive or reverse at a standstill.
- While moving or unknown: nothing on screen, replies of at most 12 words, and a spoken yes for
  visibility-reducing commands (defrost off; fan off while the front defrost is on or unknown).
- Confidence below 0.5 or unknown: ask once to repeat, then stop. One write per turn, 1 s timeout,
  no retry; the reply comes from reading the value back. An action that cannot start within 5 s of
  the end of the utterance is discarded.
- Only `RECORD_AUDIO`, no `INTERNET`, only the launcher activity exported (a build check enforces it).
  Audio stays in memory for one turn. Models and native sources are pinned by SHA-256. CI has no secrets.

Known residual risks include: audio played while the button is held can still issue comfort commands
(there is no speaker verification), and traces contain transcripts and are readable with `run-as` on
debug builds.

Details: [docs/safety.md](docs/safety.md) (hazards, safety goals, degradation modes as implemented),
[docs/threat-model.md](docs/threat-model.md), [docs/requirements.md](docs/requirements.md),
[docs/traceability.md](docs/traceability.md), [docs/permissions.md](docs/permissions.md). Decisions:
[0001 speech-to-text](docs/adr/0001-speech-to-text-whisper-cpp.md), [0002 native module split](docs/adr/0002-native-module-split.md),
[0003 fail-safe defaults](docs/adr/0003-fail-safe-defaults.md), [0004 language-model gating](docs/adr/0004-language-model-gating.md),
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

The harness is also where the confidence threshold gets calibrated and where the language-model
fallback is measured together with speech-to-text and noise. Later:

- The Android Automotive emulator, with real driving-state signals (speed, gear) and the car API in
  place of the simulated gateway.
- Climate writes through the car API need a signature|privileged permission
  ([VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds)), so
  they will likely need a privileged install. If that is not possible, writes stay simulated, and the
  docs will say so.
- Gradle dependency verification metadata and an SBOM, planned for a release phase.

## Licence

Earshot's own code is under the MIT licence ([LICENSE](LICENSE)). Third-party components, checked
2026-09-29 from each project's licence file and repository metadata (not legal advice):

| Component | How it is used | Licence |
|---|---|---|
| [whisper.cpp](https://api.github.com/repos/ggml-org/whisper.cpp/license) v1.9.4 | Built from the release tarball, pinned by SHA-256 | MIT (the ggml authors) |
| [llama.cpp](https://api.github.com/repos/ggml-org/llama.cpp/license) v0.5.0 | Built from the release tarball, pinned by SHA-256; core library only, with the common, tools, server and example targets off | MIT (the ggml authors); vendored third-party code in its `vendor/` and `licenses/` directories has its own licences |
| [Whisper tiny.en and base.en, ggml format](https://huggingface.co/api/models/ggerganov/whisper.cpp) | Downloaded by script from a pinned revision; not in this repository | MIT (repository tag; [upstream Whisper](https://github.com/openai/whisper/blob/main/LICENSE) states its code and weights are MIT) |
| [Qwen3-0.6B](https://huggingface.co/api/models/Qwen/Qwen3-0.6B), Q4_0 GGUF from [ggml-org/Qwen3-0.6B-GGUF](https://huggingface.co/api/models/ggml-org/Qwen3-0.6B-GGUF) | Downloaded by script from a pinned revision; not in this repository | Apache-2.0 (licence text in the Qwen/Qwen3-0.6B repository; the GGUF repository carries the tag but no licence file) |

## AI assistance

This project is built with AI coding assistance, directed and reviewed by the author, and
[docs/ai-log.md](docs/ai-log.md) records what was asked, what was produced, what was changed or
rejected, and how it was verified.
