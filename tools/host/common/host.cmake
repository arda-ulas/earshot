# Shared settings for the host engine builds (tools/host/whisperhost, tools/host/lmhost).
#
# The source pins are read from the app's native CMakeLists.txt, so the host engines are always built
# from the same release tarball, checked against the same SHA-256, as the libraries in the app.

get_filename_component(EARSHOT_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)

# Reads `set(<NAME> "<value>")` from a CMakeLists.txt into OUT. Fails the configure if it is missing.
function(earshot_read_pin file name out)
    file(STRINGS "${file}" lines REGEX "^set\\(${name} \"[^\"]+\"\\)")
    list(LENGTH lines n)
    if(NOT n EQUAL 1)
        message(FATAL_ERROR "expected exactly one set(${name} \"...\") in ${file}, found ${n}")
    endif()
    string(REGEX REPLACE "^set\\(${name} \"([^\"]+)\"\\).*" "\\1" value "${lines}")
    set(${out} "${value}" PARENT_SCOPE)
endfunction()

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# CPU only, like the app: no Metal, no Accelerate or other BLAS, no OpenMP.
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# On arm64 hosts the CPU kernels are built for the same baseline as the app (armv8.2-a with dot
# product and fp16; all Apple silicon has both). Elsewhere (x86_64) there is nothing to mirror, so
# ggml is tuned for the build machine. Override with -DEARSHOT_HOST_ARM_ARCH=... if a Linux arm64
# host lacks these extensions.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$")
    set(EARSHOT_HOST_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "ggml CPU arch on arm64 hosts")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "${EARSHOT_HOST_ARM_ARCH}" CACHE STRING "" FORCE)
else()
    set(GGML_NATIVE ON CACHE BOOL "" FORCE)
endif()

# Not used here; silences a configure warning.
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
