# Requirements

Safety and security requirements (`SR-n`) are derived from the safety goals (`SG-n`) and threats
(`TH-n`) in [safety.md](safety.md) and [threat-model.md](threat-model.md). Tests that verify a
requirement carry `@Verifies("SR-n")`; [traceability.md](traceability.md) is generated from them.

| ID | Requirement | From | Verified by |
|---|---|---|---|
| SR-1 | No vehicle action unless the command is in domain and the speech-to-text confidence is known and at or above the threshold. | SG-1, TH-1 | unit tests |
| SR-2 | Below the confidence threshold, the assistant asks once to repeat, then stops. It never guesses an action. | SG-1 | unit tests |
| SR-3 | Every command value is bounded. An explicit out-of-range value is answered with the valid range and no action, never clamped silently. A relative change that would pass a bound stops at the bound and says so. | SG-2 | unit tests |
| SR-4 | While moving or with the driving state unknown, nothing is shown on screen; screen-dependent requests are refused with a short spoken summary. | SG-3 | unit tests |
| SR-5 | Spoken replies while moving or unknown are at most 12 words. | SG-3 | unit tests |
| SR-6 | Missing, stale or ambiguous driving signals are treated as moving. A stopped vehicle in drive or reverse counts as moving. | SG-4 | unit tests |
| SR-7 | An action that cannot start within 5 s of the end of the utterance is discarded. | SG-5 | unit tests (fake clock) |
| SR-8 | Visibility-reducing commands (defrost off; fan off while the front defrost is on or unknown) need a spoken yes while moving or unknown. | SG-6 | unit tests |
| SR-9 | Language-model output must parse exactly into the command schema with in-bounds values; anything else is "not understood" and nothing happens. | SG-7 | unit tests |
| SR-10 | Every command from the language model needs a spoken yes. The model cannot produce cancel, help, yes/no or screen commands. | SG-7 | unit tests |
| SR-11 | A language-model timeout or failure is "not understood" and nothing happens. | SG-7 | unit tests (fault injection) |
| SR-12 | Model files are checked against pinned size and SHA-256 before loading. No valid speech model disables the assistant with an on-screen reason; an invalid language model disables only the fallback. | TH-4 | unit tests (fault injection) |
| SR-13 | With the vehicle connection down, writes are disabled and the assistant says vehicle controls are unavailable. | fail-safe | unit tests (fault injection) |
| SR-14 | A rejected or timed-out write is reported by voice; there is no retry. | fail-safe | unit tests (fault injection) |
| SR-15 | The spoken confirmation of an action comes from reading the property back after the write, never from the request. | fail-safe | unit tests |
| SR-16 | At most one vehicle action per turn. | fail-safe | unit tests |
| SR-17 | Only allowlisted comfort properties can be written. | deny by default | by construction (`ClimateProperty`), unit tests |
| SR-18 | A confirmation question expires after 8 s; any other request abandons it; "never mind" cancels it. | SG-6, SG-7 | unit tests |
| SR-19 | No audio capture while text-to-speech is playing. | SG-8 | manual test plan |
| SR-20 | The app has no `INTERNET` permission and exports no component except the launcher activity. | TH-2, TH-6 | manifest check, manual test plan |
| SR-21 | Every turn writes a trace with per-stage latency, the host it was measured on, and whether the audio came from the microphone or a clip. | G4 | unit tests |
