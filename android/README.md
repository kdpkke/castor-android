> Community project: [kdpkke/castor-android](https://github.com/kdpkke/castor-android), based on [stupside/castor](https://github.com/stupside/castor). Android issues belong in this repository. See [core updates](docs/UPSTREAM.md).

# Castor for Android

An independent Android app using Castor's existing Go API and media engine. The phone extracts the video, controls the TV, and serves or transcodes media itself. No PC, NAS, Termux, root, or remote Castor server is required.

## Use

1. Install the APK from [Releases](https://github.com/kdpkke/castor-android/releases) on Android 9 or newer. ARM64 phones and x86-64 emulators are supported.
2. Connect the phone and TV to the same Wi-Fi. On Android 17, allow local network access. Notifications expose the running service and its stop action.
3. Tap **Paste link** or share a URL from another app. **Automatic** recognizes common video extensions; use the source menu to override it with **Web page** or **Direct stream** for extensionless endpoints.
4. Tap **Choose your TV** to discover receivers, reuse the previous TV, or enter an address. DLNA also accepts a full description URL.
5. Open **Playback settings** if needed. **Native quality** keeps the selected stream’s resolution; the TV may still need codec conversion, so it does not promise bit-identical video. It is the default; the 720p, 1080p, 2160p and custom limits remain available. **Save** stores the playback settings; closing them with unsaved changes asks whether to save. Language, appearance, TV and playback settings persist when the app closes. Then tap the fixed **Cast video** button. If a page requires interaction, start its video in the embedded player while extraction is running.
6. **Stop casting** ends the current cast. The app-bar menu and notification expose **Stop Castor**, which shuts down the phone's engine and releases its locks.

DLNA/Roku discovery searches the default route and the phone's hotspot interfaces together, so a TV on the phone's hotspot responds even when the default route is cellular or a VPN, and TVs on home Wi-Fi stay visible while a hotspot is shared. Android 16+ reports the active hotspot interfaces; older versions try the usual soft-AP interface names. A route that fails does not hide receivers found on another route or Chromecasts.

**Last used** appears only after the current search finds that receiver. On reopening, the main screen searches for the saved TV and shows it as being checked; **Cast video** stays disabled when the search ends without it. Discovered TVs are saved by identity, so a changed IP address or description port uses the newly discovered address. Entries saved before identities are matched once by name and host, then upgraded; until then their stale address is not used. Manual addresses entered through **Enter a TV address** are used as typed and need no search. On reopening, the app recovers any running cast before enabling a new one. If a request fails while a cast still owns the receiver, the app restores that session so **Stop casting** can release it.

The last-used receiver has its own row above a divider and **Other TVs**; it is not repeated in the other results. During webpage extraction, the page player scrolls into view and shows its address and loading state. If no stream is found, the page stays available: start its video, then cast again without reloading the page. Close the page when finished. A failed main-document request is reported explicitly; missing ads or other subresources do not fail the whole page. An offline channel or a DRM-protected video cannot be made playable by opening its webpage.

The Android interface uses a shared Liquid Glass material with **Dark** as the default appearance. Choose **Appearance** from the app-bar menu for **System**, **Light** or **Dark**; the choice is saved and applies immediately without recreating the screen or restarting the cast. **System** follows device night-mode changes, while explicit choices override them, including the system-bar foregrounds. Native sheets, inputs and controls use translucent surfaces with a specular rim; Android 12+ blurs only the scenic backplate, keeping content sharp. Android 9–11 retain the same material without the native blur effect. English is the default language; choose **App language** from the app-bar menu to switch to **Italiano** or **English**. The choice is saved and applies immediately, including after reopening the app. Changing language recreates the screen while the casting service keeps running. Both language and appearance choosers stay open after selection, immediately updating their content and selected row; close them with the close button, Android Back or a native sheet gesture. The playback summary explicitly labels its subtitle value. Only language, appearance, receiver and playback preferences are saved in private preferences; video URLs and access tokens are not saved there. Errors have collapsible, selectable details; **Copy error** always copies the entire diagnostic.

Subtitles offer **Off**, **App language**, or an explicit language code such as `en` or `it`. Castor selects source captions in that language, with Whisper transcription as a fallback when captions are missing. The saved `auto` preference now follows the selected app language (English or Italian), matching upstream’s explicit language policy. First transcription use downloads a pinned, checksum-verified Whisper model; caption delivery follows the receiver’s capabilities and requires served playback. Real-time software encoding and transcription can consume substantial battery and may exceed a phone's sustained processing speed.

Webpages use GeckoView with the real uBlock Origin and AdGuard Firefox extensions
enabled together. The page opens only after both are enabled; new-window popups
are refused. Blocked video URLs left in the HTML cannot become stream candidates.
See [browser ad blocking](docs/ADBLOCK.md) for startup, licensing, updates and limits.

## Architecture

- `cmd/castor-android` composes the existing API and media services. Their RPCs stay authenticated and bound to loopback; only the device-facing media route is reachable on the LAN.
- Loopback alone does not isolate Android apps. API, media, and browser RPCs require independent ephemeral bearer tokens; internal HTTP media and device-facing playback URLs use unguessable capability paths. Android FFmpeg side outputs use Unix sockets in an app-private cache directory. Anyone given a playback URL can consume that cast's media.
- `CastorService` owns the native process, foreground notification, wake lock, and multicast lock. Casting continues when the activity is backgrounded. The native host reports cast activity, so the wake lock is held only during a cast. Service shutdown cancels the engine and browser.
- At engine startup, Android's default trust anchors are exported to an app-private PEM file and passed to OpenSSL through `SSL_CERT_FILE`. Android's hashed certificate-directory filenames are incompatible with OpenSSL's lookup scheme. TLS certificate and hostname verification remain enabled.
- GeckoView implements the existing `castor.scraping.v1` contract. Two signed ad blockers filter requests before an app-owned isolated extension captures successful video responses and their replay headers. Before the first page of each app process, a loopback check waits until an ad request is actually refused; being enabled is not enough. Browser connection, compression and cache-validation headers are not replayed to other readers. Websites have no native bridge. File/content URLs and protected-media permission requests are refused. The runtime starts only when a webpage is needed and preserves extension storage across session closure.
- Android NSD handles Chromecast discovery. SSDP uses multicast sockets on the default route and on each hotspot interface: those reported by Android 16+, or the usual soft-AP names on older versions. It looks up only those interface names through Java, without enumerating restricted netlink interfaces. Results enter the API's existing device directory; device protocols remain in Go.
- Protobuf Java classes and the official Connect-Kotlin clients use the same `../proto` contracts as the Go engine. The `client` Gradle project runs the pinned Buf CLI and Connect generator before Android compilation; generated sources stay in `client/build/` and are not committed. The app uses generated methods instead of maintaining RPC paths and protocol decoding. Its endpoint adapter retains bearer authentication, bounded responses, cancellation and the requirement for an explicit cast `Ended` result.
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

The native build runs the checked-in Bash script through WSL. It downloads pinned Go and NDK toolchains and builds FFmpeg, FFprobe, x264, OpenSSL, FreeType, HarfBuzz, zimg, and the repository's pinned Whisper. Native build caches live in the Linux filesystem at `/var/tmp/castor-android-native`: extracting the Linux NDK onto case-insensitive NTFS loses distinct headers. Whisper's CMake build directory is keyed by the checkout path, so separate clones can share dependencies without reusing a cache tied to another source directory. `CASTOR_NATIVE_CACHE` may select another **case-sensitive** Linux directory. Do not use `/tmp` for a cache that must survive WSL restarts.

The Gradle wrapper pins Gradle 9.8.1; the version catalog pins AGP 9.4.1 and Kotlin 2.4.21. Existing desktop dependencies and the Whisper submodule revision are retained. Generated native binaries and Gradle output are ignored by Git.

Client generation is part of a normal Gradle build on Windows and Linux; no separate Buf installation is needed. To regenerate explicitly, run `:client:bufGenerate` with the Gradle wrapper from `android/`. Buf CLI 1.74.0, its Gradle plugin 0.11.2 and Connect-Kotlin 0.9.0 are pinned. The generator template is `client/buf.gen.yaml`; keep its Connect version aligned with the runtime in the version catalog. Generation uses the public Buf remote plugin and requires network access when it runs. Buf is a build tool and is not included in the APK.

After building native binaries, open `android/` in Android Studio. The development APK is at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android development key and is not a Play Store release.

For distribution, use Android Studio's **Build > Generate Signed App Bundle or APK > APK**, select the **release** variant and a dedicated release keystore stored outside the repository. Alternatively, `:app:assembleRelease` produces an unsigned APK at `app/build/outputs/apk/release/app-release-unsigned.apk`; sign it with the Android SDK's `apksigner`. Keep an encrypted backup of the keystore and store its password separately. Future updates must preserve the signing identity and increase `versionCode`.

Official stable APKs use the dedicated certificate documented in [Release signing](docs/SIGNING.md). That guide includes its fingerprint, verification commands and the maintainer's signing procedure. Development installations need a one-time uninstall before moving to this identity.

Verify a signed download with `apksigner verify --verbose --print-certs <apk>` and compare the certificate SHA-256 fingerprint and APK checksum with the release notes. A fingerprint identifies the signer; it is different from the APK's file checksum. A release key change without an Android-supported rotation cannot update an installation signed with the development key. Self-signed certificates are supported; a paid certificate is not required and does not remove Android's installation or Play Protect checks.

Rerun the native builder after changing Go, protobuf, or native build sources. Gradle checks that runtime binaries and license notices are present; it does not rebuild the Go engine itself. Kotlin uses ktfmt's two-space Google style. Regenerate the Android mascot drawable with `python android/scripts/sync-logo.py` when the repository SVG changes.

On Linux, run `android/scripts/build-native.sh` with Bash for `arm64-v8a` and `x86_64`, then run the Gradle wrapper from `android/`. The native builder currently targets Linux x86-64 hosts, including WSL; macOS native builds need a matching host-toolchain adaptation.

## Verify

The build wrapper runs JVM tests, lint, and APK assembly. By default, device tests use only local fixtures: a private webpage, a generated video, and a simulated DLNA renderer. No real TV or streaming site is contacted unless the hotspot test below is explicitly enabled.

Start an Android emulator, then run from PowerShell:

```powershell
.\android\scripts\test-device.ps1 -Serial emulator-5554
```

This waits for boot completion and checks fresh XML test results. Some Android Gradle versions can print `BUILD SUCCESSFUL` after APK installation fails; that text is insufficient evidence that device tests ran. Repeat with Android 17's 16 KB system image to exercise native loading and local-network permissions.

To verify discovery on a physical Android 16+ phone, connect a powered-on DLNA TV to its hotspot, install the debug app and test APK, then run:

```powershell
adb -s <phone-serial> shell am instrument -w -e class io.github.stupside.castor.DeviceDiscoveryHotspotTest -e hotspot true io.github.stupside.castor.test/androidx.test.runner.AndroidJUnitRunner
```

These opt-in tests require a real TV. They verify discovery and connection through the native engine using the receiver's identity even with a deliberately stale saved URL. An invalid local video source ends the connection test before playback; no external streaming site is used. Stop active casts before running them. Gradle excludes classes marked `@ManualDiagnostic` from its fixture suite, through one `notAnnotation` runner argument, so they do not appear as skipped tests in CI.

To inspect a specific public webpage in the embedded browser without casting to a TV, run this manual diagnostic instead:

```powershell
adb -s <device-serial> shell am instrument -w -e class io.github.stupside.castor.ui.main.BrowserSiteDiagnosticTest -e publicPage "https://example.org/watch" io.github.stupside.castor.test/androidx.test.runner.AndroidJUnitRunner
```

It records the page document, an available browser screenshot, and the number of captured candidates under the app's external files directory. Capturing a candidate is not proof of TV playback. Normal tests use a local page and real touchscreen input to verify rendering, late Play interactions, retry without reloading, HTTP failures, dismissal, and cancellation.

## Compatibility and limits

- DLNA uses the existing AVTransport implementation, including pinned hosts. If the initial `Play` acknowledgement times out, Castor keeps serving only when bounded AVTransport queries confirm `PLAYING` and the requested media URI; explicit refusals and cancellation still fail. Chromecast and Roku retain their upstream experimental status; real-device verification remains necessary.
- Roku still requires the upstream developer-channel setup. This initial Android screen does not edit Roku credentials or published channel IDs.
- The desktop TMDB catalog and configurable source templates are not part of the Android screen. Paste or share the authorized page directly.
- Browser capture recognizes video and HLS/DASH response MIME types, common media suffixes, and HTML video URLs backed by a successful response. It does not duplicate every desktop Chromium capture heuristic. Blob/MSE-only playback, logins, anti-bot checks and some players can need further extraction work. GeckoView increases package size; `-Pcastor.abi=arm64-v8a` produces a smaller phone-only APK.
- No bundled video, site-specific sources, DRM decryption, paywall bypass, or geo-restriction circumvention is added. Gecko receives no protected-media permission.
- As on desktop, the TV must be able to reach the phone's LAN media URL. Guest-network isolation, VPN routing, or a Wi-Fi AP blocking multicast can prevent discovery or playback.

## Interface previews

Native Android emulator captures use a synthetic example.org draft. The home
screen defaults to Dark; appearance can follow System or remain Light/Dark.

<img src="docs/images/home-dark.png" width="280" alt="Castor Android home in Dark appearance" />
<img src="docs/images/appearance-dark.png" width="280" alt="System, Light and Dark appearance choices" />

## Native licensing

Castor's source remains MIT licensed. The separately executed FFmpeg binaries include GPL components (`libx264`) and are built with GPLv3 enabled for their OpenSSL combination. Distribution must include their license notices and corresponding source/build instructions. Native source versions and download URLs are pinned in `scripts/build-native.sh`; provide the corresponding-source archive alongside an APK. Go and Android dependency licenses also apply.

See the repository's [security policy](../SECURITY.md), [architecture](../ARCHITECTURE.md), and [contributing guide](../CONTRIBUTING.md).
