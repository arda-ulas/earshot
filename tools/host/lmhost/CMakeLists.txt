cmake_minimum_required(VERSION 3.22)
project(lmhost C CXX)

# Host build of the app's language-model fallback path (native/llama), for the regression harness.
include(${CMAKE_CURRENT_SOURCE_DIR}/../common/host.cmake)

set(PIN_FILE "${EARSHOT_REPO_ROOT}/native/llama/src/main/cpp/CMakeLists.txt")
earshot_read_pin("${PIN_FILE}" LLAMA_VERSION LLAMA_VERSION)
earshot_read_pin("${PIN_FILE}" LLAMA_SHA256 LLAMA_SHA256)
message(STATUS "llama.cpp v${LLAMA_VERSION}, sha256 ${LLAMA_SHA256} (from ${PIN_FILE})")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v${LLAMA_VERSION}.tar.gz
    URL_HASH SHA256=${LLAMA_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama)

add_executable(lmhost lmhost.cpp)
target_compile_features(lmhost PRIVATE cxx_std_17)
target_compile_options(lmhost PRIVATE -O3)
target_compile_definitions(lmhost PRIVATE
    ENGINE_VERSION="${LLAMA_VERSION}" ENGINE_SHA256="${LLAMA_SHA256}")
target_include_directories(lmhost PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../common)
target_link_libraries(lmhost PRIVATE llama)
