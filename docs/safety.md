# Safety

This document covers the hazards Earshot is designed against and the safety goals it sets. It also
covers how those goals become tested requirements, the fail-safe rules in the code, and how the
assistant behaves when something fails. The last section lists what is not yet covered.

Security threats are in [threat-model.md](threat-model.md). Requirements are in
[requirements.md](requirements.md), and the requirement-to-test mapping is in
[traceability.md](traceability.md).

## Scope and honesty boundary

Earshot is a hobby project. It is push-to-talk voice control for a car's cabin climate, and it runs on
the Android phone emulator (API 36, arm64) against a simulated vehicle and, since v0.3.0, on the
Android Automotive emulator (Android 15) through the public car API, with climate writes to the
emulator's vehicle HAL from a privileged test install. It has not run in a vehicle and it has no users.

The project borrows a few practices from automotive safety work, scaled to a one-person project:

- a hazard list with safety goals
- requirements derived from those goals, each with a verifying test or a manual check
- fail-safe defaults, and a single decision point between the models and the vehicle
- a manual test plan whose results say where the audio came from

The hazard analysis and threat model are informed by ISO 26262 / ISO 21448 / ISO/SAE 21434 concepts.
The project has not been developed or assessed according to any of these standards and does not claim
compliance with them. The table below describes each standard's scope only as far as the published
sources support it.

| Standard | What it covers | How Earshot uses it |
|---|---|---|
| ISO 26262, *Road vehicles: Functional safety* | Safety-related electrical and electronic (E/E) systems in series-production road vehicles, excluding mopeds. It covers hazards caused by malfunctioning behaviour of those systems, not how well a system performs its intended function. The latest published edition is from 2018 (Parts 1 to 12). A third edition was at the draft (DIS) stage when checked in September 2026. [1] [2] [3] [14] | Vocabulary and practice: hazards, safety goals, requirements traced to verifying tests. |
| ISO 21448:2022, *Safety of the intended functionality* (SOTIF) | Hazards caused by functional insufficiencies. These are gaps in how an intended function is specified at vehicle level, or specification or performance shortfalls in its E/E implementation. They can cause a hazard when nothing has failed. It is aimed mainly at functions whose safety depends on situational awareness from complex sensors and processing algorithms, such as emergency intervention systems and driving automation levels 1 to 5. Reasonably foreseeable misuse is in scope. Faults covered by ISO 26262, cybersecurity threats and deliberate feature abuse are not. ISO lists it as under revision. [3] [4] | A loose design lens only. A speech recogniser or a language model that works as built can still mishear or misread (HZ-1, HZ-2, HZ-7). |
| ISO/SAE 21434:2021, *Road vehicles: Cybersecurity engineering* | Process requirements for cybersecurity risk management across the life cycle of road-vehicle E/E systems. It does not prescribe specific technologies. It has been in systematic review since July 2026. [5] [6] | Vocabulary for [threat-model.md](threat-model.md). |

### No ASIL

Under ISO 26262, an ASIL comes from a hazard analysis and risk assessment done at vehicle level. That
analysis rates each hazardous event for severity, exposure and controllability. The result is an ASIL
from A (least stringent) to D (most stringent), or QM when no ASIL applies. [7] [15]

Earshot has no vehicle, no item definition, no exposure data and no controllability assessment. No
ASIL is assigned. The severities below are qualitative labels (Low, Medium, High) from the author's
project plan, which is not in this repository. They are not ISO 26262 severity classes.

## Hazards and safety goals

| ID | Hazard | Cause | Severity | Why this severity | Safety goal |
|---|---|---|---|---|---|
| HZ-1 | Unintended action | Misrecognition, out-of-domain speech, passenger or radio speech | Medium | Every action is a bounded comfort change (temperature, fan, AC, defrost). An unwanted one is a nuisance and can pull the driver's attention. Loss of visibility is its own hazard (HZ-6). | SG-1: no vehicle action without an in-domain command above the confidence threshold |
| HZ-2 | Wrong value ("12" heard as "21") | Speech-to-text or parsing error | Medium | Values stay inside the comfort range, so the harm is discomfort. A large wrong change invites a manual correction while driving. | SG-2: every value bounded; out-of-range means re-prompt, never a silent clamp to an extreme |
| HZ-3 | Driver distraction | Visual output or long speech while moving | High | Taking the driver's eyes or attention off the road while moving can contribute to a crash. | SG-3: no screen-dependent interaction while moving; short spoken responses |
| HZ-4 | Wrong driving-state assumption | Signal unavailable, stale, or not yet received | High | Every moving-state protection (voice only, screen refusal, confirmation) depends on the driving state. A wrong "parked" turns all of them off at once. | SG-4: unknown driving state treated as moving |
| HZ-5 | Stale action | The action runs long after the utterance | Low | A late action is still one the driver asked for, and still bounded. The harm is surprise. | SG-5: discard the action if it cannot run within a time budget after the utterance ends |
| HZ-6 | Visibility loss | Defrost turned off by mistake while moving | High | A fogged windshield directly reduces the driver's view of the road. | SG-6: visibility-reducing commands need spoken confirmation while moving |
| HZ-7 | The language model invents a command | Hallucination on the fallback path | Medium | The model can turn an unrelated sentence into an action. The vehicle changes it can reach are a subset of the comfort changes in HZ-1. | SG-7: language-model output must parse into the schema, always goes through the policy, and always needs confirmation |
| HZ-8 | Self-trigger | The assistant hears its own speech | Low | Capture runs only while push-to-talk is held, and replies are short templated sentences. | SG-8: no capture while text-to-speech is playing (barge-in is out of scope) |

The safety goals keep the plan's wording. Two details differ in the code:

- SG-1: confidence counts from 0.5 inclusive (at or above the threshold).
- SG-2: a confident, explicit out-of-range value is answered with the valid range and nothing happens
  (SR-3). It does not get the `Reprompt` verdict. An out-of-range value heard below the confidence
  threshold is re-prompted like any other unclear request.

## From safety goals to requirements

Each safety goal is refined into requirements (`SR-n`) in [requirements.md](requirements.md). Tests
that verify a requirement carry `@Verifies("SR-n")`. [traceability.md](traceability.md) is generated
from those annotations and lists every verifying test.

| Safety goal | Requirements | Verified by |
|---|---|---|
| SG-1 | SR-1 (in domain and confident, or no action), SR-2 (re-prompt once, then stop) | unit tests |
| SG-2 | SR-3 (bounded values, out-of-range answered with the valid range) | unit tests |
| SG-3 | SR-4 (no assistant result on screen while moving or unknown; see Known gaps for what still shows), SR-5 (replies of at most 12 words while moving or unknown) | unit tests |
| SG-4 | SR-6 (missing, stale or ambiguous signals mean moving) | unit tests |
| SG-5 | SR-7 (5 s action budget) | unit tests (fake clock) |
| SG-6 | SR-8 (spoken yes for visibility-reducing commands), SR-18 (confirmation expiry and cancel) | unit tests |
| SG-7 | SR-9 (strict parse), SR-10 (always confirmed, restricted commands), SR-11 (timeout or failure is "not understood"), SR-18 | unit tests, fault injection for SR-11 |
| SG-8 | SR-19 (no capture while speaking) | manual test plan (M-13), not run yet |

Some requirements do not come from a safety goal:

- Fail-safe behaviour: SR-13 (vehicle connection down), SR-14 (failed write, no retry), SR-15
  (confirmation from the read-back), SR-16 (one action per turn).
- Deny by default: SR-17 (only allowlisted comfort properties can be written).
- From the threat model: SR-12 (model files verified before loading, TH-4) and SR-20 (no `INTERNET`,
  only the launcher activity exported, TH-2 and TH-6).
- Evidence: SR-21 (every turn writes a trace with per-stage latency, host and input source).

## Fail-safe design as implemented

The reasons behind these choices are recorded in
[adr/0003-fail-safe-defaults.md](adr/0003-fail-safe-defaults.md) and
[adr/0004-language-model-gating.md](adr/0004-language-model-gating.md).

### The policy is the single decision point

[`Policy.decide`](../core/src/main/kotlin/io/github/ardaulas/earshot/core/policy/Policy.kt) is a pure
function. It has no clock, no I/O and no state. It takes a `PolicyInput`:

- the command
- where the command came from (rules or language model)
- the driving state
- the speech-to-text confidence
- the re-prompt count
- whether a confirmation is pending
- the front defrost state

It returns one verdict.

[`TurnEngine`](../core/src/main/kotlin/io/github/ardaulas/earshot/core/turn/TurnEngine.kt) acts on that
verdict. It is the only production code that calls `VehicleGateway.write`, and it does so only after
an `Allow` or `AllowVoiceOnly` verdict, directly or through a "yes" to a pending confirmation. Turns
are serialized: one runs at a time.

One case is settled before the policy runs. `TurnEngine` answers a confident, explicit out-of-range
value ("set the temperature to 35") with the valid range, and nothing is written (SR-3).

Precedence in the policy, first match wins:

1. A command from the language model in a category the model cannot produce (conversation or
   screen-dependent): refuse.
2. Cancel: stop. This comes before the confidence check because a misheard "cancel" can only stop
   things.
3. Confidence unknown or below 0.5: re-prompt once, then stop.
4. Out of domain: refuse.
5. A yes or no with no confirmation pending: refuse.
6. The table below, with an unknown driving state handled as moving.
7. A command from the language model: raise the verdict to at least `Confirm`.

| Category | Commands | Parked | Moving or unknown |
|---|---|---|---|
| Comfort | set or adjust temperature, AC on or off, defrost on, fan (except the case below) | Allow | AllowVoiceOnly |
| Query | speed, gear, cabin settings | Allow | AllowVoiceOnly |
| Conversation | help, yes or no with a confirmation pending | Allow | AllowVoiceOnly |
| Visibility-reducing | defrost off; fan off while the front defrost is on or unreadable | Allow | Confirm |
| Screen-dependent | show the climate panel | Allow | Refuse, with a short spoken summary |
| Out of domain | anything else | Refuse | Refuse |

Verdicts are ordered from least to most restrictive: `Allow` < `AllowVoiceOnly` < `Confirm` <
`Reprompt` < `Stop` < `Refuse`. Step 7 can only move a verdict up this order. So a language-model
command that the table allows becomes a question, and a refusal stays a refusal.

The language-model path has two layers. The wire format has no label for cancel, help, yes or no,
show climate, fan changes or defrost off, so the model cannot express them. If such a command still
reached the policy, step 1 would refuse the conversation and screen-dependent ones. The model runs
only when the rules miss and the speech-to-text confidence is at or above 0.5, so audio below the
threshold never reaches it. Confidence is a weak signal for unclear speech (see Known gaps), so some
unclear audio can still reach the model. Every model command still needs a spoken yes.

### Deny by default

The vehicle interface only accepts a `ClimateProperty`. That enum has five entries: cabin temperature,
fan level, front defrost, rear defrost and AC. Anything else cannot be expressed as a write (SR-17).
The simulated vehicle also rejects values outside each property's range.

### Unknown means unsafe

| When this is unknown, missing or ambiguous | Earshot treats it as |
|---|---|
| Driving signals: none received, or the latest is older than 1 s | `UNKNOWN`, handled as moving |
| Gear in drive or reverse at a standstill | moving |
| Neutral, speed zero | moving for the first 2 s, then parked; unknown if the speed is missing |
| No gear reading, speed zero | unknown (handled as moving) |
| Speech-to-text confidence (timeout, error, no value) | too low: re-prompt once, then stop |
| Front defrost state (could not be read) | on, so fan off counts as visibility-reducing |
| Vehicle connection | down: writes refused, "Vehicle controls are unavailable." |
| Language-model result (timeout, error, invalid output) | not understood: refused as out of domain ("Sorry, I can't help with that."), no action |
| Model file integrity (missing, wrong size, SHA-256 mismatch) | speech model: the next listed speech model is tried; with none valid, assistant disabled. Language model: fallback disabled |

### One action per turn

A turn performs at most one vehicle write request (SR-16). A failed or timed-out write is reported by
voice and is not retried (SR-14). On the car API one request writes every seat area of the single
zone; if some areas accept and others reject, the request is reported as failed although some areas
changed (a known gap).

### Confirm from the read-back

After a write, the turn engine reads the property back from the vehicle and builds the spoken reply
from that value, never from the request (SR-15). Every spoken string comes from a fixed template in
[`Responses`](../core/src/main/kotlin/io/github/ardaulas/earshot/core/turn/Responses.kt), filled with
command values and vehicle read-backs. The transcript is never echoed back, and language-model text
never reaches text-to-speech.

A relative change that would pass a bound stops at the bound and says so ("Temperature is now 28
degrees, the maximum."). If the value is already at the bound, nothing is written ("It's already at
the maximum, 28 degrees.").

### Bounded everything

| What | Bound | Where |
|---|---|---|
| Audio per utterance | at most 8 s, 16 kHz mono float, held in memory only | `AudioCapture`, `AudioGate` |
| Audio worth transcribing | at least 0.3 s and RMS 0.003; otherwise confidence 0 and the speech model does not run | `AudioGate` |
| Speech-to-text output | 48 tokens, single segment, greedy, English, no temperature fallback, 2 threads | `whisper_jni.cpp`; threads from `WhisperSpeechEngine.load` |
| Speech-to-text time | 10 s timeout, then treated as unclear audio; under host load the aborted native call can take longer to return (see Known gaps) | `TurnConfig.sttTimeoutMs` |
| Re-prompts | one, then stop | `Policy` |
| Temperature | 16 to 28 °C | `Bounds.TEMP_C` |
| Temperature change | ±1 to ±4 from the rules; ±2 from the language model | `Bounds.TEMP_DELTA`, `LmWireFormat.TEMP_STEP` |
| Fan level | 0 to 5 | `Bounds.FAN_LEVEL` |
| Defrost, AC | 0 or 1 | `SimulatedVehicleGateway.validRange` |
| Language-model input | 200 characters | `LmInterpreter.MAX_INPUT_CHARS` |
| Language-model output | 16 tokens; the grammar allows only one of 10 labels | `LmWireFormat` |
| Language-model time | 10 s | `LmInterpreter.DEFAULT_TIMEOUT_MS` |
| Action start | the write must start within 5 s of the end of the utterance, checked immediately before it (and on the car API before the first platform write), else discarded | `TurnConfig.actionBudgetMs`, `CarPropertyGateway.write` |
| Confirmation window | 10 s from delivery of the question; the answer must start after delivery | `TurnConfig.confirmationTtlMs` |
| Vehicle read or write | 1 s, no retry; on the car API the wait stops but a started platform call is not interrupted, so the reply says the change could not be confirmed | `TurnConfig.writeTimeoutMs` |
| Vehicle writes per turn | 1 | `TurnEngine` |
| Spoken reply while moving | at most 12 words | `Policy.MAX_WORDS_WHILE_MOVING` |
| Trace files | newest 7 kept; files older than 7 days deleted after each write and at start-up; a turn that would take a file past 1 MB is not stored and the turn reports it | `JsonlTraceWriter` |

The command values are checked when a `Command` is constructed. An out-of-range command cannot exist,
so no later stage has to remember to check.

### Exhaustive sealed types

`Command`, `Verdict`, `ReadResult` and `WriteResult` are sealed. The decisions that matter use
exhaustive `when` expressions with no `else` branch: the policy's category for each command, and the
turn engine's handling of verdicts, refusal reasons, commands and write results. A new command without
a policy category does not compile, and neither does a new verdict that the turn engine does not
handle.

## Degradation modes as implemented

Evidence labels: **unit** means covered by unit tests; **device** means run on the emulator, with
`clip` input where audio is involved (M-14 used a corrupted model file and no audio); **not-tested**
means the code path exists but has not been exercised.

| Fault | Behaviour in the code | Evidence |
|---|---|---|
| Vehicle connection down | Driving state UNKNOWN once the last reading is older than 1 s -> moving rules; writes refused; "Vehicle controls are unavailable." | unit (SR-13), device M-11 |
| Write rejected (1 s) | No retry; "I couldn't change the <thing>." | unit (SR-14) |
| Write timed out (1 s) | No retry; "I couldn't confirm the change to the <thing>. Please check it.", because the platform call may still complete | unit (SR-14) |
| Write reaches the gateway after the turn was cancelled or the driving state changed | Nothing written; "Driving changed, so I didn't do that." | unit (SR-8, re-audit 3 N12) |
| No listed speech model passes (missing, wrong size or hash mismatch) | Assistant disabled with the reason on screen; no crash. If tiny.en fails and the optional base.en passes, base.en is used | unit (ModelGateTest), device M-14 |
| Language model missing or hash mismatch | Fallback disabled; rules keep working; reason in developer panel | unit (ModelGateTest) |
| Speech-to-text slower than 10 s / throws | Treated as unclear: re-prompt once, then stop | unit; seen on device (clip, not scripted) under host load |
| LM timeout (10 s), error or invalid output | Treated as not understood: refused as out of domain ("Sorry, I can't help with that."), no action | unit (SR-11); seen on device (clip) when the grammar failed to parse (fixed) |
| TTS unavailable | Short reply text on screen, only when parked; not shown while moving | implemented, not-tested (the image has a TTS engine) |
| Microphone permission denied | Explains on screen and stops; no background retry | implemented, not-tested |
| Push-to-talk pressed while a turn is processing | Cancels the turn (trace outcome CANCELLED_BY_USER) | unit |

Notes on the table:

- Only a dropped connection makes the driving state `UNKNOWN`. A single property that cannot be read
  while the connection is up also gives "Vehicle controls are unavailable." but leaves the driving
  state unchanged. The simulated vehicle cannot produce that case, and no test covers it.
- The "seen on device" rows were not scripted tests. Speech-to-text passed its 10 s timeout when the
  laptop was heavily loaded, and the language-model path refused every indirect request while its
  grammar failed to parse. Both details are in [manual-test-plan.md](manual-test-plan.md).
- The unit test for the last row cancels the turn directly. The app wiring (a press while a turn is
  processing cancels it) has no automated test.

## Driver distraction

### What Earshot does

- While the driving state is moving or unknown, results are voice only. The turn result carries no
  screen content (SR-4). A result shown while parked is not rendered once the state stops being
  parked, and a long parked reply is stopped.
- Screen-dependent requests are refused while moving, with a short spoken summary instead: "I can't
  show that while driving. It's 21 degrees, fan 2." (SR-4; M-4 on clips).
- Replies spoken while moving are at most 12 words. A unit test checks the reply templates the engine
  speaks while moving against that limit (SR-5); the speed reply is checked from 0 to 250 km/h. The
  out-of-range and write-failed replies are not in that test yet; both are at most 11 words today.
  The full help text is longer and is only used when parked.
- Visibility-reducing commands need a spoken yes while moving (SR-8).
- Interaction is push-to-talk. The driver starts each turn, can cancel a turn in progress by pressing
  again, and can say "never mind" to drop a pending question.

### Background: NHTSA visual-manual guidelines

NHTSA's *Visual-Manual Driver Distraction Guidelines for In-Vehicle Electronic Devices* (Phase 1;
78 FR 24818, 26 April 2013; clarified at 79 FR 55530, 16 September 2014) are nonbinding, voluntary
guidelines for built-in, visual-manual interfaces in light vehicles. [8] [9]

- Under their eye-glance test, a task should be locked out while driving unless three criteria hold
  for at least 21 of 24 test participants. The mean glance away from the road is 2.0 s or less. No
  more than 15 percent (rounded up) of glances exceed 2.0 s. Total eyes-off-road time is 12.0 s or
  less.
- They also recommend always locking out some tasks while driving. These include manual text entry
  for messaging or browsing, video, automatically scrolling text, and text to be read such as messages
  or web pages.
- They define driving as any time the vehicle's propulsion is active, unless the transmission is in
  Park.
- By their own scope, they do not cover auditory-vocal interaction or portable devices. They do not
  apply to Earshot.

Earshot does not measure glances and has not been tested with either NHTSA protocol. Its own rule is
simpler: a turn decided while moving or unknown puts nothing on screen, and screen-dependent requests
are refused by voice. The single screen is not blank while moving. Status text and the developer
panel still show; a result shown while parked is removed when the car starts moving (see Known gaps).
Its choice to treat drive or reverse at a standstill as moving points the same way as NHTSA's
definition of driving, although Earshot has no propulsion signal. Its neutral-at-standstill rule
(parked after 2 s) is looser than NHTSA's definition.

### Android Automotive UX restrictions (integrated in v0.3.0)

In Android Automotive OS, the car service turns gear and speed into a driving state (parked, idling or
moving). A configuration chosen by the vehicle maker turns that state into UX restrictions, which can
differ by market and by display. Apps are expected to read restrictions from `CarUxRestrictionsManager`
rather than infer them from gear or speed. Only activities tagged as distraction optimized may be shown
while restrictions are active. [10] [11] [12] [13]

Since v0.3.0, on Android Automotive, Earshot reads speed and gear through `CarPropertyManager` and the
platform's restrictions through `CarUxRestrictionsManager`, and the stricter of the platform's answer
and its own wins (ADR 0007): if restrictions are required the assistant behaves as moving, and if the
restrictions service cannot be registered, restrictions are assumed. It does not use
`CarDrivingStateManager`. Its main activity is marked distraction optimized; while not parked it shows
only the push-to-talk control, status text and the developer view's test controls, with climate
values, transcripts and replies hidden. It has not been assessed against any driver-distraction
guideline. It has no idling state: drive or reverse at a standstill counts as moving, and a missing or
stale reading counts as moving. The platform does the opposite before its first driving-state data
arrives: its documentation says restrictions are not enforced then, and the system behaves as if
parked. [10]

## Verification

- **Unit tests.** JVM tests in `:core` (JUnit 6 and Kotest property tests). They include fault
  injection (rejected and hanging writes, a disconnected vehicle, language-model timeouts, errors and
  invalid output, speech-to-text errors and timeouts, model size and hash mismatches). Timing rules
  (action budget, confirmation expiry, speech-to-text timeout) are tested on virtual time with a fake
  clock.
- **Traceability in CI.** `scripts/traceability.py --check` runs in CI. It fails if a requirement that
  is not manual-only has no `@Verifies` test, or if [traceability.md](traceability.md) is out of date.
  SR-19 is manual-only and SR-20 is verified by a build check, so both show no unit tests there.
- **Build check.** `:app:verifyDebugMergedManifest` and `:app:verifyReleaseMergedManifest` run in CI
  and fail if the merged manifest requests `INTERNET` or exports anything but the launcher activity
  (SR-20). The check was negative-tested: adding `INTERNET` failed the build.
- **Manual test plan.** [manual-test-plan.md](manual-test-plan.md) covers what unit tests cannot: the
  real speech model, the app and the audio path. Every result with audio is labelled `clip` (synthetic
  audio from macOS `say`, voices Samantha and Daniel) or `mic` (a person speaking). On 2026-09-29, M-1
  to M-12 (M-7 with a caveat, see Known gaps), M-16 and M-17 passed on clips, and M-14 (a corrupted
  model file, no audio) passed. M-13 and M-15 need a person at the microphone and have not been run.
  There are no `mic` results yet.
- **Latency.** All timings were measured on the arm64 API 36 emulator on an Apple silicon laptop, with
  `clip` input. The speech-to-text stage took 2.2 to 4.4 s per clip, typically 2.2 to 3.4 s, and the
  language-model stage 0.5 to 2.0 s. These are not in-vehicle or on-phone figures. Every trace records
  the host it was measured on.
- **Language-model evaluation.** The shipped model and format scored 28/32 on a held-out set of 32
  typed utterances, with no wrong-direction answers. The set has 17 in-domain items, mostly indirect
  requests, and 15 out-of-domain items, of which the model refused 14. Prompts were tuned on a
  separate 30-item dev set. One item is about 3 points. An audit found minor leakage, and one test
  item is close to a prompt example. The sets are small and are text, not speech. See
  [lm-eval/README.md](lm-eval/README.md) and
  [adr/0005-language-model-choice.md](adr/0005-language-model-choice.md).

## Known gaps

- **No live-voice evidence.** Every on-device result used synthetic clips. M-13 and M-15 are pending,
  so SR-19 (no capture while speaking) has no executed verification yet.
- **Uncalibrated confidence threshold.** The 0.5 threshold on mean token probability has not been
  calibrated, and it is a weak signal for unclear speech. In one run (M-7), a whispered mumble came back
  as "I'm gonna be off." at 0.89 and was refused as out of domain instead of re-prompted. A "yes" needs
  the same threshold as any other command; there is no stricter one for confirmations. Calibration
  belongs to the regression harness in the next phase.
- **Untested degradation paths.** The text-to-speech-unavailable and microphone-permission-denied paths
  are implemented but have not been exercised.
- **No automated tests for the app layer.** The capture lock during speech, press-to-cancel,
  permission handling, the text-to-speech fallback and what the screen renders are covered only by the
  manual test plan, on clips.
- **The screen rule covers assistant output only.** SR-4 applies to the result content of a turn. The
  single screen still shows status text while moving, such as "Voice only while driving" and "Waiting
  for yes or no". Since v0.3.0 a result shown while parked (text or the climate panel) is removed
  when the driving state stops being parked, and a long parked reply is stopped (app behaviour,
  checked on the emulator, not unit-tested). The developer panel and the debug build's recording
  caption hide transcripts, replies and climate values unless parked, which also covers Android
  Automotive's `UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION` flag. [12]
- **App-level fixes without automated tests.** Capture overflow and microphone failure (the whole
  utterance is refused), stopping parked-only speech when the car starts moving, confirmation only
  after the question was spoken, teardown during inference, and cancellation during model loading
  are implemented in `:app` and reviewed, but no automated test drives `AudioRecord`, text-to-speech
  or the view-model lifecycle. Cancellation during model loading can still leave a loaded engine
  unowned until the process ends (re-audit finding N9, not fixed).
- **Debug clip player while driving.** Debug builds keep the clip player (clip file names and a
  play button) visible under the platform's UX restrictions, as the test instrument for the moving
  rows of the manual test plan. Release builds have no clip player.
- **The single path to the vehicle is kept by review.** Today the main (non-test) sources have one
  call to `VehicleGateway.write`, in `TurnEngine`. No automated check enforces that.
- **Simulated vehicle and emulator HAL.** On the phone, driving signals come from scripted scenarios.
  On the Android Automotive emulator they come from its vehicle HAL, set with test hooks. The value
  bounds are those of the simulated cabin; the emulator's fan range is not mapped to the 0-5 model.
- **Car-service calls cannot be interrupted.** Timeouts stop waiting for a platform call, but a call
  that has started runs to its end, so a timed-out write may still take effect (a write that has not
  started yet checks the turn and the driving state first and does nothing if either changed); the reply says the
  change could not be confirmed. Driving signals are polled off the main thread; if polling stalls,
  the state ages to unknown, and a separate loop with no car calls removes parked-only output. The
  car connection and area discovery still run once on the main thread when the screen is created.
- **How fresh "right before the write" is.** The re-check, and the gateway's guard just before the
  first effect, use the resolver's latest reading, not a new one. That reading is at most 1 s old;
  on the car API it is stamped with the time the poll started, and its speed value may be up to 2 s
  older by the vehicle's own timestamp (a value stamped in the future is refused). The gear value
  carries no age check of its own. The state used is therefore up to about 3 s old, not
  instantaneous.
- **Late re-prompts under host load.** whisper.cpp checks its abort flag only after the encoder
  finishes and after each decoder step, not during the encoder. On the arm64 API 36 emulator, with
  the Apple silicon laptop heavily loaded (load average about 20), an aborted speech-to-text call
  took up to about 20 s to return, so the re-prompt came late. No action ran: the transcript was
  empty.
- **Language-model misreads remain.** On the held-out set, "good morning" became a gear query and "it's
  muggy" became cooler. On the harness's synthetic clips (host build, `clip`), misheard noisy
  versions of "it's really stuffy in here" became warmer, the opposite direction; the correct
  transcript now gives out of domain, after trailing punctuation is stripped before the model. The confirmation question is the control, not the model's accuracy.
- **A short hazard list.** The eight hazards come from the author's project plan, which is not in this
  repository. There is no exposure or
  controllability rating, no systematic search for situations where the function, working as built, is
  insufficient, and no independent review.
- **Out of scope in this phase.** Barge-in, speaker verification (see TH-1 in
  [threat-model.md](threat-model.md)), languages other than English, more than one climate zone, and
  Fahrenheit.

## Sources

Checked on 2026-09-29.

1. ISO 26262-1:2018, scope and edition: https://committee.iso.org/standard/68383.html
2. ISO/DIS 26262-3 (third edition, draft): https://committee.iso.org/standard/90022.html
3. ISO 21448:2022 and ISO 26262 records, ISO open data: https://isopublicstorageprod.blob.core.windows.net/opendata/_latest/iso_deliverables_metadata/json/iso_deliverables_metadata.jsonl
4. ISO 21448:2022 sample (scope, misuse): https://cdn.standards.iteh.ai/samples/77490/d9843a45e11947e0aa79aaf2f00b65a8/ISO-21448-2022.pdf
5. ISO/SAE 21434:2021, ISO page: https://web.archive.org/web/20260927121109/https://www.iso.org/standard/70918.html
6. ISO/SAE 21434, SAE page: https://web.archive.org/web/20221220203245/https://www.sae.org/standards/content/iso/sae21434/
7. NHTSA, DOT HS 812 574 (ISO 26262 concept phase, ASIL): https://rosap.ntl.bts.gov/view/dot/37210/dot_37210_DS1.pdf
8. NHTSA guidelines, 78 FR 24818: https://www.govinfo.gov/content/pkg/FR-2013-04-26/html/2013-09883.htm
9. NHTSA clarifications, 79 FR 55530: https://www.govinfo.gov/content/pkg/FR-2014-09-16/html/2014-21991.htm
10. AOSP, car UX restrictions: https://source.android.com/docs/automotive/driver_distraction/car_uxr
11. AOSP, consuming driving state and UX restrictions: https://source.android.com/docs/automotive/driver_distraction/consume
12. `CarUxRestrictions` reference: https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions
13. Android Automotive OS parked apps: https://developer.android.com/training/cars/parked/automotive-os
14. ISO 26262-3:2018, concept phase and nominal performance: https://committee.iso.org/standard/68385.html
15. NHTSA report (ASIL A to D): https://rosap.ntl.bts.gov/view/dot/55819/dot_55819_DS1.pdf
