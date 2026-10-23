# Host engines

Command-line builds of the app's two native engines, so the regression harness can run the same
speech-to-text and language-model code on a laptop or a CI machine instead of a phone.

| Binary | Mirrors | Input (one per stdin line) | Output (one JSON object per stdout line) |
|---|---|---|---|
| `whisperhost` | `native/whisper` (`whisper_jni.cpp`, `WhisperSpeechEngine`) and core `WavReader`, `AudioGate` | absolute path of a WAV file | `{"text", "confidence", "ms", "raw", "gated", "audio_s"}` |
| `lmhost` | `native/llama` (`llama_jni.cpp`, `LlamaLmEngine`) | the user's text | `{"raw", "ms"}` |

Both are CPU only (no Metal, no Accelerate or other BLAS), like the app. Both default to 2 threads,
like the app, and accept `--threads N`. `--info` prints one JSON line with the engine, its version,
the SHA-256 of its source tarball, the thread count and ggml's CPU feature list, then exits; the
harness uses it to label results.

## Build

```bash
scripts/build-host-engines.sh              # both; prints whisperhost=... and lmhost=... paths
scripts/build-host-engines.sh lmhost       # just one
```

Output goes to `tools/host/build/` (ignored by git). Needs CMake 3.22 or newer, Ninja and a C++17
compiler; if `cmake`/`ninja` are not on `PATH`, the ones in the Android SDK (`cmake/3.31.6`) are used.
Tested on macOS arm64. Linux (x86_64 and arm64) is expected to work but has not been run yet.

**Same source as the app.** Each CMake project reads `WHISPER_VERSION`/`WHISPER_SHA256` or
`LLAMA_VERSION`/`LLAMA_SHA256` out of the app's `native/*/src/main/cpp/CMakeLists.txt` and fetches
that release tarball with the hash check. Bumping a pin there changes both builds.

**CPU kernels.** On arm64 hosts ggml is built for the app's baseline (`armv8.2-a+dotprod+fp16`,
`GGML_NATIVE` off). On x86_64 there is nothing to mirror, so ggml is tuned for the build machine.
Override the arm64 baseline with `-DEARSHOT_HOST_ARM_ARCH=...` if a Linux arm64 machine lacks those
extensions.

## whisperhost

```bash
whisperhost [--threads N] models/ggml-tiny.en.bin   < wav-paths.txt
```

Per clip, in the app's order:

1. Read the WAV like core `WavReader`: 16-bit PCM only, channels averaged, linear resampling to
   16 kHz.
2. Audio gate like core `AudioGate`: under 0.3 s or RMS under 0.003 gives `{"text":"",
   "confidence":0, "gated":true}` and whisper is not run. Audio over 8 s is cut to 8 s.
3. whisper with the parameters in `whisper_jni.cpp`: greedy, English, no context, no timestamps,
   single segment, non-speech tokens suppressed, no temperature fallback, at most 48 tokens.
4. Confidence is the mean probability of the text tokens (ids below end-of-text), `null` when there
   are none.
5. Bracketed non-speech tags (`[...]`, `(...)`, `*...*`) are removed and whitespace collapsed, like
   `AudioGate.clean`. If no letter or digit is left, the result is `""` with confidence 0.

`text` and `confidence` are what the app's speech engine would pass on. `raw` is whisper's own text
before step 5 (`null` if whisper did not run). `ms` is wall time for steps 2 to 5, without the file
read. A file that cannot be read gives `"text":""`, `"confidence":null` and an `"error"` field.

`whisperhost --self-test` checks the step 5 mirror against cases whose Kotlin result is known.

## lmhost

```bash
lmhost [--threads N] [--ctx N] models/Qwen3-0.6B-Q4_0.gguf SPEC GRAMMAR   < texts.txt
```

See [lmhost/README.md](lmhost/README.md) for the spec and grammar files. Like the app, it formats the
prompt with the model's own chat template, keeps the system prompt and examples decoded in the KV
cache, and samples greedily under the GBNF grammar. Also like the app, it runs one warm-up request
("hello", 1 token) before reading stdin, so `ms` is the cost of one request with the prefix already
cached. `raw` is the generated text, unparsed; the caller parses it (the harness uses core
`LmWireFormat.parse`). On a failure (prompt too long, decode or grammar error) `raw` is `null` and an
`"error"` field says why; the app treats the same cases as "not understood".

## What was checked

On an Apple M1 laptop, macOS 26.4, 2 threads:

- `whisperhost` with `ggml-tiny.en.bin` on clips made with the macOS `say` voice Samantha (synthetic
  audio): "set the temperature to 21" (16 kHz) and "turn on the rear defroster" (22.05 kHz,
  resampled) came back as "Set the temperature to 21." (confidence 0.98) and "Turn on the rear
  defrostor." (0.79). One second of silence and a 0.19 s clip were gated without running whisper.
  Two seconds of white noise came back as "You" with confidence 0.06. About 520 to 650 ms per clip
  on that machine.
- `lmhost` with `Qwen3-0.6B-Q4_0.gguf` and a spec built from `LmWireFormat`: the prompt prefix is
  409 tokens, as recorded in `docs/lm-eval`, and its outputs for all 30 dev and 32 held-out
  utterances in `docs/lm-eval` are identical to the recorded results of the shipped variant. Median
  about 340 ms per request on that machine.

These are host numbers. They say nothing about latency on a phone or an in-car computer.
