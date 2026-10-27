// SPDX-License-Identifier: GPL-3.0-only
//
// Host test tool, built from the same ml_core.cpp as the app:
//   clackatoo-ml-host llm  <model.gguf> <threads> <max_new_tokens> < prompts   (prompts separated by 0x1e)
//   clackatoo-ml-host stt  <model.bin>  <threads> <language> <audio.wav>   (16 kHz mono 16-bit WAV)
// llm prints, per prompt: milliseconds, a newline, the output, 0x1e. A prompt is trusted test input and its special
// tokens are parsed, unless it contains 0x1f: then it is cut at every 0x1f into parts that alternate special, plain,
// special, ... exactly as the app tokenises chat prompts (template markers special, the text never).

#include <fcntl.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#include "ml_core.h"

static double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: see header of ml_host.cpp\n");
        return 2;
    }
    eg::init_backends(nullptr);
    fprintf(stderr, "%s\n", eg::system_info().c_str());
    std::string mode = argv[1], error;
    int threads = atoi(argv[3]);
    std::atomic<bool> cancel{false};
    auto t0 = std::chrono::steady_clock::now();
    if (mode == "llm") {
        eg::Llm *llm = eg::llm_load(open(argv[2], O_RDONLY), 2048, threads, error);
        if (!llm) { fprintf(stderr, "load failed: %s\n", error.c_str()); return 1; }
        fprintf(stderr, "load %.0f ms\n", ms_since(t0));
        std::string all((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());
        size_t pos = 0;
        while (pos < all.size()) {
            size_t end = all.find('\x1e', pos);
            if (end == std::string::npos) end = all.size();
            std::string prompt = all.substr(pos, end - pos), out;
            pos = end + 1;
            auto t = std::chrono::steady_clock::now();
            std::vector<eg::PromptPart> parts;
            for (size_t from = 0; from <= prompt.size();) {
                size_t cut = prompt.find('\x1f', from);
                if (cut == std::string::npos) cut = prompt.size();
                parts.push_back({prompt.substr(from, cut - from), parts.size() % 2 == 0});
                from = cut + 1;
            }
            if (parts.size() == 1) parts[0].special = true;
            bool ok = eg::llm_generate(llm, parts, atoi(argv[4]), cancel, out, error) == eg::Status::Ok;
            printf("%.0f\n%s\x1e", ms_since(t), ok ? out.c_str() : ("ERROR: " + error).c_str());
            fflush(stdout);
        }
        eg::llm_free(llm);
    } else {
        eg::Stt *stt = eg::stt_load(open(argv[2], O_RDONLY), threads, error);
        if (!stt) { fprintf(stderr, "load failed: %s\n", error.c_str()); return 1; }
        fprintf(stderr, "load %.0f ms\n", ms_since(t0));
        if (argc < 6) return 2;
        std::ifstream f(argv[5], std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        if (bytes.size() < 44) { fprintf(stderr, "bad wav\n"); return 1; }
        size_t n = (bytes.size() - 44) / 2;
        std::vector<float> pcm(n);
        const short *s = reinterpret_cast<const short *>(bytes.data() + 44);
        for (size_t i = 0; i < n; i++) pcm[i] = s[i] / 32768.0f;
        auto t = std::chrono::steady_clock::now();
        std::string out;
        bool ok = eg::stt_transcribe(stt, pcm.data(), (int) n, argv[4], cancel, out, error) == eg::Status::Ok;
        printf("%.0f\n%s\n", ms_since(t), ok ? out.c_str() : ("ERROR: " + error).c_str());
        eg::stt_free(stt);
    }
    return 0;
}
