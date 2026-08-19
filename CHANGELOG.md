# Changelog

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
