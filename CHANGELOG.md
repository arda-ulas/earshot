# Changelog

All results below come from the arm64 API 36 phone emulator with a simulated vehicle and synthetic
test clips unless stated otherwise. Nothing here has run in a vehicle.

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
