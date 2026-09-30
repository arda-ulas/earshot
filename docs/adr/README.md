# Architecture decision records

Each record states the context, the decision, the alternatives considered, the consequences and the
evidence. All five were accepted on 2026-09-29 for Phase 1, which runs on the arm64 API 36 phone
emulator against a simulated vehicle.

| No. | Title | Summary |
|---|---|---|
| [0001](0001-speech-to-text-whisper-cpp.md) | Speech-to-text with whisper.cpp tiny.en over JNI | On-device, offline speech-to-text: whisper.cpp v1.9.4 with tiny.en, greedy, 48-token cap, 2 threads, no temperature fallback, 10 s time limit; confidence is the mean text-token probability. |
| [0002](0002-native-module-split.md) | Two native modules, each with its own hidden copy of ggml | whisper.cpp and llama.cpp each vendor a different ggml, so each engine builds into its own `.so` with ggml linked statically and only the JNI functions exported. |
| [0003](0003-fail-safe-defaults.md) | Fail-safe defaults for unknown or late inputs | When an input is unknown or a stage is late, assume the value under which the assistant does less: unknown driving state is moving, unknown confidence means no action, an unreadable defrost is on. |
| [0004](0004-language-model-gating.md) | Gate the language model behind the rules, a strict parser and a spoken yes | The model runs only on a confident rule miss; its output is parsed strictly in Kotlin, goes through the policy and always needs a spoken yes. |
| [0005](0005-language-model-choice.md) | Qwen3-0.6B (Q4_0) as the language model, choosing one intent label | Qwen3-0.6B picks one of ten intent labels; it scored 28/32 on a held-out set of 32 typed phrases, with no wrong-direction answers. |

Related: hazards and safety goals in [safety.md](../safety.md), threats in
[threat-model.md](../threat-model.md), requirements in [requirements.md](../requirements.md).
