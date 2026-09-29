cmake_minimum_required(VERSION 3.22)
project(earshot_whisper C CXX)

# Always optimized, whatever the Gradle variant (an unoptimized ggml is unusably slow).
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp pinned by release tag and tarball SHA-256 (supply chain, TH-5).
set(WHISPER_VERSION "1.9.4")
set(WHISPER_SHA256 "57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# armv8.2-a with dot product and fp16: present on the arm64 emulator on Apple silicon and on
# recent phones. Checked against /proc/cpuinfo (asimddp, asimdhp) on the target AVD.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${WHISPER_VERSION}.tar.gz
    URL_HASH SHA256=${WHISPER_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(earshot_whisper SHARED whisper_jni.cpp)
target_compile_features(earshot_whisper PRIVATE cxx_std_17)
target_compile_options(earshot_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(earshot_whisper PRIVATE whisper log)
# Keep ggml's symbols private to this library so the language-model library's ggml cannot clash.
target_link_options(earshot_whisper PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
