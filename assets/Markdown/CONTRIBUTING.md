# Contributing to QBSmarter

Thanks for your interest! This document explains how to contribute code,
translations, bug reports, and ideas to QBSmarter.

> **The canonical home of this project is
> [codeberg.org/zucham/QBSmarter](https://codeberg.org/zucham/QBSmarter).**
> The GitHub repository is a one-way mirror. Issues and pull requests filed
> on GitHub are not monitored. Please use Codeberg for everything.

---

## Table of contents

1. [Where to start](#where-to-start)
2. [Reporting bugs](#reporting-bugs)
3. [Suggesting features](#suggesting-features)
4. [Helping with translations](#helping-with-translations)
5. [Submitting code changes](#submitting-code-changes)
6. [Coding style](#coding-style)
7. [AI-assisted contributions](#ai-assisted-contributions)
8. [Local signing setup](#local-signing-setup)
9. [Licensing of contributions](#licensing-of-contributions)

---

## Where to start

Before contributing code, please skim
[`assets/Markdown/PROJECT.md`](PROJECT.md). It is the architectural document
for the project and explains the layering (BLE &rarr; transport &rarr; driver &rarr;
domain &rarr; UI), the persistence model, the i18n setup, the GAN protocol notes,
and the conventions used everywhere in the codebase.

You don't need to memorise it; just know where to look. New contributors
typically read it once cover to cover, then refer back to specific sections
when working on a particular layer.

---

## Reporting bugs

The [Codeberg issue tracker](https://codeberg.org/zucham/QBSmarter/issues) is
the canonical place. The [FAQ](FAQ.md#what-information-should-i-include-in-a-bug-report)
describes what to include in a useful bug report.

If you can't or won't sign up at Codeberg, email `zucham@duck.com` with a
subject line containing the word **`QBSmarter`**.

---

## Suggesting features

Same channels as bug reports. The README has a roadmap of currently planned
features &ndash; suggestions outside that list are welcome and will be considered
on their merits, but there is no commitment to implement them.

When suggesting a feature, please describe:

1. The problem you're trying to solve (not the solution).
2. What you've tried so far, if anything.
3. Why this would be valuable to other users, not just you.

This helps avoid the most common failure mode of feature requests, which is
solving the wrong problem.

---

## Helping with translations

Translations are the easiest and most valuable kind of contribution if your
strongest skill is being a fluent speaker of a language other than English or
Czech. The walkthrough is in the [FAQ](FAQ.md#how-do-i-help-translate-the-app-to-another-language).

The short version: copy the English `strings.xml` and `usage_guide_en.md`,
translate them, send them in via PR or email. The Kotlin glue around them is
easy and the maintainer can do that part if you'd rather not.

---

## Submitting code changes

1. Open a Codeberg account if you don't have one.
2. Fork the repository at [codeberg.org/zucham/QBSmarter](https://codeberg.org/zucham/QBSmarter).
3. Clone your fork and create a topic branch:

   ```sh
   git checkout -b fix/the-thing-that-broke
   ```

4. Make your changes. Keep them focused &ndash; one concern per PR is much easier
   to review than five concerns bundled together.
5. Build the app locally and verify it runs on a real Android device (the
   emulator is not part of the development workflow):

   ```sh
   ./gradlew :androidApp:assembleDebug
   adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

6. Commit with a clear message describing what changed and why. Multi-line
   commit messages are welcome for non-trivial changes.
7. Push your branch to your fork and open a pull request against the `main`
   branch on Codeberg.
8. Be patient. All PRs are manually reviewed by the maintainer before merge.
   Reviews can take a few days; pinging once a week is fine.

### What gets accepted

- Bug fixes with a clear reproduction case, ideally referencing an open
  issue.
- New features that have been discussed in an issue first and got a positive
  signal from the maintainer.
- Translations.
- Documentation improvements.

### What may not be accepted

- Sweeping rewrites of working code.
- New dependencies without strong justification (the dependency surface is
  intentionally lean &ndash; see the dependency table in the README).
- Code that breaks support for the current minimum Android version (API 29).
- Changes that would make the app phone home, collect analytics, or include
  any third-party service.

---

## Coding style

Follow the conventions already established in the codebase. The
[`Conventions & gotchas` section of PROJECT.md](PROJECT.md#conventions--gotchas)
lists the important ones. Highlights:

- Use Kermit (`co.touchlab.kermit.Logger`) for logging, except in
  `BleManager.android.kt` where `android.util.Log` is acceptable for the
  reasons documented there.
- Tag every logger: `private val log = Logger.withTag("ClassName")`.
- Default to `.d` for normal flow tracing, `.w` for recoverable problems,
  `.e` for failures. R8 strips `.d` / `.v` / `.i` calls in release builds, so
  liberal debug logging is free in production.
- ViewModels never capture a `userId` at construction. Reads use flows keyed
  off `activeProfile.id`; writes use `activeProfile.idSnapshot()` at call
  time.
- Single source of truth for cube state is `RubiksCube._state`. Don't
  introduce parallel "visual state" fields.
- External boundaries (BLE callbacks, SAF result handlers, intent launches)
  are wrapped in `runCatching`.

If you're not sure whether something fits the style, look at how a similar
case is handled elsewhere in the codebase, or ask in the PR.

---

## AI-assisted contributions

AI tooling is fine. The maintainer uses it. The rule is the same one that
applies to the existing codebase: **anything you submit, you have personally
read, understood, and can defend in code review.** Pull requests where the
contributor can't explain their own diff will be closed.

If you used AI to draft non-trivial portions of a contribution, mention it in
the PR description. This isn't gatekeeping &ndash; it's calibration. AI-generated
code occasionally has subtle issues (e.g. mishandled edge cases, fabricated
APIs) that benefit from extra scrutiny in review.

---

## Local signing setup

Most contributors will never need this. Debug builds work without any
keystore at all. You only need to set up signing if you want to produce a
release-mode APK locally.

If you do want to:

1. Generate a keystore (one-time setup):

   ```sh
   keytool -genkeypair -v \
     -keystore ~/keys/qbsmarter-dev.jks \
     -alias qbsmarter \
     -keyalg RSA -keysize 4096 \
     -validity 10000
   ```

   Pick any password and answer the distinguished-name prompts. The keystore
   you generate is **yours, for your local builds only** &ndash; it is not the
   release keystore the project ships APKs with.

2. Export the four environment variables before running Gradle:

   ```sh
   export QBS_KEYSTORE_PATH=$HOME/keys/qbsmarter-dev.jks
   export QBS_KEYSTORE_PASSWORD='your-password'
   export QBS_KEY_ALIAS=qbsmarter
   export QBS_KEY_PASSWORD='your-password'
   ./gradlew :androidApp:assembleRelease
   ```

3. The signed release APK lands in `androidApp/build/outputs/apk/release/`.

If any of the four env vars are missing, the release build still succeeds
but the APK is unsigned. That's intentional &ndash; it means a fresh clone of the
repo always builds out of the box.

The keystore file itself is gitignored. Don't commit it.

---

## Licensing of contributions

QBSmarter is licensed under the
[GNU Affero General Public License version 3](../../LICENSE) (AGPL-3.0). By
submitting a contribution, you agree that it will be licensed under the same
terms.

If you've copied code from another project, that other project's licence
must be compatible with the AGPL. The
[FSF compatibility matrix](https://www.gnu.org/licenses/license-list.en.html)
is a good reference. When in doubt, mention the source in the PR description
and we'll check together.
