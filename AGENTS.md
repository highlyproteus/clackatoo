# Working on Clackatoo

Clackatoo is a standalone Android keyboard, a fork of HeliBoard v4.2 (<https://github.com/HeliBorg/HeliBoard>, remote
`upstream`). It is published at <https://github.com/highlyproteus/clackatoo> and installed with Obtainium from its
GitHub releases. Design: Material 3 shapes (keys and key-like buttons 8dp, cards and panels 12dp; stock buttons and
switches), see Style notes.

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
   never starts without the recording panel on screen (a panel that is shown but still waiting for its first layout
   may keep the microphone open for at most `FIRST_LAYOUT_MS`, 1 s, then the recording ends; Stop before it was ever seen
   drops the audio), and RECORD_AUDIO is requested on first use of the Voice key
   only. Never log text the user typed, said or that a model produced, in any build. Any change that touches the
   microphone, the debug WAV entry point, model import, the model process or text replacement needs a security review.
   **Catalog model files are pinned by size and SHA-256. Custom (experimental) model files are allowed only through two
   user-started paths, labelled "Experimental · not verified", and always run in the isolated process:** (1) "Import file" in
   Settings, (2) the companion's "Browse Hugging Face" screen, where the user types the search or repo id, which hands the
   file to the keyboard with the separate typed call `TX_CUSTOM_HANDOVER` (the keyboard still sends the companion only
   catalog ids). Either way the keyboard decides: the user must have accepted the experimental notice in Settings, the file
   is at most 4 GiB with enough free space, its format is found from its own header (`ModelFormats`, never from its name or
   the companion's say-so), it must fit the role, and one test load runs in the isolated process (`MSG_PROBE`) before it is
   kept; at most eight custom models, stored apart in `files/models/custom/<role>/` under sanitised names, used only when
   the user chose them. Custom text models are prompted with their own chat template (`llm_chat`): user text cannot inject special tokens, but a model whose template uses plain-text markers can still be prompt-injected by typed text, which is accepted for experimental models (output is sanitised and shown or limited as for every model). Any change to these
   paths needs a security review.
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
$ADB install -r app/build/outputs/apk/debugNoMinify/Clackatoo_*-debugNoMinify.apk
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
  behind a light tint (`GlassStyle.SCRIM_ALPHA_BLUR`; `SCRIM_ALPHA_FLAT`, heavier, where the phone does not blur;
  `WindowManager.isCrossWindowBlurEnabled` via `GlassWindowEffect`, blur radius on Android 12+). Text and icons never
  sit on the clear window: panels (`SettingsGroup`, `ModelCard`, ...) are frosted `surfaceContainerHigh`
  (`PANEL_ALPHA_*`, stronger in dark mode) with a hairline border, and the page title, section headers, hints and
  toolbar icons get their own small frosted backing (`frostedBacking`, `FrostedTitle`, `FrostedIcons`;
  `BACKING_ALPHA_*`). Dialogs (`ThreeButtonAlertDialog`) are frosted where the phone blurs, over the settings content,
  which `dialogBackdrop` blurs while one is open; solid otherwise. Never use `FLAG_BLUR_BEHIND` or another window blur
  on top of the settings window blur: it hung SurfaceFlinger's render engine on the emulator. With glass on the colour
  scheme is `glassColorScheme`: the accent, secondary text and error colours move towards `onSurface` as far as needed
  to keep 4.5:1 on every frosted surface (`GlassStyle.readable`). The switch "Glass background" (`GlassStyle.PREF_GLASS`,
  default on) falls back to opaque Material 3 surfaces. The alphas are fixed because text must keep 4.5:1 over any
  wallpaper, in light and dark mode, with and without blur; `GlassStyleTest` checks every text surface, so change the
  alphas only together with that test, and add a case for any new text surface. Text on the window itself is a bug in
  glass mode: put it in a panel or `frostedBacking`. Models are shown as `ModelCard`s (Voice typing, Grammar check).
  In glass mode the theme's `background` colour is transparent so Scaffolds do not paint over the wallpaper.
- Themes: the theme preferences (`theme_colors`, `theme_colors_night`) hold a name: HeliBoard's built-in names, `clackatoo`,
  `glass`, `glass_dark`, `glass_light`, `omarchy_<name>`, or the name of a user theme (a user theme always wins over a
  built-in one of the same name; new user themes cannot take built-in names). Every Clackatoo theme is a `ClackatooPalette`
  (`ClackatooStyle.paletteColors`), so one contrast guard covers them all. Omarchy themes come from
  `tools/omarchy-themes/generate.py` at a pinned commit (colours only, MIT, `third_party/omarchy/`); never hand-edit
  `OmarchyThemeData.kt`, change the generator or the mapping in `OmarchyThemes.kt`.
- Glass keyboard: text and icons never sit on clear glass. The window is transparent and the blur is a separate small
  window behind the visible keyboard (`GlassWindow`; `Window.setBackgroundBlurRadius` on the keyboard window itself
  blurs the whole screen, because that window is as tall as the screen). The clear parts (gaps between keys, edges) are
  a light tint (`GlassPalette.CLEAR_TINT_BLURRED`, about 26%; a heavier `CLEAR_TINT_PLAIN` where the phone cannot blur).
  Everything that carries a label has a frosted backing, `GlassPalette.BACKING_ALPHA` (78%) with a hairline: keys (their
  tiles; the emoji keys too), each button of the strip (every suggestion, toolbar key and emoji tab gets a chip of its
  own, so the wallpaper shows between them; per word looked better than one bar under the whole strip), the strip under
  the system's navigation icons, the voice and check panel's title and body, the clipboard's placeholder
  (`GlassBacking`, `Colors.backing`). Popups, key previews and more-suggestions stay solid.
  A new text surface needs a backing and a line in `ThemePalettesTest.assertGlassReadable`, which measures every label
  on its own backing over white, black, mid grey and the saturated colours (`ClackatooPalette.BACKDROPS`), in light and
  dark mode, with and without blur: a text surface without a test is a bug. The voice and check panel is clear glass and
  hides the keys under it (alpha 0, not GONE: the blur and the touch region follow the keys being shown).
- Key labels use Inter (`app/src/main/assets/fonts/`, Regular and Medium; SIL OFL, licence in
  `third_party/inter/`). Medium replaces bold (`KeyboardTypeface.resolve`). A user-selected custom font still wins.

## Models and the companion app

- `catalog/` is the one list of downloadable models, shared by the keyboard and the companion app `models`
  ("Clackatoo Models", application id `io.github.highlyproteus.clackatoo.models`). `ModelsProtocol` in it holds the few
  names the two apps use to talk. Offer only small models (the owner's choice): the larger options (Parakeet v2,
  Nemotron 3.5, Whisper large v3 turbo, Qwen3.5 2B) were measured and removed, see ml/README.md.
- **The companion is untrusted by the keyboard and the keyboard by the companion.** The keyboard binds the companion's
  `DownloadService` and makes one Binder call (an action and a catalog id, nothing else); `DownloadBinder` checks the
  caller's package and signature, `RequestParser` refuses a URL, a size or a hash, an unknown id and leftover data, and
  `RequestLimiter` ignores a model already queued or running, enforces a 30 s cool-down after a cancel or failure and at
  most 20 new downloads an hour. Start intents carry nothing the service reads. Downloads send the fixed User-Agent
  `ClackatooModels`, resume only within one run, and a run that does not succeed deletes its partial file. The companion downloads only the catalog's pinned URL (`DownloadPolicy`: https,
  `huggingface.co` and `*.hf.co` only, also for redirects), checks exact size and SHA-256, and hands the file over as a
  read-only descriptor to the keyboard's `ModelReceiverService`, which refuses anything but the documented calls and
  installs through `ModelStore.import` (size, header, SHA-256 again). Then the companion deletes its copy. Progress is
  numbers only; failure texts are written by the keyboard.
- **Both directions need the signature permission** `io.github.highlyproteus.clackatoo.permission.MODELS` (debug builds:
  `...clackatoo.debug.permission.MODELS`, because a debug and a release app are signed with different keys and one name
  with two signatures would fail the install), declared by both apps. The keyboard also checks the companion's signature
  before it talks to it, and the receiver checks the caller's package and signature on every call. Any change to this
  link needs a security review.
- **Custom models** (rule 5): `ModelStore.importCustom` is the single entry for them (file import and the companion's
  `TX_CUSTOM_HANDOVER` both end there), `CustomPolicy`/`CustomNames`/`ModelFormats` hold the cap, the name sanitising and the
  header detection (unit tests pin all three), custom ids are `custom:<role>:<name>`, and the keyboard's confirmation
  (`AssistSettings.PREF_CUSTOM_MODELS_CONFIRMED`) gates the hand-over. The companion's Hugging Face screen
  (`BrowseActivity`, not exported) talks to `huggingface.co` and `*.hf.co` only (`DownloadPolicy`), pins the commit the API
  returned, and its custom downloads come from a process-internal queue, never from an Intent or a Binder call. The companion's
  permission list does not change.
- **Suggested models:** `DeviceProfile` + `ModelSuggestions` pick one catalog model per role from the phone's RAM, dot-product
  support, cores and ABI, using the memory measured per model (table in ml/README.md "Which model for which phone"). Add a
  catalog model and you add its measured peak there and in `ModelSuggestions.PEAK_MB` (a test checks every model has one).
- `ModelDownloads` / `ModelState` / `ModelChoice` (package `helium314.keyboard.clackatoo.models`) are the keyboard-side
  API that the settings screens and the panel use. A completed download becomes the chosen model of its role.
- The release workflow builds both APKs with `assembleNouserlib`; `versionName` of `models/build.gradle.kts` must equal the
  keyboard's.
