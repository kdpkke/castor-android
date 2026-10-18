# Contributing to Castor Android

Android bugs, receiver compatibility, extraction and playback reports belong in [this repository](https://github.com/kdpkke/castor-android/issues). Include the app version, Android version, receiver/protocol, reproducible steps and the copyable diagnostic. Remove access tokens, cookies and private URLs before posting logs.

Core-only changes can also be proposed to [upstream Castor](https://github.com/stupside/castor). This project maintains Android integration independently; the Go module and internal ownership rules remain upstream's. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Build and test

Follow [android/README.md](android/README.md). Build the Go/native runtime again after Go, proto or native-build changes. Keep English and Italian resources aligned and preserve saved preferences and active casts.

```sh
python3 -m unittest discover -s android/scripts -p '*_test.py' -v
bash android/scripts/build-native.sh arm64-v8a x86_64
cd android
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `android/scripts/build.ps1` and `test-device.ps1`. Device tests use local fixtures, not a real streaming site or TV. Verify actual device-test XML; a successful Gradle build alone does not prove that installation/tests ran.

Kotlin follows ktfmt's two-space Google style. Go follows the upstream architecture and `gofmt`; test behavior and reproduce regressions before fixing them. Use Conventional Commits (`fix(android): ...`, `chore(core): ...`), with a blank line before a message body. Never commit signing keys, caches, `local.properties`, tokens or downloaded native binaries.

## Upstream updates and releases

[Core synchronization](android/docs/UPSTREAM.md) is handled by the Upstream releases workflow. Resolve conflicts and failed tests on its update PR, rerun validation, then integrate the reviewed commit. Do not force-push the published main branch or choose entire conflicting core files as ours/theirs.

Android validation produces short-lived debug APK artifacts and corresponding native sources. Those APKs use an ephemeral CI signer. Maintainer-published APKs need the established local signer; core synchronization never uploads private keys or publishes installable updates automatically.

Desktop CI/delivery workflows are retained from upstream for history but disabled in this repository. Active workflows are Android validation and Upstream releases.
