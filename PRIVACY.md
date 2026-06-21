# Privacy

SurfSave is designed to run locally on your Android device. The current codebase does not include a project-operated analytics service, telemetry backend, advertising SDK, or account system.

## Data Stored Locally

SurfSave may store the following data on your device:

- Browser history, tabs, bookmarks, favicons, cookies, and WebView state.
- Download records, queue state, filenames, progress, error details, and per-task logs.
- Cookie profiles that you import or export for authenticated downloads.
- App settings such as search engine, proxy, secure DNS, filename template, and theme choices.
- Backup packages that you explicitly export.
- Downloaded media files in the configured download location.

## Network Requests

SurfSave makes network requests when you:

- Browse websites in the built-in browser.
- Detect media on pages you open.
- Download media or stream segments.
- Use yt-dlp backed extraction or update yt-dlp components.
- Use translation, language detection, proxy, or secure DNS features.

Websites, CDNs, DNS providers, proxy endpoints, and third-party services you choose to use may receive normal browser or downloader requests from your device.

## Cookies and Authentication

SurfSave can use WebView cookies and user-managed cookie profiles to access content that requires authentication. Cookie profiles are stored locally and should be treated as sensitive files. Only import cookies that you trust and only export them to locations you control.

## Logs and Backups

Download logs and backup files can contain URLs, filenames, site names, and operational error details. The app redacts common secret fields in task logs, but you should still review logs and backups before sharing them publicly.

## User Control

You can clear browser data, delete downloads, remove cookie profiles, and export/import backups from inside the app. Android system settings can also be used to clear all app data.
