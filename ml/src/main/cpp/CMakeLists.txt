# SPDX-License-Identifier: GPL-3.0-only
#
# llama.cpp and whisper.cpp in one build, sharing a single ggml (the copy in llama.cpp; both pin ggml 0.27).
# Android: builds libclackatoo_ml.so (JNI) plus libllama, libwhisper, libggml* and the CPU backend variants.
# Other hosts: builds the test tool `clackatoo-ml-host` (see ml/README.md), which runs the same code as the app.

cmake_minimum_required(VERSION 3.22)
project(clackatoo_ml LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# ggml in a debug build is unusably slow; the app's debug builds need the same speed as release
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(NATIVE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../native)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
# no network code, no tools, no examples: only the libraries
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # would need libomp.so in the APK; ggml's own thread pool is used
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# one CPU backend library per instruction-set level, picked at run time (ggml_backend_load_all_from_path)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
if (ANDROID AND ANDROID_ABI STREQUAL "x86_64")
    # the x86_64 build is only for the emulator, whose CPU has AVX but not AVX2/FMA: one CPU backend, built for that.
    # It stays a separate library (libggml-cpu.so, found in the native library directory) like the arm64 variants,
    # so the emulator exercises the same loading code as the phone, including in the isolated model process.
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
endif()

# keep the build modest on a shared machine: at most 2 compile jobs per ABI (Ninja job pool)
set_property(GLOBAL PROPERTY JOB_POOLS ml_jobs=2)
set(CMAKE_JOB_POOL_COMPILE ml_jobs)
set(CMAKE_JOB_POOL_LINK ml_jobs)

add_subdirectory(${NATIVE_DIR}/llama.cpp llama.cpp)   # defines ggml and llama
add_subdirectory(${NATIVE_DIR}/whisper.cpp whisper.cpp) # sees the existing ggml target and reuses it

if (ANDROID)
    add_library(clackatoo_ml SHARED jni.cpp ml_core.cpp)
    target_link_libraries(clackatoo_ml PRIVATE llama whisper log)
    target_include_directories(clackatoo_ml PRIVATE .)
else()
    add_executable(clackatoo-ml-host ${CMAKE_CURRENT_LIST_DIR}/../../host/ml_host.cpp ml_core.cpp)
    target_link_libraries(clackatoo-ml-host PRIVATE llama whisper)
    target_include_directories(clackatoo-ml-host PRIVATE .)
endif()
