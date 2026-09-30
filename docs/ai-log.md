# AI log

This project is built with AI coding assistance, directed and reviewed by the author. The log records,
per piece of work, what was asked, what was produced, what was changed or rejected, and how it was
verified. "Accepted as-is after review" is a valid entry; invented corrections are not.

Tools: Claude Code (Opus 5.5) as the main builder in the terminal, with sub-agents: Fable 5.1 for
opinionated design calls, Sonnet 5 for bulk test writing, and Claude workflows (parallel agents) for
the language-model prompt evaluation, for fact-checking the docs, and for drafting and reviewing the
docs. Nothing is merged without CI green and a read of the diff.

## 2026-09-29

### Plan
- **Asked:** build Phase 1 of the project plan (on-device assistant) in a new public repo.
- **Produced:** an implementation plan. The author added before any code: a 2-3 h timebox per native
  engine with named fallbacks, release tags after the voice loop and after the language model, an
  input-source label (clip or mic) on every trace and test result, a harness roadmap line in the
  README, a captioned screen recording, and a check that `adb push` into app storage works on API 36.

### Design calls (Fable 5.1 sub-agent)
- **Asked:** the language-model wire format and grammar, the policy structure, and a README pitch.
- **Produced:** a flat JSON wire format with a strict GBNF grammar in which cancel, help, yes/no and
  screen commands have no wire form; a verdict lattice so language-model commands are "raised to at
  least Confirm"; cancel checked before confidence; "fan off while defrosting is visibility-reducing".
- **Changed or rejected:** adopted all of the above. Kept "already at the limit" in the turn engine
  instead of the policy (it is not an action). Kept one threshold for yes and no instead of an
  asymmetric one. The pitch was reworded.
- **Verified:** policy table tests, wire-format tests; on-device runs (below) found two problems the
  design review could not: a regex Android rejects and a grammar llama.cpp cannot parse.

### Core unit tests (Sonnet 5 sub-agent)
- **Asked:** JVM tests for `:core` against [requirements.md](requirements.md), with `@Verifies`
  annotations, fault injection and virtual-time timing.
- **Produced:** 155 tests across policy, interpreter, wire format, driving state, gateway, turn engine,
  responses, models and traces. No main-source changes.
- **Changed:** reviewed before commit. The policy table test computes its expected verdicts with the
  same branching as the code instead of a literal table; noted, not blocking. Later edits by the
  builder: confirmation-expiry tests moved to 10 s, new tests for speech-to-text timeout, audio gate,
  WAV reader, cancellation helper and grammar layout (163 in total at v0.1.0).
- **Verified:** `./gradlew :core:check` locally and in CI; `scripts/traceability.py --check`.

### Speech-to-text, app and on-device testing (builder)
- **Produced:** whisper.cpp module, app, scripts, manual test plan.
- **Changed after measurement on the emulator:** temperature fallback off and a token cap (a mumble
  took 77 s); 2 threads instead of 4 (stalls of 10-15 s); encoder-window shrinking reverted (broke
  "yes"/"no"); confirmation expiry measured at the end of the answer; `ui-tooling` removed after the
  new manifest check found an exported debug activity.
- **Verified:** synthetic clips on the emulator, recorded in [manual-test-plan.md](manual-test-plan.md)
  as `clip`. Live-microphone checks are left to the author.

### Language-model fallback
- **Found on device:** the parser's regex failed to load on Android (ICU rejects a bare `}`), and
  llama.cpp rejected the multi-line grammar. Both left the fallback refusing everything, which is the
  intended fail-safe, and both were invisible to JVM tests. Fixed, with a test for the grammar layout.
- **Found on device:** Qwen2.5-0.5B mapped "I'm freezing" to 16 °C and "order me a pizza" to AC on.
  The spoken confirmation caught both, but the fallback was not useful.
- **Asked (workflow):** tune the prompt per model and wire format on a dev set, audit each result for
  dev-set leakage, then score on a held-out set written after tuning.
- **Produced:** 6 tuning agents, one per model and wire format (Qwen2.5-0.5B-Instruct, Qwen3-0.6B and
  Granite-4.0-350M; `command` and `intent`). Each rewrote only the system prompt and examples against
  the 30-item dev set, in at most 12 runs. Then 6 leakage auditors, one per tuned result. The 32-item
  held-out set was written after tuning and kept out of the tuning agents' folder. Scoring used a host
  program that mirrors the app's llama.cpp path. Held-out results:

  | Setup | Held-out | Out-of-domain correct | Wrong direction |
  |---|---|---|---|
  | First setup: Qwen2.5-0.5B, command format, first prompt | 3/32 | 1/15 | 5 |
  | Qwen3-0.6B, command format, tuned | 25/32 | 15/15 | 0 |
  | Qwen3-0.6B, intent format, tuned (adopted) | 28/32 | 14/15 | 0 |

  The auditors flagged "minor" leakage for every variant, mostly from seed examples shared by all
  starting prompts. One seed ("I can't see out the windshield") is close to a test item. All
  utterances are typed text, not speech, and one item is about 3 points. The full table, the four
  misses and the limits are in [lm-eval/README.md](lm-eval/README.md); the decision is in
  [ADR 0005](adr/0005-language-model-choice.md).
- **Adopted:** Qwen3-0.6B (Q4_0, Apache-2.0, ungated download) with the intent format. The model picks
  one label, and Kotlin maps it to one fixed command. The tuned prompt
  (`variants/intent-qwen3-best.json`) was copied into `LmWireFormat.kt`. This replaced the
  whole-command wire format from the design call. Cancel, help, yes/no, screen, fan and defrost-off
  commands still have no wire form.
- **Changed by the builder:** made the evaluation tooling's paths portable (`bench.py` finds its files
  relative to itself; the host program builds against a llama.cpp source passed in as `LLAMA_SRC`).
  Kept the user's words out of logcat: the native code logs token counts and timings, not the
  transcript or the request. Self-review found that the view model could free the language-model
  engine while the background warm-up was still in a native call; `onCleared` now waits for the
  warm-up as well as the current turn before freeing the engines. The unit-test count went from 163
  to 157 because the intent format needs fewer value cases.
- **Verified:** on the arm64 API 36 emulator on an Apple silicon laptop, with synthetic clips
  (`clip`). M-16 (U10) passes parked and moving: "I'm freezing" -> `warmer` -> "Raise the temperature
  by 2 degrees? Say yes or no."; yes raises it by 2 (read back), no does nothing. M-17 passes:
  "Order me a pizza" -> `out_of_domain` -> refused. M-1 and M-9 are unchanged with the model loaded.
  For the first prompt, the app's output on the emulator matched the host program exactly. A clean
  clone with `scripts/fetch-models.sh` downloaded and hash-verified both models, built, passed the
  unit tests and reproduced the language-model step. The yes-step was not re-run on that build: host
  load pushed speech-to-text past its 10 s timeout, and the app re-prompted as designed.
  Live-microphone checks (M-13, M-15) are pending.

### Maintenance (Dependabot)
- #2 (AGP 9.4.0 to 9.4.1) and #3 (Gradle wrapper 9.7.0 to 9.8.0) merged after re-testing.
- #4 and #5 closed. Their AndroidX updates declare `minCompileSdk=37`, and the project pins
  compileSdk and targetSdk to 36 to match the API 36 emulator. Ignore rules in
  `.github/dependabot.yml` hold those libraries back until the project moves to API 37.

### Docs fact-check (workflow)
- **Asked:** check the external facts the docs rely on: ISO 26262, ISO 21448, ISO/SAE 21434 with UN
  Regulations No. 155 and No. 156, NHTSA's visual-manual driver distraction guidelines, Android
  Automotive driver-distraction APIs, Android Automotive vehicle properties and permissions, and the
  licences of the native engines and the candidate models.
- **Produced:** 7 researcher agents and 7 adversarial verifier agents across these seven topics. Each
  claim carries a source URL. Of 155 claims, 142 were confirmed, 10 corrected, 3 unverifiable and 0
  refuted.
- **Changed or rejected:** the 10 corrected claims are used only in their corrected form. Examples:
  ISO 26262's latest published edition is 2018, with a third edition at draft stage; and Earshot does
  not implement or measure a glance budget, so the NHTSA figures are background only. The 3
  unverifiable claims are not used anywhere in the docs.
- **Verified:** doc writers were told to state external facts only from the confirmed or corrected
  list and to cite its URLs.

### Phase 1 documentation (workflow)
- **Asked:** [safety.md](safety.md) (hazards, safety goals, degradation modes as implemented),
  [threat-model.md](threat-model.md), ADRs 0001 to 0005, this log, and the README.
- **Produced:** one writer agent and one reviewer agent per document, then a consistency pass across
  all documents. Inputs: a brief of what the code does and what was measured, the code itself, and
  the fact-check list above. Rules for every writer: no compliance, certification or ASIL claims;
  `clip` and `mic` labels kept; latency always labelled with the host it was measured on.
- **Changed or rejected:** the builder reads every document before commit. Edits made at that point
  go into the same pull request and are not listed here in advance.
- **Verified:** the reviewers and the consistency pass checked the documents against the brief, the
  code and the fact-check list. CI on the pull request.
