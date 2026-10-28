// SPDX-License-Identifier: GPL-3.0-only

#include <jni.h>

#include <atomic>
#include <exception>
#include <new>
#include <string>
#include <vector>

#include "ml_core.h"

namespace {

// A loaded engine plus the flag used to cancel a running call from another thread. The caller (MlService) resets
// the flag before a call and never frees a handle while a cancel can reach it.
struct LlmHandle {
    eg::Llm *llm;
    std::atomic<bool> cancel{false};
};
struct SttHandle {
    eg::Stt *stt;
    std::atomic<bool> cancel{false};
};

struct VadHandle {
    eg::Vad *vad;
};

constexpr size_t kMaxPromptBytes = 1 << 20;

void throw_java(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;  // one is pending already (for example OutOfMemoryError)
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

// Nothing may escape as a C++ exception: that would abort the process.
template <typename T, typename F>
T guard(JNIEnv *env, T fallback, F &&body) {
    try {
        return body();
    } catch (const std::bad_alloc &) {
        throw_java(env, "out of memory");
    } catch (const std::exception &) {
        throw_java(env, "native error");
    } catch (...) {
        throw_java(env, "native error");
    }
    return fallback;
}

// Reads a Java string; false when it is null or cannot be read (an exception is then pending or thrown).
bool to_string(JNIEnv *env, jstring s, std::string &out) {
    if (!s) {
        throw_java(env, "missing argument");
        return false;
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return false;  // OutOfMemoryError is pending
    out.assign(chars);
    env->ReleaseStringUTFChars(s, chars);
    return true;
}

// Prompts and results cross JNI as UTF-8 byte arrays: JNI strings are modified UTF-8, which cannot carry emoji
// or the partial characters the model may end on.
bool to_utf8(JNIEnv *env, jbyteArray bytes, std::string &out) {
    if (!bytes) {
        throw_java(env, "missing argument");
        return false;
    }
    jsize n = env->GetArrayLength(bytes);
    out.assign((size_t) n, '\0');
    if (n > 0) {
        env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
        if (env->ExceptionCheck()) return false;
    }
    return true;
}

jbyteArray from_utf8(JNIEnv *env, const std::string &s) {
    jbyteArray array = env->NewByteArray((jsize) s.size());
    if (!array) return nullptr;  // OutOfMemoryError is pending
    if (!s.empty()) env->SetByteArrayRegion(array, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return array;
}

}  // namespace

#define JNI(name) Java_helium314_keyboard_clackatoo_ml_NativeMl_##name

extern "C" {

JNIEXPORT jint JNICALL JNI(init)(JNIEnv *env, jobject, jstring nativeLibDir) {
    return guard<jint>(env, 0, [&] {
        std::string dir;
        if (!to_string(env, nativeLibDir, dir)) return (jint) 0;
        return (jint) eg::init_backends(dir.c_str());
    });
}

JNIEXPORT jstring JNICALL JNI(systemInfo)(JNIEnv *env, jobject) {
    return guard<jstring>(env, nullptr, [&] { return env->NewStringUTF(eg::system_info().c_str()); });
}

// [fd] is detached from the Java side: this function owns it from here on, whatever happens.
JNIEXPORT jlong JNICALL JNI(llmLoad)(JNIEnv *env, jobject, jint fd, jint nCtx, jint threads) {
    return guard<jlong>(env, 0, [&] {
        std::string error;
        eg::Llm *llm = eg::llm_load(fd, nCtx, threads, error);
        if (!llm) {
            throw_java(env, error);
            return (jlong) 0;
        }
        return reinterpret_cast<jlong>(new LlmHandle{llm});
    });
}

JNIEXPORT jint JNICALL JNI(llmLoadMode)(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    return h && eg::llm_load_mode(h->llm) == eg::LoadMode::Mmap ? 1 : 0;
}

JNIEXPORT void JNICALL JNI(llmFree)(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    if (!h) return;
    guard<int>(env, 0, [&] {
        eg::llm_free(h->llm);
        delete h;
        return 0;
    });
}

JNIEXPORT void JNICALL JNI(llmCancel)(JNIEnv *, jobject, jlong handle, jboolean cancel) {
    auto *h = reinterpret_cast<LlmHandle *>(handle);
    if (h) h->cancel.store(cancel);
}

// Returns the text as UTF-8, or null (no exception) when the call was cancelled.
JNIEXPORT jbyteArray JNICALL JNI(llmGenerateParts)(JNIEnv *env, jobject, jlong handle, jobjectArray parts,
                                              jbooleanArray special, jint maxNewTokens) {
    return guard<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        auto *h = reinterpret_cast<LlmHandle *>(handle);
        if (!h || !parts || !special) {
            throw_java(env, "missing argument");
            return nullptr;
        }
        jsize count = env->GetArrayLength(parts);
        if (count <= 0 || count > 64 || env->GetArrayLength(special) != count) {
            throw_java(env, "bad prompt");
            return nullptr;
        }
        std::vector<jboolean> flags((size_t) count);
        env->GetBooleanArrayRegion(special, 0, count, flags.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<eg::PromptPart> prompt;
        prompt.reserve((size_t) count);
        size_t total = 0;
        for (jsize i = 0; i < count; i++) {
            jbyteArray item = static_cast<jbyteArray>(env->GetObjectArrayElement(parts, i));
            if (env->ExceptionCheck()) return nullptr;
            std::string text;
            bool ok = to_utf8(env, item, text);
            if (item) env->DeleteLocalRef(item);
            if (!ok) return nullptr;
            total += text.size();
            if (total > kMaxPromptBytes) {
                throw_java(env, "text too long");
                return nullptr;
            }
            prompt.push_back({std::move(text), flags[(size_t) i] != JNI_FALSE});
        }
        std::string out, error;
        switch (eg::llm_generate(h->llm, prompt, maxNewTokens, h->cancel, out, error)) {
            case eg::Status::Cancelled:
                return nullptr;
            case eg::Status::Error:
                throw_java(env, error);
                return nullptr;
            case eg::Status::Ok:
                return from_utf8(env, out);
        }
        return nullptr;
    });
}

// Chat: [roles] and [contents] (UTF-8) are laid out with the model's own chat template (see eg::llm_chat). Null: cancelled.
JNIEXPORT jbyteArray JNICALL JNI(llmChatTurns)(JNIEnv *env, jobject, jlong handle, jobjectArray roles,
                                               jobjectArray contents, jint maxNewTokens) {
    return guard<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        auto *h = reinterpret_cast<LlmHandle *>(handle);
        if (!h || !roles || !contents) {
            throw_java(env, "missing argument");
            return nullptr;
        }
        jsize count = env->GetArrayLength(roles);
        if (count <= 0 || count > 32 || env->GetArrayLength(contents) != count) {
            throw_java(env, "bad prompt");
            return nullptr;
        }
        std::vector<eg::ChatTurn> turns;
        turns.reserve((size_t) count);
        size_t total = 0;
        for (jsize i = 0; i < count; i++) {
            std::string role, content;
            for (int which = 0; which < 2; which++) {
                jbyteArray item = static_cast<jbyteArray>(env->GetObjectArrayElement(which == 0 ? roles : contents, i));
                if (env->ExceptionCheck()) return nullptr;
                bool ok = to_utf8(env, item, which == 0 ? role : content);
                if (item) env->DeleteLocalRef(item);
                if (!ok) return nullptr;
            }
            total += role.size() + content.size();
            if (total > kMaxPromptBytes) {
                throw_java(env, "text too long");
                return nullptr;
            }
            turns.push_back({std::move(role), std::move(content)});
        }
        std::string out, error;
        switch (eg::llm_chat(h->llm, turns, maxNewTokens, h->cancel, out, error)) {
            case eg::Status::Cancelled:
                return nullptr;
            case eg::Status::Error:
                throw_java(env, error);
                return nullptr;
            case eg::Status::Ok:
                return from_utf8(env, out);
        }
        return nullptr;
    });
}

JNIEXPORT jlong JNICALL JNI(sttLoad)(JNIEnv *env, jobject, jint fd, jint threads) {
    return guard<jlong>(env, 0, [&] {
        std::string error;
        eg::Stt *stt = eg::stt_load(fd, threads, error);
        if (!stt) {
            throw_java(env, error);
            return (jlong) 0;
        }
        return reinterpret_cast<jlong>(new SttHandle{stt});
    });
}

JNIEXPORT void JNICALL JNI(sttFree)(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<SttHandle *>(handle);
    if (!h) return;
    guard<int>(env, 0, [&] {
        eg::stt_free(h->stt);
        delete h;
        return 0;
    });
}

JNIEXPORT void JNICALL JNI(sttCancel)(JNIEnv *, jobject, jlong handle, jboolean cancel) {
    auto *h = reinterpret_cast<SttHandle *>(handle);
    if (h) h->cancel.store(cancel);
}

// Returns the text as UTF-8, or null (no exception) when the call was cancelled.
JNIEXPORT jbyteArray JNICALL JNI(sttTranscribeBytes)(JNIEnv *env, jobject, jlong handle, jshortArray pcm16, jstring language) {
    return guard<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        auto *h = reinterpret_cast<SttHandle *>(handle);
        if (!h || !pcm16) {
            throw_java(env, "missing argument");
            return nullptr;
        }
        std::string lang;
        if (!to_string(env, language, lang)) return nullptr;
        jsize n = env->GetArrayLength(pcm16);
        if (n <= 0 || n > 16000 * 125) {
            throw_java(env, "bad recording length");
            return nullptr;
        }
        std::vector<jshort> shorts((size_t) n);
        env->GetShortArrayRegion(pcm16, 0, n, shorts.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> pcm((size_t) n);
        for (jsize i = 0; i < n; i++) pcm[(size_t) i] = shorts[(size_t) i] / 32768.0f;
        std::string out, error;
        switch (eg::stt_transcribe(h->stt, pcm.data(), n, lang.c_str(), h->cancel, out, error)) {
            case eg::Status::Cancelled:
                return nullptr;
            case eg::Status::Error:
                throw_java(env, error);
                return nullptr;
            case eg::Status::Ok:
                return from_utf8(env, out);
        }
        return nullptr;
    });
}

// A voice-activity detector (Silero). [fd] is owned by this function from here on.
JNIEXPORT jlong JNICALL JNI(vadLoad)(JNIEnv *env, jobject, jint fd, jint threads) {
    return guard<jlong>(env, 0, [&] {
        std::string error;
        eg::Vad *vad = eg::vad_load(fd, threads, error);
        if (!vad) {
            throw_java(env, error);
            return (jlong) 0;
        }
        return reinterpret_cast<jlong>(new VadHandle{vad});
    });
}

// One speech probability per 512 samples of pcm16[offset, offset + count), or null when the detector failed (no
// exception). The caller feeds multiples of 512 samples.
JNIEXPORT jfloatArray JNICALL JNI(vadFeed)(JNIEnv *env, jobject, jlong handle, jshortArray pcm16, jint offset, jint count) {
    return guard<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
        auto *h = reinterpret_cast<VadHandle *>(handle);
        if (!h || !pcm16) {
            throw_java(env, "missing argument");
            return nullptr;
        }
        jsize length = env->GetArrayLength(pcm16);
        if (offset < 0 || count <= 0 || count > 16000 * 125 || offset > length - count) {
            throw_java(env, "bad audio range");
            return nullptr;
        }
        std::vector<jshort> shorts((size_t) count);
        env->GetShortArrayRegion(pcm16, offset, count, shorts.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> pcm((size_t) count);
        for (jsize i = 0; i < count; i++) pcm[(size_t) i] = shorts[(size_t) i] / 32768.0f;
        std::vector<float> probs;
        std::string error;
        if (eg::vad_feed(h->vad, pcm.data(), count, probs, error) != eg::Status::Ok) return nullptr;
        jfloatArray out = env->NewFloatArray((jsize) probs.size());
        if (!out) return nullptr;  // OutOfMemoryError is pending
        env->SetFloatArrayRegion(out, 0, (jsize) probs.size(), probs.data());
        return out;
    });
}

JNIEXPORT void JNICALL JNI(vadReset)(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<VadHandle *>(handle);
    if (h) eg::vad_reset(h->vad);
}

JNIEXPORT void JNICALL JNI(vadFree)(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<VadHandle *>(handle);
    if (!h) return;
    guard<int>(env, 0, [&] {
        eg::vad_free(h->vad);
        delete h;
        return 0;
    });
}

}  // extern "C"
