# ADR 0002: Two native modules, each with its own hidden copy of ggml

Status: accepted (2026-09-29)

## Context

Phase 1 runs two C/C++ engines on the arm64 API 36 phone emulator: whisper.cpp v1.9.4 for
speech-to-text ([ADR 0001](0001-speech-to-text-whisper-cpp.md)) and llama.cpp v0.5.0 for the
language-model fallback ([ADR 0005](0005-language-model-choice.md)). Both are built with the Android
NDK and called over JNI.

Both projects vendor ggml, their tensor library, and both define CMake targets named `ggml` and
`ggml-base`. The copies differ: the pinned whisper.cpp tarball carries ggml 0.23.0 and the pinned
llama.cpp tarball carries ggml 0.25.1 (read from each `ggml/CMakeLists.txt`). Building both engines
together causes problems:

- In one CMake project, each upstream adds its own ggml only if no target named `ggml` exists yet. The
  second engine would silently build against the first engine's ggml, a version it was not released
  with.
- As shared libraries, both would produce `libggml.so` and `libggml-base.so`. The APK's
  `lib/arm64-v8a/` folder holds one file per name.
- If ggml's functions were exported from both libraries, the same symbol names would exist twice in
  one process. Whether a call could reach the other library's copy depends on how the loader resolves
  symbols, which was not tested. Hiding the symbols removes the question.

The native code must also come from pinned sources (TH-5 in [threat-model.md](../threat-model.md)).
Its speed matters because SR-7 ([requirements.md](../requirements.md)) discards any action that
cannot start within 5 s of the end of the utterance. A slow engine loses requests rather than acting
late.

## Decision

Two Android library modules, `:native:whisper` and `:native:llama`. Each builds one shared library
from one JNI file. Kotlin reaches it through an internal object (`WhisperNative`, `LlamaNative`). An
engine class wraps that object behind a `:core` interface (`SpeechEngine`, `LmEngine`). The two
libraries never call each other. Both modules use the same settings:

| Setting | Value | Why |
|---|---|---|
| Output | `libearshot_whisper.so`, `libearshot_llama.so` | One ggml per library |
| Linking | Engine and ggml static (`BUILD_SHARED_LIBS OFF`); JNI file built with `-fvisibility=hidden`; linked with `-Wl,--exclude-libs,ALL` and `--gc-sections` | Every symbol from the static archives stays local; only the `JNIEXPORT` functions are exported |
| Source | CMake `FetchContent` of GitHub's generated source archive for the release tag (`archive/refs/tags/v<version>.tar.gz`, not a release asset), with `URL_HASH SHA256` (full hash in each `CMakeLists.txt`) | CMake stops the build if the download does not match (TH-5) |
| Upstream extras | Tests, examples and server off in both; for llama.cpp also tools, the unified app binary, `common` and OpenSSL | Only the core engine library is linked |
| Build type | `Release` in every variant (set in `CMakeLists.txt` and passed from Gradle), `-O3` on the JNI file | Debug and release run the same optimized native code; an unoptimized build was not measured |
| CPU | `GGML_NATIVE` off, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, OpenMP off | Never tune for the build machine; target named explicitly |
| ABI | `arm64-v8a` only (app and both modules), `minSdk` 29 | The Phase 1 target is the arm64 emulator |
| C++ runtime | `c++_static` | See consequences |

The CPU baseline was checked against the target AVD (`earshot_api36`, API 36 `google_apis`
arm64-v8a image on an Apple silicon laptop): its `/proc/cpuinfo` lists `asimddp` (dot product) and
`asimdhp` (half-precision SIMD).

## Alternatives considered

- **One module, one library for both engines.** Rejected: the `ggml` target clash and version mismatch
  above.
- **One ggml shared by both** (`WHISPER_USE_SYSTEM_GGML`, `LLAMA_USE_SYSTEM_GGML`). Rejected: the
  pinned releases carry different ggml versions, so one engine would run on a ggml its release does
  not ship with.
- **ggml as a shared library inside each module.** Rejected: two different files with the same names
  in one APK.
- **Committing the upstream sources, or git submodules.** Not chosen: a tarball URL and hash pin the
  exact bytes in two lines per engine and keep the repository small.
- **A plain armv8-a build, or several CPU variants picked at run time** (`GGML_CPU_ALL_VARIANTS` with
  `GGML_BACKEND_DL`). Not chosen for Phase 1: the only target has the extensions, and run-time
  variants need ggml built as shared libraries, which brings back the file-name clash. Speed without
  the extensions was not measured.

## Consequences

Good:

- Each engine builds against the ggml it was released with, and each can be upgraded on its own.
- Each library exports only its JNI functions (checked by hand, see Evidence), so the two copies of
  ggml do not share exported symbol names.
- A changed upstream tarball stops the build instead of building different code.
- Debug builds run the same optimized native code as release builds, so debug-build timings on the
  emulator are not skewed by an unoptimized ggml.

Bad, or not done:

- Two copies of ggml in the APK, and in memory when both engines are loaded.
- Each library carries its own C++ runtime. The NDK's
  [C++ support guide](https://developer.android.com/ndk/guides/cpp-support) says a static runtime is,
  in general, only for apps with exactly one shared library, and recommends `libc++_shared.so`
  otherwise. Earshot builds two. The APK's third native library, AndroidX's
  `libandroidx.graphics.path.so`, links only against libc, libm and libdl. Earshot relies on no C++
  object, exception or allocation crossing between its two libraries (nothing crosses between them;
  each is called only from Kotlin over JNI) and on the runtime's symbols being hidden. Beyond both
  engines running in one process on the emulator, this is untested. Moving to `c++_shared` is an
  open item.
- On an arm64 CPU without dot product and fp16 (not present on every arm64 CPU), ggml's CPU code
  would most likely crash the app with an illegal instruction. The app does not check CPU
  features at run time and ships no fallback build. No physical phone has been tested.
- No 32-bit ARM and no x86_64 emulator images.
- The native pins live in the two `CMakeLists.txt` files, not in `gradle/libs.versions.toml`, and
  Dependabot does not watch them (it covers Gradle and GitHub Actions). Upgrades are by hand: new tag,
  new hash.
- Any build without a populated `.cxx` directory, including every CI run, needs network access to
  github.com and codeload.github.com (the app itself has no `INTERNET` permission).
- GitHub's [docs](https://docs.github.com/en/repositories/working-with-files/using-files/downloading-source-code-archives)
  say a tag archive's contents change if the tag moves. They also say the compression settings can
  change, with at least six months' notice, which changes the archive's bytes even when the tag does
  not move. `URL_HASH` covers the compressed bytes, so either one fails the build. The pin then has
  to be re-checked by hand. The same page recommends a commit-ID archive for reproducibility and
  release assets for security; neither is used yet.
- Native code is never built unoptimized, which makes stepping through it in a debugger harder.
- The symbol check is manual, not in CI. The JNI layer has no automated test. On Android the real
  engines run only in the manual test plan. llama.cpp also runs on the host in the
  [language-model evaluation](../lm-eval/README.md) harness, outside JNI and the Android build.

## Evidence

- Exported symbols, checked by hand with the NDK's `llvm-nm -D --defined-only` on the debug build's
  merged libraries (re-run 2026-09-29): `libearshot_whisper.so` exports four functions (`init`, `free`,
  `abort`, `transcribe`) and `libearshot_llama.so` five (`load`, `free`, `abort`, `formatChat`,
  `generate`). All are `Java_...` JNI entry points; nothing else is exported.
- The debug APK's `lib/arm64-v8a/` holds `libearshot_whisper.so`, `libearshot_llama.so` and AndroidX's
  `libandroidx.graphics.path.so`, and no `libc++_shared.so` (checked with `unzip -l`). `llvm-readelf -d`
  lists only libc, libm and libdl as the AndroidX library's dependencies.
- CPU features: `/proc/cpuinfo` on `earshot_api36` (setup in
  [manual-test-plan.md](../manual-test-plan.md)) lists `asimddp` and `asimdhp`.
- CI: the `app` job in [`ci.yml`](../../.github/workflows/ci.yml) runs `:app:assembleDebug`, which
  fetches both tarballs, checks their hashes and compiles both libraries with the NDK and CMake
  versions pinned in [`libs.versions.toml`](../../gradle/libs.versions.toml).
- Both libraries in one process, on `earshot_api36` (Apple silicon host, debug build): in the
  [manual test plan](../manual-test-plan.md), M-16 and M-17 pass, and M-1 and M-9 were re-run with
  the language model loaded, unchanged (all `clip`).
- Supply-chain threat and residual risk: TH-5 in [threat-model.md](../threat-model.md).
- Code: [whisper `CMakeLists.txt`](../../native/whisper/src/main/cpp/CMakeLists.txt),
  [llama `CMakeLists.txt`](../../native/llama/src/main/cpp/CMakeLists.txt),
  [`native/whisper/build.gradle.kts`](../../native/whisper/build.gradle.kts),
  [`native/llama/build.gradle.kts`](../../native/llama/build.gradle.kts),
  [`app/build.gradle.kts`](../../app/build.gradle.kts).
