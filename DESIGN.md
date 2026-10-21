---
name: "Castor Android — Liquid Glass"
description: "Native casting controls in translucent glass over an aqua, blue and sand scenic ground."
colors:
  light-primary: "#075E63"
  light-on-primary: "#FFFFFF"
  light-primary-container: "#075E63"
  light-on-primary-container: "#E7F6F3"
  light-secondary: "#365F63"
  light-on-secondary: "#FFFFFF"
  light-secondary-container: "#D2E8E5"
  light-on-secondary-container: "#183B3E"
  light-background: "#DAECEF"
  light-on-background: "#172E31"
  light-surface: "#FFFFFF"
  light-on-surface: "#172E31"
  light-surface-variant: "#DEEAE8"
  light-on-surface-variant: "#365456"
  light-surface-container-lowest: "#FFFFFF"
  light-surface-container-low: "#F4F9F8"
  light-surface-container: "#EEF5F3"
  light-surface-container-high: "#E0EDEB"
  light-surface-container-highest: "#D3E5E2"
  light-outline: "#6A8585"
  light-outline-variant: "#C3D6D2"
  light-error: "#AA3434"
  light-on-error: "#FFFFFF"
  light-error-container: "#FFE7E3"
  light-on-error-container: "#6A2020"
  dark-primary: "#9AD9D1"
  dark-on-primary: "#00373B"
  dark-primary-container: "#103E42"
  dark-on-primary-container: "#C9EEE7"
  dark-secondary: "#A5CFC9"
  dark-on-secondary: "#143A3D"
  dark-secondary-container: "#24494B"
  dark-on-secondary-container: "#D5EFEA"
  dark-background: "#071B26"
  dark-on-background: "#DCEDE9"
  dark-surface: "#13272A"
  dark-on-surface: "#DCEDE9"
  dark-surface-variant: "#254245"
  dark-on-surface-variant: "#AEC9C5"
  dark-surface-container-lowest: "#0A181A"
  dark-surface-container-low: "#112326"
  dark-surface-container: "#172F32"
  dark-surface-container-high: "#203A3D"
  dark-surface-container-highest: "#29464A"
  dark-outline: "#7B9B97"
  dark-outline-variant: "#375351"
  dark-error: "#FFB4AB"
  dark-on-error: "#630F10"
  dark-error-container: "#552829"
  dark-on-error-container: "#FFDAD5"
  light-scenic-aqua: "#8DDCD6"
  light-scenic-blue: "#B5D3F0"
  light-scenic-sand: "#F4DEC0"
  dark-scenic-aqua: "#125F68"
  dark-scenic-blue: "#173F60"
  dark-scenic-sand: "#514336"
  light-glass-fill: "rgba(255, 255, 255, 0.64)"
  dark-glass-fill: "rgba(19, 39, 42, 0.72)"
  light-accent-glass-fill: "rgba(7, 94, 99, 0.94)"
  dark-accent-glass-fill: "rgba(154, 217, 209, 0.94)"
typography:
  headline-large:
    fontFamily: "Android platform sans-serif"
    fontWeight: 600
    fontSize: "32sp"
    lineHeight: "38sp"
    letterSpacing: "-0.7sp"
  headline-medium:
    fontFamily: "Android platform sans-serif"
    fontWeight: 600
    fontSize: "28sp"
    lineHeight: "34sp"
    letterSpacing: "-0.4sp"
  title-large:
    fontFamily: "Android platform sans-serif"
    fontWeight: 600
    fontSize: "22sp"
    lineHeight: "28sp"
  title-medium:
    fontFamily: "Android platform sans-serif"
    fontWeight: 600
    fontSize: "17sp"
    lineHeight: "24sp"
  body-large:
    fontFamily: "Android platform default"
    fontWeight: 400
    fontSize: "16sp"
    lineHeight: "24sp"
    letterSpacing: "0sp"
  body-medium:
    fontFamily: "Android platform sans-serif"
    fontWeight: 400
    fontSize: "14sp"
    lineHeight: "21sp"
  body-small:
    fontFamily: "Android platform sans-serif"
    fontWeight: 400
    fontSize: "12sp"
    lineHeight: "18sp"
  label-large:
    fontFamily: "Android platform sans-serif"
    fontWeight: 600
    fontSize: "15sp"
    lineHeight: "20sp"
rounded:
  small: "12dp"
  medium: "20dp"
  large: "28dp"
  extra-large: "32dp"
  pill: "50%"
spacing:
  "2": "2dp"
  "4": "4dp"
  "8": "8dp"
  "10": "10dp"
  "12": "12dp"
  "14": "14dp"
  "16": "16dp"
  "18": "18dp"
  "20": "20dp"
  "24": "24dp"
  "28": "28dp"
components:
  cast-action:
    backgroundColor: "{colors.light-accent-glass-fill}"
    textColor: "{colors.light-on-primary}"
    rounded: "{rounded.pill}"
    typography: "{typography.label-large}"
  source-surface:
    backgroundColor: "{colors.light-glass-fill}"
    textColor: "{colors.light-on-surface}"
    rounded: "{rounded.large}"
    padding: "20dp"
  active-pairing:
    backgroundColor: "{colors.light-glass-fill}"
    textColor: "{colors.light-on-surface}"
    rounded: "{rounded.large}"
    padding: "28dp"
  error-panel:
    backgroundColor: "{colors.light-glass-fill}"
    textColor: "{colors.light-on-surface}"
    rounded: "{rounded.large}"
    padding: "20dp"
  input-field:
    backgroundColor: "{colors.light-glass-fill}"
    textColor: "{colors.light-on-surface}"
    rounded: "{rounded.medium}"
  language-selected:
    backgroundColor: "{colors.light-accent-glass-fill}"
    textColor: "{colors.light-on-primary}"
    rounded: "{rounded.medium}"
    padding: "16dp 20dp"
  cast-action-dark:
    backgroundColor: "{colors.dark-accent-glass-fill}"
    textColor: "{colors.dark-on-primary}"
    rounded: "{rounded.pill}"
    typography: "{typography.label-large}"
  source-surface-dark:
    backgroundColor: "{colors.dark-glass-fill}"
    textColor: "{colors.dark-on-surface}"
    rounded: "{rounded.large}"
    padding: "20dp"
  active-pairing-dark:
    backgroundColor: "{colors.dark-glass-fill}"
    textColor: "{colors.dark-on-surface}"
    rounded: "{rounded.large}"
    padding: "28dp"
  error-panel-dark:
    backgroundColor: "{colors.dark-glass-fill}"
    textColor: "{colors.dark-on-surface}"
    rounded: "{rounded.large}"
    padding: "20dp"
  input-field-dark:
    backgroundColor: "{colors.dark-glass-fill}"
    textColor: "{colors.dark-on-surface}"
    rounded: "{rounded.medium}"
  language-selected-dark:
    backgroundColor: "{colors.dark-accent-glass-fill}"
    textColor: "{colors.dark-on-primary}"
    rounded: "{rounded.medium}"
    padding: "16dp 20dp"
---
# Design System: Castor Android

## Overview

**Creative North Star: "Liquid Glass"**

Castor's native Android interface places clear, translucent controls over a continuous aqua, blue and warm-sand scenic field. Light-catching rims and a restrained sheen give the panels depth; petrol accents identify the primary action and selected choices. Text and icons stay sharp. Dark mode uses its own deep teal and blue ground with warm sand beneath the glass.

This is an operating interface: the link, television and next action remain easy to find. The material follows the user's explicit Liquid Glass replacement request, including language and appearance selection, while Material 3 owns native interaction. The scope is `android/`; the desktop interface has its own system. The Castor name, original beaver vector and vector interface icons remain the identity.

**Key Characteristics:**

- One scenic glass material across main content, fields, choices, sheets, status and recovery.
- Sharp native sans typography above a scenic-only blur layer.
- Dark by default, with saved System, Light and Dark choices, petrol emphasis and visible selection.
- Scrollable task content and focused sheets above a reachable casting or stop action.
- Immediate, saved English or Italian choice through wide checkmarked language rows.

This record describes implemented Compose code. Frontmatter owns the explicit palette, type, shape, spacing and component tokens. Android dp and sp are retained; they are not CSS pixel equivalents. Optical composition, native defaults and source digests live in `.impeccable/design.json`.

## Colors

Petrol carries action, sea glass carries light, and warm sand keeps the scenic field from becoming a flat blue wash. Exact palette values belong to the frontmatter.

### Primary

Deep Petrol is the light scheme's primary and primary-container color; its primary foreground is white. Dark mode uses Light Sea Glass for primary emphasis with a deep petrol foreground. Prominent buttons and selected chips or choice rows use the primary color through the accent glass fill. Active casting uses ordinary glass with on-surface content, rather than a solid primary-container block.

### Secondary

Muted Petrol and soft sea-glass secondary-container roles remain available to Material's semantic styling. The scenic aqua, blue and sand tokens are backdrop colors, not alternative text or status colors. They are rendered as overlapping radial fields; they do not encode a selection or casting state.

### Neutral

Pale Water and Deep Blue form the light and dark base grounds. White and Deep Petrol surfaces supply the panel tint; ink and pale mineral foregrounds carry text. The on-surface-variant role carries helper copy on both panels and the scenic ground. The stronger current light secondary foreground is recorded from Theme.kt; the token is not a claim that every rendered contrast case has received final approval. Outline and outline-variant remain semantic separators.

Error colors identify error headings, inline validation and recovery actions on the shared glass panel. Theme error-container pairs remain explicit semantic tokens but the current ErrorPanel does not fill its background with errorContainer.

**The Semantic Pair Rule.** Resolve foregrounds from the current Material scheme; accent glass uses on-primary and ordinary panels use on-surface. Check readable text against the composed scenic ground as well as the panel tint.

## Typography

**Display and body font:** Android platform sans-serif (`FontFamily.SansSerif`, with `FontFamily.Default` for bodyLarge). No bundled font is introduced. The exact physical device font is not asserted.

The frontmatter records eight explicit Material TextStyle overrides. HeadlineLarge gives task and active-state headings a strong entrance; headlineMedium names sheets and receivers. TitleLarge names the app and preference choices, while titleMedium names sections and action rows. BodyLarge, bodyMedium and bodySmall separate explanation, row detail and helper copy. LabelLarge carries button labels. Only the two headline roles specify negative tracking; values omitted from custom TextStyle constructors remain unspecified in code. Other Material roles, including labelMedium in engine status, keep library defaults.

**The Native Type Rule.** Use the implemented Material role hierarchy and scalable sp units. Keep foreground text out of the blurred graphics layer.

## Layout

The activity is edge-to-edge. Scaffold insets protect the main body; the bottom action adds navigation-bar and IME padding. Body and bottom action are centered at a maximum width of 680dp with 24dp horizontal padding. The tablet view keeps this single-column task model. Sheets have their own maximum width of 640dp.

At configuration height below 480dp, the idle introduction is omitted, source-composer gaps contract from 12dp to 8dp, its field minimum height contracts from 88dp to 64dp, and bottom-action vertical padding contracts from 12dp to 8dp. The body scrolls above the bottom action with 20dp group spacing. Receiver and playback action rows use 20dp horizontal and 18dp vertical padding, 16dp icon separation and a 4dp title/detail gap. Their secondary detail allows two lines before ellipsis. Active pairing has 28dp padding and 18dp internal spacing.

The primary cast/stop action has a 56dp minimum height and grows with text. Other GlassButtons and GlassChoices have a 48dp minimum; sheet confirmations raise the minimum to 52dp. Language and appearance choices share a 76dp minimum and source-type choices a 60dp minimum. Native IconButton touch targets surround 24dp icon artwork. FlowRow allows source actions, quality and subtitle options to wrap. The URL field permits up to three lines; invalid-input supporting text expands inside the same glass plane. Its exterior label/type toolbar sits above the input, with paste and the detected source kind below; those actions wrap through FlowRow. Receiver and playback sheets scroll and apply IME padding; language, appearance, source-type and overflow sheets scroll without editable fields.

## Elevation & Depth

`GlassScene` records only its scenic backplate into a GraphicsLayer. On Android 12 and later, that layer receives a clamped BlurEffect with a 24px radius in both axes. `glassMaterial` samples the aligned scene behind each clipped surface, draws its translucent tint and sheen, then adds a 1dp specular gradient rim. It never records text, controls, the WebView or the whole window into the blur. Modal sheets create their own GlassScene and reuse the same material.

Ordinary panels tint the sampled scene with surface at 64% opacity in light mode and 72% in dark mode. Accent surfaces use primary at 94% opacity. The rim moves through white alpha values of 92% (light) or 44% (dark), 8%, then 48%; sheen moves from 22% (ordinary) or 14% (accent), through transparent, to 4%. These are optical composition values rather than new solid palette colors. There are no project-defined drop shadows or added UI dependencies.

Android 9–11 uses the same tint, sheen and rim with no platform blur render effect. Native Material 3 owns button ripple, focus, progress and sheet transitions; no duration, easing or spring constants are overridden. The sheet scrim uses onBackground at 30%, the native partially expanded state is skipped, and a custom small drag handle replaces the default. Screenshots establish appearance, not animation or GPU performance.

**The Scenic-Only Blur Rule.** Record and blur only the scenic backplate. Keep text, controls and media content sharp, and retain the translucent fallback on API 28–30.

## Shapes

The shape scale in frontmatter is used directly by the native theme. Large shapes form GlassPanel containers, including the active pairing and sheet panel. Medium shapes form fields and selectable language, appearance and source rows. GlassButton, GlassChoice, glass icon backgrounds and the sheet handle use a 50% rounded pill. ExtraLarge remains an explicit theme shape without being forced onto the active pairing. ExtraSmall keeps the Material default.

Clipping and the gradient border share the same shape. Large radii and transparency are part of the requested material; they are not defects inherited from the superseded flat direction.

## Components

### Buttons

GlassButton wraps the native Material Button with a transparent container over the shared optical material. The prominent enabled form uses primary tint and on-primary content; the quieter form uses ordinary glass and primary content. Disabled buttons retain the native disabled container at surface 22% and foreground at on-surface 50%, with no accent material. Cast, stop, restart, paste, discovery refresh and sheet confirmation share this wrapper. TextButton remains appropriate for manual entry, source-mode access, details and permission settings. Press, focus and hover where present remain native and distinct from persistent selection.

### Chips

GlassChoice wraps FilterChip with a pill material, no separate chip border and a 48dp minimum. Selected choices use accent glass and on-primary labels; unselected choices use ordinary glass and on-surface labels. These native controls select receiver protocol, maximum video height and subtitle mode. Quality offers Native quality, 720p, 1080p, 2160p and custom height. Native is a separate choice above the wrapping numeric/custom row and uses the same selected material. Its English and Italian helper copy states that the selected stream’s resolution is retained while television compatibility may require re-encoding. The UI and saved preference use `native`; the existing request maps it to `Int.MAX_VALUE` as the height ceiling. Numeric saved settings and the 1080p default remain intact. Subtitle modes are Off, App language or a two- or three-letter language code. The saved `auto` value follows the selected app language (English or Italian). Requests use upstream’s SubtitlePreferences with a preferred language and transcription enabled only as a fallback after source captions.

### Cards / Containers

GlassPanel supplies the shared clipped material with transparent native Surface content. Its clickable form preserves Surface interaction semantics. The single source-input plane, television and playback rows, discovered/saved receivers, active pairing, error panel and app-owned permission recovery all reuse it. The source composer has no enclosing group card: its label/type toolbar and paste/source-kind row remain on the scenic ground around that one input plane. Receiver ListItem backgrounds are transparent so the parent glass remains visible. Error heading and actions use the semantic error foreground; complete diagnostics can expand in SelectionContainer and copy without truncation.

### Inputs / Fields

The source composer uses a native TextField inside one large-shaped GlassPanel plane. Its native container and focused, unfocused and error indicators are transparent, leaving the shared glass material uninterrupted. The exterior Video link label and source-mode control establish context; the field has an accessible input description, URI keyboard, up to three lines and a native clear IconButton. Invalid nonempty HTTP(S) links receive native supportingText inside that same plane, with semantic error treatment and explicit guidance. Paste resets source mode to automatic; an empty clipboard produces Snackbar feedback. A valid URL shows its detected webpage/direct-stream kind beside paste. Source-mode overrides remain available through the native selectable sheet.

Manual receiver, custom height and subtitle-language inputs retain OutlinedTextField with medium shape and glassMaterial. Their focus adds surface at 15% and a primary border; the unfocused native border is transparent, exposing the glass rim, and the error border uses the semantic error color. Manual address requests URI input and custom height requests number input. Invalid height or language disables confirmation; validation does not silently truncate values.

### Navigation

The transparent TopAppBar preserves the original beaver, Castor name, a glass engine-status pill and a glass overflow target. Overflow opens GlassSheet with language, Appearance/Aspetto, engine restart and stop actions. The appearance row uses the theme vector icon and shows the saved choice. The playback row summary names its subtitle value explicitly, such as “Native quality · Subtitles: Off” or “Qualità nativa · Sottotitoli: Disattivati”. Language selection, appearance, source type, receiver selection, manual entry and playback settings all use native modal bottom sheets. Close actions have glass pill backgrounds; manual receiver entry adds an in-sheet Back action. Native Android Back and native sheet gestures remain available. There is no bottom tab navigation.

### Language choice

AppLanguagePicker uses the shared AppChoiceRow for full-width, medium-shaped selectable rows with radio-group semantics. The chosen language receives accent glass and a checkmark; the endonyms English and Italiano remain visible in either locale. English is the default independently of device language and carries the default subtitle. Selecting a different language saves CastOptions preferences and recreates the localized activity immediately. The open language sheet, saved draft and running casting service are preserved. Changing the choice keeps the sheet open, updates its localized text and checkmark, and permits switching back without reopening it. Closing remains an explicit close, Back or native sheet dismissal action. There is no system-language option and no separate confirmation step.

### Appearance choice

AppThemePicker reuses AppChoiceRow, the same scrolling GlassSheet, close affordance, medium shape, titleLarge/bodySmall hierarchy, native RadioButton semantics and selected accent fill/checkmark as the language chooser. It presents System, Light and Dark in that order (Sistema, Chiaro and Scuro in Italian); Dark carries the Default/Predefinito detail. Selection applies immediately and keeps the sheet open without a separate confirmation. The selected checkmark and the entire page update to the effective theme; the user dismisses the sheet explicitly.

Dark is the default for new and upgraded installs without a saved valid theme. AppTheme owns the `system`, `light` and `dark` preference values, and CastOptions persists the choice. MainActivity remembers the saved enum and updates Compose state immediately without recreating the Activity, discarding the draft or restarting the casting service. Explicit Light and Dark override device night mode; System follows OS configuration changes through isSystemInDarkTheme. Status- and navigation-bar foregrounds follow the effective app scheme. CastorTheme also defaults to AppTheme.Dark; the DesignEvidence fixture explicitly requests AppTheme.System so OS-driven light/dark captures remain deliberate. This preference introduces no palette, typography or optical-material changes.

### Active pairing and recovery

The active panel reads source and television from retained/submitted activeCast metadata; a new incoming draft or saved last-used receiver cannot replace those labels. Missing metadata receives generic source/TV text. Headings announce politely through native semantics. Progress describes connection/extraction stages without claiming confirmed television playback. The reachable primary action becomes Stop casting, or engine restart when unavailable with an error.

Interactive extraction presents the existing site's WebView through PageBrowser, brings it into view, and shows its address, loading state and concise EN/IT instructions. Main-document failures appear explicitly. A no-stream result retains the page for interaction and retry without discarding a Play click; successful extraction and cancellation clear it, and a retained page has a native Close action. Website content keeps its own rendering. The viewport is 360dp in portrait and 220dp in the compact layout.

The receiver sheet shows a confirmed Last used row first, followed by an outline-variant divider and an Other TVs heading using the titleSmall role and on-surface-variant foreground. That receiver is excluded from the following list. An empty other group says no other TVs were found; without a confirmed last-used receiver, discovery results retain their ordinary layout. Labels are localized as Ultima utilizzata and Altre TV in Italian.

App-owned permission recovery shares the scene and glass panel. Android permission prompts, foreground notifications, app settings and embedded website content keep their platform or site presentation. Only app language, appearance, receiver, height and subtitle preferences are stored by CastOptions; the URL draft uses rememberSaveable instance state rather than those preferences.

## Do's and Don'ts

### Do:

- **Do** treat Glass.kt, Theme.kt and Type.kt as the source of truth for shared material, both palettes and native typography.
- **Do** retain native Material interaction, radio selection semantics, visible press feedback, Android Back, IME handling and scalable text.
- **Do** keep the primary casting or stop action reachable while task content and focused sheets scroll.
- **Do** keep English and Italian resources and apply saved language and appearance choices immediately without losing the draft or running service.
- **Do** preserve the Castor name, original beaver vector, vector interface icons and actual active-cast metadata.

### Don't:

- **Don't** blur foreground text, controls or embedded media content to produce the glass material.
- **Don't** reintroduce the superseded opaque Laguna material or language AlertDialog into the shared app surfaces.
- **Don't** invent pause, seek, playback-position or volume controls beyond the current casting RPCs.
- **Don't** present a new draft or saved receiver preference as the source or receiver of an existing cast.
- **Don't** infer physical-device performance, television playback or final visual approval from emulator screenshots or code documentation.

Evidence: the source digests in `.impeccable/design.json` identify the code recorded here. Native screenshot producers, synthetic fixture flags, per-file version codes and hashes are in `.impeccable/review/provenance.json`; Appearance's 13 actual MainActivity captures are also listed in `.impeccable/review/theme-provenance.json`. The current independent finish review issued `ship` for the Appearance extension after opening all 17 required versionCode 10 captures: those 13 theme states plus English, Italian, dark and large-type language states. The theme set includes actual OS night-mode changes, 1.5× system font scale, tablet, landscape and a declared scrolled landscape companion. English/Italian language captures use MainActivity with the Dark default; language-dark and language-large-type are disclosed synthetic Compose fixtures. Example drafts and synthetic active states do not establish real television playback. The master manifest retains 35 PNG records with mixed per-file versions, including earlier versionCode 8 source-composer/Native references; this Appearance review does not certify all 35 as current or visually rereviewed.

Reported validation is 17 selected instrumentation tests, 10 JVM tests and lint with zero errors and 10 warnings; the full 23-test instrumentation suite was not run. Theme coverage checks Dark fallback, persistence/reopen, explicit overrides, System night-mode changes, draft retention and preservation of a synthetic active model. A separate real-engine DLNA fixture passed language changes and Stop; an earlier Native regression reported 1440-pixel FFprobe output from a 1440-pixel source. These are separate from visual review and physical OPPO/LG proof. The evidence set is emulator-only; physical-device playback of this redesign, motion/GPU performance and API 28–30 fallback appearance remain unverified. No HTML/CSS detector ran and no product raster was introduced.


Preference interaction correction (dev.12): the two selection callbacks retain the
saved open sheet. Fourteen focused instrumentation tests pass, including repeated
EN/IT changes, restored open sheets after Activity recreation, theme changes with
immediate selected rows/system bars, manual Close/Back dismissal, and explicit
subtitle summaries in both locales. Three regressions failed on dev.11 first.
Ten JVM tests and lint pass (zero errors, ten warnings). Focused native emulator
inspection confirms the localized sheet, light/dark updates and summary wrapping
at simulated 1.5x Compose font scale. The native runtime is byte-identical to dev.11;
its complete device/Go suites were not rerun for this UI-only correction. Current
home and appearance documentation captures are dev.12 MainActivity fixtures with
synthetic input. This inspection does not replace the historical independent
Appearance review or establish new physical-device evidence.
