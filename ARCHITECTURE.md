# Castor Android architecture

This community project adds an Android client and host around the upstream Castor
core. The core services and their ownership are described in the
[upstream architecture](https://github.com/stupside/castor/blob/main/ARCHITECTURE.md).
The exact integrated release is recorded in [android/upstream.json](android/upstream.json).

## Android ownership

- `android/app` owns the Kotlin/Jetpack Compose interface, preferences, Android
  permissions and foreground-service lifecycle.
- `CastorService` starts `cmd/castor-android` as a native child process in the app's
  private directory. The service owns its notification, wake and multicast locks,
  browser, discovery and shutdown. The activity can be recreated without ending a cast.
- `cmd/castor-android` composes the existing API and media services. API owns cast
  sessions and receiver control; media owns probing, ranking, transcoding,
  subtitles, delivery and recovery. Domain implementations remain in their
  service's Go `internal` packages.
- `BrowserServer` supplies scraping and device discovery through authenticated
  loopback RPCs. GeckoView opens web pages with uBlock Origin and AdGuard; Android
  NSD and SSDP find receivers. Protocol-specific device control remains in Go.
- `LocalVideos` serves only owner-selected Android documents through opaque
  loopback capabilities on `BrowserServer`, including HEAD and byte ranges.
  The engine serves these files to receivers; no full-file copy or storage-wide
  permission is needed. Ending the cast revokes its file capabilities.
- Automatic input classification checks HTTP headers for extensionless downloads.
  HTML still follows webpage extraction; detected media uses the engine's existing
  progressive delivery rather than waiting for a complete download.

## Contracts and trust boundaries

Go and Android share the Protobuf contracts in `proto/`. The `android/client`
Gradle project generates the Connect-Kotlin client with Buf; generated Java
messages and Kotlin clients are build outputs rather than hand-maintained copies.
Each consumer declares narrow ports and the host wires the existing service entry
points. Contract rules and RPC validation belong at the service boundary.

The engine's API, media and scraping RPCs use independent ephemeral bearer tokens.
Only device-facing media URLs are available on the LAN; their paths are
unguessable. A media URL still grants access to that cast's video. Loopback alone
does not isolate the app from other apps on the phone. See [security](SECURITY.md).

## Builds and updates

The native builder compiles Go, FFmpeg and Whisper for `arm64-v8a` and `x86_64`.
Gradle packages the binaries as extracted native libraries; Go and FFmpeg run as
separate processes. Native loading and playback are tested on Android 17 with
16 KB pages. See the [Android guide](android/README.md) for build and test commands.

[Upstream integration](android/docs/UPSTREAM.md) preserves the core's Git history,
Go module path and Android adapters. The four root documents describe Android;
ordinary content conflicts in those documents retain the Android copy. Conflicts
in code or other files still require review. Native builds, Go checks, JVM tests,
lint and the device suite gate automatic integration; workflow changes require
manual review. Updating the core does not publish an Android APK.
