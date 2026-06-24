# SurfSave

<p align="center"><strong>Android 视频下载浏览器 —— 内置浏览器、实时媒体检测、多引擎下载、画中画播放。</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84.svg">
  <a href="https://github.com/songsongshuo785-art/SurfSave/releases"><img alt="Release" src="https://img.shields.io/github/v/release/songsongshuo785-art/SurfSave.svg"></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-E63946.svg">
</p>

<p align="center"><a href="README.md">English</a> | <b>简体中文</b></p>

> ⚠️ <strong>免责声明</strong>：本项目仅用于个人、研究、互操作及教育目的。使用者需遵守各网站条款、版权法及当地法规，责任自负。

## 截图

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

## 为什么选 SurfSave

浏览、检测、下载、观看四合一 —— 浏览网页时实时检测页面内的视频，一键下载，再用画中画继续看，全程不离开当前操作。

## 特性

### 🌐 浏览器
- 内置浏览器：书签、历史、Cookie、搜索引擎、分享入口
- **实时检测网页内视频**
- **网页内全屏视频播放**
- 多标签 + 标签概览

### ⬇️ 下载
- **多引擎**：直链、HLS/M3U8、DASH/MPD、直播流、自定义、yt-dlp
- **下载队列**：可配置并发、重排、"稍后"、去重、单任务日志、详细错误信息
- yt-dlp URL 播放列表与批量解析
- Cookie 配置（导入/导出），支持需登录的下载
- 文件名模板，精细命名

### ▶️ 播放器
- 集成离线播放器
- **画中画（按 Home 自动进入 + 手动）**
- **手势控制** —— 左右双击快退/快进
- 音轨 / 字幕切换
- 播放速度 & 画面比例
- **缩略图到播放器的共享元素丝滑过渡**

### 🔒 隐私与网络
- 可选 **Xray/libv2ray 代理**
- **安全 DNS / DoH**
- 网页翻译 & 语言检测
- 应用数据与设置的备份 / 恢复

## 下载与安装

- **最新 APK**：[Releases v0.8.27](https://github.com/songsongshuo785-art/SurfSave/releases) —— `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`、`universal`
- **系统要求**：Android 7.0+（API 24）
- **安装**：在文件管理器开启"允许安装未知应用" → 打开 APK。覆盖安装会保留数据（无需先卸载）。
- **使用场景**：仅限个人 / 研究。请尊重版权与网站条款。

## 从源码构建

前置要求：JDK 21、Android SDK、NDK `27.3.13750724`、Go（用于 Xray 代理库）。

```powershell
# 快速诊断构建（跳过 Go/Xray 重编译）
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic

# 完整 release 风格 APK（含原生代理库）
.\gradlew.bat --console=plain exportDiagnosticApks

# 若 go 不在 PATH
.\gradlew.bat --console=plain -PGO_EXECUTABLE=C:\Go\bin\go.exe exportDiagnosticApks
```

Release 签名读取：`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

> **项目标识**：应用名 `SurfSave`，applicationId `com.surfsave.browser`，Kotlin namespace `com.myAllVideoBrowser`。namespace 仍为最初的内部包名；小型功能开发中请勿改名（属单独的迁移工作）。

## 文档

[隐私](PRIVACY.md) · [安全](SECURITY.md) · [贡献](CONTRIBUTING.md) · [更新日志](CHANGELOG.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

## 鸣谢

感谢 **youtube-dl-android**、**yt-dlp**、**FFmpeg/FFmpegKit**、**Xray/libv2ray**、AndroidX、Material Components、OkHttp、Room、Dagger、RxJava、Media3、ML Kit 的维护者与贡献者，以及 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中列出的其他开源项目。

灵感来源：[cuongpm/youtube-dl-android](https://github.com/cuongpm/youtube-dl-android)、[yausername/youtubedl-android](https://github.com/yausername/youtubedl-android)、[JunkFood02/Seal](https://github.com/JunkFood02/Seal)。

## 许可证

GNU General Public License v3.0，见 [LICENSE](LICENSE)。
