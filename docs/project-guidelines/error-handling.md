# Error Handling

记录当前项目的错误处理约定。

迁移到新项目后，至少补充：

- 何时抛异常、何时返回错误结果。
- 错误类型、错误码或响应格式。
- 是否允许 fallback；如果允许，必须如何记录和暴露。
- 禁止吞异常、静默失败或用默认值掩盖真实问题。

## Scenario: User-facing detection and download errors

### 1. Scope / Trigger

- Trigger: 修改视频检测、播放列表解析、下载入队、Worker 终态、下载详情弹窗或用户可见错误文案时必须遵守本节。
- 目标是让用户看到“可能原因 + 下一步建议”，同时保留原始日志用于排错。

### 2. Signatures

- `UserFacingError.classify(error: Throwable?): Category`
- `UserFacingError.classify(rawMessage: String?): Category`
- `UserFacingError.compactMessage(context: Context, rawMessage: String?): String`
- `UserFacingError.panelMessage(context: Context, error: Throwable?): String`
- `UserFacingError.cleanDetail(rawMessage: String?): String`
- `VideoDetectionTabViewModel.detectionStatusText: ObservableField<String>`
- `VideoDetectionTabViewModel.detectionFeedbackEvent: SingleLiveEvent<String>`

### 3. Contracts

- 用户可见错误不得直接展示未处理的 `Throwable.message` 或堆栈；必须先经过 `UserFacingError` 分类、脱敏和长度限制。
- 下载任务的详细排错信息继续走 `ProgressInfo.lastError` 和 `DownloadTaskLogger`，UI 只在详情里显示清洗后的短 detail。
- 检测面板状态区显示当前检测/失败状态；手动点击检测按钮但未检测到视频时，用 `detectionFeedbackEvent` 给轻量 Snackbar。
- 自动后台检测失败不应频繁弹出 Snackbar，避免用户浏览网页时被多个媒体请求打断。

### 4. Validation & Error Matrix

- 网络超时、DNS、SSL、连接失败 -> `NETWORK`，建议检查网络或代理后重试。
- 401/403/login/cookie/authenticated -> `AUTH_OR_COOKIE`，建议在浏览器标签页登录后重试。
- no video/no media/no formats/requested format -> `NO_FORMAT`，建议先播放视频或等页面加载完成。
- yt-dlp/extractor/signature/please update -> `YTDLP_OUTDATED`，建议到设置页手动更新 yt-dlp。
- proxy/vpn/geo/region/country blocked -> `PROXY_OR_REGION`，建议更换代理线路或桌面模式。
- ENOSPC/no space/permission/storage/error moving file -> `STORAGE`，建议释放空间或切换下载目录。
- duplicate/already exists -> `DUPLICATE`，建议查看已有任务或显式重新下载。
- playlist -> `PLAYLIST`，建议直接打开播放列表页面并等待加载完成。
- 未命中分类 -> `UNKNOWN`，建议重试并分享详情日志。

### 5. Good / Base / Bad Cases

- Good: 下载详情先显示“可能原因”和建议，再显示脱敏后的原始错误与最近日志。
- Base: 检测失败只在用户主动点击检测后的短时间窗口内显示 Snackbar，自动检测只更新面板状态。
- Bad: `Toast.makeText(context, e.message, ...)` 直接暴露原始异常、token 或 cookie。
- Bad: `catch (Throwable) {}` 静默吞掉失败，不写日志也不更新用户可见状态。

### 6. Tests Required

- 分类测试：网络、登录/Cookie、yt-dlp 过旧、存储、重复和播放列表关键词映射到正确类别。
- 脱敏测试：`Cookie`、`Authorization`、`token`、`signature` 等敏感值不出现在 `cleanDetail()` 结果中。
- UI 验证：检测面板能显示失败状态；下载详情能显示原因、建议、原始错误和最近日志。

### 7. Wrong vs Correct

#### Wrong

```kotlin
Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
```

#### Correct

```kotlin
val message = UserFacingError.panelMessage(requireContext(), error)
viewModel.showDetectionError(error)
```
