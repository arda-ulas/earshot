# Changelog

All results below come from the arm64 API 36 phone emulator with a simulated vehicle and synthetic
test clips unless stated otherwise. Nothing here has run in a vehicle.

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
