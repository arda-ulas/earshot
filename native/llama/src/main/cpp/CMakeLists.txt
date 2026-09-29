cmake_minimum_required(VERSION 3.22)
project(earshot_llama C CXX)

# Always optimized, whatever the Gradle variant (an unoptimized ggml is unusably slow).
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp pinned by release tag and tarball SHA-256 (supply chain, TH-5).
set(LLAMA_VERSION "0.5.0")
set(LLAMA_SHA256 "fef9ed754f4e031fb5c663c29260feda4ebc241abb68d64a81c0f1df5f1748e2")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Same CPU baseline as the speech module; checked against the AVD's /proc/cpuinfo.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v${LLAMA_VERSION}.tar.gz
    URL_HASH SHA256=${LLAMA_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama)

add_library(earshot_llama SHARED llama_jni.cpp)
target_compile_features(earshot_llama PRIVATE cxx_std_17)
target_compile_options(earshot_llama PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(earshot_llama PRIVATE llama log)
# Keep ggml's symbols private to this library so the speech library's ggml cannot clash.
target_link_options(earshot_llama PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
