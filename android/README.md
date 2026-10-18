> Community project: [kdpkke/castor-android](https://github.com/kdpkke/castor-android), based on [stupside/castor](https://github.com/stupside/castor). Android issues belong in this repository. See [core updates](docs/UPSTREAM.md).

# Castor for Android

An independent Android app using Castor's existing Go API and media engine. The phone extracts the video, controls the TV, and serves or transcodes media itself. No PC, NAS, Termux, root, or remote Castor server is required.

## Use

1. Install the development APK on Android 9 or newer. ARM64 phones and x86-64 emulators are supported.
2. Connect the phone and TV to the same Wi-Fi. On Android 17, allow local network access. Notifications expose the running service and its stop action.
3. Tap **Paste link** or share a URL from another app. **Automatic** recognizes common video extensions; use the source menu to override it with **Web page** or **Direct stream** for extensionless endpoints.
4. Tap **Choose your TV** to discover receivers, reuse the previous TV, or enter an address. DLNA also accepts a full description URL.
5. Open **Playback settings** if needed. **Native quality** keeps the selected stream’s resolution; the TV may still need codec conversion, so it does not promise bit-identical video. Existing height limits and the 1080p default remain available. Then tap the fixed **Cast video** button. If a page requires interaction, start its video in the embedded player while extraction is running.
6. **Stop casting** ends the current cast. The app-bar menu and notification expose **Stop Castor**, which shuts down the phone's engine and releases its locks.

The Android interface uses a shared Liquid Glass material with **Dark** as the default appearance. Choose **Appearance** from the app-bar menu for **System**, **Light** or **Dark**; the choice is saved and applies immediately without recreating the screen or restarting the cast. **System** follows device night-mode changes, while explicit choices override them, including the system-bar foregrounds. Native sheets, inputs and controls use translucent surfaces with a specular rim; Android 12+ blurs only the scenic backplate, keeping content sharp. Android 9–11 retain the same material without the native blur effect. English is the default language; choose **App language** from the app-bar menu to switch to **Italiano** or **English**. The choice is saved and applies immediately, including after reopening the app. Changing language recreates the screen while the casting service keeps running. Both language and appearance choosers stay open after selection, immediately updating their content and selected row; close them with the close button, Android Back or a native sheet gesture. The playback summary explicitly labels its subtitle value. Only language, appearance, receiver and playback preferences are saved in private preferences; video URLs and access tokens are not saved there. Errors have collapsible, selectable details; **Copy error** always copies the entire diagnostic.

Subtitles offer **Off**, **App language**, or an explicit language code such as `en` or `it`. Castor selects source captions in that language, with Whisper transcription as a fallback when captions are missing. The saved `auto` preference now follows the selected app language (English or Italian), matching upstream’s explicit language policy. First transcription use downloads a pinned, checksum-verified Whisper model; caption delivery follows the receiver’s capabilities and requires served playback. Real-time software encoding and transcription can consume substantial battery and may exceed a phone's sustained processing speed.

## Architecture

- `cmd/castor-android` composes the existing API and media services. Their RPCs stay authenticated and bound to loopback; only the device-facing media route is reachable on the LAN.
- Loopback alone does not isolate Android apps. API, media, and browser RPCs require independent ephemeral bearer tokens; internal HTTP media and device-facing playback URLs use unguessable capability paths. Android FFmpeg side outputs use Unix sockets in an app-private cache directory. Anyone given a playback URL can consume that cast's media.
- `CastorService` owns the native process, foreground notification, wake lock, and multicast lock. Casting continues when the activity is backgrounded. The native host reports cast activity, so the wake lock is held only during a cast. Service shutdown cancels the engine and browser.
- At engine startup, Android's default trust anchors are exported to an app-private PEM file and passed to OpenSSL through `SSL_CERT_FILE`. Android's hashed certificate-directory filenames are incompatible with OpenSSL's lookup scheme. TLS certificate and hostname verification remain enabled.
- Android WebView implements the existing `castor.scraping.v1` contract. It uses an app-private profile, captures stream requests and their replay headers, and exposes no JavaScript-to-native bridge. File/content URLs and protected-media permission requests are refused.
- Android NSD handles Chromecast discovery. SSDP discovery uses a default-route UDP socket, avoiding Android's restricted netlink interface enumeration. Results enter the API's existing device directory; device protocols remain in Go.
- Protobuf Java classes are generated directly from `../proto`. The Android client uses Connect's protobuf format for unary calls and framed status updates. A stream closing before an explicit `Ended` result is an error.
- The shared HLS segment reader recognizes bounded `TIKTIKPX` PNG/gzip envelopes carrying MPEG-TS. HLS detected as PNG or without usable video dimensions is measured again through that same reader before admission; ordinary static images remain ineligible. This handles the media envelope without adding site-specific URL templates or disabling admission checks.
- Progressive live playback treats an open byte-zero range as a growing response, without a fixed content length. Chromecast can keep reading future bytes instead of finishing at the initial buffer. Bounded ranges, retained-window checks and finished-stream ranges keep their existing semantics.
- Debuggable Android builds enable the engine's diagnostic logger; release builds retain normal logging. Credentials are redacted by the shared engine logger.
- Captured HLS playlists use stable, unguessable loopback URLs. A 401/403 reopens the original page once to renew the same playlist, matching an unambiguous origin and path; concurrent readers share that renewal. Relative resources keep their origin and child playlists use the same relay. Documents and expanded output are bounded to 4 MiB, redirects are bounded and cross-origin redirects discard authorization/cookies. Cancellation closes the request and the page; ending the cast revokes its playlist URLs. A page that cannot renew access still fails explicitly. Direct-stream URLs and unsupported extraction mechanisms retain their existing limits.
- A definitive playlist refusal is preserved by the shared timeline reader even if FFmpeg exits successfully, so truncated playback is not reported as completed. A later successful reload clears that refusal.

## Build on Windows

Install Android Studio, SDK platform **37.2**, build tools **37.0.0**, PowerShell 7, and Ubuntu in WSL. Android Studio's bundled JDK 25 is supported. In Ubuntu, install `build-essential`, `cmake`, `ninja-build`, `unzip`, `pkg-config`, `git`, `nasm`, `autoconf`, `automake`, `libtool`, `curl`, and `python3` using its package manager.

From the repository root in PowerShell:

```powershell
git submodule update --init third_party/whisper.cpp
.\android\scripts\build.ps1
```

The native build runs the checked-in Bash script through WSL. It downloads pinned Go and NDK toolchains and builds FFmpeg, FFprobe, x264, OpenSSL, FreeType, HarfBuzz, zimg, and the repository's pinned Whisper. Native build caches live in the Linux filesystem at `/var/tmp/castor-android-native`: extracting the Linux NDK onto case-insensitive NTFS loses distinct headers. `CASTOR_NATIVE_CACHE` may select another **case-sensitive** Linux directory. Do not use `/tmp` for a cache that must survive WSL restarts.

The Gradle wrapper pins Gradle 9.8.1; the version catalog pins AGP 9.4.1 and Kotlin 2.4.21. Existing desktop dependencies and the Whisper submodule revision are retained. Generated native binaries and Gradle output are ignored by Git.

After building native binaries, open `android/` in Android Studio. The development APK is at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android development key and is not a Play Store release.

Rerun the native builder after changing Go, protobuf, or native build sources. Gradle checks that runtime binaries and license notices are present; it does not rebuild the Go engine itself. Kotlin uses ktfmt's two-space Google style. Regenerate the Android mascot drawable with `python android/scripts/sync-logo.py` when the repository SVG changes.

On Linux, run `android/scripts/build-native.sh` with Bash for `arm64-v8a` and `x86_64`, then run the Gradle wrapper from `android/`. The native builder currently targets Linux x86-64 hosts, including WSL; macOS native builds need a matching host-toolchain adaptation.

## Verify

The build wrapper runs JVM tests, lint, and APK assembly. Device tests use only local fixtures: a private webpage, a generated video, and a simulated DLNA renderer. No real TV or streaming site is contacted.

Start an Android emulator, then run from PowerShell:

```powershell
.\android\scripts\test-device.ps1 -Serial emulator-5554
```

This waits for boot completion and checks fresh XML test results. Some Android Gradle versions can print `BUILD SUCCESSFUL` after APK installation fails; that text is insufficient evidence that device tests ran. Repeat with Android 17's 16 KB system image to exercise native loading and local-network permissions.

## Compatibility and limits

- DLNA uses the existing AVTransport implementation, including pinned hosts. If the initial `Play` acknowledgement times out, Castor keeps serving only when bounded AVTransport queries confirm `PLAYING` and the requested media URI; explicit refusals and cancellation still fail. Chromecast and Roku retain their upstream experimental status; real-device verification remains necessary.
- Roku still requires the upstream developer-channel setup. This initial Android screen does not edit Roku credentials or published channel IDs.
- The desktop TMDB catalog and configurable source templates are not part of the Android screen. Paste or share the authorized page directly.
- WebView capture recognizes MP4/WebM/Matroska and HLS/DASH URL suffixes, plus HTML video sources. It does not duplicate every desktop Chromium capture heuristic. Extensionless endpoints, service-worker-only interception, logins, anti-bot checks, and some iframe players can need further extraction work.
- No bundled video, site-specific sources, DRM decryption, paywall bypass, or geo-restriction circumvention is added. WebView receives no protected-media permission.
- As on desktop, the TV must be able to reach the phone's LAN media URL. Guest-network isolation, VPN routing, or a Wi-Fi AP blocking multicast can prevent discovery or playback.

## Interface previews

Native Android emulator captures use a synthetic example.org draft. The home
screen defaults to Dark; appearance can follow System or remain Light/Dark.

<img src="docs/images/home-dark.png" width="280" alt="Castor Android home in Dark appearance" />
<img src="docs/images/appearance-dark.png" width="280" alt="System, Light and Dark appearance choices" />

## Native licensing

Castor's source remains MIT licensed. The separately executed FFmpeg binaries include GPL components (`libx264`) and are built with GPLv3 enabled for their OpenSSL combination. Distribution must include their license notices and corresponding source/build instructions. Native source versions and download URLs are pinned in `scripts/build-native.sh`; provide the corresponding-source archive alongside an APK. Go and Android dependency licenses also apply.

See the repository's [security policy](../SECURITY.md), [architecture](../ARCHITECTURE.md), and [contributing guide](../CONTRIBUTING.md).
