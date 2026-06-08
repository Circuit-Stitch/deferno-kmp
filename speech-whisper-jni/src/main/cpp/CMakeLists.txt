cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The vendored, pinned whisper.cpp submodule (ADR-0018). CMAKE_CURRENT_SOURCE_DIR is
# speech-whisper-jni/src/main/cpp; the submodule lives at <repo-root>/third_party/whisper.cpp.
get_filename_component(
    WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp" ABSOLUTE)

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp submodule missing at ${WHISPER_DIR}.\n"
        "Run: git submodule update --init --recursive")
endif()

# Lean, strictly-on-device CPU build (ADR-0018/0009): no examples/tests/server/tools, no OpenMP, and
# no host-native codegen (we are cross-compiling for Android via the NDK toolchain). Statically link
# ggml/whisper into our single libwhisper_jni.so.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper-build)

add_library(whisper_jni SHARED whisper_jni.cpp)
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})
