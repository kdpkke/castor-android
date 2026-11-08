---
version: 1
slug: "hub-stupside-castor-ui-main-mainscreen-kt-e7e7bf1a"
primary_target: "android/app/src/main/java/io/github/stupside/castor/ui/main/MainScreen.kt"
related_targets: ["android/app/src/main/java/io/github/stupside/castor/theme/Theme.kt","android/app/src/main/java/io/github/stupside/castor/MainActivity.kt","android/app/src/main/java/io/github/stupside/castor/ui/main/PlaybackControls.kt"]
---

# Android casting

Mode: Operate. Scope: the Android client, including ready, receiver selection,
playback preferences, extraction, active cast, errors, and permissions.

## Direction contract

THESIS: Move a real video link from phone to television with two obvious choices
and one reachable action, replacing the long technical setup form.

OWN-WORLD: Liquid Glass, explicitly requested by the user on 2026-10-18.
A continuous aqua, blue and warm-sand backplate carries translucent optical
surfaces, bright specular rims, pill controls, rounded floating panels and
clear ink. Android Compose records only the scenic backplate for blur; text
and controls remain sharp. Android 12+ uses the native blur render effect;
Android 9–11 retains the same material without blur. Dark mode uses deep
blue-green glass, not a light-mode inversion. No new UI dependency.
The user preference supersedes the old flat Laguna material and any skill
default discouraging glass or large material radii.
STORY: Paste or share a link, select a television, optionally adjust playback,
then cast. See honest connection/extraction stages and stop without hunting.

FIRST VIEWPORT: Compact Castor app bar, a clear task heading, one prominent URL
field with paste and clear, a receiver row, compact playback summary and a fixed
bottom action. Advanced inputs stay in focused native sheets. The signature is
the persistent source-to-TV pairing, promoted into the active transmission view.

FORM: Native Android Operate, code-led. The user pinned Liquid Glass across
the app after rejecting the native AlertDialog language picker. Language
selection is now a native modal bottom sheet with immediate choice and a
large checkmarked selected surface. All menu, input, receiver, playback,
error, active-cast and permission surfaces share the same material.
No new aesthetic selection is needed; existing casting affordances and
Android Back, ripple, IME, selection semantics and font scaling remain.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Constraints: keep existing casting RPCs and native engine behavior. Do not add
unsupported seek, pause, volume or invented playback-position controls. Preserve
manual receiver entry, all subtitle/height values, share input, restart, stop,
interactive extraction, complete copyable errors, dark mode and font scaling.

## Source composer revision — 2026-10-18

The user rejected the nested source card (attached screenshot) and requested
modern source entry plus Native quality. Keep the Liquid Glass world. Replace
the nested card with an exterior label/type toolbar, one uninterrupted glass
input, then paste and a short source-kind label. No rhetorical question,
floating outline or repeated format list around the same link. Preserve native
editing, visible invalid-input guidance, paste/clear and source-type overrides.
Native quality is selectable beside existing height limits, preserves selected
source resolution through the existing high-ceiling request, and explicitly
allows device-required re-encoding; it is not a promise of bit-identical video.
Existing saved quality and the 1080p default remain unchanged.

## Appearance selection — 2026-10-18

Add System, Light and Dark under the app menu's Appearance entry. Dark is the
default for both new and upgraded installs with no saved theme. Selection is
immediate and saved; System tracks device night-mode changes. Keep the established
light/dark Liquid Glass palettes. Reuse the language chooser's native radio rows,
checkmark, scrolling glass sheet and close affordance. Apply the selection without
recreating the Activity, losing the draft or disturbing the current cast. Status
and navigation bar foregrounds must follow the effective app theme. Localize EN/IT.

## Preference interaction revision — 2026-10-18

Language and appearance choices apply immediately and keep the current sheet open.
Saved sheet state survives language-triggered Activity recreation, so another
language can be selected without reopening the chooser. Close, Android Back and
native dismissal remain user actions. The playback summary names subtitles
explicitly in English and Italian instead of presenting an unexplained Off value.


## Playback controls — 2026-11-02

THESIS: Control the current TV video directly from its existing active pairing panel, with real receiver state and one reachable stop action.
OWN-WORLD: Inherit Liquid Glass, Material type roles, petrol emphasis, the scenic-only blur and both existing color schemes. No new visual system or dependency.
STORY: See the actual playhead and duration; pause or resume, drag the timeline, or move ten seconds in either direction. Hold a seek button to keep moving, release to stop repeating. Unavailable receiver controls stay disabled; no invented playback clock.
FIRST VIEWPORT: Keep the television and source together. Below their divider, a native timeline and time labels sit above a centered three-control row: a circular reverse arrow with 10 inside, a larger pause/play circle, then the matching forward-10 arrow. One short localized hold hint follows; Stop casting remains fixed below.
FORM: Operate, code-led local extension, precisely specified by the user's supplied circular-arrow reference. Native combined click/long-click semantics, press ripple, cancellation and font scaling; new generated Playback/Control RPCs bind to the current leased receiver. Volume is outside this request.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

This explicit request extends the earlier constraint on unsupported controls: pause, position and seeking now require real backend support, not decorative buttons. Preserve the existing cast when a control fails. Poll only while the interface is visible. Coalesce repeated seek requests instead of queuing stale targets. Native quality, active source/receiver ownership and language/theme continuity remain intact.
