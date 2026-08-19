# SurfSave

<p align="center">
  <img src="images/icon.png" width="96" alt="SurfSave">
</p>

<p align="center"><strong>Android 视频下载浏览器 —— 内置浏览器、实时媒体检测、多引擎下载、画中画播放。</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84.svg">
  <a href="https://github.com/songsongshuo785-art/SurfSave/releases"><img alt="Release" src="https://img.shields.io/github/v/release/songsongshuo785-art/SurfSave.svg"></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-E63946.svg">
</p>

<p align="center"><a href="README.md">English</a> | <b>简体中文</b></p>

> ⚠️ <strong>免责声明</strong>：本项目仅用于个人、研究、互操作及教育目的。使用者需自行遵守各网站的服务条款、版权法及当地法规，并承担相应责任。

## 使用流程

1. 在 SurfSave 中打开网页，或从其他应用分享链接。
2. 应用检测到符合条件的媒体请求后，打开媒体详情。
3. 在线预览并选择清晰度，然后加入下载队列。
4. 在视频库中管理和播放已下载媒体，也可使用画中画。

## 功能

### 🌐 浏览器
- 内置完整浏览器：书签、历史、Cookie、多标签、标签概览
- **自动媒体检测**：浏览时自动识别符合条件的媒体请求
- 多种搜索引擎：Google、Bing、百度、DuckDuckGo
- 长按链接可快速选择「本窗口 / 新窗口 / 后台打开」
- 页面语言识别与 ML Kit 翻译；首次使用某种语言时可能需要下载翻译模型

### ⬇️ 下载
- **多引擎下载**：普通媒体直链、HLS/DASH/直播流和 yt-dlp 页面解析由不同引擎处理，应用会根据媒体类型自动选择
- **下载队列**：可调并发数、任务排序、稍后下载、重复检测、每任务日志、详细错误信息
- 播放列表 / 批量解析（yt-dlp URL）
- Cookie 配置文件导入导出（登录站点下载）
- 文件名模板（自定义命名规则）
- 支持 HLS AES-128 加密，包括密钥轮换和 IV 处理

### ▶️ 播放器
- 内置离线播放器
- **画中画（PiP）**：支持手动进入，也可配置按 Home 自动进入；小窗内可播放/暂停/快退/快进
- **手势控制**：双击左/右半屏快退/快进；水平滑动 seek（带缩略图预览）
- 音轨 / 字幕切换
- 倍速播放、画面比例控制（适应 / 填满 / 裁切）
- 缩略图 → 播放器的共享元素过渡动画

### 🔒 隐私与网络
- 可选 **Xray/libv2ray 代理**支持
- **安全 DNS / DoH**
- 应用数据与设置的备份 / 恢复（可在设置中按需配置）

## 支持范围与限制

- 不支持下载 DRM/Widevine 保护的媒体；应用中的 DRM 权限仅影响网页内播放。
- 网站结构、接口或登录策略变化后，媒体检测和 yt-dlp 解析可能暂时失效。
- 登录后可见的内容可能需要 WebView Cookie 或手动导入 Cookie 配置。
- 直播下载属于从开始任务时进行实时捕获，不能获取此前已经播出的内容。
- 在线预览是否成功取决于网络状态、媒体编码和设备解码能力。

## 下载与安装

- **下载 APK**：[GitHub Releases](https://github.com/songsongshuo785-art/SurfSave/releases)
- **当前源码版本**：`v0.8.29`
- **系统要求**：Android 7.0+（API 24）

### 选择哪个 APK？

同一版本按手机 CPU 架构分成了几个包，按设备选择即可：

| APK | 适用设备 |
| --- | --- |
| `arm64-v8a` | 绝大多数现代手机（推荐） |
| `armeabi-v7a` | 老款 32 位 ARM 手机 |
| `x86_64` | 64 位 Android 模拟器或少数 Intel Android 设备 |
| `x86` | 老式 32 位模拟器 |
| `universal` | 通用包（体积最大，一般不需要） |

### 安装步骤

1. 在手机「设置 → 安全」中允许安装未知来源应用（针对你的文件管理器）
2. 打开下载的 APK 文件，按提示安装
3. 从相同发布来源下载且签名一致时，可直接覆盖安装并保留应用数据。若希望保留数据，请勿先卸载旧版本。

> 仅供个人学习与研究使用，请遵守相关网站条款与版权法规。

## 截图

<p align="center">
  <img src="screenshots/screenshot_1.png" width="170" alt="浏览与检测">
  <img src="screenshots/screenshot_2.png" width="170" alt="媒体详情">
  <img src="screenshots/screenshot_3.png" width="170" alt="下载队列">
  <img src="screenshots/screenshot_4.png" width="170" alt="播放器">
  <img src="screenshots/screenshot_5.png" width="170" alt="视频库">
  <img src="screenshots/screenshot_6.png" width="170" alt="画中画">
  <img src="screenshots/screenshot_7.png" width="170" alt="设置">
  <img src="screenshots/screenshot_8.png" width="340" alt="横屏播放">
</p>

> 截图可能来自早期版本，界面以当前 Release 实际为准。

## 从源码构建

环境要求：JDK 21、Android SDK、NDK `27.3.13750724`、Go（构建 Xray 代理库）。

```powershell
# 快速诊断构建（跳过 Go/Xray 重新编译）
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic

# 完整导出 diagnostic APK（含内置原生代理库；用于测试，非正式签名发布包）
.\gradlew.bat --console=plain exportDiagnosticApks

# 若 go 不在 PATH 中
.\gradlew.bat --console=plain -PGO_EXECUTABLE=C:\Go\bin\go.exe exportDiagnosticApks
```

> **Release 签名**：读取 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

> **项目标识**：应用名 `SurfSave`，applicationId `com.surfsave.browser`，Kotlin namespace `com.myAllVideoBrowser`。

## 文档

[隐私说明](PRIVACY.md) · [安全说明](SECURITY.md) · [贡献指南](CONTRIBUTING.md) · [更新日志](CHANGELOG.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

## 致谢

感谢 **youtube-dl-android**、**yt-dlp**、**FFmpeg/FFmpegKit**、**Xray/libv2ray**、AndroidX、Material Components、OkHttp、Room、Dagger、RxJava、Media3、ML Kit 及其他开源项目的维护者与贡献者（完整列表见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。

参考项目：[cuongpm/youtube-dl-android](https://github.com/cuongpm/youtube-dl-android)、[yausername/youtubedl-android](https://github.com/yausername/youtubedl-android)、[JunkFood02/Seal](https://github.com/JunkFood02/Seal)。

## 许可证

GNU General Public License v3.0。详见 [LICENSE](LICENSE)。
