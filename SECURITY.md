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

## Web pages and browser extensions

The embedded GeckoView browser runs uBlock Origin and AdGuard before loading a
page. It refuses new-window popups, file/content URLs and protected-media
permission requests. Websites have no native bridge. This does not guarantee
that every website or stream is safe or supported.

The optional browser-extension gateway listens on `127.0.0.1:8411`. It accepts
only that exact Host and refuses web-page Origins. A browser must pair through an
explicit owner approval in the app or a notification; only the token's SHA-256 is
stored. The gateway exposes limited device and cast RPCs, accepts browser-found
streams rather than webpage resolution, and never reveals engine credentials.
Any app on the phone can request pairing: approve only a request you initiated.
Use **Browser extension** in the menu to revoke all grants.

## Distribution and secrets

Never commit signing keys, passwords, access tokens or `local.properties`. Revoke
credentials accidentally published. Stable APKs use the maintainer's release
signer; CI debug APKs have a different identity. Verify downloads as described in
[release signing](android/docs/SIGNING.md). Upstream core synchronization has no
access to the private release key and does not publish APK releases.
