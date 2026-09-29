# AI log

This project is built with AI coding assistance, directed and reviewed by the author. The log records,
per piece of work, what was asked, what was produced, what was changed or rejected, and how it was
verified. "Accepted as-is after review" is a valid entry; invented corrections are not.

Tools: Claude Code (Opus 5.5) as the main builder in the terminal, with sub-agents: Fable 5.1 for
opinionated design calls, Sonnet 5 for bulk test writing, and Claude workflows (parallel agents) for
the language-model prompt evaluation and for fact-checking the docs. Nothing is merged without CI green
and a read of the diff.

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
- **Asked:** JVM tests for `:core` against `docs/requirements.md`, with `@Verifies` annotations, fault
  injection and virtual-time timing.
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
- **Verified:** synthetic clips on the emulator, recorded in `docs/manual-test-plan.md` as `clip`.
  Live-microphone checks are left to the author.

### Language-model fallback
- **Found on device:** the parser's regex failed to load on Android (ICU rejects a bare `}`), and
  llama.cpp rejected the multi-line grammar. Both left the fallback refusing everything, which is the
  intended fail-safe, and both were invisible to JVM tests. Fixed, with a test for the grammar layout.
- **Found on device:** Qwen2.5-0.5B mapped "I'm freezing" to 16 °C and "order me a pizza" to AC on.
  The spoken confirmation caught both, but the fallback was not useful.
- **Asked (workflow):** tune the prompt per model and wire format on a dev set, audit each result for
  dev-set leakage, then score on a held-out set written after tuning. See ADR 0005 and the results
  there.
