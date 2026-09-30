# ADR 0001: Speech-to-text with whisper.cpp tiny.en over JNI

Status: accepted (2026-09-29)

## Context

Earshot turns a short push-to-talk utterance into a command for a simulated vehicle. Phase 1 runs on
the arm64 API 36 phone emulator. The speech-to-text engine has to:

- run on the device with no network. The app has no `INTERNET` permission (SR-20), and voice is
  personal data (TH-6 in [threat-model.md](../threat-model.md)): audio is held in memory and never
  written to storage.
- finish in bounded time. An action that cannot start within 5 s of the end of the utterance is
  discarded (SR-7).
- say how sure it is. The policy allows a vehicle action only when confidence is known and at or
  above 0.5 (SR-1, SR-2).
- build from pinned source with the Android NDK.

The plan gave the engine a 2-3 h timebox. It named sherpa-onnx and Vosk as fallbacks if the first
choice did not work on the emulator in that time.

## Decision

Use whisper.cpp v1.9.4 with the English-only tiny.en model, called over JNI from `:native:whisper`.

- whisper.cpp is C/C++ and runs on arm64 CPUs through its ggml backend. CMake fetches the v1.9.4
  release tarball, pinned by SHA-256, and links it statically into `libearshot_whisper.so` with ggml
  symbols hidden ([ADR 0002](0002-native-module-split.md)). The build is arm64-v8a only, CPU only,
  and targets armv8.2-a with dot product and fp16, which the target emulator reports in
  `/proc/cpuinfo`.
- tiny.en is the default. It is the smaller of the two speech models in the manifest (77.7 MB
  against 148.0 MB for base.en). With tiny.en, speech-to-text alone took 2.2-4.4 s per clip
  (typically 2.2-3.4 s) out of the 5 s action budget (SR-7), measured on the arm64 API 36 emulator
  on an Apple silicon laptop with clip input. base.en was not measured, so it is not known whether
  it fits that budget.
- `models/manifest.json` pins each model's Hugging Face revision, size and SHA-256. base.en is
  listed as optional (`"required": false`) and is fetched only by `scripts/fetch-models.sh --all`.
  The app loads the first speech model in manifest order that passes verification, so base.en is
  used only when tiny.en is missing or fails its check. No base.en results are recorded in this
  phase.
- The whole utterance is transcribed after the button is released. Streaming partial results are not
  used: push-to-talk utterances are short, and the audio buffer is capped at 8 s.
- `AudioGate` runs around whisper. Audio shorter than 0.3 s or quieter than RMS 0.003 is never
  transcribed, because whisper tends to invent text for silence. Non-speech tags in brackets,
  parentheses or asterisks, such as `[BLANK_AUDIO]`, are stripped. Audio the gate rejects, and a
  transcript with nothing spoken left after stripping, get confidence 0, so the assistant asks
  again. When words remain after stripping, whisper's confidence is kept.

### Settings

All timings in this section were measured on the arm64 API 36 emulator on an Apple silicon laptop,
with clip input. They are not in-vehicle or on-phone figures.

| Setting | Value | Why |
|---|---|---|
| Sampling | greedy | Cheapest decoder; one pass per utterance |
| Language | English, no translation | tiny.en is English-only, and so is the command set |
| Output | single segment, no timestamps, no context from earlier turns, non-speech tokens suppressed | One short command per turn; each turn stands alone |
| Temperature fallback | off | With fallback on, a whispered mumble took 77 s |
| Token cap | 48 | Well above a push-to-talk command; bounds decoding on unclear audio |
| Threads | 2 | With 4 threads on the emulator's 4 vCPUs, speech-to-text sometimes stalled for 10-15 s |
| Encoder window | full 30 s (`audio_ctx` left at its default) | Shrinking it saved about 0.5 s but broke short answers ("No. No. No. ...") |
| Time limit | 10 s, in the turn engine | Added after the 77 s mumble to bound a stuck call. The native call is aborted and the assistant asks again |

On that host, a 1-3 s command took about 1.0-1.2 s to encode and 0.25 s to decode, and the
speech-to-text stage took 2.2-4.4 s per clip, typically 2.2-3.4 s.

### Confidence

Confidence is the mean probability of the transcribed text tokens. Special tokens (timestamps,
language, task) are left out. With no text tokens, the native layer reports no confidence, and the
empty transcript then gets confidence 0 from `AudioGate`. Confidence is unknown only when whisper
fails, is aborted, runs past the 10 s limit or throws. The policy treats both 0 and unknown as below
the 0.5 threshold.

This is a weak signal. It measures how sure the decoder is of its own tokens, not whether those words
were said. In M-7 (clip), a near-silent turn first made the assistant ask again (audio gate; whisper
not run). The whispered mumble that followed came back as "I'm gonna be okay." at 0.24, so the
assistant stopped. In an earlier run the same mumble, played on its own, came back as "I'm gonna be
off." at 0.89. It was refused as out of domain: no action, but a refusal where a re-prompt was
intended. Calibrating the threshold is left to the regression harness planned for the next phase.

### Licences

- whisper.cpp is MIT-licensed, copyright the ggml authors
  ([licence API](https://api.github.com/repos/ggml-org/whisper.cpp/license)).
- The models are the tiny.en and base.en ggml conversions from the `ggerganov/whisper.cpp` Hugging
  Face repository, which is tagged MIT and not gated
  ([metadata](https://huggingface.co/api/models/ggerganov/whisper.cpp)). The upstream Whisper README
  states that its code and model weights are released under the MIT License
  ([README](https://github.com/openai/whisper/blob/main/README.md#license),
  [LICENSE](https://github.com/openai/whisper/blob/main/LICENSE)). The separate Transformers-format
  Whisper repositories on Hugging Face are tagged Apache-2.0; this project does not use them.
- The model card lists SHA-1-length checksums, so the manifest pins its own SHA-256 and a fixed
  repository revision. Model files are downloaded by script and never committed.

Checked on 2026-09-29 from published licence files and repository metadata. This is not legal advice.

## Alternatives considered

- **A network speech service.** Ruled out: the app has no network permission, and audio must not
  leave the device.
- **sherpa-onnx or Vosk.** The named fallbacks. Not needed and not evaluated: whisper.cpp transcribed
  a clip on the emulator about 16 minutes into its 2-3 h timebox.
- **base.en as the default.** About twice the size of tiny.en (148.0 MB against 77.7 MB in the
  manifest). Kept as an optional download; not measured.
- **Streaming partial transcripts.** Not needed for short push-to-talk utterances.
- **4 threads, a shorter encoder window, temperature fallback.** Each was in use at some point on the
  emulator and was dropped for the reasons in the settings table.

## Consequences

Good:

- Speech never leaves the device, and the app needs no network permission.
- Decoding work per turn is limited by the token cap and the absence of fallback retries. After 10 s
  the turn engine asks the native call to abort; the call can take longer to return (see below). A
  timeout or an engine error is treated as unclear speech: ask again once, then stop.
- The engine source and the models are pinned by SHA-256. The app re-checks each model before
  loading. If no speech model passes, the assistant is disabled with the reason on screen (SR-12).
  In M-14, with no other valid speech model on the device, one changed byte in tiny.en disabled the
  assistant.

Bad, or not done:

- English only.
- Accuracy on real voices is unknown. Every speech result on the device is `clip` (macOS `say`
  voices, Samantha and Daniel). The `mic` checks, M-13 and M-15, are pending. No noisy-cabin audio
  has been tested.
- Confidence can be high on a wrong transcript (M-7), and the threshold is not calibrated.
- The full 30 s encoder window costs about 0.5 s per turn (measured on the arm64 API 36 emulator on
  an Apple silicon laptop, clip input).
- whisper.cpp checks its abort flag only after the encoder finishes and after each decoder step, not
  during the encoder, so an aborted call does not stop at once. With the laptop heavily loaded (load
  average about 20), the encoder took up to 6 s and some turns hit the 10 s limit. An aborted turn
  ran for up to about 20 s before returning. Those turns asked again as designed.
- Only arm64-v8a is built, on the CPU, assuming dot product and fp16 support. There is no GPU path.
- There is no speaker verification. Anyone speaking while the button is held is transcribed (TH-1 in
  [threat-model.md](../threat-model.md)).
- The JNI layer has no automated test. Unit tests use a scripted fake speech engine; the real engine
  runs only in the manual test plan.
- The model is not in the APK. It is fetched with `scripts/fetch-models.sh` and copied to the device
  with `scripts/push-models.sh`.

## Evidence

- Requirements SR-1, SR-2, SR-7, SR-12 and SR-20 in [requirements.md](../requirements.md); the tests
  and checks that verify them are listed in [traceability.md](../traceability.md).
- Unit tests in `core/src/test/kotlin/io/github/ardaulas/earshot/core/`:
  - [`AudioGateTest`](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/speech/AudioGateTest.kt):
    gate and tag stripping.
  - [`TurnEngineTest`](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/turn/TurnEngineTest.kt):
    U7 unclear audio; speech-to-text that throws or runs past its timeout asks again.
  - [`PolicyTest`](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/policy/PolicyTest.kt):
    below-threshold confidence asks once, then stops.
  - [`AbortableTest`](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/concurrent/AbortableTest.kt):
    cancelling calls the abort hook and waits for a blocking stand-in call to return. The native
    abort itself is not unit-tested.
  - [`ModelGateTest`](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/model/ModelGateTest.kt):
    speech model selection and failure.
- [Manual test plan](../manual-test-plan.md): M-1 to M-12 pass on clips (M-7 with the caveat above)
  and M-14 passes; M-13 and M-15 need a person at the microphone and are pending. Its findings list
  records the 77 s mumble, the thread stalls, the encoder-window revert and the host-load timeouts.
- Code: [`whisper_jni.cpp`](../../native/whisper/src/main/cpp/whisper_jni.cpp),
  [`CMakeLists.txt`](../../native/whisper/src/main/cpp/CMakeLists.txt),
  [`WhisperSpeechEngine.kt`](../../native/whisper/src/main/kotlin/io/github/ardaulas/earshot/whisper/WhisperSpeechEngine.kt),
  [`AudioGate.kt`](../../core/src/main/kotlin/io/github/ardaulas/earshot/core/speech/AudioGate.kt),
  [`ModelGate.kt`](../../core/src/main/kotlin/io/github/ardaulas/earshot/core/model/ModelGate.kt),
  [`models/manifest.json`](../../models/manifest.json).
