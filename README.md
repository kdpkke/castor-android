# Castor Android

---

## ☕ Like this project?

This project is developed and maintained in my spare time. If it has saved you time or helped you build something cool, please consider buying me a coffee to say thanks!

Your support keeps me caffeinated and motivated to work on new features and bug fixes.

<a href="https://paypal.me/matteomuratore1">
  <img src="https://img.shields.io/badge/PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white" alt="Donate with PayPal" />
</a>

---

A community Android client for [Castor](https://github.com/stupside/castor), maintained independently by [kdpkke](https://github.com/kdpkke). Cast a video link from your phone to a TV on the same Wi-Fi. The phone runs the Go media engine itself; no desktop server, root or Termux is required.

[Download APKs](https://github.com/kdpkke/castor-android/releases) · [Report an Android issue](https://github.com/kdpkke/castor-android/issues) · [Android build guide](android/README.md) · [Core updates](android/docs/UPSTREAM.md)

<img src="android/docs/images/home-dark.png" width="280" alt="Castor Android home" />
<img src="android/docs/images/appearance-dark.png" width="280" alt="System, Light and Dark appearance" />

Native Android emulator previews with sample input.

## Features

- Paste a link or share it from another app; choose Automatic, Web page or Direct stream.
- Discover Chromecast/DLNA receivers, reuse a saved TV or enter an address manually.
- Keep casting in the background through an Android foreground service.
- Cast the videos you watch in Firefox on the phone with the [Castor extension for Firefox](https://github.com/kdpkke/castor-extension), after allowing it once from a notification.
- Native quality, optional height limits and subtitle preferences.
- English and Italian; System, Light and Dark appearance, with Dark as the default.
- Settings remain open while choices update immediately; diagnostic details are selectable and copyable.
- Live-stream delivery follows future bytes; captured HLS access can renew through the original page when tokens expire.

Android 9+; arm64-v8a phones and x86_64 emulators. Previous casting builds were manually tested on **OPPO Find X8 Pro / ColorOS 16 → LG C2 in Chromecast mode**; see current validation coverage below. Roku remains experimental and requires upstream's developer-channel setup.

## Install and use

Download an APK from this repository's Releases, connect the phone and TV to the same Wi-Fi, then paste/share a URL, choose the TV and tap **Cast video**. Use Web page for an HTML player, Direct stream for an actual media URL. Android 17 requires local-network permission. See the [usage and compatibility guide](android/README.md) for details.

Release notes include SHA-256 checksums and the signing certificate fingerprint so you can verify your download. CI debug APKs are temporary validation builds and use a different signer. No signing keys are committed or uploaded by the updater.

Stable releases use a dedicated signing key; see [signature verification](android/docs/SIGNING.md) and its public certificate. Moving from a development build requires uninstalling it once before installing the stable APK, which removes saved app preferences. Later stable releases update normally.

Android versions follow their own sequence, starting at **1.0.0**, with tags such as `android-v1.0.0`. Changes are listed in [android/CHANGELOG.md](android/CHANGELOG.md). The upstream core version is tracked separately in [android/upstream.json](android/upstream.json).

## Build

```sh
git clone --recurse-submodules https://github.com/kdpkke/castor-android.git
cd castor-android
```

On Windows with Android Studio, PowerShell 7 and WSL Ubuntu:

```powershell
.\android\scripts\build.ps1
```

On Linux x86-64, install the build prerequisites listed in [android/README.md](android/README.md), then:

```sh
bash android/scripts/build-native.sh arm64-v8a x86_64
cd android
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## How core updates work

Every six hours, **Upstream releases** checks published stable releases in `stupside/castor`. New versions are processed in ascending order, one per run. It merges the tagged source into an update branch, records the exact tag/commit in [android/upstream.json](android/upstream.json), opens a PR and explicitly calls Android validation. Once Go checks, both native ABI builds, JVM tests, lint and the full Android 17 / 16 KB device suite pass, main advances to that exact tested commit without a force-push.

Normal Android builds generate the official Connect-Kotlin client with Buf from the same Protobuf contracts used by the engine. New core contracts regenerate the communication code automatically; new interface controls and incompatible API changes still need Android development.

Conflicts open an issue and leave main unchanged. Failed checks leave the update PR open. Workflow/automation changes require manual review. Prereleases, drafts and unrelated tags are ignored. This is source integration; it does not install desktop release binaries or silently publish an APK. GitHub's scheduler can be delayed and can disable schedules after 60 days without repository activity; **Run workflow** also checks immediately.

The starting snapshot includes core adaptations based on upstream `af8c784` and live-stream fixes beyond the stable v1.9.0 baseline. The updater does not downgrade it. The full source and history are retained because the native entry point uses Go internal packages; changing the Go module path would break those boundaries. Android's application ID is also retained; updating an installed app requires the same signing identity.

## Validation

The generated-client integration passed 18 JVM tests, all 28 instrumentation tests on a local Android 17 / 16 KB emulator, release lint and debug/release assembly. A build without existing generated clients recreated them automatically. This integration has not yet been retested on the physical OPPO/LG pair. The unchanged Go source previously passed the short suite (1,149 passing test/subtest results; three desktop/E2E tests excluded), and repository synchronization has additional local Git integration tests. The GitHub-hosted emulator is still being diagnosed; new core releases must pass all CI gates before automatic integration.

## Credits and licensing

Castor and its Go core are by [stupside and contributors](https://github.com/stupside/castor). This Android project grew from [Castor PR #68](https://github.com/stupside/castor/pull/68). The upstream maintainer adapted the shared fixes for [live playback](https://github.com/stupside/castor/commit/e5456f1), [playlist refusals](https://github.com/stupside/castor/commit/a28a726), [wrapped MPEG-TS](https://github.com/stupside/castor/commit/d005fc9) and [DLNA confirmation](https://github.com/stupside/castor/commit/eaefa19).

Project source retains the [MIT license](LICENSE) and upstream attribution. Separately executed FFmpeg binaries include GPL components: APK distributions must include notices and corresponding native sources/build instructions. See [native licensing](android/README.md#native-licensing). Castor bundles no content or source catalog and adds no DRM/paywall/geo-restriction bypass.
