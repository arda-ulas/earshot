# Changelog

All results below come from emulators (the arm64 API 36 phone emulator with a simulated vehicle, and
from v0.3.0 the Android Automotive 15 arm64 emulator with emulated vehicle HAL values) and synthetic
test clips unless stated otherwise. Nothing here has run in a vehicle.

## v0.3.0 (2026-10-01): Android Automotive, and fixes from a hostile review

- Android Automotive emulator: a new `:vehicle:car` module reads speed and gear through the car API
  (`CarPropertyGateway`), with stale, NaN or missing speed handled as unknown. The platform's UX
  restrictions are combined with the app's own driving state, stricter wins (ADR 0007). Climate
  writes go through the car API only when `CONTROL_CAR_CLIMATE` is granted, which on the emulator
  needs the privileged install in `scripts/install-privileged.sh` (emulator only); otherwise climate
  stays simulated (ADR 0006). `:core` stays free of Android imports.
- Manual test plan rows A-1 to A-11 on the Automotive emulator (`clip` input): 10 pass, signal loss
  not reproducible with the image's hooks. The phone path is unchanged.
- An external hostile review of v0.2.1 found 15 P1 and 8 P2 issues, and a re-review of the first fixes
  found more. Each P1 has a regression test. Main changes:
  - Rules refuse negated, questioning, multi-request, conflicting, unsupported-target and
    unsupported-unit requests, more than one number, unknown symbols, and words outside the action
    vocabulary; signed or fractional numbers are out of range, never rounded to a valid value; "3
    degrees warmer" is a change of 3, never a set-point. Refused requests never reach the language
    model.
  - A zero speed with no gear reading is unknown, not parked; a relative change from a temperature
    already outside 16-28 °C does nothing; a write whose wait timed out is reported as unconfirmed.
  - A spoken yes only confirms the pending question it answers, and only after the question was
    delivered, and an answer that started before the question was delivered is not accepted; a new
    command drops the pending one.
  - The driving state is re-checked right before each write, after the read-back values are taken;
    the 5 s budget from the end of the utterance is also enforced by the vehicle gateway before its
    first write.
  - The driving-state resolver rejects negative, NaN, backwards and future samples and gaps; park
    with a non-zero or unknown speed is unknown.
  - Model files are copied to app-private storage and checked there before loading; a trace write
    failure no longer drops the turn result; traces are bounded by age and size; audio capture is
    bounded per utterance and released on lifecycle loss; native calls are safe against close during
    a call; screen output is shown only while parked and removed when driving starts; replies use an
    offline voice only; the merged-manifest check is a tested Python script.
- Requirements, safety notes, threat model and README corrected where the review showed the text
  claimed more than the code did.

## v0.2.1 (2026-09-29): Phase 1 documentation

- Docs: `docs/safety.md` (hazards, safety goals, degradation modes as implemented), `docs/threat-model.md`,
  ADRs 0001-0005 (speech-to-text, native module split, fail-safe defaults, language-model gating,
  language-model choice), a full README, and the AI log. Written with AI assistance, reviewed per document
  for accuracy and claim boundaries, and read before merge.
- Fixes found by the docs review: two cancellation tests never ran (JUnit skips methods that return a
  value) and now run; `WavReader` no longer crashes the debug clip player on a malformed header. 160 unit
  tests.
- Debug builds only: a pinned caption strip for screen recordings (what a synthetic clip says, the reply,
  the driving state), read from `clips/captions.tsv`. No recording ships: on the emulator, screen
  recording slowed speech-to-text past the 5 s action budget.
- Dependabot ignores AndroidX updates that need compileSdk 37.

## v0.2.0 (2026-09-29): on-device language-model fallback

- llama.cpp v0.5.0 over JNI in its own module, with the fixed prompt decoded once and kept in the KV
  cache, GBNF-constrained greedy sampling, and cancellation that aborts generation.
- Qwen3-0.6B (Q4_0, Apache-2.0) picks one intent label (`warmer`, `cooler`, `ac_on`, `ac_off`,
  `defrost_front_on`, `defrost_rear_on`, three queries, `out_of_domain`); Kotlin maps each label to one
  fixed command. Chosen on a held-out set of indirect requests (28/32, no wrong-direction answers);
  the first setup (Qwen2.5-0.5B writing whole commands) scored 3/32. Method and limits in
  `docs/lm-eval`.
- The fallback runs only on a confident rule miss; every command it produces needs a spoken yes; it
  cannot produce cancel, help, yes/no, screen, fan or defrost-off commands; timeouts and invalid output
  mean "not understood".
- Fixed on device: a parser regex Android's ICU engine rejected, and a grammar llama.cpp could not
  parse. The user's words are not written to the device log.
- Manual test plan: U10 passes on synthetic clips, parked and moving. Live-microphone checks pending.

## v0.1.0 (2026-09-29): on-device speech-to-text, rules only

- Push-to-talk voice loop on the phone emulator: whisper.cpp (tiny.en) speech-to-text over JNI,
  hand-written rule interpreter, safety policy, simulated vehicle, Android text-to-speech.
- Policy as a pure Kotlin function and the only path to the vehicle; unknown driving state handled as
  moving; screen output only when parked; visibility-reducing commands confirmed while moving.
- Turn engine with speech-to-text timeout, 5 s action budget, expiring confirmations, one write per
  turn, spoken replies read back from the vehicle.
- Models pinned by Hugging Face revision, size and SHA-256; checked by the download script and again
  by the app before loading.
- 163 unit tests; requirement-to-test traceability checked in CI; merged-manifest check (no
  `INTERNET`, only the launcher exported).
- Manual test plan: U1–U9 pass on synthetic clips. Live-microphone checks are pending.
- Not in this release: the language-model fallback (U10), the regression harness, the automotive
  emulator and the real car API.
