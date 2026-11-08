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

- Paste a link, share a video from another app or tap **Choose video** for a file on your phone.
- Automatic mode recognizes download links from server headers, including links without a video extension. Supported videos start playing while the phone reads them; a full download is not required first.
- Discover DLNA, Chromecast, AirPlay and FCast receivers, reuse a saved TV or enter an address manually.
- Cast several videos to different TVs at the same time, and restart a cast from where it was.
- Keep casting in the background through an Android foreground service.
- Pause, resume and seek from the app when supported by the TV. Tap a circular **10** arrow for a ten-second step, or hold it to keep seeking.
- Native playback without re-encoding, or Optimized playback for receiver compatibility; separate subtitle preferences.
- English and Italian; System, Light and Dark appearance, with Dark as the default.
- Settings remain open while choices update immediately; diagnostic details are selectable and copyable.
- Live-stream delivery follows future bytes; captured HLS access can renew through the original page when tokens expire.

Android 9+; arm64-v8a phones and x86_64 emulators. Previous casting builds were manually tested on **OPPO Find X8 Pro / ColorOS 16 → LG C2 in Chromecast mode**; see current validation coverage below. Roku remains experimental and requires upstream's developer-channel setup.

## Supported TVs and formats

### TV protocols

- **DLNA / UPnP**, found on most smart TVs (Samsung, LG, Philips, Sony and others).
  - In Native, a finished MKV, MP4, MPEG-TS or WebM file in a container the TV lists is sent unchanged, Dolby Vision included.
  - Otherwise Castor streams MPEG-TS (H.264 and AAC on TVs that list nothing more).
  - Pause and seek work where the TV allows them.
  - Tested on an LG C2.
- **Chromecast**: Google Cast devices and TVs with Chromecast built in.
  - The TV fetches the video itself when it can play it.
  - Otherwise Castor streams HLS, MP4, MPEG-TS or WebM to it.
  - Tested on an LG C2 in Chromecast mode.
- **AirPlay**: Apple TV, and TVs with AirPlay built in such as LG webOS models.
  - The first cast asks for the PIN the TV shows; the pairing is kept on the phone.
  - The TV gets HLS, or the original MP4 file. In Native, Dolby Vision is kept both for MP4 files and for MKV files Castor converts to HLS.
  - Tested on an LG C2. Apple TVs are supported but not yet tested on hardware; old Apple TVs that need the legacy PIN pairing are not supported.
- **FCast**: the FUTO receiver app and desktop receiver.
  - HLS or MP4 with H.264 video and AAC or MP3 audio; subtitles are burnt into the picture.
  - Not yet tested on hardware.
- **Roku** (experimental): Castor's developer channel plays HLS, MP4 and MKV with H.264. It needs upstream's developer-channel setup and is not yet tested on hardware.

### What you can cast

- **Sources**:
  - web pages with a video player (Castor finds the stream in the page);
  - direct links and downloads, including links without a video extension;
  - videos stored on the phone (**Choose video**, or share them to Castor).
- **Streaming**: HLS (`.m3u8`) and DASH (`.mpd`), on demand and live.
- **Files**: MP4 and M4V, MOV, MKV, WebM, MPEG-TS, AVI and FLV.
- **Video**:
  - **Native** keeps the original video (H.264, HEVC, VP9, AV1 and others, HDR10 and Dolby Vision) when the TV accepts it;
  - **Optimized** converts to H.264 up to 1080p, HDR to SDR, using the phone's hardware encoder when it works.
- **Audio**: Native keeps AAC, AC-3, E-AC-3, MP3, Opus, FLAC and the rest when the TV accepts them; Optimized converts what the TV cannot play.
- **Subtitles**:
  - the source's own subtitles in the chosen language;
  - or subtitles generated from the audio by a downloaded speech model, on TVs Castor streams to, such as DLNA.
  - They are sent as a subtitle track where the TV supports one (WebVTT for Chromecast and AirPlay), otherwise burnt into the picture. Native never burns or generates subtitles.

## Install and use

Download an APK from this repository's Releases, connect the phone and TV to the same Wi-Fi, then paste/share a URL or tap **Choose video**, choose the TV and tap **Cast video**. Automatic detects pages and media downloads; the source menu lets you override it. Android 17 requires local-network permission. See the [usage and compatibility guide](android/README.md) for details.

Release notes include SHA-256 checksums and the signing certificate fingerprint so you can verify your download. CI debug APKs are temporary validation builds and use a different signer. No signing keys are committed or uploaded by the updater.

Stable releases use a dedicated signing key; see [signature verification](android/docs/SIGNING.md) and its public certificate. Current development builds appear as **Castor (test)** and install alongside the stable app. Legacy development builds using the stable package ID require a one-time uninstall before installing a stable APK, which removes their saved preferences. Later stable releases update normally.

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

Content conflicts limited to this project's four Android-owned root documents keep the Android copy. Code conflicts, renames and deletions open an issue and leave main unchanged. Failed checks leave the update PR open. Workflow/automation changes require manual review. Prereleases, drafts and unrelated tags are ignored. This is source integration; it does not install desktop release binaries or silently publish an APK. GitHub's scheduler can be delayed and can disable schedules after 60 days without repository activity; **Run workflow** also checks immediately.

The starting snapshot includes core adaptations based on upstream `af8c784` and live-stream fixes beyond the stable v1.9.0 baseline. The updater does not downgrade it. The full source and history are retained because the native entry point uses Go internal packages; changing the Go module path would break those boundaries. Android's application ID is also retained; updating an installed app requires the same signing identity.

## Validation

Android validation runs Go tests, race checks and vet, native ARM64/x86-64 builds, JVM tests, lint, APK assembly and the full fixture suite on a GitHub-hosted Android 17 / 16 KB emulator. Repository synchronization has local Git integration tests, including conflict handling and concurrent changes. See [Actions](https://github.com/kdpkke/castor-android/actions) for the results of each commit. Physical OPPO/LG testing covers earlier casting builds; it is not a claim that every later revision has been tested on that hardware. New core releases must pass all CI gates before automatic integration.

## Credits and licensing

Castor and its Go core are by [stupside and contributors](https://github.com/stupside/castor). This Android project grew from [Castor PR #68](https://github.com/stupside/castor/pull/68). The upstream maintainer adapted the shared fixes for [live playback](https://github.com/stupside/castor/commit/e5456f1), [playlist refusals](https://github.com/stupside/castor/commit/a28a726), [wrapped MPEG-TS](https://github.com/stupside/castor/commit/d005fc9) and [DLNA confirmation](https://github.com/stupside/castor/commit/eaefa19).

Project source retains the [MIT license](LICENSE) and upstream attribution. Separately executed FFmpeg binaries include GPL components: APK distributions must include notices and corresponding native sources/build instructions. See [native licensing](android/README.md#native-licensing). Castor bundles no content or source catalog and adds no DRM/paywall/geo-restriction bypass.
