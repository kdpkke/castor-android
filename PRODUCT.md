# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Scope

This product record describes the native app in `android/`. The existing desktop
app and Go services remain outside this interface redesign.

## Users

People sending a video link from their phone to a television on the same Wi-Fi.
The confirmed device pair is an OPPO Find X8 Pro and an LG G6 2026.

## Product Purpose

Turn a shared or pasted webpage or direct video URL into a television cast.
Success means choosing the video and receiver quickly, understanding connection
progress, and stopping or recovering from failure without technical guesswork.

## Operating Context

Android 9 or later, ARM64 phones and x86-64 emulators. The phone runs the engine,
extracts media through GeckoView with uBlock Origin and AdGuard, and serves or transcodes video in a foreground
service. No computer, NAS, root, or remote server is required during use.

## Capabilities and Constraints

- Webpage extraction and direct-stream input; Android share intents.
- DLNA, Chromecast, and Roku discovery and explicit receiver addresses.
- Native quality retains the selected stream’s resolution; device compatibility may still require re-encoding. It is the default for new installations; existing height limits and saved settings remain available.
- Disabled, app-language, or language-specific subtitles; source captions precede transcription fallback.
- Active-cast status, stop, engine restart, and complete copyable errors.
- Casting from the Castor extension for Firefox on the same phone, after the
  owner allows that browser from a notification; permissions can be removed
  from the menu.
- English by default, with an immediate, saved choice of English or Italian.
  Interface language changes preserve the draft and running casting service.
- Dark appearance by default, with saved System, Light and Dark choices.
  Appearance changes apply immediately without recreating the screen or casting service. Language and appearance sheets stay open after a selection until the user dismisses them. Playback summaries explicitly identify the subtitle setting.
- An embedded browser remains available during extraction when playback needs
  user interaction. Protected DRM video is unsupported.
- Current public casting RPCs expose start, status, and stop, not seeking,
  playback position, pause, or volume. Do not invent those controls.
- Preserve authenticated local services, certificate verification, the tested
  HLS reader, and the working DLNA handover.

## Brand Commitments

Keep the Castor name and existing beaver asset. The user explicitly requested a
complete replacement of the Android UI/UX with a more convenient, modern design.
On 2026-10-18 the user explicitly pinned Liquid Glass across the Android app,
including the language selector. This supersedes the earlier flat material.

## Evidence on Hand

The user confirmed APK 4 works on the physical OPPO/LG pair. The previous Android
17/16 KB emulator suite passed eight tests, including casting, stop, TLS, and
copying a complete error. No real media catalog or artwork is supplied.

## Product Principles

- Make the video, television, and primary action immediately findable.
- Keep advanced controls available without making every cast a long form.
- State progress honestly; distinguish an active attempt from confirmed playback.
- Preserve working casting behavior while improving the client experience.
