<!-- SPDX-License-Identifier: GPL-3.0-only -->

# Contributing to Clackatoo

You're welcome to open pull requests. Every PR gets read and considered: bug fixes, new features, themes, layouts,
translations, docs, tests. If you aren't sure whether something fits, open an issue first and ask.

## We are agent native

Clackatoo is built with coding agents, and we don't look down on PRs that were written, or partly written, by an
agent. Use Claude, Codex, Copilot, Cursor or whatever you like; agent-written and hand-written PRs are judged by the
same standard. You don't have to hide it, and you don't have to apologise for it.

What we ask in return is the same as for any PR:

- **Stand behind it.** You opened it, so you are the one who answers questions about it. Read the diff before you
  submit.
- **Say what you checked.** List the tests you ran and what you tried on a phone or emulator (screenshots help for UI
  changes). "The agent says it works" is not a check; the build and the tests are.
- **Keep it focused.** One change per PR, without unrelated reformatting or drive-by rewrites.

Point your agent at [AGENTS.md](AGENTS.md) first. It holds the project's rules, the build and test commands, how to
install on an emulator, and the style notes.

### Not upstream

Clackatoo is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard), and HeliBoard's own policy
([docs/HELIBOARD_AI_USAGE.md](docs/HELIBOARD_AI_USAGE.md)) does not accept AI-generated pull requests or issues. Send
agent-written changes here, not to HeliBoard. Anything that goes upstream must be written by a human and follow
HeliBoard's [contribution guide](docs/HELIBOARD_CONTRIBUTING.md).

## House rules

The full list is in [AGENTS.md](AGENTS.md). The ones that most often matter for a PR:

- **No network in the keyboard.** The keyboard never gets the `INTERNET` permission or any network-capable
  dependency. Only the companion app (`models/`) downloads, and its permission list is fixed.
- **Privacy-sensitive areas need extra care.** Changes to the microphone and recording, model import, the isolated
  model process, text replacement or the link between the two apps get a security review before merging. Say in the
  PR if yours touches one of them.
- **Licence.** Contributions are GPL-3.0-only. New files start with `SPDX-License-Identifier: GPL-3.0-only`, except
  in the swipe decoder (`app/src/main/jni/src/suggest/policyimpl/gesture/`), which is Apache-2.0. Keep every
  upstream copyright header.
- **NOTICE.** Add a dated line to [NOTICE](NOTICE) for every change a user can see.
- **Stay mergeable with upstream.** Prefer new files and additive changes to rewriting HeliBoard code; Clackatoo-only
  classes live in `helium314.keyboard.clackatoo`.

## Before you open the PR

```sh
G="./gradlew -Dorg.gradle.jvmargs=-Xmx3g --max-workers=4"
$G :app:testRunTestsUnitTest :catalog:testDebugUnitTest :models:testDebugUnitTest   # the unit tests, as CI runs them
$G assembleDebugNoMinify         # a debug APK to try on a phone or emulator
```

Building the APK needs the submodules and the SDK/NDK versions listed in [AGENTS.md](AGENTS.md#build-install-test).
CI runs the unit tests on every pull request.
