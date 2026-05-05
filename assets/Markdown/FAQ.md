# Frequently asked questions

Common questions about installing, using, and contributing to QBSmarter.

If your question isn't answered here, open an issue on the
[Codeberg issue tracker](https://codeberg.org/zucham/QBSmarter/issues), or
write to `zucham@duck.com` with a subject line containing the word
**`QBSmarter`**.

---

## General

### How do you pronounce "QBSmarter"?

"Cube smarter". That's it.

### Which smart cubes are supported?

Any GAN smart cube that speaks the GAN Gen2, Gen3, or Gen4 BLE protocol. The
cube the project was developed against is the **GAN 356 i Carry** (Gen2). Other
GAN models speaking the same protocol generations should also work, although
they have not all been individually tested. Non-GAN cubes (MoYu, QiYi, Gocube,
Cubicle Connected, etc.) are not supported in v1.0.0.

Adding new vendors is feasible, since the architecture has a generic
`SmartCubeDriver` interface; see
[PROJECT.md](PROJECT.md#smart-cube-driver-layer) for the integration
points.

### Which Android versions are supported?

Android 10 (API level 29) and newer. Older versions are not supported because
the 3D rendering library (korender) requires API 29 as its floor.

The app uses the modern `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` runtime
permissions on Android 12+. On Android 10 and 11 it falls back to
`ACCESS_FINE_LOCATION`, which the OS requires for BLE scanning on those
versions. Either way, the app does not access location data &ndash; the permission
is only there because the OS bundles BLE scanning under the location umbrella
on those older versions.

### Is the app on the Play Store?

Not in v1.0.0. The recommended way to get it is to download the signed APK
from the [GitHub Releases page](https://github.com/zucham/QBSmarter/releases) and
sideload it. 

GitHub is the binary distribution channel; Codeberg is the canonical home for
source code, issues, and pull requests. The split exists because Codeberg's
hosted CI is too resource-limited to build a Compose Multiplatform Android
APK within its time budget, while GitHub Actions handles it in a few minutes.

In the near future, QBSmarter will get published to F-Droid and to the Play Store after that.

### Will there ever be an iOS version?

It's not impossible, but not anytime soon, mainly because of missing equipment and
library support. See the [project documentation](PROJECT.md#why-no-iosmain) for technical details.

---

## Installation and updates

### How do I sideload the APK?

1. Download the APK file from the latest release on
   [GitHub](https://github.com/zucham/QBSmarter/releases).
2. On your Android device, open Settings &rarr; Apps &rarr; Special access &rarr;
   Install unknown apps. Grant permission to whichever app you'll use to open
   the APK (your file manager, Drive, etc.).
3. Open the downloaded APK file. Confirm the install when prompted.
4. The app will appear in your launcher.

### How do I update?

Download the new APK from the Releases page and install it the same way. As
long as the same release keystore was used (which is the case for every
release the project ships), Android will recognise it as an upgrade and your
solve history will be preserved.

If you ever see a "package conflict" error during install, that means the new
APK was signed with a different key than the one currently installed. The
solution is to uninstall the old version first, but you will lose your local
solve history (back it up first via Settings &rarr; per-profile export).

### Is the APK safe to install?

The APK published on the
[GitHub Releases page](https://github.com/zucham/QBSmarter/releases) is built
by GitHub Actions from the public source code mirrored from
[Codeberg](https://codeberg.org/zucham/QBSmarter). You can verify any release
by:

- Checking out the matching tag from Codeberg (`git checkout v1.0.0`).
- Running `./gradlew :androidApp:assembleRelease` locally.
- Comparing the resulting APK signature.

The release keystore is private (it has to be, by design) but the build inputs
are fully public. There is no obfuscated payload, no analytics, no telemetry,
and no network access except to your smart cube over BLE.

---

## Bugs and feature requests

### Where do I report bugs?

The [Codeberg issue tracker](https://codeberg.org/zucham/QBSmarter/issues) is
the canonical place. Email (`zucham@duck.com` with `QBSmarter` in the subject)
also works if you can't or won't sign up at Codeberg.

The GitHub mirror has issues disabled deliberately. Anything filed there
won't be seen.

### What information should I include in a bug report?

In rough order of importance:

1. Android version and phone model.
2. The smart cube model (GAN356 i Carry, GAN356 i 3, GAN 12 ui FreePlay, etc.) and
   approximate firmware version (visible in the cube's Info dialog inside the
   app).
3. What you did, what you expected, what happened.
4. The active profile's user ID, found at the bottom of the Settings screen.
   This helps when the bug is data-shape-specific or when you include your profile backup.
5. If a crash happens, ideally the logcat output around the crash. `adb logcat -d
   --pid=$(adb shell pidof com.zucham.qbsmarter)` is a one-liner that gets it.

You don't need all five for a useful report. Item 3 alone is fine for many
bugs.

### Can I request a feature?

Yes &ndash; via the issue tracker or via email. The current planned features are
listed in the README; reasonable additions outside that list are welcome.
There is no SLA on feature requests; this is a hobby project.

---

## Localisation

### How do I help translate the app to another language?

The translation workflow is intentionally simple. The full step-by-step guide
is in [PROJECT.md](PROJECT.md#adding-a-new-language), but as a
high-level summary:

1. The app's strings live in
   [`shared/src/commonMain/composeResources/values/strings.xml`](../../shared/src/commonMain/composeResources/values/strings.xml)
   (the English source) and `values-cs/strings.xml` (the Czech translation).
2. To add a new language, copy the English file to a new
   `values-<tag>/strings.xml` directory, where `<tag>` is the
   [BCP 47 language tag](https://en.wikipedia.org/wiki/IETF_language_tag) for
   the target language (e.g. `de` for German, `fr` for French, `es-419` for
   Latin American Spanish).
3. Translate every string. The English keys must stay; only the values
   change.
4. The in-app usage guide also has localised Markdown files under
   [`shared/src/commonMain/composeResources/files/usage_guides/`](../../shared/src/commonMain/composeResources/files/usage_guides/).
   Translate the English version (`usage_guide_en.md`) to your target language
   and save it as `usage_guide_<tag>.md`.
5. Add an entry to `AppLanguage` (in
   [`shared/src/commonMain/kotlin/com/zucham/qbsmarter/ui/i18n/`](../../shared/src/commonMain/kotlin/com/zucham/qbsmarter/ui/i18n/))
   so the language picker offers it.
6. Submit a pull request on Codeberg.

If steps 1&ndash;5 sound intimidating, just translate the strings file and the
usage guide, send them to `zucham@duck.com`, and I'll wire them up. The Kotlin
side is easy; the part that needs a native or fluent speaker are the
translations themselves. All help with localisation is extra helpful to me, 
as I don't speak too many languages.

### Will my translation always work?

The English source is the canonical version. Translations are best-effort.
When new features are added, new strings appear; until a translator updates
the localised file, those new strings fall back to English. If you would like
to be pinged when new strings need translation, mention that in your PR or
email.

---

## Privacy and data

### Does the app send my data anywhere?

No. There is no analytics, no crash reporting, no remote configuration, no
phone-home of any kind. The only network communication is BLE, between your
phone and your cube. Solve history is stored in a local SQLite database on
your device.

### How do I back up my solve history?

In Settings, open the per-profile settings dialog (gear icon next to your
profile name) and use **Export profile**. You'll get a JSON file containing
all of that profile's solves and settings. Save it wherever you back up your
phone. To restore, use **Import** on the Settings screen.

The whole-database export is also available via the Import / Export buttons
in the main Settings section &ndash; that one bundles every profile.

---

## Why AGPL?

Because there are plans to add a server layer in a future version, and
the AGPL extends copyleft to network use. If someone forks the project, runs
a closed-source modified version of the server, and lets the public connect
to it, the AGPL requires them to share the source of those modifications
with their users. Plain GPL doesn't cover the network case.

For the Android client itself, in isolation, the AGPL behaves essentially the
same as the GPL.

If you are unsure whether your intended use is compatible with the AGPL, see
the [GNU AGPL FAQ](https://www.gnu.org/licenses/gpl-faq.html) or open an issue.
