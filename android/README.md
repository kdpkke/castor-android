> Community project: [kdpkke/castor-android](https://github.com/kdpkke/castor-android), based on [stupside/castor](https://github.com/stupside/castor). Android issues belong in this repository. See [core updates](docs/UPSTREAM.md).

# Castor for Android

An independent Android app using Castor's existing Go API and media engine. The phone extracts the video, controls the TV, and serves or transcodes media itself. No PC, NAS, Termux, root, or remote Castor server is required.

## Multiple TVs and recovery

Use **New cast** to send another video to another TV without stopping the current transmissions. The transmission list keeps each TV and source separate; select one to use its playback controls. A TV already casting is marked in the TV picker.

**Restart casting**, next to **Stop casting**, reloads the original source on that TV. Finite videos resume from the receiver's last reported position; live streams return to live. Direct-source playback can remain under app control without forcing conversion or relaying every video through the phone.

For confirmed live sources, the native engine automatically restarts playback after more than ten seconds without receiver-clock progress, including while the screen is off. Pause and unavailable position reset the timer. Normal videos never use this automatic restart. Three consecutive stalled recovery attempts stop with an error; thirty seconds of progress resets that budget. TV-reported time cannot detect a frozen picture whose audio clock keeps advancing.

## Use

1. Install the APK from [Releases](https://github.com/kdpkke/castor-android/releases) on Android 9 or newer. ARM64 phones and x86-64 emulators are supported.
2. Connect the phone and TV to the same Wi-Fi. On Android 17, allow local network access. Notifications expose the running service and its stop action.
3. Tap **Paste link**, share a URL/video from another app, or tap **Choose video** to open Android’s document picker. **Automatic** recognizes media extensions and checks server headers for extensionless downloads; the source menu lets you override detection.
4. Tap **Choose your TV** to see the TVs found on Wi-Fi with their IP addresses and ports. Select a TV, then choose one of its available protocols. **Back** below the protocols returns to the TV list. Manual entry remains available; DLNA also accepts a full description URL.
5. Open **Playback settings** if needed. Choose **Native** to preserve the original video and audio without re-encoding. It keeps resolution, codecs, HDR and frame rate; Chromecast tries the original tracks and lets the receiver decide whether it can play them, rather than rejecting HEVC or HDR because only generic device limits are known. Live streams automatically retry in **Optimized** when Native cannot preserve them, and the app shows the effective mode without changing the saved preference. Finite videos remain strict: if the original tracks cannot be played, choose **Optimized**. A container change may still be needed, so Native does not mean a byte-identical file. Choose **Optimized** to allow receiver-specific conversion when needed, including a resolution limit of 1080p and HDR-to-SDR conversion. Each choice updates its explanation immediately; both choices and explanations are translated into Italian. Native remains the default; previous numeric limits appear as Optimized. **Save** stores the playback settings; closing them with unsaved changes asks whether to save. Language, appearance, TV and playback settings persist when the app closes. Then tap the fixed **Cast video** button. If a page requires interaction, start its video in the embedded player while extraction is running.
6. During playback, use **Pause/Resume**, the timeline, or the circular **10** arrows. Tap an arrow for a ten-second step; hold it to keep seeking until release. Controls follow the receiver's reported capabilities. Unknown position or duration appears as **--:--**, and seeking is unavailable for live media without a known duration. Native Chromecast downloads use indexed HLS fragments while the source arrives, preserving the original video and audio. A seek within the receiver's available window uses those fragments; a seek outside it prepares a new portion from the requested source position. Receivers with pause support hold the current picture before seeking or preparing the new portion, then resume only if playback was running before the command. A failed preparation restores the previous playback state. The app shows the position within the complete video; the TV's own timer may instead show time within the new portion. Seeking depends on source access and may round to segment/keyframe boundaries. Castor confirms the receiver's position after a local seek; an acknowledgement that restarts at zero is an error. Control errors can be copied without ending the cast. Controls recover automatically from the receiver's state; a failed command cancels the current held gesture, and a new press can retry.
7. **Stop casting** ends the current cast. The app-bar menu and notification expose **Stop Castor**, which shuts down the phone's engine and releases its locks.

DLNA/Roku discovery searches the default route and the phone's hotspot interfaces together, so a TV on the phone's hotspot responds even when the default route is cellular or a VPN, and TVs on home Wi-Fi stay visible while a hotspot is shared. Android 16+ reports the active hotspot interfaces; older versions try the usual soft-AP interface names. A route that fails does not hide receivers found on another route or Chromecasts.

**Last used** appears only after the current search finds that receiver. If the saved Chromecast is missing from multicast announcements, Castor also checks its saved address, verifies the same receiver UUID over HTTP and confirms that its Cast port is open. Offline receivers and addresses reused by another device stay hidden. On reopening, the main screen searches for the saved TV and shows it as being checked; **Cast video** stays disabled when the search ends without it. Discovered TVs are saved by identity, so a changed IP address or description port uses the newly discovered address. Entries saved before identities are matched once by name and host, then upgraded; until then their stale address is not used. Manual addresses entered through **Enter a TV address** are used as typed and need no search. On reopening, the app recovers any running cast before enabling a new one. If a request fails while a cast still owns the receiver, the app restores that session so **Stop casting** can release it.

The TV list groups discovered services at the same host, showing each TV once with its IP addresses and ports. A confirmed last-used TV appears first. Selecting a TV opens its discovered protocols; **Back** below them returns to the list. During webpage extraction, the page player scrolls into view and shows its address and loading state. If no stream is found, the page stays available: start its video, then cast again without reloading the page. Close the page when finished. A failed main-document request is reported explicitly; missing ads or other subresources do not fail the whole page. An offline channel or a DRM-protected video cannot be made playable by opening its webpage.

The Android interface uses a shared Liquid Glass material with **Dark** as the default appearance. Choose **Appearance** from the app-bar menu for **System**, **Light** or **Dark**; the choice is saved and applies immediately without recreating the screen or restarting the cast. **System** follows device night-mode changes, while explicit choices override them, including the system-bar foregrounds. Native sheets, inputs and controls use translucent surfaces with a specular rim; Android 12+ blurs only the scenic backplate, keeping content sharp. Android 9–11 retain the same material without the native blur effect. English is the default language; choose **App language** from the app-bar menu to switch to **Italiano** or **English**. The choice is saved and applies immediately, including after reopening the app. Changing language recreates the screen while the casting service keeps running. Both language and appearance choosers stay open after selection, immediately updating their content and selected row; close them with the close button, Android Back or a native sheet gesture. The playback summary explicitly labels its subtitle value. Only language, appearance, receiver and playback preferences are saved in private preferences; video URLs and access tokens are not saved there. Errors have collapsible, selectable details; **Copy error** always copies the entire diagnostic.

Subtitles offer **Off**, **App language**, or an explicit language code such as `en` or `it`. Native uses source captions only and never burns text into the video. Optimized can also use Whisper transcription when captions are missing. The saved `auto` preference follows the selected app language (English or Italian), matching upstream’s explicit language policy. First transcription use downloads a pinned, checksum-verified Whisper model; caption delivery follows the receiver’s capabilities and requires served playback. Real-time software encoding and transcription can consume substantial battery and may exceed a phone's sustained processing speed.

Webpages use GeckoView with the real uBlock Origin and AdGuard Firefox extensions
enabled together. The page opens only after both are enabled; new-window popups
are refused. Blocked video URLs left in the HTML cannot become stream candidates.
See [browser ad blocking](docs/ADBLOCK.md) for startup, licensing, updates and limits.

A selected video stays in its original location: Castor reads it through an unguessable private HTTP URL and serves it to the TV. No upload to an external server, full-file copy or storage-wide permission is needed. Keep the original file available until casting ends. The selection survives language changes while the app is running; video locations are not saved as app preferences.

Download links do not need a `.mp4` or `.mkv` suffix. On **Cast video**, Automatic checks the server’s MIME type and attachment filename without downloading the body, with a one-byte range request when HEAD is unavailable. Recognized downloads use the existing progressive media engine. Playback can start before the full file arrives; a container with end-of-file metadata and a server without byte-range support may still need more downloading. Login-only, expired and DRM-protected downloads retain their usual limits. This does not save a separate permanent download on the phone.

## Architecture

- `cmd/castor-android` composes the existing API and media services. Their RPCs stay authenticated and bound to loopback; only the device-facing media route is reachable on the LAN.
- `LocalVideos` adds selected-document capability routes to `BrowserServer`, with HEAD and finite byte ranges. The service owns their lifetime and revokes them when casting ends. Local sources explicitly use served delivery so the TV never receives a phone-only loopback URL.
- Loopback alone does not isolate Android apps. API, media, and browser RPCs require independent ephemeral bearer tokens; internal HTTP media and device-facing playback URLs use unguessable capability paths. Android FFmpeg side outputs use Unix sockets in an app-private cache directory. Anyone given a playback URL can consume that cast's media.
- `CastorService` owns the native process, foreground notification, wake lock, and multicast lock. Casting continues when the activity is backgrounded. The native host reports cast activity, so the wake lock is held only during a cast. Service shutdown cancels the engine and browser.
- At engine startup, Android's default trust anchors are exported to an app-private PEM file and passed to OpenSSL through `SSL_CERT_FILE`. Android's hashed certificate-directory filenames are incompatible with OpenSSL's lookup scheme. TLS certificate and hostname verification remain enabled.
- GeckoView implements the existing `castor.scraping.v1` contract. Two signed ad blockers filter requests before an app-owned isolated extension captures successful video responses and their replay headers. Before the first page of each app process, a loopback check waits until an ad request is actually refused; being enabled is not enough. Browser connection, compression and cache-validation headers are not replayed to other readers. Websites have no native bridge. File/content URLs and protected-media permission requests are refused. The runtime starts only when a webpage is needed and preserves extension storage across session closure.
- Android NSD handles Chromecast discovery. SSDP uses multicast sockets on the default route and on each hotspot interface: those reported by Android 16+, or the usual soft-AP names on older versions. It looks up only those interface names through Java, without enumerating restricted netlink interfaces. Results enter the API's existing device directory; device protocols remain in Go.
- Protobuf Java classes and the official Connect-Kotlin clients use the same `../proto` contracts as the Go engine. The `client` Gradle project runs the pinned Buf CLI and Connect generator before Android compilation; generated sources stay in `client/build/` and are not committed. The app uses generated methods instead of maintaining RPC paths and protocol decoding. Its endpoint adapter retains bearer authentication, bounded responses, cancellation and the requirement for an explicit cast `Ended` result.
- Playback and Control RPCs reuse the current cast's leased receiver. Playback polling runs only while the app is visible and continues after a control error. Repeated seeks keep one request in flight and the latest pending target, rather than queuing every intermediate position. Releasing/cancelling a held arrow or leaving the screen stops repetition; commands are cancelled with their cast. Native Chromecast HLS seeks outside the receiver's reported window prepare a new media pipeline from the requested source offset, retaining the same API cast identity and device lease. A replacement LOAD waits until preparation succeeds; failed preparation preserves the old playback, and a refused LOAD attempts to restore it. Playback state adds the portion's source offset to the receiver's local position. This app-controlled preparation does not extend the TV remote's seek range.
- The shared HLS segment reader recognizes bounded `TIKTIKPX` PNG/gzip envelopes carrying MPEG-TS. HLS detected as PNG or without usable video dimensions is measured again through that same reader before admission; ordinary static images remain ineligible. This handles the media envelope without adding site-specific URL templates or disabling admission checks.
- Progressive live playback treats an open byte-zero range as a growing response, without a fixed content length. Chromecast can keep reading future bytes instead of finishing at the initial buffer. Bounded ranges, retained-window checks and finished-stream ranges keep their existing semantics.
- Native Chromecast remuxes of finite downloads prefer retained HLS event playlists over unindexed progressive MP4. Complete fragments remain available for seeking under the same 8 GiB output limit as a finite progressive spool, with a runtime-derived file-count limit capped at 16,384 and the shared disk budget. The master playlist declares the codecs read from the actual initialization segment, including copied HEVC and surround audio; playback starts at the beginning rather than the growing live edge. A finite HLS LOAD waits for the receiver to report playback or pause before committing the new portion. If it remains buffering at zero with seekable media available, one recovery seek starts a second into that portion; a receiver that still cannot start fails within a bounded wait. Paused finite playback waits for the receiver's end or cancellation instead of treating HTTP inactivity as completion. Live/unknown-duration and Optimized selection keep their existing behavior.
- Chromecast startup allows IDLE without a terminal idle reason, including extended LOADING. Status queries work before a media-session ID exists and follow its assignment; explicit terminal reasons still fail. The finite HLS startup wait remains bounded, with at most one recovery seek.
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

After building native binaries, open `android/` in Android Studio. The development APK is at `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android development key. The debug variant uses the separate `io.github.stupside.castor.debug` application ID and the launcher name **Castor (test)**, so it can be installed beside the stable app without uninstalling it or losing its preferences. It is not an update for the stable APK.

For distribution, use Android Studio's **Build > Generate Signed App Bundle or APK > APK**, select the **release** variant and a dedicated release keystore stored outside the repository. Alternatively, `:app:assembleRelease` produces an unsigned APK at `app/build/outputs/apk/release/app-release-unsigned.apk`; sign it with the Android SDK's `apksigner`. Keep an encrypted backup of the keystore and store its password separately. Future updates must preserve the signing identity and increase `versionCode`.

Official stable APKs use the dedicated certificate documented in [Release signing](docs/SIGNING.md). That guide includes its fingerprint, verification commands and the maintainer's signing procedure. Legacy development installations with the stable package ID need a one-time uninstall before moving to this identity. Current **Castor (test)** builds use a separate package and coexist with stable installations.

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
