// SPDX-License-Identifier: GPL-3.0-only
//
// Thin C++ layer over llama.cpp (text generation) and whisper.cpp (speech to text), both MIT licensed and
// built against one shared ggml. Used by the JNI wrapper (jni.cpp) and by the host test tool (src/host).
//
// Models are opened from a file descriptor, never from a path: in the app the model process is an isolated process
// that cannot open the app's files, and the keyboard hands it a read-only descriptor.

#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace eg {

/** Loads the ggml backends. [dir] is the directory with libggml-cpu*.so (the app's native library
 *  directory); nullptr searches next to the executable (host tool). Safe to call more than once.
 *  Returns the number of ggml devices that can run models (0: no CPU backend could be loaded). */
int init_backends(const char *dir);

/** Like llama.cpp's system info string: shows which CPU features the loaded backend variant uses. */
std::string system_info();

/** How a call ended. */
enum class Status { Ok, Cancelled, Error };

/** One piece of a prompt. Special pieces (the chat template's markers) may produce control tokens; the others
 *  (everything the user or a transcript wrote) never do. */
struct PromptPart {
    std::string text;
    bool special;
};

/** Which way a model file was read; reported for diagnostics. */
enum class LoadMode { Mmap, Read };

struct Llm;
/** Takes ownership of [fd] (a readable, seekable file), also when loading fails. Tries mmap, then reading. */
Llm *llm_load(int fd, int n_ctx, int n_threads, std::string &error);
void llm_free(Llm *llm);
LoadMode llm_load_mode(const Llm *llm);
/** Greedy decoding. The parts are tokenised one by one, so text in a non-special part can never become a control
 *  token. Stops at an end-of-generation token or after [max_new_tokens]. When [cancel] becomes true the call ends
 *  with Status::Cancelled and [out] must not be used. */
Status llm_generate(Llm *llm, const std::vector<PromptPart> &prompt, int max_new_tokens, const std::atomic<bool> &cancel,
                    std::string &out, std::string &error);

struct Stt;
/** Takes ownership of [fd]. The header is checked before whisper.cpp reads the file (see [whisper_header_ok]). */
Stt *stt_load(int fd, int n_threads, std::string &error);
void stt_free(Stt *stt);
/** Transcribes 16 kHz mono float PCM in [-1, 1]. [language] is a Whisper language code such as "en", or "auto". */
Status stt_transcribe(Stt *stt, const float *pcm, int n_samples, const char *language,
                      const std::atomic<bool> &cancel, std::string &out, std::string &error);

/** Whether the first bytes of a Whisper ggml file are a header this code is willing to hand to whisper.cpp: the
 *  magic, and mel filters of 80 or 128 bands and 201 FFT bins. whisper.cpp trusts these numbers from the file and
 *  sizes buffers by them, so a crafted file with other values overflows a buffer. [n] is the number of bytes in
 *  [header]; at least [kWhisperHeaderBytes] are needed. */
constexpr size_t kWhisperHeaderBytes = 56;
bool whisper_header_ok(const unsigned char *header, size_t n);

}  // namespace eg
