# Requirements

Safety and security requirements (`SR-n`) are derived from the safety goals (`SG-n`) and threats
(`TH-n`) in [safety.md](safety.md) and [threat-model.md](threat-model.md). Tests that verify a
requirement carry `@Verifies("SR-n")`; [traceability.md](traceability.md) is generated from them.

| ID | Requirement | From | Verified by |
|---|---|---|---|
| SR-1 | No vehicle action unless the command is in domain and the speech-to-text confidence is known and at or above the threshold. Negated, interrogative, unsupported-target and multi-action utterances never become actions and do not reach the language model. | SG-1, TH-1 | unit tests (incl. audit regression) |
| SR-2 | Below the confidence threshold, the assistant asks once to repeat, then stops. It never guesses an action. | SG-1 | unit tests |
| SR-3 | Every command value is bounded. An explicit out-of-range value, including a signed or fractional one ("-21", "28.5"), is answered with the valid range and no action, never clamped or truncated. A relative change that would pass a bound stops at the bound and says so. | SG-2 | unit tests (incl. property tests with signed values) |
| SR-4 | A turn decided while moving or unknown shows nothing on screen, and the climate panel is shown only if the car is still parked after its reads; screen-dependent requests are refused with a short spoken summary. When the car starts moving, the app removes parked-only content from the screen and stops a long parked reply. | SG-3 | unit tests (decision and panel re-check); app revocation: manual test plan |
| SR-5 | Spoken replies while moving or unknown are at most 12 words. | SG-3 | unit tests |
| SR-6 | Missing, stale or ambiguous driving signals are treated as moving. A stopped vehicle in drive or reverse counts as moving. | SG-4 | unit tests |
| SR-7 | An action whose vehicle write cannot start within 5 s of the end of the utterance is discarded; the absolute deadline is checked immediately before the write, after any reads. | SG-5 | unit tests (fake clock, slow-gateway regression) |
| SR-8 | Visibility-reducing commands (defrost off; fan off while the front defrost is on or unknown) need a spoken yes while moving or unknown. | SG-6 | unit tests |
| SR-9 | Language-model output must parse exactly into the command schema with in-bounds values; anything else is "not understood" and nothing happens. | SG-7 | unit tests |
| SR-10 | Every command from the language model needs a spoken yes. The model cannot produce cancel, help, yes/no or screen commands. | SG-7 | unit tests |
| SR-11 | A language-model timeout or failure is "not understood" and nothing happens. | SG-7 | unit tests (fault injection) |
| SR-12 | Model files are checked against pinned size and SHA-256, and the app loads only a verified copy in app-private storage, so the checked bytes are the loaded bytes. No valid speech model disables the assistant with an on-screen reason; an invalid language model disables only the fallback. | TH-4 | unit tests (fault injection, snapshot) |
| SR-13 | With the vehicle connection down, writes are disabled and the assistant says vehicle controls are unavailable. | fail-safe | unit tests (fault injection) |
| SR-14 | A rejected or timed-out write is reported by voice; there is no retry. | fail-safe | unit tests (fault injection) |
| SR-15 | The spoken confirmation of an action comes from reading the property back after the write, never from the request. | fail-safe | unit tests |
| SR-16 | At most one vehicle action per turn. | fail-safe | unit tests |
| SR-17 | Only allowlisted comfort properties can be written. | deny by default | by construction (`ClimateProperty`), unit tests |
| SR-18 | A confirmation becomes answerable only after its question was delivered (spoken, or shown while parked); delivery failure drops it. It expires 10 s after delivery, the answer must start after delivery, and any other outcome (another request, an out-of-range request, a stop, "never mind") ends it. | SG-6, SG-7 | unit tests (incl. audit regression) |
| SR-19 | No audio capture while text-to-speech is playing. | SG-8 | manual test plan |
| SR-20 | The app requests only allowlisted permissions, in every declaration form (`uses-permission`, `-sdk-23`, `-sdk-m`), never `INTERNET`, and exports no component except the launcher activity. | TH-2, TH-6 | build check on the merged manifest (`:app:verify<Variant>MergedManifest`, runs `scripts/check_manifest.py`); negative fixtures in CI |
| SR-21 | Every turn writes a trace with per-stage latency, the host it was measured on, and whether the audio came from the microphone or a clip. | G4 | unit tests |
| SR-22 | On the automotive emulator, speed and gear come from the car API. A value that cannot be read (unavailable, error status, missing permission, service disconnected) is treated as missing, so the driving state becomes unknown and is handled as moving; speed wins over a conflicting park gear. | SG-4 | unit tests |
| SR-23 | When the platform's UX restrictions require distraction-optimized UI, the assistant behaves as if moving even if the signals say parked; if the restrictions service cannot be reached on a car, restrictions are assumed. The stricter of the two wins. | SG-3 | unit tests |
| SR-24 | A failure to store the trace never hides or undoes the result of a turn: the spoken read-back of an executed action is still delivered. | fail-safe | unit tests (audit regression) |
| SR-25 | Speech output uses only an installed voice that needs no network connection; with none, speech is treated as unavailable (short text only when parked). | TH-6 | manual test plan (voice selection logged on the emulator) |
