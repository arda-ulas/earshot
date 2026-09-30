# ADR 0005: Qwen3-0.6B (Q4_0) as the language model, choosing one intent label

Status: accepted (2026-09-29)

## Context

The rules miss indirect requests such as "I'm freezing". [ADR 0004](0004-language-model-gating.md)
adds an on-device language model as a fallback for them, behind a strict parser, the policy and a
spoken yes. This ADR records which model runs there and what it is asked to output.

The model had to meet five criteria:

- at most 1B parameters;
- a 4-bit (Q4_0) GGUF file;
- runs in the pinned llama.cpp v0.5.0 using the model's own chat template;
- an Apache-2.0 licence, which lets a user fetch the file with `scripts/fetch-models.sh` and use it
  locally (the repository itself never stores or redistributes the file);
- an ungated download: no login and no terms to accept.

Every command the model proposes needs a spoken yes, and `out_of_domain` is refused. So a wrong
label ends in a refusal or a question the driver can decline, not in a direct action. The measures
that mattered were held-out accuracy, correct refusals of out-of-domain requests, and
wrong-direction answers (warming a hot driver or cooling a cold one), because a driver who says yes
out of habit would let them through.

## Decision

Use Qwen3-0.6B, Q4_0, from `ggml-org/Qwen3-0.6B-GGUF` at revision
`b5f37287796e5be0ea3dab2e7430873fb3f73e49`. `models/manifest.json` pins its size (428,970,080 bytes)
and SHA-256. It runs in `:native:llama` with 2 threads, a 1024-token context, greedy sampling under
a GBNF grammar, at most 16 generated tokens and a 10 s timeout. An empty think block after the
assistant marker switches Qwen3's reasoning off.

The model outputs one intent label, `{"intent":"<label>"}`, from ten: `warmer`, `cooler`, `ac_on`,
`ac_off`, `defrost_front_on`, `defrost_rear_on`, `query_speed`, `query_gear`, `query_cabin`,
`out_of_domain`. Kotlin maps each label to one fixed command. `warmer` and `cooler` are
`AdjustTemp(+2)` and `AdjustTemp(-2)`. The prompt is the tuned variant
`docs/lm-eval/variants/intent-qwen3-best.json`, copied into `LmWireFormat.kt`.

### Why labels, not whole commands

- A 0.6B model makes one free choice: which of ten labels. It never writes a number, a field name or
  a command outside the list.
- Exact values are the rules' job. "Set the temperature to 22" is matched by the rules and never
  reaches the model. A value phrased without "temperature", "degrees", "heat" or "thermostat"
  ("set it to 22") is a rule miss, so it goes to the model like any other confident miss. No label
  carries a value, so the result is a refusal or a question about one fixed command, which the
  driver can decline.
- The step is fixed in Kotlin at 2 °C, not chosen by the model. Temperature bounds still apply (SR-3).
- Every label's mapping to its command is unit-tested, and the grammar is two lines.
- On the held-out set, labels scored 28/32 against 25/32 for the same model writing whole commands.
  The command format refused one more out-of-domain item (15/15 against 14/15), but its prompt
  contains one of those items as an example (see the limits below). Three items is close to the
  noise of a 32-item set, so the design reasons above carry at least as much weight as the score.

### Evaluation

Summarised from [docs/lm-eval](../lm-eval/README.md). A host program built against the same pinned
llama.cpp source mirrors the JNI path: same chat template, prefix cache, grammar and greedy sampling.
One AI agent per model and format tuned the prompt on a 30-item dev set, in at most 12 runs, under
rules against copying dev items into the prompt. A separate agent audited each result for leakage.
The 32-item held-out set was written after tuning and kept out of the tuning agents' folder. It has
17 in-domain requests and 15 out-of-domain ones, including near-domain traps (trunk, headlights,
mirrors, steering-wheel heater, speed limit) and one injection attempt. These are typed-text results
on the host, not `clip` or `mic` results.

| Model, format | Dev (tuned on) | Held-out | Held-out out-of-domain correct | Wrong direction | Prefix tokens |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct, command, first prompt | 3/30 | 3/32 | 1/15 | 5 | 213 |
| Qwen2.5-0.5B-Instruct, command, tuned | 22/30 | 20/32 | 13/15 | 0 | 390 |
| Qwen2.5-0.5B-Instruct, intent, tuned | 18/30 | 17/32 | 13/15 | 0 | 378 |
| Qwen3-0.6B, command, tuned | 22/30 | 25/32 | 15/15 | 0 | 373 |
| **Qwen3-0.6B, intent, tuned (shipped)** | 27/30 | 28/32 | 14/15 | 0 | 409 |
| Granite-4.0-350M, command, tuned | 19/30 | 16/32 | 10/15 | 2 | 383 |
| Granite-4.0-350M, intent, tuned | 20/30 | 18/32 | 15/15 | 3 | 400 |

Every cell has a per-item file in `docs/lm-eval/results/` except the first-prompt dev score (3/30),
which is taken from the evaluation README and has no per-item file.

Limits, stated plainly:

- 32 items is a small set. One item is about 3 points. Treat differences of one or two items as noise.
- The inputs are typed text, not speech. Speech-to-text errors are not part of these numbers.
- The auditors flagged "minor" leakage in every variant, mostly from three seed examples in all
  starting prompts. "I'm freezing" is itself a dev item, so dev scores are inflated. The seed example
  "I can't see out the windshield" is close to the held-out item "I can't see through the windshield".
- Five of the six tuned prompts, but not the shipped one, contain one or two held-out utterances word
  for word as examples, and each of those items scored correct. For example, "turn on the headlights"
  is an example in the Qwen3 command prompt, so its 25/32 and 15/15 include an item the prompt
  already answers (see [ai-log.md](../ai-log.md)).
- There is one injection-style item. The shipped prompt labelled it `out_of_domain`. One typed item
  is not a security test.

### Measured cost

Measured on the arm64 API 36 emulator on an Apple silicon laptop, with clip input. These are not
in-vehicle or on-phone figures.

| What | Value |
|---|---|
| Model file | 429 MB (428,970,080 bytes), downloaded by script, not in the APK |
| Fixed prompt prefix | 409 tokens, decoded once in the background after start-up (8.2 s and 5.4 s in two runs; the manual test plan records the 8.2 s run), then kept in the KV cache |
| Language-model stage per request | 0.5-2.0 s in the M-16 clip turns (only the new turn is decoded) |

### Licences

- Qwen3-0.6B is Apache-2.0 and not gated. The source repository contains the full Apache 2.0 text
  ([metadata](https://huggingface.co/api/models/Qwen/Qwen3-0.6B),
  [LICENSE](https://huggingface.co/Qwen/Qwen3-0.6B/raw/main/LICENSE)).
- The file used is the `ggml-org/Qwen3-0.6B-GGUF` conversion. It declares Apache-2.0, names
  Qwen/Qwen3-0.6B as its base model, says it was converted automatically, and is not gated. It has no
  LICENSE file of its own ([metadata](https://huggingface.co/api/models/ggml-org/Qwen3-0.6B-GGUF)).
- llama.cpp is MIT-licensed, copyright the ggml authors
  ([licence API](https://api.github.com/repos/ggml-org/llama.cpp/license)). It also vendors
  third-party code under its own licences
  ([vendor/](https://api.github.com/repos/ggml-org/llama.cpp/contents/vendor)). The JNI build turns
  off llama.cpp's common, tools, server and example targets and links only the core library.

Checked on 2026-09-29 from published licence files and repository metadata. This is not legal advice.

## Alternatives considered

| Candidate | Outcome |
|---|---|
| Qwen2.5-0.5B-Instruct (Apache-2.0, licence file in the GGUF repo, not gated; [metadata](https://huggingface.co/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF)) | The first choice. With the first prompt it scored 3/32, with 5 wrong-direction answers. On the emulator (clip), it proposed 16 °C for "I'm freezing" and AC on for "order me a pizza". The confirmation question exposed both, and nothing happened without a yes. Tuned, it reached 20/32. Replaced. |
| Granite-4.0-350M (tagged Apache-2.0 on the base and GGUF repos, no licence file in either, not gated; [metadata](https://huggingface.co/api/models/ibm-granite/granite-4.0-350m-GGUF)) | Evaluated, not chosen. Held-out 16/32 (command) and 18/32 (intent). Its tuned variants were the only tuned ones with wrong-direction answers (2 and 3). |
| Qwen3-0.6B writing whole commands | 25/32, 15/15 out of domain, 0 wrong direction. Close to the shipped result, but the model chooses values. |
| Gemma 3 1B | Excluded before the evaluation. The repository is gated: it asks for a login and acceptance of the licence, and an anonymous download of `config.json` returned HTTP 401 ([metadata](https://huggingface.co/api/models/google/gemma-3-1b-it)). The licence is the Gemma Terms of Use, a custom licence whose redistribution conditions include passing on its use restrictions and shipping a Notice file ([terms](https://ai.google.dev/gemma/terms)). |
| LFM2 | Not tried: its licence is not Apache-2.0, so it fails the licence criterion. |
| SmolLM2 | Not tried: no Q4_0 file. |

The LFM2 and SmolLM2 screenings were recorded without a source link, unlike the rows above, and were
not re-checked for this ADR.

## Consequences

Good:

- The shipped setup had the highest held-out score, 28/32, with no wrong-direction answers. Two
  other variants refused all 15 out-of-domain items against its 14/15, and its 3-item lead over the
  runner-up is close to the noise of a 32-item set.
- The model's only freedom is one label from a closed list. Values are fixed in Kotlin and tested.
- The licence is Apache-2.0 and the download ungated. The script fetches a pinned revision and
  checks size and SHA-256, and the app checks again before loading (SR-12). A missing or changed file
  disables only the fallback; the rules keep working.
- The fixed prompt is decoded once, so each request decodes only the new turn.

Bad, or not done:

- Fan changes and turning a defroster off are not reachable through the fallback: no label maps to
  them. Neither is an exact temperature; `warmer` and `cooler` always mean 2 °C. These need a direct
  command that the rules match.
- The first request after start-up may wait for the warm-up. The engine runs one call at a time, and
  the wait counts toward the 10 s language-model timeout. A timeout is "not understood": refused, no
  action.
- Four known held-out misses. "I can't feel my fingers" and "clear the rear window" were labelled
  `out_of_domain` (refused, no action). "It's muggy" was labelled `cooler` (the accepted answer was
  `ac_on`), which would ask "Lower the temperature by 2 degrees?". "Good morning" was labelled
  `query_gear`, which would ask "Tell you your gear?". Each ends in a refusal or a question the driver
  can decline.
- Accuracy on speech is unknown. The evaluation is 32 typed items, and there are no `mic` results
  yet (M-15 is pending). The regression harness planned for the next phase is meant to measure the
  fallback together with speech-to-text, noise and the policy.
- The prompt was tuned for this model, and the empty think block is specific to Qwen3. Changing the
  model means re-tuning and re-running the evaluation.
- The model adds a 429 MB download. Its memory use is not recorded in this phase.
- The host program mirrors the JNI path, but it is not the device. The JNI layer has no automated
  test; the real engine runs only in the manual test plan.

## Evidence

- Evaluation method, data, per-item outputs and how to reproduce: [docs/lm-eval](../lm-eval/README.md)
  (shipped prompt: `variants/intent-qwen3-best.json`; per-item held-out outputs:
  `results/intent-qwen3-best-test.json`).
- Unit tests (JVM), mapped to SR-8, SR-9, SR-10 and SR-11 in [traceability.md](../traceability.md):
  [LmWireFormatTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/interpret/LmWireFormatTest.kt)
  (each label maps to one fixed command; no label produces a conversation, screen or
  visibility-reducing command; exact wire form only; grammar lists every label and nothing else),
  [LmInterpreterTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/interpret/LmInterpreterTest.kt)
  (invalid output, timeout, failure) and
  [TurnEngineTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/turn/TurnEngineTest.kt)
  (U10, with a scripted fake model returning `warmer`: "I'm freezing" -> question -> yes -> 23 °C).
  Requirements in [requirements.md](../requirements.md).
- [Manual test plan](../manual-test-plan.md), clip input on the arm64 API 36 emulator on an Apple
  silicon laptop: M-16 ("I'm freezing" -> `warmer` -> question; yes raised 21 to 23 °C, read back;
  no did nothing) and M-17 ("Order me a pizza" -> `out_of_domain` -> refused; Samantha's clip only,
  Daniel's was re-prompted below the confidence threshold before the model ran) pass. The 8.2 s
  prefix decode and the 0.5-2.0 s stage are recorded there. M-15 (mic) is pending.
- Code: [LmWireFormat.kt](../../core/src/main/kotlin/io/github/ardaulas/earshot/core/interpret/LmWireFormat.kt),
  [LlamaLmEngine.kt](../../native/llama/src/main/kotlin/io/github/ardaulas/earshot/llama/LlamaLmEngine.kt),
  [llama_jni.cpp](../../native/llama/src/main/cpp/llama_jni.cpp),
  [AssistantViewModel.kt](../../app/src/main/kotlin/io/github/ardaulas/earshot/app/AssistantViewModel.kt)
  (warm-up), [models/manifest.json](../../models/manifest.json).
