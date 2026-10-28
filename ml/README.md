# Offline voice typing and grammar check

Both features run completely on the phone. The keyboard has no `INTERNET` permission and audio and text never leave
the device. Models are downloaded by the separate app Clackatoo Models (see "Getting models onto the phone") or imported
by hand; the keyboard itself never fetches anything.

| | What | Runtime | Model | Licence | Size |
| --- | --- | --- | --- | --- | --- |
| Voice typing, live text (optional) | speech to text with the words shown while you speak | parakeet.cpp (MIT), module `asr` | Parakeet TDT 0.6B v3, 5-bit (`tdt-0.6b-v3-q5_k.gguf`) | CC-BY-4.0 (NVIDIA) | 742 MB |
| Voice typing | speech to text, English and Spanish | whisper.cpp (MIT) | Whisper small, multilingual, 5-bit (`ggml-small-q5_1.bin`) | MIT | 190 MB |
| Voice typing, English only | clean-up of the transcript: fillers, self-corrections, punctuation, numbers | llama.cpp (MIT) | S1-mini by Superwhisper (`s1-mini-q4_k_m.gguf`) | Apache-2.0 + naming term | 484 MB (462 MiB) |
| Grammar check | spelling and grammar, English and Spanish | llama.cpp (MIT) | Qwen3.5 0.8B, 4-bit (`Qwen3.5-0.8B-Q4_K_M.gguf`) | Apache-2.0 | 533 MB |
| Voice detection (live text) | finds the utterances in the live audio | whisper.cpp's VAD (MIT) | Silero VAD v6.2.0 (`ggml-silero-v6.2.0.bin`), **shipped in the APK** | MIT | 885 KB |
| | | | **Total without Parakeet** | | **1.21 GB** |

The total is above the 1 GB target and inside the 1.5 GB limit, because the smaller grammar models were not good
enough (see "Why these models"). S1-mini must be called "S1-mini" by "Superwhisper" wherever it is used; the credit is
in Settings > Voice typing and in [NOTICE](../NOTICE).

## How it fits together

```
Voice key ──► AssistController ──► AudioRecorder (memory only, 16 kHz)
                    │                       │ stop
                    │                       ▼
                    │   MlClient ══ Binder / pipes / model fd ══► MlService (isolated process ":ml")
                    │                                       ├─ whisper.cpp: audio → text
                    │                                       └─ llama.cpp:  S1-mini prompt → clean text
                    ▼
        AssistPanelView (level meter, Stop, Cancel)  ──►  sanitised text inserted at the cursor

Check key ──► AssistController ──► read selection or field ──► MlService (llama.cpp, Qwen3.5)
                    ▼
        AssistPanelView (corrected text, changes highlighted, Accept / Cancel)
        Accept ──► read text and selection again inside a batch edit; replace exactly the text that was read,
                   only if text, cursor and selection are all still unchanged
```

- `ml/` is a Gradle library module. `ml/src/main/cpp/CMakeLists.txt` builds llama.cpp and whisper.cpp in one CMake
  project, so they share **one ggml** (the copy in llama.cpp; both pin ggml 0.27). On arm64 the CPU backend is built as
  one library per instruction-set level and picked at run time. The HTTP client, server, tools and examples of
  llama.cpp are switched off. The x86_64 build (emulator only) has a single AVX2 backend.
- `ml_core.cpp` is a thin C++ layer, `jni.cpp` the JNI wrapper (`NativeMl.kt`). The same `ml_core.cpp` is built into a
  host test tool (`clackatoo-ml-host`), so models and prompts can be tried on a PC.
- `MlService` is an **isolated process** (`android:isolatedProcess="true"`): a throw-away UID with no permissions that
  cannot open the app's files (`files/`, settings, dictionaries, clipboard history). whisper.cpp and llama.cpp parse
  the model files, so a memory-safety bug triggered by a crafted file would run there and get nothing; a crash or an
  out-of-memory kill takes down only this process: the keyboard shows "A model file could not be loaded", marks the file
  as broken and does not load it again until it is replaced. The process exits when the keyboard has been idle for a
  minute, which frees the models' memory. The service is not exported.
  - **Models arrive as file descriptors**, never as paths: `MlClient` opens the file read-only
    (`ParcelFileDescriptor.open`) and sends it with a cache key (name, size, modification time) that lets the service
    keep a loaded model. The native side takes the descriptor over (`llama_model_load_from_file_ptr` on an `fdopen`ed
    descriptor; `whisper_init_with_params` with a `whisper_model_loader` that reads from it). llama.cpp first maps the
    descriptor (`mmap`) and falls back to reading the file into memory if the mapping is refused.
  - **Replies are untrusted.** They carry numbers only (`what`, `arg1` = request id, `arg2` = timings or an error
    code); the reply's Bundle is never read. The result text goes through a pipe that the keyboard reads with a size
    limit (32 KiB) and then runs through `OutputSanitizer`. Error messages shown to the user are written by the
    keyboard, not taken from the process.
  - **Cancelling** goes through `CallRegistry`: a cancel reaches a native handle only while it is attached to the running
    request, is matched by request id (a late cancel never stops the next request), and the handle is detached before
    anything can free it. A cancelled call ends as "cancelled", not as a partial success.
  - The native layer checks every argument (`n_ctx`, thread count, output length, prompt size), null-checks JNI
    results and catches C++ exceptions at the JNI boundary.
  - The ggml CPU backends are `dlopen`ed from the app's native library directory by the isolated process. On the
    emulator (x86_64) the single CPU backend is also a separate library (`libggml-cpu.so`), so the emulator exercises
    the same loading code as the phone.
- **No user-supplied native library, in any build type.** HeliBoard lets the user provide `libjni_latinime.so` (for
  Google's swipe decoder), which is then loaded into the keyboard's own process; Clackatoo decodes swipes with its own
  built-in decoder instead. The isolation is only worth something if nothing else can load native code there.
  `BuildConfig.ALLOW_USER_LIBRARY` is false for every build type (`app/build.gradle.kts`), `JniUtils` and the advanced
  settings screen check it through `UserLibraryPolicy`, and the setting is hidden. Shipped builds are still built with
  `assembleNouserlib` (upstream's own switch for the same).
- **Replies are read as three integers.** The keyboard's reply endpoint (`ReplyBinder` in `MlClient.kt`) answers the
  Messenger call itself and reads `what`, `arg1` and `arg2` only; a message that carries an object is dropped unread.
- Code: `app/src/main/java/helium314/keyboard/clackatoo/assist/`. Prompt building and output parsing (`Prompts.kt`),
  language selection (`AssistLanguages.kt`), model discovery and import validation (`ModelStore.kt`,
  `ModelCatalog.kt`) and text replacement (`TextEdit.kt`) are plain Kotlin with unit tests in
  `app/src/test/java/helium314/keyboard/clackatoo/assist/`.

### Live text with Parakeet

When a Parakeet model is installed (it comes first in `ModelCatalog.all`, so `AssistController.speechModel()` prefers it
on arm64-v8a and x86_64), the Voice key shows the words in the panel while you speak. Nothing reaches the app until Stop.

```
AudioRecorder ─onChunk─► LiveCall.write ─queue─► "clackatoo-live-pipe" thread ══ audio pipe (PCM16 LE) ══►
   MlService (MSG_LIVE) ─ LiveSession: reader thread ─► Silero VAD (ml: vadFeed, 3 windows of 512 samples) ─► LiveSegmenter
                          decoder loop (worker thread) ─► Parakeet (asr: NativeAsr.transcribe) on a closed segment or a growing open one
   ◄══ event pipe: [type:1][length:4 BE][UTF-8], PARTIAL / SEGMENT / END ══ ◄─┘
MlClient.readEvents (checks every frame, OutputSanitizer) ─► AssistPanelView.showLiveText (committed, partial)
Stop ─► LiveCall.finish ─► the model process decodes the rest, sends END, then MSG_RESULT ─► finishTranscript
        (S1-mini clean-up, sanitising, insert), shared with the Whisper path
```

- `LiveSegmenter` (pure logic) turns the VAD's speech probabilities (one per 512 samples) into utterances: speech at
  p ≥ 0.5, 5,120 samples of padding on each side, a close after 19 silent windows (about 0.6 s) or at 240,000 samples.
  `LiveSession` decodes a closed segment as soon as it exists and re-decodes the open one when it has grown by 8,000
  samples (0.5 s) since its last partial, one decode at a time. A frame is at most 4,096 bytes and the finished text at
  most 32 KiB (`MlProtocol`); the keyboard cancels the call on any other frame.
- **`asr` module.** parakeet.cpp cannot share `ml`'s ggml: `ml` builds ggml with loadable CPU backends, while parakeet
  calls ggml-cpu functions directly. `asr/src/main/cpp/CMakeLists.txt` therefore builds its own ggml, static and with
  hidden symbols, into `libclackatoo_asr.so`, which exports only the `NativeAsr` JNI functions (check with
  `llvm-nm -D --defined-only`). The submodule `asr/native/parakeet.cpp` is the fork
  <https://github.com/highlyproteus/parakeet.cpp> (branch `clackatoo`, v0.6.1 plus one commit): the isolated process may
  read a descriptor it was given but not open paths, and upstream loads by path only, so the fork adds
  `parakeet_capi_load_asr_file_ptr(FILE*)` (ggml's `gguf_init_from_file_ptr`) and `parakeet_capi_set_threads`. The arm64
  build assumes the Arm dot-product instructions; `NativeAsr.load` returns 0 on a CPU without them, the model is then
  marked broken and the next try uses Whisper.
- **Silero VAD** (`ggml-silero-v6.2.0.bin`, 885,098 bytes, MIT) is in `app/src/main/assets/models/`. On first live use
  `ModelStore.ensureBundled` copies it into the model folder through the same checks as an import (exact size and SHA-256);
  it cannot be imported by hand. It runs in the `ml` library through whisper.cpp (`vadLoad` / `vadFeed` in `NativeMl`).
- Memory: Parakeet q5_k reads its 742 MB file into memory and then into its own buffers while loading, so the peak is
  about twice the file; the isolated process keeps only one speech engine loaded.

### Languages

The Whisper language is the current keyboard language. `AssistLanguages.all` lists English and Spanish. To add a
language, add a row (its Whisper code; the multilingual Whisper model knows about 99 languages) and test the grammar
model on it with `ml/eval/`. S1-mini is English only, so `cleanUpWithS1Mini` stays false for other languages: they
get Whisper's own punctuation.

### Adding a model

Add a `CatalogModel` to `Catalog.all` in the `catalog` module (id, kind, engine, file name, display name, maker, licence,
languages, **exact size and SHA-256 of the reference download**, Hugging Face repository and the **40-hex commit** the
file is pinned to; size and digest come from `https://huggingface.co/api/models/<repo>/tree/<commit>`) and a licence entry
with the digest in NOTICE. `ModelCatalog` in the app is built from it. `CatalogTest` checks that every entry is an
https `huggingface.co/.../resolve/<40 hex>/<file>` address with a 64-hex digest. Then run the model on the emulator (below)
before it ships. "Import model file" and the hand-over from Clackatoo Models accept only files with a catalog name, the
exact size and the exact digest (`sha256sum file`): native code parses these files, so no other file may reach it. A Whisper file's header is also checked (`ModelValidator.whisperFiltersOk`, repeated in `ml_core.cpp`
before whisper.cpp reads anything): 80 or 128 mel bands and 201 FFT bins, because whisper.cpp sizes buffers by these
numbers from the file.

## Getting models onto the phone

1. **Clackatoo Models** (the companion app, module `models`): Download next to a model in Settings > Voice typing or
   Grammar check, or in the panel that the Voice and Check keys show when there is no model. The keyboard sends only the
   catalog id; the companion downloads the pinned file (https, Hugging Face hosts only), checks size and SHA-256, hands it
   to the keyboard as a read-only file descriptor and deletes its copy; the keyboard checks it again (`ModelStore.import`).
   Needs about twice the model's size free for a moment (the companion's copy and the keyboard's). The choice per role
   (`ModelChoice`) is kept in the keyboard's preferences; a finished download becomes the chosen model of its role.
2. **Import model file** and 3. **adb**, below.

## Models on the device

The app reads models from its private files folder, `files/models/` (`/data/user/0/<application id>/files/models/`,
which is also where `run-as` starts; checked on the Android 17 emulator: the app's `filesDir`, with its dictionaries
and clipboard files, is under `/data/user/0`, not `/data/user_de`, although the manifest asks for device-protected
storage). The folder is excluded from backup and device transfer in both domains (`file` and `device_file`).
Besides the download, there are two ways to put files there:

1. **Import model file** in Settings > Voice typing or Settings > Grammar check (system file picker, no permission).
   The file is copied to a temporary file of its own, checked (known name, model header, exact size, SHA-256) and only
   then moved over the old one in one atomic step; on any failure nothing is left behind.
2. **adb, for development** (debug build):

   ```sh
   ADB="$HOME/Android/Sdk/platform-tools/adb -s emulator-5554"
   $ADB push models/ggml-small-q5_1.bin /data/local/tmp/
   $ADB shell run-as io.github.highlyproteus.clackatoo.debug mkdir -p files/models
   $ADB shell run-as io.github.highlyproteus.clackatoo.debug cp /data/local/tmp/ggml-small-q5_1.bin files/models/
   $ADB shell rm /data/local/tmp/ggml-small-q5_1.bin
   ```

   Repeat for `tdt-0.6b-v3-q5_k.gguf` (Parakeet, live text), `s1-mini-q4_k_m.gguf` and `Qwen3.5-0.8B-Q4_K_M.gguf` (kept in a `models/` folder outside the
   repository). Files placed with adb skip the import checks (development only; nothing restores or transfers models by itself, since
   they are excluded from backup). The isolated model process still protects the keyboard, and whisper.cpp's header
   check still runs.

Reference downloads (free; these exact files match the pinned sizes and digests). The URLs are the catalog's
(`Catalog.all[...].url`), all `https://huggingface.co/<repo>/resolve/<commit>/<file>`:

| File | Repository @ commit |
| --- | --- |
| `tdt-0.6b-v3-q5_k.gguf` (741,867,360 bytes), `tdt_ctc-110m-q5_k.gguf` | `mudler/parakeet-cpp-gguf` @ `c77726e46ceaab96b5a5a3328915edb44c73209d` |
| `ggml-tiny-q5_1.bin`, `ggml-base-q5_1.bin`, `ggml-small-q5_1.bin` | `ggerganov/whisper.cpp` @ `5359861c739e955e79d9a303bcbc70fb988958b1` |
| `s1-mini-q4_k_m.gguf` | `superwhisper/s1-mini-GGUF` @ `34add00a48a2e5d24e5a4ee5405a99620a3a240c` |
| `Qwen3.5-0.8B-Q4_K_M.gguf` | `unsloth/Qwen3.5-0.8B-GGUF` @ `6ab461498e2023f6e3c1baea90a8f0fe38ab64d0` |

(The q4_k Parakeet file of the same revision, 675 MB, was tried first and mis-heard the test sentence in two more places
than Whisper small, see "Measured times".)

## Debug entry point: transcribe a WAV file

The emulator has no microphone. Debug builds (and only debug builds) accept a broadcast that runs a WAV file through
the same pipeline as a recording (speech to text, English clean-up, insert into the focused field):

```sh
$ADB push en.wav /data/local/tmp/ && \
$ADB shell run-as io.github.highlyproteus.clackatoo.debug mkdir -p files/debug && \
$ADB shell run-as io.github.highlyproteus.clackatoo.debug cp /data/local/tmp/en.wav files/debug/
$ADB shell am broadcast -a io.github.highlyproteus.clackatoo.debug.TRANSCRIBE_WAV --es name en.wav          # language: the keyboard's
$ADB shell am broadcast -a io.github.highlyproteus.clackatoo.debug.TRANSCRIBE_WAV --es name es.wav --es language es
```

With a Parakeet model installed the clip is played into the live path instead (1,600 samples every 100 ms, then Stop),
so the panel shows the partial text while the clip "plays".

The WAV must be 16 kHz mono 16-bit (`ffmpeg -i in.wav -ar 16000 -ac 1 -c:a pcm_s16le out.wav`). The receiver is
registered in code only when `BuildConfig.DEBUG` is true and the app is debuggable, requires the sender to hold
`android.permission.DUMP` (the adb shell does; other apps do not) and is not in the manifest. Release and nouserlib
builds neither contain nor register it. It follows the Voice key's rules (no password fields), and nothing that is said
or transcribed is logged, in any build.

The test recordings were made with Piper voices (a local text-to-speech tool, not shipped):

```sh
python3 -m venv /tmp/tts-venv && /tmp/tts-venv/bin/pip install piper-tts
echo "So um I need to send the report to Sarah by uh Friday, no wait, make that Thursday at three thirty." | \
  /tmp/tts-venv/bin/python -m piper -m en_US-lessac-medium.onnx -f en_raw.wav     # voices: huggingface.co/rhasspy/piper-voices
ffmpeg -i en_raw.wav -ar 16000 -ac 1 -c:a pcm_s16le -fflags +bitexact -map_metadata -1 en.wav
```

## Building

```sh
git submodule update --init --depth 1        # ml/native/whisper.cpp, ml/native/llama.cpp and asr/native/parakeet.cpp
git -C asr/native/parakeet.cpp submodule update --init --depth 1 third_party/ggml   # parakeet.cpp's ggml
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --install "cmake;3.31.6" "ndk;28.0.13004108"
# from the repository root (ABIs: arm64-v8a for the phone, x86_64 for the emulator):
mise exec java@21 -- ./gradlew -Dorg.gradle.jvmargs=-Xmx3g --max-workers=4 assembleDebugNoMinify
# only the emulator ABI, faster:
mise exec java@21 -- ./gradlew -Dorg.gradle.jvmargs=-Xmx3g --max-workers=4 -Pandroid.injected.build.abi=x86_64 assembleDebugNoMinify
```

The native build allows two compile jobs per ABI (Ninja job pool in `CMakeLists.txt`). The first build of an ABI
compiles all of llama.cpp and whisper.cpp and takes a while; later builds are incremental. Unit tests
(`:app:testRunTestsUnitTest`) do not build native code.

Host test tool and the grammar comparison (the host build uses the same `ml_core.cpp`):

```sh
cmake -S ml/src/main/cpp -B /tmp/ml-host -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON && cmake --build /tmp/ml-host -j4 --target clackatoo-ml-host
cp /tmp/ml-host/clackatoo-ml-host /tmp/ml-host/bin/        # it looks for the ggml backends next to itself
/tmp/ml-host/bin/clackatoo-ml-host stt models/ggml-small-q5_1.bin 4 en en.wav
python3 ml/eval/run_grammar_eval.py /tmp/ml-host/bin/clackatoo-ml-host models/llm 4
```

The host tool opens the model as a descriptor like the app does. Its `llm` prompts are trusted test input whose special
tokens are parsed; a prompt that contains 0x1f is cut there into parts that alternate special, plain, special, ... exactly
as the app tokenises chat prompts (template markers special, the user's text never).

Only the Qwen3.5-0.8B-Q4_K_M file is needed on the device for the grammar check; the other models in the eval script
are the losing candidates.

## Measured times

Emulator (x86_64, 4 virtual cores = 2 model threads, AVX without AVX2; not representative of the Pixel 10 Pro XL):

| Step | Input | Time |
| --- | --- | --- |
| Whisper small, English | 5.2 s clip | 12.5 s (+1.4 s first load) |
| Whisper small, Spanish | 4.4 s clip | 12.3 s |
| S1-mini clean-up | 1 sentence | 2.8 s (+2.7 s first load) |
| Grammar check (Qwen3.5 0.8B) | 55 characters | 2.4 s |

Parakeet live text, emulator `clackatoo-test` (arm64-v8a under Apple virtualisation, 4 cores, 4 GB, with the Arm
dot-product instructions; 2026-10-27), the 5.2 s English test clip fed at real time, model process started cold:

| Step | Time |
| --- | --- |
| Parakeet q5_k (742 MB) and Silero VAD load, in the model process | 0.74 s and 0.80 s in two runs (process start not included) |
| First partial text, from the start of the recording | 1.5 s and 1.1 s in two runs |
| Recognition of the whole clip, all partial passes added | 5.8 s for 5.2 s of audio |
| Text ready after the audio ended (the keyboard waits for this after Stop) | 1.8 s (Whisper small, same clip: 8.2 s) |
| 28 s dictation of several sentences | 34.6 s of recognition in total; text ready 7.4 s after the audio ended, because the last utterance had been growing for 12 s |

The q4_k file (675 MB) of the same Parakeet revision heard the clip worse than Whisper small ("So I might need ...",
"make the Thursday"), so q5_k is the catalog model; q5_k's differences are "by a Friday" for "by uh Friday" and "make the" for
"make that" (Whisper small has the first too). The host `parakeet-cli` produced the same text as the emulator for both files.

### Every option in the catalog, on the emulator (2026-10-28)

Each file was downloaded from its pinned URL (size and SHA-256 checked against the catalog), copied into the debug app's
`files/models/` as the only speech model, and the 5.2 s English test clip ("So um I need to send the report to Sarah by uh
Friday, no wait, make that Thursday at three thirty.") was run through `TRANSCRIBE_WAV`, with S1-mini removed so the text
in the field is the raw transcript. Same emulator as above (`clackatoo-test`: arm64-v8a, 4 cores, 4 GB). The text is what
the field showed; times are from the app's log (load = loading the file in the model process, "after audio" = the wait
after Stop for the live models, whole transcription for Whisper). All of them load and run. The larger ones (Parakeet
v2, Nemotron 3.5, Whisper large v3 turbo, Qwen3.5 2B) were then taken out of the catalog: on these clips they were no
more accurate than the smaller options and were slower or needed much more memory; their rows stay here as the reason.

| Model | Load | Time | Text in the field |
| --- | --- | --- | --- |
| Parakeet TDT 0.6B v3 | 0.58 s | 2.4 s after audio | "So um I need to send the report to Sarah by a Friday. No wait, make the Thursday at three thirty." |
| Parakeet TDT 0.6B v2 (English) | 0.46 s | 2.9 s after audio | "So am I need to send the report to Sarah by a Friday? No, wait, make that Thursday at three thirty." |
| Parakeet TDT-CTC 110M (English) | 0.79 s | 0.4 s after audio | "So am I need to send the report to Sarah by a Friday? No wait, make the Thursday at three thirty." |
| Nemotron 3.5 ASR streaming 0.6B | 1.9 s | 3.9 s after audio | "So what might need to send the report to Sarah by a Friday?  No wait, make the Thursday at three thirty." |
| Whisper tiny | 0.12 s | 1.2 s | "So while my need to send the report to Sarah by a Friday, no wait, make the Thursday at 330." |
| Whisper base | 0.13 s | 4.0 s | "So I might need to send the report to Sarah by off Friday. No wait, make the Thursday at 3.30." |
| Whisper small | 1.4 s | 11.3 s | "So I need to send the report to Sarah by a Friday, no wait, make that Thursday at 3.30." |
| Whisper large v3 turbo | 0.28 s | 52.5 s | "So am I need to send the report to Sarah by a Friday. No wait, make that Thursday at 3.30." |
| Qwen3.5 2B (grammar check) | | 3.5 s for 98 characters | corrected "Ths is a tset of the grammer chek, i hope it werk" to "This is a test of the grammar. Check, I hope it works." |

Nemotron is a cache-aware streaming model; parakeet.cpp's offline decoder runs it here (the live text comes from the same
segment-wise decoding as for the other Parakeet models), so its streaming-specific accuracy is not what this measures. The
2B grammar model maps its 1.28 GB file and repacks the weights, which takes about 2.5 GB of memory in the model process: on
the 4 GB emulator the low-memory killer ended the process on the first try (the keyboard then marks the file as not
loadable until it is imported again) and it ran normally once other apps were closed. Whisper large v3 turbo is too slow on
the emulator to be pleasant; the phone is faster, but it is the one option to expect to be slow. Qwen3.5 0.8B and S1-mini
are unchanged from the tables above.

Whisper always encodes a full 30 s window, so the time barely depends on the clip length. On a desktop CPU (16 cores,
4 threads) the same clips take 2.3 s and a grammar sentence 0.65 s. Limiting Whisper's encoder to the clip
(`audio_ctx`) made it 2.5 to 3.5 times faster on the host but changed the text of two of five clips for the worse
("se me olvida" became "se me olida"), so it is not used. Not measured: the phone itself.

### Which model for which phone ("Suggested for this phone")

`DeviceProfile` (keyboard app) reads the phone: total RAM (`ActivityManager.MemoryInfo.totalMem`), the first ABI, the Android
version, whether the CPU has the Arm dot-product instructions (`asimddp` in `/proc/cpuinfo`; always true on x86_64) and the core
count. `ModelSuggestions` turns that into one suggested catalog model per role. The suggestion is shown first in Settings with
the label "Suggested for this phone" and is what the "needs a model" sheet offers; every other listed model can still be
downloaded, chosen and used, and nothing is forced.

Measured on the emulator `clackatoo-test` (arm64-v8a under Apple virtualisation, 4 cores, 4,061,984 kB `MemTotal`, Android 17,
Arm dot-product present; 2026-10-28), debug build, each model alone in `files/models/`, the model process started cold. Peak is
`VmHWM` of the `:ml` process (peak resident memory, read from `/proc/<pid>/status`, polled once a second during the run and
read again at the end); PSS is `dumpsys meminfo` at the end. The clip is the 5.2 s English test clip (`en.wav`) through
`TRANSCRIBE_WAV`, the grammar text is 390 characters through the Check key. Whisper and Parakeet were run with no clean-up
model installed. S1-mini's number is the difference between a run with Whisper tiny and S1-mini loaded (1,256 MB) and Whisper tiny
alone (248 MB).

| Model | Peak (VmHWM) | PSS | Time on the test input |
| --- | --- | --- | --- |
| Whisper tiny | 248 MB | 165 MB | 1.1 s after Stop (load 0.14 s) |
| Whisper base | 298 MB | 215 MB | 1.1 s (load 0.09 s) |
| Whisper small | 497 MB | 413 MB | 5.7 s (load 0.25 s) |
| Parakeet 110M (live) | 324 MB | 242 MB | 1.3 s of recognition in all, text ready 0.2 s after the audio ended |
| Parakeet v3 (live) | 938 MB | 854 MB | 4.5 s of recognition in all, text ready 1.0 s after the audio ended (load 0.7 s) |
| S1-mini (clean-up) | about 1,010 MB | | 0.5 s for one sentence (load 0.7 s) |
| Qwen3.5 0.8B (grammar) | 1,318 MB | 1,233 MB | 2.0 s for 390 characters |

A 484 MB or 533 MB text model takes about 1 to 1.3 GB because the weights are mapped from the file and then repacked for the
CPU's matrix kernels. The model process keeps the models of the current use resident (see "Only the models of the current use stay in memory"), so Voice
typing holds a speech model and S1-mini together and a Check holds the grammar model alone: the worst case on a phone is the
larger of the two (938 + 1,010 MB with Parakeet v3, about 1.9 GB measured), not all three. Where a model
was too big for the memory it was killed on the same 4 GB emulator (Qwen3.5 2B, about 2.5 GB, see above), which is the only
failure observed; the real phone was not measured.

#### Only the models of the current use stay in memory (`ModelResidency`)

The model process used to keep every model it had loaded until it exited (a minute after the last request), so Voice then Check
left the speech model, the voice detector, S1-mini and Qwen in memory together, and every further custom text model added to it.
`ModelResidency` now keeps one use resident: **voice** = one speech engine (Whisper or Parakeet) + the voice detector + the clean-up
model; **check** = the grammar model. `MSG_GENERATE` carries the purpose (`KEY_PURPOSE`: `PURPOSE_CLEAN_UP` or `PURPOSE_GRAMMAR`; any
other value is `ERR_BAD_REQUEST`), a request frees everything that is not part of its use first, and a slot that holds another
file is freed before the new one loads (so at most one text model per purpose). The freeing happens on the worker thread before the
request registers anything for cancelling, and the leaked-detector rule of live text is unchanged.

Measured on the same emulator (2026-10-28, debug build, `:ml` process started cold, `VmRSS` / `VmHWM` from `/proc/<pid>/status`
after each step, no waiting for the idle exit; Parakeet v3 + S1-mini for voice, Qwen3.5 0.8B and two custom Qwen3-0.6B files
(Q4_K_M "A", Q3_K_S "B") for grammar):

| Step | Before: RSS / HWM | After: RSS / HWM |
| --- | --- | --- |
| 1 voice (v3 + S1-mini) | 1,941 / 1,945 MB | 1,936 / 1,936 MB |
| 2 check (Qwen3.5 0.8B) | 1,943 / 2,301 MB | 1,346 / 1,934 MB |
| 3 voice again | 2,499 / 2,509 MB | 1,466 / 1,934 MB |
| 4 check, custom grammar A | (process had exited, not measured) | 1,258 / 1,934 MB |
| 5 check, custom grammar B | 860 / 863 MB (new process) | 824 / 1,934 MB |
| 6 check, Qwen3.5 0.8B | 2,018 / 2,018 MB (new process) | 1,263 / 1,934 MB |

Before, the process after a Check held about 1.94 GB of resident memory (nothing was freed) and the second Voice took it to 2.5 GB;
after, a Check leaves about 1.3 GB (Qwen alone) and the peak never exceeds the voice pair's 1.94 GB. (The "before" run lost its
process at step 4 because the settings screens took longer than the process's one-minute idle exit, so steps 5 and 6 there started
from a fresh process; the growth is already visible in steps 2 and 3.) The price is load time when the use changes: the first
Voice after a Check loads Parakeet v3 and S1-mini again, 0.45 s and 1.0 s (it was 0 s while they were resident), and the first Check
after a Voice loads the grammar model (about the same as a first Check); repeating the same use costs nothing.

The rules, from those numbers (`ModelSuggestions`, `ModelSuggestionsTest`):

- **Budget:** the models of one use (the only ones resident at a time) together may take 45 percent of the phone's RAM (`totalMem`); the rest is for Android
  and the app in front. For Voice typing that is the speech model plus S1-mini (when one of the keyboards is English, because
  S1-mini only cleans up English), for the grammar check the grammar model alone.
- **Speech:** the first of Parakeet v3, Parakeet 110M, Whisper small, base, tiny that speaks every keyboard language and fits
  the budget together with S1-mini. Parakeet (live text) additionally needs the dot-product instructions on arm64 and at least
  four cores: it decodes the growing utterance while you speak. When nothing fits, the smallest Whisper model is suggested and
  the card says "May be slow on this phone".
- **Clean-up:** S1-mini when the user has an English keyboard and it fits next to the suggested speech model; otherwise none.
- **Grammar:** Qwen3.5 0.8B, the only grammar model, flagged "May be slow on this phone" when it alone is over the budget or the
  CPU has no dot-product instructions.
- A phone that cannot run the native code at all (not arm64-v8a or x86_64, or older than Android 9) gets no suggestion, as before.

What that gives (for an English-only keyboard unless noted): 8 GB and bigger with dot-product, 6 GB: Parakeet v3 (with S1-mini and
Qwen); a 4 GB phone (3.7 GiB reported): Parakeet 110M, or v3 for a user with no English keyboard, or Whisper small for English and
Spanish together; 3 GiB: Whisper base; 2.8 GiB and less: Whisper tiny; no dot-product or fewer than four cores: no live model.
The emulator (3.9 GiB, 4 cores) therefore shows Parakeet 110M, S1-mini and Qwen3.5 0.8B as "Suggested for this phone".

### Experimental custom models

The catalog is the suggested, verified set. Below it, each role (speech, clean-up, grammar) has "Use your own model
(experimental)" rows, behind a one-time notice (experimental, not checked by Clackatoo, may not work, may be slow or use a lot
of memory, results may be poor; it runs in the locked-down model process without internet or access to what you type in other
apps). This is the one exception to "a model file is byte for byte a known file" and there are exactly two ways in, both
started by the user:

1. **Import file** (Settings > Voice typing or Grammar check): any file from the system file picker. `ModelStore.importCustom`
   copies it to a temporary file (at most 4 GiB, checked against the announced size and again while copying; the free space
   must cover the file plus 128 MB), finds the format **from the first 256 KiB** (`ModelFormats.detect`: a Whisper ggml file with
   80 or 128 mel bands and 201 FFT bins is Whisper, English-only when its vocabulary has 51,864 entries; a GGUF whose
   `general.architecture` is `parakeet` is a Parakeet speech model; any other GGUF is a possible text model; everything else,
   and GGUF files of the Parakeet tools that are not recognisers, are refused), checks that the format fits the role (a text
   model cannot be a speech model and the other way round), and then does **one test load in the isolated model process**
   (`MSG_PROBE`: load the file with the engine of the role, free it again, run nothing). A file that fails any step is
   removed and the message says why. At most eight custom models are kept.
2. **Hugging Face, in the Clackatoo Models app only:** "Browse Hugging Face" (behind the same notice). The user types a search
   or a `owner/name`, sees the files (name, size) from the Hugging Face API, and picks a file and the role it is for. The
   companion downloads it (https, `huggingface.co` and `*.hf.co` only, also for redirects, pinned to the commit the API
   returned) and hands it to the keyboard with its own Binder transaction, `TX_CUSTOM_HANDOVER` (the name, a role number and a
   read-only descriptor, nothing else). The keyboard refuses it until the user has accepted the notice in its own settings, then
   treats it exactly like an imported file: size cap, format from the header, role, test load. The keyboard never sends a URL,
   a query or anything but catalog ids to the companion.

A custom model lives in `files/models/custom/<role>/<sanitised name>` (separate from the catalog files; the name is reduced to
`A-Z a-z 0-9 . _ -`, at most 80 characters, never `..` or hidden), its id is `custom:<role>:<name>` and its card says
"Experimental · not verified". It is used **only when the user has chosen it** (an import or hand-over makes it the choice of its
role; the radio on the card chooses another), never as a fallback, and the menu on the card deletes it. Whisper custom models
are told English-only by their header and are not used for Spanish. A custom text model is prompted with **its own chat
template**: `llm_chat` applies the template stored in the model file with llama.cpp's `llama_chat_apply_template` (which knows
the common templates, it is not a Jinja interpreter) with markers in place of the texts and tokenises only the template's own
pieces as special tokens, so text in a field cannot inject special tokens (a model whose template uses plain-text markers such as `[INST]` or `### Instruction:` can still be prompt-injected by typed text; accepted for experimental models, whose output is sanitised and shown or limited as for every model); a model with no usable template gets ChatML, and a template
that mentions `enable_thinking` (Qwen3 family) gets the empty think block after the assistant's opening, which is what the
template does with thinking off. Results depend on the model; the clean-up and grammar prompts are the same system prompts as
for the catalog models.

IPC added for this, for the security review: `ModelsProtocol.TX_CUSTOM_HANDOVER = 4` on the keyboard's `ModelReceiverService`
(same signature permission, caller package and signature checked on every call; `writeString(name)`, `writeInt(role)`,
`writeFileDescriptor`, and nothing else, any other shape is answered `HANDOVER_BAD_REQUEST`), the reply codes 8 to 12, and
`MlProtocol.MSG_PROBE = 5` and `KEY_CHAT_ROLES` between the keyboard and the model process (see the code for the checks).

## Why these models

Whisper small (5-bit) is 190 MB and transcribed both test clips correctly; large-v3-turbo (5-bit, 574 MB) was about
three times slower on the same host and no more accurate on them, and would put the total over 1.5 GB.

For the grammar check, four candidates ran the same 24 sentences (12 English, 12 Spanish, with spelling, agreement and
tense errors, two clean sentences and two chat-style ones) through `ml_core.cpp`, with the same system prompt and
greedy decoding (`ml/eval/grammar-cases.json`, `ml/eval/run_grammar_eval.py`). "sim" is the average character
similarity to the reference correction (1.0 is identical); host times are for 4 threads on a desktop CPU and are
only good for comparing the models with each other.

| Model (4-bit) | Licence | Size | English sim | Spanish sim | ms per sentence (host) |
| --- | --- | --- | --- | --- | --- |
| Granite 4.0 350M | Apache-2.0 | 237 MB | 0.881 | 0.677 | 245 |
| Qwen3 0.6B | Apache-2.0 | 397 MB | 0.948 | 0.952 | 549 / 643 |
| **Qwen3.5 0.8B** | Apache-2.0 | 533 MB | **0.966** | **0.978** | 758 / 644 |
| Qwen3 1.7B (over budget) | Apache-2.0 | 1107 MB | 0.942 | 0.979 | 1293 |

Granite 4.0 350M often answered a different question or left errors in. Qwen3 0.6B changed the meaning of a few
sentences ("No sé si podré llegar" became "No se puede llegar"; "Haber si nos vemos" became "Habíamos visto"), which
is the worst failure for a grammar check. Qwen3.5 0.8B made the fewest harmful changes, and Qwen3 1.7B with two
examples in the prompt was only slightly better (0.969 / 0.983) at twice the size, which would take the total to 1.8 GB,
so Qwen3.5 0.8B is the choice. Showing the model examples or the language name did not help it, so the prompt is a
plain system prompt (`GrammarPrompt`). The Check key never replaces anything without showing the result first, and
rejects answers that are empty, much longer or shorter than the text, or that replace most of its words.
