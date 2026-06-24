# SurfSave

<p align="center"><strong>Android video downloader with a built-in browser, realtime media detection, multi-engine downloads, and Picture-in-Picture playback.</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84.svg">
  <a href="https://github.com/songsongshuo785-art/SurfSave/releases"><img alt="Release" src="https://img.shields.io/github/v/release/songsongshuo785-art/SurfSave.svg"></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-E63946.svg">
</p>

<p align="center"><b>English</b> | <a href="README_ZH.md">简体中文</a></p>

> ⚠️ <strong>Disclaimer</strong>: This project is intended for personal, research, interoperability, and educational use. Users are responsible for respecting website terms, copyright law, and local regulations.

## Screenshots

<p float="left">
  <img src="screenshots/screenshot_1.png" width="170">
  <img src="screenshots/screenshot_2.png" width="170">
  <img src="screenshots/screenshot_3.png" width="170">
  <img src="screenshots/screenshot_4.png" width="170">
  <img src="screenshots/screenshot_5.png" width="170">
  <img src="screenshots/screenshot_6.png" width="170">
  <img src="screenshots/screenshot_7.png" width="170">
  <img src="screenshots/screenshot_8.png" width="340">
</p>

## Why SurfSave

Browse, detect, download, and watch in one app — realtime in-page video detection catches media as you browse, one tap to download, then keep watching in Picture-in-Picture without leaving what you're doing.

## Features

### 🌐 Browser
- Built-in browser: bookmarks, history, cookies, search engines, share-target
- **Realtime in-page video detection**
- **Fullscreen video playback** in the webview
- Multi-tab with tab overview

### ⬇️ Download
- **Multi-engine**: direct media, HLS/M3U8, DASH/MPD, live streams, custom, and yt-dlp
- **Download queue**: configurable concurrency, reorder, "later", duplicate detection, per-task logs, rich error details
- Playlist & batch parsing for yt-dlp URLs
- Cookie profiles (import/export) for authenticated downloads
- Filename templates for advanced naming

### ▶️ Player
- Integrated offline player
- **Picture-in-Picture** (auto on Home + manual)
- **Gesture controls** — double-tap left/right to seek
- Audio / subtitle track selection
- Playback speed & aspect ratio control
- **Smooth shared-element transition** from thumbnail to player

### 🔒 Privacy & Network
- Optional **Xray/libv2ray proxy** support
- **Secure DNS / DoH**
- Page translation & language detection
- Backup & restore for app data and selected settings

## Download & Install

- **Latest APK**: [Releases v0.8.27](https://github.com/songsongshuo785-art/SurfSave/releases) — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, `universal`
- **Requirements**: Android 7.0+ (API 24)
- **Install**: Enable "Install unknown apps" for your file manager → open the APK. Overlay-install preserves your data (no uninstall needed).
- **Use case**: Personal/research only. Respect copyright and site terms.

## Build from source

Prerequisites: JDK 21, Android SDK, NDK `27.3.13750724`, Go (for the Xray proxy library).

```powershell
# Fast diagnostic build (skips Go/Xray rebuild)
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic

# Full release-style APK with bundled native proxy library
.\gradlew.bat --console=plain exportDiagnosticApks

# If go is not on PATH
.\gradlew.bat --console=plain -PGO_EXECUTABLE=C:\Go\bin\go.exe exportDiagnosticApks
```

Release signing reads: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

> **Project identity**: App name `SurfSave`, applicationId `com.surfsave.browser`, Kotlin namespace `com.myAllVideoBrowser`. The namespace is the original internal package name; do not rename it in small feature work (that is a separate migration).

## Documentation

[Privacy](PRIVACY.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## Credits

Thanks to the maintainers and contributors of **youtube-dl-android**, **yt-dlp**, **FFmpeg/FFmpegKit**, **Xray/libv2ray**, AndroidX, Material Components, OkHttp, Room, Dagger, RxJava, Media3, ML Kit, and the other open-source projects listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Inspired by [cuongpm/youtube-dl-android](https://github.com/cuongpm/youtube-dl-android), [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android), and [JunkFood02/Seal](https://github.com/JunkFood02/Seal).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
