# Clackatoo

An offline Android keyboard with voice typing and a grammar check, based on
[HeliBoard](https://github.com/HeliBorg/HeliBoard) v4.2. Clackatoo has no `INTERNET` permission: typing, speech
recognition and grammar correction all run on the device.

| Dark | Dark, alternate palette | Light |
| --- | --- | --- |
| ![Dark](docs/screenshots/16-follow-phone-dark-baseline.png) | ![Dark, alternate palette](docs/screenshots/17-follow-phone-dark-amber.png) | ![Light](docs/screenshots/18-follow-phone-light-baseline.png) |

| Voice typing | Grammar check | Popup keys |
| --- | --- | --- |
| ![Voice typing](docs/screenshots/19-follow-phone-voice-panel.png) | ![Grammar check](docs/screenshots/20-follow-phone-check-panel.png) | ![Popup keys](docs/screenshots/21-follow-phone-popup-keys.png) |

## Features

- **System theming.** Square keys with a hairline edge and Inter labels, coloured from the system's Material 3
  palette. The keyboard follows palette and dark-theme changes without a restart.
- **Voice typing.** Speech to text with Whisper (English and Spanish). English transcripts are cleaned up by S1-mini by
  Superwhisper (filler words, self-corrections, punctuation, numbers).
- **Grammar check.** Corrects the selection or the whole field with Qwen3.5 0.8B. Changes are shown highlighted and
  applied only when accepted.
- **Everything from HeliBoard.** Layouts, dictionaries, clipboard history, colour themes and the Material, Holo and
  Rounded styles.

## Installation

Clackatoo is distributed as a signed APK on the [releases page](https://github.com/highlyproteus/clackatoo/releases).
Updates can be tracked with [Obtainium](https://github.com/ImranR98/Obtainium) using the repository URL
`https://github.com/highlyproteus/clackatoo`.

After installation, enable the keyboard under Settings > System > Keyboard > On-screen keyboard.

Each release lists the package name and signing certificate for verification with
[AppVerifier](https://github.com/soupslurpr/AppVerifier).

### Models

The voice and grammar models (about 1.2 GB in total) are not bundled. They are imported from
Settings > Voice typing and Settings > Grammar check:

| Feature | File | Size |
| --- | --- | --- |
| Voice typing | [ggml-small-q5_1.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin) | 190 MB |
| English clean-up (optional) | [s1-mini-q4_k_m.gguf](https://huggingface.co/superwhisper/s1-mini-GGUF/resolve/main/s1-mini-q4_k_m.gguf) | 484 MB |
| Grammar check | [Qwen3.5-0.8B-Q4_K_M.gguf](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf) | 533 MB |

Only these exact files are accepted; each is pinned by size and SHA-256. See [ml/README.md](ml/README.md).

## Theming

The default colour theme, "Clackatoo (follows the phone)", maps keyboard elements to Material 3 colour roles:

| Element | Colour role |
| --- | --- |
| Keyboard background, suggestion strip | `surface` |
| Letter keys, space bar | `surfaceContainerHigh`, 1 px `onSurface` edge (12% dark, 16% light) |
| Function keys, popups | `surfaceContainerHighest` |
| Key labels, toolbar icons | `onSurface` |
| Hint letters, secondary text | `onSurfaceVariant` |
| Pressed and selected keys, active controls | `primary` |

Android 14 and later provide these roles directly; Android 12 and 13 use the nearest system tones, and earlier
versions use a fixed fallback palette. Labels are kept at a contrast ratio of at least 4.5:1, falling back to black or
white where a system colour would not meet it.

## Privacy and security

- **No network access.** The app does not request `INTERNET`; release builds fail if it appears.
- **Isolated model process.** whisper.cpp and llama.cpp run in an isolated process
  (`android:isolatedProcess="true"`) with no permissions and no access to the keyboard's files. Models are passed as
  read-only file descriptors and all output is treated as untrusted.
- **Microphone.** `RECORD_AUDIO` is requested on first use of the Voice key. Audio is held in memory only, and
  recording stops when the voice panel is hidden. Voice and Check are disabled in password fields.
- **No external native code.** HeliBoard's option to load a user-supplied gesture-typing library is removed in every
  build type, so no third-party code runs inside the keyboard process. As a result, swipe typing is not available.
- **Clipboard.** Clips marked sensitive (`android.content.extra.IS_SENSITIVE`) are not stored, and clipboard history
  is excluded from backups.
- **System-app installs.** When preinstalled in a ROM, the keyboard works before the first unlock while clipboard
  history, learned words and models remain in credential-protected storage.

## Building

Requirements: JDK 21, Android SDK (platform 37, build-tools 37.0.0), NDK `28.0.13004108`, CMake `3.31.6`.

```sh
git submodule update --init --depth 1   # whisper.cpp and llama.cpp
./gradlew assembleDebugNoMinify         # debug build (io.github.highlyproteus.clackatoo.debug)
./gradlew assembleNouserlib             # release build, unsigned
./gradlew :app:testRunTestsUnitTest     # unit tests
```

Development notes are in [AGENTS.md](AGENTS.md).

## Releases

Releases are built and signed by the [Release workflow](.github/workflows/release.yml) when a `vX.Y.Z` tag matching
`versionName` in `app/build.gradle.kts` is pushed. The workflow verifies the package name, version, permissions and
signing certificate before publishing. Signing is configured once with `tools/make-release-key`.

## Upstream

`main` is HeliBoard v4.2 plus Clackatoo's changes. HeliBoard releases are merged from the `upstream` remote
(`https://github.com/HeliBorg/HeliBoard`). HeliBoard's original README, contribution guide and AI policy are kept in
[docs/](docs/HELIBOARD_README.md).

## License

GPL-3.0-only, as HeliBoard; see [LICENSE](LICENSE). Portions from AOSP are Apache-2.0
([LICENSE-Apache-2.0](LICENSE-Apache-2.0)). The bundled Inter font is under the SIL Open Font License 1.1
([third_party/inter/OFL.txt](third_party/inter/OFL.txt)). Modifications to HeliBoard (GPL-3.0 section 5a) and
third-party notices are listed in [NOTICE](NOTICE).
