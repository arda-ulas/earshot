#!/usr/bin/env bash
# Builds the host engines used by the regression harness (whisperhost, lmhost) into tools/host/build/
# and prints the binary paths. The sources are fetched from the same release tarballs, checked
# against the same SHA-256 pins, as the app's native libraries. CPU only; macOS arm64 and Linux.
#
#   scripts/build-host-engines.sh              build both
#   scripts/build-host-engines.sh whisperhost  build one (whisperhost | lmhost)
#
# Needs CMake >= 3.22, Ninja and a C++17 compiler. If cmake/ninja are not on PATH, the ones bundled
# with the Android SDK (cmake/3.31.6) are used.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
build="$root/tools/host/build"

sdk_cmake="${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/3.31.6/bin"
if ! command -v cmake >/dev/null 2>&1 || ! command -v ninja >/dev/null 2>&1; then
  if [[ -x "$sdk_cmake/cmake" && -x "$sdk_cmake/ninja" ]]; then
    export PATH="$sdk_cmake:$PATH"
  else
    echo "cmake and ninja are needed (not on PATH, not in $sdk_cmake)" >&2
    exit 1
  fi
fi

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then targets=(whisperhost lmhost); fi

for t in "${targets[@]}"; do
  case "$t" in
    whisperhost|lmhost) ;;
    *) echo "unknown target: $t (expected whisperhost or lmhost)" >&2; exit 1 ;;
  esac
  cmake -S "$root/tools/host/$t" -B "$build/$t" -G Ninja -DCMAKE_BUILD_TYPE=Release >&2
  cmake --build "$build/$t" --target "$t" >&2
done

for t in "${targets[@]}"; do
  echo "$t=$build/$t/$t"
done
