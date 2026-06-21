# Changelog

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
