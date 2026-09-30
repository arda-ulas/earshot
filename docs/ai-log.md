# AI log

This project is built with AI coding assistance, directed and reviewed by the author. The log records,
per piece of work, what was asked, what was produced, what was changed or rejected, and how it was
verified. "Accepted as-is after review" is a valid entry; invented corrections are not.

Tools: Claude Code (Opus 5.5) is the main builder, in the terminal. Sub-agents: Fable 5.1 for
opinionated design calls and Sonnet 5 for bulk test writing. Claude workflows (parallel agents) ran
the language-model prompt evaluation, the docs fact-check, and the drafting and review of the docs.
Nothing reaches `main` without a pull request and CI green. Each entry says who checked the work and
how.

## 2026-09-29

### Plan
- **Asked:** build Phase 1 of the project plan (on-device assistant) in a new public repo.
- **Produced:** an implementation plan.
- **Changed:** the author added, before any code:
  - a 2-3 h timebox per native engine, with named fallbacks;
  - release tags after the voice loop and after the language model;
  - an input-source label (clip or mic) on every trace and test result;
  - a harness roadmap line in the README;
  - a captioned screen recording;
  - a check that `adb push` into app storage works on API 36.

### Design calls (Fable 5.1 sub-agent)
- **Asked:** the language-model wire format and grammar, the policy structure, and a README pitch.
- **Produced:** a flat JSON wire format with a strict GBNF grammar in which cancel, help, yes/no and
  screen commands have no wire form; a verdict lattice so language-model commands are "raised to at
  least Confirm"; cancel checked before confidence; "fan off while defrosting is visibility-reducing".
- **Changed or rejected:** adopted all of the above; the wire format was later replaced (see
  Language-model fallback). Rejected two other suggestions: putting "already at the limit" in the
  policy (kept in the turn engine; it is not an action) and asymmetric yes/no thresholds (kept one).
  The pitch was reworded.
- **Verified:** policy table tests, wire-format tests; emulator runs (below) found two problems the
  design review could not: a regex Android rejects and a grammar llama.cpp cannot parse.

### Core unit tests (Sonnet 5 sub-agent)
- **Asked:** JVM tests for `:core` against [requirements.md](requirements.md), with `@Verifies`
  annotations, fault injection and virtual-time timing.
- **Produced:** 155 tests across policy, interpreter, wire format, driving state, gateway, turn engine,
  responses, models and traces. No main-source changes.
- **Changed:** reviewed before commit. The policy table test computes its expected verdicts with the
  same branching as the code instead of a literal table; noted, not blocking. Later edits by the
  builder: confirmation-expiry tests moved to 10 s, new tests for speech-to-text timeout, audio gate,
  WAV reader and the cancellation helper (163 in total at v0.1.0).
- **Found in the review of this log:** the two cancellation-helper tests in `AbortableTest` never run.
  Their bodies return a value (Kotest's `shouldBe` returns its receiver), and JUnit skips test methods
  that do not return Unit. They are not in the 163 or 157 counts. SR-11 also has tests that do run.
  Fixed in v0.2.1 (block bodies); both now run and pass (160 tests).
- **Also found in the docs review:** a malformed WAV header could crash the debug clip player.
  Fixed in v0.2.1: `WavReader` checks the fmt chunk length, bounds the sample rate and channel count,
  and turns any parse failure into an `IOException`, with a test that truncates a WAV at every length.
- **Verified:** `./gradlew :core:check` locally and in CI; `scripts/traceability.py --check`.

### Speech-to-text, app and emulator testing (builder)
- **Produced:** whisper.cpp module, app, scripts, manual test plan.
- **Changed after measurement on the arm64 API 36 emulator on an Apple silicon laptop, with synthetic
  clips (`clip`):**
  - temperature fallback off and a token cap, after a whispered-mumble clip took 77 s in whisper;
  - two threads instead of four: with 4 threads on 4 vCPUs, speech-to-text sometimes stalled for
    10-15 s, and with 2 it typically took 2.2-3.4 s;
  - encoder-window shrinking reverted: it saved about 0.5 s but broke "yes" and "no";
  - confirmation expiry now measured to the end of the spoken answer (10 s window), so slow
    speech-to-text no longer counts against the driver;
  - `ui-tooling` removed because it exported a preview activity in debug builds; a merged-manifest
    build check now enforces SR-20.
- **Verified:** synthetic clips on the emulator, recorded in [manual-test-plan.md](manual-test-plan.md)
  as `clip`. Live-microphone checks are left to the author.

### Language-model fallback
- **Found on the emulator:** llama.cpp rejected the multi-line grammar, which left the fallback
  refusing every indirect request, the intended fail-safe. The parser's regex also failed to load on
  Android (ICU rejects a bare `}` that the desktop JVM accepts), so the parser class could not load.
  JVM tests could see neither problem. Both are fixed; the grammar layout now has a test.
- **Found on the emulator (`clip`):** with the first setup, Qwen2.5-0.5B mapped "I'm freezing" to
  16 °C and "order me a pizza" to AC on. The spoken confirmation caught both, but the fallback was not
  useful.
- **Asked (workflow):** tune the prompt per model and wire format on a dev set, then audit each result
  for dev-set leakage.
- **Produced:** 6 tuning agents, one per model and wire format (Qwen2.5-0.5B-Instruct, Qwen3-0.6B and
  Granite-4.0-350M; `command` and `intent`). Each rewrote only the system prompt and examples against
  the 30-item dev set, in at most 12 runs. The two Qwen3 agents also added an empty think block after
  the assistant marker, which `bench.py` allows and the app adopted. Then 6 leakage auditors, one per
  tuned result.
- **Held-out scoring (builder):** the builder wrote the 32-item held-out set while the tuning agents
  ran, in a folder outside the one they worked in, and scored it only after tuning finished. Scoring
  used a host program that mirrors the app's llama.cpp path. Held-out results:

  | Setup | Held-out correct (of 32) | Out-of-domain items refused (of 15) | Wrong-direction answers |
  |---|---|---|---|
  | First setup: Qwen2.5-0.5B, command format, first prompt | 3 | 1 | 5 |
  | Qwen3-0.6B, command format, tuned | 25 | 15 | 0 |
  | Qwen3-0.6B, intent format, tuned (adopted) | 28 | 14 | 0 |

  The other four tuned variants (Qwen2.5-0.5B and Granite-4.0-350M, both formats) scored 16/32 to
  20/32; the two Granite variants gave 2 and 3 wrong-direction answers.

  Limits. The auditors flagged "minor" leakage for every variant, mostly from seed examples shared by
  all starting prompts; this inflates the dev scores. Five of the six tuned prompts also contain one or
  two held-out utterances word for word as examples, and each of those items scored correct. For
  example, "turn on the headlights" is an example in the Qwen3 command prompt above, so its 25/32 and
  15/15 include an item the prompt already answers. The adopted intent prompt contains no held-out
  utterance. Two of its examples are close to test items: "I can't see out the windshield" (test: "I
  can't see through the windshield") and "I'm freezing" (test: "it's freezing in this car"). All
  utterances are typed text, not speech. Each held-out item is about 3 percentage points, so
  differences of one or two items are noise; the adopted variant leads Qwen3 in command format by
  three items. Its one out-of-domain miss was "good morning" -> `query_gear`, which would be asked back
  as "Tell you your gear? Say yes or no." before anything happens. The full table, the four misses and
  the limits are in [lm-eval/README.md](lm-eval/README.md); the decision is in
  [ADR 0005](adr/0005-language-model-choice.md).
- **Adopted:** Qwen3-0.6B with the intent format. The file is the Q4_0 GGUF from
  `ggml-org/Qwen3-0.6B-GGUF`, tagged Apache-2.0; the licence text is in the upstream `Qwen/Qwen3-0.6B`
  repo; the download is ungated (details in ADR 0005). The model picks one of ten labels, and Kotlin
  maps it to one fixed command. The tuned prompt
  ([lm-eval/variants/intent-qwen3-best.json](lm-eval/variants/intent-qwen3-best.json)) was copied into
  `LmWireFormat.kt`. This replaced the whole-command wire format from the design call. Cancel, help,
  yes/no and screen commands still have no wire form; fan changes, turning a defroster off and exact
  temperatures now have none either.
- **Changed by the builder:** made the evaluation tooling's paths portable (`bench.py` finds its files
  relative to itself; the host program builds against a llama.cpp source passed in as `LLAMA_SRC`).
  Kept the user's words out of logcat: the native code logs the fixed prompt prefix, token counts,
  timings and mean token probability, never the transcript, the request or the model's answer.
  Self-review found that the view model could free the language-model engine while the background
  warm-up was still in a native call. `onCleared` now waits for the warm-up as well as the current
  turn before freeing the engines. The unit-test count went from 163 to 157 because the intent format
  needs fewer value cases.
- **Verified:** on the arm64 API 36 emulator on an Apple silicon laptop, with synthetic clips
  (`clip`). M-16 (U10) passes: "I'm freezing" -> `warmer` -> "Raise the temperature by 2 degrees? Say
  yes or no." Moving + yes raised it from 21 to 23 (read back, voice only); parked + no did nothing.
  M-17 passes for the clip that reached the model: "Order me a pizza" -> `out_of_domain` -> refused.
  The other voice's clip was heard below the confidence threshold and re-prompted, so the model was
  not asked. Both phrases are dev-set items and "I'm freezing" is a prompt example, so these runs
  check the app path, not held-out accuracy. M-1 and M-9 are unchanged with the model loaded. The app
  on the emulator and the host program gave the same outputs for the phrases compared: "I'm freezing"
  and "order me a pizza" with the first setup, and the M-16 and M-17 phrases with the adopted prompt
  (against the host's dev-set results). The held-out scores come from the host program only. A clean
  clone with `scripts/fetch-models.sh` downloaded and SHA-256-verified the two required models
  (whisper tiny.en and Qwen3-0.6B Q4_0), built, passed the unit tests and reproduced the
  language-model step. The yes-step was not re-run on that build: host load pushed speech-to-text past
  its 10 s timeout, and the app re-prompted as designed. Live-microphone checks (M-13, M-15) are
  pending.

### Maintenance (Dependabot)
- #2 (AGP 9.4.0 to 9.4.1) and #3 (Gradle wrapper 9.7.0 to 9.8.0) merged with CI green.
- #4 and #5 closed. Their AndroidX updates declare `minCompileSdk=37`, and the project pins
  compileSdk and targetSdk to 36 to match the API 36 emulator. The builder added ignore rules to
  `.github/dependabot.yml` that hold those libraries back until the project moves to API 37.

### Captioned screen recording (builder)
- **Asked:** a captioned screen recording for the README, if cheap.
- **Produced:** a caption strip in debug builds only, pinned above the scrolling content. It shows
  what the clip says, the spoken reply and the driving state (`RecordingCaption` in
  `AssistantScreen.kt`). `scripts/make-clips.sh` writes `captions.tsv`, and `scripts/push-models.sh`
  copies it to the device. In release builds, `ClipProvider` returns no clips and no captions.
- **Rejected:** the recording itself. With `adb shell screenrecord` running, speech-to-text on the
  arm64 API 36 emulator on an Apple silicon laptop took 5.3-7.6 s for short commands (`clip`). The 5 s
  action budget (SG-5) then discarded the actions, and longer turns hit the 10 s speech-to-text
  timeout. That is the design working, but not a useful demo, so this release has no recording.
- **Verified:** the timings come from that attempt, recorded in
  [manual-test-plan.md](manual-test-plan.md). The caption strip has no automated test.

### Docs fact-check (workflow)
- **Asked:** check the external facts the docs rely on: ISO 26262, ISO 21448, ISO/SAE 21434 with UN
  Regulations No. 155 and No. 156, NHTSA's visual-manual driver distraction guidelines, Android
  Automotive driver-distraction APIs, Android Automotive vehicle properties and permissions, and the
  licences of the native engines and the candidate models. The Android Automotive topics are
  background only: the app runs on the phone emulator, does not use the `android.car` APIs and holds
  none of these permissions.
- **Produced:** 7 researcher agents and 7 adversarial verifier agents across these seven topics. Of
  155 claims, 142 were confirmed, 10 corrected, 3 unverifiable and 0 refuted. Each kept claim carries
  a source: a URL for all but one, a correction about Earshot's own screen rule, which cites this
  repo's [requirements.md](requirements.md). Claims that rest on inference are labelled as such, for
  example that an ordinary third-party app cannot be granted `CONTROL_CAR_CLIMATE`. The list itself
  is not committed; the docs cite the sources they use inline.
- **Changed or rejected:** the 10 corrected claims are used only in their corrected form. Examples:
  ISO 26262's latest published edition is 2018, and a third edition was at the DIS (draft) stage when
  checked in September 2026 ([ISO](https://committee.iso.org/standard/90020.html)); Earshot does not
  implement or measure a glance budget, so the NHTSA figures are background only. The 3 unverifiable
  claims were left out of the list given to the doc writers.
- **Verified:** writers could state external facts only from the confirmed or corrected list, with its
  sources. A search of the docs while this log was written found none of the 3 unverifiable claims.

### Phase 1 documentation (workflow)
- **Asked:** [safety.md](safety.md) (hazards, safety goals, degradation modes as implemented),
  [threat-model.md](threat-model.md), ADRs 0001 to 0005, this log, and the README.
- **Produced:** one writer agent per document, then two independent reviewer agents. Inputs: a brief
  of what the code does and what was measured, the code itself, and the fact-check list above. Rules
  for every writer: no compliance, certification or ASIL claims; `clip` and `mic` labels kept;
  latency always labelled with the host it was measured on. A final agent checked numbers, IDs,
  claims and links across these documents, fixed the mismatches it found, and added an index of the
  ADRs ([adr/README.md](adr/README.md)).
- **Changed or rejected:** reviewer findings went back to the writer, who checked each one against the
  sources before applying or skipping it. For this log, the review found overstated or unlabelled
  claims (held-out leakage, when the held-out set was written, what M-16 covered, missing host and
  clip labels, a test credited to the wrong release) and a missing entry for the screen recording.
  All were corrected. Pending before merge: the author's read of the final documents, and any edits
  it leads to.
- **Verified:** the reviewers checked the documents against the brief, the code and the fact-check
  list. CI on the pull request: pending.

## 2026-09-30

### Android Automotive and the car API (builder)
- **Asked:** run the app on the Android Automotive emulator, read speed and gear through the car API,
  honour the platform's UX restrictions, and try real climate writes within a 2 h timebox (simulated
  otherwise), keeping `:core` free of Android imports.
- **Produced:** the `:vehicle:car` module (`CarPropertyGateway`, signal mapping, a fake car for JVM
  tests), app wiring, manifest entries, ADRs 0006 and 0007, the emulator scripts
  (`aaos-scenario.sh`, `install-privileged.sh`, `drive-clips.py`) and manual test rows A-1 to A-11.
- **Changed:** permission protection levels, property ids and area ids were read from the emulator
  image and the SDK stub rather than assumed; the first gear hook (`car_service set-property-value`)
  did not work and was replaced by the vehicle HAL's debug `--set`; speed injection was overwritten
  by the emulator and replaced by the HAL's fake-data generator. The privileged install was narrowed
  to one permission and later to emulators only.
- **Verified:** unit tests on a fake car; on the emulator, climate values read with
  `dumpsys` on the vehicle HAL before and after each command (A-3 to A-7), and the permission grant
  read from `dumpsys package`.

### Hostile review of v0.2.1 and fixes (external review, builder)
- **Asked:** the author supplied an external hostile review of v0.2.1 made with another AI tool
  (15 P1, 8 P2) and asked for every P1 to be fixed with a regression test that reproduces it, then a
  hostile re-review before tagging.
- **Produced:** two rounds of fixes (interpretation, confirmation, driving state and deadline,
  capture, native close, model storage, traces, manifest check), each P1 with a named regression
  test; corrected requirements, safety notes, threat model and README.
- **Changed:** the first round was re-reviewed by the same external tool and judged partial for
  seven findings, with new findings in the Automotive code (for example park gear with a NaN speed
  resolving to parked, and a curly apostrophe bypassing the negation check). The second round
  addressed those. Where a finding could not be fixed in code (signal loss on the Automotive
  emulator), the docs say so instead.
- **Verified:** each regression test drives the scenario named in the review (input text, signal
  timeline or call sequence) through the real classes, with fakes only at the edges; `:core`, `:vehicle:car` and the manifest-check tests green locally; the Automotive rows re-run on
  the emulator after the second round. The final re-review result is recorded in the release notes.

### Regression harness (workflow and agents)
- **Asked:** build the Phase 2 harness: a CLI reusing `:core`, a labelled car test set with synthetic
  voices and generated noise, host builds of the pinned engines, metrics, a CI gate (policy 100%, no
  false actions) and a committed report.
- **Produced:** a Claude workflow (builder, integrator, two adversarial reviewers, fixer) and then
  a single agent produced the `:harness` module, the test set, host engines, baseline, report and CI
  job, in a separate worktree.
- **Changed:** the reviewers found that low-confidence decisions were never scored (a policy that
  acted on unclear speech would have passed) and that the policy gate could pass with nothing
  scored; both fixed with tests. Labels were rewritten twice as the core changed. The harness's own
  findings led to core fixes (language-model input punctuation, unsupported features and negated
  requests reaching the model); the harness did not edit core. The author's builder session reviewed
  the diff, checked it for banned names and binaries, and re-ran the text gate on the final core.
- **Verified:** `:harness:check`, the reference run and `compare` on the release branch; the audio
  runs (about 25 minutes on the host) were run by the agent on the core before the last three-word
  rule change, and the report says so.
