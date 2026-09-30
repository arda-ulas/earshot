# ADR 0005: Qwen3-0.6B (Q4_0) picking one intent label

Status: accepted (2026-09-29)

## Context

The rules miss indirect requests such as "I'm freezing". [ADR 0004](0004-language-model-gating.md)
adds an on-device language model as a fallback for them, behind a strict parser, the policy and a
spoken yes. This ADR records which model runs there and what it is asked to output.

The model had to meet five criteria:

- at most 1B parameters;
- a 4-bit (Q4_0) GGUF file;
- runs in the pinned llama.cpp v0.5.0 with its own chat template;
- a permissive licence, so `scripts/fetch-models.sh` can download the file for local use and the
  repository never stores or redistributes it;
- an ungated download: no login and no terms to accept.

Every model command is confirmed, so a wrong answer costs a declined question, not an action. The
measures that mattered were held-out accuracy, correct refusals of out-of-domain requests, and
wrong-direction answers (warming a hot driver or cooling a cold one), because a driver who says yes
out of habit would act on those.

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
- Exact values are the rules' job. "Set it to 22" is matched by the rules and never reaches the model.
  The fallback is for requests with no value in them.
- The step is fixed in Kotlin at 2 °C, not chosen by the model. Temperature bounds still apply (SR-3).
- The label set is small enough to test exhaustively, and the grammar is two lines.
- On the held-out set, labels scored 28/32 against 25/32 for the same model writing whole commands.
  Three items is close to the noise of a 32-item set, so the design reasons above carry at least as
  much weight as the score.

### Evaluation

Summarised from [docs/lm-eval](../lm-eval/README.md). A host program built against the same pinned
llama.cpp source mirrors the JNI path: same chat template, prefix cache, grammar and greedy sampling.
One agent per model and format tuned the prompt on a 30-item dev set, in at most 12 runs, under rules
against copying dev items into the prompt. A separate agent audited each result for leakage. The 32-item held-out set was written
after tuning and kept away from the tuning agents. It has 17 in-domain requests and 15 out-of-domain
ones, including near-domain traps (trunk, headlights, mirrors, steering-wheel heater, speed limit)
and one injection attempt. These are typed-text results on the host, not `clip` or `mic` results.

| Model, format | Dev (tuned on) | Held-out | Held-out out-of-domain correct | Wrong direction | Prefix tokens |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct, command, first prompt | 3/30 | 3/32 | 1/15 | 5 | 213 |
| Qwen2.5-0.5B-Instruct, command, tuned | 22/30 | 20/32 | 13/15 | 0 | 390 |
| Qwen2.5-0.5B-Instruct, intent, tuned | 18/30 | 17/32 | 13/15 | 0 | 378 |
| Qwen3-0.6B, command, tuned | 22/30 | 25/32 | 15/15 | 0 | 373 |
| **Qwen3-0.6B, intent, tuned (shipped)** | 27/30 | 28/32 | 14/15 | 0 | 409 |
| Granite-4.0-350M, command, tuned | 19/30 | 16/32 | 10/15 | 2 | 383 |
| Granite-4.0-350M, intent, tuned | 20/30 | 18/32 | 15/15 | 3 | 400 |

Limits, stated plainly:

- 32 items is a small set. One item is about 3 points. Treat differences of one or two items as noise.
- The inputs are typed text, not speech. Speech-to-text errors are not part of these numbers.
- The auditors flagged "minor" leakage in every variant, mostly from three seed examples in all
  starting prompts. "I'm freezing" is itself a dev item, so dev scores are inflated. The seed example
  "I can't see out the windshield" is close to the held-out item "I can't see through the windshield".
- There is one injection-style item. The shipped prompt labelled it `out_of_domain`. One typed item
  is not a security test.

### Measured cost

Measured on the arm64 API 36 emulator on an Apple silicon laptop, with clip input. These are not
in-vehicle or on-phone figures.

| What | Value |
|---|---|
| Model file | 429 MB (428,970,080 bytes), downloaded by script, not in the APK |
| Fixed prompt prefix | 409 tokens, decoded once in the background after start-up: 8.2 s and 5.4 s in two runs, then kept in the KV cache |
| Language-model stage per request | 0.5-2.0 s (only the new turn is decoded) |

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
| Qwen2.5-0.5B-Instruct (Apache-2.0, licence file in the GGUF repo, not gated; [metadata](https://huggingface.co/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF)) | The first choice. With the first prompt it scored 3/32 with 5 wrong-direction answers; on the emulator "I'm freezing" became 16 °C and "order me a pizza" became AC on. Tuned, it reached 20/32. Replaced. |
| Granite-4.0-350M (tagged Apache-2.0 on the base and GGUF repos, no licence file in either, not gated; [metadata](https://huggingface.co/api/models/ibm-granite/granite-4.0-350m-GGUF)) | Evaluated. 16/32 and 18/32, with 2 and 3 wrong-direction answers. |
| Qwen3-0.6B writing whole commands | 25/32, 15/15 out of domain, 0 wrong direction. Close to the shipped result, but the model chooses values. |
| Gemma 3 1B | Excluded before the evaluation. The repository is gated: it asks for a login and acceptance of the licence, and an anonymous download of `config.json` returned HTTP 401 ([metadata](https://huggingface.co/api/models/google/gemma-3-1b-it)). The licence is the Gemma Terms of Use, a custom licence whose redistribution conditions include passing on its use restrictions and shipping a Notice file ([terms](https://ai.google.dev/gemma/terms)). |
| LFM2 | Not tried: not Apache-licensed. |
| SmolLM2 | Not tried: no Q4_0 file. |

## Consequences

Good:

- The shipped setup scored best on the held-out set: 28/32, 14/15 out of domain, no wrong-direction
  answers.
- The model's only freedom is one label from a closed list. Values are fixed in Kotlin and tested.
- The licence is permissive and the download ungated. The script fetches a pinned revision and checks
  size and SHA-256, and the app checks again before loading (SR-12). A missing or changed file
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
  yet (M-15 is pending). The regression harness planned for the next phase re-measures the fallback
  together with speech-to-text, noise and the policy.
- The prompt was tuned for this model, and the empty think block is specific to Qwen3. Changing the
  model means re-tuning and re-running the evaluation.
- The model adds a 429 MB download. Its memory use is not recorded in this phase.
- The host program mirrors the JNI path, but it is not the device. The JNI layer has no automated
  test; the real engine runs only in the manual test plan.

## Evidence

- Evaluation method, data, per-item outputs and how to reproduce: [docs/lm-eval](../lm-eval/README.md)
  (shipped prompt: `variants/intent-qwen3-best.json`; per-item held-out outputs:
  `results/intent-qwen3-best-test.json`).
- Unit tests (JVM), mapped to SR-9, SR-10 and SR-11 in [traceability.md](../traceability.md):
  [LmWireFormatTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/interpret/LmWireFormatTest.kt)
  (each label maps to one fixed command; exact wire form only; grammar lists every label and nothing
  else), [LmInterpreterTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/interpret/LmInterpreterTest.kt)
  (invalid output, timeout, failure) and
  [TurnEngineTest](../../core/src/test/kotlin/io/github/ardaulas/earshot/core/turn/TurnEngineTest.kt)
  (U10: "I'm freezing" -> `warmer` -> question -> yes -> 23 °C). Requirements in
  [requirements.md](../requirements.md).
- [Manual test plan](../manual-test-plan.md), clip input on the emulator: M-16 ("I'm freezing" ->
  `warmer` -> question; yes raised 21 to 23 °C, read back; no did nothing) and M-17 ("Order me a
  pizza" -> `out_of_domain` -> refused) pass. The 8.2 s prefix decode and the 0.5-2.0 s stage are
  recorded there. M-15 (mic) is pending.
- Code: [LmWireFormat.kt](../../core/src/main/kotlin/io/github/ardaulas/earshot/core/interpret/LmWireFormat.kt),
  [LlamaLmEngine.kt](../../native/llama/src/main/kotlin/io/github/ardaulas/earshot/llama/LlamaLmEngine.kt),
  [llama_jni.cpp](../../native/llama/src/main/cpp/llama_jni.cpp),
  [AssistantViewModel.kt](../../app/src/main/kotlin/io/github/ardaulas/earshot/app/AssistantViewModel.kt)
  (warm-up), [models/manifest.json](../../models/manifest.json).
