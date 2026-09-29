# Language-model fallback: evaluation

How the on-device model, wire format and prompt for the fallback interpreter were chosen. The fallback
only runs when the hand-written rules miss, for indirect requests such as "I'm freezing". Whatever it
returns is parsed strictly, goes through the policy, and needs a spoken yes before anything happens
(SG-7), so a wrong answer costs a declined question, not an action.

## Method

- **Same code path as the phone.** `lmhost/lmhost.cpp` mirrors `native/llama/src/main/cpp/llama_jni.cpp`:
  the model's own chat template via `llama_chat_apply_template`, the system prompt and examples decoded
  once and kept in the KV cache, GBNF-constrained greedy sampling. It is built against the same pinned
  llama.cpp v0.5.0 source. Greedy decoding makes results deterministic. The app's output on the
  emulator matched the host exactly for the first prompt ("I'm freezing" -> 16 °C,
  "order me a pizza" -> AC on).
- **Candidates:** instruction-tuned models of at most 1B parameters with an Apache-2.0 licence, an
  ungated download and a Q4_0 GGUF: Qwen2.5-0.5B-Instruct (the first choice), Qwen3-0.6B and
  Granite-4.0-350M. Gemma 3 1B was excluded earlier (gated download, Gemma terms); LFM2 (non-Apache
  licence) and SmolLM2 (no Q4_0 file) were not tried.
- **Two wire formats:** `command` (the model writes a whole command, e.g.
  `{"cmd":"adjust_temp","delta":2}`) and `intent` (the model picks one label, e.g. `{"intent":"warmer"}`,
  and Kotlin maps it to a fixed command).
- **Tuning:** one agent per model and format rewrote only the system prompt and few-shot examples
  against `dev.json` (30 utterances), at most 12 runs each, with rules against copying dev items into
  the prompt. A separate agent then audited each result for leakage.
- **Held-out test:** `test.json` (32 utterances) was written after tuning and kept out of the tuning
  agents' folder. It includes near-domain traps (headlights, trunk, mirrors, steering-wheel heater,
  speed limit) and an injection attempt.
- **Scoring (`bench.py`):** an answer is correct if its label is in the accepted set for that
  utterance; "wrong direction" counts warming for a hot driver or cooling for a cold one.

## Results

| Model, format | Dev (tuned on) | Held-out test | Test out-of-domain correct | Test wrong direction | Prompt prefix tokens |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct, command, first prompt | 3/30 | 3/32 | 1/15 | 5 | 213 |
| Qwen2.5-0.5B-Instruct, command, tuned | 22/30 | 20/32 | 13/15 | 0 | 390 |
| Qwen2.5-0.5B-Instruct, intent, tuned | 18/30 | 17/32 | 13/15 | 0 | 378 |
| Qwen3-0.6B, command, tuned | 22/30 | 25/32 | 15/15 | 0 | 373 |
| **Qwen3-0.6B, intent, tuned (shipped)** | 27/30 | 28/32 | 14/15 | 0 | 409 |
| Granite-4.0-350M, command, tuned | 19/30 | 16/32 | 10/15 | 2 | 383 |
| Granite-4.0-350M, intent, tuned | 20/30 | 18/32 | 15/15 | 3 | 400 |

Shipped variant: `variants/intent-qwen3-best.json`, copied into `LmWireFormat.kt`. Its four held-out
misses: "I can't feel my fingers" -> out of domain (no action), "it's muggy" -> cooler, "clear the
rear window" -> out of domain, "good morning" -> query_gear (would be asked as "Tell you your gear?").

## Limits

- 30 and 32 utterances are small sets; one item is about 3 points. Treat differences of one or two
  items as noise.
- All utterances are typed text, not speech: speech-to-text errors are not part of this evaluation.
- The auditors flagged "minor" leakage for every variant, mostly from three seed examples present in
  all starting prompts ("I'm freezing" is itself a dev item). Dev scores are inflated by that; the
  held-out scores are not, except that the seed example "I can't see out the windshield" is close to
  the test item "I can't see through the windshield".
- These numbers describe the fallback's interpretation only. The next phase's regression harness is
  where it gets measured together with speech-to-text, noise and the policy.

## Reproduce

Models go in `models/` (Qwen3 and Qwen2.5 via `scripts/fetch-models.sh`; Granite from
`https://huggingface.co/ibm-granite/granite-4.0-350m-GGUF`, revision
`b8208a86a58427e1739265318028eb5895b74bf2`, SHA-256
`a9db4c0ab23bb3c676f5fea5521d7a9301c577834a1cabf3430ab826fb68195a`).

```bash
cmake -S docs/lm-eval/lmhost -B docs/lm-eval/lmhost/build -G Ninja -DLLAMA_SRC=/path/to/llama.cpp-0.5.0
cmake --build docs/lm-eval/lmhost/build --target lmhost
python3 docs/lm-eval/bench.py docs/lm-eval/variants/intent-qwen3-best.json test
```
