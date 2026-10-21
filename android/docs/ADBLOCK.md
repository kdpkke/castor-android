# Browser and ad blockers

The page player uses **Mozilla GeckoView**, with the real **uBlock Origin** and
**AdGuard AdBlocker** Firefox extensions enabled together. There is one page,
without general browser chrome, tab management or an extension-disable control.
The Android System WebView and the earlier AdAway host-only blocker are no
longer used. This change stays in the Android layer; Castor's Go core is unchanged.

## Startup and lifetime

Gecko starts on the first webpage resolution, rather than for direct-stream
casting. One runtime belongs to the app process; each resolver owns a session.
Closing a page or stopping Castor closes its session, preserving extension
settings, downloaded filters and the app's existing preferences. Gecko uses an
app-private profile. Existing WebView cookies are not migrated or deleted.

Both signed XPI packages ship inside the APK. First installation verifies their
SHA-256 hashes and Firefox's signature checks remain enabled. Existing installed
extensions are reused; both user/app disable flags are cleared at startup.
Before each resolution the app checks that both extensions are enabled. A
missing or disabled blocker fails the request before opening the source page.
The page panel distinguishes protection startup from enabled protection.

AdGuard awaits `tabs.create` for its internal first-install page before enabling
its default filters. A restricted `TabDelegate` serves only a trusted blocker's
own `moz-extension:` pages in temporary, undisplayed sessions. It refuses external
promotional pages, releases completed setup sessions, and cannot replace the
source player. Site-created new windows and non-HTTP(S) navigation are refused.

Filters update through the extensions' normal mechanisms. The application does
not modify or repackage either extension. Installed extensions retain their
storage across app updates. Update the pinned packages, checksums and version
catalog deliberately for a new browser/extension release; do not silently
replace packages with an unpinned first-launch download.

## Stream capture and trust boundary

`castor-browser/` is a small, app-owned built-in extension. It observes successful
HTTP responses and the request headers needed to replay video. Requests cancelled
by either blocker never become responses or stream candidates. HLS/DASH and video
MIME types are supported alongside familiar URL suffixes. HTML video sources are
admitted only after that URL has produced a successful response. Merely placing a
blocked advertisement's URL in a video element cannot admit it.

Only the isolated top-level content script communicates with the session's
native port. Frame players report through the extension background script;
websites receive no JavaScript-to-native interface or window-message bridge.
Native code checks session/port ownership, URL schemes, credentials, message
sizes and candidate limits. Response records are scoped to their navigation;
page closure clears them. File/content URLs, TLS bypasses and protected-media
permissions remain unsupported.

The existing source RPC, replay headers, loopback HLS capabilities, renewal,
cancellation and receiver protocols remain in place. Failed interactive
extraction keeps the page for Play and retry without a reload. Background HLS
renewal stays noninteractive. HTTP errors in the main document remain visible;
failed subresources do not fail the page.
Main HTTP failures also have a validated background-extension notification path:
an empty error document cannot be required to execute a content script before
the resolver can handle its HTTP status. Gecko's TextureView backend participates
in Compose's scrolling and clipping; it avoids a separate SurfaceView layer at
the cost of some rendering performance while the page is visible.

## Pinned components and licenses

| Component | Version | Source / license |
| --- | --- | --- |
| GeckoView | 158.0.20261021001945 | [Mozilla Maven](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/) · [Firefox source](https://firefox-source-docs.mozilla.org/contributing/contribution_quickref.html) · MPL 2.0 |
| uBlock Origin | 1.75.0 | [Signed AMO package](https://addons.mozilla.org/firefox/downloads/file/5034826/ublock_origin-1.75.0.xpi) · [corresponding source](https://github.com/gorhill/uBlock/tree/1.75.0) · GPL 3.0 |
| AdGuard AdBlocker | 5.5.3.5 | [Signed AMO package](https://addons.mozilla.org/firefox/downloads/file/5116233/adguard_adblocker-5.5.3.5.xpi) · [corresponding source](https://github.com/AdguardTeam/AdguardBrowserExtension/tree/v5.5.3.5) · GPL 3.0 or later |

The XPI packages, their original notices and additional full license texts are
in `app/src/main/assets/extensions/`. Source snapshots must accompany binary
redistribution as required by those licenses; the engine and extensions are
separate components and Castor's own source remains MIT licensed.
`extensions/NOTICE.txt` links the corresponding source archives, including
Gecko's exact native revision `e4ce606692cd81e6898547e2edd7c552fefcbb04`.

- uBlock XPI SHA-256: `5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287`
- AdGuard XPI SHA-256: `becd4d42749c57b5fbc22613ace4a93d0e47c2e646f8771a9e4f28c1c768cd89`

## Verification and size

`BrowserAdBlockIntegrationTest` checks both installed/enabled extensions, proves
each can filter independently after initialization, and verifies that a disabled
blocker prevents page loading. Local fixtures use real generic rules present in
both packages: an ad script attempts to replace the page and video ads appear
before the legitimate stream. No external advertising server is contacted.
Browser fixtures cover rendering, real Play input, popup refusal, retry without
reload, HTTP errors, headers/cookies, HLS renewal, cancellation and simulated
DLNA playback.

GeckoView includes its own browser engine and increases APK size and memory
use. The interface is minimal; the binary is not as small as System WebView.
The default build supports ARM64 and x86-64. Use `-Pcastor.abi=arm64-v8a` for a
smaller phone-only APK or `-Pcastor.abi=x86_64` for an emulator-only APK. Quote
the complete `'-Pcastor.abi=arm64-v8a'` argument in PowerShell. This
selects packaged libraries; it does not rebuild Castor's native engine.

Two blockers can overlap or break a site's player. Being enabled is not proof
of zero startup latency, and no blocker guarantees removal of every advertisement.
Remaining media candidates still pass Castor's media inspection. This does not
make offline or DRM-protected video playable. Local fixtures alone do not prove
playback of a public webpage on a real television.
