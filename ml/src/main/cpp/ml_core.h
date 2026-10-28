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

/** One turn of a chat: [role] is "system", "user" or "assistant"; [content] is what the user, a transcript or a model wrote. */
struct ChatTurn {
    std::string role;
    std::string content;
};

/** Like [llm_generate], but the prompt is laid out with the chat template stored in the model file (llama.cpp's
 *  `llama_chat_apply_template`, which knows the common templates but is no Jinja interpreter). Only the template's own
 *  markers are tokenised as special tokens: every [ChatTurn::content] is tokenised as plain text, so text in a field
 *  cannot forge turns. A model with no template, or one llama.cpp cannot apply, gets ChatML ("<|im_start|>role"). When
 *  the template mentions `enable_thinking` (Qwen3 family) an empty think block is added after the assistant's opening,
 *  which is what the template does with thinking switched off. Roles other than the three above are an error. */
Status llm_chat(Llm *llm, const std::vector<ChatTurn> &turns, int max_new_tokens, const std::atomic<bool> &cancel,
                std::string &out, std::string &error);

/** The prompt [llm_chat] would use, as parts, without running the model (exposed for tests and the host tool). */
bool llm_chat_prompt(Llm *llm, const std::vector<ChatTurn> &turns, std::vector<PromptPart> &prompt, std::string &error);

struct Stt;
/** Takes ownership of [fd]. The header is checked before whisper.cpp reads the file (see [whisper_header_ok]). */
Stt *stt_load(int fd, int n_threads, std::string &error);
void stt_free(Stt *stt);
/** Transcribes 16 kHz mono float PCM in [-1, 1]. [language] is a Whisper language code such as "en", or "auto". */
Status stt_transcribe(Stt *stt, const float *pcm, int n_samples, const char *language,
                      const std::atomic<bool> &cancel, std::string &out, std::string &error);

struct Vad;
/** Samples per voice-activity window: the detector gives one speech probability for each. */
constexpr int kVadWindowSamples = 512;
/** Takes ownership of [fd], also when loading fails. The Silero model is pinned by size and SHA-256 by the app. */
Vad *vad_load(int fd, int n_threads, std::string &error);
/** Feeds 16 kHz mono float PCM in [-1, 1] to the detector, keeping its state from the previous call. [n] must be a
 *  positive multiple of [kVadWindowSamples], else Status::Error ("bad sample count"). [probs] gets one speech
 *  probability per window. */
Status vad_feed(Vad *vad, const float *pcm, int n, std::vector<float> &probs, std::string &error);
/** Forgets the audio fed so far (the detector's memory), for the next recording. */
void vad_reset(Vad *vad);
void vad_free(Vad *vad);

/** Whether the first bytes of a Whisper ggml file are a header this code is willing to hand to whisper.cpp: the
 *  magic, and mel filters of 80 or 128 bands and 201 FFT bins. whisper.cpp trusts these numbers from the file and
 *  sizes buffers by them, so a crafted file with other values overflows a buffer. [n] is the number of bytes in
 *  [header]; at least [kWhisperHeaderBytes] are needed. */
constexpr size_t kWhisperHeaderBytes = 56;
bool whisper_header_ok(const unsigned char *header, size_t n);

}  // namespace eg
