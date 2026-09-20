# Download Queue

本文件记录 SurfSave 下载任务、队列调度、通知、错误日志和历史去重的实现契约。

## Scenario: First-tier download task system

### 1. Scope / Trigger

- Trigger: 修改下载任务创建、暂停、继续、取消、稍后下载、队列顺序、并发限制、下载通知、错误详情、每任务日志或历史去重时必须读取本文件。
- 该链路横跨 UI、ViewModel、Room、WorkManager Worker、通知 Receiver 和本地文件日志，不能只改其中一层。

### 2. Signatures

- `ProgressInfo.queuePosition: Long`
- `ProgressInfo.queuedAt: Long`
- `ProgressInfo.startedAt: Long`
- `ProgressInfo.completedAt: Long`
- `ProgressInfo.downloadFingerprint: String`
- `ProgressInfo.lastError: String`
- `ProgressInfo.logPath: String`
- `ProgressInfo.queuedForLater: Boolean`
- `SharedPrefHelper.getMaxConcurrentDownloads(): Int`，范围 `1..5`，默认 `2`
- `DownloadQueueManager.enqueue(videoInfo: VideoInfo, force: Boolean = false): EnqueueResult`
- `DownloadQueueManager.scheduleNext(): List<ProgressInfo>`
- `DownloadQueueManager.pause/resume/cancel/markLater/moveUp/moveDown/moveToTop(taskId: String)`
- `DownloadQueueManager.onTaskTerminal(taskId: String, taskState: Int, errorMessage: String? = null)`
- `DownloadFingerprint.fromVideoInfo(videoInfo: VideoInfo): String`
- `DownloadTaskLogger.info/warn/error(taskId: String, ...)`

### 3. Contracts

- 所有新下载必须先进入 `DownloadQueueManager.enqueue()`，不得从 UI、Receiver 或新功能直接调用 `CustomRegularDownloader`、`SuperXDownloader`、`YoutubeDlDownloader` 的 start/pause/resume/cancel。
- `VideoTaskState.PENDING` 只表示尚未启动且可调度的队列任务；Worker 启动后只能写 `PREPARE`、`DOWNLOADING`、`PAUSE`、`SUCCESS`、`ERROR`、`ENOSPC` 或 `CANCELED`。
- `scheduleNext()` 用 `PREPARE/START/DOWNLOADING/PROXYREADY` 计算活跃任务数，按 `queuePosition -> queuedAt -> id` 启动排队任务。
- 曾经启动过的任务（`startedAt > 0` 或已有进度）重新排队后必须走 downloader 的 `RESUME` action；从未启动的任务走 `DOWNLOAD` action。
- Worker 终态路径必须在保存最终 `ProgressInfo` 后调用 `DownloadQueueManager.onTaskTerminal()`，否则后台下载不会推进下一项。
- 每个任务日志写入 `files/download_logs/<taskId>.log`，分享前必须经过 `DownloadTaskLogger.redact()` 脱敏。
- 通知 PendingIntent requestCode 必须包含 `taskId + action`，不能复用全局 requestCode。

### 4. Validation & Error Matrix

- `maxConcurrentDownloads < 1` -> 保存时钳制为 `1`。
- `maxConcurrentDownloads > 5` -> 保存时钳制为 `5`。
- 指纹重复且 `force=false` -> 返回 `EnqueueResult.Duplicate`，UI 必须提供查看已有任务或强制重新下载。
- 本地文件名重复但没有历史任务 -> 返回 `EnqueueResult.Rejected(download_duplicate_file)`。
- Worker 失败 -> 写 `lastError`、任务日志和错误通知；错误详情必须能看到最近日志。
- 用户取消任务 -> 调用队列管理器取消并释放槽位；不要只取消 WorkManager 而不更新队列。

### 5. Good / Base / Bad Cases

- Good: 并发数为 `1` 时，第二个任务保持 `PENDING`；第一个 `SUCCESS/ERROR/CANCELED` 后，Worker 调用 `onTaskTerminal()` 自动启动第二个。
- Base: 用户把排队任务设为稍后下载，该任务变为 `PAUSE + queuedForLater=true`，不参与自动调度；点击继续后重新入队。
- Bad: Worker 启动后把任务状态写回 `PENDING`，导致并发计数漏算并启动过多任务。
- Bad: 通知 Receiver 自己判断下载器类型并直接调用 downloader，绕过队列状态和日志。

### 6. Tests Required

- 指纹测试：临时签名参数变化不影响同一任务；不同格式/清晰度不能误判为同一任务；URL 存在时标题变化不影响指纹。
- 日志测试：`Cookie`、`Authorization`、`token`、`signature` 等敏感信息必须被脱敏，非敏感参数保留。
- 构建验证：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。
- 手动验证：设置并发数为 `1`，连续添加两个下载，确认第二个排队；第一个结束后第二个自动开始。

### 7. Wrong vs Correct

#### Wrong

```kotlin
// UI 或 Receiver 直接启动下载，队列和日志都不知道这件事。
YoutubeDlDownloader.startDownload(context, videoInfo)
```

#### Correct

```kotlin
// 统一入口负责去重、入队、并发和日志。
downloadQueueManager.enqueue(videoInfo)
```

#### Wrong

```kotlin
// Worker 已经开始下载，却把状态写回 PENDING。
saveProgress(taskId, Progress(0, 0), VideoTaskState.PENDING)
```

#### Correct

```kotlin
// PENDING 只留给未启动队列项。
saveProgress(taskId, Progress(0, 0), VideoTaskState.PREPARE)
```

## Scenario: Second-tier download integrations

### 1. Scope / Trigger

- Trigger: 新增或修改播放列表批量下载、文件名模板、Cookie profile、yt-dlp 更新入口，或任何会创建下载任务的功能时必须读取本节。
- 第二梯队能力只能扩展队列入口，不能绕过第一梯队的并发、日志、去重、通知和错误详情。

### 2. Signatures

- `DownloadQueueManager.enqueue(videoInfo: VideoInfo, force: Boolean = false, filenameContext: DownloadFilenameTemplate.Context = DownloadFilenameTemplate.Context()): EnqueueResult`
- `DownloadFilenameTemplate.Context(playlistIndex: Int? = null, playlistTitle: String? = null)`
- `ProgressViewModel.downloadPlaylistItems(items: List<PlaylistExtractor.PlaylistDownloadItem>)`
- `CookieUtils.addCookiesToRequest(url: String, request: YoutubeDLRequest, additionalUrl: String? = null): File`
- `CookieUtils.deleteTemporaryCookieFile(cookieFile: File?)`

### 3. Contracts

- 播放列表批量入队必须逐项调用 `DownloadQueueManager.enqueue()`，并汇总 `Accepted/Duplicate/Rejected`，不得直接启动 Worker。
- 文件名模板在 `DownloadQueueManager` 入队前应用，生成新的 `VideoInfo` 副本；检测列表中的原始 `VideoInfo` 不应被原地修改。
- 播放列表任务必须传入 `playlistIndex` 和 `playlistTitle`，让 `%(playlist_index)s` / `%(playlist_title)s` 可用。
- Cookie profile 匹配后只能生成 app cache 下的临时 cookie 文件；调用方必须用 `CookieUtils.deleteTemporaryCookieFile()` 清理，不能直接 `File.delete()`。
- `--cookies-from-browser` 这类浏览器目录路径不归调用方所有，绝不能被下载或解析流程删除。
- yt-dlp 更新必须由用户从设置页显式触发；应用启动只允许初始化 yt-dlp/FFmpeg，不允许静默联网更新。

### 4. Validation & Error Matrix

- 播放列表解析失败 -> UI 显示失败原因，不创建下载任务。
- 批量入队遇到重复 -> 汇总 duplicate 数量，不强制覆盖。
- 批量入队遇到本地文件名重复 -> 汇总 rejected 数量，不绕过去重。
- 文件名模板为空 -> 下载时回退 `DownloadFilenameTemplate.DEFAULT_TEMPLATE`。
- Cookie profile 不匹配 -> 回退现有 WebView cookie 读取路径。
- Cookie 临时文件不在 app cache 或不是普通文件 -> `deleteTemporaryCookieFile()` 必须 no-op。

### 5. Good / Base / Bad Cases

- Good: 播放列表解析出 20 条，用户选择 5 条，最终 5 次 `enqueue()` 进入队列，Snackbar 显示成功/重复/拒绝汇总。
- Base: 用户设置模板为空，设置页允许继续编辑；真正下载时使用默认模板。
- Bad: 新增“批量下载”按钮后直接调用 `YoutubeDlDownloader.startDownload()`，导致并发限制和去重失效。
- Bad: 调用 `cookieFile.delete()` 清理由 `addCookiesToRequest()` 返回的文件，误删 WebView cookie 目录或和并发下载抢同一个固定临时文件。

### 6. Tests Required

- 模板测试：核心变量、播放列表变量、非法文件名字符清理。
- 搜索测试：Google engine 映射到 `https://www.google.com/search?q=%s`，默认搜索仍为 Bing。
- Cookie 测试：Netscape domain 解析、子域匹配、非子域不匹配。
- 构建验证：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true clean testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。
- 手动验证：播放列表多选入队、Cookie profile 导入后认证下载、yt-dlp 手动更新状态展示。

### 7. Wrong vs Correct

#### Wrong

```kotlin
// 直接启动下载，绕过队列、去重、日志和并发限制。
items.forEach { YoutubeDlDownloader.startDownload(context, it.videoInfo) }
```

#### Correct

```kotlin
// 批量能力仍只是在统一队列入口上循环。
items.forEach { item ->
    downloadQueueManager.enqueue(
        item.videoInfo,
        filenameContext = DownloadFilenameTemplate.Context(
            playlistIndex = item.playlistIndex,
            playlistTitle = item.playlistTitle
        )
    )
}
```

#### Wrong

```kotlin
// addCookiesToRequest 可能返回浏览器目录，不归当前流程所有。
CookieUtils.addCookiesToRequest(url, request).delete()
```

#### Correct

```kotlin
val cookieFile = CookieUtils.addCookiesToRequest(url, request)
try {
    YoutubeDL.getInstance().execute(request)
} finally {
    CookieUtils.deleteTemporaryCookieFile(cookieFile)
}
```

## Scenario: Crash-safe finalization, live capture, and media publication

### 1. Scope / Trigger

- Trigger: 修改 yt-dlp 暂停/取消/恢复、Worker 终态、HLS/MPD live、分片落盘、代理客户端或 Android 10+ 最终媒体发布时必须读取本节。
- 这些路径跨 WorkManager、Room、下载进程、临时文件、MediaStore 和通知，任何一层的“看起来成功”都不能代替最终提交。

### 2. Signatures

- `ProgressInfo.stopReason: YoutubeDlStopReason`
- `ProgressInfo.executionToken: String`
- `ProgressRepository.claimYtDlpFinalization(taskId, token, source, target): Int`
- `ProgressRepository.commitYtDlpFinalization(taskId, token, status, completedAt, error, infoLine): Int`
- `YoutubeDlFinalizationCoordinator.claimAndFinalize(...): Result`
- `FileUtil.uniqueMediaTarget(context, requested: File): File`
- `FileUtil.moveMedia(context, source: Uri, target: Uri): Boolean`
- `FileUtil.resolveMediaUri(context, target: File): Uri?`
- `DownloaderUtils.publishHlsCaptureSnapshot(...)`
- `MpdLiveCaptureIndex.loadOrMigrate(): Snapshot`

### 3. Contracts

- yt-dlp 控制意图和执行代次必须持久化；控制 Worker 与下载 Worker 不能依赖进程内全局布尔值。
- 旧 execution token 的进度和终态必须被 DAO 条件更新拒绝；FINALIZING 只能由一次 claim/commit CAS 提交。
- PAUSE/CANCEL 只由对应持久化意图产生；无控制标志的 `CancellationException` 原样向上抛，不能伪装成用户暂停或 ERROR。
- 分片先写同目录 `.part`，完整关闭后再发布；PAUSE/CANCEL 清除半分片，STOP_AND_SAVE 只允许当前分片安全收尾。
- HLS 双流只通过单一代次 manifest 提交；MPD 只按持久化连续 sequence index 合并。未提交孤儿、mtime 和 hash 文件名字典序不得决定合并顺序。
- Android 10+ 公共目标只发布到 `Download/SurfSave/`；MediaStore 使用 `IS_PENDING=1 -> 写入/验证 -> IS_PENDING=0`，最终 content URI 可读、长度正确且源明确消失后才允许 SUCCESS。
- Android 16 及部分 MediaProvider 对刚写入的行可能暂时报告 `SIZE=0`；`SIZE` 仅用于诊断。真实长度统一通过 `ContentLengthResolver` 的 `ParcelFileDescriptor.statSize -> AssetFileDescriptor.length -> 输入流计数` 确认，且媒体容器校验必须在删除临时源之前完成。
- yt-dlp 发布失败后必须保留 `finalizationSource/finalizationTarget/executionToken`。用户重试时，临时源仍存在则以原 token 原子切回 `FINALIZING` 并仅重新发布；若源已删除但目标 MediaStore URI 存在，仍切回 `FINALIZING`，由后台 Publisher 校验目标并提交成功。UI 线程不得为重试扫描整个媒体；若上一次已明确报目标校验失败，则重新排队下载，避免无效/空目标无限发布重试。
- 代理客户端在一个下载任务内固定，缓存键覆盖类型、host、port、用户名和密码；直连显式 `NO_PROXY`，带凭证 SOCKS5 在没有真实支持时明确拒绝。

### 4. Validation & Error Matrix

- CAS 更新 `0` 行 -> 当前 Worker 不是所有者，不执行终态副作用。
- CAS 更新 `>1` 行 -> 数据一致性错误，立即失败。
- Range 被忽略、分片短读、key/init 缺失 -> ERROR，不发布或合并半成品。
- HLS/MPD manifest 主文件缺失但存在非空 `.bak` -> 先恢复上次已提交 manifest，再读取；不得迁移未提交孤儿。
- `moveMedia=false`、最终 URI 缺失、Validator 失败、源仍存在或存在性 Unknown -> ERROR，不得回看 `target.exists()` 翻转为成功。
- `MediaStore.SIZE=0` 但描述符或输入流真实长度等于复制字节数 -> 继续发布；描述符和内容都为空或长度不符 -> ERROR，清理 pending 目标并保留源。
- `ERROR/ENOSPC + finalizationSource` 存在 -> 重试发布；源不存在但已发布目标校验通过 -> 恢复提交；源不存在且目标缺失或校验失败 -> 重新下载，不得无限停留在 ERROR。
- STOP_AND_SAVE 无活动 Worker -> 仅 live 任务按 HLS/MPD 类型进入对应本地 merge-only；不得重新抓远端滑动窗口。

### 5. Good / Base / Bad Cases

- Good: MPD 两个同毫秒完成的分片仍按 sequence 0/1 合并；崩溃遗留的未索引文件不参与。
- Good: MediaStore 发布成功后，最终 content URI 通过容器验证且临时源已删除，CAS 唯一提交 SUCCESS。
- Base: 用户 STOP_AND_SAVE，当前分片完成后从已提交 capture manifest 合并已有内容。
- Bad: 在 `finally` 无条件合并；PAUSE、CANCEL 或网络失败因此变成部分 SUCCESS。
- Bad: 只要目标文件出现就把移动失败改判成功，导致源和目标同时存在时丢失错误。

### 6. Tests Required

- DAO/Coordinator：并发 token、PAUSE/CANCEL 互不污染、claim/commit exactly-once、进程恢复 FINALIZING。
- HLS：多 key、显式/默认 IV、非零 media sequence、双流同代 manifest、backup 恢复、失败不推进提交点。
- MPD：显式 sequence、同 mtime、空索引先提交、孤儿忽略、乱序 JSON 归一、backup 恢复。
- 发布：MediaStore pending 清理、最终 URI 验证、源删除失败、目标/源双存在、Unknown 不算成功。
- 发布延迟：`SIZE=0 + descriptor 正确` 成功，`SIZE=0 + descriptor/stream 为空` 失败并保留源；诊断字段区分 `copiedBytes/mediaStoreReportedSize/descriptorSize`。
- 重试：发布失败且源存在只调用发布；源缺失但目标有效时只校验目标并恢复提交；目标缺失/无效时走正常重新下载；并发重试只有一次 CAS 获胜。
- 长度解析器：直接测试 `statSize` 优先、描述符均为 0 时输入流计数，以及 `MediaStore.SIZE` 查询抛异常后仍使用描述符；不能只依赖 `FileUtil` 集成测试间接覆盖。
- 失败任务入口：列表弹窗与通知栏都显示“重试”，暂停任务才显示“继续/恢复”；两者可以复用同一队列入口，但必须有队列级测试分别断言“只恢复发布”和“重新排队下载”。
- 构建：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。

### 7. Wrong vs Correct

#### Wrong

```kotlin
if (target.exists()) saveState(VideoTaskState.SUCCESS)
```

#### Correct

```kotlin
val moved = fileUtil.moveMedia(context, sourceUri, targetUri)
val finalUri = moved.takeIf { it }?.let { fileUtil.resolveMediaUri(context, target) }
val error = finalUri?.let { DownloadedMediaValidator.validate(context, it) }
if (moved && finalUri != null && error == null && !source.exists()) {
    commitYtDlpFinalization(/* token-scoped SUCCESS */)
} else {
    commitYtDlpFinalization(/* token-scoped ERROR */)
}
```

#### Wrong

```kotlin
if (mediaStoreSize != copiedBytes) throw IOException("Copy failed")
```

#### Correct

```kotlin
val actualLength = ContentLengthResolver.resolve(context, targetUri).length
if (actualLength != copiedBytes) throw IOException("Published length mismatch")
// MediaStore.SIZE remains diagnostic metadata only.
```

## Scenario: Browser media handoff and selected download source

### 1. Scope / Trigger

- Trigger: 修改 WebView 直链下载、普通附件下载、媒体响应复核、弹窗媒体接管、`VideoInfo.originalUrl`、格式选择或 SuperX 入队数据时必须读取本节。
- 目标是让浏览器下载请求拥有明确最终消费者：媒体进入 SurfSave 队列/格式选择，普通附件进入 Android `DownloadManager`；两者不能依赖页面候选生命周期。

### 2. Signatures

- `BrowserMediaClassifier.classify(url, contentType, manifestHint, contentDisposition): ContentType`
- `BrowserContentDisposition.fileName(headerValue): String?`
- `BrowserDownloadRequest.mediaType(): ContentType`
- `BrowserDownloadCoordinator.plan(request: BrowserDownloadRequest): BrowserDownloadPlan`
- `BrowserDownloadCoordinator.executeConfirmed(plan, fallbackTitle, submitMedia): Result<BrowserConfirmedDownload>`
- `VideoDetectionTabViewModel.handleBrowserDownloadRequest(request: BrowserDownloadRequest)`
- `BrowserMediaPopupPolicy.shouldCapture(url, hasUserGesture): Boolean`
- `BrowserPopupRedirectGuard.arm(sourcePageUrl)` / `shouldBlock(targetUrl, hasUserGesture, isMainFrame)`
- `SuperXDownloadSourceResolver.resolve(videoInfo: VideoInfo): SuperXDownloadSource`

### 3. Contracts

- 响应复核统一综合 URL、`Content-Type` 和 `Content-Disposition`；明确 `.mp4` URL 即使返回 `application/octet-stream` 仍是视频，裸下载端点只有在附件文件名是已知媒体扩展名时才进入媒体流程。`download.php` 等未知 URL 扩展不得压住明确的附件媒体文件名，但 TS/M4S 分片仍不得提升为独立视频。
- WebView `DownloadListener` 的返回值不能把请求交还 WebView；一旦注册监听器，所有 HTTP(S) 下载回调都必须路由到真实消费者。普通音视频显示确认后通过 `ProgressViewModel.downloadVideo()` 进入 `DownloadQueueManager`，不得只生成页面候选。
- HLS/DASH 继续进入现有清单解析和格式选择；APK、ZIP、PDF 等普通附件显示确认后交给 Android `DownloadManager` 并保存到公共 Downloads，不进入视频库或媒体队列。
- 浏览器下载请求只保留当前 User-Agent、页面 Referer 和目标 URL Cookie；直链媒体、HLS/DASH 清单和系统附件共用这一白名单。不得转发 Authorization 或页面探针收集的任意 header。
- 附件文件名必须用自有、可测试的 Content-Disposition 解析器处理 `filename*`、charset/language 段、百分号编码和 quoted parameter，不得把平台 `URLUtil.guessFileName()` 当成唯一事实源。安全文件名去除路径/控制字符；若 URL/附件名均无扩展而 MIME 明确是 APK/PDF/ZIP 等已知类型，应补齐对应扩展，`application/octet-stream` 不猜测。
- Android 9（API 28）及以下向公共 Downloads 写入前必须在用户确认下载时检查 `WRITE_EXTERNAL_STORAGE`；需授权时保留当次计划，授权成功后恢复同一请求，拒绝时明确提示。API 29+ 不得多要旧写入权限。
- 用户手势打开的明确 HTTP(S) 媒体弹窗可在主 WebView 导航前交给同一下载协调器；脚本弹窗、普通网页、无扩展端点和媒体分片保持原导航/拦截流程。
- 捕获用户手势媒体 Popup 后，可启动一次性短时保护：只阻止无手势、主 Frame、跨 host 的紧随跳转；同 host、子资源、用户手势、超时后的导航一律放行。不得写站点 host。
- `VideoInfo.originalUrl` 表示来源网页或刷新上下文，不能作为 SuperX 实际媒体源；SuperX 只能读取当前所选格式的非空 `manifestUrl`，其次读取 `url`，并使用同一格式的 headers、formatId 和 codec。
- HLS 主清单继续由 Worker 按所选 `formatId` 找回清晰度；不得用 `originalUrl` 回退掩盖格式缺失。

### 4. Validation & Error Matrix

- `DownloadListener` 收到无媒体证据的 HTTP(S) 请求 -> 作为普通附件确认并交给 Android `DownloadManager`，不能静默丢失。
- 非 HTTP(S) 下载请求 -> 拒绝，不交给媒体队列或系统下载。
- Popup 没有用户手势或目标不是明确媒体 URL -> 不接管。
- Popup 回调拒绝或抛异常 -> 保持旧的允许导航，不吞链接。
- Android `DownloadManager.enqueue()` 抛异常 -> 显示“无法开始下载”并记录不含完整 URL/Cookie 的错误；不能伪造成功通知。
- API 24–28 旧存储权限未授予 -> 不先行 `enqueue()`；请求授权并保留同一计划，授权回调时 Fragment view 不可用则等待 view 重建再恢复。
- 所选格式不存在，或 `manifestUrl`/`url` 都为空 -> SuperX 明确失败，不请求 HTML 来源页。
- 401/403 复核重试 -> 使用相同媒体分类合同，不能退回仅判断 `video/*` / `audio/*`。

### 5. Good / Base / Bad Cases

- Good: 用户确认 `.mp4 + application/octet-stream` 后直接生成持久 `VideoInfo` 并调用统一队列；页面 500ms 后跳转也不影响任务。
- Good: APK/ZIP/PDF 保留安全文件名、MIME、User-Agent、Referer、Cookie 后由系统下载，且永不出现在视频库。
- Good: 来源页是 HTML、所选格式是 HLS 主清单时，Worker 收到主清单 URL 和对应 formatId。
- Base: 无扩展下载端点先走 WebView，服务器返回媒体型 `Content-Disposition` 后由 DownloadListener 接管。
- Bad: DownloadListener 对非媒体返回 `false` 后假设 WebView 会继续下载；只创建页面候选；把 ZIP 当媒体；SuperX 用 `originalUrl` 请求来源 HTML；为单个站点添加 host 分支。

### 6. Tests Required

- 分类器：通用二进制 MIME + 明确 MP4、`filename*` 媒体附件、`download.php + filename=.mp4`、非媒体附件、TS/M4S 分片。
- Popup：用户手势下 MP4/HLS/音频接管；无手势、普通页、无扩展端点、分片不接管。
- 协调器：MP4 确认必须调用媒体 sink 并生成可入队 `VideoInfo`；APK 必须调用真实 `DownloadManager.enqueue()`；ZIP/PDF 不得调用媒体 sink。
- 系统附件：断言 RFC 5987/quoted 文件名、路径/控制字符清理、明确 MIME 补扩展、公共 Downloads 目标及 UA/Referer/Cookie；Authorization 和换行 header 必须被拒绝。
- 版本权限：API 28 未授权时需请求旧写入权限，已授权或 API 29+ 不再请求。
- 跳转保护：只拦截有效期内一次无手势跨 host 主导航，同 host/用户手势/子资源/超时均放行。
- SuperX：`manifestUrl` 优先、缺失时使用格式 `url`、两者都缺失明确失败且不回退 `originalUrl`。
- 完整门禁：`testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`，并核验 ARM64 Diagnostic 的 applicationId、版本码、V2 签名和证书。

### 7. Wrong vs Correct

#### Wrong

```kotlin
// DownloadListener 没有“交还 WebView”语义，这个请求会无人处理。
if (!request.isSupportedMedia()) return false
val sourceUrl = videoInfo.originalUrl
```

#### Correct

```kotlin
val plan = browserDownloadCoordinator.plan(request)
// DirectMedia -> DownloadQueueManager；SystemFile -> Android DownloadManager。
browserDownloadCoordinator.executeConfirmed(plan, fallbackTitle, submitMedia)

val source = SuperXDownloadSourceResolver.resolve(videoInfo)
```
