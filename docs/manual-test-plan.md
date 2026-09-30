# Manual test plan

Scripted checks on the emulator for what unit tests cannot cover: the real speech model, the app, and
the audio path. Every result row says where the audio came from:

- **clip**: a synthetic WAV from `scripts/make-clips.sh` (macOS `say`, voices Samantha and Daniel),
  fed through the same pipeline by the debug-only clip player. This tests the pipeline, not how well
  it hears a person.
- **mic**: a person speaking into the emulator's microphone (host audio input). Only these rows count
  as live-voice testing.

## Setup

1. `scripts/fetch-models.sh` (hashes verified), `scripts/make-clips.sh`
2. AVD `earshot_api36`: API 36 `google_apis` arm64-v8a image, 6 GB RAM, 4 vCPUs, host audio input on
3. `./gradlew :app:installDebug`, then `scripts/push-models.sh --clips`
4. Driving scenarios and the vehicle connection are set from the app's developer panel (simulated
   vehicle). Traces: `adb shell run-as io.github.ardaulas.earshot cat files/traces/<date>.jsonl`

## Cases

| ID | Scenario | Steps | Expected |
|---|---|---|---|
| M-1 | U1 | Parked; "Set the temperature to 21" | Temperature set; reply read back from the vehicle; shown on screen |
| M-2 | U2 | City drive; "Turn on the front defrost" | Front defrost on; voice only (nothing on screen) |
| M-3 | U3 | City drive; "How fast am I going?" | Speaks the simulated speed; voice only |
| M-4 | U4 | City drive, then Parked; "Show me my climate settings" | Moving: refused with a short spoken summary. Parked: climate panel shown |
| M-5 | U5 | "Make it warmer" | +1 °C, read back |
| M-6 | U6 | "Order me a pizza" | Refused, no action |
| M-7 | U7 | Near-silence, then a whispered mumble | Asks once to repeat, then stops; no action |
| M-8 | U8 | City drive; "Turn off the defrost", then "Never mind" | Confirmation cancelled; no action |
| M-9 | U9 | City drive; "Turn off the defrost", then "yes" / "no" | Asks first; yes turns it off (read back), no does nothing |
| M-10 | SR-6 | Signal lost, wait > 5 s; U1, U3, U4 | State UNKNOWN handled as moving: voice only, screen refused, speed unavailable |
| M-11 | SR-13 | Parked; vehicle connection off; U1, U3 | "Vehicle controls are unavailable"; driving state UNKNOWN; no write |
| M-12 | SR-3 | "Set the temperature to 35" | Gives the valid range; no action |
| M-13 | SR-19 | Hold to talk while a reply is being spoken | The button does nothing until speech ends |
| M-14 | SR-12 | Delete or corrupt the speech model, restart | Assistant disabled with the reason on screen; no crash |
| M-15 | Live voice | Repeat M-1, M-2, M-5, M-6, M-9, M-16 speaking into the microphone | As above |
| M-16 | U10 | Parked and City drive; "I'm freezing", then "yes" / "no" | Rules miss; the language model answers `warmer`; asks "Raise the temperature by 2 degrees? Say yes or no."; yes raises it by 2 (read back), no does nothing |
| M-17 | SG-7 | "Order me a pizza" (heard clearly, so the rules miss and the language model runs) | Model answers `out_of_domain`; refused, no action |

## Results

### 2026-09-29, `earshot_api36` on an Apple silicon laptop, debug build

Latency is what this emulator measured; it is not an in-vehicle or on-phone figure. The
speech-to-text stage (whisper tiny.en, 2 threads) took 2.2–4.4 s per clip, typically 2.2–3.4 s.

| ID | Input | Result | Notes |
|---|---|---|---|
| M-1 | clip | pass | Samantha and Daniel: "Set the temperature to 21." (mean token p 0.95–0.98) → set, "Temperature is now 21 degrees." |
| M-2 | clip | pass | `AllowVoiceOnly`, screen empty, "Front defrost is now on." |
| M-3 | clip | pass | "You're going 50 kilometres per hour." |
| M-4 | clip | pass | Moving: "I can't show that while driving. It's 21 degrees, fan 2." (11 words). Parked: panel shown |
| M-5 | clip | pass | 21 → 22, read back |
| M-6 | clip | pass | Refused. One Daniel clip was heard as "Automia pizza" (p 0.48): below threshold, so it re-prompted instead; still no action |
| M-7 | clip | pass, with a caveat | Near-silence: re-prompt (audio gate, whisper not run). Whispered mumble after it: "I'm gonna be okay." at p 0.24 → stop. In an earlier run the same mumble alone came back as "I'm gonna be off." at p 0.89 and was refused as out of domain: no action, but a refusal rather than a re-prompt. Token probability is a weak signal for unclear speech; the next phase's harness is where the threshold gets calibrated |
| M-8 | clip | pass | "Nevermind." → "Cancelled."; a later "yes" → "There's nothing to confirm." |
| M-9 | clip | pass | Yes → "Front defrost is now off." (read back). No → "Okay, I won't." |
| M-10 | clip | pass | UNKNOWN: voice only; "Speed isn't available right now."; screen refused |
| M-11 | clip | pass | "Vehicle controls are unavailable."; state UNKNOWN; reconnecting returns to PARKED |
| M-12 | clip | pass | "I can only set the temperature from 16 to 28." |
| M-13 | — | not run | Needs the microphone |
| M-14 | — | pass | One byte of `ggml-tiny.en.bin` changed on the device: "Assistant disabled. Speech model ggml-tiny.en.bin: SHA-256 mismatch; the file was changed or damaged." Button disabled, no crash. File restored afterwards |
| M-15 | mic | not run | Pending: needs a person at the microphone |

### 2026-09-29 (later), same emulator and host, debug build with the language-model fallback

Model Qwen3-0.6B Q4_0, intent format (see `docs/lm-eval`). The fixed prompt prefix (409 tokens) is
decoded once in the background after start-up; that took 8.2 s on this emulator.

| ID | Input | Result | Notes |
|---|---|---|---|
| M-16 | clip | pass | Samantha and Daniel, parked and moving: "I'm freezing." -> `{"intent":"warmer"}` -> "Raise the temperature by 2 degrees? Say yes or no." Moving + yes: "Temperature is now 23 degrees." (21 + 2, read back, voice only). Parked + no: "Okay, I won't." Language-model stage 0.5–2.0 s |
| M-17 | clip | pass | Samantha: "Order me a pizza." -> `{"intent":"out_of_domain"}` -> refused. Daniel's clip was heard as "Automia pizza" at p 0.48, below the threshold, so it re-prompted and the model was not asked |
| M-1, M-9 | clip | pass | Re-run with the language model loaded: unchanged |
| Clean clone | clip | partial | Fresh clone of the v0.2.0 branch: `scripts/fetch-models.sh` downloaded both models and verified their hashes, `:core:check` and the debug build passed, and on the emulator the prefix decoded in 5.4 s and "I'm freezing" reached the confirmation question. The "yes" step was not re-run on that build: under host load speech-to-text hit its timeout and re-prompted |

Findings during these runs:

- With the previous model (Qwen2.5-0.5B) and the first prompt, "I'm freezing" became 16 °C and
  "order me a pizza" became AC on. The confirmation question exposed both, so nothing happened
  without a yes, but the fallback was not useful. Replaced after the evaluation in `docs/lm-eval`.
- On device, the parser's regex failed to load (Android's ICU rejects a bare `}` that the desktop JVM
  accepts) and llama.cpp rejected the multi-line grammar. Either one left the fallback refusing
  every indirect request, which is the intended fail-safe. Both were invisible to the JVM tests; both
  are fixed, the grammar layout now has a test.
- When the laptop was heavily loaded (load average around 20 while building), whisper's encoder took
  up to 6 s and some turns hit the 10 s speech-to-text timeout, which re-prompted as designed.
  Whisper checks its abort flag only after the encoder finishes and after each decoder step, so an aborted turn can run past 10 s before
  it returns (up to about 20 s seen).
- A captioned screen recording was attempted for the README. With `adb shell screenrecord` running
  (540x1200, 2 Mbit/s), speech-to-text on this emulator took 5.3–7.6 s for short commands, so the
  5 s action budget discarded the actions ("That took too long, so I didn't do it."), and longer turns
  hit the 10 s speech-to-text timeout. That is SG-5 and the timeout working as designed, but not a
  useful demo, so this release has no recording. The debug build keeps a pinned caption strip (what
  the clip says, what the assistant replied, the driving state) for a later recording on an idle
  machine.

Findings during these runs, fixed before the results above:

- A whispered mumble took 77 s in whisper (temperature-fallback retries). Fallback is now off, tokens
  are capped, and the turn engine abandons speech-to-text after 10 s and re-prompts.
- With 4 threads on 4 vCPUs, speech-to-text sometimes stalled for 10–15 s; with 2 threads it stayed
  within 2.2–3.4 s. When a turn took longer than the 5 s action budget, the action was discarded
  (SG-5) as designed.
- Shrinking whisper's encoder window saved about 0.5 s but broke short answers ("No. No. No. ..."), so
  it was reverted.
- Confirmation expiry was measured when the policy ran, so slow speech-to-text of a "yes" counted
  against the driver. It is now measured to the end of the answer, with a 10 s window.
- Debug builds exported `androidx.compose.ui.tooling.PreviewActivity` (from `ui-tooling`). The
  dependency was removed, and the merged-manifest build check now enforces SR-20.

## Android Automotive emulator (v0.3.0)

Setup: AVD `earshot_aaos`, Android Automotive 15 image (`system-images;android-35-ext15;android-automotive;arm64-v8a`),
6 GB RAM, started with `-writable-system`. The app is installed as a privileged app with
`scripts/install-privileged.sh` (grants only `CONTROL_CAR_CLIMATE`); `CAR_SPEED` granted at the
runtime prompt or with `pm grant`. Driving state is set through the emulator's vehicle HAL with
`scripts/aaos-scenario.sh parked|city|stopped`. Turns are driven with `scripts/drive-clips.py`
(synthetic clips). HAL values are read back with `adb shell cmd car_service get-property-value`.

| ID | Scenario | Steps | Expected |
|---|---|---|---|
| A-1 | Car API reads | `parked`, then `city`, then `stopped` | Developer panel: 0 km/h park -> PARKED; 50 km/h drive -> MOVING; 0 km/h drive -> MOVING (ADR 0003) |
| A-2 | UX restrictions | `city` | Platform UX restrictions "required"; assistant voice only |
| A-3 | U1, real write | `parked`; "Set the temperature to 21" | `HVAC_TEMPERATURE_SET` becomes 21.0 in every seat area; reply read back from the car |
| A-4 | U2 | `city`; "Turn on the front defrost" | `HVAC_DEFROSTER` front windshield TRUE; voice only |
| A-5 | U3, U4 | `city`; speed question, "Show me my climate settings" | Speaks 50 km/h; refuses the screen with a summary read from the car |
| A-6 | U9 | `city`; "Turn off the defrost", "yes" | Question spoken first; after yes, front defroster FALSE |
| A-7 | U10 | `city`; "I'm freezing", "yes" | Language model `warmer`; question; after yes the car's temperature rises by 2 |
| A-8 | Revocation (audit #7) | `parked`; "Show me my climate settings"; then `city` | Panel shown while parked; removed when moving |
| A-9 | Audit #1, #2 | "Don't make it warmer"; "Set the temperature to minus 21" | Refused, language model not asked; valid range given |
| A-10 | Offline voice (SR-25) | start the app | A local voice is selected (logcat `onIsValidVoiceName(...-local)`) |
| A-11 | Signal lost | — | Not reproducible with this image's hooks for polled reads; covered by unit tests (SR-22) |

### 2026-09-30, `earshot_aaos` on the same Apple silicon laptop, debug build (privileged install)

| ID | Input | Result | Notes |
|---|---|---|---|
| A-1 | — | pass | All three states as expected |
| A-2 | — | pass | "Platform UX restrictions: required (voice only)" at 50 km/h in drive |
| A-3 | clip | pass | 17.0 -> 21.0 °C in ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, ROW_2_CENTER; "Temperature is now 21 degrees." |
| A-4 | clip | pass | FRONT_WINDSHIELD TRUE. An earlier run was discarded by SG-5 when host load pushed speech-to-text to 7.8 s |
| A-5 | clip | pass | "You're going 50 kilometres per hour."; "I can't show that while driving. It's 23 degrees, fan 3." |
| A-6 | clip | pass | After the fixes for audit #3/#4: yes accepted after the question was spoken; FRONT_WINDSHIELD FALSE |
| A-7 | clip | pass | `{"intent":"warmer"}` -> "Raise the temperature by 2 degrees?" -> yes -> 21.0 -> 23.0 °C in the car |
| A-8 | clip | pass | Screenshots: panel with car values while parked; gone, "Voice only while driving" after `city` |
| A-9 | clip | pass | "Sorry, I can't help with that." (no language-model stage in the trace); "I can only set the temperature from 16 to 28." |
| A-10 | — | pass | `en-us-x-tpf-local` |
| A-11 | — | not run | See above |

Re-run on 2026-09-30 after the last interpreter and driving-state fixes (`clip`, same emulator): A-3
("Set the temperature to 21", acted), U5 ("Make it warmer", acted), A-9 (both refused), A-6 (defrost
off, confirmed, acted) and A-7 (parked and moving: question, yes, acted) pass. An earlier attempt at
A-7 was refused as expired: the clip driver took 25 s to select the answer clip, past the 10 s answer
window, which is the intended behaviour; the driver now selects it while the question turn runs.

Re-run on 2026-09-30 after the fixes for the third and fourth re-audits (`clip`, same emulator):
parked, A-3, U5 and A-9 (both) pass; in `city`, A-4, A-6 and A-5 (speed) pass, and the developer view
shows only "Hidden while driving" and a numbered clip player. A-7 in `city` passed once and was
refused as expired twice: with the laptop loaded, the clip driver needed more than 10 s after the
spoken question to select and play "yes". Timing logs from the passing run: question delivered at
109.6 s (monotonic), answer at 119.1 s, acted.

Speech-to-text on this AVD was 0.7–1.3 s per clip with the host quiet, and up to 7.8 s while the
host was busy building (same laptop; not an in-vehicle figure). The phone emulator regression
(M-rows) after the audit fixes is recorded separately below when run.
