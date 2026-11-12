# Castor Android security

This is the independently maintained Android project. Report sensitive Android
issues privately through the maintainer's GitHub profile; do not publish tokens,
cookies or private stream URLs in issues or logs. Non-sensitive bugs belong in
[kdpkke/castor-android](https://github.com/kdpkke/castor-android/issues).
For the shared engine, follow the
[upstream security policy](https://github.com/stupside/castor/blob/main/SECURITY.md).
The integrated core version is recorded in [android/upstream.json](android/upstream.json).

## Android services and media

- API, media and scraping RPCs use independent ephemeral bearer tokens and
  loopback bindings. Other apps can reach loopback, so authentication is required.
- Device-facing media is reachable on the LAN through unguessable capability
  URLs. Anyone given such a URL can fetch that cast's media. Use a trusted network.
- Playback and RPC traffic may use plain HTTP. Replay headers can contain source
  credentials; keep diagnostics and URLs private. Default Android trust anchors
  are exported for native HTTPS requests, preserving certificate and hostname checks.
- Native processes, certificates and temporary files run under the app's private
  directories. FFmpeg side outputs use private Unix sockets.
- Android does not configure an external telemetry collector. The core exports
  logs, traces and metrics only when `telemetry.endpoint` is explicitly configured.
  GeckoView and its extensions have separate network activity described in the
  [browser guide](android/docs/ADBLOCK.md).

## Web pages and selected videos

The embedded GeckoView browser runs uBlock Origin and AdGuard before loading a
page. It refuses new-window popups, file/content URLs and protected-media
permission requests. Websites have no native bridge. This does not guarantee
that every website or stream is safe or supported. When a page offers no stream,
Castor taps or clicks its player as a person would; it never taps a link, and for
two seconds after such a tap the page cannot navigate the tab elsewhere.

Local videos are opened through Android's document picker or a video share grant.
The app requests no storage-wide permission. Only explicitly selected content
receives an unguessable loopback capability; ending the cast revokes that access.
Web pages still cannot open file/content URLs. The retired external browser
extension gateway is no longer started or exposed in settings.

## In-app updates

Only the release build signed with the release key, installed by anything other
than Google Play, asks GitHub's API for this repository's latest release, at most
every six hours. It installs only that release's own `castor-android.apk`, after
checking its SHA-256 against the release's `SHA256SUMS.txt`, its package name and
a higher version code. Both files come from the same GitHub release, so the
checksum catches a damaged download, not a compromised release; Android still
refuses an APK not signed with the installed app's key. The download-complete
receiver is exported for the system download manager and acts only on the
download Castor started; installer results go to a receiver no other app can reach.

## Distribution and secrets

Never commit signing keys, passwords, access tokens or `local.properties`. Revoke
credentials accidentally published. Stable APKs use the maintainer's release
signer; CI debug APKs have a different identity. Verify downloads as described in
[release signing](android/docs/SIGNING.md). Upstream core synchronization has no
access to the private release key and does not publish APK releases.
