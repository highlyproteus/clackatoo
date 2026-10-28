# SPDX-License-Identifier: GPL-3.0-only
#
# parakeet.cpp (our fork, pinned as the submodule asr/native/parakeet.cpp) with its own ggml, linked statically with
# hidden symbols into libclackatoo_asr.so. It cannot share the ggml of the ml module: that one is built with loadable
# CPU backends (GGML_BACKEND_DL), while parakeet calls ggml-cpu functions directly. No CLI, no server, no sound-event
# or speaker models, and no network code.

cmake_minimum_required(VERSION 3.22)
project(clackatoo_asr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# ggml in a debug build is unusably slow; the app's debug builds need the same speed as release
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# hidden symbols for every library built here (ggml's too), so nothing clashes with libggml*.so of the ml module
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PK ${CMAKE_CURRENT_LIST_DIR}/../../../native/parakeet.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # would need libomp.so in the APK; ggml's own thread pool is used
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # Armv8.2 with the dot-product instructions: every phone Parakeet is meant for. asr_jni.cpp checks HWCAP at run time
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()
if (ANDROID AND ANDROID_ABI STREQUAL "x86_64")
    # the x86_64 build is only for the emulator, whose CPU has AVX but not AVX2/FMA
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
endif()

# keep the build modest on a shared machine: at most 2 compile jobs per ABI (Ninja job pool)
set_property(GLOBAL PROPERTY JOB_POOLS asr_jobs=2)
set(CMAKE_JOB_POOL_COMPILE asr_jobs)
set(CMAKE_JOB_POOL_LINK asr_jobs)

# parakeet's apply_ggml_patches.sh is not run: its CPU patch only speeds up llamafile
add_subdirectory(${PK}/third_party/ggml ggml EXCLUDE_FROM_ALL)

set(PARAKEET_SRC
    src/parakeet.cpp
    src/model.cpp
    src/vad_head.cpp
    src/vad_segmenter.cpp
    src/vad_json.cpp
    src/bundle.cpp
    src/bundle_map.cpp
    src/parakeet_capi.cpp
    src/common.cpp
    src/audio_io.cpp
    src/transcription_json.cpp
    src/model_loader.cpp
    src/backend.cpp
    src/ggml_graph.cpp
    src/backend_pool.cpp
    src/fft.cpp
    src/mel.cpp
    src/mel_gpu.cpp
    src/subsampling.cpp
    src/relpos_attention.cpp
    src/conformer.cpp
    src/pos_enc.cpp
    src/encoder.cpp
    src/streaming_encoder.cpp
    src/streaming.cpp
    src/ctc_decoder.cpp
    src/prediction.cpp
    src/joint.cpp
    src/prompt_kernel.cpp
    src/ternary.cpp
    src/exact_matvec.cpp
    src/ternary_kernels_x86.cpp
    src/ternary_kernels_neon.cpp
    src/tdt.cpp
    src/rnnt.cpp
    src/transducer_batch.cpp
    src/tokenizer.cpp
    src/search.cpp
    src/transcription.cpp
    src/diarization.cpp
    src/diarization_encoder.cpp
    src/diarization_head.cpp
    src/sas_merge.cpp
    src/asr_committer.cpp
    src/diar_pcm_stream.cpp
    src/scene_stream.cpp
    src/diarization_streaming.cpp
    src/ced_tagger.cpp
    src/sound_stream.cpp
    src/speaker_registry.cpp
    src/speaker_identifier.cpp
    src/speaker_encoder.cpp
    src/scene_render.cpp
    src/silero_vad.cpp
    src/dr_wav_impl.cpp)
list(TRANSFORM PARAKEET_SRC PREPEND ${PK}/)

add_library(parakeet STATIC ${PARAKEET_SRC})
set_source_files_properties(${PK}/src/ternary.cpp ${PK}/src/ternary_kernels_x86.cpp ${PK}/src/ternary_kernels_neon.cpp
                            PROPERTIES COMPILE_OPTIONS "-ffp-contract=off")
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set_property(SOURCE ${PK}/src/ternary_kernels_neon.cpp APPEND PROPERTY COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
endif()
target_include_directories(parakeet PUBLIC ${PK}/include PRIVATE ${PK}/src ${PK}/third_party)
target_compile_definitions(parakeet PRIVATE PARAKEET_VERSION="0.6.1-clackatoo")
target_link_libraries(parakeet PUBLIC ggml)

add_library(clackatoo_asr SHARED asr_jni.cpp)
target_link_libraries(clackatoo_asr PRIVATE parakeet log)
target_link_options(clackatoo_asr PRIVATE -Wl,--exclude-libs,ALL)
