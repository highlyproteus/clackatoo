// SPDX-License-Identifier: GPL-3.0-only
//
// JNI wrapper for parakeet.cpp (NVIDIA Parakeet speech recognition). Used only by the isolated model process.
// Deliberately compiled without the dot-product flag: only the ggml CPU backend is built for it, so this file runs on
// any 64-bit CPU and can refuse to load the model on one that lacks the instructions.

#include <jni.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <vector>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

#include "parakeet_capi.h"

namespace {

// MlProtocol.MAX_SAMPLES: the longest recording the app ever hands over (120 s at 16 kHz)
constexpr jsize kMaxSamples = 1920000;
constexpr int kMaxThreads = 16;

bool cpu_supported() {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
#else
    return true;
#endif
}

}  // namespace

#define JNI(name) Java_helium314_keyboard_clackatoo_asr_NativeAsr_##name

extern "C" {

// Takes ownership of [fd] (a readable, seekable file) and closes it, also when loading fails. Returns 0 on failure
// (including a CPU without the dot-product instructions the quantised model needs).
JNIEXPORT jlong JNICALL JNI(load)(JNIEnv *, jobject, jint fd, jint threads) {
    FILE *f = fd >= 0 ? fdopen(fd, "rb") : nullptr;
    if (!f) {
        if (fd >= 0) close(fd);
        return 0;
    }
    jlong result = 0;
    try {
        unsigned char magic[4];
        if (cpu_supported() && threads >= 1 && threads <= kMaxThreads && fread(magic, 1, sizeof magic, f) == sizeof magic &&
            memcmp(magic, "GGUF", 4) == 0 && fseek(f, 0, SEEK_SET) == 0) {
            parakeet_capi_set_threads(threads);
            result = reinterpret_cast<jlong>(parakeet_capi_load_asr_file_ptr(f));
        }
    } catch (...) {
        result = 0;
    }
    fclose(f);
    return result;
}

JNIEXPORT void JNICALL JNI(free)(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    try {
        parakeet_capi_free(reinterpret_cast<parakeet_ctx *>(handle));
    } catch (...) {
    }
}

// pcm16[offset, offset + count) as 16 kHz mono samples. Returns the transcript as UTF-8, or null when the arguments
// are out of range or recognition fails.
JNIEXPORT jbyteArray JNICALL JNI(transcribeBytes)(JNIEnv *env, jobject, jlong handle, jshortArray pcm16, jint offset,
                                                  jint count) {
    if (!handle || !pcm16 || offset < 0 || count <= 0 || count > kMaxSamples) return nullptr;
    jsize length = env->GetArrayLength(pcm16);
    if (offset > length - count) return nullptr;
    try {
        std::vector<jshort> shorts((size_t) count);
        env->GetShortArrayRegion(pcm16, offset, count, shorts.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> pcm((size_t) count);
        for (size_t i = 0; i < pcm.size(); i++) pcm[i] = shorts[i] / 32768.0f;
        char *text = parakeet_capi_transcribe_pcm(reinterpret_cast<parakeet_ctx *>(handle), pcm.data(), count, 16000, 0);
        if (!text) return nullptr;
        size_t n = strlen(text);
        jbyteArray out = n <= (size_t) INT32_MAX ? env->NewByteArray((jsize) n) : nullptr;
        if (out && n > 0) env->SetByteArrayRegion(out, 0, (jsize) n, reinterpret_cast<const jbyte *>(text));
        parakeet_capi_free_string(text);
        return out;
    } catch (...) {
        return nullptr;
    }
}

}  // extern "C"
