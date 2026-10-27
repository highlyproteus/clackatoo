# Clackatoo

Clackatoo is an Android keyboard: a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) (v4.2) with its own
default look, offline voice typing and an offline grammar check. It has no `INTERNET` permission, so nothing you type
or say leaves the phone. It is free software under GPL-3.0.

- **Looks like your phone.** Square keys with a hairline edge, Inter key labels and the phone's own colours: it follows
  the system palette and dark theme and recolours itself when they change. All of HeliBoard's styles, colour themes,
  layouts and dictionaries are still there.
- **Voice key**: tap, speak, tap Stop. Whisper turns speech into text on the phone (English and Spanish); for English,
  S1-mini by Superwhisper then cleans it up (fillers, self-corrections, punctuation).
- **Check key**: checks the selected text, or the whole field, and shows the corrections before anything changes.
- **Private by design.** No network access; clipboard history skips clips marked sensitive (password managers do) and
  is left out of backups; the models run in an isolated process with no access to the keyboard's data.

**Modifications notice (GPL-3.0 section 5a):** this is a modified version of HeliBoard v4.2, modified starting on
2026-10-20. See [NOTICE](NOTICE) for what changed.

## Install

With [Obtainium](https://github.com/ImranR98/Obtainium): **Add App**, paste
`https://github.com/highlyproteus/clackatoo`, leave everything else as it is, then **Add** and **Install**. Obtainium
offers each new release as an update. Or download the APK from
[Releases](https://github.com/highlyproteus/clackatoo/releases).

Then turn it on: Settings > System > Keyboard > On-screen keyboard > Manage on-screen keyboards, enable Clackatoo and
make it the default. Android may warn that a keyboard can see what you type; Clackatoo has no network access.

Every release page lists the package name and signing certificate for [AppVerifier](https://github.com/soupslurpr/AppVerifier).

### Voice typing and grammar check models

The models (about 1.2 GB together) are not in the APK. Download the ones you want on the phone and import them in
Clackatoo's Settings > Voice typing and Settings > Grammar check:

| Feature | File |
| --- | --- |
| Voice typing (needed) | [ggml-small-q5_1.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin) (190 MB) |
| Voice typing clean-up, English (optional) | [s1-mini-q4_k_m.gguf](https://huggingface.co/superwhisper/s1-mini-GGUF/resolve/main/s1-mini-q4_k_m.gguf) (484 MB) |
| Grammar check | [Qwen3.5-0.8B-Q4_K_M.gguf](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf) (533 MB) |

Import accepts only these exact files (each is pinned by size and SHA-256). You can delete the downloads afterwards.
Details: [ml/README.md](ml/README.md).

## Screenshots

| Dark, blue palette | Dark, amber palette | Light |
| --- | --- | --- |
| ![Dark, blue](docs/screenshots/16-follow-phone-dark-baseline.png) | ![Dark, amber](docs/screenshots/17-follow-phone-dark-amber.png) | ![Light](docs/screenshots/18-follow-phone-light-baseline.png) |

| Voice | Check | Popup keys |
| --- | --- | --- |
| ![Voice panel](docs/screenshots/19-follow-phone-voice-panel.png) | ![Check panel](docs/screenshots/20-follow-phone-check-panel.png) | ![Popup keys](docs/screenshots/21-follow-phone-popup-keys.png) |

The two dark screenshots were taken one after the other with the keyboard open: the phone's palette changed from blue to
amber and the keyboard recoloured itself.

## How the look follows the phone

The default colour theme, "Clackatoo (follows the phone)", takes Android's Material 3 role colours:

| Part | Colour role |
| --- | --- |
| Keyboard background, suggestion strip | `surface` |
| Letter keys, space bar | `surfaceContainerHigh`, with a 1-pixel `onSurface` edge (12% dark, 16% light) |
| Function keys (shift, delete, symbols), popups | `surfaceContainerHighest` |
| Key labels, toolbar icons | `onSurface` |
| Hint letters, secondary text | `onSurfaceVariant` |
| Pressed or selected keys, the selected suggestion, Voice and Check buttons, the Enter key's edge | `primary` |

On Android 14 and later these are the system role colours; on Android 12 and 13 the nearest system tones; below
Android 12 a fixed fallback table. Labels keep at least 4.5:1 contrast against their keys; if an unusual system colour
would break that, black or white is used. Appearance > Style also offers HeliBoard's Material, Holo and Rounded styles,
and HeliBoard's colour themes and custom colours work in every style.

## Security notes

- **No user-supplied native library in any build.** HeliBoard can load a gesture-typing library into the keyboard
  process; Clackatoo turns that off everywhere (`BuildConfig.ALLOW_USER_LIBRARY`), so no outside code runs next to what
  you type. Releases are the `nouserlib` build. Swipe typing is therefore not available.
- **Models run isolated.** whisper.cpp and llama.cpp parse model files, so they run in an isolated process
  (`android:isolatedProcess="true"`): its own throw-away UID, no permissions, no access to the keyboard's files. Models
  arrive as read-only file descriptors, audio through a pipe, and everything that comes back is treated as untrusted.
- **The microphone** permission is asked the first time you use the Voice key. Audio stays in memory, and recording
  stops as soon as the voice panel is hidden. The Voice and Check keys never act on password fields.
- **Preinstalled as a system app** (in a custom ROM) it also types on the lock screen before the first unlock, while
  clipboard history, learned words and models stay in credential-protected storage.

## Build

Requires JDK 21, the Android SDK (platform 37, build-tools 37.0.0), NDK `28.0.13004108` and CMake `3.31.6`.

```sh
git submodule update --init --depth 1      # whisper.cpp and llama.cpp
./gradlew assembleDebugNoMinify            # debug APK, application id io.github.highlyproteus.clackatoo.debug
./gradlew assembleNouserlib                # the release build (unsigned)
./gradlew :app:testRunTestsUnitTest        # unit tests
```

Developer notes are in [AGENTS.md](AGENTS.md).

## Releasing

1. Once: run `tools/make-release-key` in a terminal. It creates the signing key on your computer and stores it as
   GitHub secrets. Back up the key file and password it shows; every update must be signed with that key.
2. For each release: raise `versionName` (and `versionCode` by one) in `app/build.gradle.kts`, commit, then tag and push:
   `git tag v1.0.1 && git push origin main v1.0.1`. The Release workflow builds, signs, checks and publishes the APK.

## Upstream

`main` is HeliBoard v4.2 plus Clackatoo's changes. To bring in a HeliBoard release:
`git fetch upstream --tags && git merge <tag>` (remote `upstream` is <https://github.com/HeliBorg/HeliBoard>).
HeliBoard's own README, contributing guide and AI policy are in [docs/](docs/HELIBOARD_README.md); HeliBoard does not
accept AI-written contributions, so nothing from this repository is sent upstream.

## Licence

GPL-3.0-only, like HeliBoard; see [LICENSE](LICENSE). Parts come from AOSP (Apache-2.0,
[LICENSE-Apache-2.0](LICENSE-Apache-2.0)) and other upstream sources; their notices are kept in the source files. The
bundled Inter font is under the SIL Open Font License 1.1 ([third_party/inter/OFL.txt](third_party/inter/OFL.txt)).
See [NOTICE](NOTICE).
