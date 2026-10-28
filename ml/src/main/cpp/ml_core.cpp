// SPDX-License-Identifier: GPL-3.0-only

#include "ml_core.h"

#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
static void log_cb(ggml_log_level level, const char *text, void *) {
    // never log prompts, transcripts or audio: ggml only logs model loading and errors here
    // (live text feeds the voice detector ten times a second; its five lines per call are not worth logging)
    if (level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_WARN && strncmp(text, "whisper_vad_detect_speech", 25) == 0) return;
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_write(prio, "ClackatooMl", text);
}
#else
static void log_cb(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}
#endif

namespace eg {

static std::once_flag backends_once;
static int backend_devices = 0;

int init_backends(const char *dir) {
    std::call_once(backends_once, [dir] {
        llama_log_set(log_cb, nullptr);
        whisper_log_set(log_cb, nullptr);
        if (dir) ggml_backend_load_all_from_path(dir);
        else ggml_backend_load_all();
        llama_backend_init();
        backend_devices = (int) ggml_backend_dev_count();
    });
    return backend_devices;
}

std::string system_info() { return llama_print_system_info(); }

// ---------------------------------------------------------------- llama

struct Llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    LoadMode mode = LoadMode::Mmap;
};

static llama_model *load_model(FILE *fp, llama_load_mode mode) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = mode;
    // The FILE is closed once the model is loaded, so no tensor may be read on demand from it later (the default,
    // AUTO, would do that for tensors over 4 GiB; none of the pinned models has one).
    mp.lazy_mode = LLAMA_LAZY_MODE_OFF;
    return llama_model_load_from_file_ptr(fp, mp);
}

Llm *llm_load(int fd, int n_ctx, int n_threads, std::string &error) {
    FILE *fp = fdopen(fd, "rb");
    if (!fp) {
        close(fd);
        error = "could not read the model file";
        return nullptr;
    }
    if (n_ctx < 256 || n_ctx > 8192 || n_threads < 1 || n_threads > 16) {
        fclose(fp);
        error = "bad model settings";
        return nullptr;
    }
    // The model is read from a descriptor handed to this process. Mapping it is the cheap way; if the process is
    // not allowed to map the descriptor, read the file into memory instead.
    LoadMode mode = LoadMode::Mmap;
    llama_model *model = load_model(fp, LLAMA_LOAD_MODE_MMAP);
    if (!model) {
        mode = LoadMode::Read;
        clearerr(fp);
        fseek(fp, 0, SEEK_SET);
        model = load_model(fp, LLAMA_LOAD_MODE_NONE);
    }
    fclose(fp);  // a mapping stays valid without the descriptor
    if (!model) {
        error = "could not load the model file";
        return nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        error = "could not create the model context";
        return nullptr;
    }
    auto *llm = new Llm();
    llm->model = model;
    llm->ctx = ctx;
    llm->vocab = llama_model_get_vocab(model);
    llm->n_ctx = (int) llama_n_ctx(ctx);
    llm->mode = mode;
    return llm;
}

void llm_free(Llm *llm) {
    if (!llm) return;
    llama_free(llm->ctx);
    llama_model_free(llm->model);
    delete llm;
}

LoadMode llm_load_mode(const Llm *llm) { return llm->mode; }

static bool starts_with_bos(const llama_vocab *vocab, const std::string &text) {
    const llama_token bos = llama_vocab_bos(vocab);
    if (bos == LLAMA_TOKEN_NULL) return false;
    const char *bos_text = llama_vocab_get_text(vocab, bos);
    return bos_text && *bos_text && text.compare(0, strlen(bos_text), bos_text) == 0;
}

Status llm_generate(Llm *llm, const std::vector<PromptPart> &prompt, int max_new_tokens, const std::atomic<bool> &cancel,
                    std::string &out, std::string &error) {
    out.clear();
    if (max_new_tokens < 1 || max_new_tokens > 4096) {
        error = "bad output length";
        return Status::Error;
    }
    std::vector<llama_token> tokens;
    for (const PromptPart &part : prompt) {
        if (part.text.empty()) continue;
        std::vector<llama_token> piece(part.text.size() + 8);
        // the beginning-of-text token (if the model wants one) goes in front of the first part only
        // (a template that writes the beginning-of-text token itself, Llama 3 style, must not get a second one)
        const bool add_bos = tokens.empty() && !(part.special && starts_with_bos(llm->vocab, part.text));
        int n = llama_tokenize(llm->vocab, part.text.c_str(), (int) part.text.size(), piece.data(), (int) piece.size(),
                               add_bos, part.special);
        if (n < 0) {
            piece.resize(-n);
            n = llama_tokenize(llm->vocab, part.text.c_str(), (int) part.text.size(), piece.data(), (int) piece.size(),
                               add_bos, part.special);
        }
        if (n < 0) {
            error = "could not tokenize the prompt";
            return Status::Error;
        }
        tokens.insert(tokens.end(), piece.begin(), piece.begin() + n);
        if ((long) tokens.size() + max_new_tokens > llm->n_ctx) {
            error = "text too long";
            return Status::Error;
        }
    }
    const int n = (int) tokens.size();
    if (n == 0) {
        error = "could not tokenize the prompt";
        return Status::Error;
    }

    llama_memory_clear(llama_get_memory(llm->ctx), true);

    // prompt, in batches
    for (int i = 0; i < n; i += llm->n_batch) {
        if (cancel.load()) return Status::Cancelled;
        int len = std::min(llm->n_batch, n - i);
        if (llama_decode(llm->ctx, llama_batch_get_one(tokens.data() + i, len)) != 0) {
            error = "model failed while reading the text";
            return Status::Error;
        }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    Status status = Status::Ok;
    char piece[256];
    for (int i = 0; i < max_new_tokens; i++) {
        if (cancel.load()) {
            status = Status::Cancelled;
            break;
        }
        llama_token tok = llama_sampler_sample(smpl, llm->ctx, -1);
        if (llama_vocab_is_eog(llm->vocab, tok)) break;
        int len = llama_token_to_piece(llm->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        if (llama_decode(llm->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            error = "model failed while writing the text";
            status = Status::Error;
            break;
        }
    }
    llama_sampler_free(smpl);
    return status;
}

// Laying out a chat with the model's own template. Every content is replaced by a marker that cannot be in a template,
// the template is applied, and the result is cut at the markers: the pieces between them are the template's special
// markers, the contents are put back as plain text.
static const char kMarker = '\x1e';

static std::string marker(size_t i) { return std::string(1, kMarker) + std::to_string(i) + std::string(1, kMarker); }

static void chatml_prompt(const std::vector<ChatTurn> &turns, std::vector<PromptPart> &prompt, bool no_think) {
    for (const ChatTurn &t : turns) {
        prompt.push_back({"<|im_start|>" + t.role + "\n", true});
        prompt.push_back({t.content, false});
        prompt.push_back({"<|im_end|>\n", true});
    }
    prompt.push_back({no_think ? "<|im_start|>assistant\n<think>\n\n</think>\n\n" : "<|im_start|>assistant\n", true});
}

bool llm_chat_prompt(Llm *llm, const std::vector<ChatTurn> &turns, std::vector<PromptPart> &prompt, std::string &error) {
    prompt.clear();
    if (turns.empty() || turns.size() > 32) {
        error = "bad prompt";
        return false;
    }
    for (const ChatTurn &t : turns) {
        if (t.role != "system" && t.role != "user" && t.role != "assistant") {
            error = "bad prompt";
            return false;
        }
    }
    const char *tmpl = llama_model_chat_template(llm->model, nullptr);
    const bool think = tmpl && strstr(tmpl, "enable_thinking") != nullptr;
    if (!tmpl) {
        chatml_prompt(turns, prompt, false);
        return true;
    }
    std::vector<std::string> marks;
    std::vector<llama_chat_message> messages;
    marks.reserve(turns.size());
    for (size_t i = 0; i < turns.size(); i++) marks.push_back(marker(i));
    for (size_t i = 0; i < turns.size(); i++) messages.push_back({turns[i].role.c_str(), marks[i].c_str()});
    std::vector<char> buf(1024);
    int32_t n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n);
        n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    }
    std::string text;
    if (n > 0) text.assign(buf.data(), (size_t) n);
    // every marker must appear exactly once and in order; otherwise the template is not usable this way
    bool usable = n > 0;
    size_t at = 0;
    std::vector<std::pair<size_t, size_t>> spans;  // where each marker is
    for (size_t i = 0; usable && i < marks.size(); i++) {
        size_t p = text.find(marks[i], at);
        if (p == std::string::npos || text.find(marks[i], p + marks[i].size()) != std::string::npos) usable = false;
        else {
            spans.push_back({p, marks[i].size()});
            at = p + marks[i].size();
        }
    }
    if (!usable) {
        chatml_prompt(turns, prompt, think);
        return true;
    }
    size_t cursor = 0;
    for (size_t i = 0; i < spans.size(); i++) {
        if (spans[i].first > cursor) prompt.push_back({text.substr(cursor, spans[i].first - cursor), true});
        prompt.push_back({turns[i].content, false});
        cursor = spans[i].first + spans[i].second;
    }
    std::string tail = text.substr(cursor);
    if (think) tail += "<think>\n\n</think>\n\n";
    if (!tail.empty()) prompt.push_back({tail, true});
    // a template that writes the beginning-of-text token itself must not get a second one from the tokeniser
    return true;
}

Status llm_chat(Llm *llm, const std::vector<ChatTurn> &turns, int max_new_tokens, const std::atomic<bool> &cancel,
                std::string &out, std::string &error) {
    std::vector<PromptPart> prompt;
    if (!llm_chat_prompt(llm, turns, prompt, error)) return Status::Error;
    return llm_generate(llm, prompt, max_new_tokens, cancel, out, error);
}

// ---------------------------------------------------------------- whisper

struct Stt {
    whisper_context *ctx = nullptr;
    int n_threads = 4;
};

static int32_t le32(const unsigned char *p) {
    return (int32_t) ((uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24);
}

bool whisper_header_ok(const unsigned char *header, size_t n) {
    if (n < kWhisperHeaderBytes) return false;
    // "ggml" as a little-endian uint32, then ten hyper-parameters and the file type (ints), then the mel filters'
    // band count (offset 48) and FFT size (offset 52)
    if (le32(header) != 0x67676d6c) return false;
    const int32_t n_mel = le32(header + 48), n_fft = le32(header + 52);
    return (n_mel == 80 || n_mel == 128) && n_fft == 201;
}

namespace {
struct FileSource {
    FILE *fp;
};

// A whisper.cpp model loader that reads from [source]'s file. [close] closes the file (once, whatever happened).
void init_file_loader(whisper_model_loader &loader, FileSource &source) {
    loader = {};
    loader.context = &source;
    loader.read = [](void *ctx, void *output, size_t size) -> size_t {
        auto *s = static_cast<FileSource *>(ctx);
        return s->fp ? fread(output, 1, size, s->fp) : 0;
    };
    loader.eof = [](void *ctx) -> bool {
        auto *s = static_cast<FileSource *>(ctx);
        return !s->fp || feof(s->fp);
    };
    loader.close = [](void *ctx) {  // whisper_init_with_params calls this once, whether or not loading worked
        auto *s = static_cast<FileSource *>(ctx);
        if (s->fp) fclose(s->fp);
        s->fp = nullptr;
    };
}
}  // namespace

Stt *stt_load(int fd, int n_threads, std::string &error) {
    FILE *fp = fdopen(fd, "rb");
    if (!fp) {
        close(fd);
        error = "could not read the speech model file";
        return nullptr;
    }
    unsigned char header[kWhisperHeaderBytes];
    if (n_threads < 1 || n_threads > 16 || fread(header, 1, sizeof header, fp) != sizeof header ||
        !whisper_header_ok(header, sizeof header) || fseek(fp, 0, SEEK_SET) != 0) {
        fclose(fp);
        error = "not a supported speech model file";
        return nullptr;
    }
    FileSource source{fp};
    whisper_model_loader loader;
    init_file_loader(loader, source);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_with_params(&loader, cp);
    if (!ctx) {
        error = "could not load the speech model file";
        return nullptr;
    }
    auto *stt = new Stt();
    stt->ctx = ctx;
    stt->n_threads = n_threads;
    return stt;
}

void stt_free(Stt *stt) {
    if (!stt) return;
    whisper_free(stt->ctx);
    delete stt;
}

Status stt_transcribe(Stt *stt, const float *pcm, int n_samples, const char *language,
                      const std::atomic<bool> &cancel, std::string &out, std::string &error) {
    out.clear();
    if (n_samples <= 0 || n_samples > 16000 * 125) {
        error = "bad recording length";
        return Status::Error;
    }
    if (cancel.load()) return Status::Cancelled;
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.suppress_nst = true;
    p.n_threads = stt->n_threads;
    p.language = language;
    p.detect_language = false;
    p.abort_callback = [](void *user) { return static_cast<const std::atomic<bool> *>(user)->load(); };
    p.abort_callback_user_data = const_cast<std::atomic<bool> *>(&cancel);
    if (whisper_full(stt->ctx, p, pcm, n_samples) != 0) {
        if (cancel.load()) return Status::Cancelled;
        error = "speech recognition failed";
        return Status::Error;
    }
    if (cancel.load()) return Status::Cancelled;
    int segments = whisper_full_n_segments(stt->ctx);
    for (int i = 0; i < segments; i++) {
        const char *text = whisper_full_get_segment_text(stt->ctx, i);
        if (text) out += text;
    }
    return Status::Ok;
}

// ---------------------------------------------------------------- voice activity detection (Silero, via whisper.cpp)

struct Vad {
    whisper_vad_context *ctx = nullptr;
};

Vad *vad_load(int fd, int n_threads, std::string &error) {
    FILE *fp = fdopen(fd, "rb");
    if (!fp) {
        close(fd);
        error = "could not read the voice detection model file";
        return nullptr;
    }
    if (n_threads < 1 || n_threads > 16) {
        fclose(fp);
        error = "bad thread count";
        return nullptr;
    }
    FileSource source{fp};
    whisper_model_loader loader;
    init_file_loader(loader, source);
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.use_gpu = false;
    cp.n_threads = n_threads;
    whisper_vad_context *ctx = whisper_vad_init_with_params(&loader, cp);
    loader.close(loader.context);  // whisper_vad_init_with_params never closes the loader
    if (!ctx) {
        error = "could not load the voice detection model file";
        return nullptr;
    }
    auto *vad = new Vad();
    vad->ctx = ctx;
    return vad;
}

Status vad_feed(Vad *vad, const float *pcm, int n, std::vector<float> &probs, std::string &error) {
    probs.clear();
    if (n <= 0 || n % kVadWindowSamples != 0) {
        error = "bad sample count";
        return Status::Error;
    }
    if (!whisper_vad_detect_speech_no_reset(vad->ctx, pcm, n)) {
        error = "voice detection failed";
        return Status::Error;
    }
    int count = whisper_vad_n_probs(vad->ctx);
    const float *values = whisper_vad_probs(vad->ctx);
    if (count != n / kVadWindowSamples || !values) {
        error = "voice detection failed";
        return Status::Error;
    }
    probs.assign(values, values + count);
    return Status::Ok;
}

void vad_reset(Vad *vad) {
    if (vad) whisper_vad_reset_state(vad->ctx);
}

void vad_free(Vad *vad) {
    if (!vad) return;
    whisper_vad_free(vad->ctx);
    delete vad;
}

}  // namespace eg
