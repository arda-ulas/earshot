# ADR 0003: Fail-safe defaults for unknown or late inputs

Status: accepted (2026-09-29)

## Context

Earshot turns a push-to-talk utterance into at most one write on a simulated vehicle. Phase 1 runs on
the arm64 API 36 phone emulator. There is no real vehicle and there are no users. Several inputs to
each decision can be missing, stale or uncertain: the driving signals, the speech-to-text confidence,
the front defrost state and the vehicle connection. Stages can also take too long. Each gap needs a
default, and the default decides whether the assistant does more or less. The hazards and safety
goals behind these choices (HZ-1 to HZ-8, SG-1 to SG-8) are in [safety.md](../safety.md). The
requirements (SR-n) are in [requirements.md](../requirements.md).

## Decision

When an input is unknown or a stage is late, assume the value under which the assistant does less:
no action, a spoken confirmation, voice only, or no screen. Code paths are under
`core/src/main/kotlin/io/github/ardaulas/earshot/core/` unless noted.

| Default | Reason | Where in code |
|---|---|---|
| No driving signal, or the latest is older than 1 s: state `UNKNOWN`, handled as moving. A NaN speed counts as moving. | SG-4. A missing signal must not unlock the screen or skip a confirmation. | `DrivingStateResolver.current` (`vehicle/`), `DrivingState.effective` (`policy/`) |
| Gear D or R at speed 0: moving. Neutral or no gear at speed 0 becomes parked only after 2 s. | SR-6. A car stopped in gear can move at any moment. Trade-off below. | `DrivingStateResolver.current` |
| Confidence unknown (null or NaN) or below 0.5: no action. Re-prompt once, then stop. Speech-to-text that fails, throws or times out gives unknown confidence. Audio that is too short or quiet gives 0 and is never transcribed. | SG-1. Never guess from unclear audio. The language model never sees it either. | `Policy.isConfident` and step 3 of `Policy.decide` (`policy/Policy.kt`), `TurnEngine.runTurn`, `AudioGate` (`speech/`), applied in `WhisperSpeechEngine.transcribe` (`native/whisper`) |
| Cancel is checked before confidence. | SR-2. A misheard "cancel" can only stop things. The rules run even on unclear audio, so "never mind" still works. | Step 2 of `Policy.decide`, `TurnEngine.runTurn` |
| Front defrost state unreadable: assumed on. Fan off then counts as visibility-reducing and needs a spoken yes while moving or unknown. | SG-6. With the fan off, the defrost cannot clear the windshield. | `Command.category(frontDefrostOn)` in `policy/Policy.kt`. The engine reads the defrost only for `SetFan`. |
| Vehicle connection down: no write; the reply is "Vehicle controls are unavailable." The simulated gateway stops reporting signals, so the state becomes `UNKNOWN` after 1 s. | SR-13. No write without a working connection. | `TurnEngine.writeThenReadBack` checks `VehicleGateway.isAvailable`; `SimulatedVehicleGateway` |
| The spoken result comes from reading the property back after the write, never from the request. | SR-15. The driver hears what the vehicle reports, even when it differs from what was asked. | `TurnEngine.writeThenReadBack` |
| At most one write per turn. A confirmed command is the only write of the answering turn. | SR-16. Limits what one misheard or injected utterance can do. | `TurnEngine.act`: one command, one `writeThenReadBack`, no loop |
| An action that has not started 5 s after the end of the utterance is dropped: "That took too long, so I didn't do it." The end is button release for microphone input and the moment the clip is handed to the engine for clip input; for a confirmation, it is the end of the "yes". The check runs once, before the action. The reads and the write that follow each have their own 1 s limit. | SG-5, SR-7. A late action is a surprise. | `TurnConfig.actionBudgetMs`, `TurnEngine.execute`; the end time is set in `AssistantViewModel.onRelease` and `playClip` (`app/`) |
| Confirmation window 10 s, from just before the policy decides to ask to the end of the answer. Any other clearly heard request abandons it; an unclear one that is re-prompted leaves it open. "Never mind" cancels it. | SR-18. Why it changed is below. | `TurnConfig.confirmationTtlMs`, `pendingValid` in `TurnEngine.runTurn` |
| Speech-to-text timeout 10 s, then handled as unclear: re-prompt once, then stop. | SR-2. Without a limit, one slow speech-to-text call holds up the turn. A whispered-mumble clip once took 77 s in whisper.cpp (temperature-fallback retries, since turned off). Measured with `clip` input on the arm64 API 36 emulator on an Apple silicon laptop. | `TurnConfig.sttTimeoutMs`, `TurnEngine.runTurn` |
| Vehicle write timeout 1 s, no retry. The reply is "I couldn't change the ..." Reads use the same limit. | SR-14. A retry would act later than asked (SG-5). The driver can ask again. | `TurnConfig.writeTimeoutMs`, `TurnEngine.writeThenReadBack` and `readInt` |

**Drive or reverse at a standstill.** This is a real trade-off. Android Automotive OS derives three
states from gear and speed: parked, idling (not in Park, speed zero) and moving. Which restrictions
apply while idling can differ by vehicle maker and market
([developer.android.com](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)),
and the documented example restricts idling less than moving
([source.android.com](https://source.android.com/docs/automotive/driver_distraction/car_uxr)).
Earshot has no idling state and takes the restrictive side: stopped at a light in D, it refuses the
screen and asks before turning the defrost off. As background only: NHTSA's visual-manual distraction
guidelines count a vehicle as driving whenever propulsion is active and the transmission is not in
Park ([78 FR 24818](https://www.govinfo.gov/content/pkg/FR-2013-04-26/html/2013-09883.htm)). Those
guidelines do not cover voice interaction and do not apply to Earshot.

**Confirmation window.** The first version (SR-18 and `TurnConfig`) set 8 s, checked when the policy
ran on the answer. That counted the time to recognize the "yes" against the driver: an answer that
ended in time could still get "That request timed out. Please ask again." Recognizing a short answer
took about 0.6 s, and longer under host load (measured on the arm64 API 36 emulator on an Apple
silicon laptop, clip input). The check now uses the end of the answering utterance, and the window is
10 s. The clock starts just before the policy decides to ask, so before the question is spoken.
Capture stays off while the question plays. The time left to finish answering is therefore at most
10 s minus the time it takes to speak the question.

## Alternatives considered

- **Unknown driving state as parked.** Android Automotive OS does not enforce restrictions, and acts
  as if parked, until driving-state data first arrives (same source). Rejected: SG-4 asks for the
  opposite.
- **An idling state with lighter rules.** Rejected for Phase 1: the right rules depend on vehicle
  maker and market, and there is no vehicle here to decide them. Worth revisiting on the Android
  Automotive emulator, where the platform supplies the restrictions.
- **Acting on a best guess below the threshold.** Rejected: SG-1.
- **Assuming the defrost is off when it cannot be read.** Rejected: that is the unsafe side.
- **Retrying a failed write, or speaking the requested value.** Rejected: SG-5 and SR-15.
- **Keeping 8 s checked at policy time.** Rejected after the emulator runs above.

## Consequences

- Good: an unknown input never lets the assistant do more than a known value would. An unknown
  driving state gets the moving rules: comfort commands and queries run by voice only,
  visibility-reducing commands need a spoken yes, and the screen is refused. An unreadable front
  defrost makes fan-off need a yes while moving. Unknown confidence, a lost vehicle connection, a
  speech-to-text timeout and an action past its budget end with no write.
- Good: the defaults live in a few small places (the pure `Policy`, `DrivingStateResolver`,
  `AudioGate`, and the limits in `TurnConfig` that `TurnEngine` enforces), all covered by unit tests;
  timing tests use a virtual clock. The call to the audio gate in `WhisperSpeechEngine` has no unit
  test of its own.
- Bad: more refusals, re-prompts and confirmations than a permissive design. Stopped in D, the screen
  is refused and "turn off the defrost" needs a yes. With the signal lost, replies are voice only, the
  screen is refused, defrost-off needs a yes, and a speed query gets "Speed isn't available right
  now."
- Bad: a slow host costs actions. With 4 threads on 4 vCPUs, speech-to-text sometimes stalled for
  10-15 s and actions past the 5 s budget were dropped; with 2 threads these stalls did not recur in
  the recorded runs. With the laptop heavily loaded, some turns hit the 10 s timeout and re-prompted.
  The whisper.cpp library checks its abort flag only after a whole encoder or decoder pass, not
  during one, so an abandoned turn took up to about 20 s to return. All with `clip` input on the
  arm64 API 36 emulator on an Apple silicon laptop.
- Bad: unclear speech is judged by mean token probability, which is a weak signal. In M-7 (`clip`), a
  whispered mumble once came back as "I'm gonna be off." with a mean token probability of 0.89. It
  was refused as out of domain instead of re-prompted: no action, but not the intended path.
- Not done: the 0.5 threshold and the timing values are not calibrated; calibrating the threshold is
  planned for the regression harness in the next phase. Nothing here was measured in a vehicle or
  with a person at the microphone (M-13 and M-15 need the microphone and are pending). There are no
  per-market or per-display rules.

## Evidence

- Unit tests, tagged `@Verifies` and listed in [traceability.md](../traceability.md):
  - [PolicyTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/policy/PolicyTest.kt):
    - unknown driving state behaves exactly like moving for every command (SR-6)
    - cancel always stops, even at low confidence (SR-2)
    - confidence below the threshold, including null and NaN, re-prompts once, then stops (SR-2)
    - fan to zero is visibility-reducing unless the front defrost is confirmed off (SR-8)
  - [DrivingStateResolverTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/vehicle/DrivingStateResolverTest.kt):
    no sample, a stale reading, drive or reverse at a standstill, NaN speed, the 2 s neutral rule
    (SR-6).
  - [AudioGateTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/speech/AudioGateTest.kt):
    near-silent and very short audio is not worth transcribing, and an empty result has zero
    confidence (SR-1, SR-2).
  - [TurnEngineTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/turn/TurnEngineTest.kt):
    - the action budget, from the end of the utterance and from the confirming "yes" (SR-7)
    - vehicle unavailable (SR-13)
    - rejected and hanging writes fail with no retry (SR-14, SR-16)
    - the reply comes from the read-back (SR-15)
    - one write per turn (SR-16)
    - expiry at 10 s, measured to the end of the answer (SR-18)
    - speech-to-text that throws or times out re-prompts (SR-2)
- [Manual test plan](../manual-test-plan.md), `clip` input on the arm64 API 36 emulator: M-7 (unclear
  audio), M-8 (never mind), M-9 (confirm yes and no), M-10 (signal lost: `UNKNOWN` handled as moving),
  M-11 (vehicle connection off). All pass on clips, M-7 with the caveat above.
- Related: [ADR 0004](0004-language-model-gating.md) adds a confirmation to every language-model
  command on top of these defaults.
