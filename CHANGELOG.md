# Changelog

## [Unreleased]

### Changed
- Replaced tag-triggered release rebuilds with a build-once signed candidate flow. Maintainers now test the exact APK bytes later promoted to GitHub Releases, with source-run provenance, certificate, version, file-size, and SHA-256 verification before and after upload.

## [0.8.31] - 2026-08-27

### Added
- Added limited import support for public Telegram post links shared or pasted into SurfSave. Posts whose anonymous Telegram web preview exposes direct video media can reuse the existing playback, media-detail, and download queue flows.
- Added a Telegram post summary state with channel, description, media counts, poster-only handling, and an explicit action to open the post in Telegram.
- Added page-metadata-aware media ranking and duration labels so the likely main video is easier to find on pages with many detected requests.

### Changed
- Consolidated the home-page paste action and usage guide into a single compact post-or-video link panel.
- Kept one-shot media imports isolated from normal browser tabs and discarded stale resolver results after navigation.

### Fixed
- Preserved stable Telegram post URLs for download-time re-extraction instead of persisting temporary CDN URLs and request headers in the queue.
- Added a clear web-playback action when a page exposes protected media state but no transferable media URL.

### Limitations
- Telegram import is intentionally anonymous and only works when Telegram exposes media through its public web preview. Posts that show only “Please open Telegram to view this post” cannot be played or downloaded by SurfSave and must be opened in Telegram.

## [0.8.30] - 2026-08-24

### Added
- Added a split online-play control for detected web media: the main action uses the current default player, while the menu can add installed Android players through the system chooser, remember them, switch defaults, and remove remembered external targets.
- Added a session-level recently closed tab history with a dismissible undo prompt and a long-press recovery entry from empty space in the tab overview.
- Added broader discovery for extensionless HLS/DASH manifests and media URLs exposed through fetch/XHR, performance entries, JSON responses, and service workers, with bounded inspection limits.

### Changed
- Reduced dynamic-page translation cost with per-document language reuse, bounded ambiguous-node detection, visible-content priority, translation caches, character budgets, and observer self-change filtering.
- Ordered the video library by the best available added/modified timestamp so newly downloaded media appears first.

### Fixed
- Kept automatic page translation active across navigation and dynamic page updates, translated mixed-language content even when the page declares the target language, and prevented stale translation tasks from suppressing a new page.
- Restored native-player video surfaces and playback intent after backgrounding, added a task-root return fallback, and kept browser fullscreen video attached while the app is temporarily backgrounded.
- Paused media in inactive browser tabs, fixed media-detail back handling and first-page tab return behavior, and resolved image-anchor popups to their destination link instead of opening the cover image.
- Preserved detected media type and format-specific request headers through online playback, refreshed stale 401/403 sources with the original detection engine, and selected explicit HLS/DASH handling for extensionless URLs.
- Expanded HEVC/Dolby Vision codec recognition for HLS/DASH/live merges and enabled decoder fallback so supported devices choose a compatible decoder more reliably.
- Quantized the persisted detection threshold before binding it to the discrete Material Slider, preventing legacy settings from crashing the Settings screen.
- Corrected indeterminate download progress detection and avoided rendering `0 B / 0 B` when the total size is unknown.
- Prevented display titles such as `Minecraft 1.20.4` from losing their final numeric segment; known media extensions and duplicate extensions are still removed.
- Prevented duplicate Slider listeners and corrected candidate-card stroke widths so resource dimensions are not converted from dp twice.
- Removed the browser download-ring observer when the WebView view is destroyed.
- Made text-appearance line heights compatible with Android 7-8 and scalable with the user's font size.

### Release
- Refreshed the README screenshot gallery with the current SurfSave interface.
- Increased the release base version code to keep ABI split APK upgrades monotonic.
- CI now resolves official Gradle repositories before mirrors and marks tag builds as the latest stable release.
- Formalized GitHub Release signing with early secret/alias/version-code validation and post-build APK certificate verification pinned to SurfSave's historical signing identity.

## [0.8.29] - 2026-08-20

### Added
- Signature motion: aggregate download progress ring around the detection FAB (`fab_progress_ring`), badge pop-in on video detection, retained FAB pulse on can-download transitions.
- Procedural empty-state art (`surf_empty_state_art.xml`, `surf_video_placeholder.xml`) with localized title/subtitle and a "browse the web" CTA on Progress and Video pages.
- Localized video-kind chips (HLS / DASH / playlist / file), dynamic download button label showing the selected quality, and localized download-status lines (`download_status_*`).
- New helpers: `DisplayNameFormatter` (display-only filename cleanup) and `ProgressTextHumanizer` (localized progress lines).

### Changed
- Dark palette rebuilt as a carbon blue-grey stage ramp; capsule (999dp) becomes the product shape signature across buttons, search fields, chips, switches, sliders and progress bars.
- "Video found" modal dialog + Toast replaced by a Snackbar with a View action (respects the existing alert setting).
- Detection sheet restyled (sheet surface, drag handle tokens, host moved to a subtitle row, badge contrast fixed).
- Candidate format cards: selection state now fully driven by data binding (background/stroke), runtime `setCardBackgroundColor` removed.
- Progress list: old ProgressBar replaced by `LinearProgressIndicator` (with indeterminate state), error rows show the localized compact error line in error color.
- Video library: 16:9 center-crop thumbnails with rounded thumbnail shape and gradient placeholder; names shown humanized.
- Player: top bar uses a scrim gradient, control chips are translucent capsules, loading indicator is M3 circular, seek preview margin tokenized.
- Settings: all five SeekBars converted to M3 Sliders (detection threshold quantized 0..100% of the 50 MB cap to avoid float precision loss); hardcoded switch thumb/track tints removed in favor of `Widget.Surf.Switch`.
- Browser home: tabs/bookmark quick cards merged into one two-row card; search omnibox is now a capsule with a primary focus ring; toolbar/badge contrast tokens applied.
- Legacy `sx_*` drawables reskinned in place onto M3 tokens; six unreferenced duplicate `surf_*` drawables removed.

### Fixed
- Fresh installs without the legacy companion app no longer auto-open the migration center.


### Changed
- Refreshed the design system foundation with a "Clean Tool" Material 3 direction:
  - Added comprehensive M3 semantic color tokens in `values/colors.xml` and `values-night/colors.xml` (`colorSurface*`, `colorOnSurface*`, `colorOutline*`, `colorSecondary*`, `colorTertiary*`, `colorScrim`, etc.).
  - Added component-specific tokens for player controls, badges, and switches.
  - Expanded `dimens.xml` with component-height tokens (app bar, bottom nav, buttons, list items, text fields, chips, sliders).
  - Rewrote `values/styles.xml` to wire M3 semantic colors into `Base.AppTheme` and set default styles for buttons, cards, switches, checkboxes, radio buttons, sliders, text inputs, and bottom sheets.
  - Expanded `TextAppearance.Surf.*` and `Widget.Surf.*` families to cover the full typography and component scale.
- Added a shared component library (Phase 3):
  - New drawable resources for app bar, bottom nav, search field, chip, thumbnail, badge, sheet handle, and player scrims.
  - New reusable layouts: `widget_surf_toolbar`, `widget_surf_list_item_one_line/two_line/three_line`, `widget_surf_empty_state`, `widget_surf_loading_state`, `widget_surf_error_state`, `widget_surf_setting_row_switch/navigation/slider`.
  - New data binding adapters for click listeners, image sources, and slider change listeners.
- Created `PROJECTWIKI.md` as the single source of truth for project knowledge.

### Deprecated
- Legacy color references (`@color/white`, `@color/black_*`, `@color/color_gray`) are kept for backward compatibility but will be phased out during the UI refresh.

## 0.8.26 - Open-source preparation

- Rebranded the app identity to SurfSave with application ID `com.surfsave.browser`.
- Removed the legacy bridge flavor from the build.
- Improved browser and detection UX: primary FAB styling, detection feedback, default format selection, and clearer single-video behavior.
- Added download queue improvements: configurable concurrency, reorder/later actions, duplicate detection, independent notifications, per-task logs, and richer error details.
- Added playlist/batch parsing for supported yt-dlp URLs.
- Added cookie profile import/export.
- Added filename templates.
- Improved backup/restore controls.
- Added Google as a search engine option alongside Bing, Baidu, and DuckDuckGo.
- Added release-readiness documentation, privacy/security guidance, third-party notices, and public CI cleanup.

## Verification

Latest release-prep verification command:

```powershell
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic
```
