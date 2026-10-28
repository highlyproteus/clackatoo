# Working on Clackatoo

Clackatoo is a standalone Android keyboard, a fork of HeliBoard v4.2 (<https://github.com/HeliBorg/HeliBoard>, remote
`upstream`). It is published at <https://github.com/highlyproteus/clackatoo> and installed with Obtainium from its
GitHub releases. Design: Material 3 default shapes (see Style notes), not custom square or pill shapes.

## Rules

1. **GPL-3.0-only.** Keep LICENSE and every upstream copyright header. New files get
   `SPDX-License-Identifier: GPL-3.0-only`. The one exception is the swipe (gesture) decoder in
   `app/src/main/jni/src/suggest/policyimpl/gesture/`: its files are Apache-2.0 (`Copyright (C) 2026 The Clackatoo
   Authors`, `SPDX-License-Identifier: Apache-2.0`) so that other AOSP-derived keyboards can reuse them. Keep the
   modifications notice in README.md and NOTICE accurate (GPL-3.0 section 5a): add a dated line to NOTICE for every
   user-visible modification.
2. **Never add the `INTERNET` permission to the keyboard**, or any network-capable component, library or dependency (the
   llama.cpp build in `ml/` has its HTTP client, server and tools switched off; keep it that way). The release workflow
   fails if the keyboard APK asks for INTERNET; check locally with `aapt2 dump permissions <apk>`. Downloads are the
   job of the companion app `models` ("Clackatoo Models"), which is the only module allowed INTERNET and whose
   permission list is fixed exactly (INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS and
   the signature permission below; the workflow fails on anything else). Adding or widening any permission, in either
   app, needs a security review.
3. **Keep upstream mergeable.** Prefer additive changes (new files, new style entries) to rewriting upstream code. The
   Clackatoo look is a dedicated style ("Clackatoo", `KeyboardTheme.STYLE_CLACKATOO`), not edits to the
   Material/Holo/Rounded styles. The Kotlin package stays `helium314.keyboard` and the Gradle namespace stays
   `helium314.keyboard.latin`; only the `applicationId` is Clackatoo's. Clackatoo-only classes live in
   `helium314.keyboard.clackatoo`.
4. Upstream's AI policy (`docs/HELIBOARD_AI_USAGE.md`) forbids AI-generated upstream PRs and issues. Nothing here goes
   upstream unless a human writes the PR and says AI was used.
5. Offline voice typing and grammar check live in `helium314.keyboard.clackatoo.assist` and the `ml` and `asr` modules
   (read `ml/README.md`; `asr` is Parakeet with its own static ggml). Everything stays on the phone: models run in the
   separate `:ml` service, which is an **isolated process** (`android:isolatedProcess="true"`, own UID, no access to
   the app's files): models arrive as file descriptors, never as paths, and replies from it are untrusted (numbers
   only, text through a size-limited pipe, sanitised by `OutputSanitizer`). Model files are pinned by size and SHA-256
   in `ModelCatalog`; changing a model means changing both there and in NOTICE. Audio is only in memory, recording
   never starts without the recording panel on screen, and RECORD_AUDIO is requested on first use of the Voice key
   only. Never log text the user typed, said or that a model produced, in any build. Any change that touches the
   microphone, the debug WAV entry point, model import, the model process or text replacement needs a security review.
   Models are listed in the `catalog` module (`Catalog.kt`: id, file, size, SHA-256, Hugging Face repository and a
   40-hex revision; `CatalogTest` pins the shape) and `ModelCatalog` is built on it; add a model there and in NOTICE.
6. **No user-supplied native library in any build type.** `BuildConfig.ALLOW_USER_LIBRARY` is false for every build
   type, and `UserLibraryPolicy` (checked in `JniUtils` and the advanced settings screen) keeps HeliBoard's "load
   gesture typing library" closed: such a library would run inside the keyboard process and void the isolation
   above. Releases are `assembleNouserlib`. Never set the flag to true. Swipe typing does not need one: the built-in
   `jni_latinime` decodes gestures (`policyimpl/gesture/`), and `JniUtils` loads nothing else.
7. **Versions.** `versionName` is Clackatoo's own (1.0.0, ...) and must equal the release tag without "v".
   `versionCode` stays in HeliBoard's numbering (`AppUpgrade` migrates settings by comparing it with upstream codes):
   one more per release, above the merged upstream release's code and below upstream's next release.
8. **Signing.** Releases are signed only by the Release workflow, with the key `tools/make-release-key` put in the
   repository secrets. Never create, read, copy or replace the release key; the owner runs that script.
9. Non-goals until a task says otherwise: GIFs, emoji redesign.

## Build, install, test

Machine setup: JDK 21, Android SDK with platform 37 and build-tools 37.0.0, NDK `28.0.13004108`, CMake `3.31.6`.

```sh
G="./gradlew -Dorg.gradle.jvmargs=-Xmx3g --max-workers=4"
$G assembleDebugNoMinify                    # fast debug APK (assembleDebug: the minified one)
$G :app:testRunTestsUnitTest               # the full unit suite, as CI runs it: offline, skips tests known to fail
$G :app:testDebugUnitTest --tests 'helium314.keyboard.clackatoo.*'   # quick: tests of Clackatoo code
git submodule update --init --depth 1       # whisper.cpp, llama.cpp and our parakeet.cpp fork, needed to build the APK (not for unit tests)
git -C asr/native/parakeet.cpp submodule update --init --depth 1 third_party/ggml   # parakeet's ggml (its other submodules are not used)
# XLinkTest checks links against live websites and runs only with -PlinkChecks. Two of its tests fail on
# clean v4.2 too: layoutsLinks (a dead link in layouts.md) and readmeLinks.
```

Install and select on an emulator:

```sh
ADB="adb -s emulator-5554"
ID=io.github.highlyproteus.clackatoo.debug/helium314.keyboard.latin.LatinIME
$ADB install -r app/build/outputs/apk/debugNoMinify/Clackatoo_1.0.0-debugNoMinify.apk
$ADB shell ime enable $ID && $ADB shell ime set $ID
$ADB shell settings put secure show_ime_with_hard_keyboard 1    # restore to 0 when finished
```

Test by tapping keys (`$ADB shell input tap X Y`, key centres from a screenshot); `input text` bypasses the
IME and proves nothing. Read the field's text with `uiautomator dump`. Put UI screenshots in `docs/screenshots/`.
Debug builds change the label to "Clackatoo debug".

## Swipe typing

The native dictionary engine (`app/src/main/jni`) already captured and sampled strokes; Clackatoo adds the decoder
policy in `app/src/main/jni/src/suggest/policyimpl/gesture/` (Apache-2.0). Its constants are `GestureParams`. To change
it, build the host harness and run it on the HeliBoard Gesture Data Project (downloaded, never committed; needs `zstd`):

```sh
cmake -S tools/gesture-eval -B build/gesture-eval -DCMAKE_BUILD_TYPE=Release && cmake --build build/gesture-eval -j
build/gesture-eval/gesture_eval --dict app/src/main/assets/dicts/main_en-US.dict --selftest   # also run by CI
python3 tools/gesture-eval/run_eval.py --binary build/gesture-eval/gesture_eval \
    --dict app/src/main/assets/dicts/main_en-US.dict --split dev --jobs 4 --param LANGUAGE_WEIGHT=8
```

Tune on `--split dev`; run `--split test` once per change you keep. `run_eval.py` prints top-1/top-3 next to Google's
decoder on the same strokes and the decoding time.

## Style notes

- Default colours follow the phone: `ClackatooPalette` takes the Material 3 roles from `android.R.color.system_*`
  (Android 14+), the nearest tones (12-13) or the fallback table, in the phone's dark theme (not the keyboard's
  day/night setting). Mapping: background `surface`, letter keys `surfaceContainerHigh` with a 1-pixel hairline,
  function keys and popups `surfaceContainerHighest`, labels `onSurface`, hints `onSurfaceVariant`, `primary` only for
  pressed/selected/active things. `ClackatooStyle.colors` builds a `DefaultColors` whose `changeCheck` makes
  `KeyboardSwitcher` rebuild the views when the system colours change. Never hard-code colours in Clackatoo code: take
  them from `Settings.getValues().mColors`. Labels must stay at 4.5:1 (`ClackatooPaletteTest`).
- Shapes: Material 3 defaults. Settings panels and cards 12dp (`MaterialTheme.shapes.medium`), small elements 8dp,
  dialogs 28dp, buttons and switches stock (pill); keys 8dp in the Clackatoo style and the new themes (the earlier
  "square, 6dp/4dp, no pills" rule is replaced). Check suggestion strip, toolbar, popups, toast and the Voice/Check
  panel when adding UI.
- Settings UI: every settings screen is built from the shared components in `helium314.keyboard.clackatoo.ui`
  (`SettingsGroup` panels, `SettingsRow` rows with a state subtitle, `SettingsSectionHeader`, `SettingsHint`; the
  HeliBoard `Preference`/`PreferenceCategory`/`SwitchPreference`, `SearchSettingsScreen` and `SearchScreen` delegate to
  them). Screens give a list of settings keys to `SearchSettingsScreen`; an `Int` entry starts a new group with that
  title. Do not hand-roll rows, cards or app bars in a screen. Dialogs use `ThreeButtonAlertDialog` or M3's
  `AlertDialog` (Material 3 default look); never a custom surface.
- Glass settings: the settings activity is translucent (`platformActivityTheme.Settings`) and shows the wallpaper
  behind a tinted scrim (`surface` at `GlassStyle.SCRIM_ALPHA`), blurred as well on Android 12+ when
  `WindowManager.isCrossWindowBlurEnabled` (`GlassWindowEffect`); panels are translucent `surfaceContainerHigh` with a
  hairline border. The switch "Glass background" (`GlassStyle.PREF_GLASS`, default on) falls back to opaque Material 3
  surfaces. The alphas are fixed because text must keep 4.5:1 over any wallpaper, in light and dark mode;
  `GlassStyleTest` checks it, so change the alphas only together with that test. Text directly on the window uses
  `onSurface`; `onSurfaceVariant` only inside panels. In glass mode the theme's `background` colour is transparent so
  Scaffolds do not paint over the wallpaper.
- Key labels use Inter (`app/src/main/assets/fonts/`, Regular and Medium; SIL OFL, licence in
  `third_party/inter/`). Medium replaces bold (`KeyboardTypeface.resolve`). A user-selected custom font still wins.

## Models and the companion app

- `catalog/` is the one list of downloadable models, shared by the keyboard and the companion app `models`
  ("Clackatoo Models", application id `io.github.highlyproteus.clackatoo.models`). `ModelsProtocol` in it holds the few
  names the two apps use to talk.
- **The companion is untrusted by the keyboard and the keyboard by the companion.** The keyboard sends one Intent extra,
  `id` (a catalog id), to the companion's `DownloadService`; `RequestParser` refuses any other extra, a data URI, a URL, a
  size or a hash, and an unknown id. The companion downloads only the catalog's pinned URL (`DownloadPolicy`: https,
  `huggingface.co` and `*.hf.co` only, also for redirects), checks exact size and SHA-256, and hands the file over as a
  read-only descriptor to the keyboard's `ModelReceiverService`, which refuses anything but the documented calls and
  installs through `ModelStore.import` (size, header, SHA-256 again). Then the companion deletes its copy. Progress is
  numbers only; failure texts are written by the keyboard.
- **Both directions need the signature permission** `io.github.highlyproteus.clackatoo.permission.MODELS` (debug builds:
  `...clackatoo.debug.permission.MODELS`, because a debug and a release app are signed with different keys and one name
  with two signatures would fail the install), declared by both apps. The keyboard also checks the companion's signature
  before it talks to it, and the receiver checks the caller's package and signature on every call. Any change to this
  link needs a security review.
- `ModelDownloads` / `ModelState` / `ModelChoice` (package `helium314.keyboard.clackatoo.models`) are the keyboard-side
  API that the settings screens and the panel use. A completed download becomes the chosen model of its role.
- The release workflow builds both APKs with `assembleNouserlib`; `versionName` of `models/build.gradle.kts` must equal the
  keyboard's.
