cmake_minimum_required(VERSION 3.22)
project(whisperhost C CXX)

# Host build of the app's speech-to-text path (native/whisper), for the regression harness.
include(${CMAKE_CURRENT_SOURCE_DIR}/../common/host.cmake)

set(PIN_FILE "${EARSHOT_REPO_ROOT}/native/whisper/src/main/cpp/CMakeLists.txt")
earshot_read_pin("${PIN_FILE}" WHISPER_VERSION WHISPER_VERSION)
earshot_read_pin("${PIN_FILE}" WHISPER_SHA256 WHISPER_SHA256)
message(STATUS "whisper.cpp v${WHISPER_VERSION}, sha256 ${WHISPER_SHA256} (from ${PIN_FILE})")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${WHISPER_VERSION}.tar.gz
    URL_HASH SHA256=${WHISPER_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whisper)

add_executable(whisperhost whisperhost.cpp)
target_compile_features(whisperhost PRIVATE cxx_std_17)
target_compile_options(whisperhost PRIVATE -O3)
target_compile_definitions(whisperhost PRIVATE
    ENGINE_VERSION="${WHISPER_VERSION}" ENGINE_SHA256="${WHISPER_SHA256}")
target_include_directories(whisperhost PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../common)
target_link_libraries(whisperhost PRIVATE whisper)
