# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Scope

This product record describes the native app in `android/`. The existing desktop
app and Go services remain outside this interface redesign.

## Users

People sending a video link from their phone to a television on the same Wi-Fi.
The current confirmed device pair is an OPPO Find X8 Pro with ColorOS 16 and an LG C2 in Chromecast mode.

## Product Purpose

Turn a shared or pasted webpage or direct video URL into a television cast.
Success means choosing the video and receiver quickly, understanding connection
progress, and stopping or recovering from failure without technical guesswork.

## Operating Context

Android 9 or later, ARM64 phones and x86-64 emulators. The phone runs the engine,
extracts media through GeckoView with uBlock Origin and AdGuard, and serves or transcodes video in a foreground
service. No computer, NAS, root, or remote server is required during use.

## Capabilities and Constraints

- Webpage extraction, direct streams and extensionless media downloads; Android URL/video share intents and a native document picker for videos on the phone.
- DLNA, Chromecast, and Roku discovery and explicit receiver addresses. The TV picker first groups the services found at each host into one TV row with IP and ports. Choosing a TV opens its available protocols; selecting one keeps its exact protocol identity and address. A Back button below the protocols returns to the TV list without committing a selection. A saved TV appears only after current discovery confirms it; manual addresses retain their separate entry flow.
- Two playback modes: Native preserves the selected video and audio without re-encoding or automatic stream/rendition changes; an incompatible source is refused with a localized offer to choose Optimized. Optimized adapts the source to the receiver when needed, with a 1080p ceiling. Native remains the default; previous numeric height preferences are shown as Optimized. Both modes explain their behavior in English and Italian.
- Disabled, app-language, or language-specific subtitles; source captions precede transcription fallback.
- Multiple concurrent transmissions to different TVs, each retaining its source, state and independent controls. A visible session list switches the selected transmission; New cast opens the composer without stopping the others. A TV already in use is identified in the picker; its existing transmission can be selected to restart or stop it. The core retains exclusive ownership of each receiver.
- Restart casting sits beside Stop casting. It reloads the original source on the same leased TV without replacing the cast identity, preferences or private headers. Finite video resumes from the complete-video playhead, including a previously prepared source offset; known pause is retained. A failed position read uses the last observed position; when none exists, the video is not silently restarted from zero.
- Only explicitly identified live sources are automatically restarted after more than ten seconds without receiver-clock progress. Pause, unavailable position and new presentations reset observation. Recovery runs in the native engine while the app is backgrounded. Three consecutive stalled restarts end with an explicit failure; thirty seconds of source-clock progress resets that budget. This measures receiver-reported progress, not image similarity or frozen frames with an advancing audio clock.
- Android retains receiver ownership after direct-source handoff, keeping controls and restart while the TV reads the original URL. Other clients retain their existing handoff behavior. Phone-served media and any conversion share the phone's CPU, network and battery across transmissions.
- Active-cast status, pause/resume, playback position, a timeline and ten-second backward/forward controls when the receiver supports them. Holding an arrow repeats seeking until release; unavailable controls remain disabled. Stop, engine restart, and complete copyable errors remain available.
- Local files are read through private HTTP capabilities without copying the whole file. Remote downloads use progressive delivery; startup depends on source range support and container metadata.
- Finite Native Chromecast downloads use retained HLS fragments so the receiver can seek while media arrives; video/audio stay unchanged without re-encoding and earlier fragments remain available within bounded storage. App seeks outside the receiver's available window prepare playback from the requested source position using the same cast identity and device lease. Receivers with pause support hold the picture before seeking or preparing a new portion. Playback resumes only if it was running before the command, and failed preparation restores the old pause state. The app reports positions within the complete video. The TV timer may remain relative to the current portion; its remote cannot request preparation outside that portion. A local seek is confirmed against the receiver's reported position. A control error cancels the current held gesture and does not end the cast; polling restores controls without requiring dismissal of the error.
- English by default, with an immediate, saved choice of English or Italian.
  Interface language changes preserve the draft and running casting service.
- Dark appearance by default, with saved System, Light and Dark choices.
  Appearance changes apply immediately without recreating the screen or casting service. Language and appearance sheets stay open after a selection until the user dismisses them. Playback summaries explicitly identify the subtitle setting.
- An embedded browser remains available during extraction when playback needs
  user interaction. Protected DRM video is unsupported.
- Playback and Control RPCs share the active cast's receiver connection. Playback state comes from the receiver; unknown position or duration stays unknown. Control failures do not end the cast. No volume control is exposed.
- Preserve authenticated local services, certificate verification, the tested
  HLS reader, and the working DLNA handover.

## Brand Commitments

Keep the Castor name and existing beaver asset. The user explicitly requested a
complete replacement of the Android UI/UX with a more convenient, modern design.
On 2026-10-18 the user explicitly pinned Liquid Glass across the Android app,
including the language selector. This supersedes the earlier flat material.

## Evidence on Hand

The user confirmed Native HDR playback is smooth on OPPO/LG C2 with test.4.
Test.5 restarted on seek; the failure was reproduced in Chromium with
unknown-length progressive MP4. Retained HLS passed synthetic seeking, but
test.6 exposed a distinct limit: large seeks exceeded the receiver's reported
HLS window, and an error stopped state polling. The user reported a TV timer
near 54 minutes; a later read-only receiver query reported a window ending
near 11 minutes. The timer alone did not establish the source position reached.
Test.7 uses source-offset preparation outside that window and recovers controls
after errors. Focused Go race gates and eight Android control tests passed,
along with 31 JVM tests and lint with zero errors. ARM64 FFmpeg/FFprobe remuxed
the user's source from 54 minutes while retaining 4K HEVC Main10/HDR and
six-channel AC3; packet seeks were checked with segment/keyframe rounding.
The user confirmed correct quality and working forward/backward seeks and
controls with test.7 on LG C2, while reporting delayed stopping and one transient
return to the previous portion before the correct position. Two failing
regressions demonstrated that playback continued during source preparation and
receiver-seek fallback. Test.8 holds the picture before either operation and
restores the previous pause state on failure. Focused Go race gates, 31 JVM
tests and nine emulator tests, including a native-engine fixture, passed.
The user confirmed small seeks work in test.8, but a large seek near 80 minutes
left LG C2 buffering at zero even with the full new portion available. A direct
one-second recovery seek resumed PLAYING and an advancing clock on that same
receiver/session. Test.9 waits for finite HLS playback to start and applies that
recovery once when buffering at zero; a bounded failure preserves error reporting
and the previous publication for rollback. The recovery may start one second into
the portion. Test.9 then refused initial playback with "the receiver became idle
before HLS playback started": its readiness check incorrectly rejected all IDLE
states. Google Cast documents initial IDLE without an idle reason, including
extended LOADING, as normal startup. Test.10 waits through those states and
refreshes the new media-session identity; explicit terminal reasons still fail.
Four regression cases reproduced the exact refusal before correction, including
a session ID assigned after the LOAD response. On LG C2, test.10 then reported
IDLE without an idle reason, BUFFERING and PLAYING at initial startup. A source
seek to 53m32.926s recovered automatically from buffering at zero and reached
PLAYING; a later receiver read showed 25 seconds into that portion and 3840×1608.
The user then confirmed much better seeking; logs reached PLAYING after source
positions 1h25m25.622s, 1h38m38.369s and a backward seek to 32m54.107s. Pressing
Pause during a later seek caused cancellation and a stuck control transition.
Two Android regressions reproduced the enabled button and cancelled request in
test.10. Test.11 uses one derived pause-availability rule in the UI and ViewModel
so Pause returns only once the seek has finished.
The same build permits `about:srcdoc` subframes required by inline verification
widgets while preserving popup and ad blocking. The user reports TorBox's
Cloudflare check succeeds in Firefox on the same phone; completion inside the
embedded browser requires a separate public-site check, not just fixture success.
Eight earlier native
synthetic captures cover
phone/tablet, both themes, Italian and enlarged Compose text. No real media
catalog or artwork is supplied.

## Product Principles

- Make the video, television, and primary action immediately findable.
- Keep advanced controls available without making every cast a long form.
- State progress honestly; distinguish an active attempt from confirmed playback.
- Preserve working casting behavior while improving the client experience.
