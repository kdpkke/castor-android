# Embedded browser ad blocking

Webpage extraction always uses a bundled AdAway hosts list. No root, VPN, remote
filtering service or first-launch download is required. This affects the Android
browser; the upstream Go core and explicit direct-stream mode are unchanged.

`BrowserAdBlock` matches the URL's canonical hostname, including subdomains of
listed hosts. It does not match arbitrary substrings in paths or query strings.
The original file's `localhost` mappings are excluded so local APIs and media
continue to work.

`BrowserResolver` applies these immutable rules before capturing or loading
WebView requests, to service-worker network requests, to page navigations, and
to URLs read from HTML video elements. Blocked subresources receive an empty
204 response and do not fail the whole page. An explicitly submitted blocked
page gets a clear error; a multi-page request can continue to its next page.
New windows and `target="_blank"` are refused instead of replacing the player.
Normal same-window links, unlisted CDNs and iframe players remain available.

## Source and license

- Project: [AdAway default blocklist](https://github.com/AdAway/adaway.github.io).
- Source: <https://adaway.org/hosts.txt> (also linked in the file header).
- Bundled file: `app/src/main/assets/adblock/hosts.txt`, downloaded 2026-10-21.
- SHA-256: `8c925c37a5eaa5e1c582c76e12301797861addee5ad0fa0e6e46c9dd0136fe55`.
- 6,540 host entries, excluding localhost mappings.
- Authors: Kicelo, Dominik Schuermann and contributors listed in the source
  project's commit history.
- License: [Creative Commons Attribution 3.0 Unported](https://creativecommons.org/licenses/by/3.0/).
  The list is included unchanged, with its original attribution and license
  header. Matching subdomains is application behavior, not a modification of
  the list. Castor's source license is unchanged.

The snapshot ships with the APK and is not silently replaced at runtime. To
refresh it, download that HTTPS source to a temporary file, review the diff and
license header, then replace the bundled file. Update the date, count and hash
above; run the ad-block unit and device tests before committing it. Native
libraries do not need rebuilding for a list update.

## Tests and limits

`BrowserAdBlockTest` checks hostname boundaries, subdomains, case, trailing dots,
ports, malformed lists and loopback preservation. `BrowserAdBlockIntegrationTest`
uses local servers with a separate blocked host: ad scripts, frames, videos,
extensionless DOM sources, HLS/DASH requests and page hijacking must never reach
the origin or become candidates. A delayed real stream and a normal iframe
player must still be captured. It also exercises service-worker filtering and
checks that the packaged list blocks known advertising domains. The browser
presentation fixtures cover real Play interaction, retry and page errors.

This is a domain blocker, not an EasyList parser or a cosmetic filter engine.
Ads on unlisted hosts or on the same endpoints as real content can still appear.
WebView does not expose every redirected subresource URL to interception;
WebSockets and existing service-worker caches also have platform limitations.
DOM candidates on listed hosts are still rejected. Blocking does not establish
that every remaining candidate is the intended video, nor make an offline or
DRM-protected channel playable. Fixtures do not prove playback of an external
website on a real TV.
